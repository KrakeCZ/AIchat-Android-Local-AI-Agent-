package com.example.llama.agent.perception

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.MainActivity
import com.example.llama.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ========================================================================
 * TRANSCRIPT FOREGROUND SERVICE — MediaProjection Host (Android 14/15/16 Compliant)
 * ========================================================================
 *
 * Dedikovaný Foreground Service s foregroundServiceType="mediaProjection",
 * který slouží jako právní hostitel pro MediaProjection token.
 *
 * KRITICKÝ POŽADAVEK Android 14+ (targetSDK 35):
 * `startForeground()` s typem `mediaProjection` vyžaduje, aby AppOps permission
 * `android:project_media` byla již udělena systémem PŘEDTÍM, než je zavolán
 * `startForeground()`. Tato permission se uděluje implicitně, když uživatel
 * přijme dialog z `MediaProjectionManager.createScreenCaptureIntent()`.
 *
 * KOREKTNÍ FLOW:
 * 1. AiAgentAccessibilityService spustí MediaProjectionPermissionActivity
 * 2. Uživatel přijme → resultCode + data Intent (token)
 * 3. Token se uloží do MediaProjectionPermissionActivity.pendingResultCode/Data
 * 4. AccessibilityService spustí tento Service
 * 5. Service zavolá startForeground() (nyní povoleno - project_media je uděleno)
 * 6. Service přečte token z pendingResult* (same process, zero-copy)
 * 7. Service vytvoří Virtual AudioRecord přes SystemAudioInterceptor
 * 8. Service broadcastuje READY → AccessibilityService spustí TranscriptAgent
 *
 * TOKEN PŘEDÁVÁNÍ:
 * Token se NEPŘEDÁVÁ přes Intent extras (selhává na Android 14+ kvůli
 * Binder Parcel lazy unmarshalling). Místo toho se používá statické pole
 * v MediaProjectionPermissionActivity (same process, same ClassLoader).
 *
 * THREAD SAFETY:
 * - pendingResult* jsou @Volatile v companion object
 * - AudioRecord reference je thread-safe přes volatile
 * - Lifecycle je řízen výhradně hlavní službou
 *
 * SECURITY:
 * - foregroundServiceType="mediaProjection" deklarován v Manifestu
 * - FOREGROUND_SERVICE_MEDIA_PROJECTION permission deklarován v Manifestu
 * - Token je one-time-use (Android 14+ per-session consent)
 */
class TranscriptForegroundService : Service() {

    companion object {
        private const val TAG = "TranscriptFgService"
        private const val NOTIFICATION_ID = 201
        private const val CHANNEL_ID = "TranscriptSystemAudioChannel"

        // Intent Actions
        const val ACTION_START_SYSTEM_AUDIO = "com.example.llama.ACTION_START_SYSTEM_AUDIO"

        // Broadcast Actions — posílá výsledek zpět do AccessibilityService
        const val ACTION_SYSTEM_AUDIO_READY = "com.example.llama.ACTION_SYSTEM_AUDIO_READY"
        const val ACTION_SYSTEM_AUDIO_FAILED = "com.example.llama.ACTION_SYSTEM_AUDIO_FAILED"
        const val ACTION_SYSTEM_AUDIO_STOPPED = "com.example.llama.ACTION_SYSTEM_AUDIO_STOPPED"
        
        // Video Actions
        const val ACTION_START_SYSTEM_VIDEO = "com.example.llama.ACTION_START_SYSTEM_VIDEO"
        const val ACTION_STOP_SYSTEM_VIDEO = "com.example.llama.ACTION_STOP_SYSTEM_VIDEO"

        // Graceful shutdown
        const val ACTION_STOP_SYSTEM_AUDIO_GRACEFULLY = "com.example.llama.ACTION_STOP_SYSTEM_AUDIO_GRACEFULLY"

        /** Singleton reference na vytvořený AudioRecord pro přístup z AccessibilityService */
        @Volatile
        var virtualAudioRecord: AudioRecord? = null
            private set
            
        @Volatile
        var virtualDisplay: android.hardware.display.VirtualDisplay? = null
            private set
            
        var imageReader: android.media.ImageReader? = null
        var videoHandlerThread: android.os.HandlerThread? = null
        var isProcessingVideoFrame = false
            private set

        /** Singleton reference na interceptor pro lifecycle management */
        @android.annotation.SuppressLint("StaticFieldLeak")
        @Volatile
        var activeInterceptor: SystemAudioInterceptor? = null
            private set
    }

