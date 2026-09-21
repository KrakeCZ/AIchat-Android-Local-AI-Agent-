package com.example.llama.srt.ui.editor

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.srt.SrtBlock
import com.example.llama.srt.ui.models.EditorTab
import com.example.llama.srt.ui.models.ExportState
import com.example.llama.srt.ui.models.SrtEditorEvent
import com.example.llama.srt.ui.models.SrtEditorUiState
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.content.Context

class SrtEditorViewModel : ViewModel() {

    companion object {
        private const val TAG = "SrtEditorVM"
        private const val SYNC_INTERVAL_MS = 16L // ~60fps refresh rate pro Karaoke a Timeline
    }

    private val _uiState = MutableStateFlow<SrtEditorUiState>(SrtEditorUiState.Loading)
    val uiState: StateFlow<SrtEditorUiState> = _uiState.asStateFlow()

    private val readyState: SrtEditorUiState.Ready?
        get() = _uiState.value as? SrtEditorUiState.Ready

    private var syncLoopJob: Job? = null
    private var exportJob: Job? = null
    private var pcmProxyJob: Job? = null

    // Původní cesta k SRT souboru pro ukládání změn
    private var originalSrtFilePath: String? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Inicializace
    // ─────────────────────────────────────────────────────────────────────────

    fun initialize(videoUriString: String, srtFilePath: String, initialBlocks: List<SrtBlock>, durationMs: Long, videoFps: Float = 30f) {
        if (_uiState.value is SrtEditorUiState.Ready) {
            val currentState = _uiState.value as SrtEditorUiState.Ready
            // Pokud aktualizujeme dodatečně metadata (z ExoPlayeru či jindy)
            var needsUpdate = false
            var newFps = currentState.videoFps
            var newDuration = currentState.durationMs

            // Pokud volající poslal specifické FPS, a liší se (a není to jen ten default 30f při re-initu)
            // nebo prostě aktualizujeme
            if (videoFps != 30f && currentState.videoFps != videoFps) {
                newFps = videoFps
                needsUpdate = true
            }
            if (durationMs > 0L && currentState.durationMs == 0L) {
                newDuration = durationMs
                needsUpdate = true
            }

            if (needsUpdate) {
                _uiState.value = currentState.copy(videoFps = newFps, durationMs = newDuration)
            }
            return
        }

        this.originalSrtFilePath = srtFilePath

        _uiState.value = SrtEditorUiState.Ready(
            videoUriString = videoUriString,
            srtBlocks = initialBlocks,
            videoFps = videoFps,
            durationMs = durationMs,
            selectedBlockIndex = if (initialBlocks.isNotEmpty()) 0 else -1
        )
    }

