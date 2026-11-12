package com.example.llama.agent.vision.tensor

import android.graphics.RectF
import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.perception.EntityCategory
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter
import kotlin.math.max

class YoloV8Profile(private val minConfidence: Float = 0.4f) : TensorProfile {
    private val TAG = "YoloV8Profile"

    private var outputBuffer: java.nio.ByteBuffer? = null
    private var lastNumChannels = -1
    private var lastNumAnchors = -1

    private var outputArray = FloatArray(0)

    // NMS pre-allocations (Zero-GC, dynamická velikost)
    private var MAX_CANDIDATES = 1000
    private var candidateCoords = FloatArray(MAX_CANDIDATES * 4) // [left, top, right, bottom]
    private var candidateScores = FloatArray(MAX_CANDIDATES)
    private var candidateClasses = IntArray(MAX_CANDIDATES)
    private var candidateActive = BooleanArray(MAX_CANDIDATES)
    private var candidateOrder = IntArray(MAX_CANDIDATES)

    override fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>> {
        val outputTensor = interpreter.getOutputTensor(0)
        val shape = outputTensor.shape() // Očekáváme [1, 84, 8400]

        // OCHRANA PROTI OOM: Zkontrolujeme, jestli tvar dává smysl
        if (shape.size != 3 || shape[1] < 4) {
            Log.e(TAG, "❌ Neplatný tenzor pro YOLOv8: ${shape.joinToString()}")
            return Pair(emptyList(), emptyList())
        }

        val numChannels = shape[1] // např. 84 (4 coords + 80 classes)
        val numAnchors = shape[2]  // např. 8400 kotev
        val outBytesPerItem = if (outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32) 4 else 1

        if (outputBuffer == null || lastNumChannels != numChannels || lastNumAnchors != numAnchors) {
            outputBuffer = java.nio.ByteBuffer.allocateDirect(1 * numChannels * numAnchors * outBytesPerItem)
            outputBuffer!!.order(java.nio.ByteOrder.nativeOrder())
            lastNumChannels = numChannels
            lastNumAnchors = numAnchors
            
            if (numAnchors > MAX_CANDIDATES) {
                MAX_CANDIDATES = numAnchors
                candidateCoords = FloatArray(MAX_CANDIDATES * 4)
                candidateScores = FloatArray(MAX_CANDIDATES)
                candidateClasses = IntArray(MAX_CANDIDATES)
                candidateActive = BooleanArray(MAX_CANDIDATES)
                candidateOrder = IntArray(MAX_CANDIDATES)
            }
        }

        try {
            interpreter.run(inputBuffer, outputBuffer!!)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při inferenci YOLOv8: ${e.message}", e)
            return Pair(emptyList(), emptyList())
        }

        outputBuffer!!.rewind()

        val isFloat = outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
        Log.d(TAG, "🔍 YOLOv8 Inference Complete: numChannels=$numChannels, numAnchors=$numAnchors, isFloat=$isFloat")

        val totalElements = numChannels * numAnchors
        if (outputArray.size != totalElements) {
            outputArray = FloatArray(totalElements)
        }
        
        if (isFloat) {
            outputBuffer!!.asFloatBuffer().get(outputArray)
        } else {
            val quantizationParams = outputTensor.quantizationParams()
            val scale = quantizationParams?.scale ?: 1.0f
            val zeroPoint = quantizationParams?.zeroPoint ?: 0
            
            for (i in 0 until (numChannels * numAnchors)) {
                val byteVal = outputBuffer!!.get().toInt() and 0xFF
                outputArray[i] = (byteVal - zeroPoint) * scale
            }
        }

        // Global Pixel Check Heuristic
        // YOLOv8 má rozložení [channels, anchors]. Kanál 0 a 2 jsou cx a w.
        var isPixels = false
        val sampleAnchor = numAnchors / 2
        val sampleCx = outputArray[0 * numAnchors + sampleAnchor]
        val sampleW = outputArray[2 * numAnchors + sampleAnchor]
        if (sampleCx > 2f || sampleW > 2f) {
            isPixels = true
        }

        var absoluteMaxScore = 0f
        var candidateCount = 0

        // YOLOv8 Memory Layout (Flattened): index = channel * numAnchors + anchor
        for (i in 0 until numAnchors) {
            var maxClassScore = 0f
            var classId = -1

            // Najdeme třídu s nejvyšším skóre pro danou kotvu
            val actualClasses = if (modelProfile.classCount > 0) modelProfile.classCount else (numChannels - 4)
            for (c in 0 until actualClasses) {
                val score = outputArray[(c + 4) * numAnchors + i]
                if (score > maxClassScore) {
                    maxClassScore = score
                    classId = c
                }
            }
            if (maxClassScore > absoluteMaxScore) absoluteMaxScore = maxClassScore

            // Pokud je skóre nad prahem, uložíme kandidáta do polí
            if (maxClassScore >= minConfidence && classId != -1) {
                if (candidateCount >= MAX_CANDIDATES) break

                val cx = outputArray[0 * numAnchors + i]
                val cy = outputArray[1 * numAnchors + i]
                val w = outputArray[2 * numAnchors + i]
                val h = outputArray[3 * numAnchors + i]

                // Normalizace na 0.0 - 1.0 pomocí globálního isPixels
                val iw = if (isPixels) modelProfile.inputWidth.toFloat() else 1f
                val ih = if (isPixels) modelProfile.inputHeight.toFloat() else 1f
                
                val left = ((cx - w / 2f) / iw).coerceIn(0f, 1f)
                val top = ((cy - h / 2f) / ih).coerceIn(0f, 1f)
                val right = ((cx + w / 2f) / iw).coerceIn(0f, 1f)
                val bottom = ((cy + h / 2f) / ih).coerceIn(0f, 1f)

                val idx = candidateCount
                candidateCoords[idx * 4 + 0] = left
                candidateCoords[idx * 4 + 1] = top
                candidateCoords[idx * 4 + 2] = right
                candidateCoords[idx * 4 + 3] = bottom
                candidateScores[idx] = maxClassScore
                candidateClasses[idx] = classId
                candidateActive[idx] = true
                candidateOrder[idx] = idx
                candidateCount++
            }
        }
        
        Log.d(TAG, "🔍 YOLOv8 Parse Done: Candidates=$candidateCount, Max Confidence: $absoluteMaxScore")
        
        // Zero-Alloc Sort (Insertion sort, fast for small N)
        for (i in 1 until candidateCount) {
            val keyIdx = candidateOrder[i]
            val keyScore = candidateScores[keyIdx]
            var j = i - 1
            while (j >= 0 && candidateScores[candidateOrder[j]] < keyScore) {
                candidateOrder[j + 1] = candidateOrder[j]
                j--
            }
            candidateOrder[j + 1] = keyIdx
        }

        // Zero-Alloc NMS (Non-Maximum Suppression)
        for (i in 0 until candidateCount) {
            val idx1 = candidateOrder[i]
            if (!candidateActive[idx1]) continue
            
            val l1 = candidateCoords[idx1 * 4 + 0]
            val t1 = candidateCoords[idx1 * 4 + 1]
            val r1 = candidateCoords[idx1 * 4 + 2]
            val b1 = candidateCoords[idx1 * 4 + 3]
            val area1 = (r1 - l1) * (b1 - t1)
            val c1 = candidateClasses[idx1]
            
            for (j in (i + 1) until candidateCount) {
                val idx2 = candidateOrder[j]
                if (!candidateActive[idx2] || c1 != candidateClasses[idx2]) continue
                
                val l2 = candidateCoords[idx2 * 4 + 0]
                val t2 = candidateCoords[idx2 * 4 + 1]
                val r2 = candidateCoords[idx2 * 4 + 2]
                val b2 = candidateCoords[idx2 * 4 + 3]
                
                val intersectL = max(l1, l2)
                val intersectT = max(t1, t2)
                val intersectR = kotlin.math.min(r1, r2)
                val intersectB = kotlin.math.min(b1, b2)
                
                if (intersectL < intersectR && intersectT < intersectB) {
                    val intersectArea = (intersectR - intersectL) * (intersectB - intersectT)
                    val area2 = (r2 - l2) * (b2 - t2)
                    val iou = intersectArea / (area1 + area2 - intersectArea)
                    
                    if (iou > 0.45f) { // NMS threshold
                        candidateActive[idx2] = false
                    }
                }
            }
        }

        // Final Object Creation (Only for surviving detections)
        val finalDetections = mutableListOf<Detection>()
        val finalEntities = mutableListOf<DetectedEntity>()
        
        for (i in 0 until candidateCount) {
            val idx = candidateOrder[i]
            if (!candidateActive[idx]) continue
            
            val classId = candidateClasses[idx]
            val labelName = resolveLabel(classId)
            val score = candidateScores[idx]
            val l = candidateCoords[idx * 4 + 0]
            val t = candidateCoords[idx * 4 + 1]
            val r = candidateCoords[idx * 4 + 2]
            val b = candidateCoords[idx * 4 + 3]
            
            finalDetections.add(
                Detection(
                    rect = RectF(l, t, r, b),
                    label = labelName,
                    confidence = score,
                    colorIndex = classId
                )
            )
            finalEntities.add(
                DetectedEntity(
                    label = labelName,
                    category = EntityCategory.OBJECT,
                    confidence = score,
                    boundingBox = com.example.llama.agent.perception.NormalizedBox(l, t, r, b)
                )
            )
        }

        Log.d(TAG, "🔍 YOLOv8 NMS Done: Final Detections=${finalDetections.size}")
        return Pair(finalEntities, finalDetections)
    }
}
