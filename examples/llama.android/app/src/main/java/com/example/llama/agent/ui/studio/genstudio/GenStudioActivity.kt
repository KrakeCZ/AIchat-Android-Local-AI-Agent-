package com.example.llama.agent.ui.studio.genstudio

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.example.llama.srt.ui.editor.SrtVisualEditorActivity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.llama.agent.ui.studio.genstudio.engine.*
import com.example.llama.agent.ui.studio.genstudio.importer.GenModelImporter
import com.example.llama.agent.ui.studio.genstudio.importer.GenModelPairingManager
import com.example.llama.agent.ui.studio.genstudio.memory.PhaseLockedMemoryStateMachine
import com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences
import com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfiler
import com.example.llama.agent.ui.studio.genstudio.profiling.ThermalDutyCycleGovernor
import com.example.llama.agent.ui.studio.genstudio.service.GenStudioForegroundService
import com.example.llama.data.gen.GenDatabase
import com.example.llama.data.gen.GenModel
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rozpozná, zda jde o primární generativní model (DiT / backbone) nebo pouze o pomocnou subsíť (Enkodér / VAE).
 */
fun isPrimaryGenerativeModel(model: GenModel?): Boolean {
    if (model == null) return false
    val arch = model.architecture.uppercase()
    val name = model.displayName.lowercase()
    val file = model.filePath.lowercase()
    if (arch.contains("ENCODER") || arch.contains("VAE")) return false
    if (name.contains("vae") || file.contains("vae") || name.contains("ae.safetensors") || file.contains("ae.safetensors") || name.contains("ae.gguf") || file.contains("ae.gguf")) return false
    if (name.contains("umt5") || name.contains("clip") || (name.contains("t5") && !name.contains("t2v") && !name.contains("i2v"))) return false
    if (file.contains("umt5") || file.contains("clip") || (file.contains("t5") && !file.contains("t2v") && !file.contains("i2v"))) return false
    return true
}

/**
 * Profesionální studio pro generování AI videa a obrazu (GEN_Studio).
 * Vybaveno přímým Tensor Debuggerem, Text Conditioning enginem, ochranou LMK VRAM a NDK 60 FPS interpolací.
 */
class GenStudioActivity : ComponentActivity() {

    private lateinit var profiler: HardwareProfiler
    private lateinit var thermalGovernor: ThermalDutyCycleGovernor
    private lateinit var memoryStateMachine: PhaseLockedMemoryStateMachine
    private lateinit var engineRouter: GenStudioEngineRouter
    private lateinit var genDatabase: GenDatabase
    private lateinit var modelImporter: GenModelImporter
    private lateinit var motionInterpolator: GenMotionInterpolator
    private lateinit var tensorParser: GgufTensorParser
    private lateinit var pairingManager: GenModelPairingManager
    private lateinit var studioPrefs: GenStudioPreferences

    private var currentGenerationJob: Job? = null
    private var isInspecting by mutableStateOf(false)
    private var inspectionResult by mutableStateOf<ModelTensorInspection?>(null)
    private var inspectingModel by mutableStateOf<GenModel?>(null)
    private var modelToPair: GenModel? = null
    private var onImageSelectedCallback: ((Bitmap?) -> Unit)? = null
    private var lastClickTime: Long = 0L
    private var lastActionTimeMs: Long = 0L

