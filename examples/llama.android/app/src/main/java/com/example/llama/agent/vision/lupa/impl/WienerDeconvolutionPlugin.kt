package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import com.example.llama.agent.vision.lupa.math.FftUtils
import kotlin.math.exp

/**
 * Wiener Deconvolution — frekvenční doména (FFT).
 *
 * Ĥ(f) = H*(f) / (|H(f)|² + 1/SNR)
 *
 * kde H(f) je FFT Gaussova PSF (atmosférický blur),
 * *(f) je komplexní konjugát, SNR odhadnuté poměr signál/šum.
 *
 * Jeden průchod FFT → Wiener filtr → IFFT → upscale.
 * Rychlejší než Richardson-Lucy (není iterativní).
 */
class WienerDeconvolutionPlugin(
    private val psfSigma: Float = 1.5f,
    private val snr: Float = 15f
) : LupaPlugin {
    override val pluginId = "lupa_wiener_deconv"
    override val displayName = "Wiener Deconvolution (FFT)"
    override val category = LupaCategory.DECONVOLUTION
    override val expectedFpsHint = 5

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val w = input.width; val h = input.height

        val srcPx = IntArray(w * h)
        input.getPixels(srcPx, 0, w, 0, 0, w, h)

        val fftW = FftUtils.nextPow2(w); val fftH = FftUtils.nextPow2(h)

        // Precompute PSF FFT (sdíleno přes kanály)
        val psfRe = Array(fftH) { FloatArray(fftW) }
        val psfIm = Array(fftH) { FloatArray(fftW) }
        buildGaussianPsfFft(psfRe, psfIm, fftW, fftH, psfSigma)
        FftUtils.fft2D(psfRe, psfIm)

        val rOut = wienerChannel(srcPx, w, h, fftW, fftH, 16, psfRe, psfIm)
        val gOut = wienerChannel(srcPx, w, h, fftW, fftH, 8, psfRe, psfIm)
        val bOut = wienerChannel(srcPx, w, h, fftW, fftH, 0, psfRe, psfIm)

        val deconvPx = IntArray(w * h) { i ->
            (0xFF shl 24) or
                (rOut[i].toInt().coerceIn(0, 255) shl 16) or
                (gOut[i].toInt().coerceIn(0, 255) shl 8) or
                bOut[i].toInt().coerceIn(0, 255)
        }

        val deconvBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(deconvPx, 0, w, 0, 0, w, h)
        }

        val dstW = (w * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (h * scaleFactor).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(deconvBmp, dstW, dstH, true)
        deconvBmp.recycle()
        return result
    }

    private fun wienerChannel(
        pixels: IntArray, w: Int, h: Int, fftW: Int, fftH: Int, shift: Int,
        psfRe: Array<FloatArray>, psfIm: Array<FloatArray>
    ): FloatArray {
        val sigRe = Array(fftH) { y -> FloatArray(fftW) { x ->
            if (y < h && x < w) ((pixels[y * w + x] shr shift) and 0xFF).toFloat() else 0f
        }}
        val sigIm = Array(fftH) { FloatArray(fftW) }

        FftUtils.fft2D(sigRe, sigIm)

        // Wiener filtr ve frekvenční doméně
        for (fy in 0 until fftH) {
            for (fx in 0 until fftW) {
                val hr = psfRe[fy][fx]; val hi = psfIm[fy][fx]
                val hMagSq = hr * hr + hi * hi
                val denom = hMagSq + (1f / snr)
                // H*(f) / (|H(f)|² + 1/SNR) = (hr - j*hi) / denom
                val filterRe = hr / denom; val filterIm = -hi / denom
                val yr = sigRe[fy][fx]; val yi = sigIm[fy][fx]
                sigRe[fy][fx] = yr * filterRe - yi * filterIm
                sigIm[fy][fx] = yr * filterIm + yi * filterRe
            }
        }

        FftUtils.fft2D(sigRe, sigIm, inverse = true)

        return FloatArray(w * h) { i ->
            sigRe[i / w][i % w].coerceIn(0f, 255f)
        }
    }

    private fun buildGaussianPsfFft(
        re: Array<FloatArray>, im: Array<FloatArray>,
        fftW: Int, fftH: Int, sigma: Float
    ) {
        var sum = 0.0
        for (fy in 0 until fftH) {
            for (fx in 0 until fftW) {
                // Gaussovo PSF centrované v [0,0] pro korektní FFT konvoluci
                val dy = if (fy <= fftH / 2) fy.toDouble() else (fy - fftH).toDouble()
                val dx = if (fx <= fftW / 2) fx.toDouble() else (fx - fftW).toDouble()
                val v = exp(-(dx * dx + dy * dy) / (2.0 * sigma * sigma)).toFloat()
                re[fy][fx] = v
                sum += v
            }
        }
        // Normalizace PSF (suma = 1)
        for (fy in 0 until fftH) for (fx in 0 until fftW) re[fy][fx] = (re[fy][fx] / sum).toFloat()
    }

    override fun reset() {}
    override fun release() {}
}