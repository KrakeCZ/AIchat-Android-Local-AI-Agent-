package com.example.llama.agent.perception

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import android.os.Process

/**
 * ======================================================================== PERCEPTION ENGINE —
 * Enterprise PRO MediaPipe Hub (Soft Real-Time, Track B)
 * ========================================================================
 *
 * Orchestruje lehké ML modely (MediaPipe/ML Kit) na pozadí a zapisuje výsledky do
 * PerceptionStateRepository (Immutable Snapshots via StateFlow).
 *
 * Research Foundation:
 * - MediaPipe Tasks API (Google 2024): LIVE_STREAM async mode, supervisorScope pro izolaci selhání
 * paralelních úloh.
 * - Android Thermal API (API 29+): PowerManager.addThermalStatusListener() pro okamžité uspání při
 * THERMAL_STATUS_SEVERE.
 * - Zero-Copy Pipeline: ByteBufferImageBuilder + ImageProxy.getImage() .planes[0].buffer
 * (RGBA_8888), bez alokace Bitmapy.
 * - EdgeDAM (arxiv 2024/2026): Backpressure via AtomicBoolean — zahazování framů pokud předchozí
 * inference ještě běží.
 *
 * Thread Safety:
 * - Engine runs on Dispatchers.Default (CPU-bound ML inference)
 * - Writers: This engine only (background thread)
 * - Readers: AgentCoordinator (any thread, via PerceptionStateRepository.snapshot)
 * - Backpressure: AtomicBoolean prevents frame queue overflow
 *
 * Lifecycle:
 * - start() / stop() / destroy() — managed by AiAgentAccessibilityService
 * - Thermal & Screen Off hooks auto-pause/resume the perception loop
 *
 * IMPORTANT: This engine does NOT own the camera. It receives frames from VisionSourceManager
 * (Track B distributor) or on-demand requests. IrisMotionController (Track A) has absolute camera
 * priority.
 */
