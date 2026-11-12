package com.example.llama.agent.irismotion

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.llama.SettingsManager
import com.example.llama.agent.core.AppLayoutCache
import com.example.llama.agent.irismotion.blink.ReEngagementRamp
import java.util.concurrent.Executors

/**
 * IrisMotionController — Hlavní orchestrator iris tracking pipeline.
 *
 * OPRAVENO: DeepWriteShell-Android Edition (Robust Asymmetric Blink State Machine & Gaze Freeze)
 *
 * Opravené chyby oproti předchozí verzi:
 * 1. Gaze Pollution: Při mrkání jsou deformované souřadnice z FilterPipeline odříznuty
 * (isGazeFrozen).
 * 2. Race Condition: Gesto se vyhodnocuje real-time BĚHEM zavření oka (ne až po otevření).
 * 3. isGazeFrozen je @Volatile pro thread-safe cross-thread read (camera executor → main thread).
 * 4. startCamera() má try-catch pro ExecutionException z providerFuture.get().
 * 5. triggerAction() + handleDwellClick() — odstraněna dvojitá animace (double animateClick()).
 * 6. processImageProxy() má catch{} pro podchycení výjimek z bitmap operací.
 *
 * Pipeline: Camera Frame → FaceLandmarkerEngine → GazeData → [isGazeFrozen?] → GazeFilterPipeline →
 * CalibrationMapper → CursorOverlayManager → BlinkStateMachine → DwellClickEngine → AppLayoutCache
 * → onElementClicked
 */
