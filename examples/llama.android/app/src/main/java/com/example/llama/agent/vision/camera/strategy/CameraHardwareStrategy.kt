package com.example.llama.agent.vision.camera.strategy

import android.hardware.camera2.params.MeteringRectangle
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner

data class CameraLensInfo(
    val id: String,
    val name: String,
    val opticalZoom: Float,
    val isPhysical: Boolean = false,
    val logicalParentId: String = id
)

enum class StudioFocusMode { AF, TRACK, MF }

interface CameraHardwareStrategy {
    fun bindCameraUseCases(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        preview: Preview?,
        imageAnalysis: ImageAnalysis,
        lensFacing: Int,
        lensInfo: CameraLensInfo? = null
    )
    fun setZoom(zoomRatio: Float)
    fun getMinZoomRatio(): Float
    fun getMaxZoomRatio(): Float
    fun getZoomRatio(): Float
    fun lockFocusOnRegion(rect: MeteringRectangle)
    fun updateFocusRegion(rect: MeteringRectangle)
    fun releaseFocusLock()
    fun getAvailableLenses(): List<CameraLensInfo>
    fun closeCamera()
    fun onThermalStateChanged(isCritical: Boolean)

    // 🔴 NOVÉ PRO REŽIM 2026/2027 🔴
    fun updateProSettings(
        isProMode: Boolean,
        iso: Int,
        exposureTimeNs: Long,
        whiteBalanceMode: Int
    )
    fun updateFocusMode(
        mode: StudioFocusMode,
        manualDistanceNormalized: Float // 0.0f (nekonečno) až 1.0f (makro)
    )
}