    /**
     * Fáze 3: PCM Proxy Engine Launcher
     * Asyncronně extrahuje audio do lokálního .raw souboru pro vizualizaci A1 Waveform.
     * Cesta se zapíše zpět do stavu a Compose AudioTrack ji okamžitě zachytí.
     * Pokud PCM soubor již existuje a je neprázdný, extrakci přeskočí (idempotentní).
     */
    fun initPcmProxy(context: Context, forceReExtract: Boolean = false, extractFn: suspend (outFile: File, streamIndex: Int) -> Boolean) {
        val currentReady = readyState ?: return
        // Idempotence: pokud již cestu máme a nenutíme reextrakci, nespouštíme
        if (!forceReExtract && currentReady.pcmProxyPath != null) return

        pcmProxyJob?.cancel()
        pcmProxyJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // Přidáme k názvu index streamu, abychom zamezili konfliktům
                val streamIdx = currentReady.selectedAudioStream
                val outFile = File(context.cacheDir, "proxy_audio_s${streamIdx}.raw")
                
                // Přeskočíme pokud soubor existuje a má data
                val needsExtract = forceReExtract || !outFile.exists() || outFile.length() == 0L
                if (needsExtract) {
                    val success = extractFn(outFile, streamIdx)
                    if (!success) {
                        Log.w(TAG, "PCM proxy extrakce selhala — AudioTrack použije fallback dummy vlnu.")
                        return@launch
                    }
                }
                // Zápis cesty do stavu na Main vlákně
                withContext(Dispatchers.Main) {
                    val s = readyState ?: return@withContext
                    _uiState.value = s.copy(pcmProxyPath = outFile.absolutePath)
                    Log.i(TAG, "PCM proxy připraven: ${outFile.absolutePath} (${outFile.length()} B)")
                }
            } catch (e: CancellationException) {
                // Normální zrušení při onCleared — ignorujeme
            } catch (e: Exception) {
                Log.e(TAG, "PCM proxy výjimka: ${e.message}", e)
            }
        }
    }

    fun setActiveStudio(studio: SrtEditorUiState.ActiveStudio) {
        val s = readyState ?: return
        if (s.activeStudio == studio) return
        _uiState.value = s.copy(activeStudio = studio)
    }

    fun setAudioStreamCount(count: Int) {
        val s = readyState ?: return
        val tracks = List(count) { i ->
            SrtEditorUiState.AudioTrackConfig(
                streamIndex = i,
                isSelected = (i == s.selectedAudioStream)
            )
        }
        _uiState.value = s.copy(audioStreamCount = count, audioTracks = tracks)
    }

    fun setSelectedAudioStream(index: Int) {
        val s = readyState ?: return
        _uiState.value = s.copy(selectedAudioStream = index)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event Handler (UDF)
    // ─────────────────────────────────────────────────────────────────────────

    fun onEvent(event: SrtEditorEvent) {
        val state = readyState ?: return

        when (event) {
            is SrtEditorEvent.SelectBlock -> {
                _uiState.value = state.copy(selectedBlockIndex = event.index)
            }
            is SrtEditorEvent.UpdateBlockText -> {
                val updatedBlocks = state.srtBlocks.toMutableList()
                if (event.index in updatedBlocks.indices) {
                    val oldBlock = updatedBlocks[event.index]
                    updatedBlocks[event.index] = oldBlock.copy(text = event.newText)
                    _uiState.value = state.copy(srtBlocks = updatedBlocks)
                }
            }
            is SrtEditorEvent.TrimBlockStart -> {
                val updatedBlocks = state.srtBlocks.toMutableList()
                if (event.index in updatedBlocks.indices) {
                    val oldBlock = updatedBlocks[event.index]
                    // Validace: Začátek nesmí přesáhnout konec
                    val validStart = event.newStartMs.coerceAtMost(oldBlock.endTimeMs - 100L)
                    updatedBlocks[event.index] = oldBlock.copy(startTimeMs = validStart)
                    _uiState.value = state.copy(srtBlocks = updatedBlocks)
                }
            }
            is SrtEditorEvent.TrimBlockEnd -> {
                val updatedBlocks = state.srtBlocks.toMutableList()
                if (event.index in updatedBlocks.indices) {
                    val oldBlock = updatedBlocks[event.index]
                    // Validace: Konec nesmí být před začátkem
                    val validEnd = event.newEndMs.coerceAtLeast(oldBlock.startTimeMs + 100L)
                    updatedBlocks[event.index] = oldBlock.copy(endTimeMs = validEnd)
                    _uiState.value = state.copy(srtBlocks = updatedBlocks)
                }
            }
            is SrtEditorEvent.SeekTo -> {
                _uiState.value = state.copy(currentPositionMs = event.positionMs)
            }
            is SrtEditorEvent.SetPlaying -> {
                _uiState.value = state.copy(isPlaying = event.playing)
            }
            is SrtEditorEvent.ApplyStyle -> {
                _uiState.value = state.copy(styleConfig = event.config)
            }
            is SrtEditorEvent.SwitchTab -> {
                _uiState.value = state.copy(activeTab = event.tab)
            }
            is SrtEditorEvent.RepositionSubtitle -> {
                val newConfig = state.styleConfig.copy(posYPercent = event.newPosYPercent)
                _uiState.value = state.copy(styleConfig = newConfig)
            }
            is SrtEditorEvent.StartExport -> {
                _uiState.value = state.copy(exportState = ExportState.InProgress(0))
            }
            is SrtEditorEvent.CancelExport -> {
                cancelExport()
            }
            is SrtEditorEvent.UpdateExportConfig -> {
                _uiState.value = state.copy(exportConfig = event.config)
            }
            is SrtEditorEvent.ProxyStatusChanged -> {
                _uiState.value = state.copy(
                    proxyState = event.newState,
                    proxyUriString = event.proxyPath ?: state.proxyUriString
                )
            }
            is SrtEditorEvent.LoadAudioStream -> {
                setSelectedAudioStream(event.streamIndex)
                _uiState.value = state.copy(
                    selectedAudioStream = event.streamIndex,
                    pcmProxyPath = null // Vynutí A1 fallback a re-load
                )
            }
            is SrtEditorEvent.AutoFixVfr -> {
                // VFR AutoFix Logic: Lineárně přeškáluje časová razítka podle rozdílu 
                // Pokud poslední titulek končí na T1 a video má celkovou délku T2, a víme o driftu.
                // Prozatím implementujeme jednoduché lineární protažení o 1.001 (běžný 30fps -> 29.97fps drift).
                // V ostré verzi by se načetla přesná délka z MediaExtractoru (audio vs video trvání).
                val updatedBlocks = state.srtBlocks.map { block ->
                    // Vzorová korekce: 0.1% drift correction
                    val scaleFactor = 1.001
                    block.copy(
                        startTimeMs = (block.startTimeMs * scaleFactor).toLong(),
                        endTimeMs = (block.endTimeMs * scaleFactor).toLong()
                    )
                }
                _uiState.value = state.copy(srtBlocks = updatedBlocks)
            }
            is SrtEditorEvent.UpdateAudioTrackConfig -> {
                val updatedTracks = state.audioTracks.map {
                    if (it.streamIndex == event.config.streamIndex) event.config else it
                }
                _uiState.value = state.copy(audioTracks = updatedTracks)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shift Titulků (VFR Sync & Ergo-Úpravy)
    // ─────────────────────────────────────────────────────────────────────────

    fun shiftAllBlocks(offsetMs: Long) {
        val current = readyState ?: return
        val newBlocks = current.srtBlocks.map { block ->
            block.copy(
                startTimeMs = (block.startTimeMs + offsetMs).coerceAtLeast(0L),
                endTimeMs = (block.endTimeMs + offsetMs).coerceAtLeast(0L)
            )
        }
        _uiState.value = current.copy(srtBlocks = newBlocks)
    }

    fun shiftBlock(index: Int, offsetMs: Long) {
        val current = readyState ?: return
        if (index !in current.srtBlocks.indices) return
        
        val mutableBlocks = current.srtBlocks.toMutableList()
        val block = mutableBlocks[index]
        mutableBlocks[index] = block.copy(
            startTimeMs = (block.startTimeMs + offsetMs).coerceAtLeast(0L),
            endTimeMs = (block.endTimeMs + offsetMs).coerceAtLeast(0L)
        )
        
        // Zajištění sekvenční integrity (aby posunutý blok nepřeskočil jiný v čase)
        mutableBlocks.sortBy { it.startTimeMs }
        
        val newSelectedIndex = mutableBlocks.indexOfFirst { it === mutableBlocks[index] }
        _uiState.value = current.copy(
            srtBlocks = mutableBlocks,
            selectedBlockIndex = newSelectedIndex.takeIf { it != -1 } ?: current.selectedBlockIndex
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Proxy Video Pipeline (WorkManager → StateFlow)
    // ─────────────────────────────────────────────────────────────────────────

    fun startProxyGeneration(context: Context, videoUri: Uri) {
        val proxyFile = File(context.cacheDir, "proxy_${videoUri.lastPathSegment}.mp4")

        // Idempotence: existující proxy → okamžitě použij, nespouštěj Worker znovu
        if (proxyFile.exists() && proxyFile.length() > 0L) {
            val s = readyState ?: return
            _uiState.value = s.copy(
                proxyState = com.example.llama.srt.ui.models.ProxyState.READY,
                proxyUriString = proxyFile.absolutePath
            )
            Log.i(TAG, "Proxy nalezena v cache: ${proxyFile.absolutePath}")
            return
        }

        val s = readyState ?: return
        _uiState.value = s.copy(proxyState = com.example.llama.srt.ui.models.ProxyState.GENERATING)

        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.llama.srt.proxy.ProxyWorker>()
            .setInputData(
                androidx.work.workDataOf(
                    com.example.llama.srt.proxy.ProxyWorker.KEY_INPUT_URI to videoUri.toString(),
                    com.example.llama.srt.proxy.ProxyWorker.KEY_OUTPUT_PATH to proxyFile.absolutePath
                )
            )
            .build()

        androidx.work.WorkManager.getInstance(context).enqueue(workRequest)

        // Flow API — bezpečné v ViewModel bez lifecycle leaku
        viewModelScope.launch {
            androidx.work.WorkManager.getInstance(context)
                .getWorkInfoByIdFlow(workRequest.id)
                .collect { workInfo ->
                    val current = readyState ?: return@collect
                    when (workInfo?.state) {
                        androidx.work.WorkInfo.State.RUNNING -> {
                            val progress = workInfo.progress.getInt("progress", 0)
                            _uiState.value = current.copy(
                                proxyState = com.example.llama.srt.ui.models.ProxyState.GENERATING,
                                proxyProgressPercent = progress
                            )
                        }
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            _uiState.value = current.copy(
                                proxyState = com.example.llama.srt.ui.models.ProxyState.READY,
                                proxyUriString = proxyFile.absolutePath
                            )
                            Log.i(TAG, "Proxy hotova: ${proxyFile.absolutePath}")
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            Log.e(TAG, "Worker selhal — editor bude použit s originálním souborem.")
                            _uiState.value = current.copy(
                                proxyState = com.example.llama.srt.ui.models.ProxyState.FAILED
                            )
                        }
                        else -> {}
                    }
                }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Přehrávání & Sync Loop
    // ─────────────────────────────────────────────────────────────────────────

    fun startPlaybackSyncLoop(getPositionMs: () -> Long) {
        syncLoopJob?.cancel()
        syncLoopJob = viewModelScope.launch(Dispatchers.Main) {
            while (isActive) {
                val state = readyState
                if (state != null && state.isPlaying) {
                    val currentPos = getPositionMs()
                    _uiState.value = state.copy(currentPositionMs = currentPos)
                }
                delay(SYNC_INTERVAL_MS)
            }
        }
    }

    fun stopPlaybackSyncLoop() {
        syncLoopJob?.cancel()
        syncLoopJob = null
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fyzický zápis .SRT souboru (Zero-Wear HW I/O)
    // ─────────────────────────────────────────────────────────────────────────

    fun saveSrtFile(onSuccess: () -> Unit, onError: (String) -> Unit) {
        val state = readyState
        val path = originalSrtFilePath
        if (state == null || path == null) {
            onError("Chybí kontext pro uložení SRT.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. FÁZE: Výpočet a alokace čistě v RAM (Zero I/O)
                // Odhadneme kapacitu, abychom předešli realokacím pole (cca 100 bytů per blok)
                val estimatedSize = state.srtBlocks.size * 100
                val srtBuilder = StringBuilder(estimatedSize)
                
                state.srtBlocks.forEachIndexed { index, block ->
                    srtBuilder.append(index + 1).append('\n')
                    srtBuilder.append(formatSrtTime(block.startTimeMs)).append(" --> ").append(formatSrtTime(block.endTimeMs)).append('\n')
                    srtBuilder.append(block.text).append("\n\n")
                }
                
                // Konverze do pole bajtů přímo v paměti
                val rawBytes = srtBuilder.toString().toByteArray(Charsets.UTF_8)

                // 2. FÁZE: Atomický blokový zápis na disk (Ochrana NAND Flash)
                val file = File(path)
                FileOutputStream(file).use { out ->
                    out.write(rawBytes) // Zapsáno VŠECHNO najednou (1 I/O call)
                    out.fd.sync()       // Hardwarový flush do buňky filesystému (1 fsync call)
                }

                withContext(Dispatchers.Main) {
                    onSuccess()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při ukládání SRT: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    onError(e.message ?: "Neznámá chyba zápisu IO.")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export Kontrakty (Video Burn-in)
    // ─────────────────────────────────────────────────────────────────────────

    fun onExportProgress(percent: Int) {
        val state = readyState ?: return
        _uiState.value = state.copy(exportState = ExportState.InProgress(percent))
    }

    fun onExportDone(outputPath: String) {
        val state = readyState ?: return
        _uiState.value = state.copy(exportState = ExportState.Done(outputPath))
    }

    fun onExportFailed(reason: String) {
        val state = readyState ?: return
        _uiState.value = state.copy(exportState = ExportState.Failed(reason))
    }

    private fun cancelExport() {
        exportJob?.cancel()
        _uiState.value = readyState?.copy(exportState = ExportState.Idle) ?: return
    }

    override fun onCleared() {
        super.onCleared()
        syncLoopJob?.cancel()
        exportJob?.cancel()
        pcmProxyJob?.cancel()

        val state = readyState

        // PCM proxy cleanup
        state?.pcmProxyPath?.let { path ->
            try {
                val f = File(path)
                if (f.exists()) { f.delete(); Log.i(TAG, "PCM proxy smazán: $path") }
            } catch (e: Exception) { Log.e(TAG, "Chyba mazání PCM: ${e.message}") }
        }

        // Video proxy cleanup (cacheDir — OS může mazat sám, ale explicitní mazání = deterministické)
        state?.proxyUriString?.let { path ->
            try {
                val f = File(path)
                if (f.exists() && f.name.startsWith("proxy_")) {
                    f.delete()
                    Log.i(TAG, "Video proxy smazán: $path")
                }
            } catch (e: Exception) { Log.e(TAG, "Chyba mazání Video proxy: ${e.message}") }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: SRT čas formátování (ms → "HH:MM:SS,mmm")
    // ─────────────────────────────────────────────────────────────────────────

    private fun formatSrtTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h      = clamped / 3_600_000
        val m      = (clamped % 3_600_000) / 60_000
        val s      = (clamped % 60_000) / 1000
        val millis = clamped % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
    }
}
