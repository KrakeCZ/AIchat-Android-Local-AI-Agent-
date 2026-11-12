package com.example.llama.agent.core

import android.content.Context
import android.util.Base64
import android.util.Log
import com.arm.aichat.FinishReason
import com.arm.aichat.GenerationEvent
import com.arm.aichat.InferenceEngine
import com.arm.aichat.NativeModelCapabilities
import com.example.llama.SettingsManager
import com.example.llama.SettingsManager.AiProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * CloudInferenceEngine — Nativní bez-SDK (Zero-Bloat) implementace [InferenceEngine]
 * pro Google AI Studio (Gemini REST SSE API) a OpenAI-kompatibilní API (OpenAI, Groq, DeepSeek, OpenRouter, Ollama).
 *
 * Architektonické vlastnosti:
 * - 100% kompatibilita s [InferenceEngine] kontraktem (MainActivity, AgentCoordinator, AudioStudio, LlamaGenerationService).
 * - Podpora multimodálních vstupů ([processImage] detekuje JPEG/PNG/WEBP/WAV/MP3/FLAC přes magic bytes a posílá Base64 inline_data).
 * - Překlad GBNF gramatiky ([setGrammar]) na nativní JSON Schema / JSON Mode pro deterministické ovládání OS Agenta.
 * - Podpora Reasoning / Thinking proudu (Gemini 2.5 native `thought: true` + `<think>...</think>` + DeepSeek `reasoning_content`).
 * - Automatický retry s exponenciálním backoffem při HTTP 429 (Rate Limit u Free Tieru).
 */
