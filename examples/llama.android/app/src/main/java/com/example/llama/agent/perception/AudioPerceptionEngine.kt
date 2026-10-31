package com.example.llama.agent.perception

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.withLock

/**
 * ======================================================================== AUDIO PERCEPTION ENGINE
 * — YAMNet On-Device Sound Event Detector
 * ========================================================================
 *
 * Detekuje 521 kategorií zvuků z AudioSet taxonomie v reálném čase pomocí YAMNet modelu
 * (MobileNetV1, ~3.7MB, ~194ms latence).
 *
 * Research Foundation:
 * - YAMNet (Google Research): MobileNetV1 depthwise-separable convolution, trénováno na AudioSet
 * (2M+ clips, 632 kategorií, 521 v modelu)
 * - TensorFlow Lite Task Audio API: AudioClassifier, TensorAudio buffer
 * - On-device ML best practices (arxiv 2024): continuous inference, thermal throttling,
 * battery-aware scheduling
 *
 * Architektura:
 * - Samostatný engine (nezávislý na PerceptionEngine = vision)
 * - Vlastní coroutine scope (supervisorJob pro izolaci chyb)
 * - AudioRecord → TensorAudio buffer → AudioClassifier → PerceptionEventBus
 * - Multilingual label mapping: EN → CZ, DE, ES, FR, PL, SK
 *
 * Thread Safety:
 * - AtomicBoolean pro lifecycle gate
 * - Všechny operace na Dispatchers.Default
 * - AudioRecord na dedikovaném vlákně
 *
 * Požadavky:
 * - Manifest: android.permission.RECORD_AUDIO
 * - Model: yamnet.tflite v /assets/
 * - Dependency: org.tensorflow:tensorflow-lite-task-audio
 *
 * Životní cyklus:
 * - start() → vytvoří AudioRecord + classifier + spustí inference loop
 * - stop() → zastaví loop + uvolní AudioRecord
 * - destroy() → cancel scope + close classifier
 */
