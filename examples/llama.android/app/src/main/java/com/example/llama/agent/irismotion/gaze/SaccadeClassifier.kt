package com.example.llama.agent.irismotion.gaze

/**
 * Zhodnocuje aktuální rychlost oka (velX/velY z AlphaBetaFilteru) a klasifikuje pohyb
 * do 3 stavů: SACCADE (rychlý přelet), SMOOTH_PURSUIT (plynulé sledování), FIXATION (zastavení/čtení).
 *
 * Re-engagement ramp (Anti-Snapback) by se měl aktivovat pouze po Fixation,
 * nikoli uprostřed rychlé sakkády, aby nedošlo k zamrznutí kurzoru během přirozeného pohybu oka.
 */
class SaccadeClassifier {

    enum class GazeState {
        FIXATION,
        SMOOTH_PURSUIT,
        SACCADE
    }

    companion object {
        // Rychlosti jsou ve zlomcích obrazovky [0.0 - 1.0] za sekundu
        // Příklad: 0.5 = oko přejede půlku obrazovky za 1 sekundu.
        private const val FIXATION_THRESHOLD = 0.5f     // < 0.5 screen/sec (pomalý pohyb / čtení)
        private const val SACCADE_THRESHOLD = 3.0f      // > 3.0 screen/sec (velmi rychlý "skok")
    }

    var currentState: GazeState = GazeState.FIXATION
        private set

    /**
     * @param velX Aktuální rychlost na ose X (z AlphaBetaFilter)
     * @param velY Aktuální rychlost na ose Y (z AlphaBetaFilter)
     * @return Klasifikovaný stav
     */
    fun classify(velX: Float, velY: Float): GazeState {
        val speed = Math.sqrt((velX * velX + velY * velY).toDouble()).toFloat()

        currentState = when {
            speed > SACCADE_THRESHOLD -> GazeState.SACCADE
            speed < FIXATION_THRESHOLD -> GazeState.FIXATION
            else -> GazeState.SMOOTH_PURSUIT
        }

        return currentState
    }
}
