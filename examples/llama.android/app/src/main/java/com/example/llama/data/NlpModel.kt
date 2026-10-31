package com.example.llama.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entita reprezentující importovaný NLP/BERT model (.tflite).
 *
 * Architektura je 1:1 kopie WhisperModel — uživatel může importovat vlastní
 * fine-tuned BERT modely (např. CZ sentiment, custom classifier) přes file picker
 * v Settings → NLP. Default model je bundled v assets/nlp_models/.
 *
 * Typy modelů:
 * - SENTIMENT: MobileBERT TextClassifier (SST-2 / custom)
 * - CLASSIFIER: Custom text classifier (MediaPipe TextClassifier kompatibilní)
 */
@Entity(tableName = "nlp_models")
data class NlpModel(
        @PrimaryKey val id: String = UUID.randomUUID().toString(),
        val displayName: String,
        val sourceUri: String, // Absolutní cesta k souboru v interním úložišti
        val fileSize: Long,
        val modelType: String = MODEL_TYPE_SENTIMENT, // "sentiment" | "classifier"
        val lastAccessTime: Long = System.currentTimeMillis()
) {
    companion object {
        const val MODEL_TYPE_SENTIMENT = "sentiment"
        const val MODEL_TYPE_CLASSIFIER = "classifier"
        const val MODEL_TYPE_MEDIAPIPE_LLM = "mediapipe_llm"
    }
}
