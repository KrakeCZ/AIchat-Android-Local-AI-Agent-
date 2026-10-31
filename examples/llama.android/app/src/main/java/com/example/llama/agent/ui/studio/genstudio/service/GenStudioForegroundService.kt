package com.example.llama.agent.ui.studio.genstudio.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.llama.agent.ui.studio.genstudio.GenStudioActivity

/**
 * Robustní Foreground Service pro offline generování AI videa a obrazu (GEN_Studio).
 * Zabraňuje agresivnímu uspání procesoru (Doze Mode / Quick Freeze) a LMK OOM zabití
 * procesu během dlouhé neuronové syntézy (Wan 2.1 difúze, T5-XXL kódování a 3D-VAE).
 *
 * Kompatibilní s Android 14/15/16 (foregroundServiceType="dataSync").
 */
class GenStudioForegroundService : Service() {

    companion object {
        private const val TAG = "GenStudioService"
        const val CHANNEL_ID = "gen_studio_generation_channel"
        const val NOTIFICATION_ID = 4091

        const val ACTION_START = "com.example.llama.genstudio.action.START"
        const val ACTION_UPDATE = "com.example.llama.genstudio.action.UPDATE"
        const val ACTION_STOP = "com.example.llama.genstudio.action.STOP"
        const val ACTION_CANCEL = "com.example.llama.genstudio.action.CANCEL"

        const val EXTRA_PROMPT = "extra_prompt"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_MESSAGE = "extra_message"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_TOTAL = "extra_total"
        const val EXTRA_COMPLETED_FILE = "extra_completed_file"

        @Volatile
        var onCancelListener: (() -> Unit)? = null

        var onCancelRequested: (() -> Unit)?
            get() = onCancelListener
            set(value) { onCancelListener = value }

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun start(context: Context, prompt: String) {
            val intent = Intent(context, GenStudioForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PROMPT, prompt)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun startService(context: Context, prompt: String) = start(context, prompt)

        fun updateProgress(context: Context, title: String, message: String, progress: Int, total: Int) {
            if (!isServiceRunning) return
            val intent = Intent(context, GenStudioForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_PROGRESS, progress)
                putExtra(EXTRA_TOTAL, total)
            }
            context.startService(intent)
        }

        fun updateProgress(context: Context, progress: Int, message: String) {
            updateProgress(context, "🎬 GEN_Studio Syntéza", message, progress, 100)
        }

        fun stop(context: Context, completedFileName: String? = null) {
            val intent = Intent(context, GenStudioForegroundService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_COMPLETED_FILE, completedFileName)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) = stop(context)
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var notificationManager: NotificationManager
    private var currentPrompt: String = ""

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        acquireWakeLock()
        isServiceRunning = true
        Log.i(TAG, "GenStudioForegroundService vytvořena a WakeLock alokován.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPrompt = intent.getStringExtra(EXTRA_PROMPT) ?: "Generování scény"
                val notification = buildOngoingNotification(
                    title = "🎬 GEN_Studio: Inicializace...",
                    message = currentPrompt,
                    progress = 0,
                    total = 100
                )
                startWithForegroundType(notification)
            }

            ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "🎬 GEN_Studio: Generování..."
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: currentPrompt
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val total = intent.getIntExtra(EXTRA_TOTAL, 100)

                val notification = buildOngoingNotification(title, message, progress, total)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }

            ACTION_CANCEL -> {
                Log.w(TAG, "Uživatel zrušil generování z notifikační lišty.")
                onCancelListener?.invoke()
                stopForegroundAndSelf(completedFileName = null, wasCancelled = true)
            }

            ACTION_STOP -> {
                val completedFile = intent.getStringExtra(EXTRA_COMPLETED_FILE)
                stopForegroundAndSelf(completedFileName = completedFile, wasCancelled = false)
            }
        }
        return START_NOT_STICKY
    }

    private fun startWithForegroundType(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                }
                if (serviceType != 0) {
                    startForeground(NOTIFICATION_ID, notification, serviceType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground s typem selhalo, fallback na standardní startForeground: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "I fallback startForeground selhal: ${e2.message}", e2)
            }
        }
    }

    private fun buildOngoingNotification(title: String, message: String, progress: Int, total: Int): Notification {
        val openAppIntent = Intent(this, GenStudioActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, GenStudioForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val pendingCancelIntent = PendingIntent.getService(
            this, 1, cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(pendingOpenIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(Color.parseColor("#00E5FF"))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Zrušit", pendingCancelIntent)

        if (total > 0 && progress >= 0) {
            builder.setProgress(total, progress, false)
        } else {
            builder.setProgress(0, 0, true) // Indeterminate pro inicializaci
        }

        return builder.build()
    }

    private fun stopForegroundAndSelf(completedFileName: String?, wasCancelled: Boolean) {
        releaseWakeLock()
        isServiceRunning = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        if (!wasCancelled && !completedFileName.isNullOrBlank()) {
            triggerCompletionFeedback()
            showCompletionNotification(completedFileName)
        }

        stopSelf()
    }

    private fun showCompletionNotification(fileName: String) {
        val openAppIntent = Intent(this, GenStudioActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenIntent = PendingIntent.getActivity(
            this, 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setContentTitle("✅ AI Video Dokončeno!")
            .setContentText("Výstup: $fileName je připraven v Přehrávači.")
            .setContentIntent(pendingOpenIntent)
            .setAutoCancel(true)
            .setColor(Color.parseColor("#10B981"))
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notif)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun triggerCompletionFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(200)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibrace selhala: ${e.message}")
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "AiChat:GenStudioWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    // Bezpečnostní timeout 15 minut pro případ neočekávaného pádu vlákna
                    acquire(15 * 60 * 1000L)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při alokaci WakeLock: ${e.message}", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při uvolnění WakeLock: ${e.message}", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GEN_Studio AI Syntéza",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Průběh generování neuronového videa a obrazu na pozadí"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        isServiceRunning = false
        super.onDestroy()
    }
}
