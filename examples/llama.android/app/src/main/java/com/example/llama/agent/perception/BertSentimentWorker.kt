package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.example.llama.data.BertModel
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier
import com.google.mediapipe.tasks.text.textclassifier.TextClassifier.TextClassifierOptions
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite / LiteRT Worker pro analýzu sentimentu offline.
 *
 * Plná integrace s BertModelManagerem (2026 architektura).
 * Automaticky volí engine (MediaPipe vs LiteRT Interpreter) podle typu modelu:
 * - EMBEDDED_METADATA -> Používá MediaPipe TextClassifier (Kaggle/TFHub modely).
 * - SEPARATE_TXT / BUNDLED_ASSET -> Používá čistý Interpreter + BertTokenizer (HuggingFace modely).
 */
class BertSentimentWorker(private val context: Context) {

    companion object {
        private const val TAG = "BertSentimentWorker"
    }

    // Engine 1: MediaPipe (pro String Input / Embedded Vocab modely)
    private var textClassifier: TextClassifier? = null

    // Engine 2: LiteRT Interpreter (pro INT32 Input / Separate Vocab modely)
    private var interpreter: Interpreter? = null
    private var tokenizer: BertTokenizer? = null

    @Volatile
    private var isPrepared = false
    private var isMediaPipe = false

    // Maximální počet tokenů podporovaný embedding tabulkou modelu
    private var modelVocabLimit: Int = Int.MAX_VALUE

    // Dynamická délka sekvence z modelu, výchozí 128
    private var modelSeqLen: Int = 128

    init {
        initialize()
    }

    private fun initialize() {
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val modelPath = BertModelManager.getActiveModelPath(context, requireSquad = false)
                val vocabPath = BertModelManager.getActiveVocabPath(context)

                if (vocabPath == BertModelManager.VOCAB_EMBEDDED_MARKER) {
                    // === ENGINE 1: MediaPipe TextClassifier ===
                    Log.i(TAG, "🤖 Startuji MediaPipe TextClassifier pro Embedded model: $modelPath")
                    isMediaPipe = true
                    
                    val baseOptionsBuilder = BaseOptions.builder()
                    if (modelPath.startsWith(BertModelManager.ASSET_PREFIX)) {
                        baseOptionsBuilder.setModelAssetPath(modelPath.removePrefix(BertModelManager.ASSET_PREFIX))
                    } else {
                        baseOptionsBuilder.setModelAssetPath(modelPath) 
                    }

                    val options = TextClassifierOptions.builder()
                        .setBaseOptions(baseOptionsBuilder.build())
                        .build()

                    textClassifier = TextClassifier.createFromOptions(context, options)
                    isPrepared = true
                    Log.i(TAG, "✅ BertSentimentWorker (MediaPipe) úspěšně inicializován.")
                } else {
                    // === ENGINE 2: LiteRT Interpreter + WordPiece Tokenizer ===
                    Log.i(TAG, "🤖 Startuji LiteRT Interpreter s externím vocab: $modelPath, vocab: $vocabPath")
                    isMediaPipe = false
                    
                    tokenizer = BertTokenizer(context, vocabPath)
                    
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
                            stream.channel.map(FileChannel.MapMode.READ_ONLY, 0L, localFile.length())
                        }
                    } else {
                        val localFile = File(modelPath) 
                        localFile.inputStream().channel.use { channel ->
                            channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                        }
                    }
                    
                    val interpreterOptions = Interpreter.Options().apply { setNumThreads(2) }
                    val delegate = runCatching { NnApiDelegate() }.getOrNull()
                    delegate?.let { interpreterOptions.addDelegate(it) }
                    
                    interpreter = Interpreter(modelBuffer, interpreterOptions)

                    // Získání skutečné velikosti embedding tabulky z modelu
                    // Pokud není k dispozici, fallback na standardní 30522 (BERT-base-uncased)
                    try {
                        val inputTensor = interpreter!!.getInputTensor(0)
                        val shape = inputTensor.shape() // [batch, seq_len]
                        Log.i(TAG, "📐 Model vstupní tensor shape: ${shape.contentToString()}, dtype: ${inputTensor.dataType()}")
                        
                        if (shape.size >= 2 && shape[1] > 0) {
                            modelSeqLen = shape[1]
                        }
                        
                        // Pro BERT modely: Embedding GATHER layer se indexuje od 0
                        // Skutečný limit je velikost embedding matice — to z input tensoru nedostaneme přímo.
                        // Bezpečný fallback: pokud vocab > 30522, clampujeme na 30522 (std BERT).
                        // Uživatel může importovat odpovídající vocab k multilingual modelu.
                        modelVocabLimit = tokenizer?.vocabSize ?: 30522
                    } catch (e: Exception) {
                        Log.w(TAG, "⚠️ Nelze zjistit parametry modelu, fallback vocabLimit=30522", e)
                        modelVocabLimit = 30522
                    }

