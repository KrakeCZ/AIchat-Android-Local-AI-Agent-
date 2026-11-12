package com.example.llama.agent.vision.camera.math

import kotlin.math.abs

/**
 * Enterprise-grade Zero-Allocation Fast NEDI (New Edge-Directed Interpolation)
 * Optimalizováno pro jednoprůchodový běh na CPU pomocí JIT (žádné NDK).
 */
object FastNEDI {

    private var sourceBuffer = IntArray(0)
    private var destBuffer = IntArray(0)

    fun processZoom(
        srcBitmap: android.graphics.Bitmap,
        dstBitmap: android.graphics.Bitmap,
        cropX: Float,
        cropY: Float,
        cropW: Float,
        cropH: Float
    ) {
        val srcW = srcBitmap.width
        val srcH = srcBitmap.height
        val dstW = dstBitmap.width
        val dstH = dstBitmap.height

        val srcSize = srcW * srcH
        val dstSize = dstW * dstH

        if (sourceBuffer.size < srcSize) sourceBuffer = IntArray(srcSize)
        if (destBuffer.size < dstSize) destBuffer = IntArray(dstSize)

        srcBitmap.getPixels(sourceBuffer, 0, srcW, 0, 0, srcW, srcH)

        val scaleX = cropW / dstW
        val scaleY = cropH / dstH

        for (dy in 0 until dstH) {
            val srcCenterY = cropY + (dy * scaleY)
            val destRowOffset = dy * dstW

            for (dx in 0 until dstW) {
                val srcCenterX = cropX + (dx * scaleX)
                
                destBuffer[destRowOffset + dx] = sampleNEDI(
                    sourceBuffer, srcW, srcH, srcCenterX, srcCenterY
                )
            }
        }

        dstBitmap.setPixels(destBuffer, 0, dstW, 0, 0, dstW, dstH)
    }

    private fun sampleNEDI(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val px = x.toInt()
        val py = y.toInt()

        val pxLeft = if (px - 1 < 0) 0 else px - 1
        val pxRight = if (px + 1 >= w) w - 1 else px + 1
        val pyTop = if (py - 1 < 0) 0 else py - 1
        val pyBottom = if (py + 1 >= h) h - 1 else py + 1

        val pC = src[py * w + px]
        val pL = src[py * w + pxLeft]
        val pR = src[py * w + pxRight]
        val pT = src[pyTop * w + px]
        val pB = src[pyBottom * w + px]

        val rC = (pC shr 16) and 0xFF; val gC = (pC shr 8) and 0xFF; val bC = pC and 0xFF
        val rL = (pL shr 16) and 0xFF; val gL = (pL shr 8) and 0xFF; val bL = pL and 0xFF
        val rR = (pR shr 16) and 0xFF; val gR = (pR shr 8) and 0xFF; val bR = pR and 0xFF
        val rT = (pT shr 16) and 0xFF; val gT = (pT shr 8) and 0xFF; val bT = pT and 0xFF
        val rB = (pB shr 16) and 0xFF; val gB = (pB shr 8) and 0xFF; val bB = pB and 0xFF

        val diffH = abs(rL - rR) + abs(gL - gR) + abs(bL - bR)
        val diffV = abs(rT - rB) + abs(gT - gB) + abs(bT - bB)

        val totalDiff = (diffH + diffV).toFloat() + 0.001f
        val weightH = diffV / totalDiff
        val weightV = diffH / totalDiff

        val rH = (rL + rR) * 0.5f; val gH = (gL + gR) * 0.5f; val bH = (bL + bR) * 0.5f
        val rV = (rT + rB) * 0.5f; val gV = (gT + gB) * 0.5f; val bV = (bT + bB) * 0.5f

        val normSum = weightH + weightV + 0.001f
        val normH = weightH / normSum
        val normV = weightV / normSum

        val finalR = (rC * 0.5f + (rH * normH + rV * normV) * 0.5f).toInt().coerceIn(0, 255)
        val finalG = (gC * 0.5f + (gH * normH + gV * normV) * 0.5f).toInt().coerceIn(0, 255)
        val finalB = (bC * 0.5f + (bH * normH + bV * normV) * 0.5f).toInt().coerceIn(0, 255)

        return -0x1000000 or (finalR shl 16) or (finalG shl 8) or finalB
    }
}