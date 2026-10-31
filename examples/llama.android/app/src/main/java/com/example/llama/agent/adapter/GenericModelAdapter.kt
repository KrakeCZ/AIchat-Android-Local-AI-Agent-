package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Univerzální fallback adaptér pro libovolný GGUF model bez specifického přizpůsobení.
 * Zajišťuje plný přístup ke všem 14 nástrojům, reálnému systémovému datu a času (2026).
 */
class GenericModelAdapter : BaseModelAdapter() {
    override val familyName: String = "Generic"

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
