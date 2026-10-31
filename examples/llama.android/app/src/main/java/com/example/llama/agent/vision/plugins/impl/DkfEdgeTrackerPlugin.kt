package com.example.llama.agent.vision.plugins.impl

import android.graphics.RectF
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import com.example.llama.agent.vision.tracking.math.DynamicKalmanFilter2D
import com.example.llama.agent.vision.tracking.math.MotionEstimator
import kotlin.math.max
import kotlin.math.min

/**
 * DKF Edge Tracker — Bipartite IoU Tracker s 8-stavovým Dynamickým Kalmanovým Filtrem.
 */
class DkfEdgeTrackerPlugin : VisionFilterPlugin {
    override val pluginId = "tracker_dkf_edge"
    override val displayName = "DKF Edge (Dynamic Kalman 8-State)"

    private val activeTracks = mutableListOf<ActiveTrack>()
    private var nextTrackId = 1

    private val maxLostFrames = 15
    private val minIouThreshold = 0.15f

    // Továrna pro Hot-Swapping filtrů za běhu
    var estimatorFactory: () -> MotionEstimator = { DynamicKalmanFilter2D() }

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        // 1. Krok: Kinetická predikce všech existujících stop
        activeTracks.forEach { it.predict(timestampMs) }

        val unmatchedDetections = detections.toMutableList()

        // 2. Krok: Bipartite Matching (Greedy IoU asociace dat)
        activeTracks.forEach { track ->
            var bestMatchIdx = -1
            var bestIou = minIouThreshold

            for (i in unmatchedDetections.indices) {
                val det = unmatchedDetections[i]
                if (det.label == track.classLabel) {
                    val iou = calculateIoU(track.predictedRect, det.rect)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestMatchIdx = i
                    }
                }
            }

            if (bestMatchIdx != -1) {
                // Objekt nalezen → korekční krok s confidence-weighted R
                val match = unmatchedDetections[bestMatchIdx]
                track.update(match.rect, match.confidence, timestampMs)
                unmatchedDetections.removeAt(bestMatchIdx)
            } else {
                // Objekt ztracen → prediktivní režim okluze
                track.markMiss()
            }
        }

        // 3. Krok: Nové stopy pro nespárované detekce s vysokou confidence
        unmatchedDetections.forEach { det ->
            if (det.confidence >= 0.40f) {
                activeTracks.add(
                    ActiveTrack(
                        id = nextTrackId++,
                        classLabel = det.label,
                        initialRect = det.rect,
                        confidence = det.confidence,
                        colorIndex = det.colorIndex,
                        estimatorFactory = estimatorFactory
                    )
                )
            }
        }

        // 4. Krok: Evikce exspirujících stop
        activeTracks.removeAll { it.lostFramesCounter > maxLostFrames }

        // 5. Krok: Mapování na sjednocený výstup
        return activeTracks.map { track ->
            val cleanLabel = track.classLabel.substringAfter("] ").trim()
                .ifEmpty { track.classLabel }

            Detection(
                id = track.id,
                rect = RectF(track.predictedRect),
                label = "[#${track.id}] $cleanLabel",
                confidence = track.confidence,
                colorIndex = track.colorIndex,
                vx = track.kalmanFilter.velocityX,
                vy = track.kalmanFilter.velocityY,
                vw = track.kalmanFilter.velocityW,
                vh = track.kalmanFilter.velocityH
            )
        }
    }

    @Synchronized
    override fun reset() {
        activeTracks.clear()
        nextTrackId = 1
    }

    override fun release() {
        reset()
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val intersectLeft = max(a.left, b.left)
        val intersectTop = max(a.top, b.top)
        val intersectRight = min(a.right, b.right)
        val intersectBottom = min(a.bottom, b.bottom)

        if (intersectLeft < intersectRight && intersectTop < intersectBottom) {
            val intersectionArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
            val areaA = a.width() * a.height()
            val areaB = b.width() * b.height()
            val unionArea = areaA + areaB - intersectionArea
            return if (unionArea > 0) intersectionArea / unionArea else 0f
        }
        return 0f
    }

    /**
     * Vnitřní datový kontejner s abstrahovaným filtrem
     */
    private class ActiveTrack(
        val id: Int,
        val classLabel: String,
        initialRect: RectF,
        var confidence: Float,
        val colorIndex: Int,
        estimatorFactory: () -> MotionEstimator
    ) {
        val predictedRect = RectF(initialRect)
        val kalmanFilter: MotionEstimator = estimatorFactory()
        var lostFramesCounter = 0

        init {
            kalmanFilter.initiate(initialRect)
        }

        fun predict(timestampMs: Long) {
            kalmanFilter.predict(timestampMs)
            kalmanFilter.getPredictedRect(predictedRect)
        }

        fun update(newRect: RectF, newConfidence: Float, timestampMs: Long) {
            kalmanFilter.update(newRect, newConfidence, timestampMs)
            kalmanFilter.getPredictedRect(predictedRect)
            this.confidence = newConfidence
            lostFramesCounter = 0
        }

        fun markMiss() {
            lostFramesCounter++
            confidence *= 0.92f
        }
    }
}
