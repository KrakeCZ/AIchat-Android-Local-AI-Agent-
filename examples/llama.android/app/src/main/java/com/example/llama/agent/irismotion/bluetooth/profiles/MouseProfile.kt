package com.example.llama.agent.irismotion.bluetooth.profiles

import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.irismotion.bluetooth.BluetoothHidTransmitter
import com.example.llama.agent.irismotion.bluetooth.HidBridge
import com.example.llama.agent.irismotion.bluetooth.MouseDeltaTranslator
import com.example.llama.agent.irismotion.PhysicalGesture

class MouseProfile(
    private val settingsManager: SettingsManager,
    private val customMapping: Map<Int, Int> = emptyMap()
) : RemoteProfile {

    override val profileId = "default_pc_mouse"
    override val profileName = "PC Myš (Standard)"

    private val TAG = "MouseProfile"

    // Vlastní překladač absolutních souřadnic na delta pohyb pro PC myš
    private val translator = MouseDeltaTranslator(settingsManager = settingsManager) { dx, dy ->
        HidBridge.transmitter?.sendMouseMove(dx, dy)
    }

    override fun handleGazeUpdate(x: Float, y: Float, transmitter: BluetoothHidTransmitter?) {
        // Počítá Deltu a odesílá přes transmitter
        translator.processAbsoluteCoordinates(x, y)
    }

    override fun handleEyeGesture(actionId: Int, transmitter: BluetoothHidTransmitter?) {
        if (transmitter == null) {
            Log.d(TAG, "Transmitter není dostupný, zahazuji gesto: $actionId")
            return
        }

        // 1. Zkusíme najít namapovanou akci v Databázi (z RAM)
        val mappedAction = customMapping[actionId]

        if (mappedAction != null) {
            when (mappedAction) {
                0 -> transmitter.sendMouseClick(BluetoothHidTransmitter.LEFT_BUTTON)
                1 -> transmitter.sendMouseClick(BluetoothHidTransmitter.RIGHT_BUTTON)
                2 -> transmitter.sendMouseClick(BluetoothHidTransmitter.MIDDLE_BUTTON)
                -1 -> transmitter.sendMouseMove(0, 0, wheel = 1)  // Nahoru
                -2 -> transmitter.sendMouseMove(0, 0, wheel = -1) // Dolů
            }
            return
        }

        // 2. Fallback na hardcoded hodnoty pro zpětnou kompatibilitu a výchozí chování
        when (actionId) {
            PhysicalGesture.LEFT_BLINK.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.LEFT_BUTTON)
            }
            PhysicalGesture.RIGHT_BLINK.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.RIGHT_BUTTON)
            }
            PhysicalGesture.DWELL_CLICK.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.LEFT_BUTTON)
            }
            PhysicalGesture.DOUBLE_CLICK.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.LEFT_BUTTON)
                transmitter.sendMouseClick(BluetoothHidTransmitter.LEFT_BUTTON)
            }
            PhysicalGesture.BOTH_BLINK.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.MIDDLE_BUTTON)
            }
            PhysicalGesture.SMILE.id -> {
                transmitter.sendMouseClick(BluetoothHidTransmitter.RIGHT_BUTTON)
            }
            PhysicalGesture.FROWN.id -> {
                transmitter.sendMouseMove(0, 0, wheel = -1) // Scroll down
            }
            PhysicalGesture.RAISE_EYEBROWS.id -> {
                transmitter.sendMouseMove(0, 0, wheel = 1)  // Scroll up
            }
            else -> {
                val gestureName = PhysicalGesture.fromId(actionId)?.name ?: "Neznámé gesto"
                Log.d(TAG, "Toto gesto profil PC Myš nepodporuje: $gestureName ($actionId)")
            }
        }
    }

    override fun handleHolisticPose(poseData: Any, transmitter: BluetoothHidTransmitter?) {
        // Myš nereaguje na celotělová gesta, ignorujeme
    }

    override fun resetTracking() {
        translator.reset()
    }

    override fun updateSensitivity(sensitivity: Float) {
        translator.updateSensitivity(sensitivity)
    }
}
