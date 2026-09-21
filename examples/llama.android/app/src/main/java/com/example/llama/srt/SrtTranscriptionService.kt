package com.example.llama.srt

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.AudioStudioActivity
import com.example.llama.R
import com.example.llama.SettingsManager
import com.example.llama.SrtBlock
import com.example.llama.agent.perception.SherpaModelManager
import com.example.llama.agent.perception.SherpaModelType
import com.example.llama.agent.perception.WhisperJni
import com.example.llama.agent.perception.WhisperModelManager
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ======================================================================== SrtTranscriptionService
 * — Foreground Service pro batch SRT přepis
 * ========================================================================
 *
 * Spuštění: startForegroundService(Intent) z SrtEditorActivity / AudioStudio Komunikace:
 * BroadcastReceiver (PROGRESS / DONE / ERROR) WakeLock: PARTIAL_WAKE_LOCK s 4h timeoutem (pokrývá
 * audiobook) Priority: nastavena dle AudioStudioEngineGate.ThreadPriorityMode
 *
 * Extras (Intent):
 * - MEDIA_URI (String) — Uri zdrojového média
 * - PRIORITY_MODE (String) — ThreadPriorityMode.name()
 * - THREAD_COUNT (Int) — počet vláken pro STT engine
 *
 * Broadcasts:
 * - ACTION_SRT_PROGRESS — {"progress": 0-100, "status": String}
 * - ACTION_SRT_DONE — {"srt_file_path": String}
 * - ACTION_SRT_ERROR — {"error": String}
 *
 * Lifecycle:
 * - onStartCommand() → startForeground() → launch coroutine → stopSelf()
 * - onDestroy() → cancel coroutine + release WakeLock
 *
 * KRITICKÉ: FOREGROUND_SERVICE_DATA_SYNC (Android 14+) — offline ML inference POZOR:
 * TranscriptAgent NENÍ dotčen — tato služba je zcela oddělená.
 */
class SrtTranscriptionService : Service() {

    companion object {
        private const val TAG = "SrtTranscriptionService"
        private const val CHANNEL_ID = "srt_transcription_channel"
        private const val NOTIFICATION_ID = 7741
        private const val SAMPLE_RATE = 16000

        // Velikost PCM chunku pro streaming OnlineRecognizer (100ms @ 16kHz = 1600 floatů)
        private const val ONLINE_CHUNK_FLOATS = 1600

        const val ACTION_START = "com.example.llama.srt.ACTION_START"
        const val ACTION_CANCEL = "com.example.llama.srt.ACTION_CANCEL"
        const val ACTION_SRT_PROGRESS = "com.example.llama.srt.PROGRESS"
        const val ACTION_SRT_DONE = "com.example.llama.srt.DONE"
        const val ACTION_SRT_ERROR = "com.example.llama.srt.ERROR"

        const val EXTRA_MEDIA_URI = "MEDIA_URI"
        const val EXTRA_PRIORITY_MODE = "PRIORITY_MODE"
        const val EXTRA_THREAD_COUNT = "THREAD_COUNT"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS = "status"
        const val EXTRA_SRT_FILE_PATH = "srt_file_path"
        const val EXTRA_ERROR = "error"

        fun buildStartIntent(
                context: Context,
                mediaUri: Uri,
                priorityMode: com.example.llama.AudioStudioEngineGate.ThreadPriorityMode,
                threadCount: Int
        ): Intent =
                Intent(context, SrtTranscriptionService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_MEDIA_URI, mediaUri.toString())
                    putExtra(EXTRA_PRIORITY_MODE, priorityMode.name)
                    putExtra(EXTRA_THREAD_COUNT, threadCount)
                }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var transcriptionJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager

    // ========================================================================
    // LIFECYCLE
    // ========================================================================

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.i(TAG, "SrtTranscriptionService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        handleCancel()
        serviceScope.cancel()
        Log.i(TAG, "SrtTranscriptionService destroyed")
    }

    // ========================================================================
    // START LOGIC
    // ========================================================================

