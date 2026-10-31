package com.example.llama.agent.vision.models

import androidx.annotation.Keep

/**
 * Striktní definice ML úlohy na základě matematického tvaru výstupního tenzoru.
 * Definuje, jaký Post-Processing se má spustit (BBox vs. Mask vs. Keypoints).
 */
@Keep
enum class VisionTaskType {
    OBJECT_DETECTION,       // YOLO, SSD -> Bounding Boxy
    SEMANTIC_SEGMENTATION,  // DeepLab, U-Net -> Pixelové Masky (Medical, Auto)
    DEPTH_ESTIMATION,       // MiDaS -> Grayscale hloubková mapa
    POSE_ESTIMATION,        // MoveNet -> Kostry a klouby (Security, Sport)
    CLASSIFICATION,         // ImageNet, Anomaly Detection -> Globální pravděpodobnost

    // NOVÉ - Expanze Sandboxu
    INSTANCE_SEGMENTATION,  // Mask R-CNN (Kombinace Boxu a Masky)
    FACE_LANDMARKS,         // 468/478 bodů
    ACTION_RECOGNITION,     // Časoprostorové tenzory [1, T, H, W, 3]
    IMAGE_TO_IMAGE,         // SuperResolution, Denoising
    VISION_LANGUAGE_MODEL,  // VLM (Obrázek dovnitř -> Text ven)
    UNKNOWN
}
