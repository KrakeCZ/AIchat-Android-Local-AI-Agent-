package com.example.llama.agent.vision.camera.math

import kotlin.math.ln

/**
 * Logaritmický stretch pre-procesor — astrofotografický standard.
 *
 * Transformace: Out = log(1 + strength × In) / log(1 + strength × 255)
 *
 * Efekt: Tmavé pixely (vzdálené objekty, hvězdy) se výrazně rozsvítí.
 * Jasné pixely (obloha, mraky) se komprimují — neztrácíme detaily v jasných oblastech.
 *
 * Pro UAP detekci v noci nebo za soumraku: strength = 5–10
 * Pro denní oblohu: strength = 2–3
 *
 * Zpracování přes pre-kalkulovanou LUT → Zero-Allocation za běhu.
 */
object FastLogStretch {

    private val lut = IntArray(256)
    private var cachedStrength = -1f  // Invalidace LUT při změně parametru

    /**
     * @param pixels   ARGB_8888 pole — modifikováno in-place
     * @param width    šířka obrazu
     * @param height   výška obrazu
     * @param strength síla logaritmického stretch (2.0–10.0)
     */
    fun processInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        strength: Float = 3.0f
    ) {
        val size = width * height
        if (size == 0) return

        // Přepočítej LUT pouze pokud se změnil strength parametr
        if (strength != cachedStrength) {
            val divisor = ln(1.0 + strength * 255.0)
            for (i in 0..255) {
                lut[i] = (ln(1.0 + strength * i) / divisor * 255.0).toInt().coerceIn(0, 255)
            }
            cachedStrength = strength
        }

        // Aplikuj LUT in-place — O(n), zero allocations
        for (i in 0 until size) {
            val pixel = pixels[i]
            val alpha = pixel and -0x1000000
            val r = lut[(pixel shr 16) and 0xFF]
            val g = lut[(pixel shr 8) and 0xFF]
            val b = lut[pixel and 0xFF]
            pixels[i] = alpha or (r shl 16) or (g shl 8) or b
        }
    }
}
