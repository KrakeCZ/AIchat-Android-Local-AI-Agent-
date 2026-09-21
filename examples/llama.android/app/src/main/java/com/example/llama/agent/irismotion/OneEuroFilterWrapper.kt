package com.example.llama.agent.irismotion

/**
 * Wrapper pro existující GazeStabilizer (OneEuroFilter). Zastřešuje původní logiku do nového
 * rozhraní GazeFilter pro seamless integraci do flexibilní roury.
 */
class OneEuroFilterWrapper(private val stabilizer: GazeStabilizer = GazeStabilizer()) : GazeFilter {

    override fun filter(
            x: Float,
            y: Float,
            z: Float,
            timestamp: Long
    ): Triple<Float, Float, Float> {
        // GazeStabilizer bere timestamp a z ve specifickém pořadí (x, y, timestamp, z)
        return stabilizer.filter(x, y, timestamp, z)
    }

    override fun reset() {
        stabilizer.reset()
    }
}
