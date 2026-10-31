package com.example.llama.agent.perception.nlp.workers

import android.content.Context
import android.util.Log
import com.example.llama.agent.perception.nlp.ChunkProgressCallback
import com.example.llama.agent.perception.nlp.DictaphoneTemplateManager
import com.example.llama.managers.LlamaServiceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Llama Expert (GGUF).
 * Zpracovává složité templaty a map-reduce komprese textů.
 * Speculative Offloading: Zapne se jen na zpracování a pak se vypne (pokud není resident).
 */
class LlamaWorker(
    context: Context,
    private val llamaManager: LlamaServiceManager
) : BaseMapReduceWorker(context, "Llama GGUF (MoE)", 6000) {

    override suspend fun executePromptSafe(prompt: String, systemPrompt: String?, isSystem: Boolean, resetContext: Boolean): String? {
        val settings = com.example.llama.SettingsManager(context)
        
        // Pokud jde o Krok 1 (isSystem), pošleme šablonu jako prompt. 
        // Pokud jde o Krok 2, pošleme text s resetContext=false, čímž llama.cpp naváže.
        val actualSystemPrompt = if (isSystem) null else systemPrompt
        
        val result = llamaManager.awaitGeneration(prompt, maxTokens = settings.maxTokens, systemPrompt = actualSystemPrompt, resetContext = resetContext)
        return if (!result.isNullOrBlank()) {
            result
        } else {
            Log.w("LlamaWorker", "⚠️ Llama prompt selhal nebo vrátil prázdný výsledek")
            null
        }
    }

    // Přepisujeme hlavní spouštěcí metodu, abychom obalili Map-Reduce logiku lifecycle managementem
    override suspend fun processMapReduce(
        fullTranscript: String,
        template: DictaphoneTemplateManager.DictaphoneTemplate,
        onProgress: ChunkProgressCallback?
    ): String? = withContext(Dispatchers.Default) {
        
        Log.i("LlamaWorker", "Zahajuji Llama MoE proces (lifecycle obálka)...")
        
        // 1. Zajištění, že je služba nastartovaná
        val wasConnected = llamaManager.isServiceConnected()
        if (!wasConnected) {
            Log.i("LlamaWorker", "Startuji LlamaServiceManager on-demand...")
            onProgress?.invoke(0, 1, "Startuji GGUF Model...", engineName)
            withContext(Dispatchers.Main) {
                llamaManager.connect(asResident = false)
            }
            // Čekání na naběhnutí modelu z disku (MoE offloading limit: 3-6s)
            kotlinx.coroutines.delay(4000) 
            onProgress?.invoke(1, 1, "Model nahrán do RAM", engineName)
        }

        try {
            // Zavolání supertřídy pro robustní, hierarchické Map-Reduce s persistencí
            return@withContext super.processMapReduce(fullTranscript, template, onProgress)
        } finally {
            if (!wasConnected) {
                Log.i("LlamaWorker", "Odpojuji model (Offloading přes disconnect)...")
                withContext(Dispatchers.Main) {
                    llamaManager.disconnect()
                }
            }
        }
    }

    fun cleanup() {
        llamaManager.disconnect()
    }
}

