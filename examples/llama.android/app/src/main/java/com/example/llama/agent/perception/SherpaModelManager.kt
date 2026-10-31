package com.example.llama.agent.perception

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.llama.SettingsManager
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SherpaModelType {
    // === STREAMING (Online) ===
    STT_ONLINE_TRANSDUCER,       // Streaming Transducer (encoder+decoder+joiner)
    STT_ONLINE_CTC,              // Streaming CTC (sherpa-onnx-streaming-zipformer-*)

    // === BATCH (Offline) ===
    STT_OFFLINE_TRANSDUCER,      // Offline Transducer (NeMo Parakeet TDT)
    STT_OFFLINE_CTC,             // Offline CTC / Paraformer (single model.onnx)
    STT_OFFLINE_CTC_ZIPFORMER,   // Offline Zipformer CTC (sherpa-onnx-zipformer-ctc-zh-*)
    STT_OFFLINE_SENSEVOICE,      // Offline SenseVoice (emotion + lang detection, FunAudioLLM)
    STT_OFFLINE_WHISPER,         // Offline Whisper (encoder + decoder, OpenAI)
    STT_OFFLINE_MOONSHINE,       // Offline Moonshine (preprocessor+encoder+decoder, Useful Sensors)
    STT_OFFLINE_NEMO_CTC,        // Offline NeMo EncDecCTC (single model.onnx, NVIDIA)
    STT_OFFLINE_OMNILINGUAL,     // Offline Omnilingual ASR CTC (Meta AI, 1600+ jazyků)
    STT_OFFLINE_QWEN,            // Offline Qwen-ASR
    STT_OFFLINE_GIGA_AM,         // Offline GigaAM (Sber)
    STT_OFFLINE_VOSK,            // Offline Vosk
    STT_OFFLINE_TELE_SPEECH,     // Offline TeleSpeech
    STT_OFFLINE_FUNASR_NANO,     // Offline FunASR / Nano

    // === TTS ===
    TTS_PIPER,                   // VITS Piper (espeak-ng-data, single model.onnx)
    TTS_VITS_STANDARD,           // VITS standard (lexicon.txt)
    TTS_KOKORO,                  // Kokoro TTS (voices.bin, data_dir)
    TTS_MATCHA,                  // Matcha TTS (acoustic_model + vocoder)
    TTS_ZIPVOICE,                // ZipVoice TTS (encoder+decoder+vocoder, flow-matching)
    TTS_POCKET,                  // PocketTTS (lmFlow+lmMain+encoder+decoder+textConditioner)
    TTS_SUPERTONIC,              // SupertonicTTS (durationPredictor+textEncoder+vectorEstimator+vocoder)
    TTS_KITTEN,                  // KittenTTS (model+voices, VITS-like)

    // === Ostatní ===
    VAD,
    UNKNOWN;

    /** Vrací true pokud je tento model STT typu (jakéhokoliv). */
    val isStt: Boolean
        get() = when (this) {
            STT_ONLINE_TRANSDUCER, STT_ONLINE_CTC,
            STT_OFFLINE_TRANSDUCER, STT_OFFLINE_CTC, STT_OFFLINE_CTC_ZIPFORMER,
            STT_OFFLINE_SENSEVOICE, STT_OFFLINE_WHISPER, STT_OFFLINE_MOONSHINE,
            STT_OFFLINE_NEMO_CTC, STT_OFFLINE_OMNILINGUAL,
            STT_OFFLINE_QWEN, STT_OFFLINE_GIGA_AM, STT_OFFLINE_VOSK,
            STT_OFFLINE_TELE_SPEECH, STT_OFFLINE_FUNASR_NANO -> true
            else -> false
        }

    /** Vrací true pokud je tento model TTS typu. */
    val isTts: Boolean
        get() = when (this) {
            TTS_PIPER, TTS_VITS_STANDARD, TTS_KOKORO, TTS_MATCHA,
            TTS_ZIPVOICE, TTS_POCKET, TTS_SUPERTONIC, TTS_KITTEN -> true
            else -> false
        }

    /** Vrací true pokud je tento model Transducer typu (encoder+decoder+joiner). */
    val isTransducer: Boolean
        get() = this == STT_ONLINE_TRANSDUCER || this == STT_OFFLINE_TRANSDUCER

    /** Vrací true pokud je tento model Online (streaming). */
    val isOnline: Boolean
        get() = this == STT_ONLINE_TRANSDUCER || this == STT_ONLINE_CTC

    /** Vrací true pokud je tento model Offline (batch). */
    val isOffline: Boolean
        get() = when (this) {
            STT_OFFLINE_TRANSDUCER, STT_OFFLINE_CTC, STT_OFFLINE_CTC_ZIPFORMER,
            STT_OFFLINE_SENSEVOICE, STT_OFFLINE_WHISPER, STT_OFFLINE_MOONSHINE,
            STT_OFFLINE_NEMO_CTC, STT_OFFLINE_OMNILINGUAL -> true
            else -> false
        }
}

/**
 * Cesty pro STT engine.
 * Pole decoder2 a preprocessor slouží pro Whisper/Moonshine (multi-file modely).
 */
data class SherpaSttPaths(
        val type: SherpaModelType,
        val tokens: String,
        val encoder: String? = null,
        val decoder: String? = null,      // Whisper: decoder.onnx / Transducer: decoder.onnx
        val joiner: String? = null,       // Transducer: joiner.onnx
        val model: String? = null,        // CTC/Paraformer/SenseVoice/NeMo/Omnilingual: model.onnx
        val decoder2: String? = null,     // Moonshine: cached_decoder.onnx
        val preprocessor: String? = null  // Moonshine: preprocess.onnx
)

data class SherpaTtsPaths(
        val type: SherpaModelType,
        val model: String,
        val tokens: String,
        val dataDir: String? = null,  // Piper/Kokoro/ZipVoice: espeak-ng-data
        val lexicon: String? = null,  // VITS standard / ZipVoice
        val model2: String? = null,   // Matcha: vocoder.onnx
        // === ZipVoice / flow-matching TTS specifická pole ===
        val encoder: String? = null,  // ZipVoice/Pocket/Supertonic: encoder.onnx
        val decoder: String? = null,  // ZipVoice/Pocket: decoder.onnx
        val vocoder: String? = null,  // ZipVoice/Matcha/Supertonic: vocoder.onnx
        // === PocketTTS specifická pole ===
        val lmFlow: String? = null,
        val lmMain: String? = null,
        val textConditioner: String? = null,
        val vocabJson: String? = null,
        val tokenScoresJson: String? = null,
        // === SupertonicTTS specifická pole ===
        val durationPredictor: String? = null,
        val textEncoder: String? = null,
        val vectorEstimator: String? = null,
        val ttsJson: String? = null,
        val unicodeIndexer: String? = null,
        val voiceStyle: String? = null,
        // === KittenTTS specifická pole ===
        val voices: String? = null
)

data class SherpaModelInfo(
        val id: String,
        val name: String,
        val type: SherpaModelType,
        val path: String,
        val sizeBytes: Long
)

