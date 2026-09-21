package com.example.llama.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log

/**
 * Zpráva o analýze videa při importu.
 */
data class FormatReport(
    val fps: Float,
    val hasVideoTrack: Boolean,
    val hasAudioTrack: Boolean,
    val videoMimeType: String?,
    val audioMimeType: String?,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val isSupportedByExoPlayer: Boolean,
    val warnings: List<String>
)

/**
 * Třída pro rychlou detekci formátů (video kodek, audio kodek, kontejner).
 * Slouží k varování uživatele při importu do SrtVisualEditorActivity.
 */
object MediaFormatAnalyzer {
    private const val TAG = "MediaFormatAnalyzer"

    // ExoPlayer na Androidu (zvl. v MediaCodec mode) obvykle dobře podporuje tyto video kodeky:
    private val SUPPORTED_VIDEO_MIMES = setOf(
        MediaFormat.MIMETYPE_VIDEO_AVC,  // H.264
        MediaFormat.MIMETYPE_VIDEO_HEVC, // H.265
        MediaFormat.MIMETYPE_VIDEO_VP8,
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaFormat.MIMETYPE_VIDEO_AV1
    )

    // A tyto audio kodeky:
    private val SUPPORTED_AUDIO_MIMES = setOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_OPUS,
        MediaFormat.MIMETYPE_AUDIO_VORBIS,
        MediaFormat.MIMETYPE_AUDIO_MPEG, // MP3
        MediaFormat.MIMETYPE_AUDIO_FLAC
    )

    /**
     * Rychlá analýza formátu přes MediaExtractor a MediaMetadataRetriever.
     */
    fun analyzeFormat(context: Context, uri: Uri): FormatReport {
        var hasVideo = false
        var hasAudio = false
        var vMime: String? = null
        var aMime: String? = null
        var durMs = 0L
        var w = 0
        var h = 0
        var rot = 0
        var fps = 30.0f
        val warnings = mutableListOf<String>()

        // 1. Zjistíme základní metadata z Retrievru (hlavně kvůli rotaci a délce, a hrubé detekci)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "MediaMetadataRetriever selhal na: $uri", e)
            warnings.add("Nelze načíst hlavičku souboru.")
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }

        // 2. Extrahujeme MimeType pomocí MediaExtractoru
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/")) {
                    hasVideo = true
                    vMime = mime
                    
                    fps = try {
                        format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                    } catch (e: Exception) {
                        try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (e2: Exception) {
                            Log.w(TAG, "FPS nenalezeno, fallback na 30 FPS.")
                            30.0f
                        }
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (aMime == null) aMime = mime // Vezmeme první audio
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor selhal na: $uri", e)
            warnings.add("Nepodporovaný kontejner (např. nestandardní MKV nebo AVI).")
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }

        // 3. Vyhodnocení kompatibility
        var supported = true

        if (!hasVideo) {
            warnings.add("Soubor neobsahuje video stopu (nebo ji nelze detekovat).")
            supported = false
        } else if (vMime !in SUPPORTED_VIDEO_MIMES) {
            warnings.add("Nepodporovaný video kodek: $vMime. Může se zasekávat.")
            supported = false
        }

        if (hasAudio && aMime !in SUPPORTED_AUDIO_MIMES) {
            warnings.add("Nepodporovaný audio kodek: $aMime. Zvuk nemusí hrát.")
            supported = false
        }

        return FormatReport(
            fps = fps,
            hasVideoTrack = hasVideo,
            hasAudioTrack = hasAudio,
            videoMimeType = vMime,
            audioMimeType = aMime,
            durationMs = durMs,
            width = w,
            height = h,
            rotation = rot,
            isSupportedByExoPlayer = supported,
            warnings = warnings
        )
    }
}
