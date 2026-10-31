package com.example.llama.data.gen

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Entita reprezentující importovaný model pro GEN_Studio (Video / Obraz / Vizuální tokeny).
 * Navrženo pro systémovou modularitu a kompatibilitu s Android 15/16+.
 */
@Entity(tableName = "gen_models")
data class GenModel(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    val displayName: String,
    val sourceUri: String,
    val filePath: String,
    val fileSize: Long,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastAccessTime: Long = System.currentTimeMillis(),

    // Architektonická klasifikace
    val format: String,        // "GGUF", "TASK", "ONNX", "SAFETENSORS", "BIN"
    val architecture: String,  // "FLOW_MATCHING_DIT", "AUTOREGRESSIVE", "SLIDING_TILE", "CONVOLUTIONAL", "WORLD_MODEL"
    val quantization: String,  // "Q4_K_M", "Q3_K_S", "Q8_0", "FP16", "FP8", "INT4", "INT8"

    // Připojené podsítě (Sequential Offload)
    val textEncoderPath: String? = null,
    val vaePath: String? = null,

    // Výchozí parametry inference
    val defaultSteps: Int = 12,
    val defaultGuidance: Float = 5.0f,
    val defaultFps: Int = 12,
    val defaultWidth: Int = 480,
    val defaultHeight: Int = 272,
    val supportsVideo: Boolean = true,
    val supportsImage: Boolean = true,
    val isDistilled1Step: Boolean = false,
    val isBuiltIn: Boolean = false
)
