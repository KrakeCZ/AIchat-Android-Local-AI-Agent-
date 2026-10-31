package com.example.llama.agent.adapter.template

import com.arm.aichat.InferenceEngine
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse

enum class TemplateType {
    NON_JINJA_LEGACY,
    JINJA_PROMPT_INJECTED,
    JINJA2_NATIVE_TOOLS,
    USER_INJECTED,
    CHATML
}

/**
 * Samostatný modul protokolu šablony.
 * Řeší technický způsob komunikace s llama.cpp a doručení nástrojů:
 * - Zda nakonfigurovat C++ engine nativně přes setToolDefinitions / setToolPolicy,
 * - Zda doručit systémový kontext přes setSystemPrompt nebo User-Injected turn,
 * - Jak extrahovat tool call a formátovat výsledek nástroje čistě bez manipulace modelu.
 */
interface TemplateProtocolModule {
    val type: TemplateType

    /**
     * Nakonfiguruje C++ engine (nativní Jinja tool definice vs prompt injected policy).
     */
    fun configureEngine(engine: InferenceEngine, toolsEnabled: Boolean, toolsJson: String)

    /**
     * Připraví text uživatelského promptu pro daný turn (pro User-Injected modely např. Gemma 3 sloučí kontext s prvním turnem).
     */
    fun prepareUserPrompt(userMsg: String, systemPrompt: String, isFirstTurn: Boolean): String

    /**
     * Extrahuje volání nástroje z textu, myšlenkového proudu nebo nativních eventů.
     */
    fun extractToolCall(
        content: String,
        reasoning: String,
        nativeToolName: String?,
        nativeToolArgs: String?
    ): ToolCallRequest?

    /**
     * Zformátuje výsledek nástroje čistě a standardně bez manipulačních direktiv.
     */
    fun formatToolResponse(
        userMsg: String,
        toolResponse: ToolCallResponse,
        currentDateTime: String
    ): String
}
