package com.example.llama.agent.vision.camera.core

import com.example.llama.agent.vision.camera.strategy.CameraHardwareStrategy

class ZoomController(private val strategy: CameraHardwareStrategy) {

    // Faktor pro hluboký digitální ořez nad rámec optiky/hardwaru (např. 10x HW max -> 150x = 15x Crop)
    var softwareCropFactor: Float = 1.0f
        private set

    fun setZoom(zoomRatio: Float) {
        val hardwareMax = strategy.getMaxZoomRatio()
        val hardwareMin = strategy.getMinZoomRatio()
        
        if (zoomRatio <= hardwareMax) {
            // Fáze 1: Hardwarový zoom zvládne čip/optika (1x - Maxx)
            strategy.setZoom(zoomRatio.coerceAtLeast(hardwareMin))
            softwareCropFactor = 1.0f
        } else {
            // Fáze 2: Za hranicí čipu -> Hardwarový max + Softwarový Crop (Deep Zoom)
            strategy.setZoom(hardwareMax)
            softwareCropFactor = zoomRatio / hardwareMax
        }
    }

    val currentZoomRatio: Float
        get() = strategy.getZoomRatio() * softwareCropFactor

    val minZoomRatio: Float
        get() = strategy.getMinZoomRatio()

    val maxZoomRatio: Float
        get() = 400.0f // Povolíme UI slideru jít až na 400x (Extreme Deep Zoom)
}
