package com.example.llama.agent.perception.nlp.workers

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.AiCoreMasterEngine
import com.example.llama.agent.perception.AiCoreResult

/**
 * AiCore Expert (Gemini Nano).
 * Zvládá lehké až střední úkoly velmi efektivně s hardwarovou akcelerací.
 */
class AiCoreWorker(
    context: Context,
    private val aiCoreEngine: AiCoreMasterEngine
) : BaseMapReduceWorker(context, "AiCore (MoE)", 4000) {

    private var lastSystemDirective: String? = null

    override suspend fun executePromptSafe(prompt: String, systemPrompt: String?, isSystem: Boolean, resetContext: Boolean): String? {
        if (resetContext) {
            lastSystemDirective = null
        }
        
        if (isSystem) {
            lastSystemDirective = prompt
            return "OK"
        }
        
        // AiCore/Gemini Nano na Androidu obvykle přijímá kontext nejlépe přes přímé spojení promptů
        // pokud nevyužijeme nativní ChatSession (která nemusí být vždy z AiCoreMasterEngine dostupná).
        val finalPrompt = if (lastSystemDirective != null) {
            "SYSTEM INSTRUCTION:\n$lastSystemDirective\n\nUSER INPUT:\n$prompt"
        } else {
            prompt
        }

        val result = aiCoreEngine.prompt(finalPrompt)
        return if (result is AiCoreResult.Success) {
            result.text
        } else {
            Log.w("AiCoreWorker", "⚠️ AiCore prompt selhal: ${(result as? AiCoreResult.Error)?.message}")
            null
        }
    }

    override suspend fun processMapReduce(
        fullTranscript: String,
        template: com.example.llama.agent.perception.nlp.DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: com.example.llama.agent.perception.nlp.ChunkProgressCallback?
    ): String? {
        // SPECIÁLNÍ TRASA: dedikované Proofread API pro AiCore
        if (template.id == "lang_proofread") {
            Log.i("AiCoreWorker", "Aktivována speciální trasa: AiCore Proofread API")
            onProgress?.invoke(1, 1, "Korekce textu", "AiCore Proofread")
            
            val proofResult = aiCoreEngine.proofread(fullTranscript.take(4000))
            if (proofResult is AiCoreResult.Success) {
                Log.i("AiCoreWorker", "âś… Proofread API OK (${proofResult.text.length} znaků)")
                onProgress?.invoke(1, 1, "Hotovo", "AiCore Proofread")
                return proofResult.text
            } else {
                Log.w("AiCoreWorker", "âš ď¸Ź Proofread API selhalo, fallback na standardní MoE map-reduce")
                // Pokračuje na super.processMapReduce jako fallback
            }
        }
        
        // HLAVNÍ TRASA: robustní Map-Reduce s persistencí z BaseMapReduceWorker
        return super.processMapReduce(fullTranscript, template, onProgress)
    }

}

