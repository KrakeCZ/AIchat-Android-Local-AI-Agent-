package com.example.llama.media

import android.media.MediaCodecList
import android.media.MediaFormat
import java.util.Locale

/**
 * Enterprise FFmpeg & MediaCodec Compatibility Matrix (Zero-Implicit)
 * Slouží k dynamickému profilování zařízení, definici kontejnerů a dodání FFmpeg vlajek 
 * (flags) pro bezpečný SW fallback v případě HW selhání.
 */
object ExportFormatRegistry {

    enum class VideoCodec(
        val codecIndex: Int, 
        val displayName: String, 
        val isHardwareAccelerated: Boolean, 
        val mimeType: String?,
        val ffmpegVcodec: String,
        val ffmpegPreset: String? = null
    ) {
        H264(0, "H.264 / AVC", true, MediaFormat.MIMETYPE_VIDEO_AVC, "h264_mediacodec", null),
        HEVC(1, "H.265 / HEVC", true, MediaFormat.MIMETYPE_VIDEO_HEVC, "hevc_mediacodec", null),
        VP9(2, "VP9 (Google Web Standard)", true, MediaFormat.MIMETYPE_VIDEO_VP9, "libvpx-vp9", null),
        AV1(3, "AV1 (Nejmodernější formát)", true, MediaFormat.MIMETYPE_VIDEO_AV1, "libaom-av1", null),
        MPEG4(4, "MPEG-4 (Starší zařízení)", true, MediaFormat.MIMETYPE_VIDEO_MPEG4, "mpeg4", null),
        VP8(5, "VP8 (Legacy Web Standard)", true, "video/x-vnd.on2.vp8", "libvpx", null),
        MPEG2(6, "MPEG-2 (Legacy Broadcast)", true, MediaFormat.MIMETYPE_VIDEO_MPEG2, "mpeg2video", null),
        PRORES(12, "Apple ProRes 422", false, null, "prores_ks", null),
        DNXHD(13, "Avid DNxHD", false, null, "dnxhd", null)
    }

    enum class AudioCodec(val displayName: String, val ffmpegAcodec: String, val defaultBitrate: String) {
        AAC("AAC (Pokročilé kódování zvuku)", "aac", "192k"),
        OPUS("Opus (Ultra nízká latence)", "libopus", "128k"),
        MP3("MP3 (Klasický formát)", "libmp3lame", "192k"),
        AC3("Dolby Digital AC-3", "ac3", "384k"),
        FLAC("FLAC (Bezztrátový formát)", "flac", "0"), // 0 = default pro FLAC VBR
        PCM_S16LE("PCM 16-bit Little-Endian (RAW)", "pcm_s16le", "1411k")
    }

