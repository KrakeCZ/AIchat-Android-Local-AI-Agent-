package com.example.llama.agent.perception

import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * TfliteVocabDetector — Auto-detekce potřeby externího vocab souboru pro .tflite model.
 *
 * Princip detekce (bez extra závislostí — pouze litert-core):
 * LiteRT Interpreter načte model a zkontroluje DataType prvního vstupního tensoru:
 *
 *   INT32 inputs (2–3 tensorů) → BERT-style model vyžadující zewnętrzní tokenizaci
 *     → vocabStrategy = SEPARATE_TXT (HuggingFace workflow)
 *     → uživatel musí dodat vocab.txt
 *
 *   STRING input (1 tensor) → MediaPipe-style / TFHub model s embedded tokenizérem
 *     → vocabStrategy = EMBEDDED_METADATA
 *     → vocab není potřeba
 *
 * Tato detekce je spolehlivá protože:
 * - Všechny raw BERT/MobileBERT .tflite modely přijímají INT32 token ID arrays
 * - MediaPipe TextClassifier a modely s embedded metadata přijímají STRING (raw text)
 *
 * Kompatibilní s: LiteRT 1.x (litert-core), NDK r29, Android 15/16, 16 KB page size
 */
object TfliteVocabDetector {

    private const val TAG = "TfliteVocabDetector"

    /**
     * Výsledek auto-detekce vocab požadavku.
     */
    enum class VocabRequirement {
        /** Model vyžaduje external vocab.txt (BERT INT32 inputs) */
        REQUIRED,
        /** Model má vocab embedded — nepotřebuje external soubor (MediaPipe / metadata) */
        NOT_REQUIRED,
        /** Detekci se nepodařilo provést — soubor nelze načíst nebo parsovat */
        UNKNOWN
    }

    /**
     * Spustí detekci na základě absolutní cesty k souboru.
     *
     * Bezpečné volat z coroutine (IO dispatcher). Interpretr je ihned uzavřen po detekci.
     *
     * @param modelFilePath Absolutní cesta k .tflite souboru na disku
     * @return [VocabRequirement] výsledek detekce
     */
    fun detectFromFile(modelFilePath: String): VocabRequirement {
        val file = File(modelFilePath)

        if (!file.exists()) {
            Log.e(TAG, "Soubor neexistuje: $modelFilePath")
            return VocabRequirement.UNKNOWN
        }
        if (!file.canRead()) {
            Log.e(TAG, "Soubor nelze číst (oprávnění?): $modelFilePath")
            return VocabRequirement.UNKNOWN
        }

        return try {
            val modelBuffer = loadMappedFile(file)
            detectFromBuffer(modelBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při detekci vocab požadavku pro $modelFilePath: ${e.message}")
            VocabRequirement.UNKNOWN
        }
    }

    /**
     * Spustí detekci na základě MappedByteBuffer (efektivní — bez kopírování dat do paměti).
     *
     * @param modelBuffer MappedByteBuffer načtený z .tflite souboru
     * @return [VocabRequirement] výsledek detekce
     */
    fun detectFromBuffer(modelBuffer: MappedByteBuffer): VocabRequirement {
        val options = Interpreter.Options().apply {
            // CPU only — detekce je jednorázová, GPU delegate zbytečný overhead
            numThreads = 1
        }

        val interpreter = Interpreter(modelBuffer, options)
        return try {
            val inputCount = interpreter.inputTensorCount

            if (inputCount == 0) {
                Log.w(TAG, "Model nemá žádný vstupní tensor — nelze detekovat")
                return VocabRequirement.UNKNOWN
            }

            val firstInputType: DataType = interpreter.getInputTensor(0).dataType()
            val inputShape = interpreter.getInputTensor(0).shape()

            Log.d(TAG, "Model: $inputCount vstupů, tensor[0] type=$firstInputType, shape=${inputShape.toList()}")

            when {
                // BERT/MobileBERT — raw token IDs, vždy INT32, typicky 2–3 inputs
                firstInputType == DataType.INT32 && inputCount >= 2 -> {
                    Log.i(TAG, "✅ Detekce: BERT-style model (INT32 inputs, $inputCount vstupů) → vocab REQUIRED")
                    VocabRequirement.REQUIRED
                }

                // MediaPipe TextClassifier / model s embedded tokenizérem — STRING input
                firstInputType == DataType.STRING -> {
                    Log.i(TAG, "✅ Detekce: Embedded-tokenizer model (STRING input) → vocab NOT_REQUIRED")
                    VocabRequirement.NOT_REQUIRED
                }

                // INT32 s jediným vstupem — edge case (custom model?)
                firstInputType == DataType.INT32 && inputCount == 1 -> {
                    Log.w(TAG, "⚠️ INT32 s 1 vstupem — pravděpodobně REQUIRED, ale neobvyklé")
                    VocabRequirement.REQUIRED
                }

                // Neznámý typ
                else -> {
                    Log.w(TAG, "⚠️ Neznámý tensor type: $firstInputType — nelze určit vocab strategii")
                    VocabRequirement.UNKNOWN
                }
            }
        } finally {
            // Interpreter MUSÍ být uzavřen — uvolní nativní paměť
            interpreter.close()
        }
    }

    /**
     * Mapuje soubor do paměti jako MappedByteBuffer.
     * Efektivní pro velké .tflite soubory (28+ MB MobileBERT) — žádná kopie do heap.
     */
    private fun loadMappedFile(file: File): MappedByteBuffer {
        return FileInputStream(file).use { stream ->
            stream.channel.map(
                FileChannel.MapMode.READ_ONLY,
                0L,
                file.length()
            )
        }
    }

    /**
     * Mapuje VocabRequirement na vocabStrategy string kompatibilní s BertModel.VocabStrategy.
     *
     * @param requirement Výsledek detekce
     * @param fallbackSource Zdroj modelu — pro UNKNOWN case heuristika dle zdroje
     * @return String konstanta z BertModel.Companion.VocabStrategy
     */
    fun toVocabStrategy(requirement: VocabRequirement, fallbackSource: String): String {
        return when (requirement) {
            VocabRequirement.REQUIRED     -> com.example.llama.data.BertModel.Companion.VocabStrategy.SEPARATE_TXT
            VocabRequirement.NOT_REQUIRED -> com.example.llama.data.BertModel.Companion.VocabStrategy.EMBEDDED_METADATA
            VocabRequirement.UNKNOWN      -> {
                // Heuristika dle zdroje modelu
                when (fallbackSource) {
                    com.example.llama.data.BertModel.Companion.ModelSource.HUGGINGFACE -> {
                        Log.w(TAG, "UNKNOWN detekce pro HuggingFace model → předpokládáme SEPARATE_TXT")
                        com.example.llama.data.BertModel.Companion.VocabStrategy.SEPARATE_TXT
                    }
                    com.example.llama.data.BertModel.Companion.ModelSource.KAGGLE,
                    com.example.llama.data.BertModel.Companion.ModelSource.TFLITE_HUB  -> {
                        Log.w(TAG, "UNKNOWN detekce pro Kaggle/TFHub model → předpokládáme EMBEDDED_METADATA")
                        com.example.llama.data.BertModel.Companion.VocabStrategy.EMBEDDED_METADATA
                    }
                    else -> {
                        Log.w(TAG, "UNKNOWN detekce, neznámý zdroj → fallback BUNDLED_ASSET")
                        com.example.llama.data.BertModel.Companion.VocabStrategy.BUNDLED_ASSET
                    }
                }
            }
        }
    }
}
