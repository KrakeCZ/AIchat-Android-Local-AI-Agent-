package com.example.llama.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.llama.MainActivity
import com.example.llama.R



/**
 * Zodpovídá za správu notifikací pro LlamaGenerationService.
 * Odděluje prezentační logiku (Notifikace) od byznys logiky služby (Generování).
 */
class LlamaNotificationHelper(private val service: Service) {

    companion object {
        const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "LlamaGenerationChannel"
        
        // Actions
        const val ACTION_RELEASE_RAM = "com.example.llama.ACTION_RELEASE_RAM"
        const val ACTION_START_RESIDENT = "com.example.llama.ACTION_START_RESIDENT"
        const val ACTION_STOP_RESIDENT = "com.example.llama.ACTION_STOP_RESIDENT"
        const val ACTION_DISABLE_AGENT_TTS = "com.example.llama.ACTION_DISABLE_AGENT_TTS"
        const val ACTION_DISABLE_AGENT_STT = "com.example.llama.ACTION_DISABLE_AGENT_STT"
        const val ACTION_DISABLE_AGENT = "com.example.llama.ACTION_DISABLE_AGENT"
        
        // Texty
        private const val TITLE_GENERATING = "AI generuje..."
        private const val CONTENT_GENERATING = "Probíhá výpočet..."
        private const val TITLE_STANDBY = "AI: Standby režim"
        private const val CONTENT_STANDBY = "Model je připraven v RAM k okamžité akci."
    }

    init {
        createNotificationChannel()
    }

    /**
     * Update notification - default behavior (Chat Only / Standby)
     */
    fun updateNotification(isGenerating: Boolean) {
        // ASCII ART SPEC: "AI chat Model v RAM"
        val title = "AI chat Model v RAM"
        val chatStatus = if (isGenerating) "Generuje..." else "Idle"
        val content = "Chat: $chatStatus"
        
        val notification = buildNotification(title, content, isGenerating, agentState = null)
        startForeground(notification, useMicrophone = false)
    }
    
    /**
     * Update notification s agent mode state (Enhanced)
     */
    fun updateNotificationWithAgent(isGenerating: Boolean, agentState: AgentNotificationState) {
        // ASCII ART SPEC: "🤖 AI Model v RAM"
        val title = "🤖 AI Model v RAM"
        
        // Build detailed content: "Chat: X | Agent: Y"
        val chatStatus = if (isGenerating) "Generuje" else "Idle"
        
        val agentStatus = when {
            agentState.isAgentActive && agentState.isListening -> "Poslouchá hlasem"
            agentState.isAgentActive -> "V pohotovosti"
            else -> "Vypnut"
        }
        
        // ASCII ART SPEC: "Chat: Idle            Agent: Poslouchá hlasem"
        val content = "Chat: $chatStatus | Agent: $agentStatus"
        
        val notification = buildNotification(title, content, isGenerating, agentState)
        // Use microphone only if Agent STT is enabled
        startForeground(notification, useMicrophone = agentState.isSttEnabled)
    }
    
    private fun startForeground(notification: Notification, useMicrophone: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (useMicrophone) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                service.startForeground(NOTIFICATION_ID, notification, types)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("NotifHelper", "startForeground failed, falling back to specialUse only", e)
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    service.startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    service.startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e2: Exception) {
                android.util.Log.e("NotifHelper", "Fallback startForeground also failed", e2)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Llama AI Standby", NotificationManager.IMPORTANCE_LOW)
            val manager = service.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        title: String, 
        content: String, 
        isGenerating: Boolean,
        agentState: AgentNotificationState?
    ): Notification {
        val openAppIntent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            service, 0, openAppIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification_24)
            .setOngoing(true) // Always ongoing (RAM Anchor)
            .setAutoCancel(false)
            .setContentIntent(openAppPendingIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true) // Prevent noise on updates

        // Dynamic action buttons based on state
        if (agentState?.isAgentActive == true) {
            // Agent mode active → show 4 agent control buttons
            addAgentActions(builder, agentState)
        } else {
            // Standby/Chat (no agent) → show only Unload RAM
            // ASCII ART SPEC: [Uvolnit RAM]
            addAction(builder, ACTION_RELEASE_RAM, "Uvolnit RAM", 1)
        }

        return builder.build()
    }
    
    private fun addAgentActions(builder: NotificationCompat.Builder, state: AgentNotificationState) {
        var requestCode = 10 // Start from 10 to avoid conflicts
        
        // ASCII ART SPEC BUTTONS:
        // [Vypnout TTS] [Vypnout STT] [Vypnout Agenta] [Uvolnit RAM]
        
        // Button 1: Vypnout TTS (if enabled)
        if (state.isTtsEnabled) {
            addAction(builder, ACTION_DISABLE_AGENT_TTS, "Vypnout TTS", requestCode++)
        }
        
        // Button 2: Vypnout STT (if enabled)
        if (state.isSttEnabled) {
            // Correcting user typo "SST" to "STT" for professionalism, unless strictly requested otherwise
            addAction(builder, ACTION_DISABLE_AGENT_STT, "Vypnout STT", requestCode++)
        }
        
        // Button 3: Vypnout Agenta (always if agent active)
        addAction(builder, ACTION_DISABLE_AGENT, "Vypnout Agenta", requestCode++)
        
        // Button 4: Uvolnit RAM (always)
        addAction(builder, ACTION_RELEASE_RAM, "Uvolnit RAM", requestCode)
    }
    
    private fun addAction(builder: NotificationCompat.Builder, action: String, label: String, requestCode: Int) {
        val intent = Intent(service, LlamaGenerationService::class.java).apply { this.action = action }
        val pendingIntent = PendingIntent.getService(
            service, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(0, label, pendingIntent) // 0 = no icon (text only)
    }
}

/**
 * Agent notification state data
 */
data class AgentNotificationState(
    val isAgentActive: Boolean,
    val isListening: Boolean = false,
    val isTtsEnabled: Boolean = false,
    val isSttEnabled: Boolean = false
)
