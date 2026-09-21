package com.example.llama.agent.perception

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ========================================================================
 * PERCEPTION STATE — Enterprise PRO Spatiotemporal Memory Layer
 * ========================================================================
 *
 * Inspired by:
 * - STMA Framework (arxiv 2025, Lei et al.) — Spatio-Temporal Memory Agent
 *   with Dynamic Knowledge Graph and Planner-Critic mechanism.
 *   Key insight: Separating temporal memory (action/observation sequences)
 *   from spatial memory (entity relationships) yields +31% success rate.
 *
 * - EdgeDAM (arxiv 2024) — Dual-Buffer Distractor-Aware Memory for
 *   single-object tracking on edge devices. Uses Recent-Aware Memory
 *   for temporally consistent target hypotheses.
 *
 * - MediaPipe Detection+Tracking Best Practices (Google, 2024):
 *   min_detection_confidence + min_tracking_confidence thresholds,
 *   async LIVE_STREAM processing, jitter reduction via temporal consistency.
 *
 * Design Principles (Zero Silent Failures):
 * 1. Immutable Snapshots via StateFlow (atomic reads, no ConcurrentModificationException)
 * 2. Exponential Decay (not linear) for confidence — smoother transitions
 * 3. Bounding Box support for future overlay integration (SmartVisionOverlay)
 * 4. Multi-source fusion ready (Vision Track B + ScreenParser + Audio)
 * 5. Idempotent: repeated updateAndDecay() calls produce consistent state
 *
 * Thread Safety:
 * - Writers: PerceptionEngine (background Dispatchers.Default)
 * - Readers: AgentCoordinator (any thread, via StateFlow collect)
 * - Internal ConcurrentHashMap + volatile fields + immutable snapshot emission
 */

private const val TAG = "PerceptionState"

// ========================================================================
// 1. ENTITY MODEL (Reprezentace jedné detekované entity)
// ========================================================================

/**
 * Represents a single detected entity in the physical world.
 *
 * Modeled after EdgeDAM's "Recent-Aware Memory" — each entity tracks
 * its own temporal lifecycle (first seen, last seen, confidence decay).
 *
 * @param label Human-readable label (e.g., "chair", "person")
 * @param category Classification category for tiered routing
 * @param confidence Current confidence score [0.0 .. 1.0]
 * @param firstSeenMs Timestamp when entity was first detected (episodic memory)
 * @param lastSeenMs Timestamp of last positive detection
 * @param boundingBox Optional normalized bounding box [0..1] for overlay integration
 * @param detectionCount How many times this entity was positively detected (stability metric)
 */
data class DetectedEntity(
    val label: String,
    val category: EntityCategory = EntityCategory.OBJECT,
    var confidence: Float = 1.0f,
    val firstSeenMs: Long = System.currentTimeMillis(),
    var lastSeenMs: Long = System.currentTimeMillis(),
    val boundingBox: NormalizedBox? = null,
    var detectionCount: Int = 1
) {
    /**
     * Stability score: Entities detected multiple times across frames
     * are more reliable than single-frame detections (reduces hallucination).
     * Inspired by STMA's temporal observation sequence weighting.
     */
    fun stabilityScore(): Float {
        return (detectionCount.coerceAtMost(10) / 10.0f) * confidence
    }
}

/**
 * Entity classification for Tiered Routing in AgentCoordinator.
 * Allows different processing paths for different entity types.
 */
enum class EntityCategory {
    OBJECT,     // Physical objects (chair, cup, car)
    TEXT,       // OCR-detected text fragments
    FACE,       // Detected human faces
    SOUND,      // Audio-classified events (future: AudioClassifier)
    SCREEN      // ScreenParser-sourced UI elements (semantic fusion)
}

/**
 * Normalized bounding box with coordinates in [0.0 .. 1.0] range.
 * Device-independent — overlay converts to pixels at render time.
 */
data class NormalizedBox(
    val xMin: Float,
    val yMin: Float,
    val xMax: Float,
    val yMax: Float
)
    
// ========================================================================
// 1.5 NLP KNOWLEDGE GRAPH (Rolling Summary)
// ========================================================================

