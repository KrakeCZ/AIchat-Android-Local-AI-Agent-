package com.example.llama.agent.irismotion.expressions

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1D One Euro Filter optimalizovaný pro filtrování jedné blendshape hodnoty (např. smileScore).
 * Zcela oddělený od Gaze stabilizace.
 */
class ScalarOneEuroFilter(
    private var minCutoff: Float = 1.0f,
    private var beta: Float = 0.0f,
    private var dCutoff: Float = 1.0f
) {
    private var xHat: Float = 0f
    private var dxHat: Float = 0f
    private var prevTimestamp: Long = 0L
    private var initialized: Boolean = false

    fun filter(rawValue: Float, timestamp: Long): Float {
        if (!initialized) {
            xHat = rawValue
            prevTimestamp = timestamp
            initialized = true
            return rawValue
        }

        val dtMs = (timestamp - prevTimestamp).coerceAtLeast(1L)
        val dt = dtMs / 1000.0f

        val dxRaw = (rawValue - xHat) / dt
        val alphaDx = smoothingAlpha(dt, dCutoff)
        dxHat = alphaDx * dxRaw + (1.0f - alphaDx) * dxHat

        val cutoffX = minCutoff + beta * abs(dxHat)
        val alphaX = smoothingAlpha(dt, cutoffX)
        xHat = alphaX * rawValue + (1.0f - alphaX) * xHat

        prevTimestamp = timestamp
        return xHat
    }

    private fun smoothingAlpha(dt: Float, cutoff: Float): Float {
        val r = (2.0 * PI * cutoff * dt).toFloat()
        return r / (r + 1.0f)
    }

    fun reset() {
        initialized = false
        xHat = 0f
        dxHat = 0f
        prevTimestamp = 0L
    }
}
