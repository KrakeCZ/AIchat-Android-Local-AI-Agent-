package com.example.llama.srt

import android.os.PowerManager
import android.os.Process
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * TranscriptProfileOrchestrator â€” Správa profilů přepisu pro SrtEditorActivity (2026).
 *
 * Architektura:
 * - TranscriptProfile = čistý datový kontejner (SOLID SRP)
 * - TranscriptProfileOrchestrator = továrna profilů (stateless, thread-safe)
 * - ThermalGuard = adaptivní správa vláken dle teploty zařízení (Android 15+ API)
 *
 * Thread priority pravidla (ověřeno 04/2026):
 * - THREAD_PRIORITY_AUDIO (-16): AudioRecord reading loop (live recording threads)
 * - THREAD_PRIORITY_DEFAULT (0): ML inference offline batch (SRT_EDITOR)
 * - THREAD_PRIORITY_URGENT_AUDIO (-19): VÝHRADNĚ AAudio/Oboe HW callback (nikdy pro inference!)
 *
 * Dispatcher pravidla (ověřeno 04/2026):
 * - Dispatchers.Default: CPU-bound inference (fixed pool = počet core, žádný context-switch overhead)
 * - Dispatchers.IO: I/O blocking POUZE (síť, disk, SharedPreferences) â€” MAX 64 vláken = cache thrashing!
 */

// ============================================================
// DATA: Přepis profil (immutable, thread-safe data class)
// ============================================================

enum class TranscriptUseCase {
    /** Live agent přepis â€” AudioRecord + kontinuální sliding window */
    LIVE_AGENT,
    /** Diktafon â€” přesný offline přepis v pozadí */
    DICTAPHONE,
    /** SRT Editor â€” offline dávkový přepis souborů */
    SRT_EDITOR
}

/**
 * Profil přepisu â€” zapouzdřuje veškerá nastavení pro jeden kontext použití.
 *
 * @param useCase Kontext použití (LIVE_AGENT, DICTAPHONE, SRT_EDITOR)
 * @param numThreads Počet C++ vláken (whisper.cpp / sherpa OMP pool)
 * @param slidingWindowMs Délka audio kontextu v ms
 * @param enableVAD Aktivovat detekci řeči (VAD) â€” pro offline batch zbytečné
 * @param osThreadPriority Priorita Kotlin vlákna (dědí C++ thread pool!) â€” Process.THREAD_PRIORITY_*
 * @param dispatcher Coroutine dispatcher â€” vždy Dispatchers.Default pro CPU-bound
 * @param thermalGuardEnabled Adaptivní snížení vláken při tepelném throttlingu (Android 15+)
 */
data class TranscriptProfile(
    val useCase: TranscriptUseCase,
    val numThreads: Int,
    val slidingWindowMs: Long,
    val overlap: Int = 2000,
    val enableVAD: Boolean,
    val osThreadPriority: Int,
    val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    val thermalGuardEnabled: Boolean = true,
    // --- Nové konfigurační parametry pro diktafon / přesný přepis ---
    val enablePunctuation: Boolean = false,
    val processingIntervalMs: Long = 500L,
    val draftWordThreshold: Int = 0,
    val vadSilenceThreshold: Double = 50.0,
    val vadSilenceChunks: Int = 10
)

// ============================================================
// THERMAL GUARD: Adaptivní správa vláken dle teploty (Android 15+)
// ============================================================

/**
 * ThermalGuard â€” Proaktivní správa tepelného throttlingu.
 *
 * Použití PowerManager.getThermalHeadroom() (Android 15+, API 31+):
 * - Vrací 0.0â€“1.0 kde 1.0 = těsně před THERMAL_STATUS_SEVERE
 * - Polling max 1x za 5â€“10 sekund (více = NaN response)
 * - Na Android Box s ventilátorem (S24U ~75Â°C vs CoD ~98Â°C) lze vypnout
 *
 * @param powerManager Android PowerManager instance
 */
class ThermalGuard(private val powerManager: PowerManager) {

    /**
     * Aktuální tepelný "headroom" â€” vzdálenost od severe throttlingu.
     * @param forecastSeconds Kolik sekund dopředu předpovědět (5â€“30s)
     * @return 0.0â€“1.0 (1.0 = throttling hrozí), nebo 0.5 pokud API vrací NaN
     */
    fun currentHeadroom(forecastSeconds: Int = 10): Float {
        return try {
            val h = powerManager.getThermalHeadroom(forecastSeconds)
            if (h.isNaN() || h.isInfinite()) 0.5f else h.coerceIn(0.0f, 1.0f)
        } catch (_: Exception) {
            // Starší Android API nemusí getThermalHeadroom() podporovat
            0.5f // Safe default â€” střední výkon
        }
    }

