package com.example.llama.agent.perception.nlp

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.AiCoreMasterEngine
import com.example.llama.agent.perception.AiCoreResult
import com.example.llama.agent.perception.BertSentimentWorker
import com.example.llama.agent.perception.NlpSummaryWorker
import com.example.llama.agent.perception.NlpTextProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Jednoduchý objekt pro držení bloku transkripce pro MeetingIntelligenceRefiner. */
data class TranscriptChunk(
        val startTimeMs: Long,
        val endTimeMs: Long,
        val speakerId: String,
        val rawText: String
) {
    fun toFormattedString(): String = "[$speakerId]: $rawText"
}

/**
 * Callback pro průběžný progres zpracování.
 * @param current  Index aktuálně zpracovaného chunku (1-based)
 * @param total    Celkový počet chunků
 * @param phase    Fáze pipeline ("Chunk", "MiniSyntéza", "FinálníSyntéza")
 * @param engine   Engine který zpracoval poslední chunk ("AICore", "MediaPipe", "Heuristika")
 */
typealias ChunkProgressCallback = (current: Int, total: Int, phase: String, engine: String) -> Unit

/**
 * ========================================================================
 * Meeting Intelligence Brain — NextGen Hierarchical Pipeline 2026
 * ========================================================================
 *
 * Zpracovává transkripty libovolné délky (od 100 do 999 999+ znaků).
 *
 * ARCHITEKTURA (3stupňová hierarchická syntéza):
 *
 * Stupeň 1 — Chunk Processing:
 *   Rozdělení plného textu na chunky dle AICore limitu.
 *   Každý chunk zpracován: AICore → MediaPipe → Heuristika.
 *   Výsledek každého chunku okamžitě zapsán do temp souboru.
 *   → 96 chunků × 500 znaků výstupu = 48 000 znaků mezidat.
 *
 * Stupeň 2 — Mini-syntéza (pokud > 1 chunk):
 *   Skupiny 5 chunk-výsledků kondenzovány do 1 mini-souhrnu.
 *   → 96/5 = 19 mini-souhrnů × ~300 znaků = 5 700 znaků.
 *
 * Stupeň 3 — Finální syntéza:
 *   Všechny mini-souhrny (<= 11 000 znaků) → 1 finální výstup.
 *   Pokud je mini-souhrnů stále příliš mnoho → opakuj redukci.
 *
 * Temp soubor: cacheDir/brain_work_{templateId}_{ts}.tmp
 *   → Persistuje výsledky chunků. Ztráta procesu = ztráta max 1 chunku.
 *   → Automaticky smazán po úspěšném dokončení.
 *
 * Offline-First: Žádná data neopustí zařízení.
 */
