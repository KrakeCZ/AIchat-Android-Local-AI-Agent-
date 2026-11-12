package com.example.llama.media

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File

// --------------------------------------------------------------------------
// Výstupní formáty podporované nativním enginem
// --------------------------------------------------------------------------
enum class OutputFormat {
    MP4_H264,
    MKV_H265
}

// --------------------------------------------------------------------------
// Výsledkové typy nativního render pipeline (Sealed = exhaustivní when)
// --------------------------------------------------------------------------
sealed class RenderResult {
    data class Success(val outputFile: File) : RenderResult()
    data class Error(val message: String, val code: Int) : RenderResult()
    object Cancelled : RenderResult()
}

// --------------------------------------------------------------------------
// Progress callback interface pro JNI → Kotlin propagaci
// --------------------------------------------------------------------------
interface RenderProgressListener {
    fun onProgress(percent: Float)
}

fun interface SubtitleFrameProvider {
    /**
     * WYSIWYG Pixel-Perfect Pipeline Interface.
     *
     * C++ FFmpeg engine volá tuto metodu pro každý video snímek.
     * Kotlin vykreslí přesně stejnou grafiku jako v UI editoru do předané [bitmap].
     *
     * @param positionMs Časová pozice snímku v milisekundách (absolutní čas videa)
     * @param bitmap     Průhledná ARGB_8888 bitmapa, do které se kreslit. Volač ji zajistí prázdnou.
     */
    fun renderFrame(positionMs: Long, bitmap: Bitmap)
}

/**
 * JNI Bridge pro C++ FFmpeg/libass pipeline (libmedia_engine.so).
 *
 * Zodpovědnost:
 * 1. Načte nativní knihovnu (System.loadLibrary) s ošetřením UnsatisfiedLinkError.
 * 2. Poskytuje suspend-kompatibilní API pro Kotlin coroutiny.
 * 3. Mapuje content:// URI na /proc/self/fd/<fd> přes ParcelFileDescriptor.
 * 4. Propaguje výsledkový kód nativní vrstvy na typovaný RenderResult.
 *
 * Thread-safety:
 * - nativeBurnSubtitles() BLOKUJE volající vlákno (Dispatchers.IO).
 * - nativeCancel() je bezpečné volat z libovolného vlákna (@Volatile flag v C++).
 */
class NativeVideoEngine(private val context: Context) {

    companion object {
        private const val TAG = "NativeVideoEngine"

        @Volatile private var isNativeLoaded = false
        @Volatile private var lastLoadError: String? = null

        @Synchronized
        private fun ensureLoaded(): Boolean {
            if (isNativeLoaded) return true
            try {
                // Monolitický build: Všechny FFmpeg a externí závislosti (.a) jsou staticky
                // slinkovány do libmedia_engine.so. Už žádné TLS crashe na dlopen!
                System.loadLibrary("c++_shared")
                System.loadLibrary("media_engine")
                
                isNativeLoaded = true
                Log.i(TAG, "FFmpeg Monolithic SDK (Statické knihovny) úspěšně načteno.")
                return true
            } catch (e: Throwable) {
                lastLoadError = e.message
                Log.e(TAG, "KRITICKÁ CHYBA PŘI NAČÍTÁNÍ NATIVE KNIHOVEN: ${e.message}")
                return false
            }
        }

        fun isAvailable(): Boolean {
            val available = ensureLoaded()
            if (!available) {
                Log.e(TAG, "NativeVideoEngine isAvailable=false. Poslední chyba: $lastLoadError")
            }
            return available
        }
        @JvmStatic
        external fun nativeReleaseCompositor()
    }

    // Uchovává otevřený ParcelFileDescriptor po dobu JNI volání.
    // MUSÍ být uzavřen po dokončení nativního volání (v finally bloku)!
    private var activePfd: android.os.ParcelFileDescriptor? = null

    // ----------------------------------------------------------------------
    // Deklarace nativních metod (implementace v media_engine_jni.cpp)
    // ----------------------------------------------------------------------

