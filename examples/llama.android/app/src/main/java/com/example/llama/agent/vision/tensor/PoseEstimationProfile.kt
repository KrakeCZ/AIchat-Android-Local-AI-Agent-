package com.example.llama.agent.vision.tensor

import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter

class PoseEstimationProfile(private val minConfidence: Float) : TensorProfile {
    private val TAG = "PoseEstimationProfile"

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        Log.w(TAG, "Zde dojde k parsování Kostry a emitaci do EventBusu. Boxy se nevrací.")
        return Pair(emptyList(), emptyList())
    }
}
