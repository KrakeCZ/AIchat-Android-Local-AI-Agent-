package com.example.llama.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BertModelDao {

    /** Všechny záznamy (modely i vocab soubory) seřazené dle posledního přístupu */
    @Query("SELECT * FROM bert_models ORDER BY lastAccessTime DESC")
    fun getAllModelsFlow(): Flow<List<BertModel>>

    @Query("SELECT * FROM bert_models ORDER BY lastAccessTime DESC")
    suspend fun getAllModels(): List<BertModel>

    /** Pouze model záznamy (isVocabFile = 0), bez vocab záznamů */
    @Query("SELECT * FROM bert_models WHERE isVocabFile = 0 ORDER BY lastAccessTime DESC")
    suspend fun getBertModelsOnly(): List<BertModel>

    /** Pouze vocab záznamy (isVocabFile = 1) */
    @Query("SELECT * FROM bert_models WHERE isVocabFile = 1 ORDER BY lastAccessTime DESC")
    suspend fun getVocabFiles(): List<BertModel>

    /**
     * Najde vocab záznam přímo navázaný na konkrétní model přes linkedModelId.
     * Vrátí první nalezený (LIMIT 1) — předpokládáme 1 vocab na 1 model.
     */
    @Query("SELECT * FROM bert_models WHERE linkedModelId = :modelId AND isVocabFile = 1 LIMIT 1")
    suspend fun getVocabForModel(modelId: String): BertModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: BertModel)

    @Delete
    suspend fun delete(model: BertModel)

    @Query("UPDATE bert_models SET lastAccessTime = :time WHERE id = :id")
    suspend fun updateLastAccessTime(id: String, time: Long = System.currentTimeMillis())
}
