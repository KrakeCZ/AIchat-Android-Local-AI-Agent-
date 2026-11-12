package com.example.llama.agent.core

import android.graphics.Rect
import android.util.Log
import com.example.llama.agent.models.ScreenState

/**
 * App Layout Cache — zapamatuje si pozice UI elementů per package.
 *
 * Účel: Při SmartClick("lajkni") na TikToku nemusíme parsovat celou obrazovku, ale podíváme se do
 * cache: "TikTok → Like button → bounds [950,1200]" → instant click (<1ms).
 *
 * Architektura:
 * - LRU cache s max 20 appkami
 * - Každá appka má snapshot svých interaktivních elementů (label, bounds, type, capabilities)
 * - Cache se aktualizuje po každém úspěšném screen parse v MacroEngine
 * - Cache hit: SmartClick najde element okamžitě bez re-parse
 * - Cache miss: normální screen parse + cache update
 *
 * Thread safety: synchronized access (MacroEngine běží na jednom coroutine scope)
 */
object AppLayoutCache {

    private const val TAG = "AppLayoutCache"
    var settingsManager: com.example.llama.SettingsManager? = null

    private val maxCachedApps: Int
        get() = settingsManager?.appCacheMaxApps ?: 20

    private val maxElementsPerApp: Int
        get() = settingsManager?.appCacheMaxElements ?: 60

    // Konfigurovatelné TTL (Default 5s, mění se přes Settings)
    var cacheTtlMs: Long = 5000L

    /**
     * Cachovaný UI element — lightweight kopie ScreenState.UIElement (bez AccessibilityNodeInfo
     * reference, ta se recykluje)
     */
    data class CachedElement(
            val label: String, // Viditelný text nebo contentDescription
            val text: String, // Přesný text elementu
            val contentDescription: String, // Accessibility description
            val type: String, // "Button", "EditText", etc.
            val bounds: Rect, // Souřadnice na obrazovce
            val isClickable: Boolean,
            val isEditable: Boolean,
            val isScrollable: Boolean,
            val isCheckable: Boolean,
            val isLongClickable: Boolean,
            val virtualId: Int, // Virtuální ID z posledního parse
            val resourceId: String = "", // viewIdResourceName (pro resource-id matching)
            val hintText: String = "", // Hint text (pro editable field matching)
            // --- Full Observability rozšíření ---
            val isEnabled: Boolean = true, // Disabled state
            val isPassword: Boolean = false, // Security flag
            val tooltipText: String = "", // Tooltip
            val errorText: String = "", // Validace formulářů
            val inputType: String? = null, // "email", "phone", "number", "text"
            val supportedActions: List<String> = emptyList(), // Sémantické akce
            val metadata: Map<String, String> = emptyMap() // Compose/WebView extras
    )

    /** Snapshot jedné appky — seznam elementů + timestamp */
    data class AppSnapshot(
            val packageName: String,
            val elements: List<CachedElement>,
            val timestamp: Long = System.currentTimeMillis()
    ) {
        fun isStale(): Boolean = (System.currentTimeMillis() - timestamp) > cacheTtlMs
    }

    private val cache =
            object : LinkedHashMap<String, AppSnapshot>(20, 0.75f, true) {
                override fun removeEldestEntry(
                        eldest: MutableMap.MutableEntry<String, AppSnapshot>?
                ): Boolean {
                    return size > maxCachedApps
                }
            }

    // ==================== PUBLIC API ====================

