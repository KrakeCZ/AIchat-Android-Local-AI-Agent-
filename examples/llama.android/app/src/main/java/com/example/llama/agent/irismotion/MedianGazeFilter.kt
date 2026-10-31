package com.example.llama.agent.irismotion

/**
 * MedianGazeFilter — Mediánový pre-filtr pro eliminaci outlierů z MediaPipe.
 *
 * Účel: MediaPipe občas na 1–2 framy „ustřelí" (odlesk, obočí, rozmazání). Průměr nebo One Euro
 * Filter takový spike propustí, ale medián ho zcela ignoruje — vybírá prostřední hodnotu ze
 * seřazeného okna.
 *
 * Příklad: Hodnoty X = [0.50, 0.51, 0.95, 0.50, 0.52]
 * - Průměr: 0.596 (spike posune kurzor)
 * - Medián: 0.51 (spike ignorován)
 *
 * Konfigurace:
 * - windowSize = 3: nejmenší efektivní okno, minimální latence (~2 framy, <70ms @ 30fps)
 * - windowSize = 5: silnější filtr, přežije i 2 po sobě jdoucí spiky, +1 frame latence
 * - windowSize = 7–9: extrémní filtr, vhodný pro hodně šumný senzor / slabé osvětlení
 * - windowSize = 1: filtr vypnut (passthrough)
 *
 * Thread-safety: NENÍ thread-safe — volat pouze z jednoho vlákna (camera executor).
 *
 * @param windowSize Velikost mediánového okna. MUSÍ být liché číslo ≥ 1.
 */
class MedianGazeFilter(windowSize: Int = 3) : GazeFilter {

    // Vynucení lichého okna ≥ 1
    private val effectiveWindow: Int =
            windowSize.coerceAtLeast(1).let { if (it % 2 == 0) it + 1 else it }

    private val historyX = ArrayDeque<Float>(effectiveWindow)
    private val historyY = ArrayDeque<Float>(effectiveWindow)
    private val historyZ = ArrayDeque<Float>(effectiveWindow)

    /**
     * Filtruje jeden frame gaze dat mediánovým filtrem.
     *
     * @param x Normalizovaná X souřadnice (0.0–1.0)
     * @param y Normalizovaná Y souřadnice (0.0–1.0)
     * @param z Z-osa (headYaw/headPitch proxy)
     * @param timestamp Časové razítko (povinné pro GazeFilter, zde nevyužito)
     * @return Triple(medianX, medianY, medianZ) — vyfiltrované souřadnice
     */
    override fun filter(
            x: Float,
            y: Float,
            z: Float,
            timestamp: Long
    ): Triple<Float, Float, Float> {
        // Ochrana před chybnými vstupy
        if (x.isNaN() || y.isNaN() || z.isNaN()) {
            return if (historyX.isNotEmpty()) {
                Triple(historyX.last(), historyY.last(), historyZ.last())
            } else {
                Triple(0f, 0f, 0f)
            }
        }

        // Passthrough pokud je filtr vypnutý (windowSize == 1)
        if (effectiveWindow <= 1) return Triple(x, y, z)

        // Přidat nový vzorek, odstranit nejstarší pokud jsme na limitu okna
        historyX.addLast(x)
        if (historyX.size > effectiveWindow) historyX.removeFirst()

        historyY.addLast(y)
        if (historyY.size > effectiveWindow) historyY.removeFirst()

        historyZ.addLast(z)
        if (historyZ.size > effectiveWindow) historyZ.removeFirst()

        // Medián = prostřední hodnota v seřazené kopii
        val medX = historyX.sorted()[historyX.size / 2]
        val medY = historyY.sorted()[historyY.size / 2]
        val medZ = historyZ.sorted()[historyZ.size / 2]

        return Triple(medX, medY, medZ)
    }

    /**
     * Resetuje vnitřní historii filtru. Volat při přerušení trackingu (zavřené oči, výpadek kamery,
     * změna trackedEye).
     */
    override fun reset() {
        historyX.clear()
        historyY.clear()
        historyZ.clear()
    }

    /** Aktuální efektivní velikost okna (pro diagnostické logování). */
    fun getWindowSize(): Int = effectiveWindow
}
