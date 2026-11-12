package com.example.llama.srt.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.llama.media.ExportFormatRegistry
import com.example.llama.media.NativeVideoEngine
import com.example.llama.media.RenderProgressListener
import com.example.llama.media.RenderResult
import com.example.llama.media.SubtitleFrameProvider
import com.example.llama.media.VideoRenderPipeline
import com.example.llama.srt.SrtBlock
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import com.example.llama.srt.ui.views.SubtitleOverlayRenderer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Subtitle Export Orchestrator — Dual-Pipeline: GPU Zero-Copy (A) + FFmpeg Canvas (B).
 *
 * OBJE pipeline renderují titulky přes SubtitleOverlayRenderer + Canvas:
 *  → Všechny animace (WORD_POP, KARAOKE_SWEEP, BOUNCE, TYPEWRITER...) fungují v OBOU cestách.
 *  → Žádné plain-text ASS. Žádný bypass animací.
 *
 * Dálnice A (API 34+, GPU Zero-Copy):
 *   MediaCodec Decoder → SurfaceTexture → EGL →
 *   GpuSubtitleProvider.renderFrameAndGetFence() [Canvas → HardwareBuffer] →
 *   nativeCompositeFrame() [EGL composite] → MediaCodec Encoder → C++ Muxer.
 *
 * Dálnice B (FFmpeg + Kotlin Canvas Callback):
 *   FFmpeg Decoder → JNI renderFrame() callback →
 *   SubtitleOverlayRenderer.drawFrame() [Canvas → Bitmap] →
 *   FFmpeg Overlay Filter → FFmpeg Encoder → FFmpeg Muxer.
 *
 * Auto-fallback: A selže za runtime → automaticky B (obě mají identický Canvas rendering).
 *
 * PFD lifecycle: PFD pro audio worker je držen živý po celou dobu JNI volání (orchestratorPfd).
 * Zabráníme EBADF — GC nesmí FD zavřít dřív než FFmpeg audio worker skončí.
 */
class SubtitleExportOrchestrator(private val context: Context) {

    companion object {
        private const val TAG = "ExportOrchestrator"

        /** MIME typy podporované GPU Zero-Copy pipeline (MediaCodec HW) */
        private val GPU_PATH_SUPPORTED_MIMES = setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_VIDEO_AV1
        )

        /** Minimální délka SRT bloku pro varování (ms) */
        private const val MIN_BLOCK_DURATION_MS = 80L

        /** Práh překrytí titulků pro varování (ms) */
        private const val OVERLAP_WARNING_THRESHOLD_MS = 50L

        /** Minimální velikost platného výstupního souboru (bytes) */
        private const val MIN_OUTPUT_FILE_SIZE_BYTES = 2048L

        /** Throttle perioda pro progress callbacks (ms) */
        private const val PROGRESS_THROTTLE_MS = 250L

        /** Max velikost bitmap poolu pro Dálnici B */
        private const val BITMAP_POOL_MAX_SIZE = 2
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Interní datové modely
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Výsledek analýzy vstupního videa.
     */
    private data class VideoAnalysis(
        val width: Int,
        val height: Int,
        val mimeType: String,
        val requiresNativeFallback: Boolean,
        val rotation: Int = 0,
        val durationMs: Long = -1L,
        val frameRateNum: Int = 30,
        val frameRateDen: Int = 1,
        val bitrateBps: Long = -1L,
        val audioStreamCount: Int = 0,
        val hasHdrContent: Boolean = false
    ) {
        /** Efektivní šířka po aplikaci rotace */
        val effectiveWidth: Int get() =
            if (rotation == 90 || rotation == 270) height else width

        /** Efektivní výška po aplikaci rotace */
        val effectiveHeight: Int get() =
            if (rotation == 90 || rotation == 270) width else height

        /** FPS jako Double */
        val frameRateDouble: Double get() =
            if (frameRateDen > 0) frameRateNum.toDouble() / frameRateDen else 30.0
    }

    /**
     * Statistiky SRT bloků — pro logování a debug.
     */
    private data class SubtitleAnalysis(
        val totalBlocks: Int,
        val validBlocks: Int,
        val overlappingPairs: Int,
        val shortBlocks: Int,
        val averageDurationMs: Long,
        val firstSubtitleMs: Long,
        val lastSubtitleMs: Long,
        val hasWordTimestamps: Boolean,
        val warnings: List<String>
    )

