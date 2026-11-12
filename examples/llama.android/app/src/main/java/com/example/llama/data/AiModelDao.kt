package com.example.llama.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AiModelDao {
    // Vrací seznam modelů seřazený podle posledního použití
    @Query("SELECT * FROM ai_models ORDER BY lastAccessTime DESC")
    fun getAllModels(): Flow<List<AiModel>>

    @Query("SELECT * FROM ai_models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): AiModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: AiModel)

    @Delete
    suspend fun deleteModel(model: AiModel)

    @Query("UPDATE ai_models SET lastAccessTime = :time WHERE id = :id")
    suspend fun updateLastAccess(id: String, time: Long)
}
