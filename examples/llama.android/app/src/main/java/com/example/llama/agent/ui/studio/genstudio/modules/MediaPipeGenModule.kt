package com.example.llama.agent.ui.studio.genstudio.modules

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.llama.agent.ui.studio.genstudio.engine.*
import com.example.llama.agent.ui.studio.genstudio.memory.PhaseLockedMemoryStateMachine
import com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfiler
import com.example.llama.agent.ui.studio.genstudio.profiling.ThermalDutyCycleGovernor
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Modul pro Google MediaPipe / LiteRT (.task) modely.
 * Využívá Hexagon NPU akceleraci a MediaCodec pro bleskurychlé generování na mobilu.
 */
class MediaPipeGenModule(
    private val context: Context,
    private val memoryStateMachine: PhaseLockedMemoryStateMachine,
    private val profiler: HardwareProfiler,
    private val thermalGovernor: ThermalDutyCycleGovernor
) : IGenModule {

    companion object {
        private const val TAG = "MediaPipeGenModule"
    }

    override val engineType: GenEngineType = GenEngineType.MEDIAPIPE_TASK
    override val name: String = "MediaPipe / LiteRT NPU Task"

    private val _state = MutableStateFlow<GenProgressState>(GenProgressState.Idle)
    override val state: StateFlow<GenProgressState> = _state.asStateFlow()

    private val frameSynthesizer = GenFrameSynthesizer()
    private val videoMuxer = GenVideoMuxer()

    private var activeModel: GenModel? = null
    private var isCancelled = false

    override suspend fun isHardwareSupported(): Boolean {
        return profiler.getActiveProfile().availableRamMb >= 1800
    }

    override suspend fun loadModel(model: GenModel): Boolean {
        activeModel = model
        Log.i(TAG, "MediaPipe .task model načten: ${model.displayName} (${model.filePath})")
        return true
    }

    override suspend fun execute(config: GenExecutionConfig, outputFile: File): Result<File> = withContext(Dispatchers.Default) {
        val model = activeModel ?: return@withContext Result.failure(IllegalStateException("Žádný model není načten."))
        isCancelled = false
        val startTime = System.currentTimeMillis()

        return@withContext memoryStateMachine.executeSequentialPipeline("MediaPipe_${System.currentTimeMillis()}") { transition ->
            try {
                val resolvedModelPath = UriPathResolver.resolveToPath(context, model.filePath) ?: model.filePath
                val videoGenEngine = VideoGenEngine.getInstance()
                val preparedModel = model.copy(
                    filePath = resolvedModelPath,
                    textEncoderPath = model.textEncoderPath?.let { UriPathResolver.resolveToPath(context, it) ?: it },
                    vaePath = model.vaePath?.let { UriPathResolver.resolveToPath(context, it) ?: it }
                )

                val nativeResult = videoGenEngine.generate(
                    context = context,
                    model = preparedModel,
                    config = config,
                    onDetailedProgress = { phase, step, total, prog, msg ->
                        val fps = if (step > 0) (step / maxOf(0.001f, (System.currentTimeMillis() - startTime) / 1000f)) else 0f
                        when (phase) {
                            1 -> _state.value = GenProgressState.TextConditioning(prog)
                            2 -> _state.value = GenProgressState.Denoising(step, total, step.toFloat() / maxOf(1, total), fps)
                            3 -> _state.value = GenProgressState.LatentDecoding(step, config.frameCount)
                            4 -> _state.value = GenProgressState.VideoMuxing(prog)
                        }
                    }
                )

                if (nativeResult.isSuccess) {
                    val genFile = nativeResult.getOrThrow()
                    if (genFile.absolutePath != outputFile.absolutePath) {
                        genFile.copyTo(outputFile, overwrite = true)
                    }
                    val duration = System.currentTimeMillis() - startTime
                    _state.value = GenProgressState.Completed(outputFile, duration, totalFrames = config.frameCount)
                    return@executeSequentialPipeline outputFile
                } else {
                    val err = nativeResult.exceptionOrNull()
                    throw err ?: RuntimeException("Chyba při MediaPipe syntéze.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba během MediaPipe generování: ${e.message}", e)
                _state.value = GenProgressState.Error(e.message ?: "Neznámá chyba")
                throw e
            }
        }
    }

    override suspend fun cancel() {
        isCancelled = true
        try {
            VideoGenEngine.getInstance().cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Chyba při volání cancel na VideoGenEngine: ${e.message}")
        }
        _state.value = GenProgressState.Idle
    }

    override fun release() {
        isCancelled = true
        activeModel = null
        _state.value = GenProgressState.Idle
    }
}
