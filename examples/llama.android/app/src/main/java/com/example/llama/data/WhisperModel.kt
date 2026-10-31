package com.example.llama.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/** Entita reprezentující importovaný Whisper.cpp (GGUF) model. */
@Entity(tableName = "whisper_models")
data class WhisperModel(
        @PrimaryKey val id: String = UUID.randomUUID().toString(),
        val displayName: String,
        val sourceUri: String, // Absolutní cesta k souboru v interním úložišti (např.
        // whisper_models/ggml-tiny.bin)
        val fileSize: Long,
        val lastAccessTime: Long = System.currentTimeMillis()
)
