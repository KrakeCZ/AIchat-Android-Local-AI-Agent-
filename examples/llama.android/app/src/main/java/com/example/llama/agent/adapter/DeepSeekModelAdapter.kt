package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Adaptér pro rodinu modelů DeepSeek (DeepSeek R1, DeepSeek V3).
 * Modely DeepSeek vyžadují zachování <think> myšlenkového proudu i v kroku formulace odpovědi po vykonání nástroje.
 */
class DeepSeekModelAdapter : BaseModelAdapter() {
    override val familyName: String = "DeepSeek"

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

    override fun shouldKeepThinkingOnToolResult(): Boolean = true
}
