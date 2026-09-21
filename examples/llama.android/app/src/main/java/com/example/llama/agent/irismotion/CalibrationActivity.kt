package com.example.llama.agent.irismotion

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.example.llama.SettingsManager
import java.util.concurrent.Executors
import kotlin.math.*
import kotlinx.coroutines.*

/**
 * CalibrationActivity v3 — 9-bodová kalibrace s polynomiálním modelem 2. řádu.
 *
 * ══ KLÍČOVÉ ARCHITEKTONICKÉ PRINCIPY ══════════════════════════════════════
 *
 * 1. ŽÁDNÉ hardcoded rozlišení:
 * ```
 *     - Veškeré souřadnice kalibrač. bodů se generují AŽ PO layout-passu
 *       pomocí previewContainer.doOnLayout{} → skutečné view.width / view.height.
 *     - DisplayManager.DisplayListener reaguje na připojení externího monitoru
 *       (USB-C), rotaci i změnu DPI → invaliduje layout a regeneruje body.
 * ```
 * 2. SOUŘADNICOVÝ PROSTOR = prostor previewContainer (ne celé obrazovky):
 * ```
 *     - Tečka (calibDot) žije v previewContainer.
 *     - Kalibrační body jsou v souřadnicích previewContainer.
 *     - IrisOverlayView kreslí v souřadnicích svého vlastního width/height.
 * ```
 * 3. UI = ConstraintLayout (adaptivní, bez ručního px výpočtu):
 * ```
 *     - previewContainer: constraint top→parent, bottom→bottomPanel.top
 *     - bottomPanel: constraint bottom→parent, výška = wrap_content + min
 * ```
 * 4. Kalibrační sekvence:
 * ```
 *     FÁZE 1 — COLLECT  : 9 bodů (mřížka 3×3)
 *     FÁZE 2 — VALIDATE : 3 body mimo mřížku
 *     FÁZE 3 — RESULT   : RMS [px], skóre 0–100
 * ```
 * 5. Polynomiální model 2. řádu (6 koef. / osa):
 * ```
 *     screen_x = a0 + a1·gx + a2·gy + a3·gx·gy + a4·gx² + a5·gy²
 *     screen_y = b0 + ...
 *     Řešeno normálními rovnicemi, Gauss s částečným pivotováním (6×6).
 * ```
 * 6. Sampling:
 * ```
 *     - MIN_REQUIRED_SAMPLES = 40 / 1.5 s
 *     - MAX_ALLOWED_VARIANCE = 0.012
 *     - Vzdálenostní kompenzace: distFactor = dist / 350 mm
 * ```
 * REPOSITORY INTERFACE:
 * ```
 *    CalibrationRepository.savePolynomialCalibration(
 *        coeffX, coeffY, rmsErrorPx, qualityScore, pointCount
 *    ): Boolean
 * ```
 */
class CalibrationActivity : ComponentActivity() {

    // ══════════════════════════════════════════════════════
    //  KONSTANTY
    // ══════════════════════════════════════════════════════

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val MIN_REQUIRED_SAMPLES = 40
        private const val DWELL_BEFORE_SAMPLE_MS = 1800L
        private const val SAMPLE_DURATION_MS = 1500L
        private const val MAX_GAZE_AGE_MS = 150L
        private const val MAX_ALLOWED_VARIANCE = 0.012f
        private const val BASE_DISTANCE_MM = 350f

