package com.example.llama.domain.voice

import kotlinx.coroutines.flow.StateFlow

// Definition of Voice States for UI
// Definition of Voice States for UI
sealed class VoiceState {
    object Idle : VoiceState()
    data class Speaking(val text: String) : VoiceState() // Feedback when TTS is active
    data class Error(val message: String, val isCritical: Boolean) : VoiceState()
}

// The Interface Contract
interface VoiceAssistant {
    val state: StateFlow<VoiceState>
    fun speak(text: String)
    fun stopSpeaking()
    fun cleanup() // Explicit resource release
}