    /**
     * Adaptivní počet vláken dle aktuálního tepelného stavu.
     *
     * Prahové hodnoty:
     * - headroom > 0.6 â†’ plný výkon (zařízení je chladné â€” Android Box s ventilátorem, začátek session)
     * - headroom 0.3â€“0.6 â†’ -1 vlákno (mírné zahřátí â€” typický S24U po 10min)
     * - headroom < 0.3 â†’ poloviční výkon (blízký throttling â€” CoD úroveň 98Â°C)
     *
     * @param requested Požadovaný počet vláken (z profilu)
     * @return Adaptovaný počet vláken (nikdy méně než 1)
     */
    fun adaptThreads(requested: Int): Int {
        val headroom = currentHeadroom()
        return when {
            headroom > 0.6f -> requested                          // Chladné â€” plný výkon
            headroom > 0.3f -> maxOf(1, requested - 1)            // Mírné zahřátí â€” -1 vlákno
            else            -> maxOf(1, requested / 2)            // Horké â€” poloviční výkon
        }
    }
}

// ============================================================
// ORCHESTRÁTOR: Továrna profilů (stateless singleton)
// ============================================================

/**
 * TranscriptProfileOrchestrator â€” Vytváří TranscriptProfile dle kontextu a nastavení.
 *
 * Integrace s SettingsManager:
 * - LIVE_AGENT: čte whisperNThreads / sherpaNThreads (dle aktivního enginu)
 * - DICTAPHONE: čte whisperNThreads / sherpaNThreads - reserve (nechat jádra pro UI)
 * - SRT_EDITOR: přijímá sessionThreads (per-session slider, nezávislý na globálním nastavení)
 *
 * ThermalGuard je volitelný (null = thermal guard vypnutý â€” pro Android Box s ventilátorem).
 */
object TranscriptProfileOrchestrator {

    /**
     * Vytvoří dedikovaný CoroutineDispatcher s vlastním thread poolem a pevnou prioritou.
     *
     * PROČ ne Dispatchers.Default:
     * Process.setThreadPriority() nastavuje prioritu na konkrétní vlákno a ta ZŮSTÁVÁ,
     * i když se vlákno vrátí do sdíleného Dispatchers.Default poolu. Tím by SRT editor
     * kontaminoval prioritu vláken, která pak použije live TranscriptAgent.
     *
     * Toto vytvoří IZOLOVANÝ pool â€” vlákna nikdy neopustí tento pool a priorita je
     * nastavena jednou v ThreadFactory při vzniku vlákna.
     *
     * Životní cyklus: Volající je odpovědný za shutdown() při zániku Activity (onDestroy).
     *
     * @param numThreads Počet vláken v poolu (z profilu)
     * @param osPriority Priorita dle Process.THREAD_PRIORITY_* â€” nastavena při vzniku vlákna
     * @param namePrefix Název vláken pro logcat (např. "srt-editor", "dictaphone")
     * @return CoroutineDispatcher + ExecutorService (pro shutdown) v Pair
     */
    fun dedicatedDispatcher(
        numThreads: Int,
        osPriority: Int,
        namePrefix: String = "transcript"
    ): Pair<CoroutineDispatcher, java.util.concurrent.ExecutorService> {
        var threadIndex = 0
        val executor = Executors.newFixedThreadPool(numThreads) { runnable ->
            Thread(runnable, "$namePrefix-${threadIndex++}").apply {
                isDaemon = true
                // Priorita nastavena JEDNOU při vzniku vlákna â€” vlákno zůstane v tomto poolu
                // Process.setThreadPriority() ovlivňuje jen aktuální vlákno â†’ thread-safe
                try {
                    Process.setThreadPriority(osPriority)
                } catch (_: Exception) {}
            }
        }
        return Pair(executor.asCoroutineDispatcher(), executor)
    }

    /**
     * Vrátí profil pro LIVE_AGENT (live přepis v TranscriptAgent).
     *
     * Live recording loop má URGENT_AUDIO (-19) nastavenou přímo v recordingLoop() â€”
     * tento profil řídí ML inference část (processChunkAsync), která je oddělená.
     *
     * @param whisperNThreads Počet vláken z SettingsManager.whisperNThreads
     * @param sherpaNThreads Počet vláken z SettingsManager.sherpaNThreads
     * @param isWhisperEngine true = whisper.cpp, false = Sherpa-ONNX
     * @param thermalGuard null = thermal guard vypnutý (Android Box, výkonný tablet)
     */
    fun liveAgentProfile(
        whisperNThreads: Int,
        sherpaNThreads: Int,
        isWhisperEngine: Boolean,
        thermalGuard: ThermalGuard? = null
    ): TranscriptProfile {
        val baseThreads = if (isWhisperEngine) whisperNThreads else sherpaNThreads
        val adaptedThreads = thermalGuard?.adaptThreads(baseThreads) ?: baseThreads
        return TranscriptProfile(
            useCase       = TranscriptUseCase.LIVE_AGENT,
            numThreads    = adaptedThreads,
            slidingWindowMs = 2_000L,
            enableVAD     = true,
            // ML inference část â€” DEFAULT, recording loop má svůj URGENT_AUDIO
            osThreadPriority  = Process.THREAD_PRIORITY_DEFAULT,
            dispatcher        = Dispatchers.Default,
            thermalGuardEnabled = thermalGuard != null,
            enablePunctuation = false, // Rychlost nad gramatikou
            processingIntervalMs = 500L, // Rychlé emitování (YouTube style titulky)
            draftWordThreshold = 2
        )
    }

