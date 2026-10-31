package com.example.llama

import com.arm.aichat.isModelLoaded
import com.example.llama.agent.core.NlpEngineMode
import kotlinx.coroutines.flow.first

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.llama.agent.perception.SherpaModelManager
import com.example.llama.agent.perception.WhisperModelManager
import com.example.llama.agent.perception.audio.AsyncWavMuxer
import com.example.llama.agent.service.AiAgentAccessibilityService
import com.example.llama.agent.voice.sherpa.SherpaTtsEngine
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Audio Studio 2026 — Standalone produkční aktivita.
 *
 * ARCHITEKTURA:
 * - AudioStudioEngineGate.acquire() v onCreate() — blokuje TranscriptAgent
 * - AudioStudioEngineGate.release() v onDestroy() s 500ms C++ debounce
 * - Diktafon: MIC (ProfessionalDictaphoneEngine) nebo SYSTEM AUDIO (MediaProjection)
 * - ZipVoice: import WAV/MP3 reference → settingsManager → SherpaTtsEngine test
 * - Model selector: SherpaModelManager.getImportedModels() — jen Sherpa + Whisper
 * - VU Metr: reálná RMS amplitude ze sharedBuffer (ne random())
 */
class AudioStudioActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AudioStudioActivity"
        /** Fyzický flush na disk každých N segmentů — minimální NAND write amplification */
        private const val TRANSCRIPT_FLUSH_EVERY = 10
    }

    private lateinit var settingsManager: SettingsManager
    private var studioTtsEngine: SherpaTtsEngine? = null
    private var vuMeterJob: Job? = null
    private var zipVoicePlayer: MediaPlayer? = null

    // ---- ZipVoice Recording ----
    private var isRecordingPrompt = false
    private var promptAudioRecord: android.media.AudioRecord? = null
    private var promptRecordingThread: Thread? = null

    // ---- UI views ----
    private lateinit var toolbar: Toolbar
    private lateinit var switchAec: SwitchMaterial
    private lateinit var switchNs: SwitchMaterial
    private lateinit var switchAgc: SwitchMaterial
    private lateinit var btnOpenMixer: com.google.android.material.button.MaterialButton
    private lateinit var switchRawBypass: SwitchMaterial
    private lateinit var switchDualStream: SwitchMaterial
    private lateinit var btnRecordMaster: MaterialButton
    private lateinit var btnPauseDictaphone: MaterialButton
    private lateinit var btnStopDictaphone: MaterialButton
    private lateinit var layoutRecordControls: android.widget.LinearLayout
    private lateinit var progressVuMeter: com.example.llama.ui.views.ProfessionalVuMeterView
    private lateinit var btnVuTheme: android.widget.ImageButton
    private lateinit var tvRecordTimer: TextView
    private lateinit var switchLiveStt: SwitchMaterial
    private lateinit var rvDictaphoneFiles: RecyclerView
    private lateinit var tvActiveStt: TextView
    private lateinit var tvActiveTts: TextView
    private lateinit var spinnerAudioSource: Spinner
    private lateinit var tvZipVoiceStatus: TextView
    private lateinit var spinnerTranscriptEngine: Spinner
    private lateinit var chipgroupPriorityStudio: com.google.android.material.chip.ChipGroup
    private lateinit var chipgroupAccelerator: com.google.android.material.chip.ChipGroup
    private lateinit var chipgroupAsrAccelerator: com.google.android.material.chip.ChipGroup
    private lateinit var tvAsrAcceleratorLabel: TextView
    private lateinit var tvStudioPriorityWarning: TextView
    @Volatile
    private var currentStudioPriority: AudioStudioEngineGate.ThreadPriorityMode =
            AudioStudioEngineGate.ThreadPriorityMode.DEFAULT
    private lateinit var etZipVoiceRefText: EditText
    private lateinit var etZipVoiceChunkSize: EditText
    private lateinit var etZipVoiceTestText: EditText
    private lateinit var btnZipVoiceImport: MaterialButton
    private lateinit var btnZipVoiceRecordMic10s: MaterialButton
    private lateinit var btnZipVoiceRecordMicDyn: MaterialButton
    private lateinit var btnZipVoicePlayRef: ImageButton
    private lateinit var btnZipVoiceTest: MaterialButton
    private lateinit var btnZipVoiceSaveClone: MaterialButton
    private lateinit var btnSelectStt: MaterialButton
    private lateinit var btnSelectTts: MaterialButton
    private lateinit var btnImportMedia: MaterialButton
    private lateinit var btnGenerateSrt: MaterialButton
    private lateinit var rvAudioFiles: RecyclerView
    private lateinit var tvDictaphoneTranscript: TextView
    private lateinit var tvDictaphonePartial: TextView
    private lateinit var svDictaphoneTranscript: androidx.core.widget.NestedScrollView
    private lateinit var btnDictaphoneAiSummary: MaterialButton
    private lateinit var llTranscriptToolbar: android.widget.LinearLayout
    private lateinit var btnTranscriptCopy: MaterialButton
    private lateinit var btnTranscriptEdit: MaterialButton
    private lateinit var btnTranscriptShare: MaterialButton

    // ---- NLP Quick panel nad transkriptem ----
    private lateinit var btnNlpSummarizeStudio: com.google.android.material.button.MaterialButton
    private lateinit var btnNlpProofreadStudio: com.google.android.material.button.MaterialButton
    private lateinit var btnNlpTranslateStudio: com.google.android.material.button.MaterialButton
    private lateinit var btnNlpRewriteStudio: com.google.android.material.button.MaterialButton
    private lateinit var btnNlpSentimentStudio: com.google.android.material.button.MaterialButton
    private lateinit var tvNlpResultStudio: android.widget.TextView
    private lateinit var progressNlpStudio: android.widget.ProgressBar
    private var studioNlpEngineChipGroup: com.google.android.material.chip.ChipGroup? = null

    /** Lazy NlpActionHandler — inicializuje se při první NLP akci. */
    private val studioNlpHandler by lazy {
        com.example.llama.agent.core.NlpActionHandler(applicationContext, settingsManager)
    }
    /** Zrcadlo AI šablon z Diktafonu — dostupné i v Toolbaru (na existujících lazy enginech) */
    private var btnTranscriptAiTemplates: com.google.android.material.button.MaterialButton? = null
    private lateinit var recordingsAdapter: AudioRecordingsAdapter
    // D1: SRT generování z diktafonu
    private lateinit var switchDictaphoneSrt:
            com.google.android.material.switchmaterial.SwitchMaterial
    private val liveSrtBlocks = mutableListOf<SrtBlock>()
    @Volatile private var dictaphoneStartTimeMs = 0L
    private val dictaphoneRamBuffer = StringBuilder()
    private var isRealtimeDiskWriteEnabled = false
    private val liveSrtEngine =
            com.example.llama.srt.SegmentationEngineFactory.fromSpinnerIndex(1, 7)

    // VU Monitor
    private var isVuMeterEnabled = true

    // AI Meeting Notes
    private val aiCoreEngine by lazy { com.example.llama.agent.perception.AiCoreMasterEngine.getInstance(this) }
    private val nlpSummaryWorker by lazy {
        com.example.llama.agent.perception.NlpSummaryWorker(this, settingsManager)
    }
    private val meetingRefiner by lazy {
        com.example.llama.agent.perception.nlp.MeetingIntelligenceRefiner(
                aiCoreEngine,
                nlpSummaryWorker,
                applicationContext
        )
    }
    private val exportFactory by lazy {
        com.example.llama.agent.perception.export.MediaExportFactory(
                com.example.llama.managers.FileSystemManager(this)
        )
    }

    // Import ZipVoice reference
    private val importZipVoiceLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let { saveZipVoicePrompt(it) }
            }

    private var pendingExportFile: File? = null

    private val exportZipVoicePromptLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
                uri?.let { destUri ->
                    val sourcePath = settingsManager.sherpaTtsCustomPromptPath
                    if (sourcePath.isNotBlank() && File(sourcePath).exists()) {
                        try {
                            contentResolver.openOutputStream(destUri)?.use { outStream ->
                                File(sourcePath).inputStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            Toast.makeText(this, "Prompt úspěšně exportován", Toast.LENGTH_SHORT)
                                    .show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                            this,
                                            "Chyba při exportu: ${e.message}",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                    }
                }
            }

    private val exportAudioLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
                uri?.let { destUri ->
                    val sourceFile = pendingExportFile
                    if (sourceFile != null && sourceFile.exists()) {
                        try {
                            contentResolver.openOutputStream(destUri)?.use { outStream ->
                                sourceFile.inputStream().use { inStream ->
                                    inStream.copyTo(outStream)
                                }
                            }
                            Toast.makeText(this, "Soubor úspěšně exportován", Toast.LENGTH_SHORT)
                                    .show()
                        } catch (e: Exception) {
                            Toast.makeText(
                                            this,
                                            "Chyba při exportu: ${e.message}",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                    }
                }
            }

    private fun saveZipVoicePrompt(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                val outDir = File(filesDir, "zipvoice").also { it.mkdirs() }
                val timestamp =
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                val outFile = File(outDir, "imported_$timestamp.wav")
                FileOutputStream(outFile).use { outputStream -> inputStream.copyTo(outputStream) }
                settingsManager.sherpaTtsCustomPromptPath = outFile.absolutePath
                Toast.makeText(this, "Reference úspěšně importována", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba importu", Toast.LENGTH_SHORT).show()
        }
    }

    // Import média — SrtEditorActivity
    private val importMediaLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                uri?.let {
                    startActivity(
                        Intent(this, com.example.llama.SrtEditorActivity::class.java).apply {
                            putExtra("VIDEO_URI", it.toString())
                            putExtra("SRT_PATH", "")
                        }
                    )
                }
            }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i(TAG, "✅ Oprávnění RECORD_AUDIO uděleno")
        } else {
            Toast.makeText(this, "⚠️ Nahrávání audia zamítnuto!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun setupFullscreen() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (settingsManager.hideStatusBar) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // ========================================================================
    /** Zda je aktuálně nahráváno (lokální stav) */
    private var isStudioRecording = false
    /** Zda je nahrávání pozastaveno */
    private var isDictaphonePaused = false
    private var activeMuxer: AsyncWavMuxer? = null
    /** True pokud jsme my spustili FG Service (ne Agent) — musíme ji také ukončit */
    private var weStartedFgService = false
    /** True pokud jsme my spustili TranscriptAgent pro MIC flow — musíme ho také zastavit */
    private var weStartedAgent = false
    /** True pokud agent běžel už před spuštěním diktafonu — při ukončení ho obnovíme */
    private var agentWasRunningBeforeDictaphone = false
    /**
     * Receiver pro System Audio — čeká na ACTION_SYSTEM_AUDIO_READY z TranscriptForegroundService
     */
    private var systemAudioReadyReceiver: android.content.BroadcastReceiver? = null
    /** Temp soubor pro pr�b�n� zápis p�episu v re�ln�m �ase (Dual-Buffer committed text) */
    private var transcriptTempFile: File? = null

    /**
     * Zero-lag transcript I/O pipeline — produkční konfigurace (04/2026):
     *
     * Channel(64, DROP_OLDEST):
     * - Bounded — žádné OOM riziko (Channel.UNLIMITED je anti-pattern pro Android prod.)
     * - DROP_OLDEST: pokud IO nestíhá 64 vět — nejstarší pending se zahodí
     * ```
     *     (přijatelné pro real-time transcript: ztráta 1 věty >>> OOM crash)
     * ```
     * - trySend() = O(1), non-blocking, no alloc, žádné coroutine launch
     *
     * limitedParallelism(1):
     * - Garantované jedno IO vlákno — žádné thread-pool overhead
     * - BufferedWriter(8192B) � OS page cache absorbuje zápisy
     * - flush() každých 10 segmentů — minimální NAND write amplification
     */
    private var transcriptChannel =
            Channel<String>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var transcriptWriterJob: Job? = null
    /** Singleton IO dispatcher pro transcript writer — 1 vlákno, bez thread-pool overhead */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val transcriptIoDispatcher = Dispatchers.IO.limitedParallelism(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_studio)

        settingsManager = SettingsManager(this)

        setupFullscreen()
        bindViews()

        // KRITICKÉ: exportFactory (FileSystemManager) volá registerForActivityResult()
        // uvnitř svého konstruktoru. ActivityResultRegistry.register() MUSÍ být voláno
        // před onStart() (stav STARTED). By lazy by to inicializoval až při prvním použití
        // uvnitř coroutine (stav RESUMED) → IllegalStateException.
        // Řešení: vynutit inicializaci hned v onCreate() "dotykem" lazy property.
        @Suppress("UNUSED_EXPRESSION")
        exportFactory

        // Odstran�n AudioStudioEngineGate.acquire() - Agent nyní může běžet souběžně

        setupToolbar()
        setupDspSwitches()
        setupTranscriptEngineSpinner()
        setupAudioSourceSpinner()
        setupPriorityChipGroup() // Fáze B: Priority pro studio operace
        setupAcceleratorChipGroup() // HW Akcelerace (CPU/GPU/NPU) pro Vision
        setupAsrAcceleratorChipGroup() // HW Akcelerace pro Whisper/Sherpa
        setupRecordButton()
        setupModelSelector()
        setupZipVoice()
        setupRecordingsList()
        setupMediaButtons()
        setupVuMeter()

        onResume()
    }

    override fun onResume() {
        super.onResume()
        mirrorModels()
        updateZipVoiceStatus()
        loadRecordingsList()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("DICTAPHONE_TRANSCRIPT", tvDictaphoneTranscript.text.toString())
        outState.putLong("DICTAPHONE_START_TIME", dictaphoneStartTimeMs)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedTranscript = savedInstanceState.getString("DICTAPHONE_TRANSCRIPT", "")
        tvDictaphoneTranscript.text = savedTranscript
        dictaphoneStartTimeMs = savedInstanceState.getLong("DICTAPHONE_START_TIME", 0L)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVuMeter()
        studioTtsEngine?.shutdown()
        studioTtsEngine = null
        zipVoicePlayer?.release()
        zipVoicePlayer = null
        if (isStudioRecording) stopDictaphone()
        unregisterSysAudioReceiver()
        recordingsAdapter.releasePlayer()
        meetingRefiner.cleanup()
    }

    // ========================================================================
    // BINDING
    // ========================================================================

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_audio_studio)
        switchAec = findViewById(R.id.switch_aec)
        switchNs = findViewById(R.id.switch_ns)
        switchAgc = findViewById(R.id.switch_agc)
        btnOpenMixer = findViewById(R.id.btn_open_mixer)
        switchRawBypass = findViewById(R.id.switch_raw_bypass)
        switchDualStream = findViewById(R.id.switch_dual_stream)
        btnRecordMaster = findViewById(R.id.btn_record_master)
        btnPauseDictaphone = findViewById(R.id.btn_pause_dictaphone)
        btnStopDictaphone = findViewById(R.id.btn_stop_dictaphone)
        layoutRecordControls = findViewById(R.id.layout_record_controls)
        progressVuMeter = findViewById(R.id.progress_vu_meter)
        btnVuTheme = findViewById(R.id.btn_vu_theme)
        tvRecordTimer = findViewById(R.id.tv_record_timer)
        switchLiveStt = findViewById(R.id.switch_live_stt)
        rvDictaphoneFiles = findViewById(R.id.rv_dictaphone_files)
        tvActiveStt = findViewById(R.id.tv_active_stt)
        tvActiveTts = findViewById(R.id.tv_active_tts)
        spinnerAudioSource = findViewById(R.id.spinner_audio_source)
        tvZipVoiceStatus = findViewById(R.id.tv_zipvoice_status)
        spinnerTranscriptEngine = findViewById(R.id.spinner_transcript_engine)
        etZipVoiceRefText = findViewById(R.id.et_zipvoice_ref_text)
        etZipVoiceChunkSize = findViewById(R.id.et_zipvoice_chunk_size)
        etZipVoiceTestText = findViewById(R.id.et_zipvoice_test_text)
        btnZipVoiceImport = findViewById(R.id.btn_zipvoice_import)
        btnZipVoiceRecordMic10s = findViewById(R.id.btn_zipvoice_record_mic_10s)
        btnZipVoiceRecordMicDyn = findViewById(R.id.btn_zipvoice_record_mic_dyn)
        btnZipVoicePlayRef = findViewById(R.id.btn_zipvoice_play_ref)
        btnZipVoiceTest = findViewById(R.id.btn_zipvoice_test)
        btnZipVoiceSaveClone = findViewById(R.id.btn_zipvoice_save_clone)
        btnSelectStt = findViewById(R.id.btn_select_stt)
        btnSelectTts = findViewById(R.id.btn_select_tts)
        btnImportMedia = findViewById(R.id.btn_import_media)
        btnGenerateSrt = findViewById(R.id.btn_generate_srt)
        rvAudioFiles = findViewById(R.id.rv_audio_files)
        tvDictaphoneTranscript = findViewById(R.id.tv_dictaphone_transcript)
        tvDictaphonePartial = findViewById(R.id.tv_dictaphone_partial)
        svDictaphoneTranscript = findViewById(R.id.sv_dictaphone_transcript)
        btnDictaphoneAiSummary = findViewById(R.id.btn_dictaphone_ai_summary)
        llTranscriptToolbar    = findViewById(R.id.ll_transcript_toolbar)
        // NLP Quick panel
        btnNlpSummarizeStudio  = findViewById(R.id.btn_nlp_summarize_studio)
        btnNlpProofreadStudio  = findViewById(R.id.btn_nlp_proofread_studio)
        btnNlpTranslateStudio  = findViewById(R.id.btn_nlp_translate_studio)
        btnNlpRewriteStudio    = findViewById(R.id.btn_nlp_rewrite_studio)
        btnNlpSentimentStudio  = findViewById(R.id.btn_nlp_sentiment_studio)
        tvNlpResultStudio      = findViewById(R.id.tv_nlp_result_studio)
        progressNlpStudio      = findViewById(R.id.progress_nlp_studio)
        btnTranscriptCopy = findViewById(R.id.btn_transcript_copy)
        btnTranscriptEdit = findViewById(R.id.btn_transcript_edit)
        btnTranscriptShare = findViewById(R.id.btn_transcript_share)
        chipgroupPriorityStudio = findViewById(R.id.chipgroup_priority_studio)
        chipgroupAccelerator = findViewById(R.id.chipgroup_accelerator)
        chipgroupAsrAccelerator = findViewById(R.id.chipgroup_asr_accelerator)
        tvAsrAcceleratorLabel = findViewById(R.id.tv_asr_accelerator_label)
        tvStudioPriorityWarning = findViewById(R.id.tv_studio_priority_warning)
        switchDictaphoneSrt = findViewById(R.id.switch_dictaphone_srt)
    }

    // ========================================================================
    // TOOLBAR
    // ========================================================================

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupVuMeter() {
        btnVuTheme.setOnClickListener {
            val current = progressVuMeter.currentTheme
            progressVuMeter.currentTheme = if (current == com.example.llama.ui.views.ProfessionalVuMeterView.Theme.CLASSIC) {
                com.example.llama.ui.views.ProfessionalVuMeterView.Theme.MODERN
            } else {
                com.example.llama.ui.views.ProfessionalVuMeterView.Theme.CLASSIC
            }
        }
        
        progressVuMeter.setOnLongClickListener {
            isVuMeterEnabled = !isVuMeterEnabled
            if (!isVuMeterEnabled) {
                progressVuMeter.updateDb(-60f, -60f)
            }
            android.widget.Toast.makeText(
                this, 
                if (isVuMeterEnabled) "VU Metr: Aktivní" else "VU Metr: Skrytý", 
                android.widget.Toast.LENGTH_SHORT
            ).show()
            true
        }
    }

    // ========================================================================
    // PRIORITY CHIP GROUP — AudioStudio (F�ze B)
    // ========================================================================

    private fun setupAcceleratorChipGroup() {
        // Obnova aktuálního stavu z nastavení
        val currentMode = settingsManager.perceptionAcceleratorMode
        when (currentMode) {
            "AUTO" -> chipgroupAccelerator.check(R.id.chip_accel_auto)
            "CPU" -> chipgroupAccelerator.check(R.id.chip_accel_cpu)
            "GPU" -> chipgroupAccelerator.check(R.id.chip_accel_gpu)
            "NNAPI" -> chipgroupAccelerator.check(R.id.chip_accel_npu) // Ukládá se jako NNAPI, v UI píšeme NPU
            else -> chipgroupAccelerator.check(R.id.chip_accel_auto)
        }

        chipgroupAccelerator.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(R.id.chip_accel_auto) -> "AUTO"
                checkedIds.contains(R.id.chip_accel_cpu) -> "CPU"
                checkedIds.contains(R.id.chip_accel_gpu) -> "GPU"
                checkedIds.contains(R.id.chip_accel_npu) -> "NNAPI"
                else -> "AUTO"
            }
            settingsManager.perceptionAcceleratorMode = mode
            android.util.Log.i(TAG, "🤖 Změněn HW Akcelerátor na: $mode")
        }
    }

    private fun setupPriorityChipGroup() {
        chipgroupPriorityStudio.setOnCheckedStateChangeListener { _, checkedIds ->
            currentStudioPriority =
                    when {
                        checkedIds.contains(R.id.chip_studio_priority_urgent) ->
                                AudioStudioEngineGate.ThreadPriorityMode.URGENT
                        checkedIds.contains(R.id.chip_studio_priority_bg) ->
                                AudioStudioEngineGate.ThreadPriorityMode.BACKGROUND
                        else -> AudioStudioEngineGate.ThreadPriorityMode.DEFAULT
                    }
            tvStudioPriorityWarning.visibility =
                    if (currentStudioPriority == AudioStudioEngineGate.ThreadPriorityMode.URGENT)
                            View.VISIBLE
                    else View.GONE
            android.util.Log.d(TAG, "Studio priority přepnuta: ${currentStudioPriority.label}")
        }
    }

    // ========================================================================
    // DSP SWITCHES
    // ========================================================================

    private fun setupDspSwitches() {
        // Diagnostika HW DSP dostupnosti
        val dspChain = com.example.llama.agent.perception.DspFilterChain()
        val avail = dspChain.queryAvailability()
        android.util.Log.i(TAG, "$avail")

        // Nastavení dostupnosti přepínačů dle HW
        switchAec.isEnabled = avail.aecAvailable
        switchNs.isEnabled = avail.nsAvailable
        switchAgc.isEnabled = avail.agcAvailable

        // Výchozí stav: vše zapnuto (pokud HW podporuje)
        switchAec.isChecked = avail.aecAvailable
        switchNs.isChecked = avail.nsAvailable
        switchAgc.isChecked = avail.agcAvailable
        switchRawBypass.isChecked = false

        // Popis nedostupných efektů
        if (!avail.aecAvailable) switchAec.text = "AEC ?"
        if (!avail.nsAvailable) switchNs.text = "NS ?"
        if (!avail.agcAvailable) switchAgc.text = "AGC ?"

        // Propagace stavu do TranscriptAgent
        fun pushDspConfig() {
            val oldConfig = com.example.llama.agent.perception.TranscriptAgent.dspConfig
            val config = oldConfig.copy(
                    aecEnabled = switchAec.isChecked && !switchRawBypass.isChecked,
                    nsEnabled = switchNs.isChecked && !switchRawBypass.isChecked,
                    agcEnabled = switchAgc.isChecked && !switchRawBypass.isChecked
            )
            com.example.llama.agent.perception.TranscriptAgent.dspConfig = config
            com.example.llama.agent.perception.TranscriptAgent.instance?.updateRuntimeSettings(config)
        }

        // Inicializace config
        pushDspConfig()
        
        btnOpenMixer.setOnClickListener {
            com.example.llama.agent.ui.MixerOverlayPopup(this, this).show()
        }

        switchAec.setOnCheckedChangeListener { _, _ -> pushDspConfig() }
        switchNs.setOnCheckedChangeListener { _, _ -> pushDspConfig() }
        switchAgc.setOnCheckedChangeListener { _, _ -> pushDspConfig() }
        switchRawBypass.setOnCheckedChangeListener { _, checked ->
            listOf(switchAec, switchNs, switchAgc).forEach {
                it.isEnabled =
                        !checked &&
                                when (it) {
                                    switchAec -> avail.aecAvailable
                                    switchNs -> avail.nsAvailable
                                    switchAgc -> avail.agcAvailable
                                    else -> true
                                }
            }
            if (checked) {
                switchAec.isChecked = false
                switchNs.isChecked = false
                switchAgc.isChecked = false
            } else {
                switchAec.isChecked = avail.aecAvailable
                switchNs.isChecked = avail.nsAvailable
                switchAgc.isChecked = avail.agcAvailable
            }
            pushDspConfig()
        }
    }

    // ========================================================================
    // ASR ENGINE SPINNER
    // ========================================================================

    private fun setupTranscriptEngineSpinner() {
        val engines = arrayOf("Android (Online)", "Whisper (Offline)", "Sherpa-ONNX (Offline)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engines)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTranscriptEngine.adapter = adapter

        val currentEngine = settingsManager.transcriptEngine
        val selectedIndex =
                when (currentEngine) {
                    "android" -> 0
                    "whisper" -> 1
                    "sherpa" -> 2
                    else -> 0
                }
        spinnerTranscriptEngine.setSelection(selectedIndex)

        spinnerTranscriptEngine.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    var initialized = false
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: android.view.View?,
                            pos: Int,
                            id: Long
                    ) {
                        if (!initialized) {
                            initialized = true
                            return
                        }
                        val newEngine =
                                when (pos) {
                                    0 -> "android"
                                    1 -> "whisper"
                                    2 -> "sherpa"
                                    else -> "android"
                                }
                        if (settingsManager.transcriptEngine != newEngine) {
                            settingsManager.transcriptEngine = newEngine
                            Toast.makeText(
                                            this@AudioStudioActivity,
                                            "ASR Engine přepnut na: $newEngine",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                        }
                        
                        // Toggle ASR accelerator visibility
                        if (pos == 0) {
                            tvAsrAcceleratorLabel.visibility = View.GONE
                            chipgroupAsrAccelerator.visibility = View.GONE
                        } else {
                            tvAsrAcceleratorLabel.visibility = View.VISIBLE
                            chipgroupAsrAccelerator.visibility = View.VISIBLE
                            // Sync checking
                            val currentAsrMode = if (pos == 1) settingsManager.whisperAcceleratorMode else settingsManager.sherpaAcceleratorMode
                            when (currentAsrMode) {
                                "CPU" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_cpu)
                                "GPU" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_gpu)
                                "NNAPI" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_npu)
                                else -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_cpu)
                            }
                            findViewById<android.view.View>(R.id.chip_asr_accel_npu)?.visibility = if (pos == 1) android.view.View.GONE else android.view.View.VISIBLE
                        }
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    private fun setupAsrAcceleratorChipGroup() {
        // Initialize the UI to match the current backend
        val isWhisper = settingsManager.transcriptEngine == "whisper"
        val currentAsrMode = if (isWhisper) {
            settingsManager.whisperAcceleratorMode
        } else {
            settingsManager.sherpaAcceleratorMode
        }
        
        findViewById<android.view.View>(R.id.chip_asr_accel_npu)?.visibility = if (isWhisper) android.view.View.GONE else android.view.View.VISIBLE
        
        when (currentAsrMode) {
            "CPU" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_cpu)
            "GPU" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_gpu)
            "NNAPI" -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_npu)
            else -> chipgroupAsrAccelerator.check(R.id.chip_asr_accel_cpu)
        }

        chipgroupAsrAccelerator.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when {
                checkedIds.contains(R.id.chip_asr_accel_cpu) -> "CPU"
                checkedIds.contains(R.id.chip_asr_accel_gpu) -> "GPU"
                checkedIds.contains(R.id.chip_asr_accel_npu) -> "NNAPI"
                else -> "CPU"
            }
            var changed = false
            if (settingsManager.transcriptEngine == "whisper") {
                if (settingsManager.whisperAcceleratorMode != mode) {
                    settingsManager.whisperAcceleratorMode = mode
                    changed = true
                    android.util.Log.i(TAG, "🎙 Změněn Whisper HW Akcelerátor na: $mode")
                }
            } else if (settingsManager.transcriptEngine == "sherpa") {
                if (settingsManager.sherpaAcceleratorMode != mode) {
                    settingsManager.sherpaAcceleratorMode = mode
                    changed = true
                    android.util.Log.i(TAG, "🎙 Změněn Sherpa HW Akcelerátor na: $mode")
                }
            }
            
            if (changed) {
                @android.annotation.SuppressLint("UnsafeImplicitIntentLaunch")
                val intent = android.content.Intent("com.example.llama.ACTION_RESTART_STT")
                sendBroadcast(intent)
                android.widget.Toast.makeText(this, "Aplikováno (Restart STT)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }


    // ========================================================================
    // AUDIO SOURCE SPINNER (MIC / System Audio)
    // ========================================================================

    private fun setupAudioSourceSpinner() {
        val sources = arrayOf("🎙️ Mikrofon", "📱 Systémový zvuk (Agent)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sources)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAudioSource.adapter = adapter

        spinnerAudioSource.onItemSelectedListener =
                object : AdapterView.OnItemSelectedListener {
                    private var initialized = false
                    override fun onItemSelected(
                            parent: AdapterView<*>?,
                            view: View?,
                            pos: Int,
                            id: Long
                    ) {
                        if (!initialized) {
                            initialized = true
                            return
                        }
                        // Jen informativně - skutečné napojení prob�hne a� p�i kliknut� Record
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
    }

    // ========================================================================
    // RECORD BUTTON
    // ========================================================================

    private var recordSeconds = 0
    private var recordTimerJob: Job? = null

    private fun setupRecordButton() {
        // REC — spustit nahrávání (pouze pokud neprobíhá)
        btnRecordMaster.setOnClickListener { if (!isStudioRecording) startDictaphone() }
        // PAUSE / RESUME
        btnPauseDictaphone.setOnClickListener { togglePauseDictaphone() }
        // STOP — zastavit a uložit
        btnStopDictaphone.setOnClickListener { stopDictaphone() }
        // AI ZÁPIS
        btnDictaphoneAiSummary.setOnClickListener { handleAiSummaryGeneration() }

        // ZOOM LOGIC for tvDictaphoneTranscript
        val scaleGestureDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var scaleFactor = 1.0f
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 5.0f)) // limit zoom between 0.5x to 5.0x
                tvDictaphoneTranscript.textSize = 15f * scaleFactor // base size is 15sp
                return true
            }
        })
        svDictaphoneTranscript.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            false // allow scroll view to handle standard touches
        }

        //  Transcript Toolbar 
        btnTranscriptCopy.setOnClickListener { copyTranscriptToClipboard() }
        btnTranscriptEdit.setOnClickListener { toggleTranscriptEditMode() }
        btnTranscriptShare.setOnClickListener { shareTranscriptText() }

        // AI šablony  programov pidn do toolbaru (zrcadlo pipeline z Diktafonu)
        val aiTemplatesBtn =
                com.google.android.material.button.MaterialButton(
                                this,
                                null,
                                com.google.android.material.R.attr.materialButtonOutlinedStyle
                        )
                        .apply {
                            text = "AI šablony"
                            textSize = 12f
                            val dp8 = (8 * resources.displayMetrics.density).toInt()
                            setPadding(dp8, dp8 / 2, dp8, dp8 / 2)
                            layoutParams =
                                    android.widget.LinearLayout.LayoutParams(
                                                    android.widget.LinearLayout.LayoutParams
                                                            .WRAP_CONTENT,
                                                    android.widget.LinearLayout.LayoutParams
                                                            .WRAP_CONTENT
                                            )
                                            .also { it.setMargins(dp8, 0, 0, 0) }
                            setOnClickListener {
                                val text = tvDictaphoneTranscript.text.toString()
                                if (text.isBlank()) {
                                    android.widget.Toast.makeText(
                                                    this@AudioStudioActivity,
                                                    "Náš Transcriptárium je prázdné",
                                                    android.widget.Toast.LENGTH_SHORT
                                            )
                                            .show()
                                } else {
                                    showTemplatePickerBottomSheet(text)
                                }
                            }
                        }
        llTranscriptToolbar.addView(aiTemplatesBtn)
        btnTranscriptAiTemplates = aiTemplatesBtn

        // ── NLP Quick Buttons (nad transkriptem) ─────────────────────────────────
        val nlpActions = listOf(
            Triple("📝", "Shrnutí",  com.example.llama.agent.models.ActionType.NLP_SUMMARIZE),
            Triple("🔍", "Korektura", com.example.llama.agent.models.ActionType.NLP_PROOFREAD),
            Triple("🌍", "Překlad",  com.example.llama.agent.models.ActionType.NLP_TRANSLATE),
            Triple("🔄", "Přepis",   com.example.llama.agent.models.ActionType.NLP_REWRITE),
            Triple("🎭", "Sentiment", com.example.llama.agent.models.ActionType.NLP_SENTIMENT)
        )
        val dp8 = (8 * resources.displayMetrics.density).toInt()
        nlpActions.forEach { (emoji, label, actionType) ->
            val nlpBtn = com.google.android.material.button.MaterialButton(
                this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = "$emoji $label"
                textSize = 11f
                setPadding(dp8, dp8 / 2, dp8, dp8 / 2)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(dp8, 0, 0, 0) }
                setOnClickListener {
                    if (actionType == com.example.llama.agent.models.ActionType.NLP_REWRITE) {
                        val tones = com.example.llama.agent.perception.RewriteTone.values()
                        val toneNames = tones.map { it.name }.toTypedArray()
                        android.app.AlertDialog.Builder(this@AudioStudioActivity)
                            .setTitle("Vyberte tón přepisu")
                            .setItems(toneNames) { _, which ->
                                launchStudioNlpAction(actionType, " ", extraPrefix = tones[which].name)
                            }
                            .show()
                    } else if (actionType == com.example.llama.agent.models.ActionType.NLP_PROMPT) {
                        val input = android.widget.EditText(this@AudioStudioActivity)
                        input.hint = "Např. Vytvoř osnovu pro tento text..."
                        android.app.AlertDialog.Builder(this@AudioStudioActivity)
                            .setTitle("Vlastní AI Prompt")
                            .setView(input)
                            .setPositiveButton(getString(R.string.action_generate)) { _, _ ->
                                val promptStr = input.text.toString().trim()
                                launchStudioNlpAction(actionType, " ", extraPrefix = promptStr)
                            }
                            .setNegativeButton("Zrušit", null)
                            .show()
                    } else {
                        launchStudioNlpAction(actionType, " ")
                    }
                }
            }
            llTranscriptToolbar.addView(nlpBtn)
        }

        // ── Engine přepínač (ChipGroup) — vložený do nového řádku pod toolbar ────────
        // Např. scrollovatelný LinearLayout už existuje: sv_dictaphone_transcript → llTranscriptToolbar
        // Přidáme ChipGroup jako sourozenec llTranscriptToolbar do LayoutParentu
        val studioEngineChipGroup = com.google.android.material.chip.ChipGroup(this).apply {
            isSingleSelection   = true
            isSelectionRequired = true
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, dp8 / 2, 0, 0) }
            listOf(
                Triple(R.id.chip_studio_nlp_auto,   "MoE Auto",  null),
                Triple(R.id.chip_studio_nlp_aicore, "AiCore", null),
                Triple(R.id.chip_studio_nlp_mlkit,  "MediaPipe", null),
                Triple(R.id.chip_studio_nlp_llama,  "Llama", null),
                Triple(R.id.chip_studio_nlp_heur,   "Heuristika", null)
            ).forEachIndexed { i, (id, label, _) ->
                com.google.android.material.chip.Chip(this@AudioStudioActivity).apply {
                    this.id     = id
                    text        = label
                    textSize    = 11f
                    isCheckable = true
                    isChecked   = (i == 0)
                    addView(this)
                }
            }
        }
        // Zabalení llTranscriptToolbar do HorizontalScrollView
        val toolbarParent = llTranscriptToolbar.parent as? android.view.ViewGroup
        val toolbarIndex = toolbarParent?.indexOfChild(llTranscriptToolbar) ?: -1
        if (toolbarParent != null && toolbarIndex != -1) {
            toolbarParent.removeView(llTranscriptToolbar)
            val toolbarHsv = android.widget.HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(llTranscriptToolbar)
            }
            toolbarParent.addView(toolbarHsv, toolbarIndex)
        }

        // Ulož referenci pro přístup v launchStudioNlpAction
        studioNlpEngineChipGroup = studioEngineChipGroup

        // Zabalení ChipGroup do HorizontalScrollView a vložení
        val chipGroupHsv = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(studioEngineChipGroup)
        }

        val mainParent = btnDictaphoneAiSummary?.parent as? android.view.ViewGroup
        mainParent?.addView(chipGroupHsv, mainParent.indexOfChild(btnDictaphoneAiSummary))
    }

    /**
     * Spustí NLP akci nad aktuálním transkriptem z Diktafonu.
     * Vstup: tvDictaphoneTranscript.text — plain text.
     * Výstup: tvNlpResultStudio — zobrazen pod toolbarem.
     */
        private fun launchStudioNlpAction(
        actionType: com.example.llama.agent.models.ActionType,
        label: String,
        forceMode: NlpEngineMode? = null,
        extraPrefix: String? = null
    ) {
        val baseText = tvDictaphoneTranscript.text.toString().trim()
        val text = if (extraPrefix != null && extraPrefix.isNotBlank()) "|" else baseText
        if (text.isBlank()) {
            android.widget.Toast.makeText(this,
                "Transkript je prázdný – spusť nejdříve nahrávání",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        android.util.Log.i("AudioStudio", "NLP $label: ${text.length} znaků")
        progressNlpStudio.visibility = android.view.View.VISIBLE
        tvNlpResultStudio.visibility = android.view.View.VISIBLE
        tvNlpResultStudio.text = "⏳ $label — zpracovávám ${text.length} znaků..."

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val selectedMode: NlpEngineMode? = forceMode
                ?: when (studioNlpEngineChipGroup?.checkedChipId) {
                    R.id.chip_studio_nlp_mlkit  -> NlpEngineMode.MLKIT_ONLY
                    R.id.chip_studio_nlp_aicore -> NlpEngineMode.AICORE_ONLY
                    R.id.chip_studio_nlp_llama  -> NlpEngineMode.LLM_ONLY
                    R.id.chip_studio_nlp_heur   -> NlpEngineMode.HEURISTIC_ONLY
                    else                        -> null
                }
            val result = try {
                studioNlpHandler.execute(actionType, text, selectedMode)
            } catch (e: Exception) {
                android.util.Log.e("AudioStudio", "NLP $label selhala", e)
                "? $label selhala: ${e.message}"
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                progressNlpStudio.visibility = android.view.View.GONE
                tvNlpResultStudio.text = "✅ Poslední akce dokončena: $label"
                showNlpResultPopup(label, result)
            }
        }
    }

    private fun showNlpResultPopup(title: String, result: String) {
        val scrollView = android.widget.ScrollView(this)
        val textView = android.widget.TextView(this).apply {
            text = result
            setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
            textSize = 14f
            setPadding(48, 32, 48, 32)
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("OK", null)
            .setNeutralButton("Kopírovat") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI výsledek", result))
                Toast.makeText(this@AudioStudioActivity, "Zkopírováno", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun startDictaphone() {
        // Zobrazit Popup pro nastavení profilu, pak spustit pipeline
        showDictaphoneSttSettingsPopup { profile ->
            btnDictaphoneAiSummary.visibility = View.GONE
            llTranscriptToolbar.visibility = View.GONE
            val source = spinnerAudioSource.selectedItemPosition // 0 = MIC, 1 = System Audio
            val prefix = if (source == 0) "DICT_MIC" else "DICT_SYS"
            val outputDir = File(getExternalFilesDir(null), "Dictaphone").also { it.mkdirs() }
            val timestamp =
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
            val outFile = File(outputDir, "${prefix}_$timestamp.wav")
            activeMuxer = AsyncWavMuxer(outFile)
            activeMuxer?.start()
            isDictaphonePaused = false
            weStartedFgService = false

            // Napojit callback na Audio pipeline Agenta (pasivní odběr — nulová duplikace HW)
            com.example.llama.agent.perception.TranscriptAgent.audioDataCallback = { rawBuffer ->
                activeMuxer?.pushAudioData(rawBuffer, rawBuffer.size)
            }

            if (source == 1) {
                // �� System Audio: vyžaduje MediaProjection consent ������������������
                startSystemAudioForDictaphone(profile)
            } else {
                // �� MIC: spustit TranscriptAgent pro Mic (otevře AudioRecord + zelená notifikace)
                // ��
                startMicForDictaphone(profile)
            }
        }
    }

    private fun showDictaphoneSttSettingsPopup(
            onStart: (com.example.llama.srt.TranscriptProfile) -> Unit
    ) {
        val settingsManager = com.example.llama.SettingsManager(this)
        val isWhisper = settingsManager.transcriptEngine == "whisper"
        val engineName = if (isWhisper) "Whisper.cpp" else "Sherpa-ONNX"

        // Dynamické vytvoření UI, pokud nemáme layout, vytvoříme ho programově
        val layout =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(50, 40, 50, 40)
                }

        val titleView =
                android.widget.TextView(this).apply {
                    text = "Přesný Přepis: $engineName"
                    textSize = 18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(0, 0, 0, 30)
                }
        layout.addView(titleView)

        val checkPunctuation =
                com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                    text = "AI Interpunkce a formátování"
                    isChecked = true
                    setTextColor(android.graphics.Color.LTGRAY)
                }
        layout.addView(checkPunctuation)

        val checkVad =
                com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                    text = "Voice Activity Detection (VAD)"
                    isChecked = true
                    setTextColor(android.graphics.Color.LTGRAY)
                }
        layout.addView(checkVad)

        val vadLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val vadThresholdLabel =
                android.widget.TextView(this).apply {
                    text = "Citlivost VAD - šum v okolí (RMS): 300"
                    setTextColor(android.graphics.Color.GRAY)
                    setPadding(0, 30, 0, 10)
                }
        vadLayout.addView(vadThresholdLabel)

        val vadThresholdSlider =
                com.google.android.material.slider.Slider(this).apply {
                    valueFrom = 50f
                    valueTo = 1000f
                    stepSize = 50f
                    value = 300f
                    addOnChangeListener { _, value, _ ->
                        vadThresholdLabel.text = "Citlivost VAD - šum v okolí (RMS): ${value.toInt()}"
                    }
                }
        vadLayout.addView(vadThresholdSlider)

        val vadSilenceLabel =
                android.widget.TextView(this).apply {
                    text = "Délka pauzy pro konec věty: 850 ms"
                    setTextColor(android.graphics.Color.GRAY)
                    setPadding(0, 30, 0, 10)
                }
        vadLayout.addView(vadSilenceLabel)

        val vadSilenceSlider =
                com.google.android.material.slider.Slider(this).apply {
                    valueFrom = 300f
                    valueTo = 2000f
                    stepSize = 50f
                    value = 850f
                    addOnChangeListener { _, value, _ ->
                        vadSilenceLabel.text = "Délka pauzy pro konec věty: ${value.toInt()} ms"
                    }
                }
        vadLayout.addView(vadSilenceSlider)
        layout.addView(vadLayout)

        val nonVadLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = android.view.View.GONE
        }
        val overlapLabel = android.widget.TextView(this).apply {
            text = "Klouzavé okno - Překryv: 2000 ms"
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 30, 0, 10)
        }
        nonVadLayout.addView(overlapLabel)
        val overlapSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 0f
            valueTo = 5000f
            stepSize = 500f
            value = 2000f
            addOnChangeListener { _, value, _ ->
                overlapLabel.text = "Klouzavé okno - Překryv: ${value.toInt()} ms"
            }
        }
        nonVadLayout.addView(overlapSlider)
        
        val chunkLabel = android.widget.TextView(this).apply {
            text = "Velikost bloku (Chunk): 10000 ms"
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 30, 0, 10)
        }
        nonVadLayout.addView(chunkLabel)
        val chunkSlider = com.google.android.material.slider.Slider(this).apply {
            valueFrom = 5000f
            valueTo = 30000f
            stepSize = 1000f
            value = 10000f
            addOnChangeListener { _, value, _ ->
                chunkLabel.text = "Velikost bloku (Chunk): ${value.toInt()} ms"
            }
        }
        nonVadLayout.addView(chunkSlider)
        layout.addView(nonVadLayout)

        checkVad.setOnCheckedChangeListener { _, isChecked ->
            vadLayout.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            nonVadLayout.visibility = if (isChecked) android.view.View.GONE else android.view.View.VISIBLE
        }

        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setView(layout)
                .setPositiveButton("Spustit Nahrávání") { _, _ ->
                    val whisperThreads = settingsManager.whisperNThreads
                    val sherpaThreads = settingsManager.sherpaNThreads

                    // Vytvoříme profil dynamicky
                    var profile =
                            com.example.llama.srt.TranscriptProfileOrchestrator.dictaphoneProfile(
                                    whisperNThreads = whisperThreads,
                                    sherpaNThreads = sherpaThreads,
                                    isWhisperEngine = isWhisper,
                                    thermalGuard = null,
                                    uiVadThreshold = vadThresholdSlider.value.toDouble(),
                                    uiVadSilenceMs = vadSilenceSlider.value.toLong()
                            )

                    // Aplikujeme uživatelská nastavení
                    if (!checkVad.isChecked) {
                        profile = profile.copy(
                            enablePunctuation = checkPunctuation.isChecked,
                            enableVAD = false,
                            slidingWindowMs = chunkSlider.value.toLong(),
                            overlap = overlapSlider.value.toInt()
                        )
                    } else {
                        profile = profile.copy(
                            enablePunctuation = checkPunctuation.isChecked,
                            enableVAD = true
                        )
                    }

                    onStart(profile)
                }
                .setNegativeButton("Zrušit", null)
                .show()
    }

    /**
     * MIC flow: Spustit TranscriptAgent přes AccessibilityService nebo vytvořit lokální instanci.
     * Agent otevře AudioRecord a systém zobrazí zelenou notifikaci mikrofonu.
     */
    private fun startMicForDictaphone(profile: com.example.llama.srt.TranscriptProfile) {
        // Fáze B: Nastavit prioritu vlákna dle user volby z ChipGroup
        android.os.Process.setThreadPriority(currentStudioPriority.osValue)
        Log.i(
                TAG,
                "🎙️Diktafon MIC: vlákno priority=${currentStudioPriority.label} (osValue=${currentStudioPriority.osValue})"
        )

        val service = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
        if (service != null) {
            val agent = service.getOrCreateTranscriptAgentForSrt()

            lifecycleScope.launch(Dispatchers.IO) {
                if (agent.isRunning()) {
                    Log.w(TAG, "📱Agent běží. Vynucuji RESTART s Dictaphone profilem!")
                    agentWasRunningBeforeDictaphone = true
                    agent.stop()
                    kotlinx.coroutines.delay(300) // počkáme na uvolnění paměti a MICu
                } else {
                    agentWasRunningBeforeDictaphone = false
                }

                agent.start(null, profile) // null = Mic zdroj, p�ed�me n� Dictaphone profil
                weStartedAgent =
                        true // My jsme ho (re)startovali — budeme ho řešit v stopDictaphone()
                Log.i(
                        TAG,
                        "✅ Diktafon: spuštěn TranscriptAgent (MIC) přes Service s profilem ${profile.useCase}"
                )

                withContext(Dispatchers.Main) {
                    isStudioRecording = true
                    onRecordingStateChanged(true)
                    Log.i(TAG, "Diktafon spuštěn (MIC)")
                }
            }
        } else {
            weStartedAgent = false
            Toast.makeText(
                            this,
                            "🤖 AI Agent neběží. Zapněte ho v Nastavení → Usnadnění přístupu pro plnou funkci mikrofonu.",
                            Toast.LENGTH_LONG
                    )
                    .show()
        }
    }

    /**
     * System Audio flow: MediaProjection consent → TranscriptForegroundService. Stejný flow jako
     * AiAgentAccessibilityService.startTranscriptWithSource().
     */
    private fun startSystemAudioForDictaphone(profile: com.example.llama.srt.TranscriptProfile) {
        // Zkontrolujeme zda FG Service již běží (agent ji spustil)
        val existingRecord =
                com.example.llama.agent.perception.TranscriptForegroundService.virtualAudioRecord
        if (existingRecord != null &&
                        existingRecord.state == android.media.AudioRecord.STATE_INITIALIZED
        ) {
            // FG Service běží — napojíme se na existující stream, ALE nebudeme ji zastavovat
            // při stopDictaphone(). Diktafon je jen "pasivní odběratel" p�es audioDataCallback.
            // weStartedFgService zůstane false — stopDictaphone() nezastaví live přepis Agenta.
            Log.i(
                    TAG,
                    "System Audio FG Service už běží – napojuji jen callback (nespustil jsem ji)"
            )
            isStudioRecording = true
            onRecordingStateChanged(true)
            return
        }

        // Registrovat receiver na READY broadcast před spuštěním service
        val receiver =
                object : android.content.BroadcastReceiver() {
                    override fun onReceive(
                            context: android.content.Context?,
                            intent: android.content.Intent?
                    ) {
                        Log.i(TAG, "🎙️ System Audio READY – diktafon aktivní")
                        isStudioRecording = true
                        runOnUiThread { onRecordingStateChanged(true) }
                        unregisterSysAudioReceiver()
                    }
                }
        systemAudioReadyReceiver = receiver
        androidx.core.content.ContextCompat.registerReceiver(
                this,
                receiver,
                android.content.IntentFilter(
                        com.example.llama.agent.perception.TranscriptForegroundService
                                .ACTION_SYSTEM_AUDIO_READY
                ),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Uložíme profil pro službu, aby se neztratil
        com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()?.pendingDictaphoneProfile = profile

        // Spustit MediaProjection consent dialog
        com.example.llama.agent.perception.MediaProjectionPermissionActivity.start(
                applicationContext
        ) { resultCode, data ->
            if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                val serviceIntent =
                        Intent(
                                        applicationContext,
                                        com.example.llama.agent.perception
                                                        .TranscriptForegroundService::class
                                                .java
                                )
                                .apply {
                                    action =
                                            com.example.llama.agent.perception
                                                    .TranscriptForegroundService
                                                    .ACTION_START_SYSTEM_AUDIO
                                }
                try {
                    androidx.core.content.ContextCompat.startForegroundService(
                            applicationContext,
                            serviceIntent
                    )
                    weStartedFgService = true
                    Log.i(TAG, "Diktafon: TranscriptForegroundService spuštěna pro System Audio")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Nelze spustit TranscriptForegroundService: ${e.message}")
                    unregisterSysAudioReceiver()
                    Toast.makeText(this, "Chyba spuštění System Audio", Toast.LENGTH_SHORT).show()
                }
            } else {
                unregisterSysAudioReceiver()
                activeMuxer?.let { lifecycleScope.launch(Dispatchers.IO) { it.stop() } }
                activeMuxer = null
                Toast.makeText(this, "System Audio přístup zamítnut", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun unregisterSysAudioReceiver() {
        systemAudioReadyReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {}
            systemAudioReadyReceiver = null
        }
    }

    private fun stopDictaphone() {
        isStudioRecording = false
        isDictaphonePaused = false
        unregisterSysAudioReceiver()

        if (tvDictaphoneTranscript.text.isNotBlank()) {
            btnDictaphoneAiSummary.visibility = View.VISIBLE
            llTranscriptToolbar.visibility = View.VISIBLE
        }

        // Odpojit audio callback — nulové alokace, atomická operace
        com.example.llama.agent.perception.TranscriptAgent.audioDataCallback = null

        // �� MIC flow: zastavit TranscriptAgent pokud jsme ho spustili my ��
        //  MIC flow: zastavit TranscriptAgent pokud jsme ho spustili my 
        if (weStartedAgent) {
            val service = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
            if (service != null) {
                val agent = service.getOrCreateTranscriptAgentForSrt()

                lifecycleScope.launch(Dispatchers.IO) {
                    agent.stop()
                    Log.i(TAG, "🎙️ Diktafon: ukončen TranscriptAgent (MIC)")

                    if (agentWasRunningBeforeDictaphone) {
                        kotlinx.coroutines.delay(300) // Počkat na zastavení HW
                        Log.i(TAG, "🎙️ Diktafon: Obnovuji původní Live Agent profil do Toolbaru")
                        agent.start(null, null) // Default null = liveAgentProfile
                    }
                }
            }
            weStartedAgent = false
            agentWasRunningBeforeDictaphone = false
        }
        // System Audio flow: zastavit FG Service pokud jsme ji my spustili 
        if (weStartedFgService) {
            val stopIntent = Intent(
                this,
                com.example.llama.agent.perception.TranscriptForegroundService::class.java
            ).apply {
                action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_AUDIO_GRACEFULLY
            }
            startService(stopIntent)
            weStartedFgService = false
            Log.i(TAG, "Diktafon: Poslán signál k ukončení TranscriptForegroundService (graceful)")
        }

        val muxerToStop = activeMuxer
        activeMuxer = null
        lifecycleScope.launch(Dispatchers.IO) { muxerToStop?.stop() }

        onRecordingStateChanged(false)
        loadRecordingsList()

        // Uložení přepisu z RAM do finálního souboru
        val shouldSaveTranscript = switchLiveStt.isChecked
        if (shouldSaveTranscript && dictaphoneRamBuffer.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val outDir = File(getExternalFilesDir(null), "Dictaphone")
                val timestamp =
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                val finalTxt = File(outDir, "Transcript_$timestamp.txt")
                
                if (isRealtimeDiskWriteEnabled && transcriptTempFile != null && transcriptTempFile!!.exists()) {
                    // Měli jsme zapnutý realtime I/O, přesuneme temp
                    transcriptTempFile!!.copyTo(finalTxt, overwrite = true)
                    transcriptTempFile!!.delete()
                } else {
                    // RAM-only mód (Výchozí): Zápis celého bufferu naráz
                    finalTxt.writeText(dictaphoneRamBuffer.toString(), java.nio.charset.StandardCharsets.UTF_8)
                }
                
                Log.i(TAG, "Transcript uložen z RAM: ${finalTxt.absolutePath}")
            }
        } else {
            transcriptTempFile?.delete()
        }

        // D1: Export SRT souboru pokud toggle ON a máme bloky
        if (switchDictaphoneSrt.isChecked && liveSrtBlocks.isNotEmpty()) {
            val blocksToExport: List<SrtBlock> =
                    liveSrtBlocks.toList() // snapshot — thread-safe kopie
            liveSrtBlocks.clear()
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val outDir = File(getExternalFilesDir(null), "Dictaphone")
                    val timestamp =
                            java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                    .format(java.util.Date())
                    val srtFile = File(outDir, "Titulky_$timestamp.srt")
                    // Inline SRT zápis  stejn formt jako SrtEditorActivity export
                    java.io.BufferedWriter(
                                    java.io.OutputStreamWriter(
                                            java.io.FileOutputStream(srtFile),
                                            java.nio.charset.StandardCharsets.UTF_8
                                    ),
                                    8192
                            )
                            .use { bw ->
                                blocksToExport.forEachIndexed { i, b ->
                                    bw.write(
                                            "${i + 1}\n${b.startTime} --> ${b.endTime}\n${b.text}\n\n"
                                    )
                                }
                            }
                    Log.i(
                            TAG,
                            "✅ Diktafon SRT uložen: ${srtFile.absolutePath} (${blocksToExport.size} bloků)"
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadRecordingsList() // Obnov seznam — .srt soubor se zobrazí
                        android.widget.Toast.makeText(
                                        this@AudioStudioActivity,
                                        "SRT titulky uloženy: Titulky_$timestamp.srt",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Chyba exportu SRT: ${e.message}", e)
                }
            }
        } else {
            liveSrtBlocks.clear()
        }
    }

    private fun togglePauseDictaphone() {
        if (!isStudioRecording) return
        isDictaphonePaused = !isDictaphonePaused
        if (isDictaphonePaused) {
            activeMuxer?.pause()
            recordTimerJob?.cancel() // Timer zmrazit
            btnPauseDictaphone.text = "▶️ Resume"
            btnPauseDictaphone.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            btnPauseDictaphone.setStrokeColorResource(android.R.color.holo_green_light)
            Log.i(TAG, "⏸️ Diktafon pozastaven")
        } else {
            activeMuxer?.resume()
            // Timer obnovit od posledního stavu
            recordTimerJob =
                    lifecycleScope.launch(Dispatchers.Main) {
                        while (isActive) {
                            delay(1000)
                            recordSeconds++
                            val h = recordSeconds / 3600
                            val m = (recordSeconds % 3600) / 60
                            val s = recordSeconds % 60
                            tvRecordTimer.text = String.format("%02d:%02d:%02d", h, m, s)
                        }
                    }
            btnPauseDictaphone.text = "⏸️ Pauza"
            btnPauseDictaphone.setTextColor(android.graphics.Color.parseColor("#FFA000"))
            btnPauseDictaphone.setStrokeColor(
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#FFA000")
                    )
            )
            Log.i(TAG, "▶️ Diktafon obnoven")
        }
    }

    private fun onRecordingStateChanged(isRecording: Boolean) {
        if (isRecording) {
            btnRecordMaster.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#424242")
                    )
            btnRecordMaster.setIconResource(android.R.drawable.ic_media_pause)
            btnRecordMaster.isEnabled = false // Zablokovat opakovaný start
            layoutRecordControls.visibility = View.VISIBLE
            btnPauseDictaphone.text = "⏸️ Pauza"
            btnPauseDictaphone.setTextColor(android.graphics.Color.parseColor("#FFA000"))
            recordSeconds = 0
            tvRecordTimer.text = "00:00:00"
            recordTimerJob =
                    lifecycleScope.launch(Dispatchers.Main) {
                        while (isActive) {
                            delay(1000)
                            recordSeconds++
                            val h = recordSeconds / 3600
                            val m = (recordSeconds % 3600) / 60
                            val s = recordSeconds % 60
                            tvRecordTimer.text = String.format("%02d:%02d:%02d", h, m, s)
                        }
                    }
            startVuMeter()
        } else {
            btnRecordMaster.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(
                            android.graphics.Color.parseColor("#D32F2F")
                    )
            btnRecordMaster.setIconResource(android.R.drawable.ic_btn_speak_now)
            btnRecordMaster.isEnabled = true
            layoutRecordControls.visibility = View.GONE
            recordTimerJob?.cancel()
            stopVuMeter()
        }
    }

    // ========================================================================
    // VU METR A REAL-TIME TRANSKRPICE (Dictaphone loop)
    // ========================================================================

    private fun startVuMeter() {
        stopVuMeter()

        tvDictaphoneTranscript.visibility = View.VISIBLE
        tvDictaphonePartial.visibility = View.VISIBLE
        tvDictaphoneTranscript.text = ""
        tvDictaphonePartial.text = ""

        // D1: Reset live SRT bloků a start timestamp
        if (switchDictaphoneSrt.isChecked) {
            liveSrtBlocks.clear()
            dictaphoneStartTimeMs = System.currentTimeMillis()
            Log.i(TAG, "Diktafon SRT: aktivní, start=${dictaphoneStartTimeMs}")
        }

        // Reinicializace Channel — uzavřený Channel po stop() nelze znovu použít
        transcriptChannel = Channel(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

        // 
        // Dual-Buffer I/O Pipeline (Volitelné)
        // 
        
        dictaphoneRamBuffer.clear()
        
        if (isRealtimeDiskWriteEnabled) {
            val outDir = File(getExternalFilesDir(null), "Dictaphone").also { it.mkdirs() }
            val tempFile = File(outDir, "_transcript_temp_${System.currentTimeMillis()}.txt")
            transcriptTempFile = tempFile
            Log.i(TAG, "Transcript I/O pipeline: ${tempFile.name}")

            transcriptWriterJob =
                    lifecycleScope.launch(transcriptIoDispatcher) {
                        var unflushedCount = 0
                        val writer =
                                BufferedWriter(
                                        java.io.OutputStreamWriter(
                                                java.io.FileOutputStream(tempFile, /*append=*/ true),
                                                java.nio.charset.StandardCharsets.UTF_8
                                        ),
                                        8192
                                )
                        try {
                            for (line in transcriptChannel) {
                                writer.write(line)
                                writer.newLine()
                                unflushedCount++
                                if (unflushedCount >= TRANSCRIPT_FLUSH_EVERY) {
                                    writer.flush()
                                    unflushedCount = 0
                                }
                            }
                        } finally {
                            writer.flush()
                            writer.close()
                            Log.i(TAG, "💾 Transcript writer uzavřen, všechna data na disku")
                        }
                    }
        } else {
            transcriptTempFile = null
            Log.i(TAG, "Transcript I/O pipeline: VYPNUTO (RAM-only mód)")
        }

        // �� UI + Bus listener (Main thread) ������������������������������������������
        vuMeterJob =
                lifecycleScope.launch(Dispatchers.Main) {
                    com.example.llama.agent.perception.PerceptionEventBus.events.collect { event ->
                        if (!isStudioRecording) return@collect
                        
                        when (event) {
                            is com.example.llama.agent.perception.AudioLevelEvent -> {
                                if (isVuMeterEnabled) {
                                    progressVuMeter.updateDb(event.rmsDb, event.peakDb)
                                }
                            }
                            is com.example.llama.agent.perception.TranscriptEvent -> {
                                if (event.isFinal) {
                                    val line = event.text.trim()
                                if (line.isNotEmpty()) {
                                    // SMART DEDUPLICATION (pro Whisper/Sherpa klouzavé okno)
                                    val currentText = dictaphoneRamBuffer.toString()
                                    val existingWords = currentText.split(Regex("\\s+")).takeLast(50)
                                    val incomingWords = line.split(Regex("\\s+"))
                                    
                                    var bestOverlapLen = 0
                                    val maxOverlap = minOf(existingWords.size, incomingWords.size)
                                    for (len in maxOverlap downTo 1) {
                                        if (existingWords.takeLast(len) == incomingWords.take(len)) {
                                            bestOverlapLen = len
                                            break
                                        }
                                    }
                                    
                                    val textToAppend: String
                                    if (bestOverlapLen > 0) {
                                        val remainder = incomingWords.drop(bestOverlapLen).joinToString(" ")
                                        textToAppend = if (remainder.isNotEmpty()) {
                                            if (currentText.isNotEmpty() && !currentText.endsWith(" ") && !currentText.endsWith("\n")) " $remainder" else remainder
                                        } else ""
                                    } else {
                                        textToAppend = if (currentText.isNotEmpty()) "\n$line" else line
                                    }

                                    if (textToAppend.isNotEmpty()) {
                                        dictaphoneRamBuffer.append(textToAppend)
                                        tvDictaphonePartial.text = ""
                                        tvDictaphoneTranscript.append(textToAppend)
                                        svDictaphoneTranscript.post {
                                            svDictaphoneTranscript.fullScroll(View.FOCUS_DOWN)
                                        }
                                        if (isRealtimeDiskWriteEnabled) {
                                            transcriptChannel.trySend(textToAppend.trimStart('\n'))
                                        }
                                    }
                                    // D1: SRT blok generování (pokud toggle ON)
                                    if (switchDictaphoneSrt.isChecked) {
                                        val relativeMs =
                                                System.currentTimeMillis() - dictaphoneStartTimeMs
                                        val newBlocks =
                                                liveSrtEngine.segment(
                                                        text = line,
                                                        chunkStartMs = relativeMs,
                                                        chunkDurationMs =
                                                                2500L, // ~2.5s approx per věta
                                                        wordTimestamps = null
                                                )
                                        liveSrtBlocks.addAll(newBlocks)
                                    }
                                }
                            } else {
                                // Partial hypothesis — zobrazit, ale NEZAPISOVAT do committed
                                // buffer
                                tvDictaphonePartial.text = "? ${event.text}"
                                svDictaphoneTranscript.post {
                                    svDictaphoneTranscript.fullScroll(View.FOCUS_DOWN)
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
    }

    private fun stopVuMeter() {
        vuMeterJob?.cancel()
        vuMeterJob = null
        progressVuMeter.updateDb(-60f, -60f)
    }

    // ========================================================================
    // MODEL SELECTOR — Sherpa + Whisper (bez Android Recognizer)
    // ========================================================================

    private fun setupModelSelector() {
        mirrorModels()
        btnSelectStt.setOnClickListener { showModelDialog(forStt = true) }
        btnSelectTts.setOnClickListener { showModelDialog(forStt = false) }
    }

    private fun mirrorModels() {
        val sttPath = settingsManager.sherpaModelPath
        val ttsPath = settingsManager.sherpaTtsModelPath
        val whisperPath = WhisperModelManager.getModelPath(this)

        val sttName =
                when (settingsManager.transcriptEngine) {
                    "whisper" ->
                            if (whisperPath.isNotBlank()) "Whisper: ${File(whisperPath).name}"
                            else "? Žádný Whisper model"
                    "sherpa" ->
                            if (sttPath.isNotBlank()) "Sherpa: ${File(sttPath).name}"
                            else "? Žádný Sherpa model"
                    else -> "? Žádný STT model"
                }
        tvActiveStt.text = "STT: $sttName"
        tvActiveTts.text =
                if (ttsPath.isNotBlank()) "TTS: ${File(ttsPath).name}" else "? Žádný TTS model"
    }

    private fun showModelDialog(forStt: Boolean) {
        val manager = SherpaModelManager(this, settingsManager)
        val models =
                manager.getImportedModels().filter { m ->
                    if (forStt) m.type.isStt else m.type.isTts
                }

        // Whisper pro STT
        val whisperPath = WhisperModelManager.getModelPath(this)
        val items = mutableListOf<String>()
        val paths = mutableListOf<String>()

        if (forStt && whisperPath.isNotBlank() && File(whisperPath).exists()) {
            items.add("Whisper: ${File(whisperPath).name}")
            paths.add("whisper:$whisperPath")
        }
        models.forEach {
            val size = it.sizeBytes / 1024 / 1024
            items.add("Sherpa: ${it.name} (${size}MB)")
            paths.add("sherpa:${it.path}")
        }

        if (items.isEmpty()) {
            Toast.makeText(
                            this,
                            "Žádný ${if(forStt) "STT" else "TTS"} modely. Import v Nastavení → Modely.",
                            Toast.LENGTH_LONG
                    )
                    .show()
            return
        }

        AlertDialog.Builder(this)
                .setTitle(if (forStt) "Vybrat STT Engine" else "Vybrat TTS Engine")
                .setItems(items.toTypedArray()) { _, idx ->
                    val selected = paths[idx]
                    if (forStt) {
                        if (selected.startsWith("whisper:")) {
                            settingsManager.transcriptEngine = "whisper"
                        } else {
                            settingsManager.transcriptEngine = "sherpa"
                            settingsManager.sherpaModelPath = selected.removePrefix("sherpa:")
                        }
                    } else {
                        settingsManager.sherpaTtsModelPath = selected.removePrefix("sherpa:")
                        studioTtsEngine?.shutdown()
                        studioTtsEngine = null
                    }
                    mirrorModels()
                }
                .show()
    }

    // ========================================================================
    // ZIPVOICE — Import reference + Zrcadlení SettingsManager + Test
    // ========================================================================

    private var lastZipVoiceText: String = ""
    private var tempCloneFile: File? = null

    private fun setupZipVoice() {
        val threadsAdapter =
                android.widget.ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_item,
                        (1..8).toList()
                )
        threadsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val tvThreadsValue = findViewById<TextView>(R.id.tv_zipvoice_threads_value)
        tvThreadsValue.text = settingsManager.sherpaTtsZipvoiceThreads.toString()

        val spinnerThreads = findViewById<android.widget.Spinner>(R.id.spinner_zipvoice_threads)
        spinnerThreads.adapter = threadsAdapter
        spinnerThreads.setSelection(settingsManager.sherpaTtsZipvoiceThreads - 1)
        spinnerThreads.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long
                    ) {
                        settingsManager.sherpaTtsZipvoiceThreads = position + 1
                        tvThreadsValue.text = (position + 1).toString()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

        val priorityAdapter =
                android.widget.ArrayAdapter(
                        this,
                        android.R.layout.simple_spinner_item,
                        listOf("Background", "Default", "Urgent")
                )
        priorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val spinnerPriority = findViewById<android.widget.Spinner>(R.id.spinner_zipvoice_priority)
        spinnerPriority.adapter = priorityAdapter
        spinnerPriority.setSelection(settingsManager.sherpaTtsZipvoicePriority)
        spinnerPriority.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long
                    ) {
                        settingsManager.sherpaTtsZipvoicePriority = position
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

        val switchThermal =
                findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                        R.id.switch_zipvoice_thermal
                )

        val sampleRates = listOf(8000, 16000, 22050, 24000)
        val rateAdapter =
                android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, sampleRates)
        rateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val spinnerRate = findViewById<android.widget.Spinner>(R.id.spinner_zipvoice_sample_rate)
        spinnerRate.adapter = rateAdapter
        spinnerRate.setSelection(
                sampleRates.indexOf(settingsManager.sherpaTtsZipvoiceSampleRate).coerceAtLeast(0)
        )
        spinnerRate.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(
                            parent: android.widget.AdapterView<*>?,
                            view: android.view.View?,
                            position: Int,
                            id: Long
                    ) {
                        settingsManager.sherpaTtsZipvoiceSampleRate = sampleRates[position]
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

        etZipVoiceRefText.setText(settingsManager.sherpaTtsCustomPromptText)
        etZipVoiceChunkSize.setText(settingsManager.sherpaTtsZipvoiceChunkSize.toString())
        etZipVoiceChunkSize.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString()?.toIntOrNull() ?: 1500
                settingsManager.sherpaTtsZipvoiceChunkSize = value
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        etZipVoiceRefText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                settingsManager.sherpaTtsCustomPromptText = etZipVoiceRefText.text.toString().trim()
            }
        }

        btnZipVoiceImport.setOnClickListener { importZipVoiceLauncher.launch("audio/*") }

        btnZipVoicePlayRef.setOnClickListener {
            val path = settingsManager.sherpaTtsCustomPromptPath
            if (path.isNotBlank() && File(path).exists()) {
                zipVoicePlayer?.release()
                zipVoicePlayer =
                        MediaPlayer().apply {
                            setDataSource(path)
                            prepare()
                            start()
                        }
                Toast.makeText(this, "Přehrávám referenci...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                                this,
                                "Reference nenalezena. Nejprve nahrávej nebo importuj.",
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }

        btnZipVoiceRecordMic10s.setOnClickListener {
            handleZipVoiceMicClick(10, btnZipVoiceRecordMic10s)
        }
        btnZipVoiceRecordMicDyn.setOnClickListener {
            handleZipVoiceMicClick(180, btnZipVoiceRecordMicDyn) // 3 minuty = 180s
        }

        btnZipVoiceTest.setOnClickListener {
            // Uložit referenční text PŘED generováním (u�ivatel nemusel opustit focus)
            settingsManager.sherpaTtsCustomPromptText = etZipVoiceRefText.text.toString().trim()

            val text = etZipVoiceTestText.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            if (settingsManager.sherpaTtsCustomPromptPath.isBlank()) return@setOnClickListener

            // CACHE HIT
            if (text == lastZipVoiceText && tempCloneFile?.exists() == true) {
                zipVoicePlayer?.release()
                zipVoicePlayer =
                        MediaPlayer().apply {
                            setDataSource(tempCloneFile!!.absolutePath)
                            prepare()
                            start()
                        }
                return@setOnClickListener
            }

            btnZipVoiceTest.isEnabled = false
            btnZipVoiceTest.text = "⏳ Klonuji..."
            AudioStudioEngineGate.updateTask(AudioStudioEngineGate.StudioTask.TTS_TEST)

            val engine =
                    studioTtsEngine
                            ?: SherpaTtsEngine(this, settingsManager).also { studioTtsEngine = it }
            tempCloneFile = File(cacheDir, "temp_clone.wav")
            lastZipVoiceText = text

            engine.generateToFile(
                    text,
                    tempCloneFile!!,
                    onDone = { success ->
                        runOnUiThread {
                            btnZipVoiceTest.isEnabled = true
                            btnZipVoiceTest.text = "Přečíst"
                            AudioStudioEngineGate.updateTask(
                                    AudioStudioEngineGate.StudioTask.DICTAPHONE
                            )

                            if (success) {
                                zipVoicePlayer?.release()
                                zipVoicePlayer =
                                        MediaPlayer().apply {
                                            setDataSource(tempCloneFile!!.absolutePath)
                                            prepare()
                                            start()
                                        }
                            } else {
                                Toast.makeText(
                                                this@AudioStudioActivity,
                                                "Chyba generování",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        }
                    }
            )
        }

        btnZipVoicePlayRef.setOnLongClickListener {
            val path = settingsManager.sherpaTtsCustomPromptPath
            if (path.isNotBlank() && File(path).exists()) {
                val timestamp =
                        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                exportZipVoicePromptLauncher.launch("Prompt_$timestamp.wav")
            } else {
                Toast.makeText(
                                this,
                                "Není co exportovat (nejprve nahrajte referenci)",
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
            true
        }

        btnZipVoiceSaveClone.setOnClickListener {
            // Uložit referenční text PŘED generováním (u�ivatel nemusel opustit focus)
            settingsManager.sherpaTtsCustomPromptText = etZipVoiceRefText.text.toString().trim()

            val text = etZipVoiceTestText.text.toString().trim()
            if (text.isBlank() || settingsManager.sherpaTtsCustomPromptPath.isBlank()) {
                Toast.makeText(this, "Chybí text nebo reference", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val timestamp =
                    java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                            .format(java.util.Date())
            val defaultFileName = "Klon_$timestamp.wav"

            // FAST SAVE FROM CACHE
            if (text == lastZipVoiceText && tempCloneFile?.exists() == true) {
                pendingExportFile = tempCloneFile
                exportAudioLauncher.launch(defaultFileName)
                return@setOnClickListener
            }

            btnZipVoiceSaveClone.isEnabled = false
            btnZipVoiceSaveClone.text = "💾 Ukládám..."
            val engine =
                    studioTtsEngine
                            ?: SherpaTtsEngine(this, settingsManager).also { studioTtsEngine = it }

            tempCloneFile = File(cacheDir, "temp_clone.wav")
            lastZipVoiceText = text

            engine.generateToFile(
                    text,
                    tempCloneFile!!,
                    onDone = { success ->
                        runOnUiThread {
                            btnZipVoiceSaveClone.isEnabled = true
                            btnZipVoiceSaveClone.text = "Uložit (WAV)"
                            if (success) {
                                pendingExportFile = tempCloneFile
                                exportAudioLauncher.launch(defaultFileName)
                            } else {
                                Toast.makeText(
                                                this@AudioStudioActivity,
                                                "❌ Chyba generování klonu",
                                                Toast.LENGTH_LONG
                                        )
                                        .show()
                            }
                        }
                    }
            )
        }
    }

    private fun updateZipVoiceStatus() {
        val path = settingsManager.sherpaTtsCustomPromptPath
        if (path.isNotBlank() && File(path).exists()) {
            val sizeKb = File(path).length() / 1024
            tvZipVoiceStatus.text = "${File(path).name} → ${sizeKb} KB"
            tvZipVoiceStatus.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
        } else {
            tvZipVoiceStatus.text = "Žádná reference (Default AI hlas)"
            tvZipVoiceStatus.setTextColor(android.graphics.Color.parseColor("#888888"))
        }
        etZipVoiceRefText.setText(settingsManager.sherpaTtsCustomPromptText)

        btnZipVoiceRecordMic10s.text = "Mic 10s"
        btnZipVoiceRecordMicDyn.text = "Mic 3m"
    }

    private var activeRecordingButton: com.google.android.material.button.MaterialButton? = null
    private var activeRecordingLimitSec: Int = 10

    private fun handleZipVoiceMicClick(
            limitSec: Int,
            btn: com.google.android.material.button.MaterialButton
    ) {
        if (isStudioRecording) {
            Toast.makeText(
                            this,
                            "Zastavte hlavní diktafon před nahráváním reference.",
                            Toast.LENGTH_SHORT
                    )
                    .show()
            return
        }
        if (isRecordingPrompt) {
            // Zastavit pokud běží
            stopRecordingPrompt()
        } else {
            // Spustit nahrávání
            activeRecordingButton = btn
            activeRecordingLimitSec = limitSec
            startRecordingPrompt()
        }
    }

    private fun startRecordingPrompt() {
        try {
            val sampleRate = 16000
            val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val minBufSize =
                    android.media.AudioRecord.getMinBufferSize(
                            sampleRate,
                            channelConfig,
                            audioFormat
                    )

            promptAudioRecord =
                    android.media.AudioRecord(
                            android.media.MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            channelConfig,
                            audioFormat,
                            minBufSize * 2
                    )

            if (promptAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "Chyba inicializace mikrofonu", Toast.LENGTH_SHORT).show()
                return
            }

            promptAudioRecord?.startRecording()
            isRecordingPrompt = true

            // Obě tlačítka zablokujeme/přejmenujeme, kromě aktivního
            btnZipVoiceRecordMic10s.isEnabled = activeRecordingButton == btnZipVoiceRecordMic10s
            btnZipVoiceRecordMicDyn.isEnabled = activeRecordingButton == btnZipVoiceRecordMicDyn
            activeRecordingButton?.text = "STOP"

            tvZipVoiceStatus.text = "Stav: Nahrávám (max ${activeRecordingLimitSec}s)..."

            promptRecordingThread = Thread { writeAudioDataToFile(sampleRate) }
            promptRecordingThread?.start()

            lifecycleScope.launch {
                kotlinx.coroutines.delay(activeRecordingLimitSec * 1000L)
                if (isRecordingPrompt) {
                    withContext(Dispatchers.Main) {
                        stopRecordingPrompt()
                        Toast.makeText(
                                        this@AudioStudioActivity,
                                        "Dosažen limit ${activeRecordingLimitSec}s",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                }
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Chybí oprávnění mikrofonu", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingPrompt() {
        isRecordingPrompt = false
        try {
            promptAudioRecord?.stop()
            promptAudioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        promptAudioRecord = null
        promptRecordingThread = null

        val outDir = File(filesDir, "zipvoice").also { it.mkdirs() }
        val outFile = File(outDir, "prompt.wav")

        btnZipVoiceRecordMic10s.isEnabled = true
        btnZipVoiceRecordMicDyn.isEnabled = true
        btnZipVoiceRecordMic10s.text = "Mic 10s"
        btnZipVoiceRecordMicDyn.text = "Mic 3m"
        settingsManager.sherpaTtsCustomPromptPath = outFile.absolutePath
        updateZipVoiceStatus()
    }

    private fun writeAudioDataToFile(sampleRate: Int) {
        val data = ByteArray(2048)
        val outDir = File(filesDir, "zipvoice").also { it.mkdirs() }
        val file = File(outDir, "prompt.wav")
        var os: java.io.FileOutputStream? = null
        try {
            os = java.io.FileOutputStream(file)
            for (i in 0 until 44) os.write(0)

            var totalAudioLen: Long = 0
            while (isRecordingPrompt) {
                val read = promptAudioRecord?.read(data, 0, data.size) ?: 0
                if (read > 0) {
                    os.write(data, 0, read)
                    totalAudioLen += read
                }
            }

            os.close()

            // Header Rewrite
            val randAccess = java.io.RandomAccessFile(file, "rw")
            randAccess.seek(0)
            val totalDataLen = totalAudioLen + 36
            val byteRate = sampleRate * 2
            val header = ByteArray(44)
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = (totalDataLen shr 8 and 0xff).toByte()
            header[6] = (totalDataLen shr 16 and 0xff).toByte()
            header[7] = (totalDataLen shr 24 and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = 1
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = (sampleRate shr 8 and 0xff).toByte()
            header[26] = (sampleRate shr 16 and 0xff).toByte()
            header[27] = (sampleRate shr 24 and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = (byteRate shr 8 and 0xff).toByte()
            header[30] = (byteRate shr 16 and 0xff).toByte()
            header[31] = (byteRate shr 24 and 0xff).toByte()
            header[32] = 2
            header[33] = 0
            header[34] = 16
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = (totalAudioLen shr 8 and 0xff).toByte()
            header[42] = (totalAudioLen shr 16 and 0xff).toByte()
            header[43] = (totalAudioLen shr 24 and 0xff).toByte()
            randAccess.write(header, 0, 44)
            randAccess.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ========================================================================
    // RECORDINGS LIST
    // ========================================================================

    private fun setupRecordingsList() {
        recordingsAdapter =
                AudioRecordingsAdapter(
                        onTranscribeClick = { audioFile, existingSrt ->
                            // D2: existingSrt != null → otevřít editor s existujícím SRT
                            //     existingSrt == null → otevřít editor pro nový přepis
                            startActivity(
                                    Intent(this, com.example.llama.SrtEditorActivity::class.java).apply {
                                        putExtra("MEDIA_URI", audioFile.absolutePath)
                                        if (existingSrt != null) {
                                            putExtra("SRT_URI", existingSrt.absolutePath)
                                        } else {
                                            putExtra("SRT_URI", "")
                                        }
                                    }
                            )
                        },
                        onDeleteClick = { file, _ ->
                            AlertDialog.Builder(this)
                                    .setTitle("Smazat nahrávku")
                                    .setMessage(file.name)
                                    .setPositiveButton("Smazat") { _, _ ->
                                        file.delete()
                                        loadRecordingsList()
                                    }
                                    .setNegativeButton("Zrušit", null)
                                    .show()
                        },
                        onExportClick = { file ->
                            pendingExportFile = file
                            exportAudioLauncher.launch("Export_${file.name}")
                        }
                )
        rvDictaphoneFiles.layoutManager = LinearLayoutManager(this)
        rvDictaphoneFiles.adapter = recordingsAdapter
    }

    private fun loadRecordingsList() {
        val dir = File(getExternalFilesDir(null), "Dictaphone")
        val files =
                if (dir.exists()) {
                    dir
                            .listFiles { f ->
                                f.extension.lowercase() in listOf("wav", "mp3", "m4a", "aac")
                            }
                            ?.toList()
                            ?: emptyList()
                } else emptyList()
        recordingsAdapter.submitList(files)
    }

    // ========================================================================
    // MEDIA BUTTONS — Import + SRT
    // ========================================================================

    private fun setupMediaButtons() {
        btnImportMedia.setOnClickListener { importMediaLauncher.launch("video/*") }
        btnGenerateSrt.setOnClickListener {
            startActivity(Intent(this, com.example.llama.SrtEditorActivity::class.java).apply {
                putExtra("MEDIA_URI", "")
                putExtra("SRT_URI", "")
            })
        }
    }

    // ========================================================================
    // TRANSCRIPT TOOLBAR  Kopírovat / Upravit / Sdlet
    // ========================================================================

    /** #5 — Kopírování transkriptu do schránky */
    private fun copyTranscriptToClipboard() {
        val text = tvDictaphoneTranscript.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Přepis je prázdný", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard =
                getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
                        android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Diktafon přepis", text))
        Toast.makeText(this, "Přepis zkopírován (${text.length} znaků)", Toast.LENGTH_SHORT)
                .show()
    }

    /** Editace — přepne TextView na EditText mode (textIsSelectable + focusable) */
    private var isEditMode = false
    private fun toggleTranscriptEditMode() {
        isEditMode = !isEditMode
        if (isEditMode) {
            // Přepnout na editovatelný režim
            tvDictaphoneTranscript.apply {
                isFocusable = true
                isFocusableInTouchMode = true
                isEnabled = true
                isCursorVisible = true
                // Změna na InputType pro editaci
                inputType =
                        android.text.InputType.TYPE_CLASS_TEXT or
                                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(android.graphics.Color.WHITE)
                requestFocus()
            }
            btnTranscriptEdit.text = "Uložit"
            Toast.makeText(this, "Režim úprav – klepněte do textu", Toast.LENGTH_SHORT).show()
        } else {
            // Vrátit na read-only
            tvDictaphoneTranscript.apply {
                isFocusable = false
                isFocusableInTouchMode = false
                isCursorVisible = false
                inputType = android.text.InputType.TYPE_NULL
                setTextColor(android.graphics.Color.parseColor("#B0BEC5"))
            }
            btnTranscriptEdit.text = getString(R.string.action_edit)
            Toast.makeText(this, "Text uložen", Toast.LENGTH_SHORT).show()
        }
    }

    /** #4 - Sdílení čistého textu (Export do TXT přes ShareSheet) */
    private fun shareTranscriptText() {
        val text = tvDictaphoneTranscript.text.toString()
        if (text.isBlank()) {
            android.widget.Toast.makeText(this, getString(R.string.msg_transcript_empty), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val outDir = java.io.File(getExternalFilesDir(null), "Exports").also { it.mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val exportFile = java.io.File(outDir, "Transcript_$timestamp.txt")
            exportFile.writeText(text)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                this, "${applicationContext.packageName}.fileprovider", exportFile
            )

            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "Diktafon - Přepis")
                putExtra(android.content.Intent.EXTRA_TEXT, text)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(intent, getString(R.string.action_share_transcript)))
        } catch (e: Exception) {
            android.util.Log.e("AudioStudio", "Export failed", e)
            android.widget.Toast.makeText(this, getString(R.string.msg_export_error, e.message), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Sdílení exportovaného .md souboru přes ShareSheet */
    private fun shareExportedFile(file: java.io.File) {
        try {
            val uri =
                    androidx.core.content.FileProvider.getUriForFile(
                            this,
                            "${packageName}.fileprovider",
                            file
                    )
            val intent =
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
            startActivity(android.content.Intent.createChooser(intent, "Sdílet AI zápis"))
        } catch (e: Exception) {
            android.util.Log.w("AudioStudio", "ShareSheet selhal: ${e.message}")
        }
    }

    // ========================================================================
    // AI SUMMARY — Template Pipeline
    // ========================================================================

    private fun handleAiSummaryGeneration() {
        val transcriptText = tvDictaphoneTranscript.text.toString()
        if (transcriptText.isBlank()) {
            Toast.makeText(this, "Není co analyzovat", Toast.LENGTH_SHORT).show()
            return
        }
        // #2 — Nabídnout naposledy použitou šablonu jako rychlou volbu
        val lastTemplateId = settingsManager.lastDictaphoneTemplateId
        if (lastTemplateId.isNotBlank()) {
            val lastTemplate =
                    com.example.llama.agent.perception.nlp.DictaphoneTemplateManager
                            .getTemplateById(lastTemplateId)
            if (lastTemplate != null) {
                android.app.AlertDialog.Builder(this)
                        .setTitle("Poslední šablona")
                        .setMessage(
                                "Chcete znovu použít: ${lastTemplate.icon} ${lastTemplate.name}?"
                        )
                        .setPositiveButton("Ano") { _, _ ->
                            executeAiPipeline(transcriptText, lastTemplate)
                        }
                        .setNeutralButton("Jiná šablona") { _, _ ->
                            showTemplatePickerBottomSheet(transcriptText)
                        }
                        .setNegativeButton("Zrušit", null)
                        .show()
                return
            }
        }
        showTemplatePickerBottomSheet(transcriptText)
    }

    /**
     * BottomSheet s výběrem šablony pro AI zpracování. 30+ profesionálních šablon organizovaných do
     * kategorií.
     */
    private fun showTemplatePickerBottomSheet(transcriptText: String) {
        val checkedEngineId = studioNlpEngineChipGroup?.checkedChipId
        val enginePref = when (checkedEngineId) {
            R.id.chip_studio_nlp_aicore -> "AICORE"
            R.id.chip_studio_nlp_mlkit -> "MEDIAPIPE"
            R.id.chip_studio_nlp_llama -> "LLAMA"
            R.id.chip_studio_nlp_heur -> "HEURISTIKA"
            else -> "AUTO"
        }

        val allTemplates =
                com.example.llama.agent.perception.nlp.DictaphoneTemplateManager.getAllTemplates()
        
        val templates = allTemplates.filter { it.supportedEngines.contains(enginePref) }
        val categories =
                com.example.llama.agent.perception.nlp.DictaphoneTemplateManager.TemplateCategory
                        .entries

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val scrollView =
                android.widget.ScrollView(this).apply {
                    setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"))
                }
        val container =
                android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 32, 48, 48)
                }

        // Hlavička
        val header =
                android.widget.TextView(this).apply {
                    text = "📂 Vyberte AI šablonu"
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 20f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 0, 0, 24)
                }
        container.addView(header)

        val subHeader =
                android.widget.TextView(this).apply {
                    text = "Vše běží offline na vašem zařízení (Vybráno: $enginePref)"
                    setTextColor(android.graphics.Color.parseColor("#888888"))
                    textSize = 12f
                    setPadding(0, 0, 0, 16)
                }
        container.addView(subHeader)

        // MAP-REDUCE PŘEPÍNAČ
        val mapReduceSwitch = android.widget.Switch(this).apply {
            text = "Rozdělit záznam na části (Map-Reduce pro 3h+ audio)"
            setTextColor(android.graphics.Color.WHITE)
            isChecked = settingsManager.enableMapReduce
            setPadding(0, 0, 0, 32)
            setOnCheckedChangeListener { _, isChecked ->
                settingsManager.enableMapReduce = isChecked
            }
        }
        container.addView(mapReduceSwitch)

        // Kategorie a šablony
        for (category in categories) {
            val catTemplates = templates.filter { it.category == category }
            if (catTemplates.isEmpty()) continue

            val catLabel =
                    android.widget.TextView(this).apply {
                        text = "${category.icon} ${category.displayName}"
                        setTextColor(android.graphics.Color.parseColor("#BB86FC"))
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, 24, 0, 12)
                    }
            container.addView(catLabel)

            for (tmpl in catTemplates) {
                val btn =
                        com.google.android.material.button.MaterialButton(this).apply {
                            text = "${tmpl.icon} ${tmpl.name}"
                            setTextColor(android.graphics.Color.WHITE)
                            setBackgroundColor(android.graphics.Color.parseColor("#2D2D2D"))
                            cornerRadius = 12
                            isAllCaps = false
                            val lp =
                                    android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                            lp.setMargins(0, 4, 0, 4)
                            layoutParams = lp
                            setOnClickListener {
                                bottomSheet.dismiss()
                                if (tmpl.targetLanguage == "MULTI") {
                                    showLanguageSelectionDialog(tmpl, transcriptText)
                                } else {
                                    executeAiPipeline(transcriptText, tmpl)
                                }
                            }
                        }
                container.addView(btn)
            }
        }

        // Custom šablona — uživatel zadá vlastní prompt
        val customLabel =
                android.widget.TextView(this).apply {
                    text = "Vlastní šablona"
                    setTextColor(android.graphics.Color.parseColor("#BB86FC"))
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, 24, 0, 12)
                }
        container.addView(customLabel)

        val customBtn =
                com.google.android.material.button.MaterialButton(this).apply {
                    text = "Zadat vlastní AI prompt..."
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.parseColor("#4A148C"))
                    cornerRadius = 12
                    isAllCaps = false
                    setOnClickListener {
                        bottomSheet.dismiss()
                        showCustomPromptDialog(transcriptText)
                    }
                }
        container.addView(customBtn)

        scrollView.addView(container)
        bottomSheet.setContentView(scrollView)
        bottomSheet.show()
    }

    /** Dialog pro výběr cílového jazyka (překladové šablony). */
    private fun showLanguageSelectionDialog(
        tmpl: com.example.llama.agent.perception.nlp.DictaphoneTemplateManager.DictaphoneTemplate,
        transcriptText: String
    ) {
        val languagesMap = com.example.llama.agent.perception.NlpTextProcessor.getAvailableLanguages()
        val languageCodes = languagesMap.keys.toTypedArray()
        val displayNames = languagesMap.values.toTypedArray()

        android.app.AlertDialog.Builder(this)
            .setTitle("Vyberte cílový jazyk")
            .setItems(displayNames) { _, which ->
                val selectedLang = languageCodes[which]
                val customTemplate = tmpl.copy(targetLanguage = selectedLang)
                executeAiPipeline(transcriptText, customTemplate)
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    /** Dialog pro zadání vlastního AI promptu. */
    private fun showCustomPromptDialog(transcriptText: String) {
        val editText =
                android.widget.EditText(this).apply {
                    hint = "Např.: Přelož do němčiny a vypiš jen klíčová rozhodnutí..."
                    setTextColor(android.graphics.Color.WHITE)
                    setHintTextColor(android.graphics.Color.GRAY)
                    setBackgroundColor(android.graphics.Color.parseColor("#2D2D2D"))
                    setPadding(32, 24, 32, 24)
                    minLines = 3
                }
        android.app.AlertDialog.Builder(
                        this,
                        com.google
                                .android
                                .material
                                .R
                                .style
                                .ThemeOverlay_MaterialComponents_Dialog_Alert
                )
                .setTitle("Vlastní AI šablona")
                .setMessage("Zadejte instrukce pro AI engine:")
                .setView(editText)
                .setPositiveButton("Spustit") { _, _ ->
                    val prompt = editText.text.toString().trim()
                    if (prompt.isNotBlank()) {
                        val customTemplate =
                                com.example.llama.agent.perception.nlp.DictaphoneTemplateManager
                                        .createCustomTemplate(
                                                id = System.currentTimeMillis().toString(),
                                                name = "Vlastní: ${prompt.take(30)}...",
                                                userPrompt = prompt
                                        )
                        executeAiPipeline(transcriptText, customTemplate)
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
    }

    /**
     * Spustí AI pipeline s vybranou šablonou na pozadí. Kaskáda: AICore (Gemini Nano) → MediaPipe →
     * Heuristika + Entity + Sentiment + Translate.
     */
    private fun executeAiPipeline(
            transcriptText: String,
            template:
                    com.example.llama.agent.perception.nlp.DictaphoneTemplateManager.DictaphoneTemplate
    ) {
        btnDictaphoneAiSummary.isEnabled = false
        btnDictaphoneAiSummary.text = "? ${template.icon} ${template.name}..."
        btnTranscriptAiTemplates?.isEnabled = false
        progressNlpStudio.visibility = android.view.View.VISIBLE

        settingsManager.lastDictaphoneTemplateId = template.id

        // Zjištění vybraného enginu z UI
        val checkedEngineId = studioNlpEngineChipGroup?.checkedChipId
        val enginePref = when (checkedEngineId) {
            R.id.chip_studio_nlp_aicore -> "AICORE"
            R.id.chip_studio_nlp_mlkit -> "MEDIAPIPE"
            R.id.chip_studio_nlp_llama -> "LLAMA"
            R.id.chip_studio_nlp_heur -> "HEURISTIKA"
            else -> "AUTO"
        }

        // KRITICK: ML Kit GenAI b na IO (ne Main).
        // meetingRefiner dostává applicationContext — žádná vazba na Activity lifecycle.
        lifecycleScope.launch {
            if (enginePref == "LLAMA") {
                val engine = com.arm.aichat.AiChat.getInferenceEngine(applicationContext)
                if (!engine.state.value.isModelLoaded) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        progressNlpStudio.visibility = android.view.View.GONE
                        btnDictaphoneAiSummary.isEnabled = true
                        btnTranscriptAiTemplates?.isEnabled = true
                        showLlamaModelSelectorDialog()
                    }
                    return@launch
                }
            }

            var aiResult: com.example.llama.agent.perception.nlp.MeetingIntelligenceResult? = null
            var outFile: java.io.File? = null
            var errorMsg: String? = null

            try {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val chunk = com.example.llama.agent.perception.nlp.TranscriptChunk(
                        0L, 0L, "Diktafon", transcriptText
                    )

                    // Progress callback — aktualizuje tlačítko po každém zpracovaném chunku.
                    // Volá se z Dispatchers.Default — musíme přepnout na Main.
                    val progressCallback: com.example.llama.agent.perception.nlp.ChunkProgressCallback = {
                        current, total, phase, engine ->
                        val pct = if (total > 0) (current * 100) / total else 0
                        val progressText = when (phase) {
                            "MiniSyntéza"   -> "Syntetizuji části... ($engine)"
                            "FinálníSyntéza" -> "Finální výstup... ($engine)"
                            "Hotovo"         -> "✅ Dokončeno ($engine)"
                            "Startuji GGUF Model..." -> "⏳ Načítám Llama do RAM..."
                            "Model nahrán do RAM" -> "⏳ Generuji odpověď..."
                            else             -> "⏳ ${template.icon} $current/$total ($pct%) → $engine"
                        }
                        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Main) {
                            btnDictaphoneAiSummary.text = progressText
                        }
                    }

                    aiResult = meetingRefiner.refineMeeting(listOf(chunk), template, enginePref, progressCallback)
                    val ts = java.text.SimpleDateFormat(
                        "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
                    ).format(java.util.Date())
                    val safeId = template.id.replace(Regex("[^a-zA-Z0-9_]"), "_")
                    outFile = exportFactory.exportToMarkdown(aiResult!!, "${safeId}_$ts")
                }
            } catch (e: Exception) {
                android.util.Log.e("AudioStudio", "❌ AI Pipeline výjimka", e)
                errorMsg = e.message ?: "Neznámá chyba AI"
            }

            // UI vždy na Main
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (errorMsg != null) {
                    if (errorMsg!!.contains("Model není načten", ignoreCase = true)) {
                        Toast.makeText(this@AudioStudioActivity, "Llama: Vyžadován model pro fallback.", Toast.LENGTH_SHORT).show()
                        showLlamaModelSelectorDialog()
                    } else {
                        Toast.makeText(
                            this@AudioStudioActivity,
                            "❌ Chyba AI: $errorMsg",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else if (aiResult != null) {
                    showResultPreview(aiResult!!, outFile)
                }
                btnDictaphoneAiSummary.isEnabled = true
                btnDictaphoneAiSummary.text = "✨ Generovat AI Zápis z porady"
                btnTranscriptAiTemplates?.isEnabled = true
                progressNlpStudio.visibility = android.view.View.GONE
            }
        }
    }

    private fun showLlamaModelSelectorDialog() {
        lifecycleScope.launch {
            val db = com.example.llama.data.LlamaDatabase.getDatabase(this@AudioStudioActivity)
            val modelsFlow = db.aiModelDao().getAllModels()
            val models = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { 
                modelsFlow.first() 
            }
            
            com.example.llama.ui.dialogs.DialogManager.showModelSelector(
                context = this@AudioStudioActivity,
                models = models,
                onModelSelect = { selectedModel ->
                    lifecycleScope.launch {
                        try {
                            val repo = com.example.llama.data.ModelRepository(this@AudioStudioActivity)
                            val nativePath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                repo.prepareModelPathForNative(selectedModel.id)
                            }
                            val settings = com.example.llama.SettingsManager(this@AudioStudioActivity)
                            val engine = com.arm.aichat.AiChat.getInferenceEngine(applicationContext)
                            
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                engine.loadModel(
                                    pathToModel = nativePath,
                                    projectorPath = null,
                                    kvCacheMode = settings.kvCacheMode,
                                    useMmap = settings.useMmap,
                                    nCtx = settings.contextSize,
                                    nBatch = settings.batchSize,
                                    nUBatch = settings.ubatchSize,
                                    flashAttn = settings.flashAttention
                                )
                            }
                            android.widget.Toast.makeText(this@AudioStudioActivity, "Model GGUF načten. Můžete opakovat generování.", android.widget.Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(this@AudioStudioActivity, "Chyba při načítání modelu: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onImportClick = {
                    android.widget.Toast.makeText(this@AudioStudioActivity, "Pro import modelu přejděte do hlavní obrazovky (AiChat)", android.widget.Toast.LENGTH_LONG).show()
                },
                onDeleteClick = {}
            )
        }
    }

    /** #1 — Zobrazení náhledu AI výsledku s možností sdílení. */
    private fun showResultPreview(
            result: com.example.llama.agent.perception.nlp.MeetingIntelligenceResult,
            exportedFile: java.io.File?
    ) {
        val previewText = buildString {
            appendLine("## ${result.templateName}")
            appendLine()
            appendLine("Engine: **${result.engineDiagnostics}** | ${result.rawTranscript.length} znaků vstupu")
            appendLine()
            if (result.sentiment != null) {
                appendLine("Sentiment: ${result.sentiment}")
                appendLine()
            }
            appendLine(result.summary)
            if (result.entities.isNotEmpty()) {
                appendLine()
                appendLine("🏷️ Entity:")
                result.entities.forEach { appendLine("  • $it") }
            }
            if (result.translatedText != null) {
                appendLine()
                appendLine("Překlad:")
                appendLine(result.translatedText) // BEZ truncation — celý překlad
            }
        }

        val scrollView = android.widget.ScrollView(this)
        val textView =
                android.widget.TextView(this).apply {
                    text = previewText
                    setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                    textSize = 14f
                    setPadding(48, 32, 48, 32)
                    setTextIsSelectable(true)
                }
        scrollView.addView(textView)

        val builder =
                android.app.AlertDialog.Builder(this)
                        .setTitle("✨ AI Výsledek")
                        .setView(scrollView)
                        .setPositiveButton("OK", null)
                        .setNeutralButton("Kopírovat") { _, _ ->
                            val clipboard =
                                    getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
                                            android.content.ClipboardManager
                            clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(
                                            "AI výsledek",
                                            previewText
                                    )
                            )
                            Toast.makeText(this, "Zkopírováno", Toast.LENGTH_SHORT).show()
                        }

        if (exportedFile != null && exportedFile.exists()) {
            builder.setNegativeButton("Sdílet") { _, _ -> shareExportedFile(exportedFile) }
        }

        builder.show()
    }
}