    /**
     * WYSIWYG JNI entry point. Blokující — voláno z Dispatchers.IO.
     *
     * @param frameProvider  Kotlin interface volán z C++ pro každý video snímek
     * @param overlayBitmap  Sdílená ARGB_8888 bitmapa (rozměry videa), C++ do ní píše přes JNI
     */
    private external fun nativeBurnSubtitles(
        videoUri: String,
        outFilePath: String,
        listener: RenderProgressListener?,
        frameProvider: SubtitleFrameProvider?,
        overlayBitmap: Bitmap?,
        useHardware: Boolean,
        codecIndex: Int,
        qualityLevel: Int,
        audioStreamIndex: Int,
        threadCount: Int,
        preset: String
    ): Int

    /**
     * Nastaví cancellation flag pro probíhající nativní renderování.
     * Thread-safe — volatelné z libovolného vlákna.
     */
    private external fun nativeCancel()

    /** Extrahuje a downsampluje audio z videa pomocí FFmpeg do 16kHz PCM. */
    private external fun nativeExtractAudio16kHz(videoUri: String, outWavPath: String, streamIndex: Int): Int

    private external fun nativeGetAudioStreamCount(videoUri: String): Int
    
    fun getAudioStreamCount(videoUri: Uri): Int {
        if (!isAvailable()) return 0
        val path = resolveToFdPath(videoUri) ?: return 0
        return try {
            nativeGetAudioStreamCount(path)
        } catch (e: Exception) {
            Log.e(TAG, "nativeGetAudioStreamCount selhal: ${e.message}", e)
            0
        } finally {
            // PFD se musí uzavřít VŽDY — bez ohledu na výsledek JNI volání
            activePfd?.close()
            activePfd = null
        }
    }

    /** Generuje nízko-náročné proxy video (720p, H.264, GOP=1). */
    private external fun nativeGenerateProxy(
        inputPath: String,
        outputPath: String,
        listener: RenderProgressListener?
    ): Int

    /** NDK 60 FPS Motion Interpolation */
    external fun nativeInterpolateFps(
        inputPath: String,
        outputPath: String,
        targetFps: Int,
        listener: RenderProgressListener?
    ): Int

    fun interpolateVideoFps(
        inputFile: File,
        outputFile: File,
        targetFps: Int = 60,
        progressListener: RenderProgressListener? = null
    ): RenderResult {
        if (!isAvailable()) {
            return RenderResult.Error("libmedia_engine.so není dostupná.", -1)
        }
        return try {
            val result = nativeInterpolateFps(inputFile.absolutePath, outputFile.absolutePath, targetFps, progressListener)
            if (result == 0) RenderResult.Success(outputFile)
            else RenderResult.Error("Chyba při 60 FPS interpolaci videa: $result", result)
        } catch (e: Exception) {
            RenderResult.Error(e.message ?: "Chyba", -1)
        }
    }

    // ----------------------------------------------------------------------
    // ZERO-COPY GPU PIPELINE (Dálnice A) JNI metody
    // ----------------------------------------------------------------------
    external fun nativeCompositeFrame(bitmap: Bitmap?, videoTexId: Int, transformMatrix: FloatArray)

    // Paralelní zpracování audia přes FFmpeg
    external fun nativeProcessAudioToMuxer(
        muxerPtr: Long, 
        inVideoPath: String, 
        audioStreamIndex: Int,
        volumeGain: Float,
        compressor: Boolean,
        ebuR128: Boolean
    )
    external fun nativeInitMuxer(outPath: String, width: Int, height: Int, fps: Int, codecIndex: Int): Long
    external fun nativeWritePacket(
        muxerPtr: Long,
        directBuffer: java.nio.ByteBuffer,
        offset: Int,
        size: Int,
        ptsUs: Long,
        isKeyFrame: Boolean,
        isConfig: Boolean
    )

    external fun nativeSignalVideoEof(muxerPtr: Long)
    external fun nativeCloseMuxer(muxerPtr: Long)
    
    // ----------------------------------------------------------------------
    // Veřejné API
    // ----------------------------------------------------------------------

