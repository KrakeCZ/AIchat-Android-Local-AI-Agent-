package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Lucky Imaging + Frame Stacking — standard astrofotografie.
 *
 * Ze série N snímků vybere nejostřejší (top 30%) pomocí Laplacian Variance Score.
 * Vybrané snímky průměruje (stack) → šum klesá ~1/√N, sharp detail se posiluje.
 *
 * quality_score(frame) = variance( Laplacian(frame) )
 *
 * Optimální pro: turbulentní atmosféra při pozorování mraků, rogalo v turbulenci.
 */
class LuckyImagingPlugin(
    private val topPercentage: Float = 0.3f  // Podíl nejlepších framů (0.0–1.0)
) : LupaPlugin {
    override val pluginId = "lupa_lucky_imaging"
    override val displayName = "Lucky Imaging (Astro-grade)"
    override val category = LupaCategory.MULTI_FRAME
    override val expectedFpsHint = 4
    override val requiresMultipleFrames = true
    override val minFramesRequired = 5

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val dstW = (input.width * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (input.height * scaleFactor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, dstW, dstH, true)
    }

    override fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap {
        val w = frames[0].width; val h = frames[0].height

        // 1. Score každý frame pomocí Laplacian Variance
        val scored = frames.map { frame ->
            val score = laplacianVariance(frame)
            Pair(frame, score)
        }.sortedByDescending { it.second }

        // 2. Vyber top topPercentage% (min 1 frame)
        val keepCount = (scored.size * topPercentage).toInt().coerceAtLeast(1)
        val bestFrames = scored.take(keepCount).map { it.first }

        // 3. Stack (pixel-wise average) nejlepších framů
        val rAcc = LongArray(w * h); val gAcc = LongArray(w * h); val bAcc = LongArray(w * h)
        val px = IntArray(w * h)

        for (frame in bestFrames) {
            frame.getPixels(px, 0, w, 0, 0, w, h)
            for (i in 0 until w * h) {
                rAcc[i] += (px[i] shr 16) and 0xFF
                gAcc[i] += (px[i] shr 8) and 0xFF
                bAcc[i] += px[i] and 0xFF
            }
        }

        val n = bestFrames.size.toLong()
        val stackedPx = IntArray(w * h) { i ->
            (0xFF shl 24) or
                ((rAcc[i] / n).toInt().coerceIn(0, 255) shl 16) or
                ((gAcc[i] / n).toInt().coerceIn(0, 255) shl 8) or
                (bAcc[i] / n).toInt().coerceIn(0, 255)
        }

        val stacked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(stackedPx, 0, w, 0, 0, w, h)
        }

        val dstW = (w * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (h * scaleFactor).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(stacked, dstW, dstH, true)
        stacked.recycle()
        return result
    }

    /** Laplacian[0,1,0 / 1,-4,1 / 0,1,0] variance — vyšší = ostřejší frame. */
    private fun laplacianVariance(bitmap: Bitmap): Double {
        val w = bitmap.width; val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        var sum = 0.0; var sumSq = 0.0; var cnt = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = luminance(px[y * w + x])
                val lap = (luminance(px[(y - 1) * w + x]) +
                           luminance(px[(y + 1) * w + x]) +
                           luminance(px[y * w + x - 1]) +
                           luminance(px[y * w + x + 1]) - 4 * c).toDouble()
                sum += lap; sumSq += lap * lap; cnt++
            }
        }

        if (cnt == 0) return 0.0
        val mean = sum / cnt
        return sumSq / cnt - mean * mean
    }

    private fun luminance(pixel: Int): Int =
        ((pixel shr 16) and 0xFF) * 299 / 1000 +
        ((pixel shr 8) and 0xFF) * 587 / 1000 +
        (pixel and 0xFF) * 114 / 1000

    override fun reset() {}
    override fun release() {}
}