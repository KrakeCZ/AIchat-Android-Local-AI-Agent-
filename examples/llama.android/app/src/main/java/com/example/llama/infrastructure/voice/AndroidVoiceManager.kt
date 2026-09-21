package com.example.llama.infrastructure.voice

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.example.llama.agent.core.audio.AgentAudioOrchestrator
import com.example.llama.domain.voice.VoiceAssistant
import com.example.llama.domain.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class AndroidVoiceManager(
    private val context: Context,
    private val scope: CoroutineScope // Pass Application or ViewModel scope
) : VoiceAssistant, DefaultLifecycleObserver, TextToSpeech.OnInitListener {

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val isTtsReady = AtomicBoolean(false)
    private var tts: TextToSpeech? = null
    private var sherpaTts: com.example.llama.agent.voice.sherpa.SherpaTtsEngine? = null

    // --- Initialization Logic (Lazy) ---

    private fun ensureTtsInitialized() {
        val settings = com.example.llama.SettingsManager(context)
        if (settings.agentTtsEngine == "sherpa") {
            if (sherpaTts == null) {
                sherpaTts = com.example.llama.agent.voice.sherpa.SherpaTtsEngine(context.applicationContext, settings)
                isTtsReady.set(sherpaTts!!.initialize())
            }
            return
        }

        if (tts == null) {
            tts = TextToSpeech(context.applicationContext, this)
        }
    }

    // --- TTS Implementation ---

    override fun speak(text: String) {
        ensureTtsInitialized()

        if (!isTtsReady.get()) {
            Log.w("VoiceMgr", "TTS not ready, speak request ignored or queued.")
            _state.value = VoiceState.Error("TTS not initialized, cannot speak", false)
            return
        }

        val params = Bundle().apply {
             putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "MessageId_${System.currentTimeMillis()}")
        }

        // Delegate Hardware Focus requests to the Orchestrator
        scope.launch {
            val granted = try {
                AgentAudioOrchestrator.requestAgentSpeakingFocus()
            } catch (e: Exception) {
                Log.w("VoiceMgr", "⚠️ Orchestrátor nedostupný, fallback na přímý TTS", e)
                true // Pass-through: TTS funguje i bez focus managementu (Android 12+ ducking)
            }
            if (granted) {
                if (sherpaTts != null) {
                    sherpaTts!!.speak(
                        text = text,
                        interrupt = true,
                        onStart = {
                            _state.value = VoiceState.Speaking(text)
                        },
                        onDone = {
                            _state.value = VoiceState.Idle
                            abandonAudioFocus()
                        }
                    )
                    return@launch
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
                _state.value = VoiceState.Speaking(text)
            } else {
                Log.w("VoiceMgr", "Audio Focus rejected by Orchestrator. Cannot speak.")
                _state.value = VoiceState.Idle
            }
        }
    }

    override fun stopSpeaking() {
        sherpaTts?.stop()
        tts?.stop()
        abandonAudioFocus()
        if (_state.value !is VoiceState.Idle) {
             _state.value = VoiceState.Idle
        }
    }

    // --- Lifecycle & Cleanup (Memory Leak Prevention) ---

    override fun cleanup() {
        Log.d("VoiceMgr", "Cleanup initiated.")
        stopSpeaking()

        sherpaTts?.shutdown()
        sherpaTts = null
        tts?.shutdown()
        tts = null
        isTtsReady.set(false)
        _state.value = VoiceState.Idle
    }

    override fun onDestroy(owner: LifecycleOwner) {
        cleanup()
        super.onDestroy(owner)
    }

    // --- Callbacks (Inner logic) ---

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.getDefault())
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                _state.value = VoiceState.Error("Language not supported for TTS", false)
                isTtsReady.set(false)
            } else {
                isTtsReady.set(true)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        scope.launch { _state.emit(VoiceState.Idle) }
                        abandonAudioFocus()
                    }
                    override fun onError(utteranceId: String?) {
                        Log.e("VoiceMgr", "TTS Utterance Error for ID: $utteranceId")
                        scope.launch { _state.emit(VoiceState.Error("TTS failed to speak", false)) }
                        abandonAudioFocus()
                    }
                })
            }
        } else {
            _state.value = VoiceState.Error("TTS Init Failed", true)
            isTtsReady.set(false)
        }
    }

    // --- Audio Focus Helpers via Orchestrator ---

    private fun abandonAudioFocus() {
        scope.launch {
            try {
                AgentAudioOrchestrator.releaseFocus()
            } catch (e: Exception) {
                Log.w("VoiceMgr", "⚠️ Orchestrátor release failed (non-fatal)", e)
            }
        }
    }
}
