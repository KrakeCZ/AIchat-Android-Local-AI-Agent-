package com.example.llama.agent.vision.lupa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Frame accumulation ring buffer pro multi-frame Lupa pluginy.
 *
 * Trigger strategie (shodná s průmyslovými embedded systémy):
 *   1. N-frame trigger  → zpracuj ihned po dosažení minFrames
 *   2. Timeout trigger  → zpracuj co máme po uplynutí maxWaitMs (fallback pro pomalé scény)
 */
class LupaFrameAccumulator(
    private val pool: LupaBitmapPool,
    val minFrames: Int,             // N-frame trigger: zpracuj po N framech
    val maxFrames: Int = 30,        // Kapacita ring bufferu
    val maxWaitMs: Long = 250L,     // Timeout trigger
    val fixedResolution: Int? = null, // Vynucené rozlišení ořezu
    private val scope: CoroutineScope,
    private val onFramesReady: (List<Bitmap>) -> Unit  // Voláno na Dispatchers.Default
) {

    companion object {
        private const val TAG = "LupaFrameAccumulator"
    }

    private val ringBuffer = ArrayDeque<Bitmap>(maxFrames)
    
    // Zásadní 2026 architektura: Dedikovaný Single-Thread Dispatcher místo synchronized locků!
    // Vyřeší 100% CPU contention tím, že operace probíhají striktně sériově bez zamykání.
    private val accumulatorDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    
    // Lock-free pipeline pro frames
    // Používáme CONFLATED, takže channel zahazuje nestíhající snímky. 
    // onUndeliveredElement zaručí, že zahozený snímek se bezpečně vrátí do poolu (Zero-Leak)!
    private val channel = Channel<Bitmap>(
        capacity = Channel.CONFLATED,
        onUndeliveredElement = { droppedSlot ->
            pool.release(listOf(droppedSlot))
        }
    )
    private var accumulationStartMs = 0L
    private var timeoutJob: Job? = null
    private var collectorJob: Job? = null

    init {
        startCollector()
    }

    private fun startCollector() {
        collectorJob = scope.launch(accumulatorDispatcher) {
            for (slot in channel) {
                if (!isActive) break
                processIncomingFrame(slot)
            }
        }
    }

    /**
     * Zkopíruje snímek synchronně (než ho kamera zničí/recykluje) a odešle do lock-free channelu.
     */
    fun push(bitmap: Bitmap, cropRect: android.graphics.Rect?) {
        if (bitmap.isRecycled) return
        
        val w = fixedResolution ?: (cropRect?.width() ?: bitmap.width)
        val h = fixedResolution ?: (cropRect?.height() ?: bitmap.height)

        // Kopie do pre-alokovaného pool slotu MUSÍ proběhnout synchronně, 
        // protože původní 'bitmap' bude hned po návratu z této funkce zničen kamerou!
        val slot = pool.acquire(w, h)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)

        if (cropRect != null) {
            val srcRect = android.graphics.Rect(cropRect.left, cropRect.top, cropRect.right, cropRect.bottom)
            val dstRect = android.graphics.Rect(0, 0, w, h)
            Canvas(slot).drawBitmap(bitmap, srcRect, dstRect, paint)
        } else {
            val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
            val dstRect = android.graphics.Rect(0, 0, w, h)
            Canvas(slot).drawBitmap(bitmap, srcRect, dstRect, paint)
        }

        // Odeslání bezpečné kopie do asynchronní fronty
        channel.trySend(slot)
    }

    /** Interní zpracování na vyhrazeném vlákně (bez zamčených úseků) */
    private fun processIncomingFrame(slot: Bitmap) {
        if (slot.isRecycled) return

        var framesToProcess: List<Bitmap>? = null

        // Ring buffer: evict oldest pokud je plný
        if (ringBuffer.size >= maxFrames) {
            pool.release(ringBuffer.removeFirst())
        }
        ringBuffer.addLast(slot)

        // Spustit timeout timer při prvním framu nové série
        if (ringBuffer.size == 1) {
            accumulationStartMs = System.currentTimeMillis()
            scheduleTimeout()
        }

        // N-frame trigger: máme dost → zpracuj okamžitě
        if (ringBuffer.size >= minFrames) {
            timeoutJob?.cancel()
            framesToProcess = drainBufferLocked()
        }

        framesToProcess?.let { dispatch(it) }
    }

    private fun scheduleTimeout() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch(accumulatorDispatcher) {
            delay(maxWaitMs)
            var framesToProcess: List<Bitmap>? = null
            if (ringBuffer.isNotEmpty()) {
                framesToProcess = drainBufferLocked()
            }
            framesToProcess?.let { dispatch(it) }
        }
    }

    /** Vyprázdní ring buffer a vrátí snapshot framů. */
    private fun drainBufferLocked(): List<Bitmap> {
        val frames = ringBuffer.toList()
        ringBuffer.clear()
        accumulationStartMs = 0L
        return frames
    }

    private fun dispatch(frames: List<Bitmap>) {
        if (frames.isEmpty()) return
        scope.launch(Dispatchers.Default) {
            try {
                // Log.v(TAG, "Dispatch: ${frames.size} framů (min=$minFrames)")
                onFramesReady(frames)
            } catch (e: Exception) {
                Log.e(TAG, "Chyba zpracování: ${e.message}")
            } finally {
                // Framy zpět do poolu — vždy, i při výjimce
                pool.release(frames)
            }
        }
    }

    /** Reset bufferu — volat při přepnutí pluginu nebo pauze kamery. */
    fun reset() {
        scope.launch(accumulatorDispatcher) {
            timeoutJob?.cancel()
            timeoutJob = null
            val toRelease = ringBuffer.toList()
            ringBuffer.clear()
            accumulationStartMs = 0L
            pool.release(toRelease)
        }
    }

    fun destroy() {
        // Zabráníme posílání nových snímků a uklidíme zahozené
        channel.cancel() 
        collectorJob?.cancel()
        
        // Zde nespouštíme reset() přes scope.launch(accumulatorDispatcher), 
        // protože vzápětí voláme accumulatorDispatcher.close(), což by launch zahodilo (memory leak).
        // Vzhledem k tomu, že už žádná další úloha nepřibude, vyčistíme to rovnou:
        timeoutJob?.cancel()
        timeoutJob = null
        val toRelease = ringBuffer.toList()
        ringBuffer.clear()
        pool.release(toRelease)
        
        accumulatorDispatcher.close()
    }
}
