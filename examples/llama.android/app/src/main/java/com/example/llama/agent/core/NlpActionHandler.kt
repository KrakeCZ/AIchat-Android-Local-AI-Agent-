package com.example.llama.agent.core

import android.content.Context
import android.util.Log
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.perception.AiCoreMasterEngine
import com.example.llama.agent.perception.AiCoreResult
import com.example.llama.agent.perception.MediaPipeLlmWorker
import com.example.llama.agent.perception.NlpSummaryWorker
import com.example.llama.agent.perception.NlpTextProcessor
import com.example.llama.agent.perception.RewriteTone
import com.example.llama.agent.perception.SecureStorageEngine
import com.example.llama.agent.perception.SummaryInputType
import com.example.llama.agent.perception.SummaryOutputType
import com.example.llama.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Engine mode dle nastavení uživatele (SettingsManager.summarizationBackend).
 * Platí pro: Summarize, Proofread, Rewrite.
 * Ostatní akce (Translate, Sentiment, NER, QA, NLI, MLM, SmartReply) mají fixní pipeline.
 */
enum class NlpEngineMode {
    AUTO,           // AICore → MediaPipe LLM → Heuristika (výchozí)
    AICORE_ONLY,    // Jen Gemini Nano (AICore ML Kit GenAI)
    MLKIT_ONLY,     // Jen ML Kit task APIs (Summarize/Proofread/Rewrite)
    HEURISTIC_ONLY, // Jen TF-IDF heuristika, bez modelu
    LLM_ONLY        // Jen GGUF/MediaPipe LLM (llama.cpp nebo MediaPipe)
}

/**
 * ========================================================================
 * NLP ACTION HANDLER — Middleware pro ActionExecutor
 * ========================================================================
 *
 * Bridge mezi ActionExecutor (UI gesta) a NLP/AICore enginy.
 * Deleguje NLP_* akce na správný engine:
 *
 * - NLP_PROOFREAD  → AiCoreMasterEngine.proofread()
 * - NLP_REWRITE    → AiCoreMasterEngine.rewrite()
 * - NLP_TRANSLATE  → NlpTextProcessor.translateText()
 * - NLP_SUMMARIZE  → AiCoreMasterEngine.summarize() / NlpSummaryWorker fallback
 * - NLP_SENTIMENT  → NlpTextProcessor.classifySentiment()
 * - NLP_SMART_REPLY → NlpTextProcessor.generateSmartReplies()
 *
 * Thread Safety: Všechny metody jsou suspend a thread-safe.
 * Lifecycle: Lazy-init enginů — single-attempt, idempotentní.
 */
