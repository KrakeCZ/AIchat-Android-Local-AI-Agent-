package com.example.llama.agent.adapter

import android.util.Log
import com.arm.aichat.NativeModelCapabilities
import com.example.llama.agent.adapter.policy.*
import com.example.llama.agent.adapter.template.*

/**
 * Selektivní metadatový router a továrna oddělující:
 * 1. TemplateProtocolModule (způsob komunikace a doručení nástrojů do enginu)
 * 2. FamilySemanticPolicy (specifika rodiny modelu, časové ukotvení bez prompt steeringu)
 */
object ModelAdapterFactory {
    private const val TAG = "ModelAdapterFactory"

    fun getAdapter(
        architecture: String? = null,
        modelPath: String? = null,
        handlerName: String? = null,
        chatTemplate: String? = null,
        capabilities: NativeModelCapabilities? = null
    ): ModelFamilyAdapter {
        val archLower = architecture?.lowercase() ?: ""
        val pathLower = modelPath?.lowercase() ?: ""
        val handlerLower = handlerName?.lowercase() ?: ""
        val tmplLower = chatTemplate?.lowercase() ?: ""

        // --- 1. Dimenze protokolu šablony (Template Protocol Module) ---
        val templateModule: TemplateProtocolModule = when {
            // A) Nativní Jinja2 nástroje v šabloně (Mistral, Qwen nativní, Hermes)
            (capabilities?.supportsTools == true && capabilities.supportsJinja) ||
                tmplLower.contains("for tool in tools") ||
                tmplLower.contains("[available_tools]") ||
                tmplLower.contains("<tool_call>") -> {
                Log.i(TAG, "🔧 Routing: Jinja2NativeToolsModule (native tool branching in template)")
                Jinja2NativeToolsModule()
            }

            // B) User-Injected modely (Gemma 2/3 - ignorují/odkládají standalone system role)
            archLower.contains("gemma") || pathLower.contains("gemma") || handlerLower.contains("gemma") -> {
                Log.i(TAG, "🔧 Routing: UserInjectedTemplateModule (standalone system role not supported natively)")
                UserInjectedTemplateModule()
            }

            // C) ChatML modely (<|im_start|>)
            tmplLower.contains("<|im_start|>") || handlerLower.contains("chatml") ||
                archLower.contains("qwen") || pathLower.contains("qwen") -> {
                Log.i(TAG, "🔧 Routing: ChatMlTemplateModule (ChatML protocol)")
                ChatMlTemplateModule()
            }

            // D) Jinja modely bez tool filtrů
            capabilities?.supportsJinja == true || tmplLower.contains("{%") -> {
                Log.i(TAG, "🔧 Routing: JinjaPromptInjectedModule")
                JinjaPromptInjectedModule()
            }

            // E) Legacy / Non-Jinja
            else -> {
                Log.i(TAG, "🔧 Routing: NonJinjaLegacyModule")
                NonJinjaLegacyModule()
            }
        }

        // --- 2. Dimenze rodinné sémantiky (Family Semantic Policy) ---
        val familyPolicy: FamilySemanticPolicy = when {
            archLower.contains("deepseek") || pathLower.contains("deepseek") -> {
                DeepSeekReasoningPolicy()
            }
            archLower.contains("gemma") || pathLower.contains("gemma") -> {
                GemmaFamilyPolicy()
            }
            archLower.contains("qwen") || pathLower.contains("qwen") ||
                pathLower.contains("hermes") || pathLower.contains("yi") -> {
                QwenFamilyPolicy()
            }
            archLower.contains("mistral") || archLower.contains("ministral") ||
                pathLower.contains("mistral") || pathLower.contains("ministral") -> {
                MistralFamilyPolicy()
            }
            archLower.contains("phi") || pathLower.contains("phi") -> {
                PhiFamilyPolicy()
            }
            archLower.contains("minicpm") || pathLower.contains("minicpm") -> {
                MiniCpmFamilyPolicy()
            }
            archLower.contains("glm") || pathLower.contains("glm") -> {
                GlmFamilyPolicy()
            }
            archLower.contains("llama") || pathLower.contains("llama") -> {
                LlamaFamilyPolicy()
            }
            handlerLower.contains("deepseek") -> DeepSeekReasoningPolicy()
            handlerLower.contains("gemma") -> GemmaFamilyPolicy()
            handlerLower.contains("qwen") -> QwenFamilyPolicy()
            handlerLower.contains("mistral") -> MistralFamilyPolicy()
            handlerLower.contains("phi") -> PhiFamilyPolicy()
            handlerLower.contains("llama") -> LlamaFamilyPolicy()
            else -> {
                GenericFamilyPolicy()
            }
        }

        val controller = ModelAgentController(
            templateModule = templateModule,
            familyPolicy = familyPolicy
        )

        Log.i(TAG, "✅ Composed Controller: ${controller.familyName} for arch='$architecture', path='$modelPath'")
        return controller
    }
}
