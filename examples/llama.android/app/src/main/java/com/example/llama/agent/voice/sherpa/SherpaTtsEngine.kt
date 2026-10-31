package com.example.llama.agent.voice.sherpa

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.perception.SherpaModelManager
import com.example.llama.agent.perception.SherpaModelType
import com.example.llama.agent.perception.SherpaTtsPaths
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.GenerationConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class SherpaTtsEngine(private val context: Context, private val settingsManager: SettingsManager) {

    companion object {
        private const val TAG = "SherpaTtsEngine"
    }

    private var tts: OfflineTts? = null
    private var isInitialized = false
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var speakJob: Job? = null
    private val isPlaying = AtomicBoolean(false)
    private val audioLock = Any()
    private var currentTtsPaths: SherpaTtsPaths? = null

    fun initialize(): Boolean {
        if (isInitialized && tts != null) return true

        val modelManager = SherpaModelManager(context, settingsManager)
        val paths = modelManager.getTtsModelPaths()
        if (paths == null) {
            Log.e(TAG, "Sherpa TTS model není k dispozici nebo chybí soubory.")
            return false
        }

        try {
            val config = buildTtsConfig(paths)
            if (config == null) {
                Log.e(TAG, "❌ Nepodporovaný TTS typ: ${paths.type}")
                return false
            }

            // 🛡️ ZÁCHRANNÁ BRZDA: Validace souborů PŘED JNI voláním.
            // C++ SIGSEGV nelze zachytit Kotlin try/catch — musíme validovat zde.
            val validationError = validateTtsPaths(paths)
            if (validationError != null) {
                Log.e(TAG, "❌ TTS model nekompletní: $validationError")
                return false
            }

            tts = OfflineTts(assetManager = null, config = config)
            val sampleRate = tts?.sampleRate() ?: 24000
            initAudioTrack(sampleRate)

            isInitialized = true
            currentTtsPaths = paths
            Log.i(TAG, "✅ Sherpa TTS engine úspěšně inicializován ($sampleRate Hz)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při inicializaci Sherpa TTS: ${e.message}", e)
            return false
        }
    }

    /**
     * Pre-JNI validace: ověří, že všechny kritické soubory existují na disku.
     * C++ SIGSEGV (SEGV_MAPERR) nelze zachytit Kotlin try/catch — validace MUSÍ proběhnout zde.
     * @return null pokud je vše OK, jinak chybová zpráva
     */
    private fun validateTtsPaths(paths: SherpaTtsPaths): String? {
        fun fileExists(path: String?): Boolean =
                !path.isNullOrEmpty() && java.io.File(path).exists()

        fun checkRequired(name: String, path: String?): String? =
                if (!fileExists(path)) "Chybí povinný soubor '$name'" +
                        (if (path.isNullOrEmpty()) " (cesta prázdná)" else " (cesta: $path)") else null

        return when (paths.type) {
            SherpaModelType.TTS_PIPER, SherpaModelType.TTS_VITS_STANDARD -> {
                checkRequired("model.onnx", paths.model)
                        ?: checkRequired("tokens.txt", paths.tokens)
            }
            SherpaModelType.TTS_KOKORO -> {
                checkRequired("model.onnx", paths.model)
                        ?: checkRequired("tokens.txt", paths.tokens)
                        ?: checkRequired("voices.bin", paths.voices)
            }
            SherpaModelType.TTS_MATCHA -> {
                checkRequired("acoustic_model.onnx", paths.model)
                        ?: checkRequired("vocoder.onnx", paths.model2)
                        ?: checkRequired("tokens.txt", paths.tokens)
                        ?: checkRequired("espeak-ng-data adresář", paths.dataDir)
                        ?: if (!java.io.File(paths.dataDir, "phontab").exists()) "Chybí soubor 'phontab' ve složce espeak-ng-data" else null
            }
            SherpaModelType.TTS_ZIPVOICE -> {
                checkRequired("tokens.txt", paths.tokens)
                        ?: checkRequired("encoder (text_encoder.onnx)", paths.encoder)
                        ?: checkRequired("decoder (fm_decoder.onnx)", paths.decoder)
                        ?: checkRequired("vocoder (vocos_24khz.onnx)", paths.vocoder)
                        ?: checkRequired("lexicon.txt", paths.lexicon)
                        ?: checkRequired("espeak-ng-data adresář", paths.dataDir)
                        ?: run {
                            val hasEspeak = !paths.dataDir.isNullOrEmpty() &&
                                    java.io.File(paths.dataDir).let { it.exists() && java.io.File(it, "phontab").exists() }
                            val hasLexicon = !paths.lexicon.isNullOrEmpty() &&
                                    java.io.File(paths.lexicon!!).exists()
                            if (!hasEspeak && !hasLexicon) "Chybí espeak-ng-data (s phontab) nebo lexicon.txt" else null
                        }
            }
            SherpaModelType.TTS_KITTEN -> {
                checkRequired("model.onnx", paths.model)
                        ?: checkRequired("voices", paths.voices)
            }
            SherpaModelType.TTS_POCKET -> {
                checkRequired("lm_flow.onnx", paths.lmFlow)
                        ?: checkRequired("lm_main.onnx", paths.lmMain)
                        ?: checkRequired("vocab.json", paths.vocabJson)
            }
            SherpaModelType.TTS_SUPERTONIC -> {
                checkRequired("duration_predictor.onnx", paths.durationPredictor)
                        ?: checkRequired("text_encoder.onnx", paths.textEncoder)
                        ?: checkRequired("vector_estimator.onnx", paths.vectorEstimator)
                        ?: checkRequired("vocoder.onnx", paths.vocoder)
            }
            else -> null
        }
    }

    /**
     * Staví OfflineTtsConfig dle typu modelu.
     * Princip: JEDEN non-empty config field v OfflineTtsModelConfig.
     * Sherpa-onnx C++ vrstva autodetekuje typ dle prvního non-empty pole.
     */
    private fun buildTtsConfig(paths: SherpaTtsPaths): OfflineTtsConfig? {
        val modelConfig = when (paths.type) {

            // VITS / Piper (single model.onnx + espeak-ng-data)
            SherpaModelType.TTS_PIPER, SherpaModelType.TTS_VITS_STANDARD ->
                OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                                model = paths.model,
                                lexicon = paths.lexicon ?: "",
                                tokens = paths.tokens,
                                dataDir = paths.dataDir ?: "",
                                dictDir = ""
                        ),
                        numThreads = 1,
                        provider = "cpu"
                )

            // Kokoro (model + voices.bin)
            SherpaModelType.TTS_KOKORO ->
                OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                                model = paths.model,
                                voices = paths.voices ?: "",
                                tokens = paths.tokens,
                                dataDir = paths.dataDir ?: "",
                                lexicon = paths.lexicon ?: ""
                        ),
                        numThreads = 4,
                        provider = "cpu"
                )

            // Matcha (acoustic_model + vocoder)
            SherpaModelType.TTS_MATCHA ->
                OfflineTtsModelConfig(
                        matcha = OfflineTtsMatchaModelConfig(
                                acousticModel = paths.model,
                                vocoder = paths.model2 ?: "",
                                lexicon = paths.lexicon ?: "",
                                tokens = paths.tokens,
                                dataDir = paths.dataDir ?: ""
                        ),
                        numThreads = 2,
                        provider = "cpu"
                )

            // ZipVoice (encoder + decoder + vocoder, flow-matching)
            SherpaModelType.TTS_ZIPVOICE ->
                OfflineTtsModelConfig(
                        zipvoice = OfflineTtsZipVoiceModelConfig(
                                tokens = paths.tokens,
                                encoder = paths.encoder ?: "",
                                decoder = paths.decoder ?: "",
                                vocoder = paths.vocoder ?: "",
                                dataDir = paths.dataDir ?: "",
                                lexicon = paths.lexicon ?: ""
                        ),
                        numThreads = settingsManager.sherpaTtsZipvoiceThreads,
                        provider = "cpu"
                )

            // KittenTTS (model + voices)
            SherpaModelType.TTS_KITTEN ->
                OfflineTtsModelConfig(
                        kitten = OfflineTtsKittenModelConfig(
                                model = paths.model,
                                voices = paths.voices ?: "",
                                tokens = paths.tokens,
                                dataDir = paths.dataDir ?: ""
                        ),
                        numThreads = 4,
                        provider = "cpu"
                )

            // PocketTTS (lmFlow + lmMain + encoder + decoder + textConditioner)
            SherpaModelType.TTS_POCKET ->
                OfflineTtsModelConfig(
                        pocket = OfflineTtsPocketModelConfig(
                                lmFlow = paths.lmFlow ?: "",
                                lmMain = paths.lmMain ?: "",
                                encoder = paths.encoder ?: "",
                                decoder = paths.decoder ?: "",
                                textConditioner = paths.textConditioner ?: "",
                                vocabJson = paths.vocabJson ?: "",
                                tokenScoresJson = paths.tokenScoresJson ?: ""
                        ),
                        numThreads = 2,
                        provider = "cpu"
                )

            // SupertonicTTS (durationPredictor + textEncoder + vectorEstimator + vocoder)
            SherpaModelType.TTS_SUPERTONIC ->
                OfflineTtsModelConfig(
                        supertonic = OfflineTtsSupertonicModelConfig(
                                durationPredictor = paths.durationPredictor ?: "",
                                textEncoder = paths.textEncoder ?: "",
                                vectorEstimator = paths.vectorEstimator ?: "",
                                vocoder = paths.vocoder ?: "",
                                ttsJson = paths.ttsJson ?: "",
                                unicodeIndexer = paths.unicodeIndexer ?: "",
                                voiceStyle = paths.voiceStyle ?: ""
                        ),
                        numThreads = 2,
                        provider = "cpu"
                )

            else -> {
                Log.w(TAG, "⚠️ Neznámý TTS typ: ${paths.type}")
                return null
            }
        }

        return OfflineTtsConfig(
                model = modelConfig,
                ruleFsts = "",
                maxNumSentences = 1
        )
    }

    private fun initAudioTrack(sampleRate: Int) {
        val minBufferSize =
                AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_FLOAT
                )

        audioTrack =
                AudioTrack(
                        AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build(),
                        AudioFormat.Builder()
                                .setSampleRate(sampleRate)
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build(),
                        minBufferSize * 2,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE
                )
    }

    fun speak(
            text: String,
            interrupt: Boolean = true,
            onStart: (() -> Unit)? = null,
            onDone: (() -> Unit)? = null
    ) {
        if (!isInitialized || tts == null) {
            if (!initialize()) {
                Log.e(TAG, "Nelze přehrát text, engine není inicializován.")
                onDone?.invoke()
                return
            }
        }

        if (text.isBlank()) {
            onDone?.invoke()
            return
        }

        if (interrupt) {
            stop()
        }

        speakJob =
                scope.launch {
                    try {
                        // OPTIMALIZACE: Extrémní snížení priority vlákna pro TTS generaci.
                        // Modely jako ZipVoice (flow-matching) spotřebovávají masivní CPU výkon.
                        // Tímto zajistíme, že STT (TranscriptAgent), který běží na URGENT_AUDIO,
                        // dostane vždy přednost a nebudou se ztrácet VAD framy.
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                        } catch (ignore: Exception) {}

                        isPlaying.set(true)
                        onStart?.invoke()
                        Log.d(TAG, "Generuji TTS (Sherpa): '$text'")

                        // Early exit check — pokud byl stop() volán mezi onStart a generací
                        if (!isPlaying.get()) {
                            Log.d(TAG, "TTS zastaveno před generací audia")
                            return@launch
                        }

                        val generatedAudio = if (currentTtsPaths?.type == SherpaModelType.TTS_ZIPVOICE) {
                            val zipDir = File(currentTtsPaths!!.encoder!!).parentFile

                            // 1. Zjištění cesty k prompt.wav (Custom vs Default)
                            val customPath = settingsManager.sherpaTtsCustomPromptPath
                            val promptFile = if (customPath.isNotBlank() && File(customPath).exists()) {
                                File(customPath)
                            } else {
                                File(zipDir, "prompt.wav")
                            }

                            if (promptFile.exists()) {
                                // 2. Zjištění referenčního textu (Custom -> prompt.txt -> Fallback)
                                val customText = settingsManager.sherpaTtsCustomPromptText
                                val textFile = File(promptFile.parentFile, "prompt.txt")
                                val refText = when {
                                    customText.isNotBlank() -> customText
                                    textFile.exists() -> textFile.readText(Charsets.UTF_8).trim()
                                    else -> ""
                                }

                                val waveData = WaveReader.readWave(promptFile.absolutePath)
                                val overrideSr = settingsManager.sherpaTtsZipvoiceSampleRate
                                val finalSr = if (overrideSr > 0) overrideSr else waveData.sampleRate

                                val config = GenerationConfig(
                                        speed = 1.0f,
                                        sid = 0,
                                        referenceAudio = waveData.samples,
                                        referenceSampleRate = finalSr,
                                        referenceText = refText
                                )
                                // --- CHUNKING ---
                                val chunkSize = settingsManager.sherpaTtsZipvoiceChunkSize
                                if (chunkSize == 0 || text.length <= chunkSize) {
                                    tts?.generateWithConfig(text, config)
                                } else {
                                    val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
                                    var combinedSamples = FloatArray(0)
                                    var currentSampleRate = finalSr
                                    for (sentence in sentences) {
                                        if (!isPlaying.get()) break
                                        val chunkAudio = tts?.generateWithConfig(sentence, config)
                                        if (chunkAudio != null) {
                                            currentSampleRate = chunkAudio.sampleRate
                                            val newArray = FloatArray(combinedSamples.size + chunkAudio.samples.size)
                                            System.arraycopy(combinedSamples, 0, newArray, 0, combinedSamples.size)
                                            System.arraycopy(chunkAudio.samples, 0, newArray, combinedSamples.size, chunkAudio.samples.size)
                                            combinedSamples = newArray
                                        }
                                    }
                                    if (combinedSamples.isNotEmpty()) {
                                        com.k2fsa.sherpa.onnx.GeneratedAudio(combinedSamples, currentSampleRate)
                                    } else null
                                }
                            } else {
                                Log.e(TAG, "Chyba: Pro model ZipVoice chybí referenční audio (${promptFile.absolutePath})!")
                                null
                            }
                        } else {
                            val overrideSr = settingsManager.sherpaTtsZipvoiceSampleRate
                            val finalSr = if (overrideSr > 0) overrideSr else 24000
                            // --- CHUNKING ---
                            val chunkSize = settingsManager.sherpaTtsZipvoiceChunkSize
                            if (chunkSize == 0 || text.length <= chunkSize) {
                                tts?.generate(text, 0, 1.0f)
                            } else {
                                val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
                                var combinedSamples = FloatArray(0)
                                var currentSampleRate = finalSr
                                for (sentence in sentences) {
                                    if (!isPlaying.get()) break
                                    val chunkAudio = tts?.generate(sentence, 0, 1.0f)
                                    if (chunkAudio != null) {
                                        currentSampleRate = chunkAudio.sampleRate
                                        val newArray = FloatArray(combinedSamples.size + chunkAudio.samples.size)
                                        System.arraycopy(combinedSamples, 0, newArray, 0, combinedSamples.size)
                                        System.arraycopy(chunkAudio.samples, 0, newArray, combinedSamples.size, chunkAudio.samples.size)
                                        combinedSamples = newArray
                                    }
                                }
                                if (combinedSamples.isNotEmpty()) {
                                    com.k2fsa.sherpa.onnx.GeneratedAudio(combinedSamples, currentSampleRate)
                                } else null
                            }
                        }

                        if (generatedAudio != null && generatedAudio.samples.isNotEmpty()) {
                            if (!isPlaying.get()) return@launch // Zastaveno uprostřed generování

                            audioTrack?.let { track ->
                                synchronized(audioLock) {
                                    if (track.state != AudioTrack.STATE_UNINITIALIZED && isPlaying.get()) {
                                        track.play()
                                        track.write(
                                                generatedAudio.samples,
                                                0,
                                                generatedAudio.samples.size,
                                                AudioTrack.WRITE_BLOCKING
                                        )
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Chyba během generování TTS audia: ${e.message}", e)
                    } finally {
                        isPlaying.set(false)
                        synchronized(audioLock) {
                            try {
                                audioTrack?.pause()
                                audioTrack?.flush()
                            } catch (e: Exception) {
                                Log.w(TAG, "AudioTrack cleanup error: \${e.message}")
                            }
                        }
                        onDone?.invoke()
                    }
                }
    }

    fun stop() {
        isPlaying.set(false)
        speakJob?.cancel()
        synchronized(audioLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "Chyba při zastavování AudioTrack: ${e.message}")
            }
        }
    }

    /**
     * Bezpečný shutdown: MUSÍ počkat na dokončení C++ inference PŘED uvolněním JNI objektu.
     *
     * KRITICKÉ: Nesmíme volat speakJob?.cancel()!
     * cancel() na coroutine ji označí jako "completed" → join() by se vrátil OKAMŽITĚ,
     * i když C++ generateWithConfig() stále běží na nativním vlákně.
     * Následné tts?.release() by zničilo mutexy, které C++ stále drží → SIGSEGV.
     *
     * Řešení:
     * 1. isPlaying=false → speak() coroutine po návratu z JNI nepřehraje audio a skončí
     * 2. Zastavit AudioTrack (zabránit přehrání)
     * 3. ČEKAT na dokončení JNI inference (join s timeoutem)
     * 4. TEPRVE PAK uvolnit C++ objekt
     */
    fun shutdown() {
        // 1. Signalizuj zastavení — ale NERUŠ coroutine (JNI call nelze přerušit)
        isPlaying.set(false)

        // 2. Zastav AudioTrack okamžitě (zabrání dalšímu přehrávání)
        synchronized(audioLock) {
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack cleanup error: ${e.message}")
            }
        }

        // 3. POČKAT na dokončení C++ inference — generateWithConfig() je blokující JNI call,
        // nelze ji přerušit. Musíme počkat, než se vrátí z nativního kódu.
        val activeJob = speakJob
        if (activeJob != null && activeJob.isActive) {
            Log.w(TAG, "⏳ Čekám na dokončení C++ TTS inference před release()...")
            try {
                runBlocking {
                    withTimeoutOrNull(15_000L) {
                        activeJob.join()
                    } ?: Log.e(TAG, "⚠️ Timeout (15s) čekání na TTS inference — vynucený release()")
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Chyba při čekání na TTS job: ${e.message}")
            }
        }
        speakJob = null

        // 4. TEPRVE TEĎ je bezpečné uvolnit C++ objekt — inference doběhla
        audioTrack?.release()
        audioTrack = null
        try {
            tts?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Sherpa TTS release error: ${e.message}")
        }
        tts = null
        isInitialized = false
        Log.i(TAG, "🔇 Sherpa TTS engine shutdown kompletní")
    }

    /**
     * Vygeneruje TTS audio a přímo ho uloží do WAV souboru bez přehrávání.
     */
    fun generateToFile(
        text: String,
        outputFile: File,
        osPriority: Int = android.os.Process.THREAD_PRIORITY_URGENT_AUDIO,
        onStart: (() -> Unit)? = null,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (!isInitialized || tts == null) {
            if (!initialize()) {
                onDone?.invoke(false)
                return
            }
        }
        if (text.isBlank()) {
            onDone?.invoke(false)
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                try {
                    android.os.Process.setThreadPriority(osPriority)
                } catch (ignore: Exception) {}

                onStart?.invoke()
                val generatedAudio = if (currentTtsPaths?.type == SherpaModelType.TTS_ZIPVOICE) {
                    val zipDir = java.io.File(currentTtsPaths!!.encoder!!).parentFile
                    val customPath = settingsManager.sherpaTtsCustomPromptPath
                    val promptFile = if (customPath.isNotBlank() && java.io.File(customPath).exists()) java.io.File(customPath) else java.io.File(zipDir, "prompt.wav")

                    if (promptFile.exists()) {
                        val customText = settingsManager.sherpaTtsCustomPromptText
                        val textFile = java.io.File(promptFile.parentFile, "prompt.txt")
                        val refText = when {
                            customText.isNotBlank() -> customText
                            textFile.exists() -> textFile.readText(Charsets.UTF_8).trim()
                            else -> ""
                        }
                        val waveData = com.k2fsa.sherpa.onnx.WaveReader.readWave(promptFile.absolutePath)
                        val overrideSr = settingsManager.sherpaTtsZipvoiceSampleRate
                        val finalSr = if (overrideSr > 0) overrideSr else waveData.sampleRate
                        val config = com.k2fsa.sherpa.onnx.GenerationConfig(
                            speed = 1.0f,
                            sid = 0,
                            referenceAudio = waveData.samples,
                            referenceSampleRate = finalSr,
                            referenceText = refText
                        )
                        // --- CHUNKING ---
                        val chunkSize = settingsManager.sherpaTtsZipvoiceChunkSize
                        if (chunkSize == 0 || text.length <= chunkSize) {
                            tts?.generateWithConfig(text, config)
                        } else {
                            val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
                            var combinedSamples = FloatArray(0)
                            var currentSampleRate = finalSr
                            for (sentence in sentences) {
                                val chunkAudio = tts?.generateWithConfig(sentence, config)
                                if (chunkAudio != null) {
                                    currentSampleRate = chunkAudio.sampleRate
                                    val newArray = FloatArray(combinedSamples.size + chunkAudio.samples.size)
                                    System.arraycopy(combinedSamples, 0, newArray, 0, combinedSamples.size)
                                    System.arraycopy(chunkAudio.samples, 0, newArray, combinedSamples.size, chunkAudio.samples.size)
                                    combinedSamples = newArray
                                }
                            }
                            if (combinedSamples.isNotEmpty()) {
                                com.k2fsa.sherpa.onnx.GeneratedAudio(combinedSamples, currentSampleRate)
                            } else null
                        }
                    } else null
                } else {
                    val overrideSr = settingsManager.sherpaTtsZipvoiceSampleRate
                    val finalSr = if (overrideSr > 0) overrideSr else 24000
                    // --- CHUNKING ---
                    val chunkSize = settingsManager.sherpaTtsZipvoiceChunkSize
                    if (chunkSize == 0 || text.length <= chunkSize) {
                        tts?.generate(text, 0, 1.0f)
                    } else {
                        val sentences = text.split(Regex("(?<=[.!?\\n])\\s+")).filter { it.isNotBlank() }
                        var combinedSamples = FloatArray(0)
                        var currentSampleRate = finalSr
                        for (sentence in sentences) {
                            val chunkAudio = tts?.generate(sentence, 0, 1.0f)
                            if (chunkAudio != null) {
                                currentSampleRate = chunkAudio.sampleRate
                                val newArray = FloatArray(combinedSamples.size + chunkAudio.samples.size)
                                System.arraycopy(combinedSamples, 0, newArray, 0, combinedSamples.size)
                                System.arraycopy(chunkAudio.samples, 0, newArray, combinedSamples.size, chunkAudio.samples.size)
                                combinedSamples = newArray
                            }
                        }
                        if (combinedSamples.isNotEmpty()) {
                            com.k2fsa.sherpa.onnx.GeneratedAudio(combinedSamples, currentSampleRate)
                        } else null
                    }
                }

                if (generatedAudio != null && generatedAudio.samples.isNotEmpty()) {
                    writeWav(outputFile, generatedAudio.samples, generatedAudio.sampleRate)
                    onDone?.invoke(true)
                } else {
                    onDone?.invoke(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při ukládání klonu: ${e.message}", e)
                onDone?.invoke(false)
            }
        }
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val shortSamples = ShortArray(samples.size) { (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort() }
        val byteRate = sampleRate * 2
        val totalAudioLen = shortSamples.size * 2L
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte(); header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0; header[22] = 1; header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte(); header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte(); header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
        header[42] = ((totalAudioLen shr 16) and 0xff).toByte(); header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

        java.io.FileOutputStream(file).use { out ->
            out.write(header)
            val buffer = java.nio.ByteBuffer.allocate(shortSamples.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buffer.asShortBuffer().put(shortSamples)
            out.write(buffer.array())
        }
    }
}