class CloudInferenceEngine(context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "CloudInferenceEngine"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val MAX_HISTORY_TURNS = 24
    }

    private val appContext: Context = context.applicationContext
    private val settingsManager = SettingsManager(appContext)

    private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Initialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private val generationIdCounter = AtomicLong(1000L)

    @Volatile
    private var currentSystemPrompt: String = ""

    @Volatile
    private var activeGrammar: String? = null

    @Volatile
    private var thinkingEnabled: Boolean = false

    @Volatile
    private var maxThinkingTokens: Int = 0

    @Volatile
    private var thinkingEffort: String = "medium"

    @Volatile
    private var toolPolicyMode: Int = 2 // 0 = AUTO, 1 = REQUIRED, 2 = NONE

    @Volatile
    private var toolDefinitionsJson: String = ""

    @Volatile
    private var pendingToolResultPrompt: String? = null

    // Sampling parametry přebírané z aplikace
    @Volatile
    private var temperature: Float = 0.7f
    @Volatile
    private var topK: Int = 40
    @Volatile
    private var topP: Float = 0.95f

    // Multimodální buffer (snímek obrazovky z AgentCoordinatoru nebo příloha z chatu)
    @Volatile
    private var pendingMediaBytes: ByteArray? = null
    @Volatile
    private var pendingMediaMimeType: String = "image/jpeg"

    data class ChatTurn(
        val role: String, // "user" nebo "model"/"assistant"
        val text: String,
        val mediaBase64: String? = null,
        val mediaMimeType: String? = null
    )

    private val conversationHistory = mutableListOf<ChatTurn>()

    fun markReady() {
        _state.value = InferenceEngine.State.ModelReady
    }

    override suspend fun loadModel(
        pathToModel: String,
        projectorPath: String?,
        kvCacheMode: Int,
        useMmap: Boolean,
        nCtx: Int,
        nBatch: Int,
        nUBatch: Int,
        flashAttn: Boolean,
        backend: String,
        strictBackend: Boolean,
        gpuLayers: Int
    ) {
        _state.value = InferenceEngine.State.LoadingModel
        Log.i(TAG, "☁️ Inicializuji CloudInferenceEngine pro URI: $pathToModel")
        if (pathToModel.startsWith("cloud://")) {
            val withoutScheme = pathToModel.removePrefix("cloud://")
            val slashIdx = withoutScheme.indexOf('/')
            if (slashIdx > 0) {
                val providerSlug = withoutScheme.substring(0, slashIdx)
                val modelId = withoutScheme.substring(slashIdx + 1)
                if (modelId.isNotBlank()) {
                    settingsManager.cloudModelName = modelId
                }
                when (providerSlug.lowercase()) {
                    "gemini" -> settingsManager.aiProviderType = AiProviderType.GOOGLE_GEMINI
                    "openai" -> settingsManager.aiProviderType = AiProviderType.OPENAI_COMPATIBLE
                }
            }
        }
        _state.value = InferenceEngine.State.ModelReady
    }

    override fun getLoadedBackendInfo(): String = settingsManager.getActiveCloudDisplayName()

    override suspend fun setSystemPrompt(systemPrompt: String) {
        currentSystemPrompt = systemPrompt
    }

    override suspend fun processImage(imageBytes: ByteArray): Boolean {
        if (imageBytes.isEmpty()) return false
        pendingMediaMimeType = detectMimeType(imageBytes)
        pendingMediaBytes = imageBytes
        Log.i(TAG, "📎 Připraven multimodální payload (${imageBytes.size} B, MIME=$pendingMediaMimeType)")
        return true
    }

    override fun hasProjector(): Boolean {
        // Cloudové modely (Gemini 2.5/2.0, GPT-4o) nativně podporují Vision i Audio
        return true
    }

    override suspend fun resetContext() {
        pendingMediaBytes = null
        pendingToolResultPrompt = null
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
    }

    override suspend fun configureSampling(
        temp: Float,
        topK: Int,
        topP: Float,
        minP: Float,
        xtcProb: Float,
        xtcThreshold: Float,
        typicalP: Float,
        dynTempRange: Float,
        dynTempExp: Float,
        penaltyRepeat: Float,
        penaltyFreq: Float,
        penaltyPresent: Float,
        penaltyLastN: Int,
        mirostat: Int,
        mirostatTau: Float,
        mirostatEta: Float,
        samplersSequence: String,
        useBackendSampling: Boolean,
        ignoreEos: Boolean,
        noPerf: Boolean,
        timingPerToken: Boolean
    ) {
        this.temperature = temp.coerceIn(0.0f, 2.0f)
        this.topK = topK.coerceAtLeast(1)
        this.topP = topP.coerceIn(0.0f, 1.0f)
    }

    override fun configureThinking(enabled: Boolean, maxThinkingTokens: Int, effort: String) {
        this.thinkingEnabled = enabled
        this.maxThinkingTokens = maxThinkingTokens
        this.thinkingEffort = effort
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
        sendUserPromptEvents(message, predictLength).collect { event ->
            when (event) {
                is GenerationEvent.ContentDelta -> emit(event.text)
                is GenerationEvent.Error -> throw IllegalStateException(event.message)
                else -> {}
            }
        }
    }

    override fun sendUserPromptEvents(message: String, predictLength: Int): Flow<GenerationEvent> = flow {
        cancelRequested.set(false)
        val genId = generationIdCounter.incrementAndGet()
        var seq = 0L

        _state.value = InferenceEngine.State.ProcessingUserPrompt

        val provider = settingsManager.aiProviderType
        val apiKey = settingsManager.getActiveCloudApiKey().trim()
        var modelName = settingsManager.cloudModelName.trim()

        if (provider == AiProviderType.GOOGLE_GEMINI && apiKey.isBlank()) {
            _state.value = InferenceEngine.State.ModelReady
            emit(
                GenerationEvent.Error(
                    generationId = genId,
                    sequence = ++seq,
                    message = "Chybí Google AI Studio API klíč. Zadejte svůj klíč v Nastavení → Online Cloud API (BYOK)."
                )
            )
            return@flow
        }

        if (provider == AiProviderType.OPENAI_COMPATIBLE && apiKey.isBlank() &&
            !settingsManager.cloudBaseUrl.contains("localhost") &&
            !settingsManager.cloudBaseUrl.contains("127.0.0.1") &&
            !settingsManager.cloudBaseUrl.contains("192.168.") &&
            !settingsManager.cloudBaseUrl.contains("10.")
        ) {
            _state.value = InferenceEngine.State.ModelReady
            emit(
                GenerationEvent.Error(
                    generationId = genId,
                    sequence = ++seq,
                    message = "Chybí API klíč pro ${settingsManager.cloudBaseUrl}. Zadejte klíč v Nastavení → Online Cloud API."
                )
            )
            return@flow
        }

        if (modelName.isBlank()) {
            val fetched = fetchAvailableModels(provider, apiKey, settingsManager.cloudBaseUrl).getOrNull()
            if (!fetched.isNullOrEmpty()) {
                settingsManager.setCachedCloudModels(provider, fetched)
                modelName = fetched.first().first
                settingsManager.cloudModelName = modelName
                Log.i(TAG, "🔍 Automaticky detekován výchozí model pro daný API klíč: $modelName (z ${fetched.size} dostupných)")
            } else {
                _state.value = InferenceEngine.State.ModelReady
                emit(
                    GenerationEvent.Error(
                        generationId = genId,
                        sequence = ++seq,
                        message = "Nebyl nalezen žádný dostupný model pro zadaný API klíč. Ověřte klíč v Nastavení → Online Cloud API."
                    )
                )
                return@flow
            }
        }

        // Konzume případného čekajícího obrázku / audia
        val mediaBytes = pendingMediaBytes
        val mediaMime = pendingMediaMimeType
        pendingMediaBytes = null
        val mediaB64 = if (mediaBytes != null && mediaBytes.isNotEmpty()) {
            Base64.encodeToString(mediaBytes, Base64.NO_WRAP)
        } else null

        val isAgentJsonMode = activeGrammar != null
        val currentTurn = ChatTurn(
            role = "user",
            text = message,
            mediaBase64 = mediaB64,
            mediaMimeType = if (mediaB64 != null) mediaMime else null
        )

        val accumulatedContent = StringBuilder()
        val accumulatedReasoning = StringBuilder()
        var estimatedGeneratedTokens = 0

        var attempt = 0
        val maxAttempts = 3
        var completed = false

        while (attempt < maxAttempts && !completed && !cancelRequested.get()) {
            attempt++
            var conn: HttpURLConnection? = null
            try {
                conn = if (provider == AiProviderType.GOOGLE_GEMINI) {
                    openGeminiConnection(apiKey, modelName, currentTurn, predictLength, isAgentJsonMode)
                } else {
                    openOpenAiConnection(apiKey, modelName, currentTurn, predictLength, isAgentJsonMode)
                }

                val statusCode = conn.responseCode
                if (statusCode == 429 && attempt < maxAttempts) {
                    val waitMs = 2000L * attempt
                    Log.w(TAG, "⚠️ HTTP 429 Rate Limit (pokus $attempt/$maxAttempts). Čekám ${waitMs}ms...")
                    conn.disconnect()
                    delay(waitMs)
                    continue
                }

                if (statusCode !in 200..299) {
                    val errBody = try {
                        conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
                    } catch (_: Exception) { "" }
                    val cleanErr = extractReadableApiError(statusCode, errBody)
                    Log.e(TAG, "❌ Cloud API HTTP $statusCode: $cleanErr")
                    _state.value = InferenceEngine.State.ModelReady
                    emit(GenerationEvent.Error(genId, ++seq, cleanErr))
                    return@flow
                }

                _state.value = InferenceEngine.State.Generating

                var inThinkBlock = false
                val tagCarryBuffer = StringBuilder()

                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String?
                    while (!cancelRequested.get()) {
                        line = reader.readLine() ?: break
                        val trimmed = line!!.trim()
                        if (!trimmed.startsWith("data:")) continue

                        val dataPayload = trimmed.removePrefix("data:").trim()
                        if (dataPayload.isEmpty() || dataPayload == "[DONE]") {
                            if (dataPayload == "[DONE]") break
                            continue
                        }

                        val json = try {
                            JSONObject(dataPayload)
                        } catch (e: Exception) {
                            continue
                        }

                        if (provider == AiProviderType.GOOGLE_GEMINI) {
                            val candidates = json.optJSONArray("candidates") ?: continue
                            if (candidates.length() == 0) continue
                            val firstCand = candidates.optJSONObject(0) ?: continue
                            val contentObj = firstCand.optJSONObject("content")
                            val parts = contentObj?.optJSONArray("parts")
                            if (parts != null) {
                                for (i in 0 until parts.length()) {
                                    val part = parts.optJSONObject(i) ?: continue
                                    val isNativeThought = part.optBoolean("thought", false)
                                    val textPiece = part.optString("text", "")
                                    if (textPiece.isNotEmpty()) {
                                        estimatedGeneratedTokens += (textPiece.length / 4).coerceAtLeast(1)
                                        if (isNativeThought && !isAgentJsonMode) {
                                            accumulatedReasoning.append(textPiece)
                                            emit(GenerationEvent.ReasoningDelta(genId, ++seq, textPiece))
                                        } else {
                                            processTextChunkWithThinkTags(
                                                chunk = textPiece,
                                                isAgentJsonMode = isAgentJsonMode,
                                                inThinkBlockRef = { inThinkBlock },
                                                setInThinkBlock = { inThinkBlock = it },
                                                carryBuffer = tagCarryBuffer,
                                                onReasoning = { r ->
                                                    accumulatedReasoning.append(r)
                                                    emit(GenerationEvent.ReasoningDelta(genId, ++seq, r))
                                                },
                                                onContent = { c ->
                                                    accumulatedContent.append(c)
                                                    emit(GenerationEvent.ContentDelta(genId, ++seq, c))
                                                }
                                            )
                                        }
                                    }
                                    val fnCall = part.optJSONObject("functionCall")
                                    if (fnCall != null) {
                                        val fnName = fnCall.optString("name", "")
                                        val fnArgs = fnCall.optJSONObject("args")?.toString() ?: "{}"
                                        if (fnName.isNotEmpty()) {
                                            emit(GenerationEvent.ToolCallDelta(genId, ++seq, 0, "call_1", fnName, fnArgs))
                                        }
                                    }
                                }
                            }
                        } else {
                            // OpenAI-compatible SSE delta
                            val choices = json.optJSONArray("choices") ?: continue
                            if (choices.length() == 0) continue
                            val firstChoice = choices.optJSONObject(0) ?: continue
                            val delta = firstChoice.optJSONObject("delta") ?: continue

                            val reasoningChunk = delta.optString("reasoning_content", "")
                                .ifEmpty { delta.optString("reasoning", "") }
                            if (reasoningChunk.isNotEmpty() && !isAgentJsonMode) {
                                estimatedGeneratedTokens += (reasoningChunk.length / 4).coerceAtLeast(1)
                                accumulatedReasoning.append(reasoningChunk)
                                emit(GenerationEvent.ReasoningDelta(genId, ++seq, reasoningChunk))
                            }

                            val contentChunk = delta.optString("content", "")
                            if (contentChunk.isNotEmpty()) {
                                estimatedGeneratedTokens += (contentChunk.length / 4).coerceAtLeast(1)
                                processTextChunkWithThinkTags(
                                    chunk = contentChunk,
                                    isAgentJsonMode = isAgentJsonMode,
                                    inThinkBlockRef = { inThinkBlock },
                                    setInThinkBlock = { inThinkBlock = it },
                                    carryBuffer = tagCarryBuffer,
                                    onReasoning = { r ->
                                        accumulatedReasoning.append(r)
                                        emit(GenerationEvent.ReasoningDelta(genId, ++seq, r))
                                    },
                                    onContent = { c ->
                                        accumulatedContent.append(c)
                                        emit(GenerationEvent.ContentDelta(genId, ++seq, c))
                                    }
                                )
                            }

                            val toolCalls = delta.optJSONArray("tool_calls")
                            if (toolCalls != null) {
                                for (i in 0 until toolCalls.length()) {
                                    val tc = toolCalls.optJSONObject(i) ?: continue
                                    val tcIdx = tc.optInt("index", i)
                                    val tcId = tc.optString("id", "").takeIf { it.isNotEmpty() }
                                    val fn = tc.optJSONObject("function") ?: continue
                                    val fnName = fn.optString("name", "").takeIf { it.isNotEmpty() }
                                    val fnArgs = fn.optString("arguments", "")
                                    emit(GenerationEvent.ToolCallDelta(genId, ++seq, tcIdx, tcId, fnName, fnArgs))
                                }
                            }
                        }
                    }
                }

                if (tagCarryBuffer.isNotEmpty()) {
                    val leftover = tagCarryBuffer.toString()
                    tagCarryBuffer.clear()
                    if (inThinkBlock && !isAgentJsonMode) {
                        accumulatedReasoning.append(leftover)
                        emit(GenerationEvent.ReasoningDelta(genId, ++seq, leftover))
                    } else {
                        accumulatedContent.append(leftover)
                        emit(GenerationEvent.ContentDelta(genId, ++seq, leftover))
                    }
                }

                completed = true
            } catch (e: Exception) {
                if (cancelRequested.get()) {
                    Log.i(TAG, "🛑 Cloud generování zrušeno uživatelem.")
                    break
                }
                val safeMsg = redactSecrets(e.localizedMessage ?: e.javaClass.simpleName, apiKey)
                Log.e(TAG, "❌ Výjimka při komunikaci s Cloud API (pokus $attempt/$maxAttempts): ${e.javaClass.simpleName} - $safeMsg")
                if (attempt >= maxAttempts) {
                    _state.value = InferenceEngine.State.ModelReady
                    emit(
                        GenerationEvent.Error(
                            genId,
                            ++seq,
                            "Chyba spojení s Cloud API: $safeMsg"
                        )
                    )
                    return@flow
                } else {
                    delay(1500L * attempt)
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {}
            }
        }

        // Uložit do konverzační historie (pokud nejde o jednorázový OS Agent JSON krok)
        if (!isAgentJsonMode && accumulatedContent.isNotEmpty()) {
            synchronized(conversationHistory) {
                conversationHistory.add(ChatTurn(role = "user", text = message))
                conversationHistory.add(ChatTurn(role = "model", text = accumulatedContent.toString()))
                while (conversationHistory.size > MAX_HISTORY_TURNS * 2) {
                    conversationHistory.removeAt(0)
                }
            }
        }

        _state.value = InferenceEngine.State.ModelReady
        emit(
            GenerationEvent.Finished(
                generationId = genId,
                sequence = ++seq,
                reason = if (cancelRequested.get()) FinishReason.CANCELLED else FinishReason.EOG,
                promptTokens = (message.length / 4).coerceAtLeast(1),
                generatedTokens = estimatedGeneratedTokens
            )
        )
    }.flowOn(Dispatchers.IO)

    override fun continueGeneration(predictLength: Int): Flow<GenerationEvent> {
        val toolPrompt = pendingToolResultPrompt ?: "Pokračuj v odpovědi na základě výsledku nástroje."
        pendingToolResultPrompt = null
        return sendUserPromptEvents(toolPrompt, predictLength)
    }

    override fun continueGenerationText(predictLength: Int): Flow<String> = flow {
        continueGeneration(predictLength).collect { event ->
            when (event) {
                is GenerationEvent.ContentDelta -> emit(event.text)
                is GenerationEvent.Error -> throw IllegalStateException(event.message)
                else -> {}
            }
        }
    }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String {
        return "Cloud API režim (${settingsManager.getActiveCloudDisplayName()}) — vzdálená inference na serveru poskytovatele."
    }

    override suspend fun setGrammar(grammar: String?) {
        this.activeGrammar = grammar
    }

    override fun setToolDefinitions(toolsJson: String) {
        this.toolDefinitionsJson = toolsJson
    }

    override fun setToolPolicy(policy: Int) {
        this.toolPolicyMode = policy
    }

    override suspend fun submitToolResult(callId: String, name: String, content: String): Boolean {
        val formatted = "<tool_response>\n[Nástroj: $name | ID: $callId]\n$content\n</tool_response>\nNa základě výsledku nástroje napiš přímou a přehlednou odpověď uživateli."
        pendingToolResultPrompt = formatted
        return true
    }

    override fun getModelCapabilities(): NativeModelCapabilities {
        return NativeModelCapabilities(
            hasChatTemplate = true,
            supportsJinja = true,
            supportsThinking = true,
            supportsTools = true,
            supportsToolCalls = true,
            detectedFormat = 1,
            handlerName = if (settingsManager.aiProviderType == AiProviderType.GOOGLE_GEMINI) "gemini_cloud" else "openai_cloud"
        )
    }

    override fun cancelGeneration() {
        cancelRequested.set(true)
        if (_state.value is InferenceEngine.State.Generating || _state.value is InferenceEngine.State.ProcessingUserPrompt) {
            _state.value = InferenceEngine.State.ModelReady
        }
    }

    override fun cleanUp() {
        cancelRequested.set(true)
        pendingMediaBytes = null
        pendingToolResultPrompt = null
        synchronized(conversationHistory) {
            conversationHistory.clear()
        }
        _state.value = InferenceEngine.State.ModelReady
    }

    override fun destroy() {
        cleanUp()
    }

    private suspend inline fun processTextChunkWithThinkTags(
        chunk: String,
        isAgentJsonMode: Boolean,
        inThinkBlockRef: () -> Boolean,
        setInThinkBlock: (Boolean) -> Unit,
        carryBuffer: StringBuilder,
        crossinline onReasoning: suspend (String) -> Unit,
        crossinline onContent: suspend (String) -> Unit
    ) {
        if (isAgentJsonMode) {
            onContent(chunk)
            return
        }
        carryBuffer.append(chunk)
        val startTag = "<think>"
        val endTag = "</think>"

        while (carryBuffer.isNotEmpty()) {
            val currentStr = carryBuffer.toString()
            if (!inThinkBlockRef()) {
                val startIdx = currentStr.indexOf(startTag)
                if (startIdx >= 0) {
                    if (startIdx > 0) {
                        onContent(currentStr.substring(0, startIdx))
                    }
                    carryBuffer.delete(0, startIdx + startTag.length)
                    setInThinkBlock(true)
                } else {
                    val partialLen = findPartialSuffixLength(currentStr, startTag)
                    val safeLen = currentStr.length - partialLen
                    if (safeLen > 0) {
                        onContent(currentStr.substring(0, safeLen))
                        carryBuffer.delete(0, safeLen)
                    }
                    break
                }
            } else {
                val endIdx = currentStr.indexOf(endTag)
                if (endIdx >= 0) {
                    if (endIdx > 0) {
                        onReasoning(currentStr.substring(0, endIdx))
                    }
                    carryBuffer.delete(0, endIdx + endTag.length)
                    setInThinkBlock(false)
                } else {
                    val partialLen = findPartialSuffixLength(currentStr, endTag)
                    val safeLen = currentStr.length - partialLen
                    if (safeLen > 0) {
                        onReasoning(currentStr.substring(0, safeLen))
                        carryBuffer.delete(0, safeLen)
                    }
                    break
                }
            }
        }
    }

    private fun findPartialSuffixLength(text: String, tag: String): Int {
        val maxCheck = minOf(text.length, tag.length - 1)
        for (len in maxCheck downTo 1) {
            if (text.endsWith(tag.substring(0, len))) {
                return len
            }
        }
        return 0
    }

    private fun openGeminiConnection(
        apiKey: String,
        modelName: String,
        currentTurn: ChatTurn,
        predictLength: Int,
        isAgentJsonMode: Boolean
    ): HttpURLConnection {
        val cleanModel = modelName.removePrefix("models/")
        // Bezpečnost (MASVS-NETWORK): API klíč zásadně v HTTP hlavičce x-goog-api-key, nikdy v URL query stringu
        val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:streamGenerateContent?alt=sse"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("x-goog-api-key", apiKey)
        }

        val root = JSONObject()

        // 1. System Instruction
        val effectiveSystemPrompt = buildEffectiveSystemPrompt(isAgentJsonMode)
        if (effectiveSystemPrompt.isNotBlank()) {
            val sysParts = JSONArray().put(JSONObject().put("text", effectiveSystemPrompt))
            root.put("system_instruction", JSONObject().put("parts", sysParts))
        }

        // 2. Contents (historie + aktuální turn)
        val contentsArray = JSONArray()
        if (!isAgentJsonMode) {
            synchronized(conversationHistory) {
                for (turn in conversationHistory) {
                    val role = if (turn.role == "user") "user" else "model"
                    val parts = JSONArray().put(JSONObject().put("text", turn.text))
                    contentsArray.put(JSONObject().put("role", role).put("parts", parts))
                }
            }
        }

        val userParts = JSONArray()
        if (!currentTurn.mediaBase64.isNullOrEmpty() && !currentTurn.mediaMimeType.isNullOrEmpty()) {
            val inlineData = JSONObject()
                .put("mime_type", currentTurn.mediaMimeType)
                .put("data", currentTurn.mediaBase64)
            userParts.put(JSONObject().put("inline_data", inlineData))
        }
        userParts.put(JSONObject().put("text", currentTurn.text))
        contentsArray.put(JSONObject().put("role", "user").put("parts", userParts))
        root.put("contents", contentsArray)

        // 3. GenerationConfig
        val genConfig = JSONObject()
        genConfig.put("temperature", if (isAgentJsonMode) 0.1 else temperature.toDouble())
        genConfig.put("topP", topP.toDouble())
        if (topK > 0) {
            genConfig.put("topK", topK)
        }
        val maxTokens = if (predictLength > 0) predictLength.coerceAtLeast(256) else 4096
        genConfig.put("maxOutputTokens", maxTokens)

        if (isAgentJsonMode) {
            genConfig.put("responseMimeType", "application/json")
        } else if (thinkingEnabled && (cleanModel.contains("2.5") || cleanModel.contains("3.") || cleanModel.contains("thinking"))) {
            val thinkingCfg = JSONObject().put("includeThoughts", true)
            if (maxThinkingTokens > 0) {
                thinkingCfg.put("thinkingBudget", maxThinkingTokens)
            }
            genConfig.put("thinkingConfig", thinkingCfg)
        }

        root.put("generationConfig", genConfig)

        val payload = root.toString()
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
            writer.flush()
        }
        return conn
    }

    private fun openOpenAiConnection(
        apiKey: String,
        modelName: String,
        currentTurn: ChatTurn,
        predictLength: Int,
        isAgentJsonMode: Boolean
    ): HttpURLConnection {
        val endpoint = resolveOpenAiChatEndpoint(settingsManager.cloudBaseUrl)
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "text/event-stream")
            if (apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
        }

        val root = JSONObject()
        root.put("model", modelName)
        root.put("stream", true)
        root.put("temperature", if (isAgentJsonMode) 0.1 else temperature.toDouble())
        root.put("top_p", topP.toDouble())
        val maxTokens = if (predictLength > 0) predictLength.coerceAtLeast(256) else 4096
        root.put("max_tokens", maxTokens)

        if (isAgentJsonMode) {
            root.put("response_format", JSONObject().put("type", "json_object"))
        }

        val messagesArray = JSONArray()
        val effectiveSystemPrompt = buildEffectiveSystemPrompt(isAgentJsonMode)
        if (effectiveSystemPrompt.isNotBlank()) {
            messagesArray.put(JSONObject().put("role", "system").put("content", effectiveSystemPrompt))
        }

        if (!isAgentJsonMode) {
            synchronized(conversationHistory) {
                for (turn in conversationHistory) {
                    val role = if (turn.role == "user") "user" else "assistant"
                    messagesArray.put(JSONObject().put("role", role).put("content", turn.text))
                }
            }
        }

        if (!currentTurn.mediaBase64.isNullOrEmpty() && currentTurn.mediaMimeType?.startsWith("image/") == true) {
            val contentParts = JSONArray()
            val dataUri = "data:${currentTurn.mediaMimeType};base64,${currentTurn.mediaBase64}"
            contentParts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUri))
            )
            contentParts.put(
                JSONObject()
                    .put("type", "text")
                    .put("text", currentTurn.text)
            )
            messagesArray.put(JSONObject().put("role", "user").put("content", contentParts))
        } else {
            messagesArray.put(JSONObject().put("role", "user").put("content", currentTurn.text))
        }

        root.put("messages", messagesArray)

        val payload = root.toString()
        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
            writer.write(payload)
            writer.flush()
        }
        return conn
    }

    private fun buildEffectiveSystemPrompt(isAgentJsonMode: Boolean): String {
        if (!isAgentJsonMode) return currentSystemPrompt
        val jsonSchemaInstruction = """
            CRITICAL JSON OUTPUT REQUIREMENT:
            Respond ONLY with a single valid JSON object matching this exact schema (no markdown fences, no extra text):
            {"thought": "brief reasoning in Czech", "action": "click|input_text|scroll_down|scroll_up|scroll_to_top|scroll_to_bottom|back|home|speak|open_app|recent_apps|notifications|call|sms|open_url|compose_email|answer_call|reject_call|open_settings|set_wifi|set_bluetooth|set_flashlight|set_nfc|set_airplane|kill_app|set_volume|set_brightness|set_rotation|screenshot|lock_screen|quick_settings|split_screen|power_dialog|swipe_left|swipe_right|swipe_up|swipe_down|long_press|double_tap|media_play_pause|media_next|media_previous|volume_up|volume_down|brightness_up|brightness_down|nlp_proofread|nlp_rewrite|nlp_translate|nlp_summarize|nlp_sentiment|nlp_smart_reply", "target_id": 1, "input_text": "", "detections": [{"label": "element", "confidence": 0.95, "xMin": 0.0, "yMin": 0.0, "xMax": 100.0, "yMax": 100.0}]}
        """.trimIndent()
        return if (currentSystemPrompt.isBlank()) {
            jsonSchemaInstruction
        } else {
            "$currentSystemPrompt\n\n$jsonSchemaInstruction"
        }
    }

    private fun redactSecrets(raw: String, activeKey: String = ""): String {
        var sanitized = raw
        if (activeKey.length >= 6) {
            sanitized = sanitized.replace(activeKey, "***REDACTED***")
        }
        sanitized = sanitized
            .replace(Regex("([?&]key=)[^&\\s\"']+"), "$1***REDACTED***")
            .replace(Regex("AIza[0-9A-Za-z_\\-]{20,}"), "AIza***REDACTED***")
            .replace(Regex("sk-[0-9A-Za-z_\\-]{16,}"), "sk-***REDACTED***")
            .replace(Regex("gsk_[0-9A-Za-z_\\-]{16,}"), "gsk_***REDACTED***")
        return sanitized
    }

    private fun extractReadableApiError(statusCode: Int, rawBody: String): String {
        val parsedMsg = try {
            val json = JSONObject(rawBody)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message").takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
        val detail = redactSecrets(
            parsedMsg ?: rawBody.take(240).ifBlank { "Neznámá chyba serveru" },
            settingsManager.getActiveCloudApiKey().trim()
        )
        return when (statusCode) {
            400 -> "Neplatný požadavek na Cloud API (HTTP 400): $detail"
            401, 403 -> "Neplatný nebo neautorizovaný API klíč (HTTP $statusCode): $detail"
            404 -> "Model '${settingsManager.cloudModelName}' nebyl nalezen (HTTP 404): $detail"
            429 -> "Překročen limit dotazů (Rate Limit HTTP 429): $detail"
            else -> "Chyba Cloud API (HTTP $statusCode): $detail"
        }
    }

    private fun detectMimeType(bytes: ByteArray): String {
        if (bytes.size >= 4) {
            // PNG: 89 50 4E 47
            if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
            ) {
                return "image/png"
            }
            // JPEG: FF D8 FF
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) {
                return "image/jpeg"
            }
            // RIFF (WAV nebo WEBP)
            if (bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes.size >= 12
            ) {
                if (bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
                    bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
                ) {
                    return "audio/wav"
                }
                if (bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() &&
                    bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte()
                ) {
                    return "image/webp"
                }
            }
            // fLaC
            if (bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
            ) {
                return "audio/flac"
            }
            // ID3 nebo MP3 sync
            if ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
                (bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0)
            ) {
                return "audio/mp3"
            }
        }
        return "image/jpeg"
    }

    /**
     * Dynamické načtení seznamu dostupných modelů ze serveru poskytovatele podle zadaného API klíče.
     * Vrací seznam dvojic (modelId to displayName).
     */
    fun fetchAvailableModels(
        provider: AiProviderType = settingsManager.aiProviderType,
        apiKey: String = settingsManager.getActiveCloudApiKey().trim(),
        baseUrl: String = settingsManager.cloudBaseUrl.trim()
    ): Result<List<Pair<String, String>>> {
        var conn: HttpURLConnection? = null
        return try {
            if (provider == AiProviderType.GOOGLE_GEMINI) {
                if (apiKey.isBlank()) {
                    return Result.failure(IllegalArgumentException("Zadejte Google AI Studio API klíč"))
                }
                // Bezpečnost (MASVS-NETWORK): API klíč v hlavičce x-goog-api-key, nikdy v URL query stringu
                val endpoint = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=100"
                conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("x-goog-api-key", apiKey)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = try { conn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                    return Result.failure(IllegalStateException(extractReadableApiError(code, err)))
                }
                val body = conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                val root = JSONObject(body)
                val modelsArr = root.optJSONArray("models") ?: JSONArray()
                val discovered = mutableListOf<Pair<String, String>>()

                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.optJSONObject(i) ?: continue
                    val rawName = obj.optString("name", "").trim()
                    if (rawName.isEmpty()) continue
                    val id = rawName.removePrefix("models/")
                    val displayName = obj.optString("displayName", id).trim().ifEmpty { id }

                    // Ověřit, že model podporuje generateContent (chat/multimodal)
                    val methods = obj.optJSONArray("supportedGenerationMethods")
                    var supportsGenerate = false
                    if (methods != null) {
                        for (m in 0 until methods.length()) {
                            if (methods.optString(m) == "generateContent") {
                                supportsGenerate = true
                                break
                            }
                        }
                    }
                    // Vyfiltrovat čistě TTS / imagen / embedding / aqa modely, které nejsou určeny pro standardní text/vision chat
                    val lowerId = id.lowercase()
                    val isExcludedSpecial = lowerId.contains("embedding") ||
                        lowerId.contains("aqa") ||
                        lowerId.contains("imagen") ||
                        lowerId.contains("veo") ||
                        lowerId.endsWith("-tts")
                    if (supportsGenerate && !isExcludedSpecial) {
                        discovered.add(id to "$displayName ($id)")
                    }
                }

                // Seřadit přirozeně (modely gemini-* nahoře, sestupně dle verze) bez jakýchkoliv hardcoded názvů
                val sorted = discovered.sortedWith(
                    compareBy<Pair<String, String>> { (id, _) ->
                        when {
                            id.startsWith("gemini-") -> 0
                            id.startsWith("gemma-") -> 1
                            else -> 2
                        }
                    }.thenByDescending { it.first }
                )

                if (sorted.isEmpty()) {
                    Result.failure(IllegalStateException("API klíč je platný, ale server nevrátil žádné modely podporující generateContent."))
                } else {
                    Result.success(sorted)
                }
            } else if (provider == AiProviderType.OPENAI_COMPATIBLE) {
                val selfIp = detectDeviceWifiIpv4()
                var effectiveBaseUrl = baseUrl.trim()
                if (!selfIp.isNullOrBlank() && effectiveBaseUrl.contains(selfIp)) {
                    val fallbackHost = settingsManager.localWifiHostIp.takeIf { it.isNotBlank() && it != selfIp } ?: "192.168.0.83"
                    effectiveBaseUrl = effectiveBaseUrl.replace(selfIp, fallbackHost)
                    settingsManager.localWifiHostIp = fallbackHost
                    settingsManager.cloudBaseUrl = effectiveBaseUrl
                    Log.w(TAG, "⚠️ Auto-heal: Nahrazena vlastní IP telefonu ($selfIp) za LAN host ($fallbackHost) -> $effectiveBaseUrl")
                }

                val cleanBase = effectiveBaseUrl.removeSuffix("/").removeSuffix("/chat/completions")
                val baseCandidates = when {
                    cleanBase.endsWith("/models") || cleanBase.endsWith("/tags") -> listOf(cleanBase)
                    cleanBase.endsWith("/v1") || cleanBase.contains("/v1/") || cleanBase.endsWith("/v4") -> listOf(
                        "$cleanBase/models",
                        "${cleanBase.removeSuffix("/v1")}/api/tags"
                    )
                    else -> listOf(
                        "$cleanBase/v1/models",
                        "$cleanBase/models",
                        "$cleanBase/api/tags"
                    )
                }

                // Pokud se uživatel v LAN režimu dotazuje na port 11434, kde Ollama poslouchá pouze na 127.0.0.1
                // za bránou MojeLlama Local Studio (port 8765), přidáme jako záložní průzkum port 8765.
                val candidateEndpoints = if (cleanBase.contains(":11434") &&
                    (cleanBase.contains("192.168.") || cleanBase.contains("10.") || cleanBase.contains("172."))
                ) {
                    val studioBase = cleanBase.replace(":11434", ":8765").removeSuffix("/v1")
                    baseCandidates + listOf("$studioBase/v1/models")
                } else {
                    baseCandidates
                }

                var lastError: Exception? = null
                for (endpoint in candidateEndpoints) {
                    var stepConn: HttpURLConnection? = null
                    try {
                        stepConn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                            requestMethod = "GET"
                            connectTimeout = 5_000
                            readTimeout = 8_000
                            setRequestProperty("Accept", "application/json")
                            if (apiKey.isNotBlank()) {
                                setRequestProperty("Authorization", "Bearer $apiKey")
                            }
                        }
                        val code = stepConn.responseCode
                        if (code !in 200..299) {
                            val err = try { stepConn.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: "" } catch (_: Exception) { "" }
                            lastError = IllegalStateException(extractReadableApiError(code, err))
                            continue
                        }
                        val body = stepConn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                        val root = JSONObject(body)
                        val dataArr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
                        val discovered = mutableListOf<Pair<String, String>>()
                        for (i in 0 until dataArr.length()) {
                            val obj = dataArr.optJSONObject(i) ?: continue
                            val id = obj.optString("id", obj.optString("name", "")).trim()
                            if (id.isEmpty()) continue
                            val lower = id.lowercase()
                            if (lower.contains("whisper") || lower.contains("tts") || lower.contains("embedding") || lower.contains("moderation") || lower.contains("dall-e")) {
                                continue
                            }
                            val displayName = obj.optString("name", id).trim().ifEmpty { id }
                            val label = if (displayName != id) "$displayName ($id)" else id
                            discovered.add(id to label)
                        }
                        if (discovered.isNotEmpty()) {
                            if (cleanBase.contains(":11434") && endpoint.contains(":8765/v1/models")) {
                                val healedStudioUrl = endpoint.removeSuffix("/models")
                                settingsManager.cloudBaseUrl = healedStudioUrl
                                Log.i(TAG, "✅ Auto-heal LAN Ollama -> MojeLlama Studio Gateway: $healedStudioUrl")
                            }
                            return Result.success(discovered)
                        } else {
                            lastError = IllegalStateException("Endpoint $endpoint vrátil prázdný seznam modelů.")
                        }
                    } catch (e: Exception) {
                        lastError = e
                    } finally {
                        try { stepConn?.disconnect() } catch (_: Exception) {}
                    }
                }
                val finalErr = lastError ?: IllegalStateException("Nelze načíst modely z $effectiveBaseUrl")
                if ((finalErr is java.net.ConnectException || finalErr is java.net.SocketTimeoutException) &&
                    (effectiveBaseUrl.contains("127.0.0.1") || effectiveBaseUrl.contains("localhost"))
                ) {
                    return Result.failure(
                        IllegalStateException(
                            "Režim ADB (127.0.0.1): Nelze se připojit k $effectiveBaseUrl. Připojte USB/ADB tunel (adb reverse tcp:8765 tcp:8765 / 11434) nebo přepněte výše na režim 📶 Wi-Fi (LAN)."
                        )
                    )
                }
                if ((finalErr is java.net.ConnectException || finalErr is java.net.SocketTimeoutException) &&
                    (effectiveBaseUrl.contains("192.168.") || effectiveBaseUrl.contains("10.") || effectiveBaseUrl.contains("172."))
                ) {
                    return Result.failure(
                        IllegalStateException(
                            "Režim Wi-Fi ($effectiveBaseUrl): Server na PC neodpověděl. Ověřte, že PC i telefon jsou na stejné Wi-Fi a že v MojeLlama Studiu je zapnuto 'Povolit Wi-Fi / LAN přístup'."
                        )
                    )
                }
                Result.failure(finalErr)
            } else {
                Result.failure(IllegalArgumentException("Poskytovatel je v Offline režimu"))
            }
        } catch (e: Exception) {
            val safeMsg = redactSecrets(e.localizedMessage ?: e.javaClass.simpleName, apiKey)
            Log.e(TAG, "❌ Chyba při načítání seznamu modelů z Cloud API: ${e.javaClass.simpleName} - $safeMsg")
            Result.failure(IllegalStateException(safeMsg))
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    /**
     * Prohledá aktuální Wi-Fi / LAN podsíť telefonu (např. 192.168.0.1..254) a najde běžící
     * lokální AI servery (MojeLlama Studio 8765, Ollama 11434, LM Studio 1234, vLLM 8000).
     * Přeskakuje vlastní IP adresu telefonu (aby neskenoval vlastní adbd reverse porty)
     * a ověřuje HTTP 200 na /v1/models nebo /api/tags.
     */
    suspend fun scanWifiLanForLocalStudios(
        ports: List<Int> = listOf(8765, 11434, 1234, 8000)
    ): List<Pair<String, String>> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val localIp = detectDeviceWifiIpv4() ?: return@withContext emptyList()
        val subnetPrefix = localIp.substringBeforeLast(".", "")
        if (subnetPrefix.isBlank()) return@withContext emptyList()

        data class DiscoveredEndpoint(val portPriority: Int, val title: String, val url: String)
        val found = java.util.Collections.synchronizedList(mutableListOf<DiscoveredEndpoint>())
        val executor = java.util.concurrent.Executors.newFixedThreadPool(48)
        try {
            val futures = (1..254).mapNotNull { hostNum ->
                val candidateIp = "$subnetPrefix.$hostNum"
                // Kritické: nikdy neskenovat vlastní IP adresu telefonu (kde může naslouchat adbd reverse)
                if (candidateIp == localIp) return@mapNotNull null
                executor.submit {
                    for ((portIdx, port) in ports.withIndex()) {
                        val isOpen = try {
                            java.net.Socket().use { socket ->
                                socket.connect(java.net.InetSocketAddress(candidateIp, port), 180)
                                true
                            }
                        } catch (_: Exception) {
                            false
                        }
                        if (isOpen && verifyHttpModelsEndpoint(candidateIp, port)) {
                            val title = when (port) {
                                8765 -> "📶 MojeLlama ($candidateIp:8765)"
                                11434 -> "📶 Ollama ($candidateIp:11434)"
                                1234 -> "📶 LM Studio ($candidateIp:1234)"
                                8000 -> "📶 vLLM ($candidateIp:8000)"
                                else -> "📶 LAN Server ($candidateIp:$port)"
                            }
                            found.add(DiscoveredEndpoint(portIdx, title, "http://$candidateIp:$port/v1"))
                        }
                    }
                }
            }
            futures.forEach { try { it.get() } catch (_: Exception) {} }
        } finally {
            executor.shutdownNow()
        }
        found.sortedWith(compareBy<DiscoveredEndpoint> { it.portPriority }.thenBy { it.title })
            .map { it.title to it.url }
    }

    private fun verifyHttpModelsEndpoint(ip: String, port: Int): Boolean {
        val paths = if (port == 11434) listOf("/v1/models", "/api/tags") else listOf("/v1/models")
        for (path in paths) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("http://$ip:$port$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 700
                    readTimeout = 700
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode in 200..299) {
                    return true
                }
            } catch (_: Exception) {
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
            }
        }
        return false
    }

    fun detectDeviceWifiIpv4(): String? {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val sortedIfaces = interfaces.sortedBy { iface ->
                val n = iface.name.lowercase()
                when {
                    n.startsWith("wlan") || n.startsWith("wifi") -> 0
                    n.startsWith("eth") || n.startsWith("en") -> 1
                    else -> 2
                }
            }
            for (iface in sortedIfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveOpenAiChatEndpoint(rawBaseUrl: String): String {
        val selfIp = detectDeviceWifiIpv4()
        var clean = rawBaseUrl.trim().removeSuffix("/")
        if (!selfIp.isNullOrBlank() && clean.contains(selfIp)) {
            val fallbackHost = settingsManager.localWifiHostIp.takeIf { it.isNotBlank() && it != selfIp } ?: "192.168.0.83"
            clean = clean.replace(selfIp, fallbackHost)
        }
        return when {
            clean.endsWith("/chat/completions") -> clean
            clean.endsWith("/v1") || clean.contains("/v1/") || clean.endsWith("/v4") -> "$clean/chat/completions"
            else -> "$clean/v1/chat/completions"
        }
    }
}
