package com.example.llama.agent.irismotion.gaze

/**
 * Zabezpečovací vrstva, která zabraňuje fyzicky nereálným přesunům kurzoru (Teleportace).
 * Clampuje maximální povolenou Delta/Frame z DofProcessorů předtím, než se přičte do akumulátoru.
 */
class TrajectoryStabilizer {

    companion object {
        // Maximální povolený posun kurzoru v pixelech za frame (kolem 60fps)
        // Představuje cca rychlost 6000 px/sec
        private const val MAX_DELTA_PX = 100f
    }

    /**
     * Zpracuje syrové Delta X a Delta Y (z DofProcessoru).
     * @return Uříznutou Deltu, pokud překročí fyzické limity hardware.
     */
    fun clampDelta(deltaX: Float, deltaY: Float): Pair<Float, Float> {
        var dx = deltaX
        var dy = deltaY

        val magnitude = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()

        // Pokud rána přeskočí rychlostní bariéru, zachováme úhel, ale ořízneme délku
        if (magnitude > MAX_DELTA_PX) {
            val scale = MAX_DELTA_PX / magnitude
            dx *= scale
            dy *= scale
        }

        return Pair(dx, dy)
    }
}
