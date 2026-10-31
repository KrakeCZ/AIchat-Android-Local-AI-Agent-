package com.arm.aichat

/**
 * Profiles controlling reasoning / thinking depth for Chain-of-Thought (CoT) models
 * (e.g. Qwen 2.5/3.5, DeepSeek R1).
 *
 * maxTokens = -1 or 0 with isEnabled=true signifies unlimited budget (-1 in upstream llama.cpp sampler),
 * letting the model think completely freely and naturally without any forced token cutoff.
 */
enum class ThinkingProfile(
    val id: Int,
    val maxTokens: Int,
    val effort: String,
    val displayName: String
) {
    OEM(4, -1, "", "OEM"),
    OFF(0, 0, "none", "Off"),
    LOW(1, 512, "low", "512"),
    MEDIUM(2, 2048, "medium", "2k"),
    DEEP(3, -1, "high", "Deep");

    val isEnabled: Boolean
        get() = this != OFF

    companion object {
        fun fromId(id: Int): ThinkingProfile = values().firstOrNull { it.id == id } ?: MEDIUM
    }
}
