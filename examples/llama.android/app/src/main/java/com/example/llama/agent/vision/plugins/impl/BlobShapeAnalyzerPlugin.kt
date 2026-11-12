package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Blob Shape Analyzer — klasifikace tvaru bounding boxu + konzistence s pohybem.
 *
 * Aspect Ratio (AR) analýza:
 *   AR > 3.0  → výrazně protáhlý (letadlo, pták s rozepjatými křídly)
 *   AR < 1.3  → blíže čtvercový / symetrický (dron, UAP disk, balón)
 *   AR 1.3–3.0 → neutrální tvar
 *
 * Motion alignment check (klíčová UAP heuristika):
 *   Normální letadlo/pták: pohybuje se VE SMĚRU svého protažení (AR osa ≈ velocity osa).
 *   Anomální chování: protáhlý objekt se pohybuje KOLMO na svou osu (bokem).
 *   Symetrický objekt + vysoká rychlost → fyzikálně podezřelé.
 *
 * Nemá interní stav — stateless, bezpečné pro concurrent volání.
 */
class BlobShapeAnalyzerPlugin : VisionFilterPlugin {

    override val pluginId    = "tracker_blob_shape"
    override val displayName = "Blob Shape Analyzer (UAP/Bird/Plane)"

    // Thresholdy — konzistentní s TrajectoryClassifierPlugin velocity měřítkem
    private val minSpeedForAlignment  = 0.0012f  // Min vx/vy pro direction check
    private val planeAspectRatio      = 3.0f     // AR > toto → letadlo-like tvar
    private val symmetricAspectRatio  = 1.3f     // AR < toto → symetrický objekt
    private val fastSymmetricSpeed    = 0.010f   // Rychlost → symetrický objekt je podezřelý
    private val perpendicularMinAR    = 2.5f     // Min AR pro kolmý pohyb alert

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        return detections.map { det -> analyzeShape(det) }
    }

    private fun analyzeShape(det: Detection): Detection {
        val w = det.rect.width()
        val h = det.rect.height()

        // Validace — malformovaný bbox
        if (w <= 0f || h <= 0f) return det

        // Aspect ratio vždy >= 1.0 (dominant dimension / minor dimension)
        val isHorizontal = w >= h
        val aspectRatio  = if (isHorizontal) w / h else h / w

        val speed = sqrt(det.vx * det.vx + det.vy * det.vy)
        val hasValidVelocity = speed >= minSpeedForAlignment

        // Dominantní osa pohybu (true = horizontální)
        val movingHorizontally = hasValidVelocity && abs(det.vx) >= abs(det.vy)

        // Konzistence tvaru s pohybem: stejná osa = aligned, opačná = perpendicular
        val isAligned     = (isHorizontal == movingHorizontally)
        val isPerpendicular = hasValidVelocity && !isAligned && aspectRatio >= perpendicularMinAR

        val (tag, threatBoost) = when {

            // ANOMALY: symetrický (blízko čtverce) + pohyb vysokou rychlostí
            // → žádný přirozený objekt tohoto tvaru nelétá takhle rychle
            aspectRatio < symmetricAspectRatio && speed >= fastSymmetricSpeed ->
                "[SHAPE:SYMMETRIC+FAST]" to 0.55f

            // ANOMALY: protáhlý objekt se pohybuje kolmo na svou osu
            // → letadla a ptáci toto nedělají (aerodynamika vylučuje boční pohyb)
            isPerpendicular ->
                "[SHAPE:PERPENDICULAR-MOTION AR=${String.format("%.1f", aspectRatio)}]" to 0.35f

            // PLANE-LIKE: výrazně protáhlý + pohyb ve směru protažení
            aspectRatio >= planeAspectRatio && isAligned ->
                "[SHAPE:PLANE-LIKE AR=${String.format("%.1f", aspectRatio)}]" to 0f

            // ELONGATED: protáhlý obecně (zahrnuje ptáky)
            aspectRatio >= planeAspectRatio ->
                "[SHAPE:ELONGATED AR=${String.format("%.1f", aspectRatio)}]" to 0f

            // COMPACT: čtvercový tvar, pomalý pohyb → balón, dron v hoveru
            aspectRatio < symmetricAspectRatio ->
                "[SHAPE:COMPACT AR=${String.format("%.1f", aspectRatio)}]" to 0.12f

            // NEUTRAL: nic zajímavého
            else -> return det
        }

        return det.copy(
            customTags  = det.customTags + tag,
            threatLevel = maxOf(det.threatLevel, threatBoost)
        )
    }

    override fun reset()   {}
    override fun release() {}
}
