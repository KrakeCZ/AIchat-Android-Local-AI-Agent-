package com.example.llama.srt.ui.models

import com.example.llama.srt.SrtBlock

// ─────────────────────────────────────────────────────────────────────────────
// Sealed UI state — single source of truth for SrtEditorActivity
// ─────────────────────────────────────────────────────────────────────────────

sealed class SrtEditorUiState {

    enum class ActiveStudio { NONE, VIDEO, AUDIO, SUBTITLE }

    data class AudioTrackConfig(
        val streamIndex: Int,
        val isSelected: Boolean = false,
        val volumeGain: Float = 1.0f,
        val ebuR128Enabled: Boolean = false,
        val compressorEnabled: Boolean = false
    )

    data class Ready(
        val videoUriString: String,
        val srtBlocks: List<SrtBlock>,
        val videoFps: Float = 30f,
        val selectedBlockIndex: Int = -1,         // -1 = none selected
        val currentPositionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isPlaying: Boolean = false,
        val styleConfig: SubtitleStyleConfig = SubtitlePresets.getPreset(SubtitlePresetId.TIKTOK_WORD_POP),
        val activeTab: EditorTab = EditorTab.TIMELINE,
        val activeStudio: ActiveStudio = ActiveStudio.NONE,
        val audioStreamCount: Int = 1,
        val selectedAudioStream: Int = 0, // Používá se pro extrakci PCM vlny (hlavní stopa)
        val audioTracks: List<AudioTrackConfig> = emptyList(), // Nové DSP parametry pro mixér
        val exportState: ExportState = ExportState.Idle,
        val exportConfig: ExportConfig = ExportConfig(),
        /** Absolutní cesta k extrahovánému PCM .raw souboru pro vizualizaci AudioTrack (A1).
         *  null = extrakce ještě neprobíhá nebo selhala → A1 zobrazí dummy wave jako fallback. */
        val pcmProxyPath: String? = null,
        val proxyUriString: String? = null,
        val proxyState: ProxyState = ProxyState.PENDING,
        val proxyProgressPercent: Int = 0,
    ) : SrtEditorUiState()

    /** Initial / loading state */
    object Loading : SrtEditorUiState()

    /** Non-recoverable error */
    data class Error(val message: String) : SrtEditorUiState()
}

// Nastavení rychlosti vs. zahřívání pro záložku EXPORT
enum class ExportThermalMode(val displayName: String, val ffmpegThreads: Int, val preset: String) {
    MAX_SPEED("Maximální rychlost (Zahřívá telefon)", 0, "ultrafast"),
    BALANCED("Vyvážené (Optimalizováno)", 4, "veryfast"),
    COOL("Pomalé a Chladné (Na slunci/Léto)", 2, "faster")
}

data class ExportConfig(
    val thermalMode: ExportThermalMode = ExportThermalMode.BALANCED,
    val forceSoftware: Boolean = false // Umožní uživateli úmyslně vypnout HW, pokud zlobí
)

// ─────────────────────────────────────────────────────────────────────────────
// Editor tab indices
// ─────────────────────────────────────────────────────────────────────────────

enum class ProxyState {
    PENDING,
    GENERATING,
    READY,
    FAILED,
    NOT_NEEDED
}

enum class EditorTab(val labelRes: String) {
    TIMELINE("Timeline"),
    STYLE("Styl"),
    TEXT("Text"),
    POSITION("Pozice"),
    ANIMATION("Animace"),
    FONT("Písmo"),
    EXPORT("Export")
}

// ─────────────────────────────────────────────────────────────────────────────
// Export state machine
// ─────────────────────────────────────────────────────────────────────────────

sealed class ExportState {
    object Idle : ExportState()
    data class InProgress(val progressPercent: Int) : ExportState()
    data class Done(val outputPath: String) : ExportState()
    data class Failed(val reason: String) : ExportState()
}

// ─────────────────────────────────────────────────────────────────────────────
// Intent events fired from UI → ViewModel
// ─────────────────────────────────────────────────────────────────────────────

sealed class SrtEditorEvent {
    data class SelectBlock(val index: Int) : SrtEditorEvent()
    data class UpdateBlockText(val index: Int, val newText: String) : SrtEditorEvent()
    data class TrimBlockStart(val index: Int, val newStartMs: Long) : SrtEditorEvent()
    data class TrimBlockEnd(val index: Int, val newEndMs: Long) : SrtEditorEvent()
    data class SeekTo(val positionMs: Long) : SrtEditorEvent()
    data class SetPlaying(val playing: Boolean) : SrtEditorEvent()
    data class ApplyStyle(val config: SubtitleStyleConfig) : SrtEditorEvent()
    data class SwitchTab(val tab: EditorTab) : SrtEditorEvent()
    object StartExport : SrtEditorEvent()
    object CancelExport : SrtEditorEvent()
    data class UpdateExportConfig(val config: ExportConfig) : SrtEditorEvent()
    // Drag reposition: update posYPercent in styleConfig
    data class RepositionSubtitle(val newPosYPercent: Float) : SrtEditorEvent()
    data class ProxyStatusChanged(val newState: ProxyState, val proxyPath: String? = null) : SrtEditorEvent()
    data class LoadAudioStream(val streamIndex: Int) : SrtEditorEvent()
    data class UpdateAudioTrackConfig(val config: SrtEditorUiState.AudioTrackConfig) : SrtEditorEvent()
    object AutoFixVfr : SrtEditorEvent()
}
