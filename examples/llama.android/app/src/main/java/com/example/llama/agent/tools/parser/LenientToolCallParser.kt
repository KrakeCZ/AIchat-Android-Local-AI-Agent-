package com.example.llama.agent.tools.parser

import android.util.Log
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.WebToolsDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Robustní modulární parser volání nástrojů (Lenient Tool Call Parser).
 *
 * Eliminuje chyby typu "Failed parsing candidate tool call JSON via JSONObject"
 * díky:
 * 1. Stavovému automatu pro počítání hloubky závorek (Bracket Depth Counter), který se nenechá zmást vnořenými JSON objekty.
 * 2. Tolerantní normalizaci JSONu (převod jednoduchých uvozovek na dvojité, odstranění koncových čárek, ošetření unescapovaných nových řádků).
 * 3. Modulární podpoře specifických formátů modelů (Qwen XML, Mistral [TOOL_CALLS], ReAct, standardní OpenAI function calling).
 */
object LenientToolCallParser {
    private const val TAG = "LenientToolCallParser"

    private val SUPPORTED_TOOLS_REGEX_GROUP =
        "web_search|academic_patent_search|browser_research_page|read_url_content|read_url|fetch_url|browser_open|browser_scroll|browser_click|browser_find|browser_back|file_write|file_read|file_list|file_delete|realtime_status|rag_search|knowledge_search"

