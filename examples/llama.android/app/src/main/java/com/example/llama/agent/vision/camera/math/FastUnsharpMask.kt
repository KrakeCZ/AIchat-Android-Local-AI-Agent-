package com.example.llama.agent.vision.camera.math

import kotlin.math.abs

/**
 * Zero-Allocation Unsharp Mask pre-procesor pro zvýraznění hran malých objektů.
 * Astrofotografická technika aplikovaná in-place na surová data kamery před vstupem do neuronové sítě.
 * 
 * Ideální pro "low-SNR" oblohu (slabé pixely letadel, ptáků či UAP v dálce).
 */
object FastUnsharpMask {

    private var blurBuffer = IntArray(0)

    /**
     * @param pixels   ARGB_8888 pole — modifikováno in-place
     * @param width    šířka obrazu
     * @param height   výška obrazu
     * @param strength síla zaostření (1.0 = mírné, 2.5 = agresivní pro detekci)
     */
    fun processInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float = 1.5f
    ) {
        val size = width * height
        if (size == 0) return

        if (blurBuffer.size < size) {
            blurBuffer = IntArray(size)
        }

        // 1. Rychlý 3×3 box blur (aproximace Gaussova rozmazání pro izolaci hran)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                var sumR = 0; var sumG = 0; var sumB = 0

                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = pixels[(y + ky) * width + (x + kx)]
                        sumR += (pixel shr 16) and 0xFF
                        sumG += (pixel shr 8) and 0xFF
                        sumB += pixel and 0xFF
                    }
                }
                blurBuffer[i] = (pixels[i] and -0x1000000) or
                    ((sumR / 9) shl 16) or ((sumG / 9) shl 8) or (sumB / 9)
            }
        }

        // 2. Unsharp mask: Out = Original + strength × (Original − Blur)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val orig = pixels[i]
                val blur = blurBuffer[i]

                val origR = (orig shr 16) and 0xFF
                val origG = (orig shr 8) and 0xFF
                val origB = orig and 0xFF
                
                val blurR = (blur shr 16) and 0xFF
                val blurG = (blur shr 8) and 0xFF
                val blurB = blur and 0xFF

                // Zvýraznění hrany (rozdíl mezi originálem a rozmazanou verzí)
                val newR = (origR + strength * (origR - blurR)).toInt().coerceIn(0, 255)
                val newG = (origG + strength * (origG - blurG)).toInt().coerceIn(0, 255)
                val newB = (origB + strength * (origB - blurB)).toInt().coerceIn(0, 255)

                pixels[i] = (orig and -0x1000000) or (newR shl 16) or (newG shl 8) or newB
            }
        }
    }
}
