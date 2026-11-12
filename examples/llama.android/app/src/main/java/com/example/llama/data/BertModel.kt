package com.example.llama.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entita reprezentující TFLite on-device NLP model pro LiteRT (BERT atd.).
 *
 * Podporuje dva typy záznamů:
 * - Model záznam: isVocabFile = false
 * - Vocab záznam: isVocabFile = true, linkedModelId = ID parent modelu
 *
 * VocabStrategy určuje, jak engine načte slovník:
 * - BUNDLED_ASSET: vestavěný Google vocab z APK assets/
 * - SEPARATE_TXT: uživatelem importovaný vocab.txt (HuggingFace workflow)
 * - EMBEDDED_METADATA: vocab je uložen přímo uvnitř .tflite souboru (MediaPipe/TFHub)
 *
 * ModelSource určuje původ modelu — ovlivňuje UI import wizard:
 * - BUNDLED_GOOGLE: vestavěný model z APK, nelze smazat
 * - HUGGINGFACE: vyžaduje separátní vocab.txt
 * - KAGGLE / TFLITE_HUB: vocab embedded, stačí .tflite
 * - CUSTOM: uživatel volí strategii manuálně
 */
@Entity(tableName = "bert_models")
data class BertModel(
        @PrimaryKey val id: String = UUID.randomUUID().toString(),
        val displayName: String,
        val sourceUri: String,         // Absolutní cesta nebo "asset://..." pro bundled
        val fileSize: Long,
        val isVocabFile: Boolean = false,                          // true = vocab záznam
        val modelSource: String = ModelSource.BUNDLED_GOOGLE,      // Odkud model pochází
        val vocabStrategy: String = VocabStrategy.BUNDLED_ASSET,   // Jak načíst vocab
        val linkedModelId: String? = null,                         // Pro vocab: ID parent modelu
        val lastAccessTime: Long = System.currentTimeMillis()
) {
    companion object {

        /** Konstanty pro pole modelSource */
        object ModelSource {
            const val BUNDLED_GOOGLE = "bundled_google"   // Vestavěný v APK
            const val HUGGINGFACE   = "huggingface"       // z HuggingFace Hub (optimum export)
            const val KAGGLE        = "kaggle"             // z Kaggle Notebooks
            const val TFLITE_HUB   = "tflite_hub"         // z TFLite Model Hub / Google CDN
            const val CUSTOM        = "custom"             // Vlastní trénování / fine-tuning
        }

        /** Konstanty pro pole vocabStrategy */
        object VocabStrategy {
            /** Vocab je vestavěný v APK assets — nevyžaduje import */
            const val BUNDLED_ASSET     = "bundled_asset"
            /** Vocab je uložen jako separátní vocab.txt — importován uživatelem */
            const val SEPARATE_TXT      = "separate_txt"
            /** Vocab je embedded přímo uvnitř .tflite metadata — Task Library ho načte sama */
            const val EMBEDDED_METADATA = "embedded_metadata"
        }
    }
}
