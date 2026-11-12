package com.example.llama.agent.voice.sherpa

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.perception.SherpaModelManager
import com.example.llama.agent.perception.SherpaModelType
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ======================================================================== SHERPA STT ENGINE —
 * Dual-Track Architecture (Enterprise-Grade)
 * ========================================================================
 *
 * Podporuje DVA režimy STT inference:
 *
 * TRACK A: OnlineRecognizer (STREAMING)
 * - Pro "online" (streaming) modely (Zipformer, Conformer, LSTM)
 * - Zpracovává audio po malých kouscích (real-time)
 * - Podporuje endpointing (automatická detekce konce věty)
 * - Partial + Final results s minimální latencí
 *
 * TRACK B: OfflineRecognizer (BATCH)
 * - Pro "offline" (batch/dávkové) modely (NeMo, Offline Zipformer)
 * - Sbírá audio do bufferu, po detekci ticha (VAD) schroustne celou větu
 * - Vyšší přesnost, ale mírně vyšší latence (≈1-3s)
 *
 * Thread Safety:
 * - engineMutex chrání všechna volání do C++ recognizeru
 * - Zabraňuje SIGABRT při shutdown() dokud běží dekódování
 *
 * Zero Silent Failures:
 * - Validace souborů PŘED JNI inicializací
 * - Chybějící/poškozený model → onError callback, ne crash
 */
