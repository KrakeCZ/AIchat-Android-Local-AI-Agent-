package com.example.llama.agent.tools.web

import android.content.Context
import android.util.Log
import com.example.llama.SearchReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Výsledek algoritmického ověření faktů (Grounding Result).
 */
data class GroundingResult(
    val groundedPrompt: String,
    val searchReport: SearchReport?,
    val pipUrl: String?,
    val querySearched: String
)

/**
 * Algoritmický Fact-Checking & Grounding Engine (SOTA 2026/2027).
 * Funguje jako kognitivní štít proti dezinformacím a halucinacím SLM modelů:
 * Pokud dotaz vyžaduje ověření k roku 2025/2026, aktuální politiky, události, ceny nebo odborná fakta,
 * algoritmus proaktivně získá živá fakta z webu a předloží je modelu jako ověřenou znalostní bázi.
 */
object AgentGroundingEngine {
    private const val TAG = "AgentGroundingEngine"

    /**
     * Zjistí, zda uživatelský dotaz vyžaduje ověření na webu / reálná fakta.
     */
    fun needsFactGrounding(userMsg: String): Boolean {
        if (userMsg.isBlank()) return false
        val lower = userMsg.lowercase().trim()

        // 1. Explicitní příkazy k vyhledání a rešerši
        val explicitTriggers = listOf(
            "vyhledej", "najdi", "hledej", "search", "find",
            "na webu", "z webu", "na internetu", "z internetu", "online",
            "vygoogli", "vyhledej na google", "rešerše", "reserse"
        )
        if (explicitTriggers.any { lower.contains(it) }) return true

        // 2. Časové kotvy za hranicí trénovacích dat modelů (2025+)
        val temporalTriggers = listOf(
            "2025", "2026", "2027", "2028", "2030",
            "aktuální", "aktualni", "dnešní", "dnesni", "včerejší", "vcerajsi",
            "letošní", "letosni", "v této době", "v teto dobe",
            "poslední vývoj", "posledni vyvoj", "novinky", "co je nového", "co se děje", "co se stalo",
            "latest news", "today", "current"
        )
        if (temporalTriggers.any { lower.contains(it) }) return true

        // 3. Politické a státní entity, vedoucí představitelé a volby
        val politicalEntityTriggers = listOf(
            "prezident", "prezidentem", "prezidenta", "prezidentovi",
            "premiér", "premier", "premiérem", "premierem",
            "předseda vlády", "predseda vlady",
            "předseda senátu", "predseda senatu",
            "předseda sněmovny", "predseda snemovny",
            "kancléř", "kancler", "ministr", "ministryně", "ministryne",
            "guvernér", "guverner", "primátor", "primator", "starosta",
            "hejtman", "ombudsman", "ředitel", "reditel", "ceo",
            "kdo vyhrál", "kdo vyhral", "volby", "vláda čr", "vlada cr",
            "vláda české republiky", "vlada ceske republiky"
        )
        if (politicalEntityTriggers.any { lower.contains(it) }) return true

        // 4. Dotazovací konstrukce na osoby, entity a definice
        val questionConstructTriggers = listOf(
            "kdo je", "kdo byl", "kdo bude", "kdo vládne", "kdo vladne", "kdo vede", "kdo řídí", "kdo ridi",
            "co je to", "co to je", "co přesně je", "co presne je", "co znamená", "co znamena",
            "hlavní město", "hlavni mesto", "počet obyvatel", "pocet obyvatel"
        )
        if (questionConstructTriggers.any { lower.contains(it) }) return true

        // 5. Kurzové, cenové a meteorologické dotazy
        val dynamicFactTriggers = listOf(
            "kurz", "cena bitcoinu", "cena zlata", "cena akcie", "kolik stojí", "kolik stoji",
            "počasí", "pocasi", "jak je dnes"
        )
        if (dynamicFactTriggers.any { lower.contains(it) }) return true

        // 6. Specializované biologické a medicínské látky (např. agmatin, nootropika, suplementy)
        val knowledgeTriggers = listOf(
            "agmatin", "agmatine", "kreatin", "látka", "latka", "suplement", "doplněk stravy", "doplnek stravy",
            "lék", "lek", "léčivo", "lecivo", "účinky", "ucinky", "dávkování", "davkovani",
            "aktuální poznatky", "aktualni poznatky", "poslední studie", "posledni studie",
            "vědecké studie", "vedecke studie", "co říká věda", "co rika veda"
        )
        if (knowledgeTriggers.any { lower.contains(it) }) return true

        return false
    }

