package com.example.llama.agent.irismotion.expressions

/**
 * Schmittův klopný obvod (Schmitt Trigger).
 * Diskretizuje plovoucí signál na booleovský stav pomocí dvou odlišných prahů.
 * Zabraňuje "chvění" (chattering) na hranici detekce a aplikuje časovou bránu (Hold Time).
 */
class SchmittTrigger(
    private var highThreshold: Float,  // Práh pro zapnutí
    private var lowThreshold: Float,   // Práh pro vypnutí (obvykle např. 0.7 * highThreshold)
    private var holdTimeMs: Long = 0L  // Jak dlouho musí být signál nad highThreshold, než se zapne
) {
    private var isActive: Boolean = false
    private var triggerStartTime: Long = 0L
    
    /**
     * Zpracuje aktuální hodnotu a čas a vrátí `true`, pokud došlo K NOVÉMU SPUŠTĚNÍ gesta v tomto snímku.
     * Vrátí `false`, pokud se stav nemění, nebo pokud gesto ještě nedosáhlo Hold Time.
     * 
     * @param onTriggered Callback zavolaný ve chvíli, kdy se stav překlopí na ZAPNUTO.
     * @param onReset Callback zavolaný ve chvíli, kdy se stav překlopí na VYPNUTO.
     */
    fun process(currentValue: Float, timestampMs: Long, onTriggered: () -> Unit, onReset: () -> Unit) {
        if (!isActive) {
            // Jsme VYPNUTÍ. Čekáme na překročení HORNÍHO prahu.
            if (currentValue > highThreshold) {
                if (triggerStartTime == 0L) {
                    triggerStartTime = timestampMs
                }
                
                val duration = timestampMs - triggerStartTime
                if (duration >= holdTimeMs) {
                    // Splněn časový limit pro zapnutí
                    isActive = true
                    onTriggered()
                }
            } else {
                // Pokud hodnota klesne pod práh ještě před uplynutím holdTimeMs, resetujeme časovač
                triggerStartTime = 0L
            }
        } else {
            // Jsme ZAPNUTÍ. Čekáme na klesnutí pod SPODNÍ práh.
            if (currentValue < lowThreshold) {
                isActive = false
                triggerStartTime = 0L
                onReset()
            }
        }
    }
    
    /**
     * Možnost dynamicky aktualizovat prahy.
     */
    fun updateThresholds(newHigh: Float, newLow: Float, newHoldTimeMs: Long = holdTimeMs) {
        highThreshold = newHigh
        lowThreshold = newLow
        holdTimeMs = newHoldTimeMs
    }
    
    fun getState(): Boolean = isActive
    
    fun reset() {
        isActive = false
        triggerStartTime = 0L
    }
}
