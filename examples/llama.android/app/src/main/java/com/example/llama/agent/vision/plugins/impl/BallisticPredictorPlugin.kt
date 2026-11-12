package com.example.llama.agent.vision.plugins.impl

import android.graphics.RectF
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin

/**
 * Predikce balistické trajektorie.
 * 
 * Využívá Newtonovskou kinematiku $s = v \cdot t$. 
 * Protože tracker vkládá do detekcí vektor rychlosti (vx, vy, vw, vh),
 * tento plugin nepotřebuje stav. Pouze extrapoluje budoucí polohu
 * a vygeneruje "duchový" Bounding Box.
 */
class BallisticPredictorPlugin(
    private val predictionTimeSeconds: Float = 1.0f // Jak daleko do budoucnosti se má predikovat
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_ballistic_predict"
    override val displayName = "Ballistic Predictor (1.0s)"

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        
        for (det in detections) {
            // Přidáme samotný objekt
            result.add(det)
            
            // Extrapolujeme budoucí pozici
            // Předpokládáme, že vx/vy jsou jednotky za sekundu.
            // Pokud jsou v jednotkách na frame (např. u DynamicKalmanFilter), 
            // vynásobíme FPS koeficientem. Pro jednoduchost použijeme hrubou aproximaci 30 FPS,
            // pokud jsou hodnoty velmi malé (typické pro frame-based velocity v rozsahu 0..1).
            
            // Otestujeme velikost rychlosti, abychom rozlišili dt-based a frame-based
            val isFrameBased = (Math.abs(det.vx) < 0.05f && Math.abs(det.vy) < 0.05f)
            val timeScale = if (isFrameBased) predictionTimeSeconds * 30f else predictionTimeSeconds

            val futureCx = det.rect.centerX() + det.vx * timeScale
            val futureCy = det.rect.centerY() + det.vy * timeScale
            val futureW = det.rect.width() + det.vw * timeScale
            val futureH = det.rect.height() + det.vh * timeScale

            val halfW = (futureW / 2f).coerceAtLeast(0.01f)
            val halfH = (futureH / 2f).coerceAtLeast(0.01f)

            val predictedRect = RectF(
                futureCx - halfW,
                futureCy - halfH,
                futureCx + halfW,
                futureCy + halfH
            )

            // Vytvoříme Ghost Box
            val ghostDet = Detection(
                id = det.id,
                rect = predictedRect,
                label = "T+${predictionTimeSeconds}s",
                confidence = det.confidence * 0.7f,
                colorIndex = det.colorIndex,
                isPredictive = true
            )
            result.add(ghostDet)
        }
        
        return result
    }

    override fun reset() {
        // Zero-allocation a stateless plugin
    }

    override fun release() {
        // Nic k uvolnění
    }
}
