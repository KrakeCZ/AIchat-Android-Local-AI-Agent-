package com.example.llama.agent.irismotion.expressions

/**
 * Adaptive Baseline Tracker (ABT) pro kompenzaci biologického šumu a svalové únavy (driftu).
 * Využívá Asymetrický Exponenciální Klouzavý Průměr (EMA).
 */
class AdaptiveBaselineTracker(
    private val restingThreshold: Float = 0.15f, // Hodnoty pod tímto prahem považujeme za "klid"
    private val emaAlphaResting: Float = 0.001f, // Velmi pomalá adaptace (odolnost vůči mikrovýrazům)
    private val emaAlphaActive: Float = 0.0001f  // Extrémně pomalá adaptace, pokud je výraz aktivní
) {
    private var baseline: Float = 0.0f
    private var initialized: Boolean = false

    /**
     * Vypočítá novou "nulu" a vrátí očištěnou hodnotu gesta.
     */
    fun process(currentValue: Float): Float {
        if (!initialized) {
            baseline = currentValue
            initialized = true
            return 0.0f
        }

        // Asymetrická aktualizace
        val alpha = if (currentValue < restingThreshold) emaAlphaResting else emaAlphaActive
        
        // Pomalý posun baseline směrem k aktuální hodnotě
        baseline = (alpha * currentValue) + ((1.0f - alpha) * baseline)

        // Očištěná hodnota
        val adjustedValue = currentValue - baseline
        
        return adjustedValue.coerceAtLeast(0.0f)
    }

    fun reset() {
        initialized = false
        baseline = 0.0f
    }
}
