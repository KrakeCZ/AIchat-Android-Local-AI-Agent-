package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.example.llama.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/**
 * ToolbarBertWorker — Tier 1 LiteRT worker pro AI Toolbar moduly M1/M3/M4/M5.
 *
 * Implementuje BasePerceptionWorker.
 * Vytvořeno pod hlavičkou "Red/Blue Team" Security & Performance Review:
 * 1. Nulové sdílení EGL Contextu: Řeší Crash na Android 15/16 (TFLite GPU Delegate) 
 *    tím, že vynucuje veškerou inicializaci i inferenci do striktně izolovaného jediného vlákna `inferenceDispatcher`.
 * 2. 16 KB Page Alignment kompatibilita s NDK r28 obsažena v nejnovějších `Interpreter.Options`.
 */
class ToolbarBertWorker(
    private val context: Context,
    private val settingsManager: SettingsManager
) : BasePerceptionWorker {

    companion object {
        private const val TAG = "ToolbarBertWorker"
    }

    override val workerId: String = "toolbar_bert_worker"
    override val requiredPermissions: List<String> = emptyList()

    // 🔴 KRITICKÉ: SingleThread Executor zajišťující GPU Delegate Thread Affinity! 🔴
    private val litertExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "LiteRT-BERT-Inference-Thread").apply {
            priority = Thread.MAX_PRIORITY // Maximální priorita pro NPU/GPU vytěžování
        }
    }
    private val inferenceDispatcher = litertExecutor.asCoroutineDispatcher()

    @Volatile private var bertMiniInterpreter: Interpreter? = null
    @Volatile private var mobileBertInterpreter: Interpreter? = null
    @Volatile private var isPrepared = false

    @Volatile private var tokenizer: BertTokenizer? = null

    // Dynamicky načtené sekvence z modelu pro prevenci reshape crashů
    @Volatile private var bertMiniSeqLen: Int = 128
    @Volatile private var mobileBertSeqLen: Int = 128

    // ==================== BasePerceptionWorker lifecycle ====================

    override suspend fun prepare(): Boolean = withContext(inferenceDispatcher) {
        if (isPrepared) return@withContext true

        Log.i(TAG, "🔧 Inicializace LiteRT na izolovaném vlákně (GPU Thread Affinity protection)...")

        // Bert-mini slouží jako foundational model (Fáze 1)
        val bertMiniPath = BertModelManager.getActiveModelPath(context, requireSquad = false)
        val bertMiniOk = initInterpreter(bertMiniPath)?.let {
            bertMiniInterpreter = it
            try {
                val shape = it.getInputTensor(0).shape()
                if (shape.size >= 2 && shape[1] > 0) bertMiniSeqLen = shape[1]
            } catch (e: Exception) { Log.w(TAG, "Nelze přečíst input shape pro bertMini", e) }
            true
        } ?: false

        // MobileBert SQuAD je volitelný (95MB), pokud byl stažen lokálně
        val mobileBertPath = BertModelManager.getActiveModelPath(context, requireSquad = true)
        initInterpreter(mobileBertPath)?.let {
            mobileBertInterpreter = it
            try {
                val shape = it.getInputTensor(0).shape()
                if (shape.size >= 2 && shape[1] > 0) {
                    mobileBertSeqLen = shape[1]
                }
            } catch (e: Exception) { Log.w(TAG, "Nelze přečíst input shape pro mobileBert", e) }
            Unit
        }

        // Dynamická inicializace Tokenizeru z Repository
        val vocabPath = BertModelManager.getActiveVocabPath(context)
        tokenizer = BertTokenizer(context, vocabPath)

        isPrepared = bertMiniOk && tokenizer != null
        if (!bertMiniOk) {
            Log.e(TAG, "❌ FATAL: Základní TFLite model pro Tier 1 nenalezen: $bertMiniPath")
        }
        isPrepared
    }

    override fun start() {
        Log.i(TAG, "▶ ToolbarBertWorker start (stateless, čeká na inference event)")
    }

    override fun stop() {
        Log.i(TAG, "⏸ ToolbarBertWorker stop")
    }

    override fun destroy() {
        // Uvolnění nativních C++ pointerů musí primárně proběhnout tam, kde byly alokovány, ev. asynchronně.
        litertExecutor.execute {
            runCatching { bertMiniInterpreter?.close() }
            runCatching { mobileBertInterpreter?.close() }
            bertMiniInterpreter = null
            mobileBertInterpreter = null
            isPrepared = false
            Log.i(TAG, "💀 ToolbarBertWorker zničen (Memory Leaks prevence OK)")
        }
        // Vypnutí executoru zabrání zombie threadům
        litertExecutor.shutdown()
    }

    // ==================== M1: Tón (5 kategorií) ====================

    /** Pětitřídová klasifikace tónu (M1). Zpracovává se s `bertMiniInterpreter`. */
    suspend fun classifyTone(text: String): String = withContext(inferenceDispatcher) {
        val interpreter = bertMiniInterpreter
        if (interpreter == null) return@withContext "Engine nenalezen (Missing Model)"
        val localTokenizer = this@ToolbarBertWorker.tokenizer ?: return@withContext "Tokenizer nenalezen"
        
        try {
            val (inputIds, attentionMask) = localTokenizer.encode(text, bertMiniSeqLen)
            
            val outShape = interpreter.getOutputTensor(0).shape()
            if (outShape.size < 2) return@withContext "Engine nepodporuje tuto klasifikaci"
            val outClasses = outShape[1]
            val outputLogits = Array(1) { FloatArray(outClasses) }

            // Zámek už teoreticky není nutný díky inferenceDispatcheru, ale pro 
            // thread-safety v Native vrstvě jej preventivně ponecháme.
            val tokenTypeIds = IntArray(inputIds.size) { 0 }
            synchronized(interpreter) {
                when {
                    interpreter.inputTensorCount >= 3 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask, tokenTypeIds), mapOf(0 to outputLogits))
                    }
                    interpreter.inputTensorCount == 2 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask), mapOf(0 to outputLogits))
                    }
                    else -> {
                        interpreter.run(inputIds, outputLogits)
                    }
                }
            }

            val labels = listOf("NEUTRÁLNÍ", "FORMÁLNÍ", "AGRESIVNÍ", "IRONICKÉ", "NALÉHAVÉ")
            val maxIdx = outputLogits[0].indices.maxByOrNull { outputLogits[0][it] } ?: 0
            val conf = outputLogits[0][maxIdx]
            
            val label = if (maxIdx < labels.size) labels[maxIdx] else "Třída $maxIdx"
            "$label (${String.format("%.0f", conf * 100)}%)"
        } catch (e: Exception) {
            Log.e(TAG, "classifyTone failed", e)
            "Analýza tónu selhala"
        }
    }

    // ==================== M3: Extractive QA ====================

    /** 
     * Hledání odpovědi vytěžující GPU/NPU přes `mobileBertInterpreter` (pokud existuje), 
     * jinak fallbackuje na `bertMiniInterpreter`. 
     */
    suspend fun extractiveQa(context: String, question: String): String = withContext(inferenceDispatcher) {
        val interpreter = mobileBertInterpreter ?: bertMiniInterpreter
        if (interpreter == null) return@withContext "M3 Error: Model nenalezen"
        val localTokenizer = this@ToolbarBertWorker.tokenizer ?: return@withContext "Tokenizer nenalezen"

        try {
            val seqLenToUse = if (mobileBertInterpreter != null) mobileBertSeqLen else bertMiniSeqLen
            val (inputIds, attentionMask, tokenTypeIds) = localTokenizer.encodeQuestionContext(question, context, seqLenToUse)
            
            if (interpreter.outputTensorCount < 2) return@withContext "M3 Error: Tento model nepodporuje QA (chybí 2 výstupy)"
            val outShape0 = interpreter.getOutputTensor(0).shape()
            val expectedSeqLen = if (outShape0.size >= 2) outShape0[1] else seqLenToUse
            
            val startLogits = Array(1) { FloatArray(expectedSeqLen) }
            val endLogits = Array(1) { FloatArray(expectedSeqLen) }

            synchronized(interpreter) {
                val inputCount = interpreter.inputTensorCount
                val inputs = when {
                    inputCount >= 3 -> arrayOf(inputIds, attentionMask, tokenTypeIds)
                    inputCount == 2 -> arrayOf(inputIds, attentionMask)
                    else -> arrayOf(inputIds)
                }
                interpreter.runForMultipleInputsOutputs(
                    inputs,
                    mapOf(0 to startLogits, 1 to endLogits)
                )
            }

            val startIdx = startLogits[0].indices.maxByOrNull { startLogits[0][it] } ?: 0
            val endIdx = endLogits[0].indices.filter { it >= startIdx }.maxByOrNull { endLogits[0][it] } ?: startIdx

            val answer = localTokenizer.decodeSpan(inputIds, startIdx, endIdx)
            answer.ifBlank { "Odpověď v textu nenalezena." }
        } catch (e: Exception) {
            Log.e(TAG, "extractiveQa failed", e)
            "Nelze provést QA dotaz"
        }
    }

    // ==================== M4: NLI — Detekce rozporů ====================

    /** 
     * Hledá logické spory. (Rozdělí text na premisu a hypotézu).
     */
    suspend fun detectContradiction(text: String): String = withContext(inferenceDispatcher) {
        val interpreter = bertMiniInterpreter
        if (interpreter == null) return@withContext "Engine nenalezen"
        val localTokenizer = this@ToolbarBertWorker.tokenizer ?: return@withContext "Tokenizer nenalezen"

        val parts = if (text.contains("[SEP]")) text.split("[SEP]", limit = 2) else listOf(text.substring(0, text.length / 2), text.substring(text.length / 2))
        val premisa = parts[0].trim()
        val hypoteza = parts.getOrNull(1)?.trim() ?: ""

        try {
            val (inputIds, attentionMask) = localTokenizer.encodeNli(premisa, hypoteza, bertMiniSeqLen)
            
            val outShape = interpreter.getOutputTensor(0).shape()
            if (outShape.size < 2) return@withContext "Engine nepodporuje tuto klasifikaci"
            val outClasses = outShape[1]
            val outputLogits = Array(1) { FloatArray(outClasses) }

            val tokenTypeIds = IntArray(inputIds.size) { 0 }
            synchronized(interpreter) {
                when {
                    interpreter.inputTensorCount >= 3 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask, tokenTypeIds), mapOf(0 to outputLogits))
                    }
                    interpreter.inputTensorCount == 2 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask), mapOf(0 to outputLogits))
                    }
                    else -> {
                        interpreter.run(inputIds, outputLogits)
                    }
                }
            }

            val labels = listOf("ENTAILMENT (Souhlas)", "NEUTRAL", "CONTRADICTION (Rozpor)")
            val maxIdx = outputLogits[0].indices.maxByOrNull { outputLogits[0][it] } ?: 1
            val conf = outputLogits[0][maxIdx]

            val label = if (maxIdx < labels.size) labels[maxIdx] else "Třída $maxIdx"
            "$label (${String.format("%.0f", conf * 100)}%)\nP: \"${premisa.take(40)}\"\nH: \"${hypoteza.take(40)}\""
        } catch (e: Exception) {
            Log.e(TAG, "detectContradiction failed", e)
            "NLI selhalo"
        }
    }

    // ==================== M5: MLM — Masked Language Modeling ====================

    /** Model predikuje maskované slovo. Užívá Array s vocabSize rozložením. */
    suspend fun predictMaskedTokens(text: String): String = withContext(inferenceDispatcher) {
        val interpreter = bertMiniInterpreter
        if (interpreter == null) return@withContext "Engine nenalezen"
        val localTokenizer = this@ToolbarBertWorker.tokenizer ?: return@withContext "Tokenizer nenalezen"
        val inputText = if (BertTokenizer.MASK_TOKEN_STR in text) text else "$text ${BertTokenizer.MASK_TOKEN_STR}"

        try {
            val (inputIds, attentionMask) = localTokenizer.encode(inputText, bertMiniSeqLen)
            val maskPos = inputIds.indexOfFirst { it == localTokenizer.maskTokenId }
            if (maskPos < 0) return@withContext "Chyba maskování algoritmu: MASK token chybí"

            val outShape = interpreter.getOutputTensor(0).shape() // Očekáváme 3D tensor: [batch, seq_len, vocab_size]
            if (outShape.size < 3) return@withContext "Chyba maskování algoritmu: Zvolený model nepodporuje MLM (nema 3D vystup)"
            
            val expectedSeqLen = outShape[1]
            val expectedVocab = outShape[2]

            if (maskPos >= expectedSeqLen) return@withContext "Chyba maskování algoritmu: Sekvence přesáhla limit"

            val outputLogits = Array(1) { Array(expectedSeqLen) { FloatArray(expectedVocab) } }

            val tokenTypeIds = IntArray(inputIds.size) { 0 }
            synchronized(interpreter) {
                when {
                    interpreter.inputTensorCount >= 3 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask, tokenTypeIds), mapOf(0 to outputLogits))
                    }
                    interpreter.inputTensorCount == 2 -> {
                        interpreter.runForMultipleInputsOutputs(arrayOf(inputIds, attentionMask), mapOf(0 to outputLogits))
                    }
                    else -> {
                        interpreter.run(inputIds, outputLogits)
                    }
                }
            }

            val maskLogits = outputLogits[0][maskPos]
            val top3 = maskLogits.indices.sortedByDescending { maskLogits[it] }.take(3).map { localTokenizer.decodeToken(it) }

            "Návrhy pro MASK:\n1. \"${top3[0]}\"\n2. \"${top3[1]}\"\n3. \"${top3[2]}\""
        } catch (e: Exception) {
            Log.e(TAG, "predictMaskedTokens failed", e)
            "MLM detekce selhala"
        }
    }

    // ==================== Interpreter Builder (16 KB ALigned, Non-Root) ====================

    private fun initInterpreter(modelPath: String): Interpreter? {
        return try {
            val modelBuffer = if (modelPath.startsWith(BertModelManager.ASSET_PREFIX)) {
                val assetPath = modelPath.removePrefix(BertModelManager.ASSET_PREFIX)
                Log.i(TAG, "📦 Extrahuji .tflite z APK Assets do filesDir (16KB mmap safe): $assetPath")
                val localFile = File(context.filesDir, assetPath)
                if (!localFile.exists()) {
                    context.assets.open(assetPath).use { input ->
                        localFile.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                FileInputStream(localFile).use { stream ->
                    stream.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0L, localFile.length())
                }
            } else {
                val localFile = File(modelPath) 
                Log.i(TAG, "Načítám .tflite z FileSystemu: ${localFile.absolutePath}")
                localFile.inputStream().channel.use { channel ->
                    channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size())
                }
            }
            
            val options = Interpreter.Options().apply {
                setNumThreads(2) // Core omezeni (zabrani saturaci UI)
            }
            
            // Preferenční načítání NPU (NNAPI Delegate)
            val delegate = runCatching {
                Log.i(TAG, "Zkouším vytvořit NNAPI Delegát...")
                NnApiDelegate()
            }.getOrNull()
            
            delegate?.let { options.addDelegate(it) }
            
            Interpreter(modelBuffer, options).also {
                Log.i(TAG, "✅ LiteRT Interpreter OK (MemAligned): $modelPath")
            }
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ C++ Native vrstva odmítla Interpreter: $modelPath — ${e.message}")
            null
        }
    }
}