class PerceptionEngine(
        private val context: Context,
        private val repository: PerceptionStateRepository,
        private val settingsManager: com.example.llama.SettingsManager,
        private val odModelManager: OdModelManager? = null
) {

    companion object {
        private const val TAG = "PerceptionEngine"

        /**
         * Thermal status threshold at which perception MUST pause. THERMAL_STATUS_SEVERE = 3 (API
         * 29+) Below SEVERE (LIGHT, MODERATE) we reduce FPS but keep running.
         */
        private const val THERMAL_PAUSE_THRESHOLD = PowerManager.THERMAL_STATUS_SEVERE

        /**
         * Frame skip multiplier for thermal throttling. At THERMAL_STATUS_MODERATE, we process
         * every Nth frame. Runtime configurable via SettingsManager.perceptionThermalSkip.
         */
        private const val THERMAL_MODERATE_SKIP_DEFAULT = 3

        /**
         * Minimum confidence for ObjectDetector results. Below this threshold, detections are
         * discarded as noise. Runtime configurable via SettingsManager.perceptionOdConfidence.
         */
        private const val OD_MIN_CONFIDENCE_DEFAULT = 0.4f

        /**
         * ObjectDetector bundled model asset filename in /assets/. EfficientDet-Lite0: ~4MB,
         * 320x320 input, 80-class COCO. Používá se jako fallback pokud není importovaný model nebo
         * OdModelManager není k dispozici.
         */
        private const val OD_MODEL_ASSET = "efficientdet_lite0.tflite"

        /**
         * Maximum ObjectDetector results per frame before deduplication. Higher = more detections
         * but slower inference. 15 is sweet spot. Runtime configurable via
         * SettingsManager.perceptionOdMaxResults.
         */
        private const val MAX_OD_RESULTS_DEFAULT = 15

        /**
         * Timeout (ms) for ObjectDetector inference per frame. If inference exceeds this, coroutine
         * is cancelled (prevents hang). Runtime configurable via
         * SettingsManager.perceptionOdTimeoutMs.
         */
        private const val OD_TIMEOUT_MS_DEFAULT = 2000L

        /**
         * Timeout (ms) for ML Kit OCR inference per frame. Runtime configurable via
         * SettingsManager.perceptionOcrTimeoutMs.
         */
        private const val OCR_TIMEOUT_MS_DEFAULT = 3000L

        /**
         * Maximum OCR text length to store. Longer texts are truncated. Runtime configurable via
         * SettingsManager.perceptionOcrMaxLength.
         */
        private const val OCR_MAX_LENGTH_DEFAULT = 512

        /**
         * Maximum time (ms) between forced decay sweeps when no new frames arrive. Ensures entities
         * decay even if camera stops sending frames.
         */
        private const val DECAY_SWEEP_INTERVAL_MS = 3000L
    }

    // ==================== LIFECYCLE STATE ====================

    private val engineDispatcher = Executors.newFixedThreadPool(
        Runtime.getRuntime().availableProcessors().coerceAtMost(4),
        object : ThreadFactory {
            private val counter = AtomicInteger(1)
            override fun newThread(r: Runnable): Thread {
                return Thread(r, "PerceptionEngine-Worker-${counter.getAndIncrement()}").apply {
                    // Start thread, priority will be adjusted later via Settings
                }
            }
        }
    ).asCoroutineDispatcher()
    
    private val engineScope = CoroutineScope(engineDispatcher + SupervisorJob())

    /** Backpressure gate: prevents frame queue overflow (EdgeDAM pattern) */
    private val isProcessing = AtomicBoolean(false)

    /** Engine active flag (start/stop lifecycle) */
    @Volatile private var isRunning = false

    /** Thermal pause flag — set by PowerManager listener */
    @Volatile private var isThermalPaused = false

    /** Screen off pause flag — set by BroadcastReceiver */
    @Volatile private var isScreenOff = false

    /** Frame counter for thermal-based frame skipping */
    private val frameCounter = AtomicInteger(0)

    /** Current thermal status (for graduated response) */
    @Volatile private var currentThermalStatus = PowerManager.THERMAL_STATUS_NONE

    /** Timestamp of last decay sweep */
    @Volatile private var lastDecaySweepMs = 0L

    /** Previous entity count for SceneChange detection */
    @Volatile private var previousEntityCount = 0

    // ==================== DIAGNOSTICS (Inference Profiling) ====================

    /** Total frames successfully processed (not dropped) */
    private val totalFramesProcessed = AtomicLong(0L)

    /** Cumulative ObjectDetector inference time (ms) for averaging */
    private val totalOdInferenceMs = AtomicLong(0L)

    /** Cumulative OCR inference time (ms) for averaging */
    private val totalOcrInferenceMs = AtomicLong(0L)

    /** Count of ObjectDetector inference calls (for avg calculation) */
    private val odInferenceCount = AtomicLong(0L)

    /** Count of OCR inference calls (for avg calculation) */
    private val ocrInferenceCount = AtomicLong(0L)

    // ==================== ML MODEL INSTANCES ====================

    /**
     * MediaPipe ObjectDetector (lazy init). Uses IMAGE mode (synchronous, single-frame) — safe for
     * coroutine dispatch. Model: EfficientDet-Lite0 (COCO 25 classes). Null if model file not found
     * in assets.
     */
    private var objectDetector: ObjectDetector? = null

    /** LiteRT Interpreter pro RAW_SSD nebo RAW_UNKNOWN modely bez MediaPipe metadat. */
    private var rawInterpreter: org.tensorflow.lite.Interpreter? = null

    /** Dynamické štítky pro RAW modely načtené z odpovídajícího .txt souboru */
    private var customLabels: List<String>? = null

    /** Typ právě načteného modelu. Určuje inference path. */
    @Volatile private var activeModelType: OdModelType = OdModelType.MEDIAPIPE
    
    @Volatile private var cachedVisionProfile: com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile? = null
    @Volatile private var cachedTensorProfile: com.example.llama.agent.vision.tensor.TensorProfile? = null
    @Volatile private var cachedInputBuffer: ByteBuffer? = null
    
    /** Zda došlo k chybě na GPU a máme se trvale (pro tento model) přepnout na CPU. */
    @Volatile private var autoFallbackToCpu = false
    
    // --- ZERO ALLOC PIPELINE CACHES ---
    @Volatile private var cachedResizedBitmap: android.graphics.Bitmap? = null
    @Volatile private var cachedIntValues = IntArray(0)

    @Volatile private var odInitAttempted = false
    @Volatile private var lastLoadedOdModelId: String? = null

    /** ML Kit TextRecognizer (lazy init). Latin script on-device (bundled, no network). */
    private var textRecognizer: TextRecognizer? = null
    @Volatile private var ocrInitAttempted = false

    /** Samostatný modul pro BlazePose Pose Estimation — nezávislý na OD/OCR workerech. */
    private val poseLandmarkerEngine by lazy { PoseLandmarkerEngine(context, settingsManager) }

    /**
     * Face count bridge (Fáze 3.3). Set externally by IrisMotionController/FaceLandmarkerEngine via
     * updateFaceCount(). Atomic for thread safety.
     */
    private val currentFaceCount = AtomicInteger(0)

    // ==================== ORCHESTRÁTORY ====================
    val pluginManager = com.example.llama.agent.vision.plugins.VisionPluginManager()
    val nightModeManager = com.example.llama.agent.vision.nightmode.NightModePluginManager()
    
    /**
     * Zero-allocation telemetrie Confidence skóre posledního framu.
     * Čtení z UI vlákna je thread-safe (AtomicReference).
     * UI čte přímo bez StateFlow/LiveData (nulová GC alokace na UI vlákně).
     */
    val lastAverageConfidence = java.util.concurrent.atomic.AtomicReference<Float>(0f)
    
    // Nativní matematický CPU tracker (SAD) pro absolutní zámek na pixely
    private val opticalTracker = com.example.llama.agent.vision.tracking.OpticalTemplateTracker()
    @Volatile private var pendingOpticalLock: android.graphics.RectF? = null
    @Volatile private var lockedLabel: String? = null

    init {
        // Registrace všech dostupných tracking pluginů
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.EdgeTrackerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.DkfEdgeTrackerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.OcSortTrackerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.TelescopeKalmanTrackerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.TelemetryProPlugin())
        
        // Nové Kinematics a Vectors pluginy
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.BallisticPredictorPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.SwarmAnalyzerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.ThreatAnomalyPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.SpatialHeatmapPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.TtcEvaluatorPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.FovTrackerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.NewObjectAlerterPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.TrajectoryClassifierPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.MotionSaliencyPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.BlobShapeAnalyzerPlugin())
        pluginManager.registerPlugin(com.example.llama.agent.vision.plugins.impl.PhysicsViolationPlugin(normalizeBy = 640f))

        // Registrace dostupných Night Mode pluginů (Lazy Init v manageru)
        nightModeManager.registerPlugin(com.example.llama.agent.vision.nightmode.plugins.FastRetinexPlugin())
        // TFLite plugin je inicializován se SettingsManagerem, takže se nahraje/přenahraje dynamicky.
        nightModeManager.registerPlugin(com.example.llama.agent.vision.nightmode.plugins.LiteIETflitePlugin(context, settingsManager))

        // Načtení perzistentní konfigurace (default = Nativ EdgeTracker)
        val savedPluginId = settingsManager.activeVisionPluginId
        pluginManager.activatePlugin(savedPluginId)

        engineScope.launch {
            PerceptionEventBus.events.collect { event ->
                if (event is OpticalLockEvent) {
                    if (event.action == LockAction.LOCK && event.normalizedBox != null) {
                        pendingOpticalLock = event.normalizedBox
                        lockedLabel = event.label
                    } else {
                        opticalTracker.reset()
                        pendingOpticalLock = null
                        lockedLabel = null
                    }
                }
            }
        }
        
        engineScope.launch {
            PerceptionEventBus.configEvents.collect { event ->
                if (event is ReloadNightModeModelEvent) {
                    nightModeManager.reloadActivePlugin()
                }
            }
        }
    }

    // ==================== THERMAL MANAGEMENT ====================
    
    /** Resetuje vnitřní stav všech sledovacích pluginů (vymaže paměť, vynuluje ID). */
    fun resetTrackers() {
        pluginManager.resetActive()
        Log.i(TAG, "🧹 Trackovací subsystém resetován (paměť uvolněna, ID=1)")
    }

    /**
     * PowerManager Thermal Listener (API 29+). Implements graduated response:
     * - NONE/LIGHT: Full speed
     * - MODERATE: Frame skipping (every 3rd frame)
     * - SEVERE+: Complete pause of perception loop
     */
    private val thermalListener: Any? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                PowerManager.OnThermalStatusChangedListener { status ->
                    currentThermalStatus = status
                    val gate1 = settingsManager.perceptionThermalGate1
                    when {
                        gate1 != 99 && status >= gate1 -> {
                            if (!isThermalPaused) {
                                isThermalPaused = true
                                Log.w(TAG, "🌡️ THERMAL GATE 1 ($status >= $gate1) — Perception PAUSED")
                                PerceptionEventBus.tryEmit(
                                        PerceptionLifecycleEvent(
                                                state = EngineState.THERMAL_PAUSED,
                                                reason = "Thermal status: $status"
                                        )
                                )
                            }
                        }
                        else -> {
                            if (isThermalPaused) {
                                isThermalPaused = false
                                Log.i(TAG, "🌡️ Thermal recovered ($status < $gate1) — Perception RESUMED")
                                PerceptionEventBus.tryEmit(
                                        PerceptionLifecycleEvent(
                                                state = EngineState.THERMAL_RESUMED,
                                                reason = "Thermal recovered: $status"
                                        )
                                )
                            }
                        }
                    }
                }
            } else null

    // ==================== SCREEN ON/OFF MANAGEMENT ====================

    /**
     * BroadcastReceiver for screen on/off events. When screen is off, perception loop is paused to
     * save battery. IrisMotionController (Track A) handles its own screen lifecycle.
     */
    private val screenReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            isScreenOff = true
                            // Clear perception memory — stale data after screen off
                            repository.clear()
                            Log.i(TAG, "📴 Screen OFF — Perception PAUSED, memory cleared")
                            PerceptionEventBus.tryEmit(
                                    PerceptionLifecycleEvent(
                                            state = EngineState.SCREEN_OFF_PAUSED,
                                            reason = "Screen turned off"
                                    )
                            )
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            isScreenOff = false
                            Log.i(TAG, "📱 Screen ON — Perception RESUMED")
                            PerceptionEventBus.tryEmit(
                                    PerceptionLifecycleEvent(
                                            state = EngineState.SCREEN_ON_RESUMED,
                                            reason = "Screen turned on"
                                    )
                            )
                        }
                    }
                }
            }

    // ==================== PUBLIC API ====================

    /**
     * Start the perception engine. Registers thermal listener and screen receiver. Idempotent:
     * double-call produces no error.
     */
    fun start() {
        if (isRunning) {
            Log.d(TAG, "Engine already running — skipping start")
            return
        }

        isRunning = true
        lastDecaySweepMs = System.currentTimeMillis()

        // Register thermal listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.addThermalStatusListener(
                        thermalListener as PowerManager.OnThermalStatusChangedListener
                )
                Log.i(TAG, "🌡️ Thermal listener registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register thermal listener", e)
            }
        }

        // Register screen on/off receiver
        try {
            val filter =
                    IntentFilter().apply {
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
                    }
            context.registerReceiver(screenReceiver, filter)
            Log.i(TAG, "📱 Screen receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register screen receiver", e)
        }

        Log.i(TAG, "✅ PerceptionEngine started")
        PerceptionEventBus.tryEmit(
                PerceptionLifecycleEvent(state = EngineState.STARTED, reason = "Manual start")
        )
    }

    /**
     * Stop the perception engine. Unregisters listeners, clears state, but keeps scope alive for
     * restart. Idempotent.
     */
    fun stop() {
        if (!isRunning) return

        isRunning = false
        com.example.llama.agent.vision.camera.math.FrameDifferencePreProcessor.reset()

        // Unregister thermal listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && thermalListener != null) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.removeThermalStatusListener(
                        thermalListener as PowerManager.OnThermalStatusChangedListener
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister thermal listener", e)
            }
        }

        // Unregister screen receiver
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
            // Receiver might not be registered — safe to ignore
            Log.v(TAG, "Screen receiver was not registered or already unregistered")
        }

        repository.clear()
        previousEntityCount = 0
        poseLandmarkerEngine.unload()
        Log.i(TAG, "🛑 PerceptionEngine stopped")
        PerceptionEventBus.tryEmit(
                PerceptionLifecycleEvent(state = EngineState.STOPPED, reason = "Manual stop")
        )
    }

    /** Full destruction — cancels scope, prevents restart. Call only from Service.onDestroy(). */
    fun destroy() {
        stop()
        // Close ML model instances to free native memory
        try {
            objectDetector?.close()
        } catch (_: Exception) {}
        try {
            rawInterpreter?.close()
        } catch (_: Exception) {}
        try {
            textRecognizer?.close()
        } catch (_: Exception) {}
        objectDetector = null
        rawInterpreter = null
        textRecognizer = null
        odInitAttempted = false
        lastLoadedOdModelId = null
        cachedVisionProfile = null
        cachedTensorProfile = null
        ocrInitAttempted = false
        poseLandmarkerEngine.destroy()
        engineScope.cancel()
        Log.i(TAG, "💀 PerceptionEngine destroyed")
    }

    /**
     * Hot-Swap: Bezpečné přepnutí OD modelu za běhu (Blue-Green pattern).
     *
     * Vyvolávat z AiAgentAccessibilityService po aktivaci nového modelu v SettingsActivity.
     * Resetuje inicializační flag — následující frame automaticky spustí lazy init nového modelu.
     * Stará instance je zavřena OKAMŽITĚ (uvolnění NPU/GPU paměti).
     */
    fun reloadObjectDetector() {
        try {
            objectDetector?.close()
        } catch (_: Exception) {}
        try {
            rawInterpreter?.close()
        } catch (_: Exception) {}
        objectDetector = null
        rawInterpreter = null
        odInitAttempted = false
        Log.i(TAG, "🔄 OD model reset — nový model bude inicializován na dalším framu")
    }

    /**
     * PRIMARY ENTRY POINT: Process a new frame from VisionSourceManager (Track B).
     *
     * This method is called from the camera executor thread. It implements:
     * 1. Lifecycle checks (running, thermal, screen)
     * 2. Backpressure via AtomicBoolean (drop frame if previous still processing)
     * 3. Thermal-based frame skipping (graduated response)
     * 4. Parallel ML inference via supervisorScope
     * 5. Decay sweep on repository
     *
     * @param bitmap Downscaled camera frame. This method takes ownership
     * ```
     *               and will recycle it after processing.
     * ```
     */
    fun onNewFrame(bitmap: Bitmap, shouldRecycle: Boolean = true) {
        Log.d("TEST", "✅ Frame přijat: ${bitmap.width}x${bitmap.height}")
        // Gate 1: Lifecycle check
        if (!isRunning || isThermalPaused || isScreenOff) {
            if (shouldRecycle) bitmap.recycle()
            return
        }

        // Gate 2: Thermal-based frame skipping (MODERATE = every Nth frame)
        val gate2 = settingsManager.perceptionThermalGate2
        if (gate2 != 99 && currentThermalStatus >= gate2) {
            val count = frameCounter.incrementAndGet()
            if (count % settingsManager.perceptionThermalSkip != 0) {
                if (shouldRecycle) bitmap.recycle()
                return
            }
        }

        // Gate 3: Backpressure — drop frame if previous inference still running
        if (!isProcessing.compareAndSet(false, true)) {
            if (shouldRecycle) bitmap.recycle()
            return
        }

        engineScope.launch {
            // Aplikace dynamické priority vlákna z nastavení
            try {
                android.os.Process.setThreadPriority(settingsManager.perceptionThreadPriority)
            } catch (_: Exception) {}

            try {
                // --- Night Mode (Low-Light Enhancement) ---
                // Asynchronní singleton volání, které neblokuje UI a drží stabilní framerate.
                // Pokud je noční režim zapnut, prosvětlí obraz před odesláním do náhledu a do detekce.
                val processedBitmap = if ((settingsManager.isNightModeEnabled || settingsManager.academicNightVisionMode) && !settingsManager.academicNightVisionMode) {
                    // Hot-Swap inicializace/aktivace z prefs
                    val desiredNightPluginId = settingsManager.activeNightModePluginId
                    nightModeManager.activatePlugin(desiredNightPluginId)
                    nightModeManager.enhanceAsync(bitmap)
                } else {
                    if (!settingsManager.academicNightVisionMode) {
                        nightModeManager.activatePlugin(null) // Vypne a uvolní z paměti
                    }
                    bitmap
                }

                // --- Camera Preview (Zero-Leak Lazy Feature) ---
                if (settingsManager.isCameraPreviewEnabled) {
                    try {
                        // Vytvoříme malý zmenšený bitmap (rychlá operace pro preview)
                        val ratio = processedBitmap.height.toFloat() / processedBitmap.width.toFloat()
                        val preview =
                                Bitmap.createScaledBitmap(processedBitmap, 250, (250 * ratio).toInt(), true)
                        PerceptionEventBus.tryEmit(CameraPreviewEvent(preview))
                    } catch (e: Exception) {
                        Log.e(TAG, "Chyba při tvorbě preview bitmapy: ${e.message}")
                    }
                }

                if (settingsManager.isNightModeEnabled || settingsManager.academicNightVisionMode) {
                    if (settingsManager.academicNightVisionMode) {
                        // V Akademickém módu je obraz dostupný přímo přes 'rotatedBitmap' v UI (přes Zero-Alloc pipeline).
                        // Zde NESMÍME dělat žádnou kopii, jinak ucpeme paměť a shodíme FPS na 1-3!
                        PerceptionEventBus.tryEmit(com.example.llama.agent.perception.NightModePreviewEvent(null))
                    } else if (processedBitmap !== bitmap) {
                        // Ve standardním módu posíláme výsledek z NightModeManageru (RGB Pipeline)
                        PerceptionEventBus.tryEmit(com.example.llama.agent.perception.NightModePreviewEvent(processedBitmap))
                    } else {
                        PerceptionEventBus.tryEmit(com.example.llama.agent.perception.NightModePreviewEvent(null))
                    }
                } else {
                    PerceptionEventBus.tryEmit(com.example.llama.agent.perception.NightModePreviewEvent(null))
                }

                // Run ML inference workers in parallel with failure isolation
                supervisorScope {
                    // Worker 1: Object Detection (MediaPipe, Fáze 3.1)
                    val objectsDeferred =
                            if (settingsManager.isObjectDetectionEnabled) {
                                async { runObjectDetection(processedBitmap) }
                            } else null

                    // Worker 2: OCR (ML Kit, Fáze 3.2)
                    // [ON-DEMAND] OCR běží POUZE pokud uživatel zapnul OCR přes Omni-Control.
                    // Jinak se worker přeskočí — šetří CPU, RAM a baterii (MoE Tier 2).
                    val textDeferred =
                            if (settingsManager.isOcrEnabled) {
                                async { runOCR(processedBitmap) }
                            } else null

                    // Worker 3: Face count bridge (Fáze 3.3)
                    // Data comes from IrisMotionController via updateFaceCount()
                    val faceCount = currentFaceCount.get()

                    // Worker 4: Pose Estimation (MediaPipe PoseLandmarker, BlazePose 33) — nezávislý na OD/OCR.
                    val poseDeferred =
                        if (settingsManager.isPoseEstimationEnabled) {
                            async { poseLandmarkerEngine.detect(processedBitmap) }
                        } else null

                    // Await parallel results (supervisorScope isolates failures)
                    val odResult = objectsDeferred?.await()
                    val detectedEntities = odResult?.first ?: emptyList()
                    val uiDetections = odResult?.second ?: emptyList()
                    val detectedText = textDeferred?.await() ?: ""
                    val skeletons = poseDeferred?.await() ?: emptyList()

                    // Emit PoseUpdateEvent POUZE pokud je pose zapnutá (šetří EventBus buffer, žádné prázdné spamy)
                    if (settingsManager.isPoseEstimationEnabled) {
                        PerceptionEventBus.emit(
                            PoseUpdateEvent(
                                skeletons = skeletons,
                                sourceWidth = processedBitmap.width,
                                sourceHeight = processedBitmap.height
                            )
                        )
                    }

                    // Add face count as entity if faces detected
                    val allEntities = detectedEntities.toMutableList()
                    if (faceCount > 0) {
                        allEntities.add(
                                DetectedEntity(
                                        label = "person",
                                        category = EntityCategory.FACE,
                                        confidence = 0.9f, // High confidence from FaceLandmarker
                                        detectionCount = faceCount
                                )
                        )
                    }

                    // Fúze: Write merged results to Repository
                    repository.updateAndDecay(
                            newEntities = allEntities,
                            newText = detectedText,
                            source = UpdateSource.OBJECT_DETECTOR
                    )

                    // Event Bus: Emit VisionUpdateEvent for subscribers
                    val snapshot = repository.snapshot.value
                    val nowMs = System.currentTimeMillis()

                    // 🔴 Dynamický sync: hot-swap pluginu pokud uživatel kliknul na jiný v UI
                    val desiredPluginId = settingsManager.activeVisionPluginId
                    if (pluginManager.activePluginId != desiredPluginId) {
                        pluginManager.activatePlugin(desiredPluginId)
                    }
                    
                    // Aplikace filtr parametrů & případný Hard Flush (Option A)
                    val estimatorParams = com.example.llama.agent.vision.tracking.math.EstimatorParameters(
                        processNoise = settingsManager.kalmanProcessNoise,
                        measurementNoise = settingsManager.kalmanMeasurementNoise,
                        stdWeightPosition = settingsManager.kalmanStdWeightPosition,
                        stdWeightVelocity = settingsManager.kalmanStdWeightVelocity,
                        minAspectRatio = com.example.llama.agent.vision.tracking.math.DefaultEstimatorProfiles.DYNAMIC_DEFAULT.minAspectRatio,
                        minHeight = com.example.llama.agent.vision.tracking.math.DefaultEstimatorProfiles.DYNAMIC_DEFAULT.minHeight
                    )
                    pluginManager.updateEstimatorConfig(estimatorParams)

                    val baseDetections = pluginManager.executePipeline(uiDetections, nowMs).toMutableList()

                    // ==========================================
                    // OPTICAL ZERO-ALLOCATION TRACKER OVERRIDE
                    // ==========================================
                    val lockToProcess = pendingOpticalLock
                    if (lockToProcess != null) {
                        val absBox = android.graphics.RectF(
                            lockToProcess.left * bitmap.width,
                            lockToProcess.top * bitmap.height,
                            lockToProcess.right * bitmap.width,
                            lockToProcess.bottom * bitmap.height
                        )
                        opticalTracker.initTracker(bitmap, absBox)
                        pendingOpticalLock = null
                    }

                    val optRect = opticalTracker.track(bitmap)
                    if (optRect != null && opticalTracker.trackConfidence > 0.3f && lockedLabel != null) {
                        val normRect = android.graphics.RectF(
                            optRect.left / bitmap.width,
                            optRect.top / bitmap.height,
                            optRect.right / bitmap.width,
                            optRect.bottom / bitmap.height
                        )
                        
                        val existingIdx = baseDetections.indexOfFirst { it.label == lockedLabel }
                        val overrideDetection = com.example.llama.agent.vision.BoundingBoxView.Detection(
                            rect = normRect,
                            label = lockedLabel!!,
                            confidence = opticalTracker.trackConfidence,
                            colorIndex = if (existingIdx != -1) baseDetections[existingIdx].colorIndex else 0,
                            isPredictive = false
                        )

                        if (existingIdx != -1) {
                            baseDetections[existingIdx] = overrideDetection
                        } else {
                            baseDetections.add(overrideDetection)
                        }
                    }

                    val finalUiDetections = baseDetections

                    // Zero-allocation confidence telemetrie pro LAB MODE HUD
                    if (finalUiDetections.isNotEmpty()) {
                        val avgConf = finalUiDetections.map { it.confidence }.average().toFloat()
                        lastAverageConfidence.set(avgConf)
                    }

                    PerceptionEventBus.emit(
                            VisionUpdateEvent(
                                    snapshot = snapshot,
                                    objectCount = allEntities.size,
                                    hasText = detectedText.isNotBlank(),
                                    faceCount = faceCount,
                                    detections = finalUiDetections,
                                    sourceWidth = bitmap.width,
                                    sourceHeight = bitmap.height,
                                    updateSource = if (settingsManager.perceptionCameraSource == 2) com.example.llama.agent.perception.VisionUpdateSource.SCREEN_CAPTURE else com.example.llama.agent.perception.VisionUpdateSource.LIVE_CAM
                            )
                    )

                    // Scene Change Detection: Emit if entity count changed significantly
                    val currentCount = snapshot.entities.size
                    val delta = kotlin.math.abs(currentCount - previousEntityCount)
                    if (delta >= 3 || (previousEntityCount == 0 && currentCount > 0)) {
                        val newLabels =
                                snapshot.entities.filter { it.detectionCount <= 2 }.map { it.label }
                        PerceptionEventBus.emit(
                                SceneChangeEvent(
                                        previousObjectCount = previousEntityCount,
                                        currentObjectCount = currentCount,
                                        newEntities = newLabels
                                )
                        )
                    }
                    previousEntityCount = currentCount
                }

                // Periodic decay sweep (even without new detections)
                val now = System.currentTimeMillis()
                if (now - lastDecaySweepMs > DECAY_SWEEP_INTERVAL_MS) {
                    lastDecaySweepMs = now
                    repository.updateAndDecay(source = UpdateSource.MANUAL)
                }
            } catch (e: Exception) {
                // Zero Silent Failures: Log but don't crash the engine
                Log.e(TAG, "❌ Frame processing error (non-fatal)", e)
            } finally {
                // Always recycle bitmap and release backpressure gate (if allowed)
                if (shouldRecycle && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
                isProcessing.set(false)
            }
        }
    }

    // ==================== ML WORKER METHODS ====================

    /**
     * Worker 1 (Fáze 3.1): MediaPipe ObjectDetector (Robustified).
     *
     * Uses IMAGE mode (synchronous per-frame) with BitmapImageBuilder. Model: EfficientDet-Lite0
     * (COCO 25 classes, ~4MB, 320x320 input).
     *
     * Robustifikace (SOTA 2024/2025 best practices):
     * - Bitmap.isRecycled guard (prevents IllegalStateException)
     * - withTimeout (OD_TIMEOUT_MS) prevents inference hang
     * - Entity deduplication: same label detected multiple times → merge (count aggregation, max
     * confidence retained)
     * - Latency tracking: System.nanoTime() for diagnostics/profiling
     * - Bounded output: MAX_OD_RESULTS cap
     *
     * @param bitmap Camera frame (not recycled by this method)
     * @return List of deduplicated entities (empty if model not available)
     */
    private suspend fun runObjectDetection(bitmap: Bitmap): Pair<List<DetectedEntity>, List<com.example.llama.agent.vision.BoundingBoxView.Detection>> {
        // Guard: Bitmap may have been recycled by another thread
        if (bitmap.isRecycled) {
            Log.w(TAG, "⚠️ OD skipped — bitmap already recycled")
            return Pair(emptyList(), emptyList())
        }

        val currentModelId = settingsManager.activeOdModelId
        
        // Detekce změny aktivního modelu za běhu (např. přes AI Hub)
        if (odInitAttempted && lastLoadedOdModelId != currentModelId) {
            Log.i(TAG, "🔄 OD Model changed from $lastLoadedOdModelId to $currentModelId. Reloading...")
            objectDetector?.close()
            objectDetector = null
            rawInterpreter?.close()
            rawInterpreter = null
            odInitAttempted = false
            autoFallbackToCpu = false
            cachedVisionProfile = null
            cachedTensorProfile = null
            cachedInputBuffer = null
            cachedResizedBitmap?.recycle()
            cachedResizedBitmap = null
            // cachedIntValues si můžeme nechat alokované, zbytečně neničíme pole
        }

        // Lazy init (idempotent, single-attempt)
        if (!odInitAttempted) {
            odInitAttempted = true
            lastLoadedOdModelId = currentModelId
            try {
                val customModelPath: String? = odModelManager?.getActiveModelPath()
                activeModelType =
                        odModelManager?.getActiveRecord()?.modelType ?: OdModelType.MEDIAPIPE

                if (activeModelType == OdModelType.MEDIAPIPE) {
                    val baseOptionsBuilder = BaseOptions.builder()
                    if (customModelPath != null) {
                        Log.i(
                                TAG,
                                "📦 Načítám importovaný OD model (MediaPipe): $customModelPath"
                        )
                        val modelFile = File(customModelPath)
                        val modelBuffer: ByteBuffer =
                                FileChannel.open(modelFile.toPath()).use { ch ->
                                    val buf = ByteBuffer.allocateDirect(ch.size().toInt())
                                    ch.read(buf)
                                    buf.rewind()
                                    buf
                                }
                        baseOptionsBuilder.setModelAssetBuffer(modelBuffer)
                    } else {
                        Log.i(TAG, "📦 Načítám bundled OD model do filesDir (16KB mmap safe): $OD_MODEL_ASSET")
                        val modelFile = File(context.filesDir, OD_MODEL_ASSET)
                        if (!modelFile.exists()) {
                            context.assets.open(OD_MODEL_ASSET).use { input ->
                                modelFile.outputStream().use { output -> input.copyTo(output) }
                            }
                        }
                        val modelBuffer: ByteBuffer = FileInputStream(modelFile).use { stream ->
                            stream.channel.map(FileChannel.MapMode.READ_ONLY, 0L, modelFile.length())
                        }
                        baseOptionsBuilder.setModelAssetBuffer(modelBuffer)
                    }

                    // Aplikace akcelerace (GPU/CPU) pro MediaPipe
                    val acceleratorMode = settingsManager.perceptionAcceleratorMode
                    try {
                        if (autoFallbackToCpu) {
                            baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                            Log.i(TAG, "⚡ MediaPipe: Vynuceno CPU (Auto-fallback kvůli selhání GPU)")
                        } else {
                            when (acceleratorMode) {
                                "GPU" -> {
                                    baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.GPU)
                                    Log.i(TAG, "⚡ MediaPipe: Vynuceno GPU (dle uživatele)")
                                }
                                else -> {
                                    baseOptionsBuilder.setDelegate(com.google.mediapipe.tasks.core.Delegate.CPU)
                                    Log.i(TAG, "⚡ MediaPipe: Vynuceno CPU (dle uživatele)")
                                }
                                // MediaPipe Tasks Vision Java API nepodporuje NPU/NNAPI delegate.
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Chyba při nastavování MediaPipe delegáta: $acceleratorMode", e)
                    }

                    val baseOptions = baseOptionsBuilder.build()

                    val options =
                            ObjectDetector.ObjectDetectorOptions.builder()
                                    .setBaseOptions(baseOptions)
                                    .setMaxResults(settingsManager.perceptionOdMaxResults)
                                    .setScoreThreshold(settingsManager.perceptionOdConfidence)
                                    .build()
                    objectDetector = ObjectDetector.createFromOptions(context, options)
                    val modelLabel =
                            if (customModelPath != null) File(customModelPath).name
                            else OD_MODEL_ASSET
                    Log.i(
                            TAG,
                            "✅ ObjectDetector initialized ($modelLabel, maxResults=${settingsManager.perceptionOdMaxResults}, minConf=${settingsManager.perceptionOdConfidence})"
                    )
                } else {
                    // RAW_SSD nebo RAW_UNKNOWN -> LiteRT Interpreter
                    if (customModelPath != null) {
                        Log.i(TAG, "📦 Načítám importovaný OD model (LiteRT): $customModelPath")
                        val modelFile = File(customModelPath)
                        val modelBuffer: ByteBuffer =
                                FileChannel.open(modelFile.toPath()).use { ch ->
                                    val buf = ByteBuffer.allocateDirect(ch.size().toInt())
                                    ch.read(buf)
                                    buf.rewind()
                                    buf
                                }
                        
                        val options = org.tensorflow.lite.Interpreter.Options()
                        
                        // 1. Zpracování Accelerator Mode z nastavení
                        val acceleratorMode = settingsManager.perceptionAcceleratorMode
                        try {
                            when (acceleratorMode) {
                                "GPU" -> {
                                    val compatList = org.tensorflow.lite.gpu.CompatibilityList()
                                    if (compatList.isDelegateSupportedOnThisDevice) {
                                        options.addDelegate(org.tensorflow.lite.gpu.GpuDelegate(compatList.bestOptionsForThisDevice))
                                        Log.i(TAG, "⚡ LiteRT: Vynuceno GPU (dle uživatele)")
                                    } else {
                                        Log.w(TAG, "⚠️ LiteRT: GPU vynuceno, ale SoC ho nepodporuje! Jedu na CPU.")
                                    }
                                }
                                "NNAPI" -> {
                                    // ─────────────────────────────────────────────────────────
                                    // NNAPI MIGRATION NOTICE (Android 15+ / API 35+)
                                    // NnApiDelegate je deprecated od Android 15 a od Android 17
                                    // (API 37) je pristup k NPU blokovan systemem NPU Manager
                                    // bez manifest deklarace android.hardware.neural_processing_unit.
                                    //
                                    // Nahrada: LiteRT GpuDelegate (Adreno 750 na S24 Ultra) dosahuje
                                    // srovnatelneho nebo lepsiho vykonu nez NNAPI routing na stejnem SoC,
                                    // protoze GPU delegate obchazi NNAPI overhead vrstvu.
                                    //
                                    // Migrate: developer.android.com/ndk/guides/neuralnetworks/migration-guide
                                    // ─────────────────────────────────────────────────────────
                                    Log.w(TAG, "⚠️ LiteRT: NNAPI deprecated od Android 15 — automaticky presmerovan na GPU delegate (ekvivalentni vykon)")
                                    val compatList = org.tensorflow.lite.gpu.CompatibilityList()
                                    if (compatList.isDelegateSupportedOnThisDevice) {
                                        options.addDelegate(org.tensorflow.lite.gpu.GpuDelegate(compatList.bestOptionsForThisDevice))
                                        Log.i(TAG, "⚡ LiteRT: NNAPI→GPU delegate aktivovan (Adreno, CompatibilityList.bestOptions)")
                                    } else {
                                        // GPU neni dostupne (emulator, starsi SoC) → CPU fallback
                                        Log.w(TAG, "⚠️ LiteRT: GPU neni podporovano — CPU fallback (4 vlakna)")
                                    }
                                }

                                "AUTO" -> {
                                    val compatList = org.tensorflow.lite.gpu.CompatibilityList()
                                    if (compatList.isDelegateSupportedOnThisDevice) {
                                        options.addDelegate(org.tensorflow.lite.gpu.GpuDelegate(compatList.bestOptionsForThisDevice))
                                        Log.i(TAG, "⚡ LiteRT: AUTO režim -> Zvoleno GPU")
                                    } else {
                                        Log.i(TAG, "⚡ LiteRT: AUTO režim -> Zvoleno CPU")
                                    }
                                }
                                else -> {
                                    Log.i(TAG, "⚡ LiteRT: Čisté CPU zpracování (dle uživatele)")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "⚠️ Delegate init selhal. Fallback na čisté CPU. Chyba: ${e.message}")
                        }

                        // 2. Počet vláken z nastavení
                        options.setNumThreads(settingsManager.perceptionCpuThreads)
                        Log.i(TAG, "⚙️ LiteRT: Počet CPU vláken nastaven na: ${settingsManager.perceptionCpuThreads}")

                        rawInterpreter = org.tensorflow.lite.Interpreter(modelBuffer, options)
                        rawInterpreter?.allocateTensors()
                        
                        // 1. Pokus o extrakci label map z metadat .tflite souboru
                        try {
                            val metadataExtractor = org.tensorflow.lite.support.metadata.MetadataExtractor(modelBuffer)
                            if (metadataExtractor.hasMetadata()) {
                                val associatedFiles = metadataExtractor.associatedFileNames
                                val labelFileName = associatedFiles.firstOrNull { it.endsWith(".txt") }
                                if (labelFileName != null) {
                                    val labelInputStream = metadataExtractor.getAssociatedFile(labelFileName)
                                    customLabels = java.io.BufferedReader(java.io.InputStreamReader(labelInputStream)).useLines { it.toList() }
                                    Log.i(TAG, "✅ TFLite Metadata Extractor: Načteno ${customLabels?.size} štítků z vnitřního souboru '$labelFileName'")
                                } else {
                                    Log.w(TAG, "⚠️ TFLite Metadata: Model má metadata, ale žádný .txt soubor s labely.")
                                }
                            } else {
                                Log.w(TAG, "⚠️ TFLite Metadata: Model nemá zabudovaná metadata.")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "⚠️ TFLite Metadata chyba: ${e.message}")
                        }

                        // 2. Fallback na externí .txt soubor
                        if (customLabels == null) {
                            val labelFile = File(modelFile.parentFile, modelFile.nameWithoutExtension + ".txt")
                            if (labelFile.exists()) {
                                try {
                                    customLabels = labelFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                                    Log.i(TAG, "✅ Fallback: Načteno ${customLabels?.size} štítků z externího souboru ${labelFile.name}")
                                } catch (e: Exception) {
                                    Log.e(TAG, "⚠️ Nelze načíst štítky z externího ${labelFile.name}: ${e.message}")
                                }
                            } else {
                                Log.w(TAG, "⚠️ Externí soubor se štítky (${labelFile.name}) nenalezen. Bude použito fallback ID (obj_XX/class_XX).")
                                customLabels = null
                            }
                        }

                        Log.i(
                                TAG,
                                "✅ LiteRT Interpreter initialized ($customModelPath, type=$activeModelType)"
                        )
                    } else {
                        Log.w(TAG, "⚠️ RAW model type but no custom path. Mismatch detected.")
                        activeModelType = OdModelType.MEDIAPIPE // fallback logic
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ OD Model init failed: ${e.message}")
                objectDetector = null
                rawInterpreter = null
                cachedVisionProfile = null
                cachedTensorProfile = null
                cachedInputBuffer = null
                cachedResizedBitmap?.recycle()
                cachedResizedBitmap = null
            }
        }

        if (activeModelType != OdModelType.MEDIAPIPE && rawInterpreter != null) {
            return runRawSsdDetection(bitmap)
        }

        val detector = objectDetector ?: return Pair(emptyList(), emptyList())

        return try {
            // Timeout guard: prevents inference hang on slow/overheated SoC
            withTimeout(settingsManager.perceptionOdTimeoutMs) {
                val startNs = System.nanoTime()

                // Second recycled check (race condition between guard and actual use)
                if (bitmap.isRecycled) return@withTimeout Pair(emptyList(), emptyList())

                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = detector.detect(mpImage)

                val inputWidth = bitmap.width.toFloat()
                val inputHeight = bitmap.height.toFloat()
                val uiDetections = mutableListOf<com.example.llama.agent.vision.BoundingBoxView.Detection>()

                val rawEntities = mutableListOf<DetectedEntity>()
                val activeRecord = odModelManager?.getActiveRecord()
                val labelStrategy = com.example.llama.agent.vision.models.OdLabelStrategy.FALLBACK_COCO_80

                for (detection in result.detections()) {
                    val category = detection.categories().firstOrNull() ?: continue
                    val confidence = category.score()
                    
                    // Filtrujeme šum podle aktuálního nastavení prahu
                    if (confidence < settingsManager.perceptionOdConfidence) continue

                    // 1. Získáme jméno z MediaPipe metadat
                    // Pokud model nemá jména, použijeme náš OdLabelRegistry jako fallback
                    val rawLabel = category.categoryName()
                    val finalLabel = if (!rawLabel.isNullOrBlank()) {
                        rawLabel
                    } else {
                        com.example.llama.agent.vision.models.OdLabelRegistry.resolveLabel(
                            context = context,
                            strategy = labelStrategy,
                            classIndex = category.index(),
                            customLabelMap = customLabels
                        )
                    }.lowercase().trim()

                    // 2. Extrakce Bounding Boxu (MediaPipe vrací pixely vůči VSTUPNÍMU obrázku)
                    val rect = detection.boundingBox()
                    
                    // 3. Normalizace (0.0 - 1.0) s tvrdým limitem proti odletu mimo screen (Kalman stabilization)
                    val normLeft = (rect.left / inputWidth).coerceIn(0f, 1f)
                    val normTop = (rect.top / inputHeight).coerceIn(0f, 1f)
                    val normRight = (rect.right / inputWidth).coerceIn(0f, 1f)
                    val normBottom = (rect.bottom / inputHeight).coerceIn(0f, 1f)

                    val colorIndex = category.index() % com.example.llama.agent.vision.BoundingBoxView.COLORS.size

                    uiDetections.add(
                        com.example.llama.agent.vision.BoundingBoxView.Detection(
                            rect = android.graphics.RectF(normLeft, normTop, normRight, normBottom),
                            label = finalLabel,
                            confidence = confidence,
                            colorIndex = colorIndex
                        )
                    )
                    
                    rawEntities.add(
                        DetectedEntity(
                            label = finalLabel,
                            category = EntityCategory.OBJECT,
                            confidence = confidence,
                            detectionCount = 1
                        )
                    )
                }

                // Entity deduplication: merge same-label detections
                // (e.g., 3x "chair" detected → single entity with count=3, max confidence)
                val deduplicated =
                        rawEntities.groupBy { it.label }.map { (label, group) ->
                            DetectedEntity(
                                    label = label,
                                    category = EntityCategory.OBJECT,
                                    confidence = group.maxOf { it.confidence },
                                    detectionCount = group.size
                            )
                        }

                // Latency tracking for diagnostics
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                totalOdInferenceMs.addAndGet(elapsedMs)
                odInferenceCount.incrementAndGet()

                if (deduplicated.isNotEmpty()) {
                    Log.v(
                            TAG,
                            "🔍 OD: ${deduplicated.size} entities (${elapsedMs}ms) → ${deduplicated.joinToString { "${it.label}(${it.detectionCount})" }}"
                    )
                }

                val filteredUiDetections = com.example.llama.agent.vision.BoundingBoxView.filterDetections(
                    uiDetections,
                    settingsManager.perceptionOdConfidence.coerceAtLeast(0.30f)
                )

                Pair(deduplicated, filteredUiDetections)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(
                    TAG,
                    "⏱️ ObjectDetector timeout (>${settingsManager.perceptionOdTimeoutMs}ms) — frame skipped"
            )
            Pair(emptyList(), emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "ObjectDetector inference failed (non-fatal)", e)
            if (!autoFallbackToCpu && settingsManager.perceptionAcceleratorMode == "GPU" && e is com.google.mediapipe.framework.MediaPipeException) {
                Log.w(TAG, "♻️ GPU delegate selhal (MediaPipeException), aktivuji auto-fallback na CPU a resetuji OD model.")
                autoFallbackToCpu = true
                reloadObjectDetector()
            }
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Fallback inference cesta pro RAW_SSD a RAW_UNKNOWN modely. Nepoužívá MediaPipe, komunikuje
     * přímo s LiteRT Interpreterem. Očekává 4 výstupní tenzory: [boxes, classes, scores,
     * num_detections].
     */
    private suspend fun runRawSsdDetection(bitmap: Bitmap): Pair<List<DetectedEntity>, List<com.example.llama.agent.vision.BoundingBoxView.Detection>> {
        val interpreter = rawInterpreter ?: return Pair(emptyList(), emptyList())

        return try {
            withTimeout(settingsManager.perceptionOdTimeoutMs) {
                val startNs = System.nanoTime()

                // Získání nebo vytvoření profilu a parseru
                if (cachedVisionProfile == null || cachedTensorProfile == null) {
                    val activeModelPath = odModelManager?.getActiveModelPath()
                    cachedVisionProfile = activeModelPath?.let {
                        com.example.llama.agent.vision.models.TfliteVisionAnalyzer.analyzeModel(java.io.File(it))
                    } ?: com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile(
                        isValid = true,
                        taskType = com.example.llama.agent.vision.models.VisionTaskType.OBJECT_DETECTION,
                        labelStrategy = com.example.llama.agent.vision.models.OdLabelStrategy.FALLBACK_IMAGENET_1000,
                        inputWidth = 320,
                        inputHeight = 320,
                        classCount = 1000,
                        isQuantized = false
                    )
                    cachedTensorProfile = com.example.llama.agent.vision.tensor.TensorOrchestrator.getParser(interpreter, cachedVisionProfile!!, settingsManager.perceptionOdConfidence)
                }

                val profileData = cachedVisionProfile!!
                val tensorProfile = cachedTensorProfile!!

                // Extrakce vlastností vstupního tenzoru z profilu (zohledňuje NCHW/NHWC)
                val inputWidth = if (profileData.inputWidth > 0) profileData.inputWidth else 320
                val inputHeight = if (profileData.inputHeight > 0) profileData.inputHeight else 320

                val inputTensor = interpreter.getInputTensor(0)
                val isFloatModel = inputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
                val bytesPerChannel = if (isFloatModel) 4 else 1

                val mode = settingsManager.perceptionAspectRatioMode
                var offsetX = 0f
                var offsetY = 0f
                var scaleX = 1f
                var scaleY = 1f

                // 1. ZERO ALLOC: Znovupoužití resizedBitmap
                if (cachedResizedBitmap == null || cachedResizedBitmap!!.width != inputWidth || cachedResizedBitmap!!.height != inputHeight) {
                    cachedResizedBitmap?.recycle()
                    cachedResizedBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
                }
                val resizedBitmap = cachedResizedBitmap!!
                val canvas = android.graphics.Canvas(resizedBitmap)

                when (mode) {
                    0 -> {
                        // SQUASH (Fullscreen s deformací) ZERO-ALLOC
                        val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                        val dstRect = android.graphics.Rect(0, 0, inputWidth, inputHeight)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        // scale a offset zůstávají 1.0 / 0.0
                    }
                    1 -> {
                        // LETTERBOX (Zachování proporcí s černými pruhy)
                        val scale = Math.min(inputWidth.toFloat() / bitmap.width, inputHeight.toFloat() / bitmap.height)
                        val scaledWidth = Math.round(bitmap.width * scale)
                        val scaledHeight = Math.round(bitmap.height * scale)
                        val dx = (inputWidth - scaledWidth) / 2
                        val dy = (inputHeight - scaledHeight) / 2
                        
                        canvas.drawColor(android.graphics.Color.BLACK)
                        val matrix = android.graphics.Matrix()
                        matrix.postScale(scale, scale)
                        matrix.postTranslate(dx.toFloat(), dy.toFloat())
                        canvas.drawBitmap(bitmap, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        
                        scaleX = scaledWidth.toFloat() / inputWidth
                        scaleY = scaledHeight.toFloat() / inputHeight
                        offsetX = dx.toFloat() / inputWidth
                        offsetY = dy.toFloat() / inputHeight
                    }
                    2 -> {
                        // CENTER CROP (Přesný výřez zachovávající poměr stran tensoru)
                        val targetRatio = inputWidth.toFloat() / inputHeight.toFloat()
                        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                        
                        var cropWidth = bitmap.width
                        var cropHeight = bitmap.height
                        
                        if (sourceRatio > targetRatio) {
                            cropWidth = (bitmap.height * targetRatio).toInt()
                        } else {
                            cropHeight = (bitmap.width / targetRatio).toInt()
                        }
                        
                        val startX = (bitmap.width - cropWidth) / 2
                        val startY = (bitmap.height - cropHeight) / 2
                        
                        // ZERO ALLOC: Namísto 2x createBitmap kreslíme výřez rovnou na canvas
                        val srcRect = android.graphics.Rect(startX, startY, startX + cropWidth, startY + cropHeight)
                        val dstRect = android.graphics.Rect(0, 0, inputWidth, inputHeight)
                        canvas.drawBitmap(bitmap, srcRect, dstRect, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                        
                        scaleX = bitmap.width.toFloat() / cropWidth
                        scaleY = bitmap.height.toFloat() / cropHeight
                        offsetX = -startX.toFloat() / cropWidth
                        offsetY = -startY.toFloat() / cropHeight
                    }
                }

                val requiredCapacity = 1 * inputWidth * inputHeight * 3 * bytesPerChannel
                if (cachedInputBuffer == null || cachedInputBuffer!!.capacity() != requiredCapacity) {
                    cachedInputBuffer = ByteBuffer.allocateDirect(requiredCapacity).apply {
                        order(ByteOrder.nativeOrder())
                    }
                }
                val inputBuffer = cachedInputBuffer!!
                inputBuffer.clear()

                // 2. ZERO ALLOC: Znovupoužití pole pixelů
                val totalPixels = inputWidth * inputHeight
                if (cachedIntValues.size < totalPixels) {
                    cachedIntValues = IntArray(totalPixels)
                }
                val intValues = cachedIntValues

                resizedBitmap.getPixels(
                        intValues,
                        0,
                        resizedBitmap.width,
                        0,
                        0,
                        resizedBitmap.width,
                        resizedBitmap.height
                )
                
                // --- TRACK B MATHEMATICAL PRE-PROCESSORS ---
                if (settingsManager.isSkyModeEnabled) {
                    // 1. Frame Difference: motion amplification (volat PRVNÍ — na surové pixely)
                    com.example.llama.agent.vision.camera.math.FrameDifferencePreProcessor.processInPlace(
                        pixels    = intValues,
                        width     = inputWidth,
                        height    = inputHeight,
                        amplify   = 5.0f,
                        blendRatio = 0.40f
                    )
                    // 2. Unsharp Mask: vytáhne hrany výsledného motion signálu
                    com.example.llama.agent.vision.camera.math.FastUnsharpMask.processInPlace(
                        pixels  = intValues,
                        width   = inputWidth,
                        height  = inputHeight,
                        strength = 1.8f
                    )
                    // 3. Log Stretch: pouze noční režim (podmínka dle dalšího settingu)
                    if (settingsManager.isNightSkyModeEnabled) {
                        com.example.llama.agent.vision.camera.math.FastLogStretch.processInPlace(
                            pixels   = intValues,
                            width    = inputWidth,
                            height   = inputHeight,
                            strength = 2.5f
                        )
                    }
                    // 4. CLAHE: zvýšený clip limit pro oblohu
                    com.example.llama.agent.vision.camera.math.FastCLAHE.processInPlace(
                        pixels    = intValues,
                        width     = inputWidth,
                        height    = inputHeight,
                        clipLimit = 6.0f
                    )
                } else {
                    if (settingsManager.isLogStretchEnabled) {
                        com.example.llama.agent.vision.camera.math.FastLogStretch.processInPlace(intValues, inputWidth, inputHeight, 3.0f)
                    }
                    if (settingsManager.isUnsharpMaskEnabled) {
                        com.example.llama.agent.vision.camera.math.FastUnsharpMask.processInPlace(intValues, inputWidth, inputHeight, 1.5f)
                    }
                    if (settingsManager.isClahePreProcessorEnabled) {
                        com.example.llama.agent.vision.camera.math.FastCLAHE.processInPlace(intValues, inputWidth, inputHeight, 3.0f)
                    }
                }
                
                val channelSize = inputWidth * inputHeight
                if (profileData.isNchw) {
                    // SOTA NCHW (YOLOv8/v10, PyTorch): [R...][G...][B...]
                    if (isFloatModel) {
                        val floatArray = FloatArray(channelSize * 3)
                        for (i in 0 until channelSize) {
                            val valPixel = intValues[i]
                            floatArray[i] = ((valPixel shr 16) and 0xFF) / 255.0f
                            floatArray[channelSize + i] = ((valPixel shr 8) and 0xFF) / 255.0f
                            floatArray[2 * channelSize + i] = (valPixel and 0xFF) / 255.0f
                        }
                        inputBuffer.asFloatBuffer().put(floatArray)
                    } else {
                        val byteArray = ByteArray(channelSize * 3)
                        for (i in 0 until channelSize) {
                            val valPixel = intValues[i]
                            byteArray[i] = ((valPixel shr 16) and 0xFF).toByte()
                            byteArray[channelSize + i] = ((valPixel shr 8) and 0xFF).toByte()
                            byteArray[2 * channelSize + i] = (valPixel and 0xFF).toByte()
                        }
                        inputBuffer.put(byteArray)
                    }
                } else {
                    // NHWC (TFLite standard): [RGB][RGB][RGB]
                    var pixel = 0
                    for (i in 0 until inputHeight) {
                        for (j in 0 until inputWidth) {
                            val valPixel = intValues[pixel++]
                            val r = ((valPixel shr 16) and 0xFF)
                            val g = ((valPixel shr 8) and 0xFF)
                            val b = (valPixel and 0xFF)

                            if (isFloatModel) {
                                inputBuffer.putFloat(r / 255.0f)
                                inputBuffer.putFloat(g / 255.0f)
                                inputBuffer.putFloat(b / 255.0f)
                            } else {
                                inputBuffer.put(r.toByte())
                                inputBuffer.put(g.toByte())
                                inputBuffer.put(b.toByte())
                            }
                        }
                    }
                }
                // if (resizedBitmap != bitmap) resizedBitmap.recycle() // ODSTRANĚNO: ZERO-ALLOC CACHE se nesmí recyklovat!
                
                inputBuffer.rewind()

                // 2. TENSOR MATRIX ORCHESTRATION (Milestone 7-C)
                
                val resolveLabel: (Int) -> String = { classId ->
                    if (customLabels != null && classId >= 0 && classId < customLabels!!.size) {
                        customLabels!![classId]
                    } else {
                        val strategy = profileData.labelStrategy
                        com.example.llama.agent.vision.models.OdLabelRegistry.resolveLabel(context, strategy, classId, customLabels)
                    }
                }

                val (rawEntities, rawUiDetections) = tensorProfile.runAndParse(
                    interpreter = interpreter,
                    inputBuffer = inputBuffer,
                    modelProfile = profileData,
                    resolveLabel = resolveLabel
                )
                
                // 3. Remapování souřadnic na základě aplikovaného režimu (SQUASH / LETTERBOX / CROP)
                val uiDetections = if (mode == 0) {
                    rawUiDetections // U SQUASH režimu jsou detekce 0..1 vůči čtverci korektně zmapovatelné i pro 16:9 displej (prostě se natáhnou zpět)
                } else {
                    rawUiDetections.mapNotNull { det ->
                        val newLeft = ((det.rect.left - offsetX) / scaleX).coerceIn(0f, 1f)
                        val newTop = ((det.rect.top - offsetY) / scaleY).coerceIn(0f, 1f)
                        val newRight = ((det.rect.right - offsetX) / scaleX).coerceIn(0f, 1f)
                        val newBottom = ((det.rect.bottom - offsetY) / scaleY).coerceIn(0f, 1f)

                        if (newLeft >= newRight || newTop >= newBottom) {
                            null
                        } else {
                            com.example.llama.agent.vision.BoundingBoxView.Detection(
                                rect = android.graphics.RectF(newLeft, newTop, newRight, newBottom),
                                label = det.label,
                                confidence = det.confidence
                            )
                        }
                    }
                }

                // Entity deduplication a merge
                val deduplicated = rawEntities.groupBy { it.label }.map { (label, group) ->
                    DetectedEntity(
                        label = label,
                        category = EntityCategory.OBJECT,
                        confidence = group.maxOf { it.confidence },
                        detectionCount = group.size
                    )
                }

                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                totalOdInferenceMs.addAndGet(elapsedMs)
                odInferenceCount.incrementAndGet()

                if (deduplicated.isNotEmpty()) {
                    Log.v(TAG, "🔍 OD(RAW): ${deduplicated.size} entities (${elapsedMs}ms)")
                }

                val filteredUiDetections = com.example.llama.agent.vision.BoundingBoxView.filterDetections(
                    uiDetections,
                    settingsManager.perceptionOdConfidence.coerceAtLeast(0.30f)
                )

                Pair(deduplicated, filteredUiDetections)
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "⏱️ RAW LiteRT timeout (>${settingsManager.perceptionOdTimeoutMs}ms)")
            Pair(emptyList(), emptyList())
        } catch (e: Exception) {
            Log.e(TAG, "RAW LiteRT inference failed: ${e.message}")
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Worker 2 (Fáze 3.2): ML Kit Text Recognition / OCR (Robustified).
     *
     * Uses on-device bundled Latin recognizer. suspendCancellableCoroutine wraps the async Task API
     * for coroutine integration.
     *
     * Robustifikace (SOTA 2024/2025 best practices):
     * - Bitmap.isRecycled guard
     * - withTimeout (OCR_TIMEOUT_MS) prevents ML Kit hang
     * - Text normalization: newlines → spaces, defensive trim
     * - Truncation to OCR_MAX_LENGTH (RAM saving)
     * - Latency tracking for diagnostics
     * - Singleton TextRecognizer reuse (never re-instantiate)
     *
     * @param bitmap Camera frame (not recycled by this method)
     * @return Recognized text (normalized, truncated), empty if nothing found
     */
    private suspend fun runOCR(bitmap: Bitmap): String {
        // Guard: Bitmap may have been recycled
        if (bitmap.isRecycled) {
            Log.w(TAG, "⚠️ OCR skipped — bitmap already recycled")
            return ""
        }

        // Lazy init (idempotent, singleton reuse)
        if (!ocrInitAttempted) {
            ocrInitAttempted = true
            try {
                textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                Log.i(TAG, "✅ ML Kit TextRecognizer initialized (Latin, on-device)")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ ML Kit TextRecognizer init failed: ${e.message}")
                textRecognizer = null
            }
        }

        val recognizer = textRecognizer ?: return ""

        return try {
            // Timeout guard: prevents ML Kit hang on complex/large images
            withTimeout(settingsManager.perceptionOcrTimeoutMs) {
                val startNs = System.nanoTime()

                // Second recycled check (race condition window)
                if (bitmap.isRecycled) return@withTimeout ""

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val rawText =
                        suspendCancellableCoroutine<String> { cont: CancellableContinuation<String>
                            ->
                            recognizer
                                    .process(inputImage)
                                    .addOnSuccessListener { visionText ->
                                        if (cont.isActive) cont.resume(visionText.text)
                                    }
                                    .addOnFailureListener { e ->
                                        Log.v(TAG, "OCR failed (non-fatal): ${e.message}")
                                        if (cont.isActive) cont.resume("")
                                    }
                        }

                // Text normalization pipeline:
                // 1. Trim whitespace
                // 2. Replace newlines with spaces (compact for LLM context)
                // 3. Collapse multiple spaces
                // 4. Truncate to MAX_LENGTH
                val normalized = rawText.trim().replace('\n', ' ').replace(Regex("\\s{2,}"), " ")

                val ocrMaxLen = settingsManager.perceptionOcrMaxLength
                val truncated =
                        if (normalized.length > ocrMaxLen) {
                            normalized.take(ocrMaxLen) + "…"
                        } else {
                            normalized
                        }

                // Latency tracking for diagnostics
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                totalOcrInferenceMs.addAndGet(elapsedMs)
                ocrInferenceCount.incrementAndGet()

                if (truncated.isNotBlank()) {
                    Log.v(
                            TAG,
                            "📝 OCR: ${truncated.length} chars (${elapsedMs}ms) → \"${truncated.take(80)}\""
                    )
                }

                truncated
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(
                    TAG,
                    "⏱️ OCR timeout (>${settingsManager.perceptionOcrTimeoutMs}ms) — frame skipped"
            )
            ""
        } catch (e: Exception) {
            Log.e(TAG, "OCR exception (non-fatal)", e)
            ""
        }
    }

    // ==================== FACE COUNT BRIDGE (Fáze 3.3) ====================

    /**
     * External bridge: Called by IrisMotionController/FaceLandmarkerEngine to update the current
     * face count.
     *
     * This avoids running a separate FaceDetector — reuses IrisMotion's FaceLandmarker which is
     * already running on Track A (front camera).
     *
     * Thread-safe: AtomicInteger.
     *
     * @param count Number of faces currently detected (0 = no faces)
     */
    fun updateFaceCount(count: Int) {
        currentFaceCount.set(count)
    }

    /**
     * On-demand perception request (non-camera source). Used when user explicitly asks "Co je přede
     * mnou?" and camera might not be running continuous Track B.
     *
     * @param bitmap Single frame to analyze. Engine takes ownership.
     * @param onComplete Callback with readable context string
     */
    fun analyzeOnDemand(bitmap: Bitmap, onComplete: (String) -> Unit) {
        engineScope.launch {
            try {
                // Same processing pipeline, but synchronous and with callback
                onNewFrame(bitmap) // Reuse pipeline (handles recycle)

                // Wait briefly for snapshot to propagate
                kotlinx.coroutines.delay(100)

                val context = repository.snapshot.value.toReadableContext()
                onComplete(context.ifBlank { "Aktuálně nevidím nic konkrétního." })
            } catch (e: Exception) {
                Log.e(TAG, "❌ On-demand analysis failed", e)
                onComplete("Chyba při analýze obrazu.")
            }
        }
    }

    // ==================== DIAGNOSTICS ====================

    /** Returns diagnostic info for file-based debugging (Samsung logcat suppression workaround). */
    fun getDiagnostics(): String {
        return buildString {
            appendLine("=== PerceptionEngine Diagnostics ===")
            appendLine("isRunning: $isRunning")
            appendLine("isThermalPaused: $isThermalPaused")
            appendLine("isScreenOff: $isScreenOff")
            appendLine("currentThermalStatus: $currentThermalStatus")
            appendLine("isProcessing: ${isProcessing.get()}")
            appendLine("entityCount: ${repository.entityCount()}")
            appendLine("frameCounter: ${frameCounter.get()}")
            val snap = repository.snapshot.value
            appendLine("lastSnapshotAge: ${System.currentTimeMillis() - snap.timestampMs}ms")
            appendLine("sceneComplexity: ${snap.sceneComplexity}")
            appendLine("hasUsefulContext: ${snap.hasUsefulContext()}")
            appendLine("=== END ===")
        }
    }
}
