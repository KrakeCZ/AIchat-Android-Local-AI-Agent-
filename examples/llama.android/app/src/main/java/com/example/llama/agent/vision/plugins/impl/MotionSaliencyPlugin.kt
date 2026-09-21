package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.sqrt

/**
 * Motion Saliency Filter — zobrazuje POUZE pohybující se objekty (Sky-Ground Segmentation 2026).
 *
 * Sky-scan princip: obloha = statické pozadí. Vše co se pohybuje = potenciální cíl.
 * Statické objekty (stromy, budovy na horizontu) jsou odstraněny z výstupu.
 */
class MotionSaliencyPlugin(
    private val minVelocityThreshold: Float = 0.0015f,
    private val showStaticAsGhost: Boolean = false
) : VisionFilterPlugin {

    override val pluginId = "tracker_motion_saliency"
    override val displayName = "Motion Saliency (Sky/Ground Segmentation)"

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()

        for (det in detections) {
            val speed = sqrt(det.vx * det.vx + det.vy * det.vy)

            when {
                speed >= minVelocityThreshold -> {
                    // Pohybující se → normální výstup
                    result.add(det)
                }
                showStaticAsGhost -> {
                    // Statický → ghost box (průhledný, bledý)
                    result.add(det.copy(
                        isPredictive = true,
                        confidence = det.confidence * 0.25f
                    ))
                }
                // else: statický → zahazujeme (sky-scan mód pro naprostou čistotu)
            }
        }

        return result
    }

    override fun reset() {}
    override fun release() {}
}
