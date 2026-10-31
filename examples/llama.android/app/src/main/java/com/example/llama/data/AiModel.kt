package com.example.llama.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entita reprezentující importovaný AI model.
 */
@Entity(tableName = "ai_models")
data class AiModel(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val displayName: String,
    val sourceUri: String,
    val fileSize: Long,
    val lastAccessTime: Long = System.currentTimeMillis(),

    // Settings
    val contextSize: Int = 0,
    val threadCount: Int = 4,
    val useMmap: Boolean = true
)
