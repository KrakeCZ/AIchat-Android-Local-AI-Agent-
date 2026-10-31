package com.example.llama.agent.vision.tracking.math

/**
 * Tovární nastavení profilů matematických odhadů.
 * Reprezentuje přesnou extrakci "hardcoded" hodnot ze staré verze kódové základny,
 * aby byla garantována 100% zpětná kompatibilita (cross-compatibility) před abstrahováním API.
 */
object DefaultEstimatorProfiles {
    
    val LINEAR_DEFAULT = EstimatorParameters(
        processNoise = 0.001f,
        measurementNoise = 0.02f,
        stdWeightPosition = 0.05f,       // Fallback pro abstrakci
        stdWeightVelocity = 0.00625f,    // Fallback pro abstrakci
        minAspectRatio = 0.1f,
        minHeight = 0.005f
    )
    
    val DYNAMIC_DEFAULT = EstimatorParameters(
        processNoise = 0.001f,           // Fallback pro abstrakci
        measurementNoise = 0.02f,        // Fallback pro abstrakci
        stdWeightPosition = 0.05f,       // Vytaženo z DynamicKalmanFilter2D
        stdWeightVelocity = 0.00625f,    // Vytaženo z DynamicKalmanFilter2D
        minAspectRatio = 0.1f,
        minHeight = 0.005f
    )
}
