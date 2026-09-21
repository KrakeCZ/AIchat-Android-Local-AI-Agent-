package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import com.example.llama.agent.vision.lupa.math.FftUtils
import kotlin.math.sqrt

/**
 * Phase Correlation Sub-pixel Registration + Multi-frame Stack.
 *
 * Registrace snímků přesná na sub-pixel (FFT fázová korelace).
 * Přesnější než optický tok pro rigidní pohyb (kamera drží pevně rogalo).
 *
 * Algoritmus:
 * 1. FFT referenčního framu a každého dalšího framu
 * 2. Cross-power spectrum: C = (R ⊗ conj(F)) / |R ⊗ conj(F)|
 * 3. IFFT(C) → korelační plocha; peak = sub-pixel shift
 * 4. Zarovnej frame na shift → akumuluj → průměruj
 */
class PhaseCorrelationPlugin : LupaPlugin {
    override val pluginId = "lupa_phase_correlation"
    override val displayName = "Phase Correlation (Sub-pixel reg.)"
    override val category = LupaCategory.MULTI_FRAME
    override val expectedFpsHint = 4
    override val requiresMultipleFrames = true
    override val minFramesRequired = 3

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val dstW = (input.width * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (input.height * scaleFactor).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, dstW, dstH, true)
    }

    override fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap {
        val w = frames[0].width; val h = frames[0].height
        val dstW = (w * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (h * scaleFactor).toInt().coerceAtLeast(1)
        val fftW = FftUtils.nextPow2(w); val fftH = FftUtils.nextPow2(h)

        val refPx = IntArray(w * h)
        frames[0].getPixels(refPx, 0, w, 0, 0, w, h)
        val refLuma = toLuma(refPx, w, h)

        // FFT referenčního framu
        val refRe = Array(fftH) { y -> FloatArray(fftW) { x -> if (y < h && x < w) refLuma[y * w + x] else 0f } }
        val refIm = Array(fftH) { FloatArray(fftW) }
        FftUtils.fft2D(refRe, refIm)

        val rAcc = FloatArray(w * h); val gAcc = FloatArray(w * h); val bAcc = FloatArray(w * h)
        var count = 0

        // Referenční frame vždy zahrnout (shift = 0,0)
        for (i in 0 until w * h) {
            rAcc[i] += ((refPx[i] shr 16) and 0xFF).toFloat()
            gAcc[i] += ((refPx[i] shr 8) and 0xFF).toFloat()
            bAcc[i] += (refPx[i] and 0xFF).toFloat()
        }
        count++

        for (f in 1 until frames.size) {
            val framePx = IntArray(w * h)
            frames[f].getPixels(framePx, 0, w, 0, 0, w, h)
            val frameLuma = toLuma(framePx, w, h)

            val (dx, dy) = computeShift(refRe, refIm, frameLuma, w, h, fftW, fftH)

            // Integer shift — dostatečně přesný pro stacking
            val shiftX = Math.round(dx); val shiftY = Math.round(dy)

            for (y in 0 until h) {
                for (x in 0 until w) {
                    val srcX = (x - shiftX).coerceIn(0, w - 1)
                    val srcY = (y - shiftY).coerceIn(0, h - 1)
                    val pixel = framePx[srcY * w + srcX]
                    val i = y * w + x
                    rAcc[i] += ((pixel shr 16) and 0xFF).toFloat()
                    gAcc[i] += ((pixel shr 8) and 0xFF).toFloat()
                    bAcc[i] += (pixel and 0xFF).toFloat()
                }
            }
            count++
        }

        val stackedPx = IntArray(w * h) { i ->
            (0xFF shl 24) or
                ((rAcc[i] / count).toInt().coerceIn(0, 255) shl 16) or
                ((gAcc[i] / count).toInt().coerceIn(0, 255) shl 8) or
                (bAcc[i] / count).toInt().coerceIn(0, 255)
        }

        val stacked = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(stackedPx, 0, w, 0, 0, w, h)
        }
        val result = Bitmap.createScaledBitmap(stacked, dstW, dstH, true)
        stacked.recycle()
        return result
    }

    private fun computeShift(
        refRe: Array<FloatArray>, refIm: Array<FloatArray>,
        frameLuma: FloatArray, w: Int, h: Int, fftW: Int, fftH: Int
    ): Pair<Float, Float> {
        val frmRe = Array(fftH) { y -> FloatArray(fftW) { x -> if (y < h && x < w) frameLuma[y * w + x] else 0f } }
        val frmIm = Array(fftH) { FloatArray(fftW) }
        FftUtils.fft2D(frmRe, frmIm)

        // Cross-power spectrum C = (Ref · conj(Frame)) / |Ref · conj(Frame)|
        val cRe = Array(fftH) { FloatArray(fftW) }
        val cIm = Array(fftH) { FloatArray(fftW) }
        for (fy in 0 until fftH) {
            for (fx in 0 until fftW) {
                val rr = refRe[fy][fx]; val ri = refIm[fy][fx]
                val fr = frmRe[fy][fx]; val fi = frmIm[fy][fx]
                val cr = rr * fr + ri * fi   // Ref · conj(Frame): ri*-fi + rr*fr
                val ci = ri * fr - rr * fi
                val mag = sqrt(cr * cr + ci * ci).coerceAtLeast(1e-6f)
                cRe[fy][fx] = cr / mag; cIm[fy][fx] = ci / mag
            }
        }

        FftUtils.fft2D(cRe, cIm, inverse = true)

        // Peak hledání
        var maxVal = Float.NEGATIVE_INFINITY; var peakX = 0; var peakY = 0
        for (fy in 0 until fftH) {
            for (fx in 0 until fftW) {
                if (cRe[fy][fx] > maxVal) { maxVal = cRe[fy][fx]; peakX = fx; peakY = fy }
            }
        }

        // Wrap-around konverze na podepsaný shift
        val shiftX = if (peakX > fftW / 2) (peakX - fftW).toFloat() else peakX.toFloat()
        val shiftY = if (peakY > fftH / 2) (peakY - fftH).toFloat() else peakY.toFloat()
        return Pair(shiftX, shiftY)
    }

    private fun toLuma(pixels: IntArray, w: Int, h: Int) = FloatArray(w * h) { i ->
        ((pixels[i] shr 16) and 0xFF) * 0.299f +
        ((pixels[i] shr 8) and 0xFF) * 0.587f +
        (pixels[i] and 0xFF) * 0.114f
    }

    override fun reset() {}
    override fun release() {}
}