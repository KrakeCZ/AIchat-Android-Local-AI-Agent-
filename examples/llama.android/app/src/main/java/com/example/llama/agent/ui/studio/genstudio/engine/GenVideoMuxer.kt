package com.example.llama.agent.ui.studio.genstudio.engine

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Vysoce optimalizovaný hardwarový H.264 / HEVC video kodér a multiplexor.
 * Zajišťuje přímé deterministické enkódování generovaných neurálních snímků do validního MP4 kontejneru.
 * Využívá přímé YUV420 / NV12 buffery s explicitními časovými značkami (PTS), což eliminuje
 * zahazování snímků na čipsetech Qualcomm Snapdragon 8 Gen 3 (c2.qti.avc.encoder).
 */
class GenVideoMuxer {

    companion object {
        private const val TAG = "GenVideoMuxer"
        private const val MIME_TYPE_AVC = MediaFormat.MIMETYPE_VIDEO_AVC // video/avc
        private const val MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC // video/hevc
        private const val TIMEOUT_USEC = 10000L
        private const val I_FRAME_INTERVAL = 1 // 1 vteřina mezi klíčovými snímky pro rychlé posouvání (seeking)
    }

    suspend fun encodeBitmapsToMp4(
        frames: List<Bitmap>,
        outputFile: File,
        fps: Int = 16,
        preferHevc: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.Default) {
        if (frames.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Seznam snímků je prázdný."))
        }

        val width = frames.first().width
        val height = frames.first().height
        val mimeType = if (preferHevc) MIME_TYPE_HEVC else MIME_TYPE_AVC

        Log.i(TAG, "🎬 Spouštím HW enkódování: ${frames.size} snímků, ${width}x$height @ $fps fps do ${outputFile.name}")

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var trackIndex = -1
        var muxerStarted = false

        try {
            // 1. Nastavení parametrů formátu videa
            val bitrate = calculateBitrate(width, height, fps)
            encoder = MediaCodec.createEncoderByType(mimeType)

            val caps = encoder.codecInfo.getCapabilitiesForType(mimeType)
            val (selectedColorFormat, isSemiPlanar) = resolveSupportedColorFormat(caps)

            val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // 2. Inicializace muxeru
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bufferInfo = MediaCodec.BufferInfo()
            val ySize = width * height
            val yuvSize = ySize * 3 / 2
            val argbBuffer = IntArray(ySize)
            val yuvBuffer = ByteArray(yuvSize)

            // 3. Cyklus enkódování jednotlivých snímků
            for ((index, bitmap) in frames.withIndex()) {
                val ptsUs = (index * 1_000_000L) / fps
                val isLast = (index == frames.lastIndex)

                convertBitmapToYuv(bitmap, width, height, isSemiPlanar, argbBuffer, yuvBuffer)

                var inputIndex = -1
                var attempts = 0
                while (inputIndex < 0 && attempts < 50) {
                    inputIndex = encoder.dequeueInputBuffer(TIMEOUT_USEC)
                    if (inputIndex < 0) {
                        trackIndex = drainEncoder(encoder, muxer, bufferInfo, false, trackIndex) { newTrack ->
                            trackIndex = newTrack
                            muxerStarted = true
                        }
                        attempts++
                    }
                }

                if (inputIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Input buffer $inputIndex was null")
                    inputBuffer.clear()
                    inputBuffer.put(yuvBuffer, 0, yuvSize)
                    val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    encoder.queueInputBuffer(inputIndex, 0, yuvSize, ptsUs, flags)
                }

                trackIndex = drainEncoder(encoder, muxer, bufferInfo, false, trackIndex) { newTrack ->
                    trackIndex = newTrack
                    muxerStarted = true
                }

                onProgress?.invoke((index + 1).toFloat() / frames.size.toFloat())
            }

            // 4. Vypustíme zbývající data z kodeku až do konce streamu
            trackIndex = drainEncoder(encoder, muxer, bufferInfo, true, trackIndex) { newTrack ->
                trackIndex = newTrack
                muxerStarted = true
            }

            if (trackIndex < 0 || !muxerStarted) {
                throw IllegalStateException("Během enkódování nebyla vytvořena nebo spuštěna žádná platná video stopa (trackIndex: $trackIndex).")
            }

            Log.i(TAG, "✅ Enkódování MP4 dokončeno úspěšně: ${outputFile.length() / 1024} KB (${outputFile.length()} bytes)")
            return@withContext Result.success(outputFile)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba během enkódování videa: ${e.message}", e)
            return@withContext Result.failure(e)
        } finally {
            try {
                encoder?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "encoder stop: ${e.message}")
            }
            try {
                encoder?.release()
            } catch (e: Exception) {
                Log.w(TAG, "encoder release: ${e.message}")
            }
            try {
                if (muxerStarted) {
                    muxer?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "muxer stop: ${e.message}")
            }
            try {
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "muxer release: ${e.message}")
            }
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        endOfStream: Boolean,
        currentTrackIndex: Int,
        onMuxerStart: (Int) -> Unit
    ): Int {
        var trackIndex = currentTrackIndex
        var emptyCount = 0

        while (true) {
            val encoderStatus = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) {
                    break
                } else {
                    emptyCount++
                    if (emptyCount >= 40) {
                        Log.i(TAG, "EOS draining complete (timeout reached).")
                        break
                    }
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (trackIndex < 0) {
                    val newFormat = encoder.outputFormat
                    trackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    onMuxerStart(trackIndex)
                    Log.i(TAG, "Muxer started with track $trackIndex and format $newFormat")
                }
            } else if (encoderStatus >= 0) {
                emptyCount = 0
                val encodedData: ByteBuffer = encoder.getOutputBuffer(encoderStatus)
                    ?: throw RuntimeException("EncoderBuffer $encoderStatus byl null")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    bufferInfo.size = 0
                }

                if (bufferInfo.size != 0 && trackIndex >= 0) {
                    encodedData.position(bufferInfo.offset)
                    encodedData.limit(bufferInfo.offset + bufferInfo.size)
                    muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                }

                val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                encoder.releaseOutputBuffer(encoderStatus, false)

                if (isEos) {
                    Log.i(TAG, "Dosažen konec streamu (END_OF_STREAM).")
                    break
                }
            }
        }
        return trackIndex
    }

    private fun convertBitmapToYuv(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        isSemiPlanar: Boolean,
        argbBuffer: IntArray,
        yuvBuffer: ByteArray
    ) {
        bitmap.getPixels(argbBuffer, 0, width, 0, 0, width, height)
        val ySize = width * height
        val uSize = ySize / 4
        var uvIndex = ySize
        var uIndex = ySize
        var vIndex = ySize + uSize

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val rgb = argbBuffer[rowOffset + x]
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF

                // Standard BT.601 integer conversion
                val yVal = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yuvBuffer[rowOffset + x] = yVal.coerceIn(0, 255).toByte()

                if ((y and 1) == 0 && (x and 1) == 0) {
                    val uVal = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val vVal = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

                    if (isSemiPlanar) {
                        yuvBuffer[uvIndex++] = uVal.coerceIn(0, 255).toByte()
                        yuvBuffer[uvIndex++] = vVal.coerceIn(0, 255).toByte()
                    } else {
                        yuvBuffer[uIndex++] = uVal.coerceIn(0, 255).toByte()
                        yuvBuffer[vIndex++] = vVal.coerceIn(0, 255).toByte()
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveSupportedColorFormat(caps: MediaCodecInfo.CodecCapabilities): Pair<Int, Boolean> {
        val supportedFormats = caps.colorFormats
        return when {
            supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar, true)
            supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar, false)
            else ->
                Pair(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, true)
        }
    }

    private fun calculateBitrate(width: Int, height: Int, fps: Int): Int {
        val totalPixels = width * height
        val baseBitrate = when {
            totalPixels <= 384 * 256 -> 1_500_000  // 1.5 Mbps @ baseline
            totalPixels <= 640 * 360 -> 3_500_000  // 3.5 Mbps @ baseline
            totalPixels <= 1280 * 720 -> 6_000_000 // 6.0 Mbps @ baseline
            else -> 10_000_000                     // 10.0 Mbps @ baseline
        }
        val clampedFps = fps.coerceIn(1, 120)
        // Škálování podle reálné snímkové frekvence (baseline 16 fps pro model, proporcionálně pro 30/60 fps)
        return ((baseBitrate.toLong() * clampedFps) / 16).toInt().coerceIn(500_000, 50_000_000)
    }

    private fun convertExtractorFlagsToCodecFlags(extractorFlags: Int): Int {
        var codecFlags = 0
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if ((extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    /**
     * Sloučí vytvořené bezhlučné MP4 video se zvukovou stopou (MP3/AAC/M4A/WAV)
     * do nového synchronizovaného MP4 souboru.
     */
    suspend fun muxVideoWithAudio(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            var videoTrackIndexInExtractor = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndexInExtractor = i
                    videoFormat = format
                    break
                }
            }

            var audioTrackIndexInExtractor = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndexInExtractor = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndexInExtractor == -1 || videoFormat == null) {
                return@withContext Result.failure(IllegalStateException("Ve zdrojovém souboru nebyla nalezena video stopa."))
            }

            if (audioTrackIndexInExtractor == -1 || audioFormat == null) {
                return@withContext Result.failure(IllegalStateException("Ve vybraném souboru nebyla nalezena kompatibilní audio stopa."))
            }

            videoExtractor.selectTrack(videoTrackIndexInExtractor)
            audioExtractor.selectTrack(audioTrackIndexInExtractor)

            val videoDurationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                Long.MAX_VALUE
            }

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val maxBufferSize = 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 1. Zkopírovat vzorky videa
            while (true) {
                bufferInfo.size = videoExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                bufferInfo.flags = convertExtractorFlagsToCodecFlags(videoExtractor.sampleFlags)
                muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                videoExtractor.advance()
            }

            // 2. Zkopírovat vzorky audia
            while (true) {
                bufferInfo.size = audioExtractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                val sampleTime = audioExtractor.sampleTime
                if (sampleTime > videoDurationUs) break
                bufferInfo.presentationTimeUs = sampleTime
                bufferInfo.flags = convertExtractorFlagsToCodecFlags(audioExtractor.sampleFlags)
                muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                audioExtractor.advance()
            }

            Log.i(TAG, "✅ Úspěšně smuxováno video a audio do: ${outputFile.name}")
            Result.success(outputFile)
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při slučování videa a audia: ${e.message}", e)
            Result.failure(e)
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Chyba při zastavení muxeru: ${e.message}")
            }
            videoExtractor?.release()
            audioExtractor?.release()
        }
    }
}
