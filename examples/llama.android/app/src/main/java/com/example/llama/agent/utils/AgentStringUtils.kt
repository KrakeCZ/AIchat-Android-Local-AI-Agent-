package com.example.llama.agent.utils

import java.text.Normalizer
import java.util.Locale

/**
 * AgentStringUtils — Normalizační utilita pro sémantické vyhledávání.
 *
 * Zodpovědnost:
 * - Normalizace uživatelského vstupu pro fuzzy/slang matching
 * - Odstraňuje diakritiku, převádí na lowercase, ořezává whitespace
 *
 * Příklady:
 *   "Instáč"  → "instac"
 *   "foťák"   → "fotak"
 *   "YOUTUBE" → "youtube"
 *   null      → ""
 */
object AgentStringUtils {

    /**
     * Normalizuje vstupní text pro vyhledávání v invertovaném indexu.
     *
     * Kroky:
     * 1. Null/empty guard → vrátí ""
     * 2. Lowercase (Locale.ROOT — locale-agnostické, bezpečné pro interní logiku)
     * 3. NFD normalizace — rozloží složené znaky (é → e + combining accent)
     * 4. Strip combining diacritical marks — odstraní diakritiku
     * 5. Trim — odstraní leading/trailing whitespace
     *
     * @param input Vstupní řetězec (může být null)
     * @return Normalizovaný řetězec, nikdy null
     */
    fun normalizeForLookup(input: String?): String {
        if (input.isNullOrEmpty()) return ""

        // Krok 1: Lowercase (Locale.ROOT — bezpečné pro ASCII + CZ znaky)
        val lower = input.lowercase(Locale.ROOT)

        // Krok 2: NFD Normalizace — rozloží znaky (č → c + ̌)
        val nfd = Normalizer.normalize(lower, Normalizer.Form.NFD)

        // Krok 3: Strip combining diacritical marks + trim
        return nfd.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "").trim()
    }
}
