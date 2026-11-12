package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Blind Deconvolution — Alternating Minimization Richardson-Lucy (Varianta A).
 * 
 * Pravá analytická blind dekonvoluce, kde algoritmus nezná rozmazání (PSF)
 * a musí odhadovat zároveň ostrý obraz i samotné PSF.
 * 
 * Využívá "Alternating Minimization":
 * 1. Zafixuje odhad PSF a aktualizuje obraz (Richardson-Lucy krok).
 * 2. Zafixuje odhad obrazu a aktualizuje PSF.
 * 
 * Jde o matematicky korektní iterativní proces hojně využívaný v post-processingu
 * astronomických a mikroskopických snímků.
 */
class BlindDeconvIterativePlugin(
    private val iterations: Int = 5,
    private val psfRadius: Int = 3
) : LupaPlugin {
    override val pluginId = "lupa_blind_deconv_iterative"
    override val displayName = "Blind Deconv (Iterative RL)"
    override val category = LupaCategory.DECONVOLUTION
    override val expectedFpsHint = 1 // Velmi výpočetně náročné

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val w = input.width
        val h = input.height

        val srcPx = IntArray(w * h)
        input.getPixels(srcPx, 0, w, 0, 0, w, h)

        val rObserved = extractChannel(srcPx, 16)
        val gObserved = extractChannel(srcPx, 8)
        val bObserved = extractChannel(srcPx, 0)

        // Odhadované PSF začíná jako uniformní blur (nevím nic)
        val psfSize = 2 * psfRadius + 1
        val initialPsf = FloatArray(psfSize * psfSize) { 1f / (psfSize * psfSize) }

        val rOut = blindRlDeconvolve(rObserved, initialPsf.copyOf(), w, h, psfRadius)
        val gOut = blindRlDeconvolve(gObserved, initialPsf.copyOf(), w, h, psfRadius)
        val bOut = blindRlDeconvolve(bObserved, initialPsf.copyOf(), w, h, psfRadius)

        val outPx = IntArray(w * h) { i ->
            (0xFF shl 24) or
                (rOut[i].toInt().coerceIn(0, 255) shl 16) or
                (gOut[i].toInt().coerceIn(0, 255) shl 8) or
                (bOut[i].toInt().coerceIn(0, 255))
        }

        val outBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outPx, 0, w, 0, 0, w, h)
        }

        val dstW = (w * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (h * scaleFactor).toInt().coerceAtLeast(1)
        val result = Bitmap.createScaledBitmap(outBmp, dstW, dstH, true)
        outBmp.recycle()
        return result
    }

    private fun blindRlDeconvolve(observed: FloatArray, psf: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var u = observed.copyOf() // Odhadovaný obraz
        var hPsf = psf            // Odhadované PSF

        val tempRatio = FloatArray(w * h)
        val psfSize = 2 * r + 1

        repeat(iterations) {
            // --- Krok 1: Zafixovat PSF, aktualizovat Obraz (u) ---
            val psfFlipped = flipKernel(hPsf, psfSize)
            val uConvPsf = convolve(u, w, h, hPsf, r)

            for (i in 0 until w * h) {
                val denom = uConvPsf[i]
                tempRatio[i] = if (denom > 1e-6f) observed[i] / denom else 0f
            }

            val uCorrection = convolve(tempRatio, w, h, psfFlipped, r)
            for (i in u.indices) {
                u[i] = (u[i] * uCorrection[i]).coerceIn(0f, 255f)
            }

            // --- Krok 2: Zafixovat Obraz, aktualizovat PSF (hPsf) ---
            // U klasické Blind RL se PSF aktualizuje stejnou multiplikativní logikou.
            // h(t+1) = h(t) * ( u_flipped ⊛ (y / (h(t) ⊛ u)) )
            // Pro mobilní CPU optimalizaci a zabránění zhroucení PSF do delty, 
            // děláme pouze jeden krok a aplikujeme normalizaci.
            val uConvPsfNew = convolve(u, w, h, hPsf, r)
            for (i in 0 until w * h) {
                val denom = uConvPsfNew[i]
                tempRatio[i] = if (denom > 1e-6f) observed[i] / denom else 0f
            }

            var sumPsf = 0f
            for (ky in -r..r) {
                for (kx in -r..r) {
                    var hCorrection = 0f
                    // Korelace mezi tempRatio a u_flipped
                    for (y in 0 until h) {
                        val sy = (y + ky).coerceIn(0, h - 1)
                        for (x in 0 until w) {
                            val sx = (x + kx).coerceIn(0, w - 1)
                            hCorrection += tempRatio[sy * w + sx] * u[y * w + x]
                        }
                    }
                    val idx = (ky + r) * psfSize + (kx + r)
                    hPsf[idx] *= hCorrection
                    sumPsf += hPsf[idx]
                }
            }

            // Normalizace PSF, aby součet prvků byl 1.0 (zachování jasu)
            if (sumPsf > 1e-6f) {
                for (i in hPsf.indices) hPsf[i] /= sumPsf
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

    private fun flipKernel(kernel: FloatArray, size: Int): FloatArray {
        return FloatArray(size * size) { i ->
            kernel[(size - 1 - i / size) * size + (size - 1 - i % size)]
        }
    }

    private fun extractChannel(pixels: IntArray, shift: Int): FloatArray {
        val out = FloatArray(pixels.size)
        for (i in pixels.indices) {
            out[i] = ((pixels[i] shr shift) and 0xFF).toFloat()
        }
        return out
    }

    override fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap {
        return process(frames.last(), scaleFactor)
    }

    override fun reset() {}
    override fun release() {}
}
