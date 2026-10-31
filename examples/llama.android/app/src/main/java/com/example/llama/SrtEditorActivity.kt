package com.example.llama

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
// android.media.MediaPlayer — vždy plně kvalifikovaný: android.media.MediaPlayer
// NESMÍ být importován: koliduje s org.videolan.libvlc.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsetsController
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.llama.agent.perception.WhisperModelManager
import com.example.llama.agent.perception.SherpaModelManager
import com.example.llama.srt.SegmentationEngine
import com.example.llama.srt.SegmentationEngineFactory
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.core.NlpEngineMode
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Locale
// LibVLC 3.7.0 — MediaPlayer NENÍ importován jako zkratka (koliduje s android.media.MediaPlayer)
// import org.videolan.libvlc.LibVLC
// import org.videolan.libvlc.Media
// import org.videolan.libvlc.interfaces.IMedia
// import org.videolan.libvlc.interfaces.IVLCVout

/**
 * SRT Editor 2026 — Přepis a editace titulků z importovaných médií.
 *
 * PIPELINE:
 * 1. Import audio/video → MediaMetadataRetriever → thumbnail + metadata
 * 2. STT výběr: Sherpa ONNX offline NEBO Whisper cpp (obě izolované instance)
 * 3. MediaExtractor → PCM ShortArray (16kHz, mono) → chunk-by-chunk přepis
 * 4. SRT bloky se plní postupně → RecyclerView inline editace
 * 5. Export .srt / .vtt / Share Sheet
 *
 * AudioStudioEngineGate: updateTask(TRANSCRIBING) při startu přepisu
 */
class SrtEditorActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SrtEditorActivity"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SECS = 30 // 30s chunky pro Whisper
    }

    private lateinit var settingsManager: SettingsManager
    // Android MediaPlayer zachovan pro audio-only rezim (bez video surface)
    private var mediaPlayer: android.media.MediaPlayer? = null
    private var isVideoViewActive = false
    private var mediaUri: Uri? = null
    private var transcribeJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Dedicated thread pool pro SRT přepis — izolované od Dispatchers.Default (live agent pool)
    // Lifecycle: inicializován v startTranscription(), shutdown() v onDestroy()
    private var srtEditorExecutor: java.util.concurrent.ExecutorService? = null

    // ---- LibVLC 3.7.0 ----
    // private var libVlc: LibVLC? = null
    // Plně kvalifikovaný typ — předchází konfliktu s android.media.MediaPlayer
    // private var vlcPlayer: org.videolan.libvlc.MediaPlayer? = null
    // private var vlcPfd: android.os.ParcelFileDescriptor? = null

    // UI
    private lateinit var toolbar: Toolbar
    private lateinit var ivThumbnail: ImageView
    private lateinit var surfacePlayer: SurfaceView          // LibVLC renderer surface
    private lateinit var switchVideoPreview: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvFilename: TextView
    private lateinit var tvMeta: TextView
    private lateinit var seekbarPlayer: SeekBar
    private lateinit var tvPosition: TextView
    private lateinit var tvDuration: TextView
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnPickMedia: MaterialButton
    private lateinit var spinnerStt: Spinner
    // LibVLC audio track + SPU
    private lateinit var layoutAudioTrack: android.widget.LinearLayout
    private lateinit var spinnerAudioTrack: Spinner
    private lateinit var tvSpuTracksInfo: TextView
    private lateinit var progressTranscription: ProgressBar
    private lateinit var tvTranscriptionStatus: TextView
    private lateinit var btnStartTranscription: MaterialButton
    private lateinit var btnCancelTranscription: MaterialButton
    private lateinit var rvSrtBlocks: RecyclerView
    private lateinit var btnAddBlock: MaterialButton
    private lateinit var btnExportSrt: MaterialButton
    private lateinit var btnExportVtt: MaterialButton
    private lateinit var btnShare: MaterialButton
    private lateinit var btnImportSrt: MaterialButton
    // Globální operace
    private lateinit var btnShiftAll: MaterialButton
    private lateinit var btnAutoFix: MaterialButton
    private lateinit var btnFindReplace: MaterialButton
    private lateinit var btnExportAss: MaterialButton

    // ---- NLP panel (AI operace nad transkriptem) ----
    private lateinit var btnNlpSummarize: MaterialButton
    private lateinit var btnNlpProofread: MaterialButton
    private lateinit var btnNlpTranslate: MaterialButton
    private lateinit var btnNlpRewrite: MaterialButton
    private lateinit var btnNlpSentiment: MaterialButton
    private lateinit var tvNlpResult: TextView
    private lateinit var progressNlp: ProgressBar

    /**
     * Lazy NlpActionHandler — inicializuje se při první NLP akci.
     * Sdílí AiCoreMasterEngine/NlpTextProcessor přes singleton pattern.
     * Lifecycle: drží se po dobu Activity (teardown v onDestroy).
     */
    private val nlpActionHandler by lazy {
        com.example.llama.agent.core.NlpActionHandler(applicationContext, settingsManager)
    }

    // Segmentace
    private lateinit var spinnerSegmentMode: Spinner
    private lateinit var layoutMaxWords: android.widget.LinearLayout
    private lateinit var seekbarMaxWords: SeekBar
    private lateinit var tvMaxWordsValue: TextView
    private lateinit var switchWordTimestamps: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvWordTsWarning: TextView

    // Per-session výkon přepisu (Orchestrator Faze 2)
    private lateinit var seekbarSrtThreads: SeekBar
    private lateinit var tvSrtThreadsValue: TextView
    private lateinit var switchThermalGuard: com.google.android.material.switchmaterial.SwitchMaterial
    /** Per-session počet vláken — nastaven slidereem, lokální, nezávislý na global. SettingsManager */
    @Volatile private var sessionThreadCount: Int = 4
    /** P2: ThermalGuard headroom indikátor */
    private lateinit var tvThermalHeadroom: TextView
    // Fáze B: Priority ChipGroup — per-session, nezávislý na AudioStudiu
    private lateinit var chipgroupPrioritySrt: com.google.android.material.chip.ChipGroup
    private lateinit var tvSrtPriorityWarning: TextView
    @Volatile private var currentSrtPriority: AudioStudioEngineGate.ThreadPriorityMode =
        AudioStudioEngineGate.ThreadPriorityMode.DEFAULT

    /** Aktivní engine — mění se dle výběru Spinneru + SeekBaru */
    private var segmentationEngine: SegmentationEngine = SegmentationEngineFactory.fromSpinnerIndex(1, 7)

    private lateinit var srtAdapter: SrtBlockAdapter
    private val srtBlocks = mutableListOf<SrtBlock>()

    // SeekBar update job
    private var seekJob: Job? = null

    private val pickMediaLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { loadMedia(it) }
    }

    /**
     * SAF launcher pro import existujícího .srt nebo .vtt souboru k re-editaci.
     * Podporuje jak SRT (SRTčasy s čárkou), tak VTT (s tečkou, s libovolnými hlavikami).
     */
    private val importSrtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val content = contentResolver.openInputStream(uri)
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.readText()
                    ?: throw Exception("Nelze otevřít soubor")

                // Detekce formátu: VTT začíná "WEBVTT", jinak SRT
                val mimeType = contentResolver.getType(uri) ?: ""
                val path     = uri.lastPathSegment ?: ""
                val blocks = if (content.trimStart().startsWith("WEBVTT", ignoreCase = true)
                    || mimeType.contains("vtt") || path.endsWith(".vtt", ignoreCase = true)) {
                    parseVtt(content)
                } else {
                    parseSrt(content)
                }

                withContext(Dispatchers.Main) {
                    if (blocks.isEmpty()) {
                        Toast.makeText(this@SrtEditorActivity,
                            "⚠️ Soubor neobsahuje žádné platné bloky", Toast.LENGTH_SHORT).show()
                        return@withContext
                    }
                    srtBlocks.clear()
                    srtBlocks.addAll(blocks)
                    // Přečíslovat bloky od 1
                    srtBlocks.forEachIndexed { i, b -> b.index = i + 1 }
                    srtAdapter.notifyDataSetChanged()
                    tvTranscriptionStatus.text = "✅ Načteno ${blocks.size} bloků"
                    Toast.makeText(this@SrtEditorActivity,
                        "✅ Načteno ${blocks.size} bloků", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity,
                        "Chyba importu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * SAF launcher pro export SRT — uživatel volí cíl (Downloads, Documents, Drive...).
     * Při zrušení není volán callback (uri == null).
     */
    private val createSrtLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8), 8192).use { bw ->
                        srtBlocks.forEachIndexed { i, b ->
                            bw.write("${i + 1}\n${b.startTime} --> ${b.endTime}\n${b.text}\n\n")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity, "✅ SRT uloženo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity, "Chyba exportu SRT: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * SAF launcher pro export VTT — uživatel volí cíl.
     */
    private val createVttLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8), 8192).use { bw ->
                        bw.write("WEBVTT\n\n")
                        srtBlocks.forEach { b ->
                            val start = b.startTime.replace(",", ".")
                            val end = b.endTime.replace(",", ".")
                            bw.write("$start --> $end\n${b.text}\n\n")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity, "✅ VTT uloženo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity, "Chyba exportu VTT: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * SAF launcher pro export ASS/SSA — Advanced SubStation Alpha.
     * Kompatibilní s: Aegisub, VLC (libass), MPC-HC, mpv.
     *
     * V4+ Styles — výchozí styl:
     *   Font: Arial 48px | Barva: bílá | Obrys: 2px černý | Stín: poloprůhledný
     *   Zarovnání: 2 (dole na středu) — standard pro titulky filmů
     * Barvy: &HAABBGGRR (ABGR hex — ASS standard, pozor: NENÍ RGBA!)
     */
    private val createAssLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { os ->
                    BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8), 8192).use { bw ->
                        // Script Info
                        bw.write("[Script Info]\n")
                        bw.write("ScriptType: v4.00+\n")
                        bw.write("PlayResX: 1920\n")
                        bw.write("PlayResY: 1080\n")
                        bw.write("WrapStyle: 0\n")
                        bw.write("ScaledBorderAndShadow: yes\n")
                        bw.write("YCbCr Matrix: TV.709\n\n")

                        // V4+ Styles — Format řádek MUSÍ mít přesně toto pořadí sloupců
                        bw.write("[V4+ Styles]\n")
                        bw.write("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding\n")
                        // &H00FFFFFF = bílá plně opaque | &H80000000 = černý stín 50% alpha
                        bw.write("Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,2,1,2,10,10,30,1\n\n")

                        // Events
                        bw.write("[Events]\n")
                        bw.write("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n")
                        srtBlocks.forEach { b ->
                            val s    = srtToAssTime(b.startTime)
                            val e    = srtToAssTime(b.endTime)
                            // ASS nový řádek = \N (dvě znaky), ne \n
                            val text = b.text.replace("\n", "\\N")
                            bw.write("Dialogue: 0,$s,$e,Default,,0,0,0,,$text\n")
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity,
                        "✅ ASS uloženo — kompatibilní s VLC / Aegisub",
                        Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtEditorActivity,
                        "Chyba exportu ASS: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupFullscreen() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (settingsManager.hideStatusBar) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    // ========================================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_srt_editor)

        settingsManager = SettingsManager(this)
        setupFullscreen()

        bindViews()
        setupToolbar()
        setupSttSpinner()
        setupSegmentation()
        setupPriorityChipGroup()
        setupButtons()
        setupSrtList()
        setupNlpButtons()

        // Příchozí URI z AudioStudio — médium (audio/video)
        val uriStr = intent.getStringExtra("MEDIA_URI")
        if (!uriStr.isNullOrBlank()) {
            loadMedia(Uri.parse(uriStr))
        }

        // D2: Auto-import existujícího .srt souboru (z AudioRecordingsAdapter — zelená tužka)
        val srtUriStr = intent.getStringExtra("SRT_URI")
        if (!srtUriStr.isNullOrBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val srtUri = Uri.parse(srtUriStr)
                    val content = contentResolver.openInputStream(srtUri)
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.readText()
                        ?: throw Exception("Nelze otevřít SRT soubor")
                    val blocks = parseSrt(content)
                    withContext(Dispatchers.Main) {
                        if (blocks.isNotEmpty()) {
                            srtBlocks.clear()
                            srtBlocks.addAll(blocks)
                            srtBlocks.forEachIndexed { i, b -> b.index = i + 1 }
                            srtAdapter.notifyDataSetChanged()
                            tvTranscriptionStatus.text = "✅ Načteno ${blocks.size} SRT bloků z diktafonu"
                            Log.i(TAG, "D2: Auto-import SRT ${blocks.size} bloků z ${srtUri.lastPathSegment}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "D2: Chyba auto-import SRT: ${e.message}", e)
                }
            }
        }


        switchVideoPreview.setOnCheckedChangeListener { _, _ ->
            mediaUri?.let { loadMedia(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        // Prevence sf error -13 (EACCES) při ztrátě SurfaceView (minimalizace aplikace, zhasnutí displeje)
        try {
            // if (vlcPlayer?.isPlaying == true) {
                // vlcPlayer?.pause()
                if (::btnPlayPause.isInitialized) btnPlayPause.text = "▶"
            // }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelTranscription()
        releasePlayer()
        // Shutdown dedicated SRT thread pool — prevence thread leaku
        srtEditorExecutor?.shutdown()
        srtEditorExecutor = null
    }

    // ========================================================================
    // BINDING
    // ========================================================================

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar_srt_editor)
        ivThumbnail = findViewById(R.id.iv_media_thumbnail)
        surfacePlayer = findViewById(R.id.surface_player)  // LibVLC SurfaceView
        switchVideoPreview = findViewById(R.id.switch_video_preview)
        tvFilename = findViewById(R.id.tv_media_filename)
        tvMeta = findViewById(R.id.tv_media_meta)
        seekbarPlayer = findViewById(R.id.seekbar_player)
        tvPosition = findViewById(R.id.tv_player_position)
        tvDuration = findViewById(R.id.tv_player_duration)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        btnPickMedia = findViewById(R.id.btn_pick_media)
        spinnerStt = findViewById(R.id.spinner_stt_engine)
        progressTranscription = findViewById(R.id.progress_transcription)
        tvTranscriptionStatus = findViewById(R.id.tv_transcription_status)
        btnStartTranscription = findViewById(R.id.btn_start_transcription)
        btnCancelTranscription = findViewById(R.id.btn_cancel_transcription)
        rvSrtBlocks = findViewById(R.id.rv_srt_blocks)
        btnAddBlock = findViewById(R.id.btn_add_block)
        btnExportSrt = findViewById(R.id.btn_export_srt)
        btnExportVtt = findViewById(R.id.btn_export_vtt)
        btnShare = findViewById(R.id.btn_share_srt)
        btnImportSrt = findViewById(R.id.btn_import_srt)
        // Globální operace
        btnShiftAll    = findViewById(R.id.btn_shift_all)
        btnAutoFix     = findViewById(R.id.btn_auto_fix)
        btnFindReplace = findViewById(R.id.btn_find_replace)
        btnExportAss   = findViewById(R.id.btn_export_ass)
        // LibVLC audio + SPU
        layoutAudioTrack = findViewById(R.id.layout_audio_track)
        spinnerAudioTrack = findViewById(R.id.spinner_audio_track)
        tvSpuTracksInfo  = findViewById(R.id.tv_spu_tracks_info)
        // NLP panel
        btnNlpSummarize  = findViewById(R.id.btn_nlp_summarize_srt)
        btnNlpProofread  = findViewById(R.id.btn_nlp_proofread_srt)
        btnNlpTranslate  = findViewById(R.id.btn_nlp_translate_srt)
        btnNlpRewrite    = findViewById(R.id.btn_nlp_rewrite_srt)
        btnNlpSentiment  = findViewById(R.id.btn_nlp_sentiment_srt)
        tvNlpResult      = findViewById(R.id.tv_nlp_result_srt)
        progressNlp      = findViewById(R.id.progress_nlp_srt)
        // Segmentace
        spinnerSegmentMode = findViewById(R.id.spinner_segmentation_mode)
        layoutMaxWords     = findViewById(R.id.layout_max_words)
        seekbarMaxWords    = findViewById(R.id.seekbar_max_words)
        tvMaxWordsValue    = findViewById(R.id.tv_max_words_value)
        switchWordTimestamps = findViewById(R.id.switch_word_timestamps)
        tvWordTsWarning    = findViewById(R.id.tv_word_ts_warning)
        // Per-session thread slider + ThermalGuard toggle (Faze 2)
        seekbarSrtThreads  = findViewById(R.id.seekbar_srt_threads)
        tvSrtThreadsValue  = findViewById(R.id.tv_srt_threads_value)
        switchThermalGuard    = findViewById(R.id.switch_thermal_guard)
        tvThermalHeadroom     = findViewById(R.id.tv_thermal_headroom)
        chipgroupPrioritySrt  = findViewById(R.id.chipgroup_priority_srt)
        tvSrtPriorityWarning  = findViewById(R.id.tv_srt_priority_warning)
    }

    // ========================================================================
    // TOOLBAR
    // ========================================================================

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    // ========================================================================
    // STT SPINNER — Sherpa + Whisper (bez Android Recognizer)
    // ========================================================================

    private fun setupSttSpinner() {
        // Fáze A: Engine spinner per-session — synchronizuje se settingsManager.transcriptEngine
        spinnerStt.visibility = View.VISIBLE
        val engines = arrayOf("🔵 Whisper (offline)", "🟢 Sherpa-ONNX (offline)")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, engines)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerStt.adapter = adapter

        // Syncovat počáteční výběr s globálním nastavením
        val currentEngine = settingsManager.transcriptEngine
        spinnerStt.setSelection(if (currentEngine == "whisper") 0 else 1)

        spinnerStt.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            private var initialized = false
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (!initialized) { initialized = true; return }  // Přeskočit inicializační callback
                val newEngine = if (pos == 0) "whisper" else "sherpa"
                if (settingsManager.transcriptEngine != newEngine) {
                    settingsManager.transcriptEngine = newEngine
                    Log.i(TAG, "🔄 SRT Editor: engine přepnut na $newEngine")
                    Toast.makeText(
                        this@SrtEditorActivity,
                        "Engine: ${engines[pos]} — aktivní pro příští přepis",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }


    // ========================================================================
    // PRIORITY CHIP GROUP (Fáze B)
    // ========================================================================

    private fun setupPriorityChipGroup() {
        chipgroupPrioritySrt.setOnCheckedStateChangeListener { _, checkedIds ->
            currentSrtPriority = when {
                checkedIds.contains(R.id.chip_srt_priority_urgent)  -> AudioStudioEngineGate.ThreadPriorityMode.URGENT
                checkedIds.contains(R.id.chip_srt_priority_bg)      -> AudioStudioEngineGate.ThreadPriorityMode.BACKGROUND
                else                                                 -> AudioStudioEngineGate.ThreadPriorityMode.DEFAULT
            }
            // Zobrazit/skrýt varovaní pro URGENT
            tvSrtPriorityWarning.visibility =
                if (currentSrtPriority == AudioStudioEngineGate.ThreadPriorityMode.URGENT) View.VISIBLE else View.GONE
            Log.d(TAG, "SRT priority přepnuta: ${currentSrtPriority.label}")
        }
    }

    // ========================================================================
    // SEGMENTACE — Spinner A/B/C + SeekBar max slov + Word TS toggle
    // ========================================================================

    private fun setupSegmentation() {
        // Naplň Spinner možnostmi z Factory
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            SegmentationEngineFactory.displayNames
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spinnerSegmentMode.adapter = adapter
        spinnerSegmentMode.setSelection(1) // Výchozí: Mód B (Interpunkce)

        // SeekBar progress 0–9 = slov 1–10; výchozí progress 6 = 7 slov
        tvMaxWordsValue.text = (seekbarMaxWords.progress + 1).toString()

        fun rebuildEngine() {
            val modeIdx  = spinnerSegmentMode.selectedItemPosition
            val maxWords = seekbarMaxWords.progress + 1
            val wordTsOn = switchWordTimestamps.isChecked
            // Mód C vybran a word TS OFF → varovat uživatele
            tvWordTsWarning.visibility = if (modeIdx == 2 && !wordTsOn) View.VISIBLE else View.GONE
            // Max-words panel skryt pro Mód C (timing přímý z engine)
            layoutMaxWords.visibility = View.VISIBLE
            segmentationEngine = SegmentationEngineFactory.fromSpinnerIndex(modeIdx, maxWords)
        }

        spinnerSegmentMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) = rebuildEngine()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        seekbarMaxWords.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvMaxWordsValue.text = (progress + 1).toString()
                if (fromUser) rebuildEngine()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        switchWordTimestamps.setOnCheckedChangeListener { _, _ -> rebuildEngine() }

        // ── Thread slider (per-session, 1–8 vláken) — Orchestrator Faze 2 ──
        // Default: 4 vlákna (odpovídá settingsManager.whisperNThreads default)
        sessionThreadCount = seekbarSrtThreads.progress + 1  // progress 0-7 = 1-8
        tvSrtThreadsValue.text = sessionThreadCount.toString()
        seekbarSrtThreads.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                sessionThreadCount = progress + 1   // 0–7 → 1–8
                tvSrtThreadsValue.text = sessionThreadCount.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        // ThermalGuard default ON (umožní user vypnout pro Android Box/ventilové zařízení)
        switchThermalGuard.isChecked = true
    }

    // ========================================================================
    // MEDIA LOADING
    // ========================================================================

    private fun loadMedia(uri: Uri) {
        mediaUri = uri
        releasePlayer()

        // Metadata přes MediaMetadataRetriever
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this@SrtEditorActivity, uri)

                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val thumbnail = retriever.getFrameAtTime(0)
                retriever.release()

                val sizeBytes = try { contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 0L } catch (_: Exception) { 0L }
                val sizeStr = when {
                    sizeBytes > 1_000_000 -> "${sizeBytes / 1_000_000} MB"
                    sizeBytes > 1000 -> "${sizeBytes / 1000} KB"
                    else -> "$sizeBytes B"
                }

                val displayName = uri.lastPathSegment ?: "neznámý soubor"
                val durationStr = formatMs(durationMs)
                val resStr = if (width != null && height != null) "$width×$height" else "audio"

                withContext(Dispatchers.Main) {
                    tvFilename.text = displayName
                    tvMeta.text = "Délka: $durationStr · $resStr · $sizeStr"
                    if (thumbnail != null) {
                        ivThumbnail.setImageBitmap(thumbnail)
                        ivThumbnail.setPadding(0, 0, 0, 0)
                    }
                    seekbarPlayer.max = durationMs.toInt()
                    tvDuration.text = durationStr
                    initMediaPlayer(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba načítání media metadata", e)
                withContext(Dispatchers.Main) {
                    tvFilename.text = uri.lastPathSegment ?: "neznámý soubor"
                    tvMeta.text = "Metadata nedostupná"
                    initMediaPlayer(uri)
                }
            }
        }
    }

    /**
     * LibVLC 3.7.0 init pipeline:
     * 1. Vytvoří LibVLC instanci (singleton pro tuto Activity)
     * 2. Připraví Media z SAF Uri (content:// → fd://  přes openFileDescriptor)
     * 3. Připojí IVLCVout na SurfaceView pokud je video náhled zapnutý
     * 4. Detekuje audio stopy (IMedia.Track.Type.Audio) → naplní Spinner
     * 5. Detekuje SPU stopy (embedded titulky) → zobrazí info Toast
     */
    /* private fun initVlcPlayer(uri: Uri) {
        releaseVlcPlayer()

        val options = arrayListOf<String>(
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--avcodec-hw=any"  // hw decode povoleno
        )
        libVlc = LibVLC(this, options)
        // Plně kvalifikovaný typ — předchází ambiguity
        val player = org.videolan.libvlc.MediaPlayer(libVlc!!)
        vlcPlayer = player

        // Surface připojení
        if (switchVideoPreview.isChecked) {
            surfacePlayer.visibility = View.VISIBLE
            ivThumbnail.visibility   = View.GONE
            val vout = player.vlcVout
            vout.setVideoSurface(surfacePlayer.holder.surface, surfacePlayer.holder)
            vout.setWindowSize(surfacePlayer.width.coerceAtLeast(1),
                               surfacePlayer.height.coerceAtLeast(1))
            vout.attachViews()
        } else {
            surfacePlayer.visibility = View.GONE
            ivThumbnail.visibility   = View.VISIBLE
        }

        // Media z SAF Uri — LibVLC I/O přesunuto do IO vlákna
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pfd = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw Exception("Nelze otevřít file descriptor")
                vlcPfd = pfd // Uchováme FD dokud hráč žije, jinak LibVLC ztratí přístup
                val media = Media(libVlc!!, pfd.fileDescriptor)
                media.parse(IMedia.Parse.ParseNetwork or IMedia.Parse.FetchLocal)
                
                // Počkej na parse a přečti stopy
                val trackCount = media.trackCount
                val audioTracks = mutableListOf<Pair<Int, String>>() // (trackId, label)
                val spuTracks   = mutableListOf<String>()

                for (i in 0 until trackCount) {
                    val track = media.getTrack(i) ?: continue
                    when (track.type) {
                        IMedia.Track.Type.Audio -> {
                            val lang  = track.language ?: "track $i"
                            val codecStr = track.codec?.toString() ?: "?"
                            audioTracks.add(Pair(track.id, "🔉 $lang [$codecStr]"))
                        }
                        IMedia.Track.Type.Text -> {
                            val lang = track.language ?: "sub $i"
                            spuTracks.add(lang)
                        }
                        else -> {}
                    }
                }

                withContext(Dispatchers.Main) {
                    player.media = media

                    // Audio track Spinner — zobrazit jen při >1 stopě
                    if (audioTracks.size > 1) {
                        val labels = audioTracks.map { it.second }
                        val adapter = ArrayAdapter(this@SrtEditorActivity, android.R.layout.simple_spinner_item, labels)
                            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                        spinnerAudioTrack.adapter = adapter
                        spinnerAudioTrack.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                                vlcPlayer?.setAudioTrack(audioTracks[pos].first)
                            }
                            override fun onNothingSelected(p: AdapterView<*>?) {}
                        }
                        layoutAudioTrack.visibility = View.VISIBLE
                    } else {
                        layoutAudioTrack.visibility = View.GONE
                    }

                    // SPU (embedded titulky) info
                    if (spuTracks.isNotEmpty()) {
                        tvSpuTracksInfo.text = "📌 Embedded titulky: ${spuTracks.joinToString(", ")}"
                        tvSpuTracksInfo.visibility = View.VISIBLE
                    } else {
                        tvSpuTracksInfo.visibility = View.GONE
                    }

                    player.play()
                }

                media.release()
                // pfd.close() smazáno! Zavře se až v releaseVlcPlayer(), jinak spadne playback ("Bad file descriptor")

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Log.e(TAG, "LibVLC init chyba", e)
                    Toast.makeText(this@SrtEditorActivity, "LibVLC: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    } */

    /* private fun releaseVlcPlayer() {
        seekJob?.cancel()
        // Local val — vlcPlayer je mutable var, nelze použít smart cast přímo
        val p = vlcPlayer
        try { p?.stop(); p?.vlcVout?.detachViews() } catch (_: Exception) {}
        p?.release()
        vlcPlayer = null
        libVlc?.release()
        libVlc = null
        
        try { vlcPfd?.close() } catch (_: Exception) {}
        vlcPfd = null

        if (::layoutAudioTrack.isInitialized) layoutAudioTrack.visibility = View.GONE
        if (::tvSpuTracksInfo.isInitialized)  tvSpuTracksInfo.visibility  = View.GONE
        if (::btnPlayPause.isInitialized)     btnPlayPause.text = "▶"
    } */

    private fun initNativeMediaPlayer(uri: Uri) {
        // releaseVlcPlayer()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        try {
            mediaPlayer = android.media.MediaPlayer().apply {
                setDataSource(this@SrtEditorActivity, uri)
                if (switchVideoPreview.isChecked) {
                    setDisplay(surfacePlayer.holder)
                }
                setOnPreparedListener { 
                    // Přehrávač je připraven asynchronně
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaPlayer init error", e)
            Toast.makeText(this, "Chyba přehrávače: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initMediaPlayer(uri: Uri) {
        // if (switchVideoPreview.isChecked) {
        //     initVlcPlayer(uri)
        // } else {
            initNativeMediaPlayer(uri)
        // }
    }

    private fun releasePlayer() {
        // releaseVlcPlayer()
        // Android MediaPlayer fallback
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun playMedia() {
        // val p = vlcPlayer
        // if (p != null) p.play() else mediaPlayer?.start()
        mediaPlayer?.start()
        btnPlayPause.text = "⏸"
        startSeekUpdates()
    }

    private fun pauseMedia() {
        // val p = vlcPlayer
        // if (p != null) p.pause() else mediaPlayer?.pause()
        mediaPlayer?.pause()
        btnPlayPause.text = "▶"
        seekJob?.cancel()
    }

    private fun seekMediaTo(pos: Int) {
        // val p = vlcPlayer
        // if (p != null) p.time = pos.toLong() else mediaPlayer?.seekTo(pos)
        mediaPlayer?.seekTo(pos)
    }

    private val isMediaPlaying: Boolean
        get() {
            // val p = vlcPlayer
            // return if (p != null) p.isPlaying else (mediaPlayer?.isPlaying == true)
            return mediaPlayer?.isPlaying == true
        }

    private val currentMediaPosition: Int
        get() {
            // val p = vlcPlayer
            // return if (p != null) p.time.toInt() else (mediaPlayer?.currentPosition ?: 0)
            return mediaPlayer?.currentPosition ?: 0
        }

    private fun startSeekUpdates() {
        seekJob?.cancel()
        seekJob = lifecycleScope.launch {
            while (isActive && isMediaPlaying) {
                val pos = currentMediaPosition
                seekbarPlayer.progress = pos
                tvPosition.text = formatMs(pos.toLong())

                // Synchronizace titulků: najít aktivní blok
                val currentMs = pos.toLong()
                val activeIndex = srtBlocks.indexOfFirst { block ->
                    val startMs = parseMs(block.startTime)
                    val endMs = parseMs(block.endTime)
                    currentMs in startMs..endMs
                }

                if (activeIndex != -1) {
                    rvSrtBlocks.smoothScrollToPosition(activeIndex)
                }

                delay(200) // 5fps pro plynulý scroll
            }
        }
    }

    private fun parseMs(timeStr: String): Long {
        try {
            val parts = timeStr.split(":", ",")
            if (parts.size == 4) {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toLong()
                val ms = parts[3].toLong()
                return h * 3600000 + m * 60000 + s * 1000 + ms
            }
        } catch (e: Exception) {}
        return 0L
    }

    // ========================================================================
    // BUTTONS
    // ========================================================================

    private fun setupButtons() {
        findViewById<MaterialButton>(R.id.btn_open_live_editor).setOnClickListener {
            val intent = Intent(this, com.example.llama.srt.ui.editor.SrtVisualEditorActivity::class.java)
            mediaUri?.let { uri ->
                intent.putExtra(com.example.llama.srt.ui.editor.SrtVisualEditorActivity.EXTRA_VIDEO_URI, uri.toString())
            }
            if (srtBlocks.isNotEmpty()) {
                try {
                    val tempSrt = File(cacheDir, "temp_live_editor_${System.currentTimeMillis()}.srt")
                    BufferedWriter(OutputStreamWriter(java.io.FileOutputStream(tempSrt), StandardCharsets.UTF_8), 8192).use { bw ->
                        srtBlocks.forEachIndexed { i, b ->
                            bw.write("${i + 1}\n${b.startTime} --> ${b.endTime}\n${b.text}\n\n")
                        }
                    }
                    intent.putExtra(com.example.llama.srt.ui.editor.SrtVisualEditorActivity.EXTRA_SRT_PATH, tempSrt.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Chyba při přípravě SRT pro LiveEditor: ${e.message}")
                }
            }
            startActivity(intent)
        }

        btnPickMedia.setOnClickListener { pickMediaLauncher.launch("*/*") }

        btnPlayPause.setOnClickListener {
            if (mediaPlayer == null && !switchVideoPreview.isChecked) return@setOnClickListener
            if (isMediaPlaying) {
                pauseMedia()
            } else {
                playMedia()
            }
        }

        seekbarPlayer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { seekMediaTo(progress); tvPosition.text = formatMs(progress.toLong()) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        btnStartTranscription.setOnClickListener { startTranscription() }
        btnCancelTranscription.setOnClickListener { cancelTranscription() }
        btnAddBlock.setOnClickListener { addManualBlock() }
        btnImportSrt.setOnClickListener {
            // SAF dialog: umožní vybrat .srt nebo .vtt ze zařízení
            importSrtLauncher.launch(arrayOf("text/plain", "text/x-srt", "application/x-subrip",
                "text/vtt", "*/*"))
        }
        btnShiftAll.setOnClickListener    { showShiftDialog() }
        btnAutoFix.setOnClickListener     { autoFixOverlaps() }
        btnFindReplace.setOnClickListener { showFindReplaceDialog() }
        btnExportSrt.setOnClickListener { exportSrt() }
        btnExportVtt.setOnClickListener { exportVtt() }
        btnShare.setOnClickListener     { shareSrt() }
        btnExportAss.setOnClickListener {
            if (srtBlocks.isEmpty()) {
                Toast.makeText(this, "Žádné bloky k exportu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            createAssLauncher.launch("subtitles_$ts.ass")
        }
    }

    // ========================================================================
    // NLP OPERACE NAD TRANSKRIPTEM
    // ========================================================================

    /**
     * Nastaví klikací handlery pro NLP tlačítka v Panelu 5.
     * Zdroj textu: všechny srtBlocks → plain text bez časových razítek.
     *
     * Engine ChipGroup (rychlý přepínáč):
     *   AUTO   = globální nastavení (SettingsManager)
     *   MLKit  = forceMode MLKIT_ONLY (rychlý, offline)
     *   AICore = forceMode AICORE_ONLY (Gemini Nano)
     *   Heur.  = forceMode HEURISTIC_ONLY (TF-IDF, bez modelu)
     *
     * Chunking pro dlouhé přepisy: NlpActionHandler interně využívá
     * MeetingIntelligenceRefiner s chunk/batch processingem.
     */
    private fun setupNlpButtons() {
        // Programatické přidání ChipGroup pro výber enginu
        val chipGroup = com.google.android.material.chip.ChipGroup(this).apply {
            isSingleSelection  = true
            isSelectionRequired = true
            val chips = listOf(
                Triple(R.id.chip_srt_engine_auto,  "AUTO",  null                            ),
                Triple(R.id.chip_srt_engine_mlkit, "MLKit", NlpEngineMode.MLKIT_ONLY       ),
                Triple(R.id.chip_srt_engine_aicore,"AICore",NlpEngineMode.AICORE_ONLY      ),
                Triple(R.id.chip_srt_engine_llama, "Llama", NlpEngineMode.LLM_ONLY       ),
                Triple(R.id.chip_srt_engine_heur,  "Heur.", NlpEngineMode.HEURISTIC_ONLY  )
            )
            chips.forEachIndexed { i, (id, label, _) ->
                com.google.android.material.chip.Chip(this@SrtEditorActivity).apply {
                    this.id        = id
                    text           = label
                    textSize       = 11f
                    isCheckable    = true
                    isChecked      = (i == 0)
                    addView(this)
                }
            }
        }
        // Vlož ChipGroup zabalený do HorizontalScrollView nad první NLP tlačítko (do hlavního vertical panelu)
        val scrollWrapper = android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipGroup)
        }
        val horizontalPanel = btnNlpSummarize.parent as? android.view.ViewGroup
        val mainPanel = horizontalPanel?.parent as? android.view.ViewGroup
        mainPanel?.addView(scrollWrapper, mainPanel.indexOfChild(horizontalPanel))

        // Helper pro čtení zvoleného módu
        fun selectedMode(): NlpEngineMode? = when (chipGroup.checkedChipId) {
            R.id.chip_srt_engine_mlkit  -> NlpEngineMode.MLKIT_ONLY
            R.id.chip_srt_engine_aicore -> NlpEngineMode.AICORE_ONLY
            R.id.chip_srt_engine_llama  -> NlpEngineMode.LLM_ONLY
            R.id.chip_srt_engine_heur   -> NlpEngineMode.HEURISTIC_ONLY
            else                        -> null // AUTO = žádný forceMode
        }

        btnNlpSummarize.setOnClickListener {
            launchSrtNlpAction(ActionType.NLP_SUMMARIZE, "🧠 Sumarizace", selectedMode())
        }
        btnNlpProofread.setOnClickListener {
            launchSrtNlpAction(ActionType.NLP_PROOFREAD, "✏️ Korektura", selectedMode())
        }
        btnNlpTranslate.setOnClickListener {
            launchSrtNlpAction(ActionType.NLP_TRANSLATE, "🌐 Překlad", selectedMode())
        }
        btnNlpRewrite.setOnClickListener {
            launchSrtNlpAction(ActionType.NLP_REWRITE, "✍️ Přepis", selectedMode())
        }
        btnNlpSentiment.setOnClickListener {
            launchSrtNlpAction(ActionType.NLP_SENTIMENT, "💜 Sentiment", selectedMode())
        }
    }

    /**
     * Spustí NLP akci nad kompletním textem transkriptu z srtBlocks.
     *
     * Vstup: plain text sestavený z bloků (bez časových razítek).
     * Výstup: výsledek zobrazen v tvNlpResult + scrolluje nahoru.
     *
     * Pro prázdný transkript zobrazí varování — NLP nelze spustit bez vstupu.
     */
    private fun launchSrtNlpAction(
        actionType: ActionType,
        label: String,
        forceMode: NlpEngineMode? = null
    ) {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "⚠️ Transkript je prázdný — spusť nejdříve přepis", Toast.LENGTH_SHORT).show()
            return
        }

        // Sestavení plain textu — jen text bloků bez SRT časovacích hlaviček
        val fullText = srtBlocks.joinToString("\n") { it.text.trim() }

        Log.i(TAG, "NLP $label: ${fullText.length} znaků z ${srtBlocks.size} SRT bloků")

        // UI feedback: disabled + progress
        val nlpButtons = listOf(btnNlpSummarize, btnNlpProofread, btnNlpTranslate, btnNlpRewrite, btnNlpSentiment)
        nlpButtons.forEach { it.isEnabled = false }
        progressNlp.visibility = View.VISIBLE
        tvNlpResult.text = "⏳ $label — zpracovávám ${fullText.length} znaků..."

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val result = try {
                nlpActionHandler.execute(actionType, fullText, forceMode)
            } catch (e: Exception) {
                Log.e(TAG, "NLP $label selhala", e)
                "❌ $label selhala: ${e.message}"
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                progressNlp.visibility = View.GONE
                tvNlpResult.text = "✅ Poslední akce dokončena: $label"
                nlpButtons.forEach { it.isEnabled = true }

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
            .setNeutralButton("📋 Kopírovat") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI výsledek", result))
                Toast.makeText(this@SrtEditorActivity, "📋 Zkopírováno", Toast.LENGTH_SHORT).show()
            }
            .show()
    }


    private fun startTranscription() {
        val uri = mediaUri ?: run {
            Toast.makeText(this, "Nejprve importuj médium (🎬)", Toast.LENGTH_SHORT).show(); return
        }

        srtBlocks.clear()
        srtAdapter.notifyDataSetChanged()
        progressTranscription.visibility = View.VISIBLE
        progressTranscription.progress = 0
        tvTranscriptionStatus.text = "Extrakce PCM..."
        btnStartTranscription.isEnabled = false
        btnCancelTranscription.isEnabled = true

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        // Timeout 4h = safety guard pro vícenhodinové přepisy bez rizika infinite WakeLock
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AudioStudio:SrtTranscribeWakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L)

        // Fáze B: Nastavit prioritu vlákna dle user volby z ChipGroup
        android.os.Process.setThreadPriority(currentSrtPriority.osValue)
        Log.i(TAG, "SRT přepis startuje s prioritou: ${currentSrtPriority.label} (osValue=${currentSrtPriority.osValue})")

        // Orchestrator Faze 2: per-session slider + ThermalGuard toggle
        val sessionThreads = sessionThreadCount  // Lokální per-session hodnota ze slideru
        val thermalGuard: com.example.llama.srt.ThermalGuard? = if (switchThermalGuard.isChecked) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            com.example.llama.srt.ThermalGuard(pm).also { guard ->
                // P2: Zobrazit aktuální headroom při startu přepisu
                val headroom = guard.currentHeadroom()
                val headroomPct = (headroom * 100).toInt()
                val indicator = when {
                    headroom > 0.6f -> "✅ ${headroomPct}%"
                    headroom > 0.3f -> "⚠️ ${headroomPct}%"
                    else            -> "🔥 ${headroomPct}%"
                }
                tvThermalHeadroom.text = indicator
                android.util.Log.d(TAG, "ThermalGuard headroom: $headroomPct% → vlakna: ${guard.adaptThreads(sessionThreads)}")
            }
        } else {
            tvThermalHeadroom.text = "--"
            null  // null = ThermalGuard vypnutý (Android Box, zařízení s ventilatorem)
        }
        val (srtProfile, executor) = com.example.llama.srt.TranscriptProfileOrchestrator.srtEditorProfile(
            sessionThreads = sessionThreads,
            thermalGuard   = thermalGuard
        )
        srtEditorExecutor?.shutdown() // Uklidit předchozí pool pokud session nebyla řádně ukončena
        srtEditorExecutor = executor

        // SRT_EDITOR: Dedicated dispatcher — CPU-bound extrakce+inference (ne Dispatchers.IO = I/O pool)
        transcribeJob = lifecycleScope.launch(srtProfile.dispatcher) {
            try {
                // Priorita je nastavena v ThreadFactory při vzniku vlákna — zde ji jen logujeme
                android.util.Log.d(TAG, "SRT přepis: ${srtProfile.numThreads} vláken, priorita=${srtProfile.osThreadPriority}")

                val pcmFilePair = extractPcmToFile(uri) ?: throw Exception("PCM extrakce selhala")
                val pcmFile = pcmFilePair.first
                val inputSr = pcmFilePair.second

                val fileSizeMB = pcmFile.length() / (1024 * 1024)
                withContext(kotlinx.coroutines.Dispatchers.Main) { tvTranscriptionStatus.text = "Přepisuji ($fileSizeMB MB RAW audio)..." }

                transcribeViaAgent(pcmFile, inputSr)
                pcmFile.delete()

            } catch (e: kotlinx.coroutines.CancellationException) {
                withContext(kotlinx.coroutines.Dispatchers.Main) { tvTranscriptionStatus.text = "Přepis zrušen" }
            } catch (e: Exception) {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    tvTranscriptionStatus.text = "Chyba: ${e.message}"
                    Toast.makeText(this@SrtEditorActivity, "Chyba přepisu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    btnStartTranscription.isEnabled = true
                    btnCancelTranscription.isEnabled = false
                    progressTranscription.visibility = View.GONE
                    AudioStudioEngineGate.updateTask(AudioStudioEngineGate.StudioTask.NONE)
                }
                wakeLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private fun cancelTranscription() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        transcribeJob?.cancel()
        transcribeJob = null
        progressTranscription.visibility = View.GONE
        btnStartTranscription.isEnabled = true
        btnCancelTranscription.isEnabled = false
        tvTranscriptionStatus.text = ""
    }

    /** Extrakce libovolného audia z média do lokálního RAW PCM (16-bit) souboru pro 0 RAM footprint u 5h videí */
    private suspend fun extractPcmToFile(uri: Uri): Pair<File, Int>? {
        return withContext(Dispatchers.IO) {
            val pcmFile = File(cacheDir, "temp_extract_${System.currentTimeMillis()}.raw")

            // FFmpeg (NativeVideoEngine) — jediný engine, plná podpora MKV/AVI/AC3/DTS
            if (com.example.llama.media.NativeVideoEngine.isAvailable()) {
                val nativeEngine = com.example.llama.media.NativeVideoEngine(this@SrtEditorActivity)
                val success = nativeEngine.extractAudioForAsr(uri, pcmFile)
                if (success && pcmFile.exists() && pcmFile.length() > 0) {
                    Log.i(TAG, "Audio extrahováno přes FFmpeg NativeVideoEngine.")
                    return@withContext Pair(pcmFile, 16000)
                }
                Log.e(TAG, "FFmpeg extrakce selhala — NativeVideoEngine vrátil chybu.")
            } else {
                Log.e(TAG, "FFmpeg není dostupný — libavutil.so se nepodařilo načíst (TLS crash).")
            }

            withContext(kotlinx.coroutines.Dispatchers.Main) {
                Toast.makeText(
                    this@SrtEditorActivity,
                    "⚠️ Nepodporovaný formát videa nebo selhala extrakce audia.\n" +
                    "Ověřte, zda video obsahuje platnou zvukovou stopu.",
                    Toast.LENGTH_LONG
                ).show()
                tvTranscriptionStatus.text = "Extrakce zvuku selhala"
            }
            return@withContext null
        }
    }

    /**
     * Přepis přes TranscriptAgent ze Service (Zero RAM Duplication).
     * Vyžaduje běžící AI Agent (Accessibility Service).
     * Agent se lazy-inicializuje v Service bez duplicitních modelů v RAM.
     */
    private suspend fun transcribeViaAgent(pcmFile: File, inputSr: Int) {
        val service = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
        if (service == null) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SrtEditorActivity,
                    "❌ AI Agent (Accessibility Service) neběží!\nZapněte ho: Nastavení → Usnadnění přístupu → AI Agent",
                    Toast.LENGTH_LONG
                ).show()
                tvTranscriptionStatus.text = "Chyba: Spusťte AI Agent v nastavení"
            }
            return
        }
        val agent = service.getOrCreateTranscriptAgentForSrt()
        withContext(Dispatchers.Main) {
            tvTranscriptionStatus.text = "Inicializace ASR enginu..."
        }

        val chunkSamples = inputSr * CHUNK_SECS
        val chunkBytes = chunkSamples * 2
        val totalBytes = pcmFile.length()
        val totalChunks = ((totalBytes + chunkBytes - 1) / chunkBytes).toInt()

        java.io.FileInputStream(pcmFile).use { fis ->
            for (chunkIdx in 0 until totalChunks) {
                coroutineContext.ensureActive()

                val buffer = ByteArray(chunkBytes)
                var readBytes = 0
                while (readBytes < chunkBytes) {
                    val read = fis.read(buffer, readBytes, chunkBytes - readBytes)
                    if (read <= 0) break
                    readBytes += read
                }
                if (readBytes <= 0) break

                val shortBuffer = ByteBuffer.wrap(buffer, 0, readBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val rawShorts = ShortArray(shortBuffer.remaining())
                shortBuffer.get(rawShorts)

                // Downsampling pole na 16kHz pole ShortArray pro Agenta
                val targetPcm = if (inputSr != SAMPLE_RATE) {
                    val ratio = inputSr.toFloat() / SAMPLE_RATE
                    val targetSize = (rawShorts.size / ratio).toInt()
                    ShortArray(targetSize) { i ->
                        rawShorts.getOrElse((i * ratio).toInt()) { 0 }
                    }
                } else {
                    rawShorts
                }

                // SRT_EDITOR offline batch — Dispatchers.Default (CPU-bound inference, ne IO pool)
                // THREAD_PRIORITY_DEFAULT (0): UI nezamrzne, baterka se nespálí
                val text = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
                    agent.transcribeOfflineChunk(targetPcm)
                }

                if (text == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SrtEditorActivity, "Přepis selhal (nepodporovaný engine nebo chyba)", Toast.LENGTH_SHORT).show()
                    }
                    break
                }

                val progress       = ((chunkIdx + 1) * 100 / totalChunks)
                val chunkStartMs   = chunkIdx * CHUNK_SECS * 1000L
                val chunkDurationMs = (rawShorts.size.toLong() * 1000) / inputSr

                withContext(Dispatchers.Main) {
                    progressTranscription.progress = progress
                    tvTranscriptionStatus.text = "Zpracováno: chunk ${chunkIdx + 1}/$totalChunks ($progress%)"
                    if (text.isNotBlank()) {
                        // SegmentationEngine: rozděl chunk text do přirozených bloků
                        // wordTimestamps = null (Fáze 5 dodá word-level data z Whisper JNI)
                        val newBlocks = segmentationEngine.segment(
                            text            = text.trim(),
                            chunkStartMs    = chunkStartMs,
                            chunkDurationMs = chunkDurationMs,
                            wordTimestamps  = null
                        )
                        val insertFrom = srtBlocks.size
                        // Přčíslovat dle aktuálního počtu
                        newBlocks.forEachIndexed { i, b -> b.index = insertFrom + i + 1 }
                        srtBlocks.addAll(newBlocks)
                        srtAdapter.notifyItemRangeInserted(insertFrom, newBlocks.size)
                        if (newBlocks.isNotEmpty()) rvSrtBlocks.scrollToPosition(srtBlocks.size - 1)
                    }
                }
            }
        }
        withContext(Dispatchers.Main) { tvTranscriptionStatus.text = "✅ Přepis dokončen (${srtBlocks.size} bloků)" }
    }

    // ========================================================================
    // SRT LIST
    // ========================================================================

    private fun setupSrtList() {
        srtAdapter = SrtBlockAdapter(srtBlocks) { position ->
            srtBlocks.removeAt(position)
            srtAdapter.notifyItemRemoved(position)
            // Přečíslovat od pozice smazání — notifyItemRangeChanged (ne notifyDataSetChanged = O(n) re-render)
            srtBlocks.forEachIndexed { i, b -> b.index = i + 1 }
            if (position < srtBlocks.size) {
                srtAdapter.notifyItemRangeChanged(position, srtBlocks.size - position)
            }
        }
        rvSrtBlocks.layoutManager = LinearLayoutManager(this)
        rvSrtBlocks.adapter = srtAdapter
        rvSrtBlocks.isNestedScrollingEnabled = true
        rvSrtBlocks.itemAnimator = null
    }

    private fun addManualBlock() {
        val idx = srtBlocks.size + 1
        val lastEnd = srtBlocks.lastOrNull()?.endTime ?: "00:00:00,000"
        srtBlocks.add(SrtBlock(idx, lastEnd, lastEnd, ""))
        srtAdapter.notifyItemInserted(srtBlocks.size - 1)
        rvSrtBlocks.scrollToPosition(srtBlocks.size - 1)
    }

    // ========================================================================
    // EXPORT
    // ========================================================================

    private fun exportSrt() {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "Žádné SRT bloky k exportu", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        // SAF dialog: uživatel si sám zvolí místo uložení (Downloads, Documents, Drive...)
        createSrtLauncher.launch("subtitles_$ts.srt")
    }

    private fun exportVtt() {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "Žádné bloky k exportu", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
        createVttLauncher.launch("subtitles_$ts.vtt")
    }

    private fun shareSrt() {
        if (srtBlocks.isEmpty()) { Toast.makeText(this, "Žádné bloky ke sdílení", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val file = File(cacheDir, "share_${System.currentTimeMillis()}.srt")
                BufferedWriter(OutputStreamWriter(java.io.FileOutputStream(file), StandardCharsets.UTF_8), 8192).use { bw ->
                    srtBlocks.forEachIndexed { i, b ->
                        bw.write("${i + 1}\n${b.startTime} --> ${b.endTime}\n${b.text}\n\n")
                    }
                }
                val uri = FileProvider.getUriForFile(this@SrtEditorActivity, "$packageName.provider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                withContext(Dispatchers.Main) { startActivity(Intent.createChooser(shareIntent, "Sdílet SRT")) }
            } catch (e: Exception) { withContext(Dispatchers.Main) { Toast.makeText(this@SrtEditorActivity, "Chyba sdílení", Toast.LENGTH_SHORT).show() } }
        }
    }

    // ========================================================================
    // GLOBÁLNÍ OPERACE
    // ========================================================================

    /**
     * Posun všech bloků o zadaný offset v ms.
     * Kladná hodnota = zpozdění titulků, záporná = urychlení.
     * Idempotentní: start nikdy neklesne pod 0ms.
     */
    private fun showShiftDialog() {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "Žádné bloky k posunutí", Toast.LENGTH_SHORT).show()
            return
        }
        val input = android.widget.EditText(this).apply {
            hint = "Offset v ms (např. +1000 nebo -500)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                        android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            setPadding(48, 32, 48, 32)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("⏱ Posun všech bloků")
            .setMessage("Kladná hodnota = zpozdění, záporná = urychlení")
            .setView(input)
            .setPositiveButton("Použít") { _, _ ->
                val offsetMs = input.text.toString().toLongOrNull()
                if (offsetMs == null) {
                    Toast.makeText(this, "Neplatná hodnota", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (kotlin.math.abs(offsetMs) > 3_600_000L) {
                    Toast.makeText(this, "Maximální posun je ±1 hodina", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                shiftAllBlocks(offsetMs)
                Toast.makeText(this, "✅ Posunuto o ${offsetMs}ms", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    private fun shiftAllBlocks(offsetMs: Long) {
        srtBlocks.forEachIndexed { i, block ->
            val newStart = (parseMs(block.startTime) + offsetMs).coerceAtLeast(0L)
            val newEnd   = (parseMs(block.endTime)   + offsetMs).coerceAtLeast(newStart + 100L)
            srtBlocks[i] = block.copy(startTime = formatMs(newStart), endTime = formatMs(newEnd))
        }
        srtAdapter.notifyDataSetChanged()
    }

    /**
     * Auto-Fix: odstraní prázdné bloky + opraví překryvy (konec A > začátek B → začátek B = konec A + 1ms).
     * Přečísluje bloky od 1. Idempotentní.
     */
    private fun autoFixOverlaps() {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "Žádné bloky k opravě", Toast.LENGTH_SHORT).show()
            return
        }
        val beforeCount = srtBlocks.size

        // 1. Smazat prázdné bloky
        srtBlocks.removeAll { it.text.isBlank() }

        // 2. Seřadit dle startTime (pro případ neseřazeného importu)
        srtBlocks.sortWith(compareBy { parseMs(it.startTime) })

        // 3. Opravit překryvy: konec[i-1] > začátek[i] → začátek[i] = konec[i-1] + 1ms
        for (i in 1 until srtBlocks.size) {
            val prevEndMs   = parseMs(srtBlocks[i - 1].endTime)
            val curStartMs  = parseMs(srtBlocks[i].startTime)
            if (curStartMs <= prevEndMs) {
                val fixedStart = prevEndMs + 1L
                // Zachovej délku bloku pokud to nezpůsobí zápornou délku
                val origDuration = parseMs(srtBlocks[i].endTime) - curStartMs
                val fixedEnd = (fixedStart + origDuration).coerceAtLeast(fixedStart + 100L)
                srtBlocks[i] = srtBlocks[i].copy(
                    startTime = formatMs(fixedStart),
                    endTime   = formatMs(fixedEnd)
                )
            }
        }

        // 4. Přečíslovat od 1
        srtBlocks.forEachIndexed { i, b -> b.index = i + 1 }
        srtAdapter.notifyDataSetChanged()

        val removed = beforeCount - srtBlocks.size
        val msg = buildString {
            append("✅ Auto-Fix hotov")
            if (removed > 0) append(" — odstraněno $removed prázdných bloků")
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    /**
     * Najít a nahradit text ve všech SRT blocích.
     * Case-insensitive. Hlásí počet provedených náhrad.
     */
    private fun showFindReplaceDialog() {
        if (srtBlocks.isEmpty()) {
            Toast.makeText(this, "Žádné bloky k prohledání", Toast.LENGTH_SHORT).show()
            return
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val etFind = android.widget.EditText(this).apply {
            hint = "Hledat..."
            setSingleLine()
        }
        val etReplace = android.widget.EditText(this).apply {
            hint = "Nahradit za..."
            setSingleLine()
        }
        container.addView(android.widget.TextView(this).apply { text = "Hledat:" })
        container.addView(etFind)
        container.addView(android.widget.TextView(this).apply {
            text = "Nahradit za:"
            setPadding(0, 16, 0, 0)
        })
        container.addView(etReplace)

        android.app.AlertDialog.Builder(this)
            .setTitle("🔍 Najít a nahradit")
            .setView(container)
            .setPositiveButton("Nahradit vše") { _, _ ->
                val findText    = etFind.text.toString()
                val replaceText = etReplace.text.toString()
                if (findText.isBlank()) {
                    Toast.makeText(this, "Zadejte hledaný text", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                var replacements = 0
                srtBlocks.forEachIndexed { i, block ->
                    val newText = block.text.replace(findText, replaceText, ignoreCase = true)
                    if (newText != block.text) {
                        // Počítáme počet nahrazení (case-insensitive occurrences)
                        replacements += block.text.split(
                            findText, ignoreCase = true
                        ).size - 1
                        srtBlocks[i] = block.copy(text = newText)
                    }
                }
                srtAdapter.notifyDataSetChanged()
                Toast.makeText(this,
                    if (replacements > 0) "✅ Provedeno $replacements náhrad"
                    else "Žádná shoda nenalezena",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }

    // ========================================================================
    // UTILS
    // ========================================================================

    private fun formatMs(ms: Long): String {
        val h = ms / 3_600_000; val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1000; val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, millis)
    }

    // ========================================================================
    // SRT / VTT PARSERS
    // ========================================================================

    /**
     * Parsuje standardní SRT obsah do seznamu SrtBlock.
     * Formát bloku:
     *   <číslo>
     *   HH:MM:SS,mmm --> HH:MM:SS,mmm
     *   <text (může být víceřádkový)>
     *   <prázdný řádek>
     */
    private fun parseSrt(content: String): List<SrtBlock> {
        val blocks = mutableListOf<SrtBlock>()
        // Normalizace CRLF → LF, split na dvojité prázdné řádky
        val rawBlocks = content.replace("\r\n", "\n").trim().split(Regex("\n{2,}"))

        for (raw in rawBlocks) {
            val lines = raw.trim().lines()
            if (lines.size < 2) continue

            // Hledej řádek s "-->" — nezávisle na pořadí (robustní vůči exportérům bez čísla)
            val timingIdx = lines.indexOfFirst { it.contains("-->") }
            if (timingIdx < 0) continue

            val timingLine = lines[timingIdx]
            val timeParts  = timingLine.split("-->")
            if (timeParts.size < 2) continue

            val startTime = timeParts[0].trim()
            // Odříznout poziční info (X1:0 Y1:0 ...) za časem konce
            val endTime   = timeParts[1].trim().split(Regex("\\s+"))[0]

            // Text = vše za timing řádkem, spojeno \n (víceřádkové titulky)
            val text = lines.drop(timingIdx + 1).joinToString("\n").trim()
            if (text.isEmpty()) continue

            // Index z prvního řádku pokud je číslo, jinak průběžný
            val idx = lines.getOrNull(timingIdx - 1)?.trim()?.toIntOrNull()
                ?: (blocks.size + 1)

            blocks.add(SrtBlock(idx, startTime, endTime, text))
        }
        return blocks
    }

    /**
     * Parsuje WebVTT obsah do seznamu SrtBlock.
     *
     * Robustní vůči:
     * - Libovolným hlavičkám (NOTE, STYLE, REGION sekce) před prvním cue
     * - Cue ID řádkům (text před -->)
     * - Poziční metadata za časem konce (align:middle position:50%)
     * - Timestamp formátu MM:SS.mmm i HH:MM:SS.mmm
     * Timestamps převedeny z tečky → čárka pro interní SRT formát.
     */
    private fun parseVtt(content: String): List<SrtBlock> {
        val blocks  = mutableListOf<SrtBlock>()
        val lines   = content.replace("\r\n", "\n").lines()
        var i       = 0

        // Přeskoč WEBVTT hlavičku a všechny metadata bloky (NOTE, STYLE, REGION)
        // dokud nenarazíme na první řádek s "-->"
        while (i < lines.size && !lines[i].contains("-->")) i++

        while (i < lines.size) {
            val line = lines[i]
            if (!line.contains("-->")) {
                i++
                continue
            }

            // Timing řádek nalezen
            val timeParts = line.split("-->")
            if (timeParts.size < 2) { i++; continue }

            // VTT čas: "00:01:23.456" nebo "01:23.456" → normalize na HH:MM:SS,mmm
            val rawStart = timeParts[0].trim()
            val rawEnd   = timeParts[1].trim().split(Regex("\\s+"))[0] // odřízni poziční metadata

            val startTime = vttTimeToSrt(rawStart)
            val endTime   = vttTimeToSrt(rawEnd)
            i++

            // Sbírej text cue dokud nenarazíme na prázdný řádek nebo konec souboru
            val textLines = mutableListOf<String>()
            while (i < lines.size && lines[i].isNotBlank()) {
                textLines.add(lines[i])
                i++
            }

            val text = textLines.joinToString("\n").trim()
            if (text.isNotEmpty()) {
                blocks.add(SrtBlock(blocks.size + 1, startTime, endTime, text))
            }
        }
        return blocks
    }

    /**
     * Převede VTT timestamp na SRT timestamp.
     * "1:23.456" nebo "00:01:23.456" → "00:01:23,456"
     */
    private fun vttTimeToSrt(vttTime: String): String {
        val normalized = vttTime.replace(".", ",")
        val parts      = normalized.split(":")
        return when (parts.size) {
            2    -> "00:${parts[0].padStart(2,'0')}:${parts[1]}"  // MM:SS,mmm → HH:MM:SS,mmm
            3    -> "${parts[0].padStart(2,'0')}:${parts[1]}:${parts[2]}"
            else -> normalized
        }
    }

    /**
     * Převede SRT timestamp na ASS timestamp.
     * SRT:  "00:01:23,456"  (HH:MM:SS,mmm — 3 číslice ms)
     * ASS:  "0:01:23.45"   (H:MM:SS.cs  — setiny, bez leading zero u hodin)
     *
     * POZOR: ASS používá setiny sekundy (centisekundy), ne milisekundy!
     * 456ms → 45 centisekund (zaokrouhlení dolů — aby titulky nezmizely předčasně)
     */
    private fun srtToAssTime(srtTime: String): String {
        // Formát: "HH:MM:SS,mmm" nebo "H:MM:SS,mmm"
        val parts = srtTime.split(":", ",")
        if (parts.size < 4) return "0:00:00.00"
        val h   = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0
        val m   = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
        val s   = parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0
        val ms  = parts.getOrElse(3) { "0" }.toIntOrNull() ?: 0
        val cs  = ms / 10  // milisekundy → centisekundy
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }
}

