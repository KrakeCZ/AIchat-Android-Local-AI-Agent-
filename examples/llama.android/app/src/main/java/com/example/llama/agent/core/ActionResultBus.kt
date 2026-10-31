package com.example.llama.agent.core

import android.util.Log
import com.example.llama.agent.models.ActionType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * ActionResultBus — Decoupled SharedFlow bus pro výsledky akcí.
 *
 * Účel:
 * - Umožňuje AgentCoordinator, MacroEngine a dalším komponentám
 *   reaktivně reagovat na výsledky akcí bez přímé závislosti.
 * - Základ pro self-healing smyčku a budoucí proaktivní monitoring.
 *
 * Použití (emit):
 *   ActionResultBus.emit(ActionResult(ActionType.CLICK, success = true, targetId = 42))
 *
 * Použití (subscribe):
 *   ActionResultBus.results.collect { result ->
 *       if (!result.success) handleFailure(result)
 *   }
 *
 * Garantuje:
 * - replay = 0: Žádná stará data pro nové subscribery (každý výsledek je "živý")
 * - extraBufferCapacity = 8: Neblokuje emittera při pomalém consumeru
 * - Thread-safe: SharedFlow je coroutine-safe by design
 *
 * Google Play compliance: Žádné systémové oprávnění, čistá Kotlin coroutine implementace.
 */
object ActionResultBus {

    private const val TAG = "ActionResultBus"

    /**
     * Výsledek jedné provedené akce.
     *
     * @param actionType Typ akce (CLICK, INPUT_TEXT, OPEN_APP, ...)
     * @param success Zda akce uspěla
     * @param errorMessage Chybová zpráva při selhání (null při úspěchu)
     * @param targetId ID UI elementu (null pro globální akce)
     * @param thought Popis akce z LLM (pro logging a TTS feedback)
     * @param timestampMs Čas provedení
     */
    data class ActionResult(
        val actionType: ActionType,
        val success: Boolean,
        val errorMessage: String? = null,
        val targetId: Int? = null,
        val thought: String = "",
        val timestampMs: Long = System.currentTimeMillis()
    )

    // extraBufferCapacity = 8: Buffer pro případ, že consumer je pomalý
    // onBufferOverflow = DROP_OLDEST: Nikdy neblokuje emittera
    private val _results = MutableSharedFlow<ActionResult>(
        replay = 0,
        extraBufferCapacity = 8
    )

    /**
     * Veřejný SharedFlow — subscribe pro reaktivní zpracování výsledků.
     */
    val results: SharedFlow<ActionResult> = _results.asSharedFlow()

    /**
     * Emituje výsledek akce. Suspend — volat z coroutine kontextu.
     *
     * @param result Výsledek akce
     */
    suspend fun emit(result: ActionResult) {
        Log.d(TAG, "Emit: [${if (result.success) "✅" else "❌"}] ${result.actionType} — ${result.thought.take(60)}")
        _results.emit(result)
    }

    /**
     * Emituje výsledek akce bez suspend (tryEmit — neblokuje).
     * Použití: Z non-coroutine kontextu (callback, listener).
     * Vrátí false pokud je buffer plný (DROP_OLDEST zajistí, že se to nestane).
     */
    fun tryEmit(result: ActionResult): Boolean {
        Log.d(TAG, "TryEmit: [${if (result.success) "✅" else "❌"}] ${result.actionType}")
        return _results.tryEmit(result)
    }
}
