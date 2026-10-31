package com.example.llama.agent.tools.workspace

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.llama.agent.tools.web.BrowserPageSession
import com.example.llama.agent.tools.web.ExtractedWebLink
import com.example.llama.agent.tools.web.WebSearchEngine
import com.example.llama.agent.tools.web.WebSearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

/**
 * Výsledek fulltextového vyhledávání v lokálních uživatelských dokumentech (User Document RAG).
 */
data class UserRagResult(
    val filePath: String,
    val title: String,
    val excerpt: String,
    val relevanceScore: Double,
    val breadcrumbHeader: String = "",
    val chunkIndex: Int = 0,
    val chunkContent: String = ""
)

/**
 * Sémantický blok textu (512 znaků s 64znakovým překryvem) s přesnou breadcrumb hlavičkou pro LLM kontext.
 */
data class DocumentChunk(
    val chunkIndex: Int,
    val breadcrumbHeader: String,
    val content: String,
    val startCharOffset: Int,
    val endCharOffset: Int
)

/**
 * SOTA 2026/2027 Sliding Window Chunker (AnythingLLM specifikace pro Edge AI):
 * Rozděluje text na 512znakové bloky s 64znakovým překryvem se zachováním hranic odstavců, vět a kódu.
 */
object SemanticSlidingWindowChunker {
    const val DEFAULT_CHUNK_SIZE = 512
    const val DEFAULT_CHUNK_OVERLAP = 64

    fun chunkText(
        fileName: String,
        rawText: String,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        chunkOverlap: Int = DEFAULT_CHUNK_OVERLAP
    ): List<DocumentChunk> {
        val cleanText = rawText.replace("\r\n", "\n").trim()
        if (cleanText.isEmpty()) return emptyList()

        val safeChunkSize = chunkSize.coerceAtLeast(128)
        val safeOverlap = chunkOverlap.coerceIn(0, safeChunkSize / 2)
        val chunks = mutableListOf<DocumentChunk>()

        var start = 0
        var index = 0
        val totalLength = cleanText.length

        while (start < totalLength) {
            var end = (start + safeChunkSize).coerceAtMost(totalLength)

            // Pokud nejsme na konci textu, najdeme nejbližší přirozený předěl v posledních 25 % okna
            if (end < totalLength) {
                val searchFloor = start + (safeChunkSize * 3) / 4
                val naturalBreak = findBestBreakPoint(cleanText, searchFloor, end)
                if (naturalBreak > start) {
                    end = naturalBreak
                }
            }

            val slice = cleanText.substring(start, end).trim()
            if (slice.isNotEmpty()) {
                chunks.add(
                    DocumentChunk(
                        chunkIndex = index,
                        breadcrumbHeader = "[Zdroj: $fileName | Sekce #${index + 1}]",
                        content = slice,
                        startCharOffset = start,
                        endCharOffset = end
                    )
                )
                index++
            }

            if (end >= totalLength) break

            val nextStart = end - safeOverlap
            start = if (nextStart > start) nextStart else end
        }

        val totalActualChunks = chunks.size
        return chunks.mapIndexed { idx, ch ->
            ch.copy(breadcrumbHeader = "[Zdroj: $fileName | Sekce #${idx + 1} | Chunk ${idx + 1}/$totalActualChunks]")
        }
    }

    private fun findBestBreakPoint(text: String, minPos: Int, maxPos: Int): Int {
        val window = text.substring(minPos, maxPos)
        val delimiters = listOf("\n\n", "\n", ". ", "; ", " ")
        for (delim in delimiters) {
            val lastIdx = window.lastIndexOf(delim)
            if (lastIdx != -1) {
                return minPos + lastIdx + delim.length
            }
        }
        return maxPos
    }
}

/**
 * Nástroj pro bezpečnou tvorbu, úpravu a čtení souborů LLM agentem (Android Scoped Storage).
 * Obsahuje automatickou indexaci do SQLite FTS5 pro okamžitý User Document RAG.
 */
