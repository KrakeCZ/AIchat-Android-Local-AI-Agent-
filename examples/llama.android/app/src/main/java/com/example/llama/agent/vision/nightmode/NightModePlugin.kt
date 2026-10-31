package com.example.llama.agent.vision.nightmode

import android.graphics.Bitmap

/**
 * Rozhraní pro akademické experimenty s Low-Light Image Enhancement (Night Mode).
 * Tyto pluginy prosvětlují surový Bitmap z kamery předtím, než jde do náhledu a detekce.
 */
interface NightModePlugin {
    val pluginId: String
    val displayName: String

    /** Inicializace (Lazy) např. načtení TFLite modelu. */
    suspend fun init()

    /** 
     * Asynchronní zpracování obrazu.
     * Vrátí nový Bitmap (alokovaný) nebo původní upravený In-Place.
     */
    suspend fun process(input: Bitmap): Bitmap

    /** Uvolnění zdrojů z paměti. */
    suspend fun release()
}
