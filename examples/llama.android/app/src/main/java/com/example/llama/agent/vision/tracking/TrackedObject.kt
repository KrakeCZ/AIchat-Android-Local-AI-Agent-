package com.example.llama.agent.vision.tracking

import android.graphics.RectF
import com.example.llama.agent.vision.tracking.math.MotionEstimator

/**
 * Záznam o sledovaném objektu v čase.
 */
class TrackedObject(
    val id: Int,
    var label: String,
    var confidence: Float,
    var rect: RectF,
    val color: Int,
    estimatorFactory: () -> MotionEstimator = { OD_KalmanFilter2D() }
) {
    // Fyzikální model pohybu (MotionEstimator abstrahovaný přes factory)
    val kalman: MotionEstimator = estimatorFactory().apply {
        initiate(rect)
    }
    
    var misses: Int = 0 // Kolik framů v řadě jsme objekt neviděli
    var hits: Int = 1   // Kolikrát byl objekt potvrzen (ochrana proti ghost detekcím)
    
    // Analytika (vektor pohybu extrahovaný z Kalmanova filtru)
    val velocityX: Float get() = kalman.velocityX
    val velocityY: Float get() = kalman.velocityY
    val velocityW: Float get() = kalman.velocityW
    val velocityH: Float get() = kalman.velocityH

    /** Updatuje fyzikální model přiřazenou novou detekcí */
    fun updateWithDetection(newRect: RectF, newConfidence: Float, timestampMs: Long) {
        kalman.update(newRect, newConfidence, timestampMs)
        kalman.getPredictedRect(rect)
        
        confidence = newConfidence
        hits++
        misses = 0
    }

    /** Predikce naslepo, pokud v tomto framu model objekt neviděl */
    fun predictBlind(timestampMs: Long) {
        kalman.predict(timestampMs)
        kalman.getPredictedRect(rect)
        misses++
        confidence = (confidence * 0.85f).coerceAtLeast(0f)
    }
}