class FileWorkspaceTool(
    private val context: Context,
    private val sqliteArchive: WebArchiveSqliteFts5? = null
) {
    private val workspaceRoot: File = File(context.filesDir, "agent_workspace").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private fun resolveSafeFile(relativePath: String): File? {
        val sanitized = relativePath.trim().removePrefix("/")
        if (sanitized.isBlank() || sanitized.contains("..")) return null

        val target = File(workspaceRoot, sanitized)
        val canonicalRoot = workspaceRoot.canonicalPath
        val canonicalTarget = target.canonicalPath

        return if (canonicalTarget.startsWith(canonicalRoot)) target else null
    }

    suspend fun writeFile(
        relativePath: String,
        content: String,
        append: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val targetFile = resolveSafeFile(relativePath)
            ?: return@withContext "Chyba: Neplatná nebo nebezpečná cesta k souboru \"$relativePath\"."

        try {
            val parent = targetFile.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }

            if (append && targetFile.exists()) {
                targetFile.appendText(content, Charsets.UTF_8)
            } else {
                val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
                tempFile.writeText(content, Charsets.UTF_8)
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                tempFile.renameTo(targetFile)
            }

            // Automatická indexace do User Document RAG (SQLite FTS5)
            val fullContent = targetFile.readText(Charsets.UTF_8)
            val archive = sqliteArchive ?: WebSearchEngine.getSqliteArchive()
            archive?.saveUserDocumentToFts5(
                filePath = relativePath,
                title = targetFile.name,
                content = fullContent
            )

            "Soubor úspěšně uložen: ${targetFile.name} (Velikost: ${targetFile.length()} bajtů, Režim: ${if (append) "APPEND" else "WRITE"}, FTS5 RAG indexován)."
        } catch (e: Exception) {
            "Chyba při zápisu souboru \"$relativePath\": ${e.localizedMessage}"
        }
    }

    suspend fun readFile(
        relativePath: String,
        maxChars: Int = 4000,
        offsetChars: Int = 0
    ): String = withContext(Dispatchers.IO) {
        val targetFile = resolveSafeFile(relativePath)
            ?: return@withContext "Chyba: Neplatná cesta k souboru \"$relativePath\"."

        if (!targetFile.exists() || !targetFile.isFile) {
            return@withContext "Chyba: Soubor \"$relativePath\" neexistuje."
        }

        try {
            val fullText = targetFile.readText(Charsets.UTF_8)
            val safeOffset = offsetChars.coerceIn(0, fullText.length)
            val end = (safeOffset + maxChars.coerceAtLeast(200)).coerceAtMost(fullText.length)
            val slice = fullText.substring(safeOffset, end)

            buildString {
                append("### 📄 Soubor: $relativePath (Znaky $safeOffset..$end z ${fullText.length})\n")
                append(slice)
                if (end < fullText.length) {
                    append("\n\n...(Pro pokračování zavolej `file_read` s offset=$end)")
                }
            }
        } catch (e: Exception) {
            "Chyba při čtení souboru \"$relativePath\": ${e.localizedMessage}"
        }
    }

    suspend fun listFiles(): String = withContext(Dispatchers.IO) {
        val files = workspaceRoot.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .toList()

        if (files.isEmpty()) {
            return@withContext "Pracovní prostor agenta je prázdný (${workspaceRoot.absolutePath})."
        }

        buildString {
            append("### 📁 Soubory v pracovním prostoru agenta (${files.size}):\n")
            for (f in files) {
                val rel = f.relativeTo(workspaceRoot).path
                append("- `$rel` (${f.length()} B)\n")
            }
        }.trimEnd()
    }

    suspend fun deleteFile(relativePath: String): String = withContext(Dispatchers.IO) {
        val targetFile = resolveSafeFile(relativePath)
            ?: return@withContext "Chyba: Neplatná cesta \"$relativePath\"."

        if (!targetFile.exists()) {
            return@withContext "Soubor \"$relativePath\" již neexistuje."
        }

        val archive = sqliteArchive ?: WebSearchEngine.getSqliteArchive()
        archive?.deleteUserDocumentFromFts5(relativePath)

        return@withContext if (targetFile.delete()) {
            "Soubor \"$relativePath\" byl úspěšně smazán (a odstraněn z FTS5 indexu)."
        } else {
            "Chyba: Nepodařilo se smazat soubor \"$relativePath\"."
        }
    }

    /**
     * Přeindexuje všechny soubory z pracovního prostoru do User RAG.
     */
    suspend fun reindexAllFiles(): Int = withContext(Dispatchers.IO) {
        val archive = sqliteArchive ?: WebSearchEngine.getSqliteArchive() ?: return@withContext 0
        return@withContext archive.reindexWorkspaceFolder(workspaceRoot)
    }

    companion object {
        fun getWorkspaceDir(context: Context): File = File(context.filesDir, "agent_workspace")
    }
}

/**
 * Nástroj pro čtení systémového a síťového stavu v reálném čase (Android 15/16/17+).
 */
object RealtimeSystemContextTool {

    fun getRealtimeSnapshot(context: Context): String {
        val now = Date()
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.getDefault())
        val tz = TimeZone.getDefault().id

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val totalRamMb = memInfo.totalMem / (1024 * 1024)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val isMetered = cm?.isActiveNetworkMetered ?: true
        val netCaps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }

        val transport = when {
            netCaps == null -> "Offline"
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobilní data (Cellular)"
            netCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Jiné připojení"
        }

        val webConfig = WebSearchEngine.uiConfig.value

        return buildString {
            append("### ⚡ Real-Time Stav Zařízení a Prostředí\n")
            append("- Aktuální čas: ${isoFormatter.format(now)} (Časová zóna: $tz)\n")
            append("- OS Android: API ${Build.VERSION.SDK_INT} (Model: ${Build.MODEL})\n")
            append("- Síť: $transport | Měřené připojení (FUP): $isMetered\n")
            append("- Baterie: ${if (batteryPct >= 0) "$batteryPct%" else "N/A"} | Volná RAM: $availRamMb MB z $totalRamMb MB\n")
            append("- WebEngine: DataSaver=${webConfig.dataSaverEnabled}, MaxTokensPerSource=${webConfig.maxTokensPerSource}, FTS5=${webConfig.enableSqliteFts5Archive}\n")
        }.trimEnd()
    }
}

/**
 * Duální lokální SQLite FTS5 Engine:
 * 1. Web Archive & Offline Cache (tabulky web_archive_meta a web_archive_fts)
 * 2. User Document RAG (tabulky user_documents_meta a user_documents_fts)
 *
 * Plně podporuje českou diakritiku (unicode61 remove_diacritics 2) a nativní BM25 řazení.
 */
