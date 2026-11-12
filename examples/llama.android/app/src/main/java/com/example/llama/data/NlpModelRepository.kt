package com.example.llama.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.llama.managers.NlpFileSystemManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Repository pro správu NLP/BERT modelů.
 * Architektura 1:1 WhisperModelRepository.
 *
 * Priorita resolution:
 * 1. Aktivní custom model z DB (uživatel importoval přes Settings → NLP)
 * 2. Bundled default model z assets/nlp_models/bert_classifier.tflite
 *
 * Custom modely: Uživatel může stáhnout fine-tuned BERT z internetu
 * (např. CZ sentiment, medical classifier) a importovat přes file picker.
 */
class NlpModelRepository(private val context: Context) {

    companion object {
        private const val TAG = "NlpModelRepository"
        /** Název default BERT modelu v assets/nlp_models/ */
        const val DEFAULT_ASSET_MODEL = "nlp_models/bert_classifier.tflite"
    }

    private val database = NlpDatabase.getDatabase(context)
    private val dao = database.nlpModelDao()
    private val fileSystemManager = NlpFileSystemManager(context)

    // Ukládání ID aktivního modelu
    private val prefs = context.getSharedPreferences("nlp_prefs", Context.MODE_PRIVATE)
    private val KEY_ACTIVE_MODEL_ID = "active_nlp_model_id"

    val allModels: Flow<List<NlpModel>> = dao.getAllModels()

    fun getSentimentModels(): Flow<List<NlpModel>> =
        dao.getModelsByType(NlpModel.MODEL_TYPE_SENTIMENT)

    suspend fun getModel(id: String): NlpModel? {
        return dao.getModelById(id)
    }

    suspend fun deleteModel(model: NlpModel) =
            withContext(Dispatchers.IO) {
                // Smazat fyzický soubor
                fileSystemManager.deleteModelFile(model.sourceUri)
                // Smazat z DB
                dao.deleteModel(model)

                // Pokud jsme smazali aktivní model, resetujeme preferenci
                if (getActiveModelId() == model.id) {
                    setActiveModelId(null)
                }
                Log.i(TAG, "🗑️ NLP model smazán: ${model.displayName}")
            }

    /**
     * Importuje NLP model (.tflite) z URI do interního úložiště a zapíše do DB.
     *
     * @param uri URI z file pickeru
     * @param modelType Typ modelu (MODEL_TYPE_SENTIMENT nebo MODEL_TYPE_CLASSIFIER)
     * @return ID nového modelu
     */
    suspend fun importNewModel(
        uri: Uri,
        modelType: String = NlpModel.MODEL_TYPE_SENTIMENT
    ): String =
            withContext(Dispatchers.IO) {
                // Fyzický import (kopírování do sandboxu)
                val (file, size) = fileSystemManager.importModelToInternalStorage(uri)

                val newModel =
                        NlpModel(
                                displayName = file.name,
                                sourceUri = file.absolutePath,
                                fileSize = size,
                                modelType = modelType
                        )
                dao.insertModel(newModel)

                // Automaticky nastavit jako aktivní, pokud žádný custom neexistuje
                if (getActiveModelId() == null) {
                    setActiveModelId(newModel.id)
                }

                Log.i(TAG, "✅ NLP model importován: ${file.name} (${size / 1024} KB)")
                return@withContext newModel.id
            }

    fun setActiveModelId(modelId: String?) {
        if (modelId == null) {
            prefs.edit().remove(KEY_ACTIVE_MODEL_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_MODEL_ID, modelId).apply()
        }
    }

    fun getActiveModelId(): String? {
        return prefs.getString(KEY_ACTIVE_MODEL_ID, null)
    }

    /**
     * Vrátí absolutní cestu k aktuálně zvolenému NLP modelu.
     *
     * Priorita:
     * 1. Custom model z DB (uživatel importoval)
     * 2. null (volající použije bundled assets fallback)
     */
    suspend fun getActiveModelPath(): String? =
            withContext(Dispatchers.IO) {
                val activeId = getActiveModelId() ?: return@withContext null
                val model = dao.getModelById(activeId) ?: return@withContext null

                val file = File(model.sourceUri)
                if (!file.exists()) {
                    Log.w(TAG, "⚠️ Custom model soubor neexistuje: ${model.sourceUri}")
                    return@withContext null
                }

                dao.updateLastAccess(activeId, System.currentTimeMillis())
                return@withContext model.sourceUri
            }

    /**
     * Zkontroluje, zda je bundled default BERT model v assets.
     */
    fun isDefaultModelAvailable(): Boolean {
        return try {
            context.assets.open(DEFAULT_ASSET_MODEL).use { true }
        } catch (_: Exception) {
            false
        }
    }

    // ==================== MEDIAPIPE LLM MODEL RESOLUTION ====================

    /**
     * Vrátí absolutní cestu k nejnovějšímu MediaPipe LLM modelu (GGUF/Task).
     * Používá MediaPipeLlmWorker k nalezení cesty modelu.
     *
     * @return Absolutní cesta nebo null (žádný model importován)
     */
    suspend fun getMediaPipeLlmModelPath(): String? =
            withContext(Dispatchers.IO) {
                val model = dao.getLatestMediaPipeLlm() ?: return@withContext null
                val file = File(model.sourceUri)
                if (!file.exists()) {
                    Log.w(TAG, "⚠️ MediaPipe LLM model soubor neexistuje: ${model.sourceUri}")
                    return@withContext null
                }
                dao.updateLastAccess(model.id, System.currentTimeMillis())
                return@withContext model.sourceUri
            }

    /**
     * Importuje MediaPipe LLM model (.task / .bin / .gguf) z URI.
     * Deleguje na standardní importNewModel s typem MODEL_TYPE_MEDIAPIPE_LLM.
     */
    suspend fun importMediaPipeLlmModel(uri: Uri): String {
        return importNewModel(uri, NlpModel.MODEL_TYPE_MEDIAPIPE_LLM)
    }

    /**
     * Vrátí info o aktuálně nahraném MediaPipe LLM modelu (pro zobrazení v Settings UI).
     */
    suspend fun getMediaPipeLlmInfo(): NlpModel? =
            withContext(Dispatchers.IO) {
                dao.getLatestMediaPipeLlm()
            }
}