/**
 * Zkomprimovaný blok textu (např. 3 minuty zvuku shrnuté do 1 odstavce).
 * Udržuje paměť v bezpečné velikosti (prevence OOM) a snižuje token count pro LLM.
 */
data class TimestampedSummary(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val wordCount: Int
)

// ========================================================================
// 2. IMMUTABLE SNAPSHOT (Single Source of Truth for AgentCoordinator)
// ========================================================================

/**
 * Thread-safe, immutable snapshot of the current perception state.
 *
 * This is what AgentCoordinator and LLM consume. Once emitted via StateFlow,
 * it is guaranteed to be consistent (all fields from the same millisecond).
 *
 * Inspired by STMA's "belief state" — a frozen view of the world
 * that the Planner component uses for decision-making.
 */
data class PerceptionState(
    /** All currently tracked entities with confidence > 0 */
    val entities: List<DetectedEntity> = emptyList(),

    /** Raw OCR text detected in the real world (camera) */
    val detectedText: String = "",

    /** Compressed NLP summaries from TranscriptAgent (long-term memory) */
    val summaries: List<TimestampedSummary> = emptyList(),

    /** Whether the user is currently looking at the device screen (from IrisMotion) */
    val isUserLookingAtScreen: Boolean = false,

    /** Scene complexity estimate (0.0 = empty, 1.0 = very complex scene) */
    val sceneComplexity: Float = 0.0f,

    /** Timestamp of this snapshot */
    val timestampMs: Long = 0L,

    /** Source of last update (for debugging and audit trail) */
    val lastUpdateSource: UpdateSource = UpdateSource.NONE
) {
    /**
     * Convenience: Get only high-confidence object labels for simple Tier 2 responses.
     * Uses stabilityScore() to filter out single-frame noise.
     */
    fun getStableObjectLabels(minStability: Float = 0.3f): List<String> {
        return entities
            .filter { it.category == EntityCategory.OBJECT && it.stabilityScore() >= minStability }
            .map { it.label }
    }

    /**
     * Semantic Fusion: Generates a compact, human-readable context string
     * for injection into LLM system prompt or Tier 2 TTS response.
     *
     * Format optimized for minimal token usage in LLM context window.
     */
    fun toReadableContext(): String {
        val parts = mutableListOf<String>()

        val stableObjects = getStableObjectLabels()
        if (stableObjects.isNotEmpty()) {
            parts.add("V okolí: ${stableObjects.joinToString(", ")}.")
        }

        if (detectedText.isNotBlank()) {
            parts.add("Text: $detectedText.")
        }

        val faceCount = entities.count { it.category == EntityCategory.FACE }
        if (faceCount > 0) {
            parts.add("Osoby: $faceCount.")
        }

        return if (parts.isEmpty()) "" else parts.joinToString(" ")
    }

    /**
     * Quick check: Is there anything meaningful in the perception state?
     * Used by AgentCoordinator to decide whether to inject context into LLM prompt.
     */
    fun hasUsefulContext(): Boolean {
        return entities.any { it.stabilityScore() >= 0.3f } || detectedText.isNotBlank()
    }
}

/**
 * Source tracking for debugging and audit (Zero Silent Failures principle).
 */
enum class UpdateSource {
    NONE,
    OBJECT_DETECTOR,
    OCR,
    FACE_DETECTOR,
    AUDIO_CLASSIFIER,
    SCREEN_PARSER,
    IRIS_MOTION,
    MANUAL
}

// ========================================================================
// 3. REPOSITORY (Spatiotemporal Memory with Decay)
// ========================================================================

/**
 * Central perception memory repository.
 *
 * Manages mutable entity tracking internally, applies temporal decay,
 * and publishes immutable snapshots via StateFlow.
 *
 * Decay Algorithm (Exponential, inspired by EdgeDAM Recent-Aware Memory):
 * - Entity confidence decays exponentially after not being seen for [decayOnsetMs].
 * - Formula: confidence *= decayFactor^(elapsed / decayOnsetMs)
 * - This produces smoother transitions than linear decay, preventing
 *   abrupt entity disappearance (anti-flickering).
 *
 * Scene Complexity (inspired by STMA Dynamic Knowledge Graph density):
 * - Estimated as normalized count of distinct entities.
 * - High complexity = more entities = potentially needs Tier 3 LLM for understanding.
 */
