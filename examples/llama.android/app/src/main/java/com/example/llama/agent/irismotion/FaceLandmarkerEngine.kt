package com.example.llama.agent.irismotion

import android.content.Context
import android.graphics.Matrix
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.example.llama.SettingsManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * GazeData — Immutable state object zajišťující integritu dat v pipeline.
 *
 * @param x Normalizovaná X-ová souřadnice gaze (0.0–1.0), korigovaná o rotaci hlavy
 * @param y Normalizovaná Y-ová souřadnice gaze (0.0–1.0), korigovaná o rotaci hlavy
 * @param isTrackingValid True = alespoň jedno oko je otevřené a sledovatelné
 * @param timestamp System.currentTimeMillis() v momentě detekce
 * @param leftEar Raw EAR levého oka (zpětná kompatibilita pro CalibrationOverlay)
 * @param rightEar Raw EAR pravého oka (zpětná kompatibilita)
 * @param isLeftEyeClosed True = levé oko zavřeno (z pohledu uživatele) přesahující Blink Threshold
 * @param isRightEyeClosed True = pravé oko zavřeno (z pohledu uživatele) přesahující Blink
 * Threshold
 * @param physicalIrisX Absolutní X pozice středu duhovky v normalizovaném prostoru kamery
 * @param physicalIrisY Absolutní Y pozice středu duhovky v normalizovaném prostoru kamery
 * @param leftBlinkScore Blendshape score pro eyeBlinkLeft (0.0 = otevřeno, 1.0 = zavřeno)
 * @param rightBlinkScore Blendshape score pro eyeBlinkRight
 * @param headYaw Rotace hlavy vlevo/vpravo v radiánech (z-osa uší)
 * @param headPitch Náklon hlavy nahoru/dolů v radiánech (z-osa čelo-brada)
 * @param headRoll Náklon hlavy ke straně v radiánech (6DoF — z transformační matice)
 * @param headTranslateX Translace hlavy vlevo/vpravo (6DoF — normalizovaná)
 * @param headTranslateY Translace hlavy nahoru/dolů (6DoF — normalizovaná)
 * @param headTranslateZ Translace hlavy dopředu/dozadu (6DoF — normalizovaná)
 * @param distanceMm Odhadovaná vzdálenost obličeje od kamery v milimetrech
 */
data class GazeData(
        val x: Float,
        val y: Float,
        val isTrackingValid: Boolean,
        val timestamp: Long,
        val leftEar: Float = 0f,
        val rightEar: Float = 0f,
        val isLeftEyeClosed: Boolean = false,
        val isRightEyeClosed: Boolean = false,
        // physicalIrisX/Y: absolutní X pozice iris v normalizovaném prostoru kamery.
        // ZRCADLENO (1f - raw) pro přední kameru — MediaPipe vrací souřadnice z pohledu kamery,
        // ale uživatel vidí zrcadlený obraz → pravá iris uživatele = X blíže 1.0 po zrcadlení.
        val physicalIrisX: Float = 0f,
        val physicalIrisY: Float = 0f,
        // rawEyeSocketX/Y: pozice duhovky uvnitř oční jamky (0=vlevo, 0.5=střed, 1=vpravo)
        // PŘED DDC a Gaze Fusion kompenzací — přímý vstup pro gaze vektor vizualizaci.
        // Vektor = rawEyeSocketX - 0.5 (bez zkreslení z DDC/fusion).
        val rawEyeSocketX: Float = 0.5f,
        val rawEyeSocketY: Float = 0.5f,
        // Anti-Snapback P2: Vzdálenost víček v 3D prostoru
        val eyeOpenness3D: Float = 1.0f,
        // Master IT Fix: Nulová Cross-Coupling kontaminace okem vs. hlavou
        val blendshapeEyeX: Float = 0.5f,
        val blendshapeEyeY: Float = 0.5f,
        // Sensor Fusion: Neural Gaze Vector z eyeLook* Blendshapes (NPU-trénovaná neuronová síť)
        // Diferenciální vektor: lookRight - lookLeft (X), lookDown - lookUp (Y).
        // Rozsah: [-1.0, +1.0] normalizováno na [0.0, 1.0] (0.5 = střed).
        val neuralGazeX: Float = 0.5f,
        val neuralGazeY: Float = 0.5f,
        val leftBlinkScore: Float = 0f,
        val rightBlinkScore: Float = 0f,
        val headYaw: Float = 0f,
        val headPitch: Float = 0f,
        // 6DoF rozšíření — extrahováno z MediaPipe facialTransformationMatrixes
        val headRoll: Float = 0f,
        val headTranslateX: Float = 0f,
        val headTranslateY: Float = 0f,
        val headTranslateZ: Float = 0f,
        val distanceMm: Float = 0f,
        // Saccadic Suppression: Informace o probíhajícím rychlém pohybu oka/hlavy
        val isSaccadeInProgress: Boolean = false,
        // Microsaccade Filtering: Vyhlazená hodnota očního otevření (0.0 - 1.0)
        val eyeOpennessSmooth: Float = 1.0f,
        // Aktuální rozlišení snímku z kamery pro přesný výpočet fyzického overlay v
        // CalibrationActivity
        val imageWidth: Int = 480,
        val imageHeight: Int = 640,
        // Facial Expressions (Smile, Frown, Eyebrows)
        val smileScore: Float = 0f,
        val cheekSquintScore: Float = 0f, // AU6 pro Duchennův marker
        val frownScore: Float = 0f,
        val browUpScore: Float = 0f
)

/**
 * FaceLandmarkerEngine — Plnohodnotný 3D iris tracking engine nad MediaPipe Face Landmarker.
 *
 * Pipeline: CameraX (přední kamera, RGBA) → MediaPipe (GPU/CPU fallback) → GazeData callback
 *
 * Funkce (dle Kritické Analýzy):
 * 1. Blendshapes pro blink detekci (nahrazuje nespolehlivý 2D EAR výpočet)
 * 2. 3D Eye-Socket mapping: pozice duhovky uvnitř oční jamky, nikoli absolutní poloha
 * 3. Head Pose Estimation: Yaw z uší (234/454), Pitch z čela/brady (10/152)
 * 4. Gaze Fusion: eliminace vlivu rotace hlavy na pozici kurzoru
 * 5. Dynamická Distance Compensation (DDC): normalizace gaze na referenční vzdálenost
 * 6. Thermal Throttle: pozastavení trackingu při přehřátí zařízení
 * 7. EAR legacy: zachován jako zpětná kompatibilita pro CalibrationOverlay vizualizaci
 *
 * Engine NEPOTŘEBUJE CameraX přímo — voláš processFrame() s Bitmap framem.
 */
