package com.example.llama.agent.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sjednocený poskytovatel GBNF gramatiky a OpenAI-kompatibilních definic nástrojů
 * pro JNI vrstvu `ai_chat.cpp` (`setToolDefinitionsNative` & `setGrammarNative`).
 * Standard Edge-AnythingLLM 2026/2027.
 */
object EdgeAgentGrammarAndToolsProvider {

    /**
     * Kompletní GBNF gramatika pro `setGrammarNative(jgrammar)`.
     * Vynucuje striktní JSON strukturu pro všech 14 nástrojů uvnitř <tool_call>...</tool_call>.
     */
    val STRICT_AGENT_GBNF_GRAMMAR: String = """
        root ::= think-block? (tool-call-block | direct-answer)
        think-block ::= "<think>" "\n"? think-text "</think>" "\n"*
        think-text  ::= ([^<] | "<" [^/])*
        direct-answer ::= [^\x00<] [^\x00]*
        tool-call-block ::= "<tool_call>" "\n"? tool-dispatch "\n"? "</tool_call>" ws

        tool-dispatch ::=
            call-web-search |
            call-academic-patent-search |
            call-browser-research-page |
            call-browser-open |
            call-browser-scroll |
            call-browser-click |
            call-browser-find |
            call-browser-back |
            call-rag-search |
            call-file-write |
            call-file-read |
            call-file-list |
            call-file-delete |
            call-realtime-status

        call-web-search ::= "{" ws "\"name\"" ws ":" ws "\"web_search\"" ws "," ws "\"arguments\"" ws ":" ws args-web-search "}"
        args-web-search ::= "{" ws "\"query\"" ws ":" ws string ("," ws "\"max_results\"" ws ":" ws int-small)? "}" ws

        call-academic-patent-search ::= "{" ws "\"name\"" ws ":" ws "\"academic_patent_search\"" ws "," ws "\"arguments\"" ws ":" ws args-academic-search "}"
        args-academic-search ::= "{" ws "\"query\"" ws ":" ws string ("," ws "\"category\"" ws ":" ws category-enum)? ("," ws "\"max_results\"" ws ":" ws int-small)? "}" ws
        category-enum ::= ("\"academic\"" | "\"patent\"" | "\"web\"") ws

        call-browser-research-page ::= "{" ws "\"name\"" ws ":" ws "\"browser_research_page\"" ws "," ws "\"arguments\"" ws ":" ws args-browser-research "}"
        args-browser-research ::= "{" ws "\"url\"" ws ":" ws http-url-string "," ws "\"focus_query\"" ws ":" ws string ("," ws "\"max_tokens\"" ws ":" ws int-tokens)? "}" ws

        call-browser-open ::= "{" ws "\"name\"" ws ":" ws "\"browser_open\"" ws "," ws "\"arguments\"" ws ":" ws args-browser-open "}"
        args-browser-open ::= "{" ws "\"url\"" ws ":" ws http-url-string ("," ws "\"tokens_per_viewport\"" ws ":" ws int-tokens)? "}" ws

        call-browser-scroll ::= "{" ws "\"name\"" ws ":" ws "\"browser_scroll\"" ws "," ws "\"arguments\"" ws ":" ws args-browser-scroll "}"
        args-browser-scroll ::= "{" ws "\"direction\"" ws ":" ws scroll-dir-enum ("," ws "\"steps\"" ws ":" ws int-small)? "}" ws
        scroll-dir-enum ::= ("\"down\"" | "\"up\"" | "\"top\"" | "\"bottom\"") ws

        call-browser-click ::= "{" ws "\"name\"" ws ":" ws "\"browser_click\"" ws "," ws "\"arguments\"" ws ":" ws args-browser-click "}"
        args-browser-click ::= "{" ws "\"link_index\"" ws ":" ws int-link "}" ws

        call-browser-find ::= "{" ws "\"name\"" ws ":" ws "\"browser_find\"" ws "," ws "\"arguments\"" ws ":" ws args-browser-find "}"
        args-browser-find ::= "{" ws "\"keyword\"" ws ":" ws string "}" ws

        call-browser-back ::= "{" ws "\"name\"" ws ":" ws "\"browser_back\"" ws "," ws "\"arguments\"" ws ":" ws empty-obj "}"

        call-rag-search ::= "{" ws "\"name\"" ws ":" ws "\"rag_search\"" ws "," ws "\"arguments\"" ws ":" ws args-rag-search "}"
        args-rag-search ::= "{" ws "\"query\"" ws ":" ws string ("," ws "\"top_k\"" ws ":" ws int-small)? "}" ws

        call-file-write ::= "{" ws "\"name\"" ws ":" ws "\"file_write\"" ws "," ws "\"arguments\"" ws ":" ws args-file-write "}"
        args-file-write ::= "{" ws "\"path\"" ws ":" ws safe-path-string "," ws "\"content\"" ws ":" ws string ("," ws "\"append\"" ws ":" ws boolean)? "}" ws

        call-file-read ::= "{" ws "\"name\"" ws ":" ws "\"file_read\"" ws "," ws "\"arguments\"" ws ":" ws args-file-read "}"
        args-file-read ::= "{" ws "\"path\"" ws ":" ws safe-path-string ("," ws "\"max_chars\"" ws ":" ws int-chars)? ("," ws "\"offset\"" ws ":" ws int-offset)? "}" ws

        call-file-list ::= "{" ws "\"name\"" ws ":" ws "\"file_list\"" ws "," ws "\"arguments\"" ws ":" ws empty-obj "}"

        call-file-delete ::= "{" ws "\"name\"" ws ":" ws "\"file_delete\"" ws "," ws "\"arguments\"" ws ":" ws args-file-delete "}"
        args-file-delete ::= "{" ws "\"path\"" ws ":" ws safe-path-string "}" ws

        call-realtime-status ::= "{" ws "\"name\"" ws ":" ws "\"realtime_status\"" ws "," ws "\"arguments\"" ws ":" ws empty-obj "}"

        empty-obj ::= "{" ws "}" ws
        http-url-string ::= "\"http" "s"? "://" ([^"\\\x7F\x00-\x1F ])+ "\"" ws
        safe-path-string ::= "\"" [a-zA-Z0-9_] [a-zA-Z0-9_.\-/]{0,60} "\"" ws
        string ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F]{4}) )* "\"" ws
        boolean ::= ("true" | "false") ws
        int-small ::= [1-9] [0-9]? ws
        int-link ::= [1-9] [0-9]? ws
        int-tokens ::= [1-9] [0-9]{2,3} ws
        int-chars ::= [1-9] [0-9]{2,4} ws
        int-offset ::= ("0" | [1-9] [0-9]{0,6}) ws
        ws ::= | " " | "\n" [ \t]{0,8}
    """.trimIndent()

