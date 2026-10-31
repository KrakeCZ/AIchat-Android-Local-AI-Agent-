package com.example.llama.agent.perception

import android.content.Context
import android.util.Log
import com.example.llama.data.BertDatabase
import com.example.llama.data.BertModel
import com.example.llama.data.BertModelRepository
import java.io.File

/**
 * BertModelManager — Master vrstva pro dynamické načítání TFLite modelů a slovníků.
 *
 * Chování (prioritní pořadí):
 * 1. Hledá uživatelem naimportované modely v Room DB
 * 2. Pro vocab: hledá záznam přímo navázaný na aktivní model přes linkedModelId
 * 3. Fallback: vrací "asset://..." odkaz na bundled modely z APK assets
 *
 * Integrace s TfliteVocabDetector:
 * - Při importu se auto-detekuje, zda .tflite vyžaduje externí vocab (viz TfliteVocabDetector)
 * - Výsledek je uložen do pole vocabStrategy v BertModel záznamu
 * - Tento manager pak respektuje uloženou strategii při výběru vocab cesty
 */
object BertModelManager {

    private const val TAG = "BertModelManager"

    // ==================== Asset prefix ====================
    const val ASSET_PREFIX = "asset://"

    // ==================== Bundled (APK assets) fallback cesty ====================
    private const val DEFAULT_MINI_ASSET   = "nlp_models/bert_mini_int8.tflite"
    private const val DEFAULT_SQUAD_ASSET  = "nlp_models/mobilebert_squad_int8.tflite"
    private const val DEFAULT_VOCAB_ASSET  = "nlp_models/bert_vocab.txt"

    // ==================== Model path resolution ====================

