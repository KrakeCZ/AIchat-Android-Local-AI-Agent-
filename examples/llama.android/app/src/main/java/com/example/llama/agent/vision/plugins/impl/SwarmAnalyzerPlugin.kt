package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.sqrt

/**
 * Detekce roje (Swarm Analyzer).
 * 
 * Analyzuje vektory rychlosti všech detekovaných objektů. 
 * Pokud více objektů (např. Drony) sdílí podobný směr a rychlost letu,
 * jsou označeny tagem [SWARM].
 * 
 * Využívá kosinovou podobnost vektorů nebo rozdíl úhlů.
 */
class SwarmAnalyzerPlugin(
    private val angleThresholdDegrees: Float = 15f,
    private val minVelocityNorm: Float = 0.005f // Ignoruj stojící objekty
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_swarm_analyzer"
    override val displayName = "Swarm Analyzer (Coordination)"

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        if (detections.size < 2) return detections

        val result = mutableListOf<Detection>()
        
        // 1. Spočítáme úhly rychlosti a normy pro objekty s nenulovou rychlostí
        data class KinematicData(val det: Detection, val angle: Float, val norm: Float, var swarmId: Int = -1)
        
        val kinematics = detections.map { det ->
            val norm = sqrt(det.vx * det.vx + det.vy * det.vy)
            val angle = if (norm > minVelocityNorm) atan2(det.vy.toDouble(), det.vx.toDouble()).toFloat() else Float.NaN
            KinematicData(det, angle, norm)
        }
        
        var currentSwarmId = 1
        
        // 2. Jednoduché shlukování (Clustering) podle úhlu
        for (i in kinematics.indices) {
            val a = kinematics[i]
            if (a.angle.isNaN() || a.swarmId != -1) continue
            
            val cluster = mutableListOf(a)
            a.swarmId = currentSwarmId
            
            for (j in i + 1 until kinematics.size) {
                val b = kinematics[j]
                if (b.angle.isNaN() || b.swarmId != -1) continue
                
                // Rozdíl úhlů s ošetřením přechodu 360 -> 0
                var diff = abs(a.angle - b.angle) * (180f / PI.toFloat())
                if (diff > 180f) diff = 360f - diff
                
                if (diff <= angleThresholdDegrees) {
                    b.swarmId = currentSwarmId
                    cluster.add(b)
                }
            }
            
            if (cluster.size > 1) {
                currentSwarmId++
            } else {
                // False alarm, není to roj
                a.swarmId = -1
            }
        }
        
        // 3. Modifikace původních detekcí o Swarm tagy
        for (k in kinematics) {
            if (k.swarmId != -1) {
                val newTags = k.det.customTags.toMutableList()
                newTags.add("[SWARM_G${k.swarmId}]")
                result.add(k.det.copy(customTags = newTags))
            } else {
                result.add(k.det)
            }
        }
        
        return result
    }

    override fun reset() {
    }

    override fun release() {
    }
}
