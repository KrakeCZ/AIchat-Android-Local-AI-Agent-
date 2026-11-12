package com.example.llama.agent.vision.camera.core

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import com.example.llama.agent.vision.camera.strategy.CameraEngineFactory

class CameraCoordinator(context: Context) {

    val strategy = CameraEngineFactory.createStrategy(context)
    val zoomController = ZoomController(strategy)
    val lockOnTargetController = LockOnTargetController(strategy)

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    fun bind(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        preview: Preview?,
        imageAnalysis: ImageAnalysis,
        lensFacing: Int = CameraSelector.LENS_FACING_BACK,
        lensInfo: com.example.llama.agent.vision.camera.strategy.CameraLensInfo? = null
    ) {
        try {
            for (cameraId in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(cameraId)
                if (chars.get(CameraCharacteristics.LENS_FACING) == lensFacing) {
                    val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (activeArray != null) {
                        lockOnTargetController.initialize(activeArray)
                    }
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        strategy.bindCameraUseCases(cameraProvider, lifecycleOwner, preview, imageAnalysis, lensFacing, lensInfo)
    }

    fun getAvailableLenses() = strategy.getAvailableLenses()

    fun release() {
        lockOnTargetController.releaseLock()
        strategy.closeCamera()
    }

    fun updateThermalState(isCritical: Boolean) {
        strategy.onThermalStateChanged(isCritical)
    }
}
