package com.example.llama.agent.perception

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.Equalizer
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * ========================================================================
 * DspFilterChain — Profesionální Hardware DSP Pipeline (2026)
 * ========================================================================
 *
 * Modulární správce hardwarových audio efektů Android.
 * Připojuje se na AudioRecord session a řídí HW DSP procesory:
 *
 * Pipeline řazení (dle doporučení Google/Vanderbilt Audio Research):
 *   1. AEC (Acoustic Echo Canceler) — odstranění echa z reproduktoru
 *   2. NS  (Noise Suppressor) — potlačení šumu prostředí
 *   3. AGC (Automatic Gain Control) — normalizace hlasitosti
 *
 * Všechny efekty využívají HW akceleraci (Qualcomm Hexagon DSP / Samsung ABOX)
 * pokud je zařízení podporuje. Pokud ne, Android poskytuje SW fallback.
 *
 * DŮLEŽITÉ:
 *   - Efekty se napojují na audioSessionId z AudioRecord
 *   - Musí být vytvořeny PO inicializaci AudioRecord
 *   - Musí být uvolněny PO zastavení AudioRecord
 *   - Nelze použít s AudioSource.UNPROCESSED (záměrně obchází DSP)
 *
 * Offline-First: Vše běží na zařízení v HW, nulová latence pro cloud.
 */
class DspFilterChain {

    companion object {
        private const val TAG = "DspFilterChain"
    }

    /** Konfigurace DSP filtrů z UI přepínačů. */
    data class DspConfig(
        val aecEnabled: Boolean = false,
        val nsEnabled: Boolean = false,
        val agcEnabled: Boolean = false,
        val eqEnabled: Boolean = false
    ) {
        /** Bypass mód — všechny filtry vypnuté (RAW signál). */
        val isRawBypass: Boolean get() = !aecEnabled && !nsEnabled && !agcEnabled

        override fun toString(): String = buildString {
            append("DSP[")
            if (aecEnabled) append("AEC ")
            if (nsEnabled) append("NS ")
            if (agcEnabled) append("AGC ")
            if (eqEnabled) append("EQ ")
            if (isRawBypass) append("RAW_BYPASS")
            append("]")
        }
    }

    /** Stav dostupnosti HW efektů na zařízení. */
    data class DspAvailability(
        val aecAvailable: Boolean,
        val nsAvailable: Boolean,
        val agcAvailable: Boolean
    ) {
        override fun toString(): String = buildString {
            append("HW DSP: ")
            append("AEC=${if (aecAvailable) "✅" else "❌"} ")
            append("NS=${if (nsAvailable) "✅" else "❌"} ")
            append("AGC=${if (agcAvailable) "✅" else "❌"}")
        }
    }

    // Efektové instance — nullable (ne každé zařízení podporuje všechny efekty)
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    var equalizer: Equalizer? = null
        private set

    @Volatile
    private var isAttached = false

    /**
     * Zjistí dostupnost HW DSP efektů na aktuálním zařízení.
     * Volat KDYKOLI — nevyžaduje AudioRecord.
     */
    fun queryAvailability(): DspAvailability = DspAvailability(
        aecAvailable = AcousticEchoCanceler.isAvailable(),
        nsAvailable = NoiseSuppressor.isAvailable(),
        agcAvailable = AutomaticGainControl.isAvailable()
    )

    /**
     * Připojí DSP filtry na AudioRecord session.
     *
     * MUSÍ být voláno PO [AudioRecord] inicializaci (STATE_INITIALIZED)
     * a PŘED [AudioRecord.startRecording()].
     *
     * @param audioRecord Inicializovaný AudioRecord (ne UNPROCESSED source)
     * @param config Konfigurace DSP filtrů z UI přepínačů
     * @return true pokud alespoň jeden efekt byl úspěšně připojen
     */
    fun attach(audioRecord: AudioRecord, config: DspConfig): Boolean {
        if (isAttached) {
            Log.w(TAG, "⚠️ DSP chain již připojen — nejdřív volej release()")
            return false
        }

        if (config.isRawBypass) {
            Log.i(TAG, "🔇 DSP Bypass mód — žádné efekty nebudou připojeny")
            return true
        }

        val sessionId = audioRecord.audioSessionId
        if (sessionId == 0) {
            Log.e(TAG, "❌ AudioRecord nemá platné audioSessionId!")
            return false
        }

        Log.i(TAG, "🔧 Připojuji DSP na session $sessionId: $config")
        val availability = queryAvailability()
        Log.i(TAG, "🔍 $availability")

        var anyAttached = false

        // ── 1. AEC — Acoustic Echo Canceler ──
        if (config.aecEnabled) {
            if (availability.aecAvailable) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(sessionId)?.also {
                        it.enabled = true
                        anyAttached = true
                        Log.i(TAG, "✅ AEC připojen a aktivní (session=$sessionId)")
                    }
                    if (echoCanceler == null) {
                        Log.w(TAG, "⚠️ AEC.create() vrátil null — HW nepodporuje efekt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ AEC inicializace selhala: ${e.message}")
                }
            } else {
                Log.w(TAG, "⚠️ AEC není dostupný na tomto zařízení")
            }
        }

