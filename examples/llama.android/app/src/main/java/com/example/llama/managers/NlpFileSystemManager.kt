package com.example.llama.managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Správce souborového systému pro NLP/BERT modely.
 * Architektura 1:1 WhisperFileSystemManager.
 *
 * Modely se ukládají do: filesDir/nlp_models/
 * Např.: /data/data/com.example.llama/files/nlp_models/bert_classifier.tflite
 */
class NlpFileSystemManager(private val context: Context) {

    companion object {
        private const val TAG = "NlpFileSystem"
        const val MODELS_DIR = "nlp_models"
    }

    /**
     * Importuje NLP model (.tflite) z URI do privátního úložiště aplikace.
     * Vrací absolutní cestu k novému souboru a jeho velikost.
     *
     * @param uri URI z file pickeru (SAF)
     * @return Pair(soubor, velikost)
     * @throws IOException pokud import selže
     */
    fun importModelToInternalStorage(uri: Uri): Pair<File, Long> {
        val (fileName, _) = getFileMetadata(uri)
        val modelsDir = getModelsDirectory()

        // Vytvoříme soubor. Pokud existuje, přidáme timestamp aby se nepřepsal.
        var destFile = File(modelsDir, fileName)
        if (destFile.exists()) {
            destFile = File(modelsDir, "${System.currentTimeMillis()}_$fileName")
        }

        Log.i(TAG, "Starting NLP model import: $uri -> ${destFile.absolutePath}")

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
                    Log.i(TAG, "NLP model imported: $totalBytes bytes")
                }
            } ?: throw IOException("ContentResolver returned null InputStream for: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "NLP model import failed", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            throw IOException("Failed to import NLP model: ${e.message}", e)
        }

        Log.i(TAG, "NLP import successful: ${destFile.absolutePath} (${destFile.length() / 1024} KB)")
        return Pair(destFile, destFile.length())
    }

    /** Smaže soubor modelu z disku. */
    fun deleteModelFile(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted NLP model file: $path ($deleted)")
            deleted
        } else {
            true
        }
    }

    /** Vrátí adresář pro NLP modely. Vytvoří ho, pokud neexistuje. */
    fun getModelsDirectory(): File {
        val dir = File(context.filesDir, MODELS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** Extrahuje název souboru a velikost z URI (SAF metadata). */
    private fun getFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "nlp_model.tflite"
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
            Log.e(TAG, "Cannot read URI metadata: ${e.message}")
        }
        return Pair(name, size)
    }
}
