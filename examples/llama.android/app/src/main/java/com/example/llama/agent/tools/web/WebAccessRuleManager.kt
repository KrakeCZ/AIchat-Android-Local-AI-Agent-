package com.example.llama.agent.tools.web

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.util.UUID

/**
 * Typ přístupu k URL:
 * - READ: Prohlížení a čtení obsahu (read_url_content, browser_open, web_search náhledy)
 * - EXECUTE: Provádění akcí v prohlížeči (browser_click, odesílání formulářů, spouštění JS)
 */
enum class UrlAccessType {
    READ,
    EXECUTE
}

/**
 * Politika přístupu:
 * - ALLOW: Adresa je povolena
 * - BLOCK: Adresa je explicitně blokována
 */
enum class UrlAccessPolicy {
    ALLOW,
    BLOCK
}

/**
 * Datová entita jednoho pravidla pro URL / doménu (podle agentního rozhraní).
 */
data class WebUrlRule(
    val id: String = UUID.randomUUID().toString(),
    val type: UrlAccessType,
    var pattern: String,
    var policy: UrlAccessPolicy = UrlAccessPolicy.ALLOW
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("pattern", pattern)
        put("policy", policy.name)
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): WebUrlRule? {
            return try {
                WebUrlRule(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    type = UrlAccessType.valueOf(obj.optString("type", UrlAccessType.READ.name)),
                    pattern = obj.optString("pattern", "").trim(),
                    policy = UrlAccessPolicy.valueOf(obj.optString("policy", UrlAccessPolicy.ALLOW.name))
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Trvalý manažer přístupových pravidel k webovým adresám (SOTA 2026).
 *
 * Plně perzistentní v SharedPreferences – přežije restart aplikace i systému.
 * Umožňuje rychlou editaci, hromadný import z TXT souboru i kontrolu oprávnění před provedením síťového požadavku.
 */
object WebAccessRuleManager {
    private const val TAG = "WebAccessRuleManager"
    private const val PREFS_NAME = "web_access_rules_prefs"
    private const val KEY_RULES_JSON = "rules_json_v1"

    private val inMemoryRules = mutableListOf<WebUrlRule>()
    private var prefs: SharedPreferences? = null
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadFromPrefs()
        initialized = true
    }

    @Synchronized
    fun getRules(type: UrlAccessType): List<WebUrlRule> {
        return inMemoryRules.filter { it.type == type }
    }

    @Synchronized
    fun getAllRules(): List<WebUrlRule> {
        return ArrayList(inMemoryRules)
    }

    @Synchronized
    fun addRule(type: UrlAccessType, pattern: String, policy: UrlAccessPolicy = UrlAccessPolicy.ALLOW): WebUrlRule {
        val cleanPattern = cleanDomainOrUrl(pattern)
        val existing = inMemoryRules.find { it.type == type && it.pattern.equals(cleanPattern, ignoreCase = true) }
        if (existing != null) {
            existing.policy = policy
            saveToPrefs()
            return existing
        }
        val rule = WebUrlRule(type = type, pattern = cleanPattern, policy = policy)
        inMemoryRules.add(rule)
        saveToPrefs()
        return rule
    }

    @Synchronized
    fun updateRule(id: String, pattern: String, policy: UrlAccessPolicy) {
        val rule = inMemoryRules.find { it.id == id } ?: return
        rule.pattern = cleanDomainOrUrl(pattern)
        rule.policy = policy
        saveToPrefs()
    }

    @Synchronized
    fun deleteRule(id: String) {
        inMemoryRules.removeAll { it.id == id }
        saveToPrefs()
    }

    @Synchronized
    fun clearRules(type: UrlAccessType? = null) {
        if (type == null) {
            inMemoryRules.clear()
        } else {
            inMemoryRules.removeAll { it.type == type }
        }
        saveToPrefs()
    }

    /**
     * Hromadný import ze surového textu (řádek po řádku nebo oddělené čárkou).
     */
    @Synchronized
    fun importFromRawText(type: UrlAccessType, text: String, defaultPolicy: UrlAccessPolicy = UrlAccessPolicy.ALLOW): Int {
        var count = 0
        val lines = text.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue

            val tokens = trimmed.split(Regex("[,;\\s]+")).map { it.trim() }.filter { it.isNotBlank() }
            for (token in tokens) {
                var policyToUse = defaultPolicy
                var cleanToken = token

                if (cleanToken.startsWith("allow:", ignoreCase = true)) {
                    policyToUse = UrlAccessPolicy.ALLOW
                    cleanToken = cleanToken.substring(6)
                } else if (cleanToken.startsWith("block:", ignoreCase = true)) {
                    policyToUse = UrlAccessPolicy.BLOCK
                    cleanToken = cleanToken.substring(6)
                }

                cleanToken = cleanDomainOrUrl(cleanToken)
                if (cleanToken.isNotBlank()) {
                    addRule(type, cleanToken, policyToUse)
                    count++
                }
            }
        }
        return count
    }

    /**
     * Exportuje pravidla do čistého formátu pro uložení do souboru.
     */
    @Synchronized
    fun exportToText(type: UrlAccessType): String {
        return getRules(type).joinToString("\n") { rule ->
            "${rule.policy.name.lowercase()}:${rule.pattern}"
        }
    }

    /**
     * Kontrola oprávnění: Zda je přístup k dané URL adrese povolen.
     * Pravidla:
     * 1. Pokud existuje explicitní BLOCK pro danou doménu/prefix, vrací false.
     * 2. Pokud existuje explicitní ALLOW, vrací true.
     * 3. Pokud pro daný typ nejsou definována žádná pravidla (prázdný seznam), výchozí chování je ALLOW (otevřený režim).
     * 4. Pokud existují pravidla ALLOW a tato doména v nich není, vrací false (whitelist režim).
     */
    @Synchronized
    fun isUrlAllowed(type: UrlAccessType, targetUrl: String): Boolean {
        val cleanUrl = targetUrl.trim().lowercase()
        if (cleanUrl.isBlank()) return false

        val host = extractHost(cleanUrl)
        val rules = inMemoryRules.filter { it.type == type }
        if (rules.isEmpty()) {
            return true // Žádná pravidla -> povoleno
        }

        // 1. Explicitní blokace má nejvyšší prioritu
        val blockRule = rules.find { it.policy == UrlAccessPolicy.BLOCK && matchesPattern(cleanUrl, host, it.pattern) }
        if (blockRule != null) {
            Log.w(TAG, "⛔ URL '$targetUrl' blokována pravidlem BLOCK [${blockRule.pattern}] ($type)")
            return false
        }

        // 2. Kontrola, zda existují ALLOW pravidla (Whitelist režim)
        // Výchozí systémová sada 8 domén (z první instalace) nesmí blokovat běžné čtení veřejných webů (READ),
        // pokud si uživatel nepřidal vlastní striktní whitelist nebo explicitní BLOCK pravidlo.
        val allowRules = rules.filter { it.policy == UrlAccessPolicy.ALLOW }
        if (allowRules.isEmpty()) {
            // Byly tam jen BLOCK pravidla a žádné se netrefilo -> povoleno
            return true
        }

        if (type == UrlAccessType.READ && isDefaultSeedRulesOnly(allowRules)) {
            return true
        }

        // 3. Shoda s některým z ALLOW pravidel
        val matchedAllow = allowRules.any { matchesPattern(cleanUrl, host, it.pattern) }
        if (!matchedAllow) {
            Log.w(TAG, "⛔ URL '$targetUrl' nepovolena whitelist pravidly ($type)")
        }
        return matchedAllow
    }

    private fun isDefaultSeedRulesOnly(allowRules: List<WebUrlRule>): Boolean {
        val defaultSet = setOf(
            "127.0.0.1", "wikipedia.org", "duckduckgo.com", "github.com",
            "arxiv.org", "openalex.org", "root.cz", "lupa.cz"
        )
        val currentPatterns = allowRules.map { it.pattern.lowercase().trim() }.toSet()
        return currentPatterns.isNotEmpty() && defaultSet.containsAll(currentPatterns)
    }

    private fun matchesPattern(urlLower: String, hostLower: String, pattern: String): Boolean {
        val pat = pattern.trim().lowercase()
        if (pat.isBlank()) return false
        if (pat == "*" || pat == "all") return true

        // IP adresa nebo přesná doména
        if (hostLower == pat || hostLower.endsWith(".$pat")) return true

        // URL prefix nebo zástupný znak
        if (urlLower.contains(pat)) return true

        return false
    }

    private fun cleanDomainOrUrl(raw: String): String {
        return raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    private fun extractHost(url: String): String {
        return try {
            val u = if (!url.startsWith("http://") && !url.startsWith("https://")) {
                "https://$url"
            } else {
                url
            }
            URL(u).host.removePrefix("www.").lowercase()
        } catch (_: Exception) {
            url.substringBefore("/").removePrefix("www.").lowercase()
        }
    }

    private fun loadFromPrefs() {
        val jsonStr = prefs?.getString(KEY_RULES_JSON, null)
        inMemoryRules.clear()

        if (!jsonStr.isNullOrBlank()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rule = WebUrlRule.fromJsonObject(obj)
                    if (rule != null) inMemoryRules.add(rule)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Chyba načítání pravidel: ${e.message}")
            }
        }

        // Výchozí přednastavené zdroje (pokud je databáze nově prázdná)
        if (inMemoryRules.isEmpty()) {
            val defaultRead = listOf(
                "127.0.0.1", "wikipedia.org", "duckduckgo.com", "github.com",
                "arxiv.org", "openalex.org", "root.cz", "lupa.cz"
            )
            for (d in defaultRead) {
                inMemoryRules.add(WebUrlRule(type = UrlAccessType.READ, pattern = d, policy = UrlAccessPolicy.ALLOW))
            }
            saveToPrefs()
        }
    }

    private fun saveToPrefs() {
        try {
            val arr = JSONArray()
            for (rule in inMemoryRules) {
                arr.put(rule.toJsonObject())
            }
            prefs?.edit()?.putString(KEY_RULES_JSON, arr.toString())?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Chyba ukládání pravidel: ${e.message}")
        }
    }
}
