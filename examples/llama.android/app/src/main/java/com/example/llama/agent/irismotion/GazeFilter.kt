package com.example.llama.agent.irismotion

/**
 * GazeFilter — Standardizované rozhraní pro všechny stabilizační filtry.
 *
 * Zajišťuje absolutní typovou bezpečnost a unifikované chování v rámci GazeFilterPipeline. Každý
 * filtr musí přijmout 3D souřadnice a časové razítko a vrátit zpracovaný výsledek, aniž by
 * modifikoval vnější stav systému.
 */
interface GazeFilter {
    /**
     * Zpracuje surové nebo částečně filtrované souřadnice a vrátí výsledek. Metoda musí být
     * idempotentní ve vztahu k vnějšímu systému (žádné vedlejší efekty mimo filtr).
     *
     * @param x Očekávána normalizovaná hodnota (typicky 0.0 - 1.0)
     * @param y Očekávána normalizovaná hodnota (typicky 0.0 - 1.0)
     * @param z Očekávána Z-osa (headYaw/headPitch proxy)
     * @param timestamp Časové razítko snímku v milisekundách nebo mikrosekundách (musí být
     * konzistentní v celé rouře)
     * @return Triple(x, y, z) reprezentující vyfiltrovaná data
     */
    fun filter(x: Float, y: Float, z: Float, timestamp: Long): Triple<Float, Float, Float>

    /**
     * Bezpečně vyčistí interní paměť a historii filtru. Volá se při výpadcích senzoru (např.
     * mrknutí, ztráta obličeje).
     */
    fun reset()
}
