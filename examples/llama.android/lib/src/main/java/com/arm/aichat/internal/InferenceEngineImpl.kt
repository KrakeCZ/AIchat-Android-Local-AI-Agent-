package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import com.arm.aichat.BackendInitializationException
import dalvik.annotation.optimization.FastNative

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.arm.aichat.GenerationEvent
import com.arm.aichat.NativeGenerationEvent

internal class InferenceEngineImpl private constructor(private val nativeLibDir: String) :
        InferenceEngine {

    companion object {
        private val TAG = InferenceEngineImpl::class.java.simpleName

        @Volatile private var instance: InferenceEngine? = null

        internal fun getInstance(context: Context) =
                instance
                        ?: synchronized(this) {
                            val nativeLibDir = context.applicationInfo.nativeLibraryDir
                            require(nativeLibDir.isNotBlank()) {
                                "Expected a valid native library path!"
                            }

                            try {
                                Log.i(TAG, "Instantiating InferenceEngineImpl,,,")
                                InferenceEngineImpl(nativeLibDir).also { instance = it }
                            } catch (e: UnsatisfiedLinkError) {
                                Log.e(TAG, "Failed to load native library from $nativeLibDir", e)
                                throw e
                            }
                        }
    }

    /** JNI methods */
    private external fun init(nativeLibDir: String)

    private external fun loadModelNative(
            modelPath: String,
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
    ): Long

    private external fun getLoadedBackendInfoNative(): String

    private external fun processImageNative(imageBytes: ByteArray): Boolean

    private external fun hasProjectorNative(): Boolean

    private external fun prepare(): Int

    private external fun systemInfo(): String

    private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    private external fun processSystemPrompt(systemPrompt: String): Int

    private external fun configureSamplingNative(
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
    )

    private external fun configureThinkingNative(
            enabled: Boolean,
            maxThinkingTokens: Int,
            effort: String
    )

    private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    private external fun generateNextToken(): String?

    private external fun unload()

    private external fun shutdown()

    private external fun setGrammarNative(grammar: String?)

    private val _state =
            MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile private var _cancelGeneration = false
    @Volatile private var isNativeLibLoaded = false
    @Volatile private var pendingThinkingEnabled = true
    @Volatile private var pendingThinkingMaxTokens = 2048
    @Volatile private var pendingThinkingEffort = "medium"
    @Volatile private var isPendingToolContinuation = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                check(_state.value is InferenceEngine.State.Uninitialized) {
                    "Cannot load native library in ${_state.value.javaClass.simpleName}!"
                }
                _state.value = InferenceEngine.State.Initializing
                Log.i(TAG, "Loading native library…")
                try {
                    System.loadLibrary("omp")
                } catch (e: Throwable) {
                    Log.w(TAG, "libomp load note: ${e.message}")
                }
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                isNativeLibLoaded = true
                try {
                    configureThinkingNative(pendingThinkingEnabled, pendingThinkingMaxTokens, pendingThinkingEffort)
                    Log.i(TAG, "Pending thinking applied: enabled=$pendingThinkingEnabled, maxTokens=$pendingThinkingMaxTokens, effort=$pendingThinkingEffort")
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed applying pending thinking config: ${e.message}")
                }
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
        }
    }

    override fun getLoadedBackendInfo(): String {
        return try {
            getLoadedBackendInfoNative()
        } catch (e: Exception) {
            "CPU"
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
    ) =
            withContext(llamaDispatcher) {
                // FIX: Auto-recovery from Error state
                if (_state.value is InferenceEngine.State.Error) {
                    Log.w(TAG, "Recovering from Error state, resetting to Initialized…")
                    _state.value = InferenceEngine.State.Initialized
                }

                check(_state.value is InferenceEngine.State.Initialized) {
                    "Cannot load model in ${_state.value.javaClass.simpleName}!"
                }

                try {
                    Log.i(TAG, "Checking access to model file… \n$pathToModel")
                    File(pathToModel).let {
                        if (!it.exists()) throw IOException("File not found: $pathToModel")
                        if (!it.isFile) throw IOException("Not a valid file: $pathToModel")
                        if (!it.canRead()) throw IOException("Cannot read file: $pathToModel")
                    }

                    Log.i(
                            TAG,
                            "Loading model… (Backend: $backend, Strict: $strictBackend, MMAP: $useMmap, KV-Cache: $kvCacheMode, ctx: $nCtx, batch: $nBatch, ubatch: $nUBatch, FA: $flashAttn, ngl: $gpuLayers)\n$pathToModel"
                    )
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.LoadingModel

                    loadModelNative(
                                    pathToModel,
                                    projectorPath,
                                    kvCacheMode,
                                    useMmap,
                                    nCtx,
                                    nBatch,
                                    nUBatch,
                                    flashAttn,
                                    backend,
                                    strictBackend,
                                    gpuLayers
                            )
                            .let { result ->
                                if (result != 0L) {
                                    when (result) {
                                        101L -> throw BackendInitializationException(
                                            "OpenCL akcelerátor (libggml-opencl.so) nelze inicializovat nebo GPU není podporováno."
                                        )
                                        102L -> throw BackendInitializationException(
                                            "Hexagon/HTP DSP akcelerátor (libggml-hexagon.so) nelze inicializovat nebo FastRPC session selhala."
                                        )
                                        else -> throw UnsupportedArchitectureException(
                                            "Native load failed with code: $result (Backend: $backend)"
                                        )
                                    }
                                }
                            }
                    prepare().let {
                        if (it != 0) throw IOException("Failed to prepare resources (code $it)")
                    }
                    Log.i(TAG, "Model loaded! Active backend: ${getLoadedBackendInfo()}")
                    _readyForSystemPrompt = true

                    _cancelGeneration = false
                    _state.value = InferenceEngine.State.ModelReady
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading model: ${e.message}", e)
                    _state.value = InferenceEngine.State.Error(e)
                    throw e
                }
            }

    override suspend fun processImage(imageBytes: ByteArray): Boolean =
            withContext(llamaDispatcher) {
                check(_state.value is InferenceEngine.State.ModelReady) {
                    "Cannot process image in ${_state.value.javaClass.simpleName}!"
                }
                Log.i(TAG, "Processing image (${imageBytes.size} bytes)…")

                // Rezyduum zpracování obrazu a fúze v JNI
                val oldState = _state.value
                _state.value = InferenceEngine.State.ProcessingUserPrompt
                try {
                    val success = processImageNative(imageBytes)
                    if (!success) {
                        Log.e(TAG, "Native image processing returned false")
                    }
                    return@withContext success
                } finally {
                    _state.value = InferenceEngine.State.ModelReady
                }
            }

    override fun hasProjector(): Boolean = hasProjectorNative()

    override suspend fun setSystemPrompt(systemPrompt: String) {
        withContext(llamaDispatcher) {
            require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
            if (!_readyForSystemPrompt) {
                Log.i(TAG, "setSystemPrompt called with dirty context. Resetting continuation state before setting system prompt…")
                _readyForSystemPrompt = true
                isPendingToolContinuation = false
            }

            Log.i(TAG, "Sending system prompt…")
            _readyForSystemPrompt = false
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            try {
                val result = processSystemPrompt(systemPrompt)
                if (result != 0) {
                    val ex = RuntimeException("Failed to process system prompt: $result")
                    _state.value = InferenceEngine.State.Error(ex)
                    throw ex
                }
                Log.i(TAG, "System prompt processed! Awaiting user prompt…")
            } finally {
                _state.value = InferenceEngine.State.ModelReady
            }
        }
    }

    override suspend fun resetContext() {
        if (_state.value is InferenceEngine.State.Uninitialized) {
            Log.i(TAG, "resetContext called on Uninitialized engine, skipping.")
            return
        }
        cancelGeneration()
        withContext(llamaDispatcher) {
            Log.i(TAG, "Resetting context (KV cache clear) from state ${_state.value}…")
            _state.value = InferenceEngine.State.ProcessingSystemPrompt
            try {
                resetContextNative()
                _readyForSystemPrompt = true
                isPendingToolContinuation = false
                Log.i(TAG, "Context reset complete! Ready for fresh session.")
            } catch (e: Throwable) {
                Log.e(TAG, "resetContextNative error: ${e.message}", e)
            } finally {
                _state.value = InferenceEngine.State.ModelReady
            }
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
        check(_state.value is InferenceEngine.State.ModelReady) {
            "Cannot configure sampling in ${_state.value.javaClass.simpleName}!"
        }
        Log.i(TAG, "Applying sampling configuration…")
        configureSamplingNative(
                temp,
                topK,
                topP,
                minP,
                xtcProb,
                xtcThreshold,
                typicalP,
                dynTempRange,
                dynTempExp,
                penaltyRepeat,
                penaltyFreq,
                penaltyPresent,
                penaltyLastN,
                mirostat,
                mirostatTau,
                mirostatEta,
                samplersSequence,
                useBackendSampling,
                ignoreEos,
                noPerf,
                timingPerToken
        )
    }

    override fun configureThinking(enabled: Boolean, maxThinkingTokens: Int, effort: String) {
        pendingThinkingEnabled = enabled
        pendingThinkingMaxTokens = maxThinkingTokens
        pendingThinkingEffort = effort
        Log.i(TAG, "Configuring thinking: enabled=$enabled, maxTokens=$maxThinkingTokens, effort=$effort (libLoaded=$isNativeLibLoaded)")
        if (isNativeLibLoaded) {
            try {
                configureThinkingNative(enabled, maxThinkingTokens, effort)
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "configureThinkingNative UnsatisfiedLinkError: ${e.message}")
            }
        }
    }

    override fun sendUserPromptEvents(
        message: String,
        predictLength: Int,
    ): Flow<GenerationEvent> =
        flow {
            val isContinuation = isPendingToolContinuation
            require(message.isNotEmpty() || isContinuation) {
                "User prompt discarded due to being empty!"
            }
            check(_state.value is InferenceEngine.State.ModelReady) {
                "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
            }

            try {
                Log.i(TAG, "Sending user prompt (events stream, continuation=$isContinuation)…")
                _readyForSystemPrompt = false
                _cancelGeneration = false // CRITICAL: Reset cancel flag BEFORE processing!
                _state.value = InferenceEngine.State.ProcessingUserPrompt

                if (isContinuation) {
                    isPendingToolContinuation = false
                }

                processUserPrompt(message, predictLength).let { result ->
                    if (result != 0) {
                        Log.e(TAG, "Failed to process user prompt: $result")
                        emit(GenerationEvent.Error(0L, 0L, "Failed to process user prompt (code=$result)"))
                        return@flow
                    }
                }

                Log.i(TAG, "User prompt processed. Generating assistant events…")
                _state.value = InferenceEngine.State.Generating

                suspend fun drainNativeEvents() {
                    if (isNativeLibLoaded) {
                        try {
                            val events = pollGenerationEventsNative(64)
                            for (ev in events) {
                                emit(ev.toDomainEvent())
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "pollGenerationEventsNative note: ${e.message}")
                        }
                    }
                }

                drainNativeEvents()

                while (!_cancelGeneration) {
                    val nextToken = generateNextToken()
                    drainNativeEvents()
                    if (nextToken == null) {
                        break
                    }
                }

                // Final drain to ensure FINISHED event is delivered
                drainNativeEvents()

                if (_cancelGeneration) {
                    Log.i(TAG, "Assistant generation aborted per requested.")
                } else {
                    Log.i(TAG, "Assistant generation complete. Awaiting user prompt…")
                }
                _state.value = InferenceEngine.State.ModelReady
            } catch (e: CancellationException) {
                Log.i(TAG, "Assistant generation's flow collection cancelled.")
                _state.value = InferenceEngine.State.ModelReady
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error during generation!", e)
                _state.value = InferenceEngine.State.Error(e)
                throw e
            }
        }.flowOn(llamaDispatcher)

    override fun sendUserPrompt(
        message: String,
        predictLength: Int,
    ): Flow<String> = sendUserPromptEvents(message, predictLength)
        .map { ev ->
            when (ev) {
                is GenerationEvent.ContentDelta -> ev.text
                is GenerationEvent.ReasoningDelta -> ev.text
                else -> ""
            }
        }
        .filter { it.isNotEmpty() }

    override fun continueGeneration(predictLength: Int): Flow<GenerationEvent> =
        sendUserPromptEvents("", predictLength)

    override fun continueGenerationText(predictLength: Int): Flow<String> =
        continueGeneration(predictLength)
            .map { ev ->
                when (ev) {
                    is GenerationEvent.ContentDelta -> ev.text
                    is GenerationEvent.ReasoningDelta -> ev.text
                    else -> ""
                }
            }
            .filter { it.isNotEmpty() }

    override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String =
            withContext(llamaDispatcher) {
                check(_state.value is InferenceEngine.State.ModelReady) {
                    "Benchmark request discarded due to: $state"
                }
                Log.i(TAG, "Start benchmark (pp: $pp, tg: $tg, pl: $pl, nr: $nr)")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.Benchmarking
                benchModel(pp, tg, pl, nr).also { _state.value = InferenceEngine.State.ModelReady }
            }

    override fun cancelGeneration() {
        Log.i(TAG, "Cancel generation requested")
        _cancelGeneration = true
        if (isNativeLibLoaded) {
            try {
                cancelGenerationNative()
            } catch (e: Throwable) {
                Log.w(TAG, "cancelGenerationNative note: ${e.message}")
            }
        }
    }

    override fun cleanUp() {
        _cancelGeneration = true
        isPendingToolContinuation = false
        runBlocking(llamaDispatcher) {
            when (val state = _state.value) {
                is InferenceEngine.State.ModelReady -> {
                    Log.i(TAG, "Unloading model and free resources…")
                    _readyForSystemPrompt = false
                    _state.value = InferenceEngine.State.UnloadingModel

                    unload()

                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "Model unloaded!")
                    Unit
                }
                is InferenceEngine.State.Error -> {
                    Log.i(TAG, "Resetting error states…")
                    _state.value = InferenceEngine.State.Initialized
                    Log.i(TAG, "States reset!")
                    Unit
                }
                is InferenceEngine.State.Initialized, is InferenceEngine.State.Uninitialized -> {
                    Log.i(TAG, "Model already unloaded or not initialized. Skipping cleanUp.")
                    Unit
                }
                else ->
                        throw IllegalStateException(
                                "Cannot unload model in ${state.javaClass.simpleName}"
                        )
            }
        }
    }

    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            _readyForSystemPrompt = false
            when (_state.value) {
                is InferenceEngine.State.Uninitialized -> {}
                is InferenceEngine.State.Initialized -> shutdown()
                else -> {
                    unload()
                    shutdown()
                }
            }
        }
        llamaScope.cancel()
    }
    private external fun resetContextNative()
    private external fun cancelGenerationNative()
    private external fun pollGenerationEventsNative(maxEvents: Int): Array<NativeGenerationEvent>

    private external fun setToolDefinitionsNative(toolsJson: String)
    private external fun setToolPolicyNative(policy: Int)
    private external fun submitToolResultNative(callId: String, name: String, content: String): Boolean
    private external fun getModelCapabilitiesNative(): com.arm.aichat.NativeModelCapabilities

    override fun setToolDefinitions(toolsJson: String) {
        setToolDefinitionsNative(toolsJson)
    }

    override fun setToolPolicy(policy: Int) {
        setToolPolicyNative(policy)
    }

    override suspend fun submitToolResult(callId: String, name: String, content: String): Boolean {
        return withContext(llamaDispatcher) {
            val success = submitToolResultNative(callId, name, content)
            if (success) {
                isPendingToolContinuation = true
            }
            success
        }
    }

    override fun getModelCapabilities(): com.arm.aichat.NativeModelCapabilities {
        return getModelCapabilitiesNative()
    }

    override suspend fun setGrammar(grammar: String?) {
        withContext(llamaDispatcher) {
            check(
                    _state.value is InferenceEngine.State.ModelReady ||
                            _state.value is InferenceEngine.State.Generating
            ) { "Cannot set grammar in ${_state.value.javaClass.simpleName}!" }
            setGrammarNative(grammar)
            Log.i(TAG, "Grammar ${if (grammar != null) "set" else "cleared"}.")
        }
    }
}