                    isPrepared = true
                    Log.i(TAG, "✅ BertSentimentWorker (LiteRT Interpreter) inicializován. vocabLimit=$modelVocabLimit")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Inicializace BertSentimentWorker selhala.", e)
                isPrepared = false
            }
        }
    }

    /**
     * Vrací skóre sentimetu pro daný text (Pozitivní / Negativní + procenta).
     */
    fun classifySentiment(text: String): String {
        if (!isPrepared) return "⚠️ AI Model není připraven. Zkuste aplikaci restartovat."
        if (text.isBlank()) return "Nebyl poskytnut žádný text pro analýzu."

        return try {
            if (isMediaPipe) {
                classifyWithMediaPipe(text)
            } else {
                classifyWithInterpreter(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při inference Sentiment classifieru", e)
            "Chyba při inference: ${e.message}"
        }
    }

    private fun classifyWithMediaPipe(text: String): String {
        val classifier = textClassifier ?: throw IllegalStateException("TextClassifier is null")
        val results = classifier.classify(text)
        
        if (results.classificationResult().classifications().isEmpty()) {
            return "Neznámý sentiment."
        }
        
        val categories = results.classificationResult().classifications()[0].categories()
        val bestCategory = categories.maxByOrNull { it.score() }
        
        if (bestCategory != null) {
            return formatOutput(bestCategory.categoryName() ?: "Neznámý", bestCategory.score())
        }
        return "Analýza sentimentu nevrátila žádnou kategorii."
    }

    private fun classifyWithInterpreter(text: String): String {
        val inter = interpreter ?: throw IllegalStateException("Interpreter is null")
        val tok = tokenizer ?: throw IllegalStateException("Tokenizer is null")
        
        // Klasická HuggingFace BERT Sequence Classification -> inputIds + attentionMask
        val (rawInputIds, attentionMask) = tok.encode(text, modelSeqLen)

        // Ochrana proti GATHER index out of bounds:
        // Clampovat token IDs na rozsah embedding tabulky modelu
        val inputIds = IntArray(rawInputIds.size) { i ->
            val id = rawInputIds[i]
            if (id >= modelVocabLimit) {
                // UNK token (ID 100 je standard pro [UNK] ve všech BERT modelech)
                100
            } else {
                id
            }
        }

        val outShape = inter.getOutputTensor(0).shape()
        val numClasses = if (outShape.size >= 2) outShape[1] else 2
        val outputLogits = Array(1) { FloatArray(numClasses) }
        
        val tokenTypeIds = IntArray(inputIds.size) { 0 }
        
        synchronized(inter) {
            when {
                inter.inputTensorCount >= 3 -> {
                    inter.runForMultipleInputsOutputs(
                        arrayOf(inputIds, attentionMask, tokenTypeIds),
                        mapOf(0 to outputLogits)
                    )
                }
                inter.inputTensorCount == 2 -> {
                    inter.runForMultipleInputsOutputs(
                        arrayOf(inputIds, attentionMask),
                        mapOf(0 to outputLogits)
                    )
                }
                else -> {
                    inter.run(inputIds, outputLogits)
                }
            }
        }
        
        // Softmax pro libovolný počet tříd (bezpečné pro různé modely)
        val logits = outputLogits[0]
        val expLogits = FloatArray(logits.size) { Math.exp(logits[it].toDouble()).toFloat() }
        val sumExp = expLogits.sum()
        val probs = FloatArray(expLogits.size) { if (sumExp > 0) expLogits[it] / sumExp else 0f }

        val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val label = if (maxIdx == 1) "positive" else if (maxIdx == 0) "negative" else "třída_$maxIdx"
        return formatOutput(label, probs[maxIdx])
    }

    private fun formatOutput(label: String, score: Float): String {
        val scorePct = (score * 100).toInt()
        val emoji = when (label.lowercase()) {
            "positive", "1" -> "🟢 Pozitivní"
            "negative", "0" -> "🔴 Negativní"
            else -> "⚪ $label"
        }
        return "$emoji sentiment ($scorePct% jistota)"
    }

    /**
     * Uvolní C++ graph objekty (TextClassifier / Interpreter) a zabrání leakům.
     */
    fun close() {
        try {
            textClassifier?.close()
            interpreter?.close()
            Log.i(TAG, "🧹 BertSentimentWorker uvolněn z paměti.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při zavírání AI enginů", e)
        } finally {
            textClassifier = null
            interpreter = null
            isPrepared = false
        }
    }
}
