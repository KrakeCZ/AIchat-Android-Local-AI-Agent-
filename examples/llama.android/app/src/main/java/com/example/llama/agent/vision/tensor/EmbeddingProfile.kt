package com.example.llama.agent.vision.tensor

import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter

class EmbeddingProfile : TensorProfile {
    private val TAG = "EmbeddingProfile"

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        Log.w(TAG, "Embedding Profile: Extrahované features se nehodí pro detekci boxů.")
        return Pair(emptyList(), emptyList())
    }
}
