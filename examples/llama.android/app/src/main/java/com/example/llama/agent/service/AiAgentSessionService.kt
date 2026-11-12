package com.example.llama.agent.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * AiAgentSessionService — Factory pro VoiceInteractionSession
 *
 * Vytváří AiAgentSession instance pro každou interakci.
 * Běží v SEPARÁTNÍM procesu od VoiceInteractionService (best practice).
 */
class AiAgentSessionService : VoiceInteractionSessionService() {

    companion object {
        private const val TAG = "AiAgentSessionSvc"
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Log.i(TAG, "🆕 Creating new VoiceInteractionSession")
        return AiAgentSession(this)
    }
}
