package com.example.llama.data.gen

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenModelDao {
    @Query("SELECT * FROM gen_models ORDER BY lastAccessTime DESC")
    fun getAllModels(): Flow<List<GenModel>>

    @Query("SELECT * FROM gen_models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): GenModel?

    @Query("SELECT * FROM gen_models WHERE supportsVideo = 1 ORDER BY lastAccessTime DESC")
    fun getVideoModels(): Flow<List<GenModel>>

    @Query("SELECT * FROM gen_models WHERE supportsImage = 1 ORDER BY lastAccessTime DESC")
    fun getImageModels(): Flow<List<GenModel>>

    @Query("SELECT * FROM gen_models WHERE supportsVideo = 1 ORDER BY lastAccessTime DESC LIMIT 1")
    suspend fun getLatestVideoModel(): GenModel?

    @Query("SELECT * FROM gen_models WHERE supportsImage = 1 ORDER BY lastAccessTime DESC LIMIT 1")
    suspend fun getLatestImageModel(): GenModel?

    @Query("SELECT * FROM gen_models ORDER BY lastAccessTime DESC LIMIT 1")
    suspend fun getLatestModel(): GenModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: GenModel)

    @Delete
    suspend fun deleteModel(model: GenModel)

    @Query("UPDATE gen_models SET lastAccessTime = :time WHERE id = :id")
    suspend fun updateLastAccess(id: String, time: Long)

    @Query("UPDATE gen_models SET textEncoderPath = :path WHERE id = :id")
    suspend fun updateTextEncoderPath(id: String, path: String?)

    @Query("UPDATE gen_models SET vaePath = :path WHERE id = :id")
    suspend fun updateVaePath(id: String, path: String?)

    @Query("SELECT * FROM gen_models WHERE LOWER(displayName) LIKE '%vae%' OR LOWER(filePath) LIKE '%vae%' LIMIT 1")
    suspend fun findAnyVaeModel(): GenModel?

    @Query("SELECT * FROM gen_models WHERE LOWER(displayName) LIKE '%t5%' OR LOWER(displayName) LIKE '%clip%' OR LOWER(displayName) LIKE '%encoder%' LIMIT 1")
    suspend fun findAnyTextEncoderModel(): GenModel?

    @Query("UPDATE gen_models SET supportsVideo = 0, supportsImage = 0, architecture = 'VAE_DECODER' WHERE (LOWER(displayName) LIKE '%vae%' OR LOWER(filePath) LIKE '%vae%') AND architecture != 'VAE_DECODER'")
    suspend fun sanitizeVaeModels()

    @Query("UPDATE gen_models SET supportsVideo = 0, supportsImage = 0, architecture = 'TEXT_ENCODER' WHERE (LOWER(displayName) LIKE '%umt5%' OR LOWER(displayName) LIKE '%clip%' OR (LOWER(displayName) LIKE '%t5%' AND LOWER(displayName) NOT LIKE '%t2v%' AND LOWER(displayName) NOT LIKE '%i2v%') OR LOWER(filePath) LIKE '%umt5%' OR LOWER(filePath) LIKE '%clip%' OR (LOWER(filePath) LIKE '%t5%' AND LOWER(filePath) NOT LIKE '%t2v%' AND LOWER(filePath) NOT LIKE '%i2v%')) AND architecture != 'TEXT_ENCODER'")
    suspend fun sanitizeTextEncoderModels()
}