    private fun handleStart(intent: Intent) {
        val mediaUriStr =
                intent.getStringExtra(EXTRA_MEDIA_URI)
                        ?: run {
                            Log.e(TAG, "EXTRA_MEDIA_URI chybí — nelze spustit přepis")
                            stopSelf()
                            return
                        }
        val priorityName =
                intent.getStringExtra(EXTRA_PRIORITY_MODE)
                        ?: com.example.llama.AudioStudioEngineGate.ThreadPriorityMode.DEFAULT.name
        val threadCount = intent.getIntExtra(EXTRA_THREAD_COUNT, 4)
        val mediaUri = Uri.parse(mediaUriStr)

        val priority =
                try {
                    com.example.llama.AudioStudioEngineGate.ThreadPriorityMode.valueOf(priorityName)
                } catch (e: IllegalArgumentException) {
                    com.example.llama.AudioStudioEngineGate.ThreadPriorityMode.DEFAULT
                }

        // Nastavit prioritu vlákna Service okamžitě
        android.os.Process.setThreadPriority(priority.osValue)

        // WakeLock — CPU běží i se zhasnutým displejem (max 4h pro audiobook)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock =
                pm.newWakeLock(
                                PowerManager.PARTIAL_WAKE_LOCK,
                                "AudioStudio:SrtTranscriptionService"
                        )
                        .also { it.acquire(4 * 60 * 60 * 1000L) }

        startForeground(NOTIFICATION_ID, buildNotification("Inicializace...", 0))
        Log.i(TAG, "🚀 SRT batch přepis — priority=${priority.label}, threads=$threadCount")

        transcriptionJob = serviceScope.launch { runTranscription(mediaUri, priority, threadCount) }
    }

    private fun handleCancel() {
        transcriptionJob?.cancel()
        transcriptionJob = null
        releaseWakeLock()
        Log.i(TAG, "SRT přepis zrušen")
    }

    // ========================================================================
    // TRANSCRIPTION CORE
    // ========================================================================

