package com.example.llama.agent.vision.tensor

import org.tensorflow.lite.Interpreter
import com.example.llama.agent.vision.models.TfliteVisionAnalyzer.VisionModelProfile
import com.example.llama.agent.vision.BoundingBoxView.Detection
import com.example.llama.agent.perception.DetectedEntity

/**
 * Enterprise kontrakt pro parsování výstupů (output tensors) z různých rodin
 * TFLite modelů bez memory leaků a OutOfMemoryError.
 */
interface TensorProfile {
    /**
     * Provede inferenci (model run) a následný post-processing.
     * Vrací seznam surových UI detekcí (Bounding Boxů) přímo k vykreslení, a zároveň
     * syrové entity, pokud existují, z nichž PerceptionEngine poskládá snapshot.
     */
    fun runAndParse(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        modelProfile: VisionModelProfile,
        resolveLabel: (Int) -> String
    ): Pair<List<DetectedEntity>, List<Detection>>
}
