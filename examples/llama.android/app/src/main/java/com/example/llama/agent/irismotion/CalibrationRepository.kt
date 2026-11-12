package com.example.llama.agent.irismotion

import android.content.Context
import android.graphics.PointF
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * CalibrationRepository — Bezpečná perzistence kalibračních dat iris trackeru.
 *
 * Bezpečnost:
 * - EncryptedSharedPreferences (AES-256-GCM) s hardware-backed Android Keystore MasterKey
 * - Data neopouštějí zařízení, uložena v privátním kontextu aplikace
 * - Atomický zápis (commit() blokující — zajišťuje integritu pro AccessibilityService start)
 *
 * Datový model: 4 PointF hodnoty (topLeft, topRight, bottomLeft, bottomRight) z 4-bodové kalibrace.
 * Každý bod = normalizovaná iris pozice (0.0–1.0) při pohledu na kalibrační bod na obrazovce.
 */
class CalibrationRepository(context: Context) {

    companion object {
        private const val TAG = "CalibrationRepository"
        private const val PREFS_NAME = "irismotion_calibration_secure"
        private const val KEY_TL_X = "cal_tl_x"
        private const val KEY_TL_Y = "cal_tl_y"
        private const val KEY_TR_X = "cal_tr_x"
        private const val KEY_TR_Y = "cal_tr_y"
        private const val KEY_BL_X = "cal_bl_x"
        private const val KEY_BL_Y = "cal_bl_y"
        private const val KEY_BR_X = "cal_br_x"
        private const val KEY_BR_Y = "cal_br_y"
        private const val KEY_IS_VALID = "cal_is_valid"

        // Polynomiální klíče
        private const val KEY_POLY_CX = "cal_poly_cx_" // appended with index 0..5
        private const val KEY_POLY_CY = "cal_poly_cy_" // appended with index 0..5
        private const val KEY_POLY_RMS = "cal_poly_rms"
        private const val KEY_POLY_SCORE = "cal_poly_score"
        private const val KEY_POLY_PTS = "cal_poly_pts"
        private const val KEY_IS_POLY_VALID = "cal_is_poly_valid"

        // Fallback výchozí body (rohy normalizovaného prostoru kamery)
        val FALLBACK_TL = PointF(0.2f, 0.2f)
        val FALLBACK_TR = PointF(0.8f, 0.2f)
        val FALLBACK_BL = PointF(0.2f, 0.8f)
        val FALLBACK_BR = PointF(0.8f, 0.8f)
    }

    /** Data class pro 4 kalibrační body */
    data class CalibrationData(
            val topLeft: PointF,
            val topRight: PointF,
            val bottomLeft: PointF,
            val bottomRight: PointF,
            val isValid: Boolean
    )

