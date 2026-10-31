package com.example.llama

import android.util.Log
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.StudioBusyEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * ========================================================================
 * AudioStudioEngineGate — Exkluzivní přístup k audio HW zdrojům
 * ========================================================================
 *
 * Singleton mutex (AtomicBoolean) který zajišťuje, že STT/TTS enginy
 * (WhisperJni, Sherpa OfflineRecognizer) a AudioRecord jsou vždy
 * využívány jen JEDNÍM konzumentem — buď Audio Studiem, nebo AgentTranscriptem.
 *
 * Principy:
 * - acquire(): compareAndSet(false, true) — atomicky a bez deadlocku
 * - release(): suspend s 500ms debounce (C++ chunk completion guard)
 * - StudioBusyEvent: emitován přes PerceptionEventBus → FloatingAgentButton
 *   přepne do STUDIO_BUSY stavu (zešedne, isEnabled = false)
 *
 * Lifecycle:
 * - AudioStudioActivity.onCreate() → acquire()
 * - AudioStudioActivity.onDestroy() → lifecycleScope.launch { release() }
 * - TranscriptAgent.start() → if (isStudioActive) return
 */
object AudioStudioEngineGate {

    private const val TAG = "AudioStudioEngineGate"

    /**
     * Priorita vlákna při zpracování — user-volitelná per-session.
     *
     * URGENT(-19): Max CPU freq, vlákno na Big/Prime jádrech.
     *   ✅ Live audio callback, S24U/výkonné HW
     *   ⚠️  Na slabším HW (SD660, 4-core) může způsobit UI lag + heat
     *
     * DEFAULT(0): Standardní priorita. CPU governor reaguje normálně.
     *   ✅ Doporučeno pro batch přepis, většina HW
     *
     * BACKGROUND(10): Nízká priorita. CPU dostane ostatní procesy přednost.
     *   ✅ Přepis na pozadí, šetří baterii, uživatel nečeká
     */
    enum class ThreadPriorityMode(val osValue: Int, val label: String, val warning: String?) {
        URGENT   (-19, "🔥 Urgentní",  "⚠️ Vhodné pro výkonné HW (S24U+). Na slabším HW může způsobit lag UI."),
        DEFAULT  (  0, "⚡ Výchozí",   null),
        BACKGROUND(10, "🌙 Pozadí",    null)
    }

    /**
     * Popis aktivní úlohy Audio Studia.
     * Slouží pro informativní text v agent notifikaci a FAB tooltip.
     */
    enum class StudioTask(val label: String) {
        NONE("Volno"),
        DICTAPHONE("🎙️ Diktafon nahrává"),
        TRANSCRIBING("📝 Přepis audio/video"),
        CLONING_VOICE("🎭 Generování klonu hlasu"),
        TTS_TEST("🔊 TTS Test")
    }


    private val _isStudioActive = AtomicBoolean(false)

    /** Aktuálně prováděná úloha studia. Volatile pro viditelnost mezi vlákny. */
    @Volatile
    var currentTask: StudioTask = StudioTask.NONE
        private set

    /** Aktuálně nastavená priorita vlákna studia. */
    @Volatile
    var currentPriority: ThreadPriorityMode = ThreadPriorityMode.DEFAULT
        private set

    /** True = Audio Studio je aktivní a vlastní exkluzivní přístup k audio HW. */
    val isStudioActive: Boolean get() = _isStudioActive.get()

    /**
     * Pokusí se získat exkluzivitu.
     * @param task     Úloha která bude spuštěna (pro notifikaci/FAB).
     * @param priority Priorita vlákna — nastaví Process.setThreadPriority() na VOLAJÍCÍM vlákně.
     * @return true pokud bylo acquire úspěšné, false pokud jiný konsument drží zámek.
     */
    fun acquire(task: StudioTask = StudioTask.DICTAPHONE,
                priority: ThreadPriorityMode = ThreadPriorityMode.DEFAULT): Boolean {
        val acquired = _isStudioActive.compareAndSet(false, true)
        if (acquired) {
            currentTask = task
            currentPriority = priority
            // Priorita se již nenastavuje na UI vlákně!
            // Engine si zavolá applyCurrentPriorityToThisThread() na svém pracovním vlákně.
            Log.i(TAG, "🔒 AudioStudioEngineGate ACQUIRED — task: ${task.label}, target_priority: ${priority.label}")
            PerceptionEventBus.tryEmit(StudioBusyEvent(task))
        } else {
            Log.w(TAG, "⚠️ AudioStudioEngineGate ACQUIRE FAILED — již drží: ${currentTask.label}")
        }
        return acquired
    }

    /**
     * Bezpečně aplikuje vybranou prioritu (včetně URGENT) na AKTUÁLNÍ pracovní vlákno (engine).
     * Nesmí být voláno na UI vlákně. Voláno přímo z engine coroutine/threadu.
     */
    fun applyCurrentPriorityToThisThread() {
        if (currentPriority != ThreadPriorityMode.DEFAULT) {
            try {
                android.os.Process.setThreadPriority(currentPriority.osValue)
                Log.i(TAG, "⚙️ Priorita aplikována na engine vlákno: ${currentPriority.label}")
            } catch (e: Exception) {
                Log.e(TAG, "Nelze aplikovat prioritu na engine vlákno", e)
            }
        }
    }

    /**
     * Aktualizuje aktivní úlohu bez uvolnění zámku (Studio přechází mezi úlohami).
     */
    fun updateTask(task: StudioTask) {
        if (_isStudioActive.get()) {
            currentTask = task
            Log.d(TAG, "🔄 Studio task updated: ${task.label}")
            PerceptionEventBus.tryEmit(StudioBusyEvent(task))
        }
    }

    /**
     * Uvolní exkluzivitu s 500ms debounce.
     *
     * Debounce chrání C++ vrstvu (WhisperJni / Sherpa OfflineRecognizer):
     * pokud engine zpracovává poslední chunk (30s audio → ~2s inference),
     * TranscriptAgent musí počkat než se C++ kontext bezpečně uvolní,
     * aby nedošlo k SIGSEGV při souběžném WhisperJni.init() a release().
     *
     * KRITICKÉ: Musí být voláno v coroutine scope (suspend).
     * Doporučeno: lifecycleScope.launch { AudioStudioEngineGate.release() }
     */
    suspend fun release() {
        if (!_isStudioActive.get()) {
            Log.w(TAG, "⚠️ release() voláno na již uvolněném Gate — ignoruji")
            return
        }
        Log.i(TAG, "🔓 AudioStudioEngineGate releasing (500ms C++ debounce)...")
        currentTask = StudioTask.NONE
        currentPriority = ThreadPriorityMode.DEFAULT
        PerceptionEventBus.tryEmit(StudioBusyEvent(StudioTask.NONE)) // FAB okamžitě odšedí
        // Priorita vlákna se nevrací zde, protože suspend funkce může běžet na libovolném poolu
        delay(500L) // C++ bounce time guard
        _isStudioActive.set(false)
        Log.i(TAG, "✅ AudioStudioEngineGate RELEASED")
    }
}
