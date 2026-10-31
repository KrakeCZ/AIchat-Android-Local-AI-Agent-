package com.example.llama.agent.vision.tensor

import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.perception.EntityCategory
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter

class ClassificationProfile(private val minConfidence: Float = 0.4f) : TensorProfile {
    private val TAG = "ClassificationProfile"

    private var outputBuffer: java.nio.ByteBuffer? = null
    private var lastNumClasses = -1

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()

        if (shape.size != 2) {
            Log.e(TAG, "❌ Neplatný tenzor pro klasifikaci: ${shape.joinToString()}")
            return Pair(emptyList(), emptyList())
        }

        val numClasses = shape[1]
        val isFloatOut = outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
        val outBytesPerItem = if (isFloatOut) 4 else 1

        if (outputBuffer == null || lastNumClasses != numClasses) {
            outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * numClasses * outBytesPerItem)
            outputBuffer!!.order(java.nio.ByteOrder.nativeOrder())
            lastNumClasses = numClasses
        }

        try {
            interpreter.run(inputBuffer, outputBuffer!!)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při inferenci Classification tenzoru: ${e.message}", e)
            return Pair(emptyList(), emptyList())
        }
        
        outputBuffer!!.rewind()

        var maxScore = 0f
        var maxId = -1

        for (i in 0 until numClasses) {
            val score = if (isFloatOut) outputBuffer!!.float else (outputBuffer!!.get().toInt() and 0xFF) / 255.0f
            if (score > maxScore) {
                maxScore = score
                maxId = i
            }
        }

        val rawEntities = mutableListOf<DetectedEntity>()
        
        if (maxScore >= minConfidence && maxId != -1) {
            val labelName = resolveLabel(maxId)
            rawEntities.add(
                DetectedEntity(
                    label = labelName,
                    category = EntityCategory.OBJECT,
                    confidence = maxScore,
                    detectionCount = 1
                )
            )
        }

        // Klasifikátor nemá lokaci, takže nevracíme žádné BoundingBoxy (uiDetections)
        return Pair(rawEntities, emptyList())
    }
}