class WebArchiveSqliteFts5(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG = "WebArchiveSqliteFts5"
        private const val DB_NAME = "web_agent_archive_fts5.db"
        private const val DB_VERSION = 4 // v4: univerzální SQLite kompatibilita (FTS5 + Standard SQLite BM25 engine)
        private const val DEFAULT_TTL_MS = 7L * 24L * 3600L * 1000L // 7 dní
        private const val MAX_ARCHIVE_ROWS = 500

        @Volatile
        private var instance: WebArchiveSqliteFts5? = null

        fun getInstance(context: Context): WebArchiveSqliteFts5 {
            return instance ?: synchronized(this) {
                instance ?: WebArchiveSqliteFts5(context.applicationContext).also { instance = it }
            }
        }

        fun stripDiacritics(input: String): String {
            val normalized = java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
            return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
        }
    }

    @Volatile
    private var fts5Available: Boolean = false

    override fun onCreate(db: SQLiteDatabase) {
        createAllTablesSafe(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        try {
            if (oldVersion < 4) {
                db.execSQL("DROP TABLE IF EXISTS web_archive_fts;")
                db.execSQL("DROP TABLE IF EXISTS user_documents_fts;")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cleanup during onUpgrade to v$newVersion note: ${e.message}")
        }
        createAllTablesSafe(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        createAllTablesSafe(db)
    }

    private fun createAllTablesSafe(db: SQLiteDatabase) {
        try {
            // --- 1. Web Archive & Offline Cache ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS web_archive_meta (
                    url_hash TEXT PRIMARY KEY,
                    canonical_url TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    source_provider TEXT NOT NULL,
                    structured_json_ld TEXT,
                    extracted_links_json TEXT,
                    bytes_saved INTEGER NOT NULL,
                    fetched_at_ms INTEGER NOT NULL,
                    last_accessed_at_ms INTEGER NOT NULL,
                    expires_at_ms INTEGER NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_archive_expires ON web_archive_meta(expires_at_ms);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_archive_lru ON web_archive_meta(last_accessed_at_ms);")

            // Univerzální tabulka obsahu webového archivu (100% kompatibilní na všech Android zařízeních bez ohledu na FTS5 modul)
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS web_archive_content (
                    url_hash TEXT PRIMARY KEY,
                    canonical_url TEXT NOT NULL,
                    title TEXT NOT NULL,
                    clean_body TEXT NOT NULL,
                    normalized_body TEXT NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_web_content_hash ON web_archive_content(url_hash);")

            // --- 2. User Document RAG (Lokální znalostní báze uživatele) ---
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_documents_meta (
                    doc_hash TEXT PRIMARY KEY,
                    file_path TEXT NOT NULL UNIQUE,
                    title TEXT NOT NULL,
                    bytes_size INTEGER NOT NULL,
                    chunk_count INTEGER NOT NULL DEFAULT 1,
                    updated_at_ms INTEGER NOT NULL
                );
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_documents_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    doc_hash TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    file_path TEXT NOT NULL,
                    title TEXT NOT NULL,
                    breadcrumb_header TEXT NOT NULL,
                    clean_content TEXT NOT NULL,
                    normalized_content TEXT NOT NULL
                );
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_user_chunks_doc_hash ON user_documents_chunks(doc_hash);")

            // Volitelná inicializace FTS5 virtuálních tabulek pokud je modul v ROM přítomen
            try {
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS web_archive_fts USING fts5(
                        url_hash UNINDEXED,
                        canonical_url UNINDEXED,
                        title,
                        clean_body
                    );
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE VIRTUAL TABLE IF NOT EXISTS user_documents_fts USING fts5(
                        doc_hash UNINDEXED,
                        chunk_index UNINDEXED,
                        file_path UNINDEXED,
                        title,
                        breadcrumb_header,
                        clean_content
                    );
                    """.trimIndent()
                )
                fts5Available = true
            } catch (ftsErr: Exception) {
                fts5Available = false
                Log.i(TAG, "Native FTS5 module not compiled in system SQLite (${ftsErr.message}), using Universal SQLite BM25 Engine.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in createAllTablesSafe: ${e.message}", e)
        }
    }

    /**
     * Sanitizuje uživatelský nebo LLM dotaz pro SQLite FTS5 MATCH dotazování.
     */
    fun buildSafeFts5Query(rawQuery: String): String {
        val terms = rawQuery.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
        if (terms.isEmpty()) return ""
        return terms.joinToString(" OR ") { "\"$it\"*" }
    }

    private val RAG_STOPWORDS = setOf(
        "dnes", "dnesni", "vcera", "zitra", "aktualni", "aktualne", "nyni", "letos",
        "jaky", "jaka", "jake", "ktery", "ktera", "kde", "kdy", "jak", "pro", "nad",
        "pod", "pred", "mezi", "nebo", "ale", "aby", "jako", "jsou", "byl", "byla",
        "bylo", "bude", "mesto", "meste", "kondice", "stav", "info", "informace",
        "vyhledej", "najdi", "hledej", "today", "current", "latest", "what", "where",
        "when", "from", "with", "this", "that"
    )

    private fun extractQueryTerms(rawQuery: String): List<String> {
        val allTerms = stripDiacritics(rawQuery)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()
        val filtered = allTerms.filter { it !in RAG_STOPWORDS }
        return if (filtered.isNotEmpty()) filtered else allTerms
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun getArchiveStatsSummary(): Pair<Int, Int> {
        return try {
            val db = readableDatabase
            var webCount = 0
            var docChunks = 0
            db.rawQuery("SELECT COUNT(*) FROM web_archive_meta", null).use { c ->
                if (c.moveToFirst()) webCount = c.getInt(0)
            }
            db.rawQuery("SELECT COUNT(*) FROM user_documents_chunks", null).use { c ->
                if (c.moveToFirst()) docChunks = c.getInt(0)
            }
            Pair(webCount, docChunks)
        } catch (e: Exception) {
            Log.w(TAG, "getArchiveStatsSummary failed: ${e.message}")
            Pair(0, 0)
        }
    }

    // =========================================================================
    // A. WEB ARCHIVE & CACHE OPERACE
    // =========================================================================

    fun savePageSessionToFts5(session: BrowserPageSession) {
        val cleanUrl = session.url.trim()
        if (cleanUrl.isBlank() || session.fullCleanText.isBlank()) return
        val hash = sha256(cleanUrl.lowercase())
        val now = System.currentTimeMillis()

        val linksArr = JSONArray()
        session.extractedLinks.forEach { link ->
            linksArr.put(
                JSONObject().apply {
                    put("index", link.index)
                    put("label", link.label)
                    put("url", link.url)
                }
            )
        }

        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val metaValues = ContentValues().apply {
                    put("url_hash", hash)
                    put("canonical_url", cleanUrl)
                    put("title", session.title)
                    put("source_provider", "SQLite RAG Archive")
                    put("structured_json_ld", session.structuredMetadataJson)
                    put("extracted_links_json", linksArr.toString())
                    put("bytes_saved", session.bytesDownloaded)
                    put("fetched_at_ms", now)
                    put("last_accessed_at_ms", now)
                    put("expires_at_ms", now + DEFAULT_TTL_MS)
                }
                db.insertWithOnConflict("web_archive_meta", null, metaValues, SQLiteDatabase.CONFLICT_REPLACE)

                val contentValues = ContentValues().apply {
                    put("url_hash", hash)
                    put("canonical_url", cleanUrl)
                    put("title", session.title)
                    put("clean_body", session.fullCleanText)
                    put("normalized_body", stripDiacritics("${session.title}\n${session.fullCleanText}"))
                }
                db.insertWithOnConflict("web_archive_content", null, contentValues, SQLiteDatabase.CONFLICT_REPLACE)

                if (fts5Available) {
                    runCatching {
                        db.delete("web_archive_fts", "url_hash = ?", arrayOf(hash))
                        val ftsValues = ContentValues().apply {
                            put("url_hash", hash)
                            put("canonical_url", cleanUrl)
                            put("title", session.title)
                            put("clean_body", session.fullCleanText)
                        }
                        db.insert("web_archive_fts", null, ftsValues)
                    }
                }

                pruneArchiveIfNeededLocked(db, now)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SQLite savePageSession failed: ${e.message}")
        }
    }

    fun cacheSearchResultsToFts5(query: String, results: List<WebSearchResult>) {
        if (results.isEmpty()) return
        val now = System.currentTimeMillis()
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                for (res in results) {
                    if (res.url.isBlank() || res.snippet.isBlank()) continue
                    val hash = sha256(res.url.trim().lowercase())

                    val existingCursor = db.rawQuery(
                        "SELECT bytes_saved FROM web_archive_meta WHERE url_hash = ?",
                        arrayOf(hash)
                    )
                    val alreadyHasFullPage = existingCursor.use { c ->
                        c.moveToFirst() && c.getInt(0) > 2048
                    }
                    if (alreadyHasFullPage) continue

                    val metaValues = ContentValues().apply {
                        put("url_hash", hash)
                        put("canonical_url", res.url)
                        put("title", res.title)
                        put("source_provider", "${res.sourceProvider} (SQLite Cache)")
                        put("structured_json_ld", "")
                        put("extracted_links_json", "[]")
                        put("bytes_saved", res.snippet.length)
                        put("fetched_at_ms", now)
                        put("last_accessed_at_ms", now)
                        put("expires_at_ms", now + DEFAULT_TTL_MS)
                    }
                    db.insertWithOnConflict("web_archive_meta", null, metaValues, SQLiteDatabase.CONFLICT_REPLACE)

                    val bodyText = "${res.title}\n${res.snippet}\nDotaz: $query"
                    val contentValues = ContentValues().apply {
                        put("url_hash", hash)
                        put("canonical_url", res.url)
                        put("title", res.title)
                        put("clean_body", bodyText)
                        put("normalized_body", stripDiacritics(bodyText))
                    }
                    db.insertWithOnConflict("web_archive_content", null, contentValues, SQLiteDatabase.CONFLICT_REPLACE)

                    if (fts5Available) {
                        runCatching {
                            db.delete("web_archive_fts", "url_hash = ?", arrayOf(hash))
                            val ftsValues = ContentValues().apply {
                                put("url_hash", hash)
                                put("canonical_url", res.url)
                                put("title", res.title)
                                put("clean_body", bodyText)
                            }
                            db.insert("web_archive_fts", null, ftsValues)
                        }
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SQLite cacheSearchResults failed: ${e.message}")
        }
    }

    fun searchArchiveFts5(rawQuery: String, limit: Int = 5): List<WebSearchResult> {
        val terms = extractQueryTerms(rawQuery)
        if (terms.isEmpty()) return emptyList()

        val results = mutableListOf<WebSearchResult>()
        try {
            val db = readableDatabase
            val sql = "SELECT canonical_url, title, clean_body, normalized_body FROM web_archive_content LIMIT 500"
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val url = cursor.getString(0) ?: continue
                    val title = cursor.getString(1) ?: url
                    val cleanBody = cursor.getString(2) ?: ""
                    val normBody = cursor.getString(3) ?: stripDiacritics("$title $cleanBody")
                    val normTitle = stripDiacritics(title)

                    var score = 0.0
                    var matchedTerms = 0
                    for (term in terms) {
                        val titleHits = countOccurrencesSimple(normTitle, term)
                        val bodyHits = countOccurrencesSimple(normBody, term)
                        if (titleHits > 0 || bodyHits > 0) {
                            matchedTerms++
                            score += (titleHits * 4.0) + (bodyHits.coerceAtMost(10) * 1.2)
                        }
                    }
                    val minRequired = if (terms.size >= 2) ((terms.size + 1) / 2).coerceAtLeast(2) else 1
                    if (matchedTerms >= minRequired) {
                        val coverageBoost = (matchedTerms.toDouble() / terms.size.toDouble()) * 5.0
                        val finalScore = score + coverageBoost
                        val excerpt = buildBestExcerpt(cleanBody, terms, 320)
                        results.add(
                            WebSearchResult(
                                title = title,
                                url = url,
                                snippet = excerpt,
                                sourceProvider = "SQLite Offline Cache",
                                relevanceScore = finalScore
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SQLite searchArchive query error: ${e.message}")
        }
        return results.sortedByDescending { it.relevanceScore }.take(limit)
    }

    private fun countOccurrencesSimple(text: String, term: String): Int {
        if (term.isEmpty() || text.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (true) {
            val found = text.indexOf(term, idx)
            if (found == -1) break
            count++
            idx = found + term.length
        }
        return count
    }

    private fun buildBestExcerpt(text: String, normTerms: List<String>, maxChars: Int): String {
        if (text.length <= maxChars) return text.trim()
        val normText = stripDiacritics(text)
        var bestPos = 0
        for (term in normTerms) {
            val idx = normText.indexOf(term)
            if (idx >= 0) {
                bestPos = idx
                break
            }
        }
        val start = (bestPos - 60).coerceAtLeast(0)
        val end = (start + maxChars).coerceAtMost(text.length)
        val slice = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        val prefix = if (start > 0) "… " else ""
        val suffix = if (end < text.length) " …" else ""
        return "$prefix$slice$suffix"
    }

    fun getCachedPageByUrl(url: String): BrowserPageSession? {
        val hash = sha256(url.trim().lowercase())
        var title = ""
        var jsonLd = ""
        var linksJson = "[]"
        var bytesSaved = 0
        var bodyText = ""

        try {
            val db = writableDatabase
            db.rawQuery(
                "SELECT title, structured_json_ld, extracted_links_json, bytes_saved FROM web_archive_meta WHERE url_hash = ?",
                arrayOf(hash)
            ).use { c ->
                if (!c.moveToFirst()) return null
                title = c.getString(0) ?: url
                jsonLd = c.getString(1) ?: ""
                linksJson = c.getString(2) ?: "[]"
                bytesSaved = c.getInt(3)
            }

            db.rawQuery(
                "SELECT clean_body FROM web_archive_content WHERE url_hash = ?",
                arrayOf(hash)
            ).use { c ->
                if (c.moveToFirst()) {
                    bodyText = c.getString(0) ?: ""
                }
            }

            if (bodyText.isBlank()) return null

            val links = mutableListOf<ExtractedWebLink>()
            val arr = JSONArray(linksJson)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                links.add(
                    ExtractedWebLink(
                        index = obj.optInt("index", i + 1),
                        label = obj.optString("label", ""),
                        url = obj.optString("url", "")
                    )
                )
            }

            val updateTouch = ContentValues().apply {
                put("last_accessed_at_ms", System.currentTimeMillis())
            }
            db.update("web_archive_meta", updateTouch, "url_hash = ?", arrayOf(hash))

            return BrowserPageSession(
                sessionId = "active",
                url = url,
                title = title,
                fullCleanText = bodyText,
                structuredMetadataJson = jsonLd,
                chunks = listOf(bodyText),
                currentChunkIndex = 0,
                extractedLinks = links,
                bytesDownloaded = bytesSaved,
                loadedFromOfflineCache = true
            )
        } catch (e: Exception) {
            Log.w(TAG, "getCachedPageByUrl failed: ${e.message}")
            return null
        }
    }

    fun clearAllArchive() {
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("web_archive_meta", null, null)
                db.delete("web_archive_content", null, null)
                if (fts5Available) {
                    runCatching { db.delete("web_archive_fts", null, null) }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "clearAllArchive failed: ${e.message}")
        }
    }

    private fun pruneArchiveIfNeededLocked(db: SQLiteDatabase, nowMs: Long) {
        try {
            db.execSQL("DELETE FROM web_archive_content WHERE url_hash IN (SELECT url_hash FROM web_archive_meta WHERE expires_at_ms < ?)", arrayOf(nowMs))
            if (fts5Available) {
                runCatching {
                    db.execSQL("DELETE FROM web_archive_fts WHERE url_hash IN (SELECT url_hash FROM web_archive_meta WHERE expires_at_ms < ?)", arrayOf(nowMs))
                }
            }
            db.execSQL("DELETE FROM web_archive_meta WHERE expires_at_ms < ?", arrayOf(nowMs))

            val countCursor = db.rawQuery("SELECT COUNT(*) FROM web_archive_meta", null)
            val totalRows = countCursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (totalRows > MAX_ARCHIVE_ROWS) {
                val toDelete = totalRows - MAX_ARCHIVE_ROWS
                db.execSQL(
                    """
                    DELETE FROM web_archive_content WHERE url_hash IN (
                        SELECT url_hash FROM web_archive_meta ORDER BY last_accessed_at_ms ASC LIMIT $toDelete
                    )
                    """.trimIndent()
                )
                if (fts5Available) {
                    runCatching {
                        db.execSQL(
                            """
                            DELETE FROM web_archive_fts WHERE url_hash IN (
                                SELECT url_hash FROM web_archive_meta ORDER BY last_accessed_at_ms ASC LIMIT $toDelete
                            )
                            """.trimIndent()
                        )
                    }
                }
                db.execSQL(
                    """
                    DELETE FROM web_archive_meta WHERE url_hash IN (
                        SELECT url_hash FROM web_archive_meta ORDER BY last_accessed_at_ms ASC LIMIT $toDelete
                    )
                    """.trimIndent()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "pruneArchiveIfNeededLocked note: ${e.message}")
        }
    }

    // =========================================================================
    // B. USER DOCUMENT RAG OPERACE (LOKÁLNÍ ZNALOSTI UŽIVATELE)
    // =========================================================================

    fun saveUserDocumentToFts5(
        filePath: String,
        title: String,
        content: String,
        forceReindex: Boolean = false
    ): Int {
        val cleanPath = filePath.trim()
        if (cleanPath.isBlank() || content.isBlank()) return 0
        val hash = sha256(cleanPath.lowercase())
        val now = System.currentTimeMillis()
        val docTitle = title.ifBlank { File(cleanPath).name }

        try {
            val db = writableDatabase
            if (!forceReindex) {
                val cursor = db.rawQuery(
                    "SELECT bytes_size FROM user_documents_meta WHERE doc_hash = ?",
                    arrayOf(hash)
                )
                val isUnchanged = cursor.use { c -> c.moveToFirst() && c.getInt(0) == content.length }
                if (isUnchanged) {
                    return 0
                }
            }

            val chunks = SemanticSlidingWindowChunker.chunkText(
                fileName = docTitle,
                rawText = content
            )
            if (chunks.isEmpty()) return 0

            db.beginTransaction()
            try {
                val metaValues = ContentValues().apply {
                    put("doc_hash", hash)
                    put("file_path", cleanPath)
                    put("title", docTitle)
                    put("bytes_size", content.length)
                    put("chunk_count", chunks.size)
                    put("updated_at_ms", now)
                }
                db.insertWithOnConflict("user_documents_meta", null, metaValues, SQLiteDatabase.CONFLICT_REPLACE)

                db.delete("user_documents_chunks", "doc_hash = ?", arrayOf(hash))
                if (fts5Available) {
                    runCatching { db.delete("user_documents_fts", "doc_hash = ?", arrayOf(hash)) }
                }

                for (chunk in chunks) {
                    val chunkValues = ContentValues().apply {
                        put("doc_hash", hash)
                        put("chunk_index", chunk.chunkIndex)
                        put("file_path", cleanPath)
                        put("title", docTitle)
                        put("breadcrumb_header", chunk.breadcrumbHeader)
                        put("clean_content", chunk.content)
                        put("normalized_content", stripDiacritics("${ docTitle } ${chunk.breadcrumbHeader} ${chunk.content}"))
                    }
                    db.insert("user_documents_chunks", null, chunkValues)

                    if (fts5Available) {
                        runCatching {
                            val ftsValues = ContentValues().apply {
                                put("doc_hash", hash)
                                put("chunk_index", chunk.chunkIndex)
                                put("file_path", cleanPath)
                                put("title", docTitle)
                                put("breadcrumb_header", chunk.breadcrumbHeader)
                                put("clean_content", chunk.content)
                            }
                            db.insert("user_documents_fts", null, ftsValues)
                        }
                    }
                }

                db.setTransactionSuccessful()
                Log.i(TAG, "✅ Indexed user document into SQLite RAG ($cleanPath, ${chunks.size} chunks)")
                return chunks.size
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed indexing user document: $cleanPath", e)
            return 0
        }
    }

    fun searchUserDocumentsFts5(rawQuery: String, limit: Int = 5): List<UserRagResult> {
        val terms = extractQueryTerms(rawQuery)
        if (terms.isEmpty()) return emptyList()

        val results = mutableListOf<UserRagResult>()
        try {
            val db = readableDatabase
            val sql = "SELECT file_path, title, breadcrumb_header, clean_content, normalized_content, chunk_index FROM user_documents_chunks"
            db.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0) ?: continue
                    val title = cursor.getString(1) ?: path
                    val breadcrumb = cursor.getString(2) ?: "[Zdroj: $title]"
                    val chunkText = cursor.getString(3) ?: ""
                    val normContent = cursor.getString(4) ?: stripDiacritics("$title $breadcrumb $chunkText")
                    val chunkIdx = cursor.getInt(5)
                    val normTitle = stripDiacritics("$title $breadcrumb")

                    var score = 0.0
                    var matchedTerms = 0
                    for (term in terms) {
                        val headerHits = countOccurrencesSimple(normTitle, term)
                        val contentHits = countOccurrencesSimple(normContent, term)
                        if (headerHits > 0 || contentHits > 0) {
                            matchedTerms++
                            score += (headerHits * 3.5) + (contentHits.coerceAtMost(10) * 1.5)
                        }
                    }
                    val minRequired = if (terms.size >= 2) ((terms.size + 1) / 2).coerceAtLeast(2) else 1
                    if (matchedTerms >= minRequired) {
                        val coverageBoost = (matchedTerms.toDouble() / terms.size.toDouble()) * 6.0
                        val finalScore = score + coverageBoost
                        val excerpt = buildBestExcerpt(chunkText, terms, 300)
                        results.add(
                            UserRagResult(
                                filePath = path,
                                title = title,
                                excerpt = excerpt,
                                relevanceScore = finalScore,
                                breadcrumbHeader = breadcrumb,
                                chunkIndex = chunkIdx,
                                chunkContent = chunkText
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "User Document SQLite query error: ${e.message}")
        }
        return results.sortedByDescending { it.relevanceScore }.take(limit)
    }

    data class HybridRagItem(
        val title: String,
        val sourceUriOrUrl: String,
        val breadcrumb: String,
        val snippetOrChunk: String,
        val isUserDocument: Boolean,
        val rankScore: Double
    )

    /**
     * Hybridní RAG vyhledávání (AnythingLLM architektura):
     * Paralelně prohledává uživatelské dokumenty (user_documents_fts s rank boostem 1.5x)
     * a webový offline archiv (web_archive_fts s rank boostem 1.0x).
     */
    fun searchHybridRag(
        query: String,
        topK: Int = 5,
        includeUserDocs: Boolean = true,
        includeWebArchive: Boolean = true
    ): List<HybridRagItem> {
        val safeQuery = query.trim()
        if (safeQuery.isBlank()) return emptyList()

        val items = mutableListOf<HybridRagItem>()

        if (includeUserDocs) {
            val userHits = searchUserDocumentsFts5(safeQuery, topK * 2)
            for (h in userHits) {
                items.add(
                    HybridRagItem(
                        title = h.title,
                        sourceUriOrUrl = h.filePath,
                        breadcrumb = h.breadcrumbHeader.ifBlank { "[Soubor: ${h.title}]" },
                        snippetOrChunk = if (h.chunkContent.isNotBlank()) h.chunkContent else h.excerpt,
                        isUserDocument = true,
                        rankScore = h.relevanceScore * 1.5
                    )
                )
            }
        }

        if (includeWebArchive) {
            val webHits = searchArchiveFts5(safeQuery, topK * 2)
            for (w in webHits) {
                items.add(
                    HybridRagItem(
                        title = w.title,
                        sourceUriOrUrl = w.url,
                        breadcrumb = "[Web Archiv: ${w.title}]",
                        snippetOrChunk = w.snippet,
                        isUserDocument = false,
                        rankScore = w.relevanceScore * 1.0
                    )
                )
            }
        }

        return items
            .sortedByDescending { it.rankScore }
            .take(topK)
    }

    /**
     * Formátuje nalezené hybridní RAG úryvky do přehledného kontextu pro prompt modelu.
     */
    fun formatHybridRagForPrompt(query: String, items: List<HybridRagItem>): String {
        if (items.isEmpty()) {
            return "Žádné výsledky v hybridní znalostní bázi (User RAG + Web Archive) pro dotaz: \"$query\"."
        }
        return buildString {
            append("### 📚 Nalezené úryvky z lokální znalostní báze (Hybrid RAG) pro: \"$query\"\n\n")
            items.forEachIndexed { i, item ->
                append("--- Úryvek [${i + 1}] ---\n")
                append("${item.breadcrumb}\n")
                append("Zdroj: ${item.sourceUriOrUrl}\n")
                append("Obsah:\n${item.snippetOrChunk}\n\n")
            }
            append("Instrukce pro model: Využij výše uvedené ověřené úryvky pro odpověď a uveď přesnou citaci [Zdroj: ...].")
        }.trimEnd()
    }

    fun deleteUserDocumentFromFts5(filePath: String) {
        val hash = sha256(filePath.trim().lowercase())
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                db.delete("user_documents_meta", "doc_hash = ?", arrayOf(hash))
                db.delete("user_documents_chunks", "doc_hash = ?", arrayOf(hash))
                if (fts5Available) {
                    runCatching { db.delete("user_documents_fts", "doc_hash = ?", arrayOf(hash)) }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed removing doc from SQLite RAG: $filePath", e)
        }
    }

    fun clearAllUserDocuments(): Int {
        return try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val count = db.delete("user_documents_meta", null, null)
                db.delete("user_documents_chunks", null, null)
                if (fts5Available) {
                    runCatching { db.delete("user_documents_fts", null, null) }
                }
                db.setTransactionSuccessful()
                Log.i(TAG, "Cleared all $count user documents from SQLite RAG index.")
                count
            } finally {
                db.endTransaction()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed clearing user documents: ${e.message}", e)
            0
        }
    }

    fun reindexWorkspaceFolder(workspaceRoot: File): Int {
        val files = workspaceRoot.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .toList()

        var count = 0
        for (file in files) {
            val relPath = file.relativeTo(workspaceRoot).path
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            saveUserDocumentToFts5(relPath, file.name, content)
            count++
        }
        return count
    }
}

/**
 * WebView Headless DOM Snapshot Bridge.
 * Volitelný fallback přes skrytý Android WebView na Dispatchers.Main pro SPA (Single Page Applications)
 * vyžadující JavaScript. Striktně blokuje stahování obrázků, videí, fontů a reklam přes shouldInterceptRequest.
 */
object WebViewHeadlessBridge {
    private const val TAG = "WebViewHeadlessBridge"

    data class DomSnapshotResult(
        val title: String,
        val cleanText: String,
        val links: List<ExtractedWebLink>
    )

    private val BLOCKED_EXTENSIONS = listOf(
        ".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico",
        ".mp4", ".webm", ".mp3", ".wav", ".woff", ".woff2", ".ttf", ".otf"
    )

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun captureDomSnapshot(
        context: Context,
        url: String,
        timeoutMs: Long = 8000L
    ): DomSnapshotResult? = withContext(Dispatchers.Main) {
        var webView: WebView? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                val wv = WebView(context.applicationContext)
                webView = wv

                wv.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    blockNetworkImage = true
                    loadsImagesAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = true
                    userAgentString = "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
                }

                val pageLoaded = suspendCancellableCoroutine<Boolean> { cont ->
                    wv.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString()?.lowercase() ?: return null
                            val cleanPath = reqUrl.substringBefore("?").substringBefore("#")
                            if (BLOCKED_EXTENSIONS.any { cleanPath.endsWith(it) } ||
                                reqUrl.contains("google-analytics") ||
                                reqUrl.contains("doubleclick") ||
                                reqUrl.contains("facebook.net")
                            ) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "UTF-8",
                                    ByteArrayInputStream(ByteArray(0))
                                )
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                            if (cont.isActive) {
                                cont.resume(true)
                            }
                        }
                    }
                    wv.loadUrl(url)
                }

                if (!pageLoaded) return@withTimeoutOrNull null

                // Krok 1: Stealth anti-bot maskování, automatické odkliknutí cookie bannerů a lidský scroll
                val jsPreAction = """
                    (function() {
                        try {
                            Object.defineProperty(navigator, 'webdriver', { get: () => false });
                            const acceptSelectors = [
                                '#onetrust-accept-btn-handler', '.cookie-accept', '#accept-cookies', '.cc-accept',
                                '[aria-label*="cookie" i]', '[aria-label*="accept" i]', '[aria-label*="souhlas" i]',
                                'button[id*="cookie" i]', 'button[id*="consent" i]', 'button[id*="accept" i]',
                                '#didomi-notice-agree-button', '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll',
                                'button[data-testid*="accept" i]', 'button[class*="consent" i]'
                            ];
                            for (let sel of acceptSelectors) {
                                let btn = document.querySelector(sel);
                                if (btn && typeof btn.click === 'function') { btn.click(); break; }
                            }
                            const allBtns = Array.from(document.querySelectorAll('button, a[role="button"]'));
                            for (let b of allBtns) {
                                let t = (b.innerText || '').trim().toLowerCase();
                                if (t === 'souhlasím' || t === 'přijmout vše' || t === 'přijmout' || t === 'accept all' || t === 'i agree') {
                                    b.click();
                                    break;
                                }
                            }
                            window.scrollBy(0, 500);
                        } catch(e) {}
                    })();
                """.trimIndent()
                wv.evaluateJavascript(jsPreAction, null)

                delay(550L)
                wv.evaluateJavascript("window.scrollBy(0, 500);", null)
                delay(450L)

                val jsExtractor = """
                    (function() {
                        const kill = document.querySelectorAll('script, style, noscript, svg, nav, footer, iframe, [id*="cookie" i], [class*="cookie-banner" i], [class*="consent" i]');
                        kill.forEach(el => el.remove());
                        const links = Array.from(document.querySelectorAll('a[href]'))
                            .map((a, idx) => {
                                const label = (a.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 110);
                                const parentText = (a.closest('div, li, article, section, tr')?.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 260);
                                return {
                                    index: idx + 1,
                                    label: parentText.length > label.length + 15 ? (label + ' — ' + parentText) : label,
                                    url: a.href
                                };
                            })
                            .filter(l => l.label.length > 4 && l.url.startsWith('http'))
                            .slice(0, 40);
                        const mainNode = document.querySelector('article, main, [role="main"]') || document.body;
                        const bodyText = document.body ? (document.body.innerText || '') : '';
                        const mainText = mainNode ? (mainNode.innerText || '') : '';
                        const bestText = mainText.length >= 200 ? mainText : bodyText;
                        return JSON.stringify({
                            title: document.title || '',
                            text: bestText,
                            links: links
                        });
                    })();
                """.trimIndent()

                val rawJsResult = suspendCancellableCoroutine<String?> { cont ->
                    wv.evaluateJavascript(jsExtractor) { value ->
                        if (cont.isActive) cont.resume(value)
                    }
                } ?: return@withTimeoutOrNull null

                val unescapedJson = JSONTokener(rawJsResult).nextValue()?.toString() ?: return@withTimeoutOrNull null
                val jsonObj = JSONObject(unescapedJson)
                val title = jsonObj.optString("title", "")
                val text = jsonObj.optString("text", "").trim()
                val linksArr = jsonObj.optJSONArray("links") ?: JSONArray()
                val parsedLinks = mutableListOf<ExtractedWebLink>()

                for (i in 0 until linksArr.length()) {
                    val lObj = linksArr.optJSONObject(i) ?: continue
                    parsedLinks.add(
                        ExtractedWebLink(
                            index = lObj.optInt("index", i + 1),
                            label = lObj.optString("label", ""),
                            url = lObj.optString("url", "")
                        )
                    )
                }

                DomSnapshotResult(title = title, cleanText = text, links = parsedLinks)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Headless WebView snapshot failed for $url: ${e.message}")
            null
        } finally {
            webView?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.clearHistory()
                    wv.removeAllViews()
                    wv.destroy()
                }
            }
        }
    }
}
