package com.example.llama.agent.core

import android.content.Context
import android.graphics.Rect
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ScreenContextMemory — Perzistentní KV Cache Paměť Agenta
 *
 * Inspirace: KV Cache Prefill/Decode diagram + Voice Access "Show numbers"
 *
 * Architektura:
 * - RAM: LRU cache per package (zero IO at runtime)
 * - DISK: Blokový JSON zápis (jen dirty packages, max 1× za 60s)
 * - LEARN: Po úspěšné akci uloží pozici elementu → příště instant recall
 * - DECAY: Po selhání snižuje confidence → přirozený "zapomínání"
 *
 * IO strategie:
 * - loadFromDisk(): 1× při startu service
 * - flushToDisk(): Blokový zápis jen dirty packages (app switch / onPause / 60s)
 * - Runtime: VŽDY z RAM → 0 IO při zpracování příkazu
 *
 * Thread safety: @Synchronized na všech public metodách
 *
 * Export/Import: Uživatel může exportovat/importovat svoji naučenou mapu
 */
object ScreenContextMemory {

    private const val TAG = "ScreenMemory"
    private const val FILENAME = "agent_memory.json"
    private const val JSON_VERSION = 1

    /** Dedikovaný scope pro asynchronní operace (IO, Flush) - bezpečnější náhrada GlobalScope */
    private val memoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ==================== RUNTIME KONFIGURACE (změnitelné z Settings) ====================
    @Volatile var cfgFlushIntervalMs = 60_000L // Periodický flush: 60s
    @Volatile var cfgMaxApps = 30 // Max cached apps
    @Volatile var cfgMaxPatternsPerApp = 50 // Max patterns per app
    @Volatile var cfgMinConfidence = 0.5f // Pod tímto → pattern vyřazen
    @Volatile var cfgRecallMinConfidence = 0.7f // Min confidence pro recall
    @Volatile var cfgPatternMaxAgeMs = 7L * 24 * 3600_000 // 7 dní max stáří
    @Volatile var cfgRamLimitKb = 512 // RAM limit (KB)

    // ==================== DATA CLASSES ====================

    /** Naučená mapa jedné aplikace — pozice elementů + navigační cesty */
    data class AppMemory(
            val packageName: String,
            var label: String,
            val patterns: MutableList<LearnedPattern>,
            val routes: MutableMap<String, List<String>>, // intent → navigation steps
            var lastAccess: Long = System.currentTimeMillis()
    )

    /**
     * Naučený vzor — KDE na obrazovce agent úspěšně kliknul pro daný intent.
     *
     * Ukládáme:
     * - absolutní bounds (pro přesný hit pokud rozlišení nezměněno)
     * - relativní X/Y (0.0-1.0, pro screen-independent match)
     * - confidence (success / total) → decay při selhání
     */
    data class LearnedPattern(
            val intent: String, // "lajkni", "komentuj", "sdílej"
            var boundsRect: Rect, // Absolutní pozice elementu
            var relativeX: Float, // 0.0-1.0 relativní X
            var relativeY: Float, // 0.0-1.0 relativní Y
            val elementText: String?, // Text elementu (pokud existoval)
            val resourceIdSuffix: String?, // Poslední segment resource ID
            var successCount: Int, // Kolikrát úspěšně použito
            var failCount: Int, // Kolikrát selhalo
            var lastUsed: Long // Timestamp posledního použití
    ) {
        /** Výpočet confidence: success / (success + fail), min 0.0 */
        val confidence: Float
            get() {
                val total = successCount + failCount
                return if (total > 0) successCount.toFloat() / total else 0f
            }

        /** Je pattern platný (ne zastaralý, dostatečná confidence)? */
        fun isValid(): Boolean {
            return confidence >= cfgMinConfidence &&
                    (System.currentTimeMillis() - lastUsed) < cfgPatternMaxAgeMs
        }
    }

    // ==================== RAM CACHE (Zero IO) ====================