    enum class Container(
        val containerIndex: Int,
        val extension: String,
        val displayName: String,
        val allowedVideo: List<VideoCodec>,
        val allowedAudio: List<AudioCodec>
    ) {
        MP4(1, ".mp4", "MP4 (Standardní)", 
            listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.AV1, VideoCodec.MPEG4),
            listOf(AudioCodec.AAC, AudioCodec.MP3, AudioCodec.AC3, AudioCodec.FLAC)
        ),
        MKV(0, ".mkv", "Matroska (MKV)", 
            VideoCodec.entries.toList(),
            AudioCodec.entries.toList()
        ),
        WEBM(3, ".webm", "WebM (Pro prohlížeče)", 
            listOf(VideoCodec.VP8, VideoCodec.VP9, VideoCodec.AV1),
            listOf(AudioCodec.OPUS)
        ),
        MOV(2, ".mov", "QuickTime (MOV)", 
            listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.PRORES, VideoCodec.DNXHD),
            listOf(AudioCodec.AAC, AudioCodec.PCM_S16LE)
        ),
        AVI(4, ".avi", "Audio Video Interleave (AVI)",
            listOf(VideoCodec.H264, VideoCodec.MPEG4, VideoCodec.MPEG2),
            listOf(AudioCodec.MP3, AudioCodec.AC3, AudioCodec.PCM_S16LE)
        ),
        MPEGTS(5, ".ts", "MPEG-TS (Broadcast Stream)",
            listOf(VideoCodec.H264, VideoCodec.HEVC, VideoCodec.MPEG2),
            listOf(AudioCodec.AAC, AudioCodec.AC3, AudioCodec.MP3)
        ),
        FLV(6, ".flv", "Flash Video (FLV)",
            listOf(VideoCodec.H264, VideoCodec.VP8),
            listOf(AudioCodec.AAC, AudioCodec.MP3)
        )
    }



    /**
     * Získá přesné FFmpeg CLI argumenty pro video stream.
     * @param codecIndex Index kodeku z UI
     * @param qualityLevel 0 (High), 1 (Medium), 2 (Low)
     */
    fun getFFmpegVideoArgs(codecIndex: Int, qualityLevel: Int): List<String> {
        val codec = VideoCodec.entries.find { it.codecIndex == codecIndex } ?: VideoCodec.H264
        val args = mutableListOf("-c:v", codec.ffmpegVcodec)
        
        // Pokročilé nastavení CRF (Constant Rate Factor) podle požadované kvality
        val crfValue = when (qualityLevel) {
            0 -> "18" // Visually Lossless
            2 -> "28" // Low Quality
            else -> "23" // Standard
        }
        
        if (codec.ffmpegVcodec.contains("mediacodec")) {
            val bitrate = when (qualityLevel) {
                0 -> "8M"
                2 -> "2M"
                else -> "4M"
            }
            args.add("-b:v")
            args.add(bitrate)
        } else if (codec.ffmpegVcodec == "libvpx-vp9") {
            args.add("-crf")
            args.add(crfValue)
            args.add("-b:v")
            args.add("0") // VP9 require b:v 0 pro fungování CRF
        }

        return args
    }

    /**
     * Mapovací funkce pro HW akceleraci, vrátí MIME typ pro MediaCodec.
     */
    fun getEncoderMimeFromCodecIndex(codecIndex: Int): String {
        return VideoCodec.entries.find { it.codecIndex == codecIndex }?.mimeType 
            ?: MediaFormat.MIMETYPE_VIDEO_AVC
    }

    /**
     * Generuje formátovaný seznam kodeků pro výběrové menu UI (DropDown) s využitím Device Profileru.
     */
    fun getAvailableVideoCodecsUi(context: android.content.Context, userSelectedHighway: String = "AUTO"): List<Pair<Int, String>> {
        val list = mutableListOf<Pair<Int, String>>()
        list.add(Pair(-1, "Stejný jako originál (Auto) — 1:1 klon"))
        
        val caps = DeviceProfiler.getCapabilities(context)
        
        for (codec in VideoCodec.entries) {
            val mime = codec.mimeType?.lowercase(Locale.ROOT)
            val hasAndroidEncoder = mime != null && caps.supportedHwEncoders.contains(mime)

            if (userSelectedHighway == "HW") {
                if (hasAndroidEncoder) {
                    list.add(Pair(codec.codecIndex, "${codec.displayName} (Bleskový HW Export)"))
                }
            } else if (userSelectedHighway == "SW") {
                list.add(Pair(codec.codecIndex, "${codec.displayName} (Pomalý SW Export)"))
            } else {
                val tag = if (hasAndroidEncoder) "[HW]" else "[SW FFmpeg]"
                list.add(Pair(codec.codecIndex, "${codec.displayName} $tag"))
            }
        }
        return list
    }

    fun getAvailableContainersUi(): List<Pair<Int, String>> {
        return Container.entries.map { Pair(it.containerIndex, it.displayName) }.toMutableList().apply {
            add(0, Pair(-1, "Stejný jako originál (Auto) — 1:1 klon"))
        }
    }

    /**
     * INTELIGENTNÍ LOGIKA: Vrátí pouze kontejnery, které jsou plně kompatibilní s uživatelem vybraným kodekem.
     */
    fun getCompatibleContainersForCodec(codecIndex: Int): List<Pair<Int, String>> {
        if (codecIndex == -1) return getAvailableContainersUi() // Pokud zvolil Auto, může zvolit libovolný
        val targetCodec = VideoCodec.entries.find { it.codecIndex == codecIndex } ?: return getAvailableContainersUi()
        
        return Container.entries
            .filter { container -> container.allowedVideo.contains(targetCodec) }
            .map { Pair(it.containerIndex, it.displayName) }
            .toMutableList().apply {
                add(0, Pair(-1, "Stejný jako originál (Auto) — 1:1 klon"))
            }
    }

    /**
     * INTELIGENTNÍ LOGIKA: Vrátí pouze kodeky, které jsou podporovány uživatelem zvoleným kontejnerem.
     */
    fun getCompatibleCodecsForContainer(context: android.content.Context, containerIndex: Int, userSelectedHighway: String = "AUTO"): List<Pair<Int, String>> {
        if (containerIndex == -1) return getAvailableVideoCodecsUi(context, userSelectedHighway)
        val targetContainer = Container.entries.find { it.containerIndex == containerIndex } ?: return getAvailableVideoCodecsUi(context, userSelectedHighway)
        
        val caps = DeviceProfiler.getCapabilities(context)
        
        return targetContainer.allowedVideo.mapNotNull { codec ->
            val mime = codec.mimeType?.lowercase(Locale.ROOT)
            val hasAndroidEncoder = mime != null && caps.supportedHwEncoders.contains(mime)

            if (userSelectedHighway == "HW") {
                if (hasAndroidEncoder) Pair(codec.codecIndex, "${codec.displayName} (Bleskový HW Export)") else null
            } else if (userSelectedHighway == "SW") {
                Pair(codec.codecIndex, "${codec.displayName} (Pomalý SW Export)")
            } else {
                val tag = if (hasAndroidEncoder) "[HW]" else "[SW FFmpeg]"
                Pair(codec.codecIndex, "${codec.displayName} $tag")
            }
        }.toMutableList().apply {
            add(0, Pair(-1, "Stejný jako originál (Auto) — 1:1 klon"))
        }
    }

    /**
     * Ověří, zda Android HW MediaCodec enkodér podporuje daný kodek z UI výběru.
     * Kodeky bez Android MIME typu (ProRes, DNxHD) vždy vrátí false — jdou výhradně přes FFmpeg.
     *
     * @param codecIndex Index kodeku z UI (viz VideoCodec.codecIndex)
     * @return true pokud zařízení má HW enkodér pro daný MIME typ, jinak false
     */
    fun canUseHardwareEncoder(context: android.content.Context, codecIndex: Int): Boolean {
        // codecIndex == -1 znamená "Auto/1:1 klon" — přímý remux, HW akcelerace není potřeba
        if (codecIndex == -1) return true

        val codec = VideoCodec.entries.find { it.codecIndex == codecIndex }
            ?: return false // Neznámý index → SW fallback

        // Kodeky bez Android MIME (ProRes, DNxHD) jdou vždy přes FFmpeg C++ pipeline
        val mimeType = codec.mimeType ?: return false

        val caps = DeviceProfiler.getCapabilities(context)
        return caps.supportedHwEncoders.contains(mimeType.lowercase(Locale.ROOT))
    }
}