package com.example.llama.agent.vision.plugins.impl

import android.graphics.RectF
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.max
import kotlin.math.min

/**
 * OC-SORT (Observation-Centric SORT) Tracker Plugin.
 * 
 * Řeší největší slabinu klasických trackerů: drift při okluzi.
 * Když objekt zmizí za překážkou, tradiční Kalman slepě predikuje dopředu
 * a při znovuobjevení "ustřelí" ID. OC-SORT zavádí Observation-Centric Momentum (OCM):
 * při znovuobjevení spočítá virtuální vektor rychlosti mezi posledním známým bodem
 * a aktuálním novým bodem, čímž opraví drift.
 * 
 * Vlastní interní kinematika: velocity vektory [dx, dy, dw, dh] s brzdným útlumem 15%/frame.
 */
class OcSortTrackerPlugin : VisionFilterPlugin {
    override val pluginId = "tracker_oc_sort_pro"
    override val displayName = "OC-SORT (Anti-Drift)"

    private val activeTracks = mutableListOf<OcTrack>()
    private var nextTrackId = 1
    
    private val maxAge = 30       // Udrží stopu živou cca 1 sekunda bez detekce
    private val minHits = 3       // Počet potvrzení, než se objekt vykreslí
    private val iouThreshold = 0.2f

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        // 1. Krok: Fyzikální predikce pro existující stopy
        activeTracks.forEach { it.predict() }

        val unmatchedDetections = detections.toMutableList()

        // 2. Krok: OCM Asociace (Greedy IoU Matching přes stejné třídy)
        activeTracks.forEach { track ->
            var bestMatchIdx = -1
            var bestIou = iouThreshold

            for (i in unmatchedDetections.indices) {
                val det = unmatchedDetections[i]
                if (det.label == track.label) {
                    val iou = calculateIoU(track.currentRect, det.rect)
                    if (iou > bestIou) {
                        bestIou = iou
                        bestMatchIdx = i
                    }
                }
            }

            if (bestMatchIdx != -1) {
                val match = unmatchedDetections[bestMatchIdx]
                // OC-SORT: update spočítá momentum rychlosti z poslední observace
                track.update(match.rect, match.confidence)
                unmatchedDetections.removeAt(bestMatchIdx)
            } else {
                track.markMiss()
            }
        }

        // 3. Krok: Inicializace nových stop z nespárovaných High-Confidence detekcí
        unmatchedDetections.forEach { det ->
            if (det.confidence >= 0.4f) {
                activeTracks.add(
                    OcTrack(
                        id = nextTrackId++,
                        label = det.label,
                        initialRect = det.rect,
                        confidence = det.confidence,
                        colorIndex = det.colorIndex
                    )
                )
            }
        }

        // 4. Krok: Evikce mrtvých trajektorií
        activeTracks.removeAll { it.timeSinceUpdate > maxAge }

        // 5. Krok: Filtrace — pouze stabilní potvrzené objekty
        return activeTracks
            .filter { it.hits >= minHits && it.timeSinceUpdate == 0 }
            .map { track ->
                Detection(
                    id = track.id,
                    rect = RectF(track.currentRect),
                    label = "[#${track.id}] ${track.label}",
                    confidence = track.confidence,
                    colorIndex = track.colorIndex,
                    vx = track.velocityX,
                    vy = track.velocityY,
                    vw = track.velocityW,
                    vh = track.velocityH
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
     * Vnitřní OC-SORT stopa s Observation-Centric Momentum korekcí.
     * 
     * Vlastní kinematika: velocity vektory [dx, dy, dw, dh].
     * Při znovuobjevení po okluzi → zpětný přepočet velocity z posledního
     * známého bodu (lastKnownObservation) a nové pozice.
     */
    private class OcTrack(
        val id: Int,
        val label: String,
        initialRect: RectF,
        var confidence: Float,
        val colorIndex: Int
    ) {
        var currentRect = RectF(initialRect)
        var lastKnownObservation = RectF(initialRect)
        
        var hits = 1
        var timeSinceUpdate = 0
        
        // Vektor rychlosti [dx, dy, dw, dh] (změna hran left, top, right, bottom)
        private val velocity = FloatArray(4)

        val velocityX: Float get() = (velocity[0] + velocity[2]) / 2f // Průměr left/right = pohyb středu X
        val velocityY: Float get() = (velocity[1] + velocity[3]) / 2f // Průměr top/bottom = pohyb středu Y
        val velocityW: Float get() = velocity[2] - velocity[0]        // Expanze šířky
        val velocityH: Float get() = velocity[3] - velocity[1]        // Expanze výšky

        fun predict() {
            // Aplikace momentální setrvačnosti na stav boxu
            currentRect.left   += velocity[0]
            currentRect.top    += velocity[1]
            currentRect.right  += velocity[2]
            currentRect.bottom += velocity[3]
            timeSinceUpdate++
        }

        fun update(newRect: RectF, newConfidence: Float) {
            // Výpočet Observation-Centric směrového vektoru (Momentum)
            // Vážený inverzně k počtu framů od posledního kontaktu
            val weight = 1.0f / (timeSinceUpdate.toFloat().coerceAtLeast(1.0f))
            
            velocity[0] = (newRect.left   - lastKnownObservation.left)   * weight
            velocity[1] = (newRect.top    - lastKnownObservation.top)    * weight
            velocity[2] = (newRect.right  - lastKnownObservation.right)  * weight
            velocity[3] = (newRect.bottom - lastKnownObservation.bottom) * weight

            currentRect.set(newRect)
            lastKnownObservation.set(newRect)
            
            confidence = newConfidence
            hits++
            timeSinceUpdate = 0
        }

        fun markMiss() {
            // Brzdný útlum 15% za frame — zabraňuje zběsilému odletu boxu
            velocity[0] *= 0.85f
            velocity[1] *= 0.85f
            velocity[2] *= 0.85f
            velocity[3] *= 0.85f
        }
    }
}
