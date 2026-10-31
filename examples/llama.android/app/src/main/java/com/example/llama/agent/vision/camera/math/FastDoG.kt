package com.example.llama.agent.vision.camera.math

/**
 * Fast Difference of Gaussians (DoG) — astrofotografická technika.
 * Využívá aproximaci Gaussian bluru pomocí vícenásobného Box Bluru (O(N) složitost).
 * 
 * Princip: Odstraní gradienty oblohy (světelné znečištění, měsíc) a zvýrazní
 * pouze bodové (point-source) zdroje světla určité velikosti.
 * 
 * Extrémně efektivní pro noční sledování oblohy (meteorické roje, satelity, UAP).
 * 
 * THREADING: Volat výhradně z ImageAnalysis Executor threadu.
 */
object FastDoG {

    private var blur1Buffer: IntArray = IntArray(0)
    private var blur2Buffer: IntArray = IntArray(0)
    private var tempBuffer: IntArray = IntArray(0) // Pro horizontální/vertikální pass

    /**
     * Aplikuje DoG in-place.
     * @param pixels Vstupní/výstupní pole ARGB_8888 pixelů
     * @param radius1 Poloměr menšího rozostření (detaily, např. 1)
     * @param radius2 Poloměr většího rozostření (background, např. 3 nebo 4)
     * @param amplify Zesílení nalezených bodů
     */
    fun processInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        radius1: Int = 1,
        radius2: Int = 3,
        amplify: Float = 4.0f
    ) {
        val size = width * height
        if (size == 0) return

        if (blur1Buffer.size < size) {
            blur1Buffer = IntArray(size)
            blur2Buffer = IntArray(size)
            tempBuffer = IntArray(size)
        }

        // Vytvoř blur1 (menší poloměr -> zachová hvězdy/UAP, odstraní micro šum)
        if (radius1 > 0) {
            fastBoxBlur(pixels, blur1Buffer, tempBuffer, width, height, radius1)
        } else {
            pixels.copyInto(blur1Buffer, 0, 0, size)
        }

        // Vytvoř blur2 z originálu (větší poloměr -> vytvoří mapu světelného znečištění/backgroundu)
        fastBoxBlur(pixels, blur2Buffer, tempBuffer, width, height, radius2)

        // Rozdíl: blur1 - blur2 + amplify
        for (i in 0 until size) {
            val p1 = blur1Buffer[i]
            val p2 = blur2Buffer[i]

            val r1 = (p1 shr 16) and 0xFF
            val g1 = (p1 shr 8)  and 0xFF
            val b1 =  p1         and 0xFF

            val r2 = (p2 shr 16) and 0xFF
            val g2 = (p2 shr 8)  and 0xFF
            val b2 =  p2         and 0xFF

            // Kladný rozdíl = světelný bod (hvězda/UAP je světlejší než background)
            val diffR = ((r1 - r2) * amplify).toInt().coerceIn(0, 255)
            val diffG = ((g1 - g2) * amplify).toInt().coerceIn(0, 255)
            val diffB = ((b1 - b2) * amplify).toInt().coerceIn(0, 255)

            // Kombinace výsledku (necháme RGB pro zachování barev hvězd/světel)
            pixels[i] = (pixels[i] and -0x1000000) or (diffR shl 16) or (diffG shl 8) or diffB
        }
    }

    /** Rychlý 2D box blur pomocí separabilního 1D passu O(N) nezávisle na poloměru */
    private fun fastBoxBlur(src: IntArray, dst: IntArray, tmp: IntArray, w: Int, h: Int, r: Int) {
        // Horizontal pass: src -> tmp
        for (y in 0 until h) {
            val offset = y * w
            var rSum = 0; var gSum = 0; var bSum = 0
            val windowSize = 2 * r + 1

            // Init window
            for (x in -r..r) {
                val px = src[offset + x.coerceIn(0, w - 1)]
                rSum += (px shr 16) and 0xFF
                gSum += (px shr 8) and 0xFF
                bSum += px and 0xFF
            }

            for (x in 0 until w) {
                tmp[offset + x] = (rSum / windowSize shl 16) or (gSum / windowSize shl 8) or (bSum / windowSize)

                val outX = (x - r).coerceIn(0, w - 1)
                val inX = (x + r + 1).coerceIn(0, w - 1)
                
                val outPx = src[offset + outX]
                val inPx = src[offset + inX]

                rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }

        // Vertical pass: tmp -> dst
        for (x in 0 until w) {
            var rSum = 0; var gSum = 0; var bSum = 0
            val windowSize = 2 * r + 1

            for (y in -r..r) {
                val py = tmp[y.coerceIn(0, h - 1) * w + x]
                rSum += (py shr 16) and 0xFF
                gSum += (py shr 8) and 0xFF
                bSum += py and 0xFF
            }

            for (y in 0 until h) {
                dst[y * w + x] = (rSum / windowSize shl 16) or (gSum / windowSize shl 8) or (bSum / windowSize)

                val outY = (y - r).coerceIn(0, h - 1)
                val inY = (y + r + 1).coerceIn(0, h - 1)

                val outPx = tmp[outY * w + x]
                val inPx = tmp[inY * w + x]

                rSum += ((inPx shr 16) and 0xFF) - ((outPx shr 16) and 0xFF)
                gSum += ((inPx shr 8) and 0xFF) - ((outPx shr 8) and 0xFF)
                bSum += (inPx and 0xFF) - (outPx and 0xFF)
            }
        }
    }
}
