package com.example.llama.agent.tools.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.llama.agent.tools.workspace.WebArchiveSqliteFts5
import com.example.llama.agent.tools.workspace.WebViewHeadlessBridge
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.ln

/**
 * Výsledek vyhledávání zpětně kompatibilní s původní třídou WebSearchResult,
 * obohacený o identifikaci zdroje, datum a kombinované RRF + BM25 skóre.
 */
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceProvider: String = "Web",
    val publishedDate: String = "",
    val relevanceScore: Double = 1.0
)

/**
 * Interaktivní odkaz extrahovaný ze stránky pro autonomní navigaci LLM agenta.
 */
data class ExtractedWebLink(
    val index: Int,
    val label: String,
    val url: String
)

/**
 * Stav otevřené webové stránky v prohlížeči LLM agenta (umožňuje rolování po tokenech).
 */
data class BrowserPageSession(
    val sessionId: String,
    val url: String,
    val title: String,
    val fullCleanText: String,
    val structuredMetadataJson: String,
    val chunks: List<String>,
    var currentChunkIndex: Int = 0,
    val extractedLinks: List<ExtractedWebLink>,
    val bytesDownloaded: Int,
    val renderedViaWebView: Boolean = false,
    val loadedFromOfflineCache: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Položka importovatelné databáze vyhledávačů, vlastních webů a API endpointů.
 * Umožňuje prohledávat jakýkoliv web na internetu (přes URL šablonu nebo site: doménu).
 */
data class SearchSourceEntry(
    val id: String,
    val name: String,
    val searchUrlPattern: String,
    val isJsonApi: Boolean = false,
    val isAtomOrRssXml: Boolean = false,
    val siteDomainFilter: String = "",
    val customLinkRegex: String = "",
    val customSnippetRegex: String = "",
    val category: String = "general",
    val enabled: Boolean = true,
    val priorityWeight: Double = 1.0
)

/**
 * Uživatelsky nastavitelná konfigurace pro UI (Jetpack Compose / Settings Screen)
 * včetně experimentálních funkcí pro prohlížení webu LLM agenty (SOTA 2026).
 */
data class WebAgentUiConfig(
    // --- Základní nastavení a Data Saver (TODO 0) ---
    val primarySearchEngine: String = "duckduckgo", // duckduckgo, brave, google, searxng, seznam, wikipedia, hybrid
    val enabledSearchEnginesOrder: List<String> = listOf("duckduckgo", "wikipedia", "seznam", "brave", "science", "imported_db"),
    val autonomousBotDeepRead: Boolean = true,      // Kotlin Bot automaticky otevře a přečte Top výsledky při vyhledávání
    val dataSaverEnabled: Boolean = true,
    val autoDetectMeteredNetwork: Boolean = true,
    val maxDownloadBytesPerRequest: Int = 96 * 1024, // 96 KB HTML proud bez obrázků
    val targetBandwidthKbps: Int = 6,                // Cílový tok 3-8 kB/s (výchozí 6 kB/s)
    val throttleBandwidthEnabled: Boolean = false,   // Striktní token-bucket omezovač na 3-8 kB/s

    // --- Nastavení kontextu a tokenů pro LLM (TODO 1) ---
    val maxTokensPerSource: Int = 450,               // Počet tokenů na 1 viewport / zdroj
    val maxSearchResults: Int = 6,
    val charsPerTokenRatio: Float = 3.5f,

    // --- Multi-source přepínače (TODO 0a) ---
    val enableDuckDuckGo: Boolean = true,
    val enableWikipedia: Boolean = true,
    val enableScienceProviders: Boolean = true,      // OpenAlex / Semantic Scholar / ConnectedPapers / arXiv
    val enableImportedDatabase: Boolean = true,

    // --- Vývojářské a bezpečnostní volby (TODO 0b) ---
    val disableWebSecurityForDev: Boolean = false,   // Povolit localhost, HTTP a self-signed certy (per-connection)
    val networkTimeoutMs: Int = 7500,

    // --- Lokální SQLite FTS5 Cache & Headless WebView (TODO 4 & TODO 5) ---
    val enableSqliteFts5Archive: Boolean = true,     // Ukládat a prohledávat přečtené weby v SQLite FTS5
    val preferOfflineCacheFirst: Boolean = false,    // Pokud true, nejprve vrátí lokální FTS5 shody bez sítě
    val enableHeadlessWebViewFallback: Boolean = true, // Spustit skrytý WebView bez obrázků pro JS SPA weby

    // --- Experimentální funkce pro LLM Web Agenty (SOTA 2026) ---
    val experimentalExtractJsonLd: Boolean = true,        // Extrahuje <script type="application/ld+json">
    val experimentalReaderDomDistiller: Boolean = true,   // Dvoufázová selekce <article>/<main> + Markdown
    val experimentalStealthUserAgentRotation: Boolean = true,
    val experimentalSmartQueryReranking: Boolean = true,  // Hybridní RRF + Okapi BM25-lite reranking
    val experimentalAutoPreloadTopResult: Boolean = false // Automaticky načte 1. viewport nejlepšího výsledku
)

/**
 * Autonomní vyhledávací a prohlížecí modul pro Deep Research a online asistenci (SOTA 2026).
 */
object WebSearchEngine {
    private const val TAG = "WebSearchEngine"

    private val USER_AGENTS_STEALTH_2026 = listOf(
        "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 16; SM-S938B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Mobile Safari/537.36",
        "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro XL) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"
    )
    private const val USER_AGENT_DATA_SAVER =
        "Mozilla/5.0 (Linux; Android 15; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Mobile Safari/537.36"

    private val _uiConfig = MutableStateFlow(WebAgentUiConfig())
    val uiConfig: StateFlow<WebAgentUiConfig> = _uiConfig.asStateFlow()

    private val importedDatabase = ConcurrentHashMap<String, SearchSourceEntry>()
    private val browserSessions = ConcurrentHashMap<String, BrowserPageSession>()
    private val navigationHistory = ArrayDeque<String>()

    @Volatile
    private var sqliteArchive: WebArchiveSqliteFts5? = null

    @Volatile
    private var appContextRef: Context? = null

    init {
        loadDefaultDatabase2026()
    }

    /**
     * Inicializace s Contextem pro aktivaci SQLite FTS5 archivu (TODO 4),
     * Headless WebView (TODO 5) a automatické detekce FUP sítě.
     */
    fun initialize(context: Context) {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        if (sqliteArchive == null) {
            sqliteArchive = WebArchiveSqliteFts5.getInstance(appCtx)
        }
        syncWithSystemNetworkState(appCtx)
    }

    fun getSqliteArchive(): WebArchiveSqliteFts5? = sqliteArchive

    fun updateUiConfig(transform: (WebAgentUiConfig) -> WebAgentUiConfig) {
        _uiConfig.update { current -> transform(current) }
    }

    /**
     * Automatická adaptace DataSaveru podle stavu sítě Androidu (Metered / Cellular FUP).
     */
    fun syncWithSystemNetworkState(context: Context) {
        if (appContextRef == null) {
            appContextRef = context.applicationContext
        }
        if (sqliteArchive == null) {
            sqliteArchive = WebArchiveSqliteFts5.getInstance(context.applicationContext)
        }

        val currentCfg = _uiConfig.value
        if (!currentCfg.autoDetectMeteredNetwork) return

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val isMetered = cm.isActiveNetworkMetered
        val activeNet = cm.activeNetwork
        val caps = if (activeNet != null) cm.getNetworkCapabilities(activeNet) else null
        val bandwidthDownKbps = caps?.linkDownstreamBandwidthKbps ?: 10000

        if (isMetered || bandwidthDownKbps < 1500) {
            updateUiConfig {
                it.copy(
                    dataSaverEnabled = true,
                    maxDownloadBytesPerRequest = 64 * 1024
                )
            }
            Log.i(TAG, "Metered/constrained network detected. DataSaver enforced (64 KB HTML text stream cap).")
        }
    }

    // =========================================================================
    // SPRÁVA IMPORTOVATELNÉ DATABÁZE WEBŮ A VYHLEDÁVAČŮ (TODO 0a)
    // =========================================================================

    fun getImportedSources(): List<SearchSourceEntry> {
        return importedDatabase.values.sortedBy { it.name }
    }

    fun addSource(source: SearchSourceEntry) {
        importedDatabase[source.id] = source
    }

    fun clearImportedSources() {
        importedDatabase.clear()
    }

    fun importSourcesDatabase(sources: List<SearchSourceEntry>, clearExisting: Boolean = false) {
        if (clearExisting) {
            importedDatabase.clear()
        }
        for (source in sources) {
            if (source.id.isNotBlank() &&
                (source.searchUrlPattern.contains("{query}") || source.siteDomainFilter.isNotBlank())
            ) {
                val normalizedPattern = if (source.searchUrlPattern.contains("{query}")) {
                    source.searchUrlPattern
                } else {
                    "https://html.duckduckgo.com/html/?q=site:${source.siteDomainFilter.trim()}+{query}"
                }
                importedDatabase[source.id] = source.copy(searchUrlPattern = normalizedPattern)
            }
        }
    }

    fun importSourcesFromRawText(rawContent: String, clearExisting: Boolean = false): Int {
        val trimmed = rawContent.trim()
        if (trimmed.isBlank()) return 0
        val parsedList = mutableListOf<SearchSourceEntry>()

        try {
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                val arr = if (trimmed.startsWith("[")) {
                    JSONArray(trimmed)
                } else {
                    JSONObject(trimmed).optJSONArray("sources") ?: JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "custom_$i").trim()
                    val name = obj.optString("name", id).trim()
                    val pattern = obj.optString("searchUrlPattern", "").trim()
                    val domain = obj.optString("siteDomainFilter", "").trim()
                    val isJson = obj.optBoolean("isJsonApi", false)
                    val isXml = obj.optBoolean("isAtomOrRssXml", false)
                    val category = obj.optString("category", "custom").trim()
                    val enabled = obj.optBoolean("enabled", true)
                    parsedList.add(
                        SearchSourceEntry(
                            id = id,
                            name = name,
                            searchUrlPattern = pattern,
                            isJsonApi = isJson,
                            isAtomOrRssXml = isXml,
                            siteDomainFilter = domain,
                            category = category,
                            enabled = enabled
                        )
                    )
                }
            } else {
                trimmed.lines().forEachIndexed { idx, line ->
                    val cleanLine = line.trim()
                    if (cleanLine.isBlank() || cleanLine.startsWith("#")) return@forEachIndexed
                    val parts = cleanLine.split(";", ",", "\t").map { it.trim() }
                    when {
                        parts.size >= 3 -> {
                            parsedList.add(
                                SearchSourceEntry(
                                    id = parts[0].ifBlank { "csv_$idx" },
                                    name = parts[1].ifBlank { parts[0] },
                                    searchUrlPattern = parts[2],
                                    isJsonApi = parts.getOrNull(3)?.equals("json", ignoreCase = true) == true
                                )
                            )
                        }
                        cleanLine.contains("{query}") -> {
                            val host = runCatching { URL(cleanLine.replace("{query}", "test")).host }
                                .getOrDefault("web_$idx")
                            parsedList.add(
                                SearchSourceEntry(
                                    id = "url_${host}_$idx",
                                    name = host,
                                    searchUrlPattern = cleanLine
                                )
                            )
                        }
                        else -> {
                            val domain = cleanLine
                                .removePrefix("https://")
                                .removePrefix("http://")
                                .removePrefix("www.")
                                .substringBefore("/")
                            if (domain.contains(".")) {
                                parsedList.add(
                                    SearchSourceEntry(
                                        id = "domain_${domain.replace(".", "_")}",
                                        name = "Web: $domain",
                                        searchUrlPattern = "https://html.duckduckgo.com/html/?q=site:$domain+{query}",
                                        siteDomainFilter = domain,
                                        category = "domain"
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing imported sources: ${e.message}")
        }

        if (parsedList.isNotEmpty()) {
            importSourcesDatabase(parsedList, clearExisting)
        }
        return parsedList.size
    }

    private fun loadDefaultDatabase2026() {
        val defaults = listOf(
            SearchSourceEntry(
                id = "openalex_works_2026",
                name = "OpenAlex Science Works (2026)",
                searchUrlPattern = "https://api.openalex.org/works?search={query}&per-page=5&sort=relevance_score:desc",
                isJsonApi = true,
                category = "science",
                enabled = true,
                priorityWeight = 1.25
            ),
            SearchSourceEntry(
                id = "hn_algolia_realtime",
                name = "HackerNews Algolia (Tech & Dev 2026)",
                searchUrlPattern = "https://hn.algolia.com/api/v1/search?query={query}&tags=story&hitsPerPage=5",
                isJsonApi = true,
                category = "tech",
                enabled = true,
                priorityWeight = 1.15
            ),
            SearchSourceEntry(
                id = "arxiv_open_science",
                name = "arXiv.org Preprints",
                searchUrlPattern = "https://export.arxiv.org/api/query?search_query=all:{query}&sortBy=relevance&max_results=5",
                isJsonApi = false,
                isAtomOrRssXml = true,
                category = "science",
                enabled = true,
                priorityWeight = 1.2
            ),
            SearchSourceEntry(
                id = "github_repos_api",
                name = "GitHub Public Repositories",
                searchUrlPattern = "https://api.github.com/search/repositories?q={query}&sort=stars&order=desc&per_page=4",
                isJsonApi = true,
                category = "developer",
                enabled = true,
                priorityWeight = 1.1
            ),
            SearchSourceEntry(
                id = "stackexchange_qa",
                name = "StackOverflow / StackExchange API",
                searchUrlPattern = "https://api.stackexchange.com/2.3/search/advanced?order=desc&sort=relevance&q={query}&site=stackoverflow&pagesize=4",
                isJsonApi = true,
                category = "developer",
                enabled = true,
                priorityWeight = 1.1
            ),
            SearchSourceEntry(
                id = "searxng_public_meta",
                name = "SearXNG Federated JSON",
                searchUrlPattern = "https://searx.be/search?q={query}&format=json&language=cs-CZ",
                isJsonApi = true,
                category = "general",
                enabled = true,
                priorityWeight = 1.1
            ),
            SearchSourceEntry(
                id = "brave_html_search",
                name = "Brave Search Web",
                searchUrlPattern = "https://search.brave.com/search?q={query}&source=web",
                isJsonApi = false,
                category = "general",
                enabled = false
            ),
            SearchSourceEntry(
                id = "seznam_cz_web",
                name = "Seznam.cz Vyhledávání",
                searchUrlPattern = "https://search.seznam.cz/?q={query}",
                isJsonApi = false,
                category = "cz_regional",
                enabled = false
            )
        )
        importSourcesDatabase(defaults, clearExisting = false)
    }

    // =========================================================================
    // 1. PARALELNÍ MULTI-ZDROJOVÉ VYHLEDÁVÁNÍ (ZPĚTNĚ KOMPATIBILNÍ API)
    // =========================================================================

    /**
     * Hlavní vstupní bod pro vyhledávání.
     * Plně zpětně kompatibilní s původním voláním `WebSearchEngine.search(query, maxResults)`.
     * Zároveň podporuje `targetSourceId` (z Kostry 0) i `specificSourceIds` (z Kostry 01).
     */
    suspend fun search(
        query: String,
        maxResults: Int = 5,
        targetSourceId: String? = null,
        specificSourceIds: List<String>? = null,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): List<WebSearchResult> = withContext(dispatcher) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) return@withContext emptyList()

        val cleanQuery = WebToolsDispatcher.extractSearchQueryFromUserPrompt(trimmedQuery).ifBlank { trimmedQuery }
        val rawQueryToUse = if (cleanQuery.isNotBlank() && cleanQuery.length >= 2) cleanQuery else trimmedQuery
        val queryToUse = WebToolsDispatcher.normalizeCzechSearchQuery(rawQueryToUse)

        val cfg = _uiConfig.value
        val targetMax = if (maxResults > 0) maxResults else cfg.maxSearchResults
        val providerRankedLists = ConcurrentHashMap<String, List<WebSearchResult>>()

        val effectiveSourceIds = when {
            specificSourceIds != null -> specificSourceIds
            !targetSourceId.isNullOrBlank() -> listOf(targetSourceId)
            else -> null
        }

        // 0. Kontrola lokálního hybridního RAGu (Uživatelské dokumenty + Web Archive FTS5)
        val archive = sqliteArchive
        val weatherOrRealtimeIntent = isWeatherQuery(queryToUse)
        if (cfg.enableSqliteFts5Archive && archive != null) {
            val hybridHits = runCatching { archive.searchHybridRag(queryToUse, targetMax) }
                .getOrDefault(emptyList())
                .filter { hit ->
                    if (weatherOrRealtimeIntent && !hit.isUserDocument &&
                        hit.sourceUriOrUrl.contains("wikipedia.org", ignoreCase = true)
                    ) {
                        false
                    } else {
                        true
                    }
                }
            if (hybridHits.isNotEmpty()) {
                val mapped = hybridHits.map { item ->
                    WebSearchResult(
                        title = if (item.isUserDocument) "📄 ${item.title}" else "🌐 ${item.title}",
                        url = item.sourceUriOrUrl,
                        snippet = "${item.breadcrumb}\n${item.snippetOrChunk}".take(400),
                        sourceProvider = if (item.isUserDocument) "Local User Document (RAG)" else "Web Archive FTS5",
                        relevanceScore = item.rankScore
                    )
                }
                providerRankedLists["Local_Hybrid_RAG"] = mapped
                Log.i(TAG, "📚 Found ${mapped.size} relevant hits in Local Hybrid RAG (will merge with live web search).")
            }
        }

        val orderedEngines = cfg.enabledSearchEnginesOrder.map { it.lowercase().trim() }.ifEmpty {
            listOf(cfg.primarySearchEngine.lowercase().trim())
        }
        Log.i(TAG, "🔍 Parallel Multi-Source Search for: \"$queryToUse\" (max=$targetMax, Order=${orderedEngines.joinToString(" -> ")}, DataSaver=${cfg.dataSaverEnabled})")

        supervisorScope {
            val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

            // A. Pokud jde o dotaz na počasí, paralelně dotážeme živou meteorologickou telemetrii (Open-Meteo + české počasí)
            if (isWeatherQuery(queryToUse)) {
                tasks.add(async {
                    withTimeoutOrNull(5500L) {
                        val weatherList = mutableListOf<WebSearchResult>()
                        runCatching { searchLiveWeatherOpenMeteo(queryToUse, weatherList) }
                            .onFailure { Log.w(TAG, "Live weather telemetry failed: ${it.message}") }
                        if (weatherList.isNotEmpty()) {
                            providerRankedLists["Live_Weather_Telemetry"] = weatherList
                        }
                    }
                    Unit
                })
            }

            for (engineId in orderedEngines) {
                when (engineId) {
                    "brave" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val braveList = mutableListOf<WebSearchResult>()
                                    runCatching { searchBraveHtml(queryToUse, braveList, targetMax) }
                                        .onFailure { Log.w(TAG, "Brave search failed: ${it.message}") }
                                    if (braveList.isNotEmpty()) {
                                        providerRankedLists["Brave_Web"] = braveList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                    "google" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val gList = mutableListOf<WebSearchResult>()
                                    runCatching { searchGoogleLiteHtml(queryToUse, gList, targetMax) }
                                        .onFailure { Log.w(TAG, "Google Lite failed: ${it.message}") }
                                    if (gList.isNotEmpty()) {
                                        providerRankedLists["Google_Lite"] = gList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                    "searxng" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val sList = mutableListOf<WebSearchResult>()
                                    runCatching { searchSearxngJson(queryToUse, sList, targetMax) }
                                        .onFailure { Log.w(TAG, "SearXNG failed: ${it.message}") }
                                    if (sList.isNotEmpty()) {
                                        providerRankedLists["SearXNG_JSON"] = sList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                    "seznam" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val sezList = mutableListOf<WebSearchResult>()
                                    runCatching { searchSeznamHtml(queryToUse, sezList, targetMax) }
                                        .onFailure { Log.w(TAG, "Seznam search failed: ${it.message}") }
                                    if (sezList.isNotEmpty()) {
                                        providerRankedLists["Seznam_Web"] = sezList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                    "duckduckgo" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val ddgList = mutableListOf<WebSearchResult>()
                                    runCatching { searchDuckDuckGoLite(queryToUse, ddgList, targetMax) }
                                        .onFailure { Log.w(TAG, "DDG Lite failed: ${it.message}") }
                                    if (ddgList.size < targetMax) {
                                        runCatching { searchDuckDuckGoHtml(queryToUse, ddgList, targetMax) }
                                            .onFailure { Log.w(TAG, "DDG HTML fallback failed: ${it.message}") }
                                    }
                                    if (ddgList.isNotEmpty()) {
                                        providerRankedLists["DuckDuckGo_Web"] = ddgList
                                    }
                                }
                                Unit
                            })

                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val instantList = mutableListOf<WebSearchResult>()
                                    runCatching { searchDuckDuckGoInstantApi(queryToUse, instantList, targetMax) }
                                        .onFailure { Log.w(TAG, "DDG Instant failed: ${it.message}") }
                                    if (instantList.isNotEmpty()) {
                                        providerRankedLists["DDG_Instant"] = instantList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                    "wikipedia" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val wikiCs = mutableListOf<WebSearchResult>()
                                    runCatching { searchWikipediaFulltext(queryToUse, "cs", wikiCs, targetMax) }
                                    if (wikiCs.isNotEmpty()) providerRankedLists["Wikipedia_CS"] = wikiCs
                                }
                                Unit
                            })
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val wikiEn = mutableListOf<WebSearchResult>()
                                    runCatching { searchWikipediaFulltext(queryToUse, "en", wikiEn, targetMax) }
                                    if (wikiEn.isNotEmpty()) providerRankedLists["Wikipedia_EN"] = wikiEn
                                }
                                Unit
                            })
                        }
                    }
                    "science" -> {
                        if (effectiveSourceIds == null) {
                            tasks.add(async {
                                withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                                    val scienceList = mutableListOf<WebSearchResult>()
                                    runCatching { searchSemanticScholarGraph(queryToUse, scienceList, 4) }
                                        .onFailure { Log.w(TAG, "SemanticScholar failed: ${it.message}") }
                                    if (scienceList.isNotEmpty()) {
                                        providerRankedLists["SemanticScholar"] = scienceList
                                    }
                                }
                                Unit
                            })
                        }
                    }
                }
            }

            // E. Importovaná databáze zdrojů (OpenAlex, arXiv, HN Algolia, GitHub, StackOverflow + uživatelské weby)
            if (cfg.enableImportedDatabase || orderedEngines.contains("imported_db") || effectiveSourceIds != null) {
                val activeSources = importedDatabase.values.filter { entry ->
                    entry.enabled && (effectiveSourceIds == null || effectiveSourceIds.contains(entry.id))
                }
                for (source in activeSources) {
                    if (!cfg.enableScienceProviders && source.category == "science") continue
                    tasks.add(async {
                        withTimeoutOrNull(cfg.networkTimeoutMs.toLong()) {
                            val srcList = mutableListOf<WebSearchResult>()
                            runCatching {
                                queryImportedSource(source, queryToUse, srcList, targetMax)
                            }.onFailure {
                                Log.w(TAG, "Imported source [${source.id}] error: ${it.message}")
                            }
                            if (srcList.isNotEmpty()) {
                                providerRankedLists["Imported_${source.id}"] = srcList
                            }
                        }
                        Unit
                    })
                }
            }

            tasks.awaitAll()
        }

        // D2. Živý Browser Bot Fallback: Pokud rychlé HTTP vyhledávače vrátily jen Wikipedii nebo 0 webových výsledků,
        // použijeme reálný Chromium WebView (PiP / Headless) k otevření primárního vyhledávače, odkliknutí bannerů,
        // nascrollování a přečtení výsledků přímo z vyrenderovaného DOMu!
        val hasOrganicWebResults = providerRankedLists.keys.any { key ->
            key == "DuckDuckGo_Web" || key == "Seznam_Web" || key == "Brave_Web" || key == "Google_Lite" || key == "SearXNG_JSON"
        }
        val ctx = appContextRef
        if (!hasOrganicWebResults && ctx != null) {
            val topEngine = orderedEngines.firstOrNull { it != "wikipedia" && it != "science" && it != "imported_db" } ?: "seznam"
            val encQ = URLEncoder.encode(queryToUse, "UTF-8")
            val liveSearchUrl = when (topEngine) {
                "seznam" -> "https://search.seznam.cz/?q=$encQ"
                "brave" -> "https://search.brave.com/search?q=$encQ"
                "google" -> "https://www.google.com/search?q=$encQ"
                "searxng" -> "https://searx.be/search?q=$encQ"
                else -> "https://html.duckduckgo.com/html/?q=$encQ"
            }
            Log.i(TAG, "🤖 Organic HTTP search was empty/blocked -> Invoking Live Chromium Browser Bot on $liveSearchUrl")
            val (_, liveDomResults) = runCatching {
                AutonomousBrowserSubagent.extractLiveSearchResultsFromBrowser(
                    context = ctx,
                    query = queryToUse,
                    searchUrl = liveSearchUrl,
                    providerName = topEngine.replaceFirstChar { it.uppercase() },
                    maxResults = targetMax
                )
            }.getOrDefault(Pair("", emptyList()))
            if (liveDomResults.isNotEmpty()) {
                providerRankedLists["Live_Browser_DOM"] = liveDomResults
            }
        }

        // E. Fúze výsledků přes Reciprocal Rank Fusion (RRF, k=60) + Okapi BM25-lite Reranking
        val fusedAndRanked = if (cfg.experimentalSmartQueryReranking) {
            fuseAndRankWithRrfAndBm25(queryToUse, providerRankedLists).take(targetMax)
        } else {
            providerRankedLists.values.flatten()
                .distinctBy { normalizeUrlForDeduplication(it.url) }
                .take(targetMax)
        }

        // F. Uložení nalezených snippetů do lokálního SQLite FTS5 archivu (TODO 4)
        if (cfg.enableSqliteFts5Archive && archive != null && fusedAndRanked.isNotEmpty()) {
            runCatching { archive.cacheSearchResultsToFts5(queryToUse, fusedAndRanked) }
        }

        // G. Experimentální preload 1. výsledku do stavového prohlížeče
        if (cfg.experimentalAutoPreloadTopResult && fusedAndRanked.isNotEmpty()) {
            runCatching { openWebPageInBrowser(fusedAndRanked.first().url) }
        }

        Log.i(TAG, "✅ Search finished for '$queryToUse'. Total verified unique results: ${fusedAndRanked.size}")
        fusedAndRanked
    }

    // =========================================================================
    // 2. STAVOVÝ PROHLÍŽEČ PRO LLM AGENTY (TODO 1 + TODO 4 + TODO 5)
    // =========================================================================

    suspend fun openWebPageInBrowser(
        targetUrl: String,
        customTokensPerViewport: Int? = null
    ): String = withContext(Dispatchers.IO) {
        var cleanUrl = targetUrl.trim()
        if (cleanUrl.isBlank()) {
            return@withContext "Chyba: Prázdná URL adresa."
        }
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }

        if (!WebAccessRuleManager.isUrlAllowed(UrlAccessType.READ, cleanUrl)) {
            Log.w(TAG, "Access blocked by WebAccessRuleManager (READ) for $cleanUrl")
            return@withContext "Chyba: Načtení URL '$cleanUrl' je blokováno bezpečnostním pravidlem (READ URLs)."
        }

        val cfg = _uiConfig.value
        val tokensBudget = (customTokensPerViewport ?: cfg.maxTokensPerSource).coerceIn(100, 4000)
        val charsPerChunk = (tokensBudget * cfg.charsPerTokenRatio).toInt().coerceAtLeast(350)
        val archive = sqliteArchive

        if (cfg.enableSqliteFts5Archive && cfg.preferOfflineCacheFirst && archive != null) {
            val cachedPage = archive.getCachedPageByUrl(cleanUrl)
            if (cachedPage != null && cachedPage.fullCleanText.length >= 120) {
                val rechunked = paginateTextByCharBudget(cachedPage.fullCleanText, charsPerChunk)
                val session = cachedPage.copy(chunks = rechunked, currentChunkIndex = 0, loadedFromOfflineCache = true)
                browserSessions["active"] = session
                browserSessions[cleanUrl] = session
                return@withContext formatBrowserViewportForModel(session)
            }
        }

        try {
            val maxBytes = if (cfg.dataSaverEnabled) cfg.maxDownloadBytesPerRequest else 256 * 1024
            var currentUrl = cleanUrl
            var redirects = 0
            var conn: HttpURLConnection

            while (true) {
                conn = openOptimizedConnection(currentUrl)
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = false
                val status = conn.responseCode
                if (status in 300..399) {
                    val location = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (location != null && redirects < 5) {
                        val base = URL(currentUrl)
                        currentUrl = URL(base, location).toString()
                        redirects++
                        continue
                    }
                }
                break
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                val fallback = if (cfg.enableSqliteFts5Archive) archive?.getCachedPageByUrl(cleanUrl) else null
                if (fallback != null) {
                    browserSessions["active"] = fallback
                    return@withContext formatBrowserViewportForModel(fallback)
                }
                return@withContext "Chyba HTTP $code při otevírání stránky: $currentUrl"
            }

            val rawHtml = readStreamWithOptionalThrottle(getDecodedInputStream(conn), maxBytes)
            conn.disconnect()

            var pageTitle = extractHtmlTitle(rawHtml).ifBlank { currentUrl }
            val jsonLdMeta = if (cfg.experimentalExtractJsonLd) extractJsonLdMetadata(rawHtml) else ""
            var links = extractInteractiveLinks(rawHtml, currentUrl)
            var distilledText = distillHtmlToReaderText(rawHtml, cfg.experimentalReaderDomDistiller)
            var usedWebView = false

            val ctx = appContextRef
            if (cfg.enableHeadlessWebViewFallback && ctx != null && requiresJsHeadlessFallback(rawHtml, distilledText)) {
                Log.i(TAG, "⚡ JS SPA detected on $currentUrl. Invoking WebViewHeadlessBridge (no images/media)...")
                val snapshot = WebViewHeadlessBridge.captureDomSnapshot(
                    context = ctx,
                    url = currentUrl,
                    timeoutMs = cfg.networkTimeoutMs.toLong().coerceAtLeast(6000L)
                )
                if (snapshot != null && snapshot.cleanText.length > distilledText.length) {
                    pageTitle = snapshot.title.ifBlank { pageTitle }
                    distilledText = snapshot.cleanText
                    if (snapshot.links.isNotEmpty()) {
                        links = snapshot.links
                    }
                    usedWebView = true
                }
            }

            val chunks = paginateTextByCharBudget(distilledText, charsPerChunk)
            val bytesCount = rawHtml.toByteArray(Charsets.UTF_8).size
            val session = BrowserPageSession(
                sessionId = "active",
                url = currentUrl,
                title = pageTitle,
                fullCleanText = distilledText,
                structuredMetadataJson = jsonLdMeta,
                chunks = chunks,
                currentChunkIndex = 0,
                extractedLinks = links,
                bytesDownloaded = bytesCount,
                renderedViaWebView = usedWebView,
                loadedFromOfflineCache = false
            )

            browserSessions["active"] = session
            browserSessions[currentUrl] = session
            if (navigationHistory.lastOrNull() != currentUrl) {
                navigationHistory.addLast(currentUrl)
            }

            if (cfg.enableSqliteFts5Archive && archive != null && distilledText.length >= 80) {
                runCatching { archive.savePageSessionToFts5(session) }
            }

            formatBrowserViewportForModel(session)
        } catch (e: Exception) {
            Log.e(TAG, "Browser open error for $cleanUrl: ${e.message}")
            val offlineSession = if (cfg.enableSqliteFts5Archive) archive?.getCachedPageByUrl(cleanUrl) else null
            if (offlineSession != null) {
                val rechunked = paginateTextByCharBudget(offlineSession.fullCleanText, charsPerChunk)
                val restored = offlineSession.copy(chunks = rechunked, currentChunkIndex = 0, loadedFromOfflineCache = true)
                browserSessions["active"] = restored
                return@withContext formatBrowserViewportForModel(restored)
            }
            "Chyba při otevírání stránky ($cleanUrl): ${e.localizedMessage ?: "Neznámá síťová chyba"}"
        }
    }

    private fun requiresJsHeadlessFallback(rawHtml: String, distilledText: String): Boolean {
        if (distilledText.length < 160) return true
        val lower = distilledText.lowercase()
        val jsWarnings = listOf(
            "enable javascript",
            "you need to enable javascript",
            "javascript is required",
            "prosím povolte javascript",
            "vyžaduje povolený javascript"
        )
        if (jsWarnings.any { lower.contains(it) }) return true
        val hasSpaRoot = rawHtml.contains("id=\"root\"", ignoreCase = true) ||
            rawHtml.contains("id=\"__next\"", ignoreCase = true) ||
            rawHtml.contains("id=\"app\"", ignoreCase = true)
        return hasSpaRoot && distilledText.length < 300
    }

    fun scrollBrowserPage(direction: String = "down", steps: Int = 1): String {
        val session = browserSessions["active"]
            ?: return "Chyba: Žádná webová stránka není aktuálně otevřena. Nejprve zavolej browser_open(url)."

        val safeSteps = steps.coerceIn(1, 20)
        val maxIndex = (session.chunks.size - 1).coerceAtLeast(0)

        session.currentChunkIndex = when (direction.lowercase().trim()) {
            "down", "next", "dolu", "dolů" -> (session.currentChunkIndex + safeSteps).coerceAtMost(maxIndex)
            "up", "prev", "nahoru" -> (session.currentChunkIndex - safeSteps).coerceAtLeast(0)
            "top", "start", "zacatek", "začátek" -> 0
            "bottom", "end", "konec" -> maxIndex
            else -> (session.currentChunkIndex + 1).coerceAtMost(maxIndex)
        }

        return formatBrowserViewportForModel(session)
    }

    suspend fun followBrowserLink(linkIndex: Int, customTokensPerViewport: Int? = null): String {
        val session = browserSessions["active"]
            ?: return "Chyba: Žádná stránka není otevřena. Nelze kliknout na odkaz #$linkIndex."

        val targetLink = session.extractedLinks.find { it.index == linkIndex }
            ?: return "Chyba: Odkaz [#$linkIndex] na aktuální stránce neexistuje. Dostupné odkazy: 1 až ${session.extractedLinks.size}."

        if (!WebAccessRuleManager.isUrlAllowed(UrlAccessType.EXECUTE, targetLink.url)) {
            Log.w(TAG, "Action blocked by WebAccessRuleManager (EXECUTE) for ${targetLink.url}")
            return "Chyba: Provedení akce a otevření odkazu [#$linkIndex] (${targetLink.url}) je blokováno bezpečnostním pravidlem (EXECUTE URLs)."
        }

        return openWebPageInBrowser(targetLink.url, customTokensPerViewport)
    }

    suspend fun navigateBrowserBack(): String {
        if (navigationHistory.size < 2) {
            return "Chyba: Historie prohlížení neobsahuje předchozí stránku."
        }
        navigationHistory.removeLastOrNull()
        val previousUrl = navigationHistory.lastOrNull() ?: return "Chyba historie."
        val cached = browserSessions[previousUrl]
        return if (cached != null) {
            browserSessions["active"] = cached
            formatBrowserViewportForModel(cached)
        } else {
            openWebPageInBrowser(previousUrl)
        }
    }

    fun findInOpenPage(keyword: String): String {
        val session = browserSessions["active"]
            ?: return "Chyba: Žádná stránka není otevřena."
        val needle = keyword.trim()
        if (needle.isBlank()) return "Chyba: Prázdný hledaný výraz."

        val foundIndex = session.chunks.indexOfFirst { it.contains(needle, ignoreCase = true) }
        if (foundIndex == -1) {
            return "Výraz \"$needle\" nebyl na stránce '${session.title}' nalezen."
        }
        session.currentChunkIndex = foundIndex
        return "Výraz \"$needle\" nalezen v sekci ${foundIndex + 1}/${session.chunks.size}:\n\n" +
            formatBrowserViewportForModel(session)
    }

    private fun formatBrowserViewportForModel(session: BrowserPageSession): String {
        val totalChunks = session.chunks.size.coerceAtLeast(1)
        val currentIdx = session.currentChunkIndex.coerceIn(0, totalChunks - 1)
        val viewportContent = if (session.chunks.isNotEmpty()) {
            session.chunks[currentIdx]
        } else {
            "(Stránka neobsahuje čitelný text)"
        }

        return buildString {
            append("### 🌐 Prohlížeč Agenta: ${session.title}\n")
            append("URL: ${session.url}\n")
            append("Pozice (Viewport): Sekce ${currentIdx + 1} z $totalChunks ")
            append("(Staženo: ${session.bytesDownloaded / 1024} KB, DataSaver: ${_uiConfig.value.dataSaverEnabled}")
            if (session.renderedViaWebView) append(", Render: Headless WebView")
            if (session.loadedFromOfflineCache) append(", Zdroj: SQLite FTS5 Offline Cache")
            append(")\n")

            if (currentIdx == 0 && session.structuredMetadataJson.isNotBlank()) {
                append("Strukturovaná metadata (JSON-LD): ${session.structuredMetadataJson.take(320)}\n")
            }

            append("\n--- OBSAH SEKCE (${currentIdx + 1}/$totalChunks) ---\n")
            append(viewportContent)
            append("\n--- KONEC SEKCE ---\n\n")

            if (currentIdx + 1 < totalChunks) {
                append("💡 Pro čtení další části zavolej `browser_scroll` s `direction=\"down\"` (zbývá ${totalChunks - currentIdx - 1} sekcí).\n")
            }

            if (session.extractedLinks.isNotEmpty()) {
                append("\n🔗 Interaktivní odkazy na stránce (zavolej `browser_click` s `link_index`):\n")
                val linkSlice = session.extractedLinks.take(15)
                for (link in linkSlice) {
                    append("[#${link.index}] ${link.label} -> ${link.url}\n")
                }
            }
        }.trimEnd()
    }

    // =========================================================================
    // 3. IMPLEMENTACE POSKYTOVATELŮ VYHLEDÁVÁNÍ (2026 MULTI-SOURCE)
    // =========================================================================

    private suspend fun queryImportedSource(
        source: SearchSourceEntry,
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val finalUrl = source.searchUrlPattern.replace("{query}", encodedQuery)

        val conn = openOptimizedConnection(finalUrl)
        conn.requestMethod = "GET"
        if (source.isJsonApi) {
            conn.setRequestProperty("Accept", "application/json")
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val raw = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                _uiConfig.value.maxDownloadBytesPerRequest * 2
            )
            conn.disconnect()

            when {
                source.isJsonApi -> parseFlexibleJsonSearch(raw, source.name, results, maxResults)
                source.isAtomOrRssXml || raw.trimStart().startsWith("<?xml", ignoreCase = true) || raw.contains("<feed", ignoreCase = true) ->
                    parseAtomOrRssFeed(raw, source.name, results, maxResults)
                else -> parseGenericOrCustomHtmlSearch(raw, finalUrl, source, results, maxResults)
            }
        } else {
            conn.disconnect()
        }
    }

    private suspend fun searchSemanticScholarGraph(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiUrl =
            "https://api.semanticscholar.org/graph/v1/paper/search?query=$encodedQuery&limit=$maxResults&fields=title,url,abstract,year,citationCount"

        val conn = openOptimizedConnection(apiUrl)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val jsonStr = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                _uiConfig.value.maxDownloadBytesPerRequest * 2
            )
            conn.disconnect()

            val root = JSONObject(jsonStr)
            val dataArr = root.optJSONArray("data") ?: return
            for (i in 0 until dataArr.length()) {
                if (results.size >= maxResults) break
                val item = dataArr.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                val url = item.optString("url", "").trim()
                val abstractText = item.optString("abstract", "").trim()
                val year = item.optInt("year", 0)
                val citations = item.optInt("citationCount", 0)

                if (title.isNotBlank() && url.isNotBlank()) {
                    val metaPrefix = if (year > 0) "[$year | Citace: $citations] " else ""
                    results.add(
                        WebSearchResult(
                            title = title,
                            url = url,
                            snippet = metaPrefix + abstractText.ifBlank { "Vědecká publikace (Semantic Scholar / ConnectedPapers)." },
                            sourceProvider = "SemanticScholar/ConnectedPapers",
                            publishedDate = if (year > 0) year.toString() else ""
                        )
                    )
                }
            }
        } else {
            conn.disconnect()
        }
    }

    private suspend fun searchBraveHtml(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val braveUrl = "https://search.brave.com/search?q=$encodedQuery&source=web"
        val conn = openOptimizedConnection(braveUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val html = readStreamWithOptionalThrottle(getDecodedInputStream(conn), 128 * 1024)
            conn.disconnect()
            parseBraveSearchHtml(html, results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private fun parseBraveSearchHtml(
        html: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val itemPattern = Pattern.compile(
            """<div\b[^>]*\bclass=['"][^'"]*snippet[^'"]*['"][^>]*>([\s\S]*?)</div>\s*</div>""",
            Pattern.CASE_INSENSITIVE
        )
        val linkPattern = Pattern.compile(
            """<a\b[^>]*\bhref=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val snippetPattern = Pattern.compile(
            """<div\b[^>]*\bclass=['"][^'"]*snippet-description[^'"]*['"][^>]*>([\s\S]*?)</div>|<p\b[^>]*\bclass=['"][^'"]*snippet-content[^'"]*['"][^>]*>([\s\S]*?)</p>""",
            Pattern.CASE_INSENSITIVE
        )

        val iMatcher = itemPattern.matcher(html)
        while (iMatcher.find() && results.size < maxResults) {
            val block = iMatcher.group(1) ?: continue
            val lMatcher = linkPattern.matcher(block)
            if (!lMatcher.find()) continue

            val rawUrl = lMatcher.group(1) ?: continue
            val title = stripHtmlTags(lMatcher.group(2) ?: "")
            if (rawUrl.startsWith("#") || rawUrl.contains("search.brave.com") || title.isBlank()) continue

            val sMatcher = snippetPattern.matcher(block)
            val snippet = if (sMatcher.find()) stripHtmlTags(sMatcher.group(1) ?: sMatcher.group(2) ?: "") else title

            val cleanUrl = extractActualUrl(rawUrl)
            if (cleanUrl.isNotBlank() && results.none { it.url == cleanUrl }) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = cleanUrl,
                        snippet = snippet,
                        sourceProvider = "Brave Search Web"
                    )
                )
            }
        }

        if (results.isEmpty()) {
            val genericLinks = extractInteractiveLinks(html, "https://search.brave.com")
            for (link in genericLinks) {
                if (results.size >= maxResults) break
                if (link.url.startsWith("http") && !link.url.contains("brave.com") && link.label.length >= 10) {
                    results.add(
                        WebSearchResult(
                            title = link.label,
                            url = link.url,
                            snippet = "Výsledek vyhledávání Brave Search",
                            sourceProvider = "Brave Search Web"
                        )
                    )
                }
            }
        }
    }

    private suspend fun searchGoogleLiteHtml(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val googleUrl = "https://www.google.com/search?q=$encodedQuery&gbv=1&num=$maxResults"
        val conn = openOptimizedConnection(googleUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val html = readStreamWithOptionalThrottle(getDecodedInputStream(conn), 128 * 1024)
            conn.disconnect()
            parseGoogleLiteHtml(html, results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private fun parseGoogleLiteHtml(
        html: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val linkPattern = Pattern.compile(
            """<a\b[^>]*\bhref=['"]/url\?q=([^"&]+)[^'"]*['"][^>]*>([\s\S]*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val lMatcher = linkPattern.matcher(html)
        while (lMatcher.find() && results.size < maxResults) {
            val rawUrlEncoded = lMatcher.group(1) ?: continue
            val titleHtml = lMatcher.group(2) ?: ""
            val title = stripHtmlTags(titleHtml).trim()
            if (title.isBlank() || rawUrlEncoded.contains("google.com")) continue

            val cleanUrl = runCatching { URLDecoder.decode(rawUrlEncoded, "UTF-8") }.getOrDefault(rawUrlEncoded)
            if (cleanUrl.startsWith("http") && results.none { it.url == cleanUrl }) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = cleanUrl,
                        snippet = title,
                        sourceProvider = "Google Search Lite"
                    )
                )
            }
        }
    }

    private suspend fun searchSearxngJson(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searxUrl = "https://searx.be/search?q=$encodedQuery&format=json&language=cs-CZ"
        val conn = openOptimizedConnection(searxUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val jsonStr = readStreamWithOptionalThrottle(getDecodedInputStream(conn), 128 * 1024)
            conn.disconnect()
            parseFlexibleJsonSearch(jsonStr, "SearXNG Federated", results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private suspend fun searchSeznamHtml(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val seznamUrl = "https://search.seznam.cz/?q=$encodedQuery"
        val conn = openOptimizedConnection(seznamUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        }
        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val html = readStreamWithOptionalThrottle(getDecodedInputStream(conn), 128 * 1024)
            conn.disconnect()
            parseSeznamHtml(html, results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private fun isWeatherQuery(query: String): Boolean {
        val norm = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "").lowercase()
        return norm.contains("pocasi") || norm.contains("teplot") || norm.contains("predpoved") ||
            norm.contains("weather") || norm.contains("srazk") || norm.contains("dest") ||
            norm.contains("snih") || norm.contains("boure") || norm.contains("bourk")
    }

    private fun wmoWeatherCodeToCzech(code: Int): String = when (code) {
        0 -> "Jasno ☀️"
        1 -> "Skoro jasno 🌤️"
        2 -> "Polojasno ⛅"
        3 -> "Zataženo ☁️"
        45, 48 -> "Mlha / mrznoucí mlha 🌫️"
        51, 53, 55 -> "Mrholení 🌦️"
        56, 57 -> "Mrznoucí mrholení 🌧️"
        61, 63, 65 -> "Déšť (slabý až silný) 🌧️"
        66, 67 -> "Mrznoucí déšť 🌧️❄️"
        71, 73, 75 -> "Sněžení ❄️"
        77 -> "Sněhová zrna ❄️"
        80, 81, 82 -> "Dešťové přeháňky 🌦️"
        85, 86 -> "Sněhové přeháňky 🌨️"
        95 -> "Bouřka ⛈️"
        96, 99 -> "Bouřka s krupobitím ⛈️"
        else -> "Proměnlivá oblačnost (WMO $code)"
    }

    private suspend fun searchLiveWeatherOpenMeteo(
        query: String,
        results: MutableList<WebSearchResult>
    ) {
        val stopWords = setOf(
            "vyhledej", "najdi", "hledej", "pocasi", "počasí", "teplota", "teploty", "predpoved", "předpověď",
            "dnes", "zitra", "zítra", "aktualni", "aktuální", "aktualne", "aktuálně", "jak", "jake", "jaké",
            "je", "bude", "ve", "v", "na", "pro", "mesto", "město", "meste", "městě", "obec", "obci", "cr", "čr",
            "ceska", "česká", "republika", "weather", "today", "now", "in", "city", "town", "kondice", "stav"
        )
        val candidateTokens = query
            .replace(Regex("[^\\p{L}\\p{N}\\s-]+"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 2 && it.lowercase() !in stopWords && !it.matches(Regex("\\d+")) }

        val cityCandidate = candidateTokens.joinToString(" ").ifBlank { "Praha" }
        val encodedCity = URLEncoder.encode(cityCandidate, "UTF-8")
        var geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedCity&count=8&language=cs&format=json"

        var geoConn = openOptimizedConnection(geoUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (geoConn.responseCode != HttpURLConnection.HTTP_OK) {
            geoConn.disconnect()
            return
        }
        var geoJsonStr = readStreamWithOptionalThrottle(getDecodedInputStream(geoConn), 16 * 1024)
        geoConn.disconnect()

        var geoRoot = JSONObject(geoJsonStr)
        var geoArr = geoRoot.optJSONArray("results")

        // Pokud v prvním výsledku není lokalita v ČR a dotaz měl háčky/čárky, zkusíme i ASCII variantu (např. Děčín -> Decin)
        val hasCzInFirstPass = geoArr != null && (0 until geoArr.length()).any { idx ->
            geoArr?.optJSONObject(idx)?.optString("country_code", "")?.equals("CZ", ignoreCase = true) == true
        }
        if (!hasCzInFirstPass) {
            val asciiCity = java.text.Normalizer.normalize(cityCandidate, java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            if (!asciiCity.equals(cityCandidate, ignoreCase = true)) {
                val encAscii = URLEncoder.encode(asciiCity, "UTF-8")
                val asciiConn = openOptimizedConnection("https://geocoding-api.open-meteo.com/v1/search?name=$encAscii&count=8&language=cs&format=json").apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }
                if (asciiConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val asciiJson = readStreamWithOptionalThrottle(getDecodedInputStream(asciiConn), 16 * 1024)
                    asciiConn.disconnect()
                    val asciiArr = JSONObject(asciiJson).optJSONArray("results")
                    if (asciiArr != null && asciiArr.length() > 0) {
                        geoArr = asciiArr
                    }
                } else {
                    asciiConn.disconnect()
                }
            }
        }
        if (geoArr == null || geoArr.length() == 0) return

        // Preferujeme lokalitu v ČR (CZ), případně na Slovensku (SK)
        var chosenLoc = geoArr.optJSONObject(0) ?: return
        for (i in 0 until geoArr.length()) {
            val item = geoArr.optJSONObject(i) ?: continue
            val cc = item.optString("country_code", "")
            if (cc.equals("CZ", ignoreCase = true) || cc.equals("SK", ignoreCase = true)) {
                chosenLoc = item
                break
            }
        }

        val locName = chosenLoc.optString("name", cityCandidate)
        val admin1 = chosenLoc.optString("admin1", "")
        val country = chosenLoc.optString("country", "")
        val lat = chosenLoc.optDouble("latitude", Double.NaN)
        val lon = chosenLoc.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return

        val forecastUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,surface_pressure" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max" +
            "&timezone=auto&forecast_days=3"

        val fConn = openOptimizedConnection(forecastUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (fConn.responseCode != HttpURLConnection.HTTP_OK) {
            fConn.disconnect()
            return
        }
        val fJsonStr = readStreamWithOptionalThrottle(getDecodedInputStream(fConn), 24 * 1024)
        fConn.disconnect()

        val fRoot = JSONObject(fJsonStr)
        val current = fRoot.optJSONObject("current")
        val daily = fRoot.optJSONObject("daily")

        val sb = StringBuilder()
        val fullPlace = listOf(locName, admin1, country).filter { it.isNotBlank() }.joinToString(", ")
        sb.append("Lokalita: $fullPlace (GPS: ${"%.2f".format(lat)}, ${"%.2f".format(lon)})\n")

        if (current != null) {
            val temp = current.optDouble("temperature_2m", Double.NaN)
            val feels = current.optDouble("apparent_temperature", Double.NaN)
            val hum = current.optInt("relative_humidity_2m", -1)
            val precip = current.optDouble("precipitation", 0.0)
            val wind = current.optDouble("wind_speed_10m", 0.0)
            val press = current.optDouble("surface_pressure", 0.0)
            val wCode = current.optInt("weather_code", 0)
            val wDesc = wmoWeatherCodeToCzech(wCode)
            val timeStr = current.optString("time", "")

            sb.append("• Aktuální stav ($timeStr): $wDesc\n")
            if (!temp.isNaN()) sb.append("• Aktuální teplota: ${"%.1f".format(temp)} °C (pocitová teplota: ${"%.1f".format(feels)} °C)\n")
            if (hum >= 0) sb.append("• Vlhkost vzduchu: $hum % | Vítr: ${"%.1f".format(wind)} km/h | Srážky aktuálně: ${"%.1f".format(precip)} mm")
            if (press > 0) sb.append(" | Tlak: ${"%.0f".format(press)} hPa")
            sb.append("\n")
        }

        if (daily != null) {
            val times = daily.optJSONArray("time")
            val tMax = daily.optJSONArray("temperature_2m_max")
            val tMin = daily.optJSONArray("temperature_2m_min")
            val dCodes = daily.optJSONArray("weather_code")
            val pSum = daily.optJSONArray("precipitation_sum")
            val pProb = daily.optJSONArray("precipitation_probability_max")

            if (times != null && tMax != null && tMin != null) {
                sb.append("• Denní předpověď:\n")
                for (d in 0 until times.length().coerceAtMost(3)) {
                    val dayDate = times.optString(d, "")
                    val label = when (d) {
                        0 -> "Dnes ($dayDate)"
                        1 -> "Zítra ($dayDate)"
                        else -> "Pozítří ($dayDate)"
                    }
                    val maxT = tMax.optDouble(d, Double.NaN)
                    val minT = tMin.optDouble(d, Double.NaN)
                    val code = dCodes?.optInt(d, 0) ?: 0
                    val sumMm = pSum?.optDouble(d, 0.0) ?: 0.0
                    val probPct = pProb?.optInt(d, 0) ?: 0
                    sb.append("  - $label: ${wmoWeatherCodeToCzech(code)}, min ${"%.1f".format(minT)} °C / max ${"%.1f".format(maxT)} °C, srážky ${"%.1f".format(sumMm)} mm (pravděpodobnost $probPct %)\n")
                }
            }
        }

        val czWeatherWebUrl = "https://search.seznam.cz/?q=${URLEncoder.encode("počasí $locName", "UTF-8")}"
        results.add(
            WebSearchResult(
                title = "Aktuální počasí a předpověď: $fullPlace (Živá meteorologická stanice)",
                url = czWeatherWebUrl,
                snippet = sb.toString().trim(),
                sourceProvider = "Open-Meteo & Seznam Počasí (Live)",
                relevanceScore = 8.5
            )
        )
    }

    private fun parseSeznamHtml(
        html: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val linkPattern = Pattern.compile(
            """<a\b[^>]*\bclass=['"][^'"]*Result-title-link[^'"]*['"][^>]*\bhref=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>|<a\b[^>]*\bhref=['"]([^'"]+)['"][^>]*\bclass=['"][^'"]*Result-title-link[^'"]*['"][^>]*>([\s\S]*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val lMatcher = linkPattern.matcher(html)
        while (lMatcher.find() && results.size < maxResults) {
            val rawUrl = lMatcher.group(1) ?: lMatcher.group(3) ?: continue
            val title = stripHtmlTags(lMatcher.group(2) ?: lMatcher.group(4) ?: "").trim()
            val cleanUrl = extractActualUrl(rawUrl)
            if (cleanUrl.startsWith("http") && title.isNotBlank() && results.none { it.url == cleanUrl }) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = cleanUrl,
                        snippet = title,
                        sourceProvider = "Seznam.cz Vyhledávání"
                    )
                )
            }
        }
        if (results.isEmpty()) {
            val genericLinks = extractInteractiveLinks(html, "https://search.seznam.cz")
            for (link in genericLinks) {
                if (results.size >= maxResults) break
                val u = link.url
                if (u.startsWith("http") && !u.contains("search.seznam.cz") && !u.contains("login.szn.cz") && !u.contains("o-seznam") && link.label.length >= 10) {
                    results.add(
                        WebSearchResult(
                            title = link.label,
                            url = u,
                            snippet = link.label,
                            sourceProvider = "Seznam.cz Vyhledávání"
                        )
                    )
                }
            }
        }
    }

    private suspend fun searchDuckDuckGoLite(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val liteUrl = "https://lite.duckduckgo.com/lite/"

        val conn = openOptimizedConnection(liteUrl).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            instanceFollowRedirects = true
        }

        conn.outputStream.use { os ->
            os.write("q=$encodedQuery".toByteArray(Charsets.UTF_8))
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val html = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                128 * 1024
            )
            conn.disconnect()
            parseDuckDuckGoLiteHtml(html, "DuckDuckGo Lite", results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private suspend fun searchDuckDuckGoHtml(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val htmlUrl = "https://html.duckduckgo.com/html/?q=$encodedQuery"

        val conn = openOptimizedConnection(htmlUrl).apply {
            requestMethod = "GET"
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            instanceFollowRedirects = true
        }

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val html = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                128 * 1024
            )
            conn.disconnect()
            parseDuckDuckGoLiteHtml(html, "DuckDuckGo HTML", results, maxResults)
        } else {
            conn.disconnect()
        }
    }

    private fun parseDuckDuckGoLiteHtml(
        html: String,
        providerLabel: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val linkPattern = Pattern.compile(
            """<a\b[^>]*\bclass=['"][^'"]*(?:result-link|result__a)[^'"]*['"][^>]*\bhref=['"]([^'"]+)['"][^>]*>([\s\S]*?)</a>|<a\b[^>]*\bhref=['"]([^'"]+)['"][^>]*\bclass=['"][^'"]*(?:result-link|result__a)[^'"]*['"][^>]*>([\s\S]*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val snippetPattern = Pattern.compile(
            """<td\b[^>]*\bclass=['"][^'"]*result-snippet[^'"]*['"][^>]*>([\s\S]*?)</td>|<a\b[^>]*\bclass=['"][^'"]*result__snippet[^'"]*['"][^>]*>([\s\S]*?)</a>|<div\b[^>]*\bclass=['"][^'"]*result__snippet[^'"]*['"][^>]*>([\s\S]*?)</div>""",
            Pattern.CASE_INSENSITIVE
        )

        val lMatcher = linkPattern.matcher(html)
        val sMatcher = snippetPattern.matcher(html)

        while (lMatcher.find() && results.size < maxResults) {
            val rawUrl = lMatcher.group(1) ?: lMatcher.group(3) ?: ""
            val titleHtml = lMatcher.group(2) ?: lMatcher.group(4) ?: ""
            val title = stripHtmlTags(titleHtml)
            val snippet = if (sMatcher.find()) {
                stripHtmlTags(sMatcher.group(1) ?: sMatcher.group(2) ?: sMatcher.group(3) ?: "")
            } else {
                ""
            }
            val cleanUrl = extractActualUrl(rawUrl)

            if (title.isNotBlank() && cleanUrl.startsWith("http") && !cleanUrl.contains("duckduckgo.com/y.js") && results.none { it.url == cleanUrl }) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = cleanUrl,
                        snippet = snippet.ifBlank { title },
                        sourceProvider = providerLabel
                    )
                )
            }
        }

        if (results.isEmpty()) {
            val genericLinks = extractInteractiveLinks(html, "https://html.duckduckgo.com")
            for (link in genericLinks) {
                if (results.size >= maxResults) break
                val cleanUrl = extractActualUrl(link.url)
                if (cleanUrl.startsWith("http") && !cleanUrl.contains("duckduckgo.com") && link.label.length >= 10 && results.none { it.url == cleanUrl }) {
                    results.add(
                        WebSearchResult(
                            title = link.label,
                            url = cleanUrl,
                            snippet = link.label,
                            sourceProvider = providerLabel
                        )
                    )
                }
            }
        }
    }

    private suspend fun searchDuckDuckGoInstantApi(
        query: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=0"

        val conn = openOptimizedConnection(apiUrl)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val jsonStr = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                _uiConfig.value.maxDownloadBytesPerRequest * 2
            )
            conn.disconnect()

            val root = JSONObject(jsonStr)
            val abstractText = root.optString("AbstractText", "").trim()
            val abstractUrl = root.optString("AbstractURL", "").trim()
            val heading = root.optString("Heading", "").trim()

            if (abstractText.isNotBlank() && abstractUrl.isNotBlank()) {
                results.add(
                    WebSearchResult(
                        title = heading.ifBlank { query },
                        url = abstractUrl,
                        snippet = abstractText,
                        sourceProvider = "DDG Instant Answer",
                        relevanceScore = 1.5
                    )
                )
            }

            val resultsArray = root.optJSONArray("Results")
            if (resultsArray != null) {
                for (i in 0 until resultsArray.length()) {
                    if (results.size >= maxResults) break
                    val item = resultsArray.optJSONObject(i) ?: continue
                    val firstUrl = item.optString("FirstURL", "").trim()
                    val text = item.optString("Text", "").trim()
                    if (firstUrl.isNotBlank() && text.isNotBlank() && results.none { it.url == firstUrl }) {
                        val title = text.substringBefore(" - ").takeIf { it.isNotBlank() } ?: query
                        results.add(
                            WebSearchResult(
                                title = title,
                                url = firstUrl,
                                snippet = text,
                                sourceProvider = "DDG Official"
                            )
                        )
                    }
                }
            }

            val relatedArray = root.optJSONArray("RelatedTopics")
            if (relatedArray != null) {
                for (i in 0 until relatedArray.length()) {
                    if (results.size >= maxResults) break
                    val item = relatedArray.optJSONObject(i) ?: continue
                    val firstUrl = item.optString("FirstURL", "").trim()
                    val text = item.optString("Text", "").trim()
                    if (firstUrl.isNotBlank() && text.isNotBlank() && results.none { it.url == firstUrl }) {
                        val title = text.substringBefore(" - ").takeIf { it.isNotBlank() } ?: text.take(60)
                        results.add(
                            WebSearchResult(
                                title = title,
                                url = firstUrl,
                                snippet = text,
                                sourceProvider = "DDG Knowledge"
                            )
                        )
                    }
                }
            }
        } else {
            conn.disconnect()
        }
    }

    private suspend fun fetchWikipediaPageSummary(title: String, lang: String): String? {
        val encodedTitle = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
        val summaryUrl = "https://$lang.wikipedia.org/api/rest_v1/page/summary/$encodedTitle"
        return try {
            val conn = openOptimizedConnection(summaryUrl)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val jsonStr = readStreamWithOptionalThrottle(getDecodedInputStream(conn), 12 * 1024)
                conn.disconnect()
                val json = JSONObject(jsonStr)
                val extract = json.optString("extract", "").trim()
                if (extract.isNotBlank()) extract else null
            } else {
                conn.disconnect()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchWikipediaFulltext(
        query: String,
        lang: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val weatherIntent = isWeatherQuery(query)
        val normQueryTerms = java.text.Normalizer.normalize(query, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in setOf("dnes", "aktualne", "aktualni", "pocasi", "predpoved", "mesto", "meste") }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // 1. Wikipedia OpenSearch pro okamžité přesné shody entit
        if (!weatherIntent) {
            try {
                val openSearchUrl = "https://$lang.wikipedia.org/w/api.php?action=opensearch&search=$encodedQuery&limit=3&namespace=0&format=json"
                val osConn = openOptimizedConnection(openSearchUrl)
                osConn.requestMethod = "GET"
                osConn.setRequestProperty("Accept", "application/json")
                if (osConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val osJsonStr = readStreamWithOptionalThrottle(getDecodedInputStream(osConn), 8 * 1024)
                    osConn.disconnect()
                    val osTokener = JSONTokener(osJsonStr).nextValue()
                    if (osTokener is JSONArray && osTokener.length() >= 2) {
                        val titlesArr = osTokener.optJSONArray(1)
                        if (titlesArr != null && titlesArr.length() > 0) {
                            val topTitle = titlesArr.optString(0, "").trim()
                            if (topTitle.isNotBlank()) {
                                val summary = fetchWikipediaPageSummary(topTitle, lang)
                                if (!summary.isNullOrBlank()) {
                                    val encTop = URLEncoder.encode(topTitle.replace(" ", "_"), "UTF-8")
                                    val topUrl = "https://$lang.wikipedia.org/wiki/$encTop"
                                    results.add(
                                        WebSearchResult(
                                            title = "$topTitle ($lang.wikipedia.org)",
                                            url = topUrl,
                                            snippet = summary,
                                            sourceProvider = "Wikipedia ($lang) Encyklopedie",
                                            relevanceScore = 1.6
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    osConn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Wikipedia opensearch note: ${e.message}")
            }
        }

        // 2. Wikipedia Fulltext Search API
        val apiUrl =
            "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encodedQuery&utf8=1&format=json"

        val conn = openOptimizedConnection(apiUrl)
        conn.requestMethod = "GET"
        conn.setRequestProperty("Accept", "application/json")

        if (conn.responseCode == HttpURLConnection.HTTP_OK) {
            val jsonStr = readStreamWithOptionalThrottle(
                getDecodedInputStream(conn),
                _uiConfig.value.maxDownloadBytesPerRequest * 2
            )
            conn.disconnect()

            val jsonObject = JSONObject(jsonStr)
            val searchArray = jsonObject.optJSONObject("query")?.optJSONArray("search") ?: return

            for (i in 0 until searchArray.length().coerceAtMost(maxResults)) {
                val item = searchArray.optJSONObject(i) ?: continue
                val title = item.optString("title", "").trim()
                if (title.isBlank()) continue

                val normTitle = java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD)
                    .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
                    .lowercase()

                // Pro dotazy na počasí povolíme z Wikipedie pouze článek přímo o daném městě/místě (ne "Srnec obecný" nebo "Rádio Blaník")
                if (weatherIntent && normQueryTerms.isNotEmpty() && normQueryTerms.none { normTitle.contains(it) }) {
                    continue
                }

                val encodedTitle = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
                val articleUrl = "https://$lang.wikipedia.org/wiki/$encodedTitle"
                if (results.any { it.url == articleUrl }) continue

                val richExtract = if (results.size < 2) fetchWikipediaPageSummary(title, lang) else null
                val snippet = if (!richExtract.isNullOrBlank()) richExtract else stripHtmlTags(item.optString("snippet", "").trim())

                results.add(
                    WebSearchResult(
                        title = "$title ($lang.wikipedia.org)",
                        url = articleUrl,
                        snippet = snippet,
                        sourceProvider = if (!richExtract.isNullOrBlank()) "Wikipedia ($lang) Encyklopedie" else "Wikipedia ($lang)",
                        relevanceScore = if (weatherIntent) 0.6 else 1.2
                    )
                )
            }
        } else {
            conn.disconnect()
        }
    }

    private fun parseFlexibleJsonSearch(
        jsonStr: String,
        providerName: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val tokenized = runCatching { JSONTokener(jsonStr).nextValue() }.getOrNull() ?: return
        val items: JSONArray = when (tokenized) {
            is JSONArray -> tokenized
            is JSONObject -> {
                tokenized.optJSONArray("results")
                    ?: tokenized.optJSONArray("hits")
                    ?: tokenized.optJSONArray("items")
                    ?: tokenized.optJSONArray("data")
                    ?: tokenized.optJSONObject("message")?.optJSONArray("items")
                    ?: return
            }
            else -> return
        }

        for (i in 0 until items.length().coerceAtMost(maxResults)) {
            val obj = items.optJSONObject(i) ?: continue

            val title = obj.optString("title", "")
                .ifBlank { obj.optString("display_name", "") }
                .ifBlank { obj.optString("full_name", "") }
                .ifBlank { obj.optString("story_title", "") }
                .trim()

            val url = obj.optString("url", "")
                .ifBlank { obj.optString("html_url", "") }
                .ifBlank { obj.optString("link", "") }
                .ifBlank { obj.optString("doi", "") }
                .ifBlank { obj.optString("id", "") }
                .trim()

            val openAlexAbstract = obj.optJSONObject("abstract_inverted_index")?.let {
                reconstructOpenAlexAbstract(it)
            } ?: ""

            val content = obj.optString("content", "")
                .ifBlank { obj.optString("snippet", "") }
                .ifBlank { obj.optString("description", "") }
                .ifBlank { openAlexAbstract }
                .ifBlank { obj.optString("story_text", "") }
                .ifBlank { obj.optString("body_markdown", "") }
                .trim()

            val yearOrDate = obj.optString("publication_year", "")
                .ifBlank { obj.optString("created_at", "").take(10) }

            if (title.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                val cleanSnippet = stripHtmlTags(content).ifBlank { title }
                val prefix = if (yearOrDate.isNotBlank()) "[$yearOrDate] " else ""
                results.add(
                    WebSearchResult(
                        title = stripHtmlTags(title),
                        url = url,
                        snippet = (prefix + cleanSnippet).take(450),
                        sourceProvider = providerName,
                        publishedDate = yearOrDate
                    )
                )
            }
        }
    }

    private fun reconstructOpenAlexAbstract(invertedIndex: JSONObject): String {
        val wordPositions = mutableListOf<Pair<Int, String>>()
        val keys = invertedIndex.keys()
        while (keys.hasNext()) {
            val word = keys.next()
            val posArr = invertedIndex.optJSONArray(word) ?: continue
            for (i in 0 until posArr.length()) {
                val pos = posArr.optInt(i, -1)
                if (pos in 0..500) {
                    wordPositions.add(pos to word)
                }
            }
        }
        if (wordPositions.isEmpty()) return ""
        return wordPositions.sortedBy { it.first }.joinToString(" ") { it.second }.take(400)
    }

    private fun parseAtomOrRssFeed(
        xml: String,
        providerName: String,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        val entryPattern = Pattern.compile("<(entry|item)>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE)
        val titlePattern = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE)
        val summaryPattern = Pattern.compile("<(summary|description|content:encoded)[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE)
        val idPattern = Pattern.compile("<(id|link)[^>]*>([\\s\\S]*?)</\\1>|<link[^>]+href=['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)

        val eMatcher = entryPattern.matcher(xml)
        var count = 0
        while (eMatcher.find() && count < maxResults) {
            val entryBlock = eMatcher.group(2) ?: continue
            val tMatcher = titlePattern.matcher(entryBlock)
            val sMatcher = summaryPattern.matcher(entryBlock)
            val iMatcher = idPattern.matcher(entryBlock)

            val title = if (tMatcher.find()) stripHtmlTags(tMatcher.group(1) ?: "").replace(Regex("\\s+"), " ") else ""
            val summary = if (sMatcher.find()) stripHtmlTags(sMatcher.group(2) ?: "").replace(Regex("\\s+"), " ") else ""
            val url = if (iMatcher.find()) {
                (iMatcher.group(2) ?: iMatcher.group(3) ?: "").trim()
            } else {
                ""
            }

            if (title.isNotBlank() && url.startsWith("http", ignoreCase = true)) {
                results.add(
                    WebSearchResult(
                        title = title,
                        url = url,
                        snippet = summary.take(350),
                        sourceProvider = providerName
                    )
                )
                count++
            }
        }
    }

    private fun parseGenericOrCustomHtmlSearch(
        html: String,
        baseUrl: String,
        source: SearchSourceEntry,
        results: MutableList<WebSearchResult>,
        maxResults: Int
    ) {
        if (html.contains("result-link") || html.contains("result__a")) {
            parseDuckDuckGoLiteHtml(html, source.name, results, maxResults)
            if (results.isNotEmpty()) return
        }

        if (source.customLinkRegex.isNotBlank()) {
            val customMatcher = runCatching {
                Pattern.compile(source.customLinkRegex, Pattern.CASE_INSENSITIVE).matcher(html)
            }.getOrNull()
            if (customMatcher != null) {
                while (customMatcher.find() && results.size < maxResults) {
                    val u = customMatcher.group(1) ?: ""
                    val t = if (customMatcher.groupCount() >= 2) stripHtmlTags(customMatcher.group(2) ?: "") else u
                    if (u.startsWith("http") && t.isNotBlank()) {
                        results.add(WebSearchResult(title = t, url = u, snippet = t, sourceProvider = source.name))
                    }
                }
                if (results.isNotEmpty()) return
            }
        }

        val links = extractInteractiveLinks(html, baseUrl)
        for (link in links) {
            if (results.size >= maxResults) break
            if (link.label.length >= 12) {
                results.add(
                    WebSearchResult(
                        title = link.label,
                        url = link.url,
                        snippet = "Nalezený odkaz ze zdroje ${source.name}",
                        sourceProvider = source.name
                    )
                )
            }
        }
    }

    // =========================================================================
    // 4. SÍŤOVÁ VRSTVA, DATA SAVER (3-8 kB/s) A DOM DISTILLER (TODO 0 & 0b)
    // =========================================================================

    internal fun openOptimizedConnection(urlString: String): HttpURLConnection {
        val cfg = _uiConfig.value
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection

        conn.connectTimeout = cfg.networkTimeoutMs
        conn.readTimeout = cfg.networkTimeoutMs
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate")
        conn.setRequestProperty("Accept-Language", "cs-CZ,cs;q=0.9,en-US;q=0.8,en;q=0.7")
        conn.setRequestProperty("Upgrade-Insecure-Requests", "1")

        val chosenUa = when {
            cfg.dataSaverEnabled -> USER_AGENT_DATA_SAVER
            cfg.experimentalStealthUserAgentRotation -> USER_AGENTS_STEALTH_2026.random()
            else -> USER_AGENTS_STEALTH_2026.first()
        }
        conn.setRequestProperty("User-Agent", chosenUa)

        if (cfg.disableWebSecurityForDev && conn is HttpsURLConnection) {
            createDevPermissiveSslSocketFactory()?.let { conn.sslSocketFactory = it }
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }

        return conn
    }

    internal fun getDecodedInputStream(conn: HttpURLConnection): InputStream {
        val encoding = conn.contentEncoding?.lowercase() ?: ""
        val baseStream = conn.inputStream
        return when {
            encoding.contains("gzip") -> GZIPInputStream(baseStream)
            encoding.contains("deflate") -> InflaterInputStream(baseStream)
            else -> baseStream
        }
    }

    internal suspend fun readStreamWithOptionalThrottle(stream: InputStream, maxBytes: Int): String {
        val cfg = _uiConfig.value
        val safeMaxChars = maxBytes.coerceIn(2048, 512 * 1024)
        val sb = StringBuilder(safeMaxChars.coerceAtMost(16384))
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        val buffer = CharArray(1024)
        var totalChars = 0

        val bytesPerSecLimit = (cfg.targetBandwidthKbps.coerceIn(3, 50)) * 1024
        val startTime = System.currentTimeMillis()

        reader.use { r ->
            while (totalChars < safeMaxChars) {
                val toRead = minOf(buffer.size, safeMaxChars - totalChars)
                val read = r.read(buffer, 0, toRead)
                if (read == -1) break
                sb.append(buffer, 0, read)
                totalChars += read

                if (cfg.throttleBandwidthEnabled) {
                    val elapsedMs = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)
                    val expectedMs = (totalChars * 1000L) / bytesPerSecLimit
                    if (expectedMs > elapsedMs) {
                        delay((expectedMs - elapsedMs).coerceAtMost(500L))
                    }
                }
            }
        }
        return sb.toString()
    }

    internal fun distillHtmlToReaderText(html: String, useReaderDistiller: Boolean): String {
        var working = html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")

        val articleMatch = Pattern.compile("<(article|main)[^>]*>([\\s\\S]*?)</\\1>", Pattern.CASE_INSENSITIVE)
            .matcher(working)
        if (articleMatch.find()) {
            val extractedMain = articleMatch.group(2)
            if (extractedMain != null && extractedMain.length > 250) {
                working = extractedMain
            }
        }
        working = working.replace(Regex("<(nav|footer|aside|header)[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")

        working = working
            .replace(Regex("<h1[^>]*>([\\s\\S]*?)</h1>", RegexOption.IGNORE_CASE)) {
                "\n\n# " + stripHtmlTags(it.groupValues[1]) + "\n"
            }
            .replace(Regex("<h[2-3][^>]*>([\\s\\S]*?)</h[2-3]>", RegexOption.IGNORE_CASE)) {
                "\n\n## " + stripHtmlTags(it.groupValues[1]) + "\n"
            }
            .replace(Regex("<h[4-6][^>]*>([\\s\\S]*?)</h[4-6]>", RegexOption.IGNORE_CASE)) {
                "\n\n### " + stripHtmlTags(it.groupValues[1]) + "\n"
            }
            .replace(Regex("<li[^>]*>([\\s\\S]*?)</li>", RegexOption.IGNORE_CASE)) {
                "\n• " + stripHtmlTags(it.groupValues[1])
            }
            .replace(Regex("<(p|div|section|tr|br)[^>]*>", RegexOption.IGNORE_CASE), "\n")

        val plain = stripHtmlTags(working)
        return plain
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    internal fun extractInteractiveLinks(html: String, baseUrl: String): List<ExtractedWebLink> {
        val list = mutableListOf<ExtractedWebLink>()
        val seenUrls = HashSet<String>()
        val pattern = Pattern.compile(
            """<a[^>]+href=["']([^"'#]+)["'][^>]*>([\s\S]*?)</a>""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        var index = 1

        val baseUri = runCatching { URL(baseUrl) }.getOrNull()

        while (matcher.find() && index <= 40) {
            val rawHref = (matcher.group(1) ?: "").trim()
            val label = stripHtmlTags(matcher.group(2) ?: "").replace(Regex("\\s+"), " ").trim()

            if (label.length < 3 ||
                rawHref.startsWith("javascript:", ignoreCase = true) ||
                rawHref.startsWith("mailto:", ignoreCase = true) ||
                rawHref.startsWith("tel:", ignoreCase = true)
            ) {
                continue
            }

            val resolvedUrl = try {
                when {
                    rawHref.startsWith("http://") || rawHref.startsWith("https://") -> rawHref
                    baseUri != null -> URL(baseUri, rawHref).toString()
                    else -> continue
                }
            } catch (_: Exception) {
                continue
            }

            if (seenUrls.add(resolvedUrl)) {
                list.add(ExtractedWebLink(index = index++, label = label.take(75), url = resolvedUrl))
            }
        }
        return list
    }

    private fun extractJsonLdMetadata(html: String): String {
        val pattern = Pattern.compile(
            """<script[^>]*type=["']application/ld\+json["'][^>]*>([\s\S]*?)</script>""",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            (matcher.group(1) ?: "").replace(Regex("\\s+"), " ").trim().take(600)
        } else {
            ""
        }
    }

    private fun extractHtmlTitle(html: String): String {
        val matcher = Pattern.compile("<title[^>]*>([\\s\\S]*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
        return if (matcher.find()) stripHtmlTags(matcher.group(1) ?: "").trim() else ""
    }

    internal fun paginateTextByCharBudget(text: String, charsPerChunk: Int): List<String> {
        if (text.isBlank()) return listOf("(Prázdná stránka)")
        if (text.length <= charsPerChunk) return listOf(text)

        val chunks = mutableListOf<String>()
        val paragraphs = text.split("\n")
        val currentBuilder = StringBuilder()

        for (para in paragraphs) {
            if (currentBuilder.length + para.length + 1 <= charsPerChunk) {
                if (currentBuilder.isNotEmpty()) currentBuilder.append("\n")
                currentBuilder.append(para)
            } else {
                if (currentBuilder.isNotEmpty()) {
                    chunks.add(currentBuilder.toString())
                    currentBuilder.clear()
                }
                if (para.length <= charsPerChunk) {
                    currentBuilder.append(para)
                } else {
                    var start = 0
                    while (start < para.length) {
                        val end = (start + charsPerChunk).coerceAtMost(para.length)
                        chunks.add(para.substring(start, end))
                        start = end
                    }
                }
            }
        }
        if (currentBuilder.isNotEmpty()) {
            chunks.add(currentBuilder.toString())
        }
        return chunks
    }

    // =========================================================================
    // 5. HYBRIDNÍ RRF (Reciprocal Rank Fusion) + OKAPI BM25-LITE RERANKING
    // =========================================================================

    private fun fuseAndRankWithRrfAndBm25(
        query: String,
        providerLists: Map<String, List<WebSearchResult>>
    ): List<WebSearchResult> {
        if (providerLists.isEmpty()) return emptyList()

        val rrfConstantK = 60.0
        val rrfScores = HashMap<String, Double>()
        val mergedItems = HashMap<String, WebSearchResult>()

        val orderedEngines = _uiConfig.value.enabledSearchEnginesOrder.map { it.lowercase().trim() }
        fun getOrderWeightForProvider(providerKey: String): Double {
            if (providerKey == "Live_Weather_Telemetry") return 3.2
            if (providerKey == "Live_Browser_DOM") return 2.4
            if (providerKey == "Local_Hybrid_RAG") return 1.75
            val engineId = when {
                providerKey.startsWith("DuckDuckGo") || providerKey.startsWith("DDG") -> "duckduckgo"
                providerKey.startsWith("Wikipedia") -> "wikipedia"
                providerKey.startsWith("Seznam") -> "seznam"
                providerKey.startsWith("Brave") -> "brave"
                providerKey.startsWith("Google") -> "google"
                providerKey.startsWith("SearXNG") -> "searxng"
                providerKey.startsWith("SemanticScholar") -> "science"
                providerKey.startsWith("Imported_") -> "imported_db"
                else -> ""
            }
            val pos = orderedEngines.indexOf(engineId)
            val orderBoost = when (pos) {
                0 -> 1.65
                1 -> 1.45
                2 -> 1.30
                3 -> 1.15
                -1 -> 1.0
                else -> 1.05
            }
            val baseWeight = if (providerKey.startsWith("Imported_")) {
                val id = providerKey.removePrefix("Imported_")
                importedDatabase[id]?.priorityWeight ?: 1.0
            } else {
                1.0
            }
            return baseWeight * orderBoost
        }

        for ((providerKey, list) in providerLists) {
            val weight = getOrderWeightForProvider(providerKey)
            list.forEachIndexed { rankIndex, item ->
                val normUrl = normalizeUrlForDeduplication(item.url)
                val rrfContribution = weight * (1.0 / (rrfConstantK + rankIndex + 1))
                rrfScores[normUrl] = (rrfScores[normUrl] ?: 0.0) + rrfContribution

                val existing = mergedItems[normUrl]
                if (existing == null || item.snippet.length > existing.snippet.length) {
                    val combinedProvider = if (existing != null && !existing.sourceProvider.contains(item.sourceProvider)) {
                        "${existing.sourceProvider} + ${item.sourceProvider}"
                    } else {
                        item.sourceProvider
                    }
                    mergedItems[normUrl] = item.copy(sourceProvider = combinedProvider)
                }
            }
        }

        val candidates = mergedItems.values.toList()
        val queryTerms = query.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 2 }
            .distinct()

        if (queryTerms.isEmpty()) {
            return candidates.map { item ->
                val normUrl = normalizeUrlForDeduplication(item.url)
                item.copy(relevanceScore = (rrfScores[normUrl] ?: 0.0) * 100.0)
            }.sortedByDescending { it.relevanceScore }
        }

        val nDocs = candidates.size.coerceAtLeast(1)
        val avgDocLen = candidates.map { (it.title.length + it.snippet.length).coerceAtLeast(1) }
            .average().coerceAtLeast(50.0)
        val k1 = 1.5
        val b = 0.75

        val queryLower = query.lowercase()
        val isCzechQuery = queryLower.contains("česk") || queryLower.contains("čr") || queryLower.contains("czech") || queryLower.contains("praze") || queryLower.contains("praha")
        val isSlovakQuery = queryLower.contains("slovensk") || queryLower.contains("sr")

        return candidates.map { item ->
            val normUrl = normalizeUrlForDeduplication(item.url)
            val docText = "${item.title} ${item.title} ${item.snippet}".lowercase()
            val docLen = docText.length.toDouble()
            var bm25Score = 0.0

            for (term in queryTerms) {
                val tf = countOccurrences(docText, term).toDouble()
                if (tf > 0) {
                    val df = candidates.count {
                        it.title.contains(term, ignoreCase = true) || it.snippet.contains(term, ignoreCase = true)
                    }
                    val idf = ln(1.0 + (nDocs - df + 0.5) / (df + 0.5))
                    val tfNorm = (tf * (k1 + 1.0)) / (tf + k1 * (1.0 - b + b * (docLen / avgDocLen)))
                    val termWeight = if (term.matches(Regex("""202[0-9]|203[0-9]"""))) 0.25 else 1.0
                    bm25Score += idf * tfNorm * termWeight
                }
            }

            var entityCoherenceBoost = 0.0
            if (isCzechQuery) {
                if (docText.contains("česk") || docText.contains("čr") || docText.contains("czech") || docText.contains("hrad")) {
                    entityCoherenceBoost += 4.0
                }
                if ((docText.contains("maďarsk") || docText.contains("hungary") || docText.contains("slovensk") || docText.contains("polsk")) &&
                    !docText.contains("česk") && !docText.contains("čr") && !docText.contains("czech")) {
                    entityCoherenceBoost -= 6.0
                }
            } else if (isSlovakQuery) {
                if (docText.contains("slovensk") || docText.contains("sr")) {
                    entityCoherenceBoost += 4.0
                }
            }

            val authorityBoost = when {
                item.sourceProvider.contains("Open-Meteo") || item.sourceProvider.contains("Live Bot DOM") -> 3.0
                item.sourceProvider.contains("Wikipedia") && !isWeatherQuery(query) -> 0.4
                else -> 0.0
            }

            val rrfNormalized = (rrfScores[normUrl] ?: 0.0) * 60.0
            val finalScore = (0.40 * rrfNormalized) + (0.50 * bm25Score) + (item.relevanceScore * 0.25) + entityCoherenceBoost + authorityBoost
            item.copy(relevanceScore = finalScore)
        }.sortedByDescending { it.relevanceScore }
    }

    private fun countOccurrences(text: String, sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var idx = 0
        while (text.indexOf(sub, idx).also { idx = it } != -1) {
            count++
            idx += sub.length
        }
        return count
    }

    private fun normalizeUrlForDeduplication(url: String): String {
        return url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    @SuppressLint("CustomX509TrustManager")
    private fun createDevPermissiveSslSocketFactory(): SSLSocketFactory? {
        return try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
                }
            )
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            sc.socketFactory
        } catch (e: Exception) {
            Log.w(TAG, "Dev SSL socket factory creation failed: ${e.message}")
            null
        }
    }

    private fun extractActualUrl(rawUrl: String): String {
        return try {
            if (rawUrl.contains("uddg=")) {
                val param = rawUrl.substringAfter("uddg=").substringBefore("&")
                URLDecoder.decode(param, "UTF-8")
            } else if (rawUrl.startsWith("//")) {
                "https:$rawUrl"
            } else {
                rawUrl
            }
        } catch (_: Exception) {
            rawUrl
        }
    }

    internal fun stripHtmlTags(html: String): String {
        return html
            .replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
    }

    // =========================================================================
    // 6. FORMÁTOVÁNÍ VÝSTUPU PRO MODEL A UI (1:1 ZPĚTNĚ KOMPATIBILNÍ)
    // =========================================================================

    fun formatSearchResultsForModel(
        query: String,
        results: List<WebSearchResult>,
        maxTokenLimit: Int = 600,
        currentDateContext: String = ""
    ): String {
        if (results.isEmpty()) {
            return "Žádné ověřené výsledky vyhledávání pro dotaz: \"$query\"."
        }

        val cfg = _uiConfig.value
        val effectiveTokens = if (maxTokenLimit > 0) maxTokenLimit else cfg.maxTokensPerSource
        val totalCharBudget = (effectiveTokens * cfg.charsPerTokenRatio).toInt()
        val numResults = results.size.coerceAtLeast(1)
        val perSnippetCharBudget = (totalCharBudget / numResults).coerceIn(120, 1800)

        return buildString {
            append("### Ověřené výsledky vyhledávání na webu pro dotaz: \"$query\"\n")
            if (currentDateContext.isNotBlank()) {
                append("Časové ukotvení dotazu: $currentDateContext | Režim DataSaver: ${cfg.dataSaverEnabled}\n")
            }
            append("\n")

            results.forEachIndexed { i, res ->
                val safeTitle = res.title.replace(Regex("""[<>]"""), "")
                val safeUrl = res.url.replace(Regex("""[<>]"""), "")
                val trimmedSnippet = res.snippet.replace(Regex("""[<>]"""), "").trim()
                val excerpt = if (trimmedSnippet.length > perSnippetCharBudget) {
                    trimmedSnippet.take(perSnippetCharBudget) + "…"
                } else {
                    trimmedSnippet
                }

                append("Document [${i + 1}] (Zdroj: ${res.sourceProvider}, Skóre: ${"%.2f".format(res.relevanceScore)}):\n")
                append("Title: $safeTitle\n")
                append("URL: $safeUrl\n")
                if (excerpt.isNotBlank()) {
                    append("Excerpt: $excerpt\n")
                }
                append("\n")
            }
        }.trimEnd()
    }

    fun formatSearchResultsForUi(query: String, results: List<WebSearchResult>): String {
        if (results.isEmpty()) {
            return "\n\n⚠️ *[Prohledávání webu pro dotaz \"$query\" nenalezlo žádné živé výsledky]*\n\n"
        }
        return buildString {
            append("\n\n🔍 **Prohledán web:** `\"$query\"` *(nalezeno ${results.size} ověřených zdrojů)*:\n")
            results.forEachIndexed { i, res ->
                val providerTag = if (res.sourceProvider.isNotBlank()) " `[${res.sourceProvider}]`" else ""
                val snippetText = if (res.snippet.isNotBlank()) " — *${res.snippet.take(160)}...*" else ""
                append("${i + 1}. [${res.title}](${res.url})$providerTag$snippetText\n")
            }
            append("\n")
        }
    }
}
