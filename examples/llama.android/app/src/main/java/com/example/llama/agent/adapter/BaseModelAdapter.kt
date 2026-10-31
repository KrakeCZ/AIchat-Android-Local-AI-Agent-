package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher
import com.example.llama.agent.tools.workspace.RealtimeSystemContextTool

/**
 * Bázová implementace adaptéru s plnou sadou 14 nástrojů a časovým ukotvením (SOTA 2026).
 */
abstract class BaseModelAdapter : ModelFamilyAdapter {

    protected fun buildAnchorHeader(context: Context): String {
        val dateCzech = WebToolsDispatcher.getCzechCurrentDateTimeString()
        return "[Aktuální systémové datum a čas: $dateCzech]"
    }

    override fun extractToolCall(content: String, reasoning: String): ToolCallRequest? {
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
        return """
            <tool_response>
            [Výsledek nástroje: ${toolResponse.toolName}]
            ${toolResponse.content}
            </tool_response>
        """.trimIndent()
    }
}
