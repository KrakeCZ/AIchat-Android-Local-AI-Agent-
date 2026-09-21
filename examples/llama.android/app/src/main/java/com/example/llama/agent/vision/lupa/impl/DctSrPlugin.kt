package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * DCT Super-Resolution — bloková frekvenční interpolace.
 *
 * Princip (JPEG DCT zero-padding):
 * 1. Zdrojový obraz rozděl na 8×8 bloky
 * 2. Každý blok: forward DCT-II → 64 koeficientů
 * 3. Zero-pad na 16×16 (nulové HF koeficienty = interpolace)
 * 4. Inverse DCT-III → 16×16 výstupní blok
 *
 * Žáci pochopí proč JPEG "zabíjí" hrany (truncace HF koeficientů)
 * a jak lze frekvence zpětně prodloužit pro upscale.
 *
 * Pro scaleFactor ≠ 2: nejprve DCT 2× upscale, pak bilinear resize.
 */
class DctSrPlugin : LupaPlugin {
    override val pluginId = "lupa_dct_sr"
    override val displayName = "DCT SR (Bloková frekvenční)"
    override val category = LupaCategory.FREQUENCY_DOMAIN
    override val expectedFpsHint = 6

    private val N = 8   // zdrojový blok
    private val M = 16  // výstupní blok (2× upscale)

    // Precompute DCT cosine tables
    private val cosTableN = Array(N) { k -> FloatArray(N) { n ->
        cos(PI * k * (2 * n + 1) / (2 * N)).toFloat()
    }}
    private val cosTableM = Array(M) { k -> FloatArray(M) { n ->
        cos(PI * k * (2 * n + 1) / (2 * M)).toFloat()
    }}

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val srcW = input.width; val srcH = input.height
        val twoXW = (srcW / N) * M   // 2× upscaled (blok-aligned)
        val twoXH = (srcH / N) * M

        val srcPx = IntArray(srcW * srcH)
        input.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)

        // Zpracuj každý kanál odděleně
        val rOut = dctUpscaleChannel(srcPx, srcW, srcH, twoXW, twoXH, 16)
        val gOut = dctUpscaleChannel(srcPx, srcW, srcH, twoXW, twoXH, 8)
        val bOut = dctUpscaleChannel(srcPx, srcW, srcH, twoXW, twoXH, 0)

        val twoXPx = IntArray(twoXW * twoXH) { i ->
            (0xFF shl 24) or
                (rOut[i].toInt().coerceIn(0, 255) shl 16) or
                (gOut[i].toInt().coerceIn(0, 255) shl 8) or
                bOut[i].toInt().coerceIn(0, 255)
        }

        val twoXBmp = Bitmap.createBitmap(twoXW, twoXH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(twoXPx, 0, twoXW, 0, 0, twoXW, twoXH)
        }

        val dstW = (srcW * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (srcH * scaleFactor).toInt().coerceAtLeast(1)

        // Pokud je cíl přesně 2×, vrátíme přímo; jinak bilinear resize
        return if (dstW == twoXW && dstH == twoXH) {
            twoXBmp
        } else {
            val result = Bitmap.createScaledBitmap(twoXBmp, dstW, dstH, true)
            twoXBmp.recycle()
            result
        }
    }

    private fun dctUpscaleChannel(
        src: IntArray, srcW: Int, srcH: Int,
        dstW: Int, dstH: Int, shift: Int
    ): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val blocksX = srcW / N; val blocksY = srcH / N

        for (by in 0 until blocksY) {
            for (bx in 0 until blocksX) {
                // Načti N×N blok
                val block = FloatArray(N * N) { idx ->
                    val y = by * N + idx / N; val x = bx * N + idx % N
                    ((src[y * srcW + x] shr shift) and 0xFF).toFloat()
                }

                // Forward DCT-II (8×8)
                val coeffs = dct2D_forward(block)

                // Inverse DCT-III na M×M (zero-padded HF)
                val expanded = idct2D_padded(coeffs)

                // Zápis do výstupního pole
                for (py in 0 until M) {
                    for (px in 0 until M) {
                        val dy = by * M + py; val dx = bx * M + px
                        if (dy < dstH && dx < dstW) {
                            dst[dy * dstW + dx] = expanded[py * M + px].coerceIn(0f, 255f)
                        }
                    }
                }
            }
        }
        return dst
    }

    /** Forward DCT-II: X[u,v] = (2/N)*cu*cv * Σxy x[y,x]*cos()*cos() */
    private fun dct2D_forward(block: FloatArray): FloatArray {
        val coeffs = FloatArray(N * N)
        val inv2 = 1f / sqrt(2f)
        for (u in 0 until N) {
            val cu = if (u == 0) inv2 else 1f
            for (v in 0 until N) {
                val cv = if (v == 0) inv2 else 1f
                var sum = 0f
                for (y in 0 until N) for (x in 0 until N) {
                    sum += block[y * N + x] * cosTableN[u][x] * cosTableN[v][y]
                }
                coeffs[v * N + u] = (2f / N) * cu * cv * sum
            }
        }
        return coeffs
    }

    /**
     * Inverse DCT-III na M×M výstupu s N×N vstupními koeficienty (zero-padding).
     * y[n] = (M/N) * (1/M) * Σk (cu*ck*X[k]) * cos(π*k*(2n+1)/(2M))
     */
    private fun idct2D_padded(coeffs: FloatArray): FloatArray {
        val out = FloatArray(M * M)
        val inv2 = 1f / sqrt(2f)
        val scale = (M.toFloat() / N)  // energetické škálování
        for (y in 0 until M) {
            for (x in 0 until M) {
                var sum = 0f
                for (u in 0 until N) {
                    val cu = if (u == 0) inv2 else 1f
                    for (v in 0 until N) {
                        val cv = if (v == 0) inv2 else 1f
                        sum += cu * cv * coeffs[v * N + u] * cosTableM[u][x] * cosTableM[v][y]
                    }
                }
                out[y * M + x] = scale * (2f / M) * sum
            }
        }
        return out
    }

    override fun reset() {}
    override fun release() {}
}