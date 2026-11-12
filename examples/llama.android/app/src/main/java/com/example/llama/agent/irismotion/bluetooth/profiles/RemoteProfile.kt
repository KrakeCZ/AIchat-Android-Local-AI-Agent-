package com.example.llama.agent.irismotion.bluetooth.profiles

import com.example.llama.agent.irismotion.bluetooth.BluetoothHidTransmitter

/**
 * Standardizované rozhraní pro všechny vzdálené profily.
 * Umožňuje izolaci logiky pro specifický ovládaný hardware (Myš, Gamepad, Dron, atd.)
 */
interface RemoteProfile {
    val profileId: String
    val profileName: String

    /** Voláno při každém updatu souřadnic z očí */
    fun handleGazeUpdate(x: Float, y: Float, transmitter: BluetoothHidTransmitter?)

    /** Voláno při rozpoznání gesta očí (mrknutí atd.) */
    fun handleEyeGesture(actionId: Int, transmitter: BluetoothHidTransmitter?)

    /** Voláno při rozpoznání celotělového gesta z MediaPipe Holistic (připraveno pro budoucnost) */
    fun handleHolisticPose(poseData: Any, transmitter: BluetoothHidTransmitter?)

    /** Uklidí stavové proměnné profilu (např. vynuluje detekované relativní delty) */
    fun resetTracking()

    /** Aktualizuje citlivost nebo jiná nastavení za běhu bez nutnosti restartu profilu */
    fun updateSensitivity(sensitivity: Float)
}
