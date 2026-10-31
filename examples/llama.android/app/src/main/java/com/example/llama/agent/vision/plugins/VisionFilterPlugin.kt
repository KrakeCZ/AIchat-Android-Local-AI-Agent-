package com.example.llama.agent.vision.plugins

import com.example.llama.agent.vision.BoundingBoxView.Detection

/**
 * Striktní kontrakt pro všechny budoucí moduly postprodukce obrazu 
 * (Trackery, Filtry, Vyhlazovače, Sortery).
 */
interface VisionFilterPlugin {
    val pluginId: String
    val displayName: String

    /**
     * Zpracuje detekce z daného framu.
     * @param detections Surová data z ML sítě.
     * @param timestampMs Časová značka framu pro výpočet rychlosti/fyziky.
     * @return Upravené detekce (např. obohacené o ID nebo vyhlazené souřadnice).
     */
    fun process(
        detections: List<Detection>,
        timestampMs: Long
    ): List<Detection>

    /** Resetuje vnitřní paměť (např. při změně kamery nebo pauze) */
    fun reset()
    
    /** Uvolní veškeré nativní zdroje a paměť před zničením */
    fun release()
}
