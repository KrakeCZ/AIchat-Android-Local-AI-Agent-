package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Blind Deconvolution — Fast PDE Shock Filter (Varianta B).
 * 
 * Nelineární parciální diferenciální rovnice (PDE), která reverzuje blur 
 * bez znalosti Point Spread Function (PSF). 
 * Algoritmus detekuje inflexní body (kde druhá derivace mění znaménko)
 * a "tlačí" gradient do absolutně ostré hrany (šoku).
 * 
 * Matematicky to simuluje dokonalou dekonvoluci pro neznámý blur.
 */
class BlindDeconvShockPlugin(
    private val iterations: Int = 5,
    private val dt: Float = 0.5f // Time step
) : LupaPlugin {
    override val pluginId = "lupa_blind_deconv_shock"
    override val displayName = "Blind Deconv (PDE Shock Filter)"
    override val category = LupaCategory.DECONVOLUTION
    override val expectedFpsHint = 10

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val w = input.width
        val h = input.height

        val srcPx = IntArray(w * h)
        input.getPixels(srcPx, 0, w, 0, 0, w, h)

        val rChannel = extractChannel(srcPx, 16)
        val gChannel = extractChannel(srcPx, 8)
        val bChannel = extractChannel(srcPx, 0)

        // Před-alokace bufferů pro PDE výpočet (zero-allocation in loop)
        val laplacian = FloatArray(w * h)
        val gradMag = FloatArray(w * h)

        shockFilterPDELumaSync(rChannel, gChannel, bChannel, w, h, laplacian, gradMag)

        val outPx = IntArray(w * h) { i ->
            (0xFF shl 24) or
                (rChannel[i].toInt().coerceIn(0, 255) shl 16) or
                (gChannel[i].toInt().coerceIn(0, 255) shl 8) or
                (bChannel[i].toInt().coerceIn(0, 255))
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

    private fun shockFilterPDELumaSync(
        r: FloatArray, g: FloatArray, b: FloatArray, 
        w: Int, h: Int, 
        L: FloatArray, G: FloatArray
    ) {
        val luma = FloatArray(w * h)
        repeat(iterations) {
            // Vypočteme aktuální Luma (jas) pro tuto iteraci
            for (i in 0 until w * h) {
                luma[i] = r[i] * 0.299f + g[i] * 0.587f + b[i] * 0.114f
            }

            // 1. Spočítat Laplaceův operátor a gradient JEN nad jasem (zabrání color glitchům)
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    
                    val ux = (luma[i + 1] - luma[i - 1]) * 0.5f
                    val uy = (luma[i + w] - luma[i - w]) * 0.5f
                    val uxx = luma[i + 1] - 2 * luma[i] + luma[i - 1]
                    val uyy = luma[i + w] - 2 * luma[i] + luma[i - w]

                    L[i] = uxx + uyy
                    G[i] = sqrt(ux * ux + uy * uy)
                }
            }

            // 2. PDE Time evolution: synchronně posouváme RGB kanály podle řídící rovnice Jasu
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val update = -sign(L[i]) * G[i]
                    val step = dt * update
                    
                    r[i] = (r[i] + step).coerceIn(0f, 255f)
                    g[i] = (g[i] + step).coerceIn(0f, 255f)
                    b[i] = (b[i] + step).coerceIn(0f, 255f)
                }
            }
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
        // Fallback pro multi-frame mód: aplikujeme na nejnovější snímek
        return process(frames.last(), scaleFactor)
    }

    override fun reset() {}
    override fun release() {}
}
