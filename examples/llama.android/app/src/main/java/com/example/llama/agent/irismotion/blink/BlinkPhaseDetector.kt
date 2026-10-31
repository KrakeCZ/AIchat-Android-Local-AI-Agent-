package com.example.llama.agent.irismotion.blink

import android.util.Log

/**
 * BlinkPhaseDetector — Prediktivní detekce mrkacích fází z 3D dat.
 * 
 * Využívá `eyeOpenness3D` (skutečná vzdálenost horního a dolního víčka v 3D prostoru)
 * k rozdělení fyziologického mrknutí do 4 fází namísto binárního on/off.
 * Tím předchází "Bellovu Fenoménu" (záškub oka vzhůru těsně před úplným zavřením).
 */
class BlinkPhaseDetector {

    enum class Phase {
        OPEN,
        CLOSING,
        CLOSED,
        OPENING
    }

    companion object {
        private const val TAG = "BlinkPhaseDetector"

        // EMA koeficient pro vyhlazení closing velocity (0.3 = 30% nová hodnota, 70% historie)
        // Eliminuje single-frame jitter z IK de-rotace při pohybu hlavy
        private const val VELOCITY_SMOOTH_ALPHA = 0.3f

        // Minimální počet po sobě jdoucích framů pod closingThreshold pro přechod OPEN→CLOSING
        // Zabraňuje falešným přechodům z jednoho zašuměného framu
        private const val MIN_CLOSING_FRAMES = 2
    }

    // P8: Přesunuto z Companion do instance, napojené na Auto-Kalibraci
    var openThreshold = 0.20f
        private set
    var closingThreshold = 0.15f
        private set
    var closedThreshold = 0.10f
        private set

    /**
     * Cílová aktualizace (P8)
     */
    fun reloadThresholds(physioOpenEAR: Float, blinkSensitivityPct: Float) {
        // Pevná hranice: Zadané % z maxima je OPEN_THRESHOLD
        // Default sensitivity je 0.80. Takže 0.35 * 0.80 = 0.28
        openThreshold = physioOpenEAR * blinkSensitivityPct
        
        // CLOSING fáze padne až o výrazný fyziologický skok níže (o 20 % z EAR níže). 
        // Squint (mhouření) typicky sníží EAR jen o 10-15%.
        // Pro EAR 0.35 a Sens 0.80 dříve: 0.35 * 0.75 = 0.2625 (moc úzké, squinting to sepnul)
        // Nově: 0.35 * (0.80 - 0.20) = 0.35 * 0.60 = 0.21. Zde už jde víčko opravdu dolů k mrknutí.
        closingThreshold = physioOpenEAR * (blinkSensitivityPct - 0.20f)
        
        // CLOSED fáze padne až když je víčko v podstatě zavřené
        closedThreshold = physioOpenEAR * (blinkSensitivityPct - 0.40f)
        
        // Zabráníme negativním hodnotám, pokud by si user dal sensitivity moc nízko
        if (closingThreshold < 0.05f) closingThreshold = 0.05f
        if (closedThreshold < 0.02f) closedThreshold = 0.02f
        if (openThreshold <= closingThreshold) openThreshold = closingThreshold + 0.05f

        Log.i(TAG, "👁 EAR Thresholds reloaded: physio=$physioOpenEAR, sens=$blinkSensitivityPct -> OPEN=$openThreshold, CLOSING=$closingThreshold, CLOSED=$closedThreshold")
    }

    var currentPhase: Phase = Phase.OPEN
        private set

    // Historie openness pro výpočet rychlosti
    private var lastOpenness: Float = 1.0f
    private var smoothedVelocity: Float = 0f
    var closingVelocity: Float = 0f
        private set

    // Počítadlo po sobě jdoucích framů pod closingThreshold (anti-jitter)
    private var closingFrameCount = 0

    var blinkStartTime: Long = 0L
        private set
        
    /**
     * Zpracuje nový frame dat o otevření oka.
     * @param openness Vzdálenost horního a dolního okraje víček (eyeOpenness3D)
     * @param nowMs Aktuální timestamp
     * @return Změněná (nebo nezměněná) fáze mrknutí
     */
    fun processFrame(openness: Float, nowMs: Long): Phase {
        // Výpočet rychlosti zavírání s EMA vyhlazením (eliminace single-frame jitteru)
        val rawVelocity = openness - lastOpenness
        smoothedVelocity = VELOCITY_SMOOTH_ALPHA * rawVelocity + (1f - VELOCITY_SMOOTH_ALPHA) * smoothedVelocity
        closingVelocity = smoothedVelocity
        lastOpenness = openness

        val prevPhase = currentPhase

        // Stavový automat fází mrknutí
        currentPhase = when {
            openness < closedThreshold -> {
                // Víčko je pod closedThreshold → definitivně zavřené
                closingFrameCount = 0
                Phase.CLOSED
            }
            openness < closingThreshold && closingVelocity < 0 -> {
                closingFrameCount++
                if (currentPhase == Phase.OPEN || currentPhase == Phase.CLOSING) {
                    if (currentPhase == Phase.OPEN && closingFrameCount >= MIN_CLOSING_FRAMES) {
                        // Potvrzeno: MIN_CLOSING_FRAMES po sobě pod closingThreshold
                        blinkStartTime = nowMs
                        Phase.CLOSING
                    } else if (currentPhase == Phase.CLOSING) {
                        Phase.CLOSING
                    } else {
                        // Ještě nedosaženo MIN_CLOSING_FRAMES → zůstáváme v OPEN
                        currentPhase
                    }
                } else {
                    currentPhase
                }
            }
            openness > closingThreshold && closingVelocity > 0 -> {
                closingFrameCount = 0
                if (currentPhase == Phase.CLOSED || currentPhase == Phase.CLOSING) {
                    Phase.OPENING
                } else if (openness >= openThreshold) {
                    Phase.OPEN
                } else {
                    currentPhase
                }
            }
            openness >= openThreshold -> {
                closingFrameCount = 0
                Phase.OPEN
            }
            else -> currentPhase // Udržuj stav v šedé zóně
        }

        if (prevPhase != currentPhase) {
            Log.v(TAG, "👁️ Blink Phase: $prevPhase → $currentPhase (Openness: $openness, Vel: $closingVelocity, ClosingFrames: $closingFrameCount)")
        }

        return currentPhase
    }
    
    fun reset() {
        currentPhase = Phase.OPEN
        lastOpenness = 1.0f
        closingVelocity = 0f
        smoothedVelocity = 0f
        closingFrameCount = 0
        blinkStartTime = 0L
    }
}
