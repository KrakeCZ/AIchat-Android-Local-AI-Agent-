package com.example.llama.agent.vision.tracking

import android.graphics.RectF
import com.example.llama.agent.vision.tracking.math.DefaultEstimatorProfiles
import com.example.llama.agent.vision.tracking.math.EstimatorParameters
import com.example.llama.agent.vision.tracking.math.MotionEstimator

/**
 * Diskrétní Kalmanův filtr pro sledování objektů.
 * UPGRADE: 8-State Kalman Filter [x, y, w, h, vx, vy, vw, vh]
 * Pro stabilizaci velikosti boxů a prevenci jitteru při přibližování (Looming effect).
 */
class OD_KalmanFilter2D(
    private val params: EstimatorParameters = DefaultEstimatorProfiles.LINEAR_DEFAULT
) : MotionEstimator {
    // Stavový Vektor X: [x, y, w, h, vx, vy, vw, vh]
    private val X = FloatArray(8)

    // Kovarianční matice P (8x8)
    private val P = FloatArray(64)

    // Pomocné matice pro výpočty
    private val X_pred = FloatArray(8)
    private val P_pred = FloatArray(64)

    // Q - Process noise covariance (8x8)
    private val Q = FloatArray(64)

    // R - Measurement noise covariance (4x4)
    private val R = FloatArray(16)

    private var lastTimeMs = 0L

    override val velocityX: Float get() = X[4]
    override val velocityY: Float get() = X[5]
    override val velocityW: Float get() = X[6]
    override val velocityH: Float get() = X[7]

    override fun initiate(rect: RectF) {
        X[0] = rect.centerX()
        X[1] = rect.centerY()
        X[2] = rect.width()
        X[3] = rect.height()
        X[4] = 0f
        X[5] = 0f
        X[6] = 0f
        X[7] = 0f

        for (i in 0..7) {
            for (j in 0..7) {
                P[i * 8 + j] = if (i == j) 0.1f else 0f
                Q[i * 8 + j] = if (i == j) params.processNoise else 0f
            }
        }
        // Větší šum pro rychlosti
        for (i in 4..7) {
            Q[i * 8 + i] = params.processNoise * 5f
        }
        // w a h se mění rychleji při přibližování (looming)
        Q[2 * 8 + 2] = params.processNoise * 2f
        Q[3 * 8 + 3] = params.processNoise * 2f

        for (i in 0..3) {
            for(j in 0..3) {
                R[i * 4 + j] = if (i == j) params.measurementNoise else 0f
            }
        }
    }

    override fun predict(timestampMs: Long) {
        if (lastTimeMs == 0L) lastTimeMs = timestampMs
        var dt = (timestampMs - lastTimeMs) / 1000f
        if (dt <= 0f) dt = 0.016f
        lastTimeMs = timestampMs

        if (dt > 1.0f) {
            return
        }

        // --- PREDICT STEP ---
        for (i in 0..3) {
            X_pred[i] = X[i] + X[i + 4] * dt
            X_pred[i + 4] = X[i + 4]
        }

        for (i in 0..7) {
            for (j in 0..7) {
                var sum = 0f
                for (k in 0..7) {
                    val a_ik = if (i == k) 1f else if (k - i == 4) dt else 0f
                    var p_a_sum = 0f
                    for (l in 0..7) {
                        val a_jl = if (j == l) 1f else if (l - j == 4) dt else 0f
                        p_a_sum += P[k * 8 + l] * a_jl
                    }
                    sum += a_ik * p_a_sum
                }
                P_pred[i * 8 + j] = sum + (if (i == j) Q[i * 8 + j] else 0f)
            }
        }

        for (i in 0..7) {
            X[i] = X_pred[i]
        }
        for (i in 0..63) {
            P[i] = P_pred[i]
        }
    }

    override fun update(rect: RectF, confidence: Float, timestampMs: Long) {
        val measX = rect.centerX()
        val measY = rect.centerY()
        val measW = rect.width()
        val measH = rect.height()

        if (lastTimeMs == 0L) {
            lastTimeMs = timestampMs
            hardReset(measX, measY, measW, measH)
            return
        }

        var dt = (timestampMs - lastTimeMs) / 1000f
        if (dt <= 0f) dt = 0.016f
        lastTimeMs = timestampMs

        if (dt > 1.0f) {
            hardReset(measX, measY, measW, measH)
            return
        }

        // --- 1. PREDICT STEP ---
        for (i in 0..3) {
            X_pred[i] = X[i] + X[i + 4] * dt
            X_pred[i + 4] = X[i + 4]
        }

        for (i in 0..7) {
            for (j in 0..7) {
                var sum = 0f
                for (k in 0..7) {
                    val a_ik = if (i == k) 1f else if (k - i == 4) dt else 0f
                    var p_a_sum = 0f
                    for (l in 0..7) {
                        val a_jl = if (j == l) 1f else if (l - j == 4) dt else 0f
                        p_a_sum += P[k * 8 + l] * a_jl
                    }
                    sum += a_ik * p_a_sum
                }
                P_pred[i * 8 + j] = sum + (if (i == j) Q[i * 8 + j] else 0f)
            }
        }

        // --- 2. UPDATE STEP ---
        val v = floatArrayOf(measX - X_pred[0], measY - X_pred[1], measW - X_pred[2], measH - X_pred[3])

        // S = H * P_pred * H^T + R -> P_pred[0..3, 0..3] + R
        // Invert S (4x4, diagonal approximation for speed/stability)
        val S_inv = FloatArray(16)
        for(i in 0..3) {
            S_inv[i * 4 + i] = 1f / (P_pred[i * 8 + i] + R[i * 4 + i])
        }

        // K = P_pred * H^T * S_inv (8x4)
        val K = FloatArray(32)
        for (i in 0..7) {
            for (j in 0..3) {
                K[i * 4 + j] = P_pred[i * 8 + j] * S_inv[j * 4 + j]
            }
        }

        // X = X_pred + K * v
        for (i in 0..7) {
            X[i] = X_pred[i]
            for (j in 0..3) {
                X[i] += K[i * 4 + j] * v[j]
            }
        }

        // P = P_pred - K * H * P_pred
        for (i in 0..7) {
            for (j in 0..7) {
                var sum = 0f
                for (k in 0..3) {
                    sum += K[i * 4 + k] * P_pred[k * 8 + j]
                }
                P[i * 8 + j] = P_pred[i * 8 + j] - sum
            }
        }
        
        X[2] = kotlin.math.max(params.minAspectRatio, X[2])
        X[3] = kotlin.math.max(params.minHeight, X[3])
    }

    override fun getPredictedRect(outRect: RectF) {
        val w = X[2]
        val h = X[3]
        val left = X[0] - w / 2f
        val top = X[1] - h / 2f
        outRect.set(left, top, left + w, top + h)
    }

    private fun hardReset(x: Float, y: Float, w: Float, h: Float) {
        X[0] = x
        X[1] = y
        X[2] = w
        X[3] = h
        X[4] = 0f
        X[5] = 0f
        X[6] = 0f
        X[7] = 0f
        for (i in 0..7) {
            for (j in 0..7) {
                P[i * 8 + j] = if (i == j) 0.1f else 0f
            }
        }
    }
}
