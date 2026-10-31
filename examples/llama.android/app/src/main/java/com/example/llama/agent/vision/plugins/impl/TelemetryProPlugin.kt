package com.example.llama.agent.vision.plugins.impl

import android.graphics.RectF
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Kinematic Telemetry Tracker (Constant Acceleration Model) — SOTA 2026/2027.
 * 
 * Počítá reálnou rychlost a zrychlení objektů nezávisle na kolísání FPS.
 * Na mobilních zařízeních dochází vlivem Thermal Throttlingu k neustálým
 * výkyvům snímkové frekvence. Tento plugin pracuje s reálným Δt v sekundách,
 * takže predikce funguje korektně i při dropech z 30 na 22 FPS.
 * 
 * Stavový vektor: [vx, vy, vw, vh] (rychlost) + [ax, ay] (zrychlení)
 * Pohybová rovnice: x = x + v·dt + 0.5·a·dt²
 * 
 * Label je obohacený o telemetrický údaj: v:X.XX u/s (jednotky displeje/sekundu)
 */
class TelemetryProPlugin : VisionFilterPlugin {
    override val pluginId = "tracker_kinematic_telemetry_2026"
    override val displayName = "Kinematic Telemetry (CA Model)"

    private val activeTracks = mutableListOf<KinematicTrack>()
    private var nextTrackId = 1
    private var lastFrameTimeMs = 0L

    private val maxAgeMs = 1500L     // Držíme objekt max 1.5 sekundy bez detekce
    private val iouThreshold = 0.25f

    @Synchronized
    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        if (lastFrameTimeMs == 0L) {
            lastFrameTimeMs = timestampMs
            // První frame — není odkud počítat dt, vrátíme surová data
            return detections
        }

        // Výpočet reálného delta času v sekundách
        val dt = (timestampMs - lastFrameTimeMs) / 1000.0f
        lastFrameTimeMs = timestampMs

        // Guard proti anomáliím (systémový zásek, přetečení, záporný čas)
        if (dt <= 0f || dt > 1.0f) return detections

        // 1. Krok: Fyzikální predikce polohy na základě rychlosti a zrychlení
        activeTracks.forEach { it.predict(dt) }

        val unmatchedDetections = detections.toMutableList()

        // 2. Krok: Asociace dat (Bipartite IoU matching)
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
                track.update(match.rect, match.confidence, timestampMs)
                unmatchedDetections.removeAt(bestMatchIdx)
            } else {
                track.markLost()
            }
        }

        // 3. Krok: Inicializace nových objektů
        unmatchedDetections.forEach { det ->
            if (det.confidence >= 0.45f) {
                activeTracks.add(
                    KinematicTrack(
                        id = nextTrackId++,
                        label = det.label,
                        initialRect = det.rect,
                        confidence = det.confidence,
                        colorIndex = det.colorIndex,
                        bornTimeMs = timestampMs
                    )
                )
            }
        }

        // 4. Krok: Evikce mrtvých stop na základě reálného času
        activeTracks.removeAll { (timestampMs - it.lastSeenTimeMs) > maxAgeMs }

        // 5. Krok: Obohacení labelu o telemetrická data
        return activeTracks
            .filter { (timestampMs - it.lastSeenTimeMs) == 0L && it.totalHits >= 3 }
            .map { track ->
                // Skalární rychlost (vektorová norma v prostoru obrazovky)
                val speedSec = sqrt(
                    track.velocity[0] * track.velocity[0] + 
                    track.velocity[1] * track.velocity[1]
                )
                val speedText = String.format(java.util.Locale.US, "%.2f u/s", speedSec)

                Detection(
                    rect = RectF(track.currentRect),
                    label = "[#${track.id}] ${track.label} v:${speedText}",
                    confidence = track.confidence,
                    colorIndex = track.colorIndex
                )
            }
    }

    @Synchronized
    override fun reset() {
        activeTracks.clear()
        nextTrackId = 1
        lastFrameTimeMs = 0L
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
     * Fyzikální CA (Constant Acceleration) jádro entity pracující s reálným časem.
     * 
     * Stavové vektory (alokované pouze jednou při vzniku):
     *   velocity     = [vx, vy, vw, vh] — jednotky za sekundu
     *   acceleration = [ax, ay]          — jednotky za sekundu²
     */
    private class KinematicTrack(
        val id: Int,
        val label: String,
        initialRect: RectF,
        var confidence: Float,
        val colorIndex: Int,
        bornTimeMs: Long
    ) {
        val currentRect = RectF(initialRect)
        var lastSeenTimeMs = bornTimeMs
        var totalHits = 1

        val velocity = FloatArray(4)         // [vx, vy, vw, vh]
        val acceleration = FloatArray(2)     // [ax, ay]

        fun predict(dt: Float) {
            // Rychlostní složka ovlivněná zrychlením: v = v + a · dt
            velocity[0] += acceleration[0] * dt
            velocity[1] += acceleration[1] * dt

            // Pohybové rovnice: x = x + v·dt + 0.5·a·dt²
            val dx = velocity[0] * dt + 0.5f * acceleration[0] * dt * dt
            val dy = velocity[1] * dt + 0.5f * acceleration[1] * dt * dt
            val dw = velocity[2] * dt
            val dh = velocity[3] * dt

            currentRect.left   += dx
            currentRect.top    += dy
            currentRect.right  += (dx + dw)
            currentRect.bottom += (dy + dh)
        }

        fun update(newRect: RectF, newConfidence: Float, currentTimestampMs: Long) {
            val dt = (currentTimestampMs - lastSeenTimeMs) / 1000.0f
            lastSeenTimeMs = currentTimestampMs
            confidence = newConfidence
            totalHits++

            if (dt > 0f) {
                // Výpočet okamžité nové rychlosti
                val newVx = (newRect.centerX() - currentRect.centerX()) / dt
                val newVy = (newRect.centerY() - currentRect.centerY()) / dt
                val newVw = (newRect.width() - currentRect.width()) / dt
                val newVh = (newRect.height() - currentRect.height()) / dt

                // Výpočet zrychlení: a = (v_nová - v_stará) / dt
                // Low-Pass filtr (0.3) pro vyhlazení chyb měření NPU
                acceleration[0] = acceleration[0] * 0.7f + ((newVx - velocity[0]) / dt) * 0.3f
                acceleration[1] = acceleration[1] * 0.7f + ((newVy - velocity[1]) / dt) * 0.3f

                // Uložení nové rychlosti
                velocity[0] = newVx
                velocity[1] = newVy
                velocity[2] = newVw
                velocity[3] = newVh
            }

            currentRect.set(newRect)
        }

        fun markLost() {
            // Setrvačné tření — tlumíme zrychlení i rychlost
            acceleration[0] *= 0.5f
            acceleration[1] *= 0.5f
            velocity[0] *= 0.9f
            velocity[1] *= 0.9f
            velocity[2] *= 0.9f
            velocity[3] *= 0.9f
        }
    }
}
