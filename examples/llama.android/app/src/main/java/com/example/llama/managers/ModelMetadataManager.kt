package com.example.llama.managers

import android.util.Log
import android.widget.TextView
import com.arm.aichat.gguf.GgufMetadataReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Zodpovídá za bezpečné čtení hlaviček GGUF souborů pro UI.
 * Používá existující knihovnu com.arm.aichat.gguf.
 */
object ModelMetadataManager {

    private const val TAG = "ModelMetadataManager"

    /**
     * Bezpečně načte a vrátí strukturovaná metadata GGUF modelu.
     */
    suspend fun readMetadata(filePath: String): com.arm.aichat.gguf.GgufMetadata? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) return@withContext null
        try {
            FileInputStream(file).use { inputStream ->
                GgufMetadataReader.create().readStructuredMetadata(inputStream)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse GGUF metadata for $filePath: ${e.message}")
            null
        }
    }

    /**
     * Detekuje, zda model podporuje nativní multimediální vstup (vize, audio, video) bez nutnosti externího mmproj.
     */
    suspend fun isMultimodalModel(filePath: String): Boolean {
        val meta = readMetadata(filePath) ?: return false
        return isMultimodalModel(meta)
    }

    /**
     * Zkontroluje architekturu a metadata, zda se jedná o All-in-One multimodal model.
     */
    fun isMultimodalModel(meta: com.arm.aichat.gguf.GgufMetadata): Boolean {
        val arch = meta.architecture?.architecture?.lowercase() ?: ""
        val multimodalArchs = setOf(
            "mllama", "qwen2vl", "qwen2_vl", "minicpmv", "llava", "clip",
            "whisper", "internvl", "idefics", "idefics2", "idefics3",
            "chameleon", "moondream", "pixtral", "voxtral", "gemini", "phi3v"
        )
        if (arch in multimodalArchs) return true

        val tags = meta.additional?.tags ?: emptyList()
        if (tags.any { tag ->
            tag.contains("vision", ignoreCase = true) ||
            tag.contains("multimodal", ignoreCase = true) ||
            tag.contains("audio", ignoreCase = true) ||
            tag.contains("video", ignoreCase = true) ||
            tag.contains("ocr", ignoreCase = true) ||
            tag.contains("omni", ignoreCase = true)
        }) return true

        val desc = meta.additional?.description ?: ""
        if (desc.contains("multimodal", ignoreCase = true) || desc.contains("vision-language", ignoreCase = true)) {
            return true
        }

        return false
    }

    /**
     * Přečte metadata souboru a vypíše je do TextView.
     * Pokud se něco pokazí, AI Engine to neohrozí, jen se vypíše chyba do textu.
     */
    suspend fun displayMetadata(filePath: String, targetView: TextView) {
        withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists() || !file.canRead()) {
                    updateText(targetView, "Error: File not accessible")
                    return@withContext
                }

                // Používáme tvou knihovnu GgufMetadataReader
                // Otevřeme stream jen na nezbytně nutnou dobu
                FileInputStream(file).use { inputStream ->
                    val reader = GgufMetadataReader.create() 
                    val metadata = reader.readStructuredMetadata(inputStream)
                    
                    // Úspěch -> Zobrazíme toString() objektu GgufMetadata
                    updateText(targetView, metadata.toString())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read GGUF metadata for UI", e)
                // Nevyhazujeme výjimku dál, ať nezhroutíme načítání Enginu
                updateText(targetView, "Metadata Error: ${e.message}")
            }
        }
    }

    private suspend fun updateText(view: TextView, text: String) {
        withContext(Dispatchers.Main) {
            view.text = text
        }
    }
}
