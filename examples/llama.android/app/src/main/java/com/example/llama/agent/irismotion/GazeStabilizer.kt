package com.example.llama.agent.irismotion

import kotlin.math.PI
import kotlin.math.abs

/**
 * GazeStabilizer — One Euro Filter pro stabilizaci pohybu oka ve 3D prostoru.
 *
 * Eliminuje mikrosakadické pohyby při zachování nízké latence (<20ms @ 48Hz). Adaptivní: silné
 * vyhlazení při klidu, minimální při rychlém pohybu.
 *
 * Algoritmus: Casiez et al. "1€ Filter" (CHI 2012) https://dl.acm.org/doi/10.1145/2207676.2208639
 *
 * Konfigurace pro S24 Ultra front camera (48Hz):
 * - MIN_CUTOFF = 0.001f : silné vyhlazení při klidu → stabilní kurzor
 * - BETA_BASE = 15.0f : rychlá reakce při pohybu → bez lag
 * - D_CUTOFF = 1.0f : derivace (rychlost) frekvence vzorkování
 *
 * 3D ROZŠÍŘENÍ: Filtr nyní pracuje ve třech dimenzích: X, Y = normalizovaná pozice gaze (0.0–1.0) Z
 * = normalizovaná Z-osa = headYaw nebo headPitch (radiány, rozsah přibližně -π..+π)
 *
 * Z-osa je filtrovaná odděleně s nižší citlivostí (∵ headYaw se mění pomaleji než gaze), a slouží
 * jako dynamická váha pro přesnost X/Y mapování.
 */
class GazeStabilizer {

    // One Euro Filter parametry — odemčené pro runtime konfiguraci z SettingsManager.
    // Výchozí betaBase=2.5 (dříve 15.0): sníženo dle Casiez CHI 2012 — 15.0 interpretuje
    // mikrosakády jako záměrný pohyb. Rozsah 0.001–20.0.
    // Výchozí minCutoff=0.05 (dříve 0.001): zvýšeno — silnější stabilizace kurzoru v klidu.
    // Rozsah 0.001–1.0 Hz.
    var betaBase = 2.5f
        private set
    private var betaScaled = betaBase // Upravuje se přes setSensitivity()
    var minCutoff = 0.05f
        private set
    var dCutoff = 1.0f

    // Z-osa: headYaw/headPitch se mění pomaleji než gaze → nižší beta pro silnější smoothing
    private val BETA_Z = 3.0f
    private val MIN_CUTOFF_Z = 0.001f

    // Stav filtru (X osa)
    private var xHat: Float = 0f
    private var dxHat: Float = 0f

    // Stav filtru (Y osa)
    private var yHat: Float = 0f
    private var dyHat: Float = 0f

    // Stav filtru (Z osa — headYaw nebo headPitch jako 3D depth proxy)
    private var zHat: Float = 0f
    private var dzHat: Float = 0f

    private var prevTimestamp: Long = 0L
    private var initialized: Boolean = false

