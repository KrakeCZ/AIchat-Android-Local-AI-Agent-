package com.example.llama.agent.vision.camera.math

import kotlin.math.min

/**
 * Rychlý Zero-Allocation matematický pre-processor pro zvýšení lokálního kontrastu (hrany).
 * Používá globální modifikovanou verzi Contrast Limited Histogram Equalization (CLHE).
 * Zpracovává surové pixely in-place.
 */
object FastCLAHE {
    
    // Před-alokované buffery pro absolutní Zero-Allocation za běhu
    private val histogram = IntArray(256)
    private val lut = IntArray(256)

    /**
     * Aplikuje matematické projasnění a vytažení hran přes úpravu histogramu.
     * @param pixels Pole pixelů (ARGB_8888 formát). Budou upraveny In-Place.
     * @param width Šířka výřezu (např. 320)
     * @param height Výška výřezu (např. 320)
     * @param clipLimit Limit ořezu špiček histogramu (čím vyšší, tím víc HDR efektu a šumu). 2.0 - 4.0 je ideální.
     */
    fun processInPlace(pixels: IntArray, width: Int, height: Int, clipLimit: Float = 3.0f) {
        val size = width * height
        if (size == 0) return

        // 1. Resetování bufferů a plnění histogramu
        histogram.fill(0)
        for (i in 0 until size) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Aproximace luminance (rychlá): L = (R + 2*G + B) / 4
            val luma = (r + (g shl 1) + b) shr 2
            histogram[luma]++
        }

        // 2. Aplikace Clip Limit (Zabraňuje přílišnému zesílení šumu)
        // Kolik pixelů smí maximálně být v jednom "chlívečku" histogramu
        val limit = (clipLimit * (size / 256f)).toInt()
        var excess = 0
        for (i in 0..255) {
            if (histogram[i] > limit) {
                excess += histogram[i] - limit
                histogram[i] = limit
            }
        }
        
        // Rozdělení "odříznutého" přesahu rovnoměrně mezi všechny barvy
        val increment = excess / 256
        if (increment > 0) {
            for (i in 0..255) {
                histogram[i] += increment
            }
        }

        // 3. Výpočet Kumulativní distribuční funkce (CDF) a vytvoření Look-Up Table (LUT)
        var sum = 0
        val scale = 255.0f / size
        for (i in 0..255) {
            sum += histogram[i]
            val v = (sum * scale).toInt()
            lut[i] = if (v > 255) 255 else v
        }

        // 4. Přemapování pixelů (In-Place)
        for (i in 0 until size) {
            val p = pixels[i]
            val a = p and -0x1000000 // Alpha channel mask
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            
            val oldLuma = (r + (g shl 1) + b) shr 2
            val newLuma = lut[oldLuma]
            
            if (oldLuma == 0) {
                // Z černé nelze zachovat barvu, jen zvedneme šedivou
                pixels[i] = a or (newLuma shl 16) or (newLuma shl 8) or newLuma
                continue
            }
            
            // Aplikace jasu se zachováním původní "barvy" pomocí jednoduchého poměru
            val ratio = newLuma.toFloat() / oldLuma.toFloat()
            val newR = min(255, (r * ratio).toInt())
            val newG = min(255, (g * ratio).toInt())
            val newB = min(255, (b * ratio).toInt())
            
            pixels[i] = a or (newR shl 16) or (newG shl 8) or newB
        }
    }
}