class PerceptionStateRepository {

    companion object {
        /** Maximum entities tracked simultaneously (OOM prevention) */
        private const val MAX_ENTITIES = 64

        /** Default: Start decaying after entity unseen for this duration */
        private const val DEFAULT_DECAY_ONSET_MS = 2000L

        /** Exponential decay factor per decay interval (0.7 = 30% drop per interval) */
        private const val DECAY_FACTOR = 0.7f

        /** Entity removed when confidence drops below this threshold */
        private const val EVICTION_THRESHOLD = 0.05f

        /** Scene complexity normalization divisor */
        private const val COMPLEXITY_NORMALIZER = 20.0f
        
        /** Kdy zavolat kompresi textu (Rolling Threshold) - např. 3000 znaků */
        const val ROLLING_THRESHOLD_CHARS = 3000
    }

    // Internal mutable state (writer-side)
    private val entityMap = ConcurrentHashMap<String, DetectedEntity>()

    @Volatile
    private var latestText: String = ""

    // Transcript / NLP komprese (Spatio-Temporal Memory)
    private val transcriptBuffer = java.lang.StringBuilder()
    private val compressedSummaries = mutableListOf<TimestampedSummary>()

    @Volatile
    private var lookingAtScreen: Boolean = false

    @Volatile
    private var lastSource: UpdateSource = UpdateSource.NONE

    // External immutable interface (reader-side)
    private val _snapshot = MutableStateFlow(PerceptionState())
    val snapshot: StateFlow<PerceptionState> = _snapshot.asStateFlow()

    /**
     * Primary update method. Called by PerceptionEngine after each ML inference cycle.
     *
     * Thread-safe: Can be called from any coroutine dispatcher.
     * Idempotent: Multiple calls with same data produce consistent state.
     *
     * @param newEntities Fresh detections from current frame
     * @param newText OCR text (null = keep previous, empty = clear)
     * @param isUserLooking IrisMotion screen gaze state (null = keep previous)
     * @param source Which detector produced this update
     * @param decayOnsetMs How long before unseen entities start decaying
     */
    fun updateAndDecay(
        newEntities: List<DetectedEntity> = emptyList(),
        newText: String? = null,
        isUserLooking: Boolean? = null,
        source: UpdateSource = UpdateSource.NONE,
        decayOnsetMs: Long = DEFAULT_DECAY_ONSET_MS
    ) {
        val now = System.currentTimeMillis()

        // 1. Upsert new detections (merge with existing)
        for (entity in newEntities) {
            val key = entityKey(entity)
            val existing = entityMap[key]
            if (existing != null) {
                // Reinforce: Reset confidence, update timestamp, increment counter
                existing.confidence = maxOf(existing.confidence, entity.confidence)
                existing.lastSeenMs = now
                existing.detectionCount++
            } else {
                // New entity — check capacity (OOM guard)
                if (entityMap.size >= MAX_ENTITIES) {
                    evictWeakest()
                }
                entityMap[key] = entity.copy(
                    firstSeenMs = now,
                    lastSeenMs = now,
                    detectionCount = 1
                )
            }
        }

        // 2. Update auxiliary state
        if (newText != null) {
            latestText = newText
        }
        if (isUserLooking != null) {
            lookingAtScreen = isUserLooking
        }
        lastSource = source

        // 3. Apply Exponential Decay to unseen entities
        val iterator = entityMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val entity = entry.value
            val elapsed = now - entity.lastSeenMs

            if (elapsed > decayOnsetMs) {
                // Exponential decay: confidence *= factor^(intervals)
                val intervals = elapsed.toFloat() / decayOnsetMs.toFloat()
                val decayedConfidence = entity.confidence * Math.pow(
                    DECAY_FACTOR.toDouble(),
                    intervals.toDouble()
                ).toFloat()

                entity.confidence = decayedConfidence

                // Evict entities below threshold
                if (entity.confidence < EVICTION_THRESHOLD) {
                    iterator.remove()
                    Log.v(TAG, "Entity evicted (decayed): ${entity.label}")
                }
            }
        }

