package com.example.llama.agent.core

import android.util.Log
import com.example.llama.agent.models.ActionType

/**
 * CommandContextTracker — Sleduje historii kroků v rámci jednoho uživatelského příkazu.
 *
 * Účel:
 * - Obohacuje LLM prompt o kontext provedených kroků (zabraňuje halucinacím)
 * - Sleduje selhání pro self-healing logiku
 * - Idempotentní reset mezi příkazy
 *
 * Použití:
 *   val tracker = CommandContextTracker()
 *   tracker.recordStep("Otevři nastavení", ActionType.OPEN_APP, success = true)
 *   val context = tracker.getContextSummary()
 *   // → "Provedeno: Otevři nastavení (OPEN_APP). Zbývá zpracovat."
 *
 * Thread-safety: Volá se sekvenčně z coroutine kontextu AgentCoordinator — není potřeba synchronizace.
 */
class CommandContextTracker {

    companion object {
        private const val TAG = "CommandContextTracker"
        private const val MAX_STEPS = 20 // Ochrana před memory leak při dlouhých sekvencích
    }

    /**
     * Jeden provedený krok v rámci příkazu.
     */
    data class Step(
        val description: String,
        val actionType: ActionType,
        val success: Boolean,
        val errorMessage: String? = null,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val steps = mutableListOf<Step>()

    // ==================== Záznam kroků ====================

    /**
     * Zaznamená výsledek jednoho kroku.
     *
     * @param description Popis kroku (pro LLM prompt)
     * @param actionType Typ akce
     * @param success Zda akce uspěla
     * @param errorMessage Chybová zpráva při selhání (volitelné)
     */
    fun recordStep(
        description: String,
        actionType: ActionType,
        success: Boolean,
        errorMessage: String? = null
    ) {
        if (steps.size >= MAX_STEPS) {
            Log.w(TAG, "MAX_STEPS reached — dropping oldest step")
            steps.removeAt(0)
        }
        val step = Step(description, actionType, success, errorMessage)
        steps.add(step)
        Log.d(TAG, "Step recorded: [${if (success) "✅" else "❌"}] $description ($actionType)")
    }

    // ==================== Dotazy na stav ====================

    /**
     * Vrátí true pokud byl zaznamenán alespoň jeden neúspěšný krok.
     */
    fun hasFailedSteps(): Boolean = steps.any { !it.success }

    /**
     * Vrátí počet úspěšně provedených kroků.
     */
    fun successCount(): Int = steps.count { it.success }

    /**
     * Vrátí počet neúspěšných kroků.
     */
    fun failureCount(): Int = steps.count { !it.success }

    /**
     * Vrátí true pokud nebyly zaznamenány žádné kroky.
     */
    fun isEmpty(): Boolean = steps.isEmpty()

    /**
     * Vrátí poslední zaznamenaný krok nebo null.
     */
    fun lastStep(): Step? = steps.lastOrNull()

    // ==================== LLM Prompt Enrichment ====================

    /**
     * Generuje stručný kontext pro LLM prompt.
     *
     * Formát:
     * "Provedené kroky: 1. Otevři nastavení [OK]. Zbývá: zapni Wi-Fi."
     *
     * @param remainingCommand Zbývající část příkazu (volitelné)
     * @return Kontext pro LLM prompt
     */
    fun getContextSummary(remainingCommand: String? = null): String {
        if (steps.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("=== Kontext předchozích kroků ===\n")
        sb.append("Celkem: ${steps.size} (✅ ${successCount()}, ❌ ${failureCount()})\n")

        steps.forEachIndexed { index, step ->
            val status = if (step.success) "✅" else "❌"
            sb.append("${index + 1}. $status ${step.description}")
            if (!step.success && step.errorMessage != null) {
                sb.append(" [Chyba: ${step.errorMessage}]")
            }
            sb.append("\n")
        }

        if (remainingCommand != null) {
            sb.append("=== Aktuální úkol ===\n")
            sb.append(remainingCommand)
            sb.append("\n")
        }

        return sb.toString().trim()
    }

    /**
     * Generuje kontext pro self-healing retry prompt.
     *
     * @param failedActionType Typ akce která selhala
     * @param errorMessage Chybová zpráva
     * @return Kontext pro LLM retry prompt
     */
    fun getRetryContext(failedActionType: ActionType, errorMessage: String): String {
        return buildString {
            append("=== Self-Healing Retry ===\n")
            append("Předchozí akce '${failedActionType.name}' selhala: $errorMessage\n")
            if (steps.isNotEmpty()) {
                append("Historie kroků:\n")
                steps.takeLast(3).forEachIndexed { i, step ->
                    append("  ${i + 1}. [${if (step.success) "OK" else "FAIL"}] ${step.description}\n")
                }
            }
            append("Prosím analyzuj aktuální obrazovku a navrhni opravný krok.")
        }
    }

    // ==================== Reset ====================

    /**
     * Resetuje tracker pro nový příkaz.
     * Idempotentní — bezpečné volat opakovaně.
     */
    fun reset() {
        val count = steps.size
        steps.clear()
        if (count > 0) {
            Log.d(TAG, "Tracker reset — cleared $count steps")
        }
    }
}
