package com.example.llama

import org.junit.Test
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig

class SherpaTest {
    @Test
    fun testSherpaConfigs() {
        println("Whisper Config Fields: " + OfflineWhisperModelConfig::class.java.declaredFields.map { it.name })
        println("SenseVoice Config Fields: " + OfflineSenseVoiceModelConfig::class.java.declaredFields.map { it.name })
    }
}
