package com.example.llama.agent.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.core.audio.AgentAudioOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.Locale

/**
 * AgentVoiceTTS - Text-to-Speech engine pro AI Agent
 *
 * ODDĚLENO od chat TTS (AndroidVoiceManager) - agent má vlastní TTS instanci.
 * REFACTOR: Přesměrováno do Centrálního Audio Orchestrátoru pro bezpečný HW Focus.
 */
class AgentVoiceTTS(private val context: Context) {

    companion object {
        private const val TAG = "AgentVoiceTTS"
    }

    // Internal access to settings for global disable
    private val settingsManager = SettingsManager(context)

    // Scope pro asynchronní integraci s Orchestrátorem
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // TTS engine instances
    private var tts: TextToSpeech? = null
    private var sherpaTts: com.example.llama.agent.voice.sherpa.SherpaTtsEngine? = null

    // State tracking
    sealed class TTSState {
        data object Idle : TTSState()
        data object Initializing : TTSState()
        data class Speaking(val text: String) : TTSState()
        data class Error(val message: String) : TTSState()
    }

    private val _state = MutableStateFlow<TTSState>(TTSState.Idle)
    val state: StateFlow<TTSState> = _state.asStateFlow()

    private var isInitialized = false
    private val pendingMessages = LinkedList<Pair<String, Boolean>>() // Queue for messages before init

    /**
     * Initialize TTS engine
     * MUSÍ být voláno před prvním použitím
     */
    fun initialize() {
        if (isInitialized || _state.value is TTSState.Initializing) return

        Log.d(TAG, "Initializing TTS engine...")
        _state.value = TTSState.Initializing

        val preferredEngine = settingsManager.agentTtsEngine
        if (preferredEngine == "sherpa") {
            Log.i(TAG, "Initializing Sherpa-ONNX TTS")
            sherpaTts = com.example.llama.agent.voice.sherpa.SherpaTtsEngine(context, settingsManager)
            if (sherpaTts!!.initialize()) {
                isInitialized = true
                _state.value = TTSState.Idle
                while (pendingMessages.isNotEmpty()) {
                    val (text, interrupt) = pendingMessages.poll() ?: break
                    speak(text, interrupt)
                }
            } else {
                _state.value = TTSState.Error("Sherpa TTS se nezdařila.")
            }
            return
        }

        val initListener = TextToSpeech.OnInitListener { status ->
            when (status) {
                TextToSpeech.SUCCESS -> {
                    Log.i(TAG, "✓ TTS engine initialized successfully")
                    configureTTS()
                    isInitialized = true
                    _state.value = TTSState.Idle

                    // Process pending messages
                    while (pendingMessages.isNotEmpty()) {
                        val (text, interrupt) = pendingMessages.poll() ?: break
                        speak(text, interrupt)
                    }
                }
                else -> {
                    Log.e(TAG, "❌ TTS initialization failed with status: $status")
                    _state.value = TTSState.Error("TTS engine nedostupný")
                    isInitialized = false
                }
            }
        }

        tts = if (preferredEngine.isNotEmpty()) {
            Log.i(TAG, "Initializing TTS with engine: $preferredEngine")
            TextToSpeech(context, initListener, preferredEngine)
        } else {
            Log.i(TAG, "Initializing TTS with system default engine")
            TextToSpeech(context, initListener)
        }
    }

    /**
     * Configure TTS parameters
     */
    private fun configureTTS() {
        tts?.apply {
            val isForcedOffline = settingsManager.isSttForceOffline
            val isOnlineAllowed = settingsManager.isAgentOnlineEnabled
            
            val targetLanguageCode = if (isForcedOffline) settingsManager.agentLanguageOffline else settingsManager.agentLanguageOnline
            val targetLocale = if (targetLanguageCode.isNotEmpty()) Locale.forLanguageTag(targetLanguageCode) else Locale.getDefault()
            
            Log.i(TAG, "🗣️ Configuring TTS for locale: $targetLocale (Offline: $isForcedOffline, OnlineAllowed: $isOnlineAllowed)")

            val result = setLanguage(targetLocale)

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "❌ Language ($targetLocale) missing offline data or not supported.")
                
                if (isForcedOffline && !isOnlineAllowed) {
                    _state.value = TTSState.Error("Offline hlas pro $targetLocale není dostupný.")
                    return@apply
                } else {
                    val fallbackResult = setLanguage(Locale.US)
                    if (fallbackResult != TextToSpeech.LANG_MISSING_DATA && fallbackResult != TextToSpeech.LANG_NOT_SUPPORTED) {
                         Log.w(TAG, "⚠️ Falling back to US English due to missing data for $targetLocale")
                    } else {
                         Log.e(TAG, "❌ US English also missing!")
                         _state.value = TTSState.Error("Chybí hlasová data.")
                    }
                }
            } else {
                if (isForcedOffline && !isOnlineAllowed) {
                    val currentVoice = voice
                    if (currentVoice != null && currentVoice.isNetworkConnectionRequired) {
                        Log.w(TAG, "⚠️ Current voice '${currentVoice.name}' requires network! Searching for local alternative...")
                        
                        val localVoice = voices.find { v -> 
                            !v.isNetworkConnectionRequired && v.locale.language == targetLocale.language 
                        }
                        
                        if (localVoice != null) {
                            Log.i(TAG, "✓ Found local voice: ${localVoice.name}")
                            voice = localVoice
                        } else {
                            Log.e(TAG, "❌ No local voice found for $targetLocale in Strict Offline mode!")
                            _state.value = TTSState.Error("Chybí offline hlas pro $targetLocale")
                            return@apply
                        }
                    } else if (currentVoice == null) {
                         Log.w(TAG, "❓ Voice is null after setLanguage, assuming default.")
                    }
                }
            }

