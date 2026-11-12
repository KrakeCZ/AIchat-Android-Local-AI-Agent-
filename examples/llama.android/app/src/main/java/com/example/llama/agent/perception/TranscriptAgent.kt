package com.example.llama.agent.perception

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import com.example.llama.SettingsManager
import com.example.llama.agent.core.audio.AgentAudioOrchestrator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ======================================================================== TRANSCRIPT AGENT —
 * Dual-Engine ASR (Tier 2.5)
 * ========================================================================
 *
 * Offline Speech-to-Text engine pro Live Captioning a transkripci videa.
 *
 * DUAL-ENGINE ARCHITEKTURA (2026 Enterprise):
 *
 * ENGINE A: Android SpeechRecognizer (DEFAULT — "android")
 * - Používá SpeechRecognizer.createOnDeviceSpeechRecognizer()
 * - Partial results s latencí ~200ms (slovo-po-slově)
 * - Automatický continuous listening (restart po onEndOfSpeech)
 * - Emituje TranscriptEvent(isFinal=false) pro partial, (isFinal=true) pro finální
 * - Totožný přístup jako Google Live Transcribe
 *
 * ENGINE B: whisper.cpp JNI (PŘESNÝ OFFLINE — "whisper")
 * - Batch inference přes JNI (3-10s chunky)
 * - Vyšší přesnost, ale vyšší latence (3-15s)
 * - Vhodné pro offline transkripci a přesný záznam
 *
 * Přepínání: SettingsManager.transcriptEngine ("android" | "whisper")
 *
 * Lifecycle:
 * - On-Demand: start() se volá POUZE přes Omni-Control (isTranscriptEnabled)
 * - stop() uvolní mikrofon / SpeechRecognizer / AudioRecord buffer
 *
 * Battery Optimization:
 * - Engine A: Systémový ASR — nízká spotřeba, HW akcelerace
 * - Engine B: Chunking s VAD gate — přepis jen při řeči
 *
 * Thread Safety:
 * - AtomicBoolean isRecording — backpressure gate
 * - Coroutine scope s SupervisorJob — izolace selhání
 * - SpeechRecognizer MUSÍ běžet na Main thread (Android requirement)
 */
