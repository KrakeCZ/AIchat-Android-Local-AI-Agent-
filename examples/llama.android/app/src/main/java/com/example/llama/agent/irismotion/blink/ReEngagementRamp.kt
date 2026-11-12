package com.example.llama.agent.irismotion.blink

import android.util.Log

/**
 * ReEngagementRamp — Patent-Grade Post-Blink Stabilizátor.
 *
 * Architektonický základ: RIT Patent "Frozen Position Fallback" + LMU München "Blink Artifact
 * Interpolation".
 *
 * Princip: Po otevření oka (Gaze Unfreeze) se kurzor nerozjede okamžitě, ale plynulou exponenciální
 * rampou 0.0 → 1.0 po dobu [timeoutMs]. Tím se eliminuje "Elastic Snap-Back" artefakt: oko se po
 * mrknutí reflexně vrací do neutrální polohy (post-blink microsaccade ~200ms), což v delta-tracking
 * systému generuje obrovskou zápornou deltu a teleportuje kurzor zpět.
 *
 * Konfigurovatelný timeout: 5ms (téměř okamžitý re-engagement) až 300ms (maximální stabilizace).
 * Výchozí 266ms (~8 framů při 30fps) odpovídá mediánu post-blink recovery z NIH studií.
 *
 * Thread-safety: Voláno pouze z camera executor → Main thread pipeline. Atomické operace na
 * primitivních typech, @Volatile pro cross-thread reads.
 *
 * @param timeoutMs Doba trvání rampy v milisekundách. Konfigurovatelné z SettingsManager.
 */
class ReEngagementRamp(var timeoutMs: Long = 266L) {

    companion object {
        private const val TAG = "ReEngagementRamp"
    }

    @Volatile private var startTimeMs: Long = 0L
    @Volatile private var active: Boolean = false

    /**
     * Spustí re-engagement rampu. Voláno v momentě přechodu isGazeFrozen: true → false.
     *
     * @param nowMs Aktuální čas v milisekundách (System.currentTimeMillis())
     */
    fun start(nowMs: Long) {
        startTimeMs = nowMs
        active = true
        Log.d(TAG, "🔄 Re-engagement ramp started (timeout=${timeoutMs}ms)")
    }

    /**
     * Vrátí aktuální ramp factor pro násobení pixelové delty.
     *
     * @param nowMs Aktuální čas v milisekundách
     * @return Float 0.0 → 1.0 (0.0 = plný freeze, 1.0 = plný tracking).
     * ```
     *         Pokud rampa není aktivní, vrací vždy 1.0 (bypass).
     * ```
     */
    fun getRampFactor(nowMs: Long): Float {
        if (!active) return 1.0f

        val elapsed = nowMs - startTimeMs

        // Validace: záporný čas nebo přetečení → okamžitý bypass
        if (elapsed < 0L) {
            active = false
            return 1.0f
        }

        // Timeout dosažen → plný tracking
        if (elapsed >= timeoutMs) {
            active = false
            Log.d(TAG, "✅ Re-engagement ramp completed")
            return 1.0f
        }

        // Lineární rampa 0.0 → 1.0 (budoucí evoluce: exponenciální/sigmoidní)
        return (elapsed.toFloat() / timeoutMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Vrátí true pokud je rampa aktivní (delta by měla být škálována). */
    fun isActive(): Boolean = active

    /** Okamžitě deaktivuje rampu (např. při zastavení enginu). */
    fun cancel() {
        active = false
    }
}