    /**
     * Filtruje surové gaze souřadnice z MediaPipe ve 3D prostoru.
     *
     * @param rawX Gaze X souřadnice z iris eye-socket mapping [0.0–1.0]
     * @param rawY Gaze Y souřadnice z iris eye-socket mapping [0.0–1.0]
     * @param timestamp Aktuální čas v ms (GazeData.timestamp)
     * @param rawZ Z-ová osa — headYaw kombinovaný s headPitch jako přibližný gaze depth.
     * ```
     *                  Použij: (headYaw + headPitch) / 2f nebo jen headYaw.
     *                  Výchozí = 0f (2D kompatibilita bez Z-korekce).
     * @return
     * ```
     * Triple(filteredX, filteredY, filteredZ) — stabilizované souřadnice ve 3D
     */
    fun filter(
            rawX: Float,
            rawY: Float,
            timestamp: Long,
            rawZ: Float = 0f
    ): Triple<Float, Float, Float> {
        // První frame — inicializace bez filtrace (FIX: prevTimestamp nastaven explicitně)
        if (!initialized) {
            xHat = rawX
            yHat = rawY
            zHat = rawZ
            prevTimestamp = timestamp
            initialized = true
            return Triple(rawX, rawY, rawZ)
        }

        // Delta time v sekundách — ochrana proti nulové hodnotě
        val dtMs = (timestamp - prevTimestamp).coerceAtLeast(1L)
        val dt = dtMs / 1000.0f

        // === X OSA ===
        val dxRaw = (rawX - xHat) / dt
        val alphaDx = smoothingAlpha(dt, dCutoff)
        dxHat = alphaDx * dxRaw + (1.0f - alphaDx) * dxHat

        val cutoffX = minCutoff + betaScaled * abs(dxHat)
        val alphaX = smoothingAlpha(dt, cutoffX)
        xHat = alphaX * rawX + (1.0f - alphaX) * xHat

        // === Y OSA ===
        val dyRaw = (rawY - yHat) / dt
        val alphaDy = smoothingAlpha(dt, dCutoff)
        dyHat = alphaDy * dyRaw + (1.0f - alphaDy) * dyHat

        val cutoffY = minCutoff + betaScaled * abs(dyHat)
        val alphaY = smoothingAlpha(dt, cutoffY)
        yHat = alphaY * rawY + (1.0f - alphaY) * yHat

        // === Z OSA (head pose depth proxy — filtrovaná s nižší dynamikou) ===
        // headYaw/headPitch se mění pomaleji než gaze sakkády → nižší BETA_Z
        val dzRaw = (rawZ - zHat) / dt
        val alphaDz = smoothingAlpha(dt, dCutoff)
        dzHat = alphaDz * dzRaw + (1.0f - alphaDz) * dzHat

        val cutoffZ = MIN_CUTOFF_Z + BETA_Z * abs(dzHat)
        val alphaZ = smoothingAlpha(dt, cutoffZ)
        zHat = alphaZ * rawZ + (1.0f - alphaZ) * zHat

        prevTimestamp = timestamp
        return Triple(xHat, yHat, zHat)
    }

    /**
     * Výpočet smoothing faktoru α pro One Euro Filter. α = (2π·f·dt) / (2π·f·dt + 1)
     *
     * @param dt Delta time v sekundách
     * @param cutoff Cutoff frekvence v Hz
     * @return Smoothing factor α ∈ (0, 1)
     */
    private fun smoothingAlpha(dt: Float, cutoff: Float): Float {
        val r = (2.0 * PI * cutoff * dt).toFloat()
        return r / (r + 1.0f)
    }

    /** Reset stavu filtru — volat při přerušení tracking (zakrytí kamery, výpadek). */
    fun reset() {
        initialized = false
        xHat = 0f
        yHat = 0f
        zHat = 0f
        dxHat = 0f
        dyHat = 0f
        dzHat = 0f
        prevTimestamp = 0L
    }

    /**
     * Nastaví citlivost filtru přes škálování BETA parametru One Euro Filteru.
     *
     * @param multiplier Citlivostní násobitel (0.5–3.0). 1.0 = výchozí.
     * ```
     *                   < 1.0 → silnější smoothing (stabilnější kurzor, pomalejší reakce)
     *                   > 1.0 → rychlejší sledování pohybu (vyšší citlivost, méně smoothing)
     * ```
     */
    fun setSensitivity(multiplier: Float) {
        betaScaled = betaBase * multiplier.coerceIn(0.1f, 5.0f)
    }

    /**
     * Aktualizuje základní filtrační parametry za runtime (z SettingsManager sliderů). BetaScaled
     * se přepočítá zachováním aktuálního poměru sensitivity multiplier.
     *
     * @param beta Nová hodnota betaBase (0.001–20.0)
     * @param cutoff Nová hodnota minCutoff (0.001–1.0 Hz)
     */
    fun updateFilterParams(beta: Float, cutoff: Float) {
        // Zachování sensitivity multiplieru: betaScaled/betaBase = multiplier
        val currentMultiplier = if (betaBase > 0.0001f) betaScaled / betaBase else 1.0f
        betaBase = beta.coerceIn(0.001f, 20.0f)
        betaScaled = betaBase * currentMultiplier
        minCutoff = cutoff.coerceIn(0.001f, 1.0f)
    }
}