    /**
     * Hlavní metoda pro extrakci požadavku na nástroj ze surového textového výstupu modelu.
     */
    fun extractToolCall(text: String): ToolCallRequest? {
        if (text.isBlank()) return null

        // 1. Specifické XML tagy: <tool_call> ... </tool_call> (Qwen, DeepSeek MoE)
        val qwenXmlRegex = Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""", RegexOption.IGNORE_CASE)
        val qwenMatch = qwenXmlRegex.find(text)
        if (qwenMatch != null) {
            val body = qwenMatch.groupValues[1].trim()
            val parsed = parseCandidateJson(body)
            if (parsed != null) return parsed
        }

        // 2. Mistral nativní formát: [TOOL_CALLS] [...]
        val mistralRegex = Regex("""\[TOOL_CALLS\]\s*([\s\S]*?)(?:\[/TOOL_CALLS\]|$)""", RegexOption.IGNORE_CASE)
        val mistralMatch = mistralRegex.find(text)
        if (mistralMatch != null) {
            val body = mistralMatch.groupValues[1].trim()
            val parsed = parseCandidateJson(body)
            if (parsed != null) return parsed
        }

        // 3. Markdown kódové bloky: ```json { ... } ``` nebo ```tool_call { ... } ```
        val codeBlockRegex = Regex("""```(?:tool_call|json)?\s*(\{[\s\S]*?\})\s*```""", RegexOption.IGNORE_CASE)
        for (cm in codeBlockRegex.findAll(text)) {
            val parsed = parseCandidateJson(cm.groupValues[1].trim())
            if (parsed != null) return parsed
        }

        // 4. Stavový automat: Nalezení všech vyvážených { ... } bloků v textu
        val jsonBlocks = extractBalancedJsonBlocks(text)
        for (block in jsonBlocks) {
            val parsed = parseCandidateJson(block)
            if (parsed != null) return parsed
        }

        // 5. ReAct formát: Action: tool_name\nAction Input: ...
        val reactRegex = Regex("""Action:\s*($SUPPORTED_TOOLS_REGEX_GROUP)\s*[\r\n]+Action Input:\s*([^\r\n]+)""", RegexOption.IGNORE_CASE)
        val reactMatch = reactRegex.find(text)
        if (reactMatch != null) {
            val tName = reactMatch.groupValues[1]
            val input = reactMatch.groupValues[2].trim()
            val args = parseArgumentsFallback(input)
            return ToolCallRequest(tName, args)
        }

        // 6. Volání funkce v Python stylu: call:tool_name(...) nebo tool_name(...)
        val funcCallRegex = Regex("""(?:call:)?($SUPPORTED_TOOLS_REGEX_GROUP)\s*\(([\s\S]*?)\)""", RegexOption.IGNORE_CASE)
        val funcMatch = funcCallRegex.find(text)
        if (funcMatch != null) {
            val tName = funcMatch.groupValues[1]
            val rawParams = funcMatch.groupValues[2].trim()
            val args = parseArgumentsFallback(rawParams)
            return ToolCallRequest(tName, args)
        }

        // 7. Závorkový formát: [TOOL: web_search query="..."]
        val bracketRegex = Regex("""\[(?:TOOL:?\s*)?(?:call:)?($SUPPORTED_TOOLS_REGEX_GROUP)[\s:=]+([^\]]+)\]""", RegexOption.IGNORE_CASE)
        val bracketMatch = bracketRegex.find(text)
        if (bracketMatch != null) {
            val tName = bracketMatch.groupValues[1]
            val rawParams = bracketMatch.groupValues[2].trim()
            val args = parseArgumentsFallback(rawParams)
            return ToolCallRequest(tName, args)
        }

        // 8. Jednořádkový formát s dvojtečkou: web_search: "dotaz"
        val colonRegex = Regex("""(?m)^(?:\s*call:)?\s*($SUPPORTED_TOOLS_REGEX_GROUP)\s*:\s*["']?([^"'\r\n]+)["']?\s*$""", RegexOption.IGNORE_CASE)
        val colonMatch = colonRegex.find(text)
        if (colonMatch != null) {
            val tName = colonMatch.groupValues[1]
            val queryVal = colonMatch.groupValues[2].trim()
            if (queryVal.isNotBlank() && !queryVal.contains("error", ignoreCase = true)) {
                val paramKey = if (tName.contains("search", ignoreCase = true) || tName.contains("rag", ignoreCase = true)) "query" else "url"
                return ToolCallRequest(tName, mapOf(paramKey to queryVal))
            }
        }

        // 9. Název nástroje následovaný JSONem na novém řádku
        val nameThenJsonRegex = Regex("""(?m)^(?:\s*call:)?\s*($SUPPORTED_TOOLS_REGEX_GROUP)\s*[\r\n]+(\{[\s\S]*?\})""", RegexOption.IGNORE_CASE)
        val ntjMatch = nameThenJsonRegex.find(text)
        if (ntjMatch != null) {
            val tName = ntjMatch.groupValues[1]
            val body = ntjMatch.groupValues[2].trim()
            val parsed = parseCandidateJson(body, fallbackName = tName)
            if (parsed != null) return parsed
            val args = parseArgumentsFallback(body)
            return ToolCallRequest(tName, args)
        }

        // 10. XML tagy pojmenované přímo podle nástroje: <web_search>...</web_search> nebo <function=web_search>...</function>
        val directTagRegex = Regex("""<($SUPPORTED_TOOLS_REGEX_GROUP)>\s*([\s\S]*?)\s*</\1>""", RegexOption.IGNORE_CASE)
        val dtMatch = directTagRegex.find(text)
        if (dtMatch != null) {
            val tName = dtMatch.groupValues[1]
            val body = dtMatch.groupValues[2].trim()
            val parsed = parseCandidateJson(body, fallbackName = tName)
            if (parsed != null) return parsed
            val args = parseArgumentsFallback(body)
            return ToolCallRequest(tName, args)
        }

        // 11. Prose / Markdown popis volání nástroje (když model vypíše že používá nástroj a uvede dotaz v uvozovkách, ale zapomene <tool_call>)
        val proseCall = extractProseToolCall(text)
        if (proseCall != null) return proseCall

        return null
    }

    /**
     * Záchytný extraktor pro případy, kdy model v textu/markdownu popisuje volání nástroje
     * (např. "Vyhledání pomocí nástroje web_search ... \"aktuální počasí Děčín dnes\"") místo vypsání <tool_call> tagu.
     */
    fun extractProseToolCall(text: String, userPromptFallback: String = ""): ToolCallRequest? {
        if (text.isBlank()) return null

        // A) Zmínka o web_search / rag_search / academic_patent_search následovaná dotazem v uvozovkách
        val proseSearchRegex = Regex(
            """(?:nástroj[eě]?m?\s+|pomocí\s+|using\s+|tool\s+|`|\*\*)?(web_search|academic_patent_search|rag_search|knowledge_search)(?:`|\*\*)?[\s\S]{1,220}?["„“]([^"„“\r\n]{3,120})["“”]""",
            RegexOption.IGNORE_CASE
        )
        val psMatch = proseSearchRegex.find(text)
        if (psMatch != null) {
            val toolName = psMatch.groupValues[1].lowercase()
            val queryCandidate = psMatch.groupValues[2].trim()
            if (!isSupportedToolName(queryCandidate) && !queryCandidate.contains("{") && !queryCandidate.startsWith("http")) {
                return ToolCallRequest(toolName, mapOf("query" to queryCandidate))
            }
        }

        // B) Zmínka o browser_open / read_url_content / browser_research_page s URL adresou
        val proseUrlRegex = Regex(
            """(browser_open|read_url_content|browser_research_page)[\s\S]{1,180}?(https?://[^\s"'<>)\]]+)""",
            RegexOption.IGNORE_CASE
        )
        val puMatch = proseUrlRegex.find(text)
        if (puMatch != null) {
            val toolName = puMatch.groupValues[1].lowercase()
            val urlCandidate = puMatch.groupValues[2].trim().trimEnd('.', ',', ';')
            val args = mutableMapOf("url" to urlCandidate)
            if (toolName == "browser_research_page" && userPromptFallback.isNotBlank()) {
                args["focus_query"] = userPromptFallback
            }
            return ToolCallRequest(toolName, args)
        }

        // C) Pokud model explicitně zmiňuje "pomocí nástroje web_search" / "using web_search" a máme userPromptFallback
        if (userPromptFallback.isNotBlank()) {
            val mentionsSearchTool = Regex(
                """(?:pomocí\s+nástroje|nástrojem|použiji\s+nástroj|use\s+the|using\s+the|call\s+the)\s+[`*_]*(web_search|academic_patent_search|rag_search)[`*_]*""",
                RegexOption.IGNORE_CASE
            ).find(text)
            if (mentionsSearchTool != null) {
                val tName = mentionsSearchTool.groupValues[1].lowercase()
                val cleanQ = WebToolsDispatcher.extractSearchQueryFromUserPrompt(userPromptFallback)
                if (cleanQ.isNotBlank()) {
                    return ToolCallRequest(tName, mapOf("query" to cleanQ))
                }
            }
        }

        return null
    }

    /**
     * Stavový automat pro vyhledání všech vyvážených složených závorek { ... }
     * bez ořezávání vnořených struktur.
     */
    fun extractBalancedJsonBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        var depth = 0
        var startIndex = -1
        var inString = false
        var quoteChar = ' '
        var isEscaped = false

        for (i in text.indices) {
            val c = text[i]

            if (isEscaped) {
                isEscaped = false
                continue
            }

            if (c == '\\') {
                isEscaped = true
                continue
            }

            if (inString) {
                if (c == quoteChar) {
                    inString = false
                }
                continue
            } else {
                if (c == '"' || c == '\'') {
                    inString = true
                    quoteChar = c
                    continue
                }
            }

            if (c == '{') {
                if (depth == 0) {
                    startIndex = i
                }
                depth++
            } else if (c == '}') {
                if (depth > 0) {
                    depth--
                    if (depth == 0 && startIndex != -1) {
                        val block = text.substring(startIndex, i + 1).trim()
                        if (block.contains("name") || block.contains("tool") || block.contains("action") ||
                            block.contains("function") || block.contains("query") || block.contains("url")
                        ) {
                            blocks.add(block)
                        }
                        startIndex = -1
                    }
                }
            }
        }

        // Pokud zůstal otevřený blok na konci textu (např. přerušení tokenu), zkusíme jej uzavřít
        if (depth > 0 && startIndex != -1) {
            val unfinished = text.substring(startIndex).trim()
            val autoClosed = unfinished + "}".repeat(depth)
            blocks.add(autoClosed)
        }

        return blocks
    }

    /**
     * Tolerantní naparsování JSON kandidáta s automatickou normalizací syntaxe.
     */
    fun parseCandidateJson(rawCandidate: String, fallbackName: String = ""): ToolCallRequest? {
        val normalized = normalizeLenientJson(rawCandidate)

        try {
            // Podpora JSON pole: [ { "name": "...", "arguments": ... } ]
            if (normalized.startsWith("[")) {
                val array = JSONArray(normalized)
                if (array.length() > 0) {
                    val first = array.optJSONObject(0)
                    if (first != null) {
                        val req = parseJsonObjectToToolCall(first, fallbackName)
                        if (req != null) return req
                    }
                }
            } else {
                val json = JSONObject(normalized)
                val req = parseJsonObjectToToolCall(json, fallbackName)
                if (req != null) return req
            }
        } catch (e: Exception) {
            // Nulový spam do Logcatu – tichý fallback na regulární výraz pro extrakci klíčů
            val nameMatch = Regex(""""(?:name|tool|action)"\s*:\s*"([^"]+)"""").find(normalized)
            val extractedName = nameMatch?.groupValues?.get(1) ?: fallbackName
            if (extractedName.isNotBlank() && isSupportedToolName(extractedName)) {
                val args = parseArgumentsFallback(normalized)
                if (args.isNotEmpty()) {
                    return ToolCallRequest(extractedName, args)
                }
            }
        }

        return null
    }

    private fun parseJsonObjectToToolCall(json: JSONObject, fallbackName: String): ToolCallRequest? {
        var name = json.optString("name", "")
        if (name.isBlank()) {
            val fnObj = json.optJSONObject("function")
            if (fnObj != null) {
                name = fnObj.optString("name", "")
            }
        }
        if (name.isBlank()) name = json.optString("tool", "")
        if (name.isBlank()) name = json.optString("action", "")
        if (name.isBlank()) name = fallbackName

        if (name.isNotBlank() && isSupportedToolName(name)) {
            val args = mutableMapOf<String, String>()
            val argsObj = json.optJSONObject("arguments")
                ?: json.optJSONObject("parameters")
                ?: json.optJSONObject("function")?.optJSONObject("arguments")

            if (argsObj != null) {
                // Detekce vnořeného tool callu přímo v arguments: { name: "...", arguments: { ... } }
                val innerName = argsObj.optString("name", "").ifBlank { argsObj.optString("tool", "") }
                val innerArgsObj = argsObj.optJSONObject("arguments") ?: argsObj.optJSONObject("parameters")
                if (innerName.isNotBlank() && isSupportedToolName(innerName) && innerArgsObj != null) {
                    name = innerName
                    for (key in innerArgsObj.keys()) {
                        args[key] = innerArgsObj.optString(key, "")
                    }
                } else {
                    for (key in argsObj.keys()) {
                        val subObj = argsObj.optJSONObject(key)
                        if (subObj != null) {
                            val subQuery = subObj.optString("query", "").ifBlank { subObj.optString("q", "") }
                            if (subQuery.isNotBlank()) {
                                args[key] = subQuery
                            } else {
                                args[key] = subObj.toString()
                            }
                        } else {
                            args[key] = argsObj.optString(key, "")
                        }
                    }
                }
            }

            val rawArgsStr = json.optString("arguments", "")
            if (rawArgsStr.isNotBlank() && rawArgsStr.startsWith("{")) {
                try {
                    val nestedNorm = normalizeLenientJson(rawArgsStr)
                    val nested = JSONObject(nestedNorm)
                    val innerName = nested.optString("name", "").ifBlank { nested.optString("tool", "") }
                    val innerArgs = nested.optJSONObject("arguments") ?: nested.optJSONObject("parameters")
                    if (innerName.isNotBlank() && isSupportedToolName(innerName) && innerArgs != null) {
                        name = innerName
                        for (key in innerArgs.keys()) {
                            args[key] = innerArgs.optString(key, "")
                        }
                    } else {
                        for (key in nested.keys()) {
                            args[key] = nested.optString(key, "")
                        }
                    }
                } catch (_: Exception) {}
            }

            for (key in json.keys()) {
                if (key != "name" && key != "arguments" && key != "parameters" && key != "type" && key != "function") {
                    args[key] = json.optString(key, "")
                }
            }

            return ToolCallRequest(name = name, arguments = args)
        }
        return null
    }

    /**
     * Normalizuje nevalidní JSON fragmenty na striktní JSON:
     * - Převod Python jednoduchých uvozovek na dvojité (při zachování apostrofů uvnitř slov)
     * - Odstranění přebytečných čárek před } a ]
     * - Ošetření řídicích znaků
     */
    fun normalizeLenientJson(raw: String): String {
        var s = raw.trim()

        // 1. Ořez Markdown backticků pokud existují
        if (s.startsWith("```")) {
            s = s.substringAfter("\n").substringBeforeLast("```").trim()
        }

        // 2. Nahrazení jednoduchých uvozovek u klíčů a hodnot: 'name': 'value' -> "name": "value"
        if (s.contains("'")) {
            s = s.replace(Regex("""(?<=^|[{,\s:])'([^'\\]*(?:\\.[^'\\]*)*)'(?=\s*[:},\]])""")) { m ->
                "\"${m.groupValues[1]}\""
            }
        }

        // 3. Odstranění trailing commas: ,} -> } a ,] -> ]
        s = s.replace(Regex(""",\s*([}\]])""")) { m -> m.groupValues[1] }

        // 4. Ošetření neescapovaných nových řádků uvnitř textových hodnot
        // (pokud model vytvořil multilajnový řetězec bez \n)
        s = s.replace("\r\n", "\\n").replace("\r", "\\n")

        return s
    }

    fun isSupportedToolName(name: String): Boolean {
        val n = name.lowercase().trim()
        return n.contains("search") ||
            n.contains("url") ||
            n.startsWith("browser_") ||
            n.startsWith("file_") ||
            n == "realtime_status" ||
            n.contains("rag") ||
            n.contains("knowledge")
    }

    fun parseArgumentsFallback(rawParams: String): Map<String, String> {
        val args = mutableMapOf<String, String>()
        val trimmed = rawParams.trim()

        val kvRegex = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        for (m in kvRegex.findAll(trimmed)) {
            val k = m.groupValues[1]
            val v = m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } }
            args[k] = v
        }

        if (args.isEmpty() && trimmed.isNotBlank()) {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                args["url"] = trimmed
            } else {
                val clean = trimmed.trim('"', '\'')
                args["query"] = clean
            }
        }

        return args
    }
}
