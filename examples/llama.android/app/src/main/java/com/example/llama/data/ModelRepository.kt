package com.example.llama.data

import android.content.Context
import android.net.Uri
import com.example.llama.managers.LlamaFileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class ModelRepository(context: Context) {

    private val database = LlamaDatabase.getDatabase(context)
    private val dao = database.aiModelDao()
    private val fileSystemManager = LlamaFileSystemManager(context)

    // Seznam všech modelů v DB (reaktivní stream)
    val allModels: Flow<List<AiModel>> = dao.getAllModels()

    suspend fun getModel(id: String): AiModel? {
        return dao.getModelById(id)
    }

    suspend fun deleteModel(model: AiModel) = withContext(Dispatchers.IO) {
        // 1. Smazat fyzický soubor
        fileSystemManager.deleteModelFile(model.sourceUri) // Zde předpokládáme, že sourceUri je nyní lokální cesta
        // 2. Smazat z DB
        dao.deleteModel(model)
    }

    suspend fun importNewModel(uri: Uri): String = withContext(Dispatchers.IO) {
        // 1. Fyzický Import (Kopírování)
        val (file, size) = fileSystemManager.importModelToInternalStorage(uri)

        // 2. Uložení do DB
        // DŮLEŽITÉ: Do sourceUri ukládáme absolutní cestu k souboru (file:///data/...), ne content://
        val newModel = AiModel(
            displayName = file.name,
            sourceUri = file.absolutePath,
            fileSize = size
        )
        dao.insertModel(newModel)
        return@withContext newModel.id
    }

    suspend fun prepareModelPathForNative(modelId: String): String = withContext(Dispatchers.IO) {
        val model = dao.getModelById(modelId)
            ?: throw IllegalArgumentException("Model s ID $modelId nebyl nalezen v DB.")

        dao.updateLastAccess(modelId, System.currentTimeMillis())

        // Protože jsme soubor zkopírovali při importu, sourceUri JE nyní cesta k souboru.
        // Pro jistotu ověříme existenci.
        val file = File(model.sourceUri)
        if (!file.exists()) {
             throw java.io.FileNotFoundException("Soubor modelu neexistuje na cestě: ${model.sourceUri}")
        }

        return@withContext model.sourceUri
    }
}