// ============================================================================
// SHERPA HEURISTIC PARSER
// Rekurzivně analyzuje složku modelu a detekuje typ + cesty ke všem souborům.
// Používá walkTopDown() → podporuje hluboké struktury (model/weights/encoder.onnx).
// Toto je SINGLE SOURCE OF TRUTH pro detekci — getSttModelPaths() i
// importModelFromTreeUri() delegují sem.
// ============================================================================

object SherpaHeuristicParser {

    private const val TAG = "SherpaParser"

    // ══════════════════════════════════════════════════════════════════════
    // IN-MEMORY CACHE: Prevence opakovaného walkTopDown() nad flash storage.
    // walkTopDown() nad ZipVoice modelem (170+ souborů v espeak-ng-data/)
    // generuje masivní I/O pressure, která degraduje paralelní C++ STT inference.
    // Cache se invaliduje POUZE při importu nebo smazání modelu.
    // ══════════════════════════════════════════════════════════════════════
    private data class CachedAnalysis(
            val result: Any?,              // SherpaSttPaths / SherpaTtsPaths / null
            val sizeBytes: Long,           // Celková velikost modelu (cachovaná → žádný další walkTopDown)
            val lastModified: Long         // Timestamp nejnovějšího souboru (detekce změn)
    )
    private val analysisCache = mutableMapOf<String, CachedAnalysis>()

    /**
     * Invaliduje celou cache. Volat PO importu nebo smazání modelu.
     * Bezpečné volat odkudkoli — další analyze() provede čerstvý walkTopDown().
     */
    fun invalidateCache() {
        val count = analysisCache.size
        analysisCache.clear()
        Log.i(TAG, "🧹 Parser cache invalidována ($count položek)")
    }

    /** Invaliduje cache pro konkrétní adresář modelu. */
    fun invalidateCache(dirPath: String) {
        analysisCache.remove(dirPath)
        Log.d(TAG, "🧹 Cache invalidována pro: $dirPath")
    }

    /** Vrací cachovanou velikost modelu (sizeBytes) bez dalšího I/O. */
    fun getCachedSizeBytes(dir: File): Long {
        return analysisCache[dir.absolutePath]?.sizeBytes ?: -1L
    }

