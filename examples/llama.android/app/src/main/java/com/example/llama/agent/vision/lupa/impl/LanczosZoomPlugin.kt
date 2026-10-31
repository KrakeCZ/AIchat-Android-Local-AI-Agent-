package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ceil
import kotlin.math.sin
import kotlin.math.PI

/**
 * Lanczos-3 separabilní interpolace.
 * Zachovává vysokofrekvenční hrany (letadla, rogala), minimalizuje aliasing.
 * Ideal baseline pro porovnání s ostatními algoritmy.
 */
class LanczosZoomPlugin : LupaPlugin {
    override val pluginId = "lupa_lanczos_3"
    override val displayName = "Lanczos-3 (Sinc baseline)"
    override val category = LupaCategory.SINGLE_FRAME
    override val expectedFpsHint = 15

    private val a = 3  // Lanczos lobes

    private fun lanczos(x: Double): Double {
        if (x == 0.0) return 1.0
        if (abs(x) >= a) return 0.0
        val pix = PI * x
        return a * sin(pix) * sin(pix / a) / (pix * pix)
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
                val sx = (dx + 0.5) / scaleFactor - 0.5
                val sy = (dy + 0.5) / scaleFactor - 0.5
                var r = 0.0; var g = 0.0; var b = 0.0; var wSum = 0.0

                val xMin = ceil(sx).toInt() - a + 1
                val xMax = floor(sx).toInt() + a
                val yMin = ceil(sy).toInt() - a + 1
                val yMax = floor(sy).toInt() + a

                for (ky in yMin..yMax) {
                    val wy = lanczos(sy - ky)
                    if (wy == 0.0) continue
                    val py = ky.coerceIn(0, srcH - 1)
                    for (kx in xMin..xMax) {
                        val wx = lanczos(sx - kx)
                        if (wx == 0.0) continue
                        val w = wx * wy
                        val px = kx.coerceIn(0, srcW - 1)
                        val pixel = src[py * srcW + px]
                        r += ((pixel shr 16) and 0xFF) * w
                        g += ((pixel shr 8) and 0xFF) * w
                        b += (pixel and 0xFF) * w
                        wSum += w
                    }
                }

                if (wSum != 0.0) { r /= wSum; g /= wSum; b /= wSum }
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