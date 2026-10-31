package com.example.llama.agent.vision.camera.strategy

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics

open class DefaultGoogleCameraStrategy(protected val context: Context) : CameraHardwareStrategy {
    protected var camera: Camera? = null
    protected var isThermalCritical = false
    private var lastFocusUpdateMs = 0L

    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null

    // Vnitřní stavy pro udržení konzistence při změnách UseCase
    private var internalProMode = false
    private var internalIso = 400
    private var internalExposureTimeNs = 10000000L // 1/100s
    private var internalWbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var internalFocusMode = StudioFocusMode.AF
    private var internalFocusDistanceNormalized = 0.0f

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun bindCameraUseCases(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        preview: Preview?,
        imageAnalysis: ImageAnalysis,
        lensFacing: Int,
        lensInfo: CameraLensInfo?
    ) {
        val cameraSelectorBuilder = CameraSelector.Builder().requireLensFacing(lensFacing)

        if (lensInfo != null && lensInfo.id != "Auto") {
            val isAvailableAsLogical = cameraProvider.availableCameraInfos.any {
                try {
                    androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == lensInfo.id
                } catch(e: Exception) { false }
            }

            if (isAvailableAsLogical) {
                // Samsung / Xiaomi: If the lens is exposed as a full logical camera and supports our UseCases (like UltraWide),
                // we must use addCameraFilter to select it.
                cameraSelectorBuilder.addCameraFilter { cameraInfos ->
                    val filtered = cameraInfos.filter {
                        try {
                            androidx.camera.camera2.interop.Camera2CameraInfo.  from(it).cameraId == lensInfo.id
                        } catch(e: Exception) { false }
                    }
                    if (filtered.isNotEmpty()) filtered else cameraInfos
                }
            } else if (lensInfo.isPhysical) {
                // If it's not a standalone logical camera (or CameraX hides it due to UseCase incompatibility like Telephoto),
                // we try to force the physical camera ID on the main logical camera.
                // Note: Samsung may block this at the HAL level.
                cameraSelectorBuilder.setPhysicalCameraId(lensInfo.id)
            } else {
                // Fallback pro jistotu
                cameraSelectorBuilder.addCameraFilter { cameraInfos ->
                    val filtered = cameraInfos.filter {
                        try {
                            androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == lensInfo.id
                        } catch(e: Exception) { false }
                    }
                    if (filtered.isNotEmpty()) filtered else cameraInfos
                }
            }
        }

        val cameraSelector = cameraSelectorBuilder.build()

        // Agresivní unbindAll() je nutný, protože engine je jeden.
        // Selektivní unbind způsoboval 'zombie' instance a pád na limitu 'Max allowed: 1'.
        cameraProvider.unbindAll()
        this.preview = null
        this.imageAnalysis = null

        this.preview = preview
        this.imageAnalysis = imageAnalysis

        val useCases = mutableListOf<androidx.camera.core.UseCase>(imageAnalysis)
        if (preview != null) {
            useCases.add(preview)
        }

        camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, *useCases.toTypedArray())

