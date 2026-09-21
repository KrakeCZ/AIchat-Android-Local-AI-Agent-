package com.example.llama.agent.vision.nightmode

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.media.Image
import androidx.camera.core.ImageProxy
import com.example.llama.SettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AcademicYuvEngine {

    init {
        try {
            System.loadLibrary("media_engine")
        } catch (e: Exception) {
            android.util.Log.e("AcademicYuv", "Nepodarilo se nacist media_engine JNI!", e)
        }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var qnnDelegate: org.tensorflow.lite.Delegate? = null
    private var isInitialized = false

    private var inputWidth = 0
    private var inputHeight = 0
    private var isFloatModel = false

    private var inputByteBuffer: ByteBuffer? = null
    private var outBuffer: ByteBuffer? = null

    // ZERO-COPY SOFTWARE BUFFER (PING-PONG SWAPCHAIN PROTI TEARINGU)
    private val swBitmaps = arrayOfNulls<Bitmap>(2)
    private var currentBufferIndex = 0

    // Time-Lapse (Multi-Frame Video) Encoder
    private var timeLapseEncoder: com.example.llama.media.TimeLapseEncoder? = null
    private var isRecordingTimeLapse = false
    private val timeLapseScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var lastModelName = ""
    private var lastHwMode = ""
    private var lastTargetRes = -1

    private external fun nativeYuvPlaneToFloatBuffer(
        yPlaneBufferObj: ByteBuffer, width: Int, height: Int, rowStride: Int,
        inputWidth: Int, inputHeight: Int,
        floatBufferObj: ByteBuffer, scale: Float, offset: Float, isFloatModel: Boolean
    )

    private external fun nativeFloatBufferToArgb(
        modelOutBufferObj: ByteBuffer, inputWidth: Int, inputHeight: Int,
        bitmapOutObj: Bitmap, rotation: Int, scale: Float, offset: Float, isFloatModel: Boolean
    )

    @Synchronized
    private fun initModel(context: Context, settingsManager: SettingsManager) {
        try {
            val hwMode = settingsManager.academicYuvHardwareAcceleration
            val modelPath = settingsManager.customNightModeModelPath
            val targetRes = settingsManager.perceptionResolution

            if (isInitialized && lastModelName == modelPath && lastHwMode == hwMode && lastTargetRes == targetRes) {
                return
            }
            release()

            val options = Interpreter.Options()
            options.setNumThreads(4)

            when (hwMode) {
                "GPU" -> {
                    val compatList = CompatibilityList()
                    if (compatList.isDelegateSupportedOnThisDevice) {
                        gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                        options.addDelegate(gpuDelegate)
                        android.util.Log.i("AcademicYuv", "GPU: Adreno delegate aktivovan")
                    }
                }
                "NPU" -> {
                    var qnnInitOk = false
                    try {
                        val qnnDelegateClass = Class.forName("com.qualcomm.qti.QnnDelegate")
                        val qnnOptionsClass = Class.forName("com.qualcomm.qti.QnnDelegate\$Options")
                        val qnnOpts = qnnOptionsClass.getDeclaredConstructor().newInstance()
                        
                        // qnnOptions.setBackendType(com.qualcomm.qti.QnnDelegate.Options.BackendType.HTP_BACKEND)
                        try {
                            val backendTypeEnum = Class.forName("com.qualcomm.qti.QnnDelegate\$Options\$BackendType")
                            val htpBackend = backendTypeEnum.getMethod("valueOf", String::class.java).invoke(null, "HTP_BACKEND")
                            qnnOptionsClass.getMethod("setBackendType", backendTypeEnum).invoke(qnnOpts, htpBackend)
                        } catch (e: Exception) {
                            android.util.Log.w("AcademicYuv", "NPU: BackendType enum not found or failed, ignoring")
                        }

                        qnnOptionsClass.getMethod("setSkelLibraryDir", String::class.java).invoke(qnnOpts, context.applicationInfo.nativeLibraryDir)
                        
                        val delegate = qnnDelegateClass.getDeclaredConstructor(qnnOptionsClass).newInstance(qnnOpts) as org.tensorflow.lite.Delegate
                        qnnDelegate = delegate
                        options.addDelegate(delegate)
                        qnnInitOk = true
                        android.util.Log.i("AcademicYuv", "NPU: QNN Hexagon HTP delegate aktivovan")
                    } catch (e: Exception) {
                        android.util.Log.e("AcademicYuv", "NPU: QNN init selhal. Fallback GPU.", e)
                    }
                    if (!qnnInitOk) {
                        val compatList = CompatibilityList()
                        if (compatList.isDelegateSupportedOnThisDevice) {
                            gpuDelegate = GpuDelegate(compatList.bestOptionsForThisDevice)
                            options.addDelegate(gpuDelegate)
                        }
                    }
                }
            }

            if (modelPath == null) return
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) return

            var tempInterpreter = Interpreter(modelFile, options)
            val defaultShape = tempInterpreter.getInputTensor(0).shape()
            val defaultH = defaultShape[1]
            val defaultW = defaultShape[2]

            var newW = targetRes
            var newH = targetRes

            val aspectRatio = defaultW.toFloat() / defaultH.toFloat()
            if (defaultW >= defaultH) {
                newW = targetRes
                newH = Math.round(targetRes / aspectRatio)
            } else {
                newH = targetRes
                newW = Math.round(targetRes * aspectRatio)
            }

            // Align to 16 for Conv nets
            newW = (newW / 16) * 16
            newH = (newH / 16) * 16
            if (newW == 0) newW = 16
            if (newH == 0) newH = 16

            if (newW != defaultW || newH != defaultH) {
                try {
                    tempInterpreter.resizeInput(0, intArrayOf(1, newH, newW, 3))
                    tempInterpreter.allocateTensors()
                    android.util.Log.i("AcademicYuv", "Dynamically resized tensor to ${newW}x${newH}")
                } catch (e: Exception) {
                    android.util.Log.e("AcademicYuv", "Model does not support dynamic resize, falling back to default ${defaultW}x${defaultH}", e)
                    tempInterpreter.close()
                    tempInterpreter = Interpreter(modelFile, options)
                }
            }

            interpreter = tempInterpreter
            lastModelName = modelPath ?: ""
            lastHwMode = hwMode
            lastTargetRes = targetRes
            isInitialized = true

            val inputTensor = interpreter!!.getInputTensor(0)
            isFloatModel = inputTensor.dataType() == DataType.FLOAT32
            val shape = inputTensor.shape()
            inputHeight = shape[1]
            inputWidth = shape[2]

            val modelInputBytes = inputWidth * inputHeight * (if (isFloatModel) 4 * 3 else 3)
            inputByteBuffer = ByteBuffer.allocateDirect(modelInputBytes).apply { order(ByteOrder.nativeOrder()) }

            val outTensor = interpreter!!.getOutputTensor(0)
            outBuffer = ByteBuffer.allocateDirect(outTensor.numBytes()).apply { order(ByteOrder.nativeOrder()) }

            // PING-PONG inicializujeme až při prvním framu, abychom znali rotaci kamery
            for (i in 0..1) {
                // NEVOLAT .recycle() - asynchronní UI by mohlo spadnout! Necháme na GC.
                swBitmaps[i] = null
            }
            currentBufferIndex = 0

            android.util.Log.i("AcademicYuv", "Academic Engine Init: PING-PONG SW Bitmaps prepared")
        } catch (e: Exception) {
            android.util.Log.e("AcademicYuv", "Chyba pri inicializaci", e)
        }
    }

    @Synchronized
    fun release() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        
        try {
            val closeMethod = qnnDelegate?.javaClass?.getMethod("close")
            closeMethod?.invoke(qnnDelegate)
        } catch (e: Exception) {
            android.util.Log.e("AcademicYuv", "Failed to close QNN via reflection", e)
        }
        qnnDelegate = null
        isInitialized = false
        lastModelName = ""
        lastHwMode = ""
        inputByteBuffer = null
        outBuffer = null
        
        // Uvolnění obou SW bufferů (Ping-Pong)
        for (i in 0..1) {
            // NEVOLAT .recycle() - zabraňuje SIGABRT pádu. Necháme na GC.
            swBitmaps[i] = null
        }
        
        // Zastavení případného běžícího nahrávání
        stopTimeLapse()
        
        android.util.Log.i("AcademicYuv", "Academic Engine uvolnen z pameti.")
    }

    // StateFlow pro UI, abychom viděli počet nasbíraných snímků
    private val _recordedFramesCount = kotlinx.coroutines.flow.MutableStateFlow(0)
    val recordedFramesCount: kotlinx.coroutines.flow.StateFlow<Int> = _recordedFramesCount

    /**
     * Spustí Time-Lapse záznam. Každý zpracovaný snímek bude přidán do MP4 souboru.
     */
    fun startTimeLapse(fps: Int, outputFilePath: String, targetWidth: Int = 720, targetHeight: Int = 1280, isHighQuality: Boolean = true) {
        if (isRecordingTimeLapse) return
        
        try {
            _recordedFramesCount.value = 0
            timeLapseEncoder = com.example.llama.media.TimeLapseEncoder(targetWidth, targetHeight, fps, outputFilePath, isHighQuality)
            timeLapseEncoder?.start()
            isRecordingTimeLapse = true
            android.util.Log.i("AcademicYuv", "TimeLapse záznam spuštěn do: $outputFilePath")
        } catch (e: Exception) {
            android.util.Log.e("AcademicYuv", "Chyba při startu TimeLapse", e)
            timeLapseEncoder = null
            isRecordingTimeLapse = false
        }
    }

    /**
     * Zastaví Time-Lapse záznam a uloží MP4 soubor.
     */
    fun stopTimeLapse() {
        if (!isRecordingTimeLapse) return
        isRecordingTimeLapse = false
        val encoder = timeLapseEncoder
        timeLapseEncoder = null
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                encoder?.stop()
                android.util.Log.i("AcademicYuv", "TimeLapse záznam úspěšně zastaven a uložen.")
            } catch (e: Exception) {
                android.util.Log.e("AcademicYuv", "Chyba při zastavování TimeLapse", e)
            }
        }
    }

    data class ExtractedHwFrame(
        val imageProxy: ImageProxy,
        val width: Int,
        val height: Int,
        val rowStride: Int,
        val rotation: Int
    )

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun extractYuvData(imageProxy: ImageProxy): ExtractedHwFrame? {
        val image = imageProxy.image
        if (image == null || image.format != android.graphics.ImageFormat.YUV_420_888) {
            imageProxy.close()
            return null
        }
        
        // Zde NEZAVÍRÁME imageProxy, aby HW buffer zůstal platný. 
        // Bude zavřen až po inferenci.
        return ExtractedHwFrame(
            imageProxy = imageProxy,
            width = imageProxy.width,
            height = imageProxy.height,
            rowStride = image.planes[0].rowStride,
            rotation = imageProxy.imageInfo.rotationDegrees
        )
    }

    @Synchronized
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun runInferenceOnExtractedData(
        frame: ExtractedHwFrame,
        context: Context,
        settingsManager: SettingsManager
    ): Bitmap? {
        try {
            initModel(context, settingsManager)
            val tf = interpreter

            if (tf == null || inputByteBuffer == null || outBuffer == null) {
                return null
            }

            // ALOKACE PING-PONG SW BITMAP PODLE ROTACE KAMERY
            val isPortrait = frame.rotation == 90 || frame.rotation == 270
            val targetWidth = if (isPortrait) inputHeight else inputWidth
            val targetHeight = if (isPortrait) inputWidth else inputHeight

            if (swBitmaps[0] == null || swBitmaps[0]!!.width != targetWidth || swBitmaps[0]!!.height != targetHeight) {
                for (i in 0..1) {
                    // NEVOLAT .recycle() ! Necháme GC uklidit starou paměť bezpečně.
                    swBitmaps[i] = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                }
            }

            // PING-PONG SWAPCHAIN: Přepneme na "back buffer", abychom nepřepisovali ten, který právě čte Compose Canvas
            currentBufferIndex = (currentBufferIndex + 1) % 2
            val activeBitmap = swBitmaps[currentBufferIndex]!!

            val actualScale = if (settingsManager.nightModeManualScaling) settingsManager.academicYuvInputScale else 255f
            val actualOffset = if (settingsManager.nightModeManualScaling) settingsManager.academicYuvInputOffset else 0f

            inputByteBuffer!!.rewind()

            val yPlaneBuffer = frame.imageProxy.planes[0].buffer
            try {
                nativeYuvPlaneToFloatBuffer(
                    yPlaneBuffer, frame.width, frame.height, frame.rowStride,
                    inputWidth, inputHeight,
                    inputByteBuffer!!, actualScale, actualOffset, isFloatModel
                )
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("AcademicYuv", "JNI nativeYuvPlaneToFloatBuffer not found", e)
            }

            outBuffer!!.rewind()
            try {
                tf.run(inputByteBuffer!!, outBuffer!!)
            } catch (e: Exception) {
                android.util.Log.e("AcademicYuv", "Inference error", e)
                return null
            }
            outBuffer!!.rewind()

            val outScale = if (settingsManager.nightModeManualScaling) settingsManager.academicYuvOutputScale else 255f
            val outOffset = if (settingsManager.nightModeManualScaling) settingsManager.academicYuvOutputOffset else 0f

            try {
                nativeFloatBufferToArgb(
                    outBuffer!!, inputWidth, inputHeight,
                    activeBitmap, frame.rotation, outScale, outOffset, isFloatModel
                )
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("AcademicYuv", "JNI nativeFloatBufferToArgb not found", e)
            }

            // Pokud probíhá záznam Time-Lapse (Multi-Frame videa), odešleme snímek hardwarovému enkodéru
            // Uložení rámečku, pokud běží TimeLapse a stíháme
            if (isRecordingTimeLapse) {
                timeLapseScope.launch {
                    timeLapseEncoder?.addFrame(activeBitmap)
                    val count = timeLapseEncoder?.getFrameCount() ?: 0
                    _recordedFramesCount.value = count
                }
            }

            // Vracime PING-PONG referenci na activeBitmap (SW ARGB_8888)
            // Uzavirame ImageProxy, protoze CPU preprocessing byl dokoncen!
            return activeBitmap

        } finally {
            // Absolutně nutné pro zamezení frame leaků v CameraX!
            frame.imageProxy.close()
        }
    }
}