    /**
     * Analyzuje složku modelu a vrátí SherpaSttPaths, SherpaTtsPaths nebo null.
     * Prohledává rekurzivně přes walkTopDown() — výsledek se cachuje v RAM.
     * Opakovaná volání pro stejnou složku NEPROVEDOU žádné I/O.
     */
    fun analyze(dir: File): Any? {
        if (!dir.exists() || !dir.isDirectory) return null

        // ── CACHE HIT: vrať cachovaný výsledek bez I/O ──────────────────
        val cacheKey = dir.absolutePath
        analysisCache[cacheKey]?.let { cached ->
            Log.d(TAG, "⚡ Cache hit pro: ${dir.name} (typ=${cached.result?.javaClass?.simpleName ?: "null"})")
            return cached.result
        }
        Log.d(TAG, "📀 Cache miss pro: ${dir.name} — provádím walkTopDown()")

        val allFiles = dir.walkTopDown().filter { it.isFile }.toList()
        val folderLower = dir.name.lowercase()

        // ── Výpočet sizeBytes + lastModified pro cache (jednoprůchodově, žádný další walkTopDown) ──
        var totalSizeBytes = 0L
        var latestModified = 0L
        allFiles.forEach { f ->
            totalSizeBytes += f.length()
            val mod = f.lastModified()
            if (mod > latestModified) latestModified = mod
        }

        // ── Cache-through helper: uloží výsledek do cache a vrátí ho ──────
        // Použít místo přímého `return` → `return cacheAndReturn(result)`
        fun cacheAndReturn(result: Any?): Any? {
            analysisCache[cacheKey] = CachedAnalysis(
                    result = result,
                    sizeBytes = totalSizeBytes,
                    lastModified = latestModified
            )
            return result
        }



        // === DIAGNOSTIKA — loguje strukturu složky pro debugging ===
        val onnxInFolder = allFiles.filter { it.name.endsWith(".onnx") }.map { it.name }
        val hasTxt = allFiles.filter { it.extension == "txt" }.map { it.name }
        Log.d(TAG, "📁 Analyzuji: ${dir.name} | onnx=${onnxInFolder} | txt=$hasTxt | size=${totalSizeBytes / 1024}KB")

        // Pomocná funkce: najde soubor dle přesného jména (case-insensitive)
        fun find(vararg names: String) = allFiles.find { f ->
            names.any { n -> f.name.equals(n, ignoreCase = true) }
        }

        // Najde ONNX soubory dle klíčového slova v názvu.
        // Safe-Model logika: preferuje čistý .onnx, fallback na .int8.onnx.
        // Důvod: uživatel může mít ve složce obě varianty (.onnx i .int8.onnx).
        // Bez preference by se vzal první abecedně — .int8 je abecedně před čistým.
        fun findContains(vararg keywords: String): File? {
            val candidates = allFiles.filter { f ->
                keywords.any { k -> f.name.contains(k, ignoreCase = true) && f.name.endsWith(".onnx") }
            }
            // 1. Preferuj čistý model (bez .int8 nebo .int16 v názvu)
            return candidates.firstOrNull { !it.name.contains(".int8") && !it.name.contains(".int16") }
                    ?: candidates.firstOrNull() // 2. Fallback: jakákoliv .onnx varianta
        }

        fun findAll(vararg keywords: String) = allFiles.filter { f ->
            keywords.any { k -> f.name.contains(k, ignoreCase = true) && f.name.endsWith(".onnx") }
        }

        // Pro single-model soubory: vrátí preferovanou variantu (.onnx před .int8.onnx)
        fun bestSingleOnnx(): File? {
            val onnxFiles = allFiles.filter { it.name.endsWith(".onnx") }
            return onnxFiles.firstOrNull { !it.name.contains(".int8") && !it.name.contains(".int16") }
                    ?: onnxFiles.firstOrNull()
        }

        val tokensPath = find("tokens.txt")?.absolutePath
        val lexiconPath = find("lexicon.txt")?.absolutePath

        // ── Společné ONNX soubory (použité TTS i STT detekcí) ───────────────
        val encoderFile = allFiles.firstOrNull { f ->
            f.name.endsWith(".onnx") && f.name.contains(Regex("(?i)(^|[-_])enc(oder)?(?=[-_.]|$)"))
        } ?: findContains("encoder")
        val decoderFile = allFiles.firstOrNull { f ->
            f.name.endsWith(".onnx") && f.name.contains(Regex("(?i)(^|[-_])dec(oder)?(?=[-_.]|$)")) &&
            !f.name.contains("uncached", ignoreCase = true) && !f.name.contains("cached", ignoreCase = true)
        } ?: findContains("decoder")
        val joinerFile  = findContains("joiner")

        // ── Detekce přítomnosti vocoder/vocos souboru (klíčový TTS diferenciátor) ─────
        // "vocoder" = obecný název, "vocos" = ZipVoice vocoder, "hifigan" = Matcha vocoder
        val vocoderFile = findContains("vocoder", "vocos", "hifigan")
        val espeakDir = dir.walkTopDown().firstOrNull { it.isDirectory && it.name == "espeak-ng-data" }

        // ── TTS: ZIPVOICE detekce (flow-matching) ─────────────────────────────
        // MUSÍ předcházet Piper i Whisper! ZipVoice soubory se překrývají se STT.
        //
        // TTS-specifické indikátory (NIKDY se nevyskytují v STT modelech):
        //   - "text_encoder" v názvu → TTS text encoder (STT má jen "encoder.onnx")
        //   - "fm_decoder" v názvu → "fm" = flow matching = výhradně TTS
        //   - "vocoder" v názvu → vocoder = výhradně TTS
        //   - folder name "zipvoice" → explicitní název modelu
        //
        // Varianta 1: vocoder přítomen + encoder + decoder (klasická 3-soubor architektura)
        // Varianta 2: text_encoder + fm_decoder (ZipVoice bez vocoder)
        // Varianta 3: folder name "zipvoice" + encoder + decoder
        val textEncoderTtsFile = findContains("text_encoder", "text-encoder")
        val fmDecoderFile = findContains("fm_decoder", "fm-decoder")
        val isZipVoiceByFiles = (vocoderFile != null && encoderFile != null && decoderFile != null && joinerFile == null) ||
                (textEncoderTtsFile != null && fmDecoderFile != null) ||
                (folderLower.contains("zipvoice") && encoderFile != null && decoderFile != null && joinerFile == null)
        if (isZipVoiceByFiles) {
            val zipEncoder = textEncoderTtsFile ?: encoderFile
            val zipDecoder = fmDecoderFile ?: decoderFile
            val effectiveTokens = tokensPath ?: ""
            Log.i(TAG, "🎤 Detekován TTS_ZIPVOICE model (flow-matching) " +
                    "[encoder=${zipEncoder?.name}, decoder=${zipDecoder?.name}, vocoder=${vocoderFile?.name}]")

            return cacheAndReturn(SherpaTtsPaths(
                    type = SherpaModelType.TTS_ZIPVOICE,
                    model = zipEncoder?.absolutePath ?: "",
                    tokens = effectiveTokens,
                    encoder = zipEncoder?.absolutePath ?: "",
                    decoder = zipDecoder?.absolutePath ?: "",
                    vocoder = vocoderFile?.absolutePath,
                    dataDir = espeakDir?.absolutePath,
                    lexicon = lexiconPath
            ))
        }

        // ── TTS: SUPERTONIC (durationPredictor + textEncoder + vectorEstimator + vocoder) ──
        val durationPredictorFile = findContains("duration_predictor", "duration-predictor")
        val textEncoderFile = findContains("text_encoder", "text-encoder")
        val vectorEstimatorFile = findContains("vector_estimator", "vector-estimator")
        val ttsJsonFile = find("tts.json")
        if (durationPredictorFile != null && textEncoderFile != null && vectorEstimatorFile != null) {
            val unicodeIndexerFile = find("unicode_indexer.json", "unicode-indexer.json")
            val voiceStyleFile = find("voice_style.json", "voice-style.json")
            Log.i(TAG, "🎤 Detekován TTS_SUPERTONIC model")
            return cacheAndReturn(SherpaTtsPaths(
                    type = SherpaModelType.TTS_SUPERTONIC,
                    model = durationPredictorFile.absolutePath,
                    tokens = "", // Supertonic nepoužívá tokens.txt
                    durationPredictor = durationPredictorFile.absolutePath,
                    textEncoder = textEncoderFile.absolutePath,
                    vectorEstimator = vectorEstimatorFile.absolutePath,
                    vocoder = vocoderFile?.absolutePath,
                    ttsJson = ttsJsonFile?.absolutePath,
                    unicodeIndexer = unicodeIndexerFile?.absolutePath,
                    voiceStyle = voiceStyleFile?.absolutePath
            ))
        }

        // ── TTS: POCKET (lmFlow + lmMain + encoder + decoder + textConditioner + vocab.json) ──
        val vocabJsonFile = find("vocab.json")
        val tokenScoresJsonFile = find("token_scores.json", "token-scores.json")
        val lmFlowFile = findContains("lm_flow", "lm-flow")
        val lmMainFile = findContains("lm_main", "lm-main")
        val textConditionerFile = findContains("text_conditioner", "text-conditioner")
        if (vocabJsonFile != null && lmFlowFile != null && lmMainFile != null) {
            Log.i(TAG, "🎤 Detekován TTS_POCKET model")
            return cacheAndReturn(SherpaTtsPaths(
                    type = SherpaModelType.TTS_POCKET,
                    model = lmFlowFile.absolutePath,
                    tokens = "", // Pocket nepoužívá tokens.txt
                    encoder = encoderFile?.absolutePath,
                    decoder = decoderFile?.absolutePath,
                    lmFlow = lmFlowFile.absolutePath,
                    lmMain = lmMainFile.absolutePath,
                    textConditioner = textConditionerFile?.absolutePath,
                    vocabJson = vocabJsonFile.absolutePath,
                    tokenScoresJson = tokenScoresJsonFile?.absolutePath
            ))
        }

        // ── TTS: PIPER (espeak-ng-data + single .onnx, BEZ vocoder) ──────────
        // Piper NEMÁ vocoder.onnx — to je klíčový rozdíl od ZipVoice.
        if (espeakDir != null && vocoderFile == null) {
            val piperModel = bestSingleOnnx()
            if (piperModel != null && tokensPath != null) {
                // Kontrola na KittenTTS: má voices.bin/voices.dat v espeak-ng-data modelu
                val kittenVoices = find("voices.bin", "voices.dat")
                if (kittenVoices != null) {
                    Log.i(TAG, "🎤 Detekován TTS_KITTEN model")
                    return cacheAndReturn(SherpaTtsPaths(
                            type = SherpaModelType.TTS_KITTEN,
                            model = piperModel.absolutePath,
                            tokens = tokensPath,
                            dataDir = espeakDir.absolutePath,
                            voices = kittenVoices.absolutePath
                    ))
                }
                Log.i(TAG, "🎤 Detekován TTS_PIPER model")
                return cacheAndReturn(SherpaTtsPaths(
                        type = SherpaModelType.TTS_PIPER,
                        model = piperModel.absolutePath,
                        tokens = tokensPath,
                        dataDir = espeakDir.absolutePath
                ))
            }
        }

        // ── TTS: KOKORO (voices.bin přítomen, BEZ espeak-ng-data) ─────────────
        // Kitten byl již detekován výše (espeak-ng-data + voices.bin).
        // Kokoro: voices.bin + model.onnx + tokens, BEZ espeak-ng-data.
        val voicesBin = find("voices.bin")
        if (voicesBin != null && espeakDir == null) {
            val kokoroModel = bestSingleOnnx()
            if (kokoroModel != null && tokensPath != null) {
                Log.i(TAG, "🎤 Detekován TTS_KOKORO model")
                return cacheAndReturn(SherpaTtsPaths(
                        type = SherpaModelType.TTS_KOKORO,
                        model = kokoroModel.absolutePath,
                        tokens = tokensPath,
                        dataDir = voicesBin.parent
                ))
            }
        }

        // ── TTS: MATCHA (přítomen acoustic_model + vocoder klíč v názvech) ──
        val matchaAcoustic = findContains("acoustic", "matcha", "model-steps")
        val matchaVocoder = findContains("vocoder", "hifigan")
        if (matchaAcoustic != null && (matchaVocoder != null || folderLower.contains("matcha")) && tokensPath != null) {
            Log.i(TAG, "🎤 Detekován TTS_MATCHA model")
            return cacheAndReturn(SherpaTtsPaths(
                    type = SherpaModelType.TTS_MATCHA,
                    model = matchaAcoustic.absolutePath,
                    tokens = tokensPath,
                    model2 = matchaVocoder?.absolutePath,
                    lexicon = lexiconPath,
                    dataDir = espeakDir?.absolutePath
            ))
        }

        // ── TTS: VITS STANDARD (lexicon.txt přítomen, žádný encoder soubor) ──
        val hasEncoderFile = allFiles.any { it.name.contains("encoder", ignoreCase = true) && it.name.endsWith(".onnx") }
        if (lexiconPath != null && tokensPath != null && !hasEncoderFile) {
            val vitsModel = bestSingleOnnx()
            if (vitsModel != null) {
                Log.i(TAG, "🎤 Detekován TTS_VITS_STANDARD model")
                return cacheAndReturn(SherpaTtsPaths(
                        type = SherpaModelType.TTS_VITS_STANDARD,
                        model = vitsModel.absolutePath,
                        tokens = tokensPath,
                        lexicon = lexiconPath
                ))
            }
        }

        // Od tohoto místa: všechny STT modely vyžadují tokens.txt
        if (tokensPath == null) {
            Log.w(TAG, "⚠️ Nebyl nalezen tokens.txt ve složce ${dir.name}")
            return cacheAndReturn(null)
        }

        // ── STT: TRANSDUCER (encoder + decoder + joiner .onnx) ───────────────
        // encoderFile, decoderFile, joinerFile již deklarovány výše před TTS sekcí
        if (encoderFile != null && decoderFile != null && joinerFile != null) {
            val isOffline = folderLower.contains("offline") ||
                    folderLower.contains("nemo") ||
                    folderLower.contains("non-streaming") ||
                    folderLower.contains("nonstreaming") ||
                    folderLower.contains("vosk")

            val type = when {
                folderLower.contains("vosk") -> SherpaModelType.STT_OFFLINE_VOSK
                isOffline -> SherpaModelType.STT_OFFLINE_TRANSDUCER
                else -> SherpaModelType.STT_ONLINE_TRANSDUCER
            }
            Log.i(TAG, "🔍 Detekován $type model")
            return cacheAndReturn(SherpaSttPaths(
                    type = type,
                    tokens = tokensPath,
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath,
                    joiner = joinerFile.absolutePath
            ))
        }

        // ── STT: MOONSHINE (preprocessor + encoder + uncached_decoder + cached_decoder) ──
        val preprocessorFile = allFiles.find { it.name.contains("preprocess", ignoreCase = true) && it.name.endsWith(".onnx") }
        val uncachedDecoderFile = allFiles.find { it.name.contains("uncached", ignoreCase = true) && it.name.endsWith(".onnx") }
        val cachedDecoderFile = allFiles.find { it.name.contains("cached", ignoreCase = true) && it.name.endsWith(".onnx") }
        if (preprocessorFile != null && encoderFile != null &&
                (uncachedDecoderFile != null || cachedDecoderFile != null)) {
            Log.i(TAG, "🔍 Detekován STT_OFFLINE_MOONSHINE model")
            return cacheAndReturn(SherpaSttPaths(
                    type = SherpaModelType.STT_OFFLINE_MOONSHINE,
                    tokens = tokensPath,
                    encoder = encoderFile.absolutePath,
                    decoder = uncachedDecoderFile?.absolutePath,
                    decoder2 = cachedDecoderFile?.absolutePath,
                    preprocessor = preprocessorFile.absolutePath
            ))
        }

        // ── STT: WHISPER (encoder + decoder, žádný joiner, žádný vocoder) ─────
        // KRITICKÉ: ZipVoice odfiltrován výše (vocoder/text_encoder/fm_decoder/folder name).
        // Safety net: vyloučení TTS-specifických názvů pro případ nových variant.
        val encoderIsTtsSpecific = encoderFile?.name?.contains("text_encoder", ignoreCase = true) == true
        val decoderIsTtsSpecific = decoderFile?.name?.contains("fm_decoder", ignoreCase = true) == true ||
                decoderFile?.name?.contains("fm-decoder", ignoreCase = true) == true
        if (encoderFile != null && decoderFile != null && vocoderFile == null &&
                !encoderIsTtsSpecific && !decoderIsTtsSpecific) {
            Log.i(TAG, "🔍 Detekován STT_OFFLINE_WHISPER model")
            return cacheAndReturn(SherpaSttPaths(
                    type = SherpaModelType.STT_OFFLINE_WHISPER,
                    tokens = tokensPath,
                    encoder = encoderFile.absolutePath,
                    decoder = decoderFile.absolutePath
            ))
        }

        // ── STT: Single-model .onnx — identifikace dle názvu složky/souboru ──
        // bestSingleOnnx() preferuje čistý .onnx před .int8.onnx (safe-model logika)
        val singleOnnx = bestSingleOnnx()
        if (singleOnnx != null) {
            val nameLower = singleOnnx.name.lowercase()
            val type = when {
                // OMNILINGUAL (Meta AI, wav2vec2+CTC)
                folderLower.contains("omnilingual") ->
                    SherpaModelType.STT_OFFLINE_OMNILINGUAL
                // QWEN-ASR
                folderLower.contains("qwen") || nameLower.contains("qwen") ->
                    SherpaModelType.STT_OFFLINE_QWEN
                // FUNASR NANO
                folderLower.contains("nano") || nameLower.contains("nano") ->
                    SherpaModelType.STT_OFFLINE_FUNASR_NANO
                // TELESPEECH
                folderLower.contains("telespeech") || nameLower.contains("telespeech") ->
                    SherpaModelType.STT_OFFLINE_TELE_SPEECH
                // GIGA-AM
                folderLower.contains("gigaam") || nameLower.contains("gigaam") || folderLower.contains("giga-am") ->
                    SherpaModelType.STT_OFFLINE_GIGA_AM
                // VOSK
                folderLower.contains("vosk") || nameLower.contains("vosk") ->
                    SherpaModelType.STT_OFFLINE_VOSK
                // SENSEVOICE (FunAudioLLM, emotion + lang detection)
                folderLower.contains("sensevoice") || nameLower.contains("sensevoice") ->
                    SherpaModelType.STT_OFFLINE_SENSEVOICE
                // NEMO CTC (NVIDIA NeMo EncDecCTC)
                folderLower.contains("nemo") || nameLower.contains("nemo") ||
                folderLower.contains("parakeet") || nameLower.contains("citrinet") ->
                    SherpaModelType.STT_OFFLINE_NEMO_CTC
                // ZIPFORMER CTC (offline, bez streaming v názvu)
                (folderLower.contains("zipformer") || nameLower.contains("zipformer")) &&
                folderLower.contains("ctc") && !folderLower.contains("streaming") ->
                    SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER
                // PARAFORMER
                folderLower.contains("paraformer") || nameLower.contains("paraformer") ->
                    SherpaModelType.STT_OFFLINE_CTC
                // STREAMING ONLINE CTC (zipformer bez offline/ctc, nebo explicitně streaming)
                folderLower.contains("streaming") ||
                (folderLower.contains("zipformer") && !folderLower.contains("offline") && !folderLower.contains("ctc")) ->
                    SherpaModelType.STT_ONLINE_CTC
                // DEFAULT: Offline CTC (bezpečný fallback pro neznámé single-model offline)
                else -> SherpaModelType.STT_OFFLINE_CTC
            }
            Log.i(TAG, "🔍 Detekován $type model: ${dir.name} / ${singleOnnx.name}")
            return cacheAndReturn(SherpaSttPaths(
                    type = type,
                    tokens = tokensPath,
                    model = singleOnnx.absolutePath
            ))
        }

        Log.w(TAG, "⚠️ Nelze detekovat typ Sherpa modelu ve složce: ${dir.name}")
        return cacheAndReturn(null)
    }
}

