package com.example.llama.agent.perception

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.llama.agent.vision.models.VisionTaskType
import java.nio.ByteBuffer

/**
 * ========================================================================
 * PERCEPTION EVENT BUS — Centrální sběrnice percepčních událostí
 * ========================================================================
 *
 * Implementuje Event-Driven Architecture pro Compound AI System.
 * Všechny senzory (Vision Track B, Audio, ScreenParser, IrisMotion)
 * emitují události přes tento bus. AgentCoordinator je subscriber.
 *
 * Research Foundation:
 * - SharedFlow (Kotlin Coroutines): Hot stream, multicast, replay=0
 *   pro one-time events (navigace, alerty). Lifecycle-aware collection.
 * - Multi-Agent Orchestration (arxiv 2024): Event-driven decoupling
 *   mezi perception layer a decision layer.
 *
 * Design:
 * - Sealed class hierarchy pro type-safe events
 * - replay = 0 → žádné opakování starých eventů novým subscriberům
 * - extraBufferCapacity = 16 → buffer pro burst events (OCR + OD + Face)
 * - Singleton pattern — jeden bus pro celou aplikaci
 *
 * Thread Safety:
 * - MutableSharedFlow je thread-safe (coroutine-safe by design)
 * - emit() je suspend → never blocks, ale čeká pokud buffer plný
 * - tryEmit() je non-suspend → vrací false pokud buffer plný (pro non-coroutine callsites)
 */
object PerceptionEventBus {

    private const val TAG = "PerceptionEventBus"

    /**
     * Buffer capacity for burst events.
     * 16 events je dostatek pro souběžný burst z 3 ML workerů + audio + screen.
     */
    private const val BUFFER_CAPACITY = 16

