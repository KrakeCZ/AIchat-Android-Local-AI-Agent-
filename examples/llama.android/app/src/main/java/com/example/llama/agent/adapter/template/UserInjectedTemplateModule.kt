package com.example.llama.agent.adapter.template

import com.arm.aichat.InferenceEngine
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Modul pro modely, které v šabloně nepodporují samostatnou roli ROLE_SYSTEM nebo ji zahazují (např. Google Gemma 2/3).
 * Systémový kontext a definice nástrojů se doručují v hlavičce prvního uživatelského turnu.
 */
class UserInjectedTemplateModule : TemplateProtocolModule {
    override val type: TemplateType = TemplateType.USER_INJECTED

    override fun configureEngine(engine: InferenceEngine, toolsEnabled: Boolean, toolsJson: String) {
        // C++ engine drží čisté textové samplování bez GBNF omezovačů
        engine.setToolDefinitions("")
        engine.setToolPolicy(2) // NONE
    }

    override fun prepareUserPrompt(userMsg: String, systemPrompt: String, isFirstTurn: Boolean): String {
        // C++ vrstva (ai_chat.cpp -> g_deferred_system_prompt) automaticky sloučí odložený
        // systémový prompt do 1. user turnu, pokud šablona nepodporuje samostatný ROLE_SYSTEM.
        // Tím je zaručeno 100% doručení bez duplikace v KV cache.
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
        val topic = WebToolsDispatcher.extractSearchQueryFromUserPrompt(userMsg).ifBlank { userMsg }
        val normQuery = WebToolsDispatcher.normalizeCzechSearchQuery(userMsg)
        return """
            [Výsledek nástroje: ${toolResponse.toolName} k tématu "$topic" | Aktuální čas: $currentDateTime]:
            ${toolResponse.content}

            Původní dotaz uživatele: "$userMsg" (s českou diakritikou: "$normQuery").
            Upozornění: Uživatel psal bez háčků a čárek (např. "Decin" = město Děčín, "pocasi" = počasí).
            Na základě výše uvedených ověřených informací a souhrnu z webu od Hledacího Bota napiš přímou, věcnou a přehlednou odpověď v češtině (nevolej již další nástroje).
        """.trimIndent()
    }
}
