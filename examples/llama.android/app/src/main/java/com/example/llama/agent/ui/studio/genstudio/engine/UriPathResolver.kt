package com.example.llama.agent.ui.studio.genstudio.engine

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder

/**
 * Senior Systems / Android 15/16 Storage Resolver.
 * Zajišťuje absolutní transparentní kompatibilitu mezi Android Storage Access Framework (SAF)
 * content:// URI schématy a nízkoúrovňovými POSIX C++ mmap() / open() voláními.
 */
object UriPathResolver {

    private const val TAG = "UriPathResolver"

    /**
     * Pokusí se přeložit pathOrUri (který může být content:// URI nebo absolutní cesta)
     * na reálnou, přímo čitelnou POSIX cestu souborového systému.
     * Pokud přímá cesta nelze přímo číst přes File z důvodu Scoped Storage,
     * otevře ParcelFileDescriptor a vrátí /proc/self/fd/<fd>,
     * což v Linuxu/Androidu poskytuje platnou POSIX cestu pro C++ open() a mmap().
     */
    fun resolveToPath(context: Context, pathOrUri: String?): String? {
        if (pathOrUri.isNullOrBlank()) return null

        // 0. Explicitní fd: handoff překlad na POSIX procfs
        if (pathOrUri.startsWith("fd:")) {
            val rawFd = pathOrUri.substringAfter("fd:")
            return "/proc/self/fd/$rawFd"
        }

        // 1. Již je to existující POSIX cesta
        if (pathOrUri.startsWith("/")) {
            val f = File(pathOrUri)
            if (f.exists()) {
                Log.i(TAG, "✅ Používám přímou POSIX cestu: ${f.absolutePath} (${f.length() / (1024 * 1024)} MB)")
                return f.absolutePath
            }
        }

        // 2. file:// schéma
        if (pathOrUri.startsWith("file://")) {
            val parsed = Uri.parse(pathOrUri)
            val path = parsed.path
            if (path != null) {
                val f = File(path)
                if (f.exists()) {
                    Log.i(TAG, "✅ file:// URI přeloženo na: ${f.absolutePath}")
                    return f.absolutePath
                }
            }
        }

        // 3. content:// SAF schéma
        if (pathOrUri.startsWith("content://")) {
            val uri = Uri.parse(pathOrUri)

            // Pokus o dešifrování primary: external storage
            val directPath = tryResolvePrimaryExternalStorage(context, uri)
            if (directPath != null) {
                val f = File(directPath)
                if (f.exists() && f.canRead()) {
                    Log.i(TAG, "✅ SAF URI úspěšně přeloženo na přímou POSIX cestu: $directPath (${f.length() / (1024 * 1024)} MB)")
                    return f.absolutePath
                }
            }

            // Fallback: Linux Native ParcelFileDescriptor handoff ("/proc/self/fd/<rawFd>")
            try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val rawFd = pfd.detachFd() // Odpojí deskriptor od Java GC pro trvalé vlastnictví v C++
                    val procFdPath = "/proc/self/fd/$rawFd"
                    Log.i(TAG, "✅ SAF URI otevřeno přes ParcelFileDescriptor -> $procFdPath (${uri})")
                    return procFdPath
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nelze otevřít PFD pro $uri: ${e.message}")
            }
        }