class NlpActionHandler(
    private val context: Context,
    private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "NlpActionHandler"
    }

    // Lazy init enginů — vytvořeny až při prvním volání
    // aiCoreEngine: process-scope singleton (sdílené Binder spojení k AICore službě)
    private val aiCoreEngine by lazy { AiCoreMasterEngine.getInstance(context) }
    private val nlpTextProcessor by lazy { NlpTextProcessor(context) }
    private val nlpSummaryWorker by lazy { NlpSummaryWorker(context, settingsManager) }
    private val toolbarBertWorker by lazy { com.example.llama.agent.perception.ToolbarBertWorker(context, settingsManager) }
    private val mediaPipeLlmWorker by lazy { MediaPipeLlmWorker(context, settingsManager) }

    @Volatile
    private var secureStorageInitialized = false

    /**
     * Přeloží řetězec z SettingsManager.summarizationBackend na NlpEngineMode.
     * Přednost má forceMode z volání execute() (pro lokální přepínáč v Activity).
     * Hodnoty: "aicore", "mlkit", "heuristic", "llm", "auto" (výchozí).
     */
    private fun resolveEngineMode(): NlpEngineMode = _forcedMode
        ?: when (settingsManager.summarizationBackend.lowercase().trim()) {
            "aicore"              -> NlpEngineMode.AICORE_ONLY
            "mlkit"               -> NlpEngineMode.MLKIT_ONLY
            "heuristic"           -> NlpEngineMode.HEURISTIC_ONLY
            "llm", "mediapipe"    -> NlpEngineMode.LLM_ONLY  // "mediapipe" = MediaPipe LLM worker
            else                  -> NlpEngineMode.AUTO
        }

    /**
     * Odešle text do MediaPipe LLM (GGUF/llama.cpp) s task prefixem.
     * Používá se pro LLM_ONLY mód.
     */
    private suspend fun runLlmTask(taskPrefix: String, text: String): String {
        val isReady = try { mediaPipeLlmWorker.initialize() } catch (e: Exception) { false }
        if (!isReady) return "❌ LLM model není dostupný. Nahraj GGUF model přes Nastavení → NLP."
        val prompt = "$taskPrefix\n\n$text"
        return mediaPipeLlmWorker.generateResponse(prompt)
            ?: "❌ LLM vrátil prázdnou odpověď."
    }

    /**
     * Vykonej NLP akci na vstupním textu.
     *
     * @param actionType  Typ NLP akce (NLP_PROOFREAD, NLP_REWRITE, ...)
     * @param inputText   Vstupní text
     * @param forceMode   Volitelný přepis enginu (např. NlpEngineMode.MLKIT_ONLY pro rychlý mód).
     *                    Pokud null — použije se SettingsManager.summarizationBackend.
     * @return Výsledný text (opravený, přeložený, přepsaný, ...)
     * @throws IllegalArgumentException pokud actionType není NLP akce
     */
    suspend fun execute(
        actionType: ActionType,
        inputText: String,
        forceMode: NlpEngineMode? = null
    ): String = withContext(Dispatchers.Default) {
        ensureSecureStorage()
        _forcedMode = forceMode

        try {
            when (actionType) {
                ActionType.NLP_PROOFREAD   -> executeProofread(inputText)
                ActionType.NLP_REWRITE     -> executeRewrite(inputText)
                ActionType.NLP_TRANSLATE   -> executeTranslate(inputText)
                ActionType.NLP_SUMMARIZE   -> executeSummarize(inputText)
                ActionType.NLP_SENTIMENT   -> executeSentiment(inputText)
                ActionType.NLP_SMART_REPLY -> executeSmartReply(inputText)
                ActionType.NLP_QA          -> executeQa(inputText)
                ActionType.NLP_NLI         -> executeNli(inputText)
                ActionType.NLP_MLM         -> executeMlm(inputText)
                ActionType.NLP_NER         -> executeNer(inputText)
                ActionType.NLP_PROMPT      -> executePrompt(inputText)
                else -> throw IllegalArgumentException("$actionType není NLP akce")
            }
        } finally {
            _forcedMode = null // vymaz po každém volání — idempotence
        }
    }

    /** Dochačna paměť pro forceMode v rámci jednoho volání execute(). Volné vlakno Default. */
    @Volatile private var _forcedMode: NlpEngineMode? = null

    /**
     * Korektura textu — engine dle nastavení.
     *
     * AUTO/AICORE_ONLY → AICore Proofreader
     * MLKIT_ONLY       → AICore Proofreader (ML Kit je podmnožina AICore pro tento task)
     * HEURISTIC_ONLY   → vrací originální text (heuristika pro korekuru neexistuje)
     * LLM_ONLY         → MediaPipe LLM s instrukcí
     */
    private suspend fun executeProofread(text: String): String {
        Log.i(TAG, "✏️ Proofreading [${ resolveEngineMode() }]: ${text.length} znaků")
        return when (resolveEngineMode()) {
            NlpEngineMode.LLM_ONLY -> runLlmTask(
                "Oprav gramatiku a pravopis následujícího textu. Vrať pouze opravený text bez komentáře:", text
            )
            NlpEngineMode.HEURISTIC_ONLY -> {
                Log.w(TAG, "⚠️ HEURISTIC_ONLY nemá korekturu — vrácen originál")
                text
            }
            else -> { // AUTO, AICORE_ONLY, MLKIT_ONLY
                when (val result = aiCoreEngine.proofread(text)) {
                    is AiCoreResult.Success -> {
                        Log.i(TAG, "✅ Proofread AICore OK${if (result.cached) " (cache)" else ""}")
                        result.text
                    }
                    is AiCoreResult.Error -> {
                        Log.w(TAG, "⚠️ Proofread AICore failed (${result.code}) → originál")
                        if (resolveEngineMode() == NlpEngineMode.AICORE_ONLY) text
                        else runLlmTask("Oprav gramatiku a pravopis. Vrať jen opravený text:", text)
                            .takeIf { it.isNotBlank() } ?: text
                    }
                }
            }
        }
    }

    /**
     * Přepis textu — engine dle nastavení.
     *
     * AUTO/AICORE_ONLY/MLKIT_ONLY → AICore Rewriter
     * HEURISTIC_ONLY              → TF-IDF sentence ranking (zkrácená verze)
     * LLM_ONLY                    → MediaPipe LLM
     *
     * inputText formát: "tone|text" nebo jen "text" (default PROFESSIONAL).
     */
    private suspend fun executeRewrite(rawInput: String): String {
        val parts = rawInput.split("|", limit = 2)
        val tone = if (parts.size > 1) {
            try { RewriteTone.valueOf(parts[0].uppercase()) } catch (_: Exception) { RewriteTone.PROFESSIONAL }
        } else RewriteTone.PROFESSIONAL
        val text = if (parts.size > 1) parts[1] else rawInput

        Log.i(TAG, "✍️ Rewriting [${resolveEngineMode()}] (${tone.name}): ${text.length} znaků")
        return when (resolveEngineMode()) {
            NlpEngineMode.LLM_ONLY -> runLlmTask(
                "Přepiš následující text v tónu '${tone.name}'. Vrať pouze přepsaný text:", text
            )
            NlpEngineMode.HEURISTIC_ONLY -> {
                // Heuristický přepis = výběr top vět přes NlpSummaryWorker
                when (val r = nlpSummaryWorker.summarize(text)) {
                    is NlpSummaryWorker.SummaryResult.Success -> "[Heuristický výběr vět]\n${r.summaryText}"
                    else -> text.take(500)
                }
            }
            else -> { // AUTO, AICORE_ONLY, MLKIT_ONLY
                when (val result = aiCoreEngine.rewrite(text, tone)) {
                    is AiCoreResult.Success -> {
                        Log.i(TAG, "✅ Rewrite AICore OK (${tone.name})${if (result.cached) " (cache)" else ""}")
                        result.text
                    }
                    is AiCoreResult.Error -> {
                        Log.w(TAG, "⚠️ Rewrite AICore failed (${result.code}) → LLM fallback")
                        if (resolveEngineMode() == NlpEngineMode.AICORE_ONLY) text
                        else runLlmTask("Přepiš text v tónu '${tone.name}'. Vrať jen přepsaný text:", text)
                            .takeIf { it.isNotBlank() } ?: text
                    }
                }
            }
        }
    }

    /**
     * Překlad textu — Multiâ†”Multi, dle nastavení uživatele.
     *
     * Logika směru:
     * 1. Pokud rawInput obsahuje "from|to|text" — explicitní volání (Toolbar dispatch)
     * 2. Pokud rawInput je jen text — auto-detekce jazyka:
     *    - detekován můj jazyk (translateMyLang) → přelož do cizího (translateForeignLang)
     *    - detekce ukazy jiný jazyk → přelož do mého jazyka (reverze)
     *    - detekce selhá ("und") → fallback cs→en
     */
    
    private suspend fun executePrompt(rawInput: String): String {
        val parts = rawInput.split("|", limit = 2)
        val promptHint = if (parts.size > 1) parts[0].trim() else ""
        val text = if (parts.size > 1) parts[1].trim() else rawInput
        val fullPrompt = if (promptHint.isNotBlank()) "$promptHint\n\n$text" else text

        Log.i(TAG, "💭 Prompt: ${text.length} znaků")
        return when (resolveEngineMode()) {
            NlpEngineMode.LLM_ONLY -> runLlmTask(promptHint, text)
            NlpEngineMode.HEURISTIC_ONLY -> "⚠️ HEURISTIC_ONLY nepodporuje volný prompt."
            else -> {
                when (val result = aiCoreEngine.prompt(fullPrompt)) {
                    is AiCoreResult.Success -> {
                        Log.i(TAG, "✅ Prompt AICore OK")
                        result.text
                    }
                    is AiCoreResult.Error -> {
                        Log.w(TAG, "⚠️ Prompt AICore failed → LLM fallback")
                        if (resolveEngineMode() == NlpEngineMode.AICORE_ONLY) "❌ AICore selhal: "
                        else runLlmTask(promptHint, text).takeIf { it.isNotBlank() } ?: "❌ LLM fallback selhal."
                    }
                }
            }
        }
    }

    private suspend fun executeTranslate(rawInput: String): String {
        val parts = rawInput.split("|", limit = 3)
        if (parts.size >= 3) {
            // Explicitní formát: "from|to|text"
            val from = parts[0].trim()
            val to   = parts[1].trim()
            val text = parts[2].trim()
            Log.i(TAG, "🌐 Translate explicit ($from -> $to): ${text.length} znaků")
            return nlpTextProcessor.translateText(text, from, to)
        }

        // Jen text — auto-detekce + směr dle nastavení
        val text      = rawInput
        val myLang    = settingsManager.translateMyLang.trim().lowercase()     // např. "cs"
        val foreign   = settingsManager.translateForeignLang.trim().lowercase() // např. "en"

        Log.i(TAG, "🔮 LangID pro ${text.take(60).replace('\n',' ')}...")
        val detected = nlpTextProcessor.identifyLanguage(text) // BCP-47 nebo "und"
        Log.i(TAG, "đźŚ Detekce: '$detected -> $myLang -> $foreign'")

        return when {
            detected == "und" || detected.isBlank() -> {
                // Auto-detekce selhala — fallback: můj→cizí
                Log.w(TAG, "⚠️ LangID failed → fallback $myLang -> $foreign")
                nlpTextProcessor.translateText(text, myLang, foreign)
            }
            detected.take(2) == myLang.take(2) -> {
                // Text je v mém jazyce → přelož do cizího
                Log.i(TAG, "→ můj jazyk detekcí — překládám $myLang -> $foreign")
                nlpTextProcessor.translateText(text, myLang, foreign)
            }
            else -> {
                // Jiný jazyk — přelož zpět do mého (reverze)
                Log.i(TAG, "â† cizí jazyk '$detected' — překládám $detected -> $myLang")
                nlpTextProcessor.translateText(text, detected, myLang)
            }
        }
    }

    /**
     * Sumarizace textu — engine dle nastavení uživatele.
     *
     * AUTO        → AICore → MediaPipe LLM → Heuristika (kaskáda)
     * AICORE_ONLY → pouze Gemini Nano, bez fallbacku
     * MLKIT_ONLY  → ML Kit Summarizer (podmnožina AICore) → Heuristika
     * HEURISTIC_ONLY → pouze TF-IDF NlpSummaryWorker
     * LLM_ONLY    → pouze MediaPipe LLM (llama.cpp GGUF)
     */
    private suspend fun executeSummarize(text: String): String {
        Log.i(TAG, "🧠 Summarize [${resolveEngineMode()}]: ${text.length} znaků")

        return when (resolveEngineMode()) {

            NlpEngineMode.HEURISTIC_ONLY -> {
                when (val r = nlpSummaryWorker.summarize(text)) {
                    is NlpSummaryWorker.SummaryResult.Success -> r.summaryText
                    is NlpSummaryWorker.SummaryResult.NEEDS_LLM -> "Text je příliš složitý pro heuristickou sumarizaci. Zvolte AUTO nebo LLM_ONLY."
                    is NlpSummaryWorker.SummaryResult.Error -> text.take(500)
                }
            }

            NlpEngineMode.LLM_ONLY -> runLlmTask(
                "Shrň následující text do stručného přehledu klíčových bodů v češtině:", text
            )

            NlpEngineMode.AICORE_ONLY -> {
                when (val r = aiCoreEngine.summarize(text, SummaryInputType.ARTICLE, SummaryOutputType.THREE_BULLETS)) {
                    is AiCoreResult.Success -> { Log.i(TAG, "✅ Summarize AICore OK"); r.text }
                    is AiCoreResult.Error -> "❌ AICore nedostupný (${r.code}). Zkuste AUTO nebo jiný engine."
                }
            }

            NlpEngineMode.MLKIT_ONLY -> {
                // ML Kit Summarizer = AICore s kaskádou na heuristiku (bez LLM fallbacku)
                if (AiCoreMasterEngine.isAICoreAvailable(context)) {
                    when (val r = aiCoreEngine.summarize(text, SummaryInputType.ARTICLE, SummaryOutputType.THREE_BULLETS)) {
                        is AiCoreResult.Success -> { Log.i(TAG, "✅ Summarize MLKit OK"); r.text }
                        is AiCoreResult.Error -> {
                            Log.w(TAG, "⚠️ MLKit summarize failed → heuristika")
                            when (val h = nlpSummaryWorker.summarize(text)) {
                                is NlpSummaryWorker.SummaryResult.Success -> h.summaryText
                                else -> text.take(500)
                            }
                        }
                    }
                } else {
                    when (val h = nlpSummaryWorker.summarize(text)) {
                        is NlpSummaryWorker.SummaryResult.Success -> h.summaryText
                        else -> text.take(500)
                    }
                }
            }

            NlpEngineMode.AUTO -> {
                // Kaskáda: AICore → LLM → Heuristika
                var result: String? = null
                if (AiCoreMasterEngine.isAICoreAvailable(context)) {
                    when (val r = aiCoreEngine.summarize(text, SummaryInputType.ARTICLE, SummaryOutputType.THREE_BULLETS)) {
                        is AiCoreResult.Success -> {
                            Log.i(TAG, "✅ Summarize AICore OK (AUTO)")
                            result = r.text
                        }
                        is AiCoreResult.Error -> Log.w(TAG, "⚠️ AICore failed (${r.code}) → LLM")
                    }
                }
                if (result == null) {
                    // LLM fallback
                    val llmResult = try { runLlmTask("Shrň text v češtině do klíčových bodů:", text) } catch (_: Exception) { "" }
                    if (llmResult.isNotBlank() && !llmResult.startsWith("❌")) {
                        result = llmResult
                    }
                }
                if (result == null) {
                    // Heuristika jako poslední možnost
                    result = when (val h = nlpSummaryWorker.summarize(text)) {
                        is NlpSummaryWorker.SummaryResult.Success -> h.summaryText
                        is NlpSummaryWorker.SummaryResult.NEEDS_LLM -> "Text vyžaduje LLM. Zapněte LLM engine v nastavení."
                        is NlpSummaryWorker.SummaryResult.Error -> text.take(500)
                    }
                }
                result!!
            }
        }
    }

    /**
     * Sentiment analýza přes NlpTextProcessor (MediaPipe TextClassifier).
     */
    private suspend fun executeSentiment(text: String): String {
        Log.i(TAG, "đźŠ Sentiment: ${text.length} znaků")
        return try {
            val sentiment = nlpTextProcessor.classifySentiment(text)
            "Sentiment: $sentiment"
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Sentiment analýza selhala: ${e.message}")
            "Sentiment analýza nedostupná"
        }
    }

    /**
     * Smart Reply přes ML Kit (EN only).
     * Vstup: text poslední zprávy (simulace jednozprávané konverzace).
     */
    private suspend fun executeSmartReply(text: String): String {
        Log.i(TAG, "💬 Smart Reply: ${text.length} znaků")
        return try {
            val message = NlpTextProcessor.SmartReplyMessage(
                text = text,
                timestampMs = System.currentTimeMillis(),
                isLocalUser = false,
                userId = "remote_user"
            )
            val replies = nlpTextProcessor.generateSmartReplies(listOf(message))
            if (replies.isNotEmpty()) {
                replies.joinToString(" | ")
            } else {
                "Žádné návrhy odpovědí"
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Smart Reply selhala: ${e.message}")
            "Smart Reply nedostupná"
        }
    }

    // ==================== Zpracování novými LiteRT modely ====================

    private suspend fun ensureBertWorkerReady() {
        if (!toolbarBertWorker.prepare()) {
            Log.w(TAG, "⚠️ ToolbarBertWorker selhal při prepare (chybí model?)")
        }
    }

    private suspend fun executeQa(rawInput: String): String {
        ensureBertWorkerReady()
        // Očekávaný formát: "context|question"
        val parts = rawInput.split("|", limit = 2)
        if (parts.size < 2) return "QA chyba syntaxe (nutné 'kontext|otázka')"
        val contextText = parts[0]
        val question = parts[1]
        Log.i(TAG, "🔍 QA Hledání...")
        return toolbarBertWorker.extractiveQa(contextText, question)
    }

    private suspend fun executeNli(text: String): String {
        ensureBertWorkerReady()
        Log.i(TAG, "⚖️ NLI Ověřování rozporů...")
        return toolbarBertWorker.detectContradiction(text)
    }

    private suspend fun executeMlm(text: String): String {
        ensureBertWorkerReady()
        Log.i(TAG, "🎯 MLM Doplnění (Masked LM)...")
        return toolbarBertWorker.predictMaskedTokens(text)
    }

    private suspend fun executeNer(text: String): String {
        Log.i(TAG, "🏷️ NER Entity...")
        return try {
            val entities = nlpTextProcessor.extractEntities(text)
            if (entities.isNotEmpty()) {
                entities.joinToString("\n") { "â€˘ ${it.type}: ${it.text}" }
            } else {
                "Žádné entity nenalezeny."
            }
        } catch (e: Exception) {
            "NER detekce nedostupná"
        }
    }

    /**
     * Inicializace SecureStorageEngine — jednou za lifecycle.
     */
    private fun ensureSecureStorage() {
        if (!secureStorageInitialized) {
            try {
                SecureStorageEngine.initialize(context)
                secureStorageInitialized = true
            } catch (e: Exception) {
                Log.e(TAG, "❌ SecureStorageEngine init selhala: ${e.message}")
            }
        }
    }

    /**
     * Uvolnění resources — volat při teardown.
     */
    fun teardown() {
        // aiCoreEngine je process-scope singleton — NEVOLAT teardownEngine(), bylo by sdílené
        try { nlpSummaryWorker.destroy() } catch (_: Exception) {}
        try { toolbarBertWorker.destroy() } catch (_: Exception) {}
        Log.i(TAG, "đź’€ NlpActionHandler teardown")
    }
}



