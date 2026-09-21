package com.example.llama.agent.core.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import android.media.AudioFocusRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Centrální Audio Router aplikující striktní Zero-Implicit přístup.
 * Řeší "Race Conditions" v přístupu ke zvukovému hardwaru (Mikrofon, TTS, MediaProjection).
 * Jediný Single-Source of Truth pro všechny audio Enginy.
 */
object AgentAudioOrchestrator {
    private const val TAG = "AgentAudioOrchestrator"

    // Striktní stavy celého audio subsystému
    sealed class AudioState {
        data object Idle : AudioState()
        data object AgentSpeaking : AudioState() // TTS mluví (ztlumuje STT)
        data object AgentListening : AudioState() // STT aktivní (Mikrofon)
        data object SystemTranscribing : AudioState() // MediaProjection (Přehrávání)
        data object WakeWordListening : AudioState() // Nízkopříkonový odposlech wake wordu
        data class Error(val message: String) : AudioState()
    }

    private val _state = MutableStateFlow<AudioState>(AudioState.Idle)
    val state: StateFlow<AudioState> = _state.asStateFlow()

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    // Transakční Mutex pro zamezení deadlocků mezi vlákny žádajícími o HW prostředky
    private val orchestratorMutex = Mutex()

    /**
     * Diagnostická property — vrací true pokud byl initialize() úspěšně volán.
     * Použití: logování, health-check, audit.
     */
    val isInitialized: Boolean
        get() = audioManager != null

    /**
     * Inicializace Single-Source of Truth.
     * Idempotentní — bezpečné volat opakovaně (z Activity i Service).
     */
    fun initialize(context: Context) {
        if (audioManager != null) return // Idempotentní inicializace
        
        Log.i(TAG, "🔧 Inicializace AgentAudioOrchestrator (Single-Source-of-Truth)")
        val systemService = context.applicationContext.getSystemService(Context.AUDIO_SERVICE)
        if (systemService is AudioManager) {
            audioManager = systemService
            Log.i(TAG, "✅ AudioManager získán úspěšně.")
        } else {
            Log.e(TAG, "❌ Fatální chyba: Nepodařilo se získat AudioManager službu ze systému.")
            _state.value = AudioState.Error("AudioManager service nedostupná")
        }
    }

    /**
     * Bezpečné vrácení aktuálního správce — GRACEFUL DEGRADACE.
     * Vrací null místo throw (dle Android 15+ best practices: pass-through pattern).
     * Pokud audioManager == null, volající metoda pokračuje bez focus managementu.
     * Android 12+ system-managed ducking zajistí základní audio routing automaticky.
     */
    private fun getValidAudioManager(): AudioManager? {
        val am = audioManager
        if (am == null) {
            Log.w(TAG, "⚠️ AudioManager není inicializován — pass-through mód (audio funguje bez focus managementu)")
        }
        return am
    }

