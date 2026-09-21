package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.abs
import kotlin.math.floor

/**
 * Mitchell-Netravali kubická interpolace s parametry B a C.
 * Výchozí hodnoty B=0, C=0.5 = Catmull-Rom spline:
 * maximální zachování hran pro detekci kontur letadla/rogala.
 *
 * B=1/3, C=1/3 = Mitchell (nejhladší výsledek pro mraky).
 */
class MitchellNetravaluPlugin(
    private val B: Float = 0f,
    private val C: Float = 0.5f
) : LupaPlugin {
    override val pluginId = "lupa_mitchell_netravali"
    override val displayName = "Mitchell-Netravali (Catmull-Rom)"
    override val category = LupaCategory.SINGLE_FRAME
    override val expectedFpsHint = 12

    private fun filter(t: Float): Float {
        val x = abs(t)
        return when {
            x < 1f -> ((12 - 9 * B - 6 * C) * x * x * x +
                       (-18 + 12 * B + 6 * C) * x * x +
                       (6 - 2 * B)) / 6f
            x < 2f -> ((-B - 6 * C) * x * x * x +
                       (6 * B + 30 * C) * x * x +
                       (-12 * B - 48 * C) * x +
                       (8 * B + 24 * C)) / 6f
            else -> 0f
        }
    }

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val srcW = input.width; val srcH = input.height
        val dstW = (srcW * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (srcH * scaleFactor).toInt().coerceAtLeast(1)

        val src = IntArray(srcW * srcH)
        input.getPixels(src, 0, srcW, 0, 0, srcW, srcH)
        val dst = IntArray(dstW * dstH)

        for (dy in 0 until dstH) {
            for (dx in 0 until dstW) {
                val sx = (dx + 0.5f) / scaleFactor - 0.5f
                val sy = (dy + 0.5f) / scaleFactor - 0.5f
                val x0 = floor(sx).toInt() - 1
                val y0 = floor(sy).toInt() - 1
                var r = 0f; var g = 0f; var b = 0f; var wSum = 0f

                for (ky in y0..y0 + 3) {
                    val wy = filter(sy - ky)
                    val py = ky.coerceIn(0, srcH - 1)
                    for (kx in x0..x0 + 3) {
                        val w = wy * filter(sx - kx)
                        val px = kx.coerceIn(0, srcW - 1)
                        val pixel = src[py * srcW + px]
                        r += ((pixel shr 16) and 0xFF) * w
                        g += ((pixel shr 8) and 0xFF) * w
                        b += (pixel and 0xFF) * w
                        wSum += w
                    }
                }

                if (wSum != 0f) { r /= wSum; g /= wSum; b /= wSum }
                dst[dy * dstW + dx] = (0xFF shl 24) or
                    (r.toInt().coerceIn(0, 255) shl 16) or
                    (g.toInt().coerceIn(0, 255) shl 8) or
                    b.toInt().coerceIn(0, 255)
            }
        }

        return Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888).also {
            it.setPixels(dst, 0, dstW, 0, 0, dstW, dstH)
        }
    }

    override fun reset() {}
    override fun release() {}
}