package com.example.llama.agent.vision.camera.math

import kotlin.math.abs

/**
 * Frame Difference Pre-Processor — core sky-scan technika.
 *
 * Každý pixel tensoru = abs(current - previous) × amplify, blended s originálem.
 * Efekt: pohybující se objekty jsou dominantní signál, statická obloha/mraky potlačeny.
 *
 * Klíčový benefit pro YOLO: pták/UAP o 8px v 640px tensoru se stane výraznou
 * světlou skvrnnou na tmavém pozadí → conf threshold 0.25 stačí.
 *
 * THREADING: Volat výhradně z ImageAnalysis Executor threadu (CameraX garantuje
 * single-thread dispatch). Není thread-safe pro volání z více vláken současně.
 *
 * RESET: Zavolat reset() při každém restartu CameraX session (onStop/onStart),
 * jinak první frame po restartu bude diffovat proti stale bufferu z předchozí session.
 */
object FrameDifferencePreProcessor {

    // Buffer pro předchozí frame — alokován jednou, resizován pouze při změně rozlišení.
    // Uchovává ORIGINÁLNÍ pixely před modifikací (ne diffovaný výstup).
    private var prevFrame: IntArray = IntArray(0)
    private var initialized: Boolean = false

    /**
     * Aplikuje frame difference in-place na ARGB_8888 pixel pole.
     *
     * @param pixels      ARGB_8888 pole — modifikováno in-place
     * @param width       šířka obrazu v pixelech
     * @param height      výška obrazu v pixelech
     * @param amplify     zesílení rozdílu (3.0–8.0 sky scan denní, 8.0–15.0 noční)
     * @param blendRatio  podíl originálu ve výstupu: 0.0 = čistý diff, 1.0 = originál
     *                    Doporučeno 0.35–0.45 — zachová texturu objektu pro YOLO.
     */
    fun processInPlace(
        pixels: IntArray,
        width: Int,
        height: Int,
        amplify: Float = 5.0f,
        blendRatio: Float = 0.40f
    ) {
        val size = width * height
        if (size == 0) return

        // Lazy init nebo resize při změně rozlišení
        if (!initialized || prevFrame.size < size) {
            // Zkopíruj první frame do bufferu, nevytvářej diff (neexistuje předchozí frame)
            if (prevFrame.size < size) {
                prevFrame = IntArray(size)
            }
            pixels.copyInto(prevFrame, destinationOffset = 0, startIndex = 0, endIndex = size)
            initialized = true
            return
        }

        val origRatio  = blendRatio.coerceIn(0f, 1f)
        val diffRatio  = 1.0f - origRatio

        // Single-pass: čte originál curr a prev, ukládá originál curr do bufferu,
        // zapíše diff-blend výsledek do pixels[i].
        // Pořadí operací uvnitř smyčky je zásadní — curr je přečten PŘED zápisem.
        for (i in 0 until size) {
            val curr = pixels[i]          // (1) Přečti originální aktuální pixel
            val prev = prevFrame[i]       // (2) Přečti předchozí frame pixel
            prevFrame[i] = curr           // (3) Ulož originál do bufferu PŘED zápisem na pixels[i]

            val currR = (curr shr 16) and 0xFF
            val currG = (curr shr 8)  and 0xFF
            val currB =  curr         and 0xFF
            val prevR = (prev shr 16) and 0xFF
            val prevG = (prev shr 8)  and 0xFF
            val prevB =  prev         and 0xFF

            // Amplifikovaný absolutní rozdíl — motion signal
            val diffR = (abs(currR - prevR) * amplify).toInt().coerceIn(0, 255)
            val diffG = (abs(currG - prevG) * amplify).toInt().coerceIn(0, 255)
            val diffB = (abs(currB - prevB) * amplify).toInt().coerceIn(0, 255)

            // Blend: origRatio × originál + diffRatio × amplified_diff
            val outR = (origRatio * currR + diffRatio * diffR).toInt().coerceIn(0, 255)
            val outG = (origRatio * currG + diffRatio * diffG).toInt().coerceIn(0, 255)
            val outB = (origRatio * currB + diffRatio * diffB).toInt().coerceIn(0, 255)

            // Alpha kanál zachován beze změny (bits 31–24)
            pixels[i] = (curr and -0x1000000) or (outR shl 16) or (outG shl 8) or outB
        }
    }

    /**
     * Resetuj buffer — MUSÍ být zavoláno při každém restartu CameraX session.
     * Jinak první frame nové session diffuje proti stale bufferu z předchozí session
     * → falešný "vše se pohybuje" výstup v prvním framu.
     */
    fun reset() {
        // Buffer není dealokován (vyhýbáme se GC pressure) — pouze příznak inicializace
        initialized = false
    }
}
