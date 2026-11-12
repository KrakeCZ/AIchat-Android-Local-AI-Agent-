package com.example.llama.agent.tools.web

import android.content.Context
import android.util.Log
import com.example.llama.agent.tools.workspace.DocumentChunk
import com.example.llama.agent.tools.workspace.SemanticSlidingWindowChunker
import com.example.llama.agent.tools.workspace.WebViewHeadlessBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer

/**
 * Rozhraní pro živé obousměrné ovládání a čtení DOMu z PiP WebView prohlížeče v MainActivity přímo z Kotlin Browser Bota.
 */
interface PipBrowserController {
    fun onLoadUrl(url: String)
    fun onScroll(direction: String, steps: Int = 1)
    fun onDismissBanners()
    fun onNavigateBack()

    /**
     * Načte URL v živém PiP WebView, odklikne cookie lišty, lidsky nascrolluje stránku
     * a přímo z vyrenderovaného DOMu vytáhne čistý text a odkazy pro model.
     */
    suspend fun navigateScrollAndExtractDom(
        url: String,
        scrollSteps: Int = 2,
        waitMs: Long = 2200L
    ): WebViewHeadlessBridge.DomSnapshotResult? = null
}

/**
 * Autonomní Browser Subagent ("Ruce Agenta" / Hledací Člověk-Bot 2026) pro lokální LLM modely.
 * Chová se jako reálný uživatel na Samsung Galaxy S24 Ultra (stealth hlavičky, živé Chromium WebView,
 * odkliknutí souhlasů, plynulý scroll, čtení vyrenderovaného DOMu a extraktivní sumarizace pro model).
 */
object AutonomousBrowserSubagent {
    private const val TAG = "AutonomousBrowserBot"

    @Volatile
    var pipController: PipBrowserController? = null

    private fun stripDiacritics(input: String): String {
        val normalized = Normalizer.normalize(input, Normalizer.Form.NFD)
        return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
    }

