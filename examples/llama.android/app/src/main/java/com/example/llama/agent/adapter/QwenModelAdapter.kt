package com.example.llama.agent.adapter

import android.content.Context
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Adaptér pro rodinu modelů Qwen (Qwen 2, Qwen 2.5, Qwen 3.5, Hermes, Yi).
 * Qwen vyniká ve strukturovaném volání funkcí pomocí <tool_call> XML bloků.
 */
class QwenModelAdapter : BaseModelAdapter() {
    override val familyName: String = "Qwen"

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
