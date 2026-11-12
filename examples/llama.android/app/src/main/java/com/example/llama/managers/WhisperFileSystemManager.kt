package com.example.llama.managers

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class WhisperFileSystemManager(private val context: Context) {

    companion object {
        private const val TAG = "WhisperFileSystem"
        private const val MODELS_DIR = "whisper_models"
    }

    /**
     * Importuje Whisper model (*.gguf, *.bin) z URI do privátního úložiště aplikace. Vrací
     * absolutní cestu k novému souboru a jeho velikost.
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
            destFile = File(modelsDir, "\${System.currentTimeMillis()}_\$fileName")
        }

        Log.i(TAG, "Starting Whisper import: \$uri -> \${destFile.absolutePath}")

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
            Log.e(TAG, "Whisper model import failed", e)
            if (destFile.exists()) {
                destFile.delete()
            }
            throw IOException("Failed to import Whisper model: \${e.message}", e)
        }

        Log.i(TAG, "Whisper import successful: \${destFile.absolutePath}")
        return Pair(destFile, destFile.length())
    }

    /** Smaže soubor modelu z disku. */
    fun deleteModelFile(path: String): Boolean {
        val file = File(path)
        return if (file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "Deleted Whisper file: \$path (\$deleted)")
            deleted
        } else {
            true
        }
    }

    private fun getFileMetadata(uri: Uri): Pair<String, Long> {
        var name = "whisper_model.bin"
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
        } catch (_: Exception) {
            Log.e(TAG, "Cannot read metadata: \${e.message}")
        }
        return Pair(name, size)
    }
}
