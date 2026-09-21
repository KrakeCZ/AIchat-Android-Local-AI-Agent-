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
