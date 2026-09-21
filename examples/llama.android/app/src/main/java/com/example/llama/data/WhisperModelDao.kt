package com.example.llama.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Rozhraní pro přístup k tabulce whisper_models */
@Dao
interface WhisperModelDao {
    @Query("SELECT * FROM whisper_models ORDER BY lastAccessTime DESC")
    fun getAllModels(): Flow<List<WhisperModel>>

    @Query("SELECT * FROM whisper_models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): WhisperModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertModel(model: WhisperModel)

    @Delete suspend fun deleteModel(model: WhisperModel)

    @Query("UPDATE whisper_models SET lastAccessTime = :time WHERE id = :id")
    suspend fun updateLastAccess(id: String, time: Long)
}
