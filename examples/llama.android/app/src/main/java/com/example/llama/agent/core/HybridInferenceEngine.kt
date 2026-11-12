package com.example.llama.agent.core

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.GenerationEvent
import com.arm.aichat.InferenceEngine
import com.arm.aichat.NativeModelCapabilities
import com.arm.aichat.isModelLoaded
import com.example.llama.SettingsManager
import com.example.llama.SettingsManager.AiProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * HybridInferenceEngine — Jednotný Singleton proxy přepínač mezi lokálním C++ GGUF enginem
 * ([AiChat.getInferenceEngine]) a online cloudovým API ([CloudInferenceEngine]).
 *
 * Umožňuje celé aplikaci (MainActivity, AgentCoordinator, LlamaGenerationService, AudioStudio)
 * pozorovat jediný reaktivní [state] StateFlow a okamžitě přepínat mezi:
 * 1. 100% Offline GGUF modely (NPU/GPU/CPU přes llama.cpp JNI)
 * 2. Online Cloud API (Google AI Studio Gemini API / OpenAI-compatible BYOK)
 * se společnou pamětí, nástroji (WebToolsDispatcher, RAG) a senzory (IrisMotion, ScreenParser).
 */
class HybridInferenceEngine private constructor(context: Context) : InferenceEngine {

