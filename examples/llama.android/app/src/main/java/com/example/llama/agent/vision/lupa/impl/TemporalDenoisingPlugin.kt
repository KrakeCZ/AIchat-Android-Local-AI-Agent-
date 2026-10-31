package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Temporal Denoising Plugin (Časová Redukce Šumu)
 * 
 * Princip: Sečte N po sobě jdoucích snímků. Jelikož šum senzoru (ISO noise) je 
 * zcela náhodný, jeho hodnoty kolísají kolem nuly a při zprůměrování N snímků
 * klesá jeho intenzita poměrem sqrt(N).
 * Statický obraz (objekt, text) zůstává 100% zachován a vystoupí ze šumu.
 * Ideální pro čtení textu ve tmě nebo stabilní makro objekty.
 */
class TemporalDenoisingPlugin : LupaPlugin {
    override val pluginId = "lupa_temporal_denoise"
    override val displayName = "Temporal Denoising (Noční mód)"
    override val category = LupaCategory.MULTI_FRAME
    override val minFramesRequired = 5 // Optimální rovnováha mezi odšuměním a rychlostí
    override val expectedFpsHint = 15

    private var outBuffer = IntArray(0)
    private var sumR = LongArray(0)
    private var sumG = LongArray(0)
    private var sumB = LongArray(0)

    override fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap {
        if (frames.isEmpty()) throw IllegalArgumentException("No frames provided")

        val firstFrame = frames.first()
        val width = firstFrame.width
        val height = firstFrame.height
        val size = width * height
        val n = frames.size

        // Alokace jen při změně rozlišení
        if (outBuffer.size < size) {
            outBuffer = IntArray(size)
            sumR = LongArray(size)
            sumG = LongArray(size)
            sumB = LongArray(size)
        }

        // Vynulování sčítacích polí
        for (i in 0 until size) {
            sumR[i] = 0L
            sumG[i] = 0L
            sumB[i] = 0L
        }

        // Akumulace (sumace) pixelů přes všechny snímky v bufferu
        val frameBuffer = IntArray(size)
        for (frame in frames) {
            frame.getPixels(frameBuffer, 0, width, 0, 0, width, height)
            for (i in 0 until size) {
                val pixel = frameBuffer[i]
                sumR[i] += ((pixel shr 16) and 0xFF).toLong()
                sumG[i] += ((pixel shr 8) and 0xFF).toLong()
                sumB[i] += (pixel and 0xFF).toLong()
            }
        }

        // Průměrování a normalizace zpět do RGB
        for (i in 0 until size) {
            val r = (sumR[i] / n).toInt().coerceIn(0, 255)
            val g = (sumG[i] / n).toInt().coerceIn(0, 255)
            val b = (sumB[i] / n).toInt().coerceIn(0, 255)
            outBuffer[i] = -0x1000000 or (r shl 16) or (g shl 8) or b
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(outBuffer, 0, width, 0, 0, width, height)
        }
    }

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        return processMultiFrame(listOf(input), scaleFactor)
    }

    override fun reset() {}
    override fun release() {}
}