class FaceLandmarkerEngine(
        private val context: Context,
        /** 0 = průměr obou očí, 1 = jen levé, 2 = jen pravé */
        var trackedEyeMode: Int = 0,
        private val onResult: (gazeData: GazeData) -> Unit,
        private val onError: ((error: String) -> Unit)? = null
) {
    private val settingsManager = SettingsManager(context)

    /**
     * Head Tracking DoF přepínač: 2 = Yaw+Pitch (default, accessibility), 4 = +Roll+TranslateZ, 6 =
     * plný 6DoF (Yaw+Pitch+Roll+TX+TY+TZ). Nastavitelný z SettingsManager / IrisMotionController.
     */
    var headTrackingDoF: Int = 2
    companion object {
        private const val TAG = "FaceLandmarkerEngine"
        private const val MODEL_ASSET = "face_landmarker.task"

        // === Iris center landmark indexy ===
        private const val IRIS_LEFT_INDEX = 468 // střed levé duhovky
        private const val IRIS_RIGHT_INDEX = 473 // střed pravé duhovky

        // === Okraje levé duhovky pro výpočet šířky (distance estimation) ===
        private const val IRIS_LEFT_EDGE_L = 474
        private const val IRIS_LEFT_EDGE_R = 476

        // === Eyelid landmarks pro EAR výpočet (zpětná kompatibilita) ===
        private const val L_EYE_TOP = 159
        private const val L_EYE_BOTTOM = 145
        private const val L_EYE_LEFT = 33
        private const val L_EYE_RIGHT = 133

        private const val R_EYE_TOP = 386
        private const val R_EYE_BOTTOM = 374
        private const val R_EYE_LEFT = 362
        private const val R_EYE_RIGHT = 263

        // === Head Pose Estimation landmarks ===
        // Yaw (otáčení vlevo/vpravo): Tragus body — nejspolehlivější pro Z-ovou diferenci
        private const val HEAD_LEFT_EAR = 454 // levé ucho
        private const val HEAD_RIGHT_EAR = 234 // pravé ucho
        // Pitch (náklon nahoru/dolů): čelo a brada
        private const val HEAD_FOREHEAD = 10
        private const val HEAD_CHIN = 152

        // === Fyzická duhovka pro Distance Estimation ===
        private const val IRIS_REAL_WIDTH_MM = 11.7f

        // === Blendshapes prahy ===
        /**
         * Score nad touto hodnotou = oko se zavírá → neplatný tracking. Nyní zvýšeno na 0.85f (z
         * 0.65f), aby mírné přivření (squint) nevyplo tracking, zastavení nyní řídí primárně
         * fyziologický BlinkPhaseDetector přes EAR.
         */
        private const val BLINK_THRESHOLD = 0.85f
        /**
         * Score nad touto hodnotou = záměrné mrknutí / zavření oka → click/gesture event MediaPipe
         * blendshape eyeBlinkLeft/Right: 0.0=plně otevřeno, 1.0=plně zavřeno. Reálné záměrné
         * mrknutí typicky dosáhne 0.85-0.95.
         */
        private const val BLINK_CLICK_THRESHOLD = 0.85f

        // === EAR prahy (legacy, pro blink state machine) ===
        private const val BLINK_EAR_CLOSE = 0.10f
        private const val BLINK_EAR_OPEN = 0.20f
        private const val BLINK_MIN_DURATION_MS = 30L
        private const val BLINK_MAX_DURATION_MS = 350L

        /**
         * Vzdálenost v mm, při které je pohled kalibrován — DDC základna pro normalizaci v
         * applyDDC()
         */
        private const val DDC_REFERENCE_DIST_MM = 350f

        // === Gaze Fusion konstanty ===
        /** Alpha: jak moc headYaw koriguje X souřadnici (0.0 = žádná korekce, 1.0 = plná) */
        private const val GAZE_FUSION_ALPHA = 0.35f
        /** Beta: korekce Y souřadnice headPitchem */
        private const val GAZE_FUSION_BETA = 0.35f

        private const val MAX_NUM_FACES = 1
    }

    private var faceLandmarker: FaceLandmarker? = null
    private var isClosed = false
    private val hybridTracker = com.example.llama.agent.irismotion.kinematics.HybridGazeEstimator()

    fun updateFixationDrift(driftRate: Float) {
        hybridTracker.fixationDriftRate = driftRate
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    @Volatile private var isThrottled = false

    // Blink state machine (EAR legacy)
    private var blinkStartTimeMs = 0L
    private var isInBlink = false

    // Poslední platné souřadnice (freeze-on-blink)
    private var lastValidGazeX = 0.5f
    private var lastValidGazeY = 0.5f
    private var lastValidBlendX = 0.5f
    private var lastValidBlendY = 0.5f
    private var lastValidNeuralX = 0.5f
    private var lastValidNeuralY = 0.5f
    private var lastValidPhysX = 0.5f
    private var lastValidPhysY = 0.5f

    private var currentFrameWidth = 0
    private var currentFrameHeight = 0

    // Fyziologická amplifikace gaze vektoru — odemčeno pro runtime konfiguraci.
    // Dříve natvrdo val GAZE_AMP_X=2.0f, GAZE_AMP_Y=1.8f v processResult().
    // Snížené výchozí dle accessibility best practices (gain 0.5–5.0).
    /** Horizontální zesílení pohybu oka. Rozsah 0.3–5.0. Default 1.3. */
    var gazeAmpX: Float = 1.3f
    /** Vertikální zesílení pohybu oka. Rozsah 0.3–5.0. Default 1.2. */
    var gazeAmpY: Float = 1.2f

    // 4-Osá Citlivost (Up/Down/Left/Right)
    var ampUp = 1.7f
    var ampDown = 1.6f
    var ampLeft = 2.1f
    var ampRight = 2.1f

    // Globální engine zrcadlení (Invert os)
    var invertEngineX: Boolean = false
    var invertEngineY: Boolean = false

    // Per-eye, per-direction asymetrické multiplikátory (0.1–3.0, default 1.0 = neutrální).
    // "LeftEye" = uživatelovo levé oko (= MediaPipe Right).
    // "Left/Right" = směr pohledu uživatele po zrcadlení kamery.
    var gazeAmpLeftEyeLeft: Float = 1.0f
    var gazeAmpLeftEyeRight: Float = 1.0f
    var gazeAmpRightEyeLeft: Float = 1.0f
    var gazeAmpRightEyeRight: Float = 1.0f

    // Master IT Fix: Dynamické těžiště oka pro vycentrování pohledu bez nutnosti kalibrace rohů
    var centerXOffset: Float = 0.5f // Výchozí horizontální střed
    var centerYOffset: Float = 0.5f // Výchozí vertikální střed

    /** Zrcadlení osy X (typické pro přední kameru) */
    var isIrisMotionCameraMirrored: Boolean = true

    /**
     * Globální citlivost pohybu oka — multiplikátor efektivní amplifikace. Ovlivňuje VŠECHNY filtry
     * a celou pipeline (na rozdíl od OneEuro-specifické sensitivity). Rozsah 0.3–3.0. Default 1.0.
     */
    var globalSensitivity: Float = 1.0f

    // Fyziologické konstanty — přístupné z UI (pokročilé nastavení)
    // Fyzický klidový bod oka z pohledu MediaPipe socket mappingu.
    /** Fyziologický klidový X bod oka (0.2–0.8). Default 0.50. */
    var physioRestX: Float = 0.50f
    /** Fyziologický klidový Y bod oka (0.1–0.7). Default 0.35 (kvůli hornímu víčku). */
    var physioRestY: Float = 0.35f

    // Intrinsic škálování — duhovka nevyplňuje celý 0.0–1.0 rozsah socketu.
    // Hrubý travel oka pokrývá reálně jen ~40% šířky → base škálování pro dosažení okrajů.
    /** Intrinsic amplifikace X (1.0–5.0). Default 3.5. */
    var intrinsicAmpX: Float = 3.5f
    /** Intrinsic amplifikace Y (1.0–5.0). Default 2.5. */
    var intrinsicAmpY: Float = 2.5f

    init {
        // Načtení persistovaných nastavení
        loadSettings()

        // Master IT Fix: Nespouštět TFLite/MediaPipe inicializaci na MainThreadu, jinak to dropne 90 framů!
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            initializeLandmarker()
        }
    }

    /** Načte kalibrované parametry enginu z persistentního úložiště */
    fun loadSettings() {
        gazeAmpX = settingsManager.irisMotionGazeAmpX
        gazeAmpY = settingsManager.irisMotionGazeAmpY
        ampUp = settingsManager.irisMotionAmpUp
        ampDown = settingsManager.irisMotionAmpDown
        ampLeft = settingsManager.irisMotionAmpLeft
        ampRight = settingsManager.irisMotionAmpRight
        invertEngineX = settingsManager.irisMotionInvertX
        invertEngineY = settingsManager.irisMotionInvertY
        gazeAmpLeftEyeLeft = settingsManager.irisMotionGazeAmpLeftEyeLeft
        gazeAmpLeftEyeRight = settingsManager.irisMotionGazeAmpLeftEyeRight
        gazeAmpRightEyeLeft = settingsManager.irisMotionGazeAmpRightEyeLeft
        gazeAmpRightEyeRight = settingsManager.irisMotionGazeAmpRightEyeRight
        centerXOffset = settingsManager.irisMotionCenterXOffset
        centerYOffset = settingsManager.irisMotionCenterYOffset
        physioRestX = settingsManager.irisMotionPhysioRestX
        physioRestY = settingsManager.irisMotionPhysioRestY
        intrinsicAmpX = settingsManager.irisMotionIntrinsicAmpX
        intrinsicAmpY = settingsManager.irisMotionIntrinsicAmpY
        Log.i(TAG, "⚙️ FaceLandmarkerEngine: Nastavení načtena ze SettingsManageru")
    }

    /** Uloží aktuální naladěné parametry enginu (např. z kalibračního UI) zpět do úložiště */
    fun saveSettings() {
        settingsManager.irisMotionGazeAmpX = gazeAmpX
        settingsManager.irisMotionGazeAmpY = gazeAmpY
        settingsManager.irisMotionAmpUp = ampUp
        settingsManager.irisMotionAmpDown = ampDown
        settingsManager.irisMotionAmpLeft = ampLeft
        settingsManager.irisMotionAmpRight = ampRight
        settingsManager.irisMotionInvertX = invertEngineX
        settingsManager.irisMotionInvertY = invertEngineY
        settingsManager.irisMotionGazeAmpLeftEyeLeft = gazeAmpLeftEyeLeft
        settingsManager.irisMotionGazeAmpLeftEyeRight = gazeAmpLeftEyeRight
        settingsManager.irisMotionGazeAmpRightEyeLeft = gazeAmpRightEyeLeft
        settingsManager.irisMotionGazeAmpRightEyeRight = gazeAmpRightEyeRight
        settingsManager.irisMotionCenterXOffset = centerXOffset
        settingsManager.irisMotionCenterYOffset = centerYOffset
        settingsManager.irisMotionPhysioRestX = physioRestX
        settingsManager.irisMotionPhysioRestY = physioRestY
        settingsManager.irisMotionIntrinsicAmpX = intrinsicAmpX
        settingsManager.irisMotionIntrinsicAmpY = intrinsicAmpY
        Log.i(TAG, "💾 FaceLandmarkerEngine: Nastavení uložena do SettingsManageru")
    }

    // ==================== INIT ====================

    private fun getSafeBaseOptionsBuilder(): BaseOptions.Builder {
        val modelFile = File(context.filesDir, MODEL_ASSET)
        if (!modelFile.exists()) {
            Log.i(TAG, "📦 Extrahuji $MODEL_ASSET do filesDir pro bezpečný 16KB mmap...")
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        val modelBuffer: ByteBuffer = FileInputStream(modelFile).use { stream ->
            stream.channel.map(FileChannel.MapMode.READ_ONLY, 0L, modelFile.length())
        }
        return BaseOptions.builder().setModelAssetBuffer(modelBuffer)
    }

    private fun initializeLandmarker() {
        try {
            val base =
                    getSafeBaseOptionsBuilder()
                            .setDelegate(Delegate.GPU)
                            .build()

            val options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setNumFaces(MAX_NUM_FACES)
                            .setOutputFaceBlendshapes(true)
                            .setOutputFacialTransformationMatrixes(true)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setResultListener { result: FaceLandmarkerResult, _ ->
                                processResult(result)
                            }
                            .setErrorListener { err ->
                                Log.e(TAG, "❌ MediaPipe LIVE_STREAM error: ${err.message}")
                                onError?.invoke(err.message ?: "Unknown MediaPipe error")
                            }
                            .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.i(TAG, "✅ FaceLandmarker inicializován (GPU, Blendshapes=true, model=$MODEL_ASSET)")
        } catch (e: Exception) {
            // GPU může ticho selhat na některých zařízeních — logujeme na ERROR pro
            // snadnější diagnózu přes logcat
            Log.e(TAG, "❌ GPU init selhal, přepínaji na CPU fallback. Příčina: ${e.message}")
            initializeLandmarkerCpu()
        }
    }

    private fun initializeLandmarkerCpu() {
        try {
            val base =
                    getSafeBaseOptionsBuilder()
                            .setDelegate(Delegate.CPU)
                            .build()

            val options =
                    FaceLandmarker.FaceLandmarkerOptions.builder()
                            .setBaseOptions(base)
                            .setNumFaces(MAX_NUM_FACES)
                            .setOutputFaceBlendshapes(true)
                            .setOutputFacialTransformationMatrixes(true)
                            .setRunningMode(RunningMode.LIVE_STREAM)
                            .setResultListener { result: FaceLandmarkerResult, _ ->
                                processResult(result)
                            }
                            .setErrorListener { err ->
                                onError?.invoke(err.message ?: "Unknown error")
                            }
                            .build()

            faceLandmarker = FaceLandmarker.createFromOptions(context, options)
            Log.w(TAG, "⚠️ FaceLandmarker běží na CPU (fallback)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ CPU fallback selhal — IrisMotion nedostupný", e)
            onError?.invoke("FaceLandmarker initialization failed: ${e.message}")
        }
    }

    /**
     * Zpracuje jeden video frame z CameraX. MUSÍ být voláno z non-Main thread (camera executor).
     *
     * KRITICKÁ OPRAVA (MRE analýza Google referenční implementace): CameraX ImageProxy vrací RAW
     * senzorový obraz, který je OTOČENÝ o 90°/270° a u přední kamery NEZRCADLENÝ. MediaPipe pracuje
     * s normalizovanými souřadnicemi relativně k vstupnímu obrázku. Pokud pošleme
     * neotočený/nezrcadlený obraz, veškeré souřadnice landmarků budou v jiném souřadnicovém systému
     * než PreviewView (která obraz automaticky otáčí a zrcadlí). Proto MUSÍME bitmapu transformovat
     * PŘED odesláním do MediaPipe — přesně tak, jak to dělá Google v FaceLandmarkerHelper.kt.
     *
     * @param bitmap RGBA Bitmap z CameraX ImageProxy (neotočená, nezrcadlená!)
     * @param rotationDegrees imageProxy.imageInfo.rotationDegrees (typicky 90 nebo 270)
     * @param isFrontCamera true pro přední kameru (zrcadlení osy X)
     */
    fun processFrame(
            bitmap: android.graphics.Bitmap,
            rotationDegrees: Int = 0,
            isFrontCamera: Boolean = true
    ) {
        if (isClosed || faceLandmarker == null) {
            Log.w(TAG, "processFrame: engine uzavřen nebo neincializován")
            return
        }
        if (checkThermalThrottle()) return

        if (bitmap.isRecycled || bitmap.width == 0 || bitmap.height == 0) {
            Log.w(TAG, "processFrame: neplatný bitmap (recycled nebo prázdný)")
            return
        }

        // ── Google-kompatibilní transformace (FaceLandmarkerHelper.kt) ──────────────
        // 1. Rotace o rotationDegrees (senzor → portret/landscape)
        // 2. Zrcadlení osy X pro přední kameru (postScale -1f)
        val matrix =
                Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                    if (isFrontCamera) {
                        // Zrcadlení kolem středu obrázku — po rotaci mají rozměry swapnuté!
                        postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
                    }
                }
        val rotatedBitmap =
                android.graphics.Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        matrix,
                        true
                )

        // Aktualizujeme rozměry po rotaci (mohou se prohodit W↔H při 90°/270°)
        currentFrameWidth = rotatedBitmap.width
        currentFrameHeight = rotatedBitmap.height

        try {
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            // Google standard: SystemClock.uptimeMillis() (milisekundy od bootu, monotonicky
            // rostoucí)
            val frameTimeMs = SystemClock.uptimeMillis()
            faceLandmarker?.detectAsync(mpImage, frameTimeMs)
        } catch (e: Exception) {
            Log.w(TAG, "processFrame detectAsync error: ${e.message}")
        } finally {
            // [MASTER IT FIX]: Prevence masivního OOM Memory Leaku.
            // Zajištění recyklace zprostředkovatelské transformované bitmapy bez porušení
            // integrity originálního bufferu Controlleru.
            if (rotatedBitmap !== bitmap && !rotatedBitmap.isRecycled) {
                rotatedBitmap.recycle()
            }
        }
    }

    // ==================== RESULT PROCESSING ====================

    // Diagnostický čítač framů (loguje každých ~5s při 30fps)
    private var frameCount = 0L

    private fun processResult(result: FaceLandmarkerResult) {
        val timestamp = System.currentTimeMillis()
        frameCount++

        val landmarks = result.faceLandmarks().firstOrNull()

        // BLENDSHAPES: result.faceBlendshapes() vrací Optional<List<List<Category>>>
        // v MediaPipe 0.10.x — vnější list je per-face, vnitřní list jsou samotné Category.
        // Typ Classifications v této verzi MediaPipe neexistuje.
        val blendshapesList: List<List<Category>>? = result.faceBlendshapes().orElse(null)
        val blendshapes: List<Category>? = blendshapesList?.firstOrNull()

        // Periodický diagnostický log (každých 5s)
        if (frameCount % (IrisMotionController.cameraFps * 5L) == 1L) {
            Log.d(
                    TAG,
                    "🔎 DIAG frame=$frameCount: " +
                            "face=${landmarks != null}, " +
                            "blendshapes=${blendshapes?.size ?: "null"}, " +
                            "landmarkCount=${landmarks?.size ?: 0}"
            )
        }

        // Žádný obličej → signalizace invalid
        if (landmarks == null) {
            if (frameCount % (IrisMotionController.cameraFps * 5L) == 1L) {
                Log.w(TAG, "📷 Žádný obličej detekován — zkontroluj osvětlení a kameru")
            }
            onResult(GazeData(0f, 0f, isTrackingValid = false, timestamp = timestamp))
            return
        }

        // Blendshapes null = model nevrací blendshapes (setOutputFaceBlendshapes=true musí být!)
        if (blendshapes == null) {
            Log.w(TAG, "⚠️ Blendshapes chybí — setOutputFaceBlendshapes(true) ověřeno?")
            onResult(GazeData(0f, 0f, isTrackingValid = false, timestamp = timestamp))
            return
        }

        // Guard: potřebujeme body až do indexu 476 (okraje duhovky)
        if (landmarks.size <= 476) {
            Log.w(TAG, "Nedostatečný počet landmarks: ${landmarks.size}")
            onResult(GazeData(0f, 0f, isTrackingValid = false, timestamp = timestamp))
            return
        }

        // 👁️👁️👁️ 1. BLENDSHAPES: Eye Blink + Neural Gaze Direction + Expressions 👁️👁️👁️
        var leftBlinkScore = 0f
        var rightBlinkScore = 0f

        // Expressions
        var smileLeft = 0f
        var smileRight = 0f
        var cheekSquintLeft = 0f
        var cheekSquintRight = 0f
        var frownLeft = 0f
        var frownRight = 0f
        var browUpLeft = 0f
        var browUpRight = 0f

        // Sensor Fusion: 8 eyeLook* Blendshapes pro Neural Gaze Vector
        // MediaPipe indices 11-18: Svalové napětí okohybných svalů (NPU trénováno)
        var eyeLookInLeft = 0f
        var eyeLookInRight = 0f
        var eyeLookOutLeft = 0f
        var eyeLookOutRight = 0f
        var eyeLookUpLeft = 0f
        var eyeLookUpRight = 0f
        var eyeLookDownLeft = 0f
        var eyeLookDownRight = 0f

        for (category in blendshapes) {
            when (category.categoryName()) {
                "eyeBlinkLeft" -> leftBlinkScore = category.score()
                "eyeBlinkRight" -> rightBlinkScore = category.score()
                // Neural Gaze Direction (8 svalových tenzí)
                "eyeLookInLeft" -> eyeLookInLeft = category.score()
                "eyeLookInRight" -> eyeLookInRight = category.score()
                "eyeLookOutLeft" -> eyeLookOutLeft = category.score()
                "eyeLookOutRight" -> eyeLookOutRight = category.score()
                "eyeLookUpLeft" -> eyeLookUpLeft = category.score()
                "eyeLookUpRight" -> eyeLookUpRight = category.score()
                "eyeLookDownLeft" -> eyeLookDownLeft = category.score()
                "eyeLookDownRight" -> eyeLookDownRight = category.score()
                
                // Facial Expressions
                "mouthSmileLeft" -> smileLeft = category.score()
                "mouthSmileRight" -> smileRight = category.score()
                "cheekSquintLeft" -> cheekSquintLeft = category.score()
                "cheekSquintRight" -> cheekSquintRight = category.score()
                "browDownLeft" -> frownLeft = category.score()
                "browDownRight" -> frownRight = category.score()
                "browOuterUpLeft" -> browUpLeft = category.score()
                "browOuterUpRight" -> browUpRight = category.score()
            }
        }

        if (frameCount % 15L == 0L) {
            val topBlendshapes = blendshapes.sortedByDescending { it.score() }.take(8).joinToString { "${it.categoryName()}:${String.format("%.2f", it.score())}" }
            Log.w(TAG, "Top Blendshapes at frame $frameCount: $topBlendshapes")
            Log.w(TAG, "Extracted Scores -> SmileL: $smileLeft, SmileR: $smileRight, FrownL: $frownLeft, BrowUpL: $browUpLeft")
        }

        // --- Výpočet výsledných Expression Score ---
        val avgSmile = (smileLeft + smileRight) / 2f
        val avgCheekSquint = (cheekSquintLeft + cheekSquintRight) / 2f
        val avgFrown = (frownLeft + frownRight) / 2f
        val avgBrowUp = (browUpLeft + browUpRight) / 2f

        // ─── 1b. NEURAL GAZE VECTOR (Sensor Fusion kanál z NPU) ──────────────────────
        // Diferenciální výpočet: svalové napětí "ven" minus "dovnitř" = absolutní směr pohledu.
        // MediaPipe "In" = pohled k nosu, "Out" = pohled od nosu.
        // Pro X-osu: Right Out + Left In = pohled doprava; Right In + Left Out = pohled doleva.
        // Pro Y-osu: Down = pohled dolů; Up = pohled nahoru.
        val neuralRawX =
                ((eyeLookOutRight + eyeLookInLeft) - (eyeLookInRight + eyeLookOutLeft)) / 2f

        // P8.2 Oprava Asymetrie: MediaPipe chronicky podhodnocuje blendshapes "eyeLookUp"
        // (hodnoty sotva dosáhnou 0.2), zatímco "eyeLookDown" snadno dává 0.8.
        // Násobíme Up hodnoty koeficientem 2.5x, aby kurzor mohl jezdit plynule i nahoru.
        val neuralRawY =
                ((eyeLookDownLeft + eyeLookDownRight) - ((eyeLookUpLeft + eyeLookUpRight) * 2.5f)) /
                        2f
        // Normalizace z [-1.0, +1.0] na [0.0, 1.0] (0.5 = střed pohledu)
        val neuralGazeX = 0.5f + neuralRawX
        val neuralGazeY = 0.5f + neuralRawY

        // ─── 2. EAR: zachován pro CalibrationOverlay vizualizaci a nyni primarni detekci mrkani ──────────────
        val leftEar =
                calculateEAR(
                        landmarks[L_EYE_TOP],
                        landmarks[L_EYE_BOTTOM],
                        landmarks[L_EYE_LEFT],
                        landmarks[L_EYE_RIGHT]
                )
        val rightEar =
                calculateEAR(
                        landmarks[R_EYE_TOP],
                        landmarks[R_EYE_BOTTOM],
                        landmarks[R_EYE_LEFT],
                        landmarks[R_EYE_RIGHT]
                )

        // Platnost oka: Jelikož MediaPipe zrcadlí (Right = Fyzické Levé) křížíme platnost
        // trackedEyeMode: 0=Obě, 1=Levé, 2=Pravé.
        // Takže UI Levé (1) vyžaduje MediaPipe Right!
        // Platnost oka pro TRASOVÁNÍ (nikoliv pro klik): Musíme použít Blendshapes!
        // Geometrický EAR je příliš nestabilní a pád pod 0.10f způsobí výpadek souřadnic
        // a skok kurzoru na telefonu. Blendshapes pro úsměv dosahují max ~0.40f, takže
        // bezpečně projdou pod limitem BLINK_THRESHOLD (0.85f) a tracking nezamrzne.
        val isLeftValid =
                leftBlinkScore < BLINK_THRESHOLD && (trackedEyeMode == 0 || trackedEyeMode == 2)
        val isRightValid =
                rightBlinkScore < BLINK_THRESHOLD && (trackedEyeMode == 0 || trackedEyeMode == 1)

        // BUG FIX z BuG.md: ignorujeme blendshapes a vyuzivame prostorovy EAR, ale pro 
        // zachycení extrémního svalového záměru respektujeme i BLINK_CLICK_THRESHOLD
        val isLeftEyeClosed = leftEar < BLINK_EAR_CLOSE || leftBlinkScore > BLINK_CLICK_THRESHOLD
        val isRightEyeClosed = rightEar < BLINK_EAR_CLOSE || rightBlinkScore > BLINK_CLICK_THRESHOLD

        // Zrcadlení: API poskytuje fyzické stavy. MediaPipe Right = Fyzické Levé.
        // Odstraněny dočasné proměnné pro inlining přímo do GazeData inicializace.

        // Detekce plného mrknutí (state machine) pro účely logování/metrik
        val avgEar = (leftEar + rightEar) / 2f
        if (detectBlink(avgEar, timestamp)) {
            Log.d(TAG, "👁️ Plné mrknutí detekováno (EAR state machine)")
        }

        // ─── 3. DISTANCE ESTIMATION: vzdálenost v mm přes 11.7mm šířku duhovky ──────────
        // Body 474 a 476 jsou levý/pravý okraj levé duhovky (spolehlivý zdroj)
        val irisWidthNorm = abs(landmarks[IRIS_LEFT_EDGE_R].x() - landmarks[IRIS_LEFT_EDGE_L].x())
        val irisWidthPx = irisWidthNorm * currentFrameWidth
        // Guard: irisWidthPx < 2px = oko zavřené / mimo záběr / kamera cold-start → výsledek
        // nesmyslný
        // Aproximace focal length pro přední kameru telefonu (empiricky ověřeno)
        val focalLengthPx = minOf(currentFrameWidth, currentFrameHeight) * 1.40625f
        val distanceMm =
                if (irisWidthPx > 2f) {
                    (focalLengthPx * IRIS_REAL_WIDTH_MM / irisWidthPx).coerceIn(150f, 600f)
                } else {
                    // Fallback na poslední platnou vzdálenost nebo defaultní értéket (50cm)
                    500f
                }

        // ─── 4. HEAD POSE ESTIMATION: 2DoF/4DoF/6DoF ─────────────────────────────────
        val matrices = result.facialTransformationMatrixes().orElse(null)
        val m = if (matrices != null && matrices.isNotEmpty()) matrices[0] else null

        var headYaw: Float
        var headPitch: Float
        var headRoll = 0f
        var headTranslateX = 0f
        var headTranslateY = 0f
        var headTranslateZ = 0f

        if (m != null && m.size >= 16) {
            // Column-major indexování: m[row + col * 4]
            // Sloupec 0: m[0]=r00, m[1]=r10, m[2]=r20, m[3]=r30
            // Sloupec 1: m[4]=r01, m[5]=r11, m[6]=r21, m[7]=r31
            // Sloupec 2: m[8]=r02, m[9]=r12, m[10]=r22, m[11]=r32
            // Sloupec 3: m[12]=tx, m[13]=ty, m[14]=tz, m[15]=1.0
            val r00 = m[0]
            val r10 = m[1]
            val r20 = m[2]
            val r21 = m[6]
            val r22 = m[10]

            // Euler úhly z rotační submatice (ZYX konvence)
            headPitch = asin((-r20).toDouble().coerceIn(-1.0, 1.0)).toFloat()
            headYaw = atan2(r10.toDouble(), r00.toDouble()).toFloat()
            headRoll = atan2(r21.toDouble(), r22.toDouble()).toFloat()

            // Translace (normalizovaná) — 4. sloupec matice (column-major: index 12,13,14)
            headTranslateX = m[12]
            headTranslateY = m[13]
            headTranslateZ = m[14]

            Log.v(
                    TAG,
                    String.format(
                            java.util.Locale.US,
                            "6DoF matrix: yaw=%.2f pitch=%.2f roll=%.2f tx=%.3f ty=%.3f tz=%.3f",
                            headYaw,
                            headPitch,
                            headRoll,
                            headTranslateX,
                            headTranslateY,
                            headTranslateZ
                    )
            )
        } else {
            // Fallback: landmark-based Yaw/Pitch (stávající logika)
            val rightEarLm = landmarks[HEAD_RIGHT_EAR]
            val leftEarLm = landmarks[HEAD_LEFT_EAR]
            headYaw =
                    atan2(
                                     (rightEarLm.z() - leftEarLm.z()).toDouble(),
                                     (rightEarLm.x() - leftEarLm.x()).toDouble()
                            )
                            .toFloat()

            val foreheadLm = landmarks[HEAD_FOREHEAD]
            val chinLm = landmarks[HEAD_CHIN]
            headPitch =
                    atan2(
                                     (chinLm.z() - foreheadLm.z()).toDouble(),
                                     (chinLm.y() - foreheadLm.y()).toDouble()
                            )
                            .toFloat()
            // Roll a translace nejsou dostupné z landmarků → zůstávají 0
        }

        // DoF filtr: maskuj data podle headTrackingDoF přepínače
        // 2DoF = pouze Yaw+Pitch, 4DoF = +Roll+TZ, 6DoF = plný
        if (headTrackingDoF < 4) {
            headRoll = 0f
            headTranslateX = 0f
            headTranslateY = 0f
            headTranslateZ = 0f
        } else if (headTrackingDoF < 6) {
            headTranslateX = 0f
            headTranslateY = 0f
        }

        // ─── 5. Pokud obě oči neplatné → vrátíme poslední platnou pozici ─────────────
        if (!isLeftValid && !isRightValid) {
            onResult(
                    GazeData(
                            x = lastValidGazeX,
                            y = lastValidGazeY,
                            isTrackingValid = false,
                            timestamp = timestamp,
                            leftEar = leftEar,
                            rightEar = rightEar,
                            isLeftEyeClosed = isRightEyeClosed, // Zrcadlení
                            isRightEyeClosed = isLeftEyeClosed, // Zrcadlení
                            physicalIrisX = lastValidPhysX,
                            physicalIrisY = lastValidPhysY,
                            blendshapeEyeX = lastValidBlendX,
                            blendshapeEyeY = lastValidBlendY,
                            neuralGazeX = lastValidNeuralX,
                            neuralGazeY = lastValidNeuralY,
                            leftBlinkScore = leftBlinkScore,
                            rightBlinkScore = rightBlinkScore,
                            headYaw = headYaw,
                            headPitch = headPitch,
                            headRoll = headRoll,
                            headTranslateX = headTranslateX,
                            headTranslateY = headTranslateY,
                            headTranslateZ = headTranslateZ,
                            distanceMm = distanceMm,
                            imageWidth = currentFrameWidth,
                            imageHeight = currentFrameHeight,
                            smileScore = avgSmile,
                            cheekSquintScore = avgCheekSquint,
                            frownScore = avgFrown,
                            browUpScore = avgBrowUp
                    )
            )
            return
        }

        // ─── 6. GAZE EXTRACTION (3D IK PCCR-Like s Saccade Gating) ─────────────────────
        // Třídní proměnná settingsManager využita místo lokální instance
        val res =
                hybridTracker.estimateGaze(
                        result = result,
                        imageWidth = currentFrameWidth,
                        imageHeight = currentFrameHeight,
                        trackedEyeMode = trackedEyeMode,
                        isMirrored = isIrisMotionCameraMirrored,
                        headYaw = headYaw,
                        headPitch = headPitch,
                        headTrackingDoF = headTrackingDoF,
                        fusionAlpha = GAZE_FUSION_ALPHA,
                        fusionBeta = GAZE_FUSION_BETA,
                        saccadeVelocityThreshold = settingsManager.irisMotionSaccadeVelocity,
                        saccadeFixationBox = settingsManager.irisMotionSaccadeBox,
                        neuralGazeX = neuralGazeX,
                        neuralGazeY = neuralGazeY,
                        neuralWeight = settingsManager.irisMotionNeuralWeight
                )

        val gazeFusionX = res.x
        val gazeFusionY = res.y
        val eyeOpenness3D = res.openness

        // Pro vizualizaci overlay necháme fyzický střed duhovky z 2D obrazu
        val physX = (landmarks[IRIS_LEFT_INDEX].x() + landmarks[IRIS_RIGHT_INDEX].x()) / 2f
        val physY = (landmarks[IRIS_LEFT_INDEX].y() + landmarks[IRIS_RIGHT_INDEX].y()) / 2f

        // --- MASTER IT FIX: Fyziologické Základny & Intrinsic Amplifikace ---
        // 1. Získání odchylky od fyzického středu oka (runtime konfigurovatelné)
        // Upozornění: Po přepracování na čisté 3D IK dává systém obří a přesné výchylky (range)
        // oproti starému zašuměnému
        // 2D Socket Mappingu. Pro zachování kompatibility s uživatelsky nastavenými násobiči (kolem
        // 3.0x - 5.0x pro osu)
        // musíme surový čistý vektor zbrzdit.
        // 0.2x ratio funguje naprosto perfektně jako 2x pomalejší verze původního 0.4x, které se
        // zdálo "hyper rychlé".
        val dx = (gazeFusionX - physioRestX) * 0.5f
        val dy = (gazeFusionY - physioRestY) * 0.6f

        // 2. Aplikace asymetrického per-eye zesilovače (UI nastavení)
        val perEyeMultiplier =
                when (trackedEyeMode) {
                    1 -> if (dx < 0f) gazeAmpLeftEyeLeft else gazeAmpLeftEyeRight
                    2 -> if (dx < 0f) gazeAmpRightEyeLeft else gazeAmpRightEyeRight
                    else -> {
                        val leftM = if (dx < 0f) gazeAmpLeftEyeLeft else gazeAmpLeftEyeRight
                        val rightM = if (dx < 0f) gazeAmpRightEyeLeft else gazeAmpRightEyeRight
                        (leftM + rightM) / 2f
                    }
                }

        // 4-Osá specifická citlivost dle směru pohledu
        val cardinalX = if (dx < 0f) ampLeft else ampRight
        val cardinalY = if (dy < 0f) ampUp else ampDown

        // 3. Výpočet finálního efektivního zesílení
        val rawAmpX = intrinsicAmpX * gazeAmpX * cardinalX * perEyeMultiplier * globalSensitivity
        val rawAmpY = intrinsicAmpY * gazeAmpY * cardinalY * globalSensitivity

        // Aplikace striktního fyzikálního limitu na finální absolutní zesílení.
        val effectiveAmpX = rawAmpX.coerceIn(0.1f, 40.0f)
        val effectiveAmpY = rawAmpY.coerceIn(0.1f, 40.0f)

        // Transformace do normálového prostoru obrazovky.
        var finalX = (dx * effectiveAmpX) + centerXOffset
        var finalY = (dy * effectiveAmpY) + centerYOffset

        // Aplikace DDC (Dynamic Distance Compensation) pro stabilizaci zisku podle vzdálenosti uživatele od kamery
        val ddcResult = applyDDC(finalX, finalY, distanceMm)
        finalX = ddcResult.first
        finalY = ddcResult.second

        // Globální inverze os (Engine Mirroring / Invert X/Y)
        if (invertEngineX) {
            finalX = 1.0f - finalX
        }
        if (invertEngineY) {
            finalY = 1.0f - finalY
        }

        // Zpětná kompatibilita pro GazeData - sjednotíme hodnoty
        val finalBlendX = finalX
        val finalBlendY = finalY

        lastValidGazeX = finalX
        lastValidGazeY = finalY
        lastValidBlendX = finalBlendX
        lastValidBlendY = finalBlendY
        lastValidNeuralX = neuralGazeX
        lastValidNeuralY = neuralGazeY
        lastValidPhysX = physX
        lastValidPhysY = physY

        onResult(
                GazeData(
                        x = finalX,
                        y = finalY,
                        isTrackingValid = true,
                        timestamp = timestamp,
                        leftEar = leftEar,
                        rightEar = rightEar,
                        isLeftEyeClosed = isRightEyeClosed, // Zrcadlení fyzického stavu
                        isRightEyeClosed = isLeftEyeClosed, // Zrcadlení fyzického stavu
                        physicalIrisX = physX,
                        physicalIrisY = physY,
                        rawEyeSocketX = gazeFusionX,
                        rawEyeSocketY = gazeFusionY,
                        eyeOpenness3D = eyeOpenness3D,
                        isSaccadeInProgress = res.isSaccade,
                        eyeOpennessSmooth = res.openness,
                        blendshapeEyeX = finalBlendX,
                        blendshapeEyeY = finalBlendY,
                        neuralGazeX = neuralGazeX,
                        neuralGazeY = neuralGazeY,
                        leftBlinkScore = leftBlinkScore,
                        rightBlinkScore = rightBlinkScore,
                        headYaw = headYaw,
                        headPitch = headPitch,
                        headRoll = headRoll,
                        headTranslateX = headTranslateX,
                        headTranslateY = headTranslateY,
                        headTranslateZ = headTranslateZ,
                        distanceMm = distanceMm,
                        imageWidth = currentFrameWidth,
                        imageHeight = currentFrameHeight,
                        smileScore = avgSmile,
                        cheekSquintScore = avgCheekSquint,
                        frownScore = avgFrown,
                        browUpScore = avgBrowUp
                )
        )
    }

    // ==================== DDC: Dynamic Distance Compensation ====================

    /**
     * Normalizuje gaze souřadnice na referenční vzdálenost [DDC_REFERENCE_DIST_MM].
     *
     * Pokud uživatel sedí blíže (menší distanceMm), pohyb oka covpokrývá menší plochu kamery. DDC
     * tento efekt kompenzuje: malý pohyb oka = stejný posun kurzoru bez ohledu na vzdálenost.
     *
     * Vzorec: compensatedGaze = center + (rawGaze - center) * distanceFactor kde distanceFactor =
     * clampedDist / DDC_REFERENCE_DIST_MM
     *
     * @param gazeX Normalizovaná X-ová souřadnice po Gaze Fusion [0,1]
     * @param gazeY Normalizovaná Y-ová souřadnice po Gaze Fusion [0,1]
     * @param distanceMm Aktuální odhadovaná vzdálenost od kamery v mm
     * @return Pair(kompenzovanéX, kompenzovanéY)
     */
    private fun applyDDC(gazeX: Float, gazeY: Float, distanceMm: Float): Pair<Float, Float> {
        val clampedDist = distanceMm.coerceIn(100f, 800f)
        val distFactor = clampedDist / DDC_REFERENCE_DIST_MM
        // Offset od středu (0.5), vynásoben faktorem vzdálenosti, vrácen na normalizovanou
        // souřadnici
        val compX = (((gazeX - 0.5f) * distFactor) + 0.5f).coerceIn(0f, 1f)
        val compY = (((gazeY - 0.5f) * distFactor) + 0.5f).coerceIn(0f, 1f)
        return Pair(compX, compY)
    }

    // eyeSocketMap() ODSTRANĚNA — nahrazena eyeLook Blendshapes (sekce 6 výše)

    /**
     * Eye Aspect Ratio s kompenzací poměru stran (AR) — Soukupova & Cech (2016) + produkční fix.
     *
     * PROBLÉM: MediaPipe vrací normalizované souřadnice 0.0–1.0 vztažené na celý frame. Při 640×480
     * platí: 0.1 na X = 48px, ale 0.1 na Y = 64px → Euklidovská vzdálenost bez AR je matematicky
     * chybná. Zakloněná hlava způsobí falešné "zavřené oko".
     *
     * FIX: Škálujeme X-složku vzdáleností faktorem AR = frameWidth/frameHeight. Pro 640×480: AR =
     * 640/480 = 1.333 → X-vzdálenosti jsou vyšší (fyzicky správné px poměry).
     *
     * Zachován pro vizualizaci v CalibrationOverlay (EAR bary v debug UI). Primární blink detekce:
     * Blendshapes (bez tohoto problému).
     */
    private fun calculateEAR(
            top: NormalizedLandmark,
            bottom: NormalizedLandmark,
            left: NormalizedLandmark,
            right: NormalizedLandmark
    ): Float {
        // AR kompenzace: fyzický poměr stran framu — klíčový pro geometrickou korektnost EAR
        // currentFrameWidth/currentFrameHeight jsou nastaveny v processFrame() před voláním EAR
        val ar =
                if (currentFrameHeight > 0) currentFrameWidth.toFloat() / currentFrameHeight else 1f

        // Vertikální vzdálenost: X-složka kompenzována AR (víčko se pohybuje dominantně vertikálně)
        val dvX = (top.x() - bottom.x()) * ar
        val dvY = top.y() - bottom.y()
        val vDist = sqrt(dvX * dvX + dvY * dvY)

        // Horizontální vzdálenost: referenční báze (koutek → koutek, dominantně horizontální)
        val dhX = (left.x() - right.x()) * ar
        val dhY = left.y() - right.y()
        val hDist = sqrt(dhX * dhX + dhY * dhY)

        return if (hDist > 0.001f) (vDist / hDist) else 0f
    }

    // ==================== BLINK DETEKCE (EAR state machine) ====================

    /**
     * 2-stavový automat pro blink detekci z EAR. OPEN → (EAR < CLOSE) → CLOSING (startuje timer) →
     * (EAR > OPEN) → OPEN Vrací true pokud spike trval BLINK_MIN..BLINK_MAX ms.
     */
    private fun detectBlink(avgEar: Float, nowMs: Long): Boolean {
        if (!isInBlink) {
            if (avgEar < BLINK_EAR_CLOSE) {
                isInBlink = true
                blinkStartTimeMs = nowMs
            }
            return false
        } else {
            val duration = nowMs - blinkStartTimeMs
            if (avgEar > BLINK_EAR_OPEN) {
                isInBlink = false
                return duration in BLINK_MIN_DURATION_MS..BLINK_MAX_DURATION_MS
            }
            if (duration > BLINK_MAX_DURATION_MS) {
                // Záměrné přivírání, ne blink
                isInBlink = false
            }
            return false
        }
    }

    // ==================== THERMAL THROTTLE ====================

    private fun checkThermalThrottle(): Boolean {
        val thermalStatus =
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    powerManager.currentThermalStatus
                } else {
                    PowerManager.THERMAL_STATUS_NONE
                }
        val shouldThrottle = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        if (shouldThrottle && !isThrottled) {
            Log.w(TAG, "🌡️ Thermal throttle aktivován — IrisMotion pozastaven")
            isThrottled = true
        } else if (!shouldThrottle && isThrottled) {
            Log.i(TAG, "🌡️ Thermal throttle deaktivován — IrisMotion obnoven")
            isThrottled = false
        }
        return shouldThrottle
    }

    // ==================== LIFECYCLE ====================

    /** Uzavře MediaPipe resources. Idempotentní. */
    fun close() {
        if (isClosed) return
        isClosed = true
        try {
            faceLandmarker?.close()
            faceLandmarker = null
            Log.d(TAG, "🔒 FaceLandmarker uzavřen")
        } catch (e: Exception) {
            Log.w(TAG, "close() error: ${e.message}")
        }
    }
}
