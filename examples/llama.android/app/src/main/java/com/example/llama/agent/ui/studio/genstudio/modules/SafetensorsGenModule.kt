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
 * Modul pro Safetensors váhy (AnimateDiff-Lightning, SDXL LoRA).
 */
class SafetensorsGenModule(
    private val context: Context,
    private val memoryStateMachine: PhaseLockedMemoryStateMachine,
    private val profiler: HardwareProfiler,
    private val thermalGovernor: ThermalDutyCycleGovernor
) : IGenModule {

    companion object {
        private const val TAG = "SafetensorsModule"
    }

    override val engineType: GenEngineType = GenEngineType.SAFETENSORS_VULKAN
    override val name: String = "Safetensors Vulkan (AnimateDiff)"

    private val _state = MutableStateFlow<GenProgressState>(GenProgressState.Idle)
    override val state: StateFlow<GenProgressState> = _state.asStateFlow()

    private val frameSynthesizer = GenFrameSynthesizer()
    private val videoMuxer = GenVideoMuxer()

    private var activeModel: GenModel? = null
    private var isCancelled = false

    override suspend fun isHardwareSupported(): Boolean {
        return profiler.getActiveProfile().availableRamMb >= 3000
    }

    override suspend fun loadModel(model: GenModel): Boolean {
        activeModel = model
        Log.i(TAG, "Safetensors model načten: ${model.displayName} (${model.filePath})")
        return true
    }

    override suspend fun execute(config: GenExecutionConfig, outputFile: File): Result<File> = withContext(Dispatchers.Default) {
        val model = activeModel ?: return@withContext Result.failure(IllegalStateException("Žádný model není načten."))
        isCancelled = false
        val startTime = System.currentTimeMillis()

        return@withContext memoryStateMachine.executeSequentialPipeline("Safetensors_${System.currentTimeMillis()}") { transition ->
            try {
                val resolvedModelPath = UriPathResolver.resolveToPath(context, model.filePath) ?: model.filePath
                val isAvailable = UriPathResolver.isModelAvailable(context, model.filePath) || File(resolvedModelPath).exists()
                if (!isAvailable) {
                    throw IllegalStateException("Soubor Safetensors modelu není k dispozici: $resolvedModelPath")
                }

                val isVideo = config.frameCount > 1
                Log.i(TAG, "⚡ Spouštím nativní C++ Safetensors engine [${if (isVideo) "VIDEO" else "FOTO"}] pro ${model.displayName} ($resolvedModelPath)...")
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
                    Log.i(TAG, "✅ Safetensors generování úspěšně dokončeno: ${outputFile.absolutePath} ($duration ms)")
                    return@executeSequentialPipeline outputFile
                } else {
                    val err = nativeResult.exceptionOrNull()
                    Log.e(TAG, "❌ Nativní Safetensors neuronová síť selhala: ${err?.message}", err)
                    throw err ?: RuntimeException("Chyba nativní Safetensors syntézy.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba během Safetensors generování: ${e.message}", e)
                _state.value = GenProgressState.Error(e.message ?: "Neznámá chyba")
                throw e
            }
        }
    }

    override suspend fun cancel() {
        isCancelled = true
        VideoGenEngine.getInstance().cancel()
        _state.value = GenProgressState.Cancelled
    }

    override fun release() {
        isCancelled = true
        VideoGenEngine.getInstance().cancel()
        activeModel = null
        _state.value = GenProgressState.Idle
    }
}