        return pathOrUri
    }

    /**
     * Pokusí se získat přímou cestu na /storage/emulated/0/... z com.android.externalstorage.documents URI.
     */
    private fun tryResolvePrimaryExternalStorage(context: Context, uri: Uri): String? {
        try {
            val uriStr = uri.toString()
            val primaryDir = Environment.getExternalStorageDirectory().absolutePath

            // Přímá textová detekce primary: tagu v URL
            if (uriStr.contains("primary%3A") || uriStr.contains("primary:")) {
                val rawSegment = if (uriStr.contains("primary%3A")) {
                    uriStr.substringAfter("primary%3A")
                } else {
                    uriStr.substringAfter("primary:")
                }
                val cleanSegment = rawSegment.substringBefore("?").substringBefore("&")
                val decoded = URLDecoder.decode(cleanSegment, "UTF-8")
                val candidate = if (decoded.startsWith("/")) "$primaryDir$decoded" else "$primaryDir/$decoded"
                val f = File(candidate)
                if (f.exists()) {
                    Log.i(TAG, "✅ Detekován přímý soubor z primary URL: $candidate")
                    return candidate
                }
            }

            val docId = if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                uri.path?.substringAfterLast("document/") ?: uri.lastPathSegment
            }

            if (docId != null) {
                val decodedDocId = URLDecoder.decode(docId, "UTF-8")
                if (decodedDocId.startsWith("primary:", ignoreCase = true)) {
                    val relPath = decodedDocId.substringAfter(":")
                    val fullPath = if (relPath.startsWith("/")) "$primaryDir$relPath" else "$primaryDir/$relPath"
                    return fullPath
                } else if (decodedDocId.contains(":")) {
                    val parts = decodedDocId.split(":")
                    if (parts.size >= 2) {
                        val storageId = parts[0]
                        val relPath = parts[1]
                        val candidate = "/storage/$storageId/$relPath"
                        if (File(candidate).exists()) {
                            return candidate
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Chyba při parsování SAF URI: ${e.message}")
        }
        return null
    }

    /**
     * Zjistí, zda je soubor modelu fyzicky dostupný a čitelný.
     */
    fun isModelAvailable(context: Context, pathOrUri: String?): Boolean {
        if (pathOrUri.isNullOrBlank()) return false

        // 1. Zkusit přímý File
        if (pathOrUri.startsWith("/")) {
            val f = File(pathOrUri)
            if (f.exists() && f.canRead() && f.length() > 1024) return true
        }

        // 2. Zkusit SAF překlad
        if (pathOrUri.startsWith("content://")) {
            val uri = Uri.parse(pathOrUri)
            val directPath = tryResolvePrimaryExternalStorage(context, uri)
            if (directPath != null) {
                val f = File(directPath)
                if (f.exists() && f.canRead() && f.length() > 1024) return true
            }

            // 3. Zkusit otevřít přes ContentResolver
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    return pfd.statSize > 1024
                }
            } catch (e: Exception) {
                Log.w(TAG, "isModelAvailable selhalo pro $pathOrUri: ${e.message}")
            }
        }

        return false
    }

    /**
     * Otevře InputStream pro daný model bez ohledu na schéma cesty.
     */
    fun openInputStream(context: Context, pathOrUri: String?): InputStream? {
        if (pathOrUri.isNullOrBlank()) return null

        try {
            if (pathOrUri.startsWith("/")) {
                val f = File(pathOrUri)
                if (f.exists() && f.canRead()) {
                    return FileInputStream(f)
                }
            }

            if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                val uri = Uri.parse(pathOrUri)
                return context.contentResolver.openInputStream(uri)
            }

            val f = File(pathOrUri)
            if (f.exists() && f.canRead()) {
                return FileInputStream(f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nelze otevřít InputStream pro $pathOrUri: ${e.message}", e)
        }

        return null
    }

    /**
     * Otevře ParcelFileDescriptor pro daný model (umožňuje přímý zero-copy C++ mmap).
     */
    fun openFileDescriptor(context: Context, pathOrUri: String?): ParcelFileDescriptor? {
        if (pathOrUri.isNullOrBlank()) return null

        try {
            if (pathOrUri.startsWith("content://") || pathOrUri.startsWith("file://")) {
                val uri = Uri.parse(pathOrUri)
                return context.contentResolver.openFileDescriptor(uri, "r")
            }

            val f = File(pathOrUri)
            if (f.exists() && f.canRead()) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Nelze otevřít ParcelFileDescriptor pro $pathOrUri: ${e.message}", e)
        }

        return null
    }

    /**
     * Zjistí reálnou velikost souboru v bajtech.
     */
    fun getFileSize(context: Context, pathOrUri: String?): Long {
        if (pathOrUri.isNullOrBlank()) return 0L

        try {
            if (pathOrUri.startsWith("/")) {
                val f = File(pathOrUri)
                if (f.exists()) return f.length()
            }

            if (pathOrUri.startsWith("content://")) {
                val uri = Uri.parse(pathOrUri)
                val direct = tryResolvePrimaryExternalStorage(context, uri)
                if (direct != null) {
                    val f = File(direct)
                    if (f.exists()) return f.length()
                }

                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getLong(sizeIndex)
                    }
                }

                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    return pfd.statSize
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Chyba při zjišťování velikosti pro $pathOrUri: ${e.message}")
        }

        return 0L
    }
}
