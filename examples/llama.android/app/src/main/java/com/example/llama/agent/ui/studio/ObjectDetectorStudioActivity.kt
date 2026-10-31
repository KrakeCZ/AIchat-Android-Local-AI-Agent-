package com.example.llama.agent.ui.studio

import com.example.llama.SettingsManager
import com.example.llama.agent.perception.OdModelManager

import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.vision.lupa.LupaPluginManager
import com.example.llama.agent.vision.lupa.impl.*
import androidx.compose.foundation.gestures.detectTapGestures
import com.example.llama.agent.perception.VisionUpdateEvent
import com.example.llama.agent.perception.VisionUpdateSource
import com.example.llama.agent.vision.BoundingBoxView.Detection
import kotlinx.coroutines.flow.filterIsInstance
import java.util.concurrent.ExecutorService
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

enum class PipMode { AUTO, MANUAL }

class ManualPipTarget(val id: String)

/**
 * Compose-Based Edge AI Lab 2026
 */
class ObjectDetectorStudioActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ObjectDetectorStudio"
    }

    private lateinit var cameraExecutor: ExecutorService
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    
    // ZASADNI: Zero-allocation pro prenos obrazu do CPU pipeline (Night Vision)
    private var sharedCpuBitmap: android.graphics.Bitmap? = null
    private var sharedMatrix: android.graphics.Matrix? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // --- ZERO ALLOC PIPELINE ---
    private var reusableBitmap: android.graphics.Bitmap? = null
    var rotatedBitmap: android.graphics.Bitmap? = null
        private set
    private var rotationMatrix = android.graphics.Matrix()
    private var rotationCanvas: android.graphics.Canvas? = null

    // --- ACADEMIC YUV PIPELINE (FIX #1) ---
    // Krok 1 (camera vlakno): extractYuvData() + imageProxy.close() -> okamzite vraci kamera
    // Krok 2 (academicInferenceScope): runInferenceOnExtractedData() -> muze trvat desitky ms
    // Channel(CONFLATED) = vzdy jen jeden cekajici frame, stare se zahazuji (stejne jako STRATEGY_KEEP_ONLY_LATEST)
    private val academicFrameChannel = kotlinx.coroutines.channels.Channel<com.example.llama.agent.vision.nightmode.AcademicYuvEngine.ExtractedHwFrame>(kotlinx.coroutines.channels.Channel.CONFLATED) { frame ->
        frame.imageProxy.close()
    }
    private val academicInferenceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Sledování aktivního objektivu
    private val activeLensInfo = mutableStateOf<com.example.llama.agent.vision.camera.strategy.CameraLensInfo?>(null)

    private lateinit var settingsManager: com.example.llama.SettingsManager
    private lateinit var cameraCoordinator: com.example.llama.agent.vision.camera.core.CameraCoordinator

    // Pro vynucení překreslení Compose, když dorazí nový frame
    val frameCount = mutableStateOf(0L)

    // State pro sledovaný (uzamčený) objekt
    private val lockedTargetId = mutableStateOf<String?>(null)
    private val pipMode = mutableStateOf(PipMode.AUTO)
    private val manualPipTargets = mutableStateListOf<ManualPipTarget>()
    private val currentZoomRatio = mutableStateOf(1f)

    // Layer nastavení
    private val showBoxes = mutableStateOf(true)
    private val showLabels = mutableStateOf(true)
    private val showStats = mutableStateOf(false)

    // --- HUD Layers & Themes ---
    private val showTacticalHud = mutableStateOf(false)
    private val showTacticalTrace = mutableStateOf(false) // Nový toggle pro vypnutí ocasní stopy
    private val showTacticalVector = mutableStateOf(false) // Toggle pro vektor letu (Lead Indicator)
    private val enableDynamicPipZoom = mutableStateOf(false)
    private val isBottomControlsVisible = mutableStateOf(true) // Toggle pro spodní menu
    private val currentHudTheme = mutableStateOf(com.example.llama.agent.ui.studio.hud.HudTheme.TACTICAL_MILITARY)
    // Zabrání cyklení dialogu pro MediaProjection
    private var isScreenCaptureStarting = false
    private val targetHistoryBuffer = com.example.llama.agent.ui.studio.hud.TacticalHistoryBuffer(120)

    // --- PRO Camera Controls Logika ---
    private val isProModeEnabled = mutableStateOf(false)
    private val showProControls = mutableStateOf(false)
    private val showFocusControls = mutableStateOf(false)

    // --- TIME LAPSE Controls ---
    private val showTimeLapseControls = mutableStateOf(false)
    private val isTimeLapseRunning = mutableStateOf(false)
    private val timeLapseFps = mutableStateOf(8)
    private val isTimeLapseHighQuality = mutableStateOf(true)

    private val proIsoValue = mutableStateOf(400)
    private val proShutterSpeedIndex = mutableStateOf(24) // 24 == 1/30s
    private val proWbMode = mutableStateOf(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO)

    private val studioFocusMode = mutableStateOf(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF)
    private val manualFocusDistance = mutableStateOf(0.00f)

    // --- LUPA SYSTEM ---
    private lateinit var lupaPluginManager: LupaPluginManager
    private val isLupaActive = mutableStateOf(false)
    private val activeLupaPluginId = mutableStateOf("lupa_lanczos_3")
    private val lupaScaleFactor = mutableStateOf(2.0f)
    private val lupaCropRect = mutableStateOf<android.graphics.RectF?>(null)

    // --- S-Mode (Auto ISO) Logika ---
    private val isShutterPriorityMode = mutableStateOf(false)
    private val currentLuminance = mutableStateOf(128)
    private val sModeDeadband = mutableStateOf(10)
    private val sModePComponent = mutableStateOf(5)
    private var lastIsoUpdateMs = 0L
    private val sModeUpdateIntervalMs = mutableStateOf(300L) // Uživatelsky konfigurovatelné

    private val luminanceAnalyzer = LuminanceAnalyzer()

    private val SHUTTER_SPEEDS_NS = arrayOf(
        30000000000L, 20000000000L, 15000000000L, 10000000000L, 8000000000L, 5000000000L, 4000000000L, 3000000000L, 2000000000L,
        1000000000L, 769230769L, 625000000L, 500000000L, 400000000L, 333333333L, 250000000L, 200000000L, 166666666L, 125000000L, 100000000L, 76923076L, 66666666L, 50000000L, 40000000L, 33333333L, 25000000L, 20000000L, 16666666L, 12500000L, 10000000L, 8000000L, 6250000L, 5000000L, 4000000L, 3125000L, 2500000L, 2000000L, 1562500L, 1250000L, 1000000L, 
        500000L, 250000L, 125000L, 83333L, 41666L
    )
    private val SHUTTER_SPEEDS_LABELS = arrayOf(
        "30s", "20s", "15s", "10s", "8s", "5s", "4s", "3s", "2s",
        "1s", "1/1.3s", "1/1.6s", "1/2s", "1/2.5s", "1/3s", "1/4s", "1/5s", "1/6s", "1/8s", "1/10s", "1/13s", "1/15s", "1/20s", "1/25s", "1/30s", "1/40s", "1/50s", "1/60s", "1/80s", "1/100s", "1/125s", "1/160s", "1/200s", "1/250s", "1/320s", "1/400s", "1/500s", "1/640s", "1/800s", "1/1000s", 
        "1/2000s", "1/4000s", "1/8000s", "1/12000s", "1/24000s"
    )

    private fun pushProSettingsToHardware() {
        cameraCoordinator.strategy.updateProSettings(
            isProMode = isProModeEnabled.value,
            iso = proIsoValue.value,
            exposureTimeNs = SHUTTER_SPEEDS_NS[proShutterSpeedIndex.value],
            whiteBalanceMode = proWbMode.value
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        settingsManager = com.example.llama.SettingsManager(this)
        cameraCoordinator = com.example.llama.agent.vision.camera.core.CameraCoordinator(this)

        // FIX #1: Spust inference konzumenta na pozadi (bezi po cely zivot Activity)
        startAcademicInferencePipeline()

        lupaPluginManager = LupaPluginManager()
        lupaPluginManager.registerPlugin(LanczosZoomPlugin())
        lupaPluginManager.registerPlugin(IbpZoomPlugin())
        lupaPluginManager.registerPlugin(DrizzlePlugin())
        lupaPluginManager.registerPlugin(WaveletSrPlugin())
        lupaPluginManager.registerPlugin(DctSrPlugin())
        lupaPluginManager.registerPlugin(LuckyImagingPlugin())
        lupaPluginManager.registerPlugin(MitchellNetravaluPlugin())
        lupaPluginManager.registerPlugin(NediPlugin())
        lupaPluginManager.registerPlugin(PhaseCorrelationPlugin())
        lupaPluginManager.registerPlugin(OpticalFlowSrPlugin())
        lupaPluginManager.registerPlugin(BlindDeconvShockPlugin())
        lupaPluginManager.registerPlugin(BlindDeconvIterativePlugin())
        lupaPluginManager.registerPlugin(RichardsonLucyPlugin())
        lupaPluginManager.registerPlugin(WienerDeconvolutionPlugin())
        lupaPluginManager.registerPlugin(TvRegularizationPlugin())
        lupaPluginManager.registerPlugin(com.example.llama.agent.vision.lupa.impl.TemporalDenoisingPlugin())

        cameraSelector = when(settingsManager.perceptionCameraSource) {
            1 -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> CameraSelector.DEFAULT_BACK_CAMERA
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                StudioScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        com.example.llama.agent.vision.VisionSourceManager.isStudioActive = true
        com.example.llama.agent.vision.VisionSourceManager.onStudioStateChanged?.invoke(true)
    }

    override fun onPause() {
        super.onPause()
        com.example.llama.agent.vision.VisionSourceManager.isStudioActive = false
        com.example.llama.agent.vision.VisionSourceManager.onStudioStateChanged?.invoke(false)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    fun StudioScreen() {
        val context = LocalContext.current
        val settingsManager = remember { SettingsManager(context) }
        val odManager = remember { OdModelManager(context, settingsManager) }

        var showAiHub by remember { mutableStateOf(false) }
        var activeModelId by remember { mutableStateOf(settingsManager.activeOdModelId) }
        var isPoseEstimationEnabled by remember { mutableStateOf(settingsManager.isPoseEstimationEnabled) }
        val poseState = PerceptionEventBus.events
            .filterIsInstance<com.example.llama.agent.perception.PoseUpdateEvent>()
            .collectAsStateWithLifecycle(initialValue = null)
        var showLupaMenu by remember { mutableStateOf(false) }
        val lupaResultBitmap = remember { mutableStateOf<android.graphics.Bitmap?>(null) }
        var isLupaCacheEnabled by remember { mutableStateOf(lupaPluginManager.isCacheEnabled) }

        var lupaCacheMinFrames by remember { mutableStateOf(lupaPluginManager.cacheMinFrames ?: 5) }
        var lupaCacheCapacity by remember { mutableStateOf(lupaPluginManager.cacheMaxCapacity) }
        var lupaCacheTimeout by remember { mutableStateOf(lupaPluginManager.cacheTimeoutMs) }
        var lupaFixedResolution by remember { mutableStateOf(lupaPluginManager.fixedResolution ?: 400) }
        var isLupaFixedResEnabled by remember { mutableStateOf(lupaPluginManager.fixedResolution != null) }

        val nightModePreview = remember { mutableStateOf<android.graphics.Bitmap?>(null) }

        val oldBitmaps = remember { ArrayDeque<android.graphics.Bitmap>(3) }
        LaunchedEffect(Unit) {
            lupaPluginManager.onResultReady = { bmp ->
                lupaResultBitmap.value = bmp
                if (bmp != null) {
                    oldBitmaps.addLast(bmp)
                    while (oldBitmaps.size > 3) {
                        val oldest = oldBitmaps.removeFirst()
                        if (!oldest.isRecycled) {
                            oldest.recycle()
                        }
                    }
                }
            }
        }

        var showKalmanConfigModal by remember { mutableStateOf(false) }

        if (showKalmanConfigModal) {
            KalmanConfigModal(settingsManager = settingsManager) {
                showKalmanConfigModal = false
            }
        }

        LaunchedEffect(activeLupaPluginId.value) {
            lupaResultBitmap.value = null // Vždy vyčistíme vizualizaci při změně (lazy reset)
            lupaPluginManager.activatePlugin(activeLupaPluginId.value)
        }

        // ZRUŠENO: Už nehardkódujeme tracker_telescope_dkf.
        // Uživatel si může sám vybrat jakýkoliv tracking engine z 20 dostupných!

        val detectionsState = PerceptionEventBus.events
            .filterIsInstance<VisionUpdateEvent>()
            .collectAsStateWithLifecycle(initialValue = null)

        LaunchedEffect(Unit) {
            PerceptionEventBus.events
                .filterIsInstance<com.example.llama.agent.perception.NightModePreviewEvent>()
                .collect { event ->
                    val oldBitmap = nightModePreview.value
                    nightModePreview.value = event.bitmap
                    // Bezpečná recyklace předchozí bitmapy, aby neucpala paměť!
                    if (oldBitmap != null && !oldBitmap.isRecycled && oldBitmap !== event.bitmap) {
                        oldBitmap.recycle()
                    }
                }
        }

        val source = detectionsState.value?.updateSource
        val currentDetections = if (source == VisionUpdateSource.LIVE_CAM || source == VisionUpdateSource.SCREEN_CAPTURE) {
            detectionsState.value?.detections ?: emptyList()
        } else {
            emptyList()
        }

        // Průběžná aktualizace regionu pro zaostření podle toho, jak se objekt hýbe v čase
        LaunchedEffect(currentDetections, lockedTargetId.value) {
            val locked = lockedTargetId.value
            if (locked != null) {
                val target = currentDetections.find { it.label == locked }
                if (target != null) {
                    cameraCoordinator.lockOnTargetController.updateTrackingRegion(
                        target.rect.left, target.rect.top, target.rect.right, target.rect.bottom,
                        cameraCoordinator.zoomController.currentZoomRatio
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // 1. Kamerový podklad (Pinch-to-zoom integrovan)
            CameraPreviewBox(Modifier.fillMaxSize())

            // 1.5 Night Mode Overlay (pokud je zapnut a generuje filtry nebo YUV)
            val nmBitmap = if (settingsManager.academicNightVisionMode) rotatedBitmap else nightModePreview.value
            if ((settingsManager.isNightModeEnabled || settingsManager.academicNightVisionMode) && nmBitmap != null && !nmBitmap.isRecycled) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawIntoCanvas { canvas ->
                        if (!nmBitmap.isRecycled) {
                            val srcRect = android.graphics.Rect(0, 0, nmBitmap.width, nmBitmap.height)
                            val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                            canvas.nativeCanvas.drawBitmap(nmBitmap, srcRect, dstRect, null)
                        }
                    }
                }
            }

            // 2. HUD a Detekce
            if (showBoxes.value || showLabels.value) {
                // Pokud je zapnuta Lupa v LABS modu, PerceptionEngine vrací sourceWidth 400 (velikost výřezu).
                // Kreslící Canvas ale leží přes celou obrazovku a poměr stran musí sedět s fyzickou kamerou (i zazoomovanou).
                // Proto mu vnutíme fyzické rozlišení `rotatedBitmap`, jinak se obraz zkreslí (z 16:9 na 1:1 apod.)
                val isLupaLabs = isLupaActive.value && settingsManager.lupaVisionDebugMode
                val baseW = if (isLupaLabs) rotatedBitmap?.width ?: 1 else detectionsState.value?.sourceWidth ?: 1
                val baseH = if (isLupaLabs) rotatedBitmap?.height ?: 1 else detectionsState.value?.sourceHeight ?: 1

                DetectionOverlay(
                    detections = currentDetections,
                    modifier = Modifier.fillMaxSize(),
                    sourceWidth = baseW,
                    sourceHeight = baseH,
                    lupaCropRect = if (isLupaLabs) lupaCropRect.value else null
                )
            }

            // 2.2 Pose Estimation Overlay (BlazePose 33) — Nezávislá vrstva, běží sólo i souběžně s OD
            if (isPoseEstimationEnabled && poseState.value != null) {
                PoseEstimationOverlay(
                    skeletons = poseState.value!!.skeletons,
                    sourceWidth = poseState.value!!.sourceWidth,
                    sourceHeight = poseState.value!!.sourceHeight,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 🔴 Taktická zaměřovací vrstva (pouze pro ne-SkyWatcher)
            if (currentHudTheme.value != com.example.llama.agent.ui.studio.hud.HudTheme.SKY_WATCHER) {
                com.example.llama.agent.ui.studio.hud.TacticalLockOverlay(
                    detections = currentDetections,
                    lockedId = lockedTargetId.value,
                    buffer = targetHistoryBuffer,
                    theme = currentHudTheme.value,
                    showTrace = showTacticalTrace.value,
                    showVector = showTacticalVector.value,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 2.5 Lupa System - Běží VŽDY A VŠUDE jako nezávislé plovoucí okno (vlastní PiP)
            if (isLupaActive.value) {
                LupaOverlay(
                    detections = currentDetections,
                    lockedId = lockedTargetId.value,
                    lupaResult = lupaResultBitmap.value,
                    lupaScaleFactor = lupaScaleFactor.value,
                    lupaCropRectState = lupaCropRect,
                    onLupaResult = { res -> lupaResultBitmap.value = res },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (currentHudTheme.value == com.example.llama.agent.ui.studio.hud.HudTheme.SKY_WATCHER) {
                com.example.llama.agent.ui.studio.skywatcher.SkyWatcherScreenUi(
                    detections = currentDetections,
                    lockedId = lockedTargetId.value,
                    rotatedBitmap = rotatedBitmap,
                    isLupaActive = isLupaActive.value,
                    isLabMode = settingsManager.lupaVisionDebugMode,
                    lupaResultBitmap = lupaResultBitmap.value,
                    activeTrackerName = settingsManager.activeVisionPluginId.uppercase(),
                    showTrace = showTacticalTrace.value,
                    showVector = showTacticalVector.value,
                    frameCount = frameCount.value,
                    onUnlock = { lockedTargetId.value = null },
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (isBottomControlsVisible.value) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // 3. Multi-PiP Tracker (pouze pro ne-SkyWatcher)
                    if (currentHudTheme.value != com.example.llama.agent.ui.studio.hud.HudTheme.SKY_WATCHER) {
                        MultiPiPOverlay(
                            detections = currentDetections,
                            lockedId = lockedTargetId.value,
                            mode = pipMode.value,
                            manualTargets = manualPipTargets,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 40.dp, start = 16.dp, end = 16.dp)
                        )
                    }

            // 4. Lens and PiP Controls and PRO Mode Controls
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // PiP Mode Switcher
                Row(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("PiP Mode:", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end=8.dp))
                    Button(
                        onClick = { pipMode.value = PipMode.AUTO },
                        colors = ButtonDefaults.buttonColors(containerColor = if (pipMode.value == PipMode.AUTO) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("AUTO", fontSize = 10.sp, color = Color.Black) }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { pipMode.value = PipMode.MANUAL },
                        colors = ButtonDefaults.buttonColors(containerColor = if (pipMode.value == PipMode.MANUAL) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("MANUAL", fontSize = 10.sp, color = Color.Black) }
                }

                // Tensor Mode (Aspect Ratio) Switcher
                var aspectRatioMode by remember { mutableStateOf(settingsManager.perceptionAspectRatioMode) }
                LazyRow(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item { Text("Tensor Mode:", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end=8.dp)) }
                    item {
                        Button(
                            onClick = { aspectRatioMode = 0; settingsManager.perceptionAspectRatioMode = 0 },
                            colors = ButtonDefaults.buttonColors(containerColor = if (aspectRatioMode == 0) Color(0xFF00FFCC) else Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp).padding(end=4.dp)
                        ) { Text("SQUASH", fontSize = 10.sp, color = if (aspectRatioMode == 0) Color.Black else Color.White) }
                    }
                    item {
                        Button(
                            onClick = { aspectRatioMode = 1; settingsManager.perceptionAspectRatioMode = 1 },
                            colors = ButtonDefaults.buttonColors(containerColor = if (aspectRatioMode == 1) Color(0xFF00FFCC) else Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp).padding(end=4.dp)
                        ) { Text("LETTERBOX", fontSize = 10.sp, color = if (aspectRatioMode == 1) Color.Black else Color.White) }
                    }
                    item {
                        Button(
                            onClick = { aspectRatioMode = 2; settingsManager.perceptionAspectRatioMode = 2 },
                            colors = ButtonDefaults.buttonColors(containerColor = if (aspectRatioMode == 2) Color(0xFF00FFCC) else Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp).padding(end=4.dp)
                        ) { Text("CENTER CROP", fontSize = 10.sp, color = if (aspectRatioMode == 2) Color.Black else Color.White) }
                    }
                }

                // Lens Switcher
                val availableLenses = remember { cameraCoordinator.getAvailableLenses() }

                LazyRow(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    item { Text("Lens:", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end=8.dp)) }
                    items(availableLenses) { lens ->
                        Button(
                            onClick = {
                                activeLensInfo.value = if (lens.id == "Auto") null else lens
                                currentZoomRatio.value = 1f // Reset na 1x vzhledem k základnímu ohnisku čočky
                                cameraCoordinator.zoomController.setZoom(1f)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if ((activeLensInfo.value?.id ?: "Auto") == lens.id) Color(0xFF00FFCC) else Color.DarkGray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp).padding(end=4.dp)
                        ) {
                            Text(lens.name, fontSize = 10.sp, color = if ((activeLensInfo.value?.id ?: "Auto") == lens.id) Color.Black else Color.White)
                        }
                    }
                }

                // Global Zoom Slider
                Row(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp).width(200.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val zoomText = String.format(java.util.Locale.US, "%.1f", currentZoomRatio.value)
                    Text("Zoom: \${zoomText}x", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(60.dp))

                    Slider(
                        value = currentZoomRatio.value,
                        onValueChange = { zoom ->
                            currentZoomRatio.value = zoom
                            cameraCoordinator.zoomController.setZoom(zoom)
                        },
                        valueRange = 1f..100f,
                        modifier = Modifier.weight(1f).height(24.dp)
                    )
                }

                // --- LUPA A CAMERA REŽIM ---
                Row(
                    modifier = Modifier.background(Color(0x88000000), RoundedCornerShape(8.dp)).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Lupa Toggle & Long Press for Menu
                    androidx.compose.foundation.layout.Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(50)) // Button shape
                            .background(if (isLupaActive.value) Color(0xFF00FFCC) else Color.DarkGray)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = { isLupaActive.value = !isLupaActive.value },
                                    onLongPress = { showLupaMenu = true }
                                )
                            }
                            .padding(horizontal = 12.dp)
                    ) {
                        Text("🔍 Lupa", fontSize = 10.sp, color = if (isLupaActive.value) Color.Black else Color.White)
                    }

                    Spacer(Modifier.width(4.dp))

                    // Pose Estimation Toggle (BlazePose 33) — Nezávislé na OD
                    Button(
                        onClick = {
                            val newState = !isPoseEstimationEnabled
                            isPoseEstimationEnabled = newState
                            settingsManager.isPoseEstimationEnabled = newState
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPoseEstimationEnabled) Color(0xFF00FFCC) else Color.DarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            if (isPoseEstimationEnabled) "🦴 POSE ON" else "🦴 POSE OFF",
                            fontSize = 10.sp,
                            color = if (isPoseEstimationEnabled) Color.Black else Color.White
                        )
                    }

                    Spacer(Modifier.width(8.dp))
                    Text("Camera Režim:", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end=8.dp))

                    Button(
                        onClick = {
                            showProControls.value = false
                            showFocusControls.value = false
                            showTimeLapseControls.value = false
                            isProModeEnabled.value = false
                            pushProSettingsToHardware()
                            studioFocusMode.value = com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF
                            cameraCoordinator.strategy.updateFocusMode(studioFocusMode.value, 0f)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (!showProControls.value && !showFocusControls.value && !showTimeLapseControls.value) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("AUTO", fontSize = 10.sp, color = Color.Black) }

                    Spacer(Modifier.width(4.dp))

                    Button(
                        onClick = {
                            showProControls.value = !showProControls.value
                            isProModeEnabled.value = showProControls.value
                            pushProSettingsToHardware()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (showProControls.value) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("PRO Mode", fontSize = 10.sp, color = Color.Black) }

                    Spacer(Modifier.width(4.dp))

                    Button(
                        onClick = { showFocusControls.value = !showFocusControls.value },
                        colors = ButtonDefaults.buttonColors(containerColor = if (showFocusControls.value) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("FOCUS", fontSize = 10.sp, color = Color.Black) }
                    
                    Spacer(Modifier.width(4.dp))
                    
                    Button(
                        onClick = { showTimeLapseControls.value = !showTimeLapseControls.value },
                        colors = ButtonDefaults.buttonColors(containerColor = if (showTimeLapseControls.value) Color(0xFF00FFCC) else Color.DarkGray),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) { Text("T-LAPSE", fontSize = 10.sp, color = Color.Black) }
                }

                // --- PRO CAMERA CONTROLS ---
                AnimatedVisibility(visible = showProControls.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("PRO CAMERA CONTROLS", color = Color(0xFF00FFCC), style = MaterialTheme.typography.labelSmall)

                            // Skrytý konfigurovatelný S-Mode
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("S-Mode (Auto ISO)", color = Color.White, fontSize = 10.sp)
                                Spacer(modifier = Modifier.width(4.dp))
                                Switch(
                                    checked = isShutterPriorityMode.value,
                                    onCheckedChange = { isShutterPriorityMode.value = it },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFCC), checkedTrackColor = Color.DarkGray),
                                    modifier = Modifier.height(20.dp)
                                )
                            }
                        }

                        if (isShutterPriorityMode.value) {
                            // LUM bar
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("LUM: ${currentLuminance.value}", color = Color.Blue, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                                LinearProgressIndicator(
                                    progress = { (currentLuminance.value / 255f).coerceIn(0f, 1f) },
                                    modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color.Blue,
                                    trackColor = Color.DarkGray
                                )
                            }
                        }

                        // ISO
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ISO: ${proIsoValue.value}", color = if (isShutterPriorityMode.value) Color.Gray else Color.White, modifier = Modifier.width(80.dp), fontSize = 12.sp)
                            Slider(
                                value = proIsoValue.value.toFloat(),
                                onValueChange = {
                                    proIsoValue.value = it.toInt()
                                    pushProSettingsToHardware()
                                },
                                valueRange = 10f..8200f,
                                modifier = Modifier.weight(1f).height(20.dp),
                                enabled = !isShutterPriorityMode.value
                            )
                        }

                        if (isShutterPriorityMode.value) {
                            // Deadband
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Deadband: ${sModeDeadband.value}", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = sModeDeadband.value.toFloat(),
                                    onValueChange = { sModeDeadband.value = it.toInt() },
                                    valueRange = 0f..50f,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD0C0FF), activeTrackColor = Color(0xFFD0C0FF), inactiveTrackColor = Color.DarkGray),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                )
                            }

                            // P-Comp
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("P-Comp: ${sModePComponent.value}x", color = Color.White, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = sModePComponent.value.toFloat(),
                                    onValueChange = { sModePComponent.value = it.toInt() },
                                    valueRange = 1f..20f,
                                    colors = SliderDefaults.colors(thumbColor = Color(0xFFD0C0FF), activeTrackColor = Color(0xFFD0C0FF), inactiveTrackColor = Color.DarkGray),
                                    modifier = Modifier.weight(1f).height(20.dp)
                                )
                            }

                            // Interval S-Mode
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Interval: ${sModeUpdateIntervalMs.value / 1000f}s", color = Color.Gray, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = sModeUpdateIntervalMs.value.toFloat(),
                                    onValueChange = { sModeUpdateIntervalMs.value = it.toLong() },
                                    valueRange = 100f..10000f,
                                    colors = SliderDefaults.colors(thumbColor = Color.Gray, activeTrackColor = Color.Gray, inactiveTrackColor = Color.DarkGray),
                                    modifier = Modifier.weight(1f).height(16.dp)
                                )
                            }
                        }

                        // Shutter
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Shutter:", color = Color.White, modifier = Modifier.width(60.dp), fontSize = 12.sp)
                            androidx.compose.foundation.lazy.LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(SHUTTER_SPEEDS_LABELS.size) { index ->
                                    val isSelected = proShutterSpeedIndex.value == index
                                    Button(
                                        onClick = {
                                            proShutterSpeedIndex.value = index
                                            pushProSettingsToHardware()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF00FFCC) else Color.DarkGray),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) { Text(SHUTTER_SPEEDS_LABELS[index], fontSize = 10.sp, color = if (isSelected) Color.Black else Color.White) }
                                }
                            }
                        }

                        // WB Mode
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("WB Mode:", color = Color.White, modifier = Modifier.width(60.dp), fontSize = 12.sp)
                            val wbModes = arrayOf(android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_AUTO, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, android.hardware.camera2.CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT)
                            val wbLabels = arrayOf("AUTO", "SUN", "BULB")

                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                wbModes.forEachIndexed { index, mode ->
                                    val isSelected = proWbMode.value == mode
                                    Button(
                                        onClick = {
                                            proWbMode.value = mode
                                            pushProSettingsToHardware()
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = if (isSelected) Color(0xFF00FFCC) else Color.DarkGray),
                                        contentPadding = PaddingValues(horizontal = 6.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) { Text(wbLabels[index], fontSize = 10.sp, color = if (isSelected) Color.Black else Color.White) }
                                }
                            }
                        }
                    }
                }

                // --- FOCUS CONTROLS ---
                AnimatedVisibility(visible = showFocusControls.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("FOCUS CONTROL", color = Color(0xFF00FFCC), style = MaterialTheme.typography.labelSmall)

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            // AF Button
                            Button(
                                onClick = {
                                    studioFocusMode.value = com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF
                                    cameraCoordinator.strategy.updateFocusMode(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF, 0f)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF) Color(0xFF00FFCC) else Color.DarkGray),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) { Text("AF", fontSize = 10.sp, color = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.AF) Color.Black else Color.White) }

                            // TRACK Button
                            Button(
                                onClick = {
                                    studioFocusMode.value = com.example.llama.agent.vision.camera.strategy.StudioFocusMode.TRACK
                                    cameraCoordinator.strategy.updateFocusMode(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.TRACK, 0f)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.TRACK) Color(0xFF00FFCC) else Color.DarkGray),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) { Text("TRACK", fontSize = 10.sp, color = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.TRACK) Color.Black else Color.White) }

                            // MF Button
                            Button(
                                onClick = {
                                    studioFocusMode.value = com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF
                                    cameraCoordinator.strategy.updateFocusMode(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF, manualFocusDistance.value)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF) Color(0xFF00FFCC) else Color.DarkGray),
                                modifier = Modifier.height(28.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp)
                            ) { Text("MF", fontSize = 10.sp, color = if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF) Color.Black else Color.White) }
                        }

                        if (studioFocusMode.value == com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val formattedDistance = String.format(java.util.Locale.US, "%.3f", manualFocusDistance.value)
                                    Text("DIST: $formattedDistance", color = Color(0xFF00FFCC), fontSize = 10.sp, modifier = Modifier.width(60.dp))
                                    Slider(
                                        value = manualFocusDistance.value,
                                        onValueChange = {
                                            manualFocusDistance.value = it
                                            cameraCoordinator.strategy.updateFocusMode(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF, it)
                                        },
                                        valueRange = 0.00f..1.00f,
                                        modifier = Modifier.weight(1f).height(20.dp)
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("MICRO:", color = Color(0xFFD0C0FF), fontSize = 10.sp, modifier = Modifier.width(60.dp))
                                    Slider(
                                        value = manualFocusDistance.value,
                                        onValueChange = {
                                            manualFocusDistance.value = it
                                            cameraCoordinator.strategy.updateFocusMode(com.example.llama.agent.vision.camera.strategy.StudioFocusMode.MF, it)
                                        },
                                        valueRange = (manualFocusDistance.value - 0.02f).coerceAtLeast(0f)..(manualFocusDistance.value + 0.02f).coerceAtMost(1f),
                                        colors = SliderDefaults.colors(thumbColor = Color(0xFFD0C0FF), activeTrackColor = Color(0xFFD0C0FF), inactiveTrackColor = Color.DarkGray),
                                        modifier = Modifier.weight(1f).height(20.dp)
                                    )
                                }
                            }
                        }
                    } // Closes Column
                } // Closes AnimatedVisibility

                // --- TIME LAPSE CONTROLS ---
                androidx.compose.animation.AnimatedVisibility(visible = showTimeLapseControls.value) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("TIME LAPSE CONTROL", color = Color(0xFF00FFCC), style = MaterialTheme.typography.labelSmall)

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Playback FPS: ${timeLapseFps.value}", color = Color.White, modifier = Modifier.width(120.dp), fontSize = 12.sp)
                            Slider(
                                value = timeLapseFps.value.toFloat(),
                                onValueChange = { timeLapseFps.value = it.toInt() },
                                valueRange = 4f..60f,
                                modifier = Modifier.weight(1f).height(20.dp),
                                enabled = !isTimeLapseRunning.value
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = isTimeLapseHighQuality.value,
                                onCheckedChange = { isTimeLapseHighQuality.value = it },
                                enabled = !isTimeLapseRunning.value,
                                colors = androidx.compose.material3.CheckboxDefaults.colors(checkedColor = Color.Green, checkmarkColor = Color.Black)
                            )
                            Text("Extrémní kvalita (High Bitrate)", color = Color.White, fontSize = 12.sp)
                        }

                        val frames = com.example.llama.agent.vision.nightmode.AcademicYuvEngine.recordedFramesCount.collectAsState()
                        if (isTimeLapseRunning.value) {
                            Text(
                                text = "Nahráno snímků: ${frames.value}",
                                color = Color(0xFF00FFCC),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        Button(
                            onClick = {
                                if (!isTimeLapseRunning.value) {
                                    val fileName = "AcademicTL_${System.currentTimeMillis()}.mp4"
                                    val file = java.io.File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), fileName)
                                    val config = context.resources.configuration
                                    val density = context.resources.displayMetrics.density
                                    val w = (config.screenWidthDp * density).toInt()
                                    val h = (config.screenHeightDp * density).toInt()
                                    
                                    // Zajištění, že rozměry jsou sudé (požadavek H.264 enkodéru)
                                    val safeW = w - (w % 2)
                                    val safeH = h - (h % 2)

                                    com.example.llama.agent.vision.nightmode.AcademicYuvEngine.startTimeLapse(
                                        timeLapseFps.value.toInt(), 
                                        file.absolutePath,
                                        safeW,
                                        safeH,
                                        isTimeLapseHighQuality.value
                                    )
                                    isTimeLapseRunning.value = true
                                    android.widget.Toast.makeText(context, "TimeLapse Started", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    com.example.llama.agent.vision.nightmode.AcademicYuvEngine.stopTimeLapse()
                                    isTimeLapseRunning.value = false
                                    android.widget.Toast.makeText(context, "TimeLapse Stopped & Saved", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = if (isTimeLapseRunning.value) Color.Red else Color.Green),
                            modifier = Modifier.fillMaxWidth().height(40.dp)
                        ) {
                            Text(if (isTimeLapseRunning.value) "STOP RECORDING" else "START TIME-LAPSE", fontSize = 14.sp, color = Color.White)
                        }
                    }
                }
            } // Closes MAIN Column
            } // End Box wrapper
        } // End if (isBottomControlsVisible)

            // 5. Layers Menu (Floating Panel) and Toggle Button
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, end = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { isBottomControlsVisible.value = !isBottomControlsVisible.value },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xAA333333)),
                    modifier = Modifier.padding(end = 8.dp).height(48.dp)
                ) {
                    Text(if (isBottomControlsVisible.value) "HIDE UI" else "SHOW UI", fontSize = 10.sp, color = Color.White)
                }
                LayersMenu(
                    onOpenAiHub = { showAiHub = true },
                    onShowKalmanConfig = { showKalmanConfigModal = true },
                    modifier = Modifier
                )
            }
        }

        if (showLupaMenu) {
            ModalBottomSheet(onDismissRequest = { showLupaMenu = false }) {
                Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("🔍 Lupa Settings", style = MaterialTheme.typography.headlineSmall, color = Color(0xFF00FFCC))
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isLupaCacheEnabled,
                            onCheckedChange = {
                                isLupaCacheEnabled = it
                                lupaPluginManager.isCacheEnabled = it
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black)
                        )
                        Text("Povolit mezipaměť (Multi-Frame DFG)", color = Color.White)
                    }

                    if (isLupaCacheEnabled) {
                        Text("Min. framů: $lupaCacheMinFrames", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = lupaCacheMinFrames.toFloat(),
                            onValueChange = { lupaCacheMinFrames = it.toInt() },
                            onValueChangeFinished = { lupaPluginManager.cacheMinFrames = lupaCacheMinFrames },
                            valueRange = 1f..60f
                        )
                        Text("Max kapacita poolu: $lupaCacheCapacity", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = lupaCacheCapacity.toFloat(),
                            onValueChange = { lupaCacheCapacity = it.toInt() },
                            onValueChangeFinished = { lupaPluginManager.cacheMaxCapacity = lupaCacheCapacity },
                            valueRange = 4f..30f
                        )
                        Text("Timeout: ${lupaCacheTimeout}ms", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = lupaCacheTimeout.toFloat(),
                            onValueChange = { lupaCacheTimeout = it.toLong() },
                            onValueChangeFinished = { lupaPluginManager.cacheTimeoutMs = lupaCacheTimeout },
                            valueRange = 50f..1000f
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = isLupaFixedResEnabled,
                                onCheckedChange = {
                                    isLupaFixedResEnabled = it
                                    lupaPluginManager.fixedResolution = if (it) lupaFixedResolution else null
                                },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black)
                            )
                            Text("Pevné rozlišení výřezu", color = Color.White, fontSize = 12.sp)
                        }
                        if (isLupaFixedResEnabled) {
                            Text("Rozlišení: ${lupaFixedResolution}x${lupaFixedResolution}px", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = lupaFixedResolution.toFloat(),
                                onValueChange = { lupaFixedResolution = it.toInt() },
                                onValueChangeFinished = { lupaPluginManager.fixedResolution = lupaFixedResolution },
                                valueRange = 240f..640f
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val formattedScale = String.format(java.util.Locale.US, "%.2f", lupaScaleFactor.value)
                    Text("Zvětšení: ${formattedScale}x", color = Color.White)

                    val minScale = 0.05f
                    val midScale = 5.0f
                    val maxScale = 100.0f

                    val scaleToSlider = { scale: Float ->
                        if (scale <= midScale) ((scale - minScale) / (midScale - minScale)) * 0.5f
                        else 0.5f + ((scale - midScale) / (maxScale - midScale)) * 0.5f
                    }
                    val sliderToScale = { v: Float ->
                        if (v <= 0.5f) minScale + (v * 2f) * (midScale - minScale)
                        else midScale + ((v - 0.5f) * 2f) * (maxScale - midScale)
                    }

                    Slider(
                        value = scaleToSlider(lupaScaleFactor.value.coerceIn(minScale, maxScale)),
                        onValueChange = { lupaScaleFactor.value = sliderToScale(it) },
                        valueRange = 0f..1f
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Algoritmus:", color = Color.White)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        items(lupaPluginManager.getAvailablePlugins()) { plugin ->
                            Button(
                                onClick = { activeLupaPluginId.value = plugin.pluginId },
                                colors = ButtonDefaults.buttonColors(containerColor = if (activeLupaPluginId.value == plugin.pluginId) Color(0xFF00FFCC) else Color.DarkGray)
                            ) {
                                Text(plugin.displayName, color = if (activeLupaPluginId.value == plugin.pluginId) Color.Black else Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }

        if (showAiHub) {
            ModalBottomSheet(onDismissRequest = { showAiHub = false }) {
                AiHubContent(
                    odManager = odManager,
                    settingsManager = settingsManager,
                    activeModelId = activeModelId,
                    onModelSelected = { id ->
                        activeModelId = id
                        settingsManager.activeOdModelId = id
                        showAiHub = false
                    }
                )
            }
        }
    }

    @Composable
    fun AiHubContent(
        odManager: OdModelManager,
        settingsManager: SettingsManager,
        activeModelId: String,
        onModelSelected: (String) -> Unit
    ) {
        val models = remember { listOf(odManager.getBundledRecord()) + odManager.getImportedModels() }

        Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
            Text("AI Hub - Active Models", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            models.forEach { model ->
                Card(
                    onClick = { onModelSelected(model.id) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (model.id == activeModelId) Color(0xFF00FFCC).copy(alpha = 0.2f) else Color.DarkGray
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(model.displayName, color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${model.modelType.name} | ${model.inputResolution}px | ${model.labelCount} labels",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (model.id == activeModelId) {
                            Text("ACTIVE", color = Color.Green, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color.DarkGray)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Hardware Acceleration", style = MaterialTheme.typography.titleMedium, color = Color(0xFF00FFCC))
            Spacer(modifier = Modifier.height(8.dp))

            var selectedMode by remember { mutableStateOf(settingsManager.perceptionAcceleratorMode) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("CPU", "GPU", "NNAPI", "AUTO").forEach { mode ->
                    Button(
                        onClick = {
                            selectedMode = mode
                            settingsManager.perceptionAcceleratorMode = mode
                            // Trigger re-init
                            odManager.activateModel(activeModelId)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedMode == mode) Color(0xFF00FFCC) else Color.DarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(mode, color = if (selectedMode == mode) Color.Black else Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("CPU Threads", style = MaterialTheme.typography.titleMedium, color = Color(0xFF00FFCC))
            var threadCount by remember { mutableStateOf(settingsManager.perceptionCpuThreads.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${threadCount.toInt()}", color = Color.White, modifier = Modifier.width(30.dp))
                Slider(
                    value = threadCount,
                    onValueChange = {
                        threadCount = it
                        settingsManager.perceptionCpuThreads = it.toInt()
                    },
                    onValueChangeFinished = {
                        // Trigger re-init
                        odManager.activateModel(activeModelId)
                    },
                    valueRange = 1f..8f,
                    steps = 6,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00FFCC),
                        activeTrackColor = Color(0xFF00FFCC)
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Aspect Ratio (Geometry)", style = MaterialTheme.typography.titleMedium, color = Color(0xFF00FFCC))
            Spacer(modifier = Modifier.height(8.dp))

            var selectedAspectMode by remember { mutableStateOf(settingsManager.perceptionAspectRatioMode) }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                val modes = listOf("SQUASH" to 0, "LETTERBOX" to 1, "CROP" to 2)
                modes.forEach { (label, modeValue) ->
                    Button(
                        onClick = {
                            selectedAspectMode = modeValue
                            settingsManager.perceptionAspectRatioMode = modeValue
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectedAspectMode == modeValue) Color(0xFF00FFCC) else Color.DarkGray
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp)
                    ) {
                        Text(label, color = if (selectedAspectMode == modeValue) Color.Black else Color.White, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    @Composable
    fun LupaOverlay(
        detections: List<Detection>,
        lockedId: String?,
        lupaResult: android.graphics.Bitmap?,
        lupaScaleFactor: Float,
        lupaCropRectState: androidx.compose.runtime.MutableState<android.graphics.RectF?>,
        onLupaResult: (android.graphics.Bitmap) -> Unit,
        modifier: Modifier
    ) {
        // Trigger processing when a new frame arrives via `frameCount`
        val currentFrame = frameCount.value

        LaunchedEffect(currentFrame) {
            val bmp = rotatedBitmap ?: return@LaunchedEffect
            val target = detections.find { it.label == lockedId }

            val bmpW = bmp.width
            val bmpH = bmp.height

            // Určení středu výřezu
            val cx: Float
            val cy: Float
            if (target != null) {
                cx = (target.rect.left + target.rect.right) / 2f * bmpW
                cy = (target.rect.top + target.rect.bottom) / 2f * bmpH
            } else {
                cx = bmpW / 2f
                cy = bmpH / 2f
            }

            // Velikost výřezu (zdrojová data) - zmenšujeme výřez podle scaleFactor
            // Čím větší lupaScaleFactor, tím menší výřez ze zdroje odesíláme.
            // Pevná velikost výstupního okna (např 400x400), takže zdrojový výřez je 400/scale
            val outputSize = 400f
            val cropW = (outputSize / lupaScaleFactor).coerceAtMost(bmpW.toFloat())
            val cropH = (outputSize / lupaScaleFactor).coerceAtMost(bmpH.toFloat())

            val srcLeft = (cx - cropW/2).toInt().coerceIn(0, bmpW - 1)
            val srcTop = (cy - cropH/2).toInt().coerceIn(0, bmpH - 1)
            val actualCropW = cropW.toInt().coerceIn(1, bmpW - srcLeft)
            val actualCropH = cropH.toInt().coerceIn(1, bmpH - srcTop)

            if (actualCropW > 0 && actualCropH > 0) {
                // Přesouváme těžkou I/O alokaci ořezu (createBitmap) úplně pryč z UI vlákna!
                // LupaPluginManager to udělá asynchronně na pozadí, takže máme Zero UI Lag.
                val cropRect = android.graphics.Rect(srcLeft, srcTop, srcLeft + actualCropW, srcTop + actualCropH)
                
                // Uložíme normalizované souřadnice výřezu pro inverzní transformaci boxů v UI
                // RectF přijímá (left, top, right, bottom), ne (left, top, width, height)!
                lupaCropRectState.value = android.graphics.RectF(
                    srcLeft.toFloat() / bmpW,
                    srcTop.toFloat() / bmpH,
                    (srcLeft + actualCropW).toFloat() / bmpW,
                    (srcTop + actualCropH).toFloat() / bmpH
                )

                // Autofokus (lockOnTarget) byl přesunut, ať neruší fyzickou čočku kamery 30x za vteřinu.
                // Uživatel ostří přes manuální UI, lupa se stará jen o svůj sw processing.

                lupaPluginManager.submitFrame(bmp, cropRect, lupaScaleFactor)
            }
        }

        // Render
        if (lupaResult != null) {
            var pipOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            var pipScale by remember { mutableStateOf(1f) }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize()
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 100.dp, end = 16.dp)
                        .offset { androidx.compose.ui.unit.IntOffset(pipOffset.x.toInt(), pipOffset.y.toInt()) }
                        .graphicsLayer(
                            scaleX = pipScale,
                            scaleY = pipScale
                        )
                        .size(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .border(2.dp / pipScale, Color(0xFF00FFCC), RoundedCornerShape(12.dp))
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                pipOffset += pan
                                pipScale = (pipScale * zoom).coerceIn(0.5f, 5f)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawIntoCanvas { canvas ->
                            val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())
                            canvas.nativeCanvas.drawBitmap(lupaResult, null, dstRect, null)
                        }
                    }
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                    ) {
                        Text(
                            text = "🔍 LUPA (x${lupaScaleFactor})",
                            color = Color(0xFF00FFCC),
                            fontSize = (10 / pipScale).sp,
                            modifier = Modifier.background(Color(0x88000000)).padding(2.dp)
                        )
                        
                        var isLabMode by remember { mutableStateOf(settingsManager.lupaVisionDebugMode) }
                        
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                                .padding(4.dp)
                                .clickable {
                                    isLabMode = !isLabMode
                                    settingsManager.lupaVisionDebugMode = isLabMode
                                    // ⚠️ RESET KALMANU: zabrání Kalman Driftu při změně pipeline zpoždění!
                                    com.example.llama.agent.vision.VisionSourceManager.onResetTrackers?.invoke()
                                }
                        ) {
                            Text(
                                text = if (isLabMode) "⚠️ LAB MODE: ON" else "LAB MODE: OFF",
                                color = if (isLabMode) Color.Red else Color.White,
                                fontSize = (10 / pipScale).sp,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }

                        // T-Junction Confidence Telemetry
                        val avgConf = if (detections.isNotEmpty()) detections.map { it.confidence }.average().toFloat() else 0f
                        Text(
                            text = "T-Junc SNR: ${(avgConf * 100).toInt()}%",
                            color = if (avgConf > 0.6f) Color(0xFF00FFCC) else Color.Yellow,
                            fontSize = (10 / pipScale).sp,
                            modifier = Modifier.padding(top = 2.dp).background(Color(0x88000000)).padding(2.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun CameraPreviewBox(modifier: Modifier) {
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        val context = LocalContext.current
        var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    previewViewRef?.let {
                        bindCamera(context, lifecycleOwner, it, activeLensInfo.value)
                    }
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }

        // Rebind when active lens changes
        LaunchedEffect(activeLensInfo.value) {
            previewViewRef?.let {
                bindCamera(context, lifecycleOwner, it, activeLensInfo.value)
            }
        }

        AndroidView(
            factory = { ctx ->
                val view = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewViewRef = view
                bindCamera(ctx, lifecycleOwner, view, activeLensInfo.value)
                view
            },
            modifier = modifier.pointerInput(Unit) {
                detectTransformGestures { _, _, zoom, _ ->
                    val currentZoom = cameraCoordinator.zoomController.currentZoomRatio
                    val maxZoom = cameraCoordinator.zoomController.maxZoomRatio
                    val minZoom = 1f
                    val newZoom = (currentZoom * zoom).coerceIn(minZoom, maxZoom)
                    cameraCoordinator.zoomController.setZoom(newZoom)
                }
            }
        )
    }

    @Composable
    fun DetectionOverlay(detections: List<Detection>, modifier: Modifier, sourceWidth: Int, sourceHeight: Int, lupaCropRect: android.graphics.RectF? = null) {
        val outRect = remember { android.graphics.RectF() }

        Canvas(
            modifier = modifier.pointerInput(detections) {
                detectTapGestures { tapOffset ->
                    handleCanvasTap(tapOffset, size, detections)
                }
            }
        ) {
            val viewW = size.width
            val viewH = size.height

            val matrix = com.example.llama.agent.vision.camera.math.ViewportCoordinateTransformer.computeFillCenterMatrix(
                imageW = sourceWidth.toFloat().coerceAtLeast(1f),
                imageH = sourceHeight.toFloat().coerceAtLeast(1f),
                viewW = viewW,
                viewH = viewH
            )

            detections.forEach { det ->
                var normLeft = det.rect.left
                var normTop = det.rect.top
                var normRight = det.rect.right
                var normBottom = det.rect.bottom

                // Inverzní transformace pro LAB MODE: 
                // Tensor posílá relativní souřadnice [0, 1] vůči výřezu Lupy,
                // musíme je přepočítat zpět na relativní souřadnice celého obrazu [0, 1].
                if (lupaCropRect != null) {
                    val cw = lupaCropRect.width()
                    val ch = lupaCropRect.height()
                    normLeft = lupaCropRect.left + (normLeft * cw)
                    normTop = lupaCropRect.top + (normTop * ch)
                    normRight = lupaCropRect.left + (normRight * cw)
                    normBottom = lupaCropRect.top + (normBottom * ch)
                }

                com.example.llama.agent.vision.camera.math.ViewportCoordinateTransformer.mapMlToUi(
                    normLeft = normLeft,
                    normTop = normTop,
                    normRight = normRight,
                    normBottom = normBottom,
                    matrix = matrix,
                    outRect = outRect
                )

                val safeColorIndex = max(0, det.colorIndex)
                val colorInt = com.example.llama.agent.vision.BoundingBoxView.COLORS[safeColorIndex % com.example.llama.agent.vision.BoundingBoxView.COLORS.size]
                // Apply confidence as alpha for smooth fade-out (min 20% visibility)
                val alpha = det.confidence.coerceIn(0.2f, 1.0f)
                val composeColor = Color(colorInt).copy(alpha = alpha)

                if (showBoxes.value) {
                    drawRect(
                        color = composeColor,
                        topLeft = Offset(outRect.left, outRect.top),
                        size = ComposeSize(outRect.width(), outRect.height()),
                        style = Stroke(
                            width = 4.dp.toPx(),
                            pathEffect = if (det.isPredictive) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) else null
                        )
                    )
                }

                if (showLabels.value) {
                    drawIntoCanvas { canvas ->
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)
                            textSize = 36f
                            isAntiAlias = true
                            setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
                        }
                        canvas.nativeCanvas.drawText(
                            det.label + if(showStats.value) " ${(det.confidence * 100).toInt()}%" else "",
                            outRect.left + 10f,
                            outRect.top + 40f,
                            paint
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun MultiPiPOverlay(
        detections: List<Detection>,
        lockedId: String?,
        mode: PipMode,
        manualTargets: androidx.compose.runtime.snapshots.SnapshotStateList<ManualPipTarget>,
        modifier: Modifier
    ) {
        val currentFrameCount by remember { derivedStateOf { frameCount.value } }
        // Local state map for zoom level per object label (works in AUTO and MANUAL)
        val pipZooms = remember { mutableStateMapOf<String, Float>() }
        // Local state map for custom dimensions (width, height)
        val pipSizes = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
        val density = LocalContext.current.resources.displayMetrics.density

        val targetsToRender = mutableListOf<Pair<Detection, ManualPipTarget?>>()

        if (mode == PipMode.AUTO) {
            val lockedDet = detections.find { it.label == lockedId }
            val others = detections.filter { it.label != lockedId }
            val combined = if (lockedDet != null) listOf(lockedDet) + others else others
            combined.take(8).forEach { targetsToRender.add(Pair(it, null)) }
        } else {
            val lockedManual = manualTargets.find { it.id == lockedId }
            val otherManuals = manualTargets.filter { it.id != lockedId }
            val combinedManuals = if (lockedManual != null) listOf(lockedManual) + otherManuals else otherManuals

            combinedManuals.forEach { manual ->
                val det = detections.find { it.label == manual.id }
                if (det != null) {
                    targetsToRender.add(Pair(det, manual))
                }
            }
        }

        if (targetsToRender.isEmpty()) return

        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            targetsToRender.forEach { (target, manualPip) ->
                val isLocked = target.label == lockedId
                val currentSize = pipSizes[target.label] ?: Pair(160f, 120f)

                Box(
                    modifier = Modifier
                        .size(currentSize.first.dp, currentSize.second.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x88000000))
                        .border(if (isLocked) 3.dp else 1.dp, if (isLocked) Color(0xFF00FFCC) else Color.Cyan, RoundedCornerShape(8.dp))
                        .pointerInput(target.label) {
                            detectTransformGestures { _, _, zoom, _ ->
                                // Pinch-to-zoom inside PiP (works for both AUTO and MANUAL)
                                // Zmenšení scale = větší zoom (ořez). Limit: 5% - 50%
                                val currentScale = pipZooms[target.label] ?: 0.15f
                                pipZooms[target.label] = (currentScale / zoom).coerceIn(0.02f, 0.5f)
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val dummy = currentFrameCount // Force recomposition
                        val bmp = rotatedBitmap
                        if (bmp != null) {
                            drawIntoCanvas { canvas ->
                                try {
                                    val bmpW = bmp.width
                                    val bmpH = bmp.height

                                    val cx = (target.rect.left + target.rect.right) / 2f * bmpW
                                    val cy = (target.rect.top + target.rect.bottom) / 2f * bmpH

                                    val scale = if (isLocked && enableDynamicPipZoom.value) {
                                        // Dynamic PiP Auto-Zoom: Fill 50% of the PiP window
                                        val targetMaxDim = kotlin.math.max(target.rect.width(), target.rect.height())
                                        (targetMaxDim * 2.0f).coerceIn(0.02f, 0.5f)
                                    } else {
                                        pipZooms[target.label] ?: 0.15f
                                    }

                                    val cropW = bmpW * scale
                                    val cropH = bmpH * scale

                                    val srcLeft = (cx - cropW/2).toInt().coerceIn(0, bmpW)
                                    val srcTop = (cy - cropH/2).toInt().coerceIn(0, bmpH)
                                    val srcRight = (cx + cropW/2).toInt().coerceIn(0, bmpW)
                                    val srcBottom = (cy + cropH/2).toInt().coerceIn(0, bmpH)

                                    val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
                                    val dstRect = android.graphics.Rect(0, 0, size.width.toInt(), size.height.toInt())

                                    canvas.nativeCanvas.drawBitmap(bmp, srcRect, dstRect, null)
                                } catch (e: Exception) { }
                            }
                        }
                    }

                    // Text Info
                    Column(modifier = Modifier.padding(4.dp).align(Alignment.BottomStart)) {
                        Text(
                            text = if (isLocked) "🎯 ${target.label}" else target.label,
                            color = if (isLocked) Color(0xFF00FFCC) else Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    // Zavírací křížek (pro manual, nebo pro locked)
                    if (isLocked || mode == PipMode.MANUAL) {
                        IconButton(
                            onClick = {
                                if (isLocked) {
                                    lockedTargetId.value = null
                                    cameraCoordinator.lockOnTargetController.releaseLock()
                                }
                                if (mode == PipMode.MANUAL && manualPip != null) {
                                    manualTargets.remove(manualPip)
                                }
                            },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) {
                            Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Drag to Resize Handle
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(topStart = 8.dp))
                            .pointerInput(target.label) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val current = pipSizes[target.label] ?: Pair(160f, 120f)
                                    val newW = (current.first + dragAmount.x / density).coerceIn(100f, 400f)
                                    val newH = (current.second + dragAmount.y / density).coerceIn(100f, 400f)
                                    pipSizes[target.label] = Pair(newW, newH)
                                }
                            }
                    ) {
                        Text("↘", color = Color.White, modifier = Modifier.align(Alignment.Center), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    fun LayersMenu(
        onOpenAiHub: () -> Unit,
        onShowKalmanConfig: () -> Unit,
        modifier: Modifier
    ) {
        var expanded by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val settingsManager = remember { com.example.llama.SettingsManager(context) }
        val scrollState = rememberScrollState()
        
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val maxMenuHeight = configuration.screenHeightDp.dp
        
        var menuHeight by remember { mutableStateOf(500.dp) }
        val density = androidx.compose.ui.platform.LocalDensity.current

        Column(
            horizontalAlignment = Alignment.End,
            modifier = modifier
        ) {
            AnimatedVisibility(visible = expanded) {
                Card(
                    modifier = Modifier
                        .padding(bottom = 16.dp)
                        .width(280.dp)
                        .height(menuHeight),
                    colors = CardDefaults.cardColors(containerColor = Color(0xD91E1E1E)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // Drag Handle
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(24.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        with(density) {
                                            menuHeight = (menuHeight - dragAmount.y.toDp()).coerceIn(200.dp, maxMenuHeight)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.HorizontalDivider(
                                modifier = Modifier
                                    .width(40.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                thickness = 4.dp,
                                color = Color.Gray
                            )
                        }

                        // Content
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp)
                                .verticalScroll(scrollState),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("HUD Layers", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            
                            Row(modifier = Modifier.fillMaxWidth().clickable { showBoxes.value = !showBoxes.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Bounding Boxes", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showBoxes.value, onCheckedChange = { showBoxes.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }
                            Row(modifier = Modifier.fillMaxWidth().clickable { showLabels.value = !showLabels.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Labels", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showLabels.value, onCheckedChange = { showLabels.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }
                            Row(modifier = Modifier.fillMaxWidth().clickable { showStats.value = !showStats.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Confidence Stats", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showStats.value, onCheckedChange = { showStats.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                            Text("Sky & Astronomy", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)

                            var enableSkyMode by remember { mutableStateOf(settingsManager.isSkyModeEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableSkyMode = !enableSkyMode; settingsManager.isSkyModeEnabled = enableSkyMode }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Sky Scan (UAP)", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableSkyMode, onCheckedChange = { enableSkyMode = it; settingsManager.isSkyModeEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            var enableNightSkyMode by remember { mutableStateOf(settingsManager.isNightSkyModeEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableNightSkyMode = !enableNightSkyMode; settingsManager.isNightSkyModeEnabled = enableNightSkyMode }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Night Sky Mode", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableNightSkyMode, onCheckedChange = { enableNightSkyMode = it; settingsManager.isNightSkyModeEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                            // ==================== NIGHT MODE ENHANCERS ====================
                            Text("Night Mode (Low-Light Enhancers)", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)
                            
                            var enableNightMode by remember { mutableStateOf(settingsManager.isNightModeEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableNightMode = !enableNightMode; settingsManager.isNightModeEnabled = enableNightMode }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Enable Night Mode Engine", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableNightMode, onCheckedChange = { enableNightMode = it; settingsManager.isNightModeEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            var selectedNightPluginId by remember { mutableStateOf(settingsManager.activeNightModePluginId) }
                            val nightPlugins = listOf(
                                Pair("night_retinex", "Fast Retinex (CPU Math)"),
                                Pair("night_tflite_mobileie", "TFLite Edge (Zero-DCE/MobileIE)")
                            )

                            nightPlugins.forEach { (id, name) ->
                                val isActive = selectedNightPluginId == id
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(36.dp)
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(if (isActive) Color(0xFF00FFCC) else Color.DarkGray)
                                        .clickable {
                                            settingsManager.activeNightModePluginId = id
                                            selectedNightPluginId = id
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(name, fontSize = 12.sp, color = if (isActive) Color.Black else Color.White)
                                }
                            }

                            // Import TFLite modelu
                            var customNightModelPath by remember { mutableStateOf(settingsManager.customNightModeModelPath) }
                            val context = LocalContext.current
                            val modelsDir = remember { java.io.File(context.filesDir, "night_models").apply { mkdirs() } }
                            var availableModels by remember { mutableStateOf(modelsDir.listFiles()?.toList() ?: emptyList()) }

                            val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                                if (uri != null) {
                                    try {
                                        var fileName = "night_model_${System.currentTimeMillis()}.tflite"
                                        // Získání reálného jména souboru
                                        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                            if (cursor.moveToFirst()) {
                                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                                if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                                            }
                                        }
                                        if (!fileName.endsWith(".tflite")) fileName += ".tflite"

                                        val targetFile = java.io.File(modelsDir, fileName)
                                        val inputStream = context.contentResolver.openInputStream(uri)
                                        inputStream?.use { input ->
                                            java.io.FileOutputStream(targetFile).use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                        
                                        availableModels = modelsDir.listFiles()?.toList() ?: emptyList()
                                        settingsManager.customNightModeModelPath = targetFile.absolutePath
                                        customNightModelPath = targetFile.absolutePath
                                        com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.ReloadNightModeModelEvent())
                                        android.widget.Toast.makeText(context, "Model uložen: $fileName", android.widget.Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Log.e("NightMode", "Chyba importu", e)
                                    }
                                }
                            }

                            if (selectedNightPluginId == "night_tflite_mobileie") {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Importované Modely:", color = Color.Gray, fontSize = 12.sp)
                                
                                androidx.compose.foundation.lazy.LazyColumn(
                                    modifier = Modifier.fillMaxWidth().heightIn(max = 150.dp)
                                ) {
                                    items(availableModels) { file ->
                                        val isSelected = file.absolutePath == customNightModelPath
                                        androidx.compose.foundation.layout.Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 2.dp)
                                                .background(if (isSelected) Color(0xFF388E3C) else Color(0xFF222222), shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                .clickable {
                                                    settingsManager.customNightModeModelPath = file.absolutePath
                                                    customNightModelPath = file.absolutePath
                                                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.ReloadNightModeModelEvent())
                                                }
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = file.name,
                                                color = Color.White,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f)
                                            )
                                            val sizeKb = file.length() / 1024
                                            Text(
                                                text = "${sizeKb} KB",
                                                color = Color.Gray,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            IconButton(
                                                onClick = {
                                                    if (file.delete()) {
                                                        availableModels = modelsDir.listFiles()?.toList() ?: emptyList()
                                                        if (isSelected) {
                                                            settingsManager.customNightModeModelPath = null
                                                            customNightModelPath = null
                                                            com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.ReloadNightModeModelEvent())
                                                        }
                                                        android.widget.Toast.makeText(context, "Smazáno", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Text("🗑", color = Color.Red, fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }

                                if (availableModels.isEmpty()) {
                                    Text("Žádné modely. Importujte prosím model.", color = Color(0xFFFFB300), fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Button(
                                    onClick = { filePickerLauncher.launch("*/*") },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                    modifier = Modifier.fillMaxWidth().height(36.dp)
                                ) {
                                    Text("Importovat .tflite", fontSize = 12.sp)
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                var enableAutoContrast by remember { mutableStateOf(settingsManager.nightModeAutoContrast) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            enableAutoContrast = !enableAutoContrast
                                            settingsManager.nightModeAutoContrast = enableAutoContrast
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Dynamic Auto-Contrast", color = Color.White, fontSize = 14.sp)
                                        Text("Zabrání vybledlému obrazu u některých modelů", color = Color.Gray, fontSize = 10.sp)
                                    }
                                    Switch(
                                        checked = enableAutoContrast,
                                        onCheckedChange = { 
                                            enableAutoContrast = it
                                            settingsManager.nightModeAutoContrast = it
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFCC), checkedTrackColor = Color(0xFF00FFCC).copy(alpha = 0.5f))
                                    )
                                }

                                var academicMode by remember { mutableStateOf(settingsManager.academicNightVisionMode) }
                                val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                                var debounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
                                
                                val onAcademicModeToggle: (Boolean) -> Unit = { newValue ->
                                    if (academicMode != newValue) {
                                        academicMode = newValue
                                        debounceJob?.cancel()
                                        debounceJob = coroutineScope.launch {
                                            kotlinx.coroutines.delay(500)
                                            settingsManager.academicNightVisionMode = newValue
                                            if (newValue && settingsManager.customNightModeModelPath == null) {
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    android.widget.Toast.makeText(this@ObjectDetectorStudioActivity, "VAROVÁNÍ: Není vybrán žádný TFLite model pro YUV!", android.widget.Toast.LENGTH_LONG).show()
                                                }
                                            }
                                            
                                            if (!newValue) {
                                                // Pokud vypínáme, uvolníme TFLite zdroje (zabrání to crashi při rychlém recreate)
                                                com.example.llama.agent.vision.nightmode.AcademicYuvEngine.release()
                                            }
                                            
                                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                android.widget.Toast.makeText(this@ObjectDetectorStudioActivity, "Restartuji engine...", android.widget.Toast.LENGTH_SHORT).show()
                                                recreate()
                                            }
                                        }
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Academic Night Vision Engine (YUV)", color = Color(0xFFFF5555), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                        Text("Použití syrová data ze senzoru (vyžaduje restart)", color = Color.Gray, fontSize = 10.sp)
                                    }
                                    Switch(
                                        checked = academicMode,
                                        onCheckedChange = { onAcademicModeToggle(it) },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFFFF5555), checkedTrackColor = Color(0xFFFF5555).copy(alpha = 0.5f))
                                    )
                                }

                                if (academicMode) {
                                    var hwAccel by remember { mutableStateOf(settingsManager.academicYuvHardwareAcceleration) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Hardware Acceleration", color = Color.White, fontSize = 14.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                "CPU",
                                                color = if (hwAccel == "CPU") Color.Black else Color.White,
                                                modifier = Modifier
                                                    .background(if (hwAccel == "CPU") Color(0xFFFF5555) else Color.DarkGray, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                    .clickable { hwAccel = "CPU"; settingsManager.academicYuvHardwareAcceleration = "CPU" }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                            Text(
                                                "GPU",
                                                color = if (hwAccel == "GPU") Color.Black else Color.White,
                                                modifier = Modifier
                                                    .background(if (hwAccel == "GPU") Color(0xFFFF5555) else Color.DarkGray, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                    .clickable { hwAccel = "GPU"; settingsManager.academicYuvHardwareAcceleration = "GPU" }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                            // NPU tlačítko: Hexagon HTP (Snapdragon 8 Gen 3+)
                                            // Barva: cyan-blue (0xFF00CCFF) — vizuální odlišení od CPU/GPU
                                            // Pokud QNN init selže na zařízení bez HTP, auto-fallback na GPU
                                            Text(
                                                "NPU",
                                                color = if (hwAccel == "NPU") Color.Black else Color.White,
                                                modifier = Modifier
                                                    .background(if (hwAccel == "NPU") Color(0xFF00CCFF) else Color.DarkGray, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                                    .clickable { hwAccel = "NPU"; settingsManager.academicYuvHardwareAcceleration = "NPU" }
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                var manualOverride by remember { mutableStateOf(settingsManager.nightModeManualScaling) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { 
                                            manualOverride = !manualOverride
                                            settingsManager.nightModeManualScaling = manualOverride
                                        }
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Manual Input Normalization", color = Color.White, fontSize = 14.sp)
                                        Text("Přepsat databázi známých modelů", color = Color.Gray, fontSize = 10.sp)
                                    }
                                    Switch(
                                        checked = manualOverride,
                                        onCheckedChange = { 
                                            manualOverride = it
                                            settingsManager.nightModeManualScaling = it
                                        },
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF00FFCC), checkedTrackColor = Color(0xFF00FFCC).copy(alpha = 0.5f))
                                    )
                                }

                                if (manualOverride) {
                                    var inputScale by remember(academicMode) { mutableStateOf(if (academicMode) settingsManager.academicYuvInputScale else settingsManager.nightModeInputScale) }
                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Input Scale: ${String.format(java.util.Locale.US, "%.1f", inputScale)}", color = Color.LightGray, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("-", color = Color(0xFF00FFCC), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { inputScale = (inputScale - 1f).coerceAtLeast(1f); if (academicMode) settingsManager.academicYuvInputScale = inputScale else settingsManager.nightModeInputScale = inputScale }.padding(horizontal = 8.dp))
                                            Text("RST", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { inputScale = 255f; if (academicMode) settingsManager.academicYuvInputScale = 255f else settingsManager.nightModeInputScale = 255f }.padding(horizontal = 8.dp))
                                            Text("+", color = Color(0xFF00FFCC), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { inputScale = (inputScale + 1f).coerceAtMost(255f); if (academicMode) settingsManager.academicYuvInputScale = inputScale else settingsManager.nightModeInputScale = inputScale }.padding(horizontal = 8.dp))
                                        }
                                    }
                                    androidx.compose.material3.Slider(
                                        value = inputScale,
                                        onValueChange = { 
                                            inputScale = it
                                            if (academicMode) settingsManager.academicYuvInputScale = it else settingsManager.nightModeInputScale = it
                                        },
                                        valueRange = 1f..255f,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = Color(0xFF00FFCC),
                                            activeTrackColor = Color(0xFF00FFCC)
                                        )
                                    )

                                    var inputOffset by remember(academicMode) { mutableStateOf(if (academicMode) settingsManager.academicYuvInputOffset else settingsManager.nightModeInputOffset) }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Input Offset: ${String.format(java.util.Locale.US, "%.2f", inputOffset)}", color = Color.LightGray, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("-", color = Color(0xFF00FFCC), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { inputOffset = (inputOffset - 0.05f).coerceAtLeast(-1f); if (academicMode) settingsManager.academicYuvInputOffset = inputOffset else settingsManager.nightModeInputOffset = inputOffset }.padding(horizontal = 8.dp))
                                            Text("RST", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { inputOffset = 0f; if (academicMode) settingsManager.academicYuvInputOffset = 0f else settingsManager.nightModeInputOffset = 0f }.padding(horizontal = 8.dp))
                                            Text("+", color = Color(0xFF00FFCC), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { inputOffset = (inputOffset + 0.05f).coerceAtMost(1f); if (academicMode) settingsManager.academicYuvInputOffset = inputOffset else settingsManager.nightModeInputOffset = inputOffset }.padding(horizontal = 8.dp))
                                        }
                                    }
                                    androidx.compose.material3.Slider(
                                        value = inputOffset,
                                        onValueChange = { 
                                            inputOffset = it
                                            if (academicMode) settingsManager.academicYuvInputOffset = it else settingsManager.nightModeInputOffset = it
                                        },
                                        valueRange = -1f..1f,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = Color(0xFF00FFCC),
                                            activeTrackColor = Color(0xFF00FFCC)
                                        )
                                    )
                                    
                                    var outputScale by remember(academicMode) { mutableStateOf(if (academicMode) settingsManager.academicYuvOutputScale else settingsManager.nightModeOutputScale) }
                                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Output Scale: ${String.format(java.util.Locale.US, "%.1f", outputScale)}", color = Color.LightGray, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("-", color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { outputScale = (outputScale - 1f).coerceAtLeast(1f); if (academicMode) settingsManager.academicYuvOutputScale = outputScale else settingsManager.nightModeOutputScale = outputScale }.padding(horizontal = 8.dp))
                                            Text("RST", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { outputScale = 255f; if (academicMode) settingsManager.academicYuvOutputScale = 255f else settingsManager.nightModeOutputScale = 255f }.padding(horizontal = 8.dp))
                                            Text("+", color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { outputScale = (outputScale + 1f).coerceAtMost(255f); if (academicMode) settingsManager.academicYuvOutputScale = outputScale else settingsManager.nightModeOutputScale = outputScale }.padding(horizontal = 8.dp))
                                        }
                                    }
                                    androidx.compose.material3.Slider(
                                        value = outputScale,
                                        onValueChange = { 
                                            outputScale = it
                                            if (academicMode) settingsManager.academicYuvOutputScale = it else settingsManager.nightModeOutputScale = it
                                        },
                                        valueRange = 1f..255f,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFCC00),
                                            activeTrackColor = Color(0xFFFFCC00)
                                        )
                                    )

                                    var outputOffset by remember(academicMode) { mutableStateOf(if (academicMode) settingsManager.academicYuvOutputOffset else settingsManager.nightModeOutputOffset) }
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text("Output Offset: ${String.format(java.util.Locale.US, "%.2f", outputOffset)}", color = Color.LightGray, fontSize = 12.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text("-", color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { outputOffset = (outputOffset - 5f).coerceAtLeast(-255f); if (academicMode) settingsManager.academicYuvOutputOffset = outputOffset else settingsManager.nightModeOutputOffset = outputOffset }.padding(horizontal = 8.dp))
                                            Text("RST", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.clickable { outputOffset = 0f; if (academicMode) settingsManager.academicYuvOutputOffset = 0f else settingsManager.nightModeOutputOffset = 0f }.padding(horizontal = 8.dp))
                                            Text("+", color = Color(0xFFFFCC00), fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.clickable { outputOffset = (outputOffset + 5f).coerceAtMost(255f); if (academicMode) settingsManager.academicYuvOutputOffset = outputOffset else settingsManager.nightModeOutputOffset = outputOffset }.padding(horizontal = 8.dp))
                                        }
                                    }
                                    androidx.compose.material3.Slider(
                                        value = outputOffset,
                                        onValueChange = { 
                                            outputOffset = it
                                            if (academicMode) settingsManager.academicYuvOutputOffset = it else settingsManager.nightModeOutputOffset = it
                                        },
                                        valueRange = -255f..255f,
                                        colors = androidx.compose.material3.SliderDefaults.colors(
                                            thumbColor = Color(0xFFFFCC00),
                                            activeTrackColor = Color(0xFFFFCC00)
                                        )
                                    )
                                }
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))


                            Text("Mathematical Pre-Processors", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)
                            
                            var enableClahe by remember { mutableStateOf(settingsManager.isClahePreProcessorEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableClahe = !enableClahe; settingsManager.isClahePreProcessorEnabled = enableClahe }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("FastCLAHE (Edge)", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableClahe, onCheckedChange = { enableClahe = it; settingsManager.isClahePreProcessorEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            var enableLogStretch by remember { mutableStateOf(settingsManager.isLogStretchEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableLogStretch = !enableLogStretch; settingsManager.isLogStretchEnabled = enableLogStretch }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("LogStretch (HDR)", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableLogStretch, onCheckedChange = { enableLogStretch = it; settingsManager.isLogStretchEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            var enableUnsharpMask by remember { mutableStateOf(settingsManager.isUnsharpMaskEnabled) }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableUnsharpMask = !enableUnsharpMask; settingsManager.isUnsharpMaskEnabled = enableUnsharpMask }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Unsharp Mask (Crisp)", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableUnsharpMask, onCheckedChange = { enableUnsharpMask = it; settingsManager.isUnsharpMaskEnabled = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                            
                            var perceptionResolution by remember { mutableStateOf(settingsManager.perceptionResolution.toFloat()) }
                            Text("ML Kit Tensor Inference Res (Day & YUV): ${perceptionResolution.toInt()}px", color = Color.Gray, style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Slider(
                                value = perceptionResolution,
                                onValueChange = { perceptionResolution = it },
                                onValueChangeFinished = { settingsManager.perceptionResolution = perceptionResolution.toInt() },
                                valueRange = 160f..4000f,
                                steps = 239,
                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = Color.Gray,
                                    activeTrackColor = Color.Gray,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                            var smartVisionTargetResolution by remember { mutableStateOf(settingsManager.smartVisionTargetResolution.toFloat()) }
                            val localCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                            Text("Camera Hardware Res (Preview): ${smartVisionTargetResolution.toInt()}px", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Slider(
                                value = smartVisionTargetResolution,
                                onValueChange = { smartVisionTargetResolution = it },
                                onValueChangeFinished = { 
                                    settingsManager.smartVisionTargetResolution = smartVisionTargetResolution.toInt() 
                                    localCoroutineScope.launch {
                                        kotlinx.coroutines.delay(500)
                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            android.widget.Toast.makeText(this@ObjectDetectorStudioActivity, "Aplikuji rozlišení kamery...", android.widget.Toast.LENGTH_SHORT).show()
                                            recreate()
                                        }
                                    }
                                },
                                valueRange = 160f..4000f,
                                steps = 239,
                                modifier = Modifier.fillMaxWidth().height(32.dp),
                                colors = androidx.compose.material3.SliderDefaults.colors(
                                    thumbColor = Color(0xFF00FFCC),
                                    activeTrackColor = Color(0xFF00FFCC),
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                            
                            Text("Tactical System", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)

                            Row(modifier = Modifier.fillMaxWidth().clickable { showTacticalHud.value = !showTacticalHud.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Target Lock HUD", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showTacticalHud.value, onCheckedChange = { showTacticalHud.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }
                            Row(modifier = Modifier.fillMaxWidth().clickable { showTacticalTrace.value = !showTacticalTrace.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Motion Trace", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showTacticalTrace.value, onCheckedChange = { showTacticalTrace.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }
                            Row(modifier = Modifier.fillMaxWidth().clickable { showTacticalVector.value = !showTacticalVector.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Lead Indicator", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = showTacticalVector.value, onCheckedChange = { showTacticalVector.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }
                            Row(modifier = Modifier.fillMaxWidth().clickable { enableDynamicPipZoom.value = !enableDynamicPipZoom.value }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Dynamic PiP Zoom", color = Color.White, fontSize = 14.sp)
                                Checkbox(checked = enableDynamicPipZoom.value, onCheckedChange = { enableDynamicPipZoom.value = it }, colors = CheckboxDefaults.colors(checkedColor = Color(0xFF00FFCC), checkmarkColor = Color.Black))
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))

                            Text("Tracking Engine", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)

                            var selectedPluginId by remember { mutableStateOf(settingsManager.activeVisionPluginId) }

                            data class PluginEntry(val id: String, val name: String)
                            val availablePlugins = remember { listOf(
                                PluginEntry(com.example.llama.agent.vision.plugins.VisionPluginManager.RAW_BYPASS_ID, "OFF (RAW)"),
                                PluginEntry("tracker_iou_edge", "Nativ (Kalman + IoU)"),
                                PluginEntry("tracker_dkf_edge", "DKF Edge (8-State)"),
                                PluginEntry("tracker_telescope_dkf", "Telescope DKF (Anti-Jitter)"),
                                PluginEntry("tracker_oc_sort_pro", "OC-SORT (Anti-Drift)"),
                                PluginEntry("tracker_kinematic_telemetry_2026", "Kinematic Telemetry (CA)"),
                                PluginEntry("tracker_ballistic_predict", "Ballistic Predictor (1.0s)"),
                                PluginEntry("tracker_swarm_analyzer", "Swarm Analyzer"),
                                PluginEntry("tracker_threat_anomaly", "Threat Anomaly"),
                                PluginEntry("tracker_spatial_heatmap", "Spatial Heatmap"),
                                PluginEntry("tracker_ttc_eval", "TTC Impact Warning"),
                                PluginEntry("tracker_fov_exit", "FOV Exit Warning"),
                                PluginEntry("tracker_blob_shape", "Blob Shape Analyzer"),
                                PluginEntry("tracker_physics_violation", "Physics Violation (UAP)")
                            ) }

                            val currentViewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
                            val customViewConfig = remember(currentViewConfig) {
                                object : androidx.compose.ui.platform.ViewConfiguration by currentViewConfig {
                                    override val longPressTimeoutMillis: Long = 500L
                                }
                            }
                            
                            androidx.compose.runtime.CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalViewConfiguration provides customViewConfig
                            ) {
                                availablePlugins.forEach { plugin ->
                                    val isActive = selectedPluginId == plugin.id
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(36.dp)
                                            .padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(if (isActive) Color(0xFF00FFCC) else Color.DarkGray)
                                            .pointerInput(Unit) {
                                                detectTapGestures(
                                                    onTap = {
                                                        settingsManager.activeVisionPluginId = plugin.id
                                                        selectedPluginId = plugin.id
                                                    },
                                                    onLongPress = {
                                                        onShowKalmanConfig()
                                                    }
                                                )
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            plugin.name,
                                            fontSize = 12.sp,
                                            color = if (isActive) Color.Black else Color.White
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            var cameraSourceState by remember { mutableStateOf(settingsManager.perceptionCameraSource) }
                            Button(
                                onClick = {
                                    val oldState = cameraSourceState
                                    cameraSourceState = (cameraSourceState + 1) % 2
                                    settingsManager.perceptionCameraSource = cameraSourceState
                                    if (oldState == 2) {
                                        val intent = android.content.Intent(context, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                                            action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
                                        }
                                        context.startService(intent)
                                    }
                                    cameraSelector = when(cameraSourceState) {
                                        1 -> CameraSelector.DEFAULT_FRONT_CAMERA
                                        else -> CameraSelector.DEFAULT_BACK_CAMERA
                                    }
                                    (context as? android.app.Activity)?.recreate()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White)
                            ) {
                                val sourceLabel = when(cameraSourceState) {
                                    0 -> "BACK"
                                    1 -> "FRONT"
                                    2 -> "SCREEN"
                                    else -> "UNKNOWN"
                                }
                                Text("⟲ Source: $sourceLabel")
                            }

                            Button(
                                onClick = onOpenAiHub,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC), contentColor = Color.Black)
                            ) {
                                Text("⚙ AI Hub Models")
                            }

                            androidx.compose.material3.HorizontalDivider(color = Color.DarkGray, modifier = Modifier.padding(vertical = 4.dp))
                            
                            // HUD Theme Expander
                            var isHudThemeExpanded by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isHudThemeExpanded = !isHudThemeExpanded }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("HUD Theme", color = Color(0xFF00FFCC), style = MaterialTheme.typography.titleMedium)
                                Text(if (isHudThemeExpanded) "▲" else "▼", color = Color.White, fontSize = 14.sp)
                            }
                            
                            AnimatedVisibility(visible = isHudThemeExpanded) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    com.example.llama.agent.ui.studio.hud.HudTheme.values().forEach { theme ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { currentHudTheme.value = theme }
                                                .padding(vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            androidx.compose.material3.RadioButton(
                                                selected = (currentHudTheme.value == theme),
                                                onClick = { currentHudTheme.value = theme },
                                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = theme.crosshairColor)
                                            )
                                            Text(theme.displayName, color = Color.White, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(56.dp).background(Color.Transparent)
            ) {
                Text(if (expanded) "▼" else "❖", fontSize = 24.sp, color = Color.White)
            }
        }
    }

    private fun handleCanvasTap(tapOffset: Offset, canvasSize: androidx.compose.ui.unit.IntSize, detections: List<Detection>) {
        val normX = tapOffset.x / canvasSize.width
        val normY = tapOffset.y / canvasSize.height

        val hit = detections.find {
            normX >= it.rect.left && normX <= it.rect.right &&
            normY >= it.rect.top && normY <= it.rect.bottom
        }

        if (hit != null) {
            val label = hit.label
            if (pipMode.value == PipMode.MANUAL) {
                if (manualPipTargets.size < 10) {
                    manualPipTargets.add(ManualPipTarget(id = label))
                }
            } else {
                lockedTargetId.value = label
                Log.i(TAG, "🎯 Cíl uzamčen: $label")

                com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                    com.example.llama.agent.perception.OpticalLockEvent(
                        normalizedBox = hit.rect,
                        label = label,
                        action = com.example.llama.agent.perception.LockAction.LOCK
                    )
                )

                // Transform and Lock AF/AE using CameraCoordinator
                val currentZoom = cameraCoordinator.zoomController.currentZoomRatio
                cameraCoordinator.lockOnTargetController.lockOnTarget(
                    hit.rect.left, hit.rect.top, hit.rect.right, hit.rect.bottom,
                    currentZoom
                )
            }
        } else {
            lockedTargetId.value = null
            cameraCoordinator.lockOnTargetController.releaseLock()
            com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                com.example.llama.agent.perception.OpticalLockEvent(
                    action = com.example.llama.agent.perception.LockAction.UNLOCK
                )
            )
        }
    }

    private fun bindCamera(context: android.content.Context, lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView, lensInfo: com.example.llama.agent.vision.camera.strategy.CameraLensInfo?) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val targetRes = settingsManager.smartVisionTargetResolution
            val resolutionSelector = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                .setResolutionStrategy(
                    androidx.camera.core.resolutionselector.ResolutionStrategy(
                        Size(targetRes, targetRes),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                ).build()

            val preview = if (settingsManager.academicNightVisionMode) {
                null
            } else {
                Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            }

            val imageFormat = if (settingsManager.academicNightVisionMode) {
                ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888
            } else {
                ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(imageFormat)
                .setResolutionSelector(resolutionSelector)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { proxy -> processImageProxy(proxy) }
                }

            try {
                if (settingsManager.perceptionCameraSource == 2) {
                    cameraProvider.unbindAll()
                    if (!isScreenCaptureStarting) {
                        startScreenCapture(context)
                    }
                    return@addListener
                } else {
                    val stopIntent = android.content.Intent(context, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                        action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
                    }
                    context.startService(stopIntent)

                    val lensFacing = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    cameraCoordinator.bind(
                        cameraProvider = cameraProvider,
                        lifecycleOwner = lifecycleOwner,
                        preview = preview,
                        imageAnalysis = imageAnalyzer,
                        lensFacing = lensFacing,
                        lensInfo = lensInfo
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun startScreenCapture(context: android.content.Context) {
        if (isScreenCaptureStarting) return
        isScreenCaptureStarting = true

        val startService = {
            val intent = android.content.Intent(context, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_START_SYSTEM_VIDEO
                val metrics = context.resources.displayMetrics
                putExtra("width", metrics.widthPixels)
                putExtra("height", metrics.heightPixels)
                putExtra("density", metrics.densityDpi)
            }
            context.startForegroundService(intent)

            // Po 1 sekundě povolíme další pokus (pro jistotu, kdyby service spadnul)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isScreenCaptureStarting = false
            }, 1000)
        }

        if (com.example.llama.agent.perception.TranscriptForegroundService.activeInterceptor?.hasActiveProjection == true) {
            startService()
        } else {
            com.example.llama.agent.perception.MediaProjectionPermissionActivity.start(context) { resultCode: Int, _: android.content.Intent? ->
                if (resultCode == android.app.Activity.RESULT_OK) {
                    startService()
                } else {
                    isScreenCaptureStarting = false
                }
            }
        }
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy) {
        val image = imageProxy.image
        if (image == null) {
            imageProxy.close()
            return
        }

        // FIX #1 PIPELINING:
        // Academic mode: extrahujeme YUV data OKAMZITE (< 1ms).
        // Inference bezi asynchronne v academicInferenceScope - kamera neni blokována.
        if (settingsManager.academicNightVisionMode) {
            if (imageProxy.format == android.graphics.ImageFormat.YUV_420_888) {
                val frame = com.example.llama.agent.vision.nightmode.AcademicYuvEngine.extractYuvData(imageProxy)
                if (frame != null) {
                    academicFrameChannel.trySend(frame)
                } else {
                    imageProxy.close()
                }
            } else {
                // Blokujeme RGBA průsak během přechodu (Day mode leak)
                imageProxy.close()
            }
            return
        }

        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            
            val sourceBitmap = imageProxy.toBitmap()
            imageProxy.close()

            // 🔴 START: S-Mode Auto-ISO Regulátor (OPTIMIZOVÁNO PROTI PŘEHŘÍVÁNÍ) 🔴
            // Lazy load - vykoná se pouze pokud je zapnutý S-Mode a PRO Mode
            if (isShutterPriorityMode.value && isProModeEnabled.value) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastIsoUpdateMs > sModeUpdateIntervalMs.value) {
                    lastIsoUpdateMs = nowMs

                    // Výpočet probíhá na sourceBitmap bez alokací objektů
                    val luminance = luminanceAnalyzer.calculateAverageLuminanceFast(sourceBitmap)
                    currentLuminance.value = luminance

                    val targetLuminance = 110
                    val error = targetLuminance - luminance
                    if (kotlin.math.abs(error) > sModeDeadband.value) {
                        val currentIso = proIsoValue.value
                        val newIso = (currentIso + (error * sModePComponent.value)).coerceIn(50, 3200)
                        if (newIso != currentIso) {
                            proIsoValue.value = newIso
                            pushProSettingsToHardware()
                        }
                    }
                }
            }
            // 🔴 END: S-Mode Regulátor 🔴

            val previousRotated = rotatedBitmap

            val cropRect = imageProxy.cropRect
            val safeLeft = cropRect.left.coerceAtLeast(0)
            val safeTop = cropRect.top.coerceAtLeast(0)
            val safeWidth = cropRect.width().coerceAtMost(sourceBitmap.width - safeLeft).coerceAtLeast(1)
            val safeHeight = cropRect.height().coerceAtMost(sourceBitmap.height - safeTop).coerceAtLeast(1)

            // sourceBitmap je RGBA_8888, ale potřebujeme aplikovat rotaci a CropRect (Zoom)
            val matrix = android.graphics.Matrix()
            if (rotation != 0) {
                matrix.postRotate(rotation.toFloat())
            }

            // Vytvoříme finální bitmapu v jedné operaci - vyřízneme ZOOM a otočíme
            rotatedBitmap = android.graphics.Bitmap.createBitmap(
                sourceBitmap, safeLeft, safeTop, safeWidth, safeHeight, matrix, true
            )

            // Compose čte `rotatedBitmap` asynchronně. Nesmíme volat `previousRotated.recycle()`,
            // jinak by Compose mohlo vykreslovat recyklovaný Bitmap, což způsobuje pád nativeCanvas.drawBitmap.
            // Spolehneme se na Android Garbage Collector, aby staré bitmapy po použití smazal.

            if (rotatedBitmap != sourceBitmap) {
                sourceBitmap.recycle()
            }

            // Force Compose to redraw PiP window using the new frame
            frameCount.value++

            // Pokud běží academicNightVisionMode, inference se už odeslala asynchronně.
            // Nechceme odesílat TENTO RAW snímek do VisionSourceManager, aby nám nepřeblikával Main View (RGB šum).
            if (settingsManager.academicNightVisionMode) {
                return
            }

            // PerceptionEngine recykluje přijatý bitmap! Musíme vytvořit kopii.
            // Zmenšením na cílové Perception rozlišení z nastavení šetříme RAM a CPU,
            // ale dáváme enginu přesně takovou kvalitu, jakou vyžaduje aktivní model.
            val targetPerceptionRes = settingsManager.perceptionResolution.toFloat()
            val aspectRatioMode = settingsManager.perceptionAspectRatioMode

            val scale = if (aspectRatioMode == 2) {
                // CENTER CROP: zmenšit tak, aby menší strana odpovídala cílovému rozlišení
                targetPerceptionRes / Math.min(rotatedBitmap!!.width, rotatedBitmap!!.height)
            } else {
                // SQUASH / LETTERBOX: zmenšit tak, aby větší strana odpovídala (šetří RAM)
                targetPerceptionRes / Math.max(rotatedBitmap!!.width, rotatedBitmap!!.height)
            }

            val tbWidth = Math.round(rotatedBitmap!!.width * scale).coerceAtLeast(1)
            val tbHeight = Math.round(rotatedBitmap!!.height * scale).coerceAtLeast(1)

            // MUSÍME vytvořit novou instanci! PerceptionEngine ho následně zrecykluje.
            // LAB MODE: posíláme Lupa bitmapu v jejím přirozeném rozlišení.
            //   PerceptionEngine sám aplikuje SQUASH / LETTERBOX / CENTER CROP z nastavení
            //   (stejně jako s RAW rotatedBitmap) — žádné dvojité škálování, žádná deformace.
            // STD MODE: pre-škálujeme na tbWidth/tbHeight dle nastavení tensoru (původní logika).
            val lupaMode = settingsManager.lupaVisionDebugMode
            val engineBitmap: android.graphics.Bitmap

            val lupaBmp = lupaPluginManager.latestResult
            if (lupaMode && lupaBmp != null && !lupaBmp.isRecycled) {
                // LAB MODE ✅ — přesměruj výstup Lupy do Tensoru v přirozeném rozlišení
                // PerceptionEngine sám provede aspect-ratio scaling dle aspektRatioMode
                android.util.Log.d("LAB_MODE", "✅ Vstřikuji Lupa Bitmapu do Tensoru! (${lupaBmp.width}x${lupaBmp.height})")
                engineBitmap = lupaBmp.copy(lupaBmp.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
            } else {
                if (lupaMode) {
                    android.util.Log.w("LAB_MODE", "⚠️ LAB Mode ON, ale Lupa Bitmapa je NULL nebo RECYCLED — fallback na RAW")
                }
                // STANDARD MODE — pre-škálování na tbWidth/tbHeight (původní chování)
                engineBitmap = if (tbWidth == rotatedBitmap!!.width && tbHeight == rotatedBitmap!!.height) {
                    rotatedBitmap!!.copy(rotatedBitmap!!.config ?: android.graphics.Bitmap.Config.ARGB_8888, true)
                } else {
                    android.graphics.Bitmap.createScaledBitmap(rotatedBitmap!!, tbWidth, tbHeight, true)
                }
            }

            com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame?.invoke(engineBitmap, true)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba", e)
        } finally {
            imageProxy.close()
        }
    }

    /**
     * FIX #1: Spusti inference pipeline konzumenta.
     * Cte framy z Channel a spousti tezke zpracovani na Dispatchers.Default.
     * Kamera thread je volny okamzite po extractYuvData() - plny pipelining!
     */
    private fun startAcademicInferencePipeline() {
        academicInferenceScope.launch {
            for (frame in academicFrameChannel) {
                try {
                    val resultBitmap = com.example.llama.agent.vision.nightmode.AcademicYuvEngine
                        .runInferenceOnExtractedData(frame, this@ObjectDetectorStudioActivity, settingsManager)

                    if (resultBitmap != null) {
                        // Inference hotova - zpracujeme bitmapy na background vlakne, NIKOLIV na UI!
                        if (resultBitmap.isRecycled) continue
                        
                        // ZERO-ALLOCATION PIXEL COPY & ROTATION 
                        // C++ nyní rotuje a zapisuje přímo do ping-pong SW bitmapy (ARGB_8888).
                        // Žádné Canvas.drawBitmap(), žádné .copy()!
                        
                        // Předáváme ping-pong bitmapu do PerceptionEngine. 
                        // Nastavíme recycleSource = false, aby ji nezničil! (ZERO COPY DIRECT LINK)
                        val targetPerceptionRes = settingsManager.perceptionResolution.toFloat()
                        val aspectRatioMode = settingsManager.perceptionAspectRatioMode
                        val scale = if (aspectRatioMode == 2) {
                            targetPerceptionRes / Math.min(resultBitmap.width, resultBitmap.height)
                        } else {
                            targetPerceptionRes / Math.max(resultBitmap.width, resultBitmap.height)
                        }

                        if (scale < 1.0f) {
                            val tbWidth = Math.round(resultBitmap.width * scale).coerceAtLeast(1)
                            val tbHeight = Math.round(resultBitmap.height * scale).coerceAtLeast(1)
                            val scaledBitmap = android.graphics.Bitmap.createScaledBitmap(resultBitmap, tbWidth, tbHeight, true)
                            com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame?.invoke(scaledBitmap, true)
                        } else {
                            com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame?.invoke(resultBitmap, false)
                        }

                        // Nyni prepneme na UI vlakno POUZE pro ultra-rychly assign
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            // PIP okna vyžadují rotatedBitmap pro vykreslení
                            rotatedBitmap = resultBitmap
                            frameCount.value++
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Propagujeme zrušení coroutiny, není to chyba
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "AcademicYUV Pipeline inference error", e)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 1. Uvolneni Lupa CoroutineScope + plugin registru + output bitmap bufferu
        lupaPluginManager.releaseAll()

        // 1b. FIX #1: Ukonci inference pipeline (zavre channel + zrusi scope)
        academicFrameChannel.close()
        academicInferenceScope.cancel()

        // 2. Recyklace ZERO ALLOC PIPELINE bitmap bufferu (native heap)
        com.example.llama.agent.vision.nightmode.AcademicYuvEngine.release()
        reusableBitmap?.let { if (!it.isRecycled) it.recycle() }
        reusableBitmap = null
        rotatedBitmap?.let { if (!it.isRecycled) it.recycle() }
        rotatedBitmap = null

        // 3. Deregistrace VisionSourceManager callbacků (zabrání memory leaku přes lambda capture)
        //com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame = null
        //com.example.llama.agent.vision.VisionSourceManager.onStudioStateChanged = null
        //com.example.llama.agent.vision.VisionSourceManager.onResetTrackers = null

        // 4. Podmíněné zastavení Screen Capture service
        val settingsManager = com.example.llama.SettingsManager(this)
        if (!settingsManager.isSmartVisionCameraLiveEnabled && settingsManager.perceptionCameraSource == 2) {
            val intent = android.content.Intent(this, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
            }
            startService(intent)
        }

        // 5. Shutdown Camera Executor
        cameraExecutor.shutdown()
    }
}

/**
 * Ultra-Fast Zero-Allocation analyzátor jasu (Luminance).
 * Analyzuje scénu za < 0.5 ms díky bitovým operacím a čtení po blocích.
 */
class LuminanceAnalyzer {
    // Pre-alokovaný buffer, abychom nehltili Garbage Collector
    private var pixelBuffer = IntArray(0)

    fun calculateAverageLuminanceFast(bitmap: android.graphics.Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        val totalPixels = width * height

        // Změníme velikost bufferu jen při startu nebo změně rozlišení
        if (pixelBuffer.size < totalPixels) {
            pixelBuffer = IntArray(totalPixels)
        }

        // JEDEN jediný systémový skok (Bulk copy do pole v RAM)
        bitmap.getPixels(pixelBuffer, 0, width, 0, 0, width, height)

        var totalLuminance = 0L
        var count = 0

        // Stride 50 -> Přečteme jen každý 50. pixel. Přesnost jasu 99 %, zátěž 2 %.
        val stride = 50
        for (i in 0 until totalPixels step stride) {
            val pixel = pixelBuffer[i]

            // Rychlé bitové posuny pro zisk R, G, B bez tvorby objektů
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            // Rec 709 Luminance vzorec
            totalLuminance += ((r * 212 + g * 715 + b * 72) / 1000)
            count++
        }

        return if (count > 0) (totalLuminance / count).toInt() else 128
    }
}

@Composable
fun KalmanConfigModal(
    settingsManager: com.example.llama.SettingsManager,
    onDismiss: () -> Unit
) {
    var processNoise by remember { mutableStateOf(settingsManager.kalmanProcessNoise) }
    var measurementNoise by remember { mutableStateOf(settingsManager.kalmanMeasurementNoise) }
    var posWeight by remember { mutableStateOf(settingsManager.kalmanStdWeightPosition) }
    var velWeight by remember { mutableStateOf(settingsManager.kalmanStdWeightVelocity) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Kalman Filter Configuration", color = Color(0xFF00FFCC))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Process Noise (Q): ${String.format(java.util.Locale.US, "%.4f", processNoise)}", color = Color.White, fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = processNoise,
                    onValueChange = { processNoise = it },
                    valueRange = 0.001f..0.5f
                )

                Text("Measurement Noise (R): ${String.format(java.util.Locale.US, "%.2f", measurementNoise)}", color = Color.White, fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = measurementNoise,
                    onValueChange = { measurementNoise = it },
                    valueRange = 0.1f..20.0f
                )

                Text("Position Std Weight: ${String.format(java.util.Locale.US, "%.2f", posWeight)}", color = Color.White, fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = posWeight,
                    onValueChange = { posWeight = it },
                    valueRange = 0.1f..10.0f
                )

                Text("Velocity Std Weight: ${String.format(java.util.Locale.US, "%.2f", velWeight)}", color = Color.White, fontSize = 12.sp)
                androidx.compose.material3.Slider(
                    value = velWeight,
                    onValueChange = { velWeight = it },
                    valueRange = 0.1f..10.0f
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    settingsManager.applyKalmanConfigAtomically(processNoise, measurementNoise, posWeight, velWeight)
                    onDismiss()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC))
            ) {
                Text("Save & Hard Flush", color = Color.Black)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        },
        containerColor = Color(0xFF112233)
    )
}

/**
 * Pose Estimation Overlay — BlazePose 33-point topologie.
 * Vykresluje lidskou kinematickou kostru v Compose Canvas s adaptivním mapováním souřadnic.
 */
@Composable
fun PoseEstimationOverlay(
    skeletons: List<com.example.llama.agent.perception.Skeleton>,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier
) {
    // BlazePose 33-point topologie (MediaPipe PoseLandmarker)
    val bonePairs = remember {
        listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 7, 0 to 4, 4 to 5, 5 to 6, 6 to 8, 9 to 10,
            11 to 12, 11 to 23, 12 to 24, 23 to 24,
            11 to 13, 13 to 15, 15 to 17, 15 to 19, 15 to 21, 17 to 19,
            12 to 14, 14 to 16, 16 to 18, 16 to 20, 16 to 22, 18 to 20,
            23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
            24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32
        )
    }
    val minConfidence = 0.3f

    Canvas(modifier = modifier) {
        val matrix = com.example.llama.agent.vision.camera.math.ViewportCoordinateTransformer.computeFillCenterMatrix(
            imageW = sourceWidth.toFloat().coerceAtLeast(1f),
            imageH = sourceHeight.toFloat().coerceAtLeast(1f),
            viewW = size.width,
            viewH = size.height
        )
        val pt = FloatArray(2)

        skeletons.forEach { skeleton ->
            val pts = skeleton.keyPoints
            if (pts.size < 33) return@forEach

            bonePairs.forEach { (a, b) ->
                val p1 = pts[a]; val p2 = pts[b]
                if (p1.confidence > minConfidence && p2.confidence > minConfidence) {
                    pt[0] = p1.x; pt[1] = p1.y
                    matrix.mapPoints(pt)
                    val x1 = pt[0]; val y1 = pt[1]
                    pt[0] = p2.x; pt[1] = p2.y
                    matrix.mapPoints(pt)
                    drawLine(
                        color = Color(0xFF00FF00),
                        start = Offset(x1, y1),
                        end = Offset(pt[0], pt[1]),
                        strokeWidth = 3.dp.toPx()
                    )
                }
            }

            pts.forEach { p ->
                if (p.confidence > minConfidence) {
                    pt[0] = p.x; pt[1] = p.y
                    matrix.mapPoints(pt)
                    drawCircle(color = Color.Red, radius = 4.dp.toPx(), center = Offset(pt[0], pt[1]))
                }
            }
        }
    }
}

