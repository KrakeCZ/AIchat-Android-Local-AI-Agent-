package com.example.llama.agent.vision.camera.strategy

import android.content.Context
import android.os.Build

object CameraEngineFactory {
    fun createStrategy(context: Context): CameraHardwareStrategy {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("samsung") -> SamsungCameraStrategy(context)
            manufacturer.contains("xiaomi") -> XiaomiCameraStrategy(context)
            else -> DefaultGoogleCameraStrategy(context)
        }
    }
}
