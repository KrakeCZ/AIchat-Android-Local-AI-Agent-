package com.example.llama.agent.vision.tensor

import android.graphics.RectF
import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.perception.EntityCategory
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter
import kotlin.math.max

/**
 * Zpracovává SOTA modely roku 2026/2027 s End-to-End architekturou bez NMS.
 * Například: YOLOv10, RT-DETR, YOLO11 (NMS-exported).
 * Očekávaný výstupní tvar: [1, num_detections, 6] nebo [1, num_detections, 5]
 * Format typicky: [left, top, right, bottom, confidence, classId]
 */
class EndToEndProfile(private val minConfidence: Float = 0.4f) : TensorProfile {
    private val TAG = "EndToEndProfile"

    private var outputBuffer: java.nio.ByteBuffer? = null
    private var lastNumDetections = -1
    private var lastElementsPerDetection = -1

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape()

        if (shape.size != 3) {
            Log.e(TAG, "❌ Neplatný tenzor pro End-to-End: ${shape.joinToString()}")
            return Pair(emptyList(), emptyList())
        }

        val numDetections = shape[1] // např. 300
        val elementsPerDetection = shape[2] // např. 6 (x1, y1, x2, y2, conf, cls)
        
        val isFloatOut = outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
        val outBytesPerItem = if (isFloatOut) 4 else 1

        if (outputBuffer == null || lastNumDetections != numDetections || lastElementsPerDetection != elementsPerDetection) {
            outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * numDetections * elementsPerDetection * outBytesPerItem)
            outputBuffer!!.order(java.nio.ByteOrder.nativeOrder())
            lastNumDetections = numDetections
            lastElementsPerDetection = elementsPerDetection
        }

        try {
            interpreter.run(inputBuffer, outputBuffer!!)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při inferenci End-to-End modelu: ${e.message}", e)
            return Pair(emptyList(), emptyList())
        }

        outputBuffer!!.rewind()
        val outputArray = FloatArray(numDetections * elementsPerDetection)
        outputBuffer!!.asFloatBuffer().get(outputArray)

        val uiDetections = mutableListOf<Detection>()
        val rawEntities = mutableListOf<DetectedEntity>()

        for (i in 0 until numDetections) {
            val baseIndex = i * elementsPerDetection
            
            // Standardní mapování pro YOLOv10 / RT-DETR je [x1, y1, x2, y2, score, classId]
            // nebo [cx, cy, w, h, score, classId] záleží na exportu. Zkusíme x1, y1, x2, y2 jako základ.
            val v0 = outputArray[baseIndex + 0]
            val v1 = outputArray[baseIndex + 1]
            val v2 = outputArray[baseIndex + 2]
            val v3 = outputArray[baseIndex + 3]
            val score = outputArray[baseIndex + 4]
            val classIdFloat = if (elementsPerDetection > 5) outputArray[baseIndex + 5] else 0f
            
            val classId = classIdFloat.toInt()

            if (score >= minConfidence && classId >= 0) {
                // Zkontrolujeme, zda jde o [cx, cy, w, h] nebo [left, top, right, bottom]
                // Většinou end2end exporty rovnou hazejí box souřadnice (x1, y1, x2, y2)
                
                val iw = if (v0 > 2f || v2 > 2f) modelProfile.inputWidth.toFloat() else 1f
                val ih = if (v1 > 2f || v3 > 2f) modelProfile.inputHeight.toFloat() else 1f

                val left = (v0 / iw).coerceIn(0f, 1f)
                val top = (v1 / ih).coerceIn(0f, 1f)
                val right = (v2 / iw).coerceIn(0f, 1f)
                val bottom = (v3 / ih).coerceIn(0f, 1f)

                val labelName = resolveLabel(classId)
                val safeColorIndex = max(0, classId)

                uiDetections.add(
                    Detection(
                        rect = RectF(left, top, right, bottom),
                        label = labelName,
                        confidence = score,
                        colorIndex = safeColorIndex
                    )
                )

                rawEntities.add(
                    DetectedEntity(
                        label = labelName,
                        category = EntityCategory.OBJECT,
                        confidence = score,
                        detectionCount = 1,
                        boundingBox = com.example.llama.agent.perception.NormalizedBox(left, top, right, bottom)
                    )
                )
            }
        }

        return Pair(rawEntities, uiDetections)
    }
}