    private val _events = MutableSharedFlow<PerceptionEvent>(
        replay = 0,                         // No replay — events jsou one-time
        extraBufferCapacity = BUFFER_CAPACITY, // Buffer pro burst
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    /**
     * Public read-only flow. Subscribers (AgentCoordinator) collectují zde.
     * Vždy collectujte v lifecycle-aware scope (engineScope, viewModelScope).
     */
    val events: SharedFlow<PerceptionEvent> = _events.asSharedFlow()

    /**
     * Dedicated flow for configuration and UI interaction events.
     * Separated from the main events flow to prevent critical config changes
     * (like model swaps) from being dropped by high-volume camera frame spam.
     */
    private val _configEvents = MutableSharedFlow<PerceptionEvent>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val configEvents: SharedFlow<PerceptionEvent> = _configEvents.asSharedFlow()

    /**
     * Emit event from coroutine context (suspend, waits if buffer full).
     * Preferred method for perception workers.
     */
    suspend fun emit(event: PerceptionEvent) {
        if (event is ReloadNightModeModelEvent) {
            _configEvents.emit(event)
        } else {
            _events.emit(event)
        }
        Log.v(TAG, "📡 Event emitted: ${event.javaClass.simpleName}")
    }

    /**
     * Try to emit event from non-coroutine context (non-suspend).
     * Returns false if buffer is full (event is dropped).
     * Use for IrisMotion callbacks that run on camera executor thread.
     */
    fun tryEmit(event: PerceptionEvent): Boolean {
        val emitted = if (event is ReloadNightModeModelEvent) {
            _configEvents.tryEmit(event)
        } else {
            _events.tryEmit(event)
        }
        
        if (!emitted) {
            Log.w(TAG, "⚠️ Event buffer full, dropped: ${event.javaClass.simpleName}")
        } else {
            // Log.v(TAG, "📡 Event emitted: ${event.javaClass.simpleName}") // Disabled due to extreme high-frequency 30+ fps spam
        }
        return emitted
    }
}

// ========================================================================
// PERCEPTION EVENT HIERARCHY (Sealed Class — Type-Safe)
// ========================================================================

/**
 * Base sealed class for all perception events.
 *
 * Each subclass represents a distinct event type from a specific sensor.
 * AgentCoordinator uses `when(event)` exhaustive matching for routing.
 *
 * All events carry:
 * - timestampMs: When the event occurred
 * - priority: For future event queue prioritization
 */
sealed class PerceptionEvent {
    abstract val timestampMs: Long
    abstract val priority: EventPriority
}

/**
 * Priority levels for event queue ordering.
 * CRITICAL events (e.g., fire alarm) skip all queues.
 */
enum class EventPriority {
    LOW,        // Background info (scene changed slightly)
    NORMAL,     // Standard perception update (new object detected)
    HIGH,       // Important change (person appeared, text detected)
    CRITICAL    // Safety alert (alarm, danger, fall detected)
}

// --- Vision Events (Track B — PerceptionEngine) ---

/**
 * Značí zdroj obrazu pro správné vizuální napojení (škálování, viewport).
 */
enum class VisionUpdateSource {
    LIVE_CAM,       // Kamera z CamPreview
    SCREEN_CAPTURE, // MediaProjection / Accessibility Screenshot
    STATIC_IMAGE    // Jednorázový obrázek
}

/**
 * Emitted after each PerceptionEngine inference cycle.
 * Contains the full perception snapshot for AgentCoordinator.
 */
data class VisionUpdateEvent(
    val snapshot: PerceptionState,
    val objectCount: Int,
    val hasText: Boolean,
    val faceCount: Int,
    val detections: List<com.example.llama.agent.vision.BoundingBoxView.Detection> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val updateSource: VisionUpdateSource = VisionUpdateSource.LIVE_CAM,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

/**
 * Emitted when the Night Mode TFLite model path changes and the active plugin needs to reload.
 */
data class ReloadNightModeModelEvent(
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

/** Typ akce pro optický zaměřovač */
enum class LockAction {
    LOCK,
    UNLOCK
}

/** 
 * Emitováno z ObjectDetectorStudioActivity po kliknutí na cíl.
 * @param normalizedBox Souřadnice cíle v rozsahu 0.0 - 1.0
 */
data class OpticalLockEvent(
    val normalizedBox: android.graphics.RectF? = null,
    val label: String? = null,
    val action: LockAction,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

/**
 * Event pro doručení surové segmentační mapy / hloubkové mapy.
 * @param maskData Surový ByteBuffer vlastněný Eventem (musí jít o kopii, ne referenci na TFLite interní buffer)
 * @param width Šířka výstupního tenzoru (např. 256)
 * @param height Výška výstupního tenzoru (např. 256)
 * @param taskType Zda jde o Semantic (třídy) nebo Depth (škála 0.0-1.0)
 */
data class SemanticMaskEvent(
    val maskData: ByteBuffer,
    val width: Int,
    val height: Int,
    val taskType: VisionTaskType,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

/** Reprezentuje jeden kloub (oko, rameno, koleno). X a Y jsou normalizované (0.0 - 1.0). */
data class KeyPoint(val x: Float, val y: Float, val confidence: Float)

/** Reprezentuje celou kostru jedné osoby. */
data class Skeleton(val keyPoints: List<KeyPoint>)

/**
 * Vysokofrekvenční událost pro Pose Estimation / Action Recognition.
 * @param skeletons Seznam detekovaných koster
 * @param sourceWidth Původní šířka tenzoru kamery
 * @param sourceHeight Původní výška tenzoru kamery
 */
data class PoseUpdateEvent(
    val skeletons: List<Skeleton>,
    val sourceWidth: Int,
    val sourceHeight: Int,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

/**
 * Emitted when a significant scene change is detected.
 * Threshold: entity count changed by ≥ 3, or new entity category appeared.
 */
data class SceneChangeEvent(
    val previousObjectCount: Int,
    val currentObjectCount: Int,
    val newEntities: List<String>,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

// --- Audio Events (Future: AudioClassifier / YAMNet) ---

/**
 * Emitted frequently (e.g. 10-30x per sec) from audio engines.
 * Carries raw dB values for VU metering.
 */
data class AudioLevelEvent(
    val rmsDb: Float,
    val peakDb: Float,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()


/**
 * Emitted when AudioClassifier detects a sound event.
 * Future implementation — placeholder pro Fázi 6 Audio.
 */
data class AudioDetectionEvent(
    val label: String,          // e.g. "Fire alarm", "Dog bark", "Speech"
    val confidence: Float,      // [0.0 .. 1.0]
    val isSpeech: Boolean,      // true → may trigger transcription agent
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent() {
    companion object {
        /**
         * Factory that auto-detects safety events and escalates priority.
         */
        fun create(label: String, confidence: Float): AudioDetectionEvent {
            val safetyKeywords = listOf("alarm", "siren", "scream", "gunshot", "crash", "explosion")
            val isSafety = safetyKeywords.any { label.contains(it, ignoreCase = true) }
            val isSpeech = label.contains("speech", ignoreCase = true) ||
                    label.contains("talking", ignoreCase = true)
            return AudioDetectionEvent(
                label = label,
                confidence = confidence,
                isSpeech = isSpeech,
                priority = if (isSafety && confidence >= 0.7f) EventPriority.CRITICAL
                           else if (isSpeech) EventPriority.HIGH
                           else EventPriority.NORMAL
            )
        }
    }
}

// --- Screen Events (ScreenParser bridge) ---

/**
 * Emitted when ScreenParser detects significant UI change.
 * Bridges between screen-level perception and physical-world perception.
 */
data class ScreenContextEvent(
    val appPackage: String,
    val visibleText: String,
    val elementCount: Int,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.LOW
) : PerceptionEvent()

// --- IrisMotion Events (Gaze & Gesture bridge) ---

/**
 * Emitted when user's gaze state changes significantly.
 * E.g., user starts/stops looking at screen, prolonged fixation.
 */
data class GazeStateEvent(
    val isLookingAtScreen: Boolean,
    val fixationDurationMs: Long,  // How long user has been fixating
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.LOW
) : PerceptionEvent()

// --- Transcript Events (whisper.cpp ASR / Live Captioning) ---

/**
 * Emitted when TranscriptAgent produces a new text segment.
 * isFinal=true means the segment is complete (sentence boundary).
 * isFinal=false means partial/streaming result that may change.
 *
 * Use-cases:
 * - Live Captioning overlay (SmartCaptionView)
 * - WorldState.transcriptBuffer accumulation for Tier 3 LLM summarization
 */
data class TranscriptEvent(
    val text: String,
    val language: String,        // ISO 639-1: "cs", "en", "de"
    val isFinal: Boolean,        // false = partial, true = final segment
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

// --- System Events (Lifecycle, Thermal) ---

/**
 * Požadavek na spuštění Tier 2.5 NLP Workera pro kompresi přepisu.
 * Emituje PerceptionState.
 */
data class RequestNLPCompressionEvent(
    val rawText: String,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

/**
 * Emitted when perception engine state changes (start, stop, thermal pause).
 * AgentCoordinator can react (e.g., stop asking perception questions if engine paused).
 */
data class PerceptionLifecycleEvent(
    val state: EngineState,
    val reason: String,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.LOW
) : PerceptionEvent()

enum class EngineState {
    STARTED,
    STOPPED,
    THERMAL_PAUSED,
    THERMAL_RESUMED,
    SCREEN_OFF_PAUSED,
    SCREEN_ON_RESUMED,
    ERROR
}

/**
 * Emitováno, pokud SystemAudioInterceptor (MediaProjection) narazí na FLAG_SECURE / DRM,
 * což se na Androidu projeví jako stream absolutního ticha (tzv. Zero-Detection).
 */
data class SystemDrmEvent(
    val reason: String = "Slepé/hluché místo (DRM / FLAG_SECURE detekováno)",
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

// --- Camera Events ---

/**
 * Event s malou bitmapou pro UI (AI Result Hub).
 * Přenáší se na ukázku živého náhledu z PerceptionEngine.
 * Pokud je bitmap null, UI by mělo odstranit preview.
 */
data class CameraPreviewEvent(
    val bitmap: android.graphics.Bitmap?,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.LOW
) : PerceptionEvent()

/** Náhled pro Night Mode Studio (plné rozlišení pro fullscreen překrytí kamery) */
data class NightModePreviewEvent(
    val bitmap: android.graphics.Bitmap?,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.LOW
) : PerceptionEvent()

// --- UI Events (Popup, Toast, Action Feedback) ---

/**
 * Požadavek na změnu stavu UI komponent (např. otevírání FloatingResultPopup).
 * Emitováno z FloatingAgentButton.
 */
data class UiActionEvent(
    val action: UiAction,
    val payload: String? = null,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.NORMAL
) : PerceptionEvent()

enum class UiAction {
    TOGGLE_POPUP,
    SHOW_POPUP,
    HIDE_POPUP,
    TRIGGER_SUMMARIZE,    // Spustit AI Shrnutí obsahu obrazovky
    TRIGGER_TRANSLATE,    // Spustit AI Překlad obsahu obrazovky
    TRIGGER_PROOFREAD,    // Spustit AI Korekturu
    TRIGGER_SENTIMENT,    // Spustit Sentiment Analýzu
    TRIGGER_REWRITE,      // Přepis textu (AICore Rewriter — PROFESSIONAL tón)
    TRIGGER_SMART_REPLY,  // Generovat návrhy odpovědí (ML Kit Smart Reply)
    TRIGGER_NER,          // Extrakce entit/pojmů z obrazovky (ML Kit Entity Extraction)
    TRIGGER_QA,           // Extractive QA — hledání odpovědi v textu (ToolbarBertWorker)
    TRIGGER_NLI,          // Logická kontrola / detekce rozporů (ToolbarBertWorker)
    TRIGGER_MLM,          // Masked Language Modeling — doplnění [MASK] tokenu
    TRIGGER_SMART_SELECT, // Přejít do módu volného kreslení (Path)
    HIDE_CONTEXT_MENU,    // Explicitní skrytí Sub-Menu Toolbaru
    TOGGLE_WAKEUP_HACK,   // Přepne hlasitost Mixeru mezi nulou a 1%
    TOGGLE_FULLSCREEN_BOXES, // Přepnout globální zobrazení rámečků
    OBJECT_CLICKED,       // Kliknutí na detekovaný bounding box
    TOGGLE_SCREEN_DETECTION // Spustit MediaProjection zachyt pro Object Detection
}


/**
 * Event reprezentující výsledek ad-hoc NLP úlohy (Summarize, Translate, apod.)
 * sloužící pro zobrazení v UI (FloatingResultPopup).
 */
data class NlpResultEvent(
    val title: String,
    val resultText: String,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

// --- Smart Select & Sub-Menu Events ---

/**
 * Emitováno jakmile uživatel dokončí kreslení oblasti ve SmartSelectOverlay.
 * AgentCoordinator na to reaguje zobrazením Sub-Menu Toolbaru na souřadnicích.
 */
data class SmartSelectCompleteEvent(
    val selectedRect: android.graphics.Rect,
    val extractedText: String,
    val croppedBitmap: android.graphics.Bitmap? = null,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

/**
 * Značí NLP Akci dostupnou přes Plovoucí SubMenu.
 */
enum class SubMenuAction {
    SUMMARIZE,
    TRANSLATE,
    PROOFREAD,
    SENTIMENT,
    REWRITE,          // AICore Rewriter (PROFESSIONAL tón)
    SMART_REPLY,      // ML Kit Smart Reply
    EXTRACT_ENTITIES, // M2 (NER)
    QA_SEARCH,        // M3 (Extractive QA)
    NLI_CHECK,        // M4 (Contradiction/Fact check)
    MLM_FILL,         // M5 (Masked Language Modeling)
    VISION_ANALYZE
}

/**
 * Emitováno po kliknutí na ikonu uvnitř ContextMenuToolbar.
 * Vyvolává konkrétní kontextovou akci.
 */
data class SubMenuActionEvent(
    val action: SubMenuAction,
    val sourceText: String,
    val croppedBitmap: android.graphics.Bitmap? = null,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

/**
 * Emitováno AudioStudioEngineGate při acquire()/updateTask()/release().
 * FloatingAgentButton a AiAgentAccessibilityService na tento event reagují:
 * - task != NONE → FAB přejde do STUDIO_BUSY stavu (zešedne, toolbar se deaktivuje)
 * - task == NONE → FAB se vrátí do IDLE stavu
 */
data class StudioBusyEvent(
    val task: com.example.llama.AudioStudioEngineGate.StudioTask,
    override val timestampMs: Long = System.currentTimeMillis(),
    override val priority: EventPriority = EventPriority.HIGH
) : PerceptionEvent()

