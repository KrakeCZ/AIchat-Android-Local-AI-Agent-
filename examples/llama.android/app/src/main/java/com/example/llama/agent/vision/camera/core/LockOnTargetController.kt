package com.example.llama.agent.vision.camera.core

import android.graphics.Rect
import com.example.llama.agent.vision.camera.math.CameraCoordinateTransformer
import com.example.llama.agent.vision.camera.strategy.CameraHardwareStrategy

class LockOnTargetController(private val strategy: CameraHardwareStrategy) {

    private var isLocked = false
    private var activeSensorRect: Rect? = null

    fun initialize(sensorRect: Rect) {
        this.activeSensorRect = sensorRect
    }

    /**
     * Uzamkne autofokus a expozici na daný výřez.
     */
    fun lockOnTarget(
        mlLeft: Float, mlTop: Float, mlRight: Float, mlBottom: Float,
        currentZoom: Float
    ) {
        val sensor = activeSensorRect ?: return
        
        // Přepočet do senzorového prostoru (un-zoom)
        val meteringRect = CameraCoordinateTransformer.transformMlBoxToSensorRegion(
            mlLeft, mlTop, mlRight, mlBottom,
            currentZoom,
            sensor
        )
        
        strategy.lockFocusOnRegion(meteringRect)
        isLocked = true
    }

    /**
     * Aktualizuje region bez nového triggeru autofokusu (plynulé sledování).
     */
    fun updateTrackingRegion(
        mlLeft: Float, mlTop: Float, mlRight: Float, mlBottom: Float,
        currentZoom: Float
    ) {
        if (!isLocked) return
        val sensor = activeSensorRect ?: return
        val meteringRect = CameraCoordinateTransformer.transformMlBoxToSensorRegion(
            mlLeft, mlTop, mlRight, mlBottom,
            currentZoom,
            sensor
        )
        strategy.updateFocusRegion(meteringRect)
    }

    fun releaseLock() {
        if (isLocked) {
            strategy.releaseFocusLock()
            isLocked = false
        }
    }

    fun isCurrentlyLocked(): Boolean = isLocked
}
