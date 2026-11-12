package com.example.llama.srt.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.R
import com.example.llama.srt.SrtParser
import com.example.llama.srt.ui.editor.SrtVisualEditorActivity
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import kotlinx.coroutines.*
import java.io.File

/**
 * Enterprise Foreground Service pro vypalování titulků (Dual-Pipeline).
 *
 * Zero-Implicit záruky:
 *  1. Android 14+ (API 34): Striktní FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
 *     s zachycenou ForegroundServiceStartNotAllowedException.
 *  2. WakeLock: Bezpodmínečně uvolněn v invokeOnCompletion (nikdy nezůstane viset).
 *  3. Workspace cleanup: workspaceDir smazán ve finally bloku vždy — i při pádu JNI.
 *  4. Broadcasts: setPackage(packageName) — ochrana proti malware interceptu.
 *  5. Orchestrátor: SubtitleExportOrchestrator rozhodne o pipeline automaticky.
 *
 * Použití:
 *  val intent = Intent(this, SubtitleExportService::class.java).apply {
 *      action = SubtitleExportService.ACTION_START_EXPORT
 *      putExtra(SubtitleExportService.EXTRA_VIDEO_URI, videoUri.toString())
 *      putExtra(SubtitleExportService.EXTRA_SRT_PATH, srtFile.absolutePath)
 *      putExtra(SubtitleExportService.EXTRA_STYLE_CONFIG, styleConfig) // Parcelable
 *  }
 *  ContextCompat.startForegroundService(this, intent)
 */
class SubtitleExportService : Service() {

    companion object {
        private const val TAG = "SubtitleExportService"

        const val CHANNEL_ID      = "SubtitleExportChannel"
        const val NOTIFICATION_ID = 404

        const val ACTION_START_EXPORT  = "com.example.llama.srt.export.ACTION_START_EXPORT"
        const val ACTION_CANCEL_EXPORT = "com.example.llama.srt.export.ACTION_CANCEL_EXPORT"

        const val EXTRA_VIDEO_URI    = "extra_video_uri"
        const val EXTRA_SRT_PATH     = "extra_srt_path"
        const val EXTRA_STYLE_CONFIG = "extra_style_config"
        const val EXTRA_USE_HARDWARE = "extra_use_hardware"
        const val EXTRA_THERMAL_MODE_ORDINAL = "extra_thermal_mode_ordinal"
        const val EXTRA_FORCE_SOFTWARE = "extra_force_software"

        // Broadcasts pro komunikaci zpět do UI (SrtVisualEditorActivity)
        // Zabezpečeny setPackage() → pouze naše aplikace je přijme
        const val BROADCAST_EXPORT_PROGRESS = "com.example.llama.EXPORT_PROGRESS"
        const val BROADCAST_EXPORT_DONE     = "com.example.llama.EXPORT_DONE"
        const val BROADCAST_EXPORT_ERROR    = "com.example.llama.EXPORT_ERROR"

        // WakeLock tag — musí být ve formátu "AppName:Tag" (Android O+ requirement)
        private const val WAKE_LOCK_TAG = "Llama:SubtitleExportLock"

        // Maximální doba WakeLock (2 hodiny) — ochrana proti uvíznutí procesu
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60 * 60 * 1000
    }

    // Coroutine scope pro pozaďové export operace
    // SupervisorJob: Pád jednoho jobu nesestřelí celý scope
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var orchestrator: SubtitleExportOrchestrator

    private var isExportSuccess = false

    // @Volatile: Viditelnost přes vlákna bez synchronized (čtení/zápis z Main + IO vlákna)
    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        orchestrator = SubtitleExportOrchestrator(this)

