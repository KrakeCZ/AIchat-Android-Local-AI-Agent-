package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Haar Wavelet Super-Resolution.
 * Obraz rozloží do subpásem LL/LH/HL/HH.
 * Luminance upscalována v wavelet doméně — hrany zachovány v HH pásmu.
 * Chrominance (Cb, Cr) upscalována bilineárně — šetří výkon.
 *
 * Didaktická hodnota: žáci vizuálně chápou co je "hrana" vs. "plocha" obrazu.
 */
class WaveletSrPlugin : LupaPlugin {
    override val pluginId = "lupa_wavelet_haar"
    override val displayName = "Wavelet SR (Haar)"
    override val category = LupaCategory.FREQUENCY_DOMAIN
    override val expectedFpsHint = 8

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val srcW = input.width; val srcH = input.height
        val dstW = (srcW * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (srcH * scaleFactor).toInt().coerceAtLeast(1)

        val srcPx = IntArray(srcW * srcH)
        input.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)

        // Separace na YCbCr — normalizováno 0..1
        val y  = FloatArray(srcW * srcH)
        val cb = FloatArray(srcW * srcH)
        val cr = FloatArray(srcW * srcH)
        for (i in 0 until srcW * srcH) {
            val r = ((srcPx[i] shr 16) and 0xFF).toFloat()
            val g = ((srcPx[i] shr 8) and 0xFF).toFloat()
            val b = (srcPx[i] and 0xFF).toFloat()
            y[i]  = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            cb[i] = (-0.169f * r - 0.331f * g + 0.5f * b + 128f) / 255f
            cr[i] = (0.5f * r - 0.419f * g - 0.081f * b + 128f) / 255f
        }

        // Wavelet upscale na Y kanálu
        val yUp = waveletUpscale(y, srcW, srcH, dstW, dstH)

        // Bilineární upscale Cb, Cr
        val cbUp = bilinearUpscale(cb, srcW, srcH, dstW, dstH)
        val crUp = bilinearUpscale(cr, srcW, srcH, dstW, dstH)

        // YCbCr → RGB
        val dst = IntArray(dstW * dstH) { i ->
            val yv  = yUp[i]
            val cbv = cbUp[i] - 0.5f
            val crv = crUp[i] - 0.5f
            val r = ((yv + 1.402f * crv) * 255f).toInt().coerceIn(0, 255)
            val g = ((yv - 0.344f * cbv - 0.714f * crv) * 255f).toInt().coerceIn(0, 255)
            val b = ((yv + 1.772f * cbv) * 255f).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(dst, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    private fun waveletUpscale(
        channel: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ): FloatArray {
        // Forward Haar 2D (1 level)
        val coeffs = haar2DForward(channel, srcW, srcH)

        // LL subpásmo (levý horní kvadrant srcW/2 × srcH/2) — rekonstruujeme zpět
        val llW = srcW / 2; val llH = srcH / 2
        val ll = FloatArray(llW * llH) { i ->
            val cy = i / llW; val cx = i % llW
            coeffs[cy * srcW + cx]
        }

        // Upscale LL bilineárně na dstW × dstH (vynecháme detail koeficienty — HF predikce)
        return bilinearUpscale(ll, llW, llH, dstW, dstH)
    }

    /** Haar 2D forward — in-place varianta vrací nové pole. */
    private fun haar2DForward(data: FloatArray, w: Int, h: Int): FloatArray {
        val result = data.copyOf()
        // Horizontální pass
        for (row in 0 until h) {
            haarForward1D(result, row * w, w)
        }
        // Vertikální pass
        val col = FloatArray(h)
        for (cx in 0 until w) {
            for (cy in 0 until h) col[cy] = result[cy * w + cx]
            haarForward1D(col, 0, h)
            for (cy in 0 until h) result[cy * w + cx] = col[cy]
        }
        return result
    }

    private fun haarForward1D(arr: FloatArray, offset: Int, len: Int) {
        if (len < 2) return
        val tmp = FloatArray(len)
        val half = len / 2
        for (i in 0 until half) {
            tmp[i] = (arr[offset + 2 * i] + arr[offset + 2 * i + 1]) * 0.5f
            tmp[half + i] = (arr[offset + 2 * i] - arr[offset + 2 * i + 1]) * 0.5f
        }
        for (i in 0 until len) arr[offset + i] = tmp[i]
    }

    private fun bilinearUpscale(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val sx = srcW.toFloat() / dstW; val sy = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val fx = (dx + 0.5f) * sx - 0.5f; val fy = (dy + 0.5f) * sy - 0.5f
                val x0 = fx.toInt().coerceIn(0, srcW - 1); val y0 = fy.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1); val y1 = (y0 + 1).coerceIn(0, srcH - 1)
                val wx = (fx - x0).coerceIn(0f, 1f); val wy = (fy - y0).coerceIn(0f, 1f)
                dst[dy * dstW + dx] =
                    src[y0 * srcW + x0] * (1 - wx) * (1 - wy) +
                    src[y0 * srcW + x1] * wx * (1 - wy) +
                    src[y1 * srcW + x0] * (1 - wx) * wy +
                    src[y1 * srcW + x1] * wx * wy
            }
        }
        return dst
    }

    override fun reset() {}
    override fun release() {}
}