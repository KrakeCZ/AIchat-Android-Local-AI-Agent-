package com.example.llama.media

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HW Akcelerovaný enkodér pro tvorbu plynulých Time-Lapse videí z izolovaných snímků.
 * Nevyužívá RAM pro uchovávání historie - vše zapisuje přímo na disk.
 * Přepisuje PTS (Presentation Time Stamp) pro fixní framerate.
 */
class TimeLapseEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val outputFile: String,
    private val isHighQuality: Boolean = true
) {
    companion object {
        private const val TAG = "TimeLapseEncoder"
    }

    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    
    private val isRecording = AtomicBoolean(true)
    private var frameCount = 0
    private var syntheticPtsUs = 0L
    private val frameDurationUs = 1000000L / fps

    private val bufferInfo = MediaCodec.BufferInfo()

    fun getFrameCount(): Int = frameCount

    fun start() {
        Log.i(TAG, "Startuji TimeLapseEncoder: ${width}x${height} @ ${fps}fps -> $outputFile")
        
        // H.264 vyžaduje zarovnání na sudá čísla (ideálně na 16)
        val safeWidth = width - (width % 2)
        val safeHeight = height - (height % 2)

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeWidth, safeHeight)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        
        // Dynamický bitrate podle rozlišení
        val qualityMultiplier = if (isHighQuality) 0.60f else 0.15f
        val bitRate = (safeWidth * safeHeight * fps * qualityMultiplier).toInt()
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder!!.createInputSurface()
        encoder!!.start()

        muxer = MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    suspend fun addFrame(bitmap: Bitmap) = withContext(Dispatchers.IO) {
        if (!isRecording.get()) return@withContext
        
        try {
            // Zamkneme plátno a nakreslíme bitmapu. 
            // Tento proces je na CPU bleskový, o YUV konverzi a kompresi se postará GPU na pozadí.
            val canvas: Canvas? = inputSurface?.lockCanvas(null)
            if (canvas != null) {
                // Přizpůsobíme velikost, abychom zrušili 1:1 ořez a dostali 9:16 poměr stran
                if (bitmap.width == canvas.width && bitmap.height == canvas.height) {
                    canvas.drawBitmap(bitmap, 0f, 0f, null)
                } else {
                    val srcRect = android.graphics.Rect(0, 0, bitmap.width, bitmap.height)
                    val dstRect = android.graphics.Rect(0, 0, canvas.width, canvas.height)
                    canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                }
                inputSurface?.unlockCanvasAndPost(canvas)
                drainEncoder(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při kreslení snímku na Surface", e)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        if (!isRecording.compareAndSet(true, false)) return@withContext
        Log.i(TAG, "Zastavuji TimeLapseEncoder. Uloženo snímků: $frameCount")
        try {
            // Oznámíme enkodéru, že už žádná další data nepřijdou
            encoder?.signalEndOfInputStream()
            drainEncoder(true)
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při vyprazdňování enkodéru", e)
        } finally {
            try {
                encoder?.stop()
                encoder?.release()
            } catch (e: Exception) {}
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (e: Exception) {}
            inputSurface?.release()
            
            encoder = null
            muxer = null
            inputSurface = null
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val enc = encoder ?: return
        
        while (true) {
            val encoderStatus = enc.dequeueOutputBuffer(bufferInfo, 10000)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break // Žádná další data zatím nejsou připravena
                } else {
                    Log.d(TAG, "Čekám na dokončení enkodéru (EOS)...")
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (muxerStarted) {
                    throw RuntimeException("Formát byl změněn podruhé!")
                }
                val newFormat = enc.outputFormat
                trackIndex = muxer!!.addTrack(newFormat)
                muxer!!.start()
                muxerStarted = true
            } else if (encoderStatus < 0) {
                Log.w(TAG, "Neočekávaný stav enkodéru: $encoderStatus")
            } else {
                val encodedData = enc.getOutputBuffer(encoderStatus)
                    ?: throw RuntimeException("encoderOutputBuffer byl null")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0 // Konfigurační data ignorujeme při zápisu snímků
                }

                if (bufferInfo.size != 0) {
                    if (!muxerStarted) {
                        throw RuntimeException("Muxer nebyl spuštěn, ale přicházejí data!")
                    }

                    // 🚀 ZÁSADNÍ TRIK PRO TIME-LAPSE:
                    // Přepíšeme PTS na náš syntetický časový údaj. Tím video uděláme dokonale
                    // plynulé (např. přesně 10 FPS), i když skutečné snímání mohlo trvat hodinu.
                    bufferInfo.presentationTimeUs = syntheticPtsUs
                    syntheticPtsUs += frameDurationUs
                    
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)

                    muxer!!.writeSampleData(trackIndex, encodedData, bufferInfo)
                    frameCount++
                }

                enc.releaseOutputBuffer(encoderStatus, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "Konec streamu dosažen (End Of Stream)")
                    break
                }
            }
        }
    }
}
