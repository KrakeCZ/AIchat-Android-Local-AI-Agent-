package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.abs
import kotlin.math.floor

/**
 * NEDI — New Edge-Directed Interpolation (Li & Orchard, 2001).
 *
 * Klíčový rozdíl od EDI/bilineární:
 * Pro každý interpolovaný pixel sestaví lokální kovarianční matici R
 * z okolí 4-pixelových diagonálních vektorů v LR obraze.
 * Řeší Wiener-Hopf rovnici R·w = r → adaptivní váhy podél detekované hrany.
 *
 * Výsledek: interpolace přesně podél hran (ne napříč nimi) → nulové staircase artefakty
 * při obřím zoomu letadla nebo rogala.
 */
class NediPlugin(private val windowRadius: Int = 5) : LupaPlugin {
    override val pluginId = "lupa_nedi"
    override val displayName = "NEDI (New Edge-Directed)"
    override val category = LupaCategory.SINGLE_FRAME
    override val expectedFpsHint = 8

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val srcW = input.width; val srcH = input.height
        val dstW = (srcW * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (srcH * scaleFactor).toInt().coerceAtLeast(1)

        val srcPx = IntArray(srcW * srcH)
        input.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)

        // Luminance pro kovarianční analýzu hran
        val luma = FloatArray(srcW * srcH) { i ->
            ((srcPx[i] shr 16) and 0xFF) * 0.299f +
            ((srcPx[i] shr 8) and 0xFF) * 0.587f +
            (srcPx[i] and 0xFF) * 0.114f
        }

        val dstPx = IntArray(dstW * dstH)

        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val srcX = (dx + 0.5f) / scaleFactor - 0.5f
                val srcY = (dy + 0.5f) / scaleFactor - 0.5f
                val x0 = floor(srcX).toInt().coerceIn(0, srcW - 1)
                val y0 = floor(srcY).toInt().coerceIn(0, srcH - 1)

                // Sestavení lokální kovarianční matice R (3×3) a vektoru r
                // z diagonálních 4-pixelových sousedů v okně windowRadius
                val R = Array(3) { FloatArray(3) }
                val r = FloatArray(3)
                var sampleCount = 0

                for (ny in (y0 - windowRadius)..(y0 + windowRadius)) {
                    for (nx in (x0 - windowRadius)..(x0 + windowRadius)) {
                        if (nx < 0 || nx >= srcW - 1 || ny < 0 || ny >= srcH - 1) continue
                        // Diagonální 4-pixel vektor: [P(nx,ny), P(nx+1,ny), P(nx,ny+1), P(nx+1,ny+1)]
                        val p0 = luma[ny * srcW + nx]
                        val p1 = luma[ny * srcW + nx + 1]
                        val p2 = luma[(ny + 1) * srcW + nx]
                        val p3 = luma[(ny + 1) * srcW + nx + 1]
                        // y3 = [p0, p1, p2] jsou "known", yTarget = p3
                        val y3 = floatArrayOf(p0, p1, p2)
                        for (i in 0..2) {
                            r[i] += y3[i] * p3
                            for (j in 0..2) R[i][j] += y3[i] * y3[j]
                        }
                        sampleCount++
                    }
                }

                val weights: FloatArray = if (sampleCount > 3) {
                    val n = sampleCount.toFloat()
                    for (i in 0..2) { r[i] /= n; for (j in 0..2) R[i][j] /= n }
                    // Přidání Tikhonov regularizace pro stabilitu
                    val lambda = 0.01f
                    R[0][0] += lambda; R[1][1] += lambda; R[2][2] += lambda
                    solveLinear3x3(R, r)
                } else {
                    floatArrayOf(0.25f, 0.25f, 0.25f)  // fallback
                }

                // Výpočet 4. váhy (zachování součtu = 1). Neclampujeme, protože NEDI může mít záporné váhy.
                val w4 = 1f - weights[0] - weights[1] - weights[2]

                val px0 = srcPx[y0 * srcW + x0]
                val px1 = srcPx[y0 * srcW + (x0 + 1).coerceIn(0, srcW - 1)]
                val px2 = srcPx[(y0 + 1).coerceIn(0, srcH - 1) * srcW + x0]
                val px3 = srcPx[(y0 + 1).coerceIn(0, srcH - 1) * srcW + (x0 + 1).coerceIn(0, srcW - 1)]

                val outR = channelBlend(px0, px1, px2, px3, weights, w4, 16)
                val outG = channelBlend(px0, px1, px2, px3, weights, w4, 8)
                val outB = channelBlend(px0, px1, px2, px3, weights, w4, 0)

                dstPx[dy * dstW + dx] = (0xFF shl 24) or
                    (outR shl 16) or (outG shl 8) or outB
            }
        }

        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(dstPx, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    private fun channelBlend(
        p0: Int, p1: Int, p2: Int, p3: Int,
        w: FloatArray, w4: Float, shift: Int
    ): Int {
        val c0 = ((p0 shr shift) and 0xFF).toFloat()
        val c1 = ((p1 shr shift) and 0xFF).toFloat()
        val c2 = ((p2 shr shift) and 0xFF).toFloat()
        val c3 = ((p3 shr shift) and 0xFF).toFloat()
        return (c0 * w[0] + c1 * w[1] + c2 * w[2] + c3 * w4).toInt().coerceIn(0, 255)
    }

    /** Cramerovo pravidlo pro 3×3 soustavu R·w = r. */
    private fun solveLinear3x3(A: Array<FloatArray>, b: FloatArray): FloatArray {
        val det = A[0][0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) -
                  A[0][1] * (A[1][0] * A[2][2] - A[1][2] * A[2][0]) +
                  A[0][2] * (A[1][0] * A[2][1] - A[1][1] * A[2][0])
        if (abs(det) < 1e-6f) return floatArrayOf(0.25f, 0.25f, 0.25f)

        val x = (b[0] * (A[1][1] * A[2][2] - A[1][2] * A[2][1]) -
                 A[0][1] * (b[1] * A[2][2] - A[1][2] * b[2]) +
                 A[0][2] * (b[1] * A[2][1] - A[1][1] * b[2])) / det

        val y = (A[0][0] * (b[1] * A[2][2] - A[1][2] * b[2]) -
                 b[0] * (A[1][0] * A[2][2] - A[1][2] * A[2][0]) +
                 A[0][2] * (A[1][0] * b[2] - b[1] * A[2][0])) / det

        val z = (A[0][0] * (A[1][1] * b[2] - b[1] * A[2][1]) -
                 A[0][1] * (A[1][0] * b[2] - b[1] * A[2][0]) +
                 b[0] * (A[1][0] * A[2][1] - A[1][1] * A[2][0])) / det

        if (x.isNaN() || y.isNaN() || z.isNaN() || abs(x) > 3f || abs(y) > 3f || abs(z) > 3f) {
            return floatArrayOf(0.25f, 0.25f, 0.25f)
        }

        return floatArrayOf(x, y, z)
    }

    override fun reset() {}
    override fun release() {}
}