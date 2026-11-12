package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.sqrt

/**
 * Detekce hrozeb a anomálního chování.
 * 
 * Sleduje derivaci rychlosti (tj. zrychlení/zpomalení a prudké změny směru).
 * Modifikuje pole `threatLevel` v Detection, které ovlivňuje
 * barvu vykreslení (červená = vysoké nebezpečí).
 */
class ThreatAnomalyPlugin(
    private val accelerationThreshold: Float = 0.05f // Změna rychlosti za frame znamenající hrozbu
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_threat_anomaly"
    override val displayName = "Threat Anomaly (Acceleration)"

    // Mapa historie rychlostí pro výpočet zrychlení
    // Key: Track ID, Value: [vx, vy, timestampMs]
    private val velocityHistory = mutableMapOf<Int, FloatArray>()

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        val currentIds = mutableSetOf<Int>()

        for (det in detections) {
            currentIds.add(det.id)
            
            var threatLevel = det.threatLevel
            val hist = velocityHistory[det.id]
            
            if (hist != null) {
                val dtMs = timestampMs - hist[2].toLong()
                if (dtMs > 0 && dtMs < 500) {
                    val prevVx = hist[0]
                    val prevVy = hist[1]
                    
                    val dvx = det.vx - prevVx
                    val dvy = det.vy - prevVy
                    
                    val accelerationNorm = sqrt(dvx * dvx + dvy * dvy)
                    
                    if (accelerationNorm > accelerationThreshold) {
                        // Prudká změna (akcelerace nebo úhyb) -> Hrozba roste k 1.0f
                        val currentNorm = sqrt(det.vx * det.vx + det.vy * det.vy)
                        threatLevel = (accelerationNorm / accelerationThreshold).coerceIn(0f, 1.0f)
                    }
                }
            }
            
            // Uložíme aktuální rychlost pro příště
            velocityHistory[det.id] = floatArrayOf(det.vx, det.vy, timestampMs.toFloat())
            
            if (threatLevel > 0f) {
                val newTags = det.customTags.toMutableList()
                newTags.add("[THREAT: ${(threatLevel * 100).toInt()}%]")
                result.add(det.copy(threatLevel = threatLevel, customTags = newTags))
            } else {
                result.add(det)
            }
        }
        
        // Úklid paměti (odstraníme stará ID, která už v detekcích nejsou)
        velocityHistory.keys.retainAll(currentIds)
        
        return result
    }

    override fun reset() {
        velocityHistory.clear()
    }

    override fun release() {
        velocityHistory.clear()
    }
}
