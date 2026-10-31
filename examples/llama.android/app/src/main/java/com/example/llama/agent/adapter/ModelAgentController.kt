package com.example.llama.agent.adapter

import android.content.Context
import com.arm.aichat.InferenceEngine
import com.example.llama.agent.adapter.policy.FamilySemanticPolicy
import com.example.llama.agent.adapter.template.TemplateProtocolModule
import com.example.llama.agent.adapter.template.TemplateType
import com.example.llama.agent.tools.web.ToolCallRequest
import com.example.llama.agent.tools.web.ToolCallResponse
import com.example.llama.agent.tools.web.WebToolsDispatcher

/**
 * Modulární kontroler kombinující nezávislý protokol šablony (TemplateProtocolModule)
 * a sémantickou politiku rodiny (FamilySemanticPolicy).
 *
 * Implementuje ModelFamilyAdapter pro 100% kompatibilitu s orchestrátorem a UI.
 */
class ModelAgentController(
    val templateModule: TemplateProtocolModule,
    val familyPolicy: FamilySemanticPolicy
) : ModelFamilyAdapter {

    override val familyName: String
        get() = "${familyPolicy.familyName} (${templateModule.type})"

    fun configureEngine(engine: InferenceEngine, toolsEnabled: Boolean, toolsJson: String = WebToolsDispatcher.getToolsJson()) {
        templateModule.configureEngine(engine, toolsEnabled, toolsJson)
    }

    fun prepareUserPrompt(userMsg: String, systemPrompt: String, isFirstTurn: Boolean): String {
        return templateModule.prepareUserPrompt(userMsg, systemPrompt, isFirstTurn)
    }

    override fun buildSystemPrompt(
        context: Context,
        userSystemMessage: String,
        skillsInstruction: String,
        toolsEnabled: Boolean
    ): String {
        val cleanUserSys = userSystemMessage.trim()
        val header = familyPolicy.buildSystemHeader(context, cleanUserSys)
        return buildString {
            append(header)
            if (skillsInstruction.isNotBlank()) {
                append("\n\n")
                append(skillsInstruction.trim())
            }
            if (toolsEnabled && !skillsInstruction.contains("<tools>")) {
                append("\n\n")
                append(familyPolicy.getToolInstructions())
            }
            if (cleanUserSys.isNotBlank() && (toolsEnabled || skillsInstruction.isNotBlank())) {
                append("\n\n")
                append("# Prioritní Systémová Instrukce Uživatele (System Prompt)\n")
                append("Při každé odpovědi i po použití nástrojů vždy striktně dodržuj toto nastavení uživatele:\n")
                append(cleanUserSys)
            }
        }.trim()
    }

    override fun extractToolCall(content: String, reasoning: String): ToolCallRequest? {
        return templateModule.extractToolCall(content, reasoning, null, null)
    }

    fun extractToolCallEx(
        content: String,
        reasoning: String,
        nativeToolName: String?,
        nativeToolArgs: String?
    ): ToolCallRequest? {
        return templateModule.extractToolCall(content, reasoning, nativeToolName, nativeToolArgs)
    }

    override fun formatToolResponse(
        userMsg: String,
        toolResponse: ToolCallResponse,
        currentDateTime: String
    ): String {
        return templateModule.formatToolResponse(userMsg, toolResponse, currentDateTime)
    }

    /**
     * STRIKTNÍ OCHRANA MYŠLENKOVÉHO PROUDU:
     * Všechny modely s uvažováním (reasoning/thinking) si MUSÍ ponechat myšlenkový proces
     * aktivní i po vykonání nástroje. Žádný model nesmí být zbaven uvažování během syntézy!
     */
    override fun shouldKeepThinkingOnToolResult(): Boolean = true
}