    companion object {
        private const val TAG = "HybridInferenceEngine"

        @Volatile
        private var INSTANCE: HybridInferenceEngine? = null

        fun getInstance(context: Context): HybridInferenceEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: HybridInferenceEngine(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val appContext: Context = context.applicationContext
    private val settingsManager = SettingsManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var localEngineInitialized = false

    private val localEngine: InferenceEngine by lazy {
        localEngineInitialized = true
        AiChat.getInferenceEngine(appContext)
    }

    val cloudEngine: CloudInferenceEngine = CloudInferenceEngine(appContext)

    @Volatile
    private var useCloudDelegate: Boolean = settingsManager.isCloudApiActive

    private val _state = MutableStateFlow<InferenceEngine.State>(
        if (settingsManager.isCloudApiActive) {
            cloudEngine.markReady()
            InferenceEngine.State.ModelReady
        } else {
            InferenceEngine.State.Initialized
        }
    )
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    init {
        scope.launch {
            cloudEngine.state.collect { cloudState ->
                if (useCloudDelegate) {
                    _state.value = cloudState
                }
            }
        }
    }

    @Volatile
    private var localStateCollectorStarted = false

    private fun ensureLocalStateCollector() {
        if (!localStateCollectorStarted) {
            synchronized(this) {
                if (!localStateCollectorStarted) {
                    localStateCollectorStarted = true
                    scope.launch {
                        localEngine.state.collect { localState ->
                            if (!useCloudDelegate) {
                                _state.value = localState
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Synchronizuje aktivní delegát s aktuálním nastavením [SettingsManager].
     * Volá se při návratu z Nastavení nebo před spuštěním Agenta.
     */
    fun syncWithSettings() {
        val shouldUseCloud = settingsManager.isCloudApiActive
        if (shouldUseCloud) {
            if (!useCloudDelegate) {
                Log.i(TAG, "☁️ Přepínám HybridInferenceEngine do Online Cloud API režimu (${settingsManager.getActiveCloudDisplayName()})")
                if (localEngineInitialized && localEngine.state.value.isModelLoaded) {
                    try {
                        Log.i(TAG, "🧹 Uvolňuji lokální GGUF model z RAM při přechodu na Cloud API")
                        localEngine.cleanUp()
                    } catch (e: Exception) {
                        Log.w(TAG, "Chyba při uvolňování lokálního modelu: ${e.message}")
                    }
                }
                useCloudDelegate = true
            }
            cloudEngine.markReady()
            _state.value = cloudEngine.state.value
        } else {
            if (useCloudDelegate) {
                Log.i(TAG, "📱 Přepínám HybridInferenceEngine zpět do Offline GGUF režimu")
                useCloudDelegate = false
                ensureLocalStateCollector()
                _state.value = localEngine.state.value
            }
        }
    }

    fun isCurrentlyCloud(): Boolean = useCloudDelegate

    private fun activeEngine(): InferenceEngine {
        return if (useCloudDelegate) {
            cloudEngine
        } else {
            ensureLocalStateCollector()
            localEngine
        }
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
        if (pathToModel.startsWith("cloud://")) {
            if (localEngineInitialized && localEngine.state.value.isModelLoaded) {
                try {
                    Log.i(TAG, "🧹 Uvolňuji lokální GGUF model z RAM před aktivací Cloud modelu: $pathToModel")
                    localEngine.cleanUp()
                } catch (e: Exception) {
                    Log.w(TAG, "Chyba při cleanUp lokálního enginu: ${e.message}")
                }
            }
            useCloudDelegate = true
            cloudEngine.loadModel(
                pathToModel, projectorPath, kvCacheMode, useMmap,
                nCtx, nBatch, nUBatch, flashAttn, backend, strictBackend, gpuLayers
            )
            _state.value = cloudEngine.state.value
        } else {
            if (settingsManager.aiProviderType != AiProviderType.LOCAL_OFFLINE) {
                Log.i(TAG, "📱 Detekováno načtení GGUF souboru ($pathToModel) — přepínám poskytovatele na LOCAL_OFFLINE")
                settingsManager.aiProviderType = AiProviderType.LOCAL_OFFLINE
            }
            useCloudDelegate = false
            ensureLocalStateCollector()
            localEngine.loadModel(
                pathToModel, projectorPath, kvCacheMode, useMmap,
                nCtx, nBatch, nUBatch, flashAttn, backend, strictBackend, gpuLayers
            )
            _state.value = localEngine.state.value
        }
    }

    override fun getLoadedBackendInfo(): String =
        activeEngine().getLoadedBackendInfo()

    override suspend fun setSystemPrompt(systemPrompt: String) =
        activeEngine().setSystemPrompt(systemPrompt)

    override suspend fun processImage(imageBytes: ByteArray): Boolean =
        activeEngine().processImage(imageBytes)

    override fun hasProjector(): Boolean =
        activeEngine().hasProjector()

    override suspend fun resetContext() {
        cloudEngine.resetContext()
        if (localEngineInitialized) {
            localEngine.resetContext()
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
        cloudEngine.configureSampling(
            temp, topK, topP, minP, xtcProb, xtcThreshold, typicalP,
            dynTempRange, dynTempExp, penaltyRepeat, penaltyFreq, penaltyPresent,
            penaltyLastN, mirostat, mirostatTau, mirostatEta, samplersSequence,
            useBackendSampling, ignoreEos, noPerf, timingPerToken
        )
        if (!useCloudDelegate) {
            ensureLocalStateCollector()
            localEngine.configureSampling(
                temp, topK, topP, minP, xtcProb, xtcThreshold, typicalP,
                dynTempRange, dynTempExp, penaltyRepeat, penaltyFreq, penaltyPresent,
                penaltyLastN, mirostat, mirostatTau, mirostatEta, samplersSequence,
                useBackendSampling, ignoreEos, noPerf, timingPerToken
            )
        }
    }

    override fun configureThinking(enabled: Boolean, maxThinkingTokens: Int, effort: String) {
        cloudEngine.configureThinking(enabled, maxThinkingTokens, effort)
        if (!useCloudDelegate && localEngineInitialized) {
            localEngine.configureThinking(enabled, maxThinkingTokens, effort)
        } else if (!useCloudDelegate) {
            ensureLocalStateCollector()
            localEngine.configureThinking(enabled, maxThinkingTokens, effort)
        }
    }

    override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> =
        activeEngine().sendUserPrompt(message, predictLength)

    override fun sendUserPromptEvents(message: String, predictLength: Int): Flow<GenerationEvent> =
        activeEngine().sendUserPromptEvents(message, predictLength)

    override fun continueGeneration(predictLength: Int): Flow<GenerationEvent> =
        activeEngine().continueGeneration(predictLength)

    override fun continueGenerationText(predictLength: Int): Flow<String> =
        activeEngine().continueGenerationText(predictLength)

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
        activeEngine().bench(pp, tg, pl, nr)

    override suspend fun setGrammar(grammar: String?) {
        cloudEngine.setGrammar(grammar)
        if (!useCloudDelegate && localEngineInitialized) {
            localEngine.setGrammar(grammar)
        } else if (!useCloudDelegate) {
            ensureLocalStateCollector()
            localEngine.setGrammar(grammar)
        }
    }

    override fun setToolDefinitions(toolsJson: String) {
        cloudEngine.setToolDefinitions(toolsJson)
        if (!useCloudDelegate) {
            ensureLocalStateCollector()
            localEngine.setToolDefinitions(toolsJson)
        }
    }

    override fun setToolPolicy(policy: Int) {
        cloudEngine.setToolPolicy(policy)
        if (!useCloudDelegate) {
            ensureLocalStateCollector()
            localEngine.setToolPolicy(policy)
        }
    }

    override suspend fun submitToolResult(callId: String, name: String, content: String): Boolean =
        activeEngine().submitToolResult(callId, name, content)

    override fun getModelCapabilities(): NativeModelCapabilities =
        activeEngine().getModelCapabilities()

    override fun cancelGeneration() {
        cloudEngine.cancelGeneration()
        if (localEngineInitialized) {
            localEngine.cancelGeneration()
        }
    }

    override fun cleanUp() {
        if (useCloudDelegate) {
            cloudEngine.cleanUp()
        } else if (localEngineInitialized) {
            localEngine.cleanUp()
        }
    }

    override fun destroy() {
        cloudEngine.destroy()
        if (localEngineInitialized) {
            localEngine.destroy()
        }
    }
}
