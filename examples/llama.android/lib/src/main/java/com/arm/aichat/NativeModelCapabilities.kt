package com.arm.aichat

/**
 * Diagnostic model capabilities reported natively by the llama.cpp chat template engine.
 */
data class NativeModelCapabilities(
    val hasChatTemplate: Boolean,
    val supportsJinja: Boolean,
    val supportsThinking: Boolean,
    val supportsTools: Boolean,
    val supportsToolCalls: Boolean,
    val detectedFormat: Int,
    val handlerName: String
) {
    val hasToolUseTemplate: Boolean
        get() = supportsTools && supportsToolCalls
}
