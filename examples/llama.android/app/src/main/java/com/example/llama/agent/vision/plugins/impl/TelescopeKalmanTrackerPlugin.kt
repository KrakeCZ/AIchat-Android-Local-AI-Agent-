package com.example.llama.agent.vision.plugins.impl

import android.graphics.RectF
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import com.example.llama.agent.vision.tracking.math.DynamicKalmanFilter2D
import kotlin.math.max
import kotlin.math.min

/**
 * Telescope Kalman Tracker — Specializovaný profil pro optické nástavce a dalekohledy.
 * 
 * Řeší:
 * 1. Obrovské vibrace od větru/dotyku (vysoký Measurement Noise R)
 * 2. Mizení objektů kvůli seeingu a úzkému zornému poli (dlouhá paměť)
 */
class TelescopeKalmanTrackerPlugin : VisionFilterPlugin {
    override val pluginId = "tracker_telescope_dkf"
    override val displayName = "Telescope DKF (Extreme Anti-Jitter)"

    private val activeTracks = mutableListOf<ActiveTrack>()
    private var nextTrackId = 1

    var estimatorFactory: () -> com.example.llama.agent.vision.tracking.math.MotionEstimator = {
        DynamicKalmanFilter2D()
    }

    // Dalekohled: pamatuje si objekt mnohem déle i když zmizí z dohledu
    private val maxLostFrames = 90  // 3 sekundy při 30 fps
    // I menší překryv stačí, protože kamera se třepe a boxy hodně skáčou
    private val minIouThreshold = 0.05f

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        activeTracks.forEach { it.predict(timestampMs) }

        val unmatchedDetections = detections.toMutableList()

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
                val match = unmatchedDetections[bestMatchIdx]
                // 💥 KLÍČOVÝ TELESCOPE TRIK: Uměle snížíme confidence měření pro Kalmanův filtr.
                // Tím ho donutíme víc věřit setrvačnosti a matematickému modelu než třepejícímu se obrazu!
                val jitterDampedConfidence = match.confidence * 0.15f
                track.update(match.rect, jitterDampedConfidence, match.confidence, timestampMs)
                unmatchedDetections.removeAt(bestMatchIdx)
            } else {
                track.markMiss()
            }
        }

        unmatchedDetections.forEach { det ->
            if (det.confidence >= 0.40f) {
                activeTracks.add(
                    ActiveTrack(
                        id = nextTrackId++,
                        classLabel = det.label,
                        initialRect = det.rect,
                        realConfidence = det.confidence,
                        colorIndex = det.colorIndex,
                        factory = estimatorFactory
                    )
                )
            }
        }

        activeTracks.removeAll { it.lostFramesCounter > maxLostFrames }

        return activeTracks.map { track ->
            val cleanLabel = track.classLabel.substringAfter("] ").trim().ifEmpty { track.classLabel }
            Detection(
                id = track.id,
                rect = RectF(track.predictedRect),
                label = "[#${track.id}] $cleanLabel",
                confidence = track.realConfidence,
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

    override fun release() = reset()

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

    private class ActiveTrack(
        val id: Int,
        val classLabel: String,
        initialRect: RectF,
        var realConfidence: Float,
        val colorIndex: Int,
        factory: () -> com.example.llama.agent.vision.tracking.math.MotionEstimator
    ) {
        val predictedRect = RectF(initialRect)
        val kalmanFilter = factory()
        var lostFramesCounter = 0

        init {
            kalmanFilter.initiate(initialRect)
        }

        fun predict(timestampMs: Long) {
            kalmanFilter.predict(timestampMs)
            kalmanFilter.getPredictedRect(predictedRect)
        }

        fun update(newRect: RectF, kalmanConfidence: Float, actualConfidence: Float, timestampMs: Long) {
            kalmanFilter.update(newRect, kalmanConfidence, timestampMs)
            kalmanFilter.getPredictedRect(predictedRect)
            this.realConfidence = actualConfidence
            lostFramesCounter = 0
        }

        fun markMiss() {
            lostFramesCounter++
            realConfidence *= 0.95f
        }
    }
}
