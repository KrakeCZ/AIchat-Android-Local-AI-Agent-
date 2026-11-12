package com.example.llama.agent.ui.studio.genstudio.profiling

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

data class HardwareProfile(
    val totalRamMb: Long,
    val availableRamMb: Long,
    val isLowMemory: Boolean,
    val thermalStatus: Int,
    val thermalStatusString: String,
    val recommendedMaxResolutionWidth: Int,
    val recommendedMaxResolutionHeight: Int,
    val recommendedBatchSteps: Int,
    val recommendedThreads: Int
)

/**
 * Senior DevOps / Kernel-level hardware profiler pro hodnocení systémových limitů
 * na zařízeních s Android 15/16+ (Snapdragon 8 Gen 3 / S24 Ultra).
 */
class HardwareProfiler(private val context: Context) {

    companion object {
        private const val TAG = "HardwareProfiler"
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun getActiveProfile(): HardwareProfile {
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val thermal = powerManager.currentThermalStatus

        val thermalStr = when (thermal) {
            PowerManager.THERMAL_STATUS_NONE -> "OPTIMAL (NONE)"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE (THROTTLED)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN ($thermal)"
        }

        // Dynamická adaptace podle dostupné paměti a teploty
        val (recW, recH) = when {
            availMb >= 5500 && thermal <= PowerManager.THERMAL_STATUS_LIGHT -> Pair(640, 368)
            availMb >= 3500 && thermal <= PowerManager.THERMAL_STATUS_MODERATE -> Pair(480, 272)
            else -> Pair(384, 256)
        }

        val recSteps = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 4
            thermal == PowerManager.THERMAL_STATUS_MODERATE -> 8
            else -> 16
        }

        val recThreads = when {
            thermal >= PowerManager.THERMAL_STATUS_SEVERE -> 2
            else -> 4
        }

        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE || memInfo.lowMemory) {
            Log.w(TAG, "Hardware status throttle warning: Total RAM: ${totalMb}MB, Avail: ${availMb}MB, Thermal: $thermalStr")
        }

        return HardwareProfile(
            totalRamMb = totalMb,
            availableRamMb = availMb,
            isLowMemory = memInfo.lowMemory,
            thermalStatus = thermal,
            thermalStatusString = thermalStr,
            recommendedMaxResolutionWidth = recW,
            recommendedMaxResolutionHeight = recH,
            recommendedBatchSteps = recSteps,
            recommendedThreads = recThreads
        )
    }
}