    private fun isDebounced(thresholdMs: Long = 500L): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionTimeMs < thresholdMs) return true
        lastActionTimeMs = now
        return false
    }

    private lateinit var modelPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<String>
    private lateinit var textEncoderPickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var vaePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var storageManagerLauncher: ActivityResultLauncher<Intent>
    private lateinit var saveDocumentLauncher: ActivityResultLauncher<String>
    private lateinit var audioPickerLauncher: ActivityResultLauncher<String>

    private var onAudioSelectedForVideo: ((Uri) -> Unit)? = null
    private var fileToSaveAs: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        profiler = HardwareProfiler(this)
        thermalGovernor = ThermalDutyCycleGovernor(profiler)
        memoryStateMachine = PhaseLockedMemoryStateMachine(this)
        engineRouter = GenStudioEngineRouter(this, memoryStateMachine, profiler, thermalGovernor)
        genDatabase = GenDatabase.getDatabase(this)
        modelImporter = GenModelImporter(this, genDatabase)
        motionInterpolator = GenMotionInterpolator(this)
        tensorParser = GgufTensorParser(this)
        pairingManager = GenModelPairingManager(this, genDatabase)
        studioPrefs = GenStudioPreferences(this)

        // Sustained Performance Mode: uzamkne Qualcomm governor a zabrání agresivnímu skokovému podtaktování během neuronových výpočtů
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
            Log.i("GenStudio", "⚡ SustainedPerformanceMode aktivován pro GenStudioActivity")
        }

        // Initialize hardware tuning profile and OpenMP/Affinity based on saved user preferences
        VideoGenEngine.getInstance().configureTuning(
            threads = studioPrefs.threadCount,
            sampleMethod = studioPrefs.sampleMethod,
            scheduler = studioPrefs.scheduler,
            dynamicCfg = studioPrefs.dynamicCfgThresholding,
            enableCpuAffinity = studioPrefs.enableCpuAffinity,
            threadPriority = studioPrefs.threadPriority,
            affinityMode = studioPrefs.affinityMode,
            enableOpenmp = studioPrefs.enableOpenmp,
            enableTeaCache = studioPrefs.enableTeaCache,
            teaCacheThreshold = studioPrefs.teaCacheThreshold,
            teaCacheMode = studioPrefs.teaCacheMode,
            fastModeNoCfg = studioPrefs.fastModeNoCfg
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                genDatabase.genModelDao().sanitizeVaeModels()
                genDatabase.genModelDao().sanitizeTextEncoderModels()
                val paired = pairingManager.autoPairAllModels()
                if (paired > 0) {
                    Log.i("GenStudioActivity", "🔗 Inicializační auto-pairing propojil $paired subsítí.")
                }
            } catch (e: Exception) {
                Log.w("GenStudioActivity", "Auto-pairing initialization error: ${e.message}")
            }
        }

        modelPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handleModelImport(it) }
        }

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { handleImageSelected(it) }
        }

        textEncoderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handlePairTextEncoder(it) }
        }

        vaePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { handlePairVae(it) }
        }

        audioPickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { onAudioSelectedForVideo?.invoke(it) }
        }

        saveDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val src = fileToSaveAs
            if (uri != null && src != null && src.exists()) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        src.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    Toast.makeText(this, "✅ Soubor byl úspěšně uložen do telefonu!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Chyba při ukládání: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        storageManagerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "✅ Oprávnění uděleno: Přístup ke všem souborům je aktivní.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ Oprávnění nebylo uděleno. Načítání velkých modelů z úložiště může selhat.", Toast.LENGTH_LONG).show()
                }
            }
        }

        checkStoragePermissions()

        setContent {
            val importedModels by genDatabase.genModelDao().getAllModels().collectAsState(initial = emptyList())
            val progressState by engineRouter.engineState.collectAsState()
            val initialFile = remember {
                getExternalFilesDir(null)?.listFiles { f ->
                    f.isFile && (f.name.startsWith("gen_studio_") || f.extension.lowercase() in listOf("mp4", "png"))
                }?.maxByOrNull { it.lastModified() }
            }
            var currentVideoFile by remember { mutableStateOf<File?>(initialFile) }

            var inspectingModel by remember { mutableStateOf<GenModel?>(null) }
            var inspectionResult by remember { mutableStateOf<ModelTensorInspection?>(null) }
            var isInspecting by remember { mutableStateOf(false) }

            // Aktualizace přehrávaného souboru po dokončení generování
            LaunchedEffect(progressState) {
                if (progressState is GenProgressState.Completed) {
                    currentVideoFile = (progressState as GenProgressState.Completed).outputFile
                }
            }

            GenStudioProfessionalScreen(
                profiler = profiler,
                studioPrefs = studioPrefs,
                importedModels = importedModels,
                progressState = progressState,
                generationStartTimeMs = engineRouter.generationStartTimeMs,
                currentVideoFile = currentVideoFile,
                onBack = { finish() },
                onImportModelClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        checkStoragePermissions()
                    } else {
                        modelPickerLauncher.launch(arrayOf("*/*"))
                    }
                },
                onPickInitialImage = { callback ->
                    onImageSelectedCallback = callback
                    imagePickerLauncher.launch("image/*")
                },
                onInspectModel = { model ->
                    inspectingModel = model
                    isInspecting = true
                    lifecycleScope.launch {
                        val res = tensorParser.inspect(model.filePath)
                        inspectionResult = res.getOrNull()
                        isInspecting = false
                    }
                },
                onPairTextEncoderClick = { model ->
                    modelToPair = model
                    textEncoderPickerLauncher.launch(arrayOf("*/*"))
                },
                onPairVaeClick = { model ->
                    modelToPair = model
                    vaePickerLauncher.launch(arrayOf("*/*"))
                },
                onDeleteModel = { model ->
                    lifecycleScope.launch {
                        genDatabase.genModelDao().deleteModel(model)
                        Toast.makeText(this@GenStudioActivity, "Model odstraněn z databáze", Toast.LENGTH_SHORT).show()
                    }
                },
                onStartGeneration = { config, selectedModel, autoInterpolate60Fps ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                        Toast.makeText(this@GenStudioActivity, "Před spuštěním generování je nutné povolit přístup ke všem souborům v nastavení.", Toast.LENGTH_LONG).show()
                        checkStoragePermissions()
                    } else if (!isDebounced()) {
                        startGenerationTask(config, selectedModel, autoInterpolate60Fps) { completedFile ->
                            currentVideoFile = completedFile
                        }
                    }
                },
                onInterpolate60Fps = { file ->
                    interpolateTo60FpsTask(file) { interpolatedFile ->
                        currentVideoFile = interpolatedFile
                    }
                },
                onAttachAudioToVideo = { file ->
                    attachAudioToVideoTask(file) { fileWithAudio ->
                        currentVideoFile = fileWithAudio
                    }
                },
                onCancelGeneration = {
                    if (!isDebounced()) {
                        engineRouter.cancelGeneration()
                        lifecycleScope.launch {
                            VideoGenEngine.getInstance().cancel()
                        }
                        currentGenerationJob?.cancel()
                        GenStudioForegroundService.stopService(this@GenStudioActivity)
                        Toast.makeText(this@GenStudioActivity, "⏹️ Generování zastaveno a paměť uvolněna", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenInPostProduction = { videoFile ->
                    openInSrtVisualEditor(videoFile)
                },
                onSaveToGallery = { file ->
                    saveOutputFileToGallery(file)
                },
                onSaveAsDocument = { file ->
                    saveOutputFileToPhone(file)
                },
                onShareFile = { file ->
                    shareOutputFile(file)
                }
            )

            // Dialog pro Tensor Debugger & Model Inspector
            if (inspectingModel != null) {
                ModelTensorInspectorDialog(
                    model = inspectingModel!!,
                    inspection = inspectionResult,
                    isLoading = isInspecting,
                    onDismiss = {
                        inspectingModel = null
                        inspectionResult = null
                    },
                    onPairTextEncoder = {
                        modelToPair = inspectingModel
                        textEncoderPickerLauncher.launch(arrayOf("*/*"))
                    },
                    onPairVae = {
                        modelToPair = inspectingModel
                        vaePickerLauncher.launch(arrayOf("*/*"))
                    },
                    onResetTextEncoder = {
                        val m = inspectingModel ?: return@ModelTensorInspectorDialog
                        lifecycleScope.launch {
                            pairingManager.resetToUniversal(m.id, GenModelPairingManager.ComponentType.TEXT_ENCODER)
                            inspectingModel = genDatabase.genModelDao().getModelById(m.id)
                        }
                    },
                    onResetVae = {
                        val m = inspectingModel ?: return@ModelTensorInspectorDialog
                        lifecycleScope.launch {
                            pairingManager.resetToUniversal(m.id, GenModelPairingManager.ComponentType.VAE_DECODER)
                            inspectingModel = genDatabase.genModelDao().getModelById(m.id)
                        }
                    }
                )
            }
        }
    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storageManagerLauncher.launch(intent)
                    Toast.makeText(this, "Pro načítání AI modelů přímo z úložiště povolte 'Přístup ke všem souborům'.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        storageManagerLauncher.launch(intent)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun handleModelImport(uri: Uri) {
        lifecycleScope.launch {
            Toast.makeText(this@GenStudioActivity, "🔍 Zkoumám binární hlavičku modelu...", Toast.LENGTH_SHORT).show()
            val result = modelImporter.importFromUri(uri, copyToInternalStorage = false)
            if (result.isSuccess) {
                val model = result.getOrNull()
                val autoPaired = pairingManager.autoPairAllModels()
                val pairNote = if (autoPaired > 0) " (Autonomně spárováno: $autoPaired subsítí)" else ""
                Toast.makeText(this@GenStudioActivity, "✅ Model importován: ${model?.displayName} [${model?.format}]$pairNote", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@GenStudioActivity, "❌ Chyba importu: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun handlePairTextEncoder(uri: Uri) {
        val model = modelToPair ?: return
        lifecycleScope.launch {
            genDatabase.genModelDao().updateTextEncoderPath(model.id, uri.toString())
            Toast.makeText(this@GenStudioActivity, "🔗 Textový enkodér spárován s ${model.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handlePairVae(uri: Uri) {
        val model = modelToPair ?: return
        lifecycleScope.launch {
            genDatabase.genModelDao().updateVaePath(model.id, uri.toString())
            Toast.makeText(this@GenStudioActivity, "🔗 VAE dekodér spárován s ${model.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleImageSelected(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
            onImageSelectedCallback?.invoke(bitmap)
            Toast.makeText(this, "✅ Referenční obrázek nastaven", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba načítání obrázku: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startGenerationTask(
        config: GenExecutionConfig,
        selectedModel: GenModel?,
        autoInterpolate60Fps: Boolean,
        onComplete: (File) -> Unit
    ) {
        val prevJob = currentGenerationJob
        currentGenerationJob = lifecycleScope.launch {
            if (prevJob != null && prevJob.isActive) {
                Log.i("GenStudioActivity", "⏹️ Zastavuji předchozí generování a čekám na uvolnění paměti...")
                engineRouter.cancelGeneration()
                VideoGenEngine.getInstance().cancel()
                prevJob.cancel()
                try {
                    prevJob.join()
                } catch (e: Throwable) {
                    // Ignorovat výjimku ze zrušení
                }
                Log.i("GenStudioActivity", "✅ Předchozí generování bezpečně ukončeno.")
            }

            // 0. Auto-pairing subsítí před generováním
            pairingManager.autoPairAllModels()

            val allDbModels = genDatabase.genModelDao().getAllModels().firstOrNull() ?: emptyList()
            val candidate = selectedModel
                ?: (if (config.frameCount > 1) genDatabase.genModelDao().getLatestVideoModel() else genDatabase.genModelDao().getLatestImageModel())
                ?: allDbModels.firstOrNull { isPrimaryGenerativeModel(it) }
                ?: genDatabase.genModelDao().getLatestModel()

            if (candidate == null) {
                Toast.makeText(
                    this@GenStudioActivity,
                    "❌ Žádný model není k dispozici. Nejprve klikněte na 'Importovat soubor' a vyberte model z úložiště telefonu.",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val modelToUse: GenModel = if (isPrimaryGenerativeModel(candidate)) {
                genDatabase.genModelDao().getModelById(candidate.id) ?: candidate
            } else {
                val realDiT = allDbModels.firstOrNull { isPrimaryGenerativeModel(it) }
                if (realDiT != null) {
                    Log.w("GenStudioActivity", "⚠️ Model '${candidate.displayName}' je podsíť. Automaticky přepnuto na primární DiT model '${realDiT.displayName}'.")
                    genDatabase.genModelDao().getModelById(realDiT.id) ?: realDiT
                } else {
                    Toast.makeText(
                        this@GenStudioActivity,
                        "❌ Soubor '${candidate.displayName}' je pomocná podsíť (${candidate.architecture}). Pro generování videa je nutné vybrat hlavní DiT model (např. Wan2.1-T2V-1.3B).",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
            }

            genDatabase.genModelDao().updateLastAccess(modelToUse.id, System.currentTimeMillis())

            val isVideo = config.frameCount > 1
            val ext = if (isVideo) "mp4" else "png"
            val outputFile = File(getExternalFilesDir(null), "gen_studio_${System.currentTimeMillis()}.$ext")

            // 1. Spuštění Foreground Service s WakeLockem a notifikací
            GenStudioForegroundService.onCancelRequested = {
                engineRouter.cancelGeneration()
                lifecycleScope.launch {
                    VideoGenEngine.getInstance().cancel()
                }
                currentGenerationJob?.cancel()
            }
            GenStudioForegroundService.startService(
                this@GenStudioActivity,
                if (isVideo) "AI Video Syntéza (${modelToUse.displayName})" else "AI Foto Syntéza (${modelToUse.displayName})"
            )

            val progressCollectorJob = launch {
                engineRouter.engineState.collect { state ->
                    when (state) {
                        is GenProgressState.TextConditioning -> {
                            GenStudioForegroundService.updateProgress(
                                this@GenStudioActivity,
                                (state.progress * 100).toInt(),
                                "Sémantické kódování textu..."
                            )
                        }
                        is GenProgressState.Denoising -> {
                            GenStudioForegroundService.updateProgress(
                                this@GenStudioActivity,
                                (state.progress * 100).toInt(),
                                "Neuronový Denoising krok ${state.step}/${state.totalSteps}"
                            )
                        }
                        is GenProgressState.LatentDecoding -> {
                            GenStudioForegroundService.updateProgress(
                                this@GenStudioActivity,
                                ((state.frame.toFloat() / maxOf(1, state.totalFrames)) * 100).toInt(),
                                "3D-VAE Dekódování ${state.frame}/${state.totalFrames}"
                            )
                        }
                        is GenProgressState.VideoMuxing -> {
                            GenStudioForegroundService.updateProgress(
                                this@GenStudioActivity,
                                (state.progress * 100).toInt(),
                                "Hardwarové muxování do MP4..."
                            )
                        }
                        else -> {}
                    }
                }
            }

            try {
                // Ensure fresh hardware tuning configuration is synchronized to native C++ engine
                VideoGenEngine.getInstance().configureTuning(
                    threads = studioPrefs.threadCount,
                    sampleMethod = studioPrefs.sampleMethod,
                    scheduler = studioPrefs.scheduler,
                    dynamicCfg = studioPrefs.dynamicCfgThresholding,
                    enableCpuAffinity = studioPrefs.enableCpuAffinity,
                    threadPriority = studioPrefs.threadPriority,
                    affinityMode = studioPrefs.affinityMode,
                    enableOpenmp = studioPrefs.enableOpenmp,
                    enableTeaCache = studioPrefs.enableTeaCache,
                    teaCacheThreshold = studioPrefs.teaCacheThreshold,
                    teaCacheMode = studioPrefs.teaCacheMode,
                    fastModeNoCfg = studioPrefs.fastModeNoCfg,
                    executionBackend = studioPrefs.executionBackend
                )

                val prepareResult = engineRouter.prepareModel(modelToUse)
                if (prepareResult.isFailure) {
                    Toast.makeText(this@GenStudioActivity, "Chyba inicializace: ${prepareResult.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val genResult = engineRouter.executeGeneration(config, outputFile)
                if (genResult.isSuccess) {
                    val rawFile = genResult.getOrNull() ?: outputFile
                    if (isVideo && autoInterpolate60Fps) {
                        Toast.makeText(this@GenStudioActivity, "⚡ Aplikuji NDK 60 FPS Motion Interpolaci...", Toast.LENGTH_SHORT).show()
                        val smoothFile = File(getExternalFilesDir(null), "gen_studio_60fps_${System.currentTimeMillis()}.mp4")
                        val interpResult = motionInterpolator.interpolateTo60Fps(rawFile, smoothFile, targetFps = 60)
                        if (interpResult.isSuccess) {
                            Toast.makeText(this@GenStudioActivity, "✅ Video vygenerováno v 60 FPS!", Toast.LENGTH_LONG).show()
                            onComplete(smoothFile)
                            return@launch
                        }
                    }
                    Toast.makeText(this@GenStudioActivity, "✅ Hotovo: ${outputFile.name}", Toast.LENGTH_LONG).show()
                    onComplete(rawFile)
                } else {
                    val err = genResult.exceptionOrNull()
                    val isCancelled = err is kotlinx.coroutines.CancellationException ||
                                      err?.message?.contains("zrušeno", ignoreCase = true) == true ||
                                      err?.message?.contains("přerušeno", ignoreCase = true) == true ||
                                      err?.message?.contains("zastaveno", ignoreCase = true) == true ||
                                      err?.message?.contains("cancelled", ignoreCase = true) == true
                    if (!isCancelled) {
                        Toast.makeText(this@GenStudioActivity, "❌ Chyba: ${err?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                progressCollectorJob.cancel()
                if (currentGenerationJob === coroutineContext[kotlinx.coroutines.Job]) {
                    currentGenerationJob = null
                    GenStudioForegroundService.stopService(this@GenStudioActivity)
                }
            }
        }
    }

    private fun attachAudioToVideoTask(videoFile: File, onDone: (File) -> Unit) {
        onAudioSelectedForVideo = { audioUri ->
            lifecycleScope.launch {
                Toast.makeText(this@GenStudioActivity, "🎵 Muxuji zvukovou stopu k videu...", Toast.LENGTH_SHORT).show()
                try {
                    val audioTempFile = File(cacheDir, "temp_audio_${System.currentTimeMillis()}.m4a")
                    contentResolver.openInputStream(audioUri)?.use { input ->
                        audioTempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val outputWithAudio = File(getExternalFilesDir(null), "gen_studio_audio_${System.currentTimeMillis()}.mp4")
                    val muxer = GenVideoMuxer()
                    val result = muxer.muxVideoWithAudio(videoFile, audioTempFile, outputWithAudio)
                    audioTempFile.delete()
                    if (result.isSuccess) {
                        Toast.makeText(this@GenStudioActivity, "✅ Zvuková stopa úspěšně synchronizována do MP4!", Toast.LENGTH_LONG).show()
                        onDone(outputWithAudio)
                    } else {
                        Toast.makeText(this@GenStudioActivity, "Chyba sloučení: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@GenStudioActivity, "Chyba načítání audia: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        audioPickerLauncher.launch("audio/*")
    }

    private fun interpolateTo60FpsTask(videoFile: File, onDone: (File) -> Unit) {
        lifecycleScope.launch {
            Toast.makeText(this@GenStudioActivity, "⚡ Spouštím NDK 60 FPS Motion Interpolaci...", Toast.LENGTH_SHORT).show()
            val outFile = File(getExternalFilesDir(null), "gen_studio_60fps_${System.currentTimeMillis()}.mp4")
            val result = motionInterpolator.interpolateTo60Fps(videoFile, outFile, targetFps = 60)
            if (result.isSuccess) {
                Toast.makeText(this@GenStudioActivity, "✅ Video převedeno na 60 FPS (Ultra Smooth)!", Toast.LENGTH_LONG).show()
                onDone(outFile)
            } else {
                Toast.makeText(this@GenStudioActivity, "Chyba interpolace: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openInSrtVisualEditor(videoFile: File) {
        try {
            val intent = Intent(this, SrtVisualEditorActivity::class.java).apply {
                putExtra(SrtVisualEditorActivity.EXTRA_VIDEO_URI, Uri.fromFile(videoFile).toString())
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nelze otevřít SRT Visual Editor: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveOutputFileToPhone(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Soubor k uložení neexistuje", Toast.LENGTH_SHORT).show()
            return
        }
        fileToSaveAs = file
        saveDocumentLauncher.launch(file.name)
    }

    private fun saveOutputFileToGallery(file: File) {
        if (!file.exists()) {
            Toast.makeText(this, "Soubor k uložení neexistuje", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val isVideo = file.extension.lowercase() == "mp4"
                val mimeType = if (isVideo) "video/mp4" else "image/png"
                val collection = if (isVideo) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    }
                } else {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                }
                val relativeDir = if (isVideo) "${Environment.DIRECTORY_MOVIES}/GEN_Studio" else "${Environment.DIRECTORY_PICTURES}/GEN_Studio"

                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val uri = contentResolver.insert(collection, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { inp -> inp.copyTo(out) }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        contentResolver.update(uri, values, null, null)
                    }
                    android.media.MediaScannerConnection.scanFile(
                        this@GenStudioActivity,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                    Toast.makeText(this@GenStudioActivity, "✅ Úspěšně uloženo do Galerie (${if (isVideo) "Videa" else "Fotky"})!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@GenStudioActivity, "Chyba vytvoření položky v Galerii", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("GenStudio", "Chyba při ukládání do Galerie", e)
                Toast.makeText(this@GenStudioActivity, "Chyba: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareOutputFile(file: File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val isVideo = file.extension.lowercase() == "mp4"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/mp4" else "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Sdílet vygenerovaný mediální soubor"))
        } catch (e: Exception) {
            Log.e("GenStudio", "Chyba sdílení: ${e.message}", e)
            Toast.makeText(this, "Chyba sdílení: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(true)
        }
    }

    override fun onPause() {
        super.onPause()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager?.isSustainedPerformanceModeSupported == true) {
            window.setSustainedPerformanceMode(false)
        }
        engineRouter.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenStudioProfessionalScreen(
    profiler: HardwareProfiler,
    studioPrefs: GenStudioPreferences,
    importedModels: List<GenModel>,
    progressState: GenProgressState,
    generationStartTimeMs: Long? = null,
    currentVideoFile: File?,
    onBack: () -> Unit,
    onImportModelClick: () -> Unit,
    onPickInitialImage: ((Bitmap?) -> Unit) -> Unit,
    onInspectModel: (GenModel) -> Unit,
    onPairTextEncoderClick: (GenModel) -> Unit,
    onPairVaeClick: (GenModel) -> Unit,
    onDeleteModel: (GenModel) -> Unit,
    onStartGeneration: (GenExecutionConfig, GenModel?, Boolean) -> Unit,
    onInterpolate60Fps: (File) -> Unit,
    onAttachAudioToVideo: (File) -> Unit,
    onCancelGeneration: () -> Unit,
    onOpenInPostProduction: (File) -> Unit,
    onSaveToGallery: (File) -> Unit,
    onSaveAsDocument: (File) -> Unit,
    onShareFile: (File) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf("🎬 Generátor", "⚙️ DiT & VAE Tuning", "📦 Modely", "🎞️ Přehrávač")

    // Načtení relace ze SharedPreferences (Session Persistence)
    var prompt by remember { mutableStateOf(studioPrefs.prompt) }
    var negativePrompt by remember { mutableStateOf(studioPrefs.negativePrompt) }
    var selectedModel by remember { mutableStateOf<GenModel?>(null) }
    var initialFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var autoInterpolate60Fps by remember { mutableStateOf(studioPrefs.autoInterpolate60Fps) }

    // Parametry difúze a formátu
    var frameCount by remember { mutableFloatStateOf(17f) }
    var steps by remember { mutableFloatStateOf(studioPrefs.steps) }
    var guidanceScale by remember { mutableFloatStateOf(studioPrefs.guidanceScale) }
    var selectedWidth by remember { mutableIntStateOf(studioPrefs.selectedWidth) }
    var selectedHeight by remember { mutableIntStateOf(studioPrefs.selectedHeight) }
    var seed by remember { mutableStateOf(studioPrefs.seed) }
    var isPhotoMode by remember { mutableStateOf(studioPrefs.isPhotoMode) }
    var showTelemetry by remember { mutableStateOf(studioPrefs.showTelemetry) }

    // Tuning Snapdragon 8 Gen 3 & Schedulery & TeaCache & OpenMP & Power Profily
    var powerProfile by remember { mutableIntStateOf(studioPrefs.powerProfile) }
    var cpuThreads by remember { mutableIntStateOf(studioPrefs.threadCount) }
    var enableCpuAffinity by remember { mutableStateOf(studioPrefs.enableCpuAffinity) }
    var affinityMode by remember { mutableIntStateOf(studioPrefs.affinityMode) }
    var threadPriority by remember { mutableIntStateOf(studioPrefs.threadPriority) }
    var sampleMethod by remember { mutableIntStateOf(studioPrefs.sampleMethod) }
    var scheduler by remember { mutableIntStateOf(studioPrefs.scheduler) }
    var dynamicCfg by remember { mutableStateOf(studioPrefs.dynamicCfgThresholding) }
    var enableOpenmp by remember { mutableStateOf(studioPrefs.enableOpenmp) }
    var enableTeaCache by remember { mutableStateOf(studioPrefs.enableTeaCache) }
    var teaCacheThreshold by remember { mutableFloatStateOf(studioPrefs.teaCacheThreshold) }
    var teaCacheMode by remember { mutableIntStateOf(studioPrefs.teaCacheMode) }
    var fastModeNoCfg by remember { mutableStateOf(studioPrefs.fastModeNoCfg) }
    var executionBackend by remember { mutableIntStateOf(studioPrefs.executionBackend) }

    val applyTuning = {
        VideoGenEngine.getInstance().configureTuning(
            threads = cpuThreads,
            sampleMethod = sampleMethod,
            scheduler = scheduler,
            dynamicCfg = dynamicCfg,
            enableCpuAffinity = enableCpuAffinity,
            threadPriority = threadPriority,
            affinityMode = affinityMode,
            enableOpenmp = enableOpenmp,
            enableTeaCache = enableTeaCache,
            teaCacheThreshold = teaCacheThreshold,
            teaCacheMode = teaCacheMode,
            fastModeNoCfg = fastModeNoCfg,
            executionBackend = executionBackend
        )
    }

    val setPowerProfile: (Int) -> Unit = { mode ->
        powerProfile = mode
        studioPrefs.applyPowerProfile(mode)
        cpuThreads = studioPrefs.threadCount
        enableCpuAffinity = studioPrefs.enableCpuAffinity
        affinityMode = studioPrefs.affinityMode
        threadPriority = studioPrefs.threadPriority
        enableOpenmp = studioPrefs.enableOpenmp
        fastModeNoCfg = studioPrefs.fastModeNoCfg
        applyTuning()
    }

    // Globální sledování času a živé kontinuální interpolace kroků difúze (vyzdviženo na úroveň obrazovky, aby se neresetovalo přepínáním karet)
    var stepStartTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastTrackedStep by remember { mutableIntStateOf(-1) }
    var measuredStepDurationSec by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(progressState) {
        if (progressState is GenProgressState.Denoising) {
            val denoising = progressState as GenProgressState.Denoising
            if (denoising.step != lastTrackedStep) {
                val now = System.currentTimeMillis()
                if (lastTrackedStep >= 0) {
                    val dur = (now - stepStartTime) / 1000f
                    if (dur > 0.5f) {
                        measuredStepDurationSec = dur
                    }
                }
                lastTrackedStep = denoising.step
                stepStartTime = now
            }
        } else if (progressState is GenProgressState.Idle || progressState is GenProgressState.Completed || progressState is GenProgressState.Error) {
            lastTrackedStep = -1
            measuredStepDurationSec = 0f
            stepStartTime = System.currentTimeMillis()
        }
    }

    // Rychlé sémantické tagy
    val positiveTags = listOf(
        Pair("🏠 Dům", "a modern architectural villa with warm lights"),
        Pair("👥 Lidé", "cinematic portrait of people walking on street"),
        Pair("🌲 Příroda", "dramatic mountain valley with wild forest and river"),
        Pair("⚡ Cyberpunk", "cyberpunk neon street with rainy reflections"),
        Pair("🎨 Anime", "studio ghibli aesthetic lush countryside"),
        Pair("🛸 Dron 4K", "wide aerial drone footage golden hour")
    )

    val negativeTags = listOf("blurry", "watermark", "deformed", "static", "distorted", "low resolution", "text artifact")

    LaunchedEffect(importedModels) {
        if (selectedModel == null || !isPrimaryGenerativeModel(selectedModel)) {
            val bestPrimary = importedModels.firstOrNull { isPrimaryGenerativeModel(it) }
            if (bestPrimary != null) {
                selectedModel = bestPrimary
            }
        }
    }

    // Automatické přepnutí do přehrávače ihned po dokončení generování
    LaunchedEffect(progressState) {
        if (progressState is GenProgressState.Completed) {
            selectedTab = 3
        }
    }

    // Živý periodický monitor paměti RAM a termálního stavu (aktualizace každých 1600 ms)
    var hwProfile by remember { mutableStateOf(profiler.getActiveProfile()) }
    LaunchedEffect(Unit) {
        while (isActive) {
            val fresh = profiler.getActiveProfile()
            if (kotlin.math.abs(fresh.availableRamMb - hwProfile.availableRamMb) >= 4 ||
                fresh.thermalStatus != hwProfile.thermalStatus) {
                hwProfile = fresh
            }
            delay(1600L)
        }
    }

    val detectedHw = remember { VideoGenEngine.getInstance().getHardwareProfile() }
    val isSnapdragon = detectedHw.isSnapdragon || detectedHw.hasQualcommNpu
    val activeHardwareBadge: String = when (executionBackend) {
        GenStudioPreferences.BACKEND_NPU -> "⚡ NPU (Hexagon HTP v75)"
        GenStudioPreferences.BACKEND_HYBRID -> "🔀 HYBRID (NPU DiT + CPU VAE)"
        GenStudioPreferences.BACKEND_CPU -> "📱 CPU (ARM NEON 6x Core)"
        else -> {
            if (isSnapdragon) "⚡ AUTO → NPU (Hexagon HTP)"
            else "📱 AUTO → CPU (NEON)"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("🎬 GEN_Studio (AI Video Lab)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(detectedHw.displayBadge, color = Color.Gray, fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = Color.White)
                    }
                },
                actions = {
                    Surface(
                        color = if (hwProfile.thermalStatus > 2) Color(0x33EF4444) else Color(0x3310B981),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(if (hwProfile.thermalStatus > 2) Color(0xFFEF4444) else Color(0xFF10B981)))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("${hwProfile.availableRamMb} MB Volno", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF161B22))
            )
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF161B22),
                contentColor = Color(0xFF00E5FF),
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Color(0xFF00E5FF)
                    )
                }
            ) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 13.sp, fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> StudioGeneratorTab(
                        prompt = prompt,
                        onPromptChange = {
                            prompt = it
                            studioPrefs.prompt = it
                        },
                        negativePrompt = negativePrompt,
                        onNegativePromptChange = {
                            negativePrompt = it
                            studioPrefs.negativePrompt = it
                        },
                        positiveTags = positiveTags,
                        negativeTags = negativeTags,
                        onAppendTag = { tag ->
                            prompt = if (prompt.isBlank()) tag else "$prompt, $tag"
                            studioPrefs.prompt = prompt
                        },
                        onAppendNegativeTag = { tag ->
                            negativePrompt = if (negativePrompt.isBlank()) tag else "$negativePrompt, $tag"
                            studioPrefs.negativePrompt = negativePrompt
                        },
                        importedModels = importedModels,
                        selectedModel = selectedModel,
                        onModelSelected = { selectedModel = it },
                        onImportModelClick = onImportModelClick,
                        onInspectModel = onInspectModel,
                        initialFrameBitmap = initialFrameBitmap,
                        onPickInitialImage = { onPickInitialImage { initialFrameBitmap = it } },
                        onClearInitialImage = { initialFrameBitmap = null },
                        selectedWidth = selectedWidth,
                        selectedHeight = selectedHeight,
                        onResolutionChange = { w, h ->
                            selectedWidth = w
                            selectedHeight = h
                            studioPrefs.selectedWidth = w
                            studioPrefs.selectedHeight = h
                        },
                        frameCount = frameCount,
                        onFrameCountChange = { frameCount = it },
                        steps = steps,
                        onStepsChange = {
                            steps = it
                            studioPrefs.steps = it
                        },
                        guidanceScale = guidanceScale,
                        onGuidanceScaleChange = {
                            guidanceScale = it
                            studioPrefs.guidanceScale = it
                        },
                        autoInterpolate60Fps = autoInterpolate60Fps,
                        onAutoInterpolate60FpsChange = {
                            autoInterpolate60Fps = it
                            studioPrefs.autoInterpolate60Fps = it
                        },
                        isPhotoMode = isPhotoMode,
                        onPhotoModeChange = {
                            isPhotoMode = it
                            studioPrefs.isPhotoMode = it
                        },
                        showTelemetry = showTelemetry,
                        onShowTelemetryChange = {
                            showTelemetry = it
                            studioPrefs.showTelemetry = it
                        },
                        executionBackend = executionBackend,
                        onExecutionBackendChange = {
                            executionBackend = it
                            studioPrefs.executionBackend = it
                        },
                        activeHardwareBadge = activeHardwareBadge,
                        progressState = progressState,
                        generationStartTimeMs = generationStartTimeMs,
                        stepStartTime = stepStartTime,
                        lastTrackedStep = lastTrackedStep,
                        measuredStepDurationSec = measuredStepDurationSec,
                        onStartGeneration = {
                            val seedVal = seed.toLongOrNull() ?: -1L
                            val actualFrames = if (isPhotoMode) 1 else frameCount.toInt()
                            
                            // Uložení stavu relace
                            studioPrefs.saveAll(
                                prompt = prompt,
                                negativePrompt = negativePrompt,
                                seed = seed,
                                steps = steps,
                                guidanceScale = guidanceScale,
                                width = selectedWidth,
                                height = selectedHeight,
                                auto60Fps = autoInterpolate60Fps,
                                photoMode = isPhotoMode,
                                telemetry = showTelemetry,
                                threadCount = cpuThreads,
                                sampleMethod = sampleMethod,
                                scheduler = scheduler,
                                dynamicCfg = dynamicCfg,
                                enableCpuAffinity = enableCpuAffinity,
                                affinityMode = affinityMode,
                                threadPriority = threadPriority,
                                enableOpenmp = enableOpenmp,
                                enableTeaCache = enableTeaCache,
                                teaCacheThreshold = teaCacheThreshold,
                                teaCacheMode = teaCacheMode,
                                fastModeNoCfg = fastModeNoCfg,
                                powerProfile = powerProfile,
                                executionBackend = executionBackend
                            )

                            // Konfigurace C++ NDK jádra
                            applyTuning()

                            onStartGeneration(
                                GenExecutionConfig(
                                    prompt = prompt,
                                    negativePrompt = negativePrompt,
                                    initialFrame = initialFrameBitmap,
                                    width = selectedWidth,
                                    height = selectedHeight,
                                    frameCount = actualFrames,
                                    steps = steps.toInt(),
                                    guidanceScale = guidanceScale,
                                    seed = seedVal,
                                    enableTelemetry = showTelemetry,
                                    executionBackend = executionBackend
                                ),
                                selectedModel,
                                autoInterpolate60Fps
                            )
                        },
                        onCancelGeneration = onCancelGeneration,
                        onOpenInPostProduction = onOpenInPostProduction,
                        onSaveToGallery = onSaveToGallery,
                        onSaveAsDocument = onSaveAsDocument,
                        onShareFile = onShareFile,
                        hwProfile = hwProfile,
                        currentVideoFile = currentVideoFile
                    )

                    1 -> AdvancedTuningTab(
                        powerProfile = powerProfile,
                        onPowerProfileChange = { setPowerProfile(it) },
                        executionBackend = executionBackend,
                        onExecutionBackendChange = {
                            executionBackend = it
                            studioPrefs.executionBackend = it
                        },
                        guidanceScale = guidanceScale,
                        onGuidanceScaleChange = {
                            guidanceScale = it
                            studioPrefs.guidanceScale = it
                        },
                        seed = seed,
                        onSeedChange = {
                            seed = it
                            studioPrefs.seed = it
                        },
                        steps = steps,
                        onStepsChange = {
                            steps = it
                            studioPrefs.steps = it
                        },
                        cpuThreads = cpuThreads,
                        onCpuThreadsChange = {
                            cpuThreads = it
                            studioPrefs.threadCount = it
                            applyTuning()
                        },
                        enableCpuAffinity = enableCpuAffinity,
                        onEnableCpuAffinityChange = {
                            enableCpuAffinity = it
                            studioPrefs.enableCpuAffinity = it
                            applyTuning()
                        },
                        affinityMode = affinityMode,
                        onAffinityModeChange = {
                            affinityMode = it
                            studioPrefs.affinityMode = it
                            applyTuning()
                        },
                        threadPriority = threadPriority,
                        onThreadPriorityChange = {
                            threadPriority = it
                            studioPrefs.threadPriority = it
                            applyTuning()
                        },
                        sampleMethod = sampleMethod,
                        onSampleMethodChange = {
                            sampleMethod = it
                            studioPrefs.sampleMethod = it
                            applyTuning()
                        },
                        scheduler = scheduler,
                        onSchedulerChange = {
                            scheduler = it
                            studioPrefs.scheduler = it
                            applyTuning()
                        },
                        dynamicCfg = dynamicCfg,
                        onDynamicCfgChange = {
                            dynamicCfg = it
                            studioPrefs.dynamicCfgThresholding = it
                            applyTuning()
                        },
                        enableOpenmp = enableOpenmp,
                        onEnableOpenmpChange = {
                            enableOpenmp = it
                            studioPrefs.enableOpenmp = it
                            applyTuning()
                        },
                        enableTeaCache = enableTeaCache,
                        onEnableTeaCacheChange = {
                            enableTeaCache = it
                            studioPrefs.enableTeaCache = it
                            applyTuning()
                        },
                        teaCacheThreshold = teaCacheThreshold,
                        onTeaCacheThresholdChange = {
                            teaCacheThreshold = it
                            studioPrefs.teaCacheThreshold = it
                            applyTuning()
                        },
                        teaCacheMode = teaCacheMode,
                        onTeaCacheModeChange = {
                            teaCacheMode = it
                            studioPrefs.teaCacheMode = it
                            applyTuning()
                        },
                        fastModeNoCfg = fastModeNoCfg,
                        onFastModeNoCfgChange = {
                            fastModeNoCfg = it
                            studioPrefs.fastModeNoCfg = it
                            applyTuning()
                        },
                        hwProfile = hwProfile
                    )

                    2 -> ModelManagementTab(
                        importedModels = importedModels,
                        onImportModelClick = onImportModelClick,
                        onInspectModel = onInspectModel,
                        onPairTextEncoderClick = onPairTextEncoderClick,
                        onPairVaeClick = onPairVaeClick,
                        onDeleteModel = onDeleteModel
                    )

                    3 -> PlayerAndExportTab(
                        currentVideoFile = currentVideoFile,
                        progressState = progressState,
                        onInterpolate60Fps = onInterpolate60Fps,
                        onAttachAudioToVideo = onAttachAudioToVideo,
                        onOpenInPostProduction = onOpenInPostProduction,
                        onSaveToGallery = onSaveToGallery,
                        onSaveAsDocument = onSaveAsDocument,
                        onShareFile = onShareFile
                    )
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// TAB 1: STUDIO GENERATOR (HLAVNÍ PRACOVNÍ PLOCHA)
// --------------------------------------------------------------------------
@Composable
fun StudioGeneratorTab(
    prompt: String,
    onPromptChange: (String) -> Unit,
    negativePrompt: String,
    onNegativePromptChange: (String) -> Unit,
    positiveTags: List<Pair<String, String>>,
    negativeTags: List<String>,
    onAppendTag: (String) -> Unit,
    onAppendNegativeTag: (String) -> Unit,
    importedModels: List<GenModel>,
    selectedModel: GenModel?,
    onModelSelected: (GenModel) -> Unit,
    onImportModelClick: () -> Unit,
    onInspectModel: (GenModel) -> Unit,
    initialFrameBitmap: Bitmap?,
    onPickInitialImage: () -> Unit,
    onClearInitialImage: () -> Unit,
    selectedWidth: Int,
    selectedHeight: Int,
    onResolutionChange: (Int, Int) -> Unit,
    frameCount: Float,
    onFrameCountChange: (Float) -> Unit,
    steps: Float,
    onStepsChange: (Float) -> Unit,
    guidanceScale: Float,
    onGuidanceScaleChange: (Float) -> Unit,
    autoInterpolate60Fps: Boolean,
    onAutoInterpolate60FpsChange: (Boolean) -> Unit,
    isPhotoMode: Boolean,
    onPhotoModeChange: (Boolean) -> Unit,
    showTelemetry: Boolean,
    onShowTelemetryChange: (Boolean) -> Unit,
    executionBackend: Int,
    onExecutionBackendChange: (Int) -> Unit,
    activeHardwareBadge: String,
    progressState: GenProgressState,
    generationStartTimeMs: Long? = null,
    stepStartTime: Long,
    lastTrackedStep: Int,
    measuredStepDurationSec: Float,
    onStartGeneration: () -> Unit,
    onCancelGeneration: () -> Unit,
    onOpenInPostProduction: (File) -> Unit,
    onSaveToGallery: (File) -> Unit,
    onSaveAsDocument: (File) -> Unit,
    onShareFile: (File) -> Unit,
    hwProfile: com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfile,
    currentVideoFile: File? = null
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Volba režimu: Video vs Foto
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF161B22))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Button(
                onClick = {
                    onPhotoModeChange(false)
                    if (frameCount <= 1f) onFrameCountChange(17f)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!isPhotoMode) Color(0xFF00E5FF) else Color.Transparent,
                    contentColor = if (!isPhotoMode) Color.Black else Color.Gray
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("🎬 Režim Video", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Button(
                onClick = {
                    onPhotoModeChange(true)
                    onFrameCountChange(1f)
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPhotoMode) Color(0xFF00E5FF) else Color.Transparent,
                    contentColor = if (isPhotoMode) Color.Black else Color.Gray
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("📷 Režim Foto", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Volba výpočetního hardware: AUTO / NPU / HYBRID / CPU
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF161B22))
                .border(BorderStroke(1.dp, Color(0xFF21262D)), RoundedCornerShape(10.dp))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Výpočetní Akcelerátor:", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Text(activeHardwareBadge, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val backends = listOf(
                    com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_AUTO to "⚡ AUTO",
                    com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_NPU to "🧠 NPU",
                    com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_HYBRID to "🔀 HYBRID",
                    com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_CPU to "📱 CPU"
                )
                backends.forEach { (bId, bLabel) ->
                    val isSelected = executionBackend == bId
                    Button(
                        onClick = { onExecutionBackendChange(bId) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Color(0xFF00E5FF) else Color(0xFF0D1117),
                            contentColor = if (isSelected) Color.Black else Color.Gray
                        ),
                        border = BorderStroke(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF30363D)),
                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(bLabel, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Aktivní Model:", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (selectedModel != null) {
                    OutlinedButton(
                        onClick = { onInspectModel(selectedModel) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tensor Debugger", fontSize = 11.sp, color = Color(0xFF00E5FF))
                    }
                }
                Button(
                    onClick = onImportModelClick,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF21262D), contentColor = Color(0xFF00E5FF)),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Importovat", fontSize = 11.sp)
                }
            }
        }

        val primaryModels = remember(importedModels) {
            importedModels.filter { isPrimaryGenerativeModel(it) }
        }

        if (primaryModels.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1F1C18)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (importedModels.isEmpty()) {
                        "ℹ️ Žádný model není importován. Klikněte na 'Importovat' a vyberte model z telefonu (.gguf, .onnx, .task, .safetensors)."
                    } else {
                        "⚠️ Nalezeny pouze pomocné subsítě (Textový Enkodér / VAE). Pro generování videa klikněte na 'Importovat' a vyberte hlavní DiT model (např. Wan2.1-T2V-1.3B)."
                    },
                    color = Color(0xFFF59E0B),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp)
                )
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(primaryModels) { model ->
                    FilterChip(
                        selected = selectedModel?.id == model.id,
                        onClick = { onModelSelected(model) },
                        label = { Text("${model.displayName} [${model.format}]", fontSize = 12.sp) },
                        leadingIcon = if (selectedModel?.id == model.id) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp)) }
                        } else null
                    )
                }
            }
        }

        // Informace o spárovaných subsítích k vybranému modelu
        if (selectedModel != null) {
            val encName = selectedModel.textEncoderPath?.substringAfterLast('/')?.substringAfterLast("%2F")
            val vaeName = selectedModel.vaePath?.substringAfterLast('/')?.substringAfterLast("%2F")
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131B26)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔤 Text Enkodér: ", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Text(
                            text = encName ?: "Universal Built-in (Offline)",
                            fontSize = 11.sp,
                            color = if (encName != null) Color(0xFF10B981) else Color(0xFFF59E0B),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🖼️ VAE Dekodér: ", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        Text(
                            text = vaeName ?: "Universal 3D-VAE (Offline)",
                            fontSize = 11.sp,
                            color = if (vaeName != null) Color(0xFF10B981) else Color(0xFFF59E0B),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        // Live Badge: Tensor Conditioning & Cross-Attention Status
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color(0xFF00E5FF)))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Neuronový Engine: Aktivní", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Text("Zero Heuristics • 100% Neural", color = Color.Gray, fontSize = 10.sp)
            }
        }

        // I2V / Image-to-Video Referenční Snímek
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Referenční Snímek (${if (isPhotoMode) "Image-to-Image" else "Image-to-Video"}):", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(if (initialFrameBitmap != null) "Aktivní počáteční snímek pro syntézu" else "Volitelné: Přidejte referenční obrázek", color = Color.Gray, fontSize = 11.sp)
                }

                if (initialFrameBitmap != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Image(
                            bitmap = initialFrameBitmap.asImageBitmap(),
                            contentDescription = "Náhled",
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(onClick = onClearInitialImage) {
                            Icon(Icons.Default.Close, contentDescription = "Smazat", tint = Color(0xFFEF4444))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = onPickInitialImage,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("Vybrat snímek", fontSize = 11.sp)
                    }
                }
            }
        }

        // Prompt Vstup
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("Prompt (popis scény: např. a cute kitten in grass)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.LightGray,
                focusedBorderColor = Color(0xFF00E5FF),
                unfocusedBorderColor = Color(0xFF30363D)
            )
        )

        // Rychlé sémantické tagy pro pozitivní prompt
        Text("Rychlé objekty & styl:", color = Color.LightGray, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(positiveTags) { (name, promptText) ->
                SuggestionChip(
                    onClick = { onAppendTag(promptText) },
                    label = { Text(name, fontSize = 11.sp) }
                )
            }
        }

        // Negative Prompt
        OutlinedTextField(
            value = negativePrompt,
            onValueChange = onNegativePromptChange,
            label = { Text("Negativní prompt (potlačení deformací, vln a šumu)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFEF4444),
                unfocusedTextColor = Color(0xFFF87171),
                focusedBorderColor = Color(0xFFEF4444),
                unfocusedBorderColor = Color(0xFF4B1D1D)
            )
        )

        // Rychlé tagy pro negativní prompt
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(negativeTags) { tag ->
                AssistChip(
                    onClick = { onAppendNegativeTag(tag) },
                    label = { Text("🚫 $tag", fontSize = 10.sp, color = Color(0xFFEF4444)) }
                )
            }
        }

        // Rozlišení & Formát
        Text("Rozlišení & Formát:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            val resList = if (isPhotoMode) {
                listOf(
                    Triple(512, 512, "512x512 (1:1 Čtverec)"),
                    Triple(384, 256, "384x256 (3:2 Rychlý)"),
                    Triple(480, 272, "480x272 (Vyvážený)"),
                    Triple(640, 368, "640x368 (16:9 HD)"),
                    Triple(768, 768, "768x768 (1:1 Detail)"),
                    Triple(1280, 720, "1280x720 (720p HD Foto)"),
                    Triple(720, 1280, "720x1280 (9:16 Portrét)"),
                    Triple(1920, 1088, "1920x1088 (1080p FHD Foto)"),
                    Triple(1088, 1920, "1088x1920 (9:16 FHD Portrét)")
                )
            } else {
                listOf(
                    Triple(384, 256, "384x256 (Rychlý)"),
                    Triple(480, 272, "480x272 (Vyvážený)"),
                    Triple(640, 368, "640x368 (HD 16:9)"),
                    Triple(512, 512, "512x512 (1:1 Čtverec)"),
                    Triple(720, 1280, "720x1280 (9:16 Vertikální)"),
                    Triple(1280, 720, "1280x720 (720p HD Video)"),
                    Triple(1920, 1088, "1920x1088 (1080p FHD Video)")
                )
            }
            items(resList) { (w, h, label) ->
                FilterChip(
                    selected = selectedWidth == w && selectedHeight == h,
                    onClick = { onResolutionChange(w, h) },
                    label = { Text(label, fontSize = 11.sp) }
                )
            }
        }

        // Slidery
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!isPhotoMode) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Délka: ${frameCount.toInt()} snímků", color = Color.LightGray, fontSize = 12.sp)
                    Text("~${String.format("%.1f", frameCount / 16f)} s @ 16 fps", color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
                Slider(value = frameCount, onValueChange = onFrameCountChange, valueRange = 5f..33f, steps = 6)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Délka: 1 snímek (Samostatná fotografie)", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text("PNG Výstup", color = Color.Gray, fontSize = 11.sp)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kroky difúze: ${steps.toInt()} kroků", color = Color.LightGray, fontSize = 12.sp)
                Text(if (isPhotoMode) "Rychlé (4-8) vs Detailní (15-20)" else "Kvalita vs Čas", color = Color.Gray, fontSize = 11.sp)
            }
            Slider(
                value = steps,
                onValueChange = onStepsChange,
                valueRange = 4f..25f,
                steps = 21
            )
        }

        // NDK 60 FPS Interpolace Přepínač (pouze pro video)
        if (!isPhotoMode) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⚡ NDK 60 FPS Motion Interpolace", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("Plynulé dopočtení mezisnímků přes C++ media_engine na 60 fps", color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = autoInterpolate60Fps, onCheckedChange = onAutoInterpolate60FpsChange)
                }
            }
        }

        // Sledování času kroků difúze je vyzdviženo na úroveň GenStudioProfessionalScreen (proti resetu při přepínání karet)

        // Živý časovač uplynulého času (udržuje absolutní čas i při přepínání karet / aplikací, ticker každých 250 ms)
        val isGenerating = progressState is GenProgressState.TextConditioning || progressState is GenProgressState.Denoising || progressState is GenProgressState.LatentDecoding || progressState is GenProgressState.VideoMuxing

        var currentNow by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(isGenerating) {
            while (isGenerating && isActive) {
                currentNow = System.currentTimeMillis()
                delay(250L)
            }
        }
        val elapsedSeconds = if (isGenerating && generationStartTimeMs != null) {
            maxOf(0L, (currentNow - generationStartTimeMs) / 1000L)
        } else 0L

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isGenerating) Color(0xFF00E5FF) else Color(0xFF10B981))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Telemetrie neuronové sítě", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (showTelemetry) "Aktivní" else "Vypnuto", color = if (showTelemetry) Color(0xFF10B981) else Color.Gray, fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = showTelemetry,
                            onCheckedChange = onShowTelemetryChange
                        )
                    }
                }

                if (showTelemetry) {
                    // Živá HW a časová telemetrie v reálném čase
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (isGenerating) "⏱️ Čas běhu: ${formatDuration(elapsedSeconds)}" else "⏱️ Připraven k inferenci",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "🧠 RAM: ${hwProfile.availableRamMb} MB volno",
                            color = if (hwProfile.availableRamMb < 3000) Color(0xFFF59E0B) else Color(0xFF10B981),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Indikátor aktivního výpočetního hardware (NPU / CPU / HYBRID)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0D1117))
                            .border(BorderStroke(1.dp, Color(0xFF21262D)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Aktivní jednotka: ", color = Color.Gray, fontSize = 11.sp)
                            val badgeColor = when (executionBackend) {
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_NPU -> Color(0xFF00E5FF)
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_HYBRID -> Color(0xFFA855F7)
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_CPU -> Color(0xFF10B981)
                                else -> Color(0xFFF59E0B)
                            }
                            Text(
                                activeHardwareBadge,
                                color = badgeColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Text(
                            when (executionBackend) {
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_NPU -> "⚡ 45 TOPS"
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_HYBRID -> "🔀 NPU+CPU"
                                com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_CPU -> "📱 NEON SIMD"
                                else -> "⚡ Auto"
                            },
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 10.sp
                        )
                    }

                    // Workflow Phase Stepper
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val phaseIndex = when (progressState) {
                            is GenProgressState.TextConditioning -> 1
                            is GenProgressState.Denoising -> 2
                            is GenProgressState.LatentDecoding -> 3
                            is GenProgressState.VideoMuxing, is GenProgressState.Completed -> 4
                            else -> 0
                        }

                        listOf("1. Text/CLIP", "2. Difúze", "3. VAE Dekód", "4. Výstup").forEachIndexed { idx, stageName ->
                            val active = (idx + 1) <= phaseIndex
                            val current = (idx + 1) == phaseIndex
                            Text(
                                text = stageName,
                                color = if (current) Color(0xFF00E5FF) else if (active) Color(0xFF10B981) else Color(0xFF4B5563),
                                fontSize = 11.sp,
                                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }

                    when (progressState) {
                        is GenProgressState.TextConditioning -> {
                            val pct = (progressState.progress * 100).toInt().coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = { progressState.progress.coerceIn(0.05f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF21262D)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("🔤 Fáze 1/4: Sémantické kódování promptu (T5-XXL)...", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("$pct%", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Text("⏱️ Výpočet sémantických embeddingů na CPU [${formatDuration(elapsedSeconds)}]...", color = Color.Gray, fontSize = 11.sp)
                        }
                        is GenProgressState.Denoising -> {
                            val now = currentNow
                            val stepElapsedSec = maxOf(0.1f, (now - stepStartTime) / 1000f)
                            val expectedStepSec = when {
                                progressState.currentFps > 0f -> 1.0f / progressState.currentFps
                                measuredStepDurationSec > 0f -> measuredStepDurationSec
                                else -> 22.0f
                            }

                            // Sub-step kontinuální interpolace pro plynulý posun progress baru každých 250 ms
                            val subStepRatio = (stepElapsedSec / maxOf(1.0f, expectedStepSec)).coerceIn(0f, 0.95f)
                            val smoothProgress = ((progressState.step.toFloat() + subStepRatio) / maxOf(1, progressState.totalSteps)).coerceIn(0f, 0.99f)
                            val smoothPct = (smoothProgress * 100).toInt().coerceIn(0, 99)

                            val currentStepSec = if (measuredStepDurationSec > 0f) measuredStepDurationSec else stepElapsedSec
                            val currentItPerSec = 1.0f / maxOf(0.1f, currentStepSec)

                            val speedStr = String.format(java.util.Locale.US, "%.1f s/krok (%.2f it/s)", currentStepSec, currentItPerSec)
                            val stepsRemaining = maxOf(0, progressState.totalSteps - progressState.step)
                            val remainingSec = maxOf(0L, (stepsRemaining * currentStepSec - (stepElapsedSec * 0.5f)).toLong())

                            val stepTitle = if (progressState.step == 0) {
                                "🌀 Fáze 2/4: Neuronová difúze [Start • Krok 1/${progressState.totalSteps}]"
                            } else {
                                "🌀 Fáze 2/4: Neuronová difúze - Krok ${progressState.step}/${progressState.totalSteps}"
                            }

                            LinearProgressIndicator(
                                progress = { smoothProgress },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                color = Color(0xFF00E5FF),
                                trackColor = Color(0xFF21262D)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(stepTitle, color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("$smoothPct% • $speedStr", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Text(
                                "⏳ Odhad zbývajícího času: ~${formatDuration(remainingSec)} • Běh kroku: ${String.format(java.util.Locale.US, "%.1f", stepElapsedSec)}s • ARMv9 NEON",
                                color = Color(0xFF10B981),
                                fontSize = 11.sp
                            )
                        }
                        is GenProgressState.LatentDecoding -> {
                            val decProg = if (progressState.totalFrames > 0) progressState.frame.toFloat() / progressState.totalFrames else 0f
                            val pct = (decProg * 100).toInt().coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = { decProg.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF10B981),
                                trackColor = Color(0xFF21262D)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("🎨 Fáze 3/4: 3D-VAE Dekódování latentů do RGB snímků...", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("$pct% • Snímek ${progressState.frame}/${progressState.totalFrames}", color = Color.LightGray, fontSize = 11.sp)
                            }
                            Text("⏱️ Rekonstrukce obrazu [${formatDuration(elapsedSeconds)}]...", color = Color.Gray, fontSize = 11.sp)
                        }
                        is GenProgressState.VideoMuxing -> {
                            val pct = (progressState.progress * 100).toInt().coerceIn(0, 100)
                            LinearProgressIndicator(
                                progress = { progressState.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = Color(0xFF3B82F6),
                                trackColor = Color(0xFF21262D)
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("📦 Fáze 4/4: Hardwarový zápis MP4 / PNG výstupu & 60 FPS...", color = Color(0xFF3B82F6), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("$pct%", color = Color.LightGray, fontSize = 11.sp)
                            }
                        }
                        is GenProgressState.Completed -> {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF10B981))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("✅ Dokončeno (${progressState.durationMs / 1000} s): ${progressState.outputFile.name}", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    Button(
                                        onClick = { onSaveToGallery(progressState.outputFile) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("📥 Do Galerie", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { onSaveAsDocument(progressState.outputFile) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("📁 Uložit jako", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onShareFile(progressState.outputFile) },
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("📤 Sdílet", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                        is GenProgressState.Cancelled -> {
                            Text("🛑 Generování bylo zrušeno. Paměť RAM byla vyčištěna.", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        is GenProgressState.Error -> {
                            Text("❌ Chyba: ${progressState.message}", color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                        else -> {
                            Text("Připraven k neuronové syntéze na procesoru Snapdragon 8 Gen 3", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                } else if (isGenerating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFF00E5FF))
                    Text("⚡ Probíhá generování na pozadí [${formatDuration(elapsedSeconds)}] (telemetrie vypnuta pro úsporu CPU)...", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }

        // Tlačítko Spuštění / Zrušení
        if (isGenerating) {
            Button(
                onClick = onCancelGeneration,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White)
            ) {
                Icon(Icons.Default.Close, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Zastavit Generování")
            }
        } else {
            Button(
                onClick = onStartGeneration,
                modifier = Modifier.fillMaxWidth(),
                enabled = prompt.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isPhotoMode) "⚡ Vygenerovat Fotografii (Neuronová síť)"
                    else "🎬 Spustit Generování Videa (Neuronová síť)",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Karta s okamžitým náhledem posledního vygenerovaného výstupu přímo v Generátoru
        if (currentVideoFile != null && currentVideoFile.exists() && !isGenerating) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🖼️ Poslední výstup: ${currentVideoFile.name}", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(if (currentVideoFile.extension.lowercase() == "mp4") "Video MP4" else "PNG Foto", color = Color.Gray, fontSize = 11.sp)
                    }

                    if (currentVideoFile.extension.lowercase() != "mp4") {
                        val bmp = remember(currentVideoFile.absolutePath) {
                            BitmapFactory.decodeFile(currentVideoFile.absolutePath)
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Náhled výsledku",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Fit
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onSaveToGallery(currentVideoFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Do Galerie", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { onShareFile(currentVideoFile) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Sdílet", fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// DIALOG: MODEL & TENSOR INSPECTOR (TENSOR DEBUGGER)
// --------------------------------------------------------------------------
@Composable
fun ModelTensorInspectorDialog(
    model: GenModel,
    inspection: ModelTensorInspection?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onPairTextEncoder: () -> Unit,
    onPairVae: () -> Unit,
    onResetTextEncoder: () -> Unit,
    onResetVae: () -> Unit
) {
    val spec = GenArchitectureRegistry.detectSpec(model.displayName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF00E5FF))
                Spacer(modifier = Modifier.width(8.dp))
                Text("🔍 Tensor Debugger & Model Inspector", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(model.displayName, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Cesta: ${model.filePath.takeLast(50)}", color = Color.Gray, fontSize = 11.sp)

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
                    }
                } else if (inspection != null) {
                    // Diagnostická zpráva o architektuře (100% offline status)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF13231B)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("⚡ ARCHITEKTURA: ${spec.displayName} (100% OFFLINE)", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Aktivní je vestavěný univerzální podsystém s plnou odolností. Generování funguje bezpečně i bez externích souborů.", color = Color(0xFFE5E7EB), fontSize = 11.sp)
                        }
                    }

                    // Tenzorová matrice
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("📊 Architektura & Tenzory:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("• Formát: ${inspection.format} | Tenzorů: ${inspection.tensorCount}", color = Color.LightGray, fontSize = 11.sp)
                            Text("• DiT Backbone: ${if (inspection.hasDiffusionBackbone) "✅ Přítomen" else "❌ Chybí"}", color = Color.LightGray, fontSize = 11.sp)
                            Text("• Textový Enkodér: ${if (model.textEncoderPath != null) "✅ Spárován soubor" else "🟢 Aktivní (${spec.textEncoderFamily} ${spec.expectedTextEmbeddingShape})"}", color = Color(0xFF10B981), fontSize = 11.sp)
                            Text("• 3D-VAE Dekodér: ${if (model.vaePath != null) "✅ Spárován soubor" else "🟢 Aktivní (Univerzální 3D-VAE ${spec.latentChannels}ch)"}", color = Color(0xFF10B981), fontSize = 11.sp)
                            Text("• Očekávaný tvar textu: ${spec.expectedTextEmbeddingShape}", color = Color(0xFF00E5FF), fontSize = 11.sp)
                            Text("• Očekávaný tvar latentu: ${spec.expectedLatentShape}", color = Color(0xFF00E5FF), fontSize = 11.sp)
                        }
                    }

                    // Tlačítka pro spárování a stav subsítí
                    Text("Přiřazené komponenty (Offline Sequential Offload):", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Text Enkodér (${spec.textEncoderFamily}):", color = Color.LightGray, fontSize = 11.sp)
                            val textStatus = if (model.textEncoderPath != null) "✅ Spárován: ${model.textEncoderPath.takeLast(25)}" else "🟢 Aktivní: Vestavěný univerzální"
                            Text(textStatus, color = Color(0xFF10B981), fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (model.textEncoderPath != null) {
                                OutlinedButton(onClick = onResetTextEncoder, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Reset", fontSize = 10.sp, color = Color.LightGray)
                                }
                            }
                            OutlinedButton(onClick = onPairTextEncoder, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(if (model.textEncoderPath != null) "Změnit" else "Spárovat", fontSize = 11.sp)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("3D-VAE Dekodér (${spec.vaeFamily}):", color = Color.LightGray, fontSize = 11.sp)
                            val vaeStatus = if (model.vaePath != null) "✅ Spárován: ${model.vaePath.takeLast(25)}" else "🟢 Aktivní: Vestavěný univerzální"
                            Text(vaeStatus, color = Color(0xFF10B981), fontSize = 10.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (model.vaePath != null) {
                                OutlinedButton(onClick = onResetVae, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("Reset", fontSize = 10.sp, color = Color.LightGray)
                                }
                            }
                            OutlinedButton(onClick = onPairVae, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                                Text(if (model.vaePath != null) "Změnit" else "Spárovat", fontSize = 11.sp)
                            }
                        }
                    }

                    // Ukázkové tenzory
                    if (inspection.sampleTensors.isNotEmpty()) {
                        Text("Vzorek tenzorů v modelu:", color = Color.LightGray, fontSize = 11.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)), shape = RoundedCornerShape(6.dp)) {
                            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                inspection.sampleTensors.take(6).forEach { t ->
                                    Text("${t.name} [${t.shape.joinToString("x")}] (${t.typeName})", color = Color(0xFF9CA3AF), fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)) {
                Text("Zavřít")
            }
        },
        containerColor = Color(0xFF161B22)
    )
}

// --------------------------------------------------------------------------
// TAB 2: ADVANCED TUNING
// --------------------------------------------------------------------------
@Composable
fun AdvancedTuningTab(
    powerProfile: Int,
    onPowerProfileChange: (Int) -> Unit,
    executionBackend: Int,
    onExecutionBackendChange: (Int) -> Unit,
    guidanceScale: Float,
    onGuidanceScaleChange: (Float) -> Unit,
    seed: String,
    onSeedChange: (String) -> Unit,
    steps: Float,
    onStepsChange: (Float) -> Unit,
    cpuThreads: Int,
    onCpuThreadsChange: (Int) -> Unit,
    enableCpuAffinity: Boolean,
    onEnableCpuAffinityChange: (Boolean) -> Unit,
    affinityMode: Int,
    onAffinityModeChange: (Int) -> Unit,
    threadPriority: Int,
    onThreadPriorityChange: (Int) -> Unit,
    sampleMethod: Int,
    onSampleMethodChange: (Int) -> Unit,
    scheduler: Int,
    onSchedulerChange: (Int) -> Unit,
    dynamicCfg: Boolean,
    onDynamicCfgChange: (Boolean) -> Unit,
    enableOpenmp: Boolean,
    onEnableOpenmpChange: (Boolean) -> Unit,
    enableTeaCache: Boolean,
    onEnableTeaCacheChange: (Boolean) -> Unit,
    teaCacheThreshold: Float,
    onTeaCacheThresholdChange: (Float) -> Unit,
    teaCacheMode: Int,
    onTeaCacheModeChange: (Int) -> Unit,
    fastModeNoCfg: Boolean,
    onFastModeNoCfgChange: (Boolean) -> Unit,
    hwProfile: com.example.llama.agent.ui.studio.genstudio.profiling.HardwareProfile
) {
    val sampleMethods = listOf(
        0 to "Euler (Výchozí, rychlý a stabilní)",
        1 to "Euler A (Dynamický / Ancestral)",
        2 to "Heun (2. řád, přesné detaily)",
        5 to "DPM++ 2M (Fotorealistický vícekrokový)",
        9 to "LCM (Latent Consistency pro rychlost)"
    )

    val schedulers = listOf(
        0 to "Discrete (Lineární rozvrh)",
        1 to "Karras (Jemný šumový rozpad)",
        2 to "Exponential (Exponenciální rozpad)",
        3 to "AYS (Align Your Steps optimalizace)"
    )

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("⚙️ Pokročilá konfigurace DiT & Hardwarové akcelerace", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)

        // 0. Hardwarový profil spotřeby & výkonu (Snapdragon 8 Gen 3)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("🚀 Hardwarový profil výkonu (Power & TDP)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Řízení jader Cortex-X4 / A720, TDP limitu a priorit", color = Color.LightGray, fontSize = 11.sp)
                    }
                    val profileBadge = when (powerProfile) {
                        0 -> "🟢 Eco (2–5W)"
                        1 -> "🔵 Balanced (6–8W)"
                        else -> "🔥 Turbo (10–15W)"
                    }
                    Surface(
                        color = when (powerProfile) {
                            0 -> Color(0x3310B981)
                            1 -> Color(0x3300E5FF)
                            else -> Color(0x33EF4444)
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = profileBadge,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = when (powerProfile) {
                                0 -> Color(0xFF10B981)
                                1 -> Color(0xFF00E5FF)
                                else -> Color(0xFFFF5252)
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 1. Eco profil
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPowerProfileChange(0) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (powerProfile == 0) Color(0x2210B981) else Color(0xFF0D1117)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (powerProfile == 0) Color(0xFF10B981) else Color(0xFF30363D)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🟢 Eco", fontWeight = FontWeight.Bold, color = if (powerProfile == 0) Color(0xFF10B981) else Color.White, fontSize = 12.sp)
                            Text("2–5 W • Pozadí", color = Color.Gray, fontSize = 10.sp)
                            Text("2 vlákna Mid • Nice +10 • Chladný telefon (28–32°C)", color = Color.LightGray, fontSize = 9.sp, lineHeight = 12.sp)
                        }
                    }

                    // 2. Balanced profil
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPowerProfileChange(1) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (powerProfile == 1) Color(0x2200E5FF) else Color(0xFF0D1117)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (powerProfile == 1) Color(0xFF00E5FF) else Color(0xFF30363D)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🔵 Balanced", fontWeight = FontWeight.Bold, color = if (powerProfile == 1) Color(0xFF00E5FF) else Color.White, fontSize = 12.sp)
                            Text("6–8 W • Studio", color = Color.Gray, fontSize = 10.sp)
                            Text("4 vlákna A720 @ 3.15 GHz • Nice 0 • Stabilní chod", color = Color.LightGray, fontSize = 9.sp, lineHeight = 12.sp)
                        }
                    }

                    // 3. Turbo profil
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPowerProfileChange(2) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (powerProfile == 2) Color(0x22EF4444) else Color(0xFF0D1117)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (powerProfile == 2) Color(0xFFFF5252) else Color(0xFF30363D)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("🔥 Turbo", fontWeight = FontWeight.Bold, color = if (powerProfile == 2) Color(0xFFFF5252) else Color.White, fontSize = 12.sp)
                            Text("10–15 W • Max", color = Color.Gray, fontSize = 10.sp)
                            Text("6 velkých jader (Prime X4 @ 3.4 GHz) • Nice -16 • Max it/s", color = Color.LightGray, fontSize = 9.sp, lineHeight = 12.sp)
                        }
                    }
                }
            }
        }

        // 0b. Výpočetní Akcelerátor (Qualcomm Hexagon NPU / ARM NEON CPU)
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("⚡ Výpočetní akcelerátor (Inference Engine)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("Výběr jednotky: Hexagon HTP NPU, Hybridní režim nebo ARM CPU", color = Color.LightGray, fontSize = 11.sp)
                    }
                }

                val backendOptions = listOf(
                    Triple(
                        com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_AUTO,
                        "⚡ Auto (Inteligentní detekce)",
                        "Automaticky zvolí Hexagon NPU na Snapdragonu 8 Gen 3, pokud je model podporován. Jinak CPU."
                    ),
                    Triple(
                        com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_NPU,
                        "🧠 Qualcomm Hexagon NPU (HTP v75)",
                        "Vynucená akcelerace přes Hexagon Tensor Processor (45 TOPS, spotřeba ~3–5 W, nulový throttling)."
                    ),
                    Triple(
                        com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_HYBRID,
                        "🔀 Hybridní režim (NPU DiT + CPU VAE)",
                        "Nejtěžší difúzní smyčka běží na NPU, textové kódování promptu a 3D-VAE dekódování obslouží CPU NEON."
                    ),
                    Triple(
                        com.example.llama.agent.ui.studio.genstudio.preferences.GenStudioPreferences.BACKEND_CPU,
                        "📱 ARM CPU (NEON SIMD & OpenMP)",
                        "Inference na hlavních CPU jádrech Cortex-X4 / A720 s možností ladění afinity a počtu vláken."
                    )
                )

                backendOptions.forEach { (bId, bTitle, bDesc) ->
                    val isSelected = executionBackend == bId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onExecutionBackendChange(bId) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) Color(0x2200E5FF) else Color(0xFF0D1117)
                        ),
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF00E5FF) else Color(0xFF30363D)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = { onExecutionBackendChange(bId) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(bTitle, fontWeight = FontWeight.Bold, color = if (isSelected) Color(0xFF00E5FF) else Color.White, fontSize = 12.sp)
                                Text(bDesc, color = Color.Gray, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // 1. Rychlý režim Wan 2.1 (Fast Mode No-CFG)
        Card(
            colors = CardDefaults.cardColors(containerColor = if (fastModeNoCfg) Color(0x2200E5FF) else Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⚡ Rychlý režim Wan 2.1 (Bez CFG)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Vynechá negativní prompt (CFG 1.0f). T5 kodér i DiT difúze běží pouze 1× místo 2× na krok! Zkracuje čas generování z 6–12 min na ~2 min (~50% zrychlení).",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = fastModeNoCfg,
                        onCheckedChange = onFastModeNoCfgChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF), checkedTrackColor = Color(0xFF005F73))
                    )
                }
            }
        }

        // 2. TeaCache Akcelerace (DiT Cache-Reuse)
        Card(
            colors = CardDefaults.cardColors(containerColor = if (enableTeaCache) Color(0x2210B981) else Color(0xFF161B22)),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🍵 TeaCache Akcelerace (DiT Cache-Reuse)", color = Color(0xFF10B981), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(
                            "Dynamické znovupoužití výstupů DiT bloků při malých změnách latentů. Zrychluje generování až 2-3× při zachování vizuální kvality.",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = enableTeaCache,
                        onCheckedChange = onEnableTeaCacheChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF10B981), checkedTrackColor = Color(0xFF064E3B))
                    )
                }

                if (enableTeaCache) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF21262D)))

                    Text("Algoritmus TeaCache:", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    val teaModes = listOf(
                        1 to "EasyCache (Optimalizováno pro Wan 2.1 & Flux)",
                        2 to "Cache-DiT (Klasický adaptivní DiT cache)"
                    )
                    teaModes.forEach { (mId, mName) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { onTeaCacheModeChange(mId) }.padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = teaCacheMode == mId,
                                onClick = { onTeaCacheModeChange(mId) },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(mName, color = if (teaCacheMode == mId) Color.White else Color.Gray, fontSize = 11.sp)
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Práh znovupoužití (Threshold): ${String.format(java.util.Locale.US, "%.2f", teaCacheThreshold)}", color = Color.White, fontSize = 12.sp)
                        Text(
                            when {
                                teaCacheThreshold <= 0.12f -> "⚡ ~1.5× zrychlení (Vysoká věrnost)"
                                teaCacheThreshold <= 0.20f -> "⚡⚡ ~2.0× zrychlení (Vyvážené)"
                                else -> "⚡⚡⚡ ~3.0× zrychlení (Maximální rychlost)"
                            },
                            color = Color(0xFF10B981),
                            fontSize = 11.sp
                        )
                    }

                    Slider(
                        value = teaCacheThreshold,
                        onValueChange = onTeaCacheThresholdChange,
                        valueRange = 0.05f..0.35f,
                        steps = 30
                    )

                    // Rychlé předvolby prahu TeaCache
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onTeaCacheThresholdChange(0.10f) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (kotlin.math.abs(teaCacheThreshold - 0.10f) < 0.02f) Color(0xFF10B981) else Color(0xFF21262D)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("1.5× (0.10)", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { onTeaCacheThresholdChange(0.16f) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (kotlin.math.abs(teaCacheThreshold - 0.16f) < 0.02f) Color(0xFF10B981) else Color(0xFF21262D)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("2.0× (0.16)", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = { onTeaCacheThresholdChange(0.25f) },
                            colors = ButtonDefaults.buttonColors(containerColor = if (kotlin.math.abs(teaCacheThreshold - 0.25f) < 0.02f) Color(0xFF10B981) else Color(0xFF21262D)),
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("3.0× (0.25)", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }
        }

        // 3. OpenMP Multi-threading
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("⚙️ OpenMP Multi-threading", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(
                        if (enableOpenmp) "Zapnuto: OpenMP paralelní smyčky v libstable-diffusion.so (omp_set_num_threads)."
                        else "Vypnuto: Jednovláknové OpenMP smyčky (pro diagnostiku a sekvenční testy).",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = enableOpenmp,
                    onCheckedChange = onEnableOpenmpChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF), checkedTrackColor = Color(0xFF005F73))
                )
            }
        }

        // 4. CPU Cluster, Affinity & Topology
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⚡ CPU Affinity Mask (Vázání jader)", color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            if (enableCpuAffinity) "Zapnuto: Vázání výpočetních vláken přímo na fyzická ARM jádra"
                            else "Vypnuto: Android OS EAS Scheduler dynamicky přesouvá vlákna",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = enableCpuAffinity,
                        onCheckedChange = onEnableCpuAffinityChange,
                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF), checkedTrackColor = Color(0xFF005F73))
                    )
                }

                if (enableCpuAffinity) {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF21262D)))
                    Text("Režim hardwarové topologie CPU (Hardwarové vázání):", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)

                    val affinityModes = listOf(
                        0 to "Auto (EAS Scheduler - Všechna jádra SoC)",
                        1 to "Všechna velká jádra (6x Big Cores: Prime + A720 - Max výkon)",
                        2 to "Solo Cortex-X4 (Prime Core @ 3.4 GHz - Exkluzivní 1 jádro)",
                        3 to "Střední jádra (5x Cortex-A720 Cluster @ 3.15 GHz - Stabilní teplota)"
                    )

                    affinityModes.forEach { (aId, aName) ->
                        val isSelected = affinityMode == aId
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAffinityModeChange(aId)
                                    val threadsForMode = when (aId) {
                                        2 -> 1
                                        1 -> 6
                                        3 -> 5
                                        else -> 8
                                    }
                                    onCpuThreadsChange(threadsForMode)
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    onAffinityModeChange(aId)
                                    val threadsForMode = when (aId) {
                                        2 -> 1
                                        1 -> 6
                                        3 -> 5
                                        else -> 8
                                    }
                                    onCpuThreadsChange(threadsForMode)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(aName, color = if (isSelected) Color.White else Color.Gray, fontSize = 11.sp)
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0D1117)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    when (affinityMode) {
                                        2 -> "Aktivní hardware: 1 výpočetní vlákno uzamčeno na Cortex-X4 (Core 7 @ 3.40 GHz)"
                                        1 -> "Aktivní hardware: 6 výpočetních vláken (1x Cortex-X4 + 5x Cortex-A720, Max výkon)"
                                        3 -> "Aktivní hardware: 5 výpočetních vláken (Cortex-A720 cluster @ 3.15 GHz, stabilní teplota)"
                                        else -> "Aktivní hardware: 8 vláken přes celou SoC topologii"
                                    },
                                    color = Color(0xFF00E5FF),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                "Hardwarová afinita přes sched_setaffinity zabraňuje Android OS migraci vláken na pomalá úsporná jádra A520.",
                                color = Color.Gray,
                                fontSize = 10.sp
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF21262D)))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Počet vláken CPU: $cpuThreads", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("OS EAS Auto (Android Scheduler)", color = Color(0xFF00E5FF), fontSize = 11.sp)
                    }
                    Text("⚡ $cpuThreads vláken – Android plánovač dynamicky rozděluje výpočetní zátěž mezi dostupná jádra.", color = Color(0xFF10B981), fontSize = 11.sp)

                    Slider(
                        value = cpuThreads.toFloat(),
                        onValueChange = { onCpuThreadsChange(it.toInt()) },
                        valueRange = 1f..8f,
                        steps = 6
                    )
                }

                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF21262D)))

                Text("Priorita výpočetního vlákna (Process Priority):", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                val priorities = listOf(
                    0 to "⚡ Urgent (Vysoká priorita / Nice -10, minimální latence)",
                    1 to "⚙️ Default (Standardní priorita / Nice 0, vyvážené)",
                    2 to "🔋 Background (Nízká priorita / Nice +10, úsporné)"
                )
                priorities.forEach { (pId, pName) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onThreadPriorityChange(pId) }.padding(vertical = 2.dp)
                    ) {
                        RadioButton(
                            selected = threadPriority == pId,
                            onClick = { onThreadPriorityChange(pId) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(pName, color = if (threadPriority == pId) Color.White else Color.Gray, fontSize = 11.sp)
                    }
                }
            }
        }

        // 5. Vzorkovač (Sample Method)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🎯 Vzorkovací Algoritmus (Sampler):", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                sampleMethods.forEach { (id, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSampleMethodChange(id) }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = sampleMethod == id,
                            onClick = { onSampleMethodChange(id) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(name, color = if (sampleMethod == id) Color.White else Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        // 6. Rozvrhovač šumu (Scheduler)
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("📈 Scheduler (Rozvrhování šumu):", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                schedulers.forEach { (id, name) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { onSchedulerChange(id) }.padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = scheduler == id,
                            onClick = { onSchedulerChange(id) },
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF00E5FF))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(name, color = if (scheduler == id) Color.White else Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        // 7. Dynamic CFG / SLG
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(12.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Dynamic CFG (Skip-Layer Guidance)", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text("SLG škálování 1.5x pro ostřejší sémantiku bez přepálení kontrastu.", color = Color.Gray, fontSize = 11.sp)
                }
                Switch(
                    checked = dynamicCfg,
                    onCheckedChange = onDynamicCfgChange,
                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00E5FF), checkedTrackColor = Color(0xFF005F73))
                )
            }
        }

        Text("Guidance Scale (CFG): ${String.format(java.util.Locale.US, "%.1f", guidanceScale)}", color = Color.White, fontSize = 13.sp)
        Slider(value = guidanceScale, onValueChange = onGuidanceScaleChange, valueRange = 1.0f..12.0f)

        OutlinedTextField(
            value = seed,
            onValueChange = onSeedChange,
            label = { Text("Deterministický Seed (-1 pro náhodný)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.LightGray)
        )

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("🧠 Qualcomm Snapdragon 8 Gen 3 Architektura", color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text("• NPU Delegate: Hexagon v75 (QNN HTP Direct Execution)", color = Color.LightGray, fontSize = 12.sp)
                Text("• GPU Compute: Adreno 750 Vulkan 1.3 Flow-Matching", color = Color.LightGray, fontSize = 12.sp)
                Text("• LMK OOM Guard: PhaseLockedMemoryStateMachine (Exclusive VRAM)", color = Color.LightGray, fontSize = 12.sp)
                Text("• NDK Frame Interpolator: C++ libmedia_engine (60 FPS Motion Smooth)", color = Color.LightGray, fontSize = 12.sp)
            }
        }
    }
}

