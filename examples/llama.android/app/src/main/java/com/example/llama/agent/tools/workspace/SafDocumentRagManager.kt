package com.example.llama.agent.tools.workspace

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.example.llama.agent.tools.web.WebSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

data class SafIndexingProgress(
    val isIndexing: Boolean = false,
    val processedFiles: Int = 0,
    val totalChunksCreated: Int = 0,
    val currentFileName: String = "",
    val lastIndexedFolderUri: String = ""
)

/**
 * Správce User Document RAG přes Android Storage Access Framework (SAF) (SOTA 2026/2027).
 * Zajišťuje trvalý přístup k externím složkám (`ACTION_OPEN_DOCUMENT_TREE`) a souborům
 * (`ACTION_OPEN_MULTIPLE_DOCUMENTS`), inkrementální delta-indexaci a sémantický chunking 512/64.
 */
class SafDocumentRagManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("saf_rag_prefs", Context.MODE_PRIVATE)

    private val _progressFlow = MutableStateFlow(
        SafIndexingProgress(
            lastIndexedFolderUri = prefs.getString(KEY_SAVED_TREE_URI, "") ?: ""
        )
    )
    val progressFlow: StateFlow<SafIndexingProgress> = _progressFlow.asStateFlow()

    val supportedExtensions: Set<String> = setOf(
        "txt", "md", "json", "csv", "log", "kt", "cpp", "h", "hpp", "py", "xml", "gradle"
    )

    private fun getArchive(): WebArchiveSqliteFts5 {
        return WebSearchEngine.getSqliteArchive() ?: WebArchiveSqliteFts5.getInstance(appContext)
    }

    /**
     * Trvale uloží oprávnění ke složce vybrané přes `ACTION_OPEN_DOCUMENT_TREE`
     * a spustí inkrementální indexaci (s přeskakováním nezměněných souborů).
     */
    suspend fun onFolderTreeSelected(treeUri: Uri, forceReindex: Boolean = false): Int =
        withContext(Dispatchers.IO) {
            takePersistableReadPermissionSafely(treeUri)
            prefs.edit().putString(KEY_SAVED_TREE_URI, treeUri.toString()).apply()

            val archive = getArchive()

            _progressFlow.value = _progressFlow.value.copy(
                isIndexing = true,
                processedFiles = 0,
                totalChunksCreated = 0,
                lastIndexedFolderUri = treeUri.toString()
            )

            var totalChunks = 0
            var filesCount = 0

            try {
                val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
                val filesToIndex = ArrayList<SafFileDescriptor>()
                collectSupportedFilesRecursively(treeUri, rootDocId, filesToIndex, maxDepth = 4, currentDepth = 0)

                for (fileDesc in filesToIndex) {
                    _progressFlow.value = _progressFlow.value.copy(
                        currentFileName = fileDesc.displayName,
                        processedFiles = filesCount
                    )

                    val textContent = readUriTextSafely(fileDesc.fileUri, maxBytes = 512 * 1024)
                    if (textContent.isNotBlank()) {
                        val chunksAdded = archive.saveUserDocumentToFts5(
                            filePath = fileDesc.fileUri.toString(),
                            title = fileDesc.displayName,
                            content = textContent,
                            forceReindex = forceReindex
                        )
                        totalChunks += chunksAdded
                        filesCount += 1
                    }
                }
            } finally {
                _progressFlow.value = _progressFlow.value.copy(
                    isIndexing = false,
                    processedFiles = filesCount,
                    totalChunksCreated = totalChunks,
                    currentFileName = ""
                )
            }
            totalChunks
        }

    /**
     * Zaindexuje jednotlivé soubory vybrané přes `ACTION_OPEN_MULTIPLE_DOCUMENTS`.
     */
    suspend fun onMultipleDocumentsSelected(uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext 0

        val archive = getArchive()

        _progressFlow.value = _progressFlow.value.copy(
            isIndexing = true,
            processedFiles = 0,
            totalChunksCreated = 0
        )

        var totalChunks = 0
        var count = 0

        try {
            for (uri in uris) {
                takePersistableReadPermissionSafely(uri)
                val meta = querySingleDocumentMetadata(uri) ?: continue
                if (!supportedExtensions.contains(meta.extension.lowercase())) {
                    continue
                }

                _progressFlow.value = _progressFlow.value.copy(
                    currentFileName = meta.displayName,
                    processedFiles = count
                )

                val content = readUriTextSafely(uri, maxBytes = 512 * 1024)
                if (content.isNotBlank()) {
                    val chunks = archive.saveUserDocumentToFts5(
                        filePath = uri.toString(),
                        title = meta.displayName,
                        content = content,
                        forceReindex = true
                    )
                    totalChunks += chunks
                    count += 1
                }
            }
        } finally {
            _progressFlow.value = _progressFlow.value.copy(
                isIndexing = false,
                processedFiles = count,
                totalChunksCreated = totalChunks,
                currentFileName = ""
            )
        }
        totalChunks
    }

    /**
     * Reindexuje naposledy připojenou SAF složku na vyžádání z UI.
     */
    suspend fun reindexSavedFolder(forceFullReindex: Boolean = false): Int {
        val savedUriStr = prefs.getString(KEY_SAVED_TREE_URI, null) ?: return 0
        if (savedUriStr.isBlank()) return 0
        return onFolderTreeSelected(Uri.parse(savedUriStr), forceReindex = forceFullReindex)
    }

    private fun collectSupportedFilesRecursively(
        treeUri: Uri,
        parentDocumentId: String,
        outList: MutableList<SafFileDescriptor>,
        maxDepth: Int,
        currentDepth: Int
    ) {
        if (currentDepth > maxDepth || outList.size >= 250) {
            return
        }

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val cursor: Cursor? = try {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)
        } catch (e: Exception) {
            null
        }

        cursor?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (c.moveToNext()) {
                val docId = if (idIdx != -1) c.getString(idIdx) ?: "" else ""
                val displayName = if (nameIdx != -1) c.getString(nameIdx) ?: "" else ""
                val mimeType = if (mimeIdx != -1) c.getString(mimeIdx) ?: "" else ""
                val sizeBytes = if (sizeIdx != -1 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val lastMod = if (modIdx != -1 && !c.isNull(modIdx)) c.getLong(modIdx) else 0L

                if (docId.isEmpty() || displayName.startsWith(".")) {
                    continue
                }

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectSupportedFilesRecursively(
                        treeUri = treeUri,
                        parentDocumentId = docId,
                        outList = outList,
                        maxDepth = maxDepth,
                        currentDepth = currentDepth + 1
                    )
                } else {
                    val ext = displayName.substringAfterLast('.', "").lowercase()
                    if (supportedExtensions.contains(ext)) {
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        outList.add(
                            SafFileDescriptor(
                                fileUri = fileUri,
                                displayName = displayName,
                                extension = ext,
                                sizeBytes = sizeBytes,
                                lastModifiedMs = lastMod
                            )
                        )
                    }
                }
            }
        }
    }

    private fun querySingleDocumentMetadata(uri: Uri): SafFileDescriptor? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = try {
            appContext.contentResolver.query(uri, projection, null, null, null)
        } catch (e: Exception) {
            null
        }

        cursor?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                val name = if (nameIdx != -1) c.getString(nameIdx) ?: "file.txt" else "file.txt"
                val size = if (sizeIdx != -1 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else 0L
                val mod = if (modIdx != -1 && !c.isNull(modIdx)) c.getLong(modIdx) else System.currentTimeMillis()
                val ext = name.substringAfterLast('.', "txt").lowercase()

                return SafFileDescriptor(
                    fileUri = uri,
                    displayName = name,
                    extension = ext,
                    sizeBytes = size,
                    lastModifiedMs = mod
                )
            }
        }
        return null
    }

    private fun readUriTextSafely(uri: Uri, maxBytes: Int): String {
        return try {
            val inputStream = appContext.contentResolver.openInputStream(uri) ?: return ""
            val sb = StringBuilder()
            val buffer = CharArray(2048)
            var totalChars = 0

            BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                var read: Int
                while (reader.read(buffer).also { read = it } != -1) {
                    sb.append(buffer, 0, read)
                    totalChars += read
                    if (totalChars >= maxBytes) {
                        break
                    }
                }
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun takePersistableReadPermissionSafely(uri: Uri) {
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            // Poskytovatel nepodporuje persistable URI, čtení proběhne v aktivní relaci
        }
    }

    private data class SafFileDescriptor(
        val fileUri: Uri,
        val displayName: String,
        val extension: String,
        val sizeBytes: Long,
        val lastModifiedMs: Long
    )

    companion object {
        private const val KEY_SAVED_TREE_URI = "saved_saf_tree_uri"
    }
}
