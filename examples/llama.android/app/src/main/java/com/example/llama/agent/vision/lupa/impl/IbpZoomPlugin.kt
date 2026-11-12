package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Iterative Back Projection (Irani & Peleg, 1991).
 * HR(t+1) = HR(t) + upsample( LR_observed − downsample(HR(t)) )
 *
 * Didakticky nejcennější — žáci vidí jak každá iterace zpřesňuje odhad.
 * Pracuje s FloatArray aby nedocházelo ke kvantizační chybě v byte rozsahu.
 */
class IbpZoomPlugin(private val iterations: Int = 12) : LupaPlugin {
    override val pluginId = "lupa_ibp"
    override val displayName = "IBP (Iterative Back Projection)"
    override val category = LupaCategory.SINGLE_FRAME
    override val expectedFpsHint = 5

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val srcW = input.width; val srcH = input.height
        val dstW = (srcW * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (srcH * scaleFactor).toInt().coerceAtLeast(1)

        val srcPx = IntArray(srcW * srcH)
        input.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)

        // Počáteční HR odhad: bilineární upscale (v float prostoru)
        val hrR = bilinearUpscaleChannel(srcPx, srcW, srcH, dstW, dstH, 16)
        val hrG = bilinearUpscaleChannel(srcPx, srcW, srcH, dstW, dstH, 8)
        val hrB = bilinearUpscaleChannel(srcPx, srcW, srcH, dstW, dstH, 0)

        // Referenční LR v float prostoru
        val lrR = FloatArray(srcW * srcH) { i -> ((srcPx[i] shr 16) and 0xFF).toFloat() }
        val lrG = FloatArray(srcW * srcH) { i -> ((srcPx[i] shr 8) and 0xFF).toFloat() }
        val lrB = FloatArray(srcW * srcH) { i -> (srcPx[i] and 0xFF).toFloat() }

        repeat(iterations) {
            ibpIteration(hrR, lrR, srcW, srcH, dstW, dstH)
            ibpIteration(hrG, lrG, srcW, srcH, dstW, dstH)
            ibpIteration(hrB, lrB, srcW, srcH, dstW, dstH)
        }

        val dst = IntArray(dstW * dstH) { i ->
            (0xFF shl 24) or
                (hrR[i].toInt().coerceIn(0, 255) shl 16) or
                (hrG[i].toInt().coerceIn(0, 255) shl 8) or
                hrB[i].toInt().coerceIn(0, 255)
        }

        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(dst, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    private fun ibpIteration(
        hr: FloatArray, lrObs: FloatArray,
        srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ) {
        // Downsample HR → LR estimate (box filter / average)
        val lrEst = bilinearDownscaleFloat(hr, dstW, dstH, srcW, srcH)

        // Chyba v LR doméně
        val error = FloatArray(srcW * srcH) { i -> lrObs[i] - lrEst[i] }

        // Upscale chyby do HR rozměru a aplikuj korekci
        val errorUp = bilinearUpscaleFloat(error, srcW, srcH, dstW, dstH)
        for (i in hr.indices) {
            hr[i] = (hr[i] + errorUp[i]).coerceIn(0f, 255f)
        }
    }

    private fun bilinearUpscaleChannel(
        src: IntArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int, shift: Int
    ): FloatArray {
        val channel = FloatArray(srcW * srcH) { i -> ((src[i] shr shift) and 0xFF).toFloat() }
        return bilinearUpscaleFloat(channel, srcW, srcH, dstW, dstH)
    }

    private fun bilinearUpscaleFloat(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ): FloatArray {
        val dst = FloatArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val sx = (dx + 0.5f) * scaleX - 0.5f
                val sy = (dy + 0.5f) * scaleY - 0.5f
                val x0 = sx.toInt().coerceIn(0, srcW - 1)
                val y0 = sy.toInt().coerceIn(0, srcH - 1)
                val x1 = (x0 + 1).coerceIn(0, srcW - 1)
                val y1 = (y0 + 1).coerceIn(0, srcH - 1)
                val fx = (sx - x0).coerceIn(0f, 1f)
                val fy = (sy - y0).coerceIn(0f, 1f)
                dst[dy * dstW + dx] =
                    src[y0 * srcW + x0] * (1 - fx) * (1 - fy) +
                    src[y0 * srcW + x1] * fx * (1 - fy) +
                    src[y1 * srcW + x0] * (1 - fx) * fy +
                    src[y1 * srcW + x1] * fx * fy
            }
        }
        return dst
    }

    private fun bilinearDownscaleFloat(
        src: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int
    ): FloatArray {
        // Box-filter downscale
        val dst = FloatArray(dstW * dstH)
        val scaleX = srcW.toFloat() / dstW
        val scaleY = srcH.toFloat() / dstH
        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val x0 = (dx * scaleX).toInt().coerceIn(0, srcW - 1)
                val y0 = (dy * scaleY).toInt().coerceIn(0, srcH - 1)
                val x1 = ((dx + 1) * scaleX).toInt().coerceIn(0, srcW - 1)
                val y1 = ((dy + 1) * scaleY).toInt().coerceIn(0, srcH - 1)
                var sum = 0f; var cnt = 0
                for (sy in y0..y1) for (sx in x0..x1) { sum += src[sy * srcW + sx]; cnt++ }
                dst[dy * dstW + dx] = if (cnt > 0) sum / cnt else 0f
            }
        }
        return dst
    }

    override fun reset() {}
    override fun release() {}
}