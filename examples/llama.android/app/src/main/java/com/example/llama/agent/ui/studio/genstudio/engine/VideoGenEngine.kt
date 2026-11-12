package com.example.llama.agent.ui.studio.genstudio.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.Keep
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Native C++ Desktop-Grade Video Diffusion Engine for ARM64 Android.
 * Orchestrates 3-Phase Sequential Offload (UMT5 -> Wan DiT -> 3D-VAE -> 60 FPS Muxer).
 */
@Keep
class VideoGenEngine private constructor() {

    companion object {
        private const val TAG = "VideoGenEngine"

        @Volatile
        private var instance: VideoGenEngine? = null

        fun getInstance(): VideoGenEngine {
            return instance ?: synchronized(this) {
                instance ?: VideoGenEngine().also { instance = it }
            }
        }

        init {
            try {
                System.loadLibrary("media_engine")
                Log.i(TAG, "libmedia_engine.so successfully loaded with VideoGen DiT Bridge.")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libmedia_engine.so: ${e.message}", e)
            }
        }
    }

    sealed class EngineStatus {
        object Idle : EngineStatus()
        data class Running(
            val phase: Int,
            val step: Int,
            val totalSteps: Int,
            val message: String
        ) : EngineStatus()
        data class Success(val outputFile: File, val durationMs: Long) : EngineStatus()
        data class Error(val message: String, val throwable: Throwable? = null) : EngineStatus()
    }

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Idle)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private var activeNativeHandle: Long = 0L

    fun getHardwareProfile(): VideoGenHardwareProfile {
        return try {
            val json = nativeDetectHardware()
            VideoGenHardwareProfile.fromJson(json)
        } catch (e: Throwable) {
            Log.w(TAG, "Native hardware detection fallback: ${e.message}")
            VideoGenHardwareProfile.fromJson("{}")
        }
    }

    /**
     * Executes the universal neural generation pipeline (Photo or Video).
     */
    suspend fun generate(
        context: Context,
        model: GenModel,
        config: GenExecutionConfig,
        onProgress: ((Float, String) -> Unit)? = null,
        onDetailedProgress: ((phase: Int, step: Int, total: Int, progress: Float, message: String) -> Unit)? = null
    ): Result<File> {
        currentExecutionBackend = config.executionBackend
        return generateVideo(
            context = context,
            model = model,
            prompt = config.prompt,
            negativePrompt = config.negativePrompt,
            width = config.width,
            height = config.height,
            frames = config.frameCount,
            steps = config.steps,
            guidance = config.guidanceScale,
            seed = config.seed,
            enableTeaCache = true,
            enableTelemetry = config.enableTelemetry,
            onProgress = onProgress,
            onDetailedProgress = onDetailedProgress
        )
    }

    private val executionMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * Executes the full neural diffusion generation pipeline (Photo or Video).
     */
    suspend fun generateVideo(
        context: Context,
        model: GenModel,
        prompt: String,
        negativePrompt: String = "blurry, low quality, distorted, watermark, bad anatomy, deformed",
        width: Int,
        height: Int,
        frames: Int = 17,
        steps: Int = 16,
        guidance: Float = 5.0f,
        seed: Long = -1L,
        enableTeaCache: Boolean = true,
        enableTelemetry: Boolean = true,
        onProgress: ((Float, String) -> Unit)? = null,
        onDetailedProgress: ((phase: Int, step: Int, total: Int, progress: Float, message: String) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.Default) {
        if (!executionMutex.tryLock()) {
            Log.w(TAG, "⚠️ Generování již aktivně probíhá. Odmítám paralelní požadavek.")
            return@withContext Result.failure(IllegalStateException("Generování již probíhá."))
        }

        val startTime = System.currentTimeMillis()
        val safeName = model.displayName.replace("\\s+".toRegex(), "_")
        val isVideo = frames > 1
        val ext = if (isVideo) "mp4" else "png"
        val outputFile = File(
            context.cacheDir,
            "neural_${if (isVideo) "video" else "photo"}_${safeName}_${System.currentTimeMillis()}.$ext"
        )

        var hintSession: Any? = null
        try {
            _status.value = EngineStatus.Running(1, 0, steps, "Fáze 1/4: Načítání vah neuronové sítě do paměti...")
            if (enableTelemetry) {
                onProgress?.invoke(0.05f, "Fáze 1/4: Inicializace neuronového jádra...")
                onDetailedProgress?.invoke(1, 0, 1, 0.05f, "Fáze 1/4: Inicializace neuronového jádra...")
            }

            val ditResolved = UriPathResolver.resolveToPath(context, model.filePath) ?: model.filePath
            val textResolved = model.textEncoderPath?.let { UriPathResolver.resolveToPath(context, it) ?: it }
            val vaeResolved = model.vaePath?.let { UriPathResolver.resolveToPath(context, it) ?: it }

            val nativeLibDir = "${context.applicationInfo.nativeLibraryDir};${context.filesDir.absolutePath}"
            val nativeHandle = nativeInitPipeline(
                ditPath = ditResolved,
                textEncPath = textResolved,
                vaePath = vaeResolved,
                nativeLibDir = nativeLibDir
            )

            if (nativeHandle == 0L) {
                throw IllegalStateException("Nepodařilo se inicializovat nativní C++ kontext pro model: ${model.displayName}")
            }
            activeNativeHandle = nativeHandle

            try {
                nativeConfigureTuning(
                    nativeHandle = nativeHandle,
                    threads = currentThreads,
                    sampleMethod = currentSampleMethod,
                    scheduler = currentScheduler,
                    dynamicCfg = currentDynamicCfg,
                    affinityMode = currentAffinityMode,
                    threadPriority = currentThreadPriority,
                    enableOpenmp = currentEnableOpenmp,
                    enableTeaCache = currentEnableTeaCache,
                    teaCacheThreshold = currentTeaCacheThreshold,
                    teaCacheMode = currentTeaCacheMode,
                    fastModeNoCfg = currentFastModeNoCfg,
                    executionBackend = currentExecutionBackend
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Nelze aplikovat nativní tuning: ${e.message}")
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                try {
                    val hintManager = context.getSystemService(Context.PERFORMANCE_HINT_SERVICE) as? android.os.PerformanceHintManager
                    val currentTid = android.os.Process.myTid()
                    hintSession = hintManager?.createHintSession(intArrayOf(currentTid), 1_000_000_000L)
                    Log.i(TAG, "⚡ ADPF PerformanceHintSession aktivována pro TID=$currentTid (PowerHAL Turbo Boost)")
                } catch (e: Throwable) {
                    Log.w(TAG, "ADPF PerformanceHintSession není dostupná: ${e.message}")
                }
            }

            val callback = object : NativeProgressCallback {
                override fun onProgress(phase: Int, step: Int, total: Int, message: String) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && hintSession != null) {
                        try {
                            (hintSession as? android.os.PerformanceHintManager.Session)?.reportActualWorkDuration(1_500_000_000L)
                        } catch (_: Throwable) {}
                    }
                    val normalizedProgress = when (phase) {
                        1 -> 0.10f + (step.toFloat() / maxOf(1, total)) * 0.15f
                        2 -> 0.25f + (step.toFloat() / maxOf(1, total)) * 0.50f
                        3 -> 0.75f + (step.toFloat() / maxOf(1, total)) * 0.15f
                        else -> 0.90f + (step.toFloat() / maxOf(1, total)) * 0.10f
                    }
                    if (enableTelemetry) {
                        Log.i(TAG, "📊 NativeProgressCallback: phase=$phase, step=$step/$total ($normalizedProgress) - $message")
                        _status.value = EngineStatus.Running(phase, step, total, message)
                    }
                    onProgress?.invoke(normalizedProgress, message)
                    onDetailedProgress?.invoke(phase, step, total, normalizedProgress, message)
                }
            }

            Log.i(TAG, "🚀 Spouštím nativní C++ neuronovou syntézu [${if (isVideo) "VIDEO" else "FOTO"}] pro model: ${model.displayName} ($ditResolved)")

            val effectiveFrames = if (isVideo) frames else 1
            val alignedWidth = ((width + 15) / 16) * 16
            val alignedHeight = ((height + 15) / 16) * 16

            // Alokace Bitmap bufferů pro přímý zápis z nativní C++ sítě
            val rawBitmaps = Array(effectiveFrames) {
                Bitmap.createBitmap(alignedWidth, alignedHeight, Bitmap.Config.ARGB_8888)
            }

            val success = nativeExecuteGeneration(
                nativeHandle = nativeHandle,
                prompt = prompt,
                negativePrompt = negativePrompt,
                width = alignedWidth,
                height = alignedHeight,
                frames = effectiveFrames,
                steps = steps,
                guidance = guidance,
                seed = seed,
                enableTeaCache = enableTeaCache,
                outBitmaps = rawBitmaps,
                callback = callback
            )

            if (!success) {
                rawBitmaps.forEach { if (!it.isRecycled) it.recycle() }
                val wasCancelled = nativeIsCancelled() || !coroutineContext.isActive
                if (wasCancelled) {
                    Log.i(TAG, "⏹️ Nativní generování bylo korektně zastaveno na žádost uživatele.")
                    throw kotlinx.coroutines.CancellationException("Generování bylo zastaveno uživatelem.")
                }
                throw RuntimeException("Nativní generování selhalo.")
            }

            if (!isVideo) {
                // === REŽIM FOTOGRAFIE (Text-to-Image / 1 snímek) ===
                _status.value = EngineStatus.Running(4, 1, 1, "Fáze 4/4: Ukládání reálné neuronové fotografie (PNG)...")
                onProgress?.invoke(0.95f, "Ukládání neuronové fotografie...")

                java.io.FileOutputStream(outputFile).use { fos ->
                    rawBitmaps[0].compress(Bitmap.CompressFormat.PNG, 100, fos)
                }
                rawBitmaps.forEach { if (!it.isRecycled) it.recycle() }

                val duration = System.currentTimeMillis() - startTime
                _status.value = EngineStatus.Success(outputFile, duration)
                onProgress?.invoke(1.0f, "Dokončeno za ${duration / 1000}s (Neuronová fotografie)")
                Log.i(TAG, "✅ Neuronová fotografie úspěšně uložena: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                return@withContext Result.success(outputFile)
            }

            // === REŽIM VIDEA (Text-to-Video / Vícesnímkové) ===
            _status.value = EngineStatus.Running(4, 1, 2, "Fáze 4/4: Hardwarový převod neuronových snímků do MP4...")
            onProgress?.invoke(0.92f, "Muxování do MP4...")

            val muxer = GenVideoMuxer()
            val baseVideoFile = File(context.cacheDir, "base_${System.currentTimeMillis()}.mp4")
            val muxResult = muxer.encodeBitmapsToMp4(
                frames = rawBitmaps.toList(),
                outputFile = baseVideoFile,
                fps = 16
            )

            try {
                if (rawBitmaps.isNotEmpty()) {
                    val previewFile = File(context.cacheDir, "preview_neural_frame_0.png")
                    java.io.FileOutputStream(previewFile).use { fos ->
                        rawBitmaps[0].compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nepodařilo se uložit náhled: ${e.message}")
            }

            rawBitmaps.forEach { if (!it.isRecycled) it.recycle() }

            if (muxResult.isFailure) {
                throw muxResult.exceptionOrNull() ?: RuntimeException("Chyba při muxování základního videa.")
            }

            // NDK 60 FPS motion interpolace
            onProgress?.invoke(0.97f, "NDK 60 FPS Motion Interpolace...")
            val interpolator = GenMotionInterpolator(context)
            val interpResult = interpolator.interpolateTo60Fps(
                inputFile = baseVideoFile,
                outputFile = outputFile,
                targetFps = 60
            )

            val finalFile = if (interpResult.isSuccess && outputFile.exists() && outputFile.length() > 0L) {
                baseVideoFile.delete()
                outputFile
            } else {
                if (outputFile.exists()) {
                    outputFile.delete()
                }
                baseVideoFile.renameTo(outputFile)
                outputFile
            }

            if (finalFile.exists() && finalFile.length() > 0L) {
                val duration = System.currentTimeMillis() - startTime
                _status.value = EngineStatus.Success(finalFile, duration)
                onProgress?.invoke(1.0f, "Dokončeno za ${duration / 1000}s (Nativní MP4)")
                Log.i(TAG, "✅ Video úspěšně vygenerováno: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
                Result.success(finalFile)
            } else {
                throw RuntimeException("Chyba při vytváření výstupního videa: soubor je prázdný")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "⏹️ Úloha generování byla korektně zastavena.")
            _status.value = EngineStatus.Idle
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba během generování: ${e.message}", e)
            _status.value = EngineStatus.Error(e.message ?: "Neznámá chyba generování", e)
            Result.failure(e)
        } finally {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && hintSession != null) {
                try {
                    (hintSession as? android.os.PerformanceHintManager.Session)?.close()
                } catch (_: Throwable) {}
                hintSession = null
            }
            if (activeNativeHandle != 0L) {
                nativeDestroyPipeline(activeNativeHandle)
                activeNativeHandle = 0L
            }
            executionMutex.unlock()
            System.gc()
        }
    }

    var currentThreads: Int = 6
    var currentSampleMethod: Int = 0
    var currentScheduler: Int = 0
    var currentDynamicCfg: Boolean = false
    var currentAffinityMode: Int = 0
    var currentThreadPriority: Int = 1
    var currentEnableOpenmp: Boolean = true
    var currentEnableTeaCache: Boolean = true
    var currentTeaCacheThreshold: Float = 0.12f
    var currentTeaCacheMode: Int = 0
    var currentFastModeNoCfg: Boolean = false
    var currentExecutionBackend: Int = 0

    fun configureTuning(
        threads: Int,
        sampleMethod: Int,
        scheduler: Int,
        dynamicCfg: Boolean,
        enableCpuAffinity: Boolean = true,
        threadPriority: Int = 1,
        affinityMode: Int = 1,
        enableOpenmp: Boolean = true,
        enableTeaCache: Boolean = true,
        teaCacheThreshold: Float = 0.12f,
        teaCacheMode: Int = 0,
        fastModeNoCfg: Boolean = false,
        executionBackend: Int = 0
    ) {
        val effectiveAffinity = if (enableCpuAffinity) affinityMode else 0
        currentThreads = threads
        currentSampleMethod = sampleMethod
        currentScheduler = scheduler
        currentDynamicCfg = dynamicCfg
        currentAffinityMode = effectiveAffinity
        currentThreadPriority = threadPriority
        currentEnableOpenmp = enableOpenmp
        currentEnableTeaCache = enableTeaCache
        currentTeaCacheThreshold = teaCacheThreshold
        currentTeaCacheMode = teaCacheMode
        currentFastModeNoCfg = fastModeNoCfg
        currentExecutionBackend = executionBackend
        if (activeNativeHandle != 0L) {
            try {
                nativeConfigureTuning(
                    nativeHandle = activeNativeHandle,
                    threads = threads,
                    sampleMethod = sampleMethod,
                    scheduler = scheduler,
                    dynamicCfg = dynamicCfg,
                    affinityMode = effectiveAffinity,
                    threadPriority = threadPriority,
                    enableOpenmp = enableOpenmp,
                    enableTeaCache = enableTeaCache,
                    teaCacheThreshold = teaCacheThreshold,
                    teaCacheMode = teaCacheMode,
                    fastModeNoCfg = fastModeNoCfg,
                    executionBackend = executionBackend
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Nepodařilo se aplikovat tuning na aktivní kontext: ${e.message}")
            }
        }
    }

    fun cancel() {
        val handle = activeNativeHandle
        if (handle != 0L) {
            Log.i(TAG, "Signalizuji nativní přerušení výpočtu (handle=$handle)...")
            nativeCancel(handle)
            // POZOR: Pipeline se bezpečně uvolní ve finally bloku metody generateVideo
            // po návratu nativního výpočetního vlákna, aby nedošlo k double-free či crashi.
        }
        _status.value = EngineStatus.Idle
        System.gc()
    }

    // --- Native JNI Declarations ---
    private external fun nativeDetectHardware(): String
    private external fun nativeInitPipeline(ditPath: String?, textEncPath: String?, vaePath: String?, nativeLibDir: String?): Long
    private external fun nativeConfigureTuning(
        nativeHandle: Long,
        threads: Int,
        sampleMethod: Int,
        scheduler: Int,
        dynamicCfg: Boolean,
        affinityMode: Int,
        threadPriority: Int,
        enableOpenmp: Boolean,
        enableTeaCache: Boolean,
        teaCacheThreshold: Float,
        teaCacheMode: Int,
        fastModeNoCfg: Boolean,
        executionBackend: Int
    )
    private external fun nativeExecuteGeneration(
        nativeHandle: Long,
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        frames: Int,
        steps: Int,
        guidance: Float,
        seed: Long,
        enableTeaCache: Boolean,
        outBitmaps: Array<Bitmap>,
        callback: NativeProgressCallback?
    ): Boolean
    private external fun nativeCancel(nativeHandle: Long)
    private external fun nativeIsCancelled(): Boolean
    private external fun nativeDestroyPipeline(nativeHandle: Long)

    @Keep
    interface NativeProgressCallback {
        fun onProgress(phase: Int, step: Int, total: Int, message: String)
    }
}
