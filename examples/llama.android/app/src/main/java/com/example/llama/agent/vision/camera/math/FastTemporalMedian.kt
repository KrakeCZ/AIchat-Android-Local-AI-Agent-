package com.example.llama.agent.vision.camera.math

import kotlin.math.abs

/**
 * Fast Temporal Median (Approximate) — robustní background model pro Sky Scan.
 * 
 * Na rozdíl od FrameDifference (který odečítá n-1 frame a reaguje na vše),
 * toto sestavuje statistický "mediánový" model pozadí v čase pomocí +1/-1 shiftu.
 * Pohybující se objekty pozadí neovlivní. Skvělé pro potlačení pomalu plujících mraků!
 * 
 * THREADING: Volat výhradně z ImageAnalysis Executor threadu.
 * RESET: Nutno zavolat reset() při restartu kamery pro vymazání background modelu.
 */
object FastTemporalMedian {

    // Background model (RGB kanály uloženy nezávisle na sobě jako ByteArray pro efektivitu)
    private var bgR = ByteArray(0)
    private var bgG = ByteArray(0)
    private var bgB = ByteArray(0)
    private var initialized = false

    /**
     * Aplikuje Approximate Temporal Median in-place.
     * @param pixels Vstupní/výstupní pole ARGB_8888 pixelů (modifikováno in-place na rozdíl)
     * @param amplify Zesílení rozdílu (pohybujícího se popředí)
     * @param learningRate Jak rychle se pozadí přizpůsobuje (1 = pomalu/robustně, 5 = rychle).
     *                     Pro pomalé mraky chceme hodnotu 1 až 2.
     * @param blendRatio Podíl originálu ve výstupu (jako u FrameDifference).
     */
    fun processInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        amplify: Float = 4.0f,
        learningRate: Int = 1,
        blendRatio: Float = 0.35f
    ) {
        val size = width * height
        if (size == 0) return

        if (!initialized || bgR.size < size) {
            bgR = ByteArray(size)
            bgG = ByteArray(size)
            bgB = ByteArray(size)
            
            // Inicializace modelu prvním framem (background se stane tímto framem)
            for (i in 0 until size) {
                val px = pixels[i]
                bgR[i] = ((px shr 16) and 0xFF).toByte()
                bgG[i] = ((px shr 8)  and 0xFF).toByte()
                bgB[i] = (px          and 0xFF).toByte()
            }
            initialized = true
            return
        }

        val origRatio = blendRatio.coerceIn(0f, 1f)
        val diffRatio = 1.0f - origRatio

        for (i in 0 until size) {
            val curr = pixels[i]
            val currR = (curr shr 16) and 0xFF
            val currG = (curr shr 8)  and 0xFF
            val currB =  curr         and 0xFF

            val backR = bgR[i].toInt() and 0xFF
            val backG = bgG[i].toInt() and 0xFF
            val backB = bgB[i].toInt() and 0xFF

            // 1. Update background modelu (Approximate Median trick = konverguje k mediánu)
            val newBackR = when {
                currR > backR -> minOf(255, backR + learningRate)
                currR < backR -> maxOf(0, backR - learningRate)
                else -> backR
            }
            val newBackG = when {
                currG > backG -> minOf(255, backG + learningRate)
                currG < backG -> maxOf(0, backG - learningRate)
                else -> backG
            }
            val newBackB = when {
                currB > backB -> minOf(255, backB + learningRate)
                currB < backB -> maxOf(0, backB - learningRate)
                else -> backB
            }

            bgR[i] = newBackR.toByte()
            bgG[i] = newBackG.toByte()
            bgB[i] = newBackB.toByte()

            // 2. Foreground detekce = abs(current - background) * amplify
            val diffR = (abs(currR - newBackR) * amplify).toInt().coerceIn(0, 255)
            val diffG = (abs(currG - newBackG) * amplify).toInt().coerceIn(0, 255)
            val diffB = (abs(currB - newBackB) * amplify).toInt().coerceIn(0, 255)

            // 3. Blend s originálem
            val outR = (origRatio * currR + diffRatio * diffR).toInt().coerceIn(0, 255)
            val outG = (origRatio * currG + diffRatio * diffG).toInt().coerceIn(0, 255)
            val outB = (origRatio * currB + diffRatio * diffB).toInt().coerceIn(0, 255)

            pixels[i] = (curr and -0x1000000) or (outR shl 16) or (outG shl 8) or outB
        }
    }

    /**
     * Vymaže background model. MUSÍ být zavoláno při restartu kamery (onStop).
     */
    fun reset() {
        initialized = false
    }
}
