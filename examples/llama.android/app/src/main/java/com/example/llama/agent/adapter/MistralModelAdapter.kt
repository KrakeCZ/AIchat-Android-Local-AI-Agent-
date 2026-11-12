package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Adaptér pro rodinu modelů Mistral AI (Mistral 7B, Ministral 8B, Mixtral, Codestral).
 */
class MistralModelAdapter : BaseModelAdapter() {
    override val familyName: String = "Mistral"

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
}