    private suspend fun runTranscription(
            mediaUri: Uri,
            priority: com.example.llama.AudioStudioEngineGate.ThreadPriorityMode,
            threadCount: Int
    ) {
        try {
            broadcastProgress(0, "Extrakce PCM...")

            val pcmFile =
                    extractPcmFromMedia(mediaUri)
                            ?: run {
                                broadcastError("Nepodporovaný kodek nebo chyba extrakce PCM")
                                return
                            }

            broadcastProgress(15, "PCM extrahováno, inicializace engine...")
            updateNotification("PCM extrahováno", 15)

            val sm = SettingsManager(applicationContext)
            val engineType = sm.transcriptEngine
            val srtBlocks = mutableListOf<SrtBlock>()
            val segEngine = SegmentationEngineFactory.fromSpinnerIndex(1, 7)

            when (engineType) {
                "whisper" ->
                        transcribeWithWhisper(pcmFile, threadCount, sm, segEngine, srtBlocks) {
                                pct,
                                msg ->
                            broadcastProgress(pct, msg)
                            updateNotification(msg, pct)
                        }
                else ->
                        transcribeWithSherpa(pcmFile, threadCount, sm, segEngine, srtBlocks) {
                                pct,
                                msg ->
                            broadcastProgress(pct, msg)
                            updateNotification(msg, pct)
                        }
            }

            broadcastProgress(95, "Zápis SRT souboru...")
            val outFile = buildOutputFile(mediaUri)
            writeSrtFile(srtBlocks, outFile)

            broadcastProgress(100, "✅ Přepis dokončen (${srtBlocks.size} bloků)")
            updateNotification("✅ Hotovo — ${srtBlocks.size} bloků", 100)
            broadcastDone(outFile)
            Log.i(TAG, "✅ ${outFile.absolutePath} (${srtBlocks.size} bloků)")
            pcmFile.delete()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "SRT přepis zrušen")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba SRT přepisu: ${e.message}", e)
            broadcastError("Chyba přepisu: ${e.message}")
        } finally {
            releaseWakeLock()
            stopSelf()
        }
    }

    // ========================================================================
    // PCM EXTRACTION (MediaCodec)
    // ========================================================================

    private suspend fun extractPcmFromMedia(mediaUri: Uri): File? {
        return withContext(Dispatchers.IO) {
            try {
                val outDir = File(cacheDir, "srt_transcription").also { it.mkdirs() }
                val pcmFile = File(outDir, "audio_${System.currentTimeMillis()}.pcm")

                // 1. Zkusit FFmpeg (NativeVideoEngine) pro garantovanou podporu všech kontejnerů (MKV/AVI/AC3)
                if (com.example.llama.media.NativeVideoEngine.isAvailable()) {
                    val nativeEngine = com.example.llama.media.NativeVideoEngine(applicationContext)
                    val success = nativeEngine.extractAudioForAsr(mediaUri, pcmFile)
                    if (success && pcmFile.exists() && pcmFile.length() > 0) {
                        Log.i(TAG, "Audio extrahováno přes FFmpeg NativeVideoEngine.")
                        return@withContext pcmFile
                    }
                }

                Log.e(TAG, "FFmpeg extrakce selhala nebo není dostupná.")
                null
            } catch (e: Exception) {
                Log.e(TAG, "PCM extrakce selhala: ${e.message}", e)
                null
            }
        }
    }

    // ========================================================================
    // WHISPER — API 1:1 s TranscriptAgent.transcribeOfflineChunk()
    // Jazyk: výhradně z user nastavení (sm.whisperLanguage), bez hardcoded fallback.
    // ========================================================================

    private suspend fun transcribeWithWhisper(
            pcmFile: File,
            threadCount: Int,
            sm: SettingsManager,
            segEngine: SegmentationEngine,
            outBlocks: MutableList<SrtBlock>,
            onProgress: suspend (Int, String) -> Unit
    ) {
        val modelPath = WhisperModelManager.getModelPathFromRepository(applicationContext)
        if (modelPath.isEmpty()) throw Exception("Whisper model nenalezen")

        val pcmBytes = withContext(Dispatchers.IO) { pcmFile.readBytes() }
        // Konverze raw PCM bytes → FloatArray (16-bit signed LE → float)
        val floatSamples =
                FloatArray(pcmBytes.size / 2) { i ->
                    val lo = pcmBytes[i * 2].toInt() and 0xFF
                    val hi = pcmBytes[i * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort() / 32768.0f
                }

        onProgress(30, "Whisper inference (${floatSamples.size / SAMPLE_RATE}s)...")

        // Jazyk z user nastavení — SettingsManager vrací user volbu (bez hardcoded fallback)
        if (!WhisperJni.init(modelPath, sm.whisperAcceleratorMode)) throw Exception("WhisperJni.init() selhalo")
        val rawText =
                WhisperJni.transcribe(
                        audioData = floatSamples,
                        language = sm.whisperLanguage, // User volba — žádný hardcoded fallback
                        nThreads = threadCount
                )
                        ?: ""

        onProgress(85, "Segmentace bloků...")
        val durationMs = (floatSamples.size.toLong() / SAMPLE_RATE) * 1000L
        outBlocks.addAll(segEngine.segment(rawText, 0L, durationMs, null))
    }

    // ========================================================================
    // SHERPA — plná podpora OFFLINE i ONLINE modelů bez narušení TranscriptAgent
    //
    // OFFLINE (STT_OFFLINE_*):
    //   → OfflineRecognizer: celý PCM najednou — optimální pro batch přepis souboru
    //
    // ONLINE (STT_ONLINE_TRANSDUCER / STT_ONLINE_CTC):
    //   → OnlineRecognizer: PCM přehráván po chuncích (100ms) = simulace streamu z file
    //   → Endpoint detection → výsledný text se skládá do celku
    //   → Vhodné pro modely které fungují v obou režimech (některé offline modely
    //     jsou také kompatibilní se streaming API)
    //
    // TranscriptAgent.transcribeOfflineChunk() a runSherpaOnlineLoop() NEJSOU dotčeny.
    // ========================================================================

    private suspend fun transcribeWithSherpa(
            pcmFile: File,
            threadCount: Int,
            sm: SettingsManager,
            segEngine: SegmentationEngine,
            outBlocks: MutableList<SrtBlock>,
            onProgress: suspend (Int, String) -> Unit
    ) {
        val modelManager = SherpaModelManager(applicationContext, sm)
        val paths =
                modelManager.getSttModelPaths()
                        ?: throw Exception("Sherpa model nenalezen — zkontrolujte nastavení modelu")

        val pcmBytes = withContext(Dispatchers.IO) { pcmFile.readBytes() }
        val floatSamples =
                FloatArray(pcmBytes.size / 2) { i ->
                    val lo = pcmBytes[i * 2].toInt() and 0xFF
                    val hi = pcmBytes[i * 2 + 1].toInt()
                    ((hi shl 8) or lo).toShort() / 32768.0f
                }
        val durationMs = (floatSamples.size.toLong() / SAMPLE_RATE) * 1000L

        if (paths.type.isOnline) {
            // ── ONLINE (Streaming) → simulace streamu z PCM souboru ──────────────
            onProgress(30, "Sherpa Online inference (${floatSamples.size / SAMPLE_RATE}s)...")

            val transConfig =
                    if (paths.type.isTransducer) {
                        com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                                encoder = paths.encoder ?: "",
                                decoder = paths.decoder ?: "",
                                joiner = paths.joiner ?: ""
                        )
                    } else {
                        com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig("", "", "")
                    }
            val ctcConfig =
                    if (paths.type == SherpaModelType.STT_ONLINE_CTC) {
                        com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(
                                model = paths.model ?: ""
                        )
                    } else {
                        com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(model = "")
                    }
            val config =
                    com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                            featConfig =
                                    com.k2fsa.sherpa.onnx.FeatureConfig(
                                            sampleRate = SAMPLE_RATE,
                                            featureDim = 80
                                    ),
                            modelConfig =
                                    com.k2fsa.sherpa.onnx.OnlineModelConfig(
                                            transducer = transConfig,
                                            zipformer2Ctc = ctcConfig,
                                            tokens = paths.tokens,
                                            numThreads = threadCount,
                                            debug = false
                                    ),
                            endpointConfig =
                                    com.k2fsa.sherpa.onnx.EndpointConfig(
                                            rule1 =
                                                    com.k2fsa.sherpa.onnx.EndpointRule(
                                                            false,
                                                            2.4f,
                                                            0.0f
                                                    ),
                                            rule2 =
                                                    com.k2fsa.sherpa.onnx.EndpointRule(
                                                            true,
                                                            1.2f,
                                                            0.0f
                                                    ),
                                            rule3 =
                                                    com.k2fsa.sherpa.onnx.EndpointRule(
                                                            false,
                                                            0.0f,
                                                            20.0f
                                                    )
                                    ),
                            enableEndpoint = true
                    )

            val recognizer =
                    com.k2fsa.sherpa.onnx.OnlineRecognizer(assetManager = null, config = config)
            val stream = recognizer.createStream()
            val sb = StringBuilder()
            var offset = 0
            val total = floatSamples.size

            // Chunk po chunku — simulace real-time streamu
            while (offset < total) {
                val end = minOf(offset + ONLINE_CHUNK_FLOATS, total)
                val chunk = floatSamples.copyOfRange(offset, end)
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                while (recognizer.isReady(stream)) recognizer.decode(stream)
                if (recognizer.isEndpoint(stream)) {
                    val seg = recognizer.getResult(stream).text.trim()
                    if (seg.isNotEmpty()) sb.append(seg).append(" ")
                    recognizer.reset(stream)
                }
                offset = end
                // Progress 30→80 lineárně
                onProgress(
                        30 + ((offset.toFloat() / total) * 50).toInt(),
                        "Sherpa Online ${offset / SAMPLE_RATE}s / ${total / SAMPLE_RATE}s"
                )
            }
            // Finální flush
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val finalSeg = recognizer.getResult(stream).text.trim()
            if (finalSeg.isNotEmpty()) sb.append(finalSeg)
            stream.release()
            recognizer.release()

            onProgress(85, "Segmentace bloků...")
            outBlocks.addAll(segEngine.segment(sb.toString(), 0L, durationMs, null))
        } else {
            // ── OFFLINE (Batch) → OfflineRecognizer: celý soubor najednou ────────
            onProgress(30, "Sherpa Offline inference (${floatSamples.size / SAMPLE_RATE}s)...")

            val modelConfig =
                    when (paths.type) {
                        SherpaModelType.STT_OFFLINE_TRANSDUCER ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        transducer =
                                                com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                                                        encoder = paths.encoder ?: "",
                                                        decoder = paths.decoder ?: "",
                                                        joiner = paths.joiner ?: ""
                                                ),
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                        SherpaModelType.STT_OFFLINE_WHISPER ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        whisper =
                                                com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                                                        encoder = paths.encoder ?: "",
                                                        decoder = paths.decoder ?: "",
                                                        language = sm.sherpaLanguage
                                                ),
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                        SherpaModelType.STT_OFFLINE_MOONSHINE ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        moonshine =
                                                com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(
                                                        preprocessor = paths.preprocessor ?: "",
                                                        encoder = paths.encoder ?: "",
                                                        uncachedDecoder = paths.decoder ?: "",
                                                        cachedDecoder = paths.decoder2 ?: ""
                                                ),
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                        SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        zipformerCtc =
                                                com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(
                                                        model = paths.model ?: ""
                                                ),
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                        SherpaModelType.STT_OFFLINE_SENSEVOICE ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        senseVoice =
                                                com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                                                        model = paths.model ?: "",
                                                        language = sm.sherpaLanguage,
                                                        useInverseTextNormalization = true
                                                ),
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                        // CTC, NeMo, Omnilingual — single model.onnx
                        else ->
                                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        tokens = paths.tokens,
                                        numThreads = threadCount,
                                        provider = "cpu"
                                )
                    }

            val config =
                    com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                            featConfig =
                                    com.k2fsa.sherpa.onnx.FeatureConfig(
                                            sampleRate = SAMPLE_RATE,
                                            featureDim = 80
                                    ),
                            modelConfig = modelConfig
                    )
            val recognizer =
                    com.k2fsa.sherpa.onnx.OfflineRecognizer(assetManager = null, config = config)
            val stream = recognizer.createStream()
            stream.acceptWaveform(floatSamples, SAMPLE_RATE)
            recognizer.decode(stream)
            val rawText = recognizer.getResult(stream).text
            stream.release()
            recognizer.release()

            onProgress(85, "Segmentace bloků...")
            outBlocks.addAll(segEngine.segment(rawText, 0L, durationMs, null))
        }
    }

    // ========================================================================
    // SRT FILE WRITE
    // ========================================================================

    private fun writeSrtFile(blocks: List<SrtBlock>, outFile: File) {
        java.io.BufferedWriter(
                        java.io.OutputStreamWriter(
                                java.io.FileOutputStream(outFile),
                                java.nio.charset.StandardCharsets.UTF_8
                        ),
                        8192
                )
                .use { bw ->
                    blocks.forEachIndexed { i, b ->
                        bw.write("${i + 1}\n${b.startTime} --> ${b.endTime}\n${b.text}\n\n")
                    }
                }
    }

    private fun buildOutputFile(mediaUri: Uri): File {
        val name =
                mediaUri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                        ?: "transcript"
        val outDir = File(getExternalFilesDir(null), "Dictaphone").also { it.mkdirs() }
        val ts =
                java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                        .format(java.util.Date())
        return File(outDir, "${name}_$ts.srt")
    }

    // ========================================================================
    // BROADCASTS
    // ========================================================================

    private fun broadcastProgress(progress: Int, status: String) {
        sendBroadcast(
                Intent(ACTION_SRT_PROGRESS).apply {
                    putExtra(EXTRA_PROGRESS, progress)
                    putExtra(EXTRA_STATUS, status)
                    setPackage(packageName)
                }
        )
    }

    private fun broadcastDone(srtFile: File) {
        sendBroadcast(
                Intent(ACTION_SRT_DONE).apply {
                    putExtra(EXTRA_SRT_FILE_PATH, srtFile.absolutePath)
                    setPackage(packageName)
                }
        )
    }

    private fun broadcastError(error: String) {
        sendBroadcast(
                Intent(ACTION_SRT_ERROR).apply {
                    putExtra(EXTRA_ERROR, error)
                    setPackage(packageName)
                }
        )
    }

    // ========================================================================
    // NOTIFICATION
    // ========================================================================

    private fun createNotificationChannel() {
        val channel =
                NotificationChannel(CHANNEL_ID, "SRT Přepis", NotificationManager.IMPORTANCE_LOW)
                        .apply {
                            description = "Průběh batch přepisu audio/video souborů na SRT titulky"
                            setShowBadge(false)
                        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String, progress: Int): Notification {
        val cancelIntent =
                PendingIntent.getService(
                        this,
                        0,
                        Intent(this, SrtTranscriptionService::class.java).apply {
                            action = ACTION_CANCEL
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        val openIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, AudioStudioActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
        return androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("📝 SRT Přepis")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_mic_24)
                .setProgress(100, progress, progress == 0)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(openIntent)
                .addAction(android.R.drawable.ic_delete, "Zrušit", cancelIntent)
                .build()
    }

    private fun updateNotification(status: String, progress: Int) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status, progress))
    }

    // ========================================================================
    // WAKE LOCK
    // ========================================================================

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock uvolněn")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba WakeLock release: ${e.message}")
        }
        wakeLock = null
    }
}
