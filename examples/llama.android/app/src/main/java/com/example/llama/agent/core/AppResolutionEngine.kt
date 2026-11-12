package com.example.llama.agent.core

import android.util.Log
import com.example.llama.agent.utils.AgentStringUtils
import java.util.concurrent.ConcurrentHashMap

/**
 * AppResolutionEngine — Sémantická vrstva pro rozlišení aplikací.
 *
 * Zodpovědnost:
 * - Přečte živý snapshot z InstalledAppsRegistry po loadSnapshot()
 * - Normalizuje všechny labely a aliasy přes AgentStringUtils
 * - Vybuduje invertovaný index: normalizedTerm → packageName (O(1) lookup)
 * - Zpřístupní resolvePackageName() pro DirectCommandMatcher a ActionExecutor
 *
 * Architektura (tok dat):
 *   InstalledAppsRegistry.loadSnapshot()
 *         ↓
 *   AppResolutionEngine.buildIndex()
 *         ↓
 *   resolvePackageName("instac") → "com.instagram.android"
 *
 * Thread safety:
 * - ConcurrentHashMap pro thread-safe čtení/zápis
 * - @Volatile isReady pro viditelnost mezi vlákny
 * - buildIndex() je idempotentní — opakované volání přestaví index
 *
 * Volání:
 *   1. InstalledAppsRegistry.loadSnapshot(context)  // scan PackageManageru
 *   2. AppResolutionEngine.buildIndex()              // vybuduj index
 *   3. AppResolutionEngine.resolvePackageName(term)  // O(1) lookup
 */
object AppResolutionEngine {

    private const val TAG = "AppResolutionEngine"

    // Invertovaný index: normalizedTerm → packageName
    // Příklad: "instac" → "com.instagram.android"
    //          "fotak"  → "com.sec.android.app.camera"
    private val index = ConcurrentHashMap<String, String>()

    @Volatile
    var isReady: Boolean = false
        private set

    // ==================== PUBLIC API ====================

    /**
     * Vybuduje invertovaný index z aktuálního snapshotu InstalledAppsRegistry.
     *
     * MUSÍ být volán po InstalledAppsRegistry.loadSnapshot() nebo loadFromFile().
     * Idempotentní — opakované volání přestaví index (pro případ re-scanu).
     *
     * Výkon: O(n * m) kde n = počet app, m = průměrný počet aliasů.
     * Typicky: ~500 app × ~10 aliasů = ~5000 zápisů, < 5ms.
     */
    fun buildIndex() {
        val allApps = InstalledAppsRegistry.getAll()

        if (allApps.isEmpty()) {
            Log.w(TAG, "buildIndex() called but InstalledAppsRegistry is empty — call loadSnapshot() first")
            isReady = false
            return
        }

        // Vyčisti starý index (idempotence)
        index.clear()

        var termCount = 0

        for (entry in allApps) {
            val pkg = entry.packageName

            // Normalizuj a indexuj label (např. "WhatsApp Messenger" → "whatsapp messenger")
            val normalizedLabel = AgentStringUtils.normalizeForLookup(entry.label)
            if (normalizedLabel.isNotEmpty()) {
                index[normalizedLabel] = pkg
                termCount++
            }

            // Normalizuj a indexuj packageName (např. "com.whatsapp" → "com.whatsapp")
            val normalizedPkg = AgentStringUtils.normalizeForLookup(pkg)
            if (normalizedPkg.isNotEmpty()) {
                index[normalizedPkg] = pkg
                termCount++
            }

            // Normalizuj a indexuj všechny aliasy (slang výrazy)
            for (alias in entry.aliases) {
                val normalizedAlias = AgentStringUtils.normalizeForLookup(alias)
                if (normalizedAlias.isNotEmpty()) {
                    // Pokud alias již existuje pro jinou app, zachovej první (vyšší priorita)
                    index.putIfAbsent(normalizedAlias, pkg)
                    termCount++
                }
            }
        }

        isReady = true
        Log.i(TAG, "Index built: $termCount terms from ${allApps.size} apps")
    }

    /**
     * Přeloží uživatelský výraz (slang/název/packageName) na packageName.
     *
     * Priorita:
     * 1. Přesná shoda normalizovaného výrazu v invertovaném indexu
     * 2. null — pokud výraz není znám
     *
     * Žádný fallback — pokud není v indexu, vrátí null.
     * Volající (DirectCommandMatcher / ActionExecutor) rozhodne o dalším postupu.
     *
     * @param rawTerm Uživatelský vstup (např. "Instáč", "YTB", "com.instagram.android")
     * @return Package name (String) nebo null pokud není nalezeno
     */
    fun resolvePackageName(rawTerm: String): String? {
        if (!isReady) {
            Log.w(TAG, "resolvePackageName() called before buildIndex() — returning null")
            return null
        }

        if (rawTerm.isBlank()) return null

        val key = AgentStringUtils.normalizeForLookup(rawTerm)
        if (key.isEmpty()) return null

        val result = index[key]

        if (result != null) {
            Log.d(TAG, "Resolved: '$rawTerm' → '$result'")
        } else {
            Log.d(TAG, "Not found: '$rawTerm' (normalized: '$key')")
        }

        return result
    }

    /**
     * Vrátí AppEntry pro daný výraz (pro přístup k dalším metadatům).
     *
     * @param rawTerm Uživatelský vstup
     * @return AppEntry nebo null
     */
    fun resolveEntry(rawTerm: String): InstalledAppsRegistry.AppEntry? {
        val pkg = resolvePackageName(rawTerm) ?: return null
        return InstalledAppsRegistry.getAll().find { it.packageName == pkg }
    }

    /**
     * Vrátí počet indexovaných výrazů (pro diagnostiku/logging).
     */
    val indexSize: Int get() = index.size

    /**
     * Vyčistí index (např. při odhlášení nebo resetu agenta).
     */
    fun clear() {
        index.clear()
        isReady = false
        Log.i(TAG, "Index cleared")
    }
}