    /**
     * Výsledné statistiky exportu.
     */
    data class ExportStatistics(
        val pipeline: String,
        val outputPath: String,
        val outputSizeBytes: Long,
        val videoDurationMs: Long,
        val subtitleBlocks: Int,
        val exportDurationMs: Long
    ) {
        val speedFactor: Double get() =
            if (exportDurationMs > 0) videoDurationMs.toDouble() / exportDurationMs else 0.0
    }

    /**
     * Pipeline strategie.
     */
    private enum class ExecutionStrategy {
        /** Dálnice A: GPU Zero-Copy (API 34+, HW encoder, podporovaný MIME) */
        GPU_ZERO_COPY,
        /** Dálnice B: FFmpeg Tank + Kotlin Canvas callback (WYSIWYG animace) */
        FFMPEG_CANVAS
    }

    /**
     * Sledovač stavu animace pro aktuální render frame.
     * Detekuje změnu aktivního SRT bloku a počítá elapsed time pro animace.
     */
    private class RendererState {
        private var currentBlockHash: Int = -1
        private var animStartMs: Long = 0L

        /**
         * Vrátí elapsed time v ms od začátku aktuálního bloku.
         * Resetuje čítač při změně bloku.
         */
        fun getElapsedMs(block: SrtBlock, ptsMs: Long): Long {
            val blockHash = System.identityHashCode(block)
            if (blockHash != currentBlockHash) {
                currentBlockHash = blockHash
                animStartMs = ptsMs
            }
            return maxOf(0L, ptsMs - animStartMs)
        }

