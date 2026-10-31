package com.example.llama.agent.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.ui.studio.genstudio.engine.*
import com.example.llama.agent.ui.studio.genstudio.memory.PhaseLockedMemoryStateMachine
import com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfiler
import com.example.llama.agent.ui.studio.genstudio.profiling.ThermalDutyCycleGovernor
import com.example.llama.data.gen.GenDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class GenPreset {
    FAST_PREVIEW,  // 384x256, 17 frames, 8 steps
    BALANCED,      // 480x272, 33 frames, 12 steps
    HIGH_QUALITY   // 640x360, 49 frames, 16 steps
}

/**
 * Autonomní Agent Tool pro generování videa a obrazu z promptu nebo referenčního snímku (I2V / T2V / T2I).
 * Využívá výhradně reálně importované modely z GenDatabase (žádné hardcoded cesty).
 */
class GenStudioTool(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    companion object {
        private const val TAG = "GenStudioTool"
    }

    private val profiler = HardwareProfiler(context)
    private val thermalGovernor = ThermalDutyCycleGovernor(profiler)
    private val memoryStateMachine = PhaseLockedMemoryStateMachine(context)
    private val engineRouter = GenStudioEngineRouter(context, memoryStateMachine, profiler, thermalGovernor)
    private val genDatabase = GenDatabase.getDatabase(context)

    suspend fun generateVideo(
        prompt: String,
        initialFrame: Bitmap? = null,
        preset: GenPreset = GenPreset.BALANCED
    ): Result<File> = withContext(Dispatchers.Default) {
        Log.i(TAG, "🎬 Agent spouští generování videa: '$prompt' (Preset: $preset, HasFrame: ${initialFrame != null})")

        val modelToUse = genDatabase.genModelDao().getLatestVideoModel()
            ?: return@withContext Result.failure(
                IllegalStateException("V databázi není žádný importovaný video model. Nejprve naimportujte model v GEN_Studio ze souborů telefonu.")
            )

        // Aktualizujeme čas použití
        genDatabase.genModelDao().updateLastAccess(modelToUse.id, System.currentTimeMillis())

        val (w, h, frames, steps) = when (preset) {
            GenPreset.FAST_PREVIEW -> listOf(384, 256, 17, 8)
            GenPreset.BALANCED -> listOf(480, 272, 33, 12)
            GenPreset.HIGH_QUALITY -> listOf(640, 360, 49, 16)
        }

        val config = GenExecutionConfig(
            prompt = prompt,
            initialFrame = initialFrame,
            width = w,
            height = h,
            frameCount = frames,
            steps = steps,
            guidanceScale = 5.0f
        )

        val outputFile = File(context.getExternalFilesDir(null), "agent_gen_${System.currentTimeMillis()}.mp4")

        val prepareResult = engineRouter.prepareModel(modelToUse)
        if (prepareResult.isFailure) {
            return@withContext Result.failure(prepareResult.exceptionOrNull() ?: RuntimeException("Chyba přípravy modelu"))
        }

        return@withContext engineRouter.executeGeneration(config, outputFile)
    }

    suspend fun generateImage(
        prompt: String,
        preset: GenPreset = GenPreset.BALANCED
    ): Result<File> = withContext(Dispatchers.Default) {
        Log.i(TAG, "🖼️ Agent spouští generování statického obrazu: '$prompt'")

        val modelToUse = genDatabase.genModelDao().getLatestImageModel()
            ?: return@withContext Result.failure(
                IllegalStateException("V databázi není žádný importovaný model pro obraz. Nejprve naimportujte model v GEN_Studio.")
            )

        // Aktualizujeme čas použití
        genDatabase.genModelDao().updateLastAccess(modelToUse.id, System.currentTimeMillis())

        val (w, h) = when (preset) {
            GenPreset.FAST_PREVIEW -> Pair(512, 512)
            GenPreset.BALANCED -> Pair(768, 768)
            GenPreset.HIGH_QUALITY -> Pair(1024, 1024)
        }

        val config = GenExecutionConfig(
            prompt = prompt,
            width = w,
            height = h,
            frameCount = 1,
            steps = 8,
            guidanceScale = 4.0f
        )

        val outputFile = File(context.getExternalFilesDir(null), "agent_img_${System.currentTimeMillis()}.png")

        val prepareResult = engineRouter.prepareModel(modelToUse)
        if (prepareResult.isFailure) {
            return@withContext Result.failure(prepareResult.exceptionOrNull() ?: RuntimeException("Chyba přípravy modelu"))
        }

        return@withContext engineRouter.executeGeneration(config, outputFile)
    }

    fun release() {
        engineRouter.release()
    }
}
