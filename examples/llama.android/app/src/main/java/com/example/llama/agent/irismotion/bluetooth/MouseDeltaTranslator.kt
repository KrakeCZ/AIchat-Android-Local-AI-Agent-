package com.example.llama.agent.irismotion.bluetooth

import android.util.Log

import com.example.llama.SettingsManager
import kotlin.math.hypot
import kotlin.math.pow

/**
 * Překládá absolutní souřadnice z IrisMotion (0..1920, 0..1080) na relativní pohyby myši (dx, dy).
 * Včetně vyhlazení, plovoucí mrtvé zóny a kinetické akcelerace.
 */
class MouseDeltaTranslator(
    private val settingsManager: SettingsManager,
    private val onDeltaComputed: (dx: Int, dy: Int) -> Unit
) {
    private var lastX: Float? = null
    private var lastY: Float? = null

    private var overrideSensitivity: Float? = null

    fun updateSensitivity(newSensitivity: Float) {
        overrideSensitivity = newSensitivity
    }

    /**
     * Vymaže historii. Důležité volat při zapnutí/vypnutí nebo ztrátě trackingu,
     * aby myš neustřelila přes celou obrazovku.
     */
    fun reset() {
        lastX = null
        lastY = null
    }

    fun processAbsoluteCoordinates(x: Float, y: Float) {
        // x a y jsou nyní normalizovaná procenta (0.0 - 1.0)
        // Převedeme je na "fiktivní" pixely TV obrazovky pro správné fungování mrtvé zóny a akcelerace
        val remoteWidth = settingsManager.irismotionBtRemoteWidth.toFloat().coerceAtLeast(1f)
        val remoteHeight = settingsManager.irismotionBtRemoteHeight.toFloat().coerceAtLeast(1f)
        val pixelX = x * remoteWidth
        val pixelY = y * remoteHeight

        val prevX = lastX
        val prevY = lastY

        if (prevX != null && prevY != null) {
            val rawDx = pixelX - prevX
            val rawDy = pixelY - prevY

            val magnitude = hypot(rawDx, rawDy)
            val deadzone = settingsManager.irismotionBtDeadzone
            
            // Plovoucí mrtvá zóna: ignorujeme mikropohyby (třes oka)
            if (magnitude < deadzone) {
                return // Ignorujeme, ale NEMĚNÍME lastX/lastY, aby se pohyb kumuloval!
            }

            val sensitivity = overrideSensitivity ?: settingsManager.irismotionBtSensitivity
            val acceleration = settingsManager.irismotionBtAcceleration

            // Kinetická akcelerace: (magnitude / base) ^ accel
            // Pokud je pohyb malý (kolem 10px), exponent ho tlumí nebo nechá.
            // Pokud je velký, exponent ho rapidně zvětší.
            // Ošetříme dělení nulou a příliš malé hodnoty.
            val baseDistance = 10f 
            val speedFactor = if (magnitude > 0) (magnitude / baseDistance).pow(acceleration - 1f) else 1f

            // Přepočet na delta + sensitivity + acceleration
            var scaledDx = rawDx * sensitivity * speedFactor
            var scaledDy = rawDy * sensitivity * speedFactor

            // Nezávislé zrcadlení pro Bluetooth Remote Mód
            if (settingsManager.irismotionBtInvertX) scaledDx *= -1f
            if (settingsManager.irismotionBtInvertY) scaledDy *= -1f

            // Pro BT HID Mouse posíláme celočíselné hodnoty.
            // HID Mouse descriptor běžně omezuje rozsah od -127 do 127 pro dx/dy
            val finalDx = scaledDx.toInt().coerceIn(-127, 127)
            val finalDy = scaledDy.toInt().coerceIn(-127, 127)

            if (finalDx != 0 || finalDy != 0) {
                onDeltaComputed(finalDx, finalDy)
            }
        } else {
            Log.d("MouseDeltaTranslator", "Initializing base coordinates: $x, $y")
        }

        // Uložení aktuálních souřadnic pro další výpočet
        lastX = pixelX
        lastY = pixelY
    }
}
