package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Trajectory Classifier — klasifikace pohybu ze kinematiky.
 *
 * Klasifikuje každý track jako:
 *   🐦 BIRD    — střední rychlost, erratická směrová změna
 *   ✈️ PLANE   — vysoká/konstantní rychlost, lineární trajektorie
 *   🔴 ANOMALY — fyzikálně nekonzistentní: náhlé zrychlení (jerk/jounce), fázově nelogické pohyby
 *   ?  UNKNOWN — nedostatek dat nebo statický objekt
 *
 * Implementuje základy z 2026 Phase-Relational Kinematics (MiraLink/Meteor Tracking).
 */
class TrajectoryClassifierPlugin(
    private val historyDepth: Int = 15,
    private val minSpeedForClassification: Float = 0.001f,
    private val anomalyAccelThreshold: Float = 0.08f,
    private val planeMaxAngularVariance: Float = 0.08f,
    private val birdMinAngularVariance: Float = 0.12f
) : VisionFilterPlugin {

    override val pluginId = "tracker_trajectory_classifier"
    override val displayName = "Trajectory Classifier (Phase-Relational)"

    private enum class TrajectoryType(val tag: String, val threatBoost: Float) {
        BIRD    ("[🐦 BIRD]", 0f),
        PLANE   ("[✈️ PLANE]", 0f),
        ANOMALY ("[🔴 ANOMALY]", 0.8f),
        UNKNOWN ("[? UNK]", 0.1f)
    }

    // Track ID → deque of (vx, vy)
    private val velocityHistory = mutableMapOf<Int, ArrayDeque<Pair<Float, Float>>>()

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        val visibleIds = detections.map { it.id }.toSet()

        for (det in detections) {
            val history = velocityHistory.getOrPut(det.id) { ArrayDeque(historyDepth) }
            if (history.size >= historyDepth) history.removeFirst()
            history.addLast(Pair(det.vx, det.vy))

            // Potřebujeme aspoň 5 framů pro spolehlivou klasifikaci
            if (history.size < 5) {
                result.add(det)
                continue
            }

            val type = classify(history)
            val newTags = det.customTags.toMutableList()
            
            if (type != TrajectoryType.UNKNOWN) {
                newTags.add(type.tag)
            }

            result.add(det.copy(
                customTags = newTags,
                threatLevel = maxOf(det.threatLevel, type.threatBoost)
            ))
        }

        // Evikce ztracených stop
        velocityHistory.keys.retainAll(visibleIds)

        return result
    }

    private fun classify(history: ArrayDeque<Pair<Float, Float>>): TrajectoryType {
        val speeds = history.map { (vx, vy) -> sqrt(vx * vx + vy * vy) }
        val avgSpeed = speeds.average().toFloat()

        // Objekty bez pohybu → UNKNOWN (nechceme klasifikovat statické objekty)
        if (avgSpeed < minSpeedForClassification) return TrajectoryType.UNKNOWN

        // Výpočet úhlů pohybu
        val angles = history.map { (vx, vy) ->
            atan2(vy.toDouble(), vx.toDouble()).toFloat()
        }

        // Angulární změny mezi po sobě jdoucími framy
        val angularDeltas = mutableListOf<Float>()
        for (i in 1 until angles.size) {
            var delta = abs(angles[i] - angles[i - 1])
            // Wrap-around korekce (přechod přes ±π)
            if (delta > PI.toFloat()) delta = (2.0 * PI).toFloat() - delta
            angularDeltas.add(delta)
        }
        val avgAngularChange = angularDeltas.average().toFloat()

        // Zrychlení (Jerk/Jounce simulace): rozdíl po sobě jdoucích rychlostí
        val speedDeltas = speeds.zipWithNext { a, b -> abs(b - a) }
        val maxAcceleration = if (speedDeltas.isEmpty()) 0f else speedDeltas.max()

        return when {
            // ANOMALY: fyzikálně nekonzistentní zrychlení při jakékoliv rychlosti
            maxAcceleration > anomalyAccelThreshold -> TrajectoryType.ANOMALY

            // PLANE: lineární trajektorie, konzistentní rychlost, minimální angulární odchylka
            avgSpeed > 0.01f && avgAngularChange <= planeMaxAngularVariance ->
                TrajectoryType.PLANE

            // BIRD: erratický pohyb (vysoká angulární variance) při nenulové rychlosti
            avgAngularChange >= birdMinAngularVariance && avgSpeed > 0.002f ->
                TrajectoryType.BIRD

            else -> TrajectoryType.UNKNOWN
        }
    }

    @Synchronized
    override fun reset() {
        velocityHistory.clear()
    }

    override fun release() = reset()
}