            setSpeechRate(1.1f)
            setPitch(1.0f)

            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "🗣️ TTS started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "✓ TTS completed: $utteranceId")
                    _state.value = TTSState.Idle
                    // Bezpečně uvolníme Audio Focus v rámci Orchestrátoru
                    scope.launch {
                        AgentAudioOrchestrator.releaseFocus()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "❌ TTS error: $utteranceId")
                    _state.value = TTSState.Error("TTS playback selhalo")
                    scope.launch {
                        AgentAudioOrchestrator.releaseFocus()
                    }
                }
            })

            @Suppress("DEPRECATION")
            Log.d(TAG, "TTS configured: Language=${language}, Rate=1.1, Pitch=1.0")
        }
    }

    /**
     * Speak text (asynchronní) s garancí HW přidělení pomocí Orchestrátoru
     */
    fun speak(text: String, interrupt: Boolean = true) {
        if (!settingsManager.isAgentTtsEnabled) {
            Log.d(TAG, "🔇 TTS disabled via settings. Skipping.")
            return
        }

        if (!isInitialized) {
            Log.i(TAG, "⏳ TTS not ready yet, queuing message: \"${text.take(20)}...\"")
            pendingMessages.add(text to interrupt)
            if (_state.value !is TTSState.Initializing) {
                initialize()
            }
            return
        }

        if (text.isBlank()) return

        Log.i(TAG, "🗣️ Speaking: \"${text.take(50)}${if (text.length > 50) "..." else ""}\"")

        _state.value = TTSState.Speaking(text)

        val queueMode = if (interrupt) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        // ENTRY: Transakční zisk HW Focusu přes Single-Source of Truth
        scope.launch {
            val isFocusGranted = try {
                AgentAudioOrchestrator.requestAgentSpeakingFocus()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Orchestrátor nedostupný, fallback na přímý TTS", e)
                true // Pass-through: TTS funguje i bez focus managementu (Android 12+ ducking)
            }
            if (isFocusGranted) {
                if (sherpaTts != null) {
                    sherpaTts!!.speak(
                        text,
                        interrupt,
                        onStart = {
                            _state.value = TTSState.Speaking(text)
                        },
                        onDone = {
                            _state.value = TTSState.Idle
                            scope.launch {
                                try {
                                    AgentAudioOrchestrator.releaseFocus()
                                } catch (e: Exception) {
                                    Log.w(TAG, "⚠️ Orchestrátor release failed (non-fatal)", e)
                                }
                            }
                        }
                    )
                    return@launch
                }

                tts?.speak(
                    text,
                    queueMode,
                    null,
                    "agent_utterance_${System.currentTimeMillis()}"
                )
            } else {
                Log.w(TAG, "⚠️ TTS odepřeno spuštění. Orchestrátor odmítl HW Focus.")
                _state.value = TTSState.Idle
            }
        }
    }

    /**
     * Stop aktuální řeč okamžitě
     */
    fun stop() {
        if (isInitialized) {
            sherpaTts?.stop()
            tts?.stop()
            _state.value = TTSState.Idle
            scope.launch {
                try {
                    AgentAudioOrchestrator.releaseFocus()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Orchestrátor release failed (non-fatal)", e)
                }
            }
            Log.d(TAG, "🛑 TTS stopped")
        }
    }

    fun isSpeaking(): Boolean {
        // Můžeme sledovat stav isPlaying v atomic boolean uvnitř Sherpy
        val isSherpaSpeaking = _state.value is TTSState.Speaking
        return isSherpaSpeaking || tts?.isSpeaking == true
    }

    /**
     * Cleanup - MUSÍ být voláno v onDestroy
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down TTS engine")
        // KRITICKÉ: NEVOLAT sherpaTts?.stop() — stop() dělá speakJob?.cancel(),
        // což označí coroutine jako "completed" a shutdown().join() se vrátí okamžitě,
        // i když C++ JNI inference stále běží → SIGSEGV.
        // sherpaTts?.shutdown() interně nastaví isPlaying=false a POČKÁ na dokončení C++.
        tts?.stop()
        scope.launch {
            AgentAudioOrchestrator.releaseFocus()
        }
        sherpaTts?.shutdown()
        sherpaTts = null
        tts?.shutdown()
        tts = null
        isInitialized = false
        _state.value = TTSState.Idle
    }

    fun speakActionFeedback(actionType: String) {
        val feedback = when (actionType) {
            "click" -> "Klikám"
            "scroll_down" -> "Scrolluji dolů"
            "scroll_up" -> "Scrolluji nahoru"
            "input_text" -> "Zadávám text"
            "back" -> "Zpět"
            "home" -> "Domů"
            else -> "Vykonávám akci"
        }
        speak(feedback, interrupt = false)
    }
}
