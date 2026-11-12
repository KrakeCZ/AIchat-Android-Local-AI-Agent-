package com.arm.aichat

/**
 * Reason why the generation ended.
 */
enum class FinishReason(val code: Int) {
    EOG(0),
    LENGTH(1),
    CONTEXT_FULL(2),
    CANCELLED(3),
    ERROR(4);

    companion object {
        fun fromCode(code: Int): FinishReason = values().firstOrNull { it.code == code } ?: EOG
    }
}

/**
 * Structured semantic events emitted during incremental generation.
 * Separates model internal reasoning (CoT) from final content and tool calls.
 */
sealed interface GenerationEvent {
    val generationId: Long
    val sequence: Long

    /** Reasoning / Chain-of-Thought chunk (<think> content) */
    data class ReasoningDelta(
        override val generationId: Long,
        override val sequence: Long,
        val text: String
    ) : GenerationEvent

    /** Final user-facing message chunk (outside <think>) */
    data class ContentDelta(
        override val generationId: Long,
        override val sequence: Long,
        val text: String
    ) : GenerationEvent

    /** Structured tool call parameter delta */
    data class ToolCallDelta(
        override val generationId: Long,
        override val sequence: Long,
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String
    ) : GenerationEvent

    /** Terminal event indicating generation completion with metrics */
    data class Finished(
        override val generationId: Long,
        override val sequence: Long,
        val reason: FinishReason,
        val promptTokens: Int,
        val generatedTokens: Int
    ) : GenerationEvent

    /** Error event during prompt processing or generation */
    data class Error(
        override val generationId: Long,
        override val sequence: Long,
        val message: String
    ) : GenerationEvent
}

/**
 * Low-level carrier class used across JNI boundary for efficient batch transfer.
 */
class NativeGenerationEvent(
    val generationId: Long,
    val sequence: Long,
    val type: Int,
    val text: String?,
    val toolIndex: Int,
    val toolId: String?,
    val toolName: String?,
    val toolArgsDelta: String?,
    val finishReason: Int,
    val promptTokens: Int,
    val generatedTokens: Int
) {
    fun toDomainEvent(): GenerationEvent {
        val safeText = text ?: ""
        return when (type) {
            0 -> GenerationEvent.ReasoningDelta(generationId, sequence, safeText)
            1 -> GenerationEvent.ContentDelta(generationId, sequence, safeText)
            2 -> GenerationEvent.ToolCallDelta(
                generationId,
                sequence,
                toolIndex,
                toolId,
                toolName,
                toolArgsDelta ?: ""
            )
            3 -> GenerationEvent.Finished(
                generationId,
                sequence,
                FinishReason.fromCode(finishReason),
                promptTokens,
                generatedTokens
            )
            else -> GenerationEvent.Error(generationId, sequence, text ?: "Native error occurred")
        }
    }
}
