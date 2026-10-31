package com.example.llama.agent.irismotion.gaze

import android.util.Log

/**
 * Prediktor polohy duhovky při ztrátě sledování (např. mrknutí, odvrácení).
 *
 * Udržuje ring buffer posledních známých validních pozic oka a při výpadku dat extrapoluje
 * (odhadne) polohu na základě hybnosti (velocity) a akcelerace. Snižuje snap-back efekt, protože
 * kurzor "dojede" na místo místo zamrznutí a ostrého návratu.
 */
class PupilPositionPredictor(private val historySize: Int = 5) {

    private val bufferX = FloatArray(historySize)
    private val bufferY = FloatArray(historySize)
    private val bufferTime = LongArray(historySize)

    private var headIndex = 0
    private var count = 0

    // Poslední extrapolovaná pozice
    var predictedX: Float = 0.5f
        private set
    var predictedY: Float = 0.5f
        private set

    /** Přidá validní (ne-mrkací) bod do historie. */
    fun addValidPoint(x: Float, y: Float, nowMs: Long) {
        bufferX[headIndex] = x
        bufferY[headIndex] = y
        bufferTime[headIndex] = nowMs

        headIndex = (headIndex + 1) % historySize
        if (count < historySize) count++

        // Zaktualizuj i predikci na reálnou hodnotu
        predictedX = x
        predictedY = y
    }

    /**
     * Vypočítá odhadovanou polohu pro aktuální čas pomocí lineární extrapolace. Čím déle predikce
     * trvá, tím více by měla konvergovat k nule (zastavení rychlosti), aby kurzor neujel mimo
     * obrazovku (damping).
     */
    @Suppress("UNUSED_PARAMETER")
    fun predict(nowMs: Long): Pair<Float, Float> {
        if (count < 2) return Pair(predictedX, predictedY)

        // Extrapolace průměrnou rychlostí z celého dostupného bufferu (odstraníme noise z pouhých 2
        // frame)
        val latestIdx = (headIndex - 1 + historySize) % historySize
        val oldestIdx = (headIndex - count + historySize) % historySize

        val dt = (bufferTime[latestIdx] - bufferTime[oldestIdx]).toFloat()
        if (dt <= 5f)
                return Pair(predictedX, predictedY) // Ochrana proti dělení nulou nebo mikro-času

        // [MASTER IT FIX]: Během mrkání (Bellův fenomén) oko nekontrolovatelně uskakuje.
        // Lineární extrapolace rychlosti by tento ustřelený pohyb (např. nahoru) jen prodlužovala.
        // Proto namísto extrapolace bezpečně zafixujeme kurzor na nejstarším dostupném bodu v
        // bufferu
        // (cca 150-200ms v minulosti, TĚSNĚ PŘED započetím sakády / mrknutí).
        // Toto je standardní postup "Frozen Position Fallback" v očních trackerech.
        predictedX = bufferX[oldestIdx]
        predictedY = bufferY[oldestIdx]

        Log.v("PupilPredictor", "🔮 Frozen Fallback: ($predictedX, $predictedY)")

        return Pair(predictedX, predictedY)
    }

    fun reset() {
        count = 0
        headIndex = 0
    }
}
