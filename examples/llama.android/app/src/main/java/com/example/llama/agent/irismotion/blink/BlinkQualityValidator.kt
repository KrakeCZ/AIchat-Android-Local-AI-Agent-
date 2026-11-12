package com.example.llama.agent.irismotion.blink

import android.util.Log

/**
 * BlinkQualityValidator — Forenzní analýza mrknutí.
 *
 * Klasifikuje mrknutí na úmyslné (Dwell-Click), neúmyslné fyziologické a chybové artefakty
 * z kamery. Snižuje riziko False-Positive kliknutí a nesprávného Gaze-Freeze logiky.
 */
class BlinkQualityValidator {

    enum class Quality {
        UNKNOWN,
        INTENTIONAL,     // Silné, vědomé mrknutí (na Dwell action)
        INVOLUNTARY,     // Rychlé mrknutí, mikromrknutí (běžné)
        ARTIFACT         // Částečné přimhouření, stín, error snímače
    }

    companion object {
        private const val TAG = "BlinkQualityValidator"
        
        // Záměrné mrknutí trvá typicky déle než fyziologické (>150ms zavřené fáze)
        private const val MIN_INTENTIONAL_DURATION_MS = 100L
        private const val MAX_INTENTIONAL_DURATION_MS = 800L
        
        // Užitečný indikátor ze SaccadeValidatoru / Blendshapes
        private const val SCORE_THRESHOLD = 0.6f
    }

    /**
     * Ohodnotí právě provedené mrknutí a určí, k jaké akci dojde
     * (např. spuštění makra/přepínače Dwell Click, ignorování, nebo jen stabilizace post-blink).
     */
    /**
     * @param isHeadStill True pokud hlava byla v klidu během mrknutí (z HeadMotionIntentFilter).
     *                    Pokud false, mrknutí je degradováno na INVOLUNTARY (nelze odlišit úmysl od reflexu).
     *                    Default = true pro zpětnou kompatibilitu.
     */
    fun evaluateQuality(blinkScore: Float, durationMs: Long, closingVelocity: Float, isHeadStill: Boolean = true): Quality {
        
        val quality = when {
            // Artefakty kamery: příliš rychlé zakmitnutí
            durationMs < 20L -> Quality.ARTIFACT
            
            // Slabé přimhouření (nedovřené víčko)
            blinkScore < SCORE_THRESHOLD -> Quality.ARTIFACT

            // Hlava se hýbala během mrknutí → nelze rozlišit úmysl od reflexu
            !isHeadStill -> Quality.INVOLUNTARY
            
            // Úmyslné, dlouhé mrknutí
            durationMs in MIN_INTENTIONAL_DURATION_MS..MAX_INTENTIONAL_DURATION_MS -> Quality.INTENTIONAL
            
            // Rychlé fyziologické mrknutí (často doprovází mikro-sakkády)
            durationMs < MIN_INTENTIONAL_DURATION_MS -> Quality.INVOLUNTARY
            
            // Spaní nebo dlouhé zavření očí
            else -> Quality.UNKNOWN 
        }

        Log.d(TAG, "🔍 Blink Quality Evaluated: $quality (Score: $blinkScore, Dur: ${durationMs}ms, Vel: $closingVelocity)")
        return quality
    }
}
