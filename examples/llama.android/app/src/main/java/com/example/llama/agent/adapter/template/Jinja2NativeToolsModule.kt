package com.example.llama.agent.adapter.template

import com.arm.aichat.InferenceEngine
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Modul pro modely s plnohodnotnou Jinja2 šablonou obsahující nativní tool support (Mistral, Qwen native, Hermes).
 * Nástroje jsou registrovány přímo v C++ jádru llama.cpp, kde je Jinja šablona zformátuje nativně.
 */
class Jinja2NativeToolsModule : TemplateProtocolModule {
    override val type: TemplateType = TemplateType.JINJA2_NATIVE_TOOLS

    override fun configureEngine(engine: InferenceEngine, toolsEnabled: Boolean, toolsJson: String) {
        if (toolsEnabled && toolsJson.isNotBlank()) {
            engine.setToolDefinitions(toolsJson)
            engine.setToolPolicy(0) // AUTO - nativní Jinja nástroje
        } else {
            engine.setToolDefinitions("")
            engine.setToolPolicy(2) // NONE
        }
    }

    override fun prepareUserPrompt(userMsg: String, systemPrompt: String, isFirstTurn: Boolean): String {
        // Nativní Jinja model má systémový kontext v KV cache přes setSystemPrompt, posílá se čistý userMsg
        return userMsg
    }

    override fun extractToolCall(
        content: String,
        reasoning: String,
        nativeToolName: String?,
        nativeToolArgs: String?
    ): ToolCallRequest? {
        // 1. Nativní eventy z Jinja parseru
        if (!nativeToolName.isNullOrBlank()) {
            val args = WebToolsDispatcher.parseArguments(nativeToolArgs ?: "")
            return ToolCallRequest(nativeToolName, args)
        }
        // 2. Extrakce z contentu
        val fromContent = WebToolsDispatcher.findToolCallInText(content)
        if (fromContent != null) return fromContent

        // 3. Extrakce z uvažovacího proudu (<think>)
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
            [Nástroj: ${toolResponse.toolName} | Aktuální čas: $currentDateTime]
            ${toolResponse.content}
            </tool_response>

            Původní dotaz uživatele: "$userMsg" (s českou diakritikou: "$normQuery").
            Upozornění: Uživatel psal bez háčků a čárek (např. "Decin" = město Děčín, "pocasi" = počasí).
            Na základě výše uvedených ověřených informací a souhrnu z webu od Hledacího Bota napiš přímou, věcnou a přehlednou odpověď v češtině (nevolej již další nástroje).
        """.trimIndent()
    }
}