// --------------------------------------------------------------------------
// TAB 3: MODEL MANAGEMENT
// --------------------------------------------------------------------------
@Composable
fun ModelManagementTab(
    importedModels: List<GenModel>,
    onImportModelClick: () -> Unit,
    onInspectModel: (GenModel) -> Unit,
    onPairTextEncoderClick: (GenModel) -> Unit,
    onPairVaeClick: (GenModel) -> Unit,
    onDeleteModel: (GenModel) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Importované Modely (${importedModels.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Button(onClick = onImportModelClick, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Importovat")
            }
        }

        if (importedModels.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Žádný model zatím nebyl importován.", color = Color.Gray)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(importedModels) { model ->
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.displayName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text("Formát: ${model.format} | Kvantizace: ${model.quantization}", color = Color(0xFF00E5FF), fontSize = 11.sp)
                                }
                                Row {
                                    IconButton(onClick = { onInspectModel(model) }) {
                                        Icon(Icons.Default.Info, contentDescription = "Tensor Inspector", tint = Color(0xFF00E5FF))
                                    }
                                    IconButton(onClick = { onDeleteModel(model) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Smazat", tint = Color(0xFFEF4444))
                                    }
                                }
                            }

                            // Stav spárovaných subsítí
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { onPairTextEncoderClick(model) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(if (model.textEncoderPath != null) "✅ Text: Spárován" else "🔗 Spárovat Text", fontSize = 10.sp)
                                }
                                OutlinedButton(
                                    onClick = { onPairVaeClick(model) },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(if (model.vaePath != null) "✅ VAE: Spárován" else "🔗 Spárovat VAE", fontSize = 10.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------
// TAB 4: PLAYER & EXPORT BRIDGE
// --------------------------------------------------------------------------
@Composable
fun PlayerAndExportTab(
    currentVideoFile: File?,
    progressState: GenProgressState,
    onInterpolate60Fps: (File) -> Unit,
    onAttachAudioToVideo: (File) -> Unit,
    onOpenInPostProduction: (File) -> Unit,
    onSaveToGallery: (File) -> Unit,
    onSaveAsDocument: (File) -> Unit,
    onShareFile: (File) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedFile by remember { mutableStateOf<File?>(null) }
    var recentFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    // Automatické načtení vygenerovaných souborů ze složky aplikace
    LaunchedEffect(currentVideoFile, progressState) {
        val dir = context.getExternalFilesDir(null)
        if (dir != null && dir.exists()) {
            val list = dir.listFiles { f ->
                f.isFile && (f.name.startsWith("gen_studio_") || f.extension.lowercase() in listOf("mp4", "png"))
            }?.sortedByDescending { it.lastModified() }?.take(15) ?: emptyList()
            recentFiles = list
        }
    }

    val fileToPlay = selectedFile ?: currentVideoFile ?: (progressState as? GenProgressState.Completed)?.outputFile ?: recentFiles.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🎞️ Integrovaný Přehrávač & Postprodukce", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)

        if (fileToPlay != null && fileToPlay.exists()) {
            val isMp4 = fileToPlay.extension.lowercase() == "mp4"

            if (isMp4) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(260.dp)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(this)
                                setMediaController(controller)
                                tag = fileToPlay.absolutePath
                                setVideoPath(fileToPlay.absolutePath)
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                            }
                        },
                        update = { view ->
                            if (view.tag != fileToPlay.absolutePath) {
                                view.tag = fileToPlay.absolutePath
                                view.setVideoPath(fileToPlay.absolutePath)
                                view.start()
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onInterpolate60Fps(fileToPlay) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("⚡ 60 FPS", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { onAttachAudioToVideo(fileToPlay) },
                        modifier = Modifier.weight(1.2f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6), contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("🎵 Přidat Zvuk", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                val bitmap = remember(fileToPlay.absolutePath) {
                    BitmapFactory.decodeFile(fileToPlay.absolutePath)
                }
                if (bitmap != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().height(280.dp)
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Vygenerovaná neuronová fotografie",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            // Media Info Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("📄 Soubor: ${fileToPlay.name}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val sizeKb = fileToPlay.length() / 1024
                        val sizeStr = if (sizeKb > 1024) "${String.format("%.2f", sizeKb / 1024f)} MB" else "$sizeKb KB"
                        Text("Typ: ${if (isMp4) "H.264 Video (MP4)" else "PNG Fotografie"} • Velikost: $sizeStr", color = Color(0xFF00E5FF), fontSize = 11.sp)
                        Text(if (isMp4) "16/60 FPS" else "Bezztrátový", color = Color.Gray, fontSize = 11.sp)
                    }
                }
            }

            // Export & Ukládání akční tlačítka
            Text("Uložit & Sdílet výstup:", color = Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onSaveToGallery(fileToPlay) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Do Galerie", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { onSaveAsDocument(fileToPlay) },
                    modifier = Modifier.weight(1.1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Uložit jako", fontSize = 11.sp)
                }

                OutlinedButton(
                    onClick = { onShareFile(fileToPlay) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Sdílet", fontSize = 11.sp)
                }
            }

            Button(
                onClick = { onOpenInPostProduction(fileToPlay) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF238636), contentColor = Color.White)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("🎬 Otevřít v SRT Visual Editoru (Střih & Titulky & Audio)")
            }

        } else {
            Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                Text("Zatím nebylo vygenerováno žádné video v této relaci.", color = Color.Gray, fontSize = 13.sp)
            }
        }

        // Nedávné vygenerované soubory (Historie relací)
        if (recentFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text("📂 Nedávno vygenerované klipy & fotografie (${recentFiles.size}):", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recentFiles) { f ->
                    val isSelected = fileToPlay?.absolutePath == f.absolutePath
                    val isVid = f.extension.lowercase() == "mp4"
                    Surface(
                        color = if (isSelected) Color(0xFF1F6FEB) else Color(0xFF161B22),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .width(180.dp)
                            .clickable { selectedFile = f }
                            .border(1.dp, if (isSelected) Color(0xFF00E5FF) else Color(0xFF30363D), RoundedCornerShape(8.dp))
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (isVid) "🎬" else "📷", fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(f.name.take(18) + if (f.name.length > 18) "..." else "", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            val sizeKb = f.length() / 1024
                            Text(if (sizeKb > 1024) "${String.format("%.1f", sizeKb / 1024f)} MB" else "$sizeKb KB", color = Color.LightGray, fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return String.format("%02d:%02d", m, s)
}