    /**
     * Aktualizuje cache pro danou appku po screen parse. Volá se z MacroEngine po každém úspěšném
     * parseScreen().
     *
     * @param packageName Package name aplikace (např. "com.zhiliaoapp.musically")
     * @param screenState Aktuální parsed screen state
     */
    @Synchronized
    fun updateCache(packageName: String, screenState: ScreenState) {
        if (packageName.isBlank() || packageName == "direct_command") return

        val cachedElements =
                screenState
                        .elements
                        .filter { it.isClickable || it.isEditable || it.isScrollable }
                        .take(maxElementsPerApp)
                        .map { el ->
                            CachedElement(
                                    label = el.label,
                                    text = el.text,
                                    contentDescription = el.contentDescription,
                                    type = el.type,
                                    bounds = Rect(el.bounds), // Defensive copy
                                    isClickable = el.isClickable,
                                    isEditable = el.isEditable,
                                    isScrollable = el.isScrollable,
                                    isCheckable = el.isCheckable,
                                    isLongClickable = el.isLongClickable,
                                    virtualId = el.id,
                                    resourceId = el.resourceId,
                                    hintText = el.hintText,
                                    isEnabled = el.isEnabled,
                                    isPassword = el.isPassword,
                                    tooltipText = el.tooltipText,
                                    errorText = el.errorText,
                                    inputType = el.inputType,
                                    supportedActions = el.supportedActions,
                                    metadata = el.metadata
                            )
                        }

        cache[packageName] = AppSnapshot(packageName, cachedElements)
        Log.d(TAG, "📸 Cache updated: $packageName (${cachedElements.size} elements)")
    }

    /**
     * Hledá element v cache dle labels. Vrací CachedElement pokud nalezen (cache HIT), null pokud
     * ne (cache MISS).
     *
     * @param packageName Package name aktuální aplikace
     * @param labels Textové patterny k hledání (text nebo contentDescription)
     * @param clickableOnly Pokud true, vrátí jen clickable elementy
     * @return CachedElement nebo null
     */
    @Synchronized
    fun findElement(
            packageName: String,
            labels: List<String>,
            clickableOnly: Boolean = true
    ): CachedElement? {
        val snapshot = cache[packageName] ?: return null

        // Stale cache → invalidate
        if (snapshot.isStale()) {
            Log.d(TAG, "⏰ Cache stale for $packageName — invalidating")
            cache.remove(packageName)
            return null
        }

        // 1. Přesná shoda (Exact text / contentDescription)
        for (label in labels) {
            val match =
                    snapshot.elements.find { el ->
                        val typeMatch =
                                if (!clickableOnly) true
                                else
                                        (el.isClickable ||
                                                el.isEditable ||
                                                el.isCheckable ||
                                                el.isLongClickable)
                        val textMatch =
                                el.text.equals(label, ignoreCase = true) ||
                                        el.contentDescription.equals(label, ignoreCase = true) ||
                                        el.label.equals(label, ignoreCase = true)
                        typeMatch && textMatch
                    }
            if (match != null) {
                Log.i(
                        TAG,
                        "⚡ Cache HIT (L1 Exact): '$packageName' → '${match.label}' (${match.type})"
                )
                return match
            }
        }

        // 2. Částečná shoda (Contains text / contentDescription)
        for (label in labels) {
            val match =
                    snapshot.elements.find { el ->
                        val typeMatch =
                                if (!clickableOnly) true
                                else
                                        (el.isClickable ||
                                                el.isEditable ||
                                                el.isCheckable ||
                                                el.isLongClickable)
                        val textMatch =
                                el.text.contains(label, ignoreCase = true) ||
                                        el.contentDescription.contains(label, ignoreCase = true) ||
                                        el.label.contains(label, ignoreCase = true)
                        typeMatch && textMatch
                    }
            if (match != null) {
                Log.i(
                        TAG,
                        "⚡ Cache HIT (L2 Contains): '$packageName' → '${match.label}' (${match.type})"
                )
                return match
            }
        }

        // 3. HintText
        for (label in labels) {
            val match =
                    snapshot.elements.find { el ->
                        val typeMatch =
                                if (!clickableOnly) true
                                else
                                        (el.isClickable ||
                                                el.isEditable ||
                                                el.isCheckable ||
                                                el.isLongClickable)
                        val textMatch =
                                el.hintText.isNotBlank() &&
                                        el.hintText.contains(label, ignoreCase = true)
                        typeMatch && textMatch
                    }
            if (match != null) {
                Log.i(
                        TAG,
                        "⚡ Cache HIT (L3 HintT): '$packageName' → '${match.label}' (${match.type})"
                )
                return match
            }
        }

        // 4. ResourceId (Nejnižší priorita)
        for (label in labels) {
            val match =
                    snapshot.elements.find { el ->
                        val typeMatch =
                                if (!clickableOnly) true
                                else
                                        (el.isClickable ||
                                                el.isEditable ||
                                                el.isCheckable ||
                                                el.isLongClickable)
                        val textMatch =
                                el.resourceId.isNotBlank() &&
                                        el.resourceId.contains(label, ignoreCase = true)
                        typeMatch && textMatch
                    }
            if (match != null) {
                Log.i(
                        TAG,
                        "⚡ Cache HIT (L4 ResId): '$packageName' → '${match.label}' (${match.type})"
                )
                return match
            }
        }

        Log.d(TAG, "❌ Cache MISS: '$packageName' → labels=$labels")
        return null
    }

