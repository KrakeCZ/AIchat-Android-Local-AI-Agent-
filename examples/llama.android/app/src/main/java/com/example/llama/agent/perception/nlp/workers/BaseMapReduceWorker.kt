package com.example.llama.agent.perception.nlp.workers

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.nlp.ChunkProgressCallback
import com.example.llama.agent.perception.nlp.DictaphoneTemplateManager
import java.io.File

/**
 * Base Map-Reduce (Hierarchical) Worker pro všechny MoE Enginy.
 * Udržuje absolutní robustnost, zpracování po částech, persistenci do temp souborů
 * a vícestupňovou syntézu, přesně jak bylo definováno pro kritickou infrastrukturu.
 */
abstract class BaseMapReduceWorker(
    protected val context: Context,
    val engineName: String,
    private val maxTokenContext: Int
) {
    companion object {
        private const val TAG = "MapReduceWorker"
        private const val CHUNK_SEPARATOR = "\n%%CHUNK_END%%\n"
        private const val MINI_SYNTHESIS_BATCH = 3
    }

    /**
     * Spustí hierarchickou pipeline s využitím specifického experta (implementovaného v podtřídě).
     */
    open suspend fun processMapReduce(
        fullTranscript: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: ChunkProgressCallback?
    ): String? {
        val systemPromptLen = template.systemPrompt.length + 50
        val maxTextChunkChars = (maxTokenContext - systemPromptLen).coerceAtLeast(1_000)

        // ── Stupeň 1: Rozdělení na chunky ──
        val textChunks = splitIntoChunks(fullTranscript, maxTextChunkChars)
        val totalChunks = textChunks.size
        Log.i(TAG, "📝 Hierarchická pipeline ($engineName): $totalChunks chunků × $maxTextChunkChars znaků (systemPrompt=$systemPromptLen)")

        if (totalChunks == 1) {
            val result = executeConversationalSession(textChunks[0], template, onProgress, 1, 1)
            if (result != null) {
                onProgress?.invoke(1, 1, "Hotovo", engineName)
                return result
            }
            return null
        }

        val tempFile = createTempFile(template.id)

        try {
            // ── Stupeň 1: Zpracuj každý chunk (konverzačně), zapiš do temp souboru ──
            val chunkResults = mutableListOf<String>()
            for ((index, textChunk) in textChunks.withIndex()) {
                val result = executeConversationalSession(textChunk, template, onProgress, index + 1, totalChunks) 
                    ?: throw Exception("$engineName selhal na chunku ${index + 1}")
                chunkResults.add(result)
                appendToTempFile(tempFile, result)
            }

            // ── Stupeň 2: Mini-syntéza po dávkách ──
            onProgress?.invoke(totalChunks, totalChunks, "MiniSyntéza", engineName)
            val miniSummaries = if (chunkResults.size <= MINI_SYNTHESIS_BATCH) {
                chunkResults
            } else {
                performMiniBatchSynthesis(chunkResults, template, systemPromptLen)
            }

            // ── Stupeň 3: Finální syntéza ──
            onProgress?.invoke(totalChunks, totalChunks, "FinálníSyntéza", engineName)
            val finalOutput = performFinalSynthesis(miniSummaries, template)

            tempFile.delete()
            return finalOutput

        } catch (e: Exception) {
            Log.e(TAG, "💥 Hierarchická pipeline ($engineName) selhala: ${e.message}")
            val savedChunks = readFromTempFile(tempFile)
            tempFile.delete()
            if (savedChunks.isNotEmpty()) {
                Log.w(TAG, "Záchrana z temp souboru (${savedChunks.size} chunků) pro $engineName")
                return savedChunks.joinToString("\n\n")
            }
            return null
        }
    }

    /**
     * Textová hygiena: Odstraní neviditelné paznaky (zero-width spaces, izolované surogáty),
     * které by mohly shodit nativní LLM enginy (Tokenization fail / SIGSEGV).
     */
    protected fun sanitizeTextForLlm(input: String): String {
        var clean = input.replace("\u200B", "")
                         .replace("\uFEFF", "")
                         .replace("\u200C", "")
                         .replace("\u200D", "")
        
        // Odstranění běžných non-printable ASCII kontrolních znaků (kromě \n, \r, \t)
        val re = Regex("[\\x00-\\x08\\x0B-\\x0C\\x0E-\\x1F]")
        clean = re.replace(clean, "")
        
        return clean.trim()
    }

    /**
     * Provede dvoukrokovou (stavovou) konverzační interakci s LLM.
     * Udržuje UI animace pro maskování prodlev (Pseudo-stavy).
     */
    open suspend fun executeConversationalSession(
        textChunk: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: ChunkProgressCallback?,
        chunkIndex: Int,
        totalChunks: Int
    ): String? {
        val cleanText = sanitizeTextForLlm(textChunk)
        val prefix = if (totalChunks > 1) "Část $chunkIndex/$totalChunks: " else ""
        
        // KROK 1: Odeslání systémové šablony (Direktiva)
        onProgress?.invoke(chunkIndex, totalChunks, "${prefix}Analyzuji šablonu...", engineName)
        
        executePromptSafe(
            prompt = template.systemPrompt, 
            systemPrompt = null, 
            isSystem = true, 
            resetContext = true
        )
        
        // Pseudo-stav (simuluje zpracování pravidel v UI)
        onProgress?.invoke(chunkIndex, totalChunks, "${prefix}Učím se formátovací pravidla...", engineName)
        kotlinx.coroutines.delay(1200)
        
        // KROK 2: Odeslání textu ke zpracování
        onProgress?.invoke(chunkIndex, totalChunks, "${prefix}Odesílám dokument ke zpracování...", engineName)
        val textPrompt = if (totalChunks > 1) "[ČÁST $chunkIndex/$totalChunks]\n$cleanText" else cleanText
        val result = executePromptSafe(
            prompt = "Zpracuj tento text podle zadané šablony:\n$textPrompt", 
            systemPrompt = null, 
            isSystem = false, 
            resetContext = false
        )
        
        return result
    }

    private suspend fun performMiniBatchSynthesis(
        chunkResults: List<String>,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        systemPromptLen: Int
    ): List<String> {
        val miniSummaries = mutableListOf<String>()
        val batches = chunkResults.chunked(MINI_SYNTHESIS_BATCH)

        for ((batchIdx, batch) in batches.withIndex()) {
            val batchText = batch.joinToString("\n\n--- díl ${batchIdx * MINI_SYNTHESIS_BATCH + 1} až ${batchIdx * MINI_SYNTHESIS_BATCH + batch.size} ---\n")
            val miniPrompt = "Shrň následující části záznamu do kompaktního souhrnu (max 400 slov). Zachovej klíčová fakta.\n\n$batchText"
            val miniPromptSafe = sanitizeTextForLlm(miniPrompt).take(maxTokenContext)

            val miniText = executePromptSafe(miniPromptSafe) ?: batch.mapNotNull { it.lines().firstOrNull { l -> l.isNotBlank() } }.joinToString(" | ")
            miniSummaries.add(miniText)
        }
        return miniSummaries
    }

    private suspend fun performFinalSynthesis(
        miniSummaries: List<String>,
        template: DictaphoneTemplateManager.DictaphoneTemplate
    ): String {
        if (miniSummaries.size == 1) return miniSummaries[0]

        var summariesToSynthesize = miniSummaries
        var reductionPass = 0

        while (true) {
            val combined = summariesToSynthesize.joinToString("\n\n--- souhrn ${reductionPass + 1} ---\n")
            val finalPrompt = "${template.systemPrompt}\n\nFINÁLNÍ SYNTÉZA KOMPLETNÍHO ZÁZNAMU:\n$combined"
            reductionPass++

            if (finalPrompt.length <= maxTokenContext) {
                return executePromptSafe(sanitizeTextForLlm(finalPrompt)) ?: summariesToSynthesize.joinToString("\n\n")
            }

            if (summariesToSynthesize.size <= MINI_SYNTHESIS_BATCH) {
                return executePromptSafe(sanitizeTextForLlm(finalPrompt).take(maxTokenContext)) ?: combined
            }

            summariesToSynthesize = performMiniBatchSynthesis(summariesToSynthesize, template, template.systemPrompt.length + 50)
        }
    }

    // ── Abstraktní metoda pro konkrétní modely (Llama, AiCore, MediaPipe) ──
    abstract suspend fun executePromptSafe(prompt: String, systemPrompt: String? = null, isSystem: Boolean = false, resetContext: Boolean = true): String?

    // ── Temp File Helpers ──
    private fun createTempFile(templateId: String): File {
        val ts = System.currentTimeMillis()
        val safeId = templateId.replace(Regex("[^a-zA-Z0-9_]"), "_")
        val dir = context.cacheDir
        return File(dir, "brain_work_${engineName}_${safeId}_$ts.tmp").apply { createNewFile() }
    }

    private fun appendToTempFile(file: File, chunkResult: String) {
        file.appendText(chunkResult + CHUNK_SEPARATOR, Charsets.UTF_8)
    }

    private fun readFromTempFile(file: File): List<String> {
        return try {
            file.readText(Charsets.UTF_8).split(CHUNK_SEPARATOR).filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }

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
}