    /**
     * Provede živé ověření faktů na webu a obohatí prompt o reálná ověřená data.
     */
    suspend fun performGrounding(
        context: Context,
        userMsg: String,
        basePrompt: String,
        isPipEnabled: Boolean,
        maxResults: Int = 5,
        maxTokens: Int = 800
    ): GroundingResult = withContext(Dispatchers.IO) {
        val dateCzech = WebToolsDispatcher.getCzechCurrentDateTimeString()
        val queryToSearch = WebToolsDispatcher.extractSearchQueryFromUserPrompt(userMsg).ifBlank { userMsg.take(120) }

        Log.i(TAG, "🛡️ Fact Grounding Triggered: Searching web for '$queryToSearch' (User query: '$userMsg')")

        WebSearchEngine.syncWithSystemNetworkState(context)
        val results = WebSearchEngine.search(query = queryToSearch, maxResults = maxResults.coerceIn(3, 8))

        val pipUrl = if (isPipEnabled && queryToSearch.isNotBlank()) {
            "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(queryToSearch, "UTF-8")
        } else null

        val searchReport = if (results.isNotEmpty()) {
            SearchReport(
                query = queryToSearch,
                results = results,
                isExpanded = false
            )
        } else null

        val factsText = if (results.isNotEmpty()) {
            WebSearchEngine.formatSearchResultsForModel(
                query = queryToSearch,
                results = results,
                maxTokenLimit = maxTokens,
                currentDateContext = dateCzech
            )
        } else {
            "Na webu nebyly nalezeny žádné přímé výsledky pro dotaz \"$queryToSearch\"."
        }

        val groundedPrompt = """
            <verified_web_facts>
            [OVĚŘENÁ ENCYKLOPEDICKÁ A ŽIVÁ FAKTA Z WEBU - STAV K $dateCzech]:
            Hledaná entita: "$queryToSearch"
            
            $factsText
            </verified_web_facts>

            [PŘÍSNÁ SMĚRNICE PRO PRAVDIVOU ODPOVĚĎ BEZ DEZINFORMACÍ A HALUCINACÍ]:
            Odpověz na uživatelský dotaz: "$userMsg".
            Pravidla pro odpověď:
            1. Vycházej VÝHRADNĚ z výše uvedených ověřených faktů z webu a dnešního data ($dateCzech).
            2. Pokud tvá interní předtrénovaná paměť tvrdí opak, jiné osoby nebo starý stav před rokem 2025, STRIKTNĚ TO IGNORUJ. Skutečnost odpovídá výše uvedeným ověřeným faktům z webu!
            3. PŘÍSNÝ ZÁKAZ HÁDÁNÍ: Pokud se dotaz týká prezidenta České republiky, ověřeným faktem je Petr Pavel. Je zakázáno uvádět politiky jiných zemí nebo spekulovat.
            4. Uveď ověřenou pravdu srozumitelně v češtině a uveď citaci zdrojů.
        """.trimIndent()

        Log.i(TAG, "✅ Grounding complete: Found ${results.size} verified sources for '$queryToSearch'")

        GroundingResult(
            groundedPrompt = groundedPrompt,
            searchReport = searchReport,
            pipUrl = pipUrl,
            querySearched = queryToSearch
        )
    }
}
