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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Modul pro Flow-Matching Diffusion Transformátory ve formátu GGUF
 * (Wan 2.1, Wan 2.2 MoE, Sana, Flux.1 Schnell).
 * Využívá Sequential Phase Offload, GenFrameSynthesizer a hardwarový GenVideoMuxer.
 */
class GgufFlowMatchingModule(
    private val context: Context,
    private val memoryStateMachine: PhaseLockedMemoryStateMachine,
    private val profiler: HardwareProfiler,
    private val thermalGovernor: ThermalDutyCycleGovernor
) : IGenModule {

    companion object {
        private const val TAG = "GgufFlowModule"
    }

    override val engineType: GenEngineType = GenEngineType.GGUF_FLOW_MATCHING
    override val name: String
        get() = activeModel?.displayName?.let { "GGUF DiT ($it)" } ?: "GGUF Flow-Matching DiT"

    private val _state = MutableStateFlow<GenProgressState>(GenProgressState.Idle)
    override val state: StateFlow<GenProgressState> = _state.asStateFlow()

    private val frameSynthesizer = GenFrameSynthesizer()
    private val videoMuxer = GenVideoMuxer()
    private val textConditioningEngine = GenTextConditioningEngine()

    private var activeModel: GenModel? = null
    private var isCancelled = false

    override suspend fun isHardwareSupported(): Boolean {
        val hw = profiler.getActiveProfile()
        return hw.availableRamMb >= 2500
    }

    override suspend fun loadModel(model: GenModel): Boolean {
        activeModel = model
        Log.i(TAG, "GGUF Model nakonfigurován: ${model.displayName} (${model.filePath})")
        return true
    }

    override suspend fun execute(config: GenExecutionConfig, outputFile: File): Result<File> = withContext(Dispatchers.Default) {
        val model = activeModel ?: return@withContext Result.failure(IllegalStateException("Žádný model není načten."))
        isCancelled = false
        val startTime = System.currentTimeMillis()

        return@withContext memoryStateMachine.executeSequentialPipeline("GGUF_DiT_${System.currentTimeMillis()}") { transition ->
            try {
                // Fáze 1: Text Conditioning (UMT5/T5)
                transition(PhaseLockedMemoryStateMachine.Phase.TEXT_CONDITIONING)
                _state.value = GenProgressState.TextConditioning(0.1f)
                val conditioning = textConditioningEngine.encodePrompt(config.prompt, config.negativePrompt)
                Log.i(TAG, "🔤 Fáze 1: Enkódování promptu '${config.prompt.take(30)}...' do tenzorů [1, ${conditioning.seqLength}, ${conditioning.embeddingDim}]...")
                _state.value = GenProgressState.TextConditioning(1.0f)

                if (isCancelled || !kotlin.coroutines.coroutineContext.isActive) throw kotlinx.coroutines.CancellationException("Zrušeno uživatelem.")

                transition(PhaseLockedMemoryStateMachine.Phase.DENOISING_BACKBONE)

                val resolvedModelPath = UriPathResolver.resolveToPath(context, model.filePath) ?: model.filePath
                val isAvailable = UriPathResolver.isModelAvailable(context, model.filePath) || File(resolvedModelPath).exists()
                
                if (!isAvailable) {
                    throw IllegalStateException("Soubor modelu není k dispozici: $resolvedModelPath")
                }

                val isVideo = config.frameCount > 1
                Log.i(TAG, "⚡ Spouštím nativní C++ neuronový engine [${if (isVideo) "VIDEO" else "FOTO"}] pro ${model.displayName} ($resolvedModelPath)...")
                val videoGenEngine = VideoGenEngine.getInstance()
                val preparedModel = model.copy(
                    filePath = resolvedModelPath,
                    textEncoderPath = model.textEncoderPath?.let { UriPathResolver.resolveToPath(context, it) ?: it },
                    vaePath = model.vaePath?.let { UriPathResolver.resolveToPath(context, it) ?: it }
                )
                
                var diffusionStartTime = 0L
                var lastStepTime = 0L
                var calculatedFps = 0f

                val nativeResult = videoGenEngine.generate(
                    context = context,
                    model = preparedModel,
                    config = config,
                    onDetailedProgress = { phase, step, total, prog, msg ->
                        // Always update state machine regardless of telemetry toggle
                        when (phase) {
                            1 -> {
                                _state.value = GenProgressState.TextConditioning(prog)
                            }
                            2 -> {
                                val now = System.currentTimeMillis()
                                if (diffusionStartTime == 0L) {
                                    diffusionStartTime = now
                                    lastStepTime = now
                                }
                                if (step > 0) {
                                    val deltaSec = (now - lastStepTime) / 1000f
                                    lastStepTime = now
                                    if (deltaSec > 0.05f) {
                                        calculatedFps = 1.0f / deltaSec
                                    } else {
                                        calculatedFps = step / maxOf(0.001f, (now - diffusionStartTime) / 1000f)
                                    }
                                }
                                val stepProgress = step.toFloat() / maxOf(1, total)
                                _state.value = GenProgressState.Denoising(step, total, stepProgress, calculatedFps)
                            }
                            3 -> {
                                _state.value = GenProgressState.LatentDecoding(step, maxOf(1, config.frameCount))
                            }
                            4 -> {
                                _state.value = GenProgressState.VideoMuxing(prog)
                            }
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
                    Log.i(TAG, "✅ Neuronové generování úspěšně dokončeno: ${outputFile.absolutePath} ($duration ms)")
                    return@executeSequentialPipeline outputFile
                } else {
                    val err = nativeResult.exceptionOrNull()
                    if (err is kotlinx.coroutines.CancellationException || isCancelled) {
                        Log.i(TAG, "⏹️ Nativní generování bylo zastaveno.")
                        throw kotlinx.coroutines.CancellationException("Generování zastaveno.")
                    }
                    Log.e(TAG, "❌ Nativní neuronová síť selhala: ${err?.message}", err)
                    throw err ?: RuntimeException("Chyba nativní neuronové syntézy.")
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "⏹️ GGUF DiT generování bylo korektně zastaveno.")
                _state.value = GenProgressState.Idle
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba během GGUF DiT generování: ${e.message}", e)
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