class AudioPerceptionEngine(
    private val context: Context,
    private val settingsManager: com.example.llama.SettingsManager
) {
    companion object {
        private const val TAG = "AudioPerceptionEngine"

        /** YAMNet TFLite model asset filename. ~3.7MB, 521 AudioSet classes, 16kHz input. */
        private const val YAMNET_MODEL_ASSET = "yamnet.tflite"

        /**
         * Minimum confidence threshold for reporting a sound event. YAMNet outputs probabilities
         * for all 521 classes. 0.3 is balanced: catches real events, filters noise.
         */
        private const val MIN_CONFIDENCE = 0.3f

        /**
         * Maximum number of top results per inference cycle. Reduces processing and event bus load.
         */
        private const val MAX_RESULTS = 5

        /**
         * Inference interval (ms). YAMNet processes ~975ms audio windows. 1000ms = ~1
         * inference/sec. Good balance of coverage and battery.
         */
        private const val INFERENCE_INTERVAL_MS = 1000L

        /** Timeout (ms) for single inference cycle. Prevents hang on slow/saturated SoC. */
        private const val INFERENCE_TIMEOUT_MS = 3000L

        /**
         * Cooldown (ms) between emitting same event label. Prevents flooding EventBus with repeated
         * "Speech" events.
         */
        private const val EVENT_COOLDOWN_MS = 5000L

        /** AudioRecord sample rate (YAMNet expects 16kHz). */
        private const val SAMPLE_RATE = 16000
    }

    // ==================== LIFECYCLE STATE ====================

    private val isRunning = AtomicBoolean(false)
    private val inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "YAMNet-Inference-Thread").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    private val engineScope = CoroutineScope(inferenceExecutor.asCoroutineDispatcher() + SupervisorJob())
    private var inferenceJob: Job? = null
    private val inferenceMutex = kotlinx.coroutines.sync.Mutex()

    // ==================== ML MODEL ====================

    private var interpreter: Interpreter? = null
    private var modelBuffer: MappedByteBuffer? = null
    private var audioRecord: AudioRecord? = null
    private var initAttempted = false

    // ==================== DIAGNOSTICS ====================

    private val totalInferences = AtomicLong(0L)
    private val totalInferenceMs = AtomicLong(0L)

    // ==================== EVENT DEDUPLICATION ====================

    /** Last emitted timestamp per label — prevents flooding */
    private val lastEmittedTimestamps = mutableMapOf<String, Long>()

    // ==================== PUBLIC API ====================

    /**
     * Start audio perception loop.
     *
     * Pre-flight checks:
     * 1. RECORD_AUDIO permission granted
     * 2. Model file exists in assets
     * 3. AudioRecord can be created
     *
     * Idempotent: calling start() when already running is no-op.
     */
    fun start() {
        if (isRunning.getAndSet(true)) {
            Log.v(TAG, "Already running — ignoring start()")
            return
        }

        // Permission check (critical — without this, AudioRecord crashes)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "⚠️ RECORD_AUDIO permission NOT granted — cannot start")
            isRunning.set(false)
            return
        }

        inferenceJob =
                engineScope.launch {
                    try {
                        initializeClassifier()
                        if (interpreter == null) {
                            Log.w(TAG, "⚠️ Interpreter init failed — audio perception disabled")
                            isRunning.set(false)
                            return@launch
                        }

                        startInferenceLoop()
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ AudioPerceptionEngine fatal error", e)
                        isRunning.set(false)
                    }
                }

        Log.i(TAG, "🎙️ AudioPerceptionEngine started")
        PerceptionEventBus.tryEmit(
                PerceptionLifecycleEvent(
                        state = EngineState.STARTED,
                        reason = "AudioPerception start"
                )
        )
    }

    /** Stop audio perception loop. Releases AudioRecord. */
    fun stop() {
        if (!isRunning.getAndSet(false)) return

        inferenceJob?.cancel()
        inferenceJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.v(TAG, "AudioRecord cleanup: ${e.message}")
        }
        audioRecord = null

        lastEmittedTimestamps.clear()

        Log.i(TAG, "🛑 AudioPerceptionEngine stopped")
        PerceptionEventBus.tryEmit(
                PerceptionLifecycleEvent(
                        state = EngineState.STOPPED,
                        reason = "AudioPerception stop"
                )
        )
    }

    /**
     * Full destruction — cancel scope, release native resources. Call only from
     * Service.onDestroy().
     */
    fun destroy() {
        stop()
        try {
            interpreter?.close()
        } catch (_: Exception) {}
        interpreter = null
        modelBuffer = null
        initAttempted = false
        engineScope.cancel()
        inferenceExecutor.shutdown()
        
        Log.i(TAG, "⏹️ AudioPerceptionEngine destroyed")
    }

    // ==================== INITIALIZATION ====================

    /**
     * Lazy init: Load YAMNet model + create TensorAudio buffer. Single-attempt — if model is
     * missing, never retries.
     */
    private fun initializeClassifier() {
        // Vždy se pokusíme o inicializaci překladů, pokud ještě nejsou (AudioLabelTranslations.init má vnitřní guard)
        try {
            AudioLabelTranslations.init(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init translations", e)
        }

        if (initAttempted && interpreter != null) {
            // Fallthrough to AudioRecord check below
        } else {
            try {
            // Lazy load model to isolated MappedByteBuffer (16KB mmap safe)
            val modelFile = File(context.filesDir, YAMNET_MODEL_ASSET)
            if (!modelFile.exists()) {
                Log.i(TAG, "📦 Extrahuji $YAMNET_MODEL_ASSET do filesDir pro bezpečný 16KB mmap...")
                context.assets.open(YAMNET_MODEL_ASSET).use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            modelBuffer = FileInputStream(modelFile).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, 0L, modelFile.length())
            }

            // Create pure LiteRT Interpreter to avoid MediaPipe C++ JNI collision
            val options = Interpreter.Options().apply {
                numThreads = 1 // YAMNet is small, 1 thread is optimal
            }
            interpreter = Interpreter(modelBuffer!!, options)
            initAttempted = true
            Log.i(TAG, "✅ YAMNet pure Interpreter initialized (model=$YAMNET_MODEL_ASSET, classes=521)")
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ AudioInterpreter init failed: ${e.message}")
            interpreter = null
            return
        }
    }

        // Initialize AudioRecord every time we start, since stop() releases it
        if (audioRecord == null || audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            try {
                val bufferSize =
                        AudioRecord.getMinBufferSize(
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT
                        )

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                                PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e(TAG, "⚠️ RECORD_AUDIO missing at AudioRecord creation")
                    return
                }

                audioRecord =
                        AudioRecord(
                                MediaRecorder.AudioSource.MIC,
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_IN_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                                bufferSize * 2 // Double buffer for safety
                        )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "❌ AudioRecord failed to initialize (state=${audioRecord?.state})")
                    audioRecord = null
                    return
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ AudioRecord creation failed: ${e.message}")
                audioRecord = null
                return
            }
        }

        try {
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.startRecording()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ AudioRecord startRecording failed: ${e.message}")
        }
    }

    // ==================== INFERENCE LOOP ====================

    /**
     * Main inference loop — runs continuously while isRunning.
     *
     * Flow per cycle:
     * 1. Load audio from AudioRecord into AudioData
     * 2. Run classifier inference (withTimeout)
     * 3. Filter results by confidence threshold
     * 4. Map labels to multilingual names
     * 5. Detect safety categories → escalate priority
     * 6. Apply cooldown deduplication
     * 7. Emit to PerceptionEventBus
     */
    private suspend fun startInferenceLoop() {
        val activeInterpreter = interpreter ?: return
        val activeAudioRecord = audioRecord ?: return

        Log.i(TAG, "🔁 Audio inference loop started (interval=${settingsManager.perceptionAudioIntervalMs}ms)")

        val bufferSize = 15600 // YAMNet requires 0.975s at 16kHz
        val shortArray = ShortArray(bufferSize)
        val floatArray = FloatArray(bufferSize)
        val outputArray = arrayOf(FloatArray(521))

        while (isRunning.get() && engineScope.isActive) {
            try {
                withTimeout(INFERENCE_TIMEOUT_MS) {
                    val startNs = System.nanoTime()

                    // Load audio samples from AudioRecord buffer manually
                    var totalRead = 0
                    while (totalRead < bufferSize && isRunning.get() && engineScope.isActive) {
                        val read = activeAudioRecord.read(shortArray, totalRead, bufferSize - totalRead)
                        if (read < 0) {
                            Log.e(TAG, "❌ AudioRecord read error: $read")
                            break
                        }
                        totalRead += read
                    }
                    if (totalRead < bufferSize) return@withTimeout

                    // Normalize to FloatArray [-1.0, 1.0]
                    for (i in 0 until bufferSize) {
                        floatArray[i] = shortArray[i] / 32768.0f
                    }

                    // Run isolated inference safely with Mutex to prevent tensor.cc:404 multiple writes
                    val inputArray = arrayOf(floatArray)
                    inferenceMutex.withLock {
                        activeInterpreter.run(inputArray, outputArray)
                    }

                    val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                    totalInferenceMs.addAndGet(elapsedMs)
                    totalInferences.incrementAndGet()

                    // Process results
                    val now = System.currentTimeMillis()
                    val scores = outputArray[0]
                    
                    for (index in scores.indices) {
                        val confidence = scores[index]

                        if (confidence < settingsManager.perceptionAudioConfidence) continue

                        // Map index to English label, skip if unknown
                        val labelEn = AudioLabelTranslations.getLabelByIndex(index, "en") ?: continue

                        // Cooldown: skip if same label emitted recently
                        val lastEmitted = lastEmittedTimestamps[labelEn] ?: 0L
                        if (now - lastEmitted < settingsManager.perceptionAudioCooldownMs) continue

                        // Emit event with multilingual label and auto-priority
                        val event =
                                AudioDetectionEvent.create(
                                        label = labelEn,
                                        confidence = confidence
                                )

                        PerceptionEventBus.emit(event)
                        lastEmittedTimestamps[labelEn] = now

                        // Verbose log with active-language translation
                        val activeLang = settingsManager.yamnetActiveLang
                        val localLabel = AudioLabelTranslations.translate(labelEn, activeLang)
                        val confStr = String.format("%.2f", confidence)
                        Log.i(
                                TAG,
                                "🔊 Detected: '$labelEn' ($localLabel) " +
                                        "conf=$confStr " +
                                        "[${event.priority}] (${elapsedMs}ms)"
                        )
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                Log.w(TAG, "⏱️ Audio inference timeout (>${INFERENCE_TIMEOUT_MS}ms)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Audio inference error (non-fatal): ${e.message}")
            }

            // Wait before next inference
            delay(settingsManager.perceptionAudioIntervalMs)
        }

        Log.i(TAG, "🔁 Audio inference loop ended")
    }

    // ==================== DIAGNOSTICS ====================

    /**
     * Get inference statistics for profiling.
     * @return Formatted string with avg/total inference stats
     */
    fun getStats(): String {
        val count = totalInferences.get()
        val totalMs = totalInferenceMs.get()
        val avgMs = if (count > 0) totalMs / count else 0
        return "AudioPerception: ${count} inferences, avg=${avgMs}ms, total=${totalMs}ms"
    }
}

// ========================================================================
// MULTILINGUAL AUDIO LABEL TRANSLATIONS
// ========================================================================

/**
 * Multilingual mapping for YAMNet AudioSet labels (521 classes).
 *
 * Contains translations for the most common and safety-critical sound categories. Labels not in the
 * map are returned as-is (English).
 *
 * Supported languages: CZ, SK, DE, ES, FR, PL
 *
 * Coverage strategy:
 * - ALL safety-critical labels (alarm, siren, scream, etc.)
 * - ALL common environmental sounds (door, car, dog, etc.)
 * - ALL human sounds (speech, laughter, cough, etc.)
 * - Musical instruments (top 30)
 * - Animals (top 40)
 * - Labels not in the map → English fallback (still understood by LLM)
 */
object AudioLabelTranslations {

    /**
     * Get Czech translation for YAMNet label. Case-insensitive lookup. Returns original if no
     * translation.
     */
    fun toCzech(label: String): String = translate(label, "cz")

    /** Get translation for specified language code. */
    fun translate(label: String, lang: String): String {
        val key = label.lowercase()
        val langLower = lang.lowercase()
        
        // 1. Try dynamic labels
        DYNAMIC_LABELS[langLower]?.get(key)?.let { return it }
        
        // 2. Alias fallbacks (cs <-> cz)
        if (langLower == "cs" || langLower == "cz") {
            DYNAMIC_LABELS["cz"]?.get(key)?.let { return it }
            DYNAMIC_LABELS["cs"]?.get(key)?.let { return it }
        }
        // 3. Slovak fallback to Czech
        if (langLower == "sk") {
            DYNAMIC_LABELS["sk"]?.get(key)?.let { return it }
            DYNAMIC_LABELS["cz"]?.get(key)?.let { return it }
        }

        // 4. Pokud neexistuje dynamicky načtený .csv slovník pro tento jazyk, vracíme anglický originál
        return label
    }



    private val DYNAMIC_LABELS = mutableMapOf<String, MutableMap<String, String>>()
    private val INDEX_TO_ENG = mutableMapOf<Int, String>()
    private var isInitialized = false

    fun getLabelByIndex(index: Int, lang: String): String? {
        val engLabel = INDEX_TO_ENG[index] ?: return null
        return if (lang == "en" || lang == "eng") engLabel else translate(engLabel, lang)
    }

    fun init(context: android.content.Context, forceReload: Boolean = false) {
        if (isInitialized && !forceReload) return
        try {
            DYNAMIC_LABELS.clear()
            INDEX_TO_ENG.clear()
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir == null) {
                android.util.Log.e("AudioLabelTranslations", "❌ getExternalFilesDir(null) is NULL. Storage not ready?")
                // V tomto případě zkusíme aspoň načíst eng.csv z assets později
            }
            
            val dbDir = java.io.File(externalDir ?: context.filesDir, "yamnet_db")
            if (!dbDir.exists()) {
                dbDir.mkdirs()
            }

            // Kopírování výchozích CSV z assets, pokud adresář zeje prázdnotou
            val existingFiles = dbDir.listFiles { _, name -> name.endsWith(".csv") }
            if (existingFiles == null || existingFiles.isEmpty()) {
                android.util.Log.i("AudioLabelTranslations", "📥 Copying default YAMNet CSVs from assets to ${dbDir.absolutePath}")
                try {
                    val assetFiles = context.assets.list("yamnet_db") ?: emptyArray<String>()
                    for (asset in assetFiles) {
                        if (asset.endsWith(".csv")) {
                            context.assets.open("yamnet_db/$asset").use { input ->
                                java.io.File(dbDir, asset).outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("AudioLabelTranslations", "Failed to copy default CSVs", e)
                }
            }

            // Fallback: Pokud 'eng.csv' neexistuje v externím úložišti, zkusíme ho načíst přímo z assets
            val engFile = java.io.File(dbDir, "eng.csv")
            val engMap = mutableMapOf<Int, String>()
            
            val engSource = if (engFile.exists()) {
                engFile.bufferedReader()
            } else {
                android.util.Log.w("AudioLabelTranslations", "⚠️ eng.csv not found in external storage! Falling back to assets.")
                try {
                    context.assets.open("yamnet_db/eng.csv").bufferedReader()
                } catch (e: Exception) {
                    android.util.Log.e("AudioLabelTranslations", "❌ Failed to load eng.csv even from assets!", e)
                    null
                }
            }

            if (engSource != null) {
                engSource.use { reader ->
                    val lines = reader.readLines().drop(1)
                    lines.forEach { line ->
                        val parts = parseCsvLine(line)
                        if (parts != null) {
                            engMap[parts.first] = parts.second.lowercase()
                            INDEX_TO_ENG[parts.first] = parts.second.lowercase()
                        }
                    }
                }
            } else {
                android.util.Log.e("AudioLabelTranslations", "🛑 INDEX_TO_ENG remains empty! Audio perception will be label-less.")
            }

            // Nyní dynamicky načteme všechny ostatní .csv soubory v adresáři
            val csvFiles = dbDir.listFiles { _, name -> name.endsWith(".csv") && name != "eng.csv" } ?: emptyArray<java.io.File>()
            
            for (file in csvFiles) {
                val langCode = file.nameWithoutExtension.lowercase()
                val langMap = mutableMapOf<String, String>()
                val lines = file.bufferedReader().readLines().drop(1)
                
                lines.forEach { line ->
                    val parts = parseCsvLine(line)
                    if (parts != null) {
                        val engName = engMap[parts.first]
                        if (engName != null) {
                            langMap[engName] = parts.second
                        }
                    }
                }
                DYNAMIC_LABELS[langCode] = langMap
            }



            isInitialized = true
            android.util.Log.i("AudioLabelTranslations", "✅ Dynamic YAMNet DB loaded. Loaded languages: ${DYNAMIC_LABELS.keys.joinToString()}")
        } catch (e: Exception) {
            android.util.Log.e("AudioLabelTranslations", "❌ Failed to load dynamic YAMNet DB", e)
        }
    }

    private fun parseCsvLine(line: String): Pair<Int, String>? {
        try {
            val regex = ",(?=(?:[^\\\"]*\\\"[^\\\"]*\\\")*[^\\\"]*$)".toRegex()
            val parts = line.split(regex)
            val index = parts[0].toIntOrNull() ?: return null
            val displayName = parts.getOrNull(2)?.removeSurrounding("\"")?.trim() ?: return null
            return Pair(index, displayName)
        } catch (e: Exception) {
            return null
        }
    }

}