/**
 * Třída pro správu modelů Sherpa-ONNX. Podporuje automatickou detekci všech modelů z balíčku
 * k2-fsa.
 */
class SherpaModelManager(
        private val context: Context,
        private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "SherpaModelManager"
        private const val MODELS_DIR = "sherpa_models"
        /** Sdílený adresář pro vocodery a další TTS komponenty sdílené napříč hlasy. */
        private const val SHARED_DIR = "_shared"
    }

    private fun getBaseModelDir(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Vrací adresář pro sdílené TTS komponenty (vocodery).
     * Cesta: /data/.../sherpa_models/_shared/
     * Vytvořen automaticky pokud neexistuje.
     */
    fun getSharedDir(): File {
        val dir = File(getBaseModelDir(), SHARED_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Vrací cestu k prázdnému lexicon.txt souboru.
     * Nutné pro Sherpa-ONNX ZipVoice modely, které nemají lexicon a jinak by způsobily SIGABRT.
     */
    fun getDummyLexiconPath(): String {
        val dummyFile = File(getSharedDir(), "dummy_lexicon.txt")
        if (!dummyFile.exists()) {
            dummyFile.writeText("") // Vytvoří prázdný soubor
        }
        return dummyFile.absolutePath
    }

    /**
     * Vrací cestu k prázdnému adresáři espeak-ng-data.
     * Nutné pro modely využívající matcha-tts-lexicon.cc (ZipVoice, Matcha), pokud nemají espeak-ng-data.
     */
    fun getDummyEspeakDataDir(): String {
        val dummyDir = File(getSharedDir(), "dummy_espeak_ng_data")
        if (!dummyDir.exists()) {
            dummyDir.mkdirs()
        }
        return dummyDir.absolutePath
    }

    /**
     * CD-Player Strategy: Resolving sdíleného vocoderu.
     *
     * Prioritní řetězec:
     *   1. SettingsManager.sherpaSharedVocoderPath (explicitně nastavený uživatelem)
     *   2. _shared/ adresář — auto-discovery dle vzoru pro danou TTS rodinu
     *   3. assets/ — bundlovaný vocoder → extrakce do _shared/ (jednorázová kopie)
     *   4. null (vocoder nenalezen → graceful error, ne crash)
     *
     * @param family Klíč TTS rodiny (např. "vocos" pro ZipVoice, "hifigan" pro Matcha)
     * @return Absolutní cesta k vocoder souboru nebo null
     */
    fun resolveSharedVocoder(family: String): String? {
        // 1. Explicitní cesta z SettingsManager
        val explicitPath = settingsManager.sherpaSharedVocoderPath
        if (explicitPath.isNotEmpty()) {
            val f = File(explicitPath)
            if (f.exists() && f.name.contains(family, ignoreCase = true)) {
                Log.i(TAG, "🔗 Sdílený vocoder z nastavení: ${f.name}")
                return f.absolutePath
            }
            if (f.exists()) {
                Log.w(TAG, "⚠️ Sdílený vocoder nalezen ($explicitPath), ale neshoduje se s rodinou '$family'. Použit jako fallback.")
                return f.absolutePath
            }
        }



        // 2. Auto-discovery v _shared/ adresáři
        val sharedDir = getSharedDir()
        val vocoderFile = sharedDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name.endsWith(".onnx") &&
                    file.name.contains(family, ignoreCase = true)
        }
        if (vocoderFile != null) {
            Log.i(TAG, "🔗 Sdílený vocoder auto-nalezen v _shared/: ${vocoderFile.name}")
            return vocoderFile.absolutePath
        }

        // 3. Asset extraction — bundlovaný vocoder v APK assets/sherpa_shared/
        val extractedPath = extractVocoderFromAssets(family)
        if (extractedPath != null) {
            Log.i(TAG, "📦 Sdílený vocoder extrahován z assets: ${File(extractedPath).name}")
            return extractedPath
        }

        // 4. Nenalezen
        Log.w(TAG, "⚠️ Sdílený vocoder '$family' nenalezen (settings / _shared / assets)")
        return null
    }

    /**
     * Extrahuje vocoder z APK assets do _shared/ adresáře.
     * Jednorázová operace — po první extrakci se soubor cachuje v _shared/.
     *
     * Assets struktura: assets/sherpa_shared/<family>*.onnx
     * Např.: assets/sherpa_shared/vocos_24khz.onnx
     *
     * @param family Klíč rodiny (např. "vocos", "hifigan")
     * @return Absolutní cesta k extrahovanému souboru nebo null
     */
    private fun extractVocoderFromAssets(family: String): String? {
        return try {
            val assetDir = "sherpa_shared"
            val assetFiles = context.assets.list(assetDir) ?: emptyArray()
            val assetName = assetFiles.firstOrNull { name ->
                name.contains(family, ignoreCase = true) && name.endsWith(".onnx")
            } ?: return null

            val targetFile = File(getSharedDir(), assetName)
            // Pokud už extrahován → vracíme cestu (idempotence)
            if (targetFile.exists() && targetFile.length() > 0) {
                return targetFile.absolutePath
            }

            // Extrakce z assets → _shared/
            context.assets.open("$assetDir/$assetName").use { input ->
                java.io.FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "📦 Vocoder '$assetName' extrahován z assets (${targetFile.length() / 1024} KB)")
            targetFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Nepodařilo se extrahovat vocoder z assets: ${e.message}")
            null
        }
    }

    /** Získá seznam všech importovaných modelů. */
    fun getImportedModels(): List<SherpaModelInfo> {
        val models = mutableListOf<SherpaModelInfo>()
        val baseDir = getBaseModelDir()

        if (baseDir.exists() && baseDir.isDirectory) {
            baseDir.listFiles()?.forEach { dir ->
                if (dir.isDirectory) {
                    val type = detectModelType(dir)
                    if (type != SherpaModelType.UNKNOWN) {
                        // Použij cachovanou velikost z parseru — žádný další walkTopDown()!
                        // analyze() již prošel celou složku a spočítal sizeBytes.
                        val cachedSize = SherpaHeuristicParser.getCachedSizeBytes(dir)
                        val sizeBytes = if (cachedSize >= 0) cachedSize
                                else dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
                        models.add(
                                SherpaModelInfo(
                                        id = dir.name,
                                        name = dir.name,
                                        type = type,
                                        path = dir.absolutePath,
                                        sizeBytes = sizeBytes
                                )
                        )
                    }
                }
            }
        }
        return models.sortedBy { it.name }
    }

    /** Smaže konkrétní model na základě jeho cesty. */
    fun deleteModel(modelPath: String): Boolean {
        try {
            val dir = File(modelPath)
            if (dir.exists() &&
                            dir.isDirectory &&
                            dir.absolutePath.startsWith(getBaseModelDir().absolutePath)
            ) {
                val deleted = dir.deleteRecursively()
                if (deleted) {
                    // Invalidace parser cache — smazaný model nesmí zůstat v RAM
                    SherpaHeuristicParser.invalidateCache(dir.absolutePath)
                    if (settingsManager.sherpaModelPath == modelPath) {
                        settingsManager.sherpaModelPath = ""
                    }
                    if (settingsManager.sherpaTtsModelPath == modelPath) {
                        settingsManager.sherpaTtsModelPath = ""
                    }
                }
                return deleted
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při mazání modelu: ${e.message}", e)
        }
        return false
    }

    /** Vrátí typ modelu nalezeného ve složce. Deleguje na SherpaHeuristicParser. */
    fun detectModelType(dir: File): SherpaModelType {
        if (!dir.exists() || !dir.isDirectory) return SherpaModelType.UNKNOWN
        return when (val result = SherpaHeuristicParser.analyze(dir)) {
            is SherpaSttPaths -> result.type
            is SherpaTtsPaths -> result.type
            else -> SherpaModelType.UNKNOWN
        }
    }

    // === LEGACY INTERNAL — nahrazen SherpaHeuristicParser — ponechán jako interní helper ===
    @Suppress("unused")
    private fun detectModelTypeInternal(dir: File): SherpaModelType {
        if (!dir.exists() || !dir.isDirectory) return SherpaModelType.UNKNOWN

        val files = dir.listFiles()?.map { it.name } ?: emptyList()
        val hasEncoder = files.any { it.startsWith("encoder") && it.endsWith(".onnx") }
        val hasDecoder = files.any { it.startsWith("decoder") && it.endsWith(".onnx") }
        val hasJoiner = files.any { it.startsWith("joiner") && it.endsWith(".onnx") }
        val hasTokens = files.contains("tokens.txt")
        val hasEspeakData =
                dir.listFiles()?.any { it.isDirectory && it.name == "espeak-ng-data" } ?: false
        val hasLexicon = files.contains("lexicon.txt")
        val onnxFiles = files.filter { it.endsWith(".onnx") }

        // === STT TRANSDUCER (encoder+decoder+joiner) ===
        if (hasEncoder && hasDecoder && hasJoiner && hasTokens) {
            // Heuristická detekce Online vs Offline z názvu složky
            val folderName = dir.name.lowercase()
            val isOfflineModel =
                    folderName.contains("offline") ||
                            folderName.contains("nemo") ||
                            folderName.contains("batch") ||
                            folderName.contains("non-streaming") ||
                            folderName.contains("non_streaming") ||
                            folderName.contains("nonstreaming")
            return if (isOfflineModel) {
                Log.i(TAG, "\uD83D\uDD0D Detekován OFFLINE Transducer model: ${dir.name}")
                SherpaModelType.STT_OFFLINE_TRANSDUCER
            } else {
                Log.i(
                        TAG,
                        "\uD83D\uDD0D Detekován ONLINE (Streaming) Transducer model: ${dir.name}"
                )
                SherpaModelType.STT_ONLINE_TRANSDUCER
            }
        }

        // === TTS PIPER (espeak-ng-data) ===
        if (hasEspeakData && hasTokens && onnxFiles.isNotEmpty()) {
            return SherpaModelType.TTS_PIPER
        }

        // --- Extrahujeme heuristiku ze složky ---
        val folderName = dir.name.lowercase()
        val isOfflineFolder =
                folderName.contains("offline") ||
                        folderName.contains("nemo") ||
                        folderName.contains("batch") ||
                        folderName.contains("non-streaming") ||
                        folderName.contains("non_streaming") ||
                        folderName.contains("nonstreaming")

        val isVits =
                folderName.contains("vits") ||
                        folderName.contains("coqui") ||
                        folderName.contains("matcha") ||
                        folderName.contains("aloe")

        // === TTS VITS Standard ===
        if ((hasLexicon || isVits) && hasTokens && onnxFiles.isNotEmpty() && !isOfflineFolder) {
            return SherpaModelType.TTS_VITS_STANDARD
        }

        // === STT OFFLINE OMNILINGUAL (Meta AI, wav2vec2+CTC, 1600+ jazyků) ===
        // Detekce: název složky obsahuje "omnilingual" → VŽDY offline batch model.
        // MUSÍ předcházet obecné CTC větvi (taky má single model.onnx + tokens.txt).
        if (hasTokens && onnxFiles.isNotEmpty() && folderName.contains("omnilingual")) {
            Log.i(TAG, "🔍 Detekován OFFLINE Omnilingual ASR model: ${dir.name}")
            return SherpaModelType.STT_OFFLINE_OMNILINGUAL
        }

        // === STT OFFLINE CTC ZIPFORMER (sherpa-onnx-zipformer-ctc-*) ===
        // Detekce: název obsahuje "zipformer" + "ctc" ale NE "streaming" → offline zipformer CTC.
        // MUSÍ předcházet obecné CTC větvi.
        val isZipformerCTC =
                folderName.contains("zipformer") &&
                folderName.contains("ctc") &&
                !folderName.contains("streaming")
        if (hasTokens && onnxFiles.size == 1 && isZipformerCTC) {
            Log.i(TAG, "🔍 Detekován OFFLINE Zipformer CTC model: ${dir.name}")
            return SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER
        }

        // === STT CTC / Paraformer (single model.onnx + tokens.txt) ===
        // Obecná větev: Paraformer a ostatní offline CTC modely bez specifické detekce výše.
        if (hasTokens && onnxFiles.size == 1) {
            return if (isOfflineFolder) SherpaModelType.STT_OFFLINE_CTC
            else SherpaModelType.STT_ONLINE_CTC
        }

        // === VAD (small single .onnx without tokens) ===
        if (onnxFiles.size == 1 && !hasTokens) {
            val f = File(dir, onnxFiles[0])
            if (f.length() < 10 * 1024 * 1024) { // Small model -> VAD
                return SherpaModelType.VAD
            }
        }

        return SherpaModelType.UNKNOWN
    }

    /**
     * Bezpečné vyhledání ONNX souboru s daným prefixem. Filtruje .int8 duplicity — preferuje čistý
     * .onnx, fallback na .int8.onnx jen pokud je JEDINÝ. Tím se zabrání smíchání encoder.onnx +
     * decoder.int8.onnx → SIGABRT v JNI.
     */
    private fun findSafeModel(dir: File, prefix: String): String? {
        val allFiles = dir.listFiles() ?: return null
        val candidates = allFiles.filter { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
        if (candidates.isEmpty()) return null

        // 1. Preferuj čistý model (bez .int8)
        val cleanModel = candidates.firstOrNull { !it.name.contains(".int8") }
        if (cleanModel != null) return cleanModel.absolutePath

        // 2. Fallback: Pokud existuje POUZE .int8 varianta, vezmi tu
        return candidates.firstOrNull()?.absolutePath
    }

    /**
     * Ziská cesty pro STT engine. Deleguje na SherpaHeuristicParser.
     * Vrací null pokud model chybí, je nekompletí nebo nepoznaný.
     */
    fun getSttModelPaths(): SherpaSttPaths? {
        val path = settingsManager.sherpaModelPath
        if (path.isEmpty()) {
            Log.w(TAG, "⚠️ getSttModelPaths: sherpaModelPath je prázdný!")
            return null
        }
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) {
            Log.e(TAG, "❌ getSttModelPaths: Cesta neexistuje nebo není adresář: $path")
            return null
        }

        val result = SherpaHeuristicParser.analyze(dir)
        if (result !is SherpaSttPaths) {
            Log.e(TAG, "❌ getSttModelPaths: SherpaHeuristicParser nerozeznal STT model v: ${dir.name} (typ=${result?.javaClass?.simpleName ?: "null"})")
            return null
        }
        Log.i(TAG, "✅ STT ${result.type.name} model připraven z: ${dir.name}")
        return result
    }

    /**
     * Získá cesty pro TTS engine. Deleguje na SherpaHeuristicParser.
     *
     * CD-Player Strategy: Pokud model potřebuje ext. vocoder a nemá ho,
     * automaticky se pokusí resolovat z _shared/ adresáře.
     */
    fun getTtsModelPaths(): SherpaTtsPaths? {
        val path = settingsManager.sherpaTtsModelPath
        if (path.isEmpty()) return null
        val dir = File(path)
        val result = SherpaHeuristicParser.analyze(dir)
        if (result !is SherpaTtsPaths) {
            Log.e(TAG, "❌ getTtsModelPaths: SherpaHeuristicParser nerozeznal TTS model v: ${dir.name}")
            return null
        }

        // ── CD-Player: Auto-inject sdíleného vocoderu a lexiconu ────────────
        // Pokud model potřebuje ext. vocoder (ZipVoice, Matcha) a nemá vlastní,
        // resoluj z _shared/ adresáře.
        var enriched = autoInjectSharedVocoder(result)

        // Prevence C++ SIGABRT z matcha-tts-lexicon.cc: ZipVoice a Matcha vyžadují lexicon.
        // Pokud jej model nemá, podstrčíme prázdný dummy soubor, aby engine úspěšně nastartoval.
        if (enriched.lexicon == null && (enriched.type == SherpaModelType.TTS_ZIPVOICE || enriched.type == SherpaModelType.TTS_MATCHA)) {
            Log.i(TAG, "📝 Auto-injektován prázdný dummy_lexicon.txt pro prevenci C++ SIGABRT")
            enriched = enriched.copy(lexicon = getDummyLexiconPath())
        }

        Log.i(TAG, "✅ TTS ${enriched.type.name} model připraven z: ${dir.name}" +
                if (enriched.vocoder != null) " [vocoder=${File(enriched.vocoder).name}]" else " [vocoder=CHYBÍ]")
        return enriched
    }

    /**
     * CD-Player: Automatický inject sdíleného vocoderu.
     *
     * Mapa závislostí (TTS rodina → vocoder family pattern):
     *   TTS_ZIPVOICE  → "vocos"   (vocos_24khz.onnx)
     *   TTS_MATCHA     → "hifigan" (hifigan_v2.onnx)
     *   TTS_SUPERTONIC → nedělá auto-inject (vocoder MUSÍ být součástí balíčku)
     *   Ostatní        → nepotřebují ext. vocoder (interní v model.onnx)
     */
    private fun autoInjectSharedVocoder(paths: SherpaTtsPaths): SherpaTtsPaths {
        // Mapa: typ → vocoder family pattern
        val vocoderFamily: String? = when (paths.type) {
            SherpaModelType.TTS_ZIPVOICE -> "vocos"
            SherpaModelType.TTS_MATCHA   -> "hifigan"
            else -> null // Ostatní nepotřebují ext. vocoder
        }

        // Nepotřebuje ext. vocoder → vrátit beze změny
        if (vocoderFamily == null) return paths

        val explicitPath = settingsManager.sherpaSharedVocoderPath
        // 1. Uživatelský výběr v UI (konkrétní vocoder)
        if (explicitPath.isNotEmpty() && File(explicitPath).exists()) {
             Log.i(TAG, "💿 CD-Player: Vocoder VYNUCEN z UI: ${File(explicitPath).name}")
             return if (paths.type == SherpaModelType.TTS_MATCHA) {
                 paths.copy(model2 = explicitPath)
             } else {
                 paths.copy(vocoder = explicitPath)
             }
        }

        // 2. Uživatel zvolil "Ze složky modelu (výchozí)" -> Má model vlastní vocoder?
        val hasOwnVocoder = if (paths.type == SherpaModelType.TTS_MATCHA) {
            !paths.model2.isNullOrEmpty() && File(paths.model2!!).exists()
        } else {
            !paths.vocoder.isNullOrEmpty() && File(paths.vocoder!!).exists()
        }

        if (hasOwnVocoder) {
            Log.i(TAG, "💿 CD-Player: Použit výchozí vocoder přímo ze složky modelu.")
            return paths
        }

        // 3. Fallback -> Nenašli jsme lokální, auto-injectneme univerzální
        val sharedVocoderPath = resolveSharedVocoder(vocoderFamily)
        return if (sharedVocoderPath != null) {
            Log.i(TAG, "🎵 CD-Player: Auto-injektován sdílený vocoder '$vocoderFamily' → ${File(sharedVocoderPath).name}")
            if (paths.type == SherpaModelType.TTS_MATCHA) {
                 paths.copy(model2 = sharedVocoderPath)
            } else {
                 paths.copy(vocoder = sharedVocoderPath)
            }
        } else {
            Log.w(TAG, "⚠️ CD-Player: Model ${paths.type} potřebuje '$vocoderFamily' vocoder, ale nebyl nalezen. " +
                    "Importujte vocoder do ${getSharedDir().absolutePath}/ nebo v Nastavení.")
            paths
        }
    }

    /**
     * Importuje sdílený vocoder ze SAF URI přímo do _shared/ adresáře.
     */
    suspend fun importSharedVocoder(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            var displayName: String? = null

            if (uri.scheme == "content") {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (index != -1) displayName = cursor.getString(index)
                    }
                }
            }
            if (displayName == null) {
                displayName = uri.path?.let { File(it).name }
            }

            if (displayName == null || !displayName!!.endsWith(".onnx", ignoreCase = true)) {
                Log.e(TAG, "❌ Import selhal: Neplatný název souboru nebo chybí přípona .onnx")
                return@withContext false
            }

            val targetFile = File(getSharedDir(), displayName!!)
            contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Log.i(TAG, "✅ Sdílený vocoder importován: ${targetFile.absolutePath}")
            return@withContext true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při importu sdíleného vocoderu", e)
            return@withContext false
        }
    }

    /**
     * Importuje Sherpa model z DocumentTree URI.
     * Kopíruje složku na interní úloziště, pak příné cesty detekuje přes SherpaHeuristicParser.
     * walkTopDown() v parseru automaticky zvládne i wrapper složky (např. Downloads).
     */
    suspend fun importModelFromTreeUri(uri: Uri): Pair<Boolean, SherpaModelType> =
            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "📥 Pokus o import Sherpa modelu z URI: $uri")
                    val tree = DocumentFile.fromTreeUri(context, uri)
                            ?: return@withContext Pair(false, SherpaModelType.UNKNOWN)
                    if (!tree.isDirectory) return@withContext Pair(false, SherpaModelType.UNKNOWN)

                    val targetDirName = tree.name ?: "imported_model_${System.currentTimeMillis()}"
                    val targetDir = File(getBaseModelDir(), targetDirName)
                    if (!targetDir.exists()) targetDir.mkdirs()

                    // 1. Kopírujeme dokumentový strom
                    copyDirectoryTree(context, tree, targetDir)

                    // 2. Invalidace cache + analýza přes SherpaHeuristicParser
                    // Nový model → cache pro tuto cestu musí být čerstvá
                    SherpaHeuristicParser.invalidateCache(targetDir.absolutePath)
                    val detected = SherpaHeuristicParser.analyze(targetDir)

                    if (detected == null) {
                        Log.e(TAG, "❌ Model nerozpárovan po importu. Složka mazána.")
                        targetDir.deleteRecursively()
                        return@withContext Pair(false, SherpaModelType.UNKNOWN)
                    }

                    val detectedType = when (detected) {
                        is SherpaSttPaths -> detected.type
                        is SherpaTtsPaths -> detected.type
                        else -> SherpaModelType.UNKNOWN
                    }

                    if (detectedType == SherpaModelType.UNKNOWN) {
                        Log.e(TAG, "❌ Model nepoznán po importu. Složka mazána.")
                        targetDir.deleteRecursively()
                        return@withContext Pair(false, SherpaModelType.UNKNOWN)
                    }

                    // 3. Uložíme cestu podle typu
                    when {
                        detectedType.isStt -> {
                            settingsManager.sherpaModelPath = targetDir.absolutePath
                            Log.i(TAG, "✅ STT Model ($detectedType) naimportován: ${targetDir.absolutePath}")
                        }
                        detectedType.isTts -> {
                            settingsManager.sherpaTtsModelPath = targetDir.absolutePath
                            Log.i(TAG, "✅ TTS Model ($detectedType) naimportován: ${targetDir.absolutePath}")
                        }
                        else -> {
                            Log.w(TAG, "⚠️ Neznámý typ modelu $detectedType — cesta neuložena.")
                        }
                    }

                    return@withContext Pair(true, detectedType)
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chyba během importu modelu Sherpa: ${e.message}", e)
                    return@withContext Pair(false, SherpaModelType.UNKNOWN)
                }
            }

    private fun copyDirectoryTree(context: Context, sourceDir: DocumentFile, targetDir: File) {
        sourceDir.listFiles().forEach { file ->
            if (file.isDirectory) {
                val newTargetDir = File(targetDir, file.name!!)
                newTargetDir.mkdirs()
                copyDirectoryTree(context, file, newTargetDir)
            } else {
                val destFile = File(targetDir, file.name!!)
                context.contentResolver.openInputStream(file.uri)?.use { input ->
                    FileOutputStream(destFile).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    /**
     * Izolovaný přepis jednoho PCM chunku přes Sherpa Offline Recognizer.
     *
     * Audio Studio používá tuto metodu pro chunk-by-chunk přepis importovaných médií.
     * Každý call vytváří nový OfflineRecognizer a ihned ho uvolní → žádná interference
     * s persistentním recognizerem TranscriptAgenta.
     *
     * @param pcmChunk PCM Short pole @ 16kHz mono
     * @param modelDirPath Absolutní cesta ke složce Sherpa STT modelu
     * @return Přepsaný text nebo prázdný řetězec při chybě
     */
    fun transcribeOfflineChunk(pcmChunk: FloatArray, modelDirPath: String): String {
        return try {
            val dir = File(modelDirPath)
            val paths = SherpaHeuristicParser.analyze(dir) as? SherpaSttPaths ?: return ""

            // Sherpa Offline OfflineRecognizer konfigurace
            val modelConfig = when (paths.type) {
                SherpaModelType.STT_OFFLINE_TRANSDUCER -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    transducer = com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig(
                        encoder = paths.encoder ?: "", decoder = paths.decoder ?: "", joiner = paths.joiner ?: ""
                    ), tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_CTC -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    paraformer = com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig(model = paths.model ?: ""),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_CTC_ZIPFORMER -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    zipformerCtc = com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig(model = paths.model ?: ""),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_SENSEVOICE -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    senseVoice = com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig(model = paths.model ?: "", language = "auto", useInverseTextNormalization = true),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_WHISPER -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    whisper = com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig(encoder = paths.encoder ?: "", decoder = paths.decoder ?: ""),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_MOONSHINE -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    moonshine = com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig(preprocessor = paths.preprocessor ?: "", encoder = paths.encoder ?: "", uncachedDecoder = paths.decoder ?: "", cachedDecoder = paths.decoder2 ?: ""),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_NEMO_CTC -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    nemo = com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig(model = paths.model ?: ""),
                    tokens = paths.tokens
                )
                SherpaModelType.STT_OFFLINE_OMNILINGUAL -> com.k2fsa.sherpa.onnx.OfflineModelConfig(
                    omnilingual = com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig(model = paths.model ?: ""),
                    tokens = paths.tokens
                )
                else -> com.k2fsa.sherpa.onnx.OfflineModelConfig(tokens = paths.tokens)
            }
            modelConfig.numThreads = settingsManager.sherpaNThreads
            modelConfig.debug = false

            val recognizerConfig = com.k2fsa.sherpa.onnx.OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )
            val recognizer = com.k2fsa.sherpa.onnx.OfflineRecognizer(
                assetManager = null,
                config = recognizerConfig
            )

            val stream = recognizer.createStream()
            stream.acceptWaveform(pcmChunk, sampleRate = 16000)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()

            stream.release()
            recognizer.release()

            Log.d(TAG, "Sherpa offline chunk → \"${result.take(80)}\"")
            result
        } catch (e: Exception) {
            Log.e(TAG, "transcribeOfflineChunk chyba: ${e.message}", e)
            ""
        }
    }
}

