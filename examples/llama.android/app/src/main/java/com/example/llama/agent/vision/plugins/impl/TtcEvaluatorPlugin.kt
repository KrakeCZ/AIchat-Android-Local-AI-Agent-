package com.example.llama.agent.vision.plugins.impl

import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.vision.plugins.VisionFilterPlugin

/**
 * Odhad času do srážky (Time to Collision / Expansion).
 * 
 * Využívá derivace velikosti (vw, vh), ke kterým máme nyní přístup díky Kalmanovu filtru.
 * Princip je jednoduchý: Pokud se velikost objektu (šířka/výška) zvětšuje
 * a jeho střed se nemění příliš rychle do stran, znamená to, že se k nám fyzicky přibližuje.
 * 
 * TTC (v sekundách) = Aktuální_Rozměr / Rychlost_Změny_Rozměru_Za_Sekundu.
 */
class TtcEvaluatorPlugin(
    private val dangerThresholdSeconds: Float = 3.0f // Varování, pokud do srážky zbývá méně než 3s
) : VisionFilterPlugin {
    
    override val pluginId = "tracker_ttc_eval"
    override val displayName = "TTC Evaluator (Impact Warning)"

    override fun process(detections: List<Detection>, timestampMs: Long): List<Detection> {
        val result = mutableListOf<Detection>()
        
        for (det in detections) {
            // Predpokládáme, že vw/vh reprezentují rychlost expanze za frame.
            // Pro sekundy násobíme FPS (zhruba 30).
            val isFrameBased = (Math.abs(det.vw) < 0.05f && Math.abs(det.vh) < 0.05f)
            val fpsScale = if (isFrameBased) 30f else 1f

            val expansionWPerSec = det.vw * fpsScale
            val expansionHPerSec = det.vh * fpsScale
            
            // Zajímá nás jen pozitivní expanze (objekt se zvětšuje)
            if (expansionWPerSec > 0.005f && expansionHPerSec > 0.005f) {
                // Vezmeme konzervativnější odhad ze šířky a výšky
                val ttcW = det.rect.width() / expansionWPerSec
                val ttcH = det.rect.height() / expansionHPerSec
                val ttc = minOf(ttcW, ttcH)

                if (ttc <= dangerThresholdSeconds && ttc > 0.1f) {
                    val newTags = det.customTags.toMutableList()
                    val formattedTtc = String.format("%.1fs", ttc)
                    newTags.add("[IMPACT IN $formattedTtc]")
                    
                    // Zvýšíme i Threat Level, aby se zobrazil červeně
                    val threatScale = (1.0f - (ttc / dangerThresholdSeconds)).coerceIn(0f, 1f)
                    val newThreat = maxOf(det.threatLevel, threatScale)
                    
                    result.add(det.copy(customTags = newTags, threatLevel = newThreat))
                    continue
                }
            }
            
            result.add(det)
        }
        
        return result
    }

    override fun reset() {}
    override fun release() {}
}