    /**
     * Vrátí cestu k hlavnímu TFLite modelu.
     *
     * @param requireSquad  true = preferovat SQuAD QA model, false = BERT mini (default)
     * @return Absolutní cesta na disk (uživatelský import) NEBO "asset://..." (bundled fallback)
     */
    suspend fun getActiveModelPath(context: Context, requireSquad: Boolean = false): String {
        val repository = getRepository(context)

        try {
            val models = repository.getBertModelsOnly()
            if (models.isNotEmpty()) {
                val targetModel = if (requireSquad) {
                    // SQuAD preferujeme model obsahující "squad" v názvu/cestě
                    models.maxByOrNull {
                        if (it.sourceUri.contains("squad", ignoreCase = true)) 1 else 0
                    } ?: models.firstOrNull()
                } else {
                    // Pre foundation (ToolbarBertWorker) preferujeme modely, které nejsou explicitně
                    // importované uživatelem spíše jako "sentiment", ale raději "mini", "base", "foundation".
                    // Zabráníme tomu, aby Sentiment model způsobil pád NLI / Tone hlavy.
                    models.filter { 
                        it.displayName.contains("mini", ignoreCase = true) || 
                        it.displayName.contains("base", ignoreCase = true) ||
                        it.modelSource == BertModel.Companion.ModelSource.BUNDLED_GOOGLE
                    }.maxByOrNull { it.lastAccessTime } ?: models.maxByOrNull { it.lastAccessTime }
                }

                targetModel?.let { model ->
                    val resolvedPath = resolvePath(model.sourceUri)
                    val f = File(resolvedPath)
                    if (f.exists() && f.canRead()) {
                        Log.i(TAG, "✅ Model z Repository: ${f.absolutePath} (${f.length() / 1024} KB)")
                        return f.absolutePath
                    } else {
                        Log.w(TAG, "⚠️ Model nalezen v DB, ale fyzický soubor chybí: ${model.sourceUri}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při načítání modelu z DB: ${e.message}")
        }

        // Fallback → bundled assets
        val fallback = if (requireSquad) DEFAULT_SQUAD_ASSET else DEFAULT_MINI_ASSET
        Log.i(TAG, "Fallback na bundled asset: $fallback")
        return "$ASSET_PREFIX$fallback"
    }

    // ==================== Vocab path resolution ====================

    /**
     * Vrátí cestu k vocab souboru pro aktivní model.
     *
     * Logika výběru:
     * 1. Aktivní model má [BertModel.VocabStrategy.EMBEDDED_METADATA]
     *    → vocab není potřeba, vrátí speciální marker [VOCAB_EMBEDDED_MARKER]
     * 2. Aktivní model má [BertModel.VocabStrategy.SEPARATE_TXT]
     *    → hledá linkedModelId vocab záznam v DB
     *    → pokud nalezeno a soubor existuje → absolutní cesta
     *    → fallback → bundled Google vocab
     * 3. Aktivní model má [BertModel.VocabStrategy.BUNDLED_ASSET] nebo žádný import
     *    → vrátí "asset://nlp_models/bert_vocab.txt"
     *
     * @return Absolutní cesta k vocab souboru, "asset://..." nebo [VOCAB_EMBEDDED_MARKER]
     */
    suspend fun getActiveVocabPath(context: Context): String {
        val repository = getRepository(context)

        try {
            val models = repository.getBertModelsOnly()
            val activeModel = models.maxByOrNull { it.lastAccessTime }

            if (activeModel != null) {
                when (activeModel.vocabStrategy) {

                    BertModel.Companion.VocabStrategy.EMBEDDED_METADATA -> {
                        // Vocab je uvnitř .tflite — BertTokenizer se nepoužívá
                        Log.i(TAG, "Model ${activeModel.displayName}: vocab embedded v metadata")
                        return VOCAB_EMBEDDED_MARKER
                    }

                    BertModel.Companion.VocabStrategy.SEPARATE_TXT -> {
                        // Hledáme navázaný vocab záznam
                        val vocabRecord = repository.getVocabForModel(activeModel.id)
                        if (vocabRecord != null) {
                            val vocabFile = File(vocabRecord.sourceUri)
                            if (vocabFile.exists() && vocabFile.canRead()) {
                                Log.i(TAG, "✅ Vocab z DB: ${vocabFile.absolutePath}")
                                return vocabFile.absolutePath
                            } else {
                                Log.w(TAG, "⚠️ Vocab nalezen v DB, ale soubor chybí: ${vocabRecord.sourceUri}")
                            }
                        } else {
                            Log.w(TAG, "⚠️ Model ${activeModel.displayName} vyžaduje external vocab, ale žádný není importován → fallback asset")
                        }
                    }

                    BertModel.Companion.VocabStrategy.BUNDLED_ASSET -> {
                        // Explicitně zvolena bundled strategie
                        Log.i(TAG, "Model ${activeModel.displayName}: použit bundled vocab asset")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při načítání vocab z DB: ${e.message}")
        }

        // Fallback → bundled Google vocab z APK assets
        Log.i(TAG, "Fallback na bundled vocab: $DEFAULT_VOCAB_ASSET")
        return "$ASSET_PREFIX$DEFAULT_VOCAB_ASSET"
    }

    // ==================== Helpers ====================

    /**
     * Speciální marker: vocab je embedded uvnitř .tflite (MediaPipe style).
     * BertTokenizer při obdržení tohoto markeru přeskočí inicializaci vocab.
     */
    const val VOCAB_EMBEDDED_MARKER = "embedded://metadata"

    /** Resolves "asset://..." prefix na reálnou cestu pro File() — vrátí jen vnitřní část. */
    private fun resolvePath(sourceUri: String): String {
        return if (sourceUri.startsWith(ASSET_PREFIX)) {
            sourceUri.removePrefix(ASSET_PREFIX)
        } else {
            sourceUri
        }
    }

    private fun getRepository(context: Context): BertModelRepository {
        val db = BertDatabase.getDatabase(context)
        return BertModelRepository(db.bertModelDao())
    }
}
