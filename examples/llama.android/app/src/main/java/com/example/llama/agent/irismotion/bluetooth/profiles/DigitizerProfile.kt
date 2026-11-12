package com.example.llama.agent.irismotion.bluetooth.profiles

import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.irismotion.PhysicalGesture
import com.example.llama.agent.irismotion.bluetooth.BluetoothHidTransmitter
import com.example.llama.agent.irismotion.bluetooth.HidBridge

/**
 * Absolutní Digitizer Profil.
 * Mapuje normalizované souřadnice (0.0..1.0) na absolutní rozlišení (0..32767).
 * Poskytuje pixel-perfect teleportaci kurzoru 1:1, bez driftu, bez nutnosti resetování.
 */
class DigitizerProfile(
    private val settingsManager: SettingsManager,
    private val customMapping: Map<Int, Int> = emptyMap()
) : RemoteProfile {

    override val profileId = "default_pc_digitizer"
    override val profileName = "PC Digitizér (Absolutní Mód)"

    companion object {
        private const val TAG = "DigitizerProfile"
        private const val MAX_HID_VALUE = 32767f // Hodnota odpovídající 100% šířce/výšce v HID deskriptoru
    }

    private var lastSentX: Int = -1
    private var lastSentY: Int = -1
    private var lastTargetX: Float = -1f
    private var lastTargetY: Float = -1f

    override fun handleGazeUpdate(x: Float, y: Float, transmitter: BluetoothHidTransmitter?) {
        val transmitter = transmitter ?: HidBridge.transmitter
        transmitter ?: return

        // Ze service dostáváme čistá normalizovaná procenta 0.0 - 1.0 (zcela bez vazby na telefon)
        var normX = x
        var normY = y

        // Aplikujeme nezávislé BT zrcadlení
        if (settingsManager.irismotionBtInvertX) {
            normX = 1f - normX
        }
        if (settingsManager.irismotionBtInvertY) {
            normY = 1f - normY
        }

        // --- 1. NEZÁVISLÁ MRTVA ZÓNA (DEADZONE) PRO DIGITIZER ---
        // Vypočteme cílové pixely na vzdáleném displeji výhradně pro výpočet Deadzone (mikrotřesů)
        val remoteWidth = settingsManager.irismotionBtRemoteWidth.toFloat().coerceAtLeast(1f)
        val remoteHeight = settingsManager.irismotionBtRemoteHeight.toFloat().coerceAtLeast(1f)
        val targetPixelX = normX * remoteWidth
        val targetPixelY = normY * remoteHeight

        if (lastTargetX >= 0f && lastTargetY >= 0f) {
            val dx = targetPixelX - lastTargetX
            val dy = targetPixelY - lastTargetY
            val magnitude = kotlin.math.hypot(dx, dy)
            if (magnitude < settingsManager.irismotionBtDeadzone) {
                return // Ignorujeme mikrotřesy (zůstává na původní pozici)
            }
        }
        lastTargetX = targetPixelX
        lastTargetY = targetPixelY

        // --- 2. ODESLÁNÍ DO HID DESKRIPTORU ---
        // Převedeme procenta (0.0 - 1.0) na absolutní HID hodnoty (0 - 32767)
        val hidX = (normX * MAX_HID_VALUE).toInt().coerceIn(0, MAX_HID_VALUE.toInt())
        val hidY = (normY * MAX_HID_VALUE).toInt().coerceIn(0, MAX_HID_VALUE.toInt())

        // [CRITICAL FIX]: Nesmíme spamovat Bluetooth IPC na Main Threadu 30x za sekundu, 
        // pokud se hodnoty reálně nezměnily. Jinak dojde k zaseknutí Main Threadu, což zpozdí
        // zpracování snímků z kamery, rozbije Kalman/OneEuro filtry a kurzor začne šíleně létat!
        if (kotlin.math.abs(hidX - lastSentX) > 10 || kotlin.math.abs(hidY - lastSentY) > 10) {
            transmitter.sendAbsolutePointer(hidX, hidY)
            lastSentX = hidX
            lastSentY = hidY
        }
    }

    override fun handleEyeGesture(actionId: Int, transmitter: BluetoothHidTransmitter?) {
        val transmitter = transmitter ?: HidBridge.transmitter
        transmitter ?: return

        // 1. Zkusíme najít namapovanou akci
        val mappedAction = customMapping[actionId]
        if (mappedAction != null) {
            when (mappedAction) {
                0 -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.LEFT_BUTTON)
                1 -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.RIGHT_BUTTON)
                2 -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.MIDDLE_BUTTON)
            }
            return
        }

        // 2. Fallback na hardcoded hodnoty
        when (actionId) {
            PhysicalGesture.LEFT_BLINK.id -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.LEFT_BUTTON)
            PhysicalGesture.RIGHT_BLINK.id -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.RIGHT_BUTTON)
            PhysicalGesture.DWELL_CLICK.id -> transmitter.sendAbsoluteClick(BluetoothHidTransmitter.LEFT_BUTTON)
            PhysicalGesture.DOUBLE_CLICK.id -> {
                transmitter.sendAbsoluteClick(BluetoothHidTransmitter.LEFT_BUTTON)
                transmitter.sendAbsoluteClick(BluetoothHidTransmitter.LEFT_BUTTON)
            }
            PhysicalGesture.BOTH_BLINK.id -> {
                transmitter.sendAbsoluteClick(BluetoothHidTransmitter.MIDDLE_BUTTON)
            }
            PhysicalGesture.SMILE.id -> {
                transmitter.sendAbsoluteClick(BluetoothHidTransmitter.RIGHT_BUTTON)
            }
            PhysicalGesture.FROWN.id -> {
                transmitter.sendMouseMove(0, 0, wheel = -1) // Scroll down
            }
            PhysicalGesture.RAISE_EYEBROWS.id -> {
                transmitter.sendMouseMove(0, 0, wheel = 1)  // Scroll up
            }
            else -> {
                val gestureName = PhysicalGesture.fromId(actionId)?.name ?: "Neznámé gesto"
                Log.d(TAG, "Gesto nepodporováno v Digitizer profilu: $gestureName ($actionId)")
            }
        }

    }

    override fun handleHolisticPose(poseData: Any, transmitter: BluetoothHidTransmitter?) {
        // Zde budeme přidávat Pinch To Click přes Holistic Pipeline!
    }

    override fun resetTracking() {
        // Digitizer nemá vnitřní paměť souřadnic (nemá delta), takže není co resetovat
    }

    override fun updateSensitivity(sensitivity: Float) {
        // Absolutní profil citlivost ignoruje, používá přesné mapování
    }
}