class SherpaSttEngine(private val context: Context, private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "SherpaSttEngine"
        private const val SAMPLE_RATE = 16000

        /** VAD: Práh RMS ticha pro offline batch dekóding */
        private const val VAD_SILENCE_THRESHOLD = 50.0
        /** VAD: Počet tichých chunků (~50ms každý) před spuštěním offline inference */
        private const val VAD_SILENCE_CHUNKS = 20
        /** Minimální délka nahrávky v samples pro offline inference (0.5s) */
        private const val MIN_AUDIO_SAMPLES = SAMPLE_RATE / 2
    }

    // === Dual-Track Recognizer Instances ===
    private var onlineRecognizer: OnlineRecognizer? = null
    private var offlineRecognizer: OfflineRecognizer? = null
    private var isOnlineMode = true // Flag: Online (streaming) vs Offline (batch)

    // === Engine State ===
    private var isInitialized = false
    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var listeningJob: Job? = null
    private val isListening = AtomicBoolean(false)

    // === Thread Safety: Nativní C++ Mutex ===
    private val engineMutex = Mutex()

    // === Callbacks pro integraci s AgentVoiceSTT ===
    var onPartialResult: ((String) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Inicializuje Sherpa STT engine. Automaticky detekuje typ modelu a vytvoří správný recognizer
     * (Online nebo Offline).
     *
     * @return true pokud se inicializace zdařila, false pokud ne
     */
    fun initialize(): Boolean {
        if (isInitialized && (onlineRecognizer != null || offlineRecognizer != null)) return true

        val modelManager = SherpaModelManager(context, settingsManager)
        val paths = modelManager.getSttModelPaths()
        if (paths == null) {
            Log.e(TAG, "❌ Sherpa STT model není k dispozici nebo je nekompletní.")
            onError?.invoke(
                    "Sherpa STT model nenalezen. Importujte model v Nastavení → 🎙 Whisper/Sherpa."
            )
            return false
        }

        // 🛡️ ZÁCHRANNÁ BRZDA: Validace souborů před JNI (Zero Silent Failures)
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
                        "❌ KRITICKÁ CHYBA: STT model nekompletní! Chybí: ${missing.joinToString(", ")}"
                )
                onError?.invoke(
                        "STT model je poškozený — chybí ${missing.joinToString(", ")}. Importujte model znovu."
                )
                return false
            }
        }

        try {
            isOnlineMode = paths.type.isOnline

            if (isOnlineMode) {
                initializeOnlineRecognizer(paths)
            } else {
                initializeOfflineRecognizer(paths)
            }

            isInitialized = true
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Fatální chyba C++ JNI při inicializaci Sherpa STT: ${e.message}", e)
            onError?.invoke("Chyba enginu Sherpa: ${e.message}")
            // Cleanup: Uvolni cokoliv co se částečně inicializovalo
            safeReleaseRecognizers()
            return false
        }
    }

    /**
     * Inicializace ONLINE (Streaming) recognizeru. Pro modely: Online Zipformer, Conformer, LSTM,
     * CTC.
     */
    private fun initializeOnlineRecognizer(
            paths: com.example.llama.agent.perception.SherpaSttPaths
    ) {
        val config = buildOnlineConfig(paths)
        onlineRecognizer = OnlineRecognizer(assetManager = null, config = config)
        Log.i(TAG, "✅ Sherpa STT ONLINE (Streaming) engine inicializován (typ: ${paths.type})")
    }

    /**
     * Inicializace OFFLINE (Batch) recognizeru. Postaví config přes buildOfflineConfig() — jen
     * JEDEN field je non-empty.
     */
    private fun initializeOfflineRecognizer(
            paths: com.example.llama.agent.perception.SherpaSttPaths
    ) {
        val config = buildOfflineConfig(paths)
        offlineRecognizer = OfflineRecognizer(assetManager = null, config = config)
        Log.i(TAG, "✅ Sherpa STT OFFLINE inicializován (typ: ${paths.type})")
    }

    // ========================================================================
    // CONFIG BUILDERS — čistý dispatch dle SherpaModelType
    // Princip: JEDEN typ => JEDNO non-empty pole v OfflineModelConfig.
    // Sherpa C++ vrstavá typ loaderu dle prvního non-empty pole.
    // ========================================================================

    private fun buildOnlineConfig(
            paths: com.example.llama.agent.perception.SherpaSttPaths
    ): OnlineRecognizerConfig {
        val transducerConfig =
                when {
                    paths.type.isTransducer ->
                            OnlineTransducerModelConfig(
                                    encoder = paths.encoder ?: "",
                                    decoder = paths.decoder ?: "",
                                    joiner = paths.joiner ?: ""
                            )
                    else -> OnlineTransducerModelConfig(encoder = "", decoder = "", joiner = "")
                }

        val ctcConfig =
                when (paths.type) {
                    SherpaModelType.STT_ONLINE_CTC ->
                            OnlineZipformer2CtcModelConfig(model = paths.model ?: "")
                    else -> OnlineZipformer2CtcModelConfig(model = "")
                }

        val providerStr = when (settingsManager.sherpaAcceleratorMode.uppercase()) {
            "NNAPI" -> "nnapi"
            "GPU" -> "gpu"
            else -> "cpu"
        }
        
        Log.i(TAG, "🤖 Sherpa Online Recognizer Init - Backend: $providerStr, Threads: ${settingsManager.sherpaNThreads}")

        return OnlineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig =
                        OnlineModelConfig(
                                transducer = transducerConfig,
                                zipformer2Ctc = ctcConfig,
                                tokens = paths.tokens,
                                numThreads = settingsManager.sherpaNThreads,
                                debug = false,
                                provider = providerStr
                        ),
                endpointConfig =
                        EndpointConfig(
                                rule1 = EndpointRule(false, 2.4f, 0.0f),
                                rule2 = EndpointRule(true, 1.2f, 0.0f),
                                rule3 = EndpointRule(false, 0.0f, 20.0f)
                        ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
                maxActivePaths = 4
        )
    }

    /**
     * Staví OfflineRecognizerConfig dle typu modelu. Kritický invariant: vždy je non-empty PRÁVĚ
     * JEDEN z konfiguracínich polochích. Sherpa-ONNX C++ vrstva autodetekuje typ dle prvního
     * non-empty pole.
     */
    private fun buildOfflineConfig(
            paths: com.example.llama.agent.perception.SherpaSttPaths
    ): OfflineRecognizerConfig {
        // Sestáváme OfflineModelConfig s JEDNÍM aktivním fieldem dle typu
        val modelConfig =
                when (paths.type) {

                    // Transducer (NeMo Parakeet TDT — 3 soubory: encoder+decoder+joiner)
                    SherpaModelType.STT_OFFLINE_TRANSDUCER ->
                            OfflineModelConfig(
                                    transducer =
                                            OfflineTransducerModelConfig(
                                                    encoder = paths.encoder ?: "",
                                                    decoder = paths.decoder ?: "",
                                                    joiner = paths.joiner ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // Paraformer (single model.onnx, čínský ASR)
                    SherpaModelType.STT_OFFLINE_CTC ->
                            OfflineModelConfig(
                                    paraformer =
                                            OfflineParaformerModelConfig(model = paths.model ?: ""),
                                    tokens = paths.tokens
                            )

                    // Offline Zipformer CTC (sherpa-onnx-zipformer-ctc-zh-*)
                    SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER ->
                            OfflineModelConfig(
                                    zipformerCtc =
                                            OfflineZipformerCtcModelConfig(
                                                    model = paths.model ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // SenseVoice (FunAudioLLM — emotion detection, multilingual)
                    SherpaModelType.STT_OFFLINE_SENSEVOICE ->
                            OfflineModelConfig(
                                    senseVoice =
                                            OfflineSenseVoiceModelConfig(
                                                    model = paths.model ?: "",
                                                    language = "auto",
                                                    useInverseTextNormalization = true
                                            ),
                                    tokens = paths.tokens
                            )

                    // Whisper (OpenAI — encoder.onnx + decoder.onnx)
                    SherpaModelType.STT_OFFLINE_WHISPER ->
                            OfflineModelConfig(
                                    whisper =
                                            OfflineWhisperModelConfig(
                                                    encoder = paths.encoder ?: "",
                                                    decoder = paths.decoder ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // Moonshine (Useful Sensors —
                    // preprocessor+encoder+uncached_decoder+cached_decoder)
                    SherpaModelType.STT_OFFLINE_MOONSHINE ->
                            OfflineModelConfig(
                                    moonshine =
                                            OfflineMoonshineModelConfig(
                                                    preprocessor = paths.preprocessor ?: "",
                                                    encoder = paths.encoder ?: "",
                                                    uncachedDecoder = paths.decoder ?: "",
                                                    cachedDecoder = paths.decoder2 ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // NeMo EncDecCTC (NVIDIA — single model.onnx)
                    SherpaModelType.STT_OFFLINE_NEMO_CTC ->
                            OfflineModelConfig(
                                    nemo =
                                            OfflineNemoEncDecCtcModelConfig(
                                                    model = paths.model ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // Omnilingual ASR (Meta AI, wav2vec2+CTC, 1600+ jazyků)
                    SherpaModelType.STT_OFFLINE_OMNILINGUAL ->
                            OfflineModelConfig(
                                    omnilingual =
                                            OfflineOmnilingualAsrCtcModelConfig(
                                                    model = paths.model ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // Qwen-ASR
                    SherpaModelType.STT_OFFLINE_QWEN ->
                            OfflineModelConfig(
                                    qwen3Asr =
                                            OfflineQwen3AsrModelConfig(
                                                    encoder = paths.encoder ?: "",
                                                    decoder = paths.decoder ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // FunASR (Nano)
                    SherpaModelType.STT_OFFLINE_FUNASR_NANO ->
                            OfflineModelConfig(
                                    funasrNano =
                                            OfflineFunAsrNanoModelConfig(
                                                    encoderAdaptor = paths.encoder ?: "",
                                                    llm = paths.model ?: paths.decoder ?: "",
                                                    embedding = paths.joiner ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // TeleSpeech
                    SherpaModelType.STT_OFFLINE_TELE_SPEECH ->
                            OfflineModelConfig(
                                    teleSpeech = paths.model ?: "",
                                    tokens = paths.tokens
                            )

                    // GigaAM (Sber) - obvykle funguje jako CTC model (napr. Nemo/Zipformer fallback)
                    SherpaModelType.STT_OFFLINE_GIGA_AM ->
                            OfflineModelConfig(
                                    nemo =
                                            OfflineNemoEncDecCtcModelConfig(
                                                    model = paths.model ?: ""
                                            ),
                                    tokens = paths.tokens
                            )

                    // Vosk - obvykle zabaleno jako kaldi transducer nebo CTC
                    SherpaModelType.STT_OFFLINE_VOSK -> {
                        if (!paths.encoder.isNullOrEmpty() && !paths.decoder.isNullOrEmpty() && !paths.joiner.isNullOrEmpty()) {
                            OfflineModelConfig(
                                    transducer = OfflineTransducerModelConfig(
                                            encoder = paths.encoder,
                                            decoder = paths.decoder,
                                            joiner = paths.joiner
                                    ),
                                    tokens = paths.tokens
                            )
                        } else {
                            // Fallback pokud je to single-file model
                            OfflineModelConfig(
                                    zipformerCtc = OfflineZipformerCtcModelConfig(
                                            model = paths.model ?: ""
                                    ),
                                    tokens = paths.tokens
                            )
                        }
                    }

                    // Fallback — neměl by nastat (ONLINE typy jdou do buildOnlineConfig)
                    else -> {
                        Log.w(
                                TAG,
                                "⚠️ buildOfflineConfig: Neočekávaný typ ${paths.type}. Prázdný config."
                        )
                        OfflineModelConfig(tokens = paths.tokens)
                    }
                }

        val providerStr = when (settingsManager.sherpaAcceleratorMode.uppercase()) {
            "NNAPI" -> "nnapi"
            "GPU" -> "gpu"
            else -> "cpu"
        }
        modelConfig.numThreads = settingsManager.sherpaNThreads
        modelConfig.debug = false
        modelConfig.provider = providerStr

        Log.i(TAG, "🤖 Sherpa Offline Recognizer Init - Backend: $providerStr, Threads: ${settingsManager.sherpaNThreads}")

        return OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig
        )
    }

    /**
     * Start listening. Requires HW Focus to be handled BEFORE calling this! Automaticky volí
     * správnou inference smyčku podle detekovaného typu modelu.
     */
    fun startListening() {
        if (!isInitialized || (onlineRecognizer == null && offlineRecognizer == null)) {
            if (!initialize()) {
                onError?.invoke("Sherpa STT engine se nepodařilo inicializovat.")
                return
            }
        }

        if (isListening.get()) return

        try {
            val bufferSize =
                    AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                    )

            audioRecord =
                    AudioRecord(
                            MediaRecorder.AudioSource.VOICE_RECOGNITION,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize * 2
                    )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke("Nepodařilo se inicializovat AudioRecord pro Sherpa STT.")
                return
            }

            audioRecord?.startRecording()
            isListening.set(true)

            listeningJob =
                    scope.launch {
                        if (isOnlineMode) {
                            Log.i(TAG, "🎤 Spouštím ONLINE (Streaming) inference smyčku...")
                            runOnlineInferenceLoop()
                        } else {
                            Log.i(TAG, "🎤 Spouštím OFFLINE (Batch/VAD) inference smyčku...")
                            runOfflineInferenceLoop()
                        }
                    }
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ Chybí oprávnění RECORD_AUDIO", e)
            onError?.invoke("Chybí oprávnění RECORD_AUDIO")
            stopListening()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při nahrávání: ${e.message}", e)
            onError?.invoke("Chyba rekordéru: ${e.message}")
            stopListening()
        }
    }

    // ========================================================================
    // TRACK A: Online (Streaming) Inference Loop
    // ========================================================================

    /**
     * Streamovaný dekóding: zpracovává audio po malých kouscích s endpointingem. Partial results
     * (průběžný text) + Final results (po detekci konce věty).
     */
    private suspend fun runOnlineInferenceLoop() {
        val recognizer = onlineRecognizer ?: return
        val stream = recognizer.createStream()
        val tempBuffer = ShortArray(1024)
        var lastText = ""

        try {
            while (coroutineContext.isActive && isListening.get()) {
                val readCount = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: -1
                if (readCount > 0) {
                    var sumSquares = 0.0
                    val floatSamples = FloatArray(readCount) { 
                        val sample = tempBuffer[it].toDouble()
                        sumSquares += sample * sample
                        tempBuffer[it] / 32768.0f 
                    }
                    val rms = Math.sqrt(sumSquares / readCount)
                    val db = 20 * Math.log10(rms.coerceAtLeast(1e-5) / 32768.0) // Normalize to 0dB max
                    com.example.llama.agent.perception.PerceptionEventBus.emit(
                        com.example.llama.agent.perception.AudioLevelEvent(rmsDb = db.toFloat(), peakDb = db.toFloat())
                    )



                    // Nativní zámek proti SIGABRT při shutdown()
                    engineMutex.withLock {
                        if (onlineRecognizer == null) return@withLock
                        stream.acceptWaveform(floatSamples, sampleRate = SAMPLE_RATE)

                        while (recognizer.isReady(stream)) {
                            recognizer.decode(stream)
                        }

                        val isEndpoint = recognizer.isEndpoint(stream)
                        val text = recognizer.getResult(stream).text

                        if (text.isNotBlank() && text != lastText) {
                            lastText = text
                            onPartialResult?.invoke(text)
                        }

                        if (isEndpoint) {
                            if (text.isNotBlank()) {
                                onFinalResult?.invoke(text)
                            }
                            recognizer.reset(stream)
                            lastText = ""
                        }
                    }
                }
            }
        } finally {
            engineMutex.withLock {
                try {
                    stream.release()
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Chyba při uvolňování online streamu: ${e.message}")
                }
            }
        }
    }

    // ========================================================================
    // TRACK B: Offline (Batch/VAD) Inference Loop
    // ========================================================================

    /**
     * VAD-řízený batch dekóding: sbírá audio do akumulátoru, po detekci ticha (~1s) schroustne
     * celou nahrávku přes OfflineRecognizer.
     *
     * Vzorováno na existující whisper.cpp logice v TranscriptAgent.
     */
    private suspend fun runOfflineInferenceLoop() {
        val tempBuffer = ShortArray(1024)
        val audioAccumulator = mutableListOf<Float>()
        var silenceCounter = 0

        try {
            while (coroutineContext.isActive && isListening.get()) {
                val readCount = audioRecord?.read(tempBuffer, 0, tempBuffer.size) ?: -1
                if (readCount > 0) {
                    // VAD: RMS výpočet pro detekci ticha
                    var sumSquares = 0.0
                    val floatChunk = FloatArray(readCount)
                    for (i in 0 until readCount) {
                        val sample = tempBuffer[i].toDouble()
                        sumSquares += sample * sample
                        floatChunk[i] = tempBuffer[i] / 32768.0f
                    }
                    val rms = Math.sqrt(sumSquares / readCount)
                    val db = 20 * Math.log10(rms.coerceAtLeast(1e-5) / 32768.0)
                    com.example.llama.agent.perception.PerceptionEventBus.emit(
                        com.example.llama.agent.perception.AudioLevelEvent(rmsDb = db.toFloat(), peakDb = db.toFloat())
                    )

                    audioAccumulator.addAll(floatChunk.toList())

                    if (rms < VAD_SILENCE_THRESHOLD) {
                        silenceCounter++
                        // Pokud je víc jak ~1 sekunda ticha a máme nahraný zvuk (>0.5s)
                        if (silenceCounter > VAD_SILENCE_CHUNKS &&
                                        audioAccumulator.size > MIN_AUDIO_SAMPLES
                        ) {
                            Log.d(
                                    TAG,
                                    "⚡ VAD ticho → Odesílám blok (${audioAccumulator.size} samples) do Offline Sherpy"
                            )
                            processOfflineBatch(audioAccumulator.toFloatArray())
                            audioAccumulator.clear()
                            silenceCounter = 0
                        }
                    } else {
                        silenceCounter = 0
                    }

                    // Ochrana proti přetečení paměti: Max 30s audio
                    if (audioAccumulator.size > SAMPLE_RATE * 30) {
                        Log.w(TAG, "⚠️ Audio buffer přetekl (30s). Nucený flush.")
                        processOfflineBatch(audioAccumulator.toFloatArray())
                        audioAccumulator.clear()
                        silenceCounter = 0
                    }
                } else if (readCount < 0) {
                    Log.w(TAG, "⚠️ AudioRecord.read() error: $readCount")
                    break
                }
            }

            // Flush zbylého audio při ukončení
            if (audioAccumulator.size > MIN_AUDIO_SAMPLES) {
                processOfflineBatch(audioAccumulator.toFloatArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba v offline inference smyčce: ${e.message}", e)
        }
    }

    /** Zpracuje nahraný audio blok přes OfflineRecognizer. Thread-safe — obaleno Mutexem. */
    private suspend fun processOfflineBatch(samples: FloatArray) {
        val text = transcribeOfflineChunk(samples)
        if (!text.isNullOrBlank()) {
            Log.i(TAG, "🎙️ (Sherpa Offline Final) → $text")
            onFinalResult?.invoke(text)
        }
    }

    /**
     * Veřejná metoda pro izolovaný přepis (např. z Titulkovače) s využitím sdílené paměti Agenta.
     */
    suspend fun transcribeOfflineChunk(samples: FloatArray): String? {
        return engineMutex.withLock {
            val recognizer = offlineRecognizer ?: return@withLock null
            try {
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples, SAMPLE_RATE)
                recognizer.decode(stream)

                val text = recognizer.getResult(stream).text
                stream.release()
                text
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při offline batch dekódingu: ${e.message}", e)
                null
            }
        }
    }

    // ========================================================================
    // LIFECYCLE MANAGEMENT
    // ========================================================================

    fun stopListening() {
        if (!isListening.getAndSet(false)) return

        listeningJob?.cancel()
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Chyba při uvolňování AudioRecord: ${e.message}")
        } finally {
            audioRecord = null
        }
    }

    fun shutdown() {
        stopListening()
        safeReleaseRecognizers()
        isInitialized = false
    }

    /**
     * Thread-safe uvolnění C++ recognizerů. Čeká na dokončení probíhajícího dekódování (Mutex) před
     * uvolněním paměti.
     */
    private fun safeReleaseRecognizers() {
        // Použijeme tryLock pro synchronní volání — pokud Mutex drží inference loop,
        // necháme cancel coroutine a loop se ukončí sám
        try {
            onlineRecognizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error releasing Online Sherpa STT: ${e.message}")
        }
        try {
            offlineRecognizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error releasing Offline Sherpa STT: ${e.message}")
        }
        onlineRecognizer = null
        offlineRecognizer = null
    }
}