    /**
     * Vrátí GBNF gramatiku z assetů aplikace nebo interní fallback.
     */
    fun getGrammar(context: Context?): String {
        if (context != null) {
            runCatching {
                context.assets.open("grammars/edge_agent_tools.gbnf").use { input ->
                    return input.bufferedReader().readText()
                }
            }
        }
        return STRICT_AGENT_GBNF_GRAMMAR
    }

    /**
     * Vytvoří JSON pole v OpenAI formátu `[{"type": "function", "function": {...}}]`,
     * které v `ai_chat.cpp` čte metoda `setToolDefinitionsNative` přes `common_chat_tools_parse_oaicompat`.
     */
    fun buildNativeToolsJsonForLlamaCpp(): String {
        val toolsArray = JSONArray()

        // 1. web_search
        toolsArray.put(
            buildToolObject(
                name = "web_search",
                description = "Performs parallel multi-source search across general web and configured custom domains.",
                properties = JSONObject().apply {
                    put("query", propString("Search query"))
                    put("max_results", propInteger("Maximum number of results (default 5)"))
                },
                required = listOf("query")
            )
        )

        // 2. academic_patent_search
        toolsArray.put(
            buildToolObject(
                name = "academic_patent_search",
                description = "Searches peer-reviewed scientific studies, preprints, academic papers (arXiv, Semantic Scholar, OpenAlex, EuropePMC) and international patents.",
                properties = JSONObject().apply {
                    put("query", propString("Scientific, technical, or patent search query"))
                    put(
                        "category",
                        JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("academic", "patent", "web")))
                            put("description", "Filter by source category")
                        }
                    )
                    put("max_results", propInteger("Maximum number of results to return (1-10)"))
                },
                required = listOf("query")
            )
        )

        // 3. browser_research_page (Autonomní Subagent)
        toolsArray.put(
            buildToolObject(
                name = "browser_research_page",
                description = "Autonomously loads a webpage (including JS/SPA via Headless WebView), scrolls through content, indexes chunks into Web RAG, and returns the most relevant excerpts for focus_query in a single step.",
                properties = JSONObject().apply {
                    put("url", propString("Full http:// or https:// URL to research"))
                    put("focus_query", propString("Specific question or topic to extract from the page"))
                    put("max_tokens", propInteger("Maximum token budget for extracted chunks (default 550)"))
                },
                required = listOf("url", "focus_query")
            )
        )

        // 4. browser_open
        toolsArray.put(
            buildToolObject(
                name = "browser_open",
                description = "Opens a webpage in stateful reader mode and returns the first token-paginated viewport and numbered links.",
                properties = JSONObject().apply {
                    put("url", propString("Target http:// or https:// URL"))
                    put("tokens_per_viewport", propInteger("Token limit per viewport"))
                },
                required = listOf("url")
            )
        )

        // 5. browser_scroll
        toolsArray.put(
            buildToolObject(
                name = "browser_scroll",
                description = "Scrolls the currently open webpage viewport up, down, to top, or to bottom.",
                properties = JSONObject().apply {
                    put(
                        "direction",
                        JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("down", "up", "top", "bottom")))
                        }
                    )
                    put("steps", propInteger("Number of viewports to scroll (default 1)"))
                },
                required = listOf("direction")
            )
        )

        // 6. browser_click
        toolsArray.put(
            buildToolObject(
                name = "browser_click",
                description = "Follows an interactive numbered link [#index] from the currently open webpage.",
                properties = JSONObject().apply {
                    put("link_index", propInteger("Numeric ID of the link to open"))
                },
                required = listOf("link_index")
            )
        )

        // 7. browser_find
        toolsArray.put(
            buildToolObject(
                name = "browser_find",
                description = "Searches for a keyword or phrase in the currently open webpage and navigates to the first match.",
                properties = JSONObject().apply {
                    put("keyword", propString("Word or phrase to find"))
                },
                required = listOf("keyword")
            )
        )

        // 8. browser_back
        toolsArray.put(
            buildToolObject(
                name = "browser_back",
                description = "Navigates back to the previous page in session history.",
                properties = JSONObject(),
                required = emptyList()
            )
        )

        // 9. rag_search (Lokální SQLite FTS5 Hybrid RAG)
        toolsArray.put(
            buildToolObject(
                name = "rag_search",
                description = "Searches the user's indexed local documents (SAF User RAG) and offline Web RAG archive using SQLite FTS5 BM25 chunking (512 chars with 64 overlap).",
                properties = JSONObject().apply {
                    put("query", propString("Keywords or phrase to search in local RAG database"))
                    put("top_k", propInteger("Number of 512-char chunks to retrieve (default 5)"))
                },
                required = listOf("query")
            )
        )

        // 10. file_write
        toolsArray.put(
            buildToolObject(
                name = "file_write",
                description = "Creates or updates a file (.md, .json, .csv, .txt, .kt, .py) inside the isolated agent workspace.",
                properties = JSONObject().apply {
                    put("path", propString("Relative file path inside workspace"))
                    put("content", propString("Full text content to write"))
                    put("append", JSONObject().put("type", "boolean").put("description", "Append to existing file"))
                },
                required = listOf("path", "content")
            )
        )

        // 11. file_read
        toolsArray.put(
            buildToolObject(
                name = "file_read",
                description = "Reads content from a file in the agent workspace.",
                properties = JSONObject().apply {
                    put("path", propString("Relative file path to read"))
                    put("max_chars", propInteger("Maximum characters to read"))
                    put("offset", propInteger("Character offset for pagination"))
                },
                required = listOf("path")
            )
        )

        // 12. file_list
        toolsArray.put(
            buildToolObject(
                name = "file_list",
                description = "Lists all files in the isolated agent workspace.",
                properties = JSONObject(),
                required = emptyList()
            )
        )

        // 13. file_delete
        toolsArray.put(
            buildToolObject(
                name = "file_delete",
                description = "Deletes a file from the isolated agent workspace.",
                properties = JSONObject().apply {
                    put("path", propString("Relative path of file to delete"))
                },
                required = listOf("path")
            )
        )

        // 14. realtime_status
        toolsArray.put(
            buildToolObject(
                name = "realtime_status",
                description = "Returns real-time device telemetry (exact date/time, battery, free RAM, network & FUP status).",
                properties = JSONObject(),
                required = emptyList()
            )
        )

        return toolsArray.toString()
    }

    private fun buildToolObject(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>
    ): JSONObject {
        val parametersObj = JSONObject().apply {
            put("type", "object")
            put("properties", properties)
            if (required.isNotEmpty()) {
                put("required", JSONArray(required))
            }
            put("additionalProperties", false)
        }

        val functionObj = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", parametersObj)
        }

        return JSONObject().apply {
            put("type", "function")
            put("function", functionObj)
        }
    }

    private fun propString(desc: String): JSONObject = JSONObject().apply {
        put("type", "string")
        put("description", desc)
    }

    private fun propInteger(desc: String): JSONObject = JSONObject().apply {
        put("type", "integer")
        put("description", desc)
    }
}
