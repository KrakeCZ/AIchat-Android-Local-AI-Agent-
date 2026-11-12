package com.example.llama.agent.vision.plugins

import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import java.util.concurrent.atomic.AtomicReference

/**
 * Sjednocený orchestrátor pro Vision Pluginy. 
 * Řídí Zero-Allocation průtok dat a bezpečný hot-swap filtrů za běhu.
 */
class VisionPluginManager {
    companion object {
        private const val TAG = "VisionPluginManager"
        const val RAW_BYPASS_ID = "core_raw_bypass"
    }

    private val registry = mutableMapOf<String, VisionFilterPlugin>()
    
    // AtomicReference pro bezpečné přepínání z UI vlákna bez zamykání (lock-free) vizuálního vlákna
    private val _activePlugin = AtomicReference<VisionFilterPlugin?>(null)
    
    // Nativní Base Tracker, který zajišťuje kinematiku pro analytické pluginy
    private val baseTracker = com.example.llama.agent.vision.plugins.impl.DkfEdgeTrackerPlugin()
    private val analyticsPluginIds = setOf(
        "tracker_ballistic_predict",
        "tracker_swarm_analyzer",
        "tracker_threat_anomaly",
        "tracker_spatial_heatmap",
        "tracker_ttc_eval",
        "tracker_fov_exit",
        "tracker_new_object_alerter",
        "tracker_trajectory_classifier",
        "tracker_motion_saliency",
        "tracker_blob_shape",
        "tracker_physics_violation"
    )

    /** Čtení aktuálního pluginId pro rychlou synchronizaci (zero-lock, thread-safe) */
    val activePluginId: String?
        get() = _activePlugin.get()?.pluginId

    fun registerPlugin(plugin: VisionFilterPlugin) {
        registry[plugin.pluginId] = plugin
        Log.i(TAG, "🔌 Plugin registrován: ${plugin.pluginId} (${plugin.displayName})")
    }

    fun getAvailablePlugins(): List<VisionFilterPlugin> {
        return registry.values.toList()
    }

    /**
     * Hot-swap pluginu. Pokud se pošle RAW_BYPASS_ID nebo null, veškerá postprodukce se vypne.
     */
    fun activatePlugin(pluginId: String?) {
        val current = _activePlugin.get()
        if (pluginId == null || pluginId == RAW_BYPASS_ID) {
            current?.reset()
            _activePlugin.set(null)
            Log.i(TAG, "⚙️ Aktivován RAW Bypass mód (Čistý výstup ze senzoru/ML)")
            return
        }

        val target = registry[pluginId]
        if (target != null && target != current) {
            current?.reset() // Vyčištění starého stavu
            _activePlugin.set(target)
            Log.i(TAG, "⚙️ Aktivován plugin: ${target.displayName}")
        }
    }

    /**
     * Průtokový bod pro PerceptionEngine.
     */
    fun executePipeline(
        rawDetections: List<Detection>,
        timestampMs: Long
    ): List<Detection> {
        val plugin = _activePlugin.get()
        return try {
            if (plugin != null) {
                if (analyticsPluginIds.contains(plugin.pluginId)) {
                    // Povýšení Trackeru na Pipeline: Tracker -> Analytics
                    val tracked = baseTracker.process(rawDetections, timestampMs)
                    plugin.process(tracked, timestampMs)
                } else {
                    plugin.process(rawDetections, timestampMs)
                }
            } else {
                rawDetections
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatální chyba v pluginu ${plugin?.pluginId}, fallback na RAW", e)
            rawDetections // Zero Silent Failures: Pokud plugin spadne, vrátíme surová data
        }
    }

    fun resetActive() {
        _activePlugin.get()?.reset()
    }

    fun releaseAll() {
        registry.values.forEach { it.release() }
        registry.clear()
        _activePlugin.set(null)
    }

    private var lastEstimatorParamsHash: Int? = null

    /**
     * Zkontroluje a aktualizuje konfiguraci estimatorů (Kalman filtru). 
     * Transakční Reset (Hard Flush) - Option A: Pokud dojde ke změně, provede reset trackerů
     * a reinicializaci s novými parametry. Tím je zaručeno, že filtry nebudou divergovat.
     */
    fun updateEstimatorConfig(params: com.example.llama.agent.vision.tracking.math.EstimatorParameters) {
        val currentHash = params.hashCode()
        if (lastEstimatorParamsHash == currentHash) return
        
        lastEstimatorParamsHash = currentHash
        Log.i(TAG, "⚙️ Konfigurace filtru změněna. Aplikuji Hard Flush (Option A).")

        val current = _activePlugin.get()
        var changed = false
        
        val dkfFactory: () -> com.example.llama.agent.vision.tracking.math.MotionEstimator = { 
            com.example.llama.agent.vision.tracking.math.DynamicKalmanFilter2D(params) 
        }
        baseTracker.estimatorFactory = dkfFactory
        
        if (current is com.example.llama.agent.vision.plugins.impl.DkfEdgeTrackerPlugin) {
            current.estimatorFactory = dkfFactory
            changed = true
        } else if (current is com.example.llama.agent.vision.plugins.impl.EdgeTrackerPlugin) {
            current.estimatorFactory = { com.example.llama.agent.vision.tracking.OD_KalmanFilter2D(params) }
            changed = true
        } else if (current is com.example.llama.agent.vision.plugins.impl.TelescopeKalmanTrackerPlugin) {
            current.estimatorFactory = dkfFactory
            changed = true
        }

        if (changed) {
            resetActive()
        }
        baseTracker.reset()
    }
}
