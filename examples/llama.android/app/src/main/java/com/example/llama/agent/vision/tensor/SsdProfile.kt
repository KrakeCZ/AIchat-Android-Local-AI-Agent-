package com.example.llama.agent.vision.tensor

import android.graphics.RectF
import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.perception.EntityCategory
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter

class SsdProfile(private val minConfidence: Float = 0.4f) : TensorProfile {
    private val TAG = "SsdProfile"

    private var outputLocations: java.nio.ByteBuffer? = null
    private var outputClasses: java.nio.ByteBuffer? = null
    private var outputScores: java.nio.ByteBuffer? = null
    private var numDetectionsBuffer: java.nio.ByteBuffer? = null
    private var outputMap: Map<Int, Any>? = null
    private var numDetections: Int = 0

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        // TFLite Object Detection API standardně vrací:
        // [0] Boxy [1, N, 4]
        // [1] Třídy [1, N]
        // [2] Skóre [1, N]
        // [3] Počet [1]
        
        if (outputMap == null) {
            val shapeBoxes = interpreter.getOutputTensor(0).shape()
            numDetections = shapeBoxes[1]

            outputLocations = java.nio.ByteBuffer.allocateDirect(1 * numDetections * 4 * 4).apply { order(java.nio.ByteOrder.nativeOrder()) }
            outputClasses = java.nio.ByteBuffer.allocateDirect(1 * numDetections * 4).apply { order(java.nio.ByteOrder.nativeOrder()) }
            outputScores = java.nio.ByteBuffer.allocateDirect(1 * numDetections * 4).apply { order(java.nio.ByteOrder.nativeOrder()) }
            numDetectionsBuffer = java.nio.ByteBuffer.allocateDirect(4).apply { order(java.nio.ByteOrder.nativeOrder()) }

            outputMap = mapOf(
                0 to outputLocations!!,
                1 to outputClasses!!,
                2 to outputScores!!,
                3 to numDetectionsBuffer!!
            )
        }

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap!!)
        } catch (e: Exception) {
            Log.e(TAG, "❌ OOM nebo chyba při inferenci SSD tenzorů: ${e.message}", e)
            return Pair(emptyList(), emptyList())
        }
        
        outputLocations!!.rewind()
        outputClasses!!.rewind()
        outputScores!!.rewind()
        numDetectionsBuffer!!.rewind()

        val uiDetections = mutableListOf<Detection>()
        val rawEntities = mutableListOf<DetectedEntity>()
        
        val actualDetections = numDetectionsBuffer!!.getFloat().toInt().coerceAtMost(numDetections)

        for (i in 0 until actualDetections) {
            val score = outputScores!!.float
            val classId = outputClasses!!.float.toInt()
            val yMin = outputLocations!!.float.coerceIn(0f, 1f)
            val xMin = outputLocations!!.float.coerceIn(0f, 1f)
            val yMax = outputLocations!!.float.coerceIn(0f, 1f)
            val xMax = outputLocations!!.float.coerceIn(0f, 1f)

            if (score >= minConfidence) {
                val labelName = resolveLabel(classId)
                
                uiDetections.add(
                    Detection(
                        rect = RectF(xMin, yMin, xMax, yMax),
                        label = labelName,
                        confidence = score,
                        colorIndex = classId
                    )
                )

                rawEntities.add(
                    DetectedEntity(
                        label = labelName,
                        category = EntityCategory.OBJECT,
                        confidence = score,
                        detectionCount = 1,
                        boundingBox = com.example.llama.agent.perception.NormalizedBox(xMin, yMin, xMax, yMax)
                    )
                )
            }
        }
        
        return Pair(rawEntities, uiDetections)
    }
}
