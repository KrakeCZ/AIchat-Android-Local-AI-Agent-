package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Optical Flow Super Resolution (Sub-pixel Shift & Add).
 * 
 * Využívá prostorové gradienty (Lucas-Kanade) k určení globálního
 * sub-pixelového posunu mezi snímky. Následně všechny snímky srovná
 * do sub-pixel mřížky (Shift & Add) pro fyzické zvýšení rozlišení.
 * 
 * Ideální pro vědecké experimenty žáků s pohybem ruky (hand tremor).
 */
class OpticalFlowSrPlugin : LupaPlugin {
    override val pluginId = "lupa_optical_flow_sr"
    override val displayName = "Optical Flow SR (Sub-pixel)"
    override val category = LupaCategory.MULTI_FRAME
    override val expectedFpsHint = 5
    override val requiresMultipleFrames = true
    override val minFramesRequired = 3

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val dstW = (input.width * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (input.height * scaleFactor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, dstW, dstH, true)
    }

    override fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap {
        if (frames.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        
        val w = frames[0].width
        val h = frames[0].height
        val dstW = (w * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (h * scaleFactor).toInt().coerceAtLeast(1)

        // Před-alokace pro sub-pixel akumulaci na zvětšené (HR) mřížce
        val rAcc = FloatArray(dstW * dstH)
        val gAcc = FloatArray(dstW * dstH)
        val bAcc = FloatArray(dstW * dstH)
        val wAcc = FloatArray(dstW * dstH) // weight accumulator

        // Referenční snímek převedeme na jas (Luma) pro LK Optical Flow
        val refPx = IntArray(w * h)
        frames[0].getPixels(refPx, 0, w, 0, 0, w, h)
        val refLuma = toLuma(refPx, w, h)
        
        // Prostorové gradienty referenčního snímku (Ix, Iy)
        val gradX = FloatArray(w * h)
        val gradY = FloatArray(w * h)
        computeGradients(refLuma, w, h, gradX, gradY)

        // Zahrnutí referenčního snímku do HR mřížky (shift 0,0) pomocí Gather
        gatherSubPixel(refPx, w, h, dstW, dstH, scaleFactor, 0f, 0f, rAcc, gAcc, bAcc, wAcc)

        for (f in 1 until frames.size) {
            val framePx = IntArray(w * h)
            frames[f].getPixels(framePx, 0, w, 0, 0, w, h)
            val frameLuma = toLuma(framePx, w, h)

            // Odhad globálního sub-pixel posunu vůči referenčnímu
            val (dx, dy) = computeGlobalOpticalFlow(refLuma, frameLuma, gradX, gradY, w, h)
            
            // Omezíme maximální shift, abychom neulítli (např. při velkém pohybu)
            val boundedDx = dx.coerceIn(-w / 2f, w / 2f)
            val boundedDy = dy.coerceIn(-h / 2f, h / 2f)

            gatherSubPixel(framePx, w, h, dstW, dstH, scaleFactor, boundedDx, boundedDy, rAcc, gAcc, bAcc, wAcc)
        }

        val stackedPx = IntArray(dstW * dstH) { i ->
            val weight = wAcc[i].coerceAtLeast(1e-6f)
            (0xFF shl 24) or
                ((rAcc[i] / weight).toInt().coerceIn(0, 255) shl 16) or
                ((gAcc[i] / weight).toInt().coerceIn(0, 255) shl 8) or
                ((bAcc[i] / weight).toInt().coerceIn(0, 255))
        }

        // Aplikujeme lehký "Laplacian Sharpening", abychom odstranili 
        // přirozený blur (rozmazání), který vzniká skládáním a bilineární interpolací.
        val sharpenedPx = applySharpening(stackedPx, dstW, dstH)

        val result = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        result.setPixels(sharpenedPx, 0, dstW, 0, 0, dstW, dstH)
        return result
    }

    /** Lucas-Kanade odhad globální translace (u, v) */
    private fun computeGlobalOpticalFlow(
        refLuma: FloatArray, 
        frameLuma: FloatArray, 
        gradX: FloatArray, 
        gradY: FloatArray, 
        w: Int, 
        h: Int
    ): Pair<Float, Float> {
        var sumIxx = 0.0
        var sumIyy = 0.0
        var sumIxy = 0.0
        var sumIxt = 0.0
        var sumIyt = 0.0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val ix = gradX[i]
                val iy = gradY[i]
                // Časový gradient It = frame(t) - frame(t-1)
                val it = frameLuma[i] - refLuma[i]

                sumIxx += (ix * ix).toDouble()
                sumIyy += (iy * iy).toDouble()
                sumIxy += (ix * iy).toDouble()
                sumIxt += (ix * it).toDouble()
                sumIyt += (iy * it).toDouble()
            }
        }

        // Řešení soustavy 2x2: A * [u v]^T = -b
        // [ sumIxx sumIxy ] [u] = [ -sumIxt ]
        // [ sumIxy sumIyy ] [v] = [ -sumIyt ]
        val det = sumIxx * sumIyy - sumIxy * sumIxy
        if (det < 1e-6) return Pair(0f, 0f) // Singularní (např. prázdná jednobarevná plocha)

        val u = (sumIyy * (-sumIxt) - sumIxy * (-sumIyt)) / det
        val v = (sumIxx * (-sumIyt) - sumIxy * (-sumIxt)) / det

        return Pair(u.toFloat(), v.toFloat())
    }