    private var systemAudioInterceptor: SystemAudioInterceptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "🎧 TranscriptForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SYSTEM_AUDIO -> {
                Log.i(TAG, "▶️ Spouštím MediaProjection FG Service...")

                // KROK 1: MUSÍME zavolat startForeground() OKAMŽITĚ
                // Jinak Android vyhodí ForegroundServiceDidNotStartInTimeException
                val foregroundSuccess = promoteToForeground()
                if (!foregroundSuccess) {
                    // startForeground selhalo — broadcastujeme FAILED a končíme
                    // (startForeground už bylo zavoláno, takže stopSelf je bezpečný)
                    broadcastFailure("startForeground selhalo")
                    stopSelf()
                    return START_NOT_STICKY
                }

                // KROK 2: Přečteme token z static field (same process memory)
                val hasActive = activeInterceptor?.hasActiveProjection == true
                var resultCode = android.app.Activity.RESULT_CANCELED
                var resultData: Intent? = null

                if (!hasActive) {
                    val (code, data) = MediaProjectionPermissionActivity.consumePendingToken()
                    resultCode = code
                    resultData = data

                    if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                        Log.e(TAG, "❌ Chybí MediaProjection token (AUDIO)")
                        broadcastFailure("Chybí MediaProjection token")
                        if (virtualDisplay == null) stopSelf()
                        return START_NOT_STICKY
                    }
                    Log.i(TAG, "✅ Token přečten z paměti (AUDIO, resultCode=$resultCode)")
                } else {
                    Log.i(TAG, "✅ Recykluji existující MediaProjection pro AUDIO.")
                    resultCode = android.app.Activity.RESULT_OK
                    resultData = Intent() // Dummy intent, bude ignorován protože hasActiveProjection == true
                }

                // KROK 3: Vytvoření Virtual AudioRecord z tokenu
                createVirtualAudioRecordFromToken(resultCode, resultData)
                return START_NOT_STICKY
            }
            ACTION_STOP_SYSTEM_AUDIO_GRACEFULLY -> {
                Log.i(TAG, "🛑 Zastavuji MediaProjection gracefully...")
                
                // 1. Řekneme Agentovi, aby zastavil čtení (čímž se uvolní visící read loop)
                val agent = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()?.getOrCreateTranscriptAgentForSrt()
                agent?.stop()

                // 2. Počkáme 300ms na bezpečné doběhnutí C++ threadů předtím, než uvolníme AudioRecord
                CoroutineScope(Dispatchers.Main).launch {
                    delay(300)
                    if (virtualDisplay == null) {
                        stopSelf()
                    } else {
                        val recordToRelease = virtualAudioRecord
                        virtualAudioRecord = null
                        recordToRelease?.stop()
                        recordToRelease?.release()
                    }
                }
                return START_NOT_STICKY
            }
            ACTION_START_SYSTEM_VIDEO -> {
                Log.i(TAG, "▶️ Spouštím MediaProjection FG Service (VIDEO)...")

                val foregroundSuccess = promoteToForeground()
                if (!foregroundSuccess) {
                    broadcastFailure("startForeground selhalo")
                    if (virtualAudioRecord == null) stopSelf()
                    return START_NOT_STICKY
                }

                val hasActive = activeInterceptor?.hasActiveProjection == true
                var resultCode = android.app.Activity.RESULT_CANCELED
                var resultData: Intent? = null

                if (!hasActive) {
                    val (code, data) = MediaProjectionPermissionActivity.consumePendingToken()
                    resultCode = code
                    resultData = data

                    if (resultCode != android.app.Activity.RESULT_OK || resultData == null) {
                        Log.e(TAG, "❌ Chybí MediaProjection token (VIDEO)")
                        if (virtualAudioRecord == null) stopSelf()
                        return START_NOT_STICKY
                    }
                    Log.i(TAG, "✅ Token přečten z paměti (VIDEO, resultCode=$resultCode)")
                } else {
                    Log.i(TAG, "✅ Recykluji existující MediaProjection pro VIDEO.")
                    resultCode = android.app.Activity.RESULT_OK
                    resultData = Intent() // Dummy intent
                }

                val width = intent.getIntExtra("width", 720)
                val height = intent.getIntExtra("height", 1280)
                val density = intent.getIntExtra("density", 320)

                createVirtualDisplayFromToken(resultCode, resultData, width, height, density)
                return START_NOT_STICKY
            }
            ACTION_STOP_SYSTEM_VIDEO -> {
                Log.i(TAG, "🛑 Zastavuji Screen Capture gracefully...")
                imageReader?.setOnImageAvailableListener(null, null)
                virtualDisplay?.release()
                virtualDisplay = null
                imageReader?.close()
                imageReader = null
                videoHandlerThread?.quitSafely()
                videoHandlerThread = null
                if (virtualAudioRecord == null) {
                    systemAudioInterceptor?.release()
                    systemAudioInterceptor = null
                    activeInterceptor = null
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                Log.w(TAG, "⚠️ Neznámá action: ${intent?.action}")
                // I pro neznámou action musíme zavolat startForeground() před stopSelf()
                promoteToForeground()
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "♻️ TranscriptForegroundService destroyed")
        releaseResources()
        super.onDestroy()
    }

    // ========================================================================
    // PRIVATE — Core: startForeground (MUST be called before stopSelf)
    // ========================================================================

    /**
     * Povýší service na Foreground s mediaProjection typem.
     *
     * KRITICKÉ: Tato metoda MUSÍ být zavolána VŽDY před stopSelf(),
     * jinak Android vyhodí ForegroundServiceDidNotStartInTimeException.
     *
     * PREREKVIZITA: project_media AppOps musí být udělena (uživatel přijal consent dialog).
     *
     * @return true pokud se podařilo, false pokud selhalo (ale startForeground bylo zavoláno
     *         s fallback notifikací, takže stopSelf je bezpečný)
     */
    private fun promoteToForeground(): Boolean {
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            Log.i(TAG, "✅ Foreground Service povýšen s mediaProjection typem")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ startForeground() selhalo: ${e.message}", e)
            // Pokusíme se alespoň basic startForeground bez typu
            // (aby stopSelf() nevyhodil ForegroundServiceDidNotStartInTimeException)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "❌ Ani fallback startForeground() se nepodařil: ${e2.message}", e2)
            }
            false
        }
    }

    // ========================================================================
    // PRIVATE — Core: createVirtualAudioRecord
    // ========================================================================

    /**
     * Vytvoří Virtual AudioRecord z MediaProjection tokenu.
     * Výsledný AudioRecord je uložen do [virtualAudioRecord] singleton reference
     * a broadcast ACTION_SYSTEM_AUDIO_READY informuje AccessibilityService.
     */
    private fun createVirtualAudioRecordFromToken(resultCode: Int, data: Intent) {
        try {
            // FIX: Správná recyklace interceptoru, aby nedošlo k vytvoření nového bez existující MediaProjection!
            val interceptor = activeInterceptor ?: SystemAudioInterceptor(applicationContext).also {
                systemAudioInterceptor = it
                activeInterceptor = it
            }

            // Registrace callback pro automatické uvolnění při systémovém ukončení
            interceptor.onProjectionStopped = {
                Log.i(TAG, "🔄 MediaProjection byla ukončena systémem (callback)")
                broadcastStopped()
                stopSelf()
                }

            // ZERO-LAG OPTIMALIZACE: Čteme zvuk na nativní frekvenci OS (typicky 48000), 
            // čímž obejdeme pomalý HW/HAL resampler v Androidu.
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val nativeSampleRate = audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000

            // Buffer: minimálně getMinBufferSize, ale alespoň 10s
            val minBuf = AudioRecord.getMinBufferSize(
                nativeSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = minBuf.coerceAtLeast(nativeSampleRate * 2) // nativeRate * 1s * 2 bytes

            val record = interceptor.createVirtualAudioRecord(
                resultCode, data, nativeSampleRate, bufferSize
            )

            if (record != null) {
                virtualAudioRecord = record
                Log.i(TAG, "✅ Virtual AudioRecord připraven (buffer=${bufferSize}B)")
                broadcastReady()
            } else {
                Log.e(TAG, "❌ createVirtualAudioRecord() vrátil null")
                broadcastFailure("Vytvoření Virtual AudioRecord selhalo")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při vytváření Virtual AudioRecord: ${e.message}", e)
            broadcastFailure("Exception: ${e.message}")
            if (virtualDisplay == null) stopSelf()
        }
    }

    private fun createVirtualDisplayFromToken(resultCode: Int, data: Intent, width: Int, height: Int, density: Int) {
        try {
            val interceptor = activeInterceptor ?: SystemAudioInterceptor(applicationContext).also {
                systemAudioInterceptor = it
                activeInterceptor = it
                it.onProjectionStopped = {
                    Log.i(TAG, "🔄 MediaProjection byla ukončena systémem (callback)")
                    broadcastStopped()
                    stopSelf()
                }
            }

            val reader = android.media.ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val streamer = com.example.llama.agent.vision.VisionSourceManager.ScreenVideoStreamer(applicationContext)
            
            streamer.onFrameProcessed = { compressedBytes ->
                val service = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
                if (service != null) {
                    service.serviceScope.launch {
                        try {
                            val engine = service.getDirectInferenceEngine()
                            engine.processImage(compressedBytes)
                        } catch (e: Exception) {
                            // Ignorováno pro inference engine bez probíhající analýzy
                        }
                    }
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)
                        if (bitmap != null) {
                            service.updateLastCameraFrame(bitmap)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Chyba při dekódování screen framu", e)
                    }
                }
            }
            
            videoHandlerThread = android.os.HandlerThread("ScreenCaptureThread").apply { start() }
            val handler = android.os.Handler(videoHandlerThread!!.looper)

            reader.setOnImageAvailableListener({ ir ->
                try {
                    val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                    if (isProcessingVideoFrame) {
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    isProcessingVideoFrame = true
                    try {
                        streamer.processImage(image) // Uvnitř má finally { image.close() }
                    } finally {
                        isProcessingVideoFrame = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Chyba při čtení ImageReaderu", e)
                    isProcessingVideoFrame = false
                }
            }, handler)

            val display = interceptor.createVirtualDisplay(resultCode, data, width, height, density, reader.surface)
            if (display != null) {
                virtualDisplay = display
                imageReader = reader
                Log.i(TAG, "✅ VirtualDisplay připraven")
            } else {
                Log.e(TAG, "❌ createVirtualDisplay() vrátil null")
                reader.close()
                if (virtualAudioRecord == null) stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při vytváření VirtualDisplay: ${e.message}", e)
            if (virtualAudioRecord == null) stopSelf()
        }
    }

    // ========================================================================
    // PRIVATE — Foreground Notification (Android 14+ Compliant)
    // ========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transkripce systémového zvuku",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikace zobrazená při nahrávání systémového zvuku pro titulky"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🎧 Transkripce systémového zvuku")
            .setContentText("Nahrávám interní audio pro titulky (hlasitost nemá vliv)")
            .setSmallIcon(R.drawable.ic_mic_24)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .build()
    }

    // ========================================================================
    // PRIVATE — Cleanup & Lifecycle
    // ========================================================================

    private fun releaseResources() {
        // KRITICKÉ POŘADÍ: Nejprve vynulujeme singleton reference (atomic),
        // pak fyzicky uvolníme AudioRecord.
        // Důvod: AccessibilityService může získat referenci z virtualAudioRecord companion
        // přes broadcast receiver JEŠTĚ během našeho cleanup → měli by dostat null, ne mrtvou referenci.
        val recordToRelease = virtualAudioRecord
        virtualAudioRecord = null  // Singleton okamžitě vynulovan

        // Uvolňujeme fyzický AudioRecord (nyní singleton je bezpečně null)
        try {
            recordToRelease?.let { record ->
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ AudioRecord cleanup: ${e.message}")
        }
        
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        videoHandlerThread?.quitSafely()
        videoHandlerThread = null

        // Uvolňujeme MediaProjection
        systemAudioInterceptor?.release()
        systemAudioInterceptor = null
        activeInterceptor = null
    }

    // ========================================================================
    // PRIVATE — Broadcast Communication (zpět do AccessibilityService)
    // ========================================================================

    private fun broadcastReady() {
        sendBroadcast(Intent(ACTION_SYSTEM_AUDIO_READY).apply {
            setPackage(packageName)
        })
    }

    private fun broadcastFailure(reason: String) {
        sendBroadcast(Intent(ACTION_SYSTEM_AUDIO_FAILED).apply {
            putExtra("reason", reason)
            setPackage(packageName)
        })
    }

    private fun broadcastStopped() {
        sendBroadcast(Intent(ACTION_SYSTEM_AUDIO_STOPPED).apply {
            setPackage(packageName)
        })
    }
}
