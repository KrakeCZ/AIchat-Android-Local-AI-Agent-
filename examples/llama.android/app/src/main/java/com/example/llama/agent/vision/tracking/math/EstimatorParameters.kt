package com.example.llama.agent.vision.tracking.math

/**
 * Zapouzdření parametrů pro Kalmanovy filtry a estimátory pohybu.
 * Odděluje konfigurační hodnoty od matematického jádra pro možnost Hot-Swap modifikace v UI.
 */
data class EstimatorParameters(
    // Pro OD_KalmanFilter2D (Lineární)
    val processNoise: Float,
    val measurementNoise: Float,
    
    // Pro DynamicKalmanFilter2D (Adaptivní šum)
    val stdWeightPosition: Float,
    val stdWeightVelocity: Float,
    
    // Ochranné limity rozměrů (pro normalizovaný souřadnicový systém 0.0 - 1.0)
    val minAspectRatio: Float = 0.1f,
    val minHeight: Float = 0.005f
)