class IrisMotionController(
        private val context: Context,
        private val lifecycleOwner: LifecycleOwner,
        private val packageNameProvider: () -> String, // Aktuální package (pro AppLayoutCache)
        private val onElementClicked:
                (element: AppLayoutCache.CachedElement, x: Int, y: Int) -> Unit,
        private val onEyeGesture: (actionId: Int, appPackage: String) -> Unit = { _, _ -> },
        var onPhysicalGesture: ((gesture: PhysicalGesture) -> Unit)? = null,
        var onGazeMiss: ((x: Int, y: Int) -> Unit)? = null,
        var onRawTap: ((x: Int, y: Int, isDouble: Boolean) -> Unit)? = null,
        var dwellTimeMs: Long = 800L,
        var isDwellEnabled: Boolean = true,
        cursorSizePx: Int = 80,
        var enableTargetSnap: Boolean = false,
        var targetSnapRadiusPx: Float = 60f,
        private val findNearestElement:
                ((
                        x: Int,
                        y: Int,
                        maxDistancePx:
                                Float) -> com.example.llama.agent.core.AppLayoutCache.CachedElement?)? =
                null
) {
    /** Umožňuje za běhu změnit parametry Dwell clicker (např. ze Settings) */
    fun reconfigureDwell(enabled: Boolean, ms: Long) {
        this.isDwellEnabled = enabled
        this.dwellTimeMs = ms
        dwellEngine.updateDwellTime(ms)
    }

    /** Umožňuje za běhu změnit nastavení Target Snap z UI bez restartu. */
    fun reconfigureTargetSnap(enabled: Boolean, radiusPx: Float) {
        this.enableTargetSnap = enabled
        this.targetSnapRadiusPx = radiusPx
        Log.i(TAG, "⚙️ Target Snap reconfigured: enabled=$enabled, radius=${radiusPx}px")
    }

    var fixationDriftRate: Float = SettingsManager.DEFAULT_IRISMOTION_FIXATION_DRIFT

    fun reconfigureFixationDrift(value: Float) {
        this.fixationDriftRate = value
        faceEngine?.updateFixationDrift(value)
        Log.i(TAG, "⚙️ Fixation Drift reconfigured to: $value")
    }
    companion object {
        private const val TAG = "IrisMotionController"
        internal const val cameraFps = 30
        private const val AUTO_CAL_SAMPLES = 30 // ~1s při 30fps
        private const val AUTO_CAL_MIN_VALID = 15 // Minimálně 50% validních vzorků pro úspěch
    }

    /**
     * Real-time detekce aktuálních HW rozměrů displeje. Respektuje aktuální orientaci zařízení
     * (Portrait/Landscape) a vrací správné hodnoty bez ohledu na to, kdy byl controller
     * inicializován.
     * @return Pair<Float, Float> = (width, height) v px pro aktuální orientaci
     */
    private fun getCurrentScreenDimensions(): Pair<Float, Float> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            // Fix deprecation bez @Suppress: Display.Mode (API 23+) vrací absolutní HW rozlišení
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            if (mode != null) {
                Pair(mode.physicalWidth.toFloat(), mode.physicalHeight.toFloat())
            } else {
                val metrics = context.resources.displayMetrics
                Pair(metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            }
        }
    }

    // Pipeline komponenty
    private val filterPipeline = GazeFilterPipeline()
    private val repository = CalibrationRepository(context)
    private val mapper: CalibrationMapper
    private val cursor = CursorOverlayManager(context)
    private var dwellEngine: DwellClickEngine
    private var faceEngine: FaceLandmarkerEngine? = null

    // CameraX
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraInstance: androidx.camera.core.Camera? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isRunning = false

    // Poslední platné pixelové souřadnice (pro Gaze Freeze)
    // Inicializováno na -1 = žádná data (odlišení od valídní souřadnice 0)
    private var lastValidScreenX = -1
    private var lastValidScreenY = -1

    // ==================== FACIAL EXPRESSIONS STATE (PUMP & HYSTERESIS) ====================
    private var smileThreshold = SettingsManager.DEFAULT_IRISMOTION_SMILE_THRESHOLD
    private var frownThreshold = SettingsManager.DEFAULT_IRISMOTION_FROWN_THRESHOLD
    private var browUpThreshold = SettingsManager.DEFAULT_IRISMOTION_RAISE_EYEBROWS_THRESHOLD

    private var isSmileActive = false
    private var isFrownActive = false
    private var isBrowUpActive = false

    private var lastSmileTriggerTime = 0L
    private var lastFrownTriggerTime = 0L
    private var lastBrowUpTriggerTime = 0L

    // ==================== ZÁKLADNÍ STATY (Gaze & Dwell) ====================
    private var cursorAccumulatorX = -1f
    private var cursorAccumulatorY = -1f

    // ADDITION 1: Čistý výstupní port pro plynulý stream souřadnic (30-60 Hz)
    var onGazeUpdate: ((x: Float, y: Float) -> Unit)? = null

    // ADDITION 2: Kontrola viditelnosti overlaye zvenčí
    fun setCursorVisibility(visible: Boolean) {
        if (visible) {
            cursor.show()
        } else {
            cursor.hide()
        }
    }

    // ==================== STRATEGY PROCESSORS ====================
    private val processor2Dof = Dof2Processor()
    private val processor4Dof = Dof4Processor()
    private val processor6Dof = Dof6Processor()

    // ==================== ANTI-SNAPBACK ENGINE ====================
    // Patent-Grade: RIT "Frozen Position" + LMU "Blink Artifact Interpolation"
    private val reEngagementRamp = ReEngagementRamp()
    private val blinkPhaseDetector = com.example.llama.agent.irismotion.blink.BlinkPhaseDetector()
    private val blinkQualityValidator =
            com.example.llama.agent.irismotion.blink.BlinkQualityValidator()

    // P7: Intentionality Engine
    private val cursorHistory = com.example.llama.agent.irismotion.gaze.CursorHistoryBuffer()
    private var timeShiftEnabled = true
    private var timeShiftMs = 120L
    private val headMotionIntentFilter =
            com.example.llama.agent.irismotion.gaze.HeadMotionIntentFilter()

    // Angular Velocity Gate — per-frame Δ rotace hlavy (zero-latency ochrana proti falešným gestům)
    private var prevHeadYawDeg = 0f
    private var prevHeadPitchDeg = 0f

    // ==================== P3: POHYBOVÁ INTELIGENCE ====================
    private val saccadeClassifier = com.example.llama.agent.irismotion.gaze.SaccadeClassifier()
    private val pupilPredictor = com.example.llama.agent.irismotion.gaze.PupilPositionPredictor()
    private val trajectoryStabilizer =
            com.example.llama.agent.irismotion.gaze.TrajectoryStabilizer()
    private var activeAlphaBetaFilter: AlphaBetaFilter? = null

    // HW display dimensions for cursor clamping
    private var screenWidthPx = 1080
    private var screenHeightPx = 1920

    // ==================== BIOLOGICKÉ FILTRY PRO GESTA ====================
    private val smileFilter = com.example.llama.agent.irismotion.expressions.ScalarOneEuroFilter(minCutoff = 1.0f, beta = 0.0f)
    private val frownFilter = com.example.llama.agent.irismotion.expressions.ScalarOneEuroFilter(minCutoff = 1.0f, beta = 0.0f)
    private val browUpFilter = com.example.llama.agent.irismotion.expressions.ScalarOneEuroFilter(minCutoff = 1.0f, beta = 0.0f)
    private val crosstalkSuppressor = com.example.llama.agent.irismotion.expressions.ExpressionCrosstalkSuppressor()
    
    private val smileAbt = com.example.llama.agent.irismotion.expressions.AdaptiveBaselineTracker()
    private val frownAbt = com.example.llama.agent.irismotion.expressions.AdaptiveBaselineTracker()
    private val browUpAbt = com.example.llama.agent.irismotion.expressions.AdaptiveBaselineTracker()
    
    private val smileTrigger = com.example.llama.agent.irismotion.expressions.SchmittTrigger(0.5f, 0.35f, 0L)
    private val frownTrigger = com.example.llama.agent.irismotion.expressions.SchmittTrigger(0.5f, 0.35f, 0L)
    private val browUpTrigger = com.example.llama.agent.irismotion.expressions.SchmittTrigger(0.5f, 0.35f, 0L)

    // ==================== STATE MACHINE PRO GESTA ====================
    // Nativní asynchronní časovače pro každé oko zvlášť (MRE přesnost pro zamezení falešných 'Both'
    // gest)
    private var leftStartTime: Long = 0L
    private var rightStartTime: Long = 0L
    private var bothStartTime: Long = 0L
    private var smileStartTime: Long = 0L
    private var frownStartTime: Long = 0L
    private var browUpStartTime: Long = 0L

    // Nezávislé pojistky pro single-trigger (eliminace Race Condition)
    // Akce se spustí real-time BĚHEM asynchronního zavření oka
    private var isLeftActionTriggered = false
    private var isRightActionTriggered = false
    private var isBothActionTriggered = false
    private var isSmileActionTriggered = false
    private var isFrownActionTriggered = false
    private var isBrowUpActionTriggered = false

    // OPRAVA 1 (Gaze Pollution): @Volatile pro bezpečné cross-thread čtení
    // Nastavuje se na camera executor, čte se na main thread uvnitř mainHandler.post{}.
    @Volatile private var isGazeFrozen = false

    // Konfigurace gest
    private var leftTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_LEFT_BLINK_TIMEOUT
    private var rightTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_RIGHT_BLINK_TIMEOUT
    private var bothTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_BOTH_BLINK_TIMEOUT
    private var smileTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_SMILE_TIMEOUT
    private var frownTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_FROWN_TIMEOUT
    private var browUpTimeoutMs = SettingsManager.DEFAULT_IRISMOTION_RAISE_EYEBROWS_TIMEOUT

    private var leftAction = SettingsManager.DEFAULT_IRISMOTION_LEFT_BLINK_ACTION
    private var rightAction = SettingsManager.DEFAULT_IRISMOTION_RIGHT_BLINK_ACTION
    private var bothAction = SettingsManager.DEFAULT_IRISMOTION_BOTH_BLINK_ACTION
    private var smileAction = SettingsManager.DEFAULT_IRISMOTION_SMILE_ACTION
    private var frownAction = SettingsManager.DEFAULT_IRISMOTION_FROWN_ACTION
    private var browUpAction = SettingsManager.DEFAULT_IRISMOTION_RAISE_EYEBROWS_ACTION
    private var bothApp = SettingsManager.DEFAULT_IRISMOTION_BOTH_BLINK_APP

    // Cached Head Compensation Gain — načítáno v start() / reloadCalibration(),
    // NE na každý frame (30fps) z SettingsManager
    @Volatile
    private var headCompensationGain = SettingsManager.DEFAULT_IRISMOTION_HEAD_COMPENSATION_GAIN
    @Volatile private var headTrackingDoF = SettingsManager.DEFAULT_IRISMOTION_HEAD_TRACKING_DOF
    @Volatile private var cachedBaseSpeed = SettingsManager.DEFAULT_IRISMOTION_BASE_SPEED
    @Volatile private var isNeuralCoreEnabled = SettingsManager.DEFAULT_IRISMOTION_NEURAL_CORE
    @Volatile private var isTrackingAbsolute = SettingsManager.DEFAULT_IRISMOTION_TRACKING_ABSOLUTE

    // ZERO-LAG GESTURE: Runtime-konfigurovatelný score threshold z BLINK_SENSITIVITY slideru.
    // Řídí, od jakého blendshape score (eyeBlinkLeft/Right) se spustí gesture timer.
    // BLINK_CLICK_THRESHOLD v FaceLandmarkerEngine (0.85f) zůstává pro tracking validity.
    @Volatile private var gestureScoreThreshold = 0.59f

    @Volatile private var isAutoCenterEnabled = false
    @Volatile private var autoCenterSpeed = 0.05f
    @Volatile private var autoCenterDelayMs = 1500L
    private var lastMotionTimeMs = 0L

    // ==================== AUTO-KALIBRACE KLIDOVÉHO BODU ====================
    // Při startu enginu sbíráme snímky surové pozice iris po dobu ~1s (AUTO_CAL_SAMPLES snímků).
    // Průměr → skutečný klidový bod oka → physioRestX/Y na FaceLandmarkerEngine.
    // Během kalibrace kurzor stojí na středu obrazovky (žádný šum).
    @Volatile private var isAutoCalibrating = true
    @Volatile private var isAutoCalDone = false // Signalizuje že auto-cal úspěšně proběhla
    private val autoCalSamplesX = mutableListOf<Float>()
    private val autoCalSamplesY = mutableListOf<Float>()
    private val autoCalSamplesEAR = mutableListOf<Float>() // P8: Mereni prirozeneho otevreni oka

    init {
        val settings = SettingsManager(context)

        // Načtení timeoutů a akcí gest
        leftTimeoutMs = settings.irisMotionLeftBlinkTimeout
        rightTimeoutMs = settings.irisMotionRightBlinkTimeout
        bothTimeoutMs = settings.irisMotionBothBlinkTimeout
        smileTimeoutMs = settings.irisMotionSmileTimeout
        frownTimeoutMs = settings.irisMotionFrownTimeout
        browUpTimeoutMs = settings.irisMotionRaiseEyebrowsTimeout
        
        smileThreshold = settings.irisMotionSmileThreshold
        frownThreshold = settings.irisMotionFrownThreshold
        browUpThreshold = settings.irisMotionRaiseEyebrowsThreshold
        leftAction = settings.irisMotionLeftBlinkAction
        rightAction = settings.irisMotionRightBlinkAction
        bothAction = settings.irisMotionBothBlinkAction
        smileAction = settings.irisMotionSmileAction
        frownAction = settings.irisMotionFrownAction
        browUpAction = settings.irisMotionRaiseEyebrowsAction
        bothApp = settings.irisMotionBothBlinkApp

        // ZERO-LAG GESTURE: Výpočet gesture threshold z BLINK_SENSITIVITY
        // sensitivity 0.80 (default) → ~0.59f, sensitivity 1.00 → 0.65f, sensitivity 0.50 → 0.50f
        gestureScoreThreshold = (0.65f - (1.0f - settings.irisMotionBlinkSensitivity) * 0.30f)
                .coerceIn(0.35f, 0.70f)

        // Anti-Snapback: Inicializace Re-Engagement Ramp z nastavení
        reEngagementRamp.timeoutMs = settings.irisMotionReEngagementTimeout
        
        reconfigureFixationDrift(settings.irisMotionFixationDrift)

        // Inicializace mapper s absolutním HW rozlišením (Modern API 30+ way & Legacy fallback)
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = wm.currentWindowMetrics
            val bounds = windowMetrics.bounds
            // Absolutní bounds obrazovky nezávisle na insets (Status Bar atd.)
            screenWidthPx = bounds.width()
            screenHeightPx = bounds.height()
        } else {
            // Fix deprecation: Použití DisplayManageru a Display.Mode
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val display = displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val mode = display?.mode
            if (mode != null) {
                screenWidthPx = mode.physicalWidth
                screenHeightPx = mode.physicalHeight
            } else {
                val metrics = context.resources.displayMetrics
                screenWidthPx = metrics.widthPixels
                screenHeightPx = metrics.heightPixels
            }
        }

        mapper = CalibrationMapper(screenWidthPx, screenHeightPx)
        mapper.useSphericalCompensation = settings.irisMotionSphericalCompensation
        mapper.edgeMargin = settings.irisMotionEdgeMargin

        // Načtení kalibrace z repository
        val polyData = repository.loadPolynomialCalibration()
        if (polyData.isValid) {
            mapper.setPolynomialCalibration(polyData.coeffX, polyData.coeffY)
            Log.i(TAG, "✅ Polynomiální kalibrace načtena a aplikována (RMS=${String.format("%.1f", polyData.rmsErrorPx)}px)")
        } else {
            val calData = repository.loadCalibration()
            if (calData.isValid) {
                mapper.setCalibrationData(
                        calData.topLeft,
                        calData.topRight,
                        calData.bottomLeft,
                        calData.bottomRight
                )
                Log.i(TAG, "✅ 4-bodová kalibrace načtena a aplikována (fallback)")
            } else {
                Log.w(TAG, "⚠️ Kalibrace chybí — IrisMotion bude méně přesný (lineární mapping)")
            }
        }

        cursor.setCursorSize(cursorSizePx)
        buildPipelineFromSettings(settings)

        val deadzonePx = settings.irisMotionDeadzonePx
        dwellEngine =
                DwellClickEngine(
                        dwellTimeMs = dwellTimeMs,
                        movementThresholdPx = deadzonePx,
                        cooldownMs = 1500L,
                        onDwellClick = { x, y -> handleDwellClick(x, y) },
                        onDwellProgress = { progress -> cursor.updateDwellProgress(progress) }
                )
        // DwellEngine interní blink click VYPNUTO — řídí se výhradně přes BlinkStateMachine gest
        dwellEngine.blinkClickEnabled = false

        Log.d(
                TAG,
                "IrisMotionController inicializován (dwell=${dwellTimeMs}ms, cursor=${cursorSizePx}px, " +
                        "deadzone=${deadzonePx}px)"
        )
    }

    /** Helper pro sestavení Pipeline z nastavení (z `irisMotionFilterSequence`) */
    private fun buildPipelineFromSettings(settings: SettingsManager) {
        filterPipeline.clear()
        activeAlphaBetaFilter = null
        val sequence = settings.irisMotionFilterSequence
        Log.i(TAG, "Sestavuji GazeFilterPipeline: $sequence")

        for (filterName in sequence) {
            when (filterName.trim().uppercase()) {
                "MEDIAN" -> {
                    filterPipeline.addFilter(
                            MedianGazeFilter(windowSize = settings.irisMotionMedianWindow)
                    )
                    Log.d(TAG, " -> Přidán MEDIAN filter")
                }
                "ALPHABETA" -> {
                    val abFilter =
                            AlphaBetaFilter(
                                    alpha = settings.irisMotionAlphaBetaAlpha,
                                    beta = settings.irisMotionAlphaBetaBeta
                            )
                    activeAlphaBetaFilter = abFilter
                    filterPipeline.addFilter(abFilter)
                    Log.d(
                            TAG,
                            " -> Přidán originální ALPHABETA filter (Alfa=${settings.irisMotionAlphaBetaAlpha}, Beta=${settings.irisMotionAlphaBetaBeta})"
                    )
                }
                "KALMAN" -> {
                    filterPipeline.addFilter(
                            KalmanFilter2D(
                                    processNoise = settings.irisMotionKalmanQNoise,
                                    measurementNoise = settings.irisMotionKalmanRNoise
                            )
                    )
                    Log.d(
                            TAG,
                            " -> Přidán KALMAN (State-Space) filter (Q=${settings.irisMotionKalmanQNoise}, R=${settings.irisMotionKalmanRNoise})"
                    )
                }
                "ONEEURO" -> {
                    val stabilizer = GazeStabilizer()
                    stabilizer.updateFilterParams(
                            beta = settings.irisMotionFilterBeta,
                            cutoff = settings.irisMotionFilterCutoff
                    )
                    stabilizer.dCutoff = settings.irisMotionFilterDCutoff
                    stabilizer.setSensitivity(settings.irisMotionFilterSensitivity)
                    filterPipeline.addFilter(OneEuroFilterWrapper(stabilizer))
                    Log.d(TAG, " -> Přidán ONEEURO filter")
                }
                else -> Log.w(TAG, "Neznámý filtr v sekvenci: '$filterName'. Ignoruji.")
            }
        }

        if (sequence.isEmpty()) {
            Log.w(TAG, "⚠️ Pipeline: žádné validní filtry — Passthrough mode.")
        }
    }

    // ==================== LIFECYCLE ====================

    /** Spustí celou pipeline. Volat z onServiceConnected nebo po povolení v Settings. */
    fun start() {
        if (isRunning) return
        isRunning = true
        cursor.show()

        faceEngine =
                FaceLandmarkerEngine(
                        context = context,
                        trackedEyeMode = SettingsManager(context).irisMotionTrackedEye,
                        onResult = { gazeData ->
                            // KRITICKÁ OPRAVA: Propustit do pipeline i isTrackingValid=false pokud
                            // je detekováno zavřené oko (blink gesto).
                            // Původní `if (gazeData.isTrackingValid)` blokoval:
                            //   1. Obě oči zavřené → isTrackingValid=false → GazeFreeze nikdy
                            // nezamrznul
                            //   2. Single-eye mód (mode 1/2) → tracked oko se zavře →
                            // isTrackingValid=false
                            // GazeFreeze v onIrisResult již deformovaná data blokuje
                            // (isGazeFrozen=true).
                            if (gazeData.isTrackingValid ||
                                            gazeData.isLeftEyeClosed ||
                                            gazeData.isRightEyeClosed
                            ) {
                                onIrisResult(gazeData)
                            }
                        },
                        onError = { error ->
                            Log.e(TAG, "FaceLandmarker error: $error")
                            stop()
                        }
                )
        val ampSettings = SettingsManager(context)
        faceEngine?.gazeAmpX = ampSettings.irisMotionGazeAmpX
        faceEngine?.gazeAmpY = ampSettings.irisMotionGazeAmpY
        faceEngine?.gazeAmpLeftEyeLeft = ampSettings.irisMotionGazeAmpLeftEyeLeft
        faceEngine?.gazeAmpLeftEyeRight = ampSettings.irisMotionGazeAmpLeftEyeRight
        faceEngine?.gazeAmpRightEyeLeft = ampSettings.irisMotionGazeAmpRightEyeLeft
        faceEngine?.gazeAmpRightEyeRight = ampSettings.irisMotionGazeAmpRightEyeRight
        faceEngine?.ampUp = ampSettings.irisMotionAmpUp
        faceEngine?.ampDown = ampSettings.irisMotionAmpDown
        faceEngine?.ampLeft = ampSettings.irisMotionAmpLeft
        faceEngine?.ampRight = ampSettings.irisMotionAmpRight

        faceEngine?.centerXOffset = ampSettings.irisMotionCenterXOffset
        faceEngine?.centerYOffset = ampSettings.irisMotionCenterYOffset
        faceEngine?.isIrisMotionCameraMirrored = ampSettings.isIrisMotionCameraMirrored
        faceEngine?.invertEngineX = ampSettings.irisMotionInvertX
        faceEngine?.invertEngineY = ampSettings.irisMotionInvertY
        // Nové properties: globální citlivost + fyziologické konstanty
        faceEngine?.globalSensitivity = ampSettings.irisMotionSensitivity
        faceEngine?.physioRestX = ampSettings.irisMotionPhysioRestX
        faceEngine?.physioRestY = ampSettings.irisMotionPhysioRestY
        faceEngine?.intrinsicAmpX = ampSettings.irisMotionIntrinsicAmpX
        faceEngine?.intrinsicAmpY = ampSettings.irisMotionIntrinsicAmpY
        faceEngine?.updateFixationDrift(fixationDriftRate)

        // Head Tracking DoF — KRŘITICKÉ: bez tohoto se engine choval vždy jako 2DoF
        val dof = ampSettings.irisMotionHeadTrackingDoF
        faceEngine?.headTrackingDoF = dof
        this.headTrackingDoF = dof
        headCompensationGain = ampSettings.irisMotionHeadCompensationGain
        cachedBaseSpeed = ampSettings.irisMotionBaseSpeed
        isNeuralCoreEnabled = ampSettings.isIrisMotionNeuralCoreEnabled
        isTrackingAbsolute = ampSettings.irisMotionTrackingAbsolute

        isAutoCenterEnabled = ampSettings.irisMotionAutoCenter
        autoCenterSpeed = ampSettings.irisMotionAutoCenterSpeed
        autoCenterDelayMs = ampSettings.irisMotionAutoCenterDelayMs

        startCamera()

        // Reset auto-kalibrace klidového bodu pro nový start
        isAutoCalibrating = true
        isAutoCalDone = false
        autoCalSamplesX.clear()
        autoCalSamplesY.clear()
        autoCalSamplesEAR.clear()

        // Reset stavů při startu (vyčištění delty)
        processor2Dof.resetState()
        processor4Dof.resetState()
        processor6Dof.resetState()

        Log.i(
                TAG,
                "🚀 IrisMotion pipeline STARTED (DoF=${ampSettings.irisMotionHeadTrackingDoF}) — auto-kalibrace začíná"
        )
    }

    /** Zastaví pipeline a uvolní resources. Idempotentní. Executor zůstává aktivní pro restart. */
    fun stop() {
        if (!isRunning) return
        isRunning = false

        dwellEngine.cancel()
        cursor.hide()

        cameraProvider?.unbindAll()
        cameraProvider = null

        faceEngine?.close()
        faceEngine = null

        Log.i(TAG, "🛑 IrisMotion pipeline STOPPED")
    }

    /**
     * Finální destrukce — voláno POUZE z onDestroy() Service. Shutdownuje executor a uvolňuje vše.
     */
    fun destroy() {
        stop()
        cameraInstance = null
        if (!cameraExecutor.isShutdown) {
            cameraExecutor.shutdown()
        }
        Log.i(TAG, "💀 IrisMotionController DESTROYED (executor shutdown)")
    }

    // ==================== CAMERA ====================

    // GC-SAFE REUSE BITMAP — eliminuje alokace na 30fps
    // @GuardedBy: přístup pouze z cameraExecutor threadu
    @Volatile private var reusableBitmap: android.graphics.Bitmap? = null

    // OPRAVA 4 (startCamera bez try-catch): try-catch pro ExecutionException z providerFuture.get()
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
                {
                    try {
                        cameraProvider = providerFuture.get()
                        bindCameraUseCases(cameraProvider!!)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ CameraProvider selhal: ${e.message}")
                        stop()
                    }
                },
                ContextCompat.getMainExecutor(context)
        )
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        val imageAnalysis =
                ImageAnalysis.Builder()
                        .setResolutionSelector(
                                androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                                androidx.camera.core.resolutionselector
                                                        .ResolutionStrategy(
                                                                android.util.Size(640, 480),
                                                                androidx.camera.core
                                                                        .resolutionselector
                                                                        .ResolutionStrategy
                                                                        .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                                        )
                                        )
                                        .build()
                        )
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        ) // Drop frames pokud MediaPipe nestíhá
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy -> processImageProxy(imageProxy) }

        try {
            provider.unbindAll()
            cameraInstance = provider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
            Log.d(TAG, "📷 CameraX přední kamera napojena")

            // Aplikace zoom ze Settings
            val settings = SettingsManager(context)
            applyCameraZoom(settings.irisMotionCameraZoom)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Camera bind selhal: ${e.message}")
        }
    }

    /**
     * Nastaví digitální zoom CameraX přední kamery.
     * @param ratio Zoom ratio (1.0 = bez zoomu). Automaticky orezová na HW limity.
     */
    fun applyCameraZoom(ratio: Float) {
        val cam = cameraInstance ?: return
        val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 4.0f
        val clampedRatio = ratio.coerceIn(1.0f, maxZoom)
        cam.cameraControl.setZoomRatio(clampedRatio)
        Log.d(TAG, "🔍 Camera Zoom nastaven na ${clampedRatio}x (HW max: ${maxZoom}x)")
    }

    // OPRAVA 6 (processImageProxy bez catch): přidán catch pro výjimky z bitmap operací
    private fun processImageProxy(imageProxy: ImageProxy) {
        if (!isRunning) {
            imageProxy.close()
            return
        }

        try {
            val w = imageProxy.width
            val h = imageProxy.height
            val bitmap =
                    (reusableBitmap?.takeIf { !it.isRecycled && it.width == w && it.height == h }
                                    ?: android.graphics.Bitmap.createBitmap(
                                                    w,
                                                    h,
                                                    android.graphics.Bitmap.Config.ARGB_8888
                                            )
                                            .also {
                                                reusableBitmap?.recycle()
                                                reusableBitmap = it
                                            })
                            .also {
                                imageProxy.planes[0].buffer.let { buf ->
                                    buf.rewind()
                                    it.copyPixelsFromBuffer(buf)
                                }
                            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            faceEngine?.processFrame(
                    bitmap,
                    rotationDegrees = rotationDegrees,
                    isFrontCamera = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "processImageProxy error: ${e.message}")
        } finally {
            imageProxy.close() // KRITICKÉ: musí být volán vždy
        }
    }

    private var gestureLogCounter = 0

    // ==================== FACIAL EXPRESSIONS PIPELINE ====================
    private fun evaluateFacialExpressions(data: GazeData) {
        val settings = SettingsManager(context)
        val isLocalMode = !settings.isRemoteModeEnabled

        // 0. Duchennův marker (Biologická validace záměru úsměvu - AU6 + AU12)
        // Záměrný úsměv musí mít koaktivaci lícního svalu (smileScore) a očního svalu (cheekSquintScore).
        // Pokud je cheekSquintScore menší než polovina smileScore, úsměv je penalizován.
        val duchenneFactor = (data.cheekSquintScore / (data.smileScore * settings.irisMotionDuchennePenalty).coerceAtLeast(0.01f)).coerceIn(0.2f, 1.0f)
        val duchenneSmile = data.smileScore * duchenneFactor

        // 1. Aplikace One Euro filtru na surová data (vyhlazení mikrotřesů)
        val smoothSmile = if (settings.irisMotionExpressionsOneEuroEnabled) smileFilter.filter(duchenneSmile, data.timestamp) else duchenneSmile
        val smoothFrown = if (settings.irisMotionExpressionsOneEuroEnabled) frownFilter.filter(data.frownScore, data.timestamp) else data.frownScore
        val smoothBrowUp = if (settings.irisMotionExpressionsOneEuroEnabled) browUpFilter.filter(data.browUpScore, data.timestamp) else data.browUpScore
        
        // 2. Křížové maskování (Crosstalk) - řeší anatomické přeslechy
        val suppressedSmile: Float
        val suppressedFrown: Float
        val suppressedBrowUp: Float
        if (settings.irisMotionExpressionsCrosstalkEnabled) {
            val suppressed = crosstalkSuppressor.suppress(smoothSmile, smoothFrown, smoothBrowUp)
            suppressedSmile = suppressed.smile
            suppressedFrown = suppressed.frown
            suppressedBrowUp = suppressed.browUp
        } else {
            suppressedSmile = smoothSmile
            suppressedFrown = smoothFrown
            suppressedBrowUp = smoothBrowUp
        }
        
        // 3. Adaptive Baseline Tracker (ABT) - kompenzuje svalovou únavu (drift)
        val finalSmile = if (settings.irisMotionExpressionsAbtEnabled) smileAbt.process(suppressedSmile) else suppressedSmile
        val finalFrown = if (settings.irisMotionExpressionsAbtEnabled) frownAbt.process(suppressedFrown) else suppressedFrown
        val finalBrowUp = if (settings.irisMotionExpressionsAbtEnabled) browUpAbt.process(suppressedBrowUp) else suppressedBrowUp

        // Debug log pro monitorování hodnot každých 30 snímků
        gestureLogCounter++
        if (gestureLogCounter >= 30) {
            gestureLogCounter = 0
            Log.w(TAG, "Gesture Scores - Smile: $finalSmile (Raw: ${data.smileScore}, Thr: $smileThreshold), Frown: $finalFrown (Thr: $frownThreshold), BrowUp: $finalBrowUp (Thr: $browUpThreshold)")
        }

        // Aktualizace prahů z UI a Hold Time
        // Hystereze: spodní práh je 70% horního
        smileTrigger.updateThresholds(smileThreshold, smileThreshold * 0.7f, smileTimeoutMs)
        frownTrigger.updateThresholds(frownThreshold, frownThreshold * 0.7f, frownTimeoutMs)
        browUpTrigger.updateThresholds(browUpThreshold, browUpThreshold * 0.7f, browUpTimeoutMs)

        // =======================================================
        // Hodnocení Úsměvu (Smile)
        smileTrigger.process(
            currentValue = finalSmile,
            timestampMs = data.timestamp,
            onTriggered = {
                Log.d(TAG, "SMILE TRIGGERED! Score: $finalSmile")
                isSmileActive = true
                isSmileActionTriggered = true
                lastSmileTriggerTime = data.timestamp
                
                // Bellův fenomén / Artefakty mimiky: Zmrazit kurzor během spuštění gesta
                reEngagementRamp.start(data.timestamp)

                onPhysicalGesture?.invoke(PhysicalGesture.SMILE)
                if (isLocalMode) triggerAction(settings.irisMotionSmileAction, "")
            },
            onReset = {
                if (isSmileActionTriggered) Log.d(TAG, "SMILE RESET (trvalo: ${data.timestamp - lastSmileTriggerTime}ms)")
                isSmileActive = false
                isSmileActionTriggered = false
                smileStartTime = 0L // Pro zpětnou kompatibilitu, pokud je použito jinde
            }
        )

        // =======================================================
        // Hodnocení Zamračení (Frown)
        frownTrigger.process(
            currentValue = finalFrown,
            timestampMs = data.timestamp,
            onTriggered = {
                Log.d(TAG, "FROWN TRIGGERED! Score: $finalFrown")
                isFrownActive = true
                isFrownActionTriggered = true
                lastFrownTriggerTime = data.timestamp
                
                // Bellův fenomén / Artefakty mimiky: Zmrazit kurzor během spuštění gesta
                reEngagementRamp.start(data.timestamp)

                onPhysicalGesture?.invoke(PhysicalGesture.FROWN)
                if (isLocalMode) triggerAction(settings.irisMotionFrownAction, "")
            },
            onReset = {
                if (isFrownActionTriggered) Log.d(TAG, "FROWN RESET (trvalo: ${data.timestamp - lastFrownTriggerTime}ms)")
                isFrownActive = false
                isFrownActionTriggered = false
                frownStartTime = 0L
            }
        )

        // =======================================================
        // Hodnocení Zvednutí obočí (Raise Eyebrows)
        browUpTrigger.process(
            currentValue = finalBrowUp,
            timestampMs = data.timestamp,
            onTriggered = {
                Log.d(TAG, "BROW_UP TRIGGERED! Score: $finalBrowUp")
                isBrowUpActive = true
                isBrowUpActionTriggered = true
                lastBrowUpTriggerTime = data.timestamp
                
                // Bellův fenomén / Artefakty mimiky: Zmrazit kurzor během spuštění gesta
                reEngagementRamp.start(data.timestamp)

                onPhysicalGesture?.invoke(PhysicalGesture.RAISE_EYEBROWS)
                if (isLocalMode) triggerAction(settings.irisMotionRaiseEyebrowsAction, "")
            },
            onReset = {
                if (isBrowUpActionTriggered) Log.d(TAG, "BROW_UP RESET (trvalo: ${data.timestamp - lastBrowUpTriggerTime}ms)")
                isBrowUpActive = false
                isBrowUpActionTriggered = false
                browUpStartTime = 0L
            }
        )
    }

    // ==================== PIPELINE ====================

    private fun onIrisResult(gazeData: GazeData) {
        val now = gazeData.timestamp
        
        // 1. Evaluace Facial Expressions (Úsměv, Zamračení, Obočí) - Nezávisle na Gaze/Blink Pipeline
        evaluateFacialExpressions(gazeData)

        // ==================== AUTO-KALIBRACE KLIDOVÉHO BODU ====================
        // Prvních ~1s sbíráme surové iris pozice, kurzor stojí na středu.
        if (isAutoCalibrating) {
            // Sbíráme pouze z validních frameů (tracking valid, oči otevřené)
            if (gazeData.isTrackingValid && !gazeData.isLeftEyeClosed && !gazeData.isRightEyeClosed
            ) {
                val sx = gazeData.rawEyeSocketX
                val sy = gazeData.rawEyeSocketY
                val ear = gazeData.eyeOpenness3D

                // Outlier rejection: iris pozice musí být v rozumném rozsahu (0.1–0.9)
                if (sx in 0.1f..0.9f && sy in 0.1f..0.9f) {
                    autoCalSamplesX.add(sx)
                    autoCalSamplesY.add(sy)
                    autoCalSamplesEAR.add(ear)
                }
            }

            // Kontrola dostatečného počtu vzorků
            val totalCollected = autoCalSamplesX.size
            if (totalCollected >= AUTO_CAL_MIN_VALID && totalCollected >= AUTO_CAL_SAMPLES) {
                // Úspěšná auto-kalibrace
                val avgX = autoCalSamplesX.average().toFloat()
                val avgY = autoCalSamplesY.average().toFloat()
                val avgEAR = autoCalSamplesEAR.average().toFloat()

                faceEngine?.physioRestX = avgX
                faceEngine?.physioRestY = avgY

                // MASTER IT FIX: Zpětný zápis auto-kalibrovaných hodnot do SettingsManager.
                // UI slidery pak zobrazí skutečné hodnoty enginu, ne staré defaulty.
                val sm = SettingsManager(context)
                sm.irisMotionPhysioRestX = avgX
                sm.irisMotionPhysioRestY = avgY
                sm.irisMotionPhysioOpenEAR = avgEAR

                // Uživatelský centerXOffset / centerYOffset ponechán beze změny!
                // Snímá se čistě klidová pozice. Uživatelský ofset se počítá TEPŘVE na ni.
                isAutoCalibrating = false
                isAutoCalDone = true
                Log.i(
                        TAG,
                        "✅ Auto-kalibrace dokončena: physioRest=(%.3f, %.3f) z $totalCollected vzorků (zapsáno do SM)".format(
                                avgX,
                                avgY
                        )
                )
            }

            // Během kalibrace: kurzor na středu obrazovky (stabilní, žádný šum)
            mainHandler.post {
                // Získáme střed obrazovky pomocí mapper.map(0.5, 0.5)
                val center = mapper.map(0.5f, 0.5f)
                cursor.updatePosition(center.x.toInt(), center.y.toInt())
            }
            return // Přeskočíme zbytek pipeline dokud kalibrace neskončí
        }

        // ─── P7: Intentionality Engine - Head Motion Tracking ───────────
        headMotionIntentFilter.addPoint(gazeData.headYaw, gazeData.headPitch, now)

        // ─── MAPPING MediaPipe → User Perspective ───────────
        // FaceLandmarkerEngine PROVÁDÍ swap booleanů (ř.469-470:
        //   finalLeftClosed = isRightEyeClosed, finalRightClosed = isLeftEyeClosed)
        // → GazeData.isLeftEyeClosed = user's LEFT (už správně) ✓
        // → GazeData.isRightEyeClosed = user's RIGHT (už správně) ✓
        //
        // ALE scores (leftBlinkScore/rightBlinkScore) NEJSOU swapované:
        // → GazeData.leftBlinkScore = eyeBlinkLeft = user's RIGHT ✗
        // → GazeData.rightBlinkScore = eyeBlinkRight = user's LEFT ✗
        // Proto SCORE swapujeme, ale BOOLEANY NE.

        val physLeftScore = gazeData.rightBlinkScore // User's LEFT score (MediaPipe RIGHT)
        val physRightScore = gazeData.leftBlinkScore // User's RIGHT score (MediaPipe LEFT)

        // [MASTER IT FIX]: Fyzický výpočet fáze mrknutí (z invariantního EAR) musí proběhnout DŘÍVE
        // než vyhodnocení gest, aby sloužil jako validační "Gate".
        val currentBlinkPhase =
                blinkPhaseDetector.processFrame(gazeData.eyeOpenness3D, now)

        // [MASTER IT FIX]: DECOUPLING — Oddělení fyziky (Gaze Freeze) od logiky gest (Timers)
        // 1. ZAMRZNUTÍ KURZORU (Gaze Freeze)
        // Kurzor zamrzne AŽ těsně před mrknutím (Score > 0.65f).
        // Mhouření (typicky 0.2 - 0.4) kurzor nezastaví!
        val freezeLeft = physLeftScore > 0.65f
        val freezeRight = physRightScore > 0.65f

        // 2. ČASOVAČE GEST (Gesture Trigger) — ZERO-LAG ARCHITEKTURA
        // Score threshold řízený BLINK_SENSITIVITY sliderem (gestureScoreThreshold).
        // Odebráno: BlinkPhaseDetector (isPhaseOpen) gate — přidával 2-frame delay.
        // Odebráno: HeadMotionIntentFilter (isHeadStill) gate — přidával 200ms delay.
        // PONECHÁNO: Angular Velocity Gate — zero-latency per-frame Δ rotace hlavy.
        //   Při aktivní rotaci hlavy blendshapes falešně rostou (0.3–0.5) → musí se blokovat.
        //   Tato kontrola nepřidává žádnou latenci (instant Hned/Nehned).
        val currentYawDeg = Math.toDegrees(gazeData.headYaw.toDouble()).toFloat()
        val currentPitchDeg = Math.toDegrees(gazeData.headPitch.toDouble()).toFloat()
        val dYaw = currentYawDeg - prevHeadYawDeg
        val dPitch = currentPitchDeg - prevHeadPitchDeg
        prevHeadYawDeg = currentYawDeg
        prevHeadPitchDeg = currentPitchDeg
        val isHeadRotatingNow = (dYaw * dYaw + dPitch * dPitch) > 0.3f

        // ═══ EXPRESSION-CONTEXTUAL THRESHOLD — Tobii 2026 / NIH 2024 / GeoGaze 2026 ═══
        // Multimodal Gating: Výraz obličeje je KONTEXT pro wink gesto, ne blokáda.
        //
        // Problém: gestureLeft/Right jsou blendshape-based (eyeBlinkLeft/Right).
        //   AU6 (orbicularis oculi) při úsměvu fyzicky zužuje oční štěrbinu → eyeBlink score
        //   stoupne na ~0.25–0.40 (mělce, pomalu, symetricky) → falešný wink trigger.
        //
        // Řešení: Při aktivním výrazu ZVÝŠÍME práh o 30%.
        //   Smile-squeeze (AU6): eyeBlink score ~0.30 < raised threshold → NE ✅
        //   Záměrný wink při úsměvu: eyeBlink score ~0.70 > raised threshold → ANO ✅
        //   Mrkání NENÍ blokováno — pouze vyžaduje vyšší jistotu (Tobii 2026 Multimodal Gate).
        //
        // EAR pipeline (BlinkPhaseDetector + Dwell Click) je nedotčena — statický BLINK_EAR_CLOSE.
        val isExpressionActive = isSmileActive || isFrownActive || isBrowUpActive
        // Koeficient 1.3f = +30% práh při výrazu (empiricky ověřeno: AU6 typicky ~0.35, wink ~0.70)
        val expressionContextMultiplier = if (isExpressionActive) 1.3f else 1.0f
        val effectiveGestureThreshold = (gestureScoreThreshold * expressionContextMultiplier).coerceAtMost(0.90f)

        var gestureLeft = physLeftScore > effectiveGestureThreshold && !isHeadRotatingNow
        var gestureRight = physRightScore > effectiveGestureThreshold && !isHeadRotatingNow

        // =======================================================
        // 0. SYMKINESIS FILTER (Oprava "levé mrknutí je vyhodnoceno jako Both")
        // Fyziologická symkineze způsobuje, že mrknutí jedním okem potáhne nahoru i druhé víčko.
        // Diferenciál asymetrie > 0.15 spolehlivě odhaluje úmyslné mrknutí jedním okem.
        // =======================================================
        if (gestureLeft && gestureRight) {
            val diff = physLeftScore - physRightScore
            if (diff > 0.15f) {
                gestureRight = false // Pravé víčko je jen mimovolný záškub
            } else if (diff < -0.15f) {
                gestureLeft = false // Levé víčko je jen mimovolný záškub
            }
        }

        // Propíšeme symkinezi i do freeze logiky (aby oko, co se jen trochu hýbe s druhým,
        // nezmrazilo kurzor)
        val finalFreezeLeft = freezeLeft && gestureLeft
        val finalFreezeRight = freezeRight && gestureRight

        // Zjemnění 3D EAR vlivu: Mhouření vyvolá Phase.CLOSING, ale my chceme mrazit už u CLOSING (ochrana před Bellovým fenoménem)
        val isPhaseClosed =
                currentBlinkPhase ==
                        com.example.llama.agent.irismotion.blink.BlinkPhaseDetector.Phase.CLOSED ||
                currentBlinkPhase ==
                        com.example.llama.agent.irismotion.blink.BlinkPhaseDetector.Phase.CLOSING

        // P7: FUSIONAL INTENT FILTER — Pokud se hlava hýbe, ignorujeme veškeré mrkání (gesta i freeze).
        // Zabraňuje "mrkání" při kývání hlavou nahoru/dolů (Pitchem vyvolané svalové tenze).
        // Rozšířeno o Saccadic Suppression z HybridGazeEstimatoru.
        val isHeadStill = headMotionIntentFilter.isHeadStill(now) && !gazeData.isSaccadeInProgress

        // ANY FREEZE - spouští Gaze Freeze a ruší Dwell
        val anyFreeze = (finalFreezeLeft || finalFreezeRight || isPhaseClosed) && isHeadStill

        // ANY GESTURE - řídí, zda se vůbec updatují časovače mrkání
        // ZERO-LAG: Bez isHeadStill gate — timeout + symkineze = dostatečný safety net
        val anyGesture = gestureLeft || gestureRight

        // =======================================================
        // 0.5 BLINK QUALITY VALIDATION FOR DWELL CLICK
        // =======================================================
        if (currentBlinkPhase ==
                        com.example.llama.agent.irismotion.blink.BlinkPhaseDetector.Phase.OPENING &&
                        blinkPhaseDetector.blinkStartTime > 0L
        ) {

            val duration = System.currentTimeMillis() - blinkPhaseDetector.blinkStartTime
            val maxScore = Math.max(physLeftScore, physRightScore)

            val quality =
                    blinkQualityValidator.evaluateQuality(
                            blinkScore = maxScore,
                            durationMs = duration,
                            closingVelocity = blinkPhaseDetector.closingVelocity,
                            isHeadStill = isHeadStill
                    )

            if (quality ==
                            com.example.llama.agent.irismotion.blink.BlinkQualityValidator.Quality
                                    .INTENTIONAL
            ) {
                // P7: Fusional Intent Filter - Zruší klik, pokud se hlava hýbala
                if (!headMotionIntentFilter.isHeadStill(now)) {
                    Log.v(TAG, "👁 Blink klik IGNOROVÁN — hlava nebyla v klidu (Intent Filter)")
                } else {
                    if (timeShiftEnabled) {
                        // KOREKCE: Musíme se odrazit od času ZAČÁTKU mrknutí, nikoliv TEĎ (kdy oko
                        // otevírá a celou dobu bylo zmražené)
                        val targetTime = blinkPhaseDetector.blinkStartTime
                        val pastCoords = cursorHistory.getPositionAt(timeShiftMs, targetTime)
                        dwellEngine.onBlinkDetected(quality, pastCoords?.first, pastCoords?.second)
                    } else {
                        dwellEngine.onBlinkDetected(quality, null, null)
                    }
                }
            }

            blinkPhaseDetector.reset()
        }

        // =======================================================
        // 1. GAZE FREEZE & PIPELINE SHIELD (OPRAVA 1 — Gaze Pollution)
        // Jakmile oko začne se zavírat → zmrazíme souřadnice a odřízneme pipeline od
        // deformovaných eye-socket dat z MediaPipe.
        // =======================================================
        if (anyFreeze && !isGazeFrozen) {
            isGazeFrozen = true
            dwellEngine.cancel() // Zastavíme standardní Dwell — gesto přebírá kontrolu
            Log.v(TAG, "❄️ Gaze Frozen (Gesture Started)")
        } else if (!anyFreeze && isGazeFrozen) {
            isGazeFrozen = false
            // Reset asynchronních pojistek
            isLeftActionTriggered = false
            isRightActionTriggered = false
            isBothActionTriggered = false
            // ★ ANTI-SNAPBACK FIX: Reset aktivního DofProcessoru vynuluje lastFilteredX/Y.
            // Tím se eliminuje nahromaděná záporná delta z post-blink microsaccade.
            // Oko se po mrknutí vrátí do neutrální pozice, ale DofProcessor začne
            // počítat od "nuly" → kurzor zůstane tam, kde byl.
            when (headTrackingDoF) {
                2 -> processor2Dof.resetState()
                4 -> processor4Dof.resetState()
                6 -> processor6Dof.resetState()
                else -> processor2Dof.resetState()
            }
            // [MASTER IT FIX]: KRITICKÝ CHYBĚJÍCÍ RESET!
            // Bez resetu by filtry (Kalman, AlphaBeta) po rozmrazení plynule vyhlazovaly cestu
            // z uvízlé mrkací extenze (A+) zpět k reálnému otevřenému oku (A).
            // Tím by generovaly reverzní/záporné delty = brutální Snapback Rubber-Banding!
            filterPipeline.reset()

            // ★ Spustíme Re-Engagement Ramp: prvních N ms po otevření oka
            // P3: Ramp se aktivuje jen po FIXATION (ne po SACCADE) - plynulý přesun nezmrazit
            if (saccadeClassifier.currentState !=
                            com.example.llama.agent.irismotion.gaze.SaccadeClassifier.GazeState
                                    .SACCADE
            ) {
                reEngagementRamp.start(System.currentTimeMillis())
                Log.v(TAG, "🔥 Gaze Unfrozen + DofProcessor RESET + ReEngagement Ramp started")
            } else {
                Log.v(
                        TAG,
                        "🔥 Gaze Unfrozen + DofProcessor RESET (Ramp skipped - SACCADE detected)"
                )
            }
        }

        // OPRAVA 3: Real-time detekce HW rozměrů displeje (Portrait/Landscape)
        val (currentMaxWidth, currentMaxHeight) = getCurrentScreenDimensions()

        // Výpočet souřadnic obrazovky
        var screenX: Int
        var screenY: Int

        if (!isGazeFrozen) {
            // [MASTER IT FIX]: VÝBĚR HARDWAROVÉHO ZDROJE (Unified Hybrid 3D Engine)
            // Úspěšně jsme odstranili starý Blendshape vs Legacy přepínač. Nyní pipeline používá
            // exkluzivně plnokrevný 3D PCCR Hybrid Tracker z FaceLandmarkerEngine.
            val selectedX = gazeData.x
            val selectedY = gazeData.y
            val baseZ =
                    if (headTrackingDoF == 2) 0f else (gazeData.headYaw + gazeData.headPitch) / 2f

            // Projít filtry POUZE s validními ne-deformovanými daty
            val pipelineResult = filterPipeline.process(selectedX, selectedY, baseZ, now)
            val rawFilteredX = pipelineResult.first
            val rawFilteredY = pipelineResult.second

            // P3: Saccade Classifier aktualizace z AlphaBetaFilteru
            activeAlphaBetaFilter?.let { filter ->
                saccadeClassifier.classify(filter.velX, filter.velY)
            }

            // P3: Pupil Position Predictor - chrání kurzor během mrknutí (zabrání strhnutí)
            val filteredX: Float
            val filteredY: Float
            if (!isGazeFrozen) {
                pupilPredictor.addValidPoint(rawFilteredX, rawFilteredY, now)
                filteredX = rawFilteredX
                filteredY = rawFilteredY
            } else {
                val predicted = pupilPredictor.predict(now)
                filteredX = predicted.first
                filteredY = predicted.second
            }

            if (cursorAccumulatorX < 0) {
                cursorAccumulatorX = currentMaxWidth / 2f
                cursorAccumulatorY = currentMaxHeight / 2f
            }

            // ==================== TRACKING PARADIGM TOGGLE ====================
            // Absolute (Gaze Pointing): Fused Vector → přímo na pixel displeje
            // Relative (HW Mouse): Fused Vector → DofProcessor delta → akumulace
            if (isTrackingAbsolute) {
                // ABSOLUTE MODE: Sensor Fused vektor [0.0–1.0] → pixel displeje
                // GazeData.x/y obsahuje plně zpracovaný Sensor Fusion výstup
                // (IK + Neural Blendshapes + Spatial Hysteresis + Amplifikace)
                val prevAccX = cursorAccumulatorX
                val prevAccY = cursorAccumulatorY
                cursorAccumulatorX = (filteredX * currentMaxWidth).coerceIn(0f, currentMaxWidth)
                cursorAccumulatorY = (filteredY * currentMaxHeight).coerceIn(0f, currentMaxHeight)

                val absDeltaX = cursorAccumulatorX - prevAccX
                val absDeltaY = cursorAccumulatorY - prevAccY
                val totalDeltaSq = absDeltaX * absDeltaX + absDeltaY * absDeltaY
                if (totalDeltaSq > 2.0f) {
                    lastMotionTimeMs = now
                }
            } else {
                // RELATIVE MODE: Stávající delta akumulace přes DofProcessors
                // Reference scale sjednotí fyzickou škálu (stejná citlivost)
                val referenceScale = maxOf(screenWidthPx, screenHeightPx).toFloat()

                // PRECISION DIVIDER (0.1x): Umožňuje ultra-jemné ladění pohybu.
                // Základní rychlost je nyní 10x pomalejší, což dává prostor pro
                // precizní 2DoF/4DoF/6DoF ovládání bez "skákání".
                // PRECISION DIVIDER (1.0x): Obnovení standardní agility po auditovém zjištění
                // limitace vertikálního rozsahu.
                val baseSpeedMultiplier = cachedBaseSpeed * 0.4f
                val headCompGain = headCompensationGain

                // [STRATEGY PATTERN DELEGACE] — Monolit rozbit do procesorů
                val (pxDeltaX, pxDeltaY) =
                        when (headTrackingDoF) {
                            2 ->
                                    processor2Dof.process(
                                            filteredX,
                                            filteredY,
                                            gazeData,
                                            referenceScale,
                                            baseSpeedMultiplier,
                                            headCompGain
                                    )
                            4 ->
                                    processor4Dof.process(
                                            filteredX,
                                            filteredY,
                                            gazeData,
                                            referenceScale,
                                            baseSpeedMultiplier,
                                            headCompGain
                                    )
                            6 ->
                                    processor6Dof.process(
                                            filteredX,
                                            filteredY,
                                            gazeData,
                                            referenceScale,
                                            baseSpeedMultiplier,
                                            headCompGain
                                    )
                            else ->
                                    processor2Dof.process(
                                            filteredX,
                                            filteredY,
                                            gazeData,
                                            referenceScale,
                                            baseSpeedMultiplier,
                                            headCompGain
                                    )
                        }

                // ★ ANTI-SNAPBACK: Škálování delty Re-Engagement rampou (0.0→1.0)
                val rampFactor = reEngagementRamp.getRampFactor(System.currentTimeMillis())
                var rampedDeltaX = pxDeltaX * rampFactor
                var rampedDeltaY = pxDeltaY * rampFactor

                // P3: Trajectory Stabilizer - Ořezání fyzicky nereálných přeskočení
                val clamped = trajectoryStabilizer.clampDelta(rampedDeltaX, rampedDeltaY)
                rampedDeltaX = clamped.first
                rampedDeltaY = clamped.second

                // Akumulace vygenerované a zpracované delty do pozice ukazatele
                cursorAccumulatorX += rampedDeltaX
                cursorAccumulatorY += rampedDeltaY

                // Detekce stabilního úmyslného pohybu pro resetování Auto-Center časovače.
                // SNÍŽENO na 1.0f: Lepší detekce jemného pohybu (sub-pixel precision)
                val totalDeltaSq = pxDeltaX * pxDeltaX + pxDeltaY * pxDeltaY
                if (totalDeltaSq > 1.0f) {
                    lastMotionTimeMs = now
                }
            }

            // =======================================================
            // EDGE REPULSION — automatické odpuzení kurzoru od okrajů
            // Oko nemá ekvivalent "zvednutí myši", proto v 5% okrajové
            // zóně aplikujeme mírnou sílu zpět ke středu displeje.
            // Efekt: kurzor se dostane k okraji, ale netlačí se tam
            // a snadno se vrátí — plyne volně jako HW myš.
            // =======================================================
            val edgeZoneX = currentMaxWidth * 0.05f // 5% šířky displeje
            val edgeZoneY = currentMaxHeight * 0.05f // 5% výšky displeje
            val repulsionStrength = 0.15f // Síla odpuzení (0.0–1.0)

            // Levý okraj
            if (cursorAccumulatorX < edgeZoneX) {
                val depth = 1.0f - (cursorAccumulatorX / edgeZoneX).coerceIn(0f, 1f)
                cursorAccumulatorX += depth * repulsionStrength * edgeZoneX
            }
            // Pravý okraj
            if (cursorAccumulatorX > currentMaxWidth - edgeZoneX) {
                val depth =
                        1.0f - ((currentMaxWidth - cursorAccumulatorX) / edgeZoneX).coerceIn(0f, 1f)
                cursorAccumulatorX -= depth * repulsionStrength * edgeZoneX
            }
            // Horní okraj
            if (cursorAccumulatorY < edgeZoneY) {
                val depth = 1.0f - (cursorAccumulatorY / edgeZoneY).coerceIn(0f, 1f)
                cursorAccumulatorY += depth * repulsionStrength * edgeZoneY
            }
            // Dolní okraj
            if (cursorAccumulatorY > currentMaxHeight - edgeZoneY) {
                val depth =
                        1.0f -
                                ((currentMaxHeight - cursorAccumulatorY) / edgeZoneY).coerceIn(
                                        0f,
                                        1f
                                )
                cursorAccumulatorY -= depth * repulsionStrength * edgeZoneY
            }

            // =======================================================
            // SOFT CLAMP — zabránění úniku akumulátoru mimo displej
            // bez hard-lock efektu. Širší zóna = méně ztráty energie.
            // =======================================================
            val useEdgeDecay = SettingsManager(context).isIrisMotionEdgeDecayEnabled

            var tempX: Int
            var tempY: Int

            if (useEdgeDecay) {
                val marginNorm = mapper.edgeMargin
                val maxOvershootX = currentMaxWidth * marginNorm * 3.0f
                val maxOvershootY = currentMaxHeight * marginNorm * 3.0f

                cursorAccumulatorX =
                        cursorAccumulatorX.coerceIn(-maxOvershootX, currentMaxWidth + maxOvershootX)
                cursorAccumulatorY =
                        cursorAccumulatorY.coerceIn(
                                -maxOvershootY,
                                currentMaxHeight + maxOvershootY
                        )

                val normX = cursorAccumulatorX / currentMaxWidth
                val normY = cursorAccumulatorY / currentMaxHeight

                val compressedNormX = mapper.edgeCompress(normX, mapper.edgeMargin)
                val compressedNormY = mapper.edgeCompress(normY, mapper.edgeMargin)

                val safePxX = (compressedNormX * currentMaxWidth).coerceIn(0f, currentMaxWidth)
                val safePxY = (compressedNormY * currentMaxHeight).coerceIn(0f, currentMaxHeight)

                tempX = safePxX.toInt()
                tempY = safePxY.toInt()
            } else {
                // Soft clamp: širší zóna než hard clamp, Edge Repulsion už brání lepení
                val softMarginX = currentMaxWidth * 0.02f
                val softMarginY = currentMaxHeight * 0.02f
                cursorAccumulatorX =
                        cursorAccumulatorX.coerceIn(-softMarginX, currentMaxWidth + softMarginX)
                cursorAccumulatorY =
                        cursorAccumulatorY.coerceIn(-softMarginY, currentMaxHeight + softMarginY)

                tempX = cursorAccumulatorX.coerceIn(0f, currentMaxWidth).toInt()
                tempY = cursorAccumulatorY.coerceIn(0f, currentMaxHeight).toInt()
            }

            // =======================================================
            // 2. MAGNETIC GAZE SNAP (TARGET LOCK)
            // Jemně přitáhne kurzor k nejbližšímu aktivnímu prvku,
            // pokud je uživatel pohledem poblíž, pro 100% jistotu dwell clicku.
            // =======================================================
            if (enableTargetSnap && findNearestElement != null) {
                // Přitáhneme uzly v rádiusu uživatelského nastavení z UI
                val nearest = findNearestElement.invoke(tempX, tempY, targetSnapRadiusPx)
                if (nearest != null) {
                    // Posun na přesný střed tlačítka
                    tempX = nearest.bounds.exactCenterX().toInt()
                    tempY = nearest.bounds.exactCenterY().toInt()
                }
            }

            screenX = tempX
            screenY = tempY

            lastValidScreenX = screenX
            lastValidScreenY = screenY
        } else {
            screenX = if (lastValidScreenX >= 0) lastValidScreenX else 0
            screenY = if (lastValidScreenY >= 0) lastValidScreenY else 0
        }

        // =======================================================
        // GLOBÁLNÍ AUTO-CENTER: Spustí se i když jsou oči zavřené
        // =======================================================
        if (isAutoCenterEnabled && (now - lastMotionTimeMs > autoCenterDelayMs)) {
            val targetX = currentMaxWidth / 2f
            val targetY = currentMaxHeight / 2f

            if (cursorAccumulatorX < 0) {
                cursorAccumulatorX = targetX
                cursorAccumulatorY = targetY
            } else {
                cursorAccumulatorX += (targetX - cursorAccumulatorX) * autoCenterSpeed
                cursorAccumulatorY += (targetY - cursorAccumulatorY) * autoCenterSpeed
            }

            screenX = cursorAccumulatorX.toInt()
            screenY = cursorAccumulatorY.toInt()

            lastValidScreenX = screenX
            lastValidScreenY = screenY
        }

        // =======================================================
        // 2. ASYNC STATE MACHINE PRO GESTA (MRE - Nezávislé časovače)
        // Vyhodnocuje obě oči izolovaně. Omezuje falešné detekce "Both"
        // prokrývajícími se tiky víček.
        // =======================================================
        mainHandler.post {
            cursor.updatePosition(screenX, screenY)

            // ADDITION 3: Odvysílání plynulého pohybu
            onGazeUpdate?.invoke(screenX.toFloat(), screenY.toFloat())

            // P7: Uloží finální absolutní rendering pozici do historie pro možný Time-Shift klik
            cursorHistory.addPoint(screenX.toFloat(), screenY.toFloat(), now)

            if (anyGesture) {
                // Aktualizace nezávislých časovačů
                if (gestureLeft) {
                    if (leftStartTime == 0L) leftStartTime = now
                } else {
                    leftStartTime = 0L
                    isLeftActionTriggered = false
                }

                if (gestureRight) {
                    if (rightStartTime == 0L) rightStartTime = now
                } else {
                    rightStartTime = 0L
                    isRightActionTriggered = false
                }

                if (gestureLeft && gestureRight) {
                    if (bothStartTime == 0L) bothStartTime = now
                } else {
                    bothStartTime = 0L
                    isBothActionTriggered = false
                }

                val leftDur = if (leftStartTime > 0) now - leftStartTime else 0
                val rightDur = if (rightStartTime > 0) now - rightStartTime else 0
                val bothDur = if (bothStartTime > 0) now - bothStartTime else 0

                // Vyhodnocení gest (nejvyšší priorita má Both)
                if (!isBothActionTriggered &&
                                bothTimeoutMs > 0 &&
                                bothDur >= bothTimeoutMs &&
                                leftDur >= bothTimeoutMs &&
                                rightDur >= bothTimeoutMs
                ) {
                    Log.i(TAG, "👁️ Both Eyes TRIGGERED (${bothDur}ms)")
                    onPhysicalGesture?.invoke(PhysicalGesture.BOTH_BLINK)
                    if (!SettingsManager(context).isRemoteModeEnabled) {
                        triggerAction(bothAction, bothApp, true)
                    }
                    isBothActionTriggered = true
                    // Vzájemné defaultní potlačení po dobu držení "Both"
                    isLeftActionTriggered = true
                    isRightActionTriggered = true
                } else {
                    // Nezávislé vyhodnocení Left/Right s tolerancí na záškub druhého oka
                    if (!isLeftActionTriggered && leftTimeoutMs > 0 && leftDur >= leftTimeoutMs) {
                        // Spustíme Left POUZE pokud pravé oko není zavřené, NEBO je jeho záškub
                        // podstatně kratší (méně než polovina času levého)
                        if (!gestureRight || rightDur < leftDur / 2) {
                            Log.i(TAG, "👁️ Left Wink TRIGGERED (${leftDur}ms)")
                            onPhysicalGesture?.invoke(PhysicalGesture.LEFT_BLINK)
                            if (!SettingsManager(context).isRemoteModeEnabled) {
                                triggerAction(leftAction, "")
                            }
                            isLeftActionTriggered = true
                        }
                    }

                    if (!isRightActionTriggered && rightTimeoutMs > 0 && rightDur >= rightTimeoutMs
                    ) {
                        if (!gestureLeft || leftDur < rightDur / 2) {
                            Log.i(TAG, "👁️ Right Wink TRIGGERED (${rightDur}ms)")
                            onPhysicalGesture?.invoke(PhysicalGesture.RIGHT_BLINK)
                            if (!SettingsManager(context).isRemoteModeEnabled) {
                                triggerAction(rightAction, "")
                            }
                            isRightActionTriggered = true
                        }
                    }
                }
            } else {
                // Obě oči plně otevřené — reset časovačů pro další gesto
                leftStartTime = 0L
                rightStartTime = 0L
                bothStartTime = 0L

                // Standardní Dwell pointer — POUZE pokud nejsme zamrzlí a je povolený (Settings)
                if (!isGazeFrozen && isDwellEnabled) {
                    dwellEngine.processPosition(screenX, screenY)
                }
            }
        }
    }

    // OPRAVA 5 (Double animateClick): Animace řídí POUZE triggerAction().
    // handleDwellClick() pro gesta nevolá animaci (zabrání dvojitému bliknutí).
    // Pro přímý Dwell click (z DwellClickEngine) se animace volá uvnitř handleDwellClick().
    private fun triggerAction(actionId: Int, appPackage: String, isBoth: Boolean = false) {
        if (actionId == SettingsManager.GESTURE_ACTION_NONE) return

        mainHandler.post {
            if (actionId == SettingsManager.GESTURE_ACTION_CLICK) {
                Log.d(TAG, "🖱️ Gesture Click → RAW TAP at ($lastValidScreenX, $lastValidScreenY)")
                cursor.animateClick()
                onRawTap?.invoke(lastValidScreenX, lastValidScreenY, false)
            } else if (actionId == SettingsManager.GESTURE_ACTION_DOUBLE_CLICK) {
                Log.d(
                        TAG,
                        "🖱️ Gesture Double Click → RAW DOUBLE TAP at ($lastValidScreenX, $lastValidScreenY)"
                )
                cursor.animateDoubleBlink()
                onRawTap?.invoke(lastValidScreenX, lastValidScreenY, true)
            } else {
                // Pro ostatní akce (Zpět, Domů, Notifikace, App launch...) vizuální feedback zde
                if (isBoth) cursor.animateDoubleBlink() else cursor.animateClick()
                Log.d(TAG, "⚙️ Odesílání Service Action. ID: $actionId, App: $appPackage")
                onEyeGesture(actionId, appPackage)
            }
        }
    }

    /**
     * Dwell click event → AppLayoutCache HitTest → agentský click callback. Voláno z: (a)
     * DwellClickEngine.Handler — přímý dwell, nebo (b) triggerAction() — gesto. Animace se volá
     * vždy (obě cesty potřebují vizuální feedback).
     */
    private fun handleDwellClick(x: Int, y: Int) {
        val pkg = packageNameProvider()
        val element = AppLayoutCache.findElementAtPoint(pkg, x, y)

        mainHandler.post {
            cursor.animateClick() // Vizuální feedback — jednou (triggerAction ho nevola pro CLICK)

            if (element != null) {
                Log.i(TAG, "👁️➡️🖱️ IrisMotion CLICK: '${element.label}' @ ($x,$y)")
                onElementClicked(element, x, y)
            } else {
                Log.d(TAG, "👁️ IrisMotion MISS @ ($x,$y) — žádný element")
                onGazeMiss?.invoke(x, y)
            }
        }
    }

    /** Znovu načte kalibraci a všechny filtrační parametry (po dokončení CalibrationActivity). */
    fun reloadCalibration() {
        val settings = SettingsManager(context)
        val calData = repository.loadCalibration()
        if (calData.isValid) {
            mapper.resetCalibration()
            mapper.setCalibrationData(
                    calData.topLeft,
                    calData.topRight,
                    calData.bottomLeft,
                    calData.bottomRight
            )
            Log.i(TAG, "✅ Kalibrace znovunačtena")
        }

        buildPipelineFromSettings(settings)

        mapper.useSphericalCompensation = settings.irisMotionSphericalCompensation
        mapper.edgeMargin = settings.irisMotionEdgeMargin

        leftTimeoutMs = settings.irisMotionLeftBlinkTimeout
        rightTimeoutMs = settings.irisMotionRightBlinkTimeout
        bothTimeoutMs = settings.irisMotionBothBlinkTimeout
        smileTimeoutMs = settings.irisMotionSmileTimeout
        frownTimeoutMs = settings.irisMotionFrownTimeout
        browUpTimeoutMs = settings.irisMotionRaiseEyebrowsTimeout
        
        smileThreshold = settings.irisMotionSmileThreshold
        frownThreshold = settings.irisMotionFrownThreshold
        browUpThreshold = settings.irisMotionRaiseEyebrowsThreshold
        leftAction = settings.irisMotionLeftBlinkAction
        rightAction = settings.irisMotionRightBlinkAction
        bothAction = settings.irisMotionBothBlinkAction
        smileAction = settings.irisMotionSmileAction
        frownAction = settings.irisMotionFrownAction
        browUpAction = settings.irisMotionRaiseEyebrowsAction
        bothApp = settings.irisMotionBothBlinkApp

        // DwellEngine blink click striktně vypnuto — řídí BlinkStateMachine gest
        dwellEngine.blinkClickEnabled = false

        // Aktualizace gazeAmp na běžícím FaceLandmarkerEngine
        faceEngine?.gazeAmpX = settings.irisMotionGazeAmpX
        faceEngine?.gazeAmpY = settings.irisMotionGazeAmpY
        faceEngine?.gazeAmpLeftEyeLeft = settings.irisMotionGazeAmpLeftEyeLeft
        faceEngine?.gazeAmpLeftEyeRight = settings.irisMotionGazeAmpLeftEyeRight
        faceEngine?.gazeAmpRightEyeLeft = settings.irisMotionGazeAmpRightEyeLeft
        faceEngine?.gazeAmpRightEyeRight = settings.irisMotionGazeAmpRightEyeRight
        faceEngine?.ampUp = settings.irisMotionAmpUp
        faceEngine?.ampDown = settings.irisMotionAmpDown
        faceEngine?.ampLeft = settings.irisMotionAmpLeft
        faceEngine?.ampRight = settings.irisMotionAmpRight
        faceEngine?.invertEngineX = settings.irisMotionInvertX
        faceEngine?.invertEngineY = settings.irisMotionInvertY
        faceEngine?.globalSensitivity = settings.irisMotionSensitivity

        // Center/PhysioRest: vždy propsat z UI — uživatel může doladit po auto-kalibraci
        faceEngine?.centerXOffset = settings.irisMotionCenterXOffset
        faceEngine?.centerYOffset = settings.irisMotionCenterYOffset
        faceEngine?.physioRestX = settings.irisMotionPhysioRestX
        faceEngine?.physioRestY = settings.irisMotionPhysioRestY

        faceEngine?.intrinsicAmpX = settings.irisMotionIntrinsicAmpX
        faceEngine?.intrinsicAmpY = settings.irisMotionIntrinsicAmpY

        // Head Tracking DoF — live reload (DoF spinner změna bez restartu)
        val dof = settings.irisMotionHeadTrackingDoF
        faceEngine?.headTrackingDoF = dof
        this.headTrackingDoF = dof
        headCompensationGain = settings.irisMotionHeadCompensationGain
        cachedBaseSpeed = settings.irisMotionBaseSpeed

        // Anti-Snapback: Live reload Re-Engagement timeout
        reEngagementRamp.timeoutMs = settings.irisMotionReEngagementTimeout

        // P7: Time-Shift Live Reload
        timeShiftEnabled = settings.irisMotionTimeShiftEnabled
        timeShiftMs = settings.irisMotionTimeShiftMs

        // P7: Intent Filter Live Reload
        headMotionIntentFilter.enabled = settings.irisMotionIntentFilterEnabled
        headMotionIntentFilter.thresholdDeg = settings.irisMotionIntentFilterThresholdDeg

        // P8: Blink Thresholds Live Reload
        blinkPhaseDetector.reloadThresholds(
                settings.irisMotionPhysioOpenEAR,
                settings.irisMotionBlinkSensitivity
        )

        // ZERO-LAG GESTURE: Live reload gesture threshold z BLINK_SENSITIVITY
        gestureScoreThreshold = (0.65f - (1.0f - settings.irisMotionBlinkSensitivity) * 0.30f)
                .coerceIn(0.35f, 0.70f)
        Log.d(TAG, "⚙️ Gesture score threshold: $gestureScoreThreshold (sensitivity=${settings.irisMotionBlinkSensitivity})")

        // Auto-Center — live reload (dříve vyžadoval restart služby)
        isAutoCenterEnabled = settings.irisMotionAutoCenter
        autoCenterSpeed = settings.irisMotionAutoCenterSpeed
        autoCenterDelayMs = settings.irisMotionAutoCenterDelayMs

        // Aktualizace DwellClickEngine parametrů
        dwellEngine.updateDwellTime(settings.irisMotionDwellMs)
        dwellEngine.updateDeadzone(settings.irisMotionDeadzonePx)

        // CameraX Zoom — live reload
        applyCameraZoom(settings.irisMotionCameraZoom)

        Log.d(
                TAG,
                "⚙️ Parametry přenačteny: " +
                        "ampX=${settings.irisMotionGazeAmpX}, ampY=${settings.irisMotionGazeAmpY}, " +
                        "L←=${settings.irisMotionGazeAmpLeftEyeLeft}, L→=${settings.irisMotionGazeAmpLeftEyeRight}, " +
                        "R←=${settings.irisMotionGazeAmpRightEyeLeft}, R→=${settings.irisMotionGazeAmpRightEyeRight}, " +
                        "deadzone=${settings.irisMotionDeadzonePx}px"
        )
    }
}