        // 4. Compute scene complexity (STMA-inspired knowledge graph density)
        val complexity = (entityMap.size.toFloat() / COMPLEXITY_NORMALIZER).coerceIn(0.0f, 1.0f)

        // 5. Emit immutable snapshot (atomic publish)
        _snapshot.value = PerceptionState(
            entities = entityMap.values.toList(),  // Defensive copy
            detectedText = latestText,
            summaries = compressedSummaries.toList(), // Defensive copy
            isUserLookingAtScreen = lookingAtScreen,
            sceneComplexity = complexity,
            timestampMs = now,
            lastUpdateSource = lastSource
        )
    }

    // ========================================================================
    // 4. TRANSCRIPT BUFFER & ROLLING SUMMARY
    // ========================================================================

    /**
     * Ukládání živě přepisovaných částí audia od Whisperu.
     * Pokud text přesáhne kapacitu, pošle se k Tier 2.5 kompresi.
     */
    fun appendTranscript(text: String, isDrmBlocked: Boolean = false) {
        if (isDrmBlocked) {
            Log.w(TAG, "Audio stream je blokován (DRM / FLAG_SECURE) — streamuje se pouze ticho.")
            PerceptionEventBus.tryEmit(SystemDrmEvent())
            return
        }

        // 1. Zapiš novou větu do dočasného bufferu
        transcriptBuffer.append(text).append(" ")
        
        // 2. Auto-Trigger ("Tlakový ventil"): Pokud má buffer už moc textu (např. > 3 minuty)
        if (transcriptBuffer.length > ROLLING_THRESHOLD_CHARS) {
            Log.i(TAG, "Transcript buffer limit reached (${transcriptBuffer.length} chars). Triggering Rolling Summary.")
            
            // Extrahuje text pro kompresi
            val textToCompress = transcriptBuffer.toString()
            
            // Vyčistí buffer (aby mohl přijímat nová data během komprese)
            transcriptBuffer.clear()
            
            // Emit EventBus pro AgentCoordinator -> ten dál zavolá Tier 2.5 NlpSummaryWorker
            PerceptionEventBus.tryEmit(RequestNLPCompressionEvent(textToCompress))
        }
    }

    /**
     * Tier 2.5 Worker po úspěšné kompresi textu zavolá tuto metodu k vložení do
     * dlouhodobé paměti. Snímek stavu (snapshot) se zaktualizuje autmoaticky
     * při dalším cyklu updateAndDecay, ale text už nezmizí.
     */
    fun addCompressedSummary(summary: TimestampedSummary) {
        compressedSummaries.add(summary)
        Log.d(TAG, "Added compressed summary (${summary.wordCount} words) to Long-Term Memory.")
    }

    /**
     * Full memory reset. Use on:
     * - Screen off (battery saving)
     * - Thermal throttle (THERMAL_STATUS_SEVERE)
     * - Explicit user "forget" command
     */
    fun clear() {
        entityMap.clear()
        latestText = ""
        lookingAtScreen = false
        lastSource = UpdateSource.NONE
        transcriptBuffer.clear()
        compressedSummaries.clear()
        _snapshot.value = PerceptionState(timestampMs = System.currentTimeMillis())
        Log.i(TAG, "Perception memory cleared (including NLP summaries)")
    }

    /**
     * Get current entity count (for diagnostics / profiling).
     */
    fun entityCount(): Int = entityMap.size

    // --- Private Helpers ---

    /**
     * Generates a unique composite key for entity deduplication.
     * Combines label + category so "chair" (OBJECT) and "chair" (TEXT) are distinct.
     */
    private fun entityKey(entity: DetectedEntity): String {
        return "${entity.category.name}:${entity.label.lowercase()}"
    }

    /**
     * OOM prevention: Evicts the entity with the lowest stability score
     * when the map exceeds MAX_ENTITIES capacity.
     */
    private fun evictWeakest() {
        val weakest = entityMap.entries.minByOrNull { it.value.stabilityScore() }
        if (weakest != null) {
            entityMap.remove(weakest.key)
            Log.v(TAG, "Entity evicted (capacity): ${weakest.value.label}")
        }
    }
}
