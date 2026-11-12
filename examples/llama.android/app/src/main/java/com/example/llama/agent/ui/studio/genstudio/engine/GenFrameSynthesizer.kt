package com.example.llama.agent.ui.studio.genstudio.engine

import android.content.Context
import android.graphics.*
import android.util.Log
import com.example.llama.data.gen.GenModel
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 2026 Čistý Neuronový Latent Dekodér pro GEN_Studio.
 * ŽÁDNÉ ŠABLONY POLYGONŮ. ŽÁDNÉ PROCEDURÁLNÍ VOZY ANI SILNICE.
 * Výhradně autentická syntéza řízená váhami neuronové sítě a difúzním jádrem.
 */
class GenFrameSynthesizer {

    companion object {
        private const val TAG = "GenFrameSynthesizer"
    }

    fun synthesizeFrames(
        config: GenExecutionConfig,
        totalFrames: Int,
        conditioning: TextConditioningTensor? = null,
        modelFilePath: String? = null,
        context: Context? = null,
        onFrameRendered: ((Int, Int, Bitmap) -> Unit)? = null
    ): List<Bitmap> {
        val width = config.width
        val height = config.height
        val frames = ArrayList<Bitmap>(totalFrames)

        val resolvedPath = if (context != null && !modelFilePath.isNullOrBlank()) {
            UriPathResolver.resolveToPath(context, modelFilePath) ?: modelFilePath
        } else modelFilePath

        // 1. Pokus o přímé volání nativního C++ neuronového enginu (100% čistá neurální syntéza)
        if (context != null && !resolvedPath.isNullOrBlank() && File(resolvedPath).exists()) {
            try {
                val dummyModel = GenModel(
                    displayName = File(resolvedPath).nameWithoutExtension,
                    sourceUri = resolvedPath,
                    filePath = resolvedPath,
                    fileSize = File(resolvedPath).length(),
                    format = if (resolvedPath.endsWith(".safetensors", true)) "SAFETENSORS" else "GGUF",
                    architecture = "DIFFUSION",
                    quantization = "Q4_K"
                )
                val engine = VideoGenEngine.getInstance()
                val result = runBlocking {
                    engine.generate(
                        context = context,
                        model = dummyModel,
                        config = config.copy(frameCount = totalFrames)
                    )
                }
                if (result.isSuccess) {
                    val file = result.getOrThrow()
                    if (totalFrames <= 1 && file.name.endsWith(".png", true)) {
                        val bmp = BitmapFactory.decodeFile(file.absolutePath)
                        if (bmp != null) {
                            frames.add(bmp)
                            onFrameRendered?.invoke(1, 1, bmp)
                            return frames
                        }
                    } else if (file.name.endsWith(".mp4", true)) {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(file.absolutePath)
                            val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            val durationMs = durationStr?.toLongOrNull() ?: 1000L
                            for (i in 0 until totalFrames) {
                                val timeUs = (i.toFloat() / maxOf(1, totalFrames - 1) * durationMs * 1000).toLong()
                                val bmp = retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                if (bmp != null) {
                                    frames.add(bmp)
                                    onFrameRendered?.invoke(i + 1, totalFrames, bmp)
                                }
                            }
                            if (frames.isNotEmpty()) return frames
                        } finally {
                            retriever.release()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autentické neuronové volání selhalo: ${e.message}", e)
                throw e
            }
        }

        throw IllegalStateException("Požadavek vyžaduje platný neuronový model. Falešné polygony a Canvas šablony byly trvale odstraněny.")
    }
}
