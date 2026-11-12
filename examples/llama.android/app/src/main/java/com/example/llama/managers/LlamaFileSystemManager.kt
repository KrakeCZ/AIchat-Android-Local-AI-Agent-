package com.example.llama.managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class LlamaFileSystemManager(private val context: Context) {

    companion object {
        private const val TAG = "LlamaFileSystem"
        private const val MODELS_DIR = "models"
    }

    /**
     * Importuje model z URI do privátního úložiště aplikace.
     * Vrací absolutní cestu k novému souboru a jeho velikost.
     */
    fun importModelToInternalStorage(uri: Uri): Pair<File, Long> {
        val (fileName, _) = getFileMetadata(uri)
        val modelsDir = File(context.filesDir, MODELS_DIR)
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }

        // Vytvoříme soubor. Pokud existuje, přidáme timestamp, aby se nepřepsal
        var destFile = File(modelsDir, fileName)
        if (destFile.exists()) {
            destFile = File(modelsDir, "${System.currentTimeMillis()}_$fileName")
        }

        Log.i(TAG, "Starting import: $uri -> ${destFile.absolutePath}")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8 * 1024) // 8KB buffer
                    var bytesRead: Int
                    var totalBytes = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            // Úklid po chybě - nenecháme tam půlku souboru
            if (destFile.exists()) {
                destFile.delete()
            }
            throw IOException("Failed to import model: ${e.message}", e)
        }

        Log.i(TAG, "Import successful: ${destFile.absolutePath}")
        return Pair(destFile, destFile.length())
    }

    /**
     * Smaže soubor modelu z disku.
     */
    fun deleteModelFile(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted file: $path ($deleted)")
            deleted
        } else {
            true // Soubor už neexistuje, považujeme za smazané
        }
    }

    private fun getFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "model.gguf"
        var size = 0L

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)

                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: name
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot read metadata: ${e.message}")
        }
        return Pair(name, size)
    }
}
