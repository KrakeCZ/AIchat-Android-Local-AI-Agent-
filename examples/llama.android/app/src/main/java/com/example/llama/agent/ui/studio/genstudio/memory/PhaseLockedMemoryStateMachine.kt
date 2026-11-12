package com.example.llama.agent.ui.studio.genstudio.memory

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.PerceptionEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Phase-Locked Memory State Machine:
 * Zajišťuje absolutní prevenci pádů z důvodu nedostatku paměti (OOM / SIGKILL z Android LMK).
 * Koordinuje exkluzivní životní cyklus mezi VLM (Qwen3-VL-8B mmproj), Text Enkodérem,
 * difúzním DiT jádrem a 3D-VAE dekodérem.
 */
class PhaseLockedMemoryStateMachine(
    private val context: Context,
    private val perceptionEngine: PerceptionEngine? = null
) {
    companion object {
        private const val TAG = "MemoryStateMachine"
    }

    private val executionLock = Mutex()

    enum class Phase {
        IDLE,
        PREPARING_MEMORY,
        TEXT_CONDITIONING,
        DENOISING_BACKBONE,
        VAE_DECODING,
        RESTORING_PERCEPTION
    }

    private var currentPhase: Phase = Phase.IDLE

    suspend fun <T> executeSequentialPipeline(
        taskName: String,
        block: suspend (transition: suspend (Phase) -> Unit) -> T
    ): Result<T> = executionLock.withLock {
        Log.i(TAG, "🔒 Zajišťuji exkluzivní paměťovou alokaci pro úlohu: $taskName")

        return try {
            transitionTo(Phase.PREPARING_MEMORY)
            
            // 1. Krok: Uspání percepce a uvolnění TFLite/GGUF bufferů
            try {
                perceptionEngine?.stop()
                perceptionEngine?.resetTrackers()
            } catch (e: Exception) {
                Log.w(TAG, "Varování při uspávání percepce: ${e.message}")
            }

            // 2. Krok: Explicitní Garbage Collection
            System.gc()

            // 3. Krok: Běh samotné sekvenční pipeline
            val result = block { nextPhase ->
                transitionTo(nextPhase)
            }

            Result.success(result)
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "⏹️ Paměťová pipeline korektně přerušena/zrušena ($currentPhase).")
            Result.failure(e)
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Selhání v paměťové pipeline ($currentPhase): ${t.message}", t)
            Result.failure(t)
        } finally {
            transitionTo(Phase.RESTORING_PERCEPTION)
            try {
                perceptionEngine?.reloadObjectDetector()
                Log.i(TAG, "✅ Percepční engine obnoven do aktivního stavu.")
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při obnově percepce: ${e.message}", e)
            }
            transitionTo(Phase.IDLE)
        }
    }

    private fun transitionTo(newPhase: Phase) {
        Log.d(TAG, "Fázový přechod: $currentPhase ➔ $newPhase")
        currentPhase = newPhase
    }
}
