package com.example.llama.agent.adapter.template

import com.arm.aichat.InferenceEngine
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Modul pro modely s Jinja2 šablonou, která ale nemá nativní větve pro tools.
 * Nástroje a kontext se předávají přes systémový prompt v setSystemPrompt.
 */
class JinjaPromptInjectedModule : TemplateProtocolModule {
    override val type: TemplateType = TemplateType.JINJA_PROMPT_INJECTED

    override fun configureEngine(engine: InferenceEngine, toolsEnabled: Boolean, toolsJson: String) {
        engine.setToolDefinitions("")
        engine.setToolPolicy(2) // NONE
    }

    override fun prepareUserPrompt(userMsg: String, systemPrompt: String, isFirstTurn: Boolean): String {
        return userMsg
    }

    override fun extractToolCall(
        content: String,
        reasoning: String,
        nativeToolName: String?,
        nativeToolArgs: String?
    ): ToolCallRequest? {
        val fromContent = WebToolsDispatcher.findToolCallInText(content)
        if (fromContent != null) return fromContent

        if (reasoning.isNotBlank()) {
            val fromReasoning = WebToolsDispatcher.findToolCallInText(reasoning)
            if (fromReasoning != null) return fromReasoning
        }

        return null
    }

    override fun formatToolResponse(
        userMsg: String,
        toolResponse: ToolCallResponse,
        currentDateTime: String
    ): String {
        val normQuery = WebToolsDispatcher.normalizeCzechSearchQuery(userMsg)
        return """
            <tool_response>
            [Výsledek: ${toolResponse.toolName} | Aktuální čas: $currentDateTime]
            ${toolResponse.content}
            </tool_response>

            Původní dotaz uživatele: "$userMsg" (s českou diakritikou: "$normQuery").
            Upozornění: Uživatel psal bez háčků a čárek (např. "Decin" = město Děčín, "pocasi" = počasí).
            Na základě výše uvedených ověřených informací a souhrnu z webu od Hledacího Bota napiš přímou, věcnou a přehlednou odpověď v češtině (nevolej již další nástroje).
        """.trimIndent()
    }
}
