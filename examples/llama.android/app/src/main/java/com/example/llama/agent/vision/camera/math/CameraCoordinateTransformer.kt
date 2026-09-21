package com.example.llama.agent.vision.camera.math

import android.graphics.Rect
import android.hardware.camera2.params.MeteringRectangle

/**
 * Matematický modul pro transformaci souřadnic z ML prostoru do Sensor prostoru.
 */
object CameraCoordinateTransformer {

    /**
     * Vezme normalizovaný box z TFLite a vrátí MeteringRectangle pro Camera2
     * @param mlLeft, mlTop, mlRight, mlBottom Hodnoty 0.0f až 1.0f z detektoru
     * @param currentZoom Aktuální zoom ratio
     * @param activeArray Velikost plného senzoru (active_array_size)
     */
    fun transformMlBoxToSensorRegion(
        mlLeft: Float, mlTop: Float, mlRight: Float, mlBottom: Float,
        currentZoom: Float,
        activeArray: Rect
    ): MeteringRectangle {
        val sensorWidth = activeArray.width()
        val sensorHeight = activeArray.height()

        // 1. Spočítáme velikost viditelného výřezu na senzoru při daném zoomu
        val visibleWidth = sensorWidth / currentZoom
        val visibleHeight = sensorHeight / currentZoom

        // 2. Spočítáme okraje tohoto výřezu (středový ořez)
        val cropLeft = (sensorWidth - visibleWidth) / 2f
        val cropTop = (sensorHeight - visibleHeight) / 2f

        // 3. Mapujeme normalizované ML souřadnice
        val finalLeft = (cropLeft + (mlLeft * visibleWidth)).toInt()
        val finalTop = (cropTop + (mlTop * visibleHeight)).toInt()
        val finalRight = (cropLeft + (mlRight * visibleWidth)).toInt()
        val finalBottom = (cropTop + (mlBottom * visibleHeight)).toInt()

        // 4. Sanity check / Clamp
        val clampedLeft = finalLeft.coerceIn(0, sensorWidth - 1)
        val clampedTop = finalTop.coerceIn(0, sensorHeight - 1)
        val clampedWidth = (finalRight - finalLeft).coerceAtLeast(1).coerceAtMost(sensorWidth - clampedLeft)
        val clampedHeight = (finalBottom - finalTop).coerceAtLeast(1).coerceAtMost(sensorHeight - clampedTop)

        return MeteringRectangle(
            clampedLeft,
            clampedTop,
            clampedWidth,
            clampedHeight,
            MeteringRectangle.METERING_WEIGHT_MAX
        )
    }
}