    /**
     * Použije živý PiP WebView (nebo Headless WebView) k otevření vyhledávače (Seznam, DuckDuckGo, Google, Brave),
     * odklikne cookie banner, nascrolluje výsledky a vytáhne jak přímý widget z vyhledávače (např. počasí / rychlý přehled),
     * tak organické výsledky vyhledávání.
     */
    suspend fun extractLiveSearchResultsFromBrowser(
        context: Context,
        query: String,
        searchUrl: String,
        providerName: String,
        maxResults: Int = 5
    ): Pair<String, List<WebSearchResult>> = withContext(Dispatchers.IO) {
        val snapshot = runCatching {
            pipController?.navigateScrollAndExtractDom(searchUrl, scrollSteps = 2, waitMs = 2400L)
                ?: WebViewHeadlessBridge.captureDomSnapshot(context, searchUrl, timeoutMs = 6500L)
        }.getOrNull() ?: return@withContext Pair("", emptyList())

        val results = mutableListOf<WebSearchResult>()
        val searchHost = runCatching { java.net.URL(searchUrl).host.lowercase().removePrefix("www.").removePrefix("search.") }.getOrDefault("")

        for (link in snapshot.links) {
            if (results.size >= maxResults) break
            val u = link.url.trim()
            val lHost = runCatching { java.net.URL(u).host.lowercase() }.getOrDefault("")
            if (!u.startsWith("http") || lHost.isBlank()) continue
            if (searchHost.isNotBlank() && lHost.contains(searchHost) && !u.contains("pocasi.seznam.cz") && !u.contains("novinky.cz") && !u.contains("seznamzpravy.cz")) continue
            if (u.contains("duckduckgo.com") || u.contains("google.com/search") || u.contains("google.com/preferences") || u.contains("accounts.google") || u.contains("login.szn.cz") || u.contains("o-seznam")) continue

            val parts = link.label.split(" — ", limit = 2)
            val title = parts[0].trim().take(110)
            val snippet = parts.getOrNull(1)?.trim()?.take(320) ?: title
            if (title.length >= 5 && results.none { it.url == u }) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = u,
                        snippet = snippet,
                        sourceProvider = "$providerName (Live Bot DOM)",
                        relevanceScore = 3.2
                    )
                )
            }
        }

        val instantWidgetSummary = if (snapshot.cleanText.length >= 80) {
            buildExtractiveSummaryForModel(snapshot.cleanText, query, maxChars = 650)
        } else {
            ""
        }

        if (instantWidgetSummary.isNotBlank() && results.isNotEmpty()) {
            val first = results[0]
            results[0] = first.copy(
                snippet = "${first.snippet}\n[Přímý výtah ze stránky vyhledávače]: $instantWidgetSummary".take(500)
            )
        } else if (instantWidgetSummary.isNotBlank() && results.isEmpty()) {
            results.add(
                WebSearchResult(
                    title = snapshot.title.ifBlank { "Živý výpis vyhledávače ($providerName): $query" },
                    url = searchUrl,
                    snippet = instantWidgetSummary,
                    sourceProvider = "$providerName (Live Bot DOM)",
                    relevanceScore = 3.0
                )
            )
        }

        Pair(instantWidgetSummary, results)
    }

    /**
     * Deterministický Kotlin Browser Bot ("Hledací Člověk-Bot") pro `web_search`:
     * Automaticky vybere nejrelevantnější webové stránky (preferuje živé zpravodajské/odborné/počasí weby před obecnou Wikipedií),
     * otevře je v PiP prohlížeči, odklikne reklamy/cookies, nascrolluje obsah, vytáhne vyrenderovaný DOM text,
     * uloží jej do SQLite RAG databáze a vytvoří sumarizovaný výtah přímo pro LLM model.
     */
    suspend fun executeAutonomousSearchAndDeepRead(
        context: Context,
        query: String,
        searchResults: List<WebSearchResult>,
        maxTokensBudget: Int = 550
    ): String = withContext(Dispatchers.IO) {
        val normQuery = stripDiacritics(query)
        val isRealtimeOrWeatherOrNews = normQuery.contains("pocasi") || normQuery.contains("teplot") ||
            normQuery.contains("predpoved") || normQuery.contains("dnes") || normQuery.contains("aktual") ||
            normQuery.contains("zprav") || normQuery.contains("cena") || normQuery.contains("kurz") ||
            normQuery.contains("2026")

        // Seřadíme kandidáty k otevření: pro aktuální dotazy preferujeme reálné weby před obecnými články z Wikipedie
        val validHttpResults = searchResults
            .filter {
                (it.url.startsWith("http://", ignoreCase = true) || it.url.startsWith("https://", ignoreCase = true)) &&
                    !it.url.contains("api.open-meteo.com")
            }
            .sortedByDescending { item ->
                val isWiki = item.url.contains("wikipedia.org", ignoreCase = true)
                val base = item.relevanceScore
                if (isRealtimeOrWeatherOrNews && isWiki) base - 3.0 else base + 1.5
            }
            .take(2)

        if (validHttpResults.isEmpty()) return@withContext ""

        val perPageTokenBudget = (maxTokensBudget / validHttpResults.size.coerceAtLeast(1)).coerceIn(220, 550)
        val deepExtracts = supervisorScope {
            validHttpResults.mapIndexed { idx, item ->
                async {
                    withTimeoutOrNull(7500L) {
                        runCatching {
                            Log.i(TAG, "🤖 Člověk-Bot otevírá a čte web #[${idx + 1}]: ${item.url}")
                            researchPageAutonomously(
                                context = context,
                                targetUrl = item.url,
                                focusQuery = query,
                                maxTokensBudget = perPageTokenBudget,
                                drivePipBrowser = (idx == 0)
                            )
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().filter {
                it.isNotBlank() &&
                    !it.startsWith("Chyba:") &&
                    !it.contains("404 Not Found", ignoreCase = true) &&
                    !it.contains("Webová stránka není k dispozici", ignoreCase = true)
            }
        }

        if (deepExtracts.isEmpty()) return@withContext ""

        buildString {
            append("\n\n### 🤖 Hledací Člověk-Bot — Sumarizovaný text přímo z otevřených webů:\n")
            for (extract in deepExtracts) {
                append(extract).append("\n\n")
            }
        }.trimEnd()
    }

    /**
     * Otevře cílovou stránku, nechá ji vyrenderovat v PiP WebView (včetně JS a odkliknutí cookie lišt),
     * nascrolluje ji dolů, vytáhne vyrenderovaný DOM text, vytvoří z něj extraktivní souhrn pro model
     * a zaindexuje plný text do SQLite FTS5 RAGu.
     */
    suspend fun researchPageAutonomously(
        context: Context,
        targetUrl: String,
        focusQuery: String,
        maxTokensBudget: Int = 550,
        drivePipBrowser: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val cleanUrl = targetUrl.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            return@withContext "Chyba: Neplatná URL adresa \"$cleanUrl\". URL musí začínat na http:// nebo https://."
        }

        val archive = WebSearchEngine.getSqliteArchive()
        var extractedTitle = cleanUrl.substringAfter("://").substringBefore("/")
        var fullCleanBody = ""

        // 1. Pokud řídíme živé PiP WebView, necháme stránku vyrenderovat, nascrollovat a přečteme přímo živý DOM z PiP!
        if (drivePipBrowser && pipController != null) {
            val pipSnapshot = runCatching {
                pipController?.navigateScrollAndExtractDom(cleanUrl, scrollSteps = 2, waitMs = 2300L)
            }.getOrNull()
            if (pipSnapshot != null && pipSnapshot.cleanText.length >= 100 &&
                !pipSnapshot.title.contains("404", ignoreCase = true) &&
                !pipSnapshot.title.contains("není k dispozici", ignoreCase = true)
            ) {
                extractedTitle = pipSnapshot.title.ifBlank { extractedTitle }
                fullCleanBody = pipSnapshot.cleanText
                Log.i(TAG, "📺 Extracted ${fullCleanBody.length} chars directly from scrolled PiP WebView DOM ($cleanUrl)")
            }
        }

        // 2. Doplnění / fallback přes rychlý HTTP reader a Headless WebView
        if (fullCleanBody.length < 180) {
            val fastViewport = WebSearchEngine.openWebPageInBrowser(cleanUrl, customTokensPerViewport = 1800)
            val isFastValid = fastViewport.length >= 260 &&
                !fastViewport.contains("enable JavaScript", ignoreCase = true) &&
                !fastViewport.contains("Chyba HTTP", ignoreCase = true) &&
                !fastViewport.contains("Chyba při otevírání", ignoreCase = true) &&
                !fastViewport.contains("404 Not Found", ignoreCase = true)

            if (isFastValid) {
                fullCleanBody = stripBrowserViewportChrome(fastViewport)
            } else {
                val snapshot = WebViewHeadlessBridge.captureDomSnapshot(context, cleanUrl, timeoutMs = 7000L)
                if (snapshot != null && snapshot.cleanText.isNotBlank()) {
                    extractedTitle = snapshot.title.ifBlank { extractedTitle }
                    fullCleanBody = snapshot.cleanText
                } else if (!fastViewport.startsWith("Chyba")) {
                    fullCleanBody = stripBrowserViewportChrome(fastViewport)
                }
            }
        }

        if (fullCleanBody.isBlank() ||
            fullCleanBody.contains("Webová stránka není k dispozici", ignoreCase = true) ||
            fullCleanBody.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true)
        ) {
            return@withContext "Chyba: Stránka $cleanUrl neobsahuje žádný čitelný text nebo je nedostupná."
        }

        // 3. Uložení a sémantický chunking (512 znaků s 64znakovým překryvem) do Web RAG FTS5
        val chunks: List<DocumentChunk> = SemanticSlidingWindowChunker.chunkText(
            fileName = extractedTitle,
            rawText = fullCleanBody
        )

        if (archive != null && chunks.isNotEmpty()) {
            val pageSession = BrowserPageSession(
                sessionId = "subagent",
                url = cleanUrl,
                title = extractedTitle,
                fullCleanText = fullCleanBody,
                structuredMetadataJson = "",
                chunks = chunks.map { it.content },
                currentChunkIndex = 0,
                extractedLinks = emptyList(),
                bytesDownloaded = fullCleanBody.length,
                loadedFromOfflineCache = false
            )
            archive.savePageSessionToFts5(pageSession)
        }

        // 4. Extraktivní sumarizace pro model (vytáhne klíčové věty, čísla, teploty, fakta a nejrelevantnější bloky)
        val charBudget = (maxTokensBudget * 3.5).toInt().coerceIn(550, 2200)
        val extractiveSummary = buildExtractiveSummaryForModel(
            rawText = fullCleanBody,
            focusQuery = focusQuery,
            maxChars = (charBudget * 0.55).toInt().coerceAtLeast(350)
        )

        val normTerms = stripDiacritics(focusQuery)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }

        val scoredChunks = chunks.map { chunk ->
            val normChunk = stripDiacritics(chunk.content)
            var score = 0.0
            for (term in normTerms) {
                if (normChunk.contains(term)) {
                    score += 3.0
                }
            }
            // Bonus za číselné údaje (teploty °C, procenta, časy, ceny, data)
            if (Regex("""\b\d+\s*(?:°C|°|%|km/h|mm|hPa|Kč|EUR|USD)\b""", RegexOption.IGNORE_CASE).containsMatchIn(chunk.content)) {
                score += 2.5
            }
            if (chunk.chunkIndex == 0) {
                score += 1.0
            }
            Pair(chunk, score)
        }.sortedByDescending { it.second }

        val maxChunksToTake = ((charBudget * 0.45).toInt() / SemanticSlidingWindowChunker.DEFAULT_CHUNK_SIZE).coerceIn(1, 3)
        val selectedChronological = scoredChunks
            .take(maxChunksToTake)
            .map { it.first }
            .sortedBy { it.chunkIndex }

        buildString {
            append("#### 🌐 Web: $extractedTitle ($cleanUrl)\n")
            if (extractiveSummary.isNotBlank()) {
                append("📋 **Klíčový souhrn ze stránky (přečteno a sumarizováno Botem):**\n")
                append(extractiveSummary).append("\n\n")
            }
            if (selectedChronological.isNotEmpty()) {
                append("📄 **Detailní kontext ze stránky:**\n")
                for (ch in selectedChronological) {
                    append(ch.content.trim()).append("\n\n")
                }
            }
        }.trimEnd()
    }

    private fun stripBrowserViewportChrome(viewportText: String): String {
        val afterStart = viewportText.substringAfter("--- OBSAH SEKCE", viewportText)
        val afterHeader = if (afterStart != viewportText) afterStart.substringAfter("---\n", afterStart) else viewportText
        return afterHeader.substringBefore("--- KONEC SEKCE ---").trim()
    }

    /**
     * Vytvoří huštěný faktický souhrn z textu webové stránky pro LLM model:
     * Vybere věty a řádky s nejvyšší hustotou hledaných pojmů, čísel, jednotek (°C, %, km/h, Kč) a faktů.
     */
    fun buildExtractiveSummaryForModel(
        rawText: String,
        focusQuery: String,
        maxChars: Int = 700
    ): String {
        val cleanLines = rawText
            .replace(Regex("[ \\t]+"), " ")
            .lines()
            .map { it.trim() }
            .filter { line ->
                line.length in 12..380 &&
                    !line.lowercase().contains("cookies") &&
                    !line.lowercase().contains("ochrana osobních údajů") &&
                    !line.lowercase().contains("přihlásit se") &&
                    !line.lowercase().contains("vytvořit účet")
            }
            .distinct()

        if (cleanLines.isEmpty()) return rawText.take(maxChars).trim()

        val normTerms = stripDiacritics(focusQuery)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }

        val unitRegex = Regex("""(?i)(?:\b\d+[.,]?\d*\s*(?:°C|°|%|km/h|m/s|mm|hPa|Kč|€|\$|let|hod)|jasno|polojasno|oblačno|zataženo|déšť|přeháňky|sněžení|teplota|předpověď)""")

        val scored = cleanLines.mapIndexed { idx, line ->
            val normLine = stripDiacritics(line)
            var score = 0.0
            for (term in normTerms) {
                if (normLine.contains(term)) {
                    score += 3.0
                }
            }
            val unitMatches = unitRegex.findAll(line).count()
            if (unitMatches > 0) {
                score += unitMatches * 2.2
            }
            if (idx < 8) {
                score += 0.8
            }
            Triple(idx, line, score)
        }.sortedByDescending { it.third }

        val chosen = mutableListOf<Pair<Int, String>>()
        var usedChars = 0
        for ((idx, line, score) in scored) {
            if (score <= 0.0 && chosen.size >= 3) break
            if (usedChars + line.length + 3 > maxChars && chosen.isNotEmpty()) break
            chosen.add(idx to line)
            usedChars += line.length + 3
            if (chosen.size >= 8) break
        }

        return chosen
            .sortedBy { it.first }
            .joinToString("\n") { "• ${it.second}" }
    }
}
