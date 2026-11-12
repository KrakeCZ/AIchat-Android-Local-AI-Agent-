package com.example.llama.data

import android.content.Context
import android.net.Uri
import com.example.llama.managers.WhisperFileSystemManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class WhisperModelRepository(private val context: Context) {

    private val database = WhisperDatabase.getDatabase(context)
    private val dao = database.whisperModelDao()
    private val fileSystemManager = WhisperFileSystemManager(context)

    // Ukládání ID aktivního modelu
    private val prefs = context.getSharedPreferences("whisper_prefs", Context.MODE_PRIVATE)
    private val KEY_ACTIVE_MODEL_ID = "active_whisper_model_id"

    val allModels: Flow<List<WhisperModel>> = dao.getAllModels()

    suspend fun getModel(id: String): WhisperModel? {
        return dao.getModelById(id)
    }

    suspend fun deleteModel(model: WhisperModel) =
            withContext(Dispatchers.IO) {
                // Smazat fyzický soubor
                fileSystemManager.deleteModelFile(model.sourceUri)
                // Smazat z DB
                dao.deleteModel(model)

                // Pokud jsme smazali aktivní model, resetujeme preferenci
                if (getActiveModelId() == model.id) {
                    setActiveModelId(null)
                }
            }

    suspend fun importNewModel(uri: Uri): String =
            withContext(Dispatchers.IO) {
                // Fyzický Import (Kopírování do bezpečného sandboxu)
                val (file, size) = fileSystemManager.importModelToInternalStorage(uri)

                val newModel =
                        WhisperModel(
                                displayName = file.name,
                                sourceUri = file.absolutePath,
                                fileSize = size
                        )
                dao.insertModel(newModel)

                // Automaticky nastavit importovaný model jako aktivní, pokud žádný neexistuje
                if (getActiveModelId() == null) {
                    setActiveModelId(newModel.id)
                }

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

    /** Vrátí absolutní cestu k aktuálně zvolenému Whisper modelu, nebo null, pokud chybí. */
    suspend fun getActiveModelPath(): String? =
            withContext(Dispatchers.IO) {
                val activeId = getActiveModelId() ?: return@withContext null
                val model = dao.getModelById(activeId) ?: return@withContext null

                val file = File(model.sourceUri)
                if (!file.exists()) {
                    return@withContext null
                }

                dao.updateLastAccess(activeId, System.currentTimeMillis())
                return@withContext model.sourceUri
            }
}
