package com.example.llama.agent.vision.nightmode

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Orchestrátor pro Night Mode pluginy (Low-Light Image Enhancement).
 * 
 * Běží na vlastním vyhrazeném vlákně (Singleton SingleThreadExecutor),
 * aby nedocházelo k blokování hlavního enginu detekce a inference.
 * Podporuje Lazy inicializaci a backpressure (zahazování framů).
 */
class NightModePluginManager {
    companion object {
        private const val TAG = "NightModeManager"
    }

    private val registry = mutableMapOf<String, NightModePlugin>()
    private val _activePlugin = AtomicReference<NightModePlugin?>(null)
    
    // Vyhrazené single-core vlákno pro asynchronní matematiku/TFLite
    private val nightModeDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    
    // Backpressure lock
    private val isProcessing = AtomicBoolean(false)

    fun registerPlugin(plugin: NightModePlugin) {
        registry[plugin.pluginId] = plugin
        Log.i(TAG, "🔌 NightMode Plugin registrován: ${plugin.pluginId} (${plugin.displayName})")
    }

    fun getAvailablePlugins(): List<NightModePlugin> {
        return registry.values.toList()
    }

    private val pluginScope = CoroutineScope(nightModeDispatcher + SupervisorJob())

    /**
     * Hot-swap pluginu a jeho Lazy inicializace.
     */
    fun activatePlugin(pluginId: String?) {
        val currentRef = _activePlugin.get()
        val targetRef = if (pluginId == null) null else registry[pluginId]
        if (currentRef === targetRef) return // Fast-path: už je aktivní správný (nebo null)

        pluginScope.launch {
            val current = _activePlugin.get()
            if (pluginId == null) {
                current?.release()
                _activePlugin.set(null)
                Log.i(TAG, "🌙 Night Mode Vypnut")
                return@launch
            }

            val target = registry[pluginId]
            if (target != null && target != current) {
                current?.release() // Vyčištění starého modelu z RAM
                // Lazy Load nového modelu se stane automaticky v process()
                _activePlugin.set(target)
                Log.i(TAG, "🌙 Nastaven aktivní Night Mode plugin: ${target.displayName}")
            }
        }
    }

    /**
     * Znovu inicializuje aktuálně aktivní plugin (např. při změně cesty k modelu).
     */
    fun reloadActivePlugin() {
        pluginScope.launch {
            val current = _activePlugin.get()
            if (current != null) {
                Log.i(TAG, "🔄 Ruční vynucené přenačtení pluginu (čekání na uvolnění paměti...): ${current.displayName}")
                current.release()
                // V plugin.process() se automaticky zavolá init() při dalším framu.
            }
        }
    }

    /**
     * Asynchronní zpracování. Vrací prosvětlený Bitmap nebo původní, pokud je engine vypnutý/přetížený.
     */
    suspend fun enhanceAsync(input: Bitmap): Bitmap {
        if (_activePlugin.get() == null) return input

        // Backpressure: Pokud algoritmus nestíhá (např. pomalý TFLite), 
        // propustíme aktuální frame nezměněný, abychom nezpomalili celý pipeline.
        if (!isProcessing.compareAndSet(false, true)) {
            Log.v(TAG, "⚠️ Night Mode nestíhá (Frame Skipped - propouštím RAW)")
            return input
        }

        return try {
            withContext(nightModeDispatcher) {
                val plugin = _activePlugin.get() ?: return@withContext input
                plugin.process(input)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba v Night Mode pluginu", e)
            input
        } finally {
            isProcessing.set(false)
        }
    }

    fun releaseAll() {
        pluginScope.launch {
            registry.values.forEach { it.release() }
            registry.clear()
            _activePlugin.set(null)
        }
    }
}
