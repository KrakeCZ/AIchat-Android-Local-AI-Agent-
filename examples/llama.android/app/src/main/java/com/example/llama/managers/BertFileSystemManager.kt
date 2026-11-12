package com.example.llama.managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class BertFileSystemManager(private val context: Context) {

    companion object {
        private const val TAG = "BertFileSystem"
        private const val MODELS_DIR = "nlp_models" // Cíleně použijeme tuto složku
    }

    /**
     * Importuje BERT model (*.tflite) nebo slovník (*.txt) z URI do privátního úložiště aplikace. 
     * Vrací absolutní cestu k novému souboru a jeho velikost.
     */
    fun importFileToInternalStorage(uri: Uri): Pair<File, Long> {
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

        Log.i(TAG, "Starting BERT file import: $uri -> ${destFile.absolutePath}")

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
            Log.e(TAG, "BERT file import failed", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            throw IOException("Failed to import BERT file: ${e.message}", e)
        }

        Log.i(TAG, "BERT import successful: ${destFile.absolutePath}")
        return Pair(destFile, destFile.length())
    }

    /** Smaže soubor z disku. */
    fun deleteModelFile(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted BERT file: $path ($deleted)")
            deleted
        } else {
            true
        }
    }

    /** Helper pro přečtení metadat ze Storage Access Framework URI. */
    private fun getFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "bert_model.tflite"
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
