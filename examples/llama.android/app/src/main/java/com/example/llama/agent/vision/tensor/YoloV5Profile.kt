package com.example.llama.agent.vision.tensor

import android.util.Log
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import org.tensorflow.lite.Interpreter

class YoloV5Profile(private val minConfidence: Float = 0.4f) : TensorProfile {
    private val TAG = "YoloV5Profile"

    private val outputBuffers = mutableMapOf<Int, java.nio.ByteBuffer>()
    private val expectedCapacities = mutableMapOf<Int, Int>()

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
        var targetOutputIndex = -1
        var numAnchors = 0
        var numChannels = 0
        var numClasses = 0
        
        val outputCount = interpreter.outputTensorCount
        val outputMap = mutableMapOf<Int, Any>()
        
        // Najdeme tenzor, který odpovídá YOLOv5 výstupu [1, anchors, 85]
        for (i in 0 until outputCount) {
            val t = interpreter.getOutputTensor(i)
            val s = t.shape()
            val outBytesPerItem = if (t.dataType() == org.tensorflow.lite.DataType.FLOAT32) 4 else 1
            
            var capacity = 1
            for (dim in s) capacity *= dim
            val requiredBytes = capacity * outBytesPerItem

            var buffer = outputBuffers[i]
            if (buffer == null || expectedCapacities[i] != requiredBytes) {
                buffer = java.nio.ByteBuffer.allocateDirect(requiredBytes)
                buffer.order(java.nio.ByteOrder.nativeOrder())
                outputBuffers[i] = buffer
                expectedCapacities[i] = requiredBytes
            }
            outputMap[i] = buffer
            
            if (s.size == 3 && targetOutputIndex == -1) {
                val dim1 = s[1]
                val dim2 = s[2]
                if (dim1 > dim2 && dim2 >= 5) {
                    targetOutputIndex = i
                    numAnchors = dim1
                    numChannels = dim2
                    numClasses = if (modelProfile.classCount > 0) modelProfile.classCount else (dim2 - 5)
                    
                    if (numAnchors > MAX_CANDIDATES) {
                        MAX_CANDIDATES = numAnchors
                        candidateCoords = FloatArray(MAX_CANDIDATES * 4)
                        candidateScores = FloatArray(MAX_CANDIDATES)
                        candidateClasses = IntArray(MAX_CANDIDATES)
                        candidateActive = BooleanArray(MAX_CANDIDATES)
                        candidateOrder = IntArray(MAX_CANDIDATES)
                    }
                }
            }
        }

        if (targetOutputIndex == -1) {
            Log.e(TAG, "❌ Nebyl nalezen vhodný výstupní tenzor pro YOLOv5.")
            return Pair(emptyList(), emptyList())
        }

        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputMap)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při inferenci YOLOv5: ${e.message}", e)
            return Pair(emptyList(), emptyList())
        }

        val outputBuffer = outputMap[targetOutputIndex] as java.nio.ByteBuffer
        outputBuffer.rewind()
        
        val outputTensor = interpreter.getOutputTensor(targetOutputIndex)

        val isFloat = outputTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
        Log.d(TAG, "🔍 YOLOv5 Inference: anchors=$numAnchors, channels=$numChannels, isFloat=$isFloat")

        val outputArray = FloatArray(numAnchors * numChannels)
        if (isFloat) {
            outputBuffer.asFloatBuffer().get(outputArray)
        } else {
            val quantizationParams = outputTensor.quantizationParams()
            val scale = quantizationParams?.scale ?: 1.0f
            val zeroPoint = quantizationParams?.zeroPoint ?: 0
            for (i in 0 until (numAnchors * numChannels)) {
                val byteVal = outputBuffer.get().toInt() and 0xFF
                outputArray[i] = (byteVal - zeroPoint) * scale
            }
        }

        // Global Pixel Check Heuristic
        // Skenujeme prvních max. 500 kotev, abychom bezpečně zjistili, zda je model v pixelech.
        var isPixels = false
        val scanCount = Math.min(numAnchors, 500)
        for (i in 0 until scanCount) {
            val offset = i * numChannels
            // Pokud cx, cy, w nebo h přesáhne hodnotu 2.0f, jsme s jistotou v pixelových hodnotách
            if (outputArray[offset] > 2f || outputArray[offset + 1] > 2f ||
                outputArray[offset + 2] > 2f || outputArray[offset + 3] > 2f) {
                isPixels = true
                break
            }
        }

        var candidateCount = 0
        var absoluteMaxScore = 0f

        // YOLOv5 Layout: [anchor][channel] => x, y, w, h, obj_conf, class_confs...
        for (i in 0 until numAnchors) {
            val offset = i * numChannels
            val objConf = outputArray[offset + 4]

            if (objConf < minConfidence) continue

            var maxClassConf = 0f
            var classId = -1

            for (c in 0 until numClasses) {
                val classConf = outputArray[offset + 5 + c]
                if (classConf > maxClassConf) {
                    maxClassConf = classConf
                    classId = c
                }
            }

            val finalScore = objConf * maxClassConf
            if (finalScore > absoluteMaxScore) absoluteMaxScore = finalScore

            if (finalScore >= minConfidence && classId != -1) {
                if (candidateCount >= MAX_CANDIDATES) break

                val cx = outputArray[offset + 0]
                val cy = outputArray[offset + 1]
                val w = outputArray[offset + 2]
                val h = outputArray[offset + 3]

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
                candidateScores[idx] = finalScore
                candidateClasses[idx] = classId
                candidateActive[idx] = true
                candidateOrder[idx] = idx
                candidateCount++
            }
        }

        Log.d(TAG, "🔍 YOLOv5 Parse Done: Candidates=$candidateCount, Max Confidence: $absoluteMaxScore")

        // Zero-Alloc Sort
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

        // Zero-Alloc NMS
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
                
                val intersectL = kotlin.math.max(l1, l2)
                val intersectT = kotlin.math.max(t1, t2)
                val intersectR = kotlin.math.min(r1, r2)
                val intersectB = kotlin.math.min(b1, b2)
                
                if (intersectL < intersectR && intersectT < intersectB) {
                    val intersectArea = (intersectR - intersectL) * (intersectB - intersectT)
                    val area2 = (r2 - l2) * (b2 - t2)
                    val iou = intersectArea / (area1 + area2 - intersectArea)
                    
                    if (iou > 0.45f) {
                        candidateActive[idx2] = false
                    }
                }
            }
        }

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
                    rect = android.graphics.RectF(l, t, r, b),
                    label = labelName,
                    confidence = score,
                    colorIndex = classId
                )
            )
            finalEntities.add(
                DetectedEntity(
                    label = labelName,
                    category = com.example.llama.agent.perception.EntityCategory.OBJECT,
                    confidence = score,
                    boundingBox = com.example.llama.agent.perception.NormalizedBox(l, t, r, b)
                )
            )
        }

        Log.d(TAG, "🔍 YOLOv5 NMS Done: Final Detections=${finalDetections.size}")
        return Pair(finalEntities, finalDetections)
    }
}