    private fun computeGradients(luma: FloatArray, w: Int, h: Int, gx: FloatArray, gy: FloatArray) {
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                // Sobel / Central difference
                gx[i] = (luma[i + 1] - luma[i - 1]) * 0.5f
                gy[i] = (luma[i + w] - luma[i - w]) * 0.5f
            }
        }
    }

    private fun gatherSubPixel(
        pixels: IntArray, w: Int, h: Int, 
        dstW: Int, dstH: Int, 
        scaleFactor: Float, 
        shiftX: Float, shiftY: Float,
        rAcc: FloatArray, gAcc: FloatArray, bAcc: FloatArray, wAcc: FloatArray
    ) {
        // Inverse Mapping (Gather) - pro každý HR pixel najdeme hodnotu v LR snímku.
        // Zabrání to vzniku "děr" (grainy artifacts), které tvořil forward splatting.
        for (y in 0 until dstH) {
            for (x in 0 until dstW) {
                // Přepočet z HR mřížky zpět na LR mřížku se zohledněním sub-pixelového posunu
                val lrX = (x / scaleFactor) - shiftX
                val lrY = (y / scaleFactor) - shiftY

                val x0 = lrX.toInt()
                val y0 = lrY.toInt()
                
                // Pokud jsme mimo okraje obrazu, přeskočíme
                if (x0 < 0 || y0 < 0 || x0 >= w - 1 || y0 >= h - 1) continue

                val dx = lrX - x0
                val dy = lrY - y0

                // Načteme 4 nejbližší LR pixely
                val p00 = pixels[y0 * w + x0]
                val p10 = pixels[y0 * w + (x0 + 1)]
                val p01 = pixels[(y0 + 1) * w + x0]
                val p11 = pixels[(y0 + 1) * w + (x0 + 1)]

                // Bilineární váhy
                val w00 = (1f - dx) * (1f - dy)
                val w10 = dx * (1f - dy)
                val w01 = (1f - dx) * dy
                val w11 = dx * dy

                // Interpolace RGB
                val r = ((p00 shr 16) and 0xFF) * w00 + ((p10 shr 16) and 0xFF) * w10 + ((p01 shr 16) and 0xFF) * w01 + ((p11 shr 16) and 0xFF) * w11
                val g = ((p00 shr 8) and 0xFF) * w00 + ((p10 shr 8) and 0xFF) * w10 + ((p01 shr 8) and 0xFF) * w01 + ((p11 shr 8) and 0xFF) * w11
                val b = (p00 and 0xFF) * w00 + (p10 and 0xFF) * w10 + (p01 and 0xFF) * w01 + (p11 and 0xFF) * w11

                val i = y * dstW + x
                rAcc[i] += r
                gAcc[i] += g
                bAcc[i] += b
                wAcc[i] += 1f
            }
        }
    }

    private fun toLuma(pixels: IntArray, w: Int, h: Int): FloatArray {
        val luma = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            luma[i] = (((p shr 16) and 0xFF) * 0.299f +
                       ((p shr 8) and 0xFF) * 0.587f +
                       (p and 0xFF) * 0.114f)
        }
        return luma
    }

    private fun applySharpening(src: IntArray, w: Int, h: Int): IntArray {
        val dst = IntArray(w * h)
        // Rychlý 3x3 kernel: 
        //  0 -1  0
        // -1  5 -1
        //  0 -1  0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                
                val pC = src[i]
                val pT = src[i - w]
                val pB = src[i + w]
                val pL = src[i - 1]
                val pR = src[i + 1]

                // Red
                val r = (5 * ((pC shr 16) and 0xFF) - ((pT shr 16) and 0xFF) - ((pB shr 16) and 0xFF) - ((pL shr 16) and 0xFF) - ((pR shr 16) and 0xFF)).coerceIn(0, 255)
                // Green
                val g = (5 * ((pC shr 8) and 0xFF) - ((pT shr 8) and 0xFF) - ((pB shr 8) and 0xFF) - ((pL shr 8) and 0xFF) - ((pR shr 8) and 0xFF)).coerceIn(0, 255)
                // Blue
                val b = (5 * (pC and 0xFF) - (pT and 0xFF) - (pB and 0xFF) - (pL and 0xFF) - (pR and 0xFF)).coerceIn(0, 255)

                dst[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        
        // Zkopírování okrajů (aby nezůstaly černé)
        for (x in 0 until w) {
            dst[x] = src[x]
            dst[(h - 1) * w + x] = src[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            dst[y * w] = src[y * w]
            dst[y * w + w - 1] = src[y * w + w - 1]
        }

        return dst
    }

    override fun reset() {}
    override fun release() {}
}
