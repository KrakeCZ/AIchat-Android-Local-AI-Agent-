package com.example.llama.agent.models

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Reprezentace aktuálního stavu obrazovky (parsed UI tree) */
data class ScreenState(
        val packageName: String, // Package name aktuální aplikace
        val elements: List<UIElement>, // Interaktivní UI elementy
        val semanticContent: String = "", // Textový kontext (Obsah obrazovky)
        val timestamp: Long // Kdy byl stav zachycen
) {

    companion object {
        /**
         * Prázdný ScreenState pro DirectCommandMatcher Globální akce (HOME, BACK, NOTIFICATIONS)
         * nepotřebují reálný screen
         */
        fun empty(): ScreenState =
                ScreenState(
                        packageName = "direct_command",
                        elements = emptyList(),
                        timestamp = System.currentTimeMillis()
                )
    }

    /**
     * UIElement: Normalizovaná reprezentace uzlu UI pro AI agenta. Poskytuje sémantický,
     * strukturální a bezpečnostní kontext nad rámec AccessibilityNodeInfo.
     *
     * Full Observability Design:
     * - Deterministická identita (xpath, resourceId, syntetické ID)
     * - Explicitní stav (isEnabled, isSelected, stateDescription)
     * - Kontextová hierarchie (parentId, indexInParent, childCount)
     * - Security (isPassword redakce, isSensitive API 34+)
     * - Framework metadata (Compose testTag, WebView HTML role)
     */
    data class UIElement(
            // --- Identifikace a Navigace ---
            val id: Int, // Virtuální ID pro LLM mapping
            val type: String, // "Button", "EditText", etc. (short class name)
            val className: String, // Plný class name (pro strukturální matching)
            val packageName: String, // Balíček aplikace (overlay/multi-window detekce)
            val resourceId: String, // viewIdResourceName (např.
            // "com.google.android.youtube:id/like_button")
            val xpath: String, // Stabilní XPath cesta (pro automatizaci/re-finding)

            // --- Obsah a Sémantika ---
            val text: String, // Viditelný text (redigováno pokud isPassword=true)
            val contentDescription: String, // Accessibility description (klíčové pro ikony)
            val hintText: String, // Hint pro editable pole (API 26+)
            val errorText: String, // Validační chyba z setError() (zpětná vazba)
            val tooltipText: String, // Tooltip text (API 28+)
            val stateDescription: String, // Sémantický stav (API 30+, např. "50%", "načítání")

            // --- Hierarchie a Stromová struktura ---
            val depth: Int, // Hloubka v UI tree
            val indexInParent: Int, // Pořadí v rámci sourozenců (pro XPath)
            val childCount: Int, // Počet potomků (indikátor komplexity kontejneru)
            val parentId: Int?, // ID rodiče pro rekonstrukci stromu

            // --- Geometrie ---
            val bounds: Rect, // Souřadnice na obrazovce (pro dispatchGesture)

            // --- Detekce Stavu (Full Observability) ---
            val isEnabled: Boolean, // Je aktivní? (false = šedý/disabled — LLM: neklikat!)
            val isSelected: Boolean, // Tab/segment selection
            val isChecked: Boolean, // Checkbox/switch stav
            val isCheckable: Boolean, // Může být zaškrtnutý?
            val isFocused: Boolean, // Má aktuální fokus?
            val isVisibleToUser: Boolean, // Skutečně viditelný? (ghost element filtering)
            val isDismissable: Boolean, // Dialog/toast — lze zavřít?
            val isContentInvalid: Boolean, // Neplatný obsah (validace, API 19+)

            // --- Interakce a Akce ---
            val isClickable: Boolean,
            val isLongClickable: Boolean, // Pro context menu akce
            val isEditable: Boolean, // Indikuje vstupní pole
            val isScrollable: Boolean,
            val inputType: String?, // Typ vstupu: "text", "number", "email", "phone", "url", "date"
            val supportedActions:
                    List<String>, // Sémantické akce: ["CLICK", "SCROLL_FORWARD", "CUSTOM_ARCHIVE"]

            // --- Hodnoty pro Slidery/ProgressBary ---
            val rangeCurrent: Float?, // Aktuální hodnota (pokud RangeInfo existuje)
            val rangeMin: Float?, // Minimum
            val rangeMax: Float?, // Maximum

            // --- Bezpečnost a Soukromí ---
            val isPassword: Boolean, // SECURITY: maskovaný vstup (LLM nesmí logovat!)
            val isSensitive: Boolean, // Citlivá data (API 34+, např. 2FA, banking)

            // --- Framework-Specific Metadata ---
            val metadata:
                    Map<String, String>, // Compose testTag, WebView HTML role/tag, Flutter custom

            // --- Native Reference (Transient) ---
            val nodeInfo: AccessibilityNodeInfo // Reference na actual Android node
    ) {
        /**
         * Human-readable label: text → contentDescription → hintText → inputType fallback SECURITY:
         * Pokud isPassword=true, vrací "[HESLO]" (redakce)
         *
         * Rozšíření pro Full Observability:
         * - Prázdné EditText pole dostanou explicitní label z hintTextu nebo inputType, aby LLM
         * věděl o existenci vstupního pole i bez viditelného textu.
         * - Emoji/symboly v contentDescription jsou zachovány bez stripování.
         */
        val label: String
            get() =
                    when {
                        isPassword -> "[HESLO]"
                        text.isNotBlank() -> text
                        contentDescription.isNotBlank() -> contentDescription
                        // Hint text: explicitně označen jako vstup, aby LLM věděl o poli
                        hintText.isNotBlank() -> "[vstup: $hintText]"
                        // Fallback přes inputType — pole existuje, ale nemá žádný popis
                        inputType != null && isEditable -> "[prázdné $inputType pole]"
                        // Poslední záchrana pro EditTexty bez jakéhokoli popisu
                        isEditable -> "[prázdné textové pole]"
                        else -> ""
                    }

        /**
         * Všechny textové identifikátory pro matching (text, contentDesc, hint, resourceId,
         * tooltip, metadata)
         */
        val allSearchableText: String
            get() {
                val rawStr = buildString {
                    append(text).append(' ')
                    append(contentDescription).append(' ')
                    append(hintText).append(' ')
                    append(resourceId).append(' ')
                    append(tooltipText).append(' ')
                    // Compose testTag a WebView tag z metadat
                    metadata["test_tag"]?.let { append(it).append(' ') }
                    metadata["tag_name"]?.let { append(it).append(' ') }
                }
                return com.example.llama.agent.utils.AgentStringUtils.normalizeForLookup(rawStr)
            }

        /** Capabilities tohoto elementu — rozšířená Full Observability verze */
        val capabilities: List<String>
            get() = buildList {
                if (isClickable) add("Clickable")
                if (isLongClickable) add("LongClickable")
                if (isEditable) add("Editable")
                if (isScrollable) add("Scrollable")
                if (isCheckable && isChecked) add("Checked") else if (isCheckable) add("Checkable")
                if (!isEnabled) add("Disabled")
                if (isSelected) add("Selected")
                if (isFocused) add("Focused")
                if (isPassword) add("Password")
                if (isDismissable) add("Dismissable")
                if (isContentInvalid) add("Invalid")
                if (errorText.isNotBlank()) add("Error")
                if (rangeCurrent != null) add("Range:${rangeCurrent}/${rangeMax ?: "?"}")
            }
    }

    /** Najdi element podle ID */
    fun findElementById(id: Int): UIElement? {
        return elements.find { it.id == id }
    }

    /**
     * VUL-004 FIX: TOCTOU staleness check. Pokud od zachycení obrazovky uplynulo více než maxAgeMs,
     * ScreenState je považován za stale a akce by neměla být provedena.
     *
     * @param maxAgeMs Maximální stáří v ms (default 10s — bezpečná rezerva pro LLM inference)
     * @return true pokud je ScreenState příliš starý
     */
    fun isStale(maxAgeMs: Long = 10_000): Boolean {
        return (System.currentTimeMillis() - timestamp) > maxAgeMs
    }

    /** Convert to markdown pro debugging */
    fun toMarkdown(): String = buildString {
        appendLine("Package: $packageName")
        appendLine("Elements: ${elements.size}")
        appendLine()
        elements.forEach { element ->
            val indent = "  ".repeat(element.depth)
            appendLine(
                    "$indent[${element.id}] ${element.type}: \"${element.label}\" (${element.capabilities.joinToString()})"
            )
        }
    }

    /**
     * CRITICAL: Manually recycle all AccessibilityNodeInfos. Must be called when this state is
     * replaced to prevent Memory Leaks.
     */
    fun recycle() {
        elements.forEach { element ->
            com.example.llama.agent.utils.CompatibilityUtils.recycleNode(element.nodeInfo)
        }
    }
}
