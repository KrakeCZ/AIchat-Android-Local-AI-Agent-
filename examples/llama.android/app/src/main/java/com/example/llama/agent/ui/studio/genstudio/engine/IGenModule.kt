package com.example.llama.agent.ui.studio.genstudio.engine

import android.graphics.Bitmap
import androidx.annotation.Keep
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.flow.StateFlow
import java.io.File

@Keep
enum class GenEngineType {
    GGUF_FLOW_MATCHING,    // Wan 2.1 / Wan 2.2 MoE / Sana / Flux.1
    MEDIAPIPE_TASK,        // MediaPipe / LiteRT .task (NPU / GPU)
    ONNX_RUNTIME,          // ONNX INT4/FP16 models
    SAFETENSORS_VULKAN,    // stable-diffusion.cpp / AnimateDiff-Lightning
    AUTOREGRESSIVE_TOKEN,  // Show-o / OmniGen-AR / Lumina-mGPT / LlamaGen
    LAN_WORKSTATION,       // ComfyUI / DiffSynth REST API
    CLOUD_API_FALLBACK     // Google AI Studio / Gemini 2.5/3.0 API
}

@Keep
data class GenExecutionConfig(
    val prompt: String,
    val negativePrompt: String = "blurry, low quality, distorted, artifacts, static, jitter",
    val initialFrame: Bitmap? = null,
    val width: Int = 480,
    val height: Int = 272,
    val frameCount: Int = 17,
    val fps: Int = 12,
    val steps: Int = 12,
    val guidanceScale: Float = 5.0f,
    val seed: Long = -1L,
    val useSequentialOffload: Boolean = true,
    val enableThermalThrottling: Boolean = true,
    val maxThreads: Int = 4,
    val enableTelemetry: Boolean = true,
    val executionBackend: Int = 0
)

sealed class GenProgressState {
    object Idle : GenProgressState()
    object Cancelled : GenProgressState()
    data class LoadingModel(val moduleName: String, val progress: Float) : GenProgressState()
    data class TextConditioning(val progress: Float) : GenProgressState()
    data class Denoising(val step: Int, val totalSteps: Int, val progress: Float, val currentFps: Float) : GenProgressState()
    data class LatentDecoding(val frame: Int, val totalFrames: Int) : GenProgressState()
    data class VideoMuxing(val progress: Float) : GenProgressState()
    data class Completed(val outputFile: File, val durationMs: Long, val totalFrames: Int = 1) : GenProgressState()
    data class Error(val message: String, val throwable: Throwable? = null) : GenProgressState()
}

/**
 * Základní modulární kontrakt pro každý generativní model v aplikaci.
 */
interface IGenModule {
    val engineType: GenEngineType
    val name: String
    val state: StateFlow<GenProgressState>

    suspend fun isHardwareSupported(): Boolean
    suspend fun loadModel(model: GenModel): Boolean
    suspend fun execute(config: GenExecutionConfig, outputFile: File): Result<File>
    suspend fun cancel()
    fun release()
}