        fun reset() {
            currentBlockHash = -1
            animStartMs = 0L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Stav
    // ──────────────────────────────────────────────────────────────────────────

    private val nativeEngine by lazy { NativeVideoEngine(context) }
    private val isCancelledFlag = AtomicBoolean(false)

    // ──────────────────────────────────────────────────────────────────────────
    // Hlavní API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Spustí export videa s vypálenými animovanými titulky přes Canvas.
     *
     * Routing:
     *   API 34+ + HW encoder + podporovaný MIME → Dálnice A (GPU Zero-Copy).
     *   Cokoliv jiného → Dálnice B (FFmpeg + Canvas).
     *   Každá pipeline jede samostatně — žádný auto-fallback.
     *
     * Obě pipeline renderují přes [SubtitleOverlayRenderer.drawFrame()] —
     * všechny animace (WORD_POP, KARAOKE_SWEEP, BOUNCE, TYPEWRITER atd.) jsou zachovány.
     *
     * @param videoUri         Content nebo file URI ke zdrojovému videu
     * @param blocks           Seznam SRT bloků k vypalování (seřazený dle startTimeMs)
     * @param styleConfig      Konfigurace stylu a animací titulků
     * @param outputFile       Cílový výstupní soubor
     * @param workspaceDir     Pracovní adresář pro temp soubory
     * @param useHardware      Preferovat HW encoder
     * @param codecIndex       Index výstupního kodeku (viz ExportFormatRegistry)
     * @param qualityLevel     0=Vysoká, 1=Standardní, 2=Úsporná
     * @param audioStreamIndex Index audio stopy (0 = první)
     * @param volumeGain       Zesílení audia (1.0f = beze změny)
     * @param compressor       Zapnout audio kompressor
     * @param ebuR128          Normalizace audia na EBU R128 (-23 LUFS)
     * @param trimStartMs      Začátek trim rozsahu (ms), 0 = bez ořezu
     * @param trimEndMs        Konec trim rozsahu (ms), Long.MAX_VALUE = bez ořezu
     * @param onProgress       Callback 0–100
     * @return Výstupní File nebo null při chybě / zrušení
     */
    suspend fun executeExport(
        videoUri: Uri,
        blocks: List<SrtBlock>,
        styleConfig: SubtitleStyleConfig,
        outputFile: File,
        workspaceDir: File,
        useHardware: Boolean = true,
        codecIndex: Int = 0,
        qualityLevel: Int = 0,
        audioStreamIndex: Int = 0,
        volumeGain: Float = 1.0f,
        compressor: Boolean = false,
        ebuR128: Boolean = false,
        trimStartMs: Long = 0L,
        trimEndMs: Long = Long.MAX_VALUE,
        forcePipeline: Int = 0,
        thermalOrdinal: Int = 1, // BALANCED by default
        forceSoftware: Boolean = false,
        onProgress: (Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {

        isCancelledFlag.set(false)
        val exportStartMs = System.currentTimeMillis()
        onProgress(1)

        // ── 1. Pre-processing bloků ──────────────────────────────────────────
        val processedBlocks = preprocessBlocks(blocks, trimStartMs, trimEndMs)
        val subtitleAnalysis = analyzeSubtitles(processedBlocks)
        logSubtitleAnalysis(subtitleAnalysis)
        onProgress(3)

        // ── 2. Analýza videa ─────────────────────────────────────────────────
        val videoAnalysis = analyzeRequirements(videoUri)
        val inputMeta = com.example.llama.media.MediaNegotiator.inspectInput(context, videoUri)
        Log.i(
            TAG,
            "🎬 Video: ${videoAnalysis.effectiveWidth}×${videoAnalysis.effectiveHeight} " +
            "${videoAnalysis.mimeType} @${String.format("%.2f", videoAnalysis.frameRateDouble)}fps " +
            "rot=${videoAnalysis.rotation}° dur=${videoAnalysis.durationMs}ms " +
            "audio=${videoAnalysis.audioStreamCount} HDR=${videoAnalysis.hasHdrContent}"
        )
        onProgress(6)

        // ── 3. Routing ───────────────────────────────────────────────────────
        val targetCodec = ExportFormatRegistry.VideoCodec.entries.find { it.codecIndex == codecIndex } ?: ExportFormatRegistry.VideoCodec.H264
        val hwFormat = com.example.llama.media.MediaNegotiator.negotiateHardwareFormat(targetCodec, inputMeta)
        val caps = com.example.llama.media.DeviceProfiler.getCapabilities(context)
        
        // Pokud hwFormat vrátí validní MediaFormat, HW kodér existuje a je pro tuto operaci připraven.
        val canUseHwEncoder = hwFormat != null && ExportFormatRegistry.canUseHardwareEncoder(context, codecIndex)
        val strategy = selectStrategy(videoAnalysis, useHardware, canUseHwEncoder, forcePipeline)
        Log.i(
            TAG,
            "🚦 ROUTER: strategy=$strategy | API=${Build.VERSION.SDK_INT} | " +
            "HW=$useHardware | canHWEncode=$canUseHwEncoder | " +
            "requiresFallback=${videoAnalysis.requiresNativeFallback} | " +
            "LowRAM=${caps.isLowRamDevice}"
        )
        
        if (caps.isLowRamDevice && strategy == ExecutionStrategy.FFMPEG_CANVAS) {
            Log.w(TAG, "Low RAM detekována. Doporučeno omezit FFmpeg na menší počet vláken v C++.")
        }
        
        onProgress(9)

        // ── 4. Workspace ─────────────────────────────────────────────────────
        ensureWorkspaceReady(workspaceDir)

        // ── 5. Výkon exportu ─────────────────────────────────────────────────
        val result: File?
        val usedStrategy = strategy

        // Záchranná brzda pro slabá zařízení a zohlednění Thermal Mode
        val thermalMode = com.example.llama.srt.ui.models.ExportThermalMode.entries.getOrNull(thermalOrdinal) 
            ?: com.example.llama.srt.ui.models.ExportThermalMode.BALANCED
        
        val ffmpegThreads = if (caps.isLowRamDevice) {
            Log.w(TAG, "Slabé zařízení detekováno. Omezuji FFmpeg na 2 vlákna navzdory Thermal módu.")
            2
        } else {
            thermalMode.ffmpegThreads
        }
        val preset = thermalMode.preset

        val effectiveStrategy = if (forceSoftware) ExecutionStrategy.FFMPEG_CANVAS else strategy

        if (effectiveStrategy == ExecutionStrategy.GPU_ZERO_COPY) {
            Log.i(TAG, "🚦 Dálnice A (GPU Zero-Copy | MediaCodec + EGL + Canvas).")
            result = executeFastGpuPath(
                videoUri, processedBlocks, styleConfig, outputFile,
                onProgress, codecIndex, qualityLevel, audioStreamIndex,
                volumeGain, compressor, ebuR128, videoAnalysis
            )
        } else {
            Log.i(TAG, "🚦 Dálnice B (FFmpeg Tank + Kotlin Canvas Callback). Threads=$ffmpegThreads, Preset=$preset")
            result = executeNativePath(
                videoUri, processedBlocks, styleConfig, outputFile, workspaceDir,
                onProgress, useHardware, codecIndex, qualityLevel,
                audioStreamIndex, volumeGain, compressor, ebuR128, videoAnalysis, ffmpegThreads, preset
            )
        }

        // ── 6. Validace výstupu ──────────────────────────────────────────────
        if (result != null && !validateOutputFile(result)) {
            Log.e(TAG, "❌ Validace výstupu selhala: ${result.absolutePath}")
            try { result.delete() } catch (_: Exception) {}
            return@withContext null
        }

        if (result != null) {
            val exportDurationMs = System.currentTimeMillis() - exportStartMs
            logExportStatistics(
                ExportStatistics(
                    pipeline         = usedStrategy.name,
                    outputPath       = result.absolutePath,
                    outputSizeBytes  = result.length(),
                    videoDurationMs  = videoAnalysis.durationMs,
                    subtitleBlocks   = processedBlocks.size,
                    exportDurationMs = exportDurationMs
                )
            )
            onProgress(100)
        }

        result
    }

    /**
     * Zruší probíhající export. Thread-safe.
     */
    fun cancel() {
        isCancelledFlag.set(true)
        nativeEngine.cancelRender()
        Log.i(TAG, "cancel(): Export zrušen.")
    }

    fun isCancelled(): Boolean = isCancelledFlag.get()

    // ──────────────────────────────────────────────────────────────────────────
    // Pre-processing
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Filtruje bloky na trim range a normalizuje timestampy na 0-based čas.
     * Seřadí výsledek dle startTimeMs.
     */
    private fun preprocessBlocks(
        blocks: List<SrtBlock>,
        trimStartMs: Long,
        trimEndMs: Long
    ): List<SrtBlock> {
        if (blocks.isEmpty()) return emptyList()

        val needsTrim = trimStartMs > 0L || trimEndMs < Long.MAX_VALUE

        val filtered = if (needsTrim) {
            blocks
                .filter { it.endTimeMs > trimStartMs && it.startTimeMs < trimEndMs }
                .map { block ->
                    val newStart = maxOf(0L, block.startTimeMs - trimStartMs)
                    val newEnd   = minOf(trimEndMs - trimStartMs, block.endTimeMs - trimStartMs)
                    block.copy(startTimeMs = newStart, endTimeMs = newEnd)
                }
        } else blocks

        return filtered.sortedBy { it.startTimeMs }
    }

    /**
     * Statistická analýza SRT bloků — detekce překryvů, krátkých bloků, warnings.
     */
    private fun analyzeSubtitles(blocks: List<SrtBlock>): SubtitleAnalysis {
        if (blocks.isEmpty()) {
            return SubtitleAnalysis(
                totalBlocks = 0, validBlocks = 0, overlappingPairs = 0,
                shortBlocks = 0, averageDurationMs = 0, firstSubtitleMs = 0,
                lastSubtitleMs = 0, hasWordTimestamps = false,
                warnings = listOf("Žádné SRT bloky.")
            )
        }

        val warnings = mutableListOf<String>()
        var overlapping = 0
        var shortCount  = 0
        var totalDur    = 0L

        for (i in blocks.indices) {
            val dur = blocks[i].endTimeMs - blocks[i].startTimeMs
            if (dur < MIN_BLOCK_DURATION_MS) shortCount++
            totalDur += dur.coerceAtLeast(0L)

            if (i < blocks.size - 1) {
                if (blocks[i].endTimeMs > blocks[i + 1].startTimeMs + OVERLAP_WARNING_THRESHOLD_MS) {
                    overlapping++
                }
            }
        }

        if (overlapping > 0)
            warnings.add("$overlapping překrývajících se párů titulků.")
        if (shortCount > 0)
            warnings.add("$shortCount bloků kratší než ${MIN_BLOCK_DURATION_MS}ms.")

        return SubtitleAnalysis(
            totalBlocks       = blocks.size,
            validBlocks       = blocks.count { (it.endTimeMs - it.startTimeMs) > 0 },
            overlappingPairs  = overlapping,
            shortBlocks       = shortCount,
            averageDurationMs = totalDur / blocks.size,
            firstSubtitleMs   = blocks.first().startTimeMs,
            lastSubtitleMs    = blocks.last().endTimeMs,
            hasWordTimestamps  = blocks.any { it.wordTimestamps?.isNotEmpty() == true },
            warnings          = warnings
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Routing: Analýza videa + výběr strategie
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Analyzuje vstupní video přes MediaExtractor + MediaMetadataRetriever.
     */
    private fun analyzeRequirements(videoUri: Uri): VideoAnalysis {
        val extractor = MediaExtractor()
        var w              = 1920
        var h              = 1080
        var mime           = "video/avc"
        var needsFallback  = false
        var rotation       = 0
        var durationMs     = -1L
        var fpsNum         = 30
        var fpsDen         = 1
        var bitrateBps     = -1L
        var audioCount     = 0
        var hasHdr         = false

        try {
            // Preferujeme PFD pro content:// URI
            val pfd = if (videoUri.scheme?.lowercase() == "content") {
                runCatching { context.contentResolver.openFileDescriptor(videoUri, "r") }.getOrNull()
            } else null

            if (pfd != null) {
                extractor.setDataSource(pfd.fileDescriptor)
                pfd.close()
            } else {
                extractor.setDataSource(context, videoUri, null)
            }

            var videoTrackFound = false

            for (i in 0 until extractor.trackCount) {
                val fmt         = extractor.getTrackFormat(i)
                val trackMime   = fmt.getString(MediaFormat.KEY_MIME) ?: continue

                if (trackMime.startsWith("video/") && !videoTrackFound) {
                    videoTrackFound = true
                    mime = trackMime

                    w        = if (fmt.containsKey(MediaFormat.KEY_WIDTH))  fmt.getInteger(MediaFormat.KEY_WIDTH)  else 1920
                    h        = if (fmt.containsKey(MediaFormat.KEY_HEIGHT)) fmt.getInteger(MediaFormat.KEY_HEIGHT) else 1080
                    rotation = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0

                    if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fpsNum = fmt.getInteger(MediaFormat.KEY_FRAME_RATE)
                    if (fmt.containsKey(MediaFormat.KEY_BIT_RATE))   bitrateBps = fmt.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                    if (fmt.containsKey(MediaFormat.KEY_DURATION))   durationMs = fmt.getLong(MediaFormat.KEY_DURATION) / 1000L

                    // HDR detekce (API 29+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        fmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER)
                    ) {
                        val ct = fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                        hasHdr = ct == MediaFormat.COLOR_TRANSFER_HLG ||
                                 ct == MediaFormat.COLOR_TRANSFER_ST2084
                    }

                    if (trackMime !in GPU_PATH_SUPPORTED_MIMES) {
                        needsFallback = true
                        Log.d(TAG, "MIME '$trackMime' mimo GPU whitelist → Dálnice B.")
                    }
                }

                if (trackMime.startsWith("audio/")) audioCount++
            }

            if (!videoTrackFound) {
                Log.w(TAG, "Žádná video stopa → Dálnice B.")
                needsFallback = true
            }

        } catch (e: Exception) {
            Log.e(TAG, "analyzeRequirements: MediaExtractor selhal → Dálnice B.", e)
            needsFallback = true
        } finally {
            extractor.release()
        }

        // HardwareBufferRenderer odstraněn, používáme standardní glTexImage2D (Dálnice A funguje všude)

        // Fallback délky přes MMR pokud extractor nic nedal
        if (durationMs < 0) durationMs = retrieveDurationMs(videoUri)

        return VideoAnalysis(
            width                 = w,
            height                = h,
            mimeType              = mime,
            requiresNativeFallback = needsFallback,
            rotation              = rotation,
            durationMs            = durationMs,
            frameRateNum          = fpsNum,
            frameRateDen          = fpsDen,
            bitrateBps            = bitrateBps,
            audioStreamCount      = audioCount,
            hasHdrContent         = hasHdr
        )
    }

    /**
     * Vybere pipeline strategii.
     * Dálnice A: API 34+ AND HW encoder AND podporovaný MIME AND useHardware=true.
     * Dálnice B: vše ostatní. 
     * forcePipeline: 0=Auto, 1=Force A, 2=Force B
     */
    private fun selectStrategy(
        analysis: VideoAnalysis,
        useHardware: Boolean,
        canUseHwEncoder: Boolean,
        forcePipeline: Int
        ): ExecutionStrategy {
        if (forcePipeline == 2) return ExecutionStrategy.FFMPEG_CANVAS
        if (forcePipeline == 1) return ExecutionStrategy.GPU_ZERO_COPY
        
        val gpuEligible = !analysis.requiresNativeFallback &&
                      canUseHwEncoder &&
                      useHardware

        return if (gpuEligible) {
            ExecutionStrategy.GPU_ZERO_COPY
        } else {
            ExecutionStrategy.FFMPEG_CANVAS
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DÁLNICE A: GPU Zero-Copy (API 34+)
    // MediaCodec Decoder → EGL SurfaceTexture → GpuSubtitleProvider Canvas →
    // HardwareBuffer Fence → nativeCompositeFrame → MediaCodec Encoder → C++ Muxer
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun executeFastGpuPath(
        videoUri: Uri,
        blocks: List<SrtBlock>,
        styleConfig: SubtitleStyleConfig,
        outputFile: File,
        onProgress: (Int) -> Unit,
        codecIndex: Int,
        qualityLevel: Int,
        audioStreamIndex: Int,
        volumeGain: Float,
        compressor: Boolean,
        ebuR128: Boolean,
        analysis: VideoAnalysis
    ): File? {
        var gpuProvider: GpuSubtitleProvider? = null
        var orchestratorPfd: ParcelFileDescriptor? = null

        return try {
            // 1. GPU producent titulků — alokuje HardwareBuffer šířky/výšky videa
            gpuProvider = GpuSubtitleProvider(analysis.effectiveWidth, analysis.effectiveHeight)
            val pipeline = VideoRenderPipeline(nativeEngine, gpuProvider)

            // 2. Canvas renderer (animace, styly, word-pop, karaoke, …)
            val renderer      = SubtitleOverlayRenderer(context, styleConfig)
            val rendererState = RendererState()

            // 3. PFD pro FFmpeg audio worker (KRITICKÉ: musí žít po celou dobu renderVideoTrack!)
            //    GC může zavřít lokální PFD → audio worker dostane EBADF → video bez zvuku.
            //    Proto udržujeme orchestratorPfd a zavíráme ho až v finally.
            val videoPathForFFmpeg: String
            if (videoUri.scheme?.lowercase() == "content") {
                orchestratorPfd = runCatching {
                    context.contentResolver.openFileDescriptor(videoUri, "r")
                }.getOrNull()

                videoPathForFFmpeg = if (orchestratorPfd != null) {
                    "/proc/self/fd/${orchestratorPfd.fd}"
                } else {
                    Log.w(TAG, "executeFastGpuPath: Nelze otevřít PFD → fallback URI.toString()")
                    videoUri.toString()
                }
            } else {
                videoPathForFFmpeg = videoUri.path ?: videoUri.toString()
            }

            // 4. Render pipeline — blokující coroutine (GL vlákno uvnitř)
            pipeline.renderVideoTrack(
                context           = context,
                videoUri          = videoUri,
                videoPathForFFmpeg = videoPathForFFmpeg,
                outPath           = outputFile.absolutePath,
                codecIndex        = codecIndex,
                qualityLevel      = qualityLevel,
                audioStreamIndex  = audioStreamIndex,
                volumeGain        = volumeGain,
                compressor        = compressor,
                ebuR128           = ebuR128,
                onProgress        = { fraction ->
                    // fraction 0.0–1.0 → UI 10–95%
                    onProgress((10 + fraction * 85f).toInt().coerceIn(10, 95))
                },
                renderCallback    = { canvas, ptsMs ->
                    // Voláno pro každý video frame na GL vlákně
                    val activeBlock = blocks.findActive(ptsMs)
                    if (activeBlock != null) {
                        val elapsedMs = rendererState.getElapsedMs(activeBlock, ptsMs)
                        renderer.drawFrame(
                            canvas,
                            activeBlock,
                            ptsMs,
                            elapsedMs,
                            analysis.effectiveWidth.toFloat(),
                            analysis.effectiveHeight.toFloat()
                        )
                    }
                }
            )

            onProgress(97)
            outputFile

        } catch (e: Exception) {
            Log.e(TAG, "❌ Dálnice A selhala: ${e.message}", e)
            throw e  // Propagace výše pro auto-fallback na Dálnici B

        } finally {
            gpuProvider?.release()
            // PFD uzavřeme ZDE — po dokončení renderVideoTrack() nebo při výjimce.
            // Dříve nesmí být zavřen!
            try { orchestratorPfd?.close() } catch (e: Exception) {
                Log.w(TAG, "Nelze uzavřít orchestratorPfd: ${e.message}")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DÁLNICE B: FFmpeg Tank + Kotlin Canvas Callback (WYSIWYG animace)
    // FFmpeg Decoder → JNI renderFrame() → SubtitleOverlayRenderer Canvas →
    // Bitmap pixels → FFmpeg Overlay Filter → FFmpeg Encoder → FFmpeg Muxer
    // ──────────────────────────────────────────────────────────────────────────

    private suspend fun executeNativePath(
        videoUri: Uri,
        blocks: List<SrtBlock>,
        styleConfig: SubtitleStyleConfig,
        outputFile: File,
        workspaceDir: File,
        onProgress: (Int) -> Unit,
        useHardware: Boolean,
        codecIndex: Int,
        qualityLevel: Int,
        audioStreamIndex: Int,
        volumeGain: Float,
        compressor: Boolean,
        ebuR128: Boolean,
        analysis: VideoAnalysis,
        ffmpegThreads: Int,
        preset: String
    ): File? = withContext(Dispatchers.IO) {

        Log.i(TAG, "Dálnice B: FFmpeg WYSIWYG Canvas. Bloků: ${blocks.size}")

        val bitmapPool    = BitmapPool(analysis.effectiveWidth, analysis.effectiveHeight)
        val rendererState = RendererState()

        try {
            // 1. Overlay bitmapa — sdílená s C++ přes JNI, ARGB_8888
            val overlayBitmap = bitmapPool.acquire()

            // 2. Canvas renderer — identický s Dálnicí A (stejné animace, stejné styly)
            val renderer = SubtitleOverlayRenderer(context, styleConfig)

            // 3. SubtitleFrameProvider — interface volaný z C++ pro každý dekódovaný video frame
            //    C++ kód volá renderFrame(positionMs, bitmap) a čeká na vyplnění bitmapy
            val frameProvider = SubtitleFrameProvider { positionMs, bitmap ->
                val canvas      = Canvas(bitmap)
                val activeBlock = blocks.findActive(positionMs)

                // Vždy vyčistit bitmap — i když žádný titulek není aktivní
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

                if (activeBlock != null) {
                    val elapsedMs = rendererState.getElapsedMs(activeBlock, positionMs)
                    renderer.drawFrame(
                        canvas,
                        activeBlock,
                        positionMs,
                        elapsedMs,
                        analysis.effectiveWidth.toFloat(),
                        analysis.effectiveHeight.toFloat()
                    )
                }
            }

            // 4. Progress listener → JNI volá onProgress() periodicky
            val progressListener = object : RenderProgressListener {
                override fun onProgress(percent: Float) {
                    val uiPct = (10 + (percent / 100f) * 85f).toInt().coerceIn(10, 95)
                    onProgress(uiPct)
                }
            }

            // 5. Blokující FFmpeg C++ render volání
            val result = nativeEngine.burnSubtitles(
                videoUri         = videoUri,
                outputFile       = outputFile,
                progressListener = progressListener,
                frameProvider    = frameProvider,
                overlayBitmap    = overlayBitmap,
                useHardware      = useHardware,
                codecIndex       = codecIndex,
                qualityLevel     = qualityLevel,
                audioStreamIndex = audioStreamIndex,
                volumeGain       = volumeGain,
                compressor       = compressor,
                ebuR128          = ebuR128,
                threads          = ffmpegThreads,
                preset           = preset
            )

            bitmapPool.release(overlayBitmap)

            return@withContext when (result) {
                is RenderResult.Success -> {
                    Log.i(TAG, "✅ Dálnice B: Render úspěšný → ${result.outputFile.absolutePath}")
                    onProgress(97)
                    result.outputFile
                }
                is RenderResult.Cancelled -> {
                    Log.i(TAG, "Dálnice B: Render zrušen.")
                    null
                }
                is RenderResult.Error -> {
                    Log.e(TAG, "❌ Dálnice B selhal: ${result.message} (kód: ${result.code})")
                    null
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Výjimka v Dálnici B: ${e.message}", e)
            return@withContext null
        } finally {
            bitmapPool.recycleAll()
            rendererState.reset()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Post-processing: Validace výstupu
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Ověří platnost výstupního souboru:
     * - Existence souboru
     * - Minimální velikost (≥ 2 KB)
     * - Obsahuje video stopu (MediaExtractor check)
     */
    private fun validateOutputFile(file: File): Boolean {
        if (!file.exists()) {
            Log.e(TAG, "validateOutputFile: Soubor neexistuje: ${file.absolutePath}")
            return false
        }

        if (file.length() < MIN_OUTPUT_FILE_SIZE_BYTES) {
            Log.e(TAG, "validateOutputFile: Příliš malý (${file.length()} bytes): ${file.absolutePath}")
            return false
        }

        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            val hasVideo = (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            }
            extractor.release()

            if (!hasVideo) {
                Log.e(TAG, "validateOutputFile: Výstup neobsahuje video stopu!")
            }
            hasVideo

        } catch (e: Exception) {
            Log.e(TAG, "validateOutputFile: MediaExtractor selhal: ${e.message}", e)
            // Pokud je soubor rozumně velký ale extractor selže, akceptujeme
            file.length() > MIN_OUTPUT_FILE_SIZE_BYTES * 50
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Workspace
    // ──────────────────────────────────────────────────────────────────────────

    private fun ensureWorkspaceReady(workspaceDir: File) {
        if (!workspaceDir.exists()) {
            val ok = workspaceDir.mkdirs()
            Log.d(TAG, "ensureWorkspaceReady: mkdirs=${ok} → ${workspaceDir.absolutePath}")
        }
    }

    /**
     * Zjistí délku videa přes MediaMetadataRetriever (fallback pro analyzeRequirements).
     */
    private fun retrieveDurationMs(videoUri: Uri): Long {
        return try {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, videoUri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: -1L
            }
        } catch (e: Exception) {
            Log.w(TAG, "retrieveDurationMs: ${e.message}")
            -1L
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bitmap Pool — prevence GC storm při Canvas renderingu v Dálnici B
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Thread-safe pool předalokovaných ARGB_8888 bitmap.
     * Každý video frame by jinak alokoval novou bitmapu → GC storm → jitter v renderingu.
     */
    private inner class BitmapPool(
        private val width: Int,
        private val height: Int,
        private val maxSize: Int = BITMAP_POOL_MAX_SIZE
    ) {
        private val pool = ArrayDeque<Bitmap>(maxSize)

        @Synchronized
        fun acquire(): Bitmap {
            if (pool.isEmpty()) {
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
            val bitmap = pool.removeFirst()
            return if (bitmap.isRecycled) {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } else {
                Canvas(bitmap).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                bitmap
            }
        }

        @Synchronized
        fun release(bitmap: Bitmap) {
            if (!bitmap.isRecycled && pool.size < maxSize) {
                pool.addLast(bitmap)
            } else if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }

        @Synchronized
        fun recycleAll() {
            pool.forEach { if (!it.isRecycled) it.recycle() }
            pool.clear()
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * O(log n) binary search pro aktivní SRT blok na dané pozici.
     * Vyžaduje seřazený seznam dle startTimeMs (zajistí preprocessBlocks).
     */
    private fun List<SrtBlock>.findActive(ptsMs: Long): SrtBlock? {
        if (isEmpty()) return null
        var lo = 0
        var hi = size - 1
        while (lo <= hi) {
            val mid   = (lo + hi) ushr 1
            val block = get(mid)
            when {
                ptsMs < block.startTimeMs -> hi = mid - 1
                ptsMs > block.endTimeMs   -> lo = mid + 1
                else                      -> return block
            }
        }
        return null
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Logování
    // ──────────────────────────────────────────────────────────────────────────

    private fun logSubtitleAnalysis(a: SubtitleAnalysis) {
        Log.i(
            TAG,
            "📋 SRT: total=${a.totalBlocks} valid=${a.validBlocks} " +
            "overlaps=${a.overlappingPairs} short=${a.shortBlocks} " +
            "avg=${a.averageDurationMs}ms wordTs=${a.hasWordTimestamps}"
        )
        a.warnings.forEach { Log.w(TAG, "⚠️ SRT: $it") }
    }

    private fun logExportStatistics(s: ExportStatistics) {
        Log.i(
            TAG,
            "📊 Export: pipeline=${s.pipeline} | " +
            "size=${s.outputSizeBytes / 1024}KB | " +
            "blocks=${s.subtitleBlocks} | " +
            "trvání=${s.exportDurationMs}ms | " +
            "speed=${String.format("%.2f", s.speedFactor)}x"
        )
    }
}