    /**
     * Spustí WYSIWYG nativní FFmpeg render pipeline.
     *
     * @param videoUri       Content nebo file URI ke zdrojovému videu
     * @param outputFile     Cílový výstupní soubor
     * @param progressListener Callback pro průběh zpracování (0–100%)
     * @param frameProvider  Kotlin renderer — volán pro každý snímek z C++
     * @param overlayBitmap  Sdílená ARGB_8888 bitmapa (rozměry = rozměry videa)
     * @return RenderResult.Success, .Error nebo .Cancelled
     */
    fun burnSubtitles(
        videoUri: Uri,
        outputFile: File,
        progressListener: RenderProgressListener,
        frameProvider: SubtitleFrameProvider? = null,
        overlayBitmap: Bitmap? = null,
        useHardware: Boolean = true,
        codecIndex: Int = 0,
        qualityLevel: Int = 0,
        audioStreamIndex: Int = 0,
        volumeGain: Float = 1.0f,
        compressor: Boolean = false,
        ebuR128: Boolean = false,
        threads: Int = 0,
        preset: String = "veryfast"
    ): RenderResult {
        if (!isNativeLoaded) {
            return RenderResult.Error(
                "libmedia_engine.so není dostupná. Zkompilujte projekt se statickými FFmpeg knihovnami.",
                code = -1
            )
        }

        val videoPath = resolveToFdPath(videoUri)
            ?: return RenderResult.Error(
                "Nelze přeložxit video URI na cestu pro FFmpeg: $videoUri",
                code = -1
            )

        Log.i(TAG, "Spouštím WYSIWYG render: $videoUri → ${outputFile.absolutePath}")
        progressListener.onProgress(0f)

        return try {
            val resultCode = try {
                nativeBurnSubtitles(
                    videoUri      = videoPath,
                    outFilePath   = outputFile.absolutePath,
                    listener      = progressListener,
                    frameProvider = frameProvider,
                    overlayBitmap = overlayBitmap,
                    useHardware   = useHardware,
                    codecIndex    = codecIndex,
                    qualityLevel  = qualityLevel,
                    audioStreamIndex = audioStreamIndex,
                    threadCount   = threads,
                    preset        = preset
                )
            } finally {
                activePfd?.close()
                activePfd = null
            }

            when (resultCode) {
                0 -> {
                    progressListener.onProgress(100f)
                    if (outputFile.exists() && outputFile.length() > 0) {
                        Log.i(TAG, "WYSIWYG render úspěšný: ${outputFile.absolutePath}")
                        RenderResult.Success(outputFile)
                    } else {
                        RenderResult.Error(
                            "FFmpeg vrátil úspěch (0), ale výstupní soubor je prázdný nebo chybí.",
                            code = 0
                        )
                    }
                }
                -3 -> {
                    Log.i(TAG, "Renderování zrušeno uživatelem.")
                    RenderResult.Cancelled
                }
                else -> {
                    RenderResult.Error(
                        "Nativní FFmpeg render selhal s kódem: $resultCode",
                        code = resultCode
                    )
                }
            }
        } catch (e: Exception) {
            activePfd?.close()
            activePfd = null
            Log.e(TAG, "JNI volání nativeBurnSubtitles vyhodilo výjimku.", e)
            RenderResult.Error(
                e.message ?: "Neznámá JNI výjimka při nativním renderování.",
                code = -99
            )
        }
    }

    /**
     * Zruší probíhající nativní render. Bezpečné volat z libovolného vlákna
     * (nastaví std::atomic<bool> v C++).
     */
    fun cancelRender() {
        if (isNativeLoaded) {
            try {
                nativeCancel()
                Log.i(TAG, "cancelRender: Nativní cancellation flag nastaven.")
            } catch (e: Exception) {
                Log.e(TAG, "cancelRender: Výjimka při volání nativeCancel.", e)
            }
        }
    }

