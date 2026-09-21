package com.example.llama.agent.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.llama.MainActivity
import com.example.llama.R
import com.example.llama.SettingsManager

/**
 * Zobrazuje a spravuje trvalou Foreground/Background notifikaci přímo pro AiAgentAccessibilityService.
 * Obsahuje Action Buttons pro rychlé přepínání workerů (Omni-Control - Možnost A).
 */
class OmniControlNotificationHelper(
    private val context: Context,
    private val settingsManager: SettingsManager
) {

    companion object {
        const val NOTIFICATION_ID = 102
        private const val CHANNEL_ID = "OmniControlChannel"

        const val ACTION_TOGGLE_VISION = "com.example.llama.omni.TOGGLE_VISION"
        const val ACTION_TOGGLE_AUDIO = "com.example.llama.omni.TOGGLE_AUDIO"
        const val ACTION_TOGGLE_OCR = "com.example.llama.omni.TOGGLE_OCR"
        const val ACTION_TOGGLE_SUMMARIZATION = "com.example.llama.omni.TOGGLE_SUMMARIZATION"
        const val ACTION_TOGGLE_TRANSCRIPT = "com.example.llama.omni.TOGGLE_TRANSCRIPT"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Broadcast receiver pro zachytávání kliknutí z notifikace
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                when (action) {
                    ACTION_TOGGLE_VISION -> {
                        settingsManager.isPerceptionEnabled = !settingsManager.isPerceptionEnabled
                        showOrUpdateNotification()
                    }
                    ACTION_TOGGLE_AUDIO -> {
                        settingsManager.isAudioPerceptionEnabled = !settingsManager.isAudioPerceptionEnabled
                        showOrUpdateNotification()
                    }
                    ACTION_TOGGLE_OCR -> {
                        settingsManager.isOcrEnabled = !settingsManager.isOcrEnabled
                        showOrUpdateNotification()
                    }
                    ACTION_TOGGLE_SUMMARIZATION -> {
                        settingsManager.isSummarizationEnabled = !settingsManager.isSummarizationEnabled
                        showOrUpdateNotification()
                    }
                    ACTION_TOGGLE_TRANSCRIPT -> {
                        settingsManager.isTranscriptEnabled = !settingsManager.isTranscriptEnabled
                        showOrUpdateNotification()
                    }
                }
            }
        }
    }

    init {
        createNotificationChannel()
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_VISION)
            addAction(ACTION_TOGGLE_AUDIO)
            addAction(ACTION_TOGGLE_OCR)
            addAction(ACTION_TOGGLE_SUMMARIZATION)
            addAction(ACTION_TOGGLE_TRANSCRIPT)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            actionReceiver,
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Omni-Control Agent Toolbar",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Toolbar pro rychlé přepínání agent workerů"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showOrUpdateNotification() {
        if (!settingsManager.isAgentEnabled) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Omni-Control Agent Toolbar")
            .setContentText("Kontrola background workerů")
            .setSmallIcon(R.drawable.ic_notification_24) 
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent)
            .setOnlyAlertOnce(true)

        // Vision Button
        val visionState = if (settingsManager.isPerceptionEnabled) "🟩 Vision On" else "🟥 Vision Off"
        val visionIntent = Intent(ACTION_TOGGLE_VISION).setPackage(context.packageName)
        val visionPendingIntent = PendingIntent.getBroadcast(
            context, 101, visionIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, visionState, visionPendingIntent)

        // Audio Button
        val audioState = if (settingsManager.isAudioPerceptionEnabled) "🟩 Audio On" else "🟥 Audio Off"
        val audioIntent = Intent(ACTION_TOGGLE_AUDIO).setPackage(context.packageName)
        val audioPendingIntent = PendingIntent.getBroadcast(
            context, 102, audioIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, audioState, audioPendingIntent)

        // OCR Button
        val ocrState = if (settingsManager.isOcrEnabled) "🟩 OCR On" else "🟥 OCR Off"
        val ocrIntent = Intent(ACTION_TOGGLE_OCR).setPackage(context.packageName)
        val ocrPendingIntent = PendingIntent.getBroadcast(
            context, 103, ocrIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, ocrState, ocrPendingIntent)

        // Summarization Button
        val sumState = if (settingsManager.isSummarizationEnabled) "🟩 Sum On" else "🟥 Sum Off"
        val sumIntent = Intent(ACTION_TOGGLE_SUMMARIZATION).setPackage(context.packageName)
        val sumPendingIntent = PendingIntent.getBroadcast(
            context, 104, sumIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, sumState, sumPendingIntent)

        // Transcript Button
        val transState = if (settingsManager.isTranscriptEnabled) "🟩 Titulky On" else "🟥 Titulky Off"
        val transIntent = Intent(ACTION_TOGGLE_TRANSCRIPT).setPackage(context.packageName)
        val transPendingIntent = PendingIntent.getBroadcast(
            context, 105, transIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, transState, transPendingIntent)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    fun destroy() {
        try {
            context.unregisterReceiver(actionReceiver)
        } catch (e: Exception) {
            // ignore
        }
        notificationManager.cancel(NOTIFICATION_ID)
    }
}