    /**
     * Hledá editable field v cache.
     *
     * @param packageName Package name
     * @param fieldLabels Textové patterny pro hledání textového pole
     * @return CachedElement (editable) nebo null
     */
    @Synchronized
    fun findEditableField(packageName: String, fieldLabels: List<String>): CachedElement? {
        val snapshot = cache[packageName] ?: return null
        if (snapshot.isStale()) {
            cache.remove(packageName)
            return null
        }

        // 1. Hledej dle fieldLabels
        for (label in fieldLabels) {
            val match =
                    snapshot.elements.find { el ->
                        el.isEditable &&
                                (el.text.contains(label, ignoreCase = true) ||
                                        el.contentDescription.contains(label, ignoreCase = true))
                    }
            if (match != null) {
                Log.i(TAG, "⚡ Cache HIT (editable): '$packageName' → '${match.label}'")
                return match
            }
        }

        // 2. Fallback: jakékoliv editable pole
        val anyEditable = snapshot.elements.find { it.isEditable }
        if (anyEditable != null) {
            Log.i(TAG, "⚡ Cache HIT (any editable): '$packageName' → '${anyEditable.label}'")
        }
        return anyEditable
    }

    /** Invaliduje cache pro danou appku (např. po přepnutí obrazovky). */
    @Synchronized
    fun invalidate(packageName: String) {
        cache.remove(packageName)
        Log.d(TAG, "🗑 Cache invalidated: $packageName")
    }

    /** Vyčistí celou cache. */
    @Synchronized
    fun clear() {
        cache.clear()
        Log.i(TAG, "🗑 Cache cleared")
    }

    /** Debug: výpis obsahu cache. */
    @Synchronized
    fun debugDump(): String = buildString {
        appendLine("=== AppLayoutCache (${cache.size} apps) ===")
        cache.forEach { (pkg, snapshot) ->
            val stale = if (snapshot.isStale()) " [STALE]" else ""
            appendLine("  $pkg: ${snapshot.elements.size} elements$stale")
            snapshot.elements.take(5).forEach { el ->
                appendLine("    [${el.type}] '${el.label}' @ ${el.bounds}")
            }
            if (snapshot.elements.size > 5) {
                appendLine("    ... a ${snapshot.elements.size - 5} dalších")
            }
        }
    }
    /**
     * Extrahuje elementy vhodné pro bulk-learning do ScreenContextMemory. Vrací clickable elementy
     * s neprázdným text/contentDescription.
     *
     * @param packageName Package name aplikace
     * @return List CachedElement s identifikovatelným textem, nebo empty list
     */
    @Synchronized
    fun extractLearnableElements(packageName: String): List<CachedElement> {
        val snapshot = cache[packageName] ?: return emptyList()
        if (snapshot.isStale()) return emptyList()

        return snapshot.elements.filter { el ->
            el.isClickable &&
                    el.bounds.width() > 0 &&
                    el.bounds.height() > 0 &&
                    (el.text.isNotBlank() ||
                            el.contentDescription.isNotBlank() ||
                            el.resourceId.isNotBlank())
        }
    }