        // ── 2. NS — Noise Suppressor ──
        if (config.nsEnabled) {
            if (availability.nsAvailable) {
                try {
                    noiseSuppressor = NoiseSuppressor.create(sessionId)?.also {
                        it.enabled = true
                        anyAttached = true
                        Log.i(TAG, "✅ NS připojen a aktivní (session=$sessionId)")
                    }
                    if (noiseSuppressor == null) {
                        Log.w(TAG, "⚠️ NS.create() vrátil null — HW nepodporuje efekt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ NS inicializace selhala: ${e.message}")
                }
            } else {
                Log.w(TAG, "⚠️ NS není dostupný na tomto zařízení")
            }
        }

        // ── 3. AGC — Automatic Gain Control ──
        if (config.agcEnabled) {
            if (availability.agcAvailable) {
                try {
                    gainControl = AutomaticGainControl.create(sessionId)?.also {
                        it.enabled = true
                        anyAttached = true
                        Log.i(TAG, "✅ AGC připojen a aktivní (session=$sessionId)")
                    }
                    if (gainControl == null) {
                        Log.w(TAG, "⚠️ AGC.create() vrátil null — HW nepodporuje efekt")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ AGC inicializace selhala: ${e.message}")
                }
            } else {
                Log.w(TAG, "⚠️ AGC není dostupný na tomto zařízení")
            }
        }

        // 🎛️ 4. EQ — Parametric Equalizer 🎛️
        if (config.eqEnabled) {
            try {
                equalizer = Equalizer(0, sessionId).also {
                    it.enabled = true
                    // Obnova uložených hodnot (pokud existují)
                    val savedBands = com.example.llama.agent.perception.TranscriptAgent.eqBandLevels
                    for (i in 0 until Math.min(it.numberOfBands.toInt(), savedBands.size)) {
                        try {
                            it.setBandLevel(i.toShort(), savedBands[i])
                        } catch (e: Exception) { }
                    }
                    anyAttached = true
                    Log.i(TAG, "🎛 EQ připojen a aktivní (session=$sessionId), obnoveny pásma")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ EQ inicializace selhala: ${e.message}")
            }
        }

        isAttached = anyAttached
        Log.i(TAG, "🏁 DSP chain ${if (anyAttached) "aktivní" else "neaktivní"} (${getActiveEffectsLog()})")
        return anyAttached
    }

    /**
     * Dynamicky aktualizuje stav efektů za běhu (bez release/attach).
     * Uživatel může přepínat filtry za běhu nahrávání.
     */
    fun updateConfig(config: DspConfig) {
        echoCanceler?.let {
            try {
                it.enabled = config.aecEnabled
                Log.d(TAG, "AEC → ${config.aecEnabled}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ AEC update selhalo: ${e.message}")
            }
        }
        noiseSuppressor?.let {
            try {
                it.enabled = config.nsEnabled
                Log.d(TAG, "NS → ${config.nsEnabled}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ NS update selhalo: ${e.message}")
            }
        }
        gainControl?.let {
            try {
                it.enabled = config.agcEnabled
                Log.d(TAG, "AGC → ${config.agcEnabled}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ AGC update selhalo: ${e.message}")
            }
        }
        equalizer?.let {
            try {
                it.enabled = config.eqEnabled
                Log.d(TAG, "EQ → ${config.eqEnabled}")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ EQ update selhalo: ${e.message}")
            }
        }
    }

    /** Uvolní všechny nativní resources. Volat v finally bloku po AudioRecord.release(). */
    fun release() {
        try { echoCanceler?.release() } catch (_: Exception) {}
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        try { gainControl?.release() } catch (_: Exception) {}
        try { equalizer?.release() } catch (_: Exception) {}

        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        equalizer = null
        isAttached = false

        Log.i(TAG, "🧹 DSP chain uvolněn")
    }

    /** Log aktivních efektů. */
    private fun getActiveEffectsLog(): String = buildString {
        echoCanceler?.let { if (it.enabled) append("AEC ") }
        noiseSuppressor?.let { if (it.enabled) append("NS ") }
        gainControl?.let { if (it.enabled) append("AGC ") }
        equalizer?.let { if (it.enabled) append("EQ ") }
        if (isEmpty()) append("žádné")
    }
}