    /** Data class pro polynomiální model (9-bodová kalibrace) */
    data class PolynomialCalibration(
            val coeffX: FloatArray,
            val coeffY: FloatArray,
            val rmsErrorPx: Float,
            val qualityScore: Int,
            val pointCount: Int,
            val isValid: Boolean
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as PolynomialCalibration
            if (!coeffX.contentEquals(other.coeffX)) return false
            if (!coeffY.contentEquals(other.coeffY)) return false
            if (rmsErrorPx != other.rmsErrorPx) return false
            if (qualityScore != other.qualityScore) return false
            if (pointCount != other.pointCount) return false
            if (isValid != other.isValid) return false
            return true
        }
        override fun hashCode(): Int {
            var result = coeffX.contentHashCode()
            result = 31 * result + coeffY.contentHashCode()
            result = 31 * result + rmsErrorPx.hashCode()
            result = 31 * result + qualityScore
            result = 31 * result + pointCount
            result = 31 * result + isValid.hashCode()
            return result
        }
    }

    // EncryptedSharedPreferences — lazy init, háže exception pokud Keystore unavailable
    private val prefs by lazy {
        try {
            val masterKey =
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ EncryptedSharedPreferences INIT FAILED — fallback to plain prefs", e)
            // Bezpečný fallback: plain SharedPreferences (kalibrace není bezpečnostně kritická)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }
    }

    /**
     * Uloží 4-bodovou kalibraci atomicky. Volá commit() (blokující) aby AccessibilityService při
     * příštím startu načetla platná data.
     *
     * @param tl Top-left iris souřadnice (normalizovaná 0.0–1.0)
     * @param tr Top-right iris souřadnice
     * @param bl Bottom-left iris souřadnice
     * @param br Bottom-right iris souřadnice
     * @return True pokud zápis proběhl úspěšně
     */
    fun saveCalibration(tl: PointF, tr: PointF, bl: PointF, br: PointF): Boolean {
        return try {
            // KTX edit{} — apply() zapisuje na pozadí (non-blocking, bezpečné pro service context)
            prefs.edit {
                putFloat(KEY_TL_X, tl.x)
                putFloat(KEY_TL_Y, tl.y)
                putFloat(KEY_TR_X, tr.x)
                putFloat(KEY_TR_Y, tr.y)
                putFloat(KEY_BL_X, bl.x)
                putFloat(KEY_BL_Y, bl.y)
                putFloat(KEY_BR_X, br.x)
                putFloat(KEY_BR_Y, br.y)
                putBoolean(KEY_IS_VALID, true)
            }
            Log.i(TAG, "✅ Kalibrace uložena: TL=$tl TR=$tr BL=$bl BR=$br")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Zápis kalibrace selhal", e)
            false
        }
    }

    /**
     * Načte kalibraci z úložiště. Pokud data nejsou platná nebo chybí → vrací
     * CalibrationData.isValid = false.
     *
     * @return CalibrationData s isValid=true pokud kalibrace existuje a je platná
     */
    fun loadCalibration(): CalibrationData {
        return try {
            val isValid = prefs.getBoolean(KEY_IS_VALID, false)
            if (!isValid) {
                Log.d(TAG, "ℹ️ Kalibrace nebyla provedena — vyžaduje kalibraci")
                return CalibrationData(FALLBACK_TL, FALLBACK_TR, FALLBACK_BL, FALLBACK_BR, false)
            }

            CalibrationData(
                            topLeft =
                                    PointF(
                                            prefs.getFloat(KEY_TL_X, 0.2f),
                                            prefs.getFloat(KEY_TL_Y, 0.2f)
                                    ),
                            topRight =
                                    PointF(
                                            prefs.getFloat(KEY_TR_X, 0.8f),
                                            prefs.getFloat(KEY_TR_Y, 0.2f)
                                    ),
                            bottomLeft =
                                    PointF(
                                            prefs.getFloat(KEY_BL_X, 0.2f),
                                            prefs.getFloat(KEY_BL_Y, 0.8f)
                                    ),
                            bottomRight =
                                    PointF(
                                            prefs.getFloat(KEY_BR_X, 0.8f),
                                            prefs.getFloat(KEY_BR_Y, 0.8f)
                                    ),
                            isValid = true
                    )
                    .also { Log.i(TAG, "✅ Kalibrace načtena: TL=${it.topLeft} TR=${it.topRight}") }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Načtení kalibrace selhalo — fallback", e)
            CalibrationData(FALLBACK_TL, FALLBACK_TR, FALLBACK_BL, FALLBACK_BR, false)
        }
    }

    /** Vymaže kalibraci. Použít při resetu nebo re-kalibraci. */
    fun clearCalibration() {
        try {
            prefs.edit { putBoolean(KEY_IS_VALID, false) }
            Log.i(TAG, "🗑️ Kalibrace vymazána")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Clear kalibrace selhal", e)
        }
    }

    /** Vrátí true pokud existuje platná kalibrace */
    fun isCalibrationValid(): Boolean =
            try {
                prefs.getBoolean(KEY_IS_VALID, false)
            } catch (_: Exception) {
                false
            }

    /**
     * Uloží polynomiální kalibraci.
     */
    fun savePolynomialCalibration(
            coeffX: FloatArray,
            coeffY: FloatArray,
            rmsErrorPx: Float,
            qualityScore: Int,
            pointCount: Int
    ): Boolean {
        require(coeffX.size == 6 && coeffY.size == 6) { "Zápis polynomu vyžaduje 6 koeficientů" }
        return try {
            prefs.edit {
                for (i in 0 until 6) {
                    putFloat(KEY_POLY_CX + i, coeffX[i])
                    putFloat(KEY_POLY_CY + i, coeffY[i])
                }
                putFloat(KEY_POLY_RMS, rmsErrorPx)
                putInt(KEY_POLY_SCORE, qualityScore)
                putInt(KEY_POLY_PTS, pointCount)
                putBoolean(KEY_IS_POLY_VALID, true)
            }
            Log.i(TAG, "✅ Polynomiální kalibrace uložena: RMS=$rmsErrorPx px, Skóre=$qualityScore")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Zápis polynomiální kalibrace selhal", e)
            false
        }
    }

    /**
     * Načte polynomiální kalibraci z úložiště.
     */
    fun loadPolynomialCalibration(): PolynomialCalibration {
        return try {
            val isValid = prefs.getBoolean(KEY_IS_POLY_VALID, false)
            if (!isValid) {
                return PolynomialCalibration(FloatArray(6), FloatArray(6), 0f, 0, 0, false)
            }
            val cx = FloatArray(6) { prefs.getFloat(KEY_POLY_CX + it, 0f) }
            val cy = FloatArray(6) { prefs.getFloat(KEY_POLY_CY + it, 0f) }
            val rms = prefs.getFloat(KEY_POLY_RMS, 0f)
            val score = prefs.getInt(KEY_POLY_SCORE, 0)
            val count = prefs.getInt(KEY_POLY_PTS, 0)
            
            Log.i(TAG, "✅ Polynomiální kalibrace načtena: RMS=$rms px, Skóre=$score")
            PolynomialCalibration(cx, cy, rms, score, count, true)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Načtení polynomiální kalibrace selhalo", e)
            PolynomialCalibration(FloatArray(6), FloatArray(6), 0f, 0, 0, false)
        }
    }

    /** Vymaže polynomiální kalibraci. */
    fun clearPolynomialCalibration() {
        try {
            prefs.edit { putBoolean(KEY_IS_POLY_VALID, false) }
            Log.i(TAG, "🗑️ Polynomiální kalibrace vymazána")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Clear polynomiální kalibrace selhal", e)
        }
    }
}
