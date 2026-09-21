package com.example.llama.agent.vision.camera.strategy

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlin.math.max


class SamsungCameraStrategy(context: Context) : DefaultGoogleCameraStrategy(context) {

    private var activeLensInfo: CameraLensInfo? = null

    override fun getAvailableLenses(): List<CameraLensInfo> {
        val lenses = mutableListOf<CameraLensInfo>()
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            val cameraIdList = cameraManager.cameraIdList
            val defaultBackCameraId = cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }

            if (defaultBackCameraId != null) {
                // Přidáme pevné mapování pro fyzické senzory S24 Ultra využívající Logical Optical Switching
                // Zoomy maji na slideru opticke rozmezi min/max + mohou od optickeho maxima jit do digitalnim zoomu +60
                // AUTO=přepíná objektivy DLE OPTICKEHO ZOOMU TAHEM SLIDERU
                // UltraWide P (0.6x) = 12MP ULTRA WIDE angle
                // Wide P (1.0x) = 200MP MAIN wide angle
                // Telephoto P (3.0x) = 3X OPTICKÝ ZOOM - MAIN (200mp přepíná na 10MP)
                // SuperTelephoto P (5.0x) = 50MP Periscope TELEPHOTO
                // Main 10x Digi (10.0x) = 10x In-sensor/Digital z 50MP periscope
                //
                val chars = cameraManager.getCameraCharacteristics(defaultBackCameraId)
                val physicalIds = chars.physicalCameraIds

                // Bezpečně zjistíme a seřadíme všechny fyzické senzory podle jejich reálné ohniskové vzdálenosti
                val sortedPhysicalCameras = physicalIds.mapNotNull { physId ->
                    val physChars = cameraManager.getCameraCharacteristics(physId)
                    val fl = physChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                    if (fl != null) Pair(physId, fl) else null
                }.sortedBy { it.second }

                val idUw = sortedPhysicalCameras.getOrNull(0)?.first ?: defaultBackCameraId
                val idWide = sortedPhysicalCameras.getOrNull(1)?.first ?: defaultBackCameraId
                val idTele3x = sortedPhysicalCameras.getOrNull(2)?.first ?: defaultBackCameraId
                val idTele5x = sortedPhysicalCameras.getOrNull(3)?.first ?: defaultBackCameraId

                lenses.add(CameraLensInfo(defaultBackCameraId, "Auto", 1.0f, false, defaultBackCameraId))
                lenses.add(CameraLensInfo(idUw, "UltraWide P (0.6x)", 0.6f, idUw != defaultBackCameraId, defaultBackCameraId))
                lenses.add(CameraLensInfo(idWide, "Wide P (1.0x)", 1.0f, idWide != defaultBackCameraId, defaultBackCameraId))
                lenses.add(CameraLensInfo(idTele3x, "Telephoto P (3.0x)", 3.0f, idTele3x != defaultBackCameraId, defaultBackCameraId))
                lenses.add(CameraLensInfo(idTele5x, "SuperTelephoto P (5.0x)", 5.0f, idTele5x != defaultBackCameraId, defaultBackCameraId))
                lenses.add(CameraLensInfo(defaultBackCameraId, "Main 10x Digi (10.0x)", 10.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(15.0x)", 15.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(22.0x)", 22.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(30.0x)", 30.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(40.0x)", 40.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(50.0x)", 50.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(60.0x)", 60.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(70.0x)", 70.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(80.0x)", 80.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(90.0x)", 90.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(100.0x)", 100.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(160.0x)", 160.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(180.0x)", 180.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(200.0x)", 200.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(220.0x)", 220.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(260.0x)", 260.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(300.0x)", 300.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(360.0x)", 360.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(400.0x)", 400.0f, false, defaultBackCameraId)) // Tohle je pouze digital zoom
                lenses.add(CameraLensInfo(defaultBackCameraId, "(9.0x)", 9.0f, true, defaultBackCameraId)) // Tohle je pouze digital zoom
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return lenses
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun bindCameraUseCases(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        preview: Preview?,
        imageAnalysis: ImageAnalysis,
        lensFacing: Int,
        lensInfo: CameraLensInfo?
    ) {
        activeLensInfo = lensInfo

        // Zpět k obchvatu na logickou kameru, matematický přepočet vynutí fyzickou čočku spolehlivě
        val safeLensInfo = lensInfo?.copy(id = lensInfo.logicalParentId, isPhysical = false)

        super.bindCameraUseCases(cameraProvider, lifecycleOwner, preview, imageAnalysis, lensFacing, safeLensInfo)

        // Zafixování režimu IHNED po startu (zakázání auto-fallbacků a Scene Optimizeru)
        if (lensInfo != null && lensInfo.id != "Auto") {
            camera?.cameraControl?.let { control ->
                try {
                    val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        val captureRequestOptions = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                            .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                            .build()
                        camera2Control.setCaptureRequestOptions(captureRequestOptions)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Okamžitá aplikace optického zoomu přes Camera2 API
        lensInfo?.let {
            if (it.opticalZoom != 1.0f && it.id != "Auto") {
                setZoom(it.opticalZoom)
            }
        }
    }

    override fun getMinZoomRatio(): Float {
        val baseMin = super.getMinZoomRatio()
        return if (activeLensInfo != null && activeLensInfo?.id != "Auto") {
            Math.max(baseMin, activeLensInfo!!.opticalZoom)
        } else {
            baseMin
        }
    }

    override fun getMaxZoomRatio(): Float {
        return super.getMaxZoomRatio()
    }

    override fun getZoomRatio(): Float {
        return super.getZoomRatio()
    }

    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun setZoom(zoomRatio: Float) {
        camera?.cameraControl?.let { control ->
            try {
                val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(control)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    val captureRequestOptions = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
                        // Zakázání automatického přepínání scén a fallbacků (jako v Pro Mode)
                        .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE, android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE_DISABLED)
                        // Omezení auto-makro a dalších automatik
                        .setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                        .build()
                    camera2Control.setCaptureRequestOptions(captureRequestOptions)
                    control.setZoomRatio(zoomRatio)
                } else {
                    control.setZoomRatio(zoomRatio)
                }
            } catch (e: Exception) {
                control.setZoomRatio(zoomRatio)
            }
        }
    }

    // Přetížení metody pro vynucení čistého hardwarového Pro Mode chování na Samsungu
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    override fun updateProSettings(isProMode: Boolean, iso: Int, exposureTimeNs: Long, whiteBalanceMode: Int) {
        super.updateProSettings(isProMode, iso, exposureTimeNs, whiteBalanceMode)
        
        camera?.let { cam ->
            try {
                val camera2Control = androidx.camera.camera2.interop.Camera2CameraControl.from(cam.cameraControl)
                val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                
                // Striktní zákaz Samsung post-processingu při manuálním Pro režimu
                builder.setCaptureRequestOption(
                    android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE, 
                    android.hardware.camera2.CaptureRequest.CONTROL_SCENE_MODE_DISABLED
                )
                camera2Control.addCaptureRequestOptions(builder.build())
            } catch (e: Exception) {}
        }
    }
}
