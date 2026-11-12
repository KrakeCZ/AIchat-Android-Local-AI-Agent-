package com.example.llama.agent.voice

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.core.audio.AgentAudioOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AgentVoiceSTT - Speech-to-Text engine pro AI Agent
 *
 * ODDĚLENO od chat STT - agent má vlastní STT instanci pro voice commands.
 *
 * Features:
 * - Nativní Android SpeechRecognizer API
 * - HW akcelerace (Samsung/Google Neural STT)
 * - Real-time recognition results
 * - Continuous listening mode (optional)
 *
 * Použití:
 * - Voice commands pro agenta ("Najdi video o kočkách")
 * - Wake word detection (optional future enhancement)
 *
 * IMPORTANT: Requires android.permission.RECORD_AUDIO
 */
class AgentVoiceSTT(private val context: Context) {

    companion object {
        private const val TAG = "AgentVoiceSTT"
    }

    // Internal access to settings for global disable
    private val settingsManager = SettingsManager(context)

    // STT engine instance
    private var speechRecognizer: SpeechRecognizer? = null
    private var sherpaStt: com.example.llama.agent.voice.sherpa.SherpaSttEngine? = null

    // State tracking
    sealed class STTState {
        data object Idle : STTState()
        data object Listening : STTState()
        data class Processing(val partialResult: String) : STTState()
        data class Result(
                val text: String,
                val confidence: Float,
                val isWakeWord: Boolean = false,
                val timestamp: Long =
                        System.currentTimeMillis() // [AUDIT FIX] Timestamp pro unikátnost eventu
        ) : STTState()
        data class Error(val message: String) : STTState()
    }

    private val _state = MutableStateFlow<STTState>(STTState.Idle)
    val state: StateFlow<STTState> = _state.asStateFlow()

    private var isInitialized = false
    private var isContinuousMode = false
    private var lastRestartTime = 0L
    private var forceOffline =
            false // FIX: Default to false to avoid Error 12 loop if offline pack missing
    private var currentLanguage = Locale.getDefault().toLanguageTag()

    @Volatile private var isInitializingInProgress = false

    // Zombie Killer State
    private var errorCount = 0
    private var lastErrorTime = 0L
    private var lastStartTime = 0L // [FIX] Track start time for race condition check

    fun getLastStartTime(): Long = lastStartTime

