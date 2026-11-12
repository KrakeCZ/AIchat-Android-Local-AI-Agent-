package com.example.llama.agent.perception.nlp.router

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.nlp.ChunkProgressCallback
import com.example.llama.agent.perception.nlp.DictaphoneTemplateManager
import com.example.llama.agent.perception.nlp.MeetingIntelligenceResult
import com.example.llama.agent.perception.nlp.TranscriptChunk
import com.example.llama.agent.perception.nlp.workers.AiCoreWorker
import com.example.llama.agent.perception.nlp.workers.LlamaWorker
import com.example.llama.agent.perception.nlp.workers.MediaPipeWorker
import com.example.llama.agent.perception.nlp.workers.HeuristicWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * On-Device Mixture of Experts (MoE) Router.
 * Rozhoduje, který engine (AiCore, MediaPipe, Llama) zpracuje payload na základě:
 * 1. Uživatelské volby (Engine Override)
 * 2. Náročnosti šablony
 * 3. Dostupnosti enginu
 */
class EngineRouter(
    private val context: Context,
    private val aiCoreWorker: AiCoreWorker,
    private val mediaPipeWorker: MediaPipeWorker,
    private val llamaWorker: LlamaWorker,
    private val heuristicWorker: HeuristicWorker
) {
    companion object {
        private const val TAG = "EngineRouter"
    }

    suspend fun routeAndProcess(
        chunks: List<TranscriptChunk>,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        enginePreference: String = "AUTO",
        onProgress: ChunkProgressCallback? = null
    ): Pair<String, String> = withContext(Dispatchers.Default) {
        
        val fullTranscript = buildString { chunks.forEach { appendLine(it.toFormattedString()) } }
        
        // MoE Decision Logic
        val selectedEngine = determineBestExpert(enginePreference, template, fullTranscript.length)
        Log.i(TAG, "MoE Router vybral experta: $selectedEngine (Preference: $enginePreference)")

        return@withContext when (selectedEngine) {
            "LLAMA" -> {
                val result = llamaWorker.processMapReduce(fullTranscript, template, onProgress)
                if (result != null) Pair(result, "Llama GGUF (MoE)")
                else fallbackProcess(fullTranscript, template, onProgress, "MediaPipe")
            }
            "MEDIAPIPE" -> {
                val result = mediaPipeWorker.processMapReduce(fullTranscript, template, onProgress)
                if (result != null) Pair(result, "MediaPipe LiteRT (MoE)")
                else fallbackProcess(fullTranscript, template, onProgress, "AiCore")
            }
            "AICORE" -> {
                val result = aiCoreWorker.processMapReduce(fullTranscript, template, onProgress)
                if (result != null) Pair(result, "AiCore Gemini Nano (MoE)")
                else Pair(heuristicWorker.summarize(fullTranscript), "Heuristika TF-IDF (Fallback)")
            }
            "HEURISTIKA" -> {
                Pair(heuristicWorker.summarize(fullTranscript), "Heuristika TF-IDF (Vynuceno)")
            }
            else -> {
                Pair(heuristicWorker.summarize(fullTranscript), "Heuristika TF-IDF (Fallback)")
            }
        }
    }

    private suspend fun fallbackProcess(
        transcript: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: ChunkProgressCallback?,
        fallbackEngine: String
    ): Pair<String, String> {
        Log.w(TAG, "⚠️ Primární expert selhal, MoE přesměrovává na: $fallbackEngine")
        return when (fallbackEngine) {
            "MediaPipe" -> {
                val result = mediaPipeWorker.processMapReduce(transcript, template, onProgress)
                if (result != null) Pair(result, "MediaPipe (Fallback)")
                else fallbackProcess(transcript, template, onProgress, "AiCore")
            }
            "AiCore" -> {
                val result = aiCoreWorker.processMapReduce(transcript, template, onProgress)
                if (result != null) Pair(result, "AiCore (Fallback)")
                else Pair(heuristicWorker.summarize(transcript), "Heuristika TF-IDF (Double Fallback)")
            }
            else -> Pair(heuristicWorker.summarize(transcript), "Heuristika TF-IDF (Fallback)")
        }
    }

    private fun determineBestExpert(preference: String, template: DictaphoneTemplateManager.DictaphoneTemplate, textLength: Int): String {
        if (preference != "AUTO") return preference

        // MoE Gating Logic
        if (template.markdownStructure.contains("{") && textLength > 500) {
            return "LLAMA"
        }
        
        if (textLength < 2000) {
            return "AICORE"
        }

        return "MEDIAPIPE"
    }

    fun cleanup() {
        llamaWorker.cleanup()
    }
}