        // Převezení stávajícího nastavení do nově nabindované kamery
        applyRepeatingCaptureOptions()
    }

    override fun setZoom(zoomRatio: Float) {
        camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    override fun getMinZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f

    override fun getMaxZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 10f

    override fun getZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun applyRepeatingCaptureOptions() {
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val builder = CaptureRequestOptions.Builder()

            // --- EXPOZICE & ISO REŽIM ---
            if (internalProMode) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, internalIso)
                builder.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, internalExposureTimeNs)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }

            // --- WHITE BALANCE ---
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, internalWbMode)

            // --- OSTŘENÍ (AF / TRACK / MF) ---
            when (internalFocusMode) {
                StudioFocusMode.AF -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                StudioFocusMode.TRACK -> {
                    // Continuous ostření zafixované na dynamický region
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                StudioFocusMode.MF -> {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)

                    // Získání minimální vzdálenosti ostření z hardwaru zařízení
                    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val cameraId = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo).cameraId
                    val chars = cameraManager.getCameraCharacteristics(cameraId)
                    val minFocusDist = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10.0f

                    // Přepočet 0.0-1.0 na dioptrie (0.0 = nekonečno, minFocusDist = makro)
                    val actualDiopters = internalFocusDistanceNormalized * minFocusDist
                    builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, actualDiopters)
                }
            }

            camera2Control.addCaptureRequestOptions(builder.build())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun updateProSettings(isProMode: Boolean, iso: Int, exposureTimeNs: Long, whiteBalanceMode: Int) {
        internalProMode = isProMode
        internalIso = iso
        internalExposureTimeNs = exposureTimeNs
        internalWbMode = whiteBalanceMode
        applyRepeatingCaptureOptions()
    }

    override fun updateFocusMode(mode: StudioFocusMode, manualDistanceNormalized: Float) {
        internalFocusMode = mode
        internalFocusDistanceNormalized = manualDistanceNormalized
        applyRepeatingCaptureOptions()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun lockFocusOnRegion(rect: MeteringRectangle) {
        val cam = camera ?: return
        if (internalFocusMode == StudioFocusMode.MF) return // V manuálním režimu ignorujeme AF zásahy

        val now = System.currentTimeMillis()
        if (isThermalCritical && (now - lastFocusUpdateMs < 1000)) return
        lastFocusUpdateMs = now

        try {
            val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
            val captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_REGIONS, arrayOf(rect))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                .build()
            camera2CameraControl.addCaptureRequestOptions(captureRequestOptions)
        } catch (e: Exception) {
            // Ignored
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun updateFocusRegion(rect: MeteringRectangle) {
        val cam = camera ?: return
        if (internalFocusMode != StudioFocusMode.TRACK) return
        try {
            val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
            val captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
                // Klíčový trik pro rok 2026: Jemný průběžný trigger bez záseku preview streamu
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                .build()
            camera2CameraControl.addCaptureRequestOptions(captureRequestOptions)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun releaseFocusLock() {
        val cam = camera ?: return
        try {
            val camera2CameraControl = Camera2CameraControl.from(cam.cameraControl)
            val captureRequestOptions = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                .clearCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS)
                .clearCaptureRequestOption(CaptureRequest.CONTROL_AE_REGIONS)
                .clearCaptureRequestOption(CaptureRequest.CONTROL_AWB_REGIONS)
                .build()
            camera2CameraControl.addCaptureRequestOptions(captureRequestOptions)
        } catch (e: Exception) {
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    override fun getAvailableLenses(): List<CameraLensInfo> {
        val lenses = mutableListOf<CameraLensInfo>()
        lenses.add(CameraLensInfo("Auto", "AUTO", 1f))

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraIdList = cameraManager.cameraIdList
            android.util.Log.e("CAMERA_DEBUG", "Total cameras found: ${cameraIdList.size} -> ${cameraIdList.joinToString()}")

            // Najdeme výchozí zadní kameru
            val defaultBackCameraId = cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            android.util.Log.e("CAMERA_DEBUG", "Default Back Camera ID: $defaultBackCameraId")

            if (defaultBackCameraId != null) {
                val chars = cameraManager.getCameraCharacteristics(defaultBackCameraId)
                val physicalIds = chars.physicalCameraIds
                android.util.Log.e("CAMERA_DEBUG", "Physical IDs for default back camera: ${physicalIds.joinToString()}")

                // Log hardware level for default back camera
                val hwLevel = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                android.util.Log.e("CAMERA_DEBUG", "Camera $defaultBackCameraId HW Level: $hwLevel (0=LTD, 1=FULL, 2=LEGACY, 3=LEVEL_3, 4=EXTERNAL)")

                val streamConfig = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val formats = streamConfig?.outputFormats?.joinToString()
                android.util.Log.e("CAMERA_DEBUG", "Camera $defaultBackCameraId Formats: $formats")

                if (physicalIds.isNotEmpty()) {
                    // Moderní Samsung / multi-camera zařízení: používáme fyzické čočky pod hlavní logickou
                    for (physId in physicalIds) {
                        val physChars = cameraManager.getCameraCharacteristics(physId)
                        val physHwLevel = physChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        val physStreamConfig = physChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val physFormats = physStreamConfig?.outputFormats?.joinToString()
                        android.util.Log.e("CAMERA_DEBUG", "Physical Camera $physId HW Level: $physHwLevel, Formats: $physFormats")

                        val focalLengths = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                        val focal = focalLengths?.firstOrNull() ?: 5.0f

                        // Přímá matematická izolace na základě absolutní ohniskové vzdálenosti (žádné složité třídění).
                        // S24 Ultra má běžně: UltraWide ~2.2mm, Wide ~6.7mm, Telephoto > 7.5mm
                        val zoomLevel = if (focal < 4.0f) 0.5f else if (focal < 7.5f) 1.0f else (focal / 6.7f)
                        val zoomText = String.format(java.util.Locale.US, "%.1f", zoomLevel)

                        val name = when {
                            focal < 4.0f -> "UltraWide P"
                            focal > 7.5f -> "Telephoto P"
                            else -> "Wide P"
                        }
                        android.util.Log.e("CAMERA_DEBUG", "Adding physical lens: $physId -> $name")
                        lenses.add(CameraLensInfo(physId, "$name ($zoomText x)", zoomLevel, true, defaultBackCameraId))
                    }
                } else {
                    // Starší telefony nebo zařízení, která nemají physicalCameraIds (např. separátní logické kamery bez vazby)
                    for (cameraId in cameraIdList) {
                        val localChars = cameraManager.getCameraCharacteristics(cameraId)
                        val facing = localChars.get(CameraCharacteristics.LENS_FACING)
                        val localHwLevel = localChars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                        val localStreamConfig = localChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val localFormats = localStreamConfig?.outputFormats?.joinToString()
                        android.util.Log.e("CAMERA_DEBUG", "Checking Logical Camera: $cameraId, Facing: $facing, HW Level: $localHwLevel, Formats: $localFormats")

                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val focalLengths = localChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val focal = focalLengths?.firstOrNull() ?: 5.0f
                            val zoomLevel = focal / 5.0f
                            val name = when {
                                zoomLevel < 0.9f -> "UltraWide"
                                zoomLevel > 1.5f -> "Telephoto"
                                else -> "Wide"
                            }
                            val zoomText = String.format(java.util.Locale.US, "%.1f", zoomLevel)
                            lenses.add(CameraLensInfo(cameraId, "$name (${zoomText}x)", zoomLevel, false, cameraId))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return lenses.sortedBy { it.opticalZoom }
    }

    override fun closeCamera() {
        camera = null
    }

    override fun onThermalStateChanged(isCritical: Boolean) {
        isThermalCritical = isCritical
    }
}