        /** Padding kalibrač. bodu od okraje previewContaineru v dp */
        private const val CALIB_PADDING_DP = 48f
        /** Minimální výška spodního panelu v dp */
        private const val BOTTOM_PANEL_MIN_DP = 200f
    }

    private enum class Phase {
        INIT,
        COLLECT,
        VALIDATE,
        RESULT
    }

    // ══════════════════════════════════════════════════════
    //  DISPLAY MANAGEMENT
    // ══════════════════════════════════════════════════════

    /**
     * Skutečné rozměry previewContaineru v px — nastaveny POUZE v doOnLayout callbacku. Výchozí -1
     * signalizuje, že layout ještě neproběhl → body se nesmí generovat.
     */
    private var previewW: Int = -1
    private var previewH: Int = -1

    private lateinit var displayManager: DisplayManager

    /**
     * Reaguje na připojení / odpojení externího displeje (USB-C, Miracast) nebo na změnu
     * konfigurace (rotace, DPI). Invaliduje rozměry a přeplánuje regeneraci.
     */
    private val displayListener =
            object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {
                    Log.i(TAG, "DisplayListener: přidán displayId=$displayId")
                    scheduleDisplayReinit()
                }
                override fun onDisplayRemoved(displayId: Int) {
                    Log.i(TAG, "DisplayListener: odebrán displayId=$displayId")
                    scheduleDisplayReinit()
                }
                override fun onDisplayChanged(displayId: Int) {
                    Log.i(TAG, "DisplayListener: změna displayId=$displayId")
                    scheduleDisplayReinit()
                }
            }

    /** Odloží reinit o 300 ms (debounce pro rychlé eventy při hot-plug). */
    private val displayReinitHandler = Handler(Looper.getMainLooper())
    private val displayReinitRunnable = Runnable {
        previewW = -1
        previewH = -1
        previewContainer.doOnLayout { onPreviewLayoutReady() }
        previewContainer.requestLayout()
    }
    private fun scheduleDisplayReinit() {
        displayReinitHandler.removeCallbacks(displayReinitRunnable)
        displayReinitHandler.postDelayed(displayReinitRunnable, 300L)
    }

    // ══════════════════════════════════════════════════════
    //  UI — VIEWS
    // ══════════════════════════════════════════════════════

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var previewView: PreviewView
    private lateinit var irisOverlay: IrisOverlayView
    private lateinit var calibDot: View
    private lateinit var bottomPanel: LinearLayout
    private lateinit var phaseLabel: TextView
    private lateinit var instructionText: TextView
    private lateinit var progressText: TextView
    private lateinit var btnRetry: Button

    // ══════════════════════════════════════════════════════
    //  ENGINE
    // ══════════════════════════════════════════════════════

    private var faceEngine: FaceLandmarkerEngine? = null
    private lateinit var repository: CalibrationRepository
    private lateinit var settingsManager: SettingsManager
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    // ══════════════════════════════════════════════════════
    //  KALIBRAČNÍ STAV
    // ══════════════════════════════════════════════════════

    private var currentPhase = Phase.INIT
    private var currentStep = 0

    private val collectTargets = ArrayList<PointF>(9)
    private val collectGazeAvg = ArrayList<PointF>(9)
    private val stepSamples = ArrayList<PointF>(200)
    private val validateTargets = ArrayList<PointF>(3)
    private val validateMeasured = ArrayList<PointF>(3)

    private var polyCalib: PolynomialCalibration? = null
    private var rmsErrorPx = 0f
    private var qualityScore = 0

    @Volatile private var latestGazeData: GazeData = GazeData(0f, 0f, false, 0L)

    // ══════════════════════════════════════════════════════
    //  OPRÁVNĚNÍ
    // ══════════════════════════════════════════════════════

    private val requestCameraPermission =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (granted) startEngine()
                else finishWithFailure("Kamera vyžaduje oprávnění pro kalibraci")
            }

    // ══════════════════════════════════════════════════════
    //  LIFECYCLE
    // ══════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = CalibrationRepository(this)
        settingsManager = SettingsManager(this)

        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        // Registrujeme před buildUi — chceme zachytit event i při prvním připojení monitoru
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))

        buildUi()
        setupImmersiveUi()

        // Rozměry zjistíme AŽ PO layout-passu — viz onPreviewLayoutReady()
        previewContainer.doOnLayout { onPreviewLayoutReady() }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
        )
                startEngine()
        else requestCameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotace / split-screen → invaliduj rozměry
        previewW = -1
        previewH = -1
        previewContainer.doOnLayout { onPreviewLayoutReady() }
    }

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        displayReinitHandler.removeCallbacks(displayReinitRunnable)
        faceEngine?.close()
        faceEngine = null
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    // ══════════════════════════════════════════════════════
    //  LAYOUT READY — JEDINÝ ZDROJ ROZMĚRŮ
    // ══════════════════════════════════════════════════════

    /**
     * Voláno po každém (re)layout-passu previewContaineru. Toto je JEDINÉ místo, kde se nastavuje
     * previewW/H. Všechny souřadnice kalibrač. bodů se odvozují odsud.
     */
    private fun onPreviewLayoutReady() {
        val w = previewContainer.width
        val h = previewContainer.height
        if (w <= 0 || h <= 0) {
            Log.w(TAG, "onPreviewLayoutReady: degenerovaný layout (${w}×${h}), čekám")
            previewContainer.doOnLayout { onPreviewLayoutReady() }
            return
        }
        if (previewW == w && previewH == h) return // beze změny

        previewW = w
        previewH = h
        Log.i(TAG, "✅ PreviewContainer layout: ${previewW}×${previewH} px")

        // Pokud kalibrace probíhala, restartujeme od INIT (display se změnil)
        if (currentPhase != Phase.INIT) {
            Log.w(TAG, "Display změna během kalibrace — restart")
            runOnUiThread {
                instructionText.text = "Detekována změna displeje — restartuji kalibraci..."
                Handler(Looper.getMainLooper()).postDelayed({ restartCalibration() }, 1500)
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  UI BUILD — ConstraintLayout, žádné hardcoded výšky
    // ══════════════════════════════════════════════════════

    private fun buildUi() {
        rootLayout =
                ConstraintLayout(this).apply {
                    setBackgroundColor(Color.parseColor("#0D0D1A"))
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                    id = View.generateViewId()
                }

        // ─── Preview kontejner ───
        previewContainer =
                FrameLayout(this).apply {
                    id = View.generateViewId()
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
                }

        previewView =
                PreviewView(this).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }

        irisOverlay =
                IrisOverlayView(this).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                            )
                }

        // Kalibrační tečka — začíná neviditelná, 64×64 dp
        val dotSizePx = dp2px(32f).toInt() * 2
        calibDot =
                View(this).apply {
                    id = View.generateViewId()
                    background = makeCircleDrawable(Color.parseColor("#E94560"))
                    layoutParams = FrameLayout.LayoutParams(dotSizePx, dotSizePx)
                    visibility = View.INVISIBLE
                }
        previewContainer.addView(previewView)
        previewContainer.addView(irisOverlay)
        previewContainer.addView(calibDot)

        // ─── Spodní panel ───
        bottomPanel = buildBottomPanel()

        // ─── ConstraintLayout sestavení ───
        rootLayout.addView(previewContainer)
        rootLayout.addView(bottomPanel)

        ConstraintSet().apply {
            clone(rootLayout)

            // previewContainer: vrchní → parent top, spodní → bottomPanel top, šířka = match
            connect(
                    previewContainer.id,
                    ConstraintSet.TOP,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.TOP
            )
            connect(
                    previewContainer.id,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START
            )
            // previewContainer: FULL SCREEN (100%) pro zachycení notifikační lišty a navbaru
            connect(previewContainer.id, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            connect(previewContainer.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(previewContainer.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(previewContainer.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainHeight(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)
            constrainWidth(previewContainer.id, ConstraintSet.MATCH_CONSTRAINT)

            // bottomPanel: PLOVOUCÍ OVERLAY na spodku, výška podle obsahu
            connect(bottomPanel.id, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            connect(bottomPanel.id, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            connect(bottomPanel.id, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            constrainHeight(bottomPanel.id, ConstraintSet.WRAP_CONTENT)
            val screenH = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                (getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds.height()
            } else {
                resources.displayMetrics.heightPixels
            }
            constrainMaxHeight(bottomPanel.id, (screenH * 0.50).toInt())

            applyTo(rootLayout)
        }

        setContentView(rootLayout)
    }

    private fun buildBottomPanel(): LinearLayout {
        return LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#E60D0D1A")) // 90% neprůhlednost (překryv přes kameru)
            layoutParams =
                    ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    )
            val padH = dp2px(16f).toInt()
            val padV = dp2px(12f).toInt()
            setPadding(padH, padV, padH, padV)

            phaseLabel = makeLabel("", 10f, Color.parseColor("#E94560")).also { addView(it) }
            instructionText =
                    makeLabel("Inicializace kamery...", 17f, Color.WHITE).also {
                        it.textSize = 17f
                        addView(it)
                    }
            progressText =
                    makeLabel("", 13f, Color.parseColor("#A8A8B3")).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp2px(4f).toInt()
                        addView(it)
                    }

            btnRetry =
                    Button(context)
                            .apply {
                                text = "🔄 Opakovat kalibraci"
                                setTextColor(Color.WHITE)
                                setBackgroundColor(Color.parseColor("#E94560"))
                                visibility = View.GONE
                                (layoutParams as? LinearLayout.LayoutParams)?.apply {
                                    topMargin = dp2px(10f).toInt()
                                    gravity = Gravity.CENTER_HORIZONTAL
                                }
                                setOnClickListener { restartCalibration() }
                            }
                            .also { addView(it) }

            // ─── Settings collapsible ───
            val tvToggle =
                    makeLabel("⚙️ Nastavení trackingu ▼", 13f, Color.parseColor("#E94560")).also {
                        (it.layoutParams as LinearLayout.LayoutParams).topMargin =
                                dp2px(10f).toInt()
                    }
            val settingsContent = buildSettingsPanel()
            val scroll =
                    ScrollView(context).apply {
                        layoutParams =
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        0,
                                        1f
                                )
                        visibility = View.GONE
                        addView(settingsContent)
                    }
            tvToggle.setOnClickListener {
                val show = scroll.visibility == View.GONE
                scroll.visibility = if (show) View.VISIBLE else View.GONE
                tvToggle.text = if (show) "⚙️ Nastavení trackingu ▲" else "⚙️ Nastavení trackingu ▼"
            }
            addView(tvToggle)
            addView(scroll)
        }
    }

    private fun buildSettingsPanel(): LinearLayout {
        val panel =
                LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A2E"))
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { topMargin = dp2px(6f).toInt() }
                    val p = dp2px(12f).toInt()
                    setPadding(p, p, p, p)
                }

        fun sliderRow(
                label: String,
                max: Int,
                init: Int,
                fmt: (Int) -> String,
                onChange: (Int) -> Unit
        ): LinearLayout {
            val row =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { topMargin = dp2px(5f).toInt() }
                        gravity = Gravity.CENTER_VERTICAL
                    }
            row.addView(
                    makeLabel(label, 12f, Color.parseColor("#A8A8B3")).apply {
                        minWidth = dp2px(80f).toInt()
                        layoutParams =
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                    }
            )
            val seek =
                    SeekBar(this).apply {
                        this.max = max
                        progress = init
                        layoutParams =
                                LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f
                                )
                    }
            val tvVal =
                    makeLabel(fmt(init), 11f, Color.WHITE).apply {
                        minWidth = dp2px(50f).toInt()
                        gravity = Gravity.END
                        layoutParams =
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                    }
            seek.setOnSeekBarChangeListener(
                    object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                            tvVal.text = fmt(p)
                            if (fromUser) onChange(p)
                        }
                        override fun onStartTrackingTouch(sb: SeekBar?) {}
                        override fun onStopTrackingTouch(sb: SeekBar?) {}
                    }
            )
            row.addView(seek)
            row.addView(tvVal)
            return row
        }

        // Dwell
        val curDwell = settingsManager.irisMotionDwellMs.coerceIn(300L, 2000L)
        panel.addView(
                sliderRow(
                        "Dwell:",
                        34,
                        ((curDwell - 300) / 50).toInt(),
                        { p -> "${300L + p * 50L}ms" },
                        { p -> settingsManager.irisMotionDwellMs = 300L + p * 50L }
                )
        )

        // Citlivost
        val curSens = settingsManager.irisMotionSensitivity.coerceIn(0.5f, 3.0f)
        panel.addView(
                sliderRow(
                        "Citlivost:",
                        25,
                        ((curSens - 0.5f) / 0.1f).toInt(),
                        { p -> String.format("%.1f×", 0.5f + p * 0.1f) },
                        { p -> settingsManager.irisMotionSensitivity = 0.5f + p * 0.1f }
                )
        )

        // Aktivní oko (RadioGroup)
        val eyeRow =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { topMargin = dp2px(8f).toInt() }
                }
        eyeRow.addView(
                makeLabel("Aktivní oko:", 13f, Color.parseColor("#A8A8B3")).apply {
                    minWidth = dp2px(90f).toInt()
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                }
        )
        val radioGroup =
                RadioGroup(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                }
        listOf("Obě" to 0, "Levé" to 1, "Pravé" to 2).forEach { (lbl, id) ->
            radioGroup.addView(
                    RadioButton(this).apply {
                        text = lbl
                        this.id = id
                        setTextColor(Color.WHITE)
                        textSize = 12f
                        setPadding(0, 0, dp2px(12f).toInt(), 0)
                    }
            )
        }
        radioGroup.check(settingsManager.irisMotionTrackedEye)
        radioGroup.setOnCheckedChangeListener { _, id ->
            if (settingsManager.irisMotionTrackedEye != id) {
                settingsManager.irisMotionTrackedEye = id
                faceEngine?.trackedEyeMode = id
            }
        }
        eyeRow.addView(radioGroup)
        panel.addView(eyeRow)

        // Separator
        panel.addView(
                View(this).apply {
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            dp2px(1f).toInt()
                                    )
                                    .apply {
                                        topMargin = dp2px(8f).toInt()
                                        bottomMargin = dp2px(6f).toInt()
                                    }
                    setBackgroundColor(Color.parseColor("#333355"))
                }
        )
        panel.addView(makeLabel("🔧 Filtrační parametry", 12f, Color.parseColor("#E94560")))

        // Reaktivita
        val curBeta = settingsManager.irisMotionFilterBeta.coerceIn(0.001f, 20.0f)
        panel.addView(
                sliderRow(
                        "Reaktivita:",
                        40,
                        ((curBeta - 0.001f) / 0.5f).toInt().coerceIn(0, 40),
                        { p -> String.format("%.1f", 0.001f + p * 0.5f) },
                        { p ->
                            settingsManager.irisMotionFilterBeta =
                                    (0.001f + p * 0.5f).coerceIn(0.001f, 20.0f)
                        }
                )
        )

        // Zmrazení
        val curCutoff = settingsManager.irisMotionFilterCutoff.coerceIn(0.001f, 1.0f)
        panel.addView(
                sliderRow(
                        "Zmrazení:",
                        100,
                        ((curCutoff - 0.001f) / 0.01f).toInt().coerceIn(0, 100),
                        { p -> String.format("%.3f Hz", 0.001f + p * 0.01f) },
                        { p ->
                            settingsManager.irisMotionFilterCutoff =
                                    (0.001f + p * 0.01f).coerceIn(0.001f, 1.0f)
                        }
                )
        )

        // Zesílení X
        val curAmpX = settingsManager.irisMotionGazeAmpX.coerceIn(-3.0f, 25.0f)
        panel.addView(
                sliderRow(
                        "Zesílení X:",
                        280,
                        ((curAmpX + 3.0f) / 0.1f).toInt().coerceIn(0, 280),
                        { p -> String.format("%.1f×", -3.0f + p * 0.1f) },
                        { p ->
                            val v = (-3.0f + p * 0.1f).coerceIn(-3.0f, 25.0f)
                            settingsManager.irisMotionGazeAmpX = v
                            faceEngine?.gazeAmpX = v
                        }
                )
        )

        // Zesílení Y
        val curAmpY = settingsManager.irisMotionGazeAmpY.coerceIn(-3.0f, 25.0f)
        panel.addView(
                sliderRow(
                        "Zesílení Y:",
                        280,
                        ((curAmpY + 3.0f) / 0.1f).toInt().coerceIn(0, 280),
                        { p -> String.format("%.1f×", -3.0f + p * 0.1f) },
                        { p ->
                            val v = (-3.0f + p * 0.1f).coerceIn(-3.0f, 25.0f)
                            settingsManager.irisMotionGazeAmpY = v
                            faceEngine?.gazeAmpY = v
                        }
                )
        )

        // Mrtvá zóna
        val curDead = settingsManager.irisMotionDeadzonePx.coerceIn(5f, 150f)
        panel.addView(
                sliderRow(
                        "Mrtvá zóna:",
                        29,
                        ((curDead - 5f) / 5f).toInt().coerceIn(0, 29),
                        { p -> "${5 + p * 5}px" },
                        { p ->
                            settingsManager.irisMotionDeadzonePx = (5f + p * 5f).coerceIn(5f, 150f)
                        }
                )
        )

        // Mediánový filtr
        val medianSteps = intArrayOf(1, 3, 5, 7, 9)
        val curMedian = settingsManager.irisMotionMedianWindow.coerceIn(1, 9)
        panel.addView(
                sliderRow(
                        "Medián:",
                        4,
                        medianSteps.indexOfFirst { it >= curMedian }.coerceAtLeast(0),
                        { p ->
                            val ws = medianSteps.getOrElse(p) { 3 }
                            if (ws <= 1) "VYP" else "${ws}f"
                        },
                        { p ->
                            settingsManager.irisMotionMedianWindow = medianSteps.getOrElse(p) { 3 }
                        }
                )
        )

        panel.addView(
                makeLabel("Nastavení se ukládají okamžitě.", 10f, Color.parseColor("#606080"))
                        .apply {
                            (layoutParams as LinearLayout.LayoutParams).topMargin =
                                    dp2px(6f).toInt()
                            gravity = Gravity.CENTER_HORIZONTAL
                        }
        )
        return panel
    }

    // ══════════════════════════════════════════════════════
    //  IMMERSIVE MODE
    // ══════════════════════════════════════════════════════

    private fun setupImmersiveUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.setFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P)
                window.attributes.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    // ══════════════════════════════════════════════════════
    //  ENGINE START + CAMERA BINDING
    // ══════════════════════════════════════════════════════

    private fun startEngine() {
        try {
            faceEngine =
                    FaceLandmarkerEngine(
                            context = this,
                            trackedEyeMode = settingsManager.irisMotionTrackedEye,
                            onResult = { gaze ->
                                latestGazeData = gaze
                                irisOverlay.post { irisOverlay.updateGaze(gaze) }
                            },
                            onError = { err ->
                                Log.e(TAG, "FaceEngine: $err")
                                finishWithFailure("Kamera chyba: $err")
                            }
                    )
            bindCamera()
        } catch (e: Exception) {
            finishWithFailure("FaceEngine init selhal: ${e.message}")
        }
    }

    private fun bindCamera() {
        ProcessCameraProvider.getInstance(this)
                .addListener(
                        {
                            try {
                                val provider = ProcessCameraProvider.getInstance(this).get()

                                val preview =
                                        Preview.Builder().build().also {
                                            it.setSurfaceProvider(previewView.surfaceProvider)
                                        }

                                val analysis =
                                        ImageAnalysis.Builder()
                                                .setResolutionSelector(
                                                        androidx.camera.core.resolutionselector
                                                                .ResolutionSelector.Builder()
                                                                .setResolutionStrategy(
                                                                        androidx.camera.core
                                                                                .resolutionselector
                                                                                .ResolutionStrategy(
                                                                                        android.util
                                                                                                .Size(
                                                                                                        640,
                                                                                                        480
                                                                                                ),
                                                                                        androidx.camera
                                                                                                .core
                                                                                                .resolutionselector
                                                                                                .ResolutionStrategy
                                                                                                .FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                                                                )
                                                                )
                                                                .build()
                                                )
                                                .setBackpressureStrategy(
                                                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                                                )
                                                .setOutputImageFormat(
                                                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                                                )
                                                .build()

                                analysis.setAnalyzer(cameraExecutor) { proxy ->
                                    var bmp: android.graphics.Bitmap? = null
                                    try {
                                        bmp = proxy.toBitmap()
                                        if (bmp.width > 0 && !bmp.isRecycled)
                                                faceEngine?.processFrame(
                                                        bmp,
                                                        rotationDegrees =
                                                                proxy.imageInfo.rotationDegrees,
                                                        isFrontCamera = true
                                                )
                                    } finally {
                                        bmp?.takeIf { !it.isRecycled }?.recycle()
                                        proxy.close()
                                    }
                                }

                                provider.unbindAll()
                                val camera =
                                        provider.bindToLifecycle(
                                                this,
                                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                                preview,
                                                analysis
                                        )

                                val maxZ = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 4.0f
                                camera.cameraControl.setZoomRatio(
                                        settingsManager.irisMotionCameraZoom.coerceIn(1.0f, maxZ)
                                )

                                Log.i(TAG, "✅ CameraX bind OK")
                                lifecycleScope.launch {
                                    instructionText.text = "🔍 Zahřívání AI modelu..."
                                    delay(1500)
                                    // Počkáme, dokud doOnLayout nedodá reálné rozměry
                                    awaitPreviewLayout()
                                    startCollectionPhase()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "❌ CameraX bind selhal", e)
                                finishWithFailure("Kamera se nepodařilo spustit: ${e.message}")
                            }
                        },
                        ContextCompat.getMainExecutor(this)
                )
    }

    /**
     * Suspenduje korutinu dokud previewW/H nejsou platné hodnoty. Poll každých 100 ms s timeoutem
     * 10 s.
     */
    private suspend fun awaitPreviewLayout() {
        val timeoutMs = 10_000L
        val startMs = System.currentTimeMillis()
        while (previewW <= 0 || previewH <= 0) {
            if (System.currentTimeMillis() - startMs > timeoutMs) {
                finishWithFailure("Timeout při čekání na layout preview plochy")
                return
            }
            delay(100)
        }
        Log.d(TAG, "awaitPreviewLayout: ${previewW}×${previewH}")
    }

    // ══════════════════════════════════════════════════════
    //  GENERÁTORY BODŮ — SOUŘADNICE V PROSTORU previewContainer
    // ══════════════════════════════════════════════════════

    /**
     * 9 bodů v mřížce 3×3 — všechny souřadnice jsou v px UVNITŘ previewContainer. Padding je v dp a
     * konvertuje se za běhu → adaptivní na každé rozlišení. Precondition: previewW > 0 && previewH
     * > 0
     */
    private fun generate9PointGrid(): List<PointF> {
        require(previewW > 0 && previewH > 0) {
            "generate9PointGrid voláno před layout-passem (previewW=$previewW, previewH=$previewH)"
        }
        val pad = dp2px(CALIB_PADDING_DP)
        val cx = previewW / 2f
        val cy = previewH / 2f
        val rx = previewW - pad
        val by_ = previewH - pad
        return listOf(
                PointF(pad, pad),
                PointF(cx, pad),
                PointF(rx, pad),
                PointF(pad, cy),
                PointF(cx, cy),
                PointF(rx, cy),
                PointF(pad, by_),
                PointF(cx, by_),
                PointF(rx, by_)
        )
    }

    /**
     * 3 validační body — záměrně v jiných pozicích než mřížka. Souřadnice jsou v px UVNITŘ
     * previewContainer.
     */
    private fun generate3ValidationPoints(): List<PointF> {
        require(previewW > 0 && previewH > 0)
        return listOf(
                PointF(previewW * 0.25f, previewH * 0.33f),
                PointF(previewW * 0.75f, previewH * 0.66f),
                PointF(previewW * 0.50f, previewH * 0.80f)
        )
    }

    // ══════════════════════════════════════════════════════
    //  FÁZE 1 — SBĚR DAT (9 bodů)
    // ══════════════════════════════════════════════════════

    private fun startCollectionPhase() {
        currentPhase = Phase.COLLECT
        collectTargets.clear()
        collectGazeAvg.clear()
        collectTargets.addAll(generate9PointGrid())
        currentStep = 0
        runOnUiThread {
            phaseLabel.text = "FÁZE 1 / 3 — SBĚR (9 BODŮ)"
            btnRetry.visibility = View.GONE
            setDotColor(Color.parseColor("#E94560"))
            showCollectStep()
        }
    }

    private fun showCollectStep() {
        if (currentStep >= collectTargets.size) {
            startValidationPhase()
            return
        }
        val target = collectTargets[currentStep]
        instructionText.text = "Fixujte zrak:\n${collectLabel(currentStep)}"
        progressText.text = "Bod ${currentStep + 1} / 9  •  držte pohled"
        moveDotTo(target)
        irisOverlay.setCalibrationTarget(target.x, target.y)

        lifecycleScope.launch {
            delay(DWELL_BEFORE_SAMPLE_MS)
            val avg = runSampling()
            withContext(Dispatchers.Main) {
                if (avg == null) {
                    Handler(Looper.getMainLooper()).postDelayed({ showCollectStep() }, 2000)
                } else {
                    collectGazeAvg.add(avg)
                    Log.d(TAG, "✅ Sběr $currentStep OK gaze=(${avg.x},${avg.y})")
                    irisOverlay.flashSuccess()
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        currentStep++
                                        showCollectStep()
                                    },
                                    600
                            )
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  SDÍLENÁ SAMPLING LOGIKA
    // ══════════════════════════════════════════════════════

    private suspend fun runSampling(): PointF? =
            withContext(Dispatchers.Default) {
                stepSamples.clear()
                val end = System.currentTimeMillis() + SAMPLE_DURATION_MS

                while (System.currentTimeMillis() < end) {
                    val g = latestGazeData
                    val age = System.currentTimeMillis() - g.timestamp
                    if (g.isTrackingValid && age < MAX_GAZE_AGE_MS) {
                        val df = g.distanceMm.coerceIn(150f, 800f) / BASE_DISTANCE_MM
                        stepSamples.add(PointF((g.x - 0.5f) * df + 0.5f, (g.y - 0.5f) * df + 0.5f))
                    }
                    delay(20L)
                }

                if (stepSamples.size < MIN_REQUIRED_SAMPLES) {
                    withContext(Dispatchers.Main) {
                        instructionText.text =
                                "Oko ztraceno (${stepSamples.size}/$MIN_REQUIRED_SAMPLES vzorků).\n" +
                                        "Dívejte se přímo do přední kamery."
                        irisOverlay.flashError()
                    }
                    return@withContext null
                }

                val avgX = stepSamples.map { it.x }.average().toFloat()
                val avgY = stepSamples.map { it.y }.average().toFloat()
                val variance =
                        stepSamples.sumOf { s ->
                            (s.x - avgX).toDouble().pow(2) + (s.y - avgY).toDouble().pow(2)
                        } / stepSamples.size

                if (variance > MAX_ALLOWED_VARIANCE) {
                    withContext(Dispatchers.Main) {
                        instructionText.text =
                                "Přílišný pohyb (var=${String.format("%.4f", variance)}).\nZafixujte pohled."
                        irisOverlay.flashError()
                    }
                    return@withContext null
                }
                PointF(avgX, avgY)
            }

    // ══════════════════════════════════════════════════════
    //  POLYNOMIÁLNÍ FIT (normální rovnice, 6×6 Gauss)
    // ══════════════════════════════════════════════════════

    private fun fitPolynomial(): PolynomialCalibration? {
        val n = collectGazeAvg.size
        if (n < 6) {
            Log.e(TAG, "Nedostatek bodů: $n < 6")
            return null
        }

        val A =
                Array(n) { i ->
                    val gx = collectGazeAvg[i].x.toDouble()
                    val gy = collectGazeAvg[i].y.toDouble()
                    doubleArrayOf(1.0, gx, gy, gx * gy, gx * gx, gy * gy)
                }
        // Cílové hodnoty = normalizované souřadnice (0.0 - 1.0) pro CalibrationMapper.
        // OPRAVA EXPANCE: body v síti (pad) namapujeme matematicky jako by ležely úplně na
        // skutečném okraji obrazovky (0.0 / 1.0). Tím zajistíme, že uživatel nemusí zrakem 
        // dosahovat až "za telefon", ale pohodlný pohled na kalibrační red-dot se roztáhne na rohy.
        val pad = dp2px(CALIB_PADDING_DP)
        val usableW = previewW - 2 * pad
        val usableH = previewH - 2 * pad

        val bx = DoubleArray(n) { i ->
            val mapped = (collectTargets[i].x - pad) / usableW
            mapped.toDouble().coerceIn(0.0, 1.0)
        }
        val by = DoubleArray(n) { i ->
            val mapped = (collectTargets[i].y - pad) / usableH
            mapped.toDouble().coerceIn(0.0, 1.0)
        }

        val cx =
                leastSquares(A, bx)
                        ?: run {
                            Log.e(TAG, "Singulární matice X")
                            return null
                        }
        val cy =
                leastSquares(A, by)
                        ?: run {
                            Log.e(TAG, "Singulární matice Y")
                            return null
                        }

        return PolynomialCalibration(
                coeffX = cx.map { it.toFloat() }.toFloatArray(),
                coeffY = cy.map { it.toFloat() }.toFloatArray()
        )
    }

    private fun leastSquares(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val c = A[0].size
        val r = A.size
        val AtA = Array(c) { DoubleArray(c) }
        val Atb = DoubleArray(c)
        for (i in 0 until c) for (j in 0 until c) for (k in 0 until r) AtA[i][j] +=
                A[k][i] * A[k][j]
        for (i in 0 until c) for (k in 0 until r) Atb[i] += A[k][i] * b[k]

        val aug =
                Array(c) { i ->
                    DoubleArray(c + 1).also {
                        AtA[i].copyInto(it)
                        it[c] = Atb[i]
                    }
                }

        for (col in 0 until c) {
            var maxRow = col
            for (row in col + 1 until c) if (abs(aug[row][col]) > abs(aug[maxRow][col]))
                    maxRow = row
            aug[col].also {
                aug[col] = aug[maxRow]
                aug[maxRow] = it
            }

            if (abs(aug[col][col]) < 1e-12) return null
            val piv = aug[col][col]
            for (j in col..c) aug[col][j] /= piv
            for (row in 0 until c) {
                if (row == col) continue
                val f = aug[row][col]
                for (j in col..c) aug[row][j] -= f * aug[col][j]
            }
        }
        return DoubleArray(c) { aug[it][c] }
    }

    // ══════════════════════════════════════════════════════
    //  FÁZE 2 — VALIDACE (3 body)
    // ══════════════════════════════════════════════════════

    private fun startValidationPhase() {
        val calib = fitPolynomial()
        if (calib == null) {
            finishWithFailure("Selhalo fitování modelu.")
            return
        }
        polyCalib = calib

        currentPhase = Phase.VALIDATE
        validateTargets.clear()
        validateMeasured.clear()
        validateTargets.addAll(generate3ValidationPoints())
        currentStep = 0

        runOnUiThread {
            phaseLabel.text = "FÁZE 2 / 3 — VALIDACE (3 BODY)"
            setDotColor(Color.parseColor("#4488FF"))
            showValidateStep()
        }
    }

    private fun showValidateStep() {
        if (currentStep >= validateTargets.size) {
            computeAccuracy()
            return
        }
        val target = validateTargets[currentStep]
        instructionText.text = "Validace — bod ${currentStep + 1} / 3"
        progressText.text = "Model bude porovnán s vaší fixací"
        moveDotTo(target)
        irisOverlay.setCalibrationTarget(target.x, target.y)

        lifecycleScope.launch {
            delay(DWELL_BEFORE_SAMPLE_MS)
            val avg = runSampling()
            withContext(Dispatchers.Main) {
                if (avg != null) {
                    val norm = polyCalib!!.map(avg.x, avg.y)
                    val pad = dp2px(CALIB_PADDING_DP)
                    
                    // Převod normovaného výstupu polynomu zpět do fyzických pixelů náhledu
                    val screenX = pad + norm.x * (previewW - 2 * pad)
                    val screenY = pad + norm.y * (previewH - 2 * pad)
                    val screen = PointF(screenX, screenY)

                    validateMeasured.add(screen)
                    val dx = screen.x - target.x
                    val dy = screen.y - target.y
                    Log.d(
                            TAG,
                            "Validace $currentStep err=(${String.format("%.1f",dx)},${String.format("%.1f",dy)})px"
                    )
                    irisOverlay.flashSuccess()
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        currentStep++
                                        showValidateStep()
                                    },
                                    600
                            )
                } else {
                    Handler(Looper.getMainLooper()).postDelayed({ showValidateStep() }, 2000)
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  FÁZE 3 — VÝSLEDEK + ULOŽENÍ
    // ══════════════════════════════════════════════════════

    private fun computeAccuracy() {
        currentPhase = Phase.RESULT
        if (validateMeasured.isEmpty()) {
            finishWithFailure("Žádná validační data.")
            return
        }

        val sumSq =
                validateMeasured.indices.sumOf { i ->
                    val dx = (validateMeasured[i].x - validateTargets[i].x).toDouble()
                    val dy = (validateMeasured[i].y - validateTargets[i].y).toDouble()
                    dx * dx + dy * dy
                }
        rmsErrorPx = sqrt((sumSq / validateMeasured.size).toFloat())
        qualityScore = rmsToScore(rmsErrorPx)
        Log.i(
                TAG,
                "📊 RMS=${String.format("%.1f",rmsErrorPx)}px skóre=$qualityScore previewH=$previewH"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val calib =
                    polyCalib
                            ?: run {
                                finishWithFailure("Chybí model.")
                                return@launch
                            }
            val ok =
                    repository.savePolynomialCalibration(
                            coeffX = calib.coeffX,
                            coeffY = calib.coeffY,
                            rmsErrorPx = rmsErrorPx,
                            qualityScore = qualityScore,
                            pointCount = collectGazeAvg.size
                    )
            if (ok) settingsManager.isIrisMotionCalibrated = true

            withContext(Dispatchers.Main) {
                calibDot.visibility = View.INVISIBLE
                phaseLabel.text = "FÁZE 3 / 3 — VÝSLEDEK"
                irisOverlay.setCalibrationTarget(-1f, -1f)

                val (emoji, grade) =
                        when {
                            qualityScore >= 85 -> "🏆" to "Výborná"
                            qualityScore >= 70 -> "✅" to "Dobrá"
                            qualityScore >= 50 -> "⚠️" to "Přijatelná"
                            else -> "❌" to "Slabá — doporučeno opakovat"
                        }
                instructionText.text =
                        "$emoji Kalibrace dokončena\nSkóre: $qualityScore / 100  ($grade)"
                progressText.text =
                        "Přesnost: ${String.format("%.1f", rmsErrorPx)} px RMS  •  " +
                                if (ok) "Uloženo ✅" else "Uložení selhalo ❌"

                if (qualityScore < 70) btnRetry.visibility = View.VISIBLE

                Toast.makeText(
                                this@CalibrationActivity,
                                if (ok) "✅ IrisMotion kalibrován (skóre $qualityScore/100)"
                                else "⚠️ Uložení selhalo.",
                                Toast.LENGTH_LONG
                        )
                        .show()

                setResult(if (ok) RESULT_OK else RESULT_CANCELED)
                Handler(Looper.getMainLooper())
                        .postDelayed({ if (currentPhase == Phase.RESULT) finish() }, 5000)
            }
        }
    }

    /**
     * RMS normalizovaný vůči výšce previewH (ne screenH). Tím je skóre konzistentní bez ohledu na
     * rozlišení zařízení nebo externího monitoru.
     */
    private fun rmsToScore(rms: Float): Int {
        val refH = 1080f // referenční výška
        val actual = if (previewH > 0) previewH.toFloat() else refH
        val rmsNorm = rms * (refH / actual) // normalizováno na referenci
        return when {
            rmsNorm < 25f -> 100
            rmsNorm < 45f -> 90
            rmsNorm < 65f -> 80
            rmsNorm < 90f -> 70
            rmsNorm < 120f -> 55
            rmsNorm < 160f -> 40
            rmsNorm < 200f -> 25
            else -> 10
        }
    }

    // ══════════════════════════════════════════════════════
    //  RESTART / FAILURE
    // ══════════════════════════════════════════════════════

    private fun restartCalibration() {
        collectTargets.clear()
        collectGazeAvg.clear()
        validateTargets.clear()
        validateMeasured.clear()
        stepSamples.clear()
        polyCalib = null
        rmsErrorPx = 0f
        qualityScore = 0
        currentPhase = Phase.INIT
        currentStep = 0
        btnRetry.visibility = View.GONE
        lifecycleScope.launch {
            awaitPreviewLayout()
            startCollectionPhase()
        }
    }

    private fun finishWithFailure(reason: String) {
        runOnUiThread {
            Log.e(TAG, "❌ $reason")
            Toast.makeText(this, "❌ $reason", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    // ══════════════════════════════════════════════════════
    //  UI HELPERS
    // ══════════════════════════════════════════════════════

    /** Konvertuje dp → px na aktuálním displeji (reaguje na změnu DPI). */
    private fun dp2px(dp: Float): Float =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun makeLabel(text: String, spSize: Float, color: Int): TextView =
            TextView(this).apply {
                this.text = text
                setTextColor(color)
                textSize = spSize
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams =
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        )
            }

    private fun makeCircleDrawable(color: Int): android.graphics.drawable.Drawable =
            android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape())
                    .apply {
                        paint.color = color
                        paint.style = Paint.Style.FILL
                    }

    private fun setDotColor(color: Int) {
        calibDot.background = makeCircleDrawable(color)
    }

    private fun moveDotTo(target: PointF) {
        val halfDot = calibDot.width / 2f
        val halfDotH = calibDot.height / 2f
        calibDot.visibility = View.VISIBLE
        calibDot.animate().x(target.x - halfDot).y(target.y - halfDotH).setDuration(600).start()
    }

    private fun collectLabel(idx: Int) =
            when (idx) {
                0 -> "↖ Levý horní roh"
                1 -> "⬆ Střed nahoře"
                2 -> "↗ Pravý horní roh"
                3 -> "⬅ Střed vlevo"
                4 -> "⊕ Střed"
                5 -> "➡ Střed vpravo"
                6 -> "↙ Levý dolní roh"
                7 -> "⬇ Střed dole"
                8 -> "↘ Pravý dolní roh"
                else -> "Bod ${idx + 1}"
            }

    // ══════════════════════════════════════════════════════
    //  POLYNOMIÁLNÍ MODEL — mapování gaze → preview px
    // ══════════════════════════════════════════════════════

    inner class PolynomialCalibration(val coeffX: FloatArray, val coeffY: FloatArray) {
        /** gx, gy jsou normalizované (0–1). Vrací px v prostoru previewContainer. */
        fun map(gx: Float, gy: Float): PointF {
            val f = floatArrayOf(1f, gx, gy, gx * gy, gx * gx, gy * gy)
            val sx = f.indices.sumOf { (f[it] * coeffX[it]).toDouble() }.toFloat()
            val sy = f.indices.sumOf { (f[it] * coeffY[it]).toDouble() }.toFloat()
            return PointF(sx, sy)
        }
    }

    // ══════════════════════════════════════════════════════
    //  IRIS OVERLAY VIEW
    // ══════════════════════════════════════════════════════

    private inner class IrisOverlayView(context: Context) : View(context) {

        private val paintStrokeOk = makePaint(Paint.Style.STROKE, 4f, Color.argb(220, 0, 230, 80))
        private val paintStrokeErr = makePaint(Paint.Style.STROKE, 4f, Color.argb(200, 255, 60, 60))
        private val paintFillOk = makePaint(Paint.Style.FILL, 0f, Color.argb(40, 0, 200, 80))
        private val paintFillErr = makePaint(Paint.Style.FILL, 0f, Color.argb(30, 255, 60, 60))
        private val paintDot = makePaint(Paint.Style.FILL, 0f, Color.WHITE)
        private val paintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val paintFlash = makePaint(Paint.Style.FILL, 0f, Color.argb(0, 255, 255, 255))
        private val paintPulse = makePaint(Paint.Style.STROKE, 3f, Color.argb(180, 233, 69, 96))
        private val paintVector =
                makePaint(Paint.Style.STROKE, 6f, Color.argb(200, 255, 200, 0)).apply {
                    strokeCap = Paint.Cap.ROUND
                }

        private fun makePaint(style: Paint.Style, sw: Float, color: Int) =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    this.style = style
                    strokeWidth = sw
                    this.color = color
                }

        private var gazeNormX = 0f
        private var gazeNormY = 0f
        private var gazeVecX = 0f
        private var gazeVecY = 0f
        private var trackingValid = false
        private var leftEar = 0f
        private var rightEar = 0f
        private var distanceMm = 0f
        private var headYaw = 0f
        private var imageWidth = 480
        private var imageHeight = 640
        private var targetX = -1f
        private var targetY = -1f
        private var pulseRadius = 0f
        private var flashAlpha = 0

        private val pulseAnim =
                ValueAnimator.ofFloat(dp2px(20f), dp2px(50f)).apply {
                    duration = 1000
                    repeatCount = ValueAnimator.INFINITE
                    repeatMode = ValueAnimator.REVERSE
                    interpolator = AccelerateDecelerateInterpolator()
                    addUpdateListener {
                        pulseRadius = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }

        fun updateGaze(g: GazeData) {
            gazeNormX = g.physicalIrisX
            gazeNormY = g.physicalIrisY
            gazeVecX = g.rawEyeSocketX
            gazeVecY = g.rawEyeSocketY
            trackingValid = g.isTrackingValid
            leftEar = g.leftEar
            rightEar = g.rightEar
            distanceMm = g.distanceMm
            headYaw = g.headYaw
            imageWidth = g.imageWidth
            imageHeight = g.imageHeight
            invalidate()
        }

        fun setCalibrationTarget(x: Float, y: Float) {
            targetX = x
            targetY = y
            invalidate()
        }
        fun flashError() {
            animateFlash(Color.RED)
        }
        fun flashSuccess() {
            animateFlash(Color.GREEN)
        }

        private fun animateFlash(color: Int) =
                ValueAnimator.ofInt(120, 0)
                        .apply {
                            duration = 500
                            addUpdateListener { a ->
                                flashAlpha = a.animatedValue as Int
                                paintFlash.color =
                                        Color.argb(
                                                flashAlpha,
                                                Color.red(color),
                                                Color.green(color),
                                                Color.blue(color)
                                        )
                                invalidate()
                            }
                        }
                        .start()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // ─ Iris kruh + gaze vector ─
            if (trackingValid || (gazeNormX > 0f && gazeNormY > 0f)) {
                val iW = if (imageWidth > 0) imageWidth.toFloat() else 480f
                val iH = if (imageHeight > 0) imageHeight.toFloat() else 640f
                val sf = max(w / iW, h / iH)
                val ox = (w - iW * sf) / 2f
                val oy = (h - iH * sf) / 2f
                val ix = ox + gazeNormX * iW * sf
                val iy = oy + gazeNormY * iH * sf

                // Gaze Vector (Před fusion/DDC zkreslením)
                // OPRAVA ZRCADLENÍ: Camera preview je zrcadlové, tudíž pohled vpravo v realitě posouvá duhovku doleva na obraze.
                val rx = -(gazeVecX - 0.5f); val ry = gazeVecY - 0.5f
                val dz = 0.03f
                val vx = if (abs(rx) > dz) rx else 0f
                val vy = if (abs(ry) > dz) ry else 0f
                // Zesílíme žlutou čáru tak, aby vizuálně dosáhla rohů z jakékoliv vzdálenosti,
                // protože uživatel se snažil čáru 'dosáhnout' až ke kalibračnímu bodu a fyzicky se namáhal.
                val vLen = max(w, h)

                if (trackingValid && (vx != 0f || vy != 0f)) {
                    val ex = ix + vx * 12.0f * vLen
                    val ey = iy + vy * 12.0f * vLen
                    canvas.drawLine(ix, iy, ex, ey, paintVector)
                    canvas.drawCircle(ex, ey, dp2px(8f), paintVector)
                }
                val r = dp2px(28f)
                canvas.drawCircle(ix, iy, r, if (trackingValid) paintFillOk else paintFillErr)
                canvas.drawCircle(ix, iy, r, if (trackingValid) paintStrokeOk else paintStrokeErr)
                canvas.drawCircle(ix, iy, dp2px(3f), paintDot)
            }

            // ─ EAR / status ─
            paintText.color =
                    if (trackingValid) Color.argb(220, 0, 230, 80) else Color.argb(200, 255, 80, 80)
            paintText.textSize = dp2px(11f)
            canvas.drawText(
                    if (trackingValid)
                            "👁 L:${String.format("%.2f",leftEar)} R:${String.format("%.2f",rightEar)}" +
                                    " | D:${String.format("%.0f",distanceMm)}mm | Y:${String.format("%.2f",headYaw)}"
                    else "❌ Oko ztraceno",
                    dp2px(8f),
                    dp2px(18f),
                    paintText
            )

            // ─ Kalibrační target pulz ─
            if (targetX >= 0f && targetY >= 0f)
                    canvas.drawCircle(targetX, targetY, pulseRadius, paintPulse)

            // ─ Flash overlay ─
            if (flashAlpha > 0) canvas.drawRect(0f, 0f, w, h, paintFlash)
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pulseAnim.cancel()
        }
    }
}
