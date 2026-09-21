package com.example.llama.agent.vision.nightmode.plugins

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.llama.agent.vision.nightmode.NightModePlugin
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
/**
 * TFLite Loader pro pokročilé Low-Light modely (Zero-DCE, MobileIE, LiteIE).
 * Bude dynamicky nahrávat custom .tflite modely, které studenti stáhnou.
 */
class LiteIETflitePlugin(
    private val context: Context,
    private val settingsManager: com.example.llama.SettingsManager
) : NightModePlugin {

    override val pluginId = "night_tflite_mobileie"
    override val displayName = "TFLite Edge (Zero-DCE/MobileIE)"

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    
    // TFLite input/output specs (typicky 400x400, 600x400 atd. záleží na modelu,
    // načteme je z interpreter input tensoru)
    private var inputWidth = 256
    private var inputHeight = 256
    private var isFloatModel = true
    private var inputIsNchw = false

    private var currentLoadedPath: String? = null
    
    // Explicitní zámek pro prevenci concurrency pádů C++ TFLite interpreteru (SIGSEGV)
    private val mutex = Mutex()
    
    @Volatile private var isInitializing = false

    override suspend fun init() {
        mutex.withLock {
            initInternal()
        }
    }

    private fun initInternal() {
        val modelPath = settingsManager.customNightModeModelPath
        Log.i("NightMode", "🔧 LiteIETflitePlugin.init() called with path: $modelPath")
        if (modelPath == null || !File(modelPath).exists()) {
            throw IllegalArgumentException("No custom TFLite model found at: $modelPath")
        }
        
        currentLoadedPath = modelPath

        val options = Interpreter.Options()
        options.setNumThreads(settingsManager.perceptionCpuThreads)
        
        val modelBuffer = loadModelFile(modelPath)
        try {
            // Zkusit akceleraci (GPU) pokud je podporována pro tento model (Zero-DCE je často CNN, GpuDelegate je super rychlý)
            val compatList = CompatibilityList()
            if (compatList.isDelegateSupportedOnThisDevice) {
                val delegateOptions = compatList.bestOptionsForThisDevice
                gpuDelegate = GpuDelegate(delegateOptions)
                options.addDelegate(gpuDelegate)
                Log.i("NightMode", "✅ GPU Delegate attached for Low-Light Enhancement")
            }
            
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            Log.w("NightMode", "GPU Delegate selhal, zkouším čisté CPU...", e)
            gpuDelegate?.close()
            gpuDelegate = null
            
            val cpuOptions = Interpreter.Options().apply { setNumThreads(settingsManager.perceptionCpuThreads) }
            interpreter = Interpreter(modelBuffer, cpuOptions)
        }
        
        // Zjištění vstupních rozměrů z modelu
        val inputShape = interpreter!!.getInputTensor(0).shape() // Většinou [1, H, W, 3] nebo [1, 3, H, W]
        if (inputShape.size == 4) {
            if (inputShape[1] == 3) {
                // [1, 3, H, W] - PyTorch styl (NCHW)
                inputIsNchw = true
                inputHeight = inputShape[2]
                inputWidth = inputShape[3]
            } else {
                // [1, H, W, 3] - Klasický TFLite styl (NHWC)
                inputIsNchw = false
                inputHeight = inputShape[1]
                inputWidth = inputShape[2]
            }
        } else {
            inputIsNchw = false
            inputHeight = 256
            inputWidth = 256
        }
        isFloatModel = interpreter!!.getInputTensor(0).dataType() == org.tensorflow.lite.DataType.FLOAT32
        
        val outputTensor = interpreter!!.getOutputTensor(0)
        val outShape = outputTensor.shape()
        Log.i("NightMode", "✅ TFLite Night Model loaded: Input=${inputShape.contentToString()} (float=$isFloatModel), Output=${outShape.contentToString()} type=${outputTensor.dataType()}")
    }

    override suspend fun process(input: Bitmap): Bitmap {
        val desiredPath = settingsManager.customNightModeModelPath
        
        if (desiredPath != currentLoadedPath && !isInitializing) {
            isInitializing = true
            CoroutineScope(Dispatchers.IO).launch {
                mutex.withLock {
                    if (desiredPath != currentLoadedPath) {
                        Log.i("NightMode", "🔄 Auto-reloading TFLite in background. Old: $currentLoadedPath, New: $desiredPath")
                        releaseInternal()
                        if (desiredPath != null) {
                            try {
                                initInternal()
                            } catch (e: Exception) {
                                Log.e("NightMode", "Failed to auto-reload model", e)
                                currentLoadedPath = desiredPath // prevent infinite loop
                            }
                        }
                    }
                }
                isInitializing = false
            }
        }
        
        if (isInitializing) {
            // Propustíme framy bez záseku, zatímco inicializace běží
            // Upozornění: Obraz může na 500ms "probliknout" do tmy (vracíme RAW z kamery bez NightMode)
            return input 
        }

        mutex.withLock {
            val tf = interpreter ?: return input

        // 1. Resize na požadované rozlišení modelu (Zero-DCE atd. často berou fixní size, 
        // ačkoliv FCN sítě mohou brát variabilní. TFLite Java API ale vyžaduje přesný tensor size)
        val resized = Bitmap.createScaledBitmap(input, inputWidth, inputHeight, true)
        
        // --- VSTUPNÍ DATABÁZE A NASTAVENÍ ---
        val actualInputScale: Float
        val actualInputOffset: Float
        if (settingsManager.nightModeManualScaling) {
            actualInputScale = settingsManager.nightModeInputScale
            actualInputOffset = settingsManager.nightModeInputOffset
        } else {
            val path = currentLoadedPath?.lowercase() ?: ""
            if (path.contains("mobileie")) {
                actualInputScale = 127.5f
                actualInputOffset = 1.0f // Většinou MobileNet backbones vyžadují [-1, 1]
            } else {
                // Výchozí pro Zero-DCE a většinu dalších je [0, 1]
                actualInputScale = 255.0f
                actualInputOffset = 0.0f 
            }
        }

        
        // 2. Příprava ByteBufferu
        val bytesPerChannel = if (isFloatModel) 4 else 1
        val inputBuffer = ByteBuffer.allocateDirect(1 * inputHeight * inputWidth * 3 * bytesPerChannel)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(inputWidth * inputHeight)
        resized.getPixels(intValues, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        
        if (inputIsNchw) {
            // NCHW layout: All R, then All G, then All B
            for (channel in 0..2) {
                var pixel = 0
                for (y in 0 until inputHeight) {
                    for (x in 0 until inputWidth) {
                        val value = intValues[pixel++]
                        val cVal = when (channel) {
                            0 -> (value shr 16 and 0xFF) // R
                            1 -> (value shr 8 and 0xFF)  // G
                            else -> (value and 0xFF)     // B
                        }
                        if (isFloatModel) {
                            inputBuffer.putFloat((cVal / actualInputScale) - actualInputOffset)
                        } else {
                            inputBuffer.put(cVal.toByte())
                        }
                    }
                }
            }
        } else {
            // NHWC layout (RGB, RGB, RGB)
            var pixel = 0
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val value = intValues[pixel++]
                    val r = (value shr 16 and 0xFF)
                    val g = (value shr 8 and 0xFF)
                    val b = (value and 0xFF)
    
                    if (isFloatModel) {
                        inputBuffer.putFloat((r / actualInputScale) - actualInputOffset)
                        inputBuffer.putFloat((g / actualInputScale) - actualInputOffset)
                        inputBuffer.putFloat((b / actualInputScale) - actualInputOffset)
                    } else {
                        inputBuffer.put(r.toByte())
                        inputBuffer.put(g.toByte())
                        inputBuffer.put(b.toByte())
                    }
                }
            }
        }
        
        // 3. Inference
        val outTensor = tf.getOutputTensor(0)
        val outBytes = outTensor.numBytes()
        val outIsFloat = outTensor.dataType() == org.tensorflow.lite.DataType.FLOAT32
        val outShape = outTensor.shape()
        
        val outputBuffer = ByteBuffer.allocateDirect(outBytes)
        outputBuffer.order(ByteOrder.nativeOrder())
        
        val t0 = System.nanoTime()
        inputBuffer.rewind()
        tf.run(inputBuffer, outputBuffer)
        val t1 = System.nanoTime()
        Log.d("NightMode", "TFLite Inference time: ${(t1 - t0) / 1_000_000} ms")

        // 4. Rekonstrukce
        outputBuffer.rewind()
        
        // Zjištění jak je output formátován
        val outH: Int
        val outW: Int
        var channelsFirst = false
        if (outShape.size == 4) {
            if (outShape[1] == 3) { // [1, 3, H, W]
                channelsFirst = true
                outH = outShape[2]
                outW = outShape[3]
            } else { // [1, H, W, 3]
                outH = outShape[1]
                outW = outShape[2]
            }
        } else {
            outH = inputHeight
            outW = inputWidth
        }

        var denormScale = 255.0f
        var denormOffset = 0.0f
        
        if (settingsManager.nightModeManualScaling) {
            denormScale = settingsManager.nightModeOutputScale
            denormOffset = settingsManager.nightModeOutputOffset
            Log.v("NightMode", "Manual-normalization: scale=$denormScale, offset=$denormOffset")
        } else if (outIsFloat) {
            var outMin = Float.MAX_VALUE
            var outMax = -Float.MAX_VALUE
            val sampleCount = Math.min(outW * outH * 3, 1000)
            for (i in 0 until sampleCount) {
                val v = outputBuffer.float
                if (v < outMin) outMin = v
                if (v > outMax) outMax = v
            }
            outputBuffer.rewind()
            
            val range = outMax - outMin
            if (settingsManager.nightModeAutoContrast && range > 0.05f && range < 1.5f && outMax <= 2.0f) {
                // EXTREME LOW CONTRAST DETECTED (např. 0.57 až 0.85)
                // Aplikujeme dynamický Min-Max Contrast Stretch (Auto-Level) pouze pokud je to povoleno v nastavení
                denormScale = 255.0f / range
                denormOffset = -outMin * denormScale
                Log.w("NightMode", "⚠️ Narrow output range ($range). Applied Dynamic Contrast Stretch!")
            } else if (outMax > 2.0f) {
                // pravděpodobně [0, 255]
                denormScale = 1.0f
                denormOffset = 0.0f
            } else if (outMin < -0.1f) {
                // pravděpodobně [-1, 1]
                denormScale = 127.5f
                denormOffset = 127.5f
            } else {
                // pravděpodobně [0, 1]
                denormScale = 255.0f
                denormOffset = 0.0f
            }
            Log.v("NightMode", "Auto-normalization: min=$outMin, max=$outMax, scale=$denormScale, offset=$denormOffset")
        }

        val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(outW * outH)
        
        if (channelsFirst) {
            // Formát [1, 3, H, W]
            // R plane, G plane, B plane
            val pixelsPerChannel = outH * outW
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    val idx = y * outW + x
                    var r = 0; var g = 0; var b = 0
                    if (outIsFloat) {
                        r = (outputBuffer.getFloat(idx * 4) * denormScale + denormOffset).toInt().coerceIn(0, 255)
                        g = (outputBuffer.getFloat((pixelsPerChannel + idx) * 4) * denormScale + denormOffset).toInt().coerceIn(0, 255)
                        b = (outputBuffer.getFloat((2 * pixelsPerChannel + idx) * 4) * denormScale + denormOffset).toInt().coerceIn(0, 255)
                    } else {
                        r = (outputBuffer.get(idx).toInt() and 0xFF)
                        g = (outputBuffer.get(pixelsPerChannel + idx).toInt() and 0xFF)
                        b = (outputBuffer.get(2 * pixelsPerChannel + idx).toInt() and 0xFF)
                    }
                    outPixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        } else {
            // Formát [1, H, W, 3]
            var pixel = 0
            for (y in 0 until outH) {
                for (x in 0 until outW) {
                    var r: Int
                    var g: Int
                    var b: Int
                    if (outIsFloat) {
                        r = (outputBuffer.float * denormScale + denormOffset).toInt().coerceIn(0, 255)
                        g = (outputBuffer.float * denormScale + denormOffset).toInt().coerceIn(0, 255)
                        b = (outputBuffer.float * denormScale + denormOffset).toInt().coerceIn(0, 255)
                    } else {
                        r = (outputBuffer.get().toInt() and 0xFF)
                        g = (outputBuffer.get().toInt() and 0xFF)
                        b = (outputBuffer.get().toInt() and 0xFF)
                    }
                    outPixels[pixel++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        
        outBitmap.setPixels(outPixels, 0, outW, 0, 0, outW, outH)

        // 5. Škálování zpět na původní velikost kamery (pokud chceme ostrý obraz, mohlo by se udělat přes AI Upscaling,
        // ale bilineární interpolace prozatím stačí pro detekční účely a preview).
        val scaledOutput = Bitmap.createScaledBitmap(outBitmap, input.width, input.height, true)
        if (scaledOutput != outBitmap) {
            outBitmap.recycle()
        }
        
        return scaledOutput
        }
    }

    private suspend fun releaseInternal() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        Log.i("NightMode", "🌙 TFLite Night Model released, waiting 500ms for VRAM cleanup...")
        // Zabráníme okamžité alokaci dalšího TFLite GPU delegate
        delay(500)
    }

    override suspend fun release() {
        mutex.withLock {
            releaseInternal()
        }
    }

    private fun loadModelFile(path: String): MappedByteBuffer {
        val file = File(path)
        FileInputStream(file).use { fileInputStream ->
            val fileChannel = fileInputStream.channel
            val startOffset = 0L
            val declaredLength = fileChannel.size()
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }
}
