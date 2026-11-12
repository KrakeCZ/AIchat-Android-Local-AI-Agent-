package com.example.llama.agent.ui.studio.genstudio.profiling

import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Duty-Cycle Throttling Governor:
 * Zabraňuje prudkému zahřátí SoC při kontinuálním běhu difúzních jader na Adreno 750 / Hexagon NPU.
 * Vkládá mikropauzy a adaptivně snižuje frekvenci kroků podle stavu PowerManageru.
 */
class ThermalDutyCycleGovernor(
    private val profiler: HardwareProfiler
) {
    companion object {
        private const val TAG = "ThermalGovernor"
    }

    suspend fun enforceCooldownIfNeeded(currentStep: Int, totalSteps: Int) {
        val profile = profiler.getActiveProfile()

        val sleepDurationMs = when (profile.thermalStatus) {
            PowerManager.THERMAL_STATUS_SEVERE -> {
                Log.w(TAG, "⚠️ Teplota SEVERE! Aplikuji cooldown 120ms v kroku $currentStep/$totalSteps")
                120L
            }
            PowerManager.THERMAL_STATUS_MODERATE -> {
                60L
            }
            PowerManager.THERMAL_STATUS_LIGHT -> {
                25L
            }
            else -> {
                0L
            }
        }

        if (sleepDurationMs > 0L) {
            delay(sleepDurationMs)
        }
    }
}