    /**
     * Hard Reset: Destroy and Recreate SpeechRecognizer Used when the engine gets into a zombie
     * state (Error 5/8 loop)
     */
    private fun hardResetStt() {
        val wasContinuous = isContinuousMode
        val lang = currentLanguage

        Log.w(TAG, "💀 Hard Resetting STT Engine...")
        shutdown()

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            initialize()
                            if (wasContinuous) {
                                Log.i(TAG, "💀 Resurrecting Continuous Mode after reset")
                                startContinuousListening(lang)
                            }
                        },
                        1000
                )
    }

    /** Initialize STT engine */
    fun initialize() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { initialize() }
            return
        }

        if (isInitializingInProgress) {
            Log.d(TAG, "Initialization already in progress, skipping redundant call")
            return
        }

        Log.d(TAG, "Initializing STT engine...")
        isInitializingInProgress = true

        val preferredEngine = settingsManager.agentSpeechEngine

        if (preferredEngine != "sherpa" && !SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "❌ Speech recognition not available on this device")
            _state.value = STTState.Error("STT není dostupné")
            isInitializingInProgress = false
            return
        }

        try {
            speechRecognizer?.let {
                Log.d(TAG, "Cleaning up existing SpeechRecognizer")
                it.cancel()
                it.destroy()
            }
            speechRecognizer = null
            if (preferredEngine == "sherpa") {
                Log.i(TAG, "Initializing Sherpa-ONNX STT")
                if (sherpaStt == null) {
                    sherpaStt = com.example.llama.agent.voice.sherpa.SherpaSttEngine(context, settingsManager)
                    sherpaStt?.onPartialResult = { text ->
                        _state.value = STTState.Processing(text)
                    }
                    sherpaStt?.onFinalResult = { text ->
                        _state.value = STTState.Result(text, 1.0f)
                    }
                    sherpaStt?.onError = { errorMsg ->
                        Log.e(TAG, "Sherpa STT Error: $errorMsg")
                        _state.value = STTState.Error(errorMsg)
                        releaseAudioManagerFocus()
                    }
                }
                
                if (sherpaStt!!.initialize()) {
                    isInitialized = true
                    isInitializingInProgress = false
                } else {
                    _state.value = STTState.Error("Sherpa STT se nezdařila inicializovat.")
                    isInitializingInProgress = false
                }
                return
            }

            if (preferredEngine.isNotEmpty()) {
                val componentName =
                        if (preferredEngine.contains("/")) {
                            val parts = preferredEngine.split("/")
                            ComponentName(parts[0], parts[1])
                        } else {
                            // [IMPROVED] Resolve engine if only package is provided
                            val pm = context.packageManager
                            val intent =
                                    Intent(android.speech.RecognitionService.SERVICE_INTERFACE)
                                            .setPackage(preferredEngine)
                            val services =
                                    pm.queryIntentServices(
                                            intent,
                                            android.content.pm.PackageManager.MATCH_ALL
                                    )
                            if (services.isNotEmpty()) {
                                ComponentName(
                                        services[0].serviceInfo.packageName,
                                        services[0].serviceInfo.name
                                )
                            } else {
                                null
                            }
                        }

                if (componentName != null) {
                    Log.i(TAG, "Creating SpeechRecognizer with component: $componentName")
                    speechRecognizer =
                            SpeechRecognizer.createSpeechRecognizer(context, componentName)
                } else {
                    Log.w(
                            TAG,
                            "Could not resolve component for $preferredEngine, falling back to default."
                    )
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }
            } else {
                Log.i(TAG, "Creating SpeechRecognizer with SYSTEM DEFAULT")
                // [FIX] Verify if default is usable
                if (SpeechRecognizer.isRecognitionAvailable(context)) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                } else {
                    Log.e(TAG, "❌ SpeechRecognizer.isRecognitionAvailable returned FALSE!")
                    _state.value = STTState.Error("Rozpoznávání řeči není v systému dostupné!")
                    return
                }
            }

            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            isInitialized = true

            // Sync with manual user settings
            forceOffline = settingsManager.isSttForceOffline

            // Validate: If ForceOffline=True AND OnlineEnabled=False -> STRICT OFFLINE
            val isOnlineAllowed = settingsManager.isAgentOnlineEnabled
            if (forceOffline && !isOnlineAllowed) {
                Log.i(TAG, "🔒 STT Mode: STRICT OFFLINE")
            } else if (forceOffline) {
                Log.i(TAG, "🔄 STT Mode: HYBRID (Prefer Offline -> Fallback Online)")
            } else {
                Log.i(TAG, "☁️ STT Mode: ONLINE PREFERRED")
            }

            // Check available languages for debugging
            checkAvailableLanguages()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize SpeechRecognizer", e)
            isInitialized = false
            _state.value = STTState.Error("Chyba inicializace STT: ${e.message}")
        } finally {
            isInitializingInProgress = false
        }
    }

    private fun checkAvailableLanguages() {
        try {
            val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
            detailsIntent.setPackage(
                    "com.google.android.googlequicksearchbox"
            ) // Target Google App specifically
            context.sendOrderedBroadcast(
                    detailsIntent,
                    null,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val results = getResultExtras(true)
                            val supportedLanguages =
                                    results.getStringArrayList(
                                            RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES
                                    )
                            val languagePreference =
                                    results.getString(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)

                            Log.i(TAG, "🌍 STT Language Check:")
                            Log.i(TAG, "   Preferred: $languagePreference")
                            Log.i(
                                    TAG,
                                    "   Supported: ${supportedLanguages?.take(10)}..."
                            ) // Log first 10

                            if (supportedLanguages?.contains(Locale.getDefault().toLanguageTag()) ==
                                            true
                            ) {
                                Log.i(
                                        TAG,
                                        "   ✅ Current locale (${Locale.getDefault().toLanguageTag()}) IS supported."
                                )
                            } else {
                                Log.w(
                                        TAG,
                                        "   ⚠️ Current locale (${Locale.getDefault().toLanguageTag()}) NOT found in supported list!"
                                )
                            }
                        }
                    },
                    null,
                    Activity.RESULT_OK,
                    null,
                    null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check languages", e)
        }
    }

    // AUDIO FOCUS MANAGEMENT (Enterprise Grade via Orchestrator)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Request Exclusive Audio Focus via AgentAudioOrchestrator
     */
    private fun gainAudioManagerFocus() {
        Log.d(TAG, "🔊 Žádám o Audio Focus přes Orchestrátor")
        scope.launch {
            val granted = try {
                AgentAudioOrchestrator.requestAgentListeningFocus()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Orchestrátor nedostupný, fallback bez focus managementu", e)
                true // Pass-through: STT funguje i bez focus managementu (Android 12+ ducking)
            }
            if (!granted) {
                Log.w(TAG, "⚠️ Audio Focus REJECTED by Orchestrator")
            }
        }
    }

    /** Release Audio Focus (Resume Music) via Orchestrator */
    private fun releaseAudioManagerFocus() {
        Log.d(TAG, "🔊 Uvolňuji Audio Focus Request přes Orchestrátor")
        scope.launch {
            try {
                AgentAudioOrchestrator.releaseFocus()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Orchestrátor release failed (non-fatal)", e)
            }
        }
    }

    /** Create RecognitionListener s callbacks */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle) {
                Log.d(TAG, "Ready for speech...")
                _state.value = STTState.Listening
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech beginning...")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // [DEBUG] Always log RMS to verify Mic HW is working
                Log.v(TAG, "🔊 Mic Signal: $rmsdB dB")
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech end...")
            }

            override fun onError(error: Int) {
                val message = getErrorMessage(error)
                Log.e(TAG, "❌ STT Error: $message ($error)")

                // FALLBACK LOGIC: If offline fails (Error 12/Server/Client/Network), retry online
                // FALLBACK LOGIC: If offline fails (Error 12/Server/Client/Network), retry online
                // FIX: Only switch to online/hybrid if allowed by user
                val isOnlineAllowed = settingsManager.isAgentOnlineEnabled

                if (forceOffline &&
                                isOnlineAllowed &&
                                (error == 12 ||
                                        error == SpeechRecognizer.ERROR_SERVER ||
                                        error == SpeechRecognizer.ERROR_CLIENT ||
                                        error == SpeechRecognizer.ERROR_NETWORK)
                ) {

                    Log.w(
                            TAG,
                            "⚠️ Offline suppression failed (Error $error). Retrying in ONLINE mode..."
                    )
                    forceOffline = false // Temporarily disable offline preference for this retry
                    // Circuit Breaker: Wait 500ms before retry to let system settle
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        if (isContinuousMode)
                                                startListeningInternal(currentLanguage)
                                        else startListening(currentLanguage)
                                    },
                                    500
                            )
                    return
                } else if (settingsManager.isSttForceOffline) {
                    // [STRICT] Stop immediately if forced offline
                    Log.e(TAG, "❌ Strict Offline STT failed (Error $error). Stopping.")
                    _state.value = STTState.Error("Offline režim selhal: $message")
                    return
                }

                // ZOMBIE KILLER LOGIC: Detect persistent Client/Busy errors
                if (error == SpeechRecognizer.ERROR_CLIENT ||
                                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
                ) {
                    val now = System.currentTimeMillis()
                    if (now - lastErrorTime < 5000) {
                        errorCount++
                    } else {
                        errorCount = 1
                    }
                    lastErrorTime = now

                    if (errorCount >= 2) {
                        Log.e(TAG, "🧟 ZOMBIE RECOGNIZER DETECTED! Performing hard reset.")
                        hardResetStt()
                        errorCount = 0
                        return
                    }
                } else {
                    // Reset count on other errors or success (in onResults)
                    errorCount = 0
                }

                // Auto-restart on non-fatal errors during wake word mode
                if (isContinuousMode) {
                    if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        // [FIX] Suppress Error state for transient errors — silent restart
                        Log.v(TAG, "🔄 Continuous mode: silent restart (error $error)")
                        restartContinuousListening()
                    } else if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    error == SpeechRecognizer.ERROR_CLIENT
                    ) {
                        // [FIX] Suppress Error state — user doesn't need to hear "Google App
                        // permission"
                        Log.w(
                                TAG,
                                "⚠️ Continuous mode: Busy/Client error ($error). Silent restart with backoff..."
                        )
                        Handler(Looper.getMainLooper())
                                .postDelayed(
                                        { restartContinuousListening() },
                                        2000
                                ) // Longer delay for busy state
                    } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Log.e(TAG, "🛑 Stopping continuous listening: Permission denied")
                        _state.value = STTState.Error(message) // Only emit for fatal errors
                        isContinuousMode = false
                    } else {
                        // For other errors, try once more then stop
                        Log.w(
                                TAG,
                                "🛑 Continuous listening stopped due to fatal/persistent error ($error)"
                        )
                        _state.value = STTState.Error(message) // Only emit for fatal errors
                        isContinuousMode = false
                    }
                } else {
                    // Non-continuous mode: always emit error
                    _state.value = STTState.Error(message)
                }
            }

            override fun onResults(results: Bundle) {
                errorCount = 0 // Reset zombie counter on success
                val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

                if (matches.isNullOrEmpty()) return

                val bestMatch = matches[0]
                val bestConfidence = confidences?.getOrNull(0) ?: 0.5f

                if (isContinuousMode) {
                    // Check for wake word in final result
                    if (checkForWakeWord(bestMatch)) {
                        Log.i(TAG, "🔔 WAKE WORD DETECTED (Result): $bestMatch")
                        triggerWakeWordAction(bestMatch)
                    } else {
                        restartContinuousListening()
                    }
                } else {
                    _state.value = STTState.Result(bestMatch, bestConfidence)
                }
            }

            override fun onPartialResults(partialResults: Bundle) {
                val matches =
                        partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val partial: String = matches[0]
                    Log.d(TAG, "📝 Partial: \"$partial\"")
                    _state.value = STTState.Processing(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle) {}
        }
    }

    /**
     * Start listening pro voice input
     *
     * @param language Jazyk (default: System Default)
     */
    fun startListening(language: String = "") {
        // Enforce global setting
        if (!settingsManager.isAgentSttEnabled) {
            Log.d(TAG, "🎤 STT disabled via settings. Skipping listen.")
            return
        }

        if (!isInitialized) {
            Log.w(TAG, "⚠️ STT not initialized")
            initialize() // [FIX] Auto-initialize if missing
        }

        // ENTERPRISE: Gain Exclusive Audio Focus (Mute Music)
        gainAudioManagerFocus()

        // RESOLVE LANGUAGE & MODE: Strict check
        // RESOLVE LANGUAGE & MODE: Strict check
        val isForcedOffline = settingsManager.isSttForceOffline
        val isOnlineAllowed = settingsManager.isAgentOnlineEnabled

        // Mode logic:
        // 1. Offline Only: ForceOffline=True, OnlineAllowed=False
        // 2. Hybrid: ForceOffline=True, OnlineAllowed=True
        // 3. Online/Standard: ForceOffline=False (OnlineAllowed ignored/assumed true)

        forceOffline = isForcedOffline // Default start state

        val targetLanguage =
                if (language.isNotEmpty()) language
                else if (forceOffline)
                        settingsManager.agentLanguageOffline.ifEmpty {
                            Locale.getDefault().toLanguageTag()
                        }
                else
                        settingsManager.agentLanguageOnline.ifEmpty {
                            Locale.getDefault().toLanguageTag()
                        }

        Log.i(
                TAG,
                "🎤 Starting voice recognition (language: $targetLanguage, forcedOffline: $forceOffline, onlineAllowed: $isOnlineAllowed)"
        )
        currentLanguage = targetLanguage
        lastStartTime = System.currentTimeMillis() // [FIX] Update start time

        // Just in case check again
        if (!isInitialized) initialize()

        val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    // [CHANGE] Use FREE_FORM for better general command recognition (less strict
                    // than WEB_SEARCH)
                    putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

                    // S24 SPECIFIC: Samsung Speech Recognizer might need this to behave
                    putExtra("android.speech.extra.DICTATION_MODE", true)

                    // TIMEOUTS - Relaxed for testing
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                    putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            10000L
                    )
                    putExtra(
                            RecognizerIntent
                                    .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                            10000L
                    )

                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, forceOffline)

                    // STRICT MODE: If Offline Only (no online allowed), strictly enforce local
                    // result
                    if (forceOffline && !isOnlineAllowed && Build.VERSION.SDK_INT >= 33) {
                        // [STRICT] API 33+ flag to prevent ANY online traffic
                        putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE_LOCAL", true)
                    }

                    // [FIX] Add DETAILED logging for debugging "silent failure"
                    Log.d(
                            TAG,
                            "🚀 Starting STT Intent: Lang=$targetLanguage, Offline=$forceOffline, Package=${context.packageName}"
                    )
                }

        isContinuousMode = false // Standard one-shot mode

        if (sherpaStt != null) {
            _state.value = STTState.Listening
            sherpaStt?.startListening()
            return
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start listening", e)
            _state.value = STTState.Error("Nelze spustit rozpoznávání: ${e.message}")
        }
    }

    /** Start continuous listening (Wake Word mode) */
    fun startContinuousListening(language: String = "") {
        if (!settingsManager.isWakeWordEnabled) {
            Log.d(TAG, "Skip continuous listening - disabled in settings")
            return
        }

        if (!isInitialized) initialize()

        // RESOLVE LANGUAGE: Strict check for continuous mode
        val isForcedOffline = settingsManager.isSttForceOffline
        // val isOnlineAllowed = settingsManager.isAgentOnlineEnabled // Unused here, logic moved to
        // startListeningInternal

        forceOffline = isForcedOffline // Enforce state

        val targetLanguage =
                if (language.isNotEmpty()) language
                else if (forceOffline)
                        settingsManager.agentLanguageOffline.ifEmpty {
                            Locale.getDefault().toLanguageTag()
                        }
                else
                        settingsManager.agentLanguageOnline.ifEmpty {
                            Locale.getDefault().toLanguageTag()
                        }

        Log.i(
                TAG,
                "👂 Starting CONTINUOUS listening (ForcedOffline: $isForcedOffline, Language: $targetLanguage)"
        )
        isContinuousMode = true
        startListeningInternal(targetLanguage)
    }

    private fun startListeningInternal(language: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { startListeningInternal(language) }
            return
        }

        if (speechRecognizer == null) return

        val intent =
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)

                    // Continuous mode timeouts
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000L)
                    putExtra(
                            RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                            3000L
                    )

                    putExtra("android.speech.extra.DICTATION_MODE", true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, forceOffline)

                    // STRICT MODE: If Offline Only (no online allowed), strictly enforce local
                    // result
                    val isOnlineAllowed = settingsManager.isAgentOnlineEnabled
                    if (forceOffline && !isOnlineAllowed && Build.VERSION.SDK_INT >= 33) {
                        putExtra("android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE_LOCAL", true)
                    }
                }

        currentLanguage = language // Sync for restart logic

        if (sherpaStt != null) {
            _state.value = STTState.Listening
            sherpaStt?.startListening()
            return
        }

        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start Recognition", e)
            // Retry after delay
            _state.value = STTState.Error("Restarting listener...")
        }
    }
    private fun restartContinuousListening() {
        if (!isContinuousMode || speechRecognizer == null) return

        // Prevent tight loop restart (max 1 restart per 500ms)
        val now = System.currentTimeMillis()
        val delayTime = if (now - lastRestartTime < 500) 500L else 50L
        lastRestartTime = now

        // Use Handler to restart on main thread with non-blocking delay
        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            if (isContinuousMode && speechRecognizer != null) {
                                Log.v(
                                        TAG,
                                        "🔄 Auto-restarting continuous loop (language: $currentLanguage)"
                                )
                                startListeningInternal(currentLanguage)
                            }
                        },
                        delayTime
                )
    }

    private fun checkForWakeWord(text: String): Boolean {
        val wakeWord = settingsManager.wakeWord.lowercase()
        val input = text.lowercase()
        // Simple fuzzy match
        return input.contains(wakeWord)
    }

    private fun triggerWakeWordAction(text: String) {
        isContinuousMode = false // Stop continuous loop to process command
        
        // Strip the wake word to get the actual command
        val wakeWord = settingsManager.wakeWord.lowercase()
        var commandText = text
        val lowerText = text.lowercase()
        val idx = lowerText.indexOf(wakeWord)
        
        if (idx != -1) {
            // Remove the wake word and trim extra spaces
            commandText = text.substring(0, idx) + text.substring(idx + wakeWord.length)
            commandText = commandText.trim()
        }
        
        // If the user ONLY said the wake word, we still send it as a trigger, 
        // but if they said a command, we send just the command.
        val finalCommand = if (commandText.isEmpty()) text else commandText

        // Emit result to trigger AiAgentAccessibilityService
        // FIX: Set isWakeWord = true to distinguish from manual commands
        _state.value = STTState.Result(finalCommand, 1.0f, isWakeWord = true)
    }

    /** Stop listening okamžitě */
    fun stopListening() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { stopListening() }
            return
        }

        if (isInitialized) {
            isContinuousMode = false
            sherpaStt?.stopListening()
            speechRecognizer?.stopListening()
            releaseAudioManagerFocus() // ABANDON FOCUS
            Log.d(TAG, "🛑 Stopped listening")
        }
    }

    /** Cancel recognition (rušit celou session) */
    fun cancel() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { cancel() }
            return
        }

        if (isInitialized) {
            isContinuousMode = false
            sherpaStt?.stopListening()
            speechRecognizer?.cancel()
            _state.value = STTState.Idle
            releaseAudioManagerFocus() // ABANDON FOCUS
            Log.d(TAG, "❌ Recognition cancelled")
        }
    }

    /** Check if currently listening for voice input */
    fun isListening(): Boolean {
        return _state.value is STTState.Listening || _state.value is STTState.Processing
    }

    /** Get human-readable error message */
    private fun getErrorMessage(errorCode: Int): String {
        return when (errorCode) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio chyba (Mikrofon?)"
            SpeechRecognizer.ERROR_CLIENT ->
                    "STT Client error (race condition při inicializaci, auto-restart...)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chybí oprávnění RECORD_AUDIO"
            SpeechRecognizer.ERROR_NETWORK -> "Síťová chyba (Offline mode?)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout sítě"
            SpeechRecognizer.ERROR_NO_MATCH -> "Nebylo rozpoznáno žádné slovo (Ticho?)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Rozpoznávač je zaneprázdněn"
            SpeechRecognizer.ERROR_SERVER -> "Server chyba (Google/Samsung backend)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nebyla detekována žádná řeč (Timeout)"
            12 -> "Jazyk není podporován offline (Stáhněte jazykový balíček v nastavení telefonu)"
            else -> "Neznámá chyba ($errorCode)"
        }
    }

    /** Cleanup - MUSÍ být voláno v onDestroy */
    fun shutdown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { shutdown() }
            return
        }

        Log.d(TAG, "Shutting down STT engine")
        isContinuousMode = false
        releaseAudioManagerFocus() // SAFE CLEANUP
        try {
            sherpaStt?.shutdown()
            sherpaStt = null
            speechRecognizer?.setRecognitionListener(null) // Detach listener
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying speech recognizer", e)
        }
        speechRecognizer = null
        isInitialized = false
        _state.value = STTState.Idle
    }
}
