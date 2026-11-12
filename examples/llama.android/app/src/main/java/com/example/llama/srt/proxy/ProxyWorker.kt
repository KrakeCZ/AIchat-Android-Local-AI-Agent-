package com.example.llama.srt.proxy

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.llama.media.NativeVideoEngine
import com.example.llama.media.RenderProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ProxyWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_INPUT_URI = "input_uri"
        const val KEY_OUTPUT_PATH = "output_path"
        private const val MIN_STORAGE_REQUIRED_MB = 2048L // 2 GB
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val inputUriStr = inputData.getString(KEY_INPUT_URI) ?: return@withContext Result.failure()
        val inputUri = Uri.parse(inputUriStr)
        
        // Zápis výhradně do CacheDir (OS si může místo kdykoliv uvolnit)
        val proxyFile = File(context.cacheDir, "proxy_${System.currentTimeMillis()}.mp4")

        // 1. Storage Deadlock Prevention (Guardrail)
        val usableSpaceMB = context.cacheDir.usableSpace / (1024 * 1024)
        if (usableSpaceMB < MIN_STORAGE_REQUIRED_MB) {
            Log.w("ProxyWorker", "Nedostatek místa pro Proxy ($usableSpaceMB MB). Bypass.")
            return@withContext Result.failure(workDataOf("reason" to "LOW_STORAGE"))
        }

        // 2. Thermal Throttling Protection (TTP Guardrail)
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val thermalStatus = powerManager.currentThermalStatus
            if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) {
                Log.e("ProxyWorker", "TTP Zásah: Zařízení se přehřívá. Zastavuji generování proxy.")
                return@withContext Result.failure(workDataOf("reason" to "THERMAL_THROTTLING"))
            }
        }

        setProgress(workDataOf("progress" to 10f)) // Zahájení

        // 3. Volání JNI FFmpeg vrstvy (Blokující C++ call na IO Dispatcheru)
        val engine = NativeVideoEngine(context)
        
        val progressListener = object : RenderProgressListener {
            override fun onProgress(percent: Float) {
                // Zamezení deadlocku: setProgressAsync() je asynchronní a bezpečné pro JNI callback z C++.
                setProgressAsync(workDataOf("progress" to percent.toInt()))
                Log.d("ProxyWorker", "Proxy progress: $percent%")
            }
        }
        
        val success = engine.generateProxy(inputUri, proxyFile, progressListener)

        return@withContext if (success && proxyFile.exists()) {
            setProgress(workDataOf("progress" to 100f))
            Result.success(workDataOf(KEY_OUTPUT_PATH to proxyFile.absolutePath))
        } else {
            // Clean up zmetku v případě pádu C++ (MediaCodec tombstone)
            if (proxyFile.exists()) proxyFile.delete()
            Result.failure(workDataOf("reason" to "NATIVE_CODEC_ERROR"))
        }
    }
}