class MeetingIntelligenceRefiner(
        private val aiCore: AiCoreMasterEngine,
        private val entityExtractor: NlpSummaryWorker,
        private val context: Context? = null
) {
    private var mediaPipeWorker: com.example.llama.agent.perception.MediaPipeLlmWorker? = null
    companion object {
        private const val TAG = "MeetingBrain"

        /** Celkový limit AICore Prompt API (< MAX_SUMMARIZATION_INPUT_LENGTH=12 000). */
        private const val AICORE_MAX_TOTAL = 11_000

        /** Bezpečný limit pro MediaPipe (prevence SIGSEGV). Synchronizováno s MediaPipeLlmWorker.SAFE_INPUT_CHARS. */
        private const val MEDIAPIPE_MAX_TOTAL = 4_200

        /** Maximální počet chunk-výsledků v jednom mini-syntetizačním volání. */
        private const val MINI_SYNTHESIS_BATCH = 5

        /** Separator v temp souboru mezi chunk výsledky. */
        private const val CHUNK_SEPARATOR = "\n%%CHUNK_END%%\n"
    }

    // ── Singleton Worker instantiations (Lazy) ──
    private val engineRouter: com.example.llama.agent.perception.nlp.router.EngineRouter? by lazy {
        if (context != null) {
            com.example.llama.agent.perception.nlp.router.EngineRouter(
                context,
                com.example.llama.agent.perception.nlp.workers.AiCoreWorker(context, aiCore),
                com.example.llama.agent.perception.nlp.workers.MediaPipeWorker(context, com.example.llama.agent.perception.MediaPipeLlmWorker(context, com.example.llama.SettingsManager(context))),
                com.example.llama.agent.perception.nlp.workers.LlamaWorker(context, com.example.llama.managers.LlamaServiceManager(context, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))),
                com.example.llama.agent.perception.nlp.workers.HeuristicWorker(context)
            )
        } else null
    }

    /**
     * Zpracuje poradu s VÝCHOZÍ šablonou (Meeting Minutes).
     * Zpětně kompatibilní s předchozím API.
     */
    suspend fun refineMeeting(chunks: List<TranscriptChunk>): MeetingIntelligenceResult {
        return refineMeeting(chunks, DictaphoneTemplateManager.getDefaultTemplate())
    }

    /**
     * Zpracuje poradu podle zvolené šablony BEZ progress callbacku.
     * Zpětně kompatibilní s AudioStudioActivity.
     */
    suspend fun refineMeeting(
        chunks: List<TranscriptChunk>,
        template: DictaphoneTemplateManager.DictaphoneTemplate
    ): MeetingIntelligenceResult {
        return refineMeeting(chunks, template, "AUTO", null)
    }

    /**
     * HLAVNÍ ENTRYPOINT pro MoE (Mixture of Experts) post-processing.
     * Nahrazuje původní monolitickou logiku.
     */
    suspend fun refineMeeting(
        chunks: List<TranscriptChunk>,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        enginePreference: String = "AUTO",
        onProgress: ChunkProgressCallback? = null
    ): MeetingIntelligenceResult = withContext(Dispatchers.Default) {

        if (chunks.isEmpty()) return@withContext MeetingIntelligenceResult.empty()

        val fullTranscript = buildString { chunks.forEach { appendLine(it.toFormattedString()) } }
        Log.i(TAG, "🧠 Zahajuji MoE analýzu — šablona: '${template.name}', vstup: ${fullTranscript.length} znaků, Engine: $enginePreference")

        // ── Fáze 1: Sentiment ──
        var sentimentResult: String? = null
        if (template.requiresSentiment && context != null) {
            sentimentResult = try {
                val worker = com.example.llama.agent.perception.BertSentimentWorker(context)
                val result = worker.classifySentiment(fullTranscript.take(512))
                worker.close()
                Log.i(TAG, "🎭 Sentiment: $result")
                result
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Sentiment analýza selhala (non-fatal): ${e.message}")
                null
            }
        }

        // ── Fáze 2: Entity Extraction ──
        val extractedEntities = if (template.requiresEntities) {
            try {
                entityExtractor.extractEntities(fullTranscript)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při extrakci entit", e)
                emptyList()
            }
        } else {
            emptyList()
        }

        // ── Fáze 3: Překlad ──
        var translatedText: String? = null
        if (template.requiresTranslation && template.targetLanguage != null && context != null) {
            translatedText = try {
                val processor = NlpTextProcessor(context)
                val detectedLang = processor.identifyLanguage(fullTranscript)
                val result = processor.translateText(fullTranscript, detectedLang, template.targetLanguage)
                processor.destroy()
                Log.i(TAG, "🌐 Překlad ($detectedLang → ${template.targetLanguage}): ${result.length} znaků")
                result
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Překlad selhal (non-fatal): ${e.message}")
                null
            }
        }

        // ── Fáze 4: Zpracování ──
        val rawAiOutput: String
        val engineUsed: String

        if (enginePreference != "AUTO" && engineRouter != null) {
            Log.i(TAG, "🔀 Směřuji přes modulární MoE Router (preference: $enginePreference)")
            val (output, engine) = engineRouter!!.routeAndProcess(chunks, template, enginePreference, onProgress)
            rawAiOutput = output
            engineUsed = engine
        } else if (template.id == "lang_proofread") {
            // SPECIÁLNÍ TRASA: dedikované Proofread API
            val proofResult = aiCore.proofread(fullTranscript.take(AICORE_MAX_TOTAL))
            if (proofResult is AiCoreResult.Success) {
                Log.i(TAG, "✅ Proofread API OK (${proofResult.text.length} znaků)")
                rawAiOutput = proofResult.text
                engineUsed = "AICore Proofread"
            } else {
                Log.w(TAG, "⚠️ Proofread API selhalo, fallback na prompt()")
                val fb = aiCore.prompt("${template.systemPrompt}\n\n${fullTranscript.take(AICORE_MAX_TOTAL)}")
                rawAiOutput = if (fb is AiCoreResult.Success) fb.text
                              else entityExtractor.summarizeWithHeuristic(fullTranscript)
                engineUsed = if (fb is AiCoreResult.Success) "AICore Prompt (proofread fallback)" else "Heuristika (TF-IDF)"
            }
        } else {
            // HLAVNÍ TRASA: hierarchická pipeline pro libovolně dlouhé texty
            val hierarchyResult = runHierarchicalPipeline(fullTranscript, template, onProgress)
            rawAiOutput = hierarchyResult.first
            engineUsed = hierarchyResult.second
        }

        Log.i(TAG, "📊 ENGINE DIAGNOSTIKA: $engineUsed | výstup ${rawAiOutput.length} znaků")

        // ── Fáze 4.5: Aplikace Markdown šablony ──
        var formattedOutput = template.markdownStructure
        val regex = Regex("\\{([a-zA-Z0-9_]+)\\}")
        val matches = regex.findAll(formattedOutput).toList()

        if (matches.isNotEmpty()) {
            val isHeuristic = engineUsed.contains("Heuristika")
            val paragraphs = if (isHeuristic) rawAiOutput.split(Regex("\n(?=\n|-|\\*|\\d+\\.)")).filter { it.isNotBlank() } else emptyList()
            
            // Check if LLM returned ANY expected XML tags
            val hasAnyTags = if (!isHeuristic) {
                matches.any { match -> 
                    val tag = match.groupValues[1]
                    Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(rawAiOutput) 
                }
            } else false

            if (!isHeuristic && !hasAnyTags) {
                Log.i(TAG, "⚠️ LLM nevygenerovalo žádné očekávané XML tagy. Aplikuji přímo hrubý LLM výstup jako Markdown.")
                formattedOutput = rawAiOutput
            } else {
                matches.forEachIndexed { index, matchResult ->
                    val tag = matchResult.groupValues[1]
                    val placeholder = matchResult.value
                    var replacement: String? = null
                    
                    if (!isHeuristic) {
                        // GenAI (Llama, MediaPipe, AiCore) má vracet XML tagy
                        val xmlRegex = Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        val xmlMatch = xmlRegex.find(rawAiOutput)
                        if (xmlMatch != null) {
                            replacement = xmlMatch.groupValues[1].trim()
                        }
                    }
                    
                    // Fallback na odstavce (pro Heuristiku, nebo pokud LLM selže s XML)
                    if (replacement.isNullOrBlank()) {
                        replacement = if (index < paragraphs.size) paragraphs[index].trim()
                                      else "*(Nedostatek dat v transkriptu pro tuto sekci)*"
                    }
                    
                    formattedOutput = formattedOutput.replace(placeholder, replacement)
                }
                
                if (isHeuristic && paragraphs.size > matches.size) {
                    val leftover = paragraphs.subList(matches.size, paragraphs.size).joinToString("\n\n")
                    formattedOutput += "\n\n### Další poznatky:\n$leftover"
                }
            }
        } else {
            formattedOutput = "$formattedOutput\n\n$rawAiOutput"
        }

        // ── Fáze 4.6: Injekce překladu do Markdown struktury (pro překladové šablony) ──
        if (template.requiresTranslation && translatedText != null) {
            formattedOutput = formattedOutput
                .replace("{original}", fullTranscript.trim())
                .replace("{translated}", translatedText)
                .replace("{bilingual_content}", buildString {
                    val origParas = fullTranscript.split("\n\n").filter { it.isNotBlank() }
                    val tranParas = translatedText.split("\n\n").filter { it.isNotBlank() }
                    origParas.forEachIndexed { i, origPara ->
                        appendLine(origPara.trim())
                        if (i < tranParas.size) appendLine("> ${tranParas[i].trim()}")
                        appendLine()
                    }
                })
        }

        // ── Fáze 5: Sestavení výsledku ──
        MeetingIntelligenceResult(
                rawTranscript = fullTranscript,
                summary = formattedOutput,
                entities = extractedEntities.map { "${it.type}: ${it.text}" },
                templateId = template.id,
                templateName = template.name,
                sentiment = sentimentResult,
                translatedText = translatedText,
                markdownStructure = template.markdownStructure,
                engineDiagnostics = engineUsed
        )
    }
    // ==================== HIERARCHICKÁ PIPELINE ====================

    /**
     * 3stupňová hierarchická pipeline:
     *
     * Stupeň 1: Chunk → zpracuj → ulož do temp souboru
     * Stupeň 2: Mini-syntéza po MINI_SYNTHESIS_BATCH výsledcích
     * Stupeň 3: Finální syntéza ze všech mini-souhrnů
     *
     * @return Pair(finalAiOutput, engineDescription)
     */
    private suspend fun runHierarchicalPipeline(
        fullTranscript: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: ChunkProgressCallback?
    ): Pair<String, String> {

        val systemPromptLen = template.systemPrompt.length + 50
        val maxTextChunkChars = (AICORE_MAX_TOTAL - systemPromptLen).coerceAtLeast(1_000)

        // ── Stupeň 1: Rozdělení na chunky ──
        val textChunks = splitIntoChunks(fullTranscript, maxTextChunkChars)
        val totalChunks = textChunks.size
        Log.i(TAG, "📐 Hierarchická pipeline: $totalChunks chunků × $maxTextChunkChars znaků (systemPrompt=$systemPromptLen)")

        if (totalChunks == 1) {
            // Krátký text — přímé zpracování bez temp souboru
            onProgress?.invoke(1, 1, "Chunk", "...")
            val (result, engine) = processOneChunk(textChunks[0], template, 0, 1, systemPromptLen)
            onProgress?.invoke(1, 1, "Hotovo", engine)
            return Pair(result, engine)
        }

        // Temp soubor pro persistenci výsledků chunků
        val tempFile = createTempFile(template.id)
        var lastEngineUsed = "Heuristika (TF-IDF)"

        try {
            // ── Stupeň 1: Zpracuj každý chunk, zapiš do temp souboru ──
            val chunkResults = mutableListOf<String>()
            for ((index, textChunk) in textChunks.withIndex()) {
                onProgress?.invoke(index + 1, totalChunks, "Chunk", lastEngineUsed)
                val (result, engine) = processOneChunk(textChunk, template, index, totalChunks, systemPromptLen)
                lastEngineUsed = engine
                chunkResults.add(result)
                // Okamžitý zápis do temp souboru — persistence při pádu procesu
                appendToTempFile(tempFile, result)
                Log.i(TAG, "💾 Chunk ${index + 1}/$totalChunks uložen do temp ($engine, ${result.length} znaků)")
            }

            // ── Stupeň 2: Mini-syntéza po dávkách ──
            onProgress?.invoke(totalChunks, totalChunks, "MiniSyntéza", lastEngineUsed)
            val miniSummaries = if (chunkResults.size <= MINI_SYNTHESIS_BATCH) {
                chunkResults // Málo výsledků → přeskočit mini-syntézu
            } else {
                performMiniBatchSynthesis(chunkResults, template, systemPromptLen)
            }

            Log.i(TAG, "🔗 Mini-syntéza: ${chunkResults.size} chunk výsledků → ${miniSummaries.size} mini-souhrnů")

            // ── Stupeň 3: Finální syntéza ──
            onProgress?.invoke(totalChunks, totalChunks, "FinálníSyntéza", lastEngineUsed)
            val finalOutput = performFinalSynthesis(miniSummaries, template, lastEngineUsed)

            // Vyčistit temp soubor po úspěchu
            tempFile.delete()
            Log.i(TAG, "🗑️ Temp soubor smazán po úspěšném dokončení")

            return Pair(finalOutput, "$lastEngineUsed + Hierarchická syntéza")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Hierarchická pipeline selhala, čtu temp soubor: ${e.message}")
            // Záchrana: přečti co se stihlo zapsat do temp souboru
            val savedChunks = readFromTempFile(tempFile)
            tempFile.delete()
            if (savedChunks.isNotEmpty()) {
                return Pair(savedChunks.joinToString("\n\n"), "Záchrana z temp souboru (${savedChunks.size} chunků)")
            }
            // Absolutní fallback
            return Pair(entityExtractor.summarizeWithHeuristic(fullTranscript), "Heuristika (TF-IDF) — záchranný fallback")
        } finally {
            // Vždy uvolni MediaPipe worker
            mediaPipeWorker?.teardown()
            mediaPipeWorker = null
        }
    }

    /**
     * Zpracuje jeden textový chunk přes AICore → MediaPipe → Heuristika.
     *
     * @return Pair(výsledný text, název enginu)
     */
    private suspend fun processOneChunk(
        textChunk: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        index: Int,
        total: Int,
        systemPromptLen: Int
    ): Pair<String, String> {
        val chunkLabel = if (total > 1) "\n\n[ČÁST ${index + 1}/$total]\n" else "\n\nTEXT K ANALÝZE:\n"
        val fullPrompt = "${template.systemPrompt}$chunkLabel$textChunk"

        Log.i(TAG, "📦 Chunk ${index + 1}/$total: fullPrompt=${fullPrompt.length} znaků")

        // PRIMÁRNÍ: AICore Prompt API
        val promptResult = aiCore.prompt(fullPrompt)
        if (promptResult is AiCoreResult.Success) {
            val engine = if (promptResult.cached) "AICore (cache)" else "AICore Gemini Nano"
            Log.i(TAG, "✅ AICore OK (chunk ${index + 1}, ${promptResult.text.length} znaků)")
            return Pair(promptResult.text, engine)
        }
        Log.w(TAG, "⚠️ AICore selhalo (chunk ${index + 1}): ${(promptResult as? AiCoreResult.Error)?.message}")

        // FALLBACK 1: MediaPipe LLM (singleton worker — nealokuj znovu pro každý chunk)
        val mediaPipePrompt = if (fullPrompt.length > MEDIAPIPE_MAX_TOTAL) {
            val availableForText = (MEDIAPIPE_MAX_TOTAL - systemPromptLen).coerceAtLeast(200)
            "${template.systemPrompt}$chunkLabel${textChunk.take(availableForText)}"
        } else {
            fullPrompt
        }

        val mpResult = runCatching {
            val ctx = context ?: return@runCatching null
            // Lazy singleton — inicializuj jednou pro celou pipeline, ne per-chunk
            if (mediaPipeWorker == null) {
                mediaPipeWorker = com.example.llama.agent.perception.MediaPipeLlmWorker(
                    ctx, com.example.llama.SettingsManager(ctx)
                )
            }
            mediaPipeWorker!!.generateResponse(mediaPipePrompt)
        }.getOrElse { e ->
            Log.w(TAG, "⚠️ MediaPipe selhalo (chunk ${index + 1}): ${e.message}")
            null
        }

        if (mpResult != null && mpResult.isNotBlank()) {
            Log.i(TAG, "✅ MediaPipe OK (chunk ${index + 1})")
            return Pair(mpResult, "MediaPipe LLM")
        }

        // FALLBACK 2: Heuristika TF-IDF (vždy funguje)
        Log.w(TAG, "⚠️ Všechny GenAI selhaly pro chunk ${index + 1} — heuristika")
        val hResult = entityExtractor.summarizeWithHeuristic(textChunk)
        return Pair(hResult, "Heuristika (TF-IDF)")
    }

    /**
     * Stupeň 2: Mini-syntéza.
     * Skupiny MINI_SYNTHESIS_BATCH chunk-výsledků → 1 mini-souhrn každá.
     * Výrazně redukuje objem dat pro finální syntézu.
     */
    private suspend fun performMiniBatchSynthesis(
        chunkResults: List<String>,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        systemPromptLen: Int
    ): List<String> {
        val miniSummaries = mutableListOf<String>()
        val batches = chunkResults.chunked(MINI_SYNTHESIS_BATCH)

        for ((batchIdx, batch) in batches.withIndex()) {
            val batchText = batch.joinToString("\n\n--- díl ${batchIdx * MINI_SYNTHESIS_BATCH + 1} až ${batchIdx * MINI_SYNTHESIS_BATCH + batch.size} ---\n")
            val miniPrompt = "Shrň následující části záznamu do kompaktního souhrnu (max 400 slov). " +
                "Zachovej klíčová fakta, rozhodnutí a akční body. Buď konkrétní.\n\n$batchText"
            val miniPromptSafe = miniPrompt.take(AICORE_MAX_TOTAL)

            val miniResult = aiCore.prompt(miniPromptSafe)
            val miniText = if (miniResult is AiCoreResult.Success) {
                Log.i(TAG, "✅ Mini-syntéza batch ${batchIdx + 1}/${batches.size} OK")
                miniResult.text
            } else {
                Log.w(TAG, "⚠️ Mini-syntéza batch ${batchIdx + 1} selhala — použit joinToString")
                // Fallback: vezmi první větu z každého chunk-výsledku
                batch.mapNotNull { it.lines().firstOrNull { l -> l.isNotBlank() } }.joinToString(" | ")
            }
            miniSummaries.add(miniText)
        }

        return miniSummaries
    }

    /**
     * Stupeň 3: Finální syntéza.
     * Všechny mini-souhrny → 1 finální výstup dle systemPrompt šablony.
     * Pokud jsou mini-souhrny stále příliš velké → opakuj redukci.
     */
    private suspend fun performFinalSynthesis(
        miniSummaries: List<String>,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        fallbackEngine: String
    ): String {
        if (miniSummaries.size == 1) return miniSummaries[0] // Jediný souhrn → žádná další syntéza

        var summariesToSynthesize = miniSummaries
        var reductionPass = 0

        // Opakuj redukci dokud výsledek nepasuje do AICore limitu
        while (true) {
            val combined = summariesToSynthesize.joinToString("\n\n--- souhrn ${reductionPass + 1} ---\n")
            val systemPromptLen = template.systemPrompt.length + 50
            val finalPrompt = "${template.systemPrompt}\n\nFINÁLNÍ SYNTÉZA KOMPLETNÍHO ZÁZNAMU:\n$combined"

            reductionPass++

            if (finalPrompt.length <= AICORE_MAX_TOTAL) {
                // Vejde se → finální výstup
                val finalResult = aiCore.prompt(finalPrompt)
                if (finalResult is AiCoreResult.Success) {
                    Log.i(TAG, "✅ Finální syntéza OK (pass=$reductionPass, ${finalResult.text.length} znaků)")
                    return finalResult.text
                }
                // AICore selhalo → vrať spojené mini-souhrny
                Log.w(TAG, "⚠️ Finální syntéza AICore selhala — vracím spojené mini-souhrny")
                return summariesToSynthesize.joinToString("\n\n")
            }

            // Příliš velké → ještě jedna mini-syntéza
            if (summariesToSynthesize.size <= MINI_SYNTHESIS_BATCH) {
                // Nelze dál redukovat — forcibly zkraťme
                Log.w(TAG, "⚠️ Finální syntéza: data stále příliš velká (${finalPrompt.length} znaků), zkracuji")
                return aiCore.prompt(finalPrompt.take(AICORE_MAX_TOTAL)).let {
                    if (it is AiCoreResult.Success) it.text
                    else entityExtractor.summarizeWithHeuristic(combined)
                }
            }

            Log.i(TAG, "🔄 Redukce pass=$reductionPass: ${summariesToSynthesize.size} souhrnů → mini-syntéza")
            summariesToSynthesize = performMiniBatchSynthesis(
                summariesToSynthesize, template, template.systemPrompt.length + 50
            )
        }
    }

    // ==================== TEMP FILE HELPERS ====================

    private fun createTempFile(templateId: String): File {
        val ts = System.currentTimeMillis()
        val safeId = templateId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val dir = context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        return File(dir, "brain_work_${safeId}_$ts.tmp").also {
            it.createNewFile()
            Log.i(TAG, "📂 Temp soubor: ${it.absolutePath}")
        }
    }

    private fun appendToTempFile(file: File, chunkResult: String) {
        file.appendText(chunkResult + CHUNK_SEPARATOR, Charsets.UTF_8)
    }

    private fun readFromTempFile(file: File): List<String> {
        return try {
            file.readText(Charsets.UTF_8)
                .split(CHUNK_SEPARATOR)
                .filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nelze číst temp soubor: ${e.message}")
            emptyList()
        }
    }

    // ==================== CHUNKING HELPER ====================

    /**
     * Rozdělí text na chunky po řádcích, max maxCharsPerChunk znaků.
     * Nikdy neroztrhne řádek uprostřed.
     */
    private fun splitIntoChunks(text: String, maxCharsPerChunk: Int): List<String> {
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        text.split("\n").forEach { line ->
            if (current.length + line.length + 1 > maxCharsPerChunk && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.clear()
            }
            current.appendLine(line)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks.ifEmpty { listOf(text) }
    }

    fun cleanup() {
        engineRouter?.cleanup()
    }
}

// ==================== DATA CLASSES ====================

/**
 * Immutable DTO pro finální report.
 */
data class MeetingIntelligenceResult(
        val rawTranscript: String,
        val summary: String,
        val entities: List<String>,
        val templateId: String = "general_meeting",
        val templateName: String = "Zápis z porady",
        val sentiment: String? = null,
        val translatedText: String? = null,
        val markdownStructure: String? = null,
        val engineDiagnostics: String = "Neznámý"
) {
    companion object {
        fun empty() = MeetingIntelligenceResult("Záznam je prázdný.", "N/A", emptyList())
    }
}
