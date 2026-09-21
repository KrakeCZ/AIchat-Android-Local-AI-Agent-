package com.example.llama.data

import kotlinx.coroutines.flow.Flow

class BertModelRepository(private val bertModelDao: BertModelDao) {

    val allModelsFlow: Flow<List<BertModel>> = bertModelDao.getAllModelsFlow()

    suspend fun getAllModels(): List<BertModel> {
        return bertModelDao.getAllModels()
    }

    /** Vrátí pouze model záznamy (isVocabFile = false) */
    suspend fun getBertModelsOnly(): List<BertModel> {
        return bertModelDao.getBertModelsOnly()
    }

    /** Vrátí pouze vocab záznamy (isVocabFile = true) */
    suspend fun getVocabFiles(): List<BertModel> {
        return bertModelDao.getVocabFiles()
    }

    /**
     * Vrátí vocab záznam navázaný na daný model přes linkedModelId.
     * Null = žádný uživatelský vocab → engine použije fallback (bundled asset nebo embedded metadata).
     */
    suspend fun getVocabForModel(modelId: String): BertModel? {
        return bertModelDao.getVocabForModel(modelId)
    }

    suspend fun insert(model: BertModel) {
        bertModelDao.insert(model)
    }

    suspend fun delete(model: BertModel) {
        bertModelDao.delete(model)
    }

    suspend fun updateLastAccessTime(id: String) {
        bertModelDao.updateLastAccessTime(id)
    }
}
