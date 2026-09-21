package com.example.llama.agent.irismotion

import android.util.Log

/**
 * GazeFilterPipeline — Architektonická roura pro řetězení filtrů (Chain of Responsibility pattern).
 *
 * Data vstoupí na jedné straně a projdou všemi připojenými filtry v přesném pořadí. Architektura je
 * navržena pro "Fail-Safe" - pokud jeden filtr selže (vyhodí výjimku), data nejsou ztracena, ale
 * filtr je v daném kroku přeskočen.
 */
class GazeFilterPipeline {

    companion object {
        private const val TAG = "GazeFilterPipeline"
    }

    private val filters = mutableListOf<GazeFilter>()

    /** Přidá filtr na konec zpracovatelského řetězce. */
    fun addFilter(filter: GazeFilter) {
        filters.add(filter)
    }

    /** Pročistí celou rouru (např. při změně nastavení). */
    fun clear() {
        filters.clear()
    }

    /** Zpracuje jeden snímek s maximální robustní ochranou. */
    fun process(
            rawX: Float,
            rawY: Float,
            rawZ: Float,
            timestamp: Long
    ): Triple<Float, Float, Float> {
        var currentX = rawX
        var currentY = rawY
        var currentZ = rawZ

        for (filter in filters) {
            try {
                val result = filter.filter(currentX, currentY, currentZ, timestamp)

                // Sanitace výstupu z filtru: Pokud filtr vrátí NaN, ignorujeme ho a předáme dál
                // předchozí validní stav.
                if (!result.first.isNaN() && !result.second.isNaN() && !result.third.isNaN()) {
                    currentX = result.first
                    currentY = result.second
                    currentZ = result.third
                } else {
                    Log.w(TAG, "Filtr ${filter.javaClass.simpleName} vrátil NaN hodnoty. By-pass.")
                }
            } catch (e: Exception) {
                // Fail-safe architektura: Výpadek jednoho filtru nesmí shodit aplikaci/robota.
                Log.e(
                        TAG,
                        "Fatální chyba ve filtru ${filter.javaClass.simpleName}: ${e.message}. Přeskakuji."
                )
            }
        }

        return Triple(currentX, currentY, currentZ)
    }

    /** Resetuje stav všech připojených filtrů. */
    fun reset() {
        filters.forEach {
            try {
                it.reset()
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při resetování filtru ${it.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
