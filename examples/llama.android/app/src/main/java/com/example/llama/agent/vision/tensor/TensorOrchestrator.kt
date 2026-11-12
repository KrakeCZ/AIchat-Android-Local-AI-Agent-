package com.example.llama.agent.vision.tensor

import android.util.Log
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity
import org.tensorflow.lite.Interpreter

object TensorOrchestrator {
    private const val TAG = "TensorOrchestrator"

    /**
     * Zcela autonomní, heuristická analýza C++ tenzorů.
     * Nezajímá nás, co si uživatel myslí že nahrál, zajímá nás, co model fyzicky je.
     */
    fun getParser(interpreter: Interpreter, profile: VisionModelProfile, minConfidence: Float): TensorProfile {
        val outputCount = interpreter.outputTensorCount

        // --- 1. RODINA SSD (4 Výstupy: Boxy, Třídy, Skóre, Počet) ---
        if (outputCount == 4) {
            val shape0 = interpreter.getOutputTensor(0).shape()
            val shape1 = interpreter.getOutputTensor(1).shape()
            if (shape0.size == 3 && shape1.size == 2) {
                Log.i(TAG, "🔀 Detekována rodina: SSD / TFLite Object Detection API")
                return SsdProfile(minConfidence)
            }
        }

        // Pokud má model jen 1 výstupní tenzor, musíme analyzovat jeho dimenze
        val outShape = interpreter.getOutputTensor(0).shape()

        // --- 2. RODINA SEGMENTACE & DEPTH (3D nebo 4D Pixelová Mapa) ---
        if (outShape.size == 4 && outShape[1] > 32 && outShape[2] > 32) {
            Log.i(TAG, "🔀 Detekována rodina: Semantic Segmentation / Maska")
            return SegmentationProfile()
        }

        // --- 3. RODINA POSE ESTIMATION (MoveNet: [1, 1, 17, 3]) ---
        if (outShape.size == 4 && outShape[2] == 17 && outShape[3] == 3) {
            Log.i(TAG, "🔀 Detekována rodina: Pose Estimation (MoveNet)")
            return PoseEstimationProfile(minConfidence)
        }

        // --- 4. RODINA EMBEDDINGŮ (FaceNet, ReID) ---
        if (outShape.size == 2 && (outShape[1] == 128 || outShape[1] == 256 || outShape[1] == 512)) {
            Log.i(TAG, "🔀 Detekována rodina: Embeddings / Feature Matching")
            return EmbeddingProfile()
        }

        // --- 5. RODINA KLASIFIKÁTORŮ (ImageNet, [1, 1000]) ---
        if (outShape.size == 2 && outShape[1] >= 1000) {
            Log.i(TAG, "🔀 Detekována rodina: Image Classification")
            return ClassificationProfile(minConfidence)
        }

        // --- 6. 🔴 ZERO-GUESSING ARCHITECTURE (YOLO, DETR, BYOM) 🔴 ---
        // Prioritní směrování podle Manifestu nebo TFLite Metadat
        if (profile.explicitArchitecture != null && profile.explicitArchitecture != "LEGACY_COCO") {
            when (profile.explicitArchitecture) {
                "YOLOv11", "YOLOv10", "RT-DETR", "RF-DETR" -> {
                    Log.i(TAG, "🔀 Zero-Guessing: Směrování na EndToEndProfile (NMS-free) pro ${profile.explicitArchitecture}")
                    return EndToEndProfile(minConfidence)
                }
                "YOLOv9", "YOLOv8" -> {
                    Log.i(TAG, "🔀 Zero-Guessing: Směrování na YoloV8Profile pro ${profile.explicitArchitecture}")
                    return YoloV8Profile(minConfidence)
                }
                "YOLOv5", "YOLOv7" -> {
                    Log.i(TAG, "🔀 Zero-Guessing: Směrování na YoloV5Profile pro ${profile.explicitArchitecture}")
                    return YoloV5Profile(minConfidence)
                }
                else -> {
                    Log.w(TAG, "⚠️ Zero-Guessing: Architektura '${profile.explicitArchitecture}' nemá specifický profil. Zkusím fallback.")
                }
            }
        }

        // --- 7. FALLBACK: Staré hádání podle tvaru (pokud chybí metadata) ---
        if (outShape.size == 3) {
            val dim1 = outShape[1]
            val dim2 = outShape[2]

            if (dim1 <= 1000 && (dim2 == 6 || dim2 == 5 || dim2 == 7)) {
                Log.i(TAG, "🔀 Fallback Detekce: End-to-End NMS-free")
                return EndToEndProfile(minConfidence)
            }
            if (dim1 > dim2) {
                Log.i(TAG, "🔀 Fallback Detekce: Legacy YOLO (v5/v7)")
                return YoloV5Profile(minConfidence)
            } else {
                Log.i(TAG, "🔀 Fallback Detekce: Modern YOLO (v8+)")
                return YoloV8Profile(minConfidence)
            }
        }

        // Neznámý formát - fail safe
        Log.w(TAG, "⚠️ Tenzorový tvar ${outShape.contentToString()} nebyl rozpoznán. Použit DummyProfile.")
        return DummyProfile()
    }

    private class DummyProfile : TensorProfile {
        override fun runAndParse(
            interpreter: Interpreter,
            inputBuffer: java.nio.ByteBuffer,
            modelProfile: VisionModelProfile,
            resolveLabel: (Int) -> String
        ): Pair<List<DetectedEntity>, List<Detection>> {
            return Pair(emptyList(), emptyList())
        }
    }
}