    /**
     * Vrátí profil pro DICTAPHONE (přesný offline přepis na pozadí).
     *
     * @param whisperNThreads SettingsManager.whisperNThreads
     * @param sherpaNThreads SettingsManager.sherpaNThreads
     * @param isWhisperEngine Aktivní engine
     * @param thermalGuard null = thermal guard vypnutý
     */
    fun dictaphoneProfile(
        whisperNThreads: Int,
        sherpaNThreads: Int,
        isWhisperEngine: Boolean,
        thermalGuard: ThermalGuard? = null,
        uiVadThreshold: Double = 300.0,
        uiVadSilenceMs: Long = 850L
    ): TranscriptProfile {
        val baseThreads = if (isWhisperEngine) whisperNThreads else sherpaNThreads
        val reservedThreads = maxOf(1, baseThreads - 1)
        val adaptedThreads = thermalGuard?.adaptThreads(reservedThreads) ?: reservedThreads
        return TranscriptProfile(
            useCase        = TranscriptUseCase.DICTAPHONE,
            numThreads     = adaptedThreads,
            slidingWindowMs  = 60_000L, // Dlouhé okno = VAD bude mít plnou kontrolu, předejdeme řezání uprostřed slova
            enableVAD      = true,
            osThreadPriority   = Process.THREAD_PRIORITY_BACKGROUND + 2, // 12
            dispatcher         = Dispatchers.Default,
            thermalGuardEnabled = thermalGuard != null,
            enablePunctuation = true, // Přesnost (pokud AI podporuje)
            processingIntervalMs = 1500L, // Pomalejší chunking
            draftWordThreshold = 5,
            vadSilenceThreshold = uiVadThreshold,
            vadSilenceChunks = maxOf(2, (uiVadSilenceMs / 85).toInt())
        )
    }

    /**
     * Vrátí profil pro SRT_EDITOR (offline dávkový přepis ze souboru).
     *
     * DEDICATED DISPATCHER: Vytvoří izolovaný thread pool s DEFAULT prioritou.
     * Vlákna nikdy neopustí tento pool â†’ žádná kontaminace Dispatchers.Default.
     * Volající (SrtEditorActivity) MUSÍ zavolat executor.shutdown() v onDestroy()!
     *
     * Per-session slider (sessionThreads) â€” lokální v SrtEditorActivity,
     * nezávislý na globálním nastavení agenta.
     *
     * @param sessionThreads Per-session počet vláken (slider v SrtEditorActivity, 1â€“8)
     * @param thermalGuard null = thermal guard vypnutý (pro výkonné zařízení s chlazením)
     * @return Pair<TranscriptProfile, ExecutorService> â€” ExecutorService pro shutdown()
     */
    fun srtEditorProfile(
        sessionThreads: Int,
        thermalGuard: ThermalGuard? = null
    ): Pair<TranscriptProfile, java.util.concurrent.ExecutorService> {
        val adaptedThreads = thermalGuard?.adaptThreads(sessionThreads) ?: sessionThreads

        // Dedicated dispatcher â€” izolovaný pool, priorita DEFAULT (-0), neovlivní live agent vlákna
        val (dispatcher, executor) = dedicatedDispatcher(
            numThreads  = adaptedThreads,
            osPriority  = Process.THREAD_PRIORITY_DEFAULT,
            namePrefix  = "srt-editor"
        )

        val profile = TranscriptProfile(
            useCase        = TranscriptUseCase.SRT_EDITOR,
            numThreads     = adaptedThreads,
            slidingWindowMs  = 30_000L,
            enableVAD      = false,  // Offline batch â€” soubor je kompletní, VAD zbytečný
            osThreadPriority   = Process.THREAD_PRIORITY_DEFAULT,
            dispatcher         = dispatcher,
            thermalGuardEnabled = thermalGuard != null,
            enablePunctuation = true,
            processingIntervalMs = 2000L,
            draftWordThreshold = 0
        )
        return Pair(profile, executor)
    }
}