    /** LRU cache: packageName → AppMemory (dynamický limit) */
    private val cache =
            object : LinkedHashMap<String, AppMemory>(32, 0.75f, true) {
                override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, AppMemory>?
                ): Boolean {
                    return size > cfgMaxApps
                }
            }

    /** Dirty flags: packages které se změnily od posledního flush */
    private val dirtyPackages = mutableSetOf<String>()

    /** Timestamp posledního flush na disk */
    private var lastFlushTime = 0L

    /** Bylo bereits z disku načteno? */
    private var isLoaded = false

    // ==================== PUBLIC API: RECALL ====================

    /**
     * RECALL: Hledá naučenou pozici pro intent na dané appce.
     *
     * Vrací Rect pokud:
     * - confidence > 0.7
     * - pattern není starší 7 dní
     * - Existuje v RAM cache
     *
     * ZERO IO — čistě z RAM.
     *
     * @param pkg Package name aktuální aplikace
     * @param intent Sémantický intent ("lajkni", "komentuj")
     * @return Rect s bounds elementu, nebo null
     */
    @Synchronized
    fun recallPosition(pkg: String, intent: String): Rect? {
        val app = cache[pkg] ?: return null
        val normalized = intent.lowercase().trim()

        val pattern =
                app.patterns
                        .filter { it.intent == normalized }
                        .filter { it.confidence >= cfgRecallMinConfidence }
                        .filter { (System.currentTimeMillis() - it.lastUsed) < cfgPatternMaxAgeMs }
                        .maxByOrNull { it.confidence }

        if (pattern != null) {
            Log.d(
                    TAG,
                    "🧠 RECALL: '$pkg' intent='$normalized' → " +
                            "bounds=${pattern.boundsRect} confidence=${pattern.confidence} " +
                            "uses=${pattern.successCount}"
            )
            app.lastAccess = System.currentTimeMillis()
        }
        return pattern?.boundsRect
    }

    /**
     * RECALL s relativními souřadnicemi — pro jiné rozlišení obrazovky. Přepočítá z relativeX/Y na
     * absolutní bounds.
     */
    @Synchronized
    fun recallRelativePosition(pkg: String, intent: String, screenW: Int, screenH: Int): Rect? {
        val app = cache[pkg] ?: return null
        val normalized = intent.lowercase().trim()

        val pattern =
                app.patterns
                        .filter {
                            it.intent == normalized && it.confidence >= cfgRecallMinConfidence
                        }
                        .maxByOrNull { it.confidence }
                        ?: return null

        // Přepočet z relativních na absolutní souřadnice
        val cx = (pattern.relativeX * screenW).toInt()
        val cy = (pattern.relativeY * screenH).toInt()
        val halfW = pattern.boundsRect.width() / 2
        val halfH = pattern.boundsRect.height() / 2
        return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
    }

    // ==================== PUBLIC API: LEARN ====================

    /**
     * LEARN: Po úspěšné akci uloží pozici elementu.
     *
     * Pokud pattern pro tento intent už existuje:
     * - Inkrementuje successCount
     * - Aktualizuje bounds (exponential moving average pro stabilitu)
     *
     * Pokud neexistuje:
     * - Vytvoří nový pattern
     *
     * Neukládá na disk okamžitě — jen markuje jako dirty.
     */
    @Synchronized
    fun recordSuccess(
            pkg: String,
            label: String,
            intent: String,
            bounds: Rect,
            screenW: Int,
            screenH: Int,
            text: String? = null,
            resId: String? = null
    ) {
        val normalized = intent.lowercase().trim()
        val app = cache.getOrPut(pkg) { AppMemory(pkg, label, mutableListOf(), mutableMapOf()) }
        app.label = label
        app.lastAccess = System.currentTimeMillis()

        val existing = app.patterns.find { it.intent == normalized }
        if (existing != null) {
            existing.successCount++
            existing.lastUsed = System.currentTimeMillis()

            // Exponential moving average (α=0.3) — stabilní bounds
            val alpha = 0.3f
            existing.boundsRect =
                    Rect(
                            lerp(existing.boundsRect.left, bounds.left, alpha),
                            lerp(existing.boundsRect.top, bounds.top, alpha),
                            lerp(existing.boundsRect.right, bounds.right, alpha),
                            lerp(existing.boundsRect.bottom, bounds.bottom, alpha)
                    )
            existing.relativeX =
                    lerp(
                            existing.relativeX,
                            bounds.centerX().toFloat() / screenW.coerceAtLeast(1),
                            alpha
                    )
            existing.relativeY =
                    lerp(
                            existing.relativeY,
                            bounds.centerY().toFloat() / screenH.coerceAtLeast(1),
                            alpha
                    )

            Log.d(
                    TAG,
                    "🧠 LEARN (update): '$pkg' '$normalized' " +
                            "success=${existing.successCount} conf=${existing.confidence}"
            )
        } else {
            // Nový pattern
            val pattern =
                    LearnedPattern(
                            intent = normalized,
                            boundsRect = Rect(bounds),
                            relativeX = bounds.centerX().toFloat() / screenW.coerceAtLeast(1),
                            relativeY = bounds.centerY().toFloat() / screenH.coerceAtLeast(1),
                            elementText = text?.take(50),
                            resourceIdSuffix = resId?.substringAfterLast("/")?.take(40),
                            successCount = 1,
                            failCount = 0,
                            lastUsed = System.currentTimeMillis()
                    )
            app.patterns.add(pattern)

            // Trim pokud přetekl limit
            if (app.patterns.size > cfgMaxPatternsPerApp) {
                app.patterns.sortByDescending { it.confidence }
                while (app.patterns.size > cfgMaxPatternsPerApp) {
                    app.patterns.removeAt(app.patterns.lastIndex)
                }
            }

            Log.d(
                    TAG,
                    "🧠 LEARN (new): '$pkg' '$normalized' " +
                            "bounds=$bounds relX=${pattern.relativeX} relY=${pattern.relativeY}"
            )
        }

        dirtyPackages.add(pkg) // Mark dirty — NO disk IO
    }

    /**
     * FORGET: Snižuje confidence při selhání (element se přesunul/zmizel). Pokud confidence padne
     * pod MIN_CONFIDENCE → odstraněn při příštím cleanup.
     */
    @Synchronized
    fun recordFailure(pkg: String, intent: String) {
        val normalized = intent.lowercase().trim()
        val app = cache[pkg] ?: return
        val pattern = app.patterns.find { it.intent == normalized } ?: return

        pattern.failCount++
        pattern.lastUsed = System.currentTimeMillis()
        Log.d(
                TAG,
                "🧠 DECAY: '$pkg' '$normalized' " +
                        "fail=${pattern.failCount} conf=${pattern.confidence}"
        )

        // Odstraň pattern pokud confidence příliš nízká
        if (!pattern.isValid()) {
            app.patterns.remove(pattern)
            Log.i(TAG, "🧠 PURGE: '$pkg' '$normalized' — confidence too low")
        }

        dirtyPackages.add(pkg)
    }

    // ==================== PUBLIC API: ROUTES ====================

    /**
     * Uloží navigační cestu pro budoucí použití. Příklad: "profil" → ["tap:bottom-bar:5",
     * "wait:300"]
     */
    @Synchronized
    fun recordRoute(pkg: String, label: String, intent: String, steps: List<String>) {
        val app = cache.getOrPut(pkg) { AppMemory(pkg, label, mutableListOf(), mutableMapOf()) }
        app.routes[intent.lowercase().trim()] = steps
        dirtyPackages.add(pkg)
        Log.d(TAG, "🧠 ROUTE: '$pkg' '$intent' → $steps")
    }

    /** Získá uloženou cestu pro intent */
    @Synchronized
    fun recallRoute(pkg: String, intent: String): List<String>? {
        return cache[pkg]?.routes?.get(intent.lowercase().trim())
    }

    // ==================== DISK IO (Blokový) ====================

    /** Načte mapu z disku do RAM. Volá se 1× při onServiceConnected. */
    @Synchronized
    fun loadFromDisk(context: Context) {
        if (isLoaded) return
        loadSettingsFromPrefs(context) // Načti konfiguraci PŘED zpracováním dat
        try {
            val file = File(context.filesDir, FILENAME)
            if (!file.exists()) {
                Log.i(TAG, "📂 No memory file — starting fresh")
                isLoaded = true
                return
            }

            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != JSON_VERSION) {
                Log.w(
                        TAG,
                        "⚠️ Memory version mismatch ($version != $JSON_VERSION) — starting fresh"
                )
                isLoaded = true
                return
            }

            val apps = json.optJSONObject("apps") ?: JSONObject()
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val appJson = apps.getJSONObject(pkg)
                val appMemory = deserializeApp(pkg, appJson)
                if (appMemory != null) {
                    cache[pkg] = appMemory
                }
            }

            isLoaded = true
            Log.i(
                    TAG,
                    "📂 Memory loaded: ${cache.size} apps, " +
                            "${cache.values.sumOf { it.patterns.size }} patterns"
            )
        } catch (e: Exception) {
            Log.e(TAG, "📂 Failed to load memory", e)
            isLoaded = true // Don't retry
        }
    }

    /**
     * Flush dirty packages na disk. Zapisuje jen změněné parts. Volá se při: app switch, onPause,
     * onDestroy, periodicky (60s).
     */
    @Synchronized
    fun flushToDisk(context: Context) {
        if (dirtyPackages.isEmpty()) return
        try {
            val json = JSONObject()
            json.put("version", JSON_VERSION)
            json.put("lastUpdated", System.currentTimeMillis())

            val appsJson = JSONObject()
            for ((pkg, app) in cache) {
                appsJson.put(pkg, serializeApp(app))
            }
            json.put("apps", appsJson)

            // Serializujeme v synchronizovaném bloku, abychom zafixovali stav paměti.
            val jsonString = json.toString(2)
            val flushedCount = dirtyPackages.size
            dirtyPackages.clear()
            lastFlushTime = System.currentTimeMillis()

            // Zápis na disk DELEGUJEME do background vlákna! (Prevence ANR a zabití Accessibility Service)
            memoryScope.launch {
                try {
                    val file = File(context.filesDir, FILENAME)
                    file.writeText(jsonString)
                    Log.i(
                        TAG,
                        "💾 Memory flushed: $flushedCount dirty packages → " +
                                "${appsJson.length()} apps total"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "💾 Failed to flush memory to disk", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "💾 Failed to prepare memory flush", e)
        }
    }

    /**
     * Periodický check — flush pokud dirty a uplynulo FLUSH_INTERVAL_MS. Volá se z
     * onAccessibilityEvent (nízká frekvence check, nikdy ne IO pokud ne-dirty).
     */
    @Synchronized
    fun periodicFlushIfNeeded(context: Context) {
        if (dirtyPackages.isEmpty()) return
        if (System.currentTimeMillis() - lastFlushTime < cfgFlushIntervalMs) return
        flushToDisk(context)
    }

    // ==================== EXPORT / IMPORT ====================

    /** Export naučené mapy do sdíleného úložiště. Vrací File pro sdílení (Intent.ACTION_SEND). */
    @Synchronized
    fun exportUserMap(context: Context): File? {
        return try {
            val exportDir = File(context.getExternalFilesDir(null), "agent_export")
            if (!exportDir.exists()) exportDir.mkdirs()
            val exportFile = File(exportDir, "agent_memory_export.json")

            // Flush aktuální stav
            flushToDisk(context)

            // Kopie
            val sourceFile = File(context.filesDir, FILENAME)
            if (sourceFile.exists()) {
                sourceFile.copyTo(exportFile, overwrite = true)
                Log.i(TAG, "📤 Memory exported: ${exportFile.absolutePath}")
                exportFile
            } else {
                Log.w(TAG, "📤 Nothing to export — no memory file")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "📤 Export failed", e)
            null
        }
    }

    /**
     * Import uživatelské mapy ze souboru. Merguje s existující pamětí (nemaže stávající, jen
     * přidává/aktualizuje).
     */
    @Synchronized
    fun importUserMap(context: Context, file: File): Boolean {
        return try {
            val json = JSONObject(file.readText())
            val version = json.optInt("version", 0)
            if (version != JSON_VERSION) {
                Log.w(TAG, "📥 Import version mismatch ($version)")
                return false
            }

            val apps = json.optJSONObject("apps") ?: return false
            var importCount = 0
            val keys = apps.keys()
            while (keys.hasNext()) {
                val pkg = keys.next()
                val appJson = apps.getJSONObject(pkg)
                val imported = deserializeApp(pkg, appJson)
                if (imported != null) {
                    val existing = cache[pkg]
                    if (existing != null) {
                        // Merge: přidej patterns které neexistují
                        for (pattern in imported.patterns) {
                            if (existing.patterns.none { it.intent == pattern.intent }) {
                                existing.patterns.add(pattern)
                                importCount++
                            }
                        }
                        // Merge routes
                        for ((key, value) in imported.routes) {
                            if (key !in existing.routes) {
                                existing.routes[key] = value
                            }
                        }
                    } else {
                        cache[pkg] = imported
                        importCount += imported.patterns.size
                    }
                    dirtyPackages.add(pkg)
                }
            }

            flushToDisk(context)
            Log.i(TAG, "📥 Memory imported: $importCount new patterns")
            true
        } catch (e: Exception) {
            Log.e(TAG, "📥 Import failed", e)
            false
        }
    }

    // ==================== DEBUG ====================

    /** Debug dump — vypisuje obsah cache */
    @Synchronized
    fun debugDump(): String = buildString {
        appendLine("=== ScreenContextMemory (${cache.size} apps) ===")
        appendLine("Dirty: ${dirtyPackages.size} packages")
        appendLine("Loaded: $isLoaded")
        for ((pkg, app) in cache) {
            appendLine(
                    "  📱 ${app.label} ($pkg): ${app.patterns.size} patterns, ${app.routes.size} routes"
            )
            app.patterns.take(5).forEach { p ->
                appendLine(
                        "    [${p.intent}] bounds=${p.boundsRect} " +
                                "rel=(${String.format("%.2f", p.relativeX)},${String.format("%.2f", p.relativeY)}) " +
                                "conf=${String.format("%.2f", p.confidence)} " +
                                "uses=${p.successCount}/${p.successCount + p.failCount}"
                )
            }
            if (app.patterns.size > 5) {
                appendLine("    ... a ${app.patterns.size - 5} dalších")
            }
        }
    }

    /** Vyčistí celou cache (RAM + disk) */
    @Synchronized
    fun clear(context: Context? = null) {
        cache.clear()
        dirtyPackages.clear()
        if (context != null) {
            try {
                File(context.filesDir, FILENAME).delete()
            } catch (_: Exception) {}
        }
        Log.i(TAG, "🗑 Memory cleared")
    }

    // ==================== SERIALIZATION ====================

    private fun serializeApp(app: AppMemory): JSONObject {
        val json = JSONObject()
        json.put("label", app.label)
        json.put("lastAccess", app.lastAccess)

        val patternsArray = JSONArray()
        for (p in app.patterns) {
            if (!p.isValid()) continue // Skip expired/low-confidence
            val pJson = JSONObject()
            pJson.put("intent", p.intent)
            pJson.put("left", p.boundsRect.left)
            pJson.put("top", p.boundsRect.top)
            pJson.put("right", p.boundsRect.right)
            pJson.put("bottom", p.boundsRect.bottom)
            pJson.put("relX", p.relativeX.toDouble())
            pJson.put("relY", p.relativeY.toDouble())
            pJson.put("text", p.elementText ?: "")
            pJson.put("resId", p.resourceIdSuffix ?: "")
            pJson.put("success", p.successCount)
            pJson.put("fail", p.failCount)
            pJson.put("lastUsed", p.lastUsed)
            patternsArray.put(pJson)
        }
        json.put("patterns", patternsArray)

        val routesJson = JSONObject()
        for ((key, steps) in app.routes) {
            routesJson.put(key, JSONArray(steps))
        }
        json.put("routes", routesJson)

        return json
    }

    private fun deserializeApp(pkg: String, json: JSONObject): AppMemory? {
        return try {
            val label = json.optString("label", pkg)
            val lastAccess = json.optLong("lastAccess", 0L)

            val patterns = mutableListOf<LearnedPattern>()
            val patternsArray = json.optJSONArray("patterns")
            if (patternsArray != null) {
                for (i in 0 until patternsArray.length()) {
                    val pJson = patternsArray.getJSONObject(i)
                    val pattern =
                            LearnedPattern(
                                    intent = pJson.getString("intent"),
                                    boundsRect =
                                            Rect(
                                                    pJson.getInt("left"),
                                                    pJson.getInt("top"),
                                                    pJson.getInt("right"),
                                                    pJson.getInt("bottom")
                                            ),
                                    relativeX = pJson.getDouble("relX").toFloat(),
                                    relativeY = pJson.getDouble("relY").toFloat(),
                                    elementText =
                                            pJson.optString("text").takeIf { it.isNotEmpty() },
                                    resourceIdSuffix =
                                            pJson.optString("resId").takeIf { it.isNotEmpty() },
                                    successCount = pJson.getInt("success"),
                                    failCount = pJson.getInt("fail"),
                                    lastUsed = pJson.getLong("lastUsed")
                            )
                    if (pattern.isValid()) {
                        patterns.add(pattern)
                    }
                }
            }

            val routes = mutableMapOf<String, List<String>>()
            val routesJson = json.optJSONObject("routes")
            if (routesJson != null) {
                val rKeys = routesJson.keys()
                while (rKeys.hasNext()) {
                    val key = rKeys.next()
                    val stepsArray = routesJson.getJSONArray(key)
                    val steps = mutableListOf<String>()
                    for (i in 0 until stepsArray.length()) {
                        steps.add(stepsArray.getString(i))
                    }
                    routes[key] = steps
                }
            }

            AppMemory(pkg, label, patterns, routes, lastAccess)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize app '$pkg'", e)
            null
        }
    }

    // ==================== MATH UTILS ====================

    /** Linear interpolation pro Int */
    private fun lerp(a: Int, b: Int, alpha: Float): Int {
        return (a + (b - a) * alpha).toInt()
    }

    /** Linear interpolation pro Float */
    private fun lerp(a: Float, b: Float, alpha: Float): Float {
        return a + (b - a) * alpha
    }

    // ==================== RUNTIME SETTINGS ====================

    /**
     * Aplikuje nastavení z SettingsManager za běhu. Volá se po uložení nastavení v
     * SettingsActivity.
     */
    @Synchronized
    fun applySettings(
            maxApps: Int,
            maxPatternsPerApp: Int,
            flushIntervalMs: Long,
            patternMaxAgeDays: Int,
            minRecallConfidence: Float,
            ramLimitKb: Int
    ) {
        cfgMaxApps = maxApps.coerceIn(5, 100)
        cfgMaxPatternsPerApp = maxPatternsPerApp.coerceIn(10, 200)
        cfgFlushIntervalMs = flushIntervalMs.coerceIn(15_000L, 300_000L)
        cfgPatternMaxAgeMs = patternMaxAgeDays.toLong().coerceIn(1, 30) * 24 * 3600_000L
        cfgRecallMinConfidence = minRecallConfidence.coerceIn(0.5f, 0.95f)
        cfgRamLimitKb = ramLimitKb.coerceIn(128, 4096)

        // Trim cache pokud nový limit je menší
        while (cache.size > cfgMaxApps) {
            val oldestKey = cache.keys.firstOrNull() ?: break
            cache.remove(oldestKey)
        }

        Log.i(
                TAG,
                "⚙️ Settings applied: maxApps=$cfgMaxApps, maxPatterns=$cfgMaxPatternsPerApp, " +
                        "flush=${cfgFlushIntervalMs}ms, maxAge=${patternMaxAgeDays}d, " +
                        "minConf=$cfgRecallMinConfidence, ram=${cfgRamLimitKb}KB"
        )
    }

    /**
     * Načte nastavení z SharedPreferences při startu service. Volá se automaticky z loadFromDisk().
     */
    private fun loadSettingsFromPrefs(context: Context) {
        try {
            val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            cfgMaxApps = prefs.getInt("agent_memory_max_apps", 30).coerceIn(5, 100)
            cfgMaxPatternsPerApp = prefs.getInt("agent_memory_max_patterns", 50).coerceIn(10, 200)
            cfgFlushIntervalMs =
                    prefs.getLong("agent_memory_flush_interval", 60_000L)
                            .coerceIn(15_000L, 300_000L)
            val maxAgeDays = prefs.getInt("agent_memory_pattern_max_age", 7).coerceIn(1, 30)
            cfgPatternMaxAgeMs = maxAgeDays.toLong() * 24 * 3600_000L
            cfgRecallMinConfidence =
                    prefs.getFloat("agent_memory_min_confidence", 0.7f).coerceIn(0.5f, 0.95f)
            cfgRamLimitKb = prefs.getInt("agent_memory_ram_limit", 512).coerceIn(128, 4096)
            Log.i(
                    TAG,
                    "⚙️ Settings loaded from prefs: maxApps=$cfgMaxApps, maxPatterns=$cfgMaxPatternsPerApp, " +
                            "flush=${cfgFlushIntervalMs}ms, maxAge=${maxAgeDays}d, minConf=$cfgRecallMinConfidence, ram=${cfgRamLimitKb}KB"
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load settings from prefs — using defaults", e)
        }
    }
}