    /**
     * [TTS] Agent začíná mluvit. Orcherstrátor bezpečně přebírá Focus.
     * Vrací true i při:
     * - Neinicializovaném AudioManageru (pass-through, TTS funguje)
     * - AUDIOFOCUS_REQUEST_FAILED (Android 15+ restrikce, Android 12+ ducking pokryje)
     */
    suspend fun requestAgentSpeakingFocus(): Boolean = orchestratorMutex.withLock {
        Log.d(TAG, "🎤 Požádavek na TTS Speaking Focus")
        
        try {
            val am = getValidAudioManager()
            if (am == null) {
                Log.w(TAG, "⚠️ Pass-through: TTS Speaking Focus — AudioManager nedostupný, pokračuji bez focusu")
                _state.value = AudioState.AgentSpeaking
                return true // Pass-through: TTS funguje i bez focus managementu
            }
            abandonCurrentFocus(am) // Uvolníme předchozí stav, abychom zamezili duchům
            
            // TTS mluví -> Chceme ducking ostatního audia (notifikací)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleFocusChangeForAgent(focusChange)
                }
                .build()

            val result = am.requestAudioFocus(request)
            
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                _state.value = AudioState.AgentSpeaking
                Log.i(TAG, "✅ TTS Speaking Focus udělen.")
                return true
            } else {
                // Android 15+ může zamítnout focus z pozadí — pass-through,
                // Android 12+ system-managed ducking zajistí audio routing automaticky
                Log.w(TAG, "⚠️ TTS Speaking Focus ZAMÍTNUT O.S. — pass-through (Android 12+ ducking aktivní)")
                _state.value = AudioState.AgentSpeaking
                return true // Pass-through místo blokování TTS
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba během žádosti o TTS Focus: ${e.message}", e)
            _state.value = AudioState.AgentSpeaking
            return true // Fault-tolerant: TTS nesmí být blokován výjimkou v orchestrátoru
        }
    }

    /**
     * [STT/WakeWord] Agent začíná asynchronně poslouchat přes nativní mikrofon.
     * Vrací true i při neinicializovaném AM nebo zamítnutém focusu (pass-through).
     */
    suspend fun requestAgentListeningFocus(): Boolean = orchestratorMutex.withLock {
        Log.d(TAG, "👂 Požádavek na STT Listening Focus")
        
        try {
            val am = getValidAudioManager()
            if (am == null) {
                Log.w(TAG, "⚠️ Pass-through: STT Listening Focus — AudioManager nedostupný, pokračuji bez focusu")
                _state.value = AudioState.AgentListening
                return true // Pass-through: STT funguje i bez focus managementu
            }
            abandonCurrentFocus(am)

            // Poslouchání vyžaduje exkluzivitu, aby ho nerušila hudba (na rozdíl od TTS)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleFocusChangeForAgent(focusChange)
                }
                .build()

            val result = am.requestAudioFocus(request)

            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                focusRequest = request
                _state.value = AudioState.AgentListening
                Log.i(TAG, "✅ STT Listening Focus udělen.")
                return true
            } else {
                // Android 15+ může zamítnout focus z pozadí — pass-through
                Log.w(TAG, "⚠️ STT Listening Focus ZAMÍTNUT O.S. — pass-through")
                _state.value = AudioState.AgentListening
                return true // Pass-through místo blokování STT
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba během žádosti o STT Focus: ${e.message}", e)
            _state.value = AudioState.AgentListening
            return true // Fault-tolerant: STT nesmí být blokován výjimkou v orchestrátoru
        }
    }

    /**
     * [Global Release] Systém potichu, uvolňuje veškerý držený HW.
     */
    suspend fun releaseFocus() = orchestratorMutex.withLock {
        Log.d(TAG, "🔇 Uvolňování jakéhokoliv drženého AudioFocusu")
        try {
            val am = audioManager // Není fatální chyba, pokud už neexistuje
            if (am != null) {
                abandonCurrentFocus(am)
            }
            _state.value = AudioState.Idle
            Log.i(TAG, "✅ Audio HW úspěšně navrácen systému.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při uvolňování Focusu: ${e.message}", e)
            _state.value = AudioState.Idle // Vždy se vrátíme do Idle
        }
    }

    /**
     * Atomické uhození focusu aplikované na reálnou službu AudioManager
     */
    private fun abandonCurrentFocus(am: AudioManager) {
        val currentFocus = focusRequest
        if (currentFocus != null) {
            am.abandonAudioFocusRequest(currentFocus)
            focusRequest = null
        }
    }

    /**
     * Interní zpracovač událostí od samotného Androidu (Např. uživatel přijal hovor)
     */
    private fun handleFocusChangeForAgent(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "⚠️ AudioFocus ztracen trvale (Např. spuštěn Spotify). Odevzdávám HW.")
                // Spustí asynchronní cleanup mimo vlákno listeneru z OS
                Thread {
                    kotlinx.coroutines.runBlocking {
                        releaseFocus()
                    }
                }.start()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.w(TAG, "⚠️ AudioFocus ukraden systémem DOČASNĚ (Notifikace/Hovor).")
                // Zde můžeme zapauzovat stream z budoucího PCM
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "✅ AudioFocus navrácen Agentovi.")
            }
        }
    }
}
