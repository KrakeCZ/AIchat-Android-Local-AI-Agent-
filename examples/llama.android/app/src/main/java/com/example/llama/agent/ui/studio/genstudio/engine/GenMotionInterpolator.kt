package com.example.llama.agent.ui.studio.genstudio.engine

import android.content.Context
import android.util.Log
import com.example.llama.media.NativeVideoEngine
import com.example.llama.media.RenderProgressListener
import com.example.llama.media.RenderResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * NDK C++ Motion Interpolátor pro zvýšení snímkové frekvence videa z 12-16 fps na 60 fps (Ultra Smooth).
 * Využívá hardwarově akcelerovaný MediaCodec a FFmpeg optický tok v libmedia_engine.so.
 */
class GenMotionInterpolator(private val context: Context) {

    companion object {
        private const val TAG = "GenMotionInterpolator"
    }

    private val nativeEngine = NativeVideoEngine(context)

    suspend fun interpolateTo60Fps(
        inputFile: File,
        outputFile: File,
        targetFps: Int = 60,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.Default) {
        if (!inputFile.exists()) {
            return@withContext Result.failure(IllegalArgumentException("Vstupní video soubor neexistuje: ${inputFile.absolutePath}"))
        }

        Log.i(TAG, "⚡ Spouštím NDK interpolaci snímků do 60 FPS: ${inputFile.name} -> ${outputFile.name}")

        val listener = object : RenderProgressListener {
            override fun onProgress(percent: Float) {
                onProgress?.invoke(percent)
            }
        }

        val result = nativeEngine.interpolateVideoFps(inputFile, outputFile, targetFps, listener)

        return@withContext when (result) {
            is RenderResult.Success -> {
                Log.i(TAG, "✅ 60 FPS NDK interpolace dokončena: ${outputFile.absolutePath} (${outputFile.length() / 1024} KB)")
                Result.success(outputFile)
            }
            is RenderResult.Error -> {
                Log.e(TAG, "❌ Chyba NDK interpolace: ${result.message} (kód ${result.code})")
                Result.failure(RuntimeException("Chyba interpolace: ${result.message}"))
            }
            is RenderResult.Cancelled -> {
                Log.w(TAG, "Interpolace zrušena.")
                Result.failure(RuntimeException("Zrušeno uživatelem."))
            }
        }
    }
}
