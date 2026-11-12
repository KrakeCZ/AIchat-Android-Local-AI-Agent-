package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Richardson-Lucy Deconvolution — prostorová doména (Gaussovo PSF).
 * Standard v astronomii (Hubble Space Telescope image restoration).
 *
 * Odstraní atmosférický blur před upscalem — ideální pro pozorování mraků
 * přes turbulentní vzduch nebo při long-exposure focení rogala.
 *
 * Algoritmus:
 * u(t+1) = u(t) * ( PSF_flipped ⊛ (y / (PSF ⊛ u(t))) )
 *
 * kde ⊛ je konvoluce, y je pozorovaný (blurred) obraz,
 * u(t) je HR odhad v iteraci t.
 */
class RichardsonLucyPlugin(
    private val iterations: Int = 15,
    private val psfSigma: Float = 1.2f,
    private val psfRadius: Int = 3
) : LupaPlugin {
    override val pluginId = "lupa_richardson_lucy"
    override val displayName = "Richardson-Lucy (Astronomická dekonv.)"
    override val category = LupaCategory.DECONVOLUTION
    override val expectedFpsHint = 3

    private val psf: FloatArray by lazy { buildGaussianPsf(psfSigma, psfRadius) }
    private val psfFlipped: FloatArray by lazy { flipKernel(psf, 2 * psfRadius + 1) }

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val w = input.width; val h = input.height

        val srcPx = IntArray(w * h)
        input.getPixels(srcPx, 0, w, 0, 0, w, h)

        val rOut = rlDeconvolve(extractChannel(srcPx, 16), w, h)
        val gOut = rlDeconvolve(extractChannel(srcPx, 8), w, h)
        val bOut = rlDeconvolve(extractChannel(srcPx, 0), w, h)

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

    private fun rlDeconvolve(observed: FloatArray, w: Int, h: Int): FloatArray {
        var u = observed.copyOf()  // počáteční odhad = pozorovaný obraz

        repeat(iterations) {
            val uConvPsf = convolve(u, w, h, psf, psfRadius)

            // Podíl: y / (PSF ⊛ u), s floor 1e-6 pro numerickou stabilitu
            val ratio = FloatArray(w * h) { i ->
                val denom = uConvPsf[i]
                if (denom > 1e-6f) observed[i] / denom else 0f
            }

            val correctionTerm = convolve(ratio, w, h, psfFlipped, psfRadius)

            // Multiplikativní update
            for (i in u.indices) {
                u[i] = (u[i] * correctionTerm[i]).coerceIn(0f, 255f)
            }
        }
        return u
    }

    private fun convolve(signal: FloatArray, w: Int, h: Int, kernel: FloatArray, r: Int): FloatArray {
        val result = FloatArray(w * h)
        val ks = 2 * r + 1
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                for (ky in -r..r) {
                    val sy = (y + ky).coerceIn(0, h - 1)
                    for (kx in -r..r) {
                        sum += signal[sy * w + (x + kx).coerceIn(0, w - 1)] *
                               kernel[(ky + r) * ks + (kx + r)]
                    }
                }
                result[y * w + x] = sum
            }
        }
        return result
    }

    private fun buildGaussianPsf(sigma: Float, radius: Int): FloatArray {
        val size = 2 * radius + 1
        val kernel = FloatArray(size * size)
        var sum = 0f
        for (ky in -radius..radius) {
            for (kx in -radius..radius) {
                val v = exp(-(kx * kx + ky * ky).toFloat() / (2f * sigma * sigma))
                kernel[(ky + radius) * size + (kx + radius)] = v
                sum += v
            }
        }
        for (i in kernel.indices) kernel[i] /= sum
        return kernel
    }

    private fun flipKernel(kernel: FloatArray, size: Int): FloatArray {
        return FloatArray(size * size) { i ->
            kernel[(size - 1 - i / size) * size + (size - 1 - i % size)]
        }
    }

    private fun extractChannel(pixels: IntArray, shift: Int): FloatArray =
        FloatArray(pixels.size) { i -> ((pixels[i] shr shift) and 0xFF).toFloat() }

    override fun reset() {}
    override fun release() {}
}