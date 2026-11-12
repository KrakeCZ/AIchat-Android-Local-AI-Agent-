package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Adaptér pro rodinu modelů Google Gemma (Gemma 2, Gemma 3).
 * Gemma nemá nativní Jinja tool template v C++, ale exceluje s čistým XML formátem <tools> a <tool_call>.
 */
class GemmaModelAdapter : BaseModelAdapter() {
    override val familyName: String = "Gemma"

    override fun buildSystemPrompt(
        context: Context,
        userSystemMessage: String,
        skillsInstruction: String,
        toolsEnabled: Boolean
    ): String {
        val anchor = buildAnchorHeader(context)

        return buildString {
            if (userSystemMessage.isNotBlank()) {
                append(userSystemMessage.trim())
                append("\n\n")
            }

            append(anchor)

            if (skillsInstruction.isNotBlank()) {
                append("\n\n")
                append(skillsInstruction.trim())
            }

            if (toolsEnabled && !skillsInstruction.contains("<tools>")) {
                append("\n\n")
                append(WebToolsDispatcher.TOOL_INSTRUCTIONS)
            }
        }.trim()
    }

    override fun formatToolResponse(
        userMsg: String,
        toolResponse: ToolCallResponse,
        currentDateTime: String
    ): String {
        val cleanTopic = WebToolsDispatcher.extractSearchQueryFromUserPrompt(userMsg).ifBlank { userMsg }
        return """
            [Výsledek nástroje: ${toolResponse.toolName} k tématu "$cleanTopic"]:
            ${toolResponse.content}
        """.trimIndent()
    }
}
