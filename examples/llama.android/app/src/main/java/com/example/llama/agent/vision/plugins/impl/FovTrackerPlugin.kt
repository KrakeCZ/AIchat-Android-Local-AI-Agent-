package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin

/**
 * Predikce opuštění zorného pole (FOV Exit Prediction).
 * 
 * Vypočítá, zda vektor rychlosti směřuje mimo obrazovku a jak rychle.
 * Užitečné pro automatické natáčení kamery (PTZ tracking) nebo varování uživatele.
 * Pracuje s normalizovanými souřadnicemi (0.0 - 1.0).
 */
class FovTrackerPlugin(
    private val warningThresholdSeconds: Float = 2.0f // Varovat, pokud opustí FOV do 2 sekund
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_fov_exit"
    override val displayName = "FOV Exit Warning (Pan/Tilt)"

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        
        for (det in detections) {
            val isFrameBased = (Math.abs(det.vx) < 0.05f && Math.abs(det.vy) < 0.05f)
            val fpsScale = if (isFrameBased) 30f else 1f

            val vxSec = det.vx * fpsScale
            val vySec = det.vy * fpsScale

            // Predpoklad: Souřadnice středu cx/cy jsou normalizované 0..1
            val cx = det.rect.centerX()
            val cy = det.rect.centerY()

            var timeToExitX = Float.POSITIVE_INFINITY
            var timeToExitY = Float.POSITIVE_INFINITY

            if (vxSec > 0.01f) {
                timeToExitX = (1.0f - cx) / vxSec
            } else if (vxSec < -0.01f) {
                timeToExitX = cx / (-vxSec)
            }

            if (vySec > 0.01f) {
                timeToExitY = (1.0f - cy) / vySec
            } else if (vySec < -0.01f) {
                timeToExitY = cy / (-vySec)
            }

            val minExitTime = minOf(timeToExitX, timeToExitY)

            if (minExitTime <= warningThresholdSeconds && minExitTime > 0.1f) {
                val newTags = det.customTags.toMutableList()
                val dir = when {
                    timeToExitX < timeToExitY && vxSec > 0 -> "RIGHT"
                    timeToExitX < timeToExitY && vxSec < 0 -> "LEFT"
                    vySec > 0 -> "BOTTOM"
                    else -> "TOP"
                }
                
                val formattedTime = String.format("%.1fs", minExitTime)
                newTags.add("[LEAVING FOV $dir IN $formattedTime]")
                
                result.add(det.copy(customTags = newTags))
            } else {
                result.add(det)
            }
        }
        
        return result
    }

    override fun reset() {}
    override fun release() {}
}
