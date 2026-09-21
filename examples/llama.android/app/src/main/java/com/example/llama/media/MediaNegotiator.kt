package com.example.llama.media

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.util.Log
import java.util.Locale

object MediaNegotiator {
    private const val TAG = "MediaNegotiator"

    data class InputMetadata(
        val videoMime: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val hasAudio: Boolean,
        val audioMime: String?
    )

    /**
     * 1. INSPEKTOR: Zjistí, co uživatel nahrál (mobil, web, staré AVI)
     */
    fun inspectInput(context: Context, uri: Uri): InputMetadata {
        val extractor = MediaExtractor()
        try {
            val pfd = if (uri.scheme?.lowercase() == "content") {
                context.contentResolver.openFileDescriptor(uri, "r")
            } else null
            
            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                extractor.setDataSource(context, uri, null)
            }

            var videoMime = MediaFormat.MIMETYPE_VIDEO_AVC
            var width = 1920
            var height = 1080
            var fps = 30
            var hasAudio = false
            var audioMime: String? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    videoMime = mime
                    width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 1920
                    height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 1080
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        fps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    audioMime = mime
                }
            }
            return InputMetadata(videoMime, width, height, fps, hasAudio, audioMime)
        } catch (e: Exception) {
            Log.e(TAG, "MediaExtractor selhal pri analyze $uri. Fallback na defaultni parametry.", e)
            return InputMetadata(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080, 30, false, null)
        } finally {
            extractor.release()
        }
    }

    /**
     * 2. VYJEDNAVAČ: Pokusí se vytvořit optimální HW konfiguraci pro cíl.
     * Pokud HW cíl nepodporuje (např. HEVC na starém telefonu), vrátí null -> Fallback na FFmpeg SW.
     */
    fun negotiateHardwareFormat(targetCodec: ExportFormatRegistry.VideoCodec, input: InputMetadata): MediaFormat? {
        val targetMime = targetCodec.mimeType ?: return null // ProRes apod. jdou rovnou do FFmpeg (SW)

        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        val format = MediaFormat.createVideoFormat(targetMime, input.width, input.height)
        
        // Zjistíme, zda má telefon HW kodér pro tento formát a rozlišení
        val codecName = codecList.findEncoderForFormat(format)
        if (codecName == null) {
            Log.w(TAG, "HW enkodér nepodporuje $targetMime v rozlišení ${input.width}x${input.height}")
            return null // Fallback na pomalý, ale jistý SW export v FFmpeg
        }

        return format.apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, calculateOptimalBitrate(input.width, input.height))
            setInteger(MediaFormat.KEY_FRAME_RATE, input.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // Keyframe každou 1 sekundu
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Zákaz B-framů (kritické pro spolehlivý Muxer v Androidu a správné DTS generování)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
            
            // Kompatibilní profily
            if (targetMime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
            } else if (targetMime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel41)
            }
        }
    }

    private fun calculateOptimalBitrate(w: Int, h: Int): Int {
        val pixels = w * h
        return when {
            pixels >= 3840 * 2160 -> 35_000_000 // 4K = 35 Mbps
            pixels >= 1920 * 1080 -> 12_000_000 // 1080p = 12 Mbps
            else -> 6_000_000                   // 720p = 6 Mbps
        }
    }
}
