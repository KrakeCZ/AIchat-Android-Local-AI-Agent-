package com.example.llama.agent.ui.studio.genstudio.engine

import android.content.Context
import android.util.Log
import com.example.llama.agent.ui.studio.genstudio.memory.PhaseLockedMemoryStateMachine
import com.example.llama.agent.ui.studio.genstudio.modules.*
import com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfiler
import com.example.llama.agent.ui.studio.genstudio.profiling.ThermalDutyCycleGovernor
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Centrální směrovač enginů pro GEN_Studio.
 * Automaticky mapuje importované modely na příslušné specializované submoduly
 * a řídí jejich životní cyklus, hardwarové profily a paměťovou ochranu.
 */
class GenStudioEngineRouter(
    private val context: Context,
    private val memoryStateMachine: PhaseLockedMemoryStateMachine,
    private val profiler: HardwareProfiler,
    private val thermalGovernor: ThermalDutyCycleGovernor
) {
    companion object {
        private const val TAG = "GenEngineRouter"
    }

    private val availableModules = mutableMapOf<GenEngineType, IGenModule>()
    private var activeModule: IGenModule? = null

    private val _engineState = MutableStateFlow<GenProgressState>(GenProgressState.Idle)
    val engineState: StateFlow<GenProgressState> = _engineState.asStateFlow()

    init {
        registerModule(GgufFlowMatchingModule(context, memoryStateMachine, profiler, thermalGovernor))
        registerModule(MediaPipeGenModule(context, memoryStateMachine, profiler, thermalGovernor))
        registerModule(OnnxGenModule(context, memoryStateMachine, profiler, thermalGovernor))
        registerModule(SafetensorsGenModule(context, memoryStateMachine, profiler, thermalGovernor))
        registerModule(AutoregressiveGenModule(context, memoryStateMachine, profiler, thermalGovernor))
    }

    fun registerModule(module: IGenModule) {
        availableModules[module.engineType] = module
        Log.i(TAG, "Registrován generativní modul: ${module.name} [${module.engineType}]")
    }

    suspend fun prepareModel(model: GenModel): Result<IGenModule> {
        val targetType = when (model.format.uppercase()) {
            "GGUF" -> {
                if (model.architecture == "AUTOREGRESSIVE") GenEngineType.AUTOREGRESSIVE_TOKEN
                else GenEngineType.GGUF_FLOW_MATCHING
            }
            "TASK", "TFLITE" -> GenEngineType.MEDIAPIPE_TASK
            "ONNX" -> GenEngineType.ONNX_RUNTIME
            "SAFETENSORS" -> GenEngineType.SAFETENSORS_VULKAN
            else -> GenEngineType.GGUF_FLOW_MATCHING
        }

        val module = availableModules[targetType]
            ?: return Result.failure(IllegalStateException("Modul pro $targetType není k dispozici."))

        if (!module.isHardwareSupported()) {
            return Result.failure(IllegalStateException("Nedostatek volné RAM nebo nekompatibilní HW pro ${module.name}."))
        }

        val success = module.loadModel(model)
        if (!success) {
            return Result.failure(IllegalStateException("Nepodařilo se inicializovat model ${model.displayName}."))
        }

        activeModule = module
        return Result.success(module)
    }

    var generationStartTimeMs: Long? = null
        private set

    suspend fun executeGeneration(config: GenExecutionConfig, outputFile: File): Result<File> {
        val module = activeModule
            ?: return Result.failure(IllegalStateException("Žádný generativní modul není aktivován."))

        generationStartTimeMs = System.currentTimeMillis()
        val stateCollector = CoroutineScope(Dispatchers.Default).launch {
            module.state.collect { st ->
                _engineState.value = st
            }
        }
        return try {
            val result = module.execute(config, outputFile)
            if (result.isSuccess) {
                _engineState.value = module.state.value
            }
            result
        } catch (e: kotlinx.coroutines.CancellationException) {
            _engineState.value = GenProgressState.Idle
            Result.failure(e)
        } catch (e: Exception) {
            _engineState.value = GenProgressState.Error(e.message ?: "Chyba generování")
            Result.failure(e)
        } finally {
            stateCollector.cancel()
            generationStartTimeMs = null
        }
    }

    fun cancelGeneration() {
        generationStartTimeMs = null
        activeModule?.let { mod ->
            CoroutineScope(Dispatchers.Default).launch {
                try {
                    mod.cancel()
                } catch (e: Throwable) {
                    Log.w(TAG, "Chyba při stornování modulu: ${e.message}")
                }
            }
        }
        _engineState.value = GenProgressState.Idle
    }

    fun release() {
        activeModule?.release()
        availableModules.values.forEach { it.release() }
        availableModules.clear()
        activeModule = null
    }
}
