package com.arm.aichat

import com.arm.aichat.InferenceEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Interface defining the core LLM inference operations. */
interface InferenceEngine {
    /** Current state of the inference engine */
    val state: StateFlow<State>

    /**
     * Load a model from the given path.
     *
     * @param pathToModel Absolute path to the model file
     * @param kvCacheMode Quantization mode for KV cache (0=F16, 1=Q8_0, etc.)
     * @param useMmap Whether to use Memory Mapping (true) or load to RAM (false)
     * @param nCtx Context window size (0 = default/model limit)
     * @param nBatch Batch size for prompt processing
     * @param flashAttn Enable Flash Attention optimization
     * @throws UnsupportedArchitectureException if model architecture not supported
     */
    suspend fun loadModel(
            pathToModel: String,
            projectorPath: String? = null,
            kvCacheMode: Int,
            useMmap: Boolean,
            nCtx: Int = 0,
            nBatch: Int = 512,
            nUBatch: Int = 512,
            flashAttn: Boolean = true,
            backend: String = "cpu",
            strictBackend: Boolean = true,
            gpuLayers: Int = -1
    )

    /** Returns information about the currently active execution backend */
    fun getLoadedBackendInfo(): String = "CPU"


    /** Sends a system prompt to the loaded model */
    suspend fun setSystemPrompt(systemPrompt: String)

    /**
     * Processes an image and adds it to the context.
     *
     * @param imageBytes The byte array of the image to process.
     * @return True if the image was successfully processed, false otherwise.
     */
    suspend fun processImage(imageBytes: ByteArray): Boolean

    /**
     * Returns true if a multimodal projector (vision/audio/video) is loaded and ready.
     */
    fun hasProjector(): Boolean = false

    /**
     * Resets the context (KV Cache) and internal states, effectively starting a fresh conversation
     * without reloading the model.
     */
    suspend fun resetContext()

    /** Configures the sampler parameters. Must be called after loading a model. */
    suspend fun configureSampling(
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
            mirostatEta: Float = 0.1f,
            samplersSequence: String = "kpytxdrn", // Default sequence
            useBackendSampling: Boolean = false,
            // New params
            ignoreEos: Boolean = false,
            noPerf: Boolean = false,
            timingPerToken: Boolean = false
    )

    /**
     * Configures reasoning / thinking parameters for Chain-of-Thought models.
     *
     * @param enabled Whether thinking mode (<think>) is enabled.
     * @param maxThinkingTokens Maximum tokens inside <think> before forced closing tag (0 = unlimited).
     * @param effort Reasoning effort hint for Jinja template ("none", "low", "medium", "high").
     */
    fun configureThinking(enabled: Boolean, maxThinkingTokens: Int, effort: String)

    /** Sends a user prompt to the loaded model and returns a Flow of generated tokens (pure content). */
    fun sendUserPrompt(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /**
     * Sends a user prompt to the loaded model and returns a Flow of structured generation events
     * (ReasoningDelta, ContentDelta, ToolCallDelta, Finished, Error).
     */
    fun sendUserPromptEvents(message: String, predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<GenerationEvent>

    /**
     * Continues generation after submitting tool result(s), allowing the assistant to synthesize
     * the tool outputs or generate subsequent tool calls without injecting a user message.
     */
    fun continueGeneration(predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<GenerationEvent>

    /**
     * Continues generation after submitting tool result(s) and returns a Flow of generated tokens (pure content).
     */
    fun continueGenerationText(predictLength: Int = DEFAULT_PREDICT_LENGTH): Flow<String>

    /** Runs a benchmark with the specified parameters. */
    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1): String

    /**
     * Sets a GBNF grammar constraint for structured output generation. When set, the sampler will
     * only generate tokens matching the grammar.
     *
     * @param grammar GBNF grammar string, or null to clear constraint
     */
    suspend fun setGrammar(grammar: String?)

    /**
     * Sets native tool definitions according to JSON schema specification.
     */
    fun setToolDefinitions(toolsJson: String)

    /**
     * Sets the tool choice policy for upcoming generation turns (0=AUTO, 1=REQUIRED, 2=NONE).
     */
    fun setToolPolicy(policy: Int)

    /**
     * Submits a tool result and atomically commits it into history and KV cache.
     *
     * @param callId ID matching the currently pending tool call
     * @param name Name of the tool matching the pending call
     * @param content Output text or structured data from the tool
     * @return true if successfully formatted, decoded into KV and committed
     */
    suspend fun submitToolResult(callId: String, name: String, content: String): Boolean

    /**
     * Returns the detected capabilities of the currently loaded model template.
     */
    fun getModelCapabilities(): NativeModelCapabilities

    /**
     * Cancels any ongoing generation without unloading the model. Sets internal cancel flag —
     * generation loop will exit and state returns to ModelReady. Safe to call from any thread.
     * No-op if not generating.
     */
    fun cancelGeneration()

    /** Unloads the currently loaded model. */
    fun cleanUp()

    /** Cleans up resources when the engine is no longer needed. */
    fun destroy()

    /** States of the inference engine */
    sealed class State {
        object Uninitialized : State()
        object Initializing : State()
        object Initialized : State()

        object LoadingModel : State()
        object UnloadingModel : State()
        object ModelReady : State()

        object Benchmarking : State()
        object ProcessingSystemPrompt : State()
        object ProcessingUserPrompt : State()

        object Generating : State()

        data class Error(val exception: Exception) : State()
    }

    companion object {
        const val DEFAULT_PREDICT_LENGTH = 1024
    }
}

val State.isUninterruptible
    get() =
            this is State.Initializing ||
                    this is State.LoadingModel ||
                    this is State.UnloadingModel ||
                    this is State.Benchmarking ||
                    this is State.ProcessingSystemPrompt ||
                    this is State.ProcessingUserPrompt

val State.isModelLoaded: Boolean
    get() =
            this is State.ModelReady ||
                    this is State.Benchmarking ||
                    this is State.ProcessingSystemPrompt ||
                    this is State.ProcessingUserPrompt ||
                    this is State.Generating

class UnsupportedArchitectureException(message: String? = null) : Exception(message)

class BackendInitializationException(message: String? = null) : Exception(message)