class TranscriptAgent(private val context: Context, private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "TranscriptAgent"
        
        /** Globální callback pro Audio Studio (Brána) k příjmu čistých raw audio dat (pro VU Metr a WAV Muxer) */
        var audioDataCallback: ((ShortArray) -> Unit)? = null

        /** DSP konfigurace — nastavuje AudioStudioActivity z UI přepínačů */
        @Volatile
        var dspConfig: DspFilterChain.DspConfig = DspFilterChain.DspConfig()
        
        @Volatile
        var eqBandLevels: ShortArray = ShortArray(5) { 0 }

        @Volatile
        var microphoneGain: Float = 1.0f

        @Volatile
        var systemAudioGain: Float = 1.0f

        @SuppressLint("StaticFieldLeak")
        var instance: TranscriptAgent? = null

        /** Whisper vyžaduje 16kHz, 16-bit Mono PCM */
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Velikost chunků v sekundách pro Whisper engine */
        private const val CHUNK_DURATION_SEC = 3

        /** Velikost kruhového bufferu v samples */
        private const val BUFFER_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_SEC

        /**
         * Maximální počet po sobě jdoucích restartů SpeechRecognizer (ochrana před nekonečnou
         * smyčkou při chybě)
         */
        private const val MAX_CONSECUTIVE_ERRORS = 5

        /** Delay před restartem SpeechRecognizer po onEndOfSpeech (ms) */
        private const val RESTART_DELAY_MS = 150L
    }

    private var engineScope: CoroutineScope? = null
    private val inferenceMutex = Mutex()
    private var audioRecord: AudioRecord? = null
    /**
     * Referencí identita custom AudioRecordu předaného do start().
     * NON-null = vlastní FG Service (MediaProjection) — TranscriptAgent nesmí volat release().
     * Null     = vlastní mic, který jsme vytvořili sami.
     * KRITICKÉ: Toto pole NEmaže stop() — maže ho pouze releaseAudioRecord().
     * Důvod: stop() běží z Main/IO, ale releaseAudioRecord() běží ve finally bloku
     * coroutiny (Default dispatcher). Pokud by stop() pole vynuloval,
     * releaseAudioRecord() by dostalo false a zavolalo by release() na cizí objekt.
     */
    @Volatile private var customAudioRecordRef: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    val dspChain = DspFilterChain()
    
    /** Aktivní profil přepisu (nastaven přes start()) */
    private var activeProfile: com.example.llama.srt.TranscriptProfile? = null

    // ==================== ENGINE A: Android SpeechRecognizer ====================
    private var speechRecognizer: SpeechRecognizer? = null
    private var consecutiveErrors = 0
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    init {
        instance = this
    }

    fun updateRuntimeSettings(config: DspFilterChain.DspConfig) {
        dspConfig = config
        // Update any running DSP chains if necessary
    }

    // ==================== ENGINE B: whisper.cpp (Sliding Window) ====================
    // Dynamické Klouzavé Okno — velikost dle SettingsManager.whisperHistorySec.
    // Realokace při každém start(), uvolnění při stop().
    private var historySec: Int = SettingsManager.DEFAULT_WHISPER_HISTORY_SEC.toInt()
    private var processingIntervalMs: Long = SettingsManager.DEFAULT_WHISPER_PROCESSING_INTERVAL_MS
    private var slidingBufferSizeSamples: Int =
            (SAMPLE_RATE * SettingsManager.DEFAULT_WHISPER_HISTORY_SEC / 1000).toInt()
    private var audioHistory: ShortArray? = null
    private val historyLock = Any() // Thread Safety: synchronized přístup k audioHistory
    private var whisperLanguage: String = SettingsManager.DEFAULT_WHISPER_LANGUAGE
    private var whisperNThreads: Int = SettingsManager.DEFAULT_WHISPER_N_THREADS
    private var softwareGain: Float = SettingsManager.DEFAULT_WHISPER_SOFTWARE_GAIN

    // Sherpa-Specific State
    private var sherpaProcessingIntervalMs: Long =
            SettingsManager.DEFAULT_SHERPA_PROCESSING_INTERVAL_MS

    private var sherpaNThreads: Int = SettingsManager.DEFAULT_SHERPA_N_THREADS

    private var sherpaSoftwareGain: Float = SettingsManager.DEFAULT_SHERPA_SOFTWARE_GAIN

    private var sherpaLanguage: String = SettingsManager.DEFAULT_SHERPA_LANGUAGE

    // Transcript historie pro WorldState akumulaci
    private val transcriptHistory = StringBuilder()

    // C++ Paměťová obrana (OOM mitigace)
    private var sherpaOfflineRecognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer? = null
    private var sherpaOnlineRecognizer: com.k2fsa.sherpa.onnx.OnlineRecognizer? = null

    /**
     * Start nahrávání a periodického ASR inference. Automaticky zvolí engine dle
     * settingsManager.transcriptEngine.
     * @param customAudioRecord Pokud je poskytnut (např. od SystemAudioInterceptor), použije se
     * tento. Jinak si vytvoří vlastní mikrofonní vstup. POZOR: platí pouze pro whisper engine.
     */
    /** Vrátí true pokud agent aktuálně nahrává (isRecording == true). */
    fun isRunning(): Boolean = isRecording.get()

    fun start(customAudioRecord: AudioRecord? = null, profile: com.example.llama.srt.TranscriptProfile? = null) {
        if (isRecording.getAndSet(true)) {
            Log.w(TAG, "⚠️ TranscriptAgent již běží, ignoruji duplicitní start()")
            return
        }


        // Kontrola RECORD_AUDIO permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "❌ Chybí RECORD_AUDIO oprávnění — TranscriptAgent nemůže startovat")
            isRecording.set(false)
            return
        }

        val selectedEngine = settingsManager.transcriptEngine
        Log.i(TAG, "🎤 TranscriptAgent starting with engine: '$selectedEngine'")

        // Inicializace gainu z hlavního nastavení (Mixpult to může dočasně overridnout)
        if (selectedEngine == "sherpa_onnx") {
            microphoneGain = settingsManager.sherpaSoftwareGain
            systemAudioGain = settingsManager.sherpaSoftwareGain
        } else {
            microphoneGain = settingsManager.whisperSoftwareGain
            systemAudioGain = settingsManager.whisperSoftwareGain
        }

        // 1. Resolve Profile: if not provided, fallback to liveAgentProfile
        activeProfile = profile ?: com.example.llama.srt.TranscriptProfileOrchestrator.liveAgentProfile(
            whisperNThreads = settingsManager.whisperNThreads,
            sherpaNThreads = settingsManager.sherpaNThreads,
            isWhisperEngine = selectedEngine == "whisper",
            thermalGuard = null // Nebo předat existující, pokud ho agent řídí
        )
        
        Log.i(TAG, "🧠 Používám profil: ${activeProfile?.useCase} (Vlákna: ${activeProfile?.numThreads}, Window: ${activeProfile?.slidingWindowMs}ms, Punctuation: ${activeProfile?.enablePunctuation})")

        // 2. Emit lifecycle event and request focus
        // Krok 3: Použijeme dispatcher z profilu, ne natvrdo Dispatchers.Default
        val dispatcher = activeProfile?.dispatcher ?: Dispatchers.Default
        engineScope = CoroutineScope(dispatcher + SupervisorJob())
        
        engineScope?.launch {
            val focus = AgentAudioOrchestrator.requestAgentListeningFocus()
            if (!focus) {
                Log.w(TAG, "⚠️ TranscriptAgentnedostal STT Focus od Orchestrátoru.")
            }
            PerceptionEventBus.emit(
                    PerceptionLifecycleEvent(
                            state = EngineState.STARTED,
                            reason = "TranscriptAgent started (engine=$selectedEngine)"
                    )
            )

            when (selectedEngine) {
                "android" -> startAndroidSpeechRecognizer()
                "whisper" -> startWhisperEngine(customAudioRecord)
                "sherpa" -> startSherpaEngine(customAudioRecord)
                else -> {
                    Log.w(TAG, "⚠️ Neznámý engine '\$selectedEngine', fallback na 'android'")
                    startAndroidSpeechRecognizer()
                }
            }
        }
    }

    /** Zastavení nahrávání a uvolnění prostředků. Idempotentní. */
    fun stop() {
        if (!isRecording.getAndSet(false)) return // Už zastaveno

        Log.i(TAG, "🔇 TranscriptAgent: stop() called")

        // Stop Engine A (SpeechRecognizer)
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
                speechRecognizer?.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ SpeechRecognizer cleanup exception", e)
            }
            speechRecognizer = null
        }

        // Zastavíme nahrávání, to okamžitě odblokuje visící audioRecord.read() v C++!
        // customAudioRecordRef: non-null = FG Service vlastní (NESMÍME release()),
        //                       null     = vlastní mic (smíme release()).
        // POZOR: customAudioRecordRef NEMAŽEME zde — releaseAudioRecord() ve finally
        // bloku coroutiny ho potřebuje pro správné rozhodnutí.
        val snapshotCustomRef = customAudioRecordRef
        val snapshotRecord = audioRecord
        audioRecord = null
        try {
            snapshotRecord?.stop()
            if (snapshotRecord !== snapshotCustomRef) {
                snapshotRecord?.release() // vlastní mic — uvolníme
            }
        } catch (_: Exception) {}

        // Stop Engine B (whisper/sherpa)
        engineScope?.cancel()
        engineScope = null

        // Bezpečná C++ dealokace pod zámkem a v nezávislém vlákně
        CoroutineScope(Dispatchers.IO).launch {
            inferenceMutex.withLock {
                try {
                    sherpaOfflineRecognizer?.release()
                    sherpaOfflineRecognizer = null
                    sherpaOnlineRecognizer?.release()
                    sherpaOnlineRecognizer = null
                    WhisperJni.release()
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chyba při C++ uvolnění Enginu B", e)
                }
            }
        }

        // Release Audio Focus via Orchestrator in independent scope
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            AgentAudioOrchestrator.releaseFocus()
        }

        consecutiveErrors = 0

        // Uvolnění Sliding Window paměti (Engine B)
        audioHistory = null
    }

    /** Úplné zničení (volat v onDestroy služby). */
    fun destroy() {
        stop()
        WhisperJni.release()
        transcriptHistory.clear()
        Log.i(TAG, "♻️ TranscriptAgent destroyed")
    }

    /** Vrací akumulovaný transcript pro LLM sumarizaci (Tier 3). */
    fun getFullTranscript(): String = transcriptHistory.toString()

    /** Vymaže transkriptovou historii (po LLM sumarizaci). */
    fun clearTranscript() {
        transcriptHistory.clear()
    }

    // ========================================================================
    // ENGINE A: Android SpeechRecognizer (OKAMŽITÝ — partial results ~200ms)
    // ========================================================================

    /**
     * Spustí Android on-device SpeechRecognizer s continuous listening pattern. MUSÍ běžet na Main
     * thread (Android framework requirement).
     *
     * Architektura (inspirováno Google Live Transcribe):
     * 1. SpeechRecognizer naslouchá a volá onPartialResults() s latencí ~200ms
     * 2. Po onEndOfSpeech/onResults se automaticky restartuje (continuous loop)
     * 3. Partial results → TranscriptEvent(isFinal=false) → SmartCaption zobrazí okamžitě
     * 4. Final results → TranscriptEvent(isFinal=true) → historie pro LLM
     */
    private fun startAndroidSpeechRecognizer() {
        mainHandler.post {
            try {
                // Preferujeme explicitly zvolený Android modul z nastavení Transcriptu
                val preferredEngine = settingsManager.transcriptAndroidEngine
                val recognizer =
                        if (preferredEngine.isNotEmpty()) {
                            val componentName =
                                    if (preferredEngine.contains("/")) {
                                        val parts = preferredEngine.split("/")
                                        android.content.ComponentName(parts[0], parts[1])
                                    } else {
                                        null
                                    }

                            if (componentName != null) {
                                Log.i(
                                        TAG,
                                        "⚙️ Používám explicitní ASR engine pro Transkripci: $preferredEngine"
                                )
                                SpeechRecognizer.createSpeechRecognizer(context, componentName)
                            } else {
                                Log.w(
                                        TAG,
                                        "⚠️ Neplatný formát ComponentName: $preferredEngine, použito výchozí"
                                )
                                SpeechRecognizer.createSpeechRecognizer(context)
                            }
                        } else {
                            if (android.os.Build.VERSION.SDK_INT >= 31 &&
                                            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                            ) {
                                Log.i(TAG, "✅ Používám Defaultní ON-DEVICE SpeechRecognizer z OS")
                                SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                            } else {
                                Log.i(
                                        TAG,
                                        "☁️ Defaultní on-device ASR nedostupný, používám cloud/systémový"
                                )
                                SpeechRecognizer.createSpeechRecognizer(context)
                            }
                        }

                if (recognizer == null) {
                    Log.e(TAG, "❌ SpeechRecognizer.create() vrátil null! ASR není dostupný.")
                    isRecording.set(false)
                    return@post
                }

                speechRecognizer = recognizer
                recognizer.setRecognitionListener(createRecognitionListener())
                startListeningSession()

                Log.i(TAG, "🎤 TranscriptAgent started (engine: Android SpeechRecognizer)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při vytváření SpeechRecognizer", e)
                isRecording.set(false)
            }
        }
    }

    /**
     * Vytvoří RecognizerIntent pro SpeechRecognizer. Klíčový parametr: EXTRA_PARTIAL_RESULTS = true
     * → okamžitý text
     */
    private fun createRecognizerIntent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            // Model: free-form (přirozená řeč, ne jen příkazy)
            putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            // KLÍČOVÉ: Partial results = okamžitý text po ~200ms
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

            // Jazyk: cs-CZ (čeština), nebo systémový default
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "cs-CZ")

            // Preferuj offline rozpoznávání pokud je dostupné
            // putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)

            // Bez promptu (žádné "Mluvte teď" UI)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "")

            // Nutné pro API 30+ a explicitní spuštění mimo default (jinak služba potichu umře)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Režim diktování vynutí delší povolenou dobu i pro nativní ASR
            putExtra("android.speech.extra.DICTATION_MODE", true)

            // Maximální délka ticha před ukončením (ms) — delší = méně restartů
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    1500L
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
    }

    /** Spustí jednu naslouchací session. Volá se opakovaně pro continuous listening pattern. */
    private fun startListeningSession() {
        if (!isRecording.get()) return

        val sr = speechRecognizer
        if (sr == null) {
            Log.w(TAG, "⚠️ SpeechRecognizer je null, nelze spustit session")
            return
        }

        try {
            sr.startListening(createRecognizerIntent())
        } catch (e: Exception) {
            Log.e(TAG, "❌ startListening() selhalo", e)
            handleRecognizerError()
        }
    }

    /**
     * Vytvoří RecognitionListener pro zpracování partial/final výsledků. Toto je srdce okamžitého
     * přepisu.
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "🎙️ SpeechRecognizer ready for speech")
                consecutiveErrors = 0 // Reset error counter — úspěšný start
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "🗣️ Řeč detekována")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Nelogujeme — příliš časté (každých ~20ms)
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Nepoužíváme — zpracováváme jen text výstupy
            }

            override fun onEndOfSpeech() {
                Log.d(TAG, "🔇 Konec řeči detekován")
            }

            /**
             * KLÍČOVÝ CALLBACK: Partial results — okamžitý text (~200ms latence). Toto je to, co
             * dělá titulky "okamžitými" jako v YouTube/TikTok.
             */
            override fun onPartialResults(partialResults: Bundle?) {
                val results =
                        partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!results.isNullOrEmpty()) {
                    val partialText = results[0]
                    if (partialText.isNotBlank()) {
                        Log.d(TAG, "⚡ Partial: $partialText")

                        // Emit PARTIAL TranscriptEvent → SmartCaption zobrazí OKAMŽITĚ
                        engineScope?.launch {
                            PerceptionEventBus.emit(
                                    TranscriptEvent(
                                            text = partialText,
                                            language = "cs",
                                            isFinal = false // PARTIAL — může se ještě změnit
                                    )
                            )
                        }
                    }
                }
            }

            /**
             * FINÁLNÍ VÝSLEDEK: Celá rozpoznaná věta po ukončení řeči. Ukládá se do historie a
             * spouští automatický restart.
             */
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val finalText = matches[0]
                    if (finalText.isNotBlank()) {
                        Log.i(TAG, "✅ Final: $finalText")

                        // Akumulace do historie
                        transcriptHistory.append(finalText).append(" ")

                        // Emit FINAL TranscriptEvent
                        engineScope?.launch {
                            PerceptionEventBus.emit(
                                    TranscriptEvent(
                                            text = finalText,
                                            language = "cs",
                                            isFinal = true // FINAL — definitivní text
                                    )
                            )
                        }
                    }
                }

                // CONTINUOUS LISTENING: Automatický restart po krátkém delay
                scheduleRestart()
            }

            override fun onError(error: Int) {
                val errorName =
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
                            SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
                            SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                    "INSUFFICIENT_PERMISSIONS"
                            SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
                            SpeechRecognizer.ERROR_SERVER -> "SERVER"
                            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
                            else -> "UNKNOWN($error)"
                        }
                Log.w(TAG, "⚠️ SpeechRecognizer error: $errorName")

                // NO_MATCH a SPEECH_TIMEOUT jsou normální — restartujeme
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    scheduleRestart()
                    return
                }

                // Ostatní chyby — inkrementujeme counter, restart s delším delay
                handleRecognizerError()
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // Nepoužíváno
            }
        }
    }

    /**
     * Naplánuje restart SpeechRecognizer po krátkém delay. Toto zajišťuje continuous listening
     * pattern (jako Google Live Transcribe).
     */
    private fun scheduleRestart() {
        if (!isRecording.get()) return

        mainHandler.postDelayed(
                {
                    if (isRecording.get()) {
                        startListeningSession()
                    }
                },
                RESTART_DELAY_MS
        )
    }

    /**
     * Zpracování chyby SpeechRecognizer s exponenciálním backoffem. Chrání před nekonečnou smyčkou
     * restartů.
     */
    private fun handleRecognizerError() {
        consecutiveErrors++

        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            Log.e(TAG, "❌ Max consecutive errors ($MAX_CONSECUTIVE_ERRORS) reached. Stopping.")
            engineScope?.launch {
                PerceptionEventBus.emit(
                        com.example.llama.agent.perception.PerceptionLifecycleEvent(
                                com.example.llama.agent.perception.EngineState.ERROR,
                                "Android ASR hard-stop fail."
                        )
                )
            }
            isRecording.set(false)
            return
        }

        // Exponenciální backoff: 300ms, 600ms, 1200ms, 2400ms...
        val backoffMs = RESTART_DELAY_MS * (1L shl consecutiveErrors)
        Log.w(TAG, "🔄 Restart po ${backoffMs}ms (error #$consecutiveErrors)")

        mainHandler.postDelayed(
                {
                    if (isRecording.get()) {
                        startListeningSession()
                    }
                },
                backoffMs
        )
    }

    // ========================================================================
    // ENGINE B: whisper.cpp JNI (PŘESNÝ OFFLINE — batch inference)
    // ========================================================================

    /**
     * Spustí whisper.cpp engine s AudioRecord nahrávací smyčkou.
     *
     * SLIDING WINDOW ARCHITEKTURA (2026):
     * 1. Načte dynamické parametry z SettingsManager (historySec, interval, language...)
     * 2. Alokuje ShortArray o velikosti SAMPLE_RATE * historySec
     * 3. Spustí recordingLoop s Klouzavým Oknem, VAD, Force Flush a Frame Drop
     *
     * @param customAudioRecord Pokud je poskytnut (SystemAudioInterceptor), použije se System Audio
     */
    private fun startWhisperEngine(customAudioRecord: AudioRecord?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        if (engineScope == null) {
            engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }

        engineScope?.launch {
            var localRecord: AudioRecord? = null
            try {
                localRecord = initAudioRecord(customAudioRecord)

                val isSystemAudio = customAudioRecord != null
                val sourceName = if (isSystemAudio) "system_audio (MediaProjection)" else "mic"
                Log.i(TAG, "🎤 TranscriptAgent started (engine: whisper.cpp, source: $sourceName)")

                // === DYNAMIC ALLOCATION: Načtení parametrů z SettingsManager a Profilu ===
                val profile = activeProfile
                val historyWindowMs = profile?.slidingWindowMs ?: settingsManager.whisperHistorySec.toLong()
                historySec = historyWindowMs.toInt().coerceIn(500, 30000) // max 30s (ms)
                
                processingIntervalMs = profile?.processingIntervalMs ?: settingsManager.whisperProcessingIntervalMs
                whisperLanguage = settingsManager.whisperLanguage // default "cs"
                whisperNThreads = profile?.numThreads ?: settingsManager.whisperNThreads
                softwareGain = settingsManager.whisperSoftwareGain // coerceIn(0.5f, 5.0f)

                slidingBufferSizeSamples = (SAMPLE_RATE * historySec / 1000).toInt()
                audioHistory = ShortArray(slidingBufferSizeSamples)

                Log.i(
                        TAG,
                        "🎙️ Whisper Sliding Window: Historie=${historySec}s " +
                                "(${slidingBufferSizeSamples} samples), Interval=${processingIntervalMs}ms, " +
                                "Jazyk=$whisperLanguage, Vlákna=$whisperNThreads, Gain=$softwareGain"
                )

                // Load Whisper Model (mimo UI vlákno)
                val modelPath = WhisperModelManager.getModelPathFromRepository(context)
                if (modelPath.isEmpty()) {
                    throw IllegalStateException(
                            "Whisper model not found! Import model via Settings → 🎙 Whisper tab."
                    )
                }
                if (!WhisperJni.init(modelPath, settingsManager.whisperAcceleratorMode)) {
                    throw IllegalStateException("Failed to initialize whisper.cpp natively")
                }

                // Hlavní nahrávací smyčka (Sliding Window)
                localRecord?.let { recordingLoop(it, isSystemAudio) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.i(TAG, "Whisper recording loop zrušen (normální ukončení).")
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ TranscriptAgent whisper error (non-fatal)", e)
            } finally {
                releaseAudioRecord(localRecord)
                isRecording.set(false)
                Log.i(TAG, "🎤 TranscriptAgent whisper stopped")
            }
        }
    }

    // ========================================================================
    // PRIVATE — Audio Record lifecycle (pro whisper engine)
    // ========================================================================

    @RequiresPermission(android.Manifest.permission.RECORD_AUDIO)
    private fun initAudioRecord(customAudioRecord: AudioRecord?): AudioRecord {
        val record =
                if (customAudioRecord != null) {
                    if (customAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("Custom AudioRecord is not initialized — FG Service pravděpodobně selhala")
                    }
                    if (customAudioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        customAudioRecord.startRecording()
                    }
                    customAudioRecordRef = customAudioRecord // FG Service vlastní tento objekt
                    customAudioRecord
                } else {
                    customAudioRecordRef = null // Vlastníme sami, smíme release()
                    // ZERO-LAG OPTIMALIZACE: Použití nativní frekvence i pro lokální mikrofon
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                    val nativeSampleRate = audioManager.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toInt() ?: 48000

                    val minBufSize =
                            AudioRecord.getMinBufferSize(nativeSampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
                    if (minBufSize == AudioRecord.ERROR || minBufSize == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        throw IllegalStateException(
                                "AudioRecord.getMinBufferSize failed: $minBufSize"
                        )
                    }
                    val bufferSize = maxOf(minBufSize, nativeSampleRate * CHUNK_DURATION_SEC * 2)
                    val newRecord =
                            AudioRecord(
                                    MediaRecorder.AudioSource.MIC,
                                    nativeSampleRate,
                                    CHANNEL_CONFIG,
                                    AUDIO_FORMAT,
                                    bufferSize
                            )
                    if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
                        throw IllegalStateException("AudioRecord initialization failed")
                    }
                    // ── DSP Pipeline: Připojit HW filtry PŘED startRecording() ──
                    val currentDspConfig = dspConfig
                    if (!currentDspConfig.isRawBypass) {
                        dspChain.attach(newRecord, currentDspConfig)
                    }
                    newRecord.startRecording()
                    newRecord
                }

        this.audioRecord = record
        return record
    }

    private fun releaseAudioRecord(record: AudioRecord?) {
        if (record == null) return

        // Snímáme customAudioRecordRef NA ZAČÁTKU — před jakýmikoliv mutacemi.
        // stop() tento field NEMAŽE, proto ho můžeme spolehlivě přečíst i po volání stop().
        val isFgServiceRecord = (record === customAudioRecordRef)

        if (this.audioRecord === record) {
            this.audioRecord = null
        }
        customAudioRecordRef = null

        // ── DSP Pipeline: Uvolnit HW filtry PŘED AudioRecord.release() ──
        dspChain.release()

        try { record.stop() } catch (_: Exception) {}

        // KRITICKÉ: custom (MediaProjection) AudioRecord NIKDY neuvolňujeme!
        // Vlastní ho TranscriptForegroundService — pouze ona smí volat release().
        if (!isFgServiceRecord) {
            try {
                record.release()
            } catch (_: Exception) {}
        } else {
            Log.d(TAG, "🔒 Custom (MediaProjection) AudioRecord — skip release(), FG Service vlastní objekt")
        }

        // Navrácení gainu na hodnoty ze Settings (pokud není diktafon aktivní)
        if (settingsManager.transcriptEngine == "sherpa_onnx") {
            microphoneGain = settingsManager.sherpaSoftwareGain
            systemAudioGain = settingsManager.sherpaSoftwareGain
        } else {
            microphoneGain = settingsManager.whisperSoftwareGain
            systemAudioGain = settingsManager.whisperSoftwareGain
        }
    }

    // ========================================================================
    // PRIVATE — Recording Loop + Whisper Inference (Engine B)
    // ========================================================================

    private fun applyGain(
            buffer: ShortArray,
            readCount: Int,
            isSystemAudio: Boolean
    ) {
        val currentGain = if (isSystemAudio) systemAudioGain else microphoneGain
        if (currentGain == 1.0f) return
        for (i in 0 until readCount) {
            val amplified = (buffer[i] * currentGain).toInt()
            buffer[i] =
                    amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun recordingLoop(record: AudioRecord, isSystemAudio: Boolean) {
        // --- ZERO-LAG DOWNSAMPLING PŘÍPRAVA ---
        val nativeSampleRate = record.sampleRate
        val needsDownsampling = nativeSampleRate != SAMPLE_RATE
        val ratio = nativeSampleRate.toFloat() / SAMPLE_RATE.toFloat()

        // Buffer: Pro plynulý mikro-batching čteme cca 85ms zvuku
        val readBufferSize = if (needsDownsampling) 4096 else 1024
        val rawBuffer = ShortArray(readBufferSize)
        
        // Velikost cílového bufferu (pro 16kHz Whisper)
        val targetSize = if (needsDownsampling) (readBufferSize / ratio).toInt() + 1 else readBufferSize
        val whisperBuffer = if (needsDownsampling) ShortArray(targetSize) else rawBuffer

        // THREAD PRIORITY: Live AudioRecord loop — prevence buffer overflow (ztracené samples = díry v přepisu)
        // URGENT_AUDIO (-19): Správné pro live AudioRecord read loop. Dovoluje Android pro audio streaming threads.
        // Pozn.: URGENT_AUDIO pro ML inference (offline batch) je špatně — pouze pro live recording loop!
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        val RMS_SILENCE_THRESHOLD = 50.0

        var silenceCounter = 0
        var isSpeaking = false
        var lastProcessTime = System.currentTimeMillis()
        var speechDurationMs = 0L // Force Flush protection

        // Lokální reference (alokováno v startWhisperEngine)
        val history = audioHistory ?: return
        val bufferSize = slidingBufferSizeSamples

        while (isRecording.get() && engineScope?.isActive == true) {
            val readCount = record.read(rawBuffer, 0, rawBuffer.size)

            if (readCount > 0) {
                // 1. DOWNSAMPLING (Zero-Lag O(N) Triks)
                val processedCount: Int
                if (needsDownsampling) {
                    if (nativeSampleRate == 48000) {
                        // Extrémně rychlá decimace 3:1 (48000 -> 16000)
                        var wIndex = 0
                        for (i in 0 until readCount step 3) {
                            whisperBuffer[wIndex++] = rawBuffer[i]
                        }
                        processedCount = wIndex
                    } else {
                        // Lineární interpolace pro 44100 -> 16000 a jiné nepoměry
                        var wIndex = 0
                        for (i in 0 until targetSize) {
                            val srcIndex = (i * ratio).toInt()
                            if (srcIndex < readCount) {
                                whisperBuffer[wIndex++] = rawBuffer[srcIndex]
                            } else {
                                break
                            }
                        }
                        processedCount = wIndex
                    }
                } else {
                    processedCount = readCount
                }

                // 2. 🔊 Software Gain (pro tichý MediaProjection z YouTube apod.)
                applyGain(whisperBuffer, processedCount, isSystemAudio)

                // 3. VAD: Výpočet RMS hlasitosti z již zpracovaných dat (16kHz)
                var sumSquares = 0.0
                for (i in 0 until processedCount) {
                    val sample = whisperBuffer[i].toDouble()
                    sumSquares += sample * sample
                }
                
                // --- GATEWAY EXPORT: Odeslání raw dat do Audio Studia ---
                if (processedCount > 0 && audioDataCallback != null) {
                    audioDataCallback?.invoke(whisperBuffer.copyOfRange(0, processedCount))
                }

                val rms = Math.sqrt(sumSquares / processedCount)

                // 4. POSUN KLOUZAVÉHO OKNA (Sliding Window Shift)
                val safeReadCount = minOf(processedCount, bufferSize)
                synchronized(historyLock) {
                    System.arraycopy(history, safeReadCount, history, 0, bufferSize - safeReadCount)
                    System.arraycopy(
                            whisperBuffer,
                            0,
                            history,
                            bufferSize - safeReadCount,
                            safeReadCount
                    )
                }

                // 5. Logika mluvení vs. ticho (VAD state machine)
                if (rms < RMS_SILENCE_THRESHOLD) {
                    silenceCounter++
                    // ~500ms ticha + předtím se mluvilo → Final flush
                    if (silenceCounter > 10 && isSpeaking) {
                        isSpeaking = false
                        speechDurationMs = 0L
                        processChunkAsync(isFinal = true)
                        lastProcessTime = System.currentTimeMillis()
                    }
                } else {
                    silenceCounter = 0
                    isSpeaking = true
                }

                // 6. PRAVIDELNÝ TIK BĚHEM MLUVENÍ (Drafting / Incremental)
                val now = System.currentTimeMillis()
                if (isSpeaking) {
                    // Přičtení reálného času v závislosti na opravdu přečtených bajtech z I/O
                    speechDurationMs += (processedCount * 1000L) / SAMPLE_RATE

                    // a) NUCENÝ FLUSH: Pokud někdo mluví v kuse > 8 sekund
                    if (speechDurationMs > 8000L) {
                        processChunkAsync(isFinal = true)
                        speechDurationMs = 0L
                        lastProcessTime = now
                    }
                    // b) BĚŽNÝ DRAFT TIK (např. každých 500ms)
                    else if (now - lastProcessTime >= processingIntervalMs) {
                        processChunkAsync(isFinal = false)
                        lastProcessTime = now
                    }
                }
            } else if (readCount < 0) {
                Log.w(TAG, "⚠️ AudioRecord.read() error: $readCount. Ukončuji smyčku.")
                break
            }
        }
    }

    /** Zpracuje audio chunk přes whisper.cpp JNI asynchronně s Frame Drop mechanismem. */
    private fun processChunkAsync(isFinal: Boolean) {
        val history = audioHistory ?: return
        val bufferSize = slidingBufferSizeSamples

        // 1. Snapshot celého klouzavého okna (Nativní O(1) Memory Copy pro URGENT_AUDIO vlákno)
        val activeAudioShorts = ShortArray(bufferSize)
        synchronized(historyLock) {
            System.arraycopy(history, 0, activeAudioShorts, 0, bufferSize)
        }

        // 2. Asynchronní inference s Frame Drop a Blockem (Bifurkovaný Mutex Logik)
        // Orchestrátor LIVE_AGENT: Dispatchers.Default (CPU-bound), THREAD_PRIORITY_DEFAULT (0)
        // Separace od recording loop (URGENT_AUDIO -19): inference NESMÍ dědit prioritu od volajícího vlákna
        engineScope?.launch(Dispatchers.Default) {
            // Explicitní priorita inference vlákna — DEFAULT (0), ne URGENT_AUDIO od recording loop
            // Bez tohoto nastavení by vlákno mohlo zdědit prioritu z thread poolu nebo scheduleru
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)

            // Převod na floaty probíhá asynchronně, neblokuje čtecí buffer z I/O
            val activeAudioData = FloatArray(bufferSize) { activeAudioShorts[it].toFloat() / 32768.0f }

            val executeInference: suspend () -> Unit = {
                val startMs = System.currentTimeMillis()
                val transcribedText =
                        WhisperJni.transcribe(
                                audioData = activeAudioData,
                                language = whisperLanguage,
                                nThreads = whisperNThreads
                        )
                val durMs = System.currentTimeMillis() - startMs

                if (transcribedText.isNotBlank() && !transcribedText.contains("[BLANK_AUDIO]")) {
                    Log.v(TAG, "⚡ Whisper ($durMs ms) [Final=$isFinal]: $transcribedText")

                    var displayText = transcribedText

                    if (isFinal) {
                        transcriptHistory.append(displayText.trim()).append(" ")
                    } else {
                        val words = transcribedText.split(" ")
                        if (words.size > 2) {
                            displayText = words.dropLast(1).joinToString(" ") + "..."
                        }
                    }

                    PerceptionEventBus.emit(
                            TranscriptEvent(
                                    text = displayText,
                                    language = whisperLanguage,
                                    isFinal = isFinal
                            )
                    )
                }
            }

            if (isFinal) {
                inferenceMutex.withLock { executeInference() }
            } else {
                if (inferenceMutex.tryLock()) {
                    try {
                        executeInference()
                    } finally {
                        inferenceMutex.unlock()
                    }
                } else {
                    Log.d(TAG, "⚠️ VAD Frame Drop pro Draft! (Není dostatek C++ výkonu Whisper).")
                }
            }
        }
    }

    // ========================================================================
    // PRIVATE — Sherpa-ONNX Dual-Track Inference (Engine C)
    // ========================================================================
    //
    // TRACK A: OnlineRecognizer (Streaming) — pro online modely (Zipformer, CTC)
    // TRACK B: OfflineRecognizer (Batch/VAD) — pro offline modely (NeMo, Offline Zipformer)
    //
    // Automatická detekce režimu z SherpaModelType.

    /** VAD: Práh RMS ticha pro offline batch dekóding */
    private val SHERPA_VAD_SILENCE_THRESHOLD = 50.0
    /** VAD: Počet tichých chunků (~50ms každý) před spuštěním offline inference */
    private val SHERPA_VAD_SILENCE_CHUNKS = 20

    private fun startSherpaEngine(customAudioRecord: AudioRecord?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        val modelManager = SherpaModelManager(context, settingsManager)
        val paths = modelManager.getSttModelPaths()

        if (paths == null) {
            Log.e(TAG, "❌ Sherpa model není dostupný nebo je poškozený! Fallback na Android STT.")
            engineScope?.launch {
                PerceptionEventBus.emit(
                        PerceptionLifecycleEvent(
                                state = EngineState.ERROR,
                                reason = "Sherpa model chybí nebo je nekompletní"
                        )
                )
            }
            startAndroidSpeechRecognizer()
            return
        }

        // 🛡️ Validace souborů PŘED JNI (Zero Silent Failures)
        if (paths.type.isTransducer) {
            if (paths.encoder.isNullOrEmpty() ||
                            paths.decoder.isNullOrEmpty() ||
                            paths.joiner.isNullOrEmpty()
            ) {
                val missing =
                        listOfNotNull(
                                if (paths.encoder.isNullOrEmpty()) "encoder" else null,
                                if (paths.decoder.isNullOrEmpty()) "decoder" else null,
                                if (paths.joiner.isNullOrEmpty()) "joiner" else null
                        )
                Log.e(
                        TAG,
                        "❌ STT model nekompletní! Chybí: ${missing.joinToString(", ")}. Fallback na Android STT."
                )
                engineScope?.launch {
                    PerceptionEventBus.emit(
                            PerceptionLifecycleEvent(
                                    state = EngineState.ERROR,
                                    reason =
                                            "Sherpa model nekompletní: ${missing.joinToString(", ")}"
                            )
                    )
                }
                startAndroidSpeechRecognizer()
                return
            }
        }

        // --- Zavedení STT Nastavení Uživatele pro Sherpu a Profilu ---
        val profile = activeProfile
        val historyWindowMs = profile?.slidingWindowMs ?: settingsManager.sherpaHistorySec.toLong()
        val historySec = historyWindowMs.toInt().coerceIn(500, 30000) // ms
        
        sherpaProcessingIntervalMs = profile?.processingIntervalMs ?: settingsManager.sherpaProcessingIntervalMs
        sherpaLanguage = settingsManager.sherpaLanguage
        sherpaNThreads = profile?.numThreads ?: settingsManager.sherpaNThreads
        sherpaSoftwareGain = settingsManager.sherpaSoftwareGain

        slidingBufferSizeSamples = (SAMPLE_RATE * historySec / 1000).toInt()
        audioHistory = ShortArray(slidingBufferSizeSamples)

        Log.i(
                TAG,
                "🎙️ Sherpa Sliding Window Nastavení: Historie=${historySec}s " +
                        "(${slidingBufferSizeSamples} samples), Interval=${sherpaProcessingIntervalMs}ms, " +
                        "Jazyk=$sherpaLanguage, Vlákna=$sherpaNThreads, Gain=$sherpaSoftwareGain"
        )

        val isSystemAudio = customAudioRecord != null

        var localRecord: AudioRecord? = null
        try {
            localRecord = initAudioRecord(customAudioRecord)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba inicializace mikrofonu pro Sherpu", e)
            return
        }

        // Rozhodnutí: Online (streaming) vs Offline (batch)
        val isOnlineMode = paths.type.isOnline

        engineScope?.launch {
            try {
                if (isOnlineMode) {
                    localRecord?.let { runSherpaOnlineLoop(paths, it, isSystemAudio) }
                } else {
                    localRecord?.let { runSherpaOfflineLoop(paths, it, isSystemAudio) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                android.util.Log.i(TAG, "Sherpa inference loop zrušen (normální ukončení).")
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Fatální chyba v Sherpa Inference: ${e.message}", e)
                consecutiveErrors++
                if (consecutiveErrors < MAX_CONSECUTIVE_ERRORS) {
                    android.util.Log.w(TAG, "🔄 Failover → Android SpeechRecognizer")
                    startAndroidSpeechRecognizer()
                }
            } finally {
                releaseAudioRecord(localRecord)
            }
        }
    }

    /**
     * TRACK A: Online (Streaming) Sherpa inference. Streamovaný dekóding s endpointingem — partial
     * + final results.
     */
    private suspend fun runSherpaOnlineLoop(paths: SherpaSttPaths, record: AudioRecord, isSystemAudio: Boolean) {
        Log.i(TAG, "⚡ Spouštím Sherpa-ONNX ONLINE (Streaming) Inferenci...")

        val transConfig =
                if (paths.type.isTransducer) {
                    com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                            encoder = paths.encoder ?: "",
                            decoder = paths.decoder ?: "",
                            joiner = paths.joiner ?: ""
                    )
                } else {
                    com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                            encoder = "",
                            decoder = "",
                            joiner = ""
                    )
                }

        val ctcConfig =
                if (paths.type == SherpaModelType.STT_ONLINE_CTC) {
                    com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(model = paths.model ?: "")
                } else {
                    com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(model = "")
                }

        val sherpaProvider = when (settingsManager.sherpaAcceleratorMode) {
            "NNAPI" -> "nnapi"
            "GPU" -> "nnapi"
            else -> "cpu"
        }

        val config =
                com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                        featConfig =
                                com.k2fsa.sherpa.onnx.FeatureConfig(
                                        sampleRate = SAMPLE_RATE,
                                        featureDim = 80
                                ),
                        modelConfig =
                                com.k2fsa.sherpa.onnx.OnlineModelConfig(
                                        transducer = transConfig,
                                        zipformer2Ctc = ctcConfig,
                                        tokens = paths.tokens,
                                        numThreads = sherpaNThreads,
                                        provider = sherpaProvider,
                                        debug = false
                                ),
                        endpointConfig =
                                com.k2fsa.sherpa.onnx.EndpointConfig(
                                        rule1 =
                                                com.k2fsa.sherpa.onnx.EndpointRule(
                                                        false,
                                                        2.4f,
                                                        0.0f
                                                ),
                                        rule2 =
                                                com.k2fsa.sherpa.onnx.EndpointRule(
                                                        true,
                                                        1.2f,
                                                        0.0f
                                                ),
                                        rule3 =
                                                com.k2fsa.sherpa.onnx.EndpointRule(
                                                        false,
                                                        0.0f,
                                                        20.0f
                                                )
                                ),
                        enableEndpoint = true
                )

        val recognizer = com.k2fsa.sherpa.onnx.OnlineRecognizer(assetManager = null, config = config)
        sherpaOnlineRecognizer = recognizer
        val stream = sherpaOnlineRecognizer!!.createStream()

        // --- ZERO-LAG DOWNSAMPLING PŘÍPRAVA ---
        val nativeSampleRate = record.sampleRate
        val needsDownsampling = nativeSampleRate != SAMPLE_RATE
        val ratio = nativeSampleRate.toFloat() / SAMPLE_RATE.toFloat()

        val readBufferSize = if (needsDownsampling) 4096 else 1024
        val rawBuffer = ShortArray(readBufferSize)
        val targetSize = if (needsDownsampling) (readBufferSize / ratio).toInt() + 1 else readBufferSize
        val sherpaBuffer = if (needsDownsampling) ShortArray(targetSize) else rawBuffer

        // URGENT_AUDIO (-19): Live Sherpa Online streaming loop — AudioRecord read, prevence buffer overflow
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        var lastEmittedPartial = ""

        try {
            Log.i(TAG, "🎤 TranscriptAgent started (engine: Sherpa ONLINE Streaming)")

            while (isRecording.get() && engineScope?.isActive == true) {
                val readCount = record.read(rawBuffer, 0, rawBuffer.size)
                if (readCount > 0) {
                    val processedCount: Int
                    if (needsDownsampling) {
                        if (nativeSampleRate == 48000) {
                            var wIndex = 0
                            for (i in 0 until readCount step 3) sherpaBuffer[wIndex++] = rawBuffer[i]
                            processedCount = wIndex
                        } else {
                            var wIndex = 0
                            for (i in 0 until targetSize) {
                                val srcIndex = (i * ratio).toInt()
                                if (srcIndex < readCount) sherpaBuffer[wIndex++] = rawBuffer[srcIndex] else break
                            }
                            processedCount = wIndex
                        }
                    } else {
                        processedCount = readCount
                    }

                    applyGain(sherpaBuffer, processedCount, isSystemAudio) // Nyní jako stateless!
                    
                    // --- GATEWAY EXPORT: Odeslání raw dat do Audio Studia ---
                    if (processedCount > 0 && audioDataCallback != null) {
                        audioDataCallback?.invoke(sherpaBuffer.copyOfRange(0, processedCount))
                    }
                    
                    val floatSamples = FloatArray(processedCount) { sherpaBuffer[it] / 32768.0f }

                    inferenceMutex.withLock {
                        stream.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)
                        while (sherpaOnlineRecognizer!!.isReady(stream)) {
                            sherpaOnlineRecognizer!!.decode(stream)
                        }

                        val currentText = sherpaOnlineRecognizer!!.getResult(stream).text

                        if (sherpaOnlineRecognizer!!.isEndpoint(stream)) {
                            // ENDPOINT → emituj jako final a resetuj
                            if (currentText.isNotBlank()) {
                                Log.i(TAG, "🏁 (Sherpa Online Final) → $currentText")
                                transcriptHistory.append(currentText).append(" ")
                                PerceptionEventBus.emit(
                                        TranscriptEvent(
                                                text = currentText,
                                                language = sherpaLanguage,
                                                isFinal = true
                                        )
                                )
                            }
                            sherpaOnlineRecognizer!!.reset(stream)
                            lastEmittedPartial = ""
                        } else if (currentText.isNotBlank() && currentText != lastEmittedPartial) {
                            // PARTIAL → emituj pouze KDYŽ SE TEXT ZMĚNÍ (Deduplikace)
                            lastEmittedPartial = currentText
                            PerceptionEventBus.emit(
                                    TranscriptEvent(
                                            text = currentText,
                                            language = sherpaLanguage,
                                            isFinal = false
                                    )
                            )
                        }
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "❌ AudioRecord vrátil chybový kód: $readCount")
                    break
                }
            }
        } finally {
            inferenceMutex.withLock {
                try {
                    stream.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Stream release: ${e.message}")
                }
                try {
                    recognizer.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Recognizer release: ${e.message}")
                }
                sherpaOnlineRecognizer = null
            }
            Log.i(TAG, "⚡ Sherpa ONLINE Inference Loop ukončena")
        }
    }

    /**
     * TRACK B: Offline (Batch/VAD) Sherpa inference. Sbírá audio, po detekci ticha schroustne celou
     * větu přes OfflineRecognizer.
     */
    private suspend fun runSherpaOfflineLoop(
            paths: SherpaSttPaths,
            record: AudioRecord,
            isSystemAudio: Boolean
    ) {
        Log.i(TAG, "⚡ Spouštím Sherpa-ONNX OFFLINE (Batch) Inferenci...")

        // Map accelerator mode to Sherpa provider
        val sherpaProvider = when (settingsManager.sherpaAcceleratorMode) {
            "NNAPI" -> "nnapi"
            "GPU" -> "nnapi" // ONNX Runtime typically uses NNAPI for GPU/NPU on Android
            else -> "cpu"
        }

        // Clean dispatch: KAŽDÝ typ plní JEDNO pole v OfflineModelConfig.
        // Sherpa C++ vrstva autodetekuje loader dle prvního non-empty pole.
        val modelConfig = when (paths.type) {

            SherpaModelType.STT_OFFLINE_TRANSDUCER ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                                    encoder = paths.encoder ?: "",
                                    decoder = paths.decoder ?: "",
                                    joiner = paths.joiner ?: ""
                            ),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_CTC ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(model = paths.model ?: ""),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            zipformerCtc = com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(model = paths.model ?: ""),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_SENSEVOICE ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(
                                    model = paths.model ?: "",
                                    language = sherpaLanguage,
                                    useInverseTextNormalization = true
                            ),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_WHISPER ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(
                                    encoder = paths.encoder ?: "",
                                    decoder = paths.decoder ?: "",
                                    language = sherpaLanguage
                            ),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_MOONSHINE ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(
                                    preprocessor = paths.preprocessor ?: "",
                                    encoder = paths.encoder ?: "",
                                    uncachedDecoder = paths.decoder ?: "",
                                    cachedDecoder = paths.decoder2 ?: ""
                            ),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_NEMO_CTC ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            nemo = com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(model = paths.model ?: ""),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            SherpaModelType.STT_OFFLINE_OMNILINGUAL ->
                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                            omnilingual = com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig(model = paths.model ?: ""),
                            tokens = paths.tokens,
                            numThreads = sherpaNThreads,
                            provider = sherpaProvider
                    )

            else -> {
                Log.w(TAG, "⚠️ runSherpaOfflineLoop: Neočekávaný typ ${paths.type}")
                com.k2fsa.sherpa.onnx.OfflineModelConfig(
                        tokens = paths.tokens,
                        numThreads = sherpaNThreads,
                        provider = sherpaProvider
                )
            }
        }

        val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                featConfig = com.k2fsa.sherpa.onnx.FeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = 80
                ),
                modelConfig = modelConfig
        )

        val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(assetManager = null, config = config)
        sherpaOfflineRecognizer = recognizer

        // --- ZERO-LAG DOWNSAMPLING PŘÍPRAVA ---
        val nativeSampleRate = record.sampleRate
        val needsDownsampling = nativeSampleRate != SAMPLE_RATE
        val ratio = nativeSampleRate.toFloat() / SAMPLE_RATE.toFloat()

        val readBufferSize = if (needsDownsampling) 4096 else 1024
        val rawBuffer = ShortArray(readBufferSize)
        val targetSize = if (needsDownsampling) (readBufferSize / ratio).toInt() + 1 else readBufferSize
        val sherpaBuffer = if (needsDownsampling) ShortArray(targetSize) else rawBuffer

        // URGENT_AUDIO (-19): Live Sherpa Offline streaming loop — AudioRecord read, prevence buffer overflow
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Exception) {}

        val SHERPA_RMS_SILENCE_THRESHOLD = activeProfile?.vadSilenceThreshold ?: 50.0

        var silenceCounter = 0
        var isSpeaking = false
        var lastProcessTime = System.currentTimeMillis()
        var speechDurationMs = 0L

        // Lokální reference na klouzavé pole
        val history = audioHistory ?: return
        val bufferSize = slidingBufferSizeSamples

        try {
            Log.i(TAG, "🎤 TranscriptAgent started (engine: Sherpa OFFLINE Sliding Window)")

            while (isRecording.get() && engineScope?.isActive == true) {
                val readCount = record.read(rawBuffer, 0, rawBuffer.size)

                if (readCount > 0) {
                    val processedCount: Int
                    if (needsDownsampling) {
                        if (nativeSampleRate == 48000) {
                            var wIndex = 0
                            for (i in 0 until readCount step 3) sherpaBuffer[wIndex++] = rawBuffer[i]
                            processedCount = wIndex
                        } else {
                            var wIndex = 0
                            for (i in 0 until targetSize) {
                                val srcIndex = (i * ratio).toInt()
                                if (srcIndex < readCount) sherpaBuffer[wIndex++] = rawBuffer[srcIndex] else break
                            }
                            processedCount = wIndex
                        }
                    } else {
                        processedCount = readCount
                    }

                    // 1. Zesílení z MediaProjection (Sherpa config)
                    applyGain(sherpaBuffer, processedCount, isSystemAudio)

                    // 2. VAD - RMS výpočet
                    var sumSquares = 0.0
                    for (i in 0 until processedCount) {
                        val sample = sherpaBuffer[i].toDouble()
                        sumSquares += sample * sample
                    }
                    
                    // --- GATEWAY EXPORT: Odeslání raw dat do Audio Studia ---
                    if (processedCount > 0 && audioDataCallback != null) {
                        audioDataCallback?.invoke(sherpaBuffer.copyOfRange(0, processedCount))
                    }

                    val rms = Math.sqrt(sumSquares / processedCount)

                    // 3. Posun Klouzavého okna (Sliding Window Shift)
                    val safeReadCount = minOf(processedCount, bufferSize)
                    synchronized(historyLock) {
                        System.arraycopy(
                                history,
                                safeReadCount,
                                history,
                                0,
                                bufferSize - safeReadCount
                        )
                        System.arraycopy(
                                sherpaBuffer,
                                0,
                                history,
                                bufferSize - safeReadCount,
                                safeReadCount
                        )
                    }

                    // 4. Logika mluvení vs. ticho (VAD state machine)
                    val vadEnabled = activeProfile?.enableVAD ?: true
                    if (vadEnabled && rms < SHERPA_RMS_SILENCE_THRESHOLD) {
                        silenceCounter++
                        val vadChunks = activeProfile?.vadSilenceChunks ?: 10
                        if (silenceCounter > vadChunks && isSpeaking) {
                            isSpeaking = false
                            speechDurationMs = 0L
                            processSherpaChunkAsync(isFinal = true, sherpaOfflineRecognizer!!)
                            lastProcessTime = System.currentTimeMillis()
                            
                            // OPRAVA DUPLIKACÍ: Vymazání historie po odeslání finální věty, ALE JEN PRO DIKTAFON!
                            // Živý přepis (Live Agent) potřebuje klouzavé okno pro kontext.
                            if (activeProfile?.useCase == com.example.llama.srt.TranscriptUseCase.DICTAPHONE) {
                                synchronized(historyLock) {
                                    history.fill(0)
                                }
                            }
                        }
                    } else {
                        silenceCounter = 0
                        isSpeaking = true
                    }

                    // 5. Draft tick a Force Flush Ochrana
                    val now = System.currentTimeMillis()
                    if (isSpeaking) {
                        speechDurationMs += (processedCount * 1000L) / SAMPLE_RATE

                        // a) Force flush po 8 sekundách (nebo dle profilu) "Infinite Speech"
                        val forceFlushLimitMs = maxOf(2000L, (activeProfile?.slidingWindowMs ?: 8000L) / 2)
                        if (speechDurationMs > forceFlushLimitMs) {
                            processSherpaChunkAsync(isFinal = true, sherpaOfflineRecognizer!!)
                            speechDurationMs = 0L
                            lastProcessTime = now
                            
                            // OPRAVA DUPLIKACÍ: Vymazání historie po nuceném odeslání, ALE JEN PRO DIKTAFON!
                            if (activeProfile?.useCase == com.example.llama.srt.TranscriptUseCase.DICTAPHONE) {
                                synchronized(historyLock) {
                                    history.fill(0)
                                }
                            }
                        }
                        // b) Běžný Draft tik
                        else if (now - lastProcessTime >= sherpaProcessingIntervalMs) {
                            processSherpaChunkAsync(isFinal = false, sherpaOfflineRecognizer!!)
                            lastProcessTime = now
                        }
                    }
                } else if (readCount < 0) {
                    Log.e(TAG, "❌ AudioRecord vrátil chybový kód: $readCount. Ukončuji smyčku.")
                    break
                }
            }
        } finally {
            inferenceMutex.withLock {
                try {
                    recognizer.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Recognizer release: ${e.message}")
                }
                sherpaOfflineRecognizer = null
            }
            Log.i(TAG, "⚡ Sherpa OFFLINE Inference Loop ukončena")
        }
    }

    /** Mimořádně asynchronní funkce pro Frame-Drop Sherpa inferenci v C++. */
    private fun processSherpaChunkAsync(
            isFinal: Boolean,
            recognizer: com.k2fsa.sherpa.onnx.OfflineRecognizer
    ) {
        val history = audioHistory ?: return
        val bufferSize = slidingBufferSizeSamples

        // 1. Snapshot celého klouzavého okna (Nativní O(1) Memory Copy pro URGENT_AUDIO vlákno)
        val activeAudioShorts = ShortArray(bufferSize)
        synchronized(historyLock) {
            System.arraycopy(history, 0, activeAudioShorts, 0, bufferSize)
        }

        // 2. Odeslání do fronty - Bifurkovaný Mutex
        engineScope?.launch(Dispatchers.Default) {
            // Převod na floaty probíhá asynchronně, neblokuje čtecí buffer z I/O
            val activeAudioData = FloatArray(bufferSize) { activeAudioShorts[it].toFloat() / 32768.0f }

            val executeSherpaInference: suspend () -> Unit = {
                var stream: com.k2fsa.sherpa.onnx.OfflineStream? = null
                try {
                    val startMs = System.currentTimeMillis()
                    stream = recognizer.createStream()
                    stream.acceptWaveform(activeAudioData, SAMPLE_RATE)
                    recognizer.decode(stream)

                    var text = recognizer.getResult(stream).text

                    // 3. Bleeding Edge Trim & Tokenizer chránění
                    if (!isFinal && text.isNotBlank()) {
                        val words = text.split(" ").filter { it.isNotBlank() }
                        if (words.size > 1) {
                            text = words.dropLast(1).joinToString(" ")
                        } else {
                            text = "" // zahodit osamocené neuplné fragmenty
                        }
                        // Odstranění "utržených" BPE znaků ze Sherpy typu _ anebo diakritik
                        text = text.replace(Regex("[_\\.,!?]+$"), "")
                    }

                    if (text.isNotBlank()) {
                        if (isFinal) {
                            Log.i(
                                    TAG,
                                    "🏁 (Sherpa Final) → $text (${System.currentTimeMillis() - startMs}ms)"
                            )
                            transcriptHistory.append(text).append(" ")
                            PerceptionEventBus.emit(
                                    TranscriptEvent(text = text, language = settingsManager.sherpaLanguage, isFinal = true)
                            )
                        } else {
                            Log.d(TAG, "⏳ (Sherpa Draft) → $text")
                            PerceptionEventBus.emit(
                                    TranscriptEvent(text = text, language = settingsManager.sherpaLanguage, isFinal = false)
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Sherpa inference failed: ${e.message}")
                } finally {
                    // EXTÉMNÍ OOP JNI ZÁCHRANA PŘED OOM
                    try {
                        stream?.release()
                    } catch (_: Exception) {}
                }
            }

            if (isFinal) {
                // FINÁLNÍ VĚTA: Nesmí se nikdy zahodit! Fronta trpělivě počká.
                inferenceMutex.withLock { executeSherpaInference() }
            } else {
                // DRAFT: Pokud procesor nestíhá, frame zahodíme (Frame Drop)
                if (inferenceMutex.tryLock()) {
                    try {
                        executeSherpaInference()
                    } finally {
                        inferenceMutex.unlock()
                    }
                } else {
                    Log.d(TAG, "⚠️ VAD Frame Drop pro Draft! (Není dostatek C++ výkonu Sherpa).")
                }
            }
        }
    }

    /**
     * Veřejná brána pro izolovaný přepis surových dat (SRT Editor, Titulkovač).
     *
     * LAZY INIT STRATEGIE (Zero RAM Duplication):
     * - Pokud agent BĚŽÍ → modely jsou již načteny → použijeme je přímo.
     * - Pokud agent NEBĚŽÍ → lazy inicializujeme model do STEJNÉ instance agenta.
     *   Žádná duplikace v RAM — jen jeden model v procesu.
     * - inferenceMutex chrání před souběžným přístupem recording loop ↔ SRT editor.
     */
    suspend fun transcribeOfflineChunk(pcmChunk: ShortArray): String? {
        return inferenceMutex.withLock {
            try {
                val floatArray = FloatArray(pcmChunk.size) { pcmChunk[it].toFloat() / 32768.0f }

                when (val engine = settingsManager.transcriptEngine) {
                    "whisper" -> {
                        // Lazy init Whisper JNI modelu pokud není načten
                        val modelPath = WhisperModelManager.getModelPathFromRepository(context)
                        if (modelPath.isEmpty()) {
                            Log.e(TAG, "❌ transcribeOfflineChunk: Whisper model nenalezen.")
                            return@withLock null
                        }
                        if (!WhisperJni.init(modelPath, settingsManager.whisperAcceleratorMode)) {
                            Log.e(TAG, "❌ transcribeOfflineChunk: WhisperJni.init() selhalo.")
                            return@withLock null
                        }
                        WhisperJni.transcribe(
                            audioData = floatArray,
                            language = whisperLanguage,
                            nThreads = whisperNThreads
                        )
                    }
                    "sherpa" -> {
                        // Lazy init Sherpa OfflineRecognizer pokud není načten
                        val recognizer = sherpaOfflineRecognizer ?: run {
                            Log.i(TAG, "🔄 transcribeOfflineChunk: Lazy init Sherpa offline modelu...")
                            val modelManager = SherpaModelManager(context, settingsManager)
                            val paths = modelManager.getSttModelPaths()
                            if (paths == null || paths.type.isOnline) {
                                Log.e(TAG, "❌ transcribeOfflineChunk: Sherpa offline model nenalezen nebo je Online typ.")
                                return@withLock null
                            }
                            val sherpaProvider = when (settingsManager.sherpaAcceleratorMode) {
                                "NNAPI" -> "nnapi"
                                "GPU" -> "nnapi"
                                else -> "cpu"
                            }
                            
                            // Sestavení konfigurace (stejná logika jako runSherpaOfflineLoop)
                            val modelConfig = when (paths.type) {
                                SherpaModelType.STT_OFFLINE_TRANSDUCER ->
                                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                                            encoder = paths.encoder ?: "",
                                            decoder = paths.decoder ?: "",
                                            joiner  = paths.joiner  ?: ""
                                        ),
                                        tokens     = paths.tokens,
                                        numThreads = settingsManager.sherpaNThreads.toInt(),
                                        provider   = sherpaProvider
                                    )
                                else ->
                                    com.k2fsa.sherpa.onnx.OfflineModelConfig(
                                        tokens     = paths.tokens,
                                        numThreads = settingsManager.sherpaNThreads.toInt(),
                                        provider   = sherpaProvider
                                    )
                            }
                            val config = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                                featConfig  = com.k2fsa.sherpa.onnx.FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                                modelConfig = modelConfig
                            )
                            val newRecognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(assetManager = null, config = config)
                            sherpaOfflineRecognizer = newRecognizer
                            Log.i(TAG, "✅ transcribeOfflineChunk: Sherpa offline lazy init OK")
                            newRecognizer
                        }
                        val stream = recognizer.createStream()
                        stream.acceptWaveform(floatArray, SAMPLE_RATE)
                        recognizer.decode(stream)
                        val text = recognizer.getResult(stream).text
                        stream.release()
                        text
                    }
                    else -> {
                        Log.w(TAG, "⚠️ transcribeOfflineChunk: Nekompatibilní engine '$engine' (použij whisper nebo sherpa)")
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ transcribeOfflineChunk chyba: ${e.message}", e)
                null
            }
        }
    }
}
