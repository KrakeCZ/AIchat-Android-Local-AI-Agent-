package com.example.llama.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Rozhraní pro přístup k tabulce nlp_models. Vzor 1:1 WhisperModelDao. */
@Dao
interface NlpModelDao {
    @Query("SELECT * FROM nlp_models ORDER BY lastAccessTime DESC")
    fun getAllModels(): Flow<List<NlpModel>>

    @Query("SELECT * FROM nlp_models WHERE modelType = :type ORDER BY lastAccessTime DESC")
    fun getModelsByType(type: String): Flow<List<NlpModel>>

    @Query("SELECT * FROM nlp_models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): NlpModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: NlpModel)

    @Delete
    suspend fun deleteModel(model: NlpModel)

    @Query("SELECT * FROM nlp_models WHERE modelType = 'mediapipe_llm' ORDER BY lastAccessTime DESC LIMIT 1")
    suspend fun getLatestMediaPipeLlm(): NlpModel?

    @Query("UPDATE nlp_models SET lastAccessTime = :time WHERE id = :id")
    suspend fun updateLastAccess(id: String, time: Long)
}
