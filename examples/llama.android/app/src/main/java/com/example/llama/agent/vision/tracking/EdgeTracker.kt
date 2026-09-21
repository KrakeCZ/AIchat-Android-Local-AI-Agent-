package com.example.llama.agent.vision.tracking

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * EdgeTracker — Zero-Implicit Lightweight Multi-Object Tracker (MOT)
 * Inspirováno algoritmy SORT a ByteTrack pro mobilní NPU.
 * Využívá Greedy Bipartite Matching pomocí IoU metriky.
 */
class EdgeTracker(
    private val maxLostFrames: Int = 5, // Sníženo z 15 na 5 pro rychlejší vyřazení ghost tracků
    private val minIouThreshold: Float = 0.1f // Minimální shoda pro spojení (sníženo pro rychlá auta/drony)
) {
    private val activeTracks = mutableListOf<TrackedObject>()
    private var nextId = 1

    var estimatorFactory: () -> com.example.llama.agent.vision.tracking.math.MotionEstimator = { com.example.llama.agent.vision.tracking.OD_KalmanFilter2D() }

    /**
     * Hlavní metoda trackeru. Vezme stateless boxy z TFLite a vrátí stateful trackované entity.
     * @param detections Surové detekce z tohoto framu
     * @param timestampMs Čas snímku
     * @return Seznam aktivních, vyhlazených a ID-očíslovaných objektů
     */
    @Synchronized
    fun update(
        detections: List<com.example.llama.agent.vision.BoundingBoxView.Detection>,
        timestampMs: Long
    ): List<TrackedObject> {
        
        // 1. KROK: Predikce (Blind kalman update pro všechny stávající tracky)
        activeTracks.forEach { it.predictBlind(timestampMs) }

        val unmatchedDetections = detections.toMutableList()
        val matchedTracks = mutableSetOf<TrackedObject>()

        // 2. KROK: Greedy IoU Association (Spojování starých tracků s novými detekcemi)
        // Seřadíme staré tracky podle spolehlivosti (čím více hitů, tím stabilnější ID)
        activeTracks.sortedByDescending { it.hits }.forEach { track ->
            var bestMatchIdx = -1
            var bestIou = minIouThreshold

            for (i in unmatchedDetections.indices) {
                val det = unmatchedDetections[i]
                
                // Měříme shodu POUZE s objekty stejné třídy (Auto se nesmí stát Člověkem)
                if (det.label == track.label) {
                    val iou = calculateIoU(track.rect, det.rect)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestMatchIdx = i
                    }
                }
            }

            // Pokud jsme našli shodu, updatujeme stav a vyřadíme detekci z hledání
            if (bestMatchIdx != -1) {
                val match = unmatchedDetections[bestMatchIdx]
                track.updateWithDetection(match.rect, match.confidence, timestampMs)
                matchedTracks.add(track)
                unmatchedDetections.removeAt(bestMatchIdx)
            }
        }

        // 3. KROK: Vytvoření nových identit pro nespárované detekce
        unmatchedDetections.forEach { newDet ->
            val newTrack = TrackedObject(
                id = nextId++,
                label = newDet.label,
                confidence = newDet.confidence,
                rect = newDet.rect,
                color = newDet.colorIndex,
                estimatorFactory = estimatorFactory
            )
            activeTracks.add(newTrack)
        }

        // 4. KROK: Odstranění mrtvých stop (Eviction)
        activeTracks.removeAll { it.misses > maxLostFrames }

        // Vracíme kopii bezpečnou pro čtení vláknem UI
        return activeTracks.toList()
    }

    /** Vyprázdnění paměti (např. při změně kamery nebo pauze) */
    @Synchronized
    fun reset() {
        activeTracks.clear()
        nextId = 1
    }

    /**
     * Výpočet Intersection over Union (Jaccard Index).
     * Optimalizováno pro nulové alokace.
     */
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
}
