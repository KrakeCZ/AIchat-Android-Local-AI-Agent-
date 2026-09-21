package com.arm.aichat.internal

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

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
    @FastNative private external fun init(nativeLibDir: String)

    @FastNative
    private external fun loadModelNative(
            modelPath: String,
            projectorPath: String?,
            kvCacheMode: Int,
            useMmap: Boolean,
            nCtx: Int,
            nBatch: Int,
            nUBatch: Int,
            flashAttn: Boolean
    ): Long

    @FastNative private external fun processImageNative(imageBytes: ByteArray): Boolean

    @FastNative private external fun prepare(): Int

    @FastNative private external fun systemInfo(): String

    @FastNative private external fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String

    @FastNative private external fun processSystemPrompt(systemPrompt: String): Int

    @FastNative
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

    @FastNative private external fun processUserPrompt(userPrompt: String, predictLength: Int): Int

    @FastNative private external fun generateNextToken(): String?

    @FastNative private external fun unload()

    @FastNative private external fun shutdown()

    @FastNative private external fun setGrammarNative(grammar: String?)

    private val _state =
            MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
    override val state: StateFlow<InferenceEngine.State> = _state.asStateFlow()

    private var _readyForSystemPrompt = false
    @Volatile private var _cancelGeneration = false

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
                System.loadLibrary("ai-chat")
                init(nativeLibDir)
                _state.value = InferenceEngine.State.Initialized
                Log.i(TAG, "Native library loaded! System info: \n${systemInfo()}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load native library", e)
                throw e
            }
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
            flashAttn: Boolean
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
                            "Loading model… (MMAP: $useMmap, KV-Cache: $kvCacheMode, ctx: $nCtx, batch: $nBatch, ubatch: $nUBatch, FA: $flashAttn)\n$pathToModel"
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
                                    flashAttn
                            )
                            .let { result ->
                                if (result != 0L) {
                                    throw UnsupportedArchitectureException(
                                            "Native load failed with code: $result"
                                    )
                                }
                            }
                    prepare().let {
                        if (it != 0) throw IOException("Failed to prepare resources (code $it)")
                    }
                    Log.i(TAG, "Model loaded!")
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

    override suspend fun setSystemPrompt(systemPrompt: String) =
            withContext(llamaDispatcher) {
                require(systemPrompt.isNotBlank()) { "Cannot process empty system prompt!" }
                check(_readyForSystemPrompt) {
                    "System prompt must be set ** RIGHT AFTER ** model loaded!"
                }
                check(_state.value is InferenceEngine.State.ModelReady) {
                    "Cannot process system prompt in ${_state.value.javaClass.simpleName}!"
                }

                Log.i(TAG, "Sending system prompt…")
                _readyForSystemPrompt = false
                _state.value = InferenceEngine.State.ProcessingSystemPrompt
                processSystemPrompt(systemPrompt).let { result ->
                    if (result != 0) {
                        RuntimeException("Failed to process system prompt: $result").also {
                            _state.value = InferenceEngine.State.Error(it)
                            throw it
                        }
                    }
                }
                Log.i(TAG, "System prompt processed! Awaiting user prompt…")
                _state.value = InferenceEngine.State.ModelReady
            }

    override suspend fun resetContext() {
        withContext(llamaDispatcher) {
            check(_state.value is InferenceEngine.State.ModelReady) {
                "Cannot reset context in ${_state.value.javaClass.simpleName}!"
            }
            Log.i(TAG, "Resetting context (KV cache clear)…")
            // Zvedneme flag aby se operace neprerušila
            val oldState = _state.value
            _state.value =
                    InferenceEngine.State.ProcessingSystemPrompt // Dočasný stav blokující interakci
            try {
                resetContextNative()
                _readyForSystemPrompt = true
                Log.i(TAG, "Context reset complete!")
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

    override fun sendUserPrompt(
            message: String,
            predictLength: Int,
    ): Flow<String> =
            flow {
                        require(message.isNotEmpty()) {
                            "User prompt discarded due to being empty!"
                        }
                        check(_state.value is InferenceEngine.State.ModelReady) {
                            "User prompt discarded due to: ${_state.value.javaClass.simpleName}"
                        }

                        try {
                            Log.i(TAG, "Sending user prompt…")
                            _readyForSystemPrompt = false
                            _cancelGeneration = false // CRITICAL: Reset cancel flag BEFORE processing!
                            // Bez tohoto resetu zůstává flag=true po předchozím Agent cancelGeneration()
                            // a generovací smyčka okamžitě skončí ("aborted per requested").
                            _state.value = InferenceEngine.State.ProcessingUserPrompt

                            processUserPrompt(message, predictLength).let { result ->
                                if (result != 0) {
                                    Log.e(TAG, "Failed to process user prompt: $result")
                                    return@flow
                                }
                            }

                            Log.i(TAG, "User prompt processed. Generating assistant prompt…")
                            _state.value = InferenceEngine.State.Generating
                            while (!_cancelGeneration) {
                                generateNextToken()?.let { utf8token ->
                                    if (utf8token.isNotEmpty()) emit(utf8token)
                                }
                                        ?: break
                            }
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
                    }
                    .flowOn(llamaDispatcher)

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
    }

    override fun cleanUp() {
        _cancelGeneration = true
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