    /**
     * Vytáhne STRIKTNĚ interaktivní prvky vhodné pro Magnetic Target Snap (např. tlačítka).
     * Běžné texty a ikony ("TextView", "ImageView", nebo prvky bez jasné akce) budou přeskočeny.
     */
    @Synchronized
    fun extractStrictClickableElements(packageName: String): List<CachedElement> {
        val snapshot = cache[packageName] ?: return emptyList()
        if (snapshot.isStale()) return emptyList()

        return snapshot.elements.filter { el ->
            el.bounds.width() > 0 &&
            el.bounds.height() > 0 &&
            el.isClickable &&
            // Vyloučíme pasivní texty a obyčejné obrázky, pokud nemají HTML roli "button"
            !(el.type.contains("TextView", ignoreCase = true) || el.type.contains("ImageView", ignoreCase = true)) || 
            (el.metadata["html_role"] == "button" || el.metadata["tag_name"] == "BUTTON")
        }
    }

    /**
     * Tier 2.2 HitTest — Najde element obsahující pixel (x, y).
     *
     * Použití: IrisMotion gaze point, head-tilt kurzor, nebo hlasová prostorová souřadnice →
     * okamžitý click BEZ LLM (<1ms).
     *
     * Strategie výběru:
     * 1. Filtr: element musí obsahovat bod a být interaktivní (clickable NEBO editable)
     * 2. Selekce: z kandidátů vybere element s nejmenší plochou (bounds.width × bounds.height)
     * ```
     *    → nejpřesnější hit (vnořené elementy = preferujeme leaf nad parent containerem)
     * ```
     * 3. Stale check: při expiraci TTL invaliduje snapshot (identické jako findElement)
     *
     * @param packageName Package name aktuální aplikace (z rootNode.packageName)
     * @param x Souřadnice X bodu v px (absolutní, screen-space)
     * @param y Souřadnice Y bodu v px (absolutní, screen-space)
     * @param editableOnly Pokud true, vrátí pouze editable elementy (pro text input mode)
     * @return CachedElement s nejmenší plochou obsahující (x,y), nebo null
     */
    @Synchronized
    fun findElementAtPoint(
            packageName: String,
            x: Int,
            y: Int,
            editableOnly: Boolean = false
    ): CachedElement? {
        val snapshot = cache[packageName] ?: return null

        // Stale cache → invalidate (konzistentní s ostatními metodami)
        if (snapshot.isStale()) {
            Log.d(TAG, "⏰ Cache stale for $packageName — invalidating (findElementAtPoint)")
            cache.remove(packageName)
            return null
        }

        // Kandidáti: element obsahuje bod (x,y) a je interaktivní
        val candidates =
                snapshot.elements.filter { el ->
                    val boundsContain = el.bounds.contains(x, y)
                    val isInteractive =
                            if (editableOnly) {
                                el.isEditable
                            } else {
                                el.isClickable ||
                                        el.isEditable ||
                                        el.isCheckable ||
                                        el.isLongClickable
                            }
                    boundsContain &&
                            isInteractive &&
                            el.bounds.width() > 0 &&
                            el.bounds.height() > 0
                }

        if (candidates.isEmpty()) {
            Log.d(TAG, "❌ HitTest MISS: '$packageName' @ ($x,$y) — žádný interaktivní element")
            return null
        }

        // Preferujeme nejmenší element (leaf node, nejpřesnější hit)
        val best = candidates.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }

        if (best != null) {
            Log.i(
                    TAG,
                    "⚡ HitTest HIT: '$packageName' @ ($x,$y) → '${best.label}' " +
                            "(${best.type}) bounds=${best.bounds} " +
                            "area=${best.bounds.width() * best.bounds.height()}px²"
            )
        }
        return best
    }
}
