package com.example.llama.agent.tools.web

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Headless čtečka obsahu webových stránek (SOTA 2026).
 *
 * Plně integrovaná s Data Saver Engine (GZIP/Deflate, 3-8 kB/s FUP shaper, DOM Distiller),
 * kontrolou přístupu (WebAccessRuleManager) a strukturovaným DOM výpisem odkazů pro modely.
 */
object UrlContentReader {
    private const val TAG = "UrlContentReader"
    private const val MAX_CONTENT_CHARS = 4500

    suspend fun readUrl(targetUrl: String, maxLength: Int = MAX_CONTENT_CHARS): String =
        withContext(Dispatchers.IO) {
            if (targetUrl.isBlank()) return@withContext "Chyba: Byla předána prázdná URL adresa."

            var currentUrl = targetUrl.trim()
            if (!currentUrl.startsWith("http://") && !currentUrl.startsWith("https://")) {
                currentUrl = "https://$currentUrl"
            }

            // Bezpečnostní kontrola přístupu podle uživatelských pravidel (Read URLs)
            if (!WebAccessRuleManager.isUrlAllowed(UrlAccessType.READ, currentUrl)) {
                return@withContext "⛔ Přístup k URL '$currentUrl' je blokován uživatelským bezpečnostním pravidlem (Read URLs)."
            }

            val cfg = WebSearchEngine.uiConfig.value

            try {
                var redirects = 0
                var conn: HttpURLConnection
                while (true) {
                    val url = URL(currentUrl)
                    conn = WebSearchEngine.openOptimizedConnection(currentUrl).apply {
                        requestMethod = "GET"
                        setRequestProperty(
                            "Accept",
                            "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8"
                        )
                        instanceFollowRedirects = false
                    }

                    val status = conn.responseCode
                    if (status in 300..399) {
                        val location = conn.getHeaderField("Location")
                        conn.disconnect()
                        if (location != null && redirects < 5) {
                            currentUrl = if (location.startsWith("/")) {
                                "${url.protocol}://${url.host}$location"
                            } else {
                                URL(url, location).toString()
                            }
                            redirects++
                            continue
                        }
                    }
                    break
                }

                val status = conn.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    conn.disconnect()
                    return@withContext "Chyba HTTP $status při načítání stránky: $currentUrl"
                }

                // DataSaver strop: V DataSaver režimu omezíme čtení na maxDownloadBytesPerRequest (16 KB)
                val maxBytes = if (cfg.dataSaverEnabled) cfg.maxDownloadBytesPerRequest else 256 * 1024
                val rawHtml = WebSearchEngine.readStreamWithOptionalThrottle(
                    WebSearchEngine.getDecodedInputStream(conn),
                    maxBytes
                )
                conn.disconnect()

                val cleanText = WebSearchEngine.distillHtmlToReaderText(
                    rawHtml,
                    cfg.experimentalReaderDomDistiller
                )

                // Extrakce sémantických odkazů pro DOM kontext modelu
                val links = WebSearchEngine.extractInteractiveLinks(rawHtml, currentUrl)
                val linksSection = if (links.isNotEmpty()) {
                    buildString {
                        append("\n\n### 🔗 DOM Odkazy na stránce (pro další čtení volejte read_url_content(url)):\n")
                        links.take(25).forEach { l ->
                            append("- [#${l.index}] ${l.label} -> ${l.url}\n")
                        }
                    }
                } else ""

                val fullText = cleanText + linksSection
                val effectiveMaxChars = if (maxLength > 0) maxLength else 6000

                if (fullText.length > effectiveMaxChars) {
                    fullText.substring(0, effectiveMaxChars) +
                        "\n\n... [Obsah stránky zkrácen na $effectiveMaxChars znaků. Pro zbytek volejte browser_open nebo browser_scroll]"
                } else {
                    fullText
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed reading URL $targetUrl", e)
                "Chyba při stahování URL: ${e.message}"
            }
        }
}
