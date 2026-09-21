package com.example.llama.agent.vision.lupa

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class LupaPluginManager {

    companion object {
        private const val TAG = "LupaPluginManager"
        const val BYPASS_ID = "lupa_bypass"
        private const val POOL_CAPACITY = 30
    }

    private val registry = mutableMapOf<String, LupaPlugin>()
    private val _activePlugin = AtomicReference<LupaPlugin?>(null)

    private val isProcessing = AtomicBoolean(false)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    var latestResult: android.graphics.Bitmap? = null

    // 2-generační output buffer: bezpečná recyklace výstupní bitmapy po 2 cyklech
    // (zaručuje, že Compose stihne vykreslit n-1 výsledek před smazáním)
    @Volatile private var recycleCandidate: android.graphics.Bitmap? = null

    // --- Mezipaměť pro multi-frame pluginy a T-Junction ---
    val bitmapPool = LupaBitmapPool(capacity = POOL_CAPACITY)
    private var frameAccumulator: LupaFrameAccumulator? = null

    @Volatile private var currentScaleFactor: Float = 2.0f
    
    // Zapínání/vypínání mezipaměti uživatelem
    var isCacheEnabled: Boolean = true
        set(value) {
            field = value
            reactivateCurrentPlugin()
        }

    // --- Konfigurace mezipaměti z UI ---
    var cacheMinFrames: Int? = null
        set(value) { field = value; reactivateCurrentPlugin() }
    
    var cacheMaxCapacity: Int = 30
        set(value) { field = value; reactivateCurrentPlugin() }
    
    var cacheTimeoutMs: Long = 250L
        set(value) { field = value; reactivateCurrentPlugin() }
    
    var fixedResolution: Int? = null
        set(value) { field = value; reactivateCurrentPlugin() }

    private fun reactivateCurrentPlugin() {
        val current = _activePlugin.get()
        if (current != null) {
            activatePlugin(current.pluginId)
        }
    }

    /**
     * Callback na Main threadu — nastavit jednou při inicializaci.
     * Analogie k "onFrameAcquired" callbacku v GenICam standardu.
     */
    var onResultReady: ((Bitmap?) -> Unit)? = null

    val activePluginId: String?
        get() = _activePlugin.get()?.pluginId

    fun registerPlugin(plugin: LupaPlugin) {
        registry[plugin.pluginId] = plugin
        Log.i(TAG, "Lupa plugin registrován: ${plugin.pluginId}")
    }

    fun getAvailablePlugins(): List<LupaPlugin> = registry.values.toList()

    /**
     * Aktivuje plugin. Pro multi-frame pluginy automaticky vytvoří
     * LupaFrameAccumulator s parametry odpovídajícími danému pluginu.
     */
    fun activatePlugin(pluginId: String?) {
        val current = _activePlugin.get()

        if (pluginId == null || pluginId == BYPASS_ID) {
            current?.reset()
            destroyAccumulator()
            bitmapPool.destroy() // Líné alokování: vyčistíme pool, když není potřeba
            _activePlugin.set(null)
            Log.i(TAG, "Lupa bypass aktivní")
            scope.launch(Dispatchers.Main) { onResultReady?.invoke(null) }
            return
        }

        val target = registry[pluginId] ?: run {
            Log.w(TAG, "Plugin $pluginId nenalezen")
            return
        }
        
        current?.reset()
        destroyAccumulator()
        _activePlugin.set(target)

        // Cache je nyní dostupná pro všechny pluginy, nejen multi-frame.
        if (isCacheEnabled) {
            bitmapPool.capacity = cacheMaxCapacity
            frameAccumulator = LupaFrameAccumulator(
                pool         = bitmapPool,
                minFrames    = cacheMinFrames ?: target.minFramesRequired,
                maxFrames    = cacheMaxCapacity,
                maxWaitMs    = cacheTimeoutMs,
                fixedResolution = fixedResolution,
                scope        = scope,
                onFramesReady = { frames ->
                    // Voláno na Dispatchers.Default
                    deliverMultiFrameResult(target, frames)
                }
            )
            Log.i(TAG, "Lupa aktivní (mezipaměť ON): ${target.displayName} " +
                       "[minFrames=${cacheMinFrames ?: target.minFramesRequired}, timeout=${cacheTimeoutMs}ms, fixedRes=$fixedResolution]")
        } else {
            val modeStr = if (target.requiresMultipleFrames) "real-time bypass" else "single-frame"
            Log.i(TAG, "Lupa aktivní ($modeStr): ${target.displayName}")
        }
    }

    /**
     * Odešle frame do aktivního pluginu.
     * Single-frame: okamžité zpracování s backpressure.
     * Multi-frame:  přes LupaFrameAccumulator (ring buffer).
     *
     * Volat z camera executor vlákna. Žádný blokující call.
     */
    fun submitFrame(bitmap: Bitmap, cropRect: android.graphics.Rect?, scaleFactor: Float) {
        val plugin = _activePlugin.get() ?: return
        if (bitmap.isRecycled) return
        currentScaleFactor = scaleFactor

        // Odbavíme mimo Main thread hned od začátku
        scope.launch(Dispatchers.Default) {
            if (isCacheEnabled && frameAccumulator != null) {
                // Při zapnuté mezipaměti jdou snímky všech pluginů do accumulatoru
                frameAccumulator?.push(bitmap, cropRect)
            } else {
                // Single-frame nebo bypass mezipaměti: backpressure — zahazuj pokud ještě zpracováváme
                if (!isProcessing.compareAndSet(false, true)) return@launch

                // ZERO ALLOC: Input crop z bitmapPool místo nové alokace každý frame
                val cropW = cropRect?.width() ?: bitmap.width
                val cropH = cropRect?.height() ?: bitmap.height
                val frameCopy = bitmapPool.acquire(cropW, cropH)
                val canvas = android.graphics.Canvas(frameCopy)
                if (cropRect != null) {
                    canvas.drawBitmap(bitmap, -cropRect.left.toFloat(), -cropRect.top.toFloat(), null)
                } else {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                }

                try {
                    val result = if (plugin.requiresMultipleFrames) {
                        // Pokud je to multi-frame plugin ale cache je vypnutá,
                        // zpracujeme jej jako dávku s 1 snímkem.
                        plugin.processMultiFrame(listOf(frameCopy), currentScaleFactor)
                    } else {
                        plugin.process(frameCopy, currentScaleFactor)
                    }
                    // 2-gen output recyklace: toRecycle je n-2 generace staré (Compose ho už nekreslí)
                    val toRecycle = recycleCandidate
                    recycleCandidate = latestResult
                    latestResult = result
                    withContext(Dispatchers.Main) { onResultReady?.invoke(result) }
                    toRecycle?.let { if (!it.isRecycled) it.recycle() }
                } catch (e: Exception) {
                    Log.e(TAG, "Lupa plugin ${plugin.pluginId} selhalo: ${e.message}")
                } finally {
                    // Input crop vrácen do poolu (nulová alokace po warmup fázi)
                    bitmapPool.release(frameCopy)
                    isProcessing.set(false)
                }
            }
        }
    }

    /**
     * Zpracuje výsledek multi-frame pluginu a doručí ho na Main thread.
     * Voláno z Dispatchers.Default (z LupaFrameAccumulator.dispatch).
     */
    private fun deliverMultiFrameResult(plugin: LupaPlugin, frames: List<Bitmap>) {
        // Backpressure: drop the batch if the plugin is still busy with the previous one!
        if (!isProcessing.compareAndSet(false, true)) {
            Log.v(TAG, "Dropping multi-frame batch: previous processing not finished.")
            return
        }
        try {
            val result = plugin.processMultiFrame(frames, currentScaleFactor)
            // 2-gen output recyklace (stejný princip jako single-frame path)
            val toRecycle = recycleCandidate
            recycleCandidate = latestResult
            latestResult = result
            scope.launch(Dispatchers.Main) { onResultReady?.invoke(result) }
            toRecycle?.let { if (!it.isRecycled) it.recycle() }
        } catch (e: Exception) {
            Log.e(TAG, "Multi-frame plugin ${plugin.pluginId} selhalo: ${e.message}")
        } finally {
            isProcessing.set(false)
        }
        // Poznámka: frames recykluje accumulator.dispatch() v finally bloku po návratu
    }

    fun resetActive() {
        _activePlugin.get()?.reset()
        frameAccumulator?.reset()
        isProcessing.set(false)
    }

    fun releaseAll() {
        scope.cancel()
        registry.values.forEach { it.release() }
        registry.clear()
        _activePlugin.set(null)
        destroyAccumulator()
        bitmapPool.destroy()
        // Recyklace output bufferů (uvolní native heap po zničení Activity)
        recycleCandidate?.let { if (!it.isRecycled) it.recycle() }
        recycleCandidate = null
        latestResult?.let { if (!it.isRecycled) it.recycle() }
        latestResult = null
    }

    private fun destroyAccumulator() {
        frameAccumulator?.destroy()
        frameAccumulator = null
    }
}