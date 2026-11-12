package com.example.llama.managers

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.llama.ChatHistoryManager
import com.example.llama.Message
import com.example.llama.SettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI Manager pro práci se soubory.
 * Řeší výběr souborů (Launchers) a sdílení (Share Sheet).
 * Nenahrazuje LlamaFileSystemManager (ten řeší fyzické ukládání v data vrstvě).
 */
class FileSystemManager(private val activity: AppCompatActivity) {

    private val settingsManager = SettingsManager(activity)

    private lateinit var importModelLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var attachFileLauncher: ActivityResultLauncher<Array<String>>

    // Callbacky pro Multimodální fúzi
    private var onModelSelectedCallback: ((Uri) -> Unit)? = null
    
    // Specifické MIME callbacky
    var onTextAttachedCallback: ((String) -> Unit)? = null
    var onImageAttachedCallback: ((ByteArray) -> Unit)? = null
    var onVideoAttachedCallback: ((Uri) -> Unit)? = null
    var onAudioAttachedCallback: ((ByteArray) -> Unit)? = null

    init {
        registerLaunchers()
    }

    private fun registerLaunchers() {
        // 1. Launcher pro import Modelu
        importModelLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { onModelSelectedCallback?.invoke(it) }
        }

        // 2. Launcher pro přílohu s MIME resolverem
        attachFileLauncher = activity.registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { resolveAndRouteAttachment(it) }
        }
    }

    // --- Veřejné metody (Volá MainActivity) ---

    fun pickModel(onResult: (Uri) -> Unit) {
        this.onModelSelectedCallback = onResult
        importModelLauncher.launch(arrayOf("*/*"))
    }

    /**
     * Využívá SAF k výběru jakéhokoliv souboru. Očekává, že MainActivity předtím registrovala
     * příslušné callbacky (onTextAttachedCallback, onImageAttachedCallback atd.) pro dané MIME typy.
     */
    fun pickMultiModalAsset() {
        attachFileLauncher.launch(arrayOf("image/*", "video/*", "audio/*", "text/*", "application/pdf"))
    }

    suspend fun exportAndShare(
        historyManager: ChatHistoryManager,
        sessionId: String,
        messages: List<Message>,
        format: ChatHistoryManager.ExportFormat
    ) {
        try {
            // Uložení a Export (běží na IO)
            historyManager.saveSession(sessionId, messages)
            val file = historyManager.exportChat(sessionId, format)

            // Sdílení (musí běžet na Main UI vlákně)
            withContext(Dispatchers.Main) {
                shareFileIntent(file, format)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(activity, "Export Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // --- Interní logika ---
    private fun resolveAndRouteAttachment(uri: Uri) {
        val mimeType = activity.contentResolver.getType(uri) ?: "application/octet-stream"
        val fileName = getFileName(uri)

        when {
            mimeType.startsWith("image/") -> {
                try {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        onImageAttachedCallback?.invoke(bytes)
                        Toast.makeText(activity, "Načten obrázek: $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("FileSystemManager", "Chyba načítání obrázku: ${e.message}", e)
                    Toast.makeText(activity, "Chyba obrázku: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            mimeType.startsWith("video/") -> {
                onVideoAttachedCallback?.invoke(uri)
                Toast.makeText(activity, "Načteno video: $fileName", Toast.LENGTH_SHORT).show()
            }
            mimeType.startsWith("audio/") -> {
                try {
                    val bytes = activity.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null) {
                        onAudioAttachedCallback?.invoke(bytes)
                        Toast.makeText(activity, "Načteno audio: $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("FileSystemManager", "Chyba načítání audia: ${e.message}", e)
                }
            }
            else -> {
                // Fallback na textové zpracování pro text/*, pdf a neznámé formáty
                readTextAttachment(uri, fileName)
            }
        }
    }

    private fun readTextAttachment(uri: Uri, fileName: String) {
        try {
            // Načtení limitu z nastavení
            val maxBytes = settingsManager.maxImportSize
            val content = TextFileModule.readContent(activity, uri, maxBytes)

            // DIAGNOSTIKA: Zobrazíme uživateli, kolik jsme načetli
            Toast.makeText(activity, "Načten text. Soubor: $fileName (${content.length} chars)", Toast.LENGTH_SHORT).show()

            // Formátování pro LLM
            val formatted = "\n\n[Soubor: $fileName]\n$content\n\n"
            onTextAttachedCallback?.invoke(formatted)
        } catch (e: Exception) {
            Log.e("FileSystemManager", "Read error: ${e.message}", e)
            val msg = if (e.message?.contains("too large") == true) "File too large (>Limit ${e.message?.substringAfter(">")})" else "Failed to read text file"
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareFileIntent(file: File, format: ChatHistoryManager.ExportFormat) {
        try {
            val authority = "${activity.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(activity, authority, file)

            val mimeType = when(format) {
                ChatHistoryManager.ExportFormat.JSON -> "application/json"
                ChatHistoryManager.ExportFormat.HTML -> "text/html"
                ChatHistoryManager.ExportFormat.MARKDOWN -> "text/markdown"
                ChatHistoryManager.ExportFormat.TXT -> "text/plain"
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(Intent.createChooser(intent, "Share Chat"))
        } catch (e: Exception) {
            Toast.makeText(activity, "Share Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getFileName(uri: Uri): String {
        var result = "attachment"
        activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx != -1) result = cursor.getString(idx)
            }
        }
        return result
    }
}
