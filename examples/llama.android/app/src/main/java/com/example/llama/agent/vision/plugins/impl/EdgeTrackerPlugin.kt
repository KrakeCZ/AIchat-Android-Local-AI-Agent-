package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import com.example.llama.agent.vision.tracking.EdgeTracker

/**
 * Standardní Bipartite/IoU Tracker zabalený jako modulární plugin.
 */
class EdgeTrackerPlugin : VisionFilterPlugin {
    override val pluginId = "tracker_iou_edge"
    override val displayName = "EdgeTracker (IoU Heuristics)"

    // Tvá stávající instance trackeru
    private val trackerEngine = EdgeTracker(maxLostFrames = 15, minIouThreshold = 0.1f)

    var estimatorFactory: () -> com.example.llama.agent.vision.tracking.math.MotionEstimator
        get() = trackerEngine.estimatorFactory
        set(value) {
            trackerEngine.estimatorFactory = value
        }

    @Synchronized
    override fun process(
        detections: List<Detection>,
        timestampMs: Long
    ): List<Detection> {
        // Zpracování přes tvůj starý systém
        val trackedObjects = trackerEngine.update(detections, timestampMs)
        
        // Mapování zpět na výstupní formát s naformátovaným labelem (ID)
        return trackedObjects.map { track ->
            Detection(
                id = track.id,
                rect = track.rect,
                label = "[#${track.id}] ${track.label}", 
                confidence = track.confidence,
                colorIndex = track.color,
                vx = track.velocityX,
                vy = track.velocityY,
                vw = track.velocityW,
                vh = track.velocityH
            )
        }
    }

    @Synchronized
    override fun reset() {
        trackerEngine.reset()
    }

    override fun release() {
        reset()
        // Zde by se čistily NPU/GPU buffery, pokud by je plugin měl
    }
}
