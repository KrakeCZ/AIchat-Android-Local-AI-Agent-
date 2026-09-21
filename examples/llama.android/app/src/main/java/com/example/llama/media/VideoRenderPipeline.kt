package com.example.llama.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES32
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import com.example.llama.srt.export.GpuSubtitleProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Enterprise Video Render Pipeline (Next-Gen)
 * Zodpovídá za Hardwarově akcelerovanou cestu zpracování videa pomocí MediaCodec API.
 * Spojuje C++ NDK vrstvu (Muxer/Audio) a OpenGL ES pro subtitle compositing.
 */
class VideoRenderPipeline(
    private val nativeEngine: NativeVideoEngine,
    private val gpuProvider: GpuSubtitleProvider
) {
    companion object {
        private const val TAG = "VideoRenderPipeline"
        private const val TIMEOUT_US = 10000L

        // Zabráníme OOM u obřích 4K videí tvrdým omezením maximálního bitrate pro daný profil
        private const val MAX_ALLOWED_BITRATE = 100_000_000 // 100 Mbps
    }

    suspend fun renderVideoTrack(
        context: Context,
        videoUri: Uri,
        videoPathForFFmpeg: String,
        outPath: String,
        codecIndex: Int,
        qualityLevel: Int,
        audioStreamIndex: Int,
        volumeGain: Float,
        compressor: Boolean,
        ebuR128: Boolean,
        onProgress: (Float) -> Unit,
        renderCallback: (Canvas, Long) -> Unit
    ) {
        Log.i(TAG, "🚀 START PIPELINE: HW Akcelerace. Zdroj: $videoUri, Výstup: $outPath")

        // EGL kontext musí striktně zůstat na jediném dedikovaném OpenGL vlákně.
        val glExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "EGL_Render_Thread_${System.currentTimeMillis()}")
        }
        val glDispatcher = glExecutor.asCoroutineDispatcher()
        var persistentAudioPfd: ParcelFileDescriptor? = null

        try {
            withContext(glDispatcher) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, videoUri, null)
                } catch (e: Exception) {
                    throw RuntimeException("Chyba čtení datového zdroje v MediaExtractor: ${e.message}", e)
                }

                val trackIdx = (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                } ?: throw RuntimeException("Kritická chyba: Video track nenalezen ve vstupním souboru.")

                extractor.selectTrack(trackIdx)
                val format = extractor.getTrackFormat(trackIdx)

                val decoderMimeType = format.getString(MediaFormat.KEY_MIME)
                    ?: throw RuntimeException("Kritická chyba: Nelze identifikovat dekódovací MIME typ.")

                val encoderMimeType = ExportFormatRegistry.getEncoderMimeFromCodecIndex(codecIndex)
                Log.i(TAG, "MIME Třídění -> Dekodér: $decoderMimeType, Enkodér: $encoderMimeType")

                // Extrakce dimenzí s ochranou (některé MP4 hlásí špatné rozměry)
                val rawWidth = format.getIntegerOrNull(MediaFormat.KEY_WIDTH) ?: 1920
                val rawHeight = format.getIntegerOrNull(MediaFormat.KEY_HEIGHT) ?: 1080

                // 🚀 KRITICKÉ: Detekce rotace pro mobilní vertikální videa (9:16 atd.)
                val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    format.getInteger(MediaFormat.KEY_ROTATION)
                } else {
                    0
                }

                val isPortrait = rotation == 90 || rotation == 270
                val finalWidth = if (isPortrait) rawHeight else rawWidth
                val finalHeight = if (isPortrait) rawWidth else rawHeight

                // Hardware Encoders (H.264/H.265) vyžadují zarovnání na makrobloky (16x16 pixelů). 
                // Bez toho mohou enkodéry (např. na starších Exynos nebo Mediatek) padat nebo dělat zelené pruhy.
                val safeWidth = finalWidth - (finalWidth % 16)
                val safeHeight = finalHeight - (finalHeight % 16)

                // 🚀 PŘESNÁ DETEKCE FPS (včetně NTSC 29.97, 23.976, 59.94)
                // MediaFormat může vracet Integer, ale u moderních videí i Float.
                val exactFps: Double = try {
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble()
                        } catch (e: Exception) {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble()
                        }
                    } else 30.0
                } catch (e: Exception) {
                    30.0
                }.takeIf { it > 0.0 } ?: 30.0

                // Pro konfiguraci hardwarového MediaCodec enkodéru potřebujeme celočíselný Int
                val fpsInt = Math.round(exactFps).toInt()

                val durationUs = format.getLongOrNull(MediaFormat.KEY_DURATION) ?: -1L

                // 🌟 ENTERPRISE BITRATE & PROFILE MANAGEMENT 🌟
                val baseBitrate = (safeWidth * safeHeight * fpsInt * 0.1).toInt().coerceAtMost(MAX_ALLOWED_BITRATE)
                val targetBitrate = when (qualityLevel) {
                    0 -> (baseBitrate * 1.5).toInt() // Vysoká kvalita
                    2 -> (baseBitrate * 0.7).toInt() // Úsporná
                    else -> baseBitrate // Standardní
                }.coerceAtMost(MAX_ALLOWED_BITRATE)

                val encFormat = MediaFormat.createVideoFormat(encoderMimeType, safeWidth, safeHeight).apply {
                    setInteger(MediaFormat.KEY_BIT_RATE, targetBitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fpsInt)
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 sekunda GOP pro hladký seek v přehrávačích

                    // Pokročilá HW konfigurace pro API 29+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // Maximalizace kvality pomocí VBR (Variable Bitrate)
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        // 🚀 KRITICKÁ OPRAVA ERROR -22: FFmpeg Muxer netoleruje B-snímky bez DTS!
                        // Zákaz B-framů aplikován plošně pro stabilitu levnějších zařízení
                        setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                    }

                    // HEVC Profiling (Main Profile)
                    if (encoderMimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41) // 1080p@60fps base
                    } else if (encoderMimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        // AVC/H.264 Profiling (High Profile pro nejlepší kvalitu a kompatibilitu)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                    }
                }

                // Bezpečná detekce a validace enkodéru pro dané nastavení
                validateEncoderCapabilities(encoderMimeType, encFormat)

                // 2. Audio Track Selector (Podpora více stop v MKV/MP4)
                var audioTrackIdx = -1
                var audioStreamCounter = 0
                for (i in 0 until extractor.trackCount) {
                    val trackFormat = extractor.getTrackFormat(i)
                    val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        if (audioStreamCounter == audioStreamIndex) {
                            audioTrackIdx = i
                            break
                        }
                        audioStreamCounter++
                    }
                }

                extractor.selectTrack(trackIdx)
                if (audioTrackIdx >= 0) extractor.selectTrack(audioTrackIdx)

                // Perzistentní File Descriptor pro FFmpeg C++ worker
                persistentAudioPfd = try {
                    if (videoUri.scheme?.lowercase() == "content") {
                        context.contentResolver.openFileDescriptor(videoUri, "r")
                    } else null
                } catch (e: Exception) {
                    Log.w(TAG, "Nelze získat fd pro FFmpeg audio worker. Fallback na raw cestu.")
                    null
                }

                val safeAudioPath = if (persistentAudioPfd != null) "/proc/self/fd/${persistentAudioPfd.fd}" else videoPathForFFmpeg

                // Zdroje
                var decoder: MediaCodec? = null
                var encoder: MediaCodec? = null
                var eglCore: EglCore? = null
                var surfaceTexture: SurfaceTexture? = null
                var decSurface: Surface? = null
                var muxerPtr = 0L
                var audioJob: Job? = null

                try {
                    Log.d(TAG, "Inicializace HW Enkodéru: $encoderMimeType")
                    encoder = MediaCodec.createEncoderByType(encoderMimeType)
                    encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    val inputSurface = encoder.createInputSurface()
                    encoder.start()

                    Log.d(TAG, "Inicializace EGL a OpenGL textur")
                    eglCore = EglCore(inputSurface)
                    eglCore.makeCurrent()
                    val textureId = eglCore.createOesTexture()
                    surfaceTexture = SurfaceTexture(textureId)
                    decSurface = Surface(surfaceTexture)

                    val frameLock = java.lang.Object()
                    var frameAvailable = false
                    surfaceTexture.setOnFrameAvailableListener {
                        synchronized(frameLock) {
                            frameAvailable = true
                            frameLock.notifyAll()
                        }
                    }

                    Log.d(TAG, "Inicializace HW Dekodéru: $decoderMimeType")
                    decoder = MediaCodec.createDecoderByType(decoderMimeType)

                    // Pokročilý Tone Mapping pro HDR zdroje (API 33+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val colorTransfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else -1
                        if (colorTransfer == MediaFormat.COLOR_TRANSFER_HLG || colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084) {
                            Log.i(TAG, "Detekováno HDR video. Zapínám HW Tone Mapping do SDR (COLOR_TRANSFER_SDR_VIDEO).")
                            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                        }
                    }
                    decoder.configure(format, decSurface, null, 0)
                    decoder.start()

                    Log.d(TAG, "Inicializace Native Muxeru (C++)")
                    muxerPtr = nativeEngine.nativeInitMuxer(outPath, safeWidth, safeHeight, fpsInt, codecIndex)
                    if (muxerPtr == 0L) throw RuntimeException("Selhala inicializace C++ Muxeru (OOM nebo chybný formát?)")

                    // Asynchronní Audio Worker v JNI přes Dispatchers.IO
                    // SupervisorJob: audioJob NESMÍ být zrušen při výjimce v render smyčce —
                    // jinak muxer dostane nativeCloseMuxer() bez dokončeného audia → video bez zvuku.
                    val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    audioJob = audioScope.launch {
                        try {
                            nativeEngine.nativeProcessAudioToMuxer(muxerPtr, safeAudioPath, audioStreamIndex, volumeGain, compressor, ebuR128)
                        } catch (e: Exception) {
                            Log.e(TAG, "Audio C++ Worker selhal!", e)
                        }
                    }

                    // ====== HLAVNÍ RENDER LOOP (Zero Allocation) ======
                    val bufferInfo = MediaCodec.BufferInfo()
                    var isExtractorEof = false
                    var isDecoderEof = false
                    var isEncoderEof = false
                    var firstVideoPtsUs = -1L
                    var lastVideoPtsUs = -1L
                    var framesProcessed = 0
                    
                    val targetFps = exactFps
                    // Plovoucí desetinná čárka pro absolutní přesnost zabraňující driftu!
                    val frameDurationUsDouble = 1_000_000.0 / targetFps
                    var accumulatorUs = 0.0
                    
                    val transformMatrix = FloatArray(16)

                    while (!isEncoderEof) {
                        // 1. Krmení dekodéru videem
                        if (!isExtractorEof) {
                            val currentTrack = extractor.sampleTrackIndex
                            if (currentTrack == trackIdx) {
                                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                                if (inIndex >= 0) {
                                    val buffer = decoder.getInputBuffer(inIndex)
                                    if (buffer != null) {
                                        val sampleSize = extractor.readSampleData(buffer, 0)
                                        if (sampleSize < 0) {
                                            Log.d(TAG, "Extractor: Dosažen konec streamu (EOF)")
                                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            isExtractorEof = true
                                        } else {
                                            decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                            extractor.advance()
                                        }
                                    }
                                }
                            } else if (currentTrack < 0) {
                                val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                                if (inIndex >= 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    isExtractorEof = true
                                }
                            } else {
                                extractor.advance()
                            }
                        }

                        // 2. Čtení obrazu z dekodéru & Aplikace titulků & Odeslání do enkodéru
                        if (!isDecoderEof) {
                            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                            if (outIndex >= 0) {
                                val doRender = bufferInfo.size != 0 && bufferInfo.presentationTimeUs >= 0
                                decoder.releaseOutputBuffer(outIndex, doRender)

                                if (doRender) {
                                    eglCore.makeCurrent()
                                    // Spurious wakeup ochrana
                                    synchronized(frameLock) {
                                        val deadline = System.currentTimeMillis() + 150L
                                        while (!frameAvailable) {
                                            val remaining = deadline - System.currentTimeMillis()
                                            if (remaining <= 0L) {
                                                Log.w(TAG, "SurfaceTexture Timeout! Frame zahozen.")
                                                break
                                            }
                                            frameLock.wait(remaining)
                                        }
                                        frameAvailable = false
                                    }

                                    // Vynucený GL Unbind/Rebind chrání paměť mezi C++ a Javou
                                    GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
                                    GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
                                    var glErr = GLES32.glGetError()
                                    while (glErr != GLES32.GL_NO_ERROR) {
                                        Log.w(TAG, "GL Error flush: 0x${glErr.toString(16)}")
                                        glErr = GLES32.glGetError()
                                    }

                                    try { surfaceTexture.updateTexImage() } catch (e: Exception) { Log.e(TAG, "updateTexImage failed", e) }
                                    surfaceTexture.getTransformMatrix(transformMatrix)

                                    // 🚀 ABSOLUTNÍ A/V SYNCHRONIZACE (VFR OCHRANA)
                                    // Namísto slepého CFR metronomu (který by při drobném výpadku snímku rozjel zvuk a obraz)
                                    // použijeme reálný čas z MediaExtractor, normalizovaný k nule.
                                    if (firstVideoPtsUs < 0) {
                                        firstVideoPtsUs = bufferInfo.presentationTimeUs
                                        lastVideoPtsUs = 0L
                                    }
                                    
                                    var currentPtsUs = bufferInfo.presentationTimeUs - firstVideoPtsUs

                                    // Ochrana proti chybným MP4 souborům, kde jde čas dozadu (zabránění pádu MediaCodec)
                                    if (currentPtsUs <= lastVideoPtsUs) {
                                        currentPtsUs = lastVideoPtsUs + 1000L // 1 ms posun vpřed
                                    }
                                    lastVideoPtsUs = currentPtsUs
                                    
                                    val currentPtsMs = currentPtsUs / 1000L

                                    // 1. Získáme vykreslenou Bitmapu pro přesný čas z dekodéru
                                    val subtitleBitmap = gpuProvider.renderFrame(currentPtsMs) { canvas ->
                                        renderCallback(canvas, currentPtsMs)
                                    }

                                    // 2. Předáme Bitmapu do C++ k prolnutí s videem
                                    eglCore.makeCurrent()
                                    nativeEngine.nativeCompositeFrame(subtitleBitmap, textureId, transformMatrix)
                                    GLES32.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

                                    // 3. Odeslání snímku do HW enkodéru se skutečným VFR časem
                                    eglCore.setPresentationTime(currentPtsUs * 1000L) // egl očekává nanosekundy
                                    eglCore.swapBuffers()
                                    
                                    framesProcessed++
                                    if (framesProcessed % 30 == 0) {
                                        Log.d(TAG, "Pipeline: Zpracováno $framesProcessed snímků (Video PTS: $currentPtsMs ms)")
                                    }
                                    if (framesProcessed % 10 == 0 && durationUs > 0) {
                                        onProgress(currentPtsUs.toFloat() / durationUs.toFloat())
                                    }
                                }

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    Log.i(TAG, "Decoder: Přijat příznak END_OF_STREAM. Odesílám signál do enkodéru.")
                                    encoder.signalEndOfInputStream()
                                    isDecoderEof = true
                                }
                            }
                        }

                        // 3. Stahování komprimovaných dat a C++ Muxing
                        val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                        if (encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val newFormat = encoder.outputFormat
                            Log.i(TAG, "Encoder: Změna formátu na výstupu: $newFormat")
                            val sps = newFormat.getByteBuffer("csd-0")
                            val pps = newFormat.getByteBuffer("csd-1")

                            if (sps != null) {
                                val configSize = sps.remaining() + (pps?.remaining() ?: 0)
                                val configBuffer = java.nio.ByteBuffer.allocateDirect(configSize)
                                configBuffer.put(sps)
                                if (pps != null) configBuffer.put(pps.duplicate())
                                configBuffer.position(0)

                                nativeEngine.nativeWritePacket(
                                    muxerPtr, configBuffer, 0, configSize, 0L,
                                    isKeyFrame = true, isConfig = true
                                )
                            }
                        } else if (encOutIndex >= 0) {
                            val encodedData = encoder.getOutputBuffer(encOutIndex)
                            if (encodedData != null && bufferInfo.size > 0) {
                                // Enkodér nám nyní vrací striktně lineární PTS nastavené naším metronomem.
                                val pts = bufferInfo.presentationTimeUs
                                nativeEngine.nativeWritePacket(
                                    muxerPtr, encodedData, bufferInfo.offset, bufferInfo.size, pts,
                                    isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0,
                                    isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                )
                            }
                            encoder.releaseOutputBuffer(encOutIndex, false)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                Log.i(TAG, "Encoder: Přijat příznak END_OF_STREAM z enkodéru.")
                                isEncoderEof = true
                                if (muxerPtr != 0L) nativeEngine.nativeSignalVideoEof(muxerPtr)
                            }
                        }
                    }
                    Log.i(TAG, "🎉 Pipeline dokončena! Zpracováno $framesProcessed snímků.")

                } catch (e: Exception) {
                    Log.e(TAG, "❌ FATAL: HW Pipeline zhavarovala během renderu", e)
                    throw e // Propagace do orchestrátoru pro fallback
                } finally {
                    Log.d(TAG, "🧹 Úklid HW a JNI prostředků")
                    withContext(NonCancellable) {
                        try { eglCore?.makeCurrent() } catch (e: Exception) {}
                        NativeVideoEngine.nativeReleaseCompositor()
                        try { decoder?.stop(); decoder?.release() } catch (e: Exception) {}
                        try { encoder?.stop(); encoder?.release() } catch (e: Exception) {}
                        try { eglCore?.release() } catch (e: Exception) {}
                        try { surfaceTexture?.release() } catch (e: Exception) {}
                        try { decSurface?.release() } catch (e: Exception) {}
                        extractor.release()

                        if (muxerPtr != 0L) {
                            nativeEngine.nativeSignalVideoEof(muxerPtr)
                            audioJob?.join()
                            nativeEngine.nativeCloseMuxer(muxerPtr)
                            Log.d(TAG, "C++ Muxer bezpečně zničen.")
                        }
                    }
                }
            }
        } finally {
            try { persistentAudioPfd?.close() } catch (e: Exception) {}
            glDispatcher.close()
            glExecutor.shutdown()
        }
    }

    /**
     * Zabrání pádu `MediaCodec.createEncoderByType`, pokud zařízení nemá profil (např. HEVC Main10).
     */
    private fun validateEncoderCapabilities(mimeType: String, format: MediaFormat) {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val codecInfo = codecList.codecInfos.find { it.isEncoder && it.supportedTypes.contains(mimeType) }
            ?: throw RuntimeException("HW Enkodér pro $mimeType není v tomto zařízení vůbec dostupný!")

        val caps = codecInfo.getCapabilitiesForType(mimeType)
        if (!caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)) {
            Log.w(TAG, "Enkodér $mimeType nativně nepodporuje COLOR_FormatSurface. Očekávejte grafické artefakty.")
        }
    }

    // Extension pro bezpečné čtení MediaFormat (brání NullPointerException z API 28-)
    private fun MediaFormat.getIntegerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null
    private fun MediaFormat.getLongOrNull(key: String): Long? = if (containsKey(key)) getLong(key) else null
}
