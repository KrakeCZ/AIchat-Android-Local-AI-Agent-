package com.example.llama.agent.ui.studio.genstudio.engine

import androidx.annotation.Keep
import org.json.JSONObject

/**
 * Dynamic Hardware & SoC Capability Profile for Edge Video Diffusion.
 * Detects Qualcomm Snapdragon 8 Gen 2-5, Google Tensor G3-G5, and generic ARM64.
 */
@Keep
data class VideoGenHardwareProfile(
    val chipsetName: String,
    val totalRamMb: Int,
    val recommendedWidth: Int,
    val recommendedHeight: Int,
    val recommendedFrames: Int,
    val recommendedSteps: Int,
    val threads: Int,
    val enableTeaCache: Boolean,
    val cooldownMs: Int
) {
    val displayBadge: String
        get() = buildString {
            if (chipsetName.contains("Snapdragon", ignoreCase = true)) {
                append("⚡ Snapdragon ")
                if (chipsetName.contains("Gen 3", ignoreCase = true)) append("8 Gen 3")
                else if (chipsetName.contains("Gen 2", ignoreCase = true)) append("8 Gen 2")
                else if (chipsetName.contains("Elite", ignoreCase = true)) append("8 Elite")
                else append("Core")
            } else if (chipsetName.contains("Tensor", ignoreCase = true)) {
                append("💠 Google Tensor Core")
            } else {
                append("📱 ARM64 SoC")
            }
            append(" • ${(totalRamMb / 1024.0).toInt()}GB RAM")
            append(" • ${recommendedWidth}x${recommendedHeight}")
            if (enableTeaCache) append(" • TeaCache 2x")
        }

    val isSnapdragon: Boolean
        get() = chipsetName.contains("Snapdragon", ignoreCase = true) || chipsetName.contains("Qualcomm", ignoreCase = true)

    val isGoogleTensor: Boolean
        get() = chipsetName.contains("Tensor", ignoreCase = true) || chipsetName.contains("Google", ignoreCase = true)

    val hasQualcommNpu: Boolean
        get() {
            if (!isSnapdragon) return false
            return try {
                val qnnHtp = java.io.File("/vendor/lib64/libQnnHtp.so").exists() ||
                             java.io.File("/vendor/lib64/libQnnHtpV75Stub.so").exists() ||
                             java.io.File("/system/vendor/lib64/libQnnHtp.so").exists()
                qnnHtp || chipsetName.contains("8 Gen", ignoreCase = true) || chipsetName.contains("Elite", ignoreCase = true)
            } catch (e: Exception) {
                isSnapdragon
            }
        }

    companion object {
        fun fromJson(jsonStr: String): VideoGenHardwareProfile {
            return try {
                val obj = JSONObject(jsonStr)
                VideoGenHardwareProfile(
                    chipsetName = obj.optString("chipset", "ARM64 Mobile Platform"),
                    totalRamMb = obj.optInt("totalRamMb", 12288),
                    recommendedWidth = obj.optInt("recommendedWidth", 384),
                    recommendedHeight = obj.optInt("recommendedHeight", 256),
                    recommendedFrames = obj.optInt("recommendedFrames", 17),
                    recommendedSteps = obj.optInt("recommendedSteps", 12),
                    threads = obj.optInt("threads", 4),
                    enableTeaCache = obj.optBoolean("teaCache", true),
                    cooldownMs = obj.optInt("cooldownMs", 35)
                )
            } catch (e: Exception) {
                // Safe default profile for S24 Ultra class hardware
                VideoGenHardwareProfile(
                    chipsetName = "Qualcomm Snapdragon 8 Gen 3 (S24 Ultra / 12GB)",
                    totalRamMb = 12288,
                    recommendedWidth = 384,
                    recommendedHeight = 256,
                    recommendedFrames = 17,
                    recommendedSteps = 12,
                    threads = 4,
                    enableTeaCache = true,
                    cooldownMs = 35
                )
            }
        }
    }
}