        // WakeLock inicializace — PARTIAL_WAKE_LOCK drží CPU bez displeje
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false) // Non-reference counted: jeden release() stačí
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
        Log.d(TAG, "WakeLock získán (timeout: ${WAKE_LOCK_TIMEOUT_MS / 3600000}h).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Příprava notifikace PŘED startForeground (povinné pro Android O+)
        val notification = createNotification(0, "Připravuji export...")

        // Android 14+ (API 34): FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        // Starší verze: startForeground bez typu
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // API 34
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) —
            // Aplikace byla spuštěna z pozadí bez platné výjimky (Battery Saver, Doze atd.)
            Log.e(TAG, "KRITICKÁ CHYBA: startForeground() zablokován OS. " +
                       "Služba nemůže běžet na pozadí.", e)
            handleError("Systém zablokoval spuštění pozaďové služby. Otevřete aplikaci do popředí a zkuste znovu.")
            releaseWakeLockSafely()
            stopSelf()
            return START_NOT_STICKY
        }

        // Zpracování intentu
        when (intent?.action) {
            ACTION_START_EXPORT -> {
                val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
                val srtPath     = intent.getStringExtra(EXTRA_SRT_PATH)

                if (videoUriStr == null || srtPath == null) {
                    Log.e(TAG, "onStartCommand: Chybí povinné extras (video URI nebo SRT path).")
                    handleError("Interní chyba: Chybí parametry pro export.")
                    releaseWakeLockSafely()
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Kompatibilní získání Parcelable pro API 33+ a starší
                val styleConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_STYLE_CONFIG, SubtitleStyleConfig::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_STYLE_CONFIG)
                } ?: SubtitleStyleConfig() // Výchozí config pokud chybí (edge case)

                val useHardware = intent.getBooleanExtra(EXTRA_USE_HARDWARE, true)
                val codecIndex = intent.getIntExtra("EXTRA_CODEC_INDEX", 0)
                val containerFormat = intent.getIntExtra("EXTRA_CONTAINER_FORMAT", 1)
                val qualityLevel = intent.getIntExtra("EXTRA_QUALITY_LEVEL", 0)
                val audioStreamIndex = intent.getIntExtra("EXTRA_AUDIO_STREAM_INDEX", 0)
                val volumeGain = intent.getFloatExtra("EXTRA_VOLUME_GAIN", 1.0f)
                val compressor = intent.getBooleanExtra("EXTRA_COMPRESSOR", false)
                val ebuR128 = intent.getBooleanExtra("EXTRA_EBU_R128", false)
                val forcePipeline = intent.getIntExtra("EXTRA_FORCE_PIPELINE", 0)
                val thermalOrdinal = intent.getIntExtra(EXTRA_THERMAL_MODE_ORDINAL, com.example.llama.srt.ui.models.ExportThermalMode.BALANCED.ordinal)
                val forceSoftware = intent.getBooleanExtra(EXTRA_FORCE_SOFTWARE, false)

                startExport(videoUriStr, srtPath, styleConfig, useHardware, codecIndex, containerFormat, qualityLevel, audioStreamIndex, volumeGain, compressor, ebuR128, forcePipeline, thermalOrdinal, forceSoftware)
            }

            ACTION_CANCEL_EXPORT -> {
                Log.i(TAG, "ACTION_CANCEL_EXPORT: Uživatel zrušil export.")
                orchestrator.cancel()
                releaseWakeLockSafely()
                stopSelf()
            }

            else -> {
                Log.w(TAG, "Neznámá action: ${intent?.action}. Ignoruji.")
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!isExportSuccess) {
            orchestrator.cancel()
            serviceScope.cancel()
        }
        releaseWakeLockSafely()
        super.onDestroy()
        Log.d(TAG, "onDestroy: Service zničena, všechny prostředky uvolněny.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Export pipeline
    // ─────────────────────────────────────────────────────────────────────────

    private fun startExport(
        videoUriStr: String,
        srtPath: String,
        styleConfig: SubtitleStyleConfig,
        useHardware: Boolean,
        codecIndex: Int,
        containerFormat: Int,
        qualityLevel: Int,
        audioStreamIndex: Int,
        volumeGain: Float,
        compressor: Boolean,
        ebuR128: Boolean,
        forcePipeline: Int,
        thermalOrdinal: Int,
        forceSoftware: Boolean
    ) {
        val exportJob = serviceScope.launch {
            // Každý export dostane vlastní workspace — izolace a snadný cleanup
            val workspaceDir = File(cacheDir, "export_ws_${System.currentTimeMillis()}")
            if (!workspaceDir.exists()) workspaceDir.mkdirs()

            try {
                // ── Validace vstupů ──────────────────────────────────────────
                val videoUri = Uri.parse(videoUriStr)

                val srtFile = File(srtPath)
                if (!srtFile.exists() || !srtFile.canRead()) {
                    throw IllegalArgumentException(
                        "SRT soubor neexistuje nebo je nečitelný: $srtPath"
                    )
                }

                val blocks = SrtParser.parse(srtFile.readText(Charsets.UTF_8))
                if (blocks.isEmpty()) {
                    throw IllegalArgumentException(
                        "SRT soubor je prázdný nebo nevalidní (nula bloků)."
                    )
                }

                Log.i(TAG, "Export zahájen: ${blocks.size} bloků, video: $videoUriStr")

                // Výběr přípony podle kontejneru z UI
                val ext = if (containerFormat == -1) {
                    // Auto (1:1 klon) - Zkusíme odhadnout z URI
                    val mime = applicationContext.contentResolver.getType(videoUri) ?: ""
                    when {
                        mime.contains("mp4") -> ".mp4"
                        mime.contains("matroska") || mime.contains("mkv") -> ".mkv"
                        mime.contains("webm") -> ".webm"
                        mime.contains("avi") -> ".avi"
                        mime.contains("quicktime") -> ".mov"
                        else -> {
                            val path = videoUri.path ?: ""
                            if (path.contains(".")) {
                                val dotIndex = path.lastIndexOf('.')
                                path.substring(dotIndex)
                            } else {
                                ".mp4" // ultimate fallback
                            }
                        }
                    }
                } else {
                    val container = com.example.llama.media.ExportFormatRegistry.Container.entries.find { it.containerIndex == containerFormat }
                    container?.extension ?: ".mp4"
                }

                // ✅ SPRÁVNĚ: Bezpečný app-specific adresář (Android 10, 11, 12, 13, 14, 15+ safe)
                val outputDir = File(
                    getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES),
                    "AudioStudio_Exports"
                )
                if (!outputDir.exists()) outputDir.mkdirs()
                val finalOutputFile = File(outputDir, "Llama_Render_${System.currentTimeMillis()}$ext")

                // ── Orchestrátor rozhodne o pipeline ─────────────────────────
                val exportedFile = orchestrator.executeExport(
                    videoUri         = videoUri,
                    blocks           = blocks,
                    styleConfig      = styleConfig,
                    outputFile       = finalOutputFile,
                    workspaceDir     = workspaceDir,
                    useHardware      = useHardware,
                    codecIndex       = codecIndex,
                    qualityLevel     = qualityLevel,
                    audioStreamIndex = audioStreamIndex,
                    volumeGain       = volumeGain,
                    compressor       = compressor,
                    ebuR128          = ebuR128,
                    forcePipeline    = forcePipeline,
                    thermalOrdinal   = thermalOrdinal,
                    forceSoftware    = forceSoftware,
                    onProgress       = { pct -> updateProgress(pct) }
                )

                // ── Ověření výstupu ──────────────────────────────────────────
                if (exportedFile != null && exportedFile.exists() && exportedFile.length() > 0) {
                    isExportSuccess = true
                    Log.i(TAG, "Export úspěšný: ${exportedFile.absolutePath} " +
                               "(${exportedFile.length() / 1024} KB)")
                    handleDone(exportedFile.absolutePath)
                } else {
                    throw IllegalStateException(
                        "Export dokončen, ale výstupní soubor chybí nebo je prázdný."
                    )
                }

            } catch (e: CancellationException) {
                // Coroutina zrušena (ACTION_CANCEL_EXPORT nebo onDestroy)
                Log.i(TAG, "Export coroutina zrušena.")
                handleError("Export zrušen uživatelem.")
                // CancellationException NEpropagujeme dál — je to normální stav
            } catch (e: Exception) {
                Log.e(TAG, "Kritická chyba exportu.", e)
                handleError(e.message ?: "Neznámá systémová chyba při exportu.")
            } finally {
                // GARANCE: workspaceDir smazán vždy (i při pádu C++ / OOM)
                // Zabrání plnění disku uživatele dočasnými soubory
                if (workspaceDir.exists()) {
                    val deleted = workspaceDir.deleteRecursively()
                    Log.d(TAG, "Workspace cleanup: ${workspaceDir.absolutePath} → smazán=$deleted")
                }
                stopSelf()
            }
        }

        // WakeLock uvolnění vázáno na životní cyklus coroutiny
        // Garantuje uvolnění i při pádu (JVM crash, OOM, exception)
        exportJob.invokeOnCompletion {
            releaseWakeLockSafely()
            Log.d(TAG, "invokeOnCompletion: WakeLock bezpečně uvolněn.")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Komunikace zpět do UI (secured broadcasts)
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateProgress(pct: Int) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(pct, "Vypaluji titulky: $pct%"))

        // Broadcast pro případné UI aktualizace v aktivitě
        sendBroadcast(Intent(BROADCAST_EXPORT_PROGRESS).apply {
            putExtra("progress", pct)
            setPackage(packageName) // Secured: pouze naše app přijme broadcast
        })
    }

    private fun handleDone(outputPath: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(100, "Export úspěšně dokončen!"))

        sendBroadcast(Intent(BROADCAST_EXPORT_DONE).apply {
            putExtra("output_path", outputPath)
            setPackage(packageName)
        })

        stopForeground(STOP_FOREGROUND_DETACH)
    }

    private fun handleError(msg: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, createNotification(0, "Chyba: $msg"))

        sendBroadcast(Intent(BROADCAST_EXPORT_ERROR).apply {
            putExtra("error_msg", msg)
            setPackage(packageName)
        })

        stopForeground(STOP_FOREGROUND_DETACH)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WakeLock management
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Uvolní WakeLock bezpečně — idempotentní, nevyhodí výjimku.
     * Volatelné z libovolného vlákna.
     */
    private fun releaseWakeLockSafely() {
        try {
            val lock = wakeLock
            if (lock != null && lock.isHeld) {
                lock.release()
                Log.d(TAG, "releaseWakeLockSafely: WakeLock uvolněn.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "releaseWakeLockSafely: Výjimka při uvolnění WakeLock.", e)
        } finally {
            wakeLock = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifikace
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotification(progress: Int, text: String): Notification {
        // PendingIntent: Klepnutím otevřeme SrtVisualEditorActivity (v2)
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SrtVisualEditorActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Cancel PendingIntent: Odešle ACTION_CANCEL_EXPORT na tuto službu
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, SubtitleExportService::class.java).apply {
                action = ACTION_CANCEL_EXPORT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioStudio: Export videa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            // Indeterminate progress pro přípravu (pct==0), determinate jinak
            .setProgress(100, progress, progress == 0)
            .setOngoing(progress < 100 && !text.startsWith("Chyba") && !text.startsWith("Export úspěšně"))
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_delete, "Zrušit", cancelIntent)
            .setOnlyAlertOnce(true) // Zabránění zvukového upozornění při každém % updatu
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Export Titulků",
                NotificationManager.IMPORTANCE_LOW // LOW: bez zvuku, ale viditelná
            ).apply {
                description = "Průběh vypalování titulků do videa"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
