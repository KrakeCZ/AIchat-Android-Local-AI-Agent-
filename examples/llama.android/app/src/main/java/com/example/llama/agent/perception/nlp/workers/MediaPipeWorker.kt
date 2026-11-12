package com.example.llama.agent.perception.nlp.workers

import android.content.Context
import android.util.Log

/**
 * MediaPipe Expert (LiteRT / TFLite).
 * Lokální offline modely na zařízení, dobré pro středně velké sumarizace bez GGUF overheadu.
 */
class MediaPipeWorker(
    context: Context,
    private val llmWorker: com.example.llama.agent.perception.MediaPipeLlmWorker
) : BaseMapReduceWorker(context, "MediaPipe (MoE)", 2000) {

    private var lastSystemDirective: String? = null

    override suspend fun executePromptSafe(prompt: String, systemPrompt: String?, isSystem: Boolean, resetContext: Boolean): String? {
        if (resetContext) {
            lastSystemDirective = null
        }
        
        // KROK 1: Uložení šablony
        if (isSystem) {
            lastSystemDirective = prompt
            return "OK" // Falešné potvrzení, zpracováváme to jako balíček v Kroku 2
        }
        
        // KROK 2: Odeslání konverzačního balíčku
        val finalPrompt = if (lastSystemDirective != null) {
            "<start_of_turn>user\n${lastSystemDirective}\n<end_of_turn>\n<start_of_turn>model\nRozumím, aplikuji na další text.\n<end_of_turn>\n<start_of_turn>user\n$prompt\n<end_of_turn>\n<start_of_turn>model\n"
        } else {
            prompt
        }

        val result = runCatching<String?> {
            llmWorker.generateResponse(finalPrompt)
        }.getOrElse { e ->
            Log.w("MediaPipeWorker", "⚠️ MediaPipe prompt selhal: ${e.message}")
            null
        }

        return if (!result.isNullOrBlank()) {
            result
        } else {
            null
        }
    }
}

