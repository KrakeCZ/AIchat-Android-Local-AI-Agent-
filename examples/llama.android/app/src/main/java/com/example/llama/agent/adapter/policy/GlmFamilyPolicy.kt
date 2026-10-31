package com.example.llama.agent.adapter.policy

import android.content.Context
import com.example.llama.agent.tools.web.WebToolsDispatcher

class GlmFamilyPolicy : FamilySemanticPolicy {
    override val familyName: String = "GLM"

    override fun buildSystemHeader(context: Context, userSystemMessage: String): String {
        val dateCzech = WebToolsDispatcher.getCzechCurrentDateTimeString()
        return buildString {
            if (userSystemMessage.isNotBlank()) {
                append(userSystemMessage.trim())
                append("\n\n")
            }
            append("[Aktuální datum a čas: $dateCzech]")
        }
    }
}
