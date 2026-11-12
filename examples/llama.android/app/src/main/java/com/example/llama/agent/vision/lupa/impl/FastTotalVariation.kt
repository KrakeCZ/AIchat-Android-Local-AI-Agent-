package com.example.llama.agent.vision.camera.math

import kotlin.math.abs

/**
 * Enterprise-grade Zero-Allocation Fast Total Variation Upscaler.
 * Jednoprůchodová aproximace L1 Gradient Minimizeru pro zachování absolutně ostrých hran.
 */
object FastTotalVariation {

    private var sourceBuffer = IntArray(0)
    private var destBuffer = IntArray(0)

    private const val LAMBDA = 0.5f // Síla zachování hran
    private const val EPSILON = 5.0f // Stabilizátor rovnice (zabraňuje color glitchům při dělení blízkém nule)

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
                
                destBuffer[destRowOffset + dx] = sampleTV(
                    sourceBuffer, srcW, srcH, srcCenterX, srcCenterY
                )
            }
        }

        dstBitmap.setPixels(destBuffer, 0, dstW, 0, 0, dstW, dstH)
    }

    private fun sampleTV(src: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val px = x.toInt()
        val py = y.toInt()

        // Okrajové záchyty
        val pxLeft = if (px - 1 < 0) 0 else px - 1
        val pxRight = if (px + 1 >= w) w - 1 else px + 1
        val pyTop = if (py - 1 < 0) 0 else py - 1
        val pyBottom = if (py + 1 >= h) h - 1 else py + 1

        val pC = src[py * w + px]
        val pL = src[py * w + pxLeft]
        val pR = src[py * w + pxRight]
        val pT = src[pyTop * w + px]
        val pB = src[pyBottom * w + px]

        // 1. Krok: Extrakce barev
        val rC = (pC shr 16) and 0xFF
        val gC = (pC shr 8) and 0xFF
        val bC = pC and 0xFF

        val rL = (pL shr 16) and 0xFF; val rR = (pR shr 16) and 0xFF
        val rT = (pT shr 16) and 0xFF; val rB = (pB shr 16) and 0xFF
        val gL = (pL shr 8) and 0xFF; val gR = (pR shr 8) and 0xFF
        val gT = (pT shr 8) and 0xFF; val gB = (pB shr 8) and 0xFF
        val bL = pL and 0xFF; val bR = pR and 0xFF
        val bT = pT and 0xFF; val bB = pB and 0xFF

        // 2. Krok: Výpočet L1 gradientů (Total Variation difuzní koeficient)
        // Čím vyšší je gradient, tím blíž je difuzní koeficient k nule -> HRANA ZŮSTÁVÁ
        val diffR_X = abs(rR - rL).toFloat(); val diffR_Y = abs(rB - rT).toFloat()
        val diffG_X = abs(gR - gL).toFloat(); val diffG_Y = abs(gB - gT).toFloat()
        val diffB_X = abs(bR - bL).toFloat(); val diffB_Y = abs(bB - bT).toFloat()

        // Gradient descent step se stabilizátorem (zabraňuje extrémním hodnotám a glitchům)
        val stepR = ((rR + rL + rT + rB - 4 * rC) / (kotlin.math.sqrt(diffR_X * diffR_X + diffR_Y * diffR_Y) + EPSILON)).coerceIn(-255f, 255f)
        val stepG = ((gR + gL + gT + gB - 4 * gC) / (kotlin.math.sqrt(diffG_X * diffG_X + diffG_Y * diffG_Y) + EPSILON)).coerceIn(-255f, 255f)
        val stepB = ((bR + bL + bT + bB - 4 * bC) / (kotlin.math.sqrt(diffB_X * diffB_X + diffB_Y * diffB_Y) + EPSILON)).coerceIn(-255f, 255f)

        // 3. Krok: Update pixelu a bilineární interpolace
        val fx = x - px
        val fy = y - py

        // Regulovaný posun (Denoise) s plynulým přechodem na sub-pixel (Zoom)
        val finalR = (rC + LAMBDA * stepR * (1 - fx) * (1 - fy)).toInt().coerceIn(0, 255)
        val finalG = (gC + LAMBDA * stepG * (1 - fx) * (1 - fy)).toInt().coerceIn(0, 255)
        val finalB = (bC + LAMBDA * stepB * (1 - fx) * (1 - fy)).toInt().coerceIn(0, 255)

        return -0x1000000 or (finalR shl 16) or (finalG shl 8) or finalB
    }
}