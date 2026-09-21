package com.example.llama.agent.irismotion.expressions

/**
 * Modul pro křížové maskování (Crosstalk Suppression).
 * Eliminuje anatomické přeslechy z MediaPipe Face Landmarkeru.
 */
class ExpressionCrosstalkSuppressor {

    data class FilteredScores(
        val smile: Float,
        val frown: Float,
        val browUp: Float
    )

    /**
     * Zpracuje surové nebo 1€ vyhlazené skóre a potlačí falešné aktivace.
     * 
     * Pravidla:
     * 1. Úsměv (zvednuté tváře) často falešně spouští "mračení" nebo ruší detekci obočí.
     * 2. Široký úsměv nesmí vyvolat falešné zvednutí obočí, ledaže by obočí bylo zvednuto extrémně.
     */
    fun suppress(smileScore: Float, frownScore: Float, browUpScore: Float): FilteredScores {
        var adjustedSmile = smileScore
        var adjustedFrown = frownScore
        var adjustedBrowUp = browUpScore

        // Pokud se uživatel silně usmívá, potlačíme falešné detekce mračení a obočí.
        if (smileScore > 0.3f) {
            val suppressionFactor = (smileScore - 0.3f) * 1.5f // Roste se silou úsměvu
            
            // Mračení je anatomicky protichůdné k úsměvu
            adjustedFrown = (frownScore - suppressionFactor).coerceAtLeast(0f)
            
            // Zvednutí obočí může být "falešně" hlášeno kvůli zvednutým tvářím, potlačíme ho lehce
            adjustedBrowUp = (browUpScore - (suppressionFactor * 0.5f)).coerceAtLeast(0f)
        }

        // Pokud se uživatel silně mračí, potlačíme úsměv
        if (frownScore > 0.4f) {
            val suppressionFactor = (frownScore - 0.4f) * 1.5f
            adjustedSmile = (smileScore - suppressionFactor).coerceAtLeast(0f)
        }

        return FilteredScores(
            smile = adjustedSmile,
            frown = adjustedFrown,
            browUp = adjustedBrowUp
        )
    }
}
