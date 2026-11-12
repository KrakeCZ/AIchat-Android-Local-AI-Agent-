package com.example.llama.agent.vision.lupa

import android.graphics.Bitmap

enum class LupaCategory(val displayLabel: String) {
    SINGLE_FRAME("Single-Frame"),
    MULTI_FRAME("Multi-Frame"),
    DECONVOLUTION("Dekonvoluce"),
    FREQUENCY_DOMAIN("Frekvenční doména")
}

interface LupaPlugin {
    val pluginId: String
    val displayName: String
    val category: LupaCategory
    /** Orientační FPS hint pro UI — negarantovaný, závisí na HW */
    val expectedFpsHint: Int

    val requiresMultipleFrames: Boolean get() = false
    val minFramesRequired: Int get() = 1

    /**
     * Single-frame upscale.
     * @param input  zdrojová bitmapa (vlastněna volajícím — plugin ji nesmí recyklovat)
     * @param scaleFactor  požadovaný zoom (např. 2.0f = 2× upscale)
     * @return nová bitmapa vlastněná volajícím
     */
    fun process(input: Bitmap, scaleFactor: Float): Bitmap

    /**
     * Multi-frame varianta — výchozí implementace deleguje na single-frame (last frame).
     * @param frames  seznam framů nejstarší→nejnovější; plugin je nesmí recyklovat
     */
    fun processMultiFrame(frames: List<Bitmap>, scaleFactor: Float): Bitmap =
        process(frames.last(), scaleFactor)

    /** Smaže vnitřní stav (frame buffer, iterace). Volá se při přepnutí pluginu nebo resetu kamery. */
    fun reset()

    /** Uvolní nativní zdroje. Volá se z LupaPluginManager.releaseAll(). */
    fun release()
}