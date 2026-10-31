package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.data.NlpModelRepository
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ========================================================================
 * MEDIAPIPE LLM WORKER (Tier 2.5 / Tier 3 Fallback)
 * ========================================================================
 *
 * Zajišťuje lokální LLM generování prostřednictvím MediaPipe Tasks Genai
 * (LiteRT / GGUF modely), kompletně odstřihnuté od GMS a Google AICore.
 * Slouží jako Kaskádový Fallback pro Samsung S24+ a další modely,
 * u kterých má Google zablokovaný AICore přístup.
 *
 * Podporované modely: Gemma 2B/3 (INT4/INT8), Phi-2 — konvertované do .task (LiteRT bundle).
 * POZOR: Surové .gguf soubory zde NEJDOU použít! Ty zpracovává LLama Engine (llama.cpp).
 * Pro MediaPipe musí být model konvertován přes ai-edge-torch + MediaPipe bundler.
 *
 * Resoluce modelu (priorita):
 * 1. NlpModelRepository — uživatel importoval model přes Settings (SAF File Picker)
 * 2. Legacy fallback — filesDir/models/gemma.task (manuální ADB push)
 */
class MediaPipeLlmWorker(
    private val context: Context,
    private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "MediaPipeLlmWorker"
        private const val LEGACY_MODEL_NAME = "gemma.task"
    }

    private var llmInference: LlmInference? = null
    private var resolvedModelPath: String? = null

    private val nlpModelRepository by lazy { NlpModelRepository(context) }

    /**
     * Dynamická resoluce cesty k modelu.
     *
     * Priorita:
     * 1. NlpModelRepository (uživatel importoval přes SAF/UI)
     * 2. Legacy cesta filesDir/models/gemma.task (ruční ADB push)
     */
    private suspend fun resolveModelPath(): String? {
        // Priorita 1: DB (uživatel importoval přes Settings File Picker)
        val repoPath = nlpModelRepository.getMediaPipeLlmModelPath()
        if (repoPath != null && File(repoPath).exists()) {
            Log.i(TAG, "📦 Model z Repository: $repoPath")
            return repoPath
        }

        // Priorita 2: Legacy cesta (manuální ADB push)
        val legacyDir = File(context.filesDir, "models")
        val legacyFile = File(legacyDir, LEGACY_MODEL_NAME)
        if (legacyFile.exists()) {
            Log.i(TAG, "📦 Legacy model: ${legacyFile.absolutePath}")
            return legacyFile.absolutePath
        }

        Log.w(TAG, "⚠️ Žádný MediaPipe LLM model nenalezen (ani v DB, ani v legacy)")
        return null
    }

    /**
     * Zkontroluje, zda je jakýkoliv model dostupný (DB nebo legacy).
     */
    suspend fun isModelAvailable(): Boolean {
        return resolveModelPath() != null
    }

    /**
     * Líná (lazy) inicializace LLM Inference. Natahuje gigabajty do RAM a NPU,
     * proto ji děláme asynchronně a jen zřídka.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.Default) {
        if (llmInference != null) return@withContext true

        Log.i(TAG, "🤖 Pokouším se inicializovat MediaPipe LlmInference...")

        val path = resolveModelPath()
        if (path == null) {
            Log.e(TAG, "❌ Model nebyl nalezen — import přes Settings → NLP → Sideload MediaPipe Model")
            return@withContext false
        }
        resolvedModelPath = path

        try {
            val optionsBuilder = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                // KRITICKÉ: maxTokens = CELKOVÝ limit (input tokens + output tokens).
                // Gemma-2B/3 INT4 zvládne 4096. Původní 1024 způsoboval SIGSEGV
                // pro jakýkoli prompt > ~750 znaků (nativní C++ nepropaguje error,
                // rovnou generuje Fatal signal 11).
                .setMaxTokens(4096)

            val options = optionsBuilder.build()

            Log.i(TAG, "⏳ Načítám LLM model do paměti z: $path (tohle může trvat několik vteřin)...")

            llmInference = LlmInference.createFromOptions(context, options)

            Log.i(TAG, "✅ MediaPipe LlmInference úspěšně inicializován! maxTokens=4096")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Inicializace LlmInference selhala", e)
            false
        }
    }

    /**
     * LLM GenAI Summarization / Inference
     *
     * KRITICKÉ — prevence SIGSEGV:
     * MediaPipe nativní C++ (LlmGpuCalculator) NEvrací graceful error pokud je
     * input_size >= maxTokens. Místo toho generuje Fatal signal 11 (SIGSEGV).
     * Proto MUSÍME oříznout prompt PŘED voláním native vrstvy.
     *
     * Token estimace: 1 token ≈ 3.5 znaku (CZ/EN mix).
     * Bezpečný input budget při maxTokens=4096: max ~1200 tokenů = ~4200 znaků.
     * Zbývá ~2896 tokenů pro generovaný výstup.
     *
     * @param prompt Úplný prompt zaslaný do modelu.
     * @return Vygenerovaný text, nebo null při chybě nebo příliš dlouhém vstupu.
     */
    suspend fun generateResponse(prompt: String): String? = withContext(Dispatchers.Default) {
        // GUARD: Ořezání promptu před nativním voláním (SIGSEGV prevence)
        // Bezpečný limit: maxTokens(4096) - rezerva výstupu(2896) = 1200 tokenů ≈ 4200 znaků
        val SAFE_INPUT_CHARS = 4200
        val safePrompt = if (prompt.length > SAFE_INPUT_CHARS) {
            Log.w(TAG, "⚠️ Prompt (${prompt.length} znaků) > SAFE_INPUT_CHARS($SAFE_INPUT_CHARS), ořezávám pro prevenci SIGSEGV")
            prompt.take(SAFE_INPUT_CHARS)
        } else {
            prompt
        }

        if (llmInference == null) {
            val success = initialize()
            if (!success) {
                return@withContext null
            }
        }

        try {
            Log.i(TAG, "🧠 Generuji text z MediaPipe... prompt: ${safePrompt.length} znaků (původní: ${prompt.length})")

            val result = llmInference?.generateResponse(safePrompt)

            Log.i(TAG, "✅ Vygenerována odpověď o délce ${result?.length ?: 0} znaků.")
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ LLM inference spadla", e)
            null
        }
    }

    /**
     * Pokusí se uvolnit model z RAM. U lite modelů (1.5GB) je extrémně důležité
     * volat toto při zastavení služby.
     */
    fun teardown() {
        Log.i(TAG, "💀 Uvolňuji MediaPipe LLM z paměti...")
        try {
            llmInference?.close()
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Nelze plynule uzavřít LLM", e)
        } finally {
            llmInference = null
            resolvedModelPath = null
        }
    }
}
