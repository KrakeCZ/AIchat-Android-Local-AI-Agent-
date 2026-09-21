package com.example.llama.agent.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.util.Log

/**
 * AiAgentVoiceInteractionService — ROLE_ASSISTANT registrace
 *
 * Umožňuje app být nastavena jako "Aplikace digitálního asistenta"
 * v Android Settings → Výchozí aplikace → Digitální asistent.
 *
 * Propojení s AccessibilityService:
 *   VIS (tento) = lightweight always-running → deleguje na AS singleton
 *   AS  = AiAgentAccessibilityService → screen reading, actions, coordinator
 *
 * Trigger:
 *   - Dlouhý stisk Home tlačítka
 *   - "Hey Google" replacement (pokud nastaveno jako výchozí)
 *   - VoiceInteractionSession pro AssistStructure
 */
class AiAgentVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "AiAgentVIS"
    }

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "🎙️ VoiceInteractionService READY — app je digitální asistent")

        // Propojení: Ověř že AccessibilityService běží
        val agentService = AiAgentAccessibilityService.getInstance()
        if (agentService != null) {
            Log.i(TAG, "✅ AccessibilityService propojení OK")
        } else {
            Log.w(TAG, "⚠️ AccessibilityService NENÍ aktivní — Tier 2/3 nebude fungovat bez ní")
        }
    }

    /**
     * HW tlačítko trigger (dlouhý stisk Home / Power)
     * showSession() otevře VoiceInteractionSession
     */
    override fun onLaunchVoiceAssistFromKeyguard() {
        Log.i(TAG, "🔑 Launch from keyguard (locked screen)")
        // VoiceInteractionSession.SHOW_WITH_ASSIST = 1
        showSession(Bundle(), 1)
    }

    override fun onShutdown() {
        Log.i(TAG, "🛑 VoiceInteractionService shutdown")
        super.onShutdown()
    }
}