    /**
     * Extrahuje a striktně podvzorkuje audio stopu na 16000 Hz, Mono, 16-bit PCM.
     * Ideální pro ASR modely (Whisper, Sherpa-ONNX).
     *
     * @param videoUri Zdrojové video (content:// nebo file://)
     * @param outFile  Cílový RAW PCM soubor
     * @return true při úspěchu, false při chybě
     */
    suspend fun extractAudioForAsr(videoUri: Uri, outFile: File, streamIndex: Int = -1): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!isAvailable()) {
                Log.e(TAG, "extractAudioForAsr: C++ knihovny nejsou k dispozici.")
                return@withContext false
            }

            try {
                val videoPath = resolveToFdPath(videoUri)
                if (videoPath == null) {
                    Log.e(TAG, "extractAudioForAsr: Nelze přeložit URI: $videoUri")
                    return@withContext false
                }
                Log.i(TAG, "Spouštím nativní extrakci audia (16kHz) pro: $videoUri")

                val resultCode = try {
                    nativeExtractAudio16kHz(videoPath, outFile.absolutePath, streamIndex)
                } finally {
                    // Uzavření fd VŽDY po dokončení nativního volání
                    activePfd?.close()
                    activePfd = null
                }

                if (resultCode == 0 && outFile.exists() && outFile.length() > 0) {
                    Log.i(TAG, "Nativní extrakce audia úspěšná: ${outFile.absolutePath}")
                    return@withContext true
                } else {
                    Log.e(TAG, "Nativní extrakce audia selhala (kód: $resultCode)")
                    return@withContext false
                }
            } catch (e: Exception) {
                activePfd?.close()
                activePfd = null
                Log.e(TAG, "Výjimka při extrakci audia: ${e.message}", e)
                return@withContext false
            }
        }

    /**
     * Vygeneruje nízko-náročné (720p, H.264, GOP=1) proxy video z původního zdroje.
     * Používá se pro zero-latency plynulý scrubbing v editoru.
     *
     * @param videoUri Původní video
     * @param outputFile Kam se má proxy uložit
     * @param progressListener Callback průběhu (0-100%)
     * @return true pokud proxy úspěšně vygenerováno, jinak false
     */
    suspend fun generateProxy(
        videoUri: Uri,
        outputFile: File,
        progressListener: RenderProgressListener
    ): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!isAvailable()) {
            Log.e(TAG, "generateProxy: C++ knihovny nejsou k dispozici.")
            return@withContext false
        }

        try {
            val videoPath = resolveToFdPath(videoUri)
            if (videoPath == null) {
                Log.e(TAG, "generateProxy: Nelze přeložit URI: $videoUri")
                return@withContext false
            }
            Log.i(TAG, "Spouštím generování proxy pro: $videoUri")

            val resultCode = try {
                nativeGenerateProxy(videoPath, outputFile.absolutePath, progressListener)
            } finally {
                activePfd?.close()
                activePfd = null
            }

            if (resultCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                Log.i(TAG, "Generování proxy úspěšné: ${outputFile.absolutePath}")
                return@withContext true
            } else {
                Log.e(TAG, "Generování proxy selhalo (kód: $resultCode)")
                return@withContext false
            }
        } catch (e: Exception) {
            activePfd?.close()
            activePfd = null
            Log.e(TAG, "Výjimka při generování proxy: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Převede libovolné URI na cestu použitelnou nativním FFmpeg.
     *
     * - file:// → přímá cesta (bez konverze)
     * - content:// → ParcelFileDescriptor → /proc/self/fd/<fd>
     *
     * PROČ vždy PFD pro content://:
     * Android 13+ Scoped Storage blokuje přímý open() na /storage/emulated/0/
     * i když File.exists() vrátí true (EACCES). Jediná spolehlivá metoda je PFD
     * přes Content Provider, který má oprávnění udělené uživatelem přes File Picker.
     * Platí pro ExternalStorage, Downloads, SD kartu i jakýkoli jiný SAF provider.
     *
     * activePfd MUSÍ volající uzavřít po dokončení JNI volání!
     */
    private fun resolveToFdPath(uri: Uri): String? {
        // file:// → přímá cesta, žádná konverze
        if (uri.scheme?.lowercase() == "file") {
            return uri.path
        }

        // content:// → VŽDY ParcelFileDescriptor, bez výjimky
        if (uri.scheme?.lowercase() == "content") {
            return try {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: throw IllegalStateException("openFileDescriptor vrátil null pro $uri")
                activePfd = pfd
                val fdPath = "/proc/self/fd/${pfd.fd}"
                Log.d(TAG, "resolveToFdPath: FD path: $fdPath (fd=${pfd.fd})")
                fdPath
            } catch (e: Exception) {
                Log.e(TAG, "resolveToFdPath: ParcelFileDescriptor selhal pro: $uri", e)
                null
            }
        }

        // Jiné schéma (http://, rtsp:// atd.) — předáme as-is, FFmpeg má vlastní handlery
        Log.w(TAG, "resolveToFdPath: Neznámé schéma '${uri.scheme}', předávám as-is.")
        return uri.toString()
    }
}
