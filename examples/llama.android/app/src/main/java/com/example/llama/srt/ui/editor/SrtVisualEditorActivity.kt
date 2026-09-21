package com.example.llama.srt.ui.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.viewModels
import android.widget.SeekBar
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.result.contract.ActivityResultContracts
import com.example.llama.srt.ui.models.SubtitleStyleRegistry
import com.example.llama.srt.ui.models.SubtitlePresetId
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.llama.media.ExportFormatRegistry
import com.example.llama.srt.proxy.ProxyWorker
import androidx.core.content.ContextCompat
import androidx.media3.exoplayer.ExoPlayer
import com.example.llama.R
import com.example.llama.srt.SrtParser
import com.example.llama.srt.export.SubtitleExportService
import com.example.llama.srt.ui.models.EditorTab
import com.example.llama.srt.ui.models.SubtitleAnimationType
import com.example.llama.srt.ui.models.ExportState
import com.example.llama.srt.ui.models.SrtEditorEvent
import com.example.llama.srt.ui.models.SrtEditorUiState
import com.example.llama.srt.ui.views.SubtitleOverlayView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.graphics.Color
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlin.math.roundToInt
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import androidx.compose.ui.graphics.nativeCanvas
import android.app.AlertDialog
import androidx.recyclerview.widget.GridLayoutManager

@OptIn(UnstableApi::class)
class SrtVisualEditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_SRT_PATH = "srt_path"
        private const val TAG = "SrtVisualEditorActivity"
    }

    private val viewModel: SrtEditorViewModel by viewModels()

    private var exoPlayer: ExoPlayer? = null
    private var lastAutoScrolledIndex = -1

    // Přijímání stavu ze služby exportu na pozadí
    private val exportReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                SubtitleExportService.BROADCAST_EXPORT_PROGRESS -> {
                    val pct = intent.getIntExtra("progress", 0)
                    viewModel.onExportProgress(pct)
                }
                SubtitleExportService.BROADCAST_EXPORT_DONE -> {
                    val path = intent.getStringExtra("output_path") ?: ""
                    viewModel.onExportDone(path)
                }
                SubtitleExportService.BROADCAST_EXPORT_ERROR -> {
                    val msg = intent.getStringExtra("error_msg") ?: "Unknown error"
                    viewModel.onExportFailed(msg)
                }
            }
        }
    }

    // UI reference
    private lateinit var videoSurface: androidx.media3.ui.PlayerView
    private lateinit var subtitleOverlay: SubtitleOverlayView
    private lateinit var composeTimeline: ComposeView
    private lateinit var tabLayoutMode: TabLayout
    private lateinit var toolbar: Toolbar
    private lateinit var fabPlayPause: com.google.android.material.floatingactionbutton.FloatingActionButton

    // Editor UI Komponenty (Zcela nové pro reálnou úpravu)
    private lateinit var textEditorContainer: LinearLayout
    private lateinit var editTextSubtitle: TextInputEditText
    private lateinit var btnSaveSrt: MaterialButton

    // Carousel UI
    private lateinit var styleCarouselContainer: androidx.core.widget.NestedScrollView
    private lateinit var rvStyleCarousel: RecyclerView
    private lateinit var btnImportTemplate: MaterialButton

    private lateinit var fontContainer: LinearLayout
    private lateinit var rvFontList: RecyclerView
    private lateinit var btnImportFont: MaterialButton

    private lateinit var animationContainer: LinearLayout
    private lateinit var rvAnimationList: RecyclerView
    private lateinit var seekBarAnimSpeed: SeekBar
    private lateinit var tvAnimSpeedValue: TextView

    private lateinit var positionContainer: androidx.core.widget.NestedScrollView
    private lateinit var seekBarPosY: SeekBar
    private lateinit var tvPosYValue: TextView
    private lateinit var seekBarPosX: SeekBar
    private lateinit var tvPosXValue: TextView
    private lateinit var seekBarMargin: SeekBar
    private lateinit var tvMarginValue: TextView

    // 3D kontroly
    private lateinit var seekBarRotX: SeekBar
    private lateinit var tvRotXValue: TextView
    private lateinit var seekBarRotY: SeekBar
    private lateinit var tvRotYValue: TextView
    private lateinit var seekBarShadow3d: SeekBar
    private lateinit var tvShadow3dValue: TextView

    // Export kontroly
    private lateinit var exportContainer: androidx.core.widget.NestedScrollView
    private lateinit var switchExport1To1: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var switchHwAccel: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var tvHwAccelDesc: TextView
    private lateinit var spinnerExportContainer: android.widget.Spinner
    private lateinit var spinnerExportCodec: android.widget.Spinner
    private lateinit var spinnerExportQuality: android.widget.Spinner
    private lateinit var spinnerExportThermal: android.widget.Spinner
    private lateinit var btnExportBurnIn: MaterialButton

    private lateinit var timelineContainer: LinearLayout
    private lateinit var rvSrtBlocks: RecyclerView
    private lateinit var btnTranslate: MaterialButton
    private lateinit var btnVfrAutofix: MaterialButton
    private lateinit var btnShiftGlobalMinus: MaterialButton
    private lateinit var btnShiftGlobalPlus: MaterialButton
    private lateinit var timelineAdapter: SubtitleTimelineAdapter

    private lateinit var btnCapsNone: MaterialButton
    private lateinit var btnCapsAll: MaterialButton
    private lateinit var btnCapsLower: MaterialButton
    private lateinit var btnCapsTitle: MaterialButton

    // Styl karta — nové prvky
    private lateinit var seekBarTextSize: SeekBar
    private lateinit var tvTextSizeValue: TextView
    private lateinit var switchStroke: SwitchMaterial
    private lateinit var strokeOptionsContainer: LinearLayout
    private lateinit var seekBarStroke: SeekBar
    private lateinit var tvStrokeValue: TextView
    private lateinit var switchBackground: SwitchMaterial
    private lateinit var bgOptionsContainer: LinearLayout
    private lateinit var seekBarBgAlpha: SeekBar
    private lateinit var switchAllCaps: SwitchMaterial
    private lateinit var switchActiveWordBg: SwitchMaterial
    private lateinit var spinnerAnchorLine: android.widget.Spinner
    private lateinit var switchShadow: SwitchMaterial
    private lateinit var btnBold: MaterialButton
    private lateinit var btnItalic: MaterialButton
    private lateinit var btnColorPicker: MaterialButton
    private lateinit var tvFontPreview: TextView
    private lateinit var btnExportTemplate: MaterialButton

    private var isUpdatingTextFromCode = false
    private var isUpdatingUI = false

    private val jsonImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val cursor = contentResolver.query(uri, null, null, null, null)
                    var fileName = "unknown"
                    if (cursor != null && cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) fileName = cursor.getString(nameIndex)
                        cursor.close()
                    }

                    val inputStream = contentResolver.openInputStream(uri) ?: return@launch

                    if (fileName.lowercase().endsWith(".json")) {
                        val jsonContent = inputStream.bufferedReader().use { it.readText() }
                        org.json.JSONObject(jsonContent) // validace
                        val dir = File(filesDir, "subtitle_templates")
                        if (!dir.exists()) dir.mkdirs()
                        val newFile = File(dir, "custom_template_${System.currentTimeMillis()}.json")
                        newFile.writeText(jsonContent)

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SrtVisualEditorActivity, "Šablona importována!", Toast.LENGTH_SHORT).show()
                            SubtitleStyleRegistry.initialize(this@SrtVisualEditorActivity)
                            setupCarousel()
                        }
                    } else if (fileName.lowercase().endsWith(".ttf") || fileName.lowercase().endsWith(".otf")) {
                        val dir = File(filesDir, "custom_fonts")
                        if (!dir.exists()) dir.mkdirs()
                        val newFile = File(dir, fileName)
                        val outStream = java.io.FileOutputStream(newFile)
                        inputStream.copyTo(outStream)
                        outStream.close()
                        inputStream.close()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SrtVisualEditorActivity, "Font $fileName importován!", Toast.LENGTH_LONG).show()
                            setupFontList() // Okamžitý refresh seznamu v UI
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SrtVisualEditorActivity, "Nepodporovaný typ souboru. Použijte .json nebo .ttf/.otf", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SrtVisualEditorActivity", "Chyba importu", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SrtVisualEditorActivity, "Chyba importu souboru.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private val exportTemplateLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val config = state.styleConfig
                val json = org.json.JSONObject().apply {
                    put("fontDisplayName", config.fontDisplayName)
                    put("fontAssetPath", config.fontAssetPath)
                    put("textSizeSp", config.textSizeSp.toDouble())
                    put("textColor", String.format("#%08X", config.textColor))
                    put("activeWordColor", String.format("#%08X", config.activeWordColor))
                    put("strokeWidthPx", config.strokeWidthPx.toDouble())
                    put("strokeColor", String.format("#%08X", config.strokeColor))
                    put("hasBackground", config.hasBackground)
                    put("backgroundColor", String.format("#%08X", config.backgroundColor))
                    put("backgroundCornerRadiusPx", config.backgroundCornerRadiusPx.toDouble())
                    put("backgroundHPaddingPx", config.backgroundHPaddingPx.toDouble())
                    put("backgroundVPaddingPx", config.backgroundVPaddingPx.toDouble())
                    put("shadowRadius", config.shadowRadius.toDouble())
                    put("shadowDx", config.shadowDx.toDouble())
                    put("shadowDy", config.shadowDy.toDouble())
                    put("shadowColor", String.format("#%08X", config.shadowColor))
                    put("animationType", config.animationType.name)
                    put("animDurationMs", config.animDurationMs)
                    put("posYPercent", config.posYPercent.toDouble())
                    put("isAllCaps", config.isAllCaps)
                    put("letterSpacing", config.letterSpacing.toDouble())
                }
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toString(4).toByteArray(Charsets.UTF_8))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtVisualEditorActivity, "Šablona úspěšně exportována!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtVisualEditorActivity, "Chyba exportu: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_srt_editor_v2)
        setupEdgeToBorder()

        val bottomSheetLayout = findViewById<android.widget.LinearLayout>(R.id.bottomSheetLayout)
        val backgroundLayout = findViewById<android.widget.LinearLayout>(R.id.backgroundLayout)
        val displayHeight = resources.displayMetrics.heightPixels
        bottomSheetLayout.layoutParams.height = (displayHeight * 0.55).toInt()

        val bottomSheetBehavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheetLayout)

        // Původní nastavení (stažený stav)
        val bgParams = backgroundLayout.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
        bgParams.bottomMargin = (110 * resources.displayMetrics.density).toInt()
        backgroundLayout.layoutParams = bgParams

        bottomSheetBehavior.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: android.view.View, newState: Int) {}
            override fun onSlide(bottomSheet: android.view.View, slideOffset: Float) {
                // OPRAVA: Místo layoutParams použijeme translationY.
                // Video se plynule posune nahoru, ale nezdeformuje se!
                val shiftUp = (displayHeight - bottomSheet.top).toFloat()
                backgroundLayout.translationY = -(shiftUp / 2f) // Dělíme 2 pro hezký parallax efekt
            }
        })

        // Bind UI
        videoSurface = findViewById(R.id.videoSurface)
        subtitleOverlay = findViewById(R.id.subtitleOverlay)
        composeTimeline = findViewById(R.id.compose_timeline_view)

        composeTimeline.setContent {
            val uiState by viewModel.uiState.collectAsState()
            TimelineEditorComponent(
                state = uiState,
                onEvent = { event -> viewModel.onEvent(event) },
                enableGreenScreen = true,
                extractObjects = true,
                onStudioSelected = { studio -> viewModel.setActiveStudio(studio) }
            )
        }
        tabLayoutMode = findViewById(R.id.tabLayoutMode)
        toolbar = findViewById(R.id.toolbar)
        fabPlayPause = findViewById(R.id.fabPlayPause)

        // Editor Views (Nutno přidat do activity_srt_editor_v2.xml pod id controlsContainer)
        textEditorContainer = findViewById(R.id.textEditorContainer)
        editTextSubtitle = findViewById(R.id.editTextSubtitle)
        btnSaveSrt = findViewById(R.id.btnSaveSrt)

        styleCarouselContainer = findViewById<androidx.core.widget.NestedScrollView>(R.id.styleCarouselContainer)
        rvStyleCarousel = findViewById(R.id.rvStyleCarousel)
        btnImportTemplate = findViewById(R.id.btnImportTemplate)

        fontContainer = findViewById(R.id.fontContainer)
        rvFontList = findViewById(R.id.rvFontList)
        btnImportFont = findViewById(R.id.btnImportFont)

        animationContainer = findViewById(R.id.animationContainer)
        rvAnimationList = findViewById(R.id.rvAnimationList)
        seekBarAnimSpeed = findViewById(R.id.seekBarAnimSpeed)
        tvAnimSpeedValue = findViewById(R.id.tvAnimSpeedValue)

        positionContainer = findViewById<androidx.core.widget.NestedScrollView>(R.id.positionContainer)
        seekBarPosY = findViewById(R.id.seekBarPosY)
        tvPosYValue = findViewById(R.id.tvPosYValue)
        seekBarPosX = findViewById(R.id.seekBarPosX)
        tvPosXValue = findViewById(R.id.tvPosXValue)
        seekBarMargin = findViewById(R.id.seekBarMargin)
        tvMarginValue = findViewById(R.id.tvMarginValue)

        seekBarRotX = findViewById(R.id.seekBarRotX)
        tvRotXValue = findViewById(R.id.tvRotXValue)
        seekBarRotY = findViewById(R.id.seekBarRotY)
        tvRotYValue = findViewById(R.id.tvRotYValue)
        seekBarShadow3d = findViewById(R.id.seekBarShadow3d)
        tvShadow3dValue = findViewById(R.id.tvShadow3dValue)

        exportContainer = findViewById(R.id.exportContainer)
        switchExport1To1 = findViewById(R.id.switchExport1To1)
        switchHwAccel = findViewById(R.id.switchHwAccel)
        tvHwAccelDesc = findViewById(R.id.tvHwAccelDesc)
        spinnerExportCodec = findViewById(R.id.spinnerExportCodec)
        spinnerExportContainer = findViewById(R.id.spinnerExportContainer)
        spinnerExportQuality = findViewById(R.id.spinnerExportQuality)
        spinnerExportThermal = findViewById(R.id.spinnerExportThermal)
        btnExportBurnIn = findViewById(R.id.btnExportBurnIn)

        timelineContainer = findViewById(R.id.timelineContainer)
        rvSrtBlocks = findViewById(R.id.rvSrtBlocks)
        btnTranslate = findViewById(R.id.btnTranslate)
        btnVfrAutofix = findViewById(R.id.btnVfrAutofix)
        btnShiftGlobalMinus = findViewById(R.id.btnShiftGlobalMinus)
        btnShiftGlobalPlus = findViewById(R.id.btnShiftGlobalPlus)

        btnCapsNone = findViewById(R.id.btnCapsNone)
        btnCapsAll = findViewById(R.id.btnCapsAll)
        btnCapsLower = findViewById(R.id.btnCapsLower)
        btnCapsTitle = findViewById(R.id.btnCapsTitle)

        seekBarTextSize = findViewById(R.id.seekBarTextSize)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)
        switchStroke = findViewById(R.id.switchStroke)
        strokeOptionsContainer = findViewById(R.id.strokeOptionsContainer)
        seekBarStroke = findViewById(R.id.seekBarStroke)
        tvStrokeValue = findViewById(R.id.tvStrokeValue)
        switchBackground = findViewById(R.id.switchBackground)
        bgOptionsContainer = findViewById(R.id.bgOptionsContainer)
        seekBarBgAlpha = findViewById(R.id.seekBarBgAlpha)
        switchAllCaps = findViewById(R.id.switchAllCaps)
        switchActiveWordBg = findViewById(R.id.switchActiveWordBg)
        spinnerAnchorLine = findViewById(R.id.spinnerAnchorLine)
        switchShadow = findViewById(R.id.switchShadow)
        btnBold = findViewById(R.id.btnBold)
        btnItalic = findViewById(R.id.btnItalic)
        btnColorPicker = findViewById(R.id.btnColorPicker)
        tvFontPreview = findViewById(R.id.tvFontPreview)
        btnExportTemplate = findViewById(R.id.btnExportTemplate)

        setupCarousel()
        setupFontList()
        setupAnimationList()
        setupPositionControls()
        setupStyleControls()
        setupTimelineAdapterAndTranslation()
        setupExportControls()

        btnImportTemplate.setOnClickListener { jsonImportLauncher.launch("*/*") }
        btnImportFont.setOnClickListener { jsonImportLauncher.launch("*/*") }
        btnExportTemplate.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready
            if (state != null) {
                val safeName = state.styleConfig.fontDisplayName.replace(" ", "_").lowercase()
                exportTemplateLauncher.launch("${safeName}_style.json")
            }
        }
        btnImportTemplate.setOnLongClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready
            if (state != null) {
                val safeName = state.styleConfig.fontDisplayName.replace(" ", "_").lowercase()
                exportTemplateLauncher.launch("${safeName}_style.json")
            }
            true
        }

        toolbar.setNavigationOnClickListener { finish() }
        toolbar.inflateMenu(R.menu.menu_srt_editor)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export -> {
                    val state = viewModel.uiState.value as? SrtEditorUiState.Ready
                    if (state != null) startBurnInExport(state)
                    true
                }
                R.id.action_save -> {
                    saveSrtProject()
                    true
                }
                else -> false
            }
        }

        // Tlačítko pro manuální save (pokud není přes menu)
        btnSaveSrt.setOnClickListener { saveSrtProject() }

        // Setup Timeline Callbacks
        // (Přesunuto do Compose TimelineEditorComponent)
        /*
        subtitleTimeline.onBlockSelected = { index ->
            viewModel.onEvent(SrtEditorEvent.SelectBlock(index))
            // Pokud vybereme blok, automaticky se přepneme na editační okno
            tabLayoutMode.getTabAt(EditorTab.TEXT.ordinal)?.select()
        }
        // FIX: TimelineView má onBlockTrimmed (3 parametry) místo onBlockTrimStart/onBlockTrimEnd
        subtitleTimeline.onBlockTrimmed = { index, newStartMs, newEndMs ->
            viewModel.onEvent(SrtEditorEvent.TrimBlockStart(index, newStartMs))
            viewModel.onEvent(SrtEditorEvent.TrimBlockEnd(index, newEndMs))
        }
        */

        // Live Text Editing Listener
        editTextSubtitle.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdatingTextFromCode) return
                val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return
                if (state.selectedBlockIndex != -1) {
                    viewModel.onEvent(SrtEditorEvent.UpdateBlockText(state.selectedBlockIndex, s?.toString() ?: ""))
                }
            }
        })

        // Rychlé formátování textu
        btnCapsNone.setOnClickListener { formatSelectedText { it } }
        btnCapsAll.setOnClickListener { formatSelectedText { it.uppercase() } }
        btnCapsLower.setOnClickListener { formatSelectedText { it.lowercase() } }
        btnCapsTitle.setOnClickListener { formatSelectedText {
            it.lowercase().split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
            }
        } }

        // Setup Overlay Callbacks (Drag & Drop + Pinch to Zoom)
        subtitleOverlay.onPositionChanged = { newPercent ->
            viewModel.onEvent(SrtEditorEvent.RepositionSubtitle(newPercent))
        }
        subtitleOverlay.onSizeChanged = { newSizeSp ->
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready
            if (state != null) {
                viewModel.onEvent(SrtEditorEvent.ApplyStyle(state.styleConfig.copy(textSizeSp = newSizeSp)))
            }
        }

        setupTabs()
        initializePlayer()
        loadIntentData()

        // StateFlow Collector
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    renderState(state)
                }
            }
        }
    }



    private fun setupCarousel() {
        rvStyleCarousel.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        SubtitleStyleRegistry.initialize(this)
        val styles = SubtitleStyleRegistry.all()
        val adapter = SubtitleStyleCarouselAdapter(styles) { selectedConfig ->
            viewModel.onEvent(SrtEditorEvent.ApplyStyle(selectedConfig))
        }

        adapter.onStyleLongPressed = { config ->
            if (config.presetId == SubtitlePresetId.CUSTOM) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Smazat šablonu?")
                    .setMessage("Opravdu chcete smazat šablonu '${config.fontDisplayName}'? Tuto akci nelze vrátit.")
                    .setPositiveButton("Smazat") { _, _ ->
                        val success = SubtitleStyleRegistry.deleteTemplate(this, config)
                        if (success) {
                            Toast.makeText(this, "Šablona smazána", Toast.LENGTH_SHORT).show()
                            setupCarousel() // reload
                        } else {
                            Toast.makeText(this, "Šablonu se nepodařilo smazat", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Zrušit", null)
                    .show()
            } else {
                Toast.makeText(this, "Výchozí šablonu nelze smazat.", Toast.LENGTH_SHORT).show()
            }
        }

        rvStyleCarousel.adapter = adapter
    }

    private fun showStylePicker() {
        // Starý StylePickerBottomSheet nahrazen Carousel UI dole
    }

    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            videoSurface.player = this
            videoSurface.useController = false // Používáme vlastní UI (Timeline)

            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.onEvent(SrtEditorEvent.SetPlaying(isPlaying))
                    if (isPlaying) {
                        viewModel.startPlaybackSyncLoop { this@apply.currentPosition }
                        fabPlayPause.visibility = View.GONE
                    } else {
                        viewModel.stopPlaybackSyncLoop()
                        viewModel.onEvent(SrtEditorEvent.SeekTo(this@apply.currentPosition))
                        fabPlayPause.visibility = View.VISIBLE
                    }
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        val state = viewModel.uiState.value as? SrtEditorUiState.Ready
                        if (state != null && state.durationMs == 0L) {
                            val duration = this@apply.duration.coerceAtLeast(0L)
                            viewModel.initialize(state.videoUriString, intent.getStringExtra(EXTRA_SRT_PATH) ?: "", state.srtBlocks, duration, state.videoFps)
                        }
                        // Zjistíme skutečné rozměry videa z ExoPlayeru a předáme SubtitleOverlayView.
                        // To inicializuje letterbox rect — drag i export budou v identickém souřadnicovém systému.
                        val videoW = this@apply.videoSize.width
                        val videoH = this@apply.videoSize.height
                        if (videoW > 0 && videoH > 0) {
                            Log.i(TAG, "Video rozměry z ExoPlayeru: ${videoW}×${videoH}")
                            subtitleOverlay.setVideoDimensions(videoW, videoH)
                        }
                    }
                }
            })
        }

        // Touch to play/pause (klik kamkoli na videoSurface nebo subtitleOverlay propadne sem)
        videoSurface.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        subtitleOverlay.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
        fabPlayPause.setOnClickListener {
            exoPlayer?.let { player ->
                if (player.isPlaying) player.pause() else player.play()
            }
        }
    }

    private fun loadIntentData() {
        val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI) ?: return
        val srtPath = intent.getStringExtra(EXTRA_SRT_PATH) ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val srtFile = File(srtPath)
                if (srtFile.exists()) {
                    val srtContent = srtFile.readText(Charsets.UTF_8)
                    val blocks = SrtParser.parse(srtContent)

                    // --- Format Detection --- (provádíme na IO vlákně pro zabránění blokování)
                    val formatReport = com.example.llama.media.MediaFormatAnalyzer.analyzeFormat(
                        this@SrtVisualEditorActivity,
                        Uri.parse(videoUriStr)
                    )

                    withContext(Dispatchers.Main) {
                        viewModel.initialize(videoUriStr, srtPath, blocks, 60_000L, formatReport.fps)
                        
                        if (!formatReport.isSupportedByExoPlayer) {
                            val msg = formatReport.warnings.joinToString("\n")
                            android.widget.Toast.makeText(
                                this@SrtVisualEditorActivity,
                                "Nepodporovaný formát. Spouštím Proxy konverzi!\n$msg",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                            
                            // Vynutíme okamžitou tvorbu Proxy, abychom zamezili černé obrazovce
                            val proxyWorkRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.llama.srt.proxy.ProxyWorker>()
                                .setInputData(
                                    androidx.work.workDataOf(
                                        com.example.llama.srt.proxy.ProxyWorker.KEY_INPUT_URI to videoUriStr
                                    )
                                )
                                .build()
                                
                            androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                "ProxyWorker_$videoUriStr",
                                androidx.work.ExistingWorkPolicy.REPLACE,
                                proxyWorkRequest
                            )
                            
                            // Zatím do přehrávače pustíme originál s nadějí, že aspoň něco zahraje
                            val mediaItem = MediaItem.fromUri(Uri.parse(videoUriStr))
                            exoPlayer?.setMediaItem(mediaItem)
                            exoPlayer?.prepare()
                            
                        } else {
                            if (formatReport.warnings.isNotEmpty()) {
                                android.widget.Toast.makeText(
                                    this@SrtVisualEditorActivity,
                                    "Upozornění:\n${formatReport.warnings.joinToString("\n")}",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                            
                            // --- Lazy Proxy Load ---
                            // ProxyWorker se spustí až v okamžiku, kdy uživatel přejde na Timeline
                            // Místo generování si ponecháme ExoPlayer na originálním souboru
                            val mediaItem = MediaItem.fromUri(Uri.parse(videoUriStr))
                            exoPlayer?.setMediaItem(mediaItem)
                            exoPlayer?.prepare()
                        }
                        // --- End Proxy Manager Logic ---

                        // Fetch audio stream count
                        val streamCount = if (com.example.llama.media.NativeVideoEngine.isAvailable()) {
                            com.example.llama.media.NativeVideoEngine(this@SrtVisualEditorActivity).getAudioStreamCount(Uri.parse(videoUriStr))
                        } else 1
                        withContext(Dispatchers.Main) {
                            viewModel.setAudioStreamCount(streamCount.coerceAtLeast(1))
                        }

                        // Fáze 4: Inicializace a spuštění PCM proxy extraktoru na pozadí
                        // Pozorujeme selectedAudioStream a při změně spustíme novou extrakci
                        lifecycleScope.launch {
                            viewModel.uiState
                                .map { (it as? SrtEditorUiState.Ready)?.selectedAudioStream }
                                .distinctUntilChanged()
                                .collect { streamIndex ->
                                    if (streamIndex != null) {
                                        viewModel.initPcmProxy(this@SrtVisualEditorActivity, forceReExtract = false) { outFile, idx ->
                                            if (com.example.llama.media.NativeVideoEngine.isAvailable()) {
                                                com.example.llama.media.NativeVideoEngine(this@SrtVisualEditorActivity)
                                                    .extractAudioForAsr(Uri.parse(videoUriStr), outFile, idx)
                                            } else {
                                                false
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při parsování SRT vstupu: ${e.message}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SrtVisualEditorActivity, "Nelze načíst titulky.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startProxyGeneration(videoUriStr: String) {
        viewModel.onEvent(SrtEditorEvent.ProxyStatusChanged(com.example.llama.srt.ui.models.ProxyState.GENERATING))
        
        val proxyWorkRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.llama.srt.proxy.ProxyWorker>()
            .setInputData(androidx.work.workDataOf(com.example.llama.srt.proxy.ProxyWorker.KEY_INPUT_URI to videoUriStr))
            .build()

        androidx.work.WorkManager.getInstance(applicationContext).enqueue(proxyWorkRequest)
        Toast.makeText(this, "Vytvářím plynulou proxy (včetně audia)...", Toast.LENGTH_SHORT).show()

        androidx.work.WorkManager.getInstance(applicationContext)
            .getWorkInfoByIdLiveData(proxyWorkRequest.id)
            .observe(this) { workInfo ->
                if (workInfo != null) {
                    when (workInfo.state) {
                        androidx.work.WorkInfo.State.SUCCEEDED -> {
                            val proxyPath = workInfo.outputData.getString(com.example.llama.srt.proxy.ProxyWorker.KEY_OUTPUT_PATH)
                            if (proxyPath != null) {
                                val proxyUri = Uri.fromFile(File(proxyPath))
                                
                                // Přepnutí ExoPlayeru na Proxy (obraz i zvuk)
                                val currentPosition = exoPlayer?.currentPosition ?: 0L
                                val isPlaying = exoPlayer?.isPlaying ?: false
                                exoPlayer?.setMediaItem(androidx.media3.common.MediaItem.fromUri(proxyUri))
                                exoPlayer?.prepare()
                                exoPlayer?.seekTo(currentPosition)
                                if (isPlaying) {
                                    exoPlayer?.play()
                                }

                                Toast.makeText(this, "Proxy připravena. Střih je plynulý.", Toast.LENGTH_SHORT).show()
                                viewModel.onEvent(SrtEditorEvent.ProxyStatusChanged(com.example.llama.srt.ui.models.ProxyState.READY, proxyUri.toString()))
                            }
                        }
                        androidx.work.WorkInfo.State.FAILED -> {
                            Log.w(TAG, "Proxy selhala. Pokračuji s originálem.")
                            viewModel.onEvent(SrtEditorEvent.ProxyStatusChanged(com.example.llama.srt.ui.models.ProxyState.FAILED))
                        }
                        else -> {}
                    }
                }
            }
    }

    private fun renderState(state: SrtEditorUiState) {
        when (state) {
            is SrtEditorUiState.Loading -> {
                // Loading spinner (lze ovládat např. progressBar.visibility = View.VISIBLE)
            }
            is SrtEditorUiState.Error -> {
                Toast.makeText(this, state.message, Toast.LENGTH_LONG).show()
            }
            is SrtEditorUiState.Ready -> {
                // 1. Update Timeline
                // Bude řešeno přes Compose State
                /*
                subtitleTimeline.updateState(
                    newBlocks = state.srtBlocks,
                    durationMs = state.durationMs,
                    positionMs = state.currentPositionMs,
                    selectedIdx = state.selectedBlockIndex
                )
                */

                // 3. Aktualizace náhledu fontu (PÍSMO karta)
                updateFontPreview(state.styleConfig.fontAssetPath)

                timelineAdapter.submitList(state.srtBlocks)
                timelineAdapter.setSelectedIndex(state.selectedBlockIndex)

                // Zajistí scroll na aktivní blok v recycler view
                if (state.selectedBlockIndex != -1 && !isUpdatingTextFromCode && lastAutoScrolledIndex != state.selectedBlockIndex) {
                    lastAutoScrolledIndex = state.selectedBlockIndex
                    rvSrtBlocks.smoothScrollToPosition(state.selectedBlockIndex)
                }

                // 2. Update Overlay (Náhled na videu)
                subtitleOverlay.setStyle(state.styleConfig)
                
                isUpdatingUI = true
                if (switchActiveWordBg.isChecked != state.styleConfig.activeWordBgEnabled) {
                    switchActiveWordBg.isChecked = state.styleConfig.activeWordBgEnabled
                }
                if (spinnerAnchorLine.selectedItemPosition != state.styleConfig.anchorLinePosition.ordinal) {
                    spinnerAnchorLine.setSelection(state.styleConfig.anchorLinePosition.ordinal)
                }
                
                // Aktualizace přepínačů vzhledu podle šablony
                if (switchStroke.isChecked != state.styleConfig.strokeEnabled) {
                    switchStroke.isChecked = state.styleConfig.strokeEnabled
                    strokeOptionsContainer.visibility = if (state.styleConfig.strokeEnabled) View.VISIBLE else View.GONE
                }
                if (switchBackground.isChecked != state.styleConfig.hasBackground) {
                    switchBackground.isChecked = state.styleConfig.hasBackground
                    bgOptionsContainer.visibility = if (state.styleConfig.hasBackground) View.VISIBLE else View.GONE
                }
                if (switchAllCaps.isChecked != state.styleConfig.isAllCaps) {
                    switchAllCaps.isChecked = state.styleConfig.isAllCaps
                }
                val hasShadow = state.styleConfig.shadowRadius > 0f
                if (switchShadow.isChecked != hasShadow) {
                    switchShadow.isChecked = hasShadow
                }
                if (btnBold.isSelected != state.styleConfig.isBold) btnBold.isSelected = state.styleConfig.isBold
                if (btnItalic.isSelected != state.styleConfig.isItalic) btnItalic.isSelected = state.styleConfig.isItalic

                isUpdatingUI = false

                val activeBlock = state.srtBlocks.find {
                    state.currentPositionMs in it.startTimeMs..it.endTimeMs
                }
                subtitleOverlay.updatePlaybackState(activeBlock, state.currentPositionMs)

                // 3. Update Text Editor Input (Zabránit nekonečné smyčce přes isUpdatingTextFromCode)
                if (state.selectedBlockIndex != -1 && state.selectedBlockIndex < state.srtBlocks.size) {
                    val currentTextInEditor = editTextSubtitle.text?.toString() ?: ""
                    val targetText = state.srtBlocks[state.selectedBlockIndex].text
                    if (currentTextInEditor != targetText) {
                        isUpdatingTextFromCode = true
                        editTextSubtitle.setText(targetText)
                        isUpdatingTextFromCode = false
                    }
                    editTextSubtitle.isEnabled = true
                } else {
                    isUpdatingTextFromCode = true
                    editTextSubtitle.setText("")
                    isUpdatingTextFromCode = false
                    editTextSubtitle.isEnabled = false
                }

                // 4. Update ExoPlayer Position (Pokud se hnulo osou)
                if (!state.isPlaying && exoPlayer != null) {
                    val diff = Math.abs(exoPlayer!!.currentPosition - state.currentPositionMs)
                    if (diff > 50L) {
                        exoPlayer!!.seekTo(state.currentPositionMs)
                    }
                }

                // 5. Update Export Status
                when (state.exportState) {
                    is ExportState.Idle -> toolbar.subtitle = ""
                    is ExportState.InProgress -> toolbar.subtitle = "Exportuji... ${state.exportState.progressPercent}%"
                    is ExportState.Done -> {
                        toolbar.subtitle = "Hotovo!"
                        Toast.makeText(this, "Video uloženo: ${state.exportState.outputPath}", Toast.LENGTH_LONG).show()
                        viewModel.onEvent(SrtEditorEvent.CancelExport)
                    }
                    is ExportState.Failed -> {
                        toolbar.subtitle = "Chyba"
                        Toast.makeText(this, "Chyba exportu: ${state.exportState.reason}", Toast.LENGTH_LONG).show()
                        viewModel.onEvent(SrtEditorEvent.CancelExport)
                    }
                }
            }
        }
    }

    private fun saveSrtProject() {
        toolbar.subtitle = "Ukládám SRT..."
        viewModel.saveSrtFile(
            onSuccess = {
                toolbar.subtitle = "Uloženo"
                Toast.makeText(this, "SRT soubor byl úspěšně aktualizován.", Toast.LENGTH_SHORT).show()
            },
            onError = { errorMsg ->
                toolbar.subtitle = "Chyba ukládání"
                Toast.makeText(this, "Chyba IO: $errorMsg", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun startBurnInExport(state: SrtEditorUiState.Ready) {
        val srtPath = intent.getStringExtra(EXTRA_SRT_PATH)
        if (srtPath.isNullOrEmpty()) {
            Toast.makeText(this, "Chyba: Neznámá cesta k SRT souboru", Toast.LENGTH_SHORT).show()
            return
        }

        // OPRAVA: Uložení upravených titulků z paměti zpět na disk (aby export nesáhl po starých)
        try {
            val updatedSrtContent = com.example.llama.srt.SrtParser.format(state.srtBlocks)
            java.io.File(srtPath).writeText(updatedSrtContent, Charsets.UTF_8)
            Log.d("SrtVisualEditorActivity", "Titulky uloženy před exportem (${state.srtBlocks.size} bloků).")
        } catch (e: Exception) {
            Log.e("SrtVisualEditorActivity", "Chyba při zápisu SRT: ${e.message}", e)
            Toast.makeText(this, "Nepodařilo se uložit titulky před exportem.", Toast.LENGTH_SHORT).show()
            return
        }

        // Čtení stavu UI prvků pro export
        val useHardware = switchHwAccel.isChecked

        val codecPos = spinnerExportCodec.selectedItemPosition
        val codecIndex = if (codecPos in availableCodecs.indices) availableCodecs[codecPos].first else -1

        val containerPos = spinnerExportContainer.selectedItemPosition
        val containerFormat = if (containerPos in availableContainers.indices) availableContainers[containerPos].first else -1

        val qualityLevel = spinnerExportQuality.selectedItemPosition
        val forcePipeline = findViewById<android.widget.Spinner>(R.id.spinnerExportPipeline).selectedItemPosition

        // DSP konfigurace pro první zaškrtnutou stopu (mixér v budoucnu)
        val audioTrack = state.audioTracks.firstOrNull { it.isSelected } ?: state.audioTracks.firstOrNull()
        val volumeGain = audioTrack?.volumeGain ?: 1.0f
        val compressor = audioTrack?.compressorEnabled ?: false
        val ebuR128 = audioTrack?.ebuR128Enabled ?: false
        val streamIndex = audioTrack?.streamIndex ?: state.selectedAudioStream

        // Export se pouští skrze Službu na pozadí, abychom přežili i uspanou obrazovku
        val serviceIntent = Intent(this, SubtitleExportService::class.java).apply {
            action = SubtitleExportService.ACTION_START_EXPORT
            putExtra(SubtitleExportService.EXTRA_VIDEO_URI, state.videoUriString)
            putExtra(SubtitleExportService.EXTRA_SRT_PATH, srtPath)
            putExtra(SubtitleExportService.EXTRA_STYLE_CONFIG, state.styleConfig)
            putExtra(SubtitleExportService.EXTRA_USE_HARDWARE, useHardware)
            putExtra("EXTRA_CODEC_INDEX", codecIndex)
            putExtra("EXTRA_CONTAINER_FORMAT", containerFormat)
            putExtra("EXTRA_QUALITY_LEVEL", qualityLevel)
            putExtra("EXTRA_AUDIO_STREAM_INDEX", streamIndex)
            putExtra("EXTRA_VOLUME_GAIN", volumeGain)
            putExtra("EXTRA_COMPRESSOR", compressor)
            putExtra("EXTRA_EBU_R128", ebuR128)
            putExtra("EXTRA_FORCE_PIPELINE", forcePipeline)
            putExtra(SubtitleExportService.EXTRA_THERMAL_MODE_ORDINAL, state.exportConfig.thermalMode.ordinal)
            putExtra(SubtitleExportService.EXTRA_FORCE_SOFTWARE, state.exportConfig.forceSoftware)
        }

        ContextCompat.startForegroundService(this, serviceIntent)
        viewModel.onEvent(SrtEditorEvent.StartExport)
    }

    private fun setupTabs() {
        tabLayoutMode.removeAllTabs()
        EditorTab.entries.forEach { tab ->
            tabLayoutMode.addTab(tabLayoutMode.newTab().setText(tab.labelRes).setTag(tab))
        }

        tabLayoutMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val selectedTab = tab?.tag as? EditorTab ?: return
                viewModel.onEvent(SrtEditorEvent.SwitchTab(selectedTab))

                // Skrýt všechny kontejnery
                textEditorContainer.visibility = View.GONE
                styleCarouselContainer.visibility = View.GONE
                fontContainer.visibility = View.GONE
                animationContainer.visibility = View.GONE
                positionContainer.visibility = View.GONE
                timelineContainer.visibility = View.GONE
                exportContainer.visibility = View.GONE

                // Zobrazit ten správný nástroj podle vybrané karty
                when (selectedTab) {
                    EditorTab.TEXT      -> textEditorContainer.visibility = View.VISIBLE
                    EditorTab.STYLE     -> styleCarouselContainer.visibility = View.VISIBLE
                    EditorTab.FONT      -> fontContainer.visibility = View.VISIBLE
                    EditorTab.ANIMATION -> animationContainer.visibility = View.VISIBLE
                    EditorTab.POSITION  -> positionContainer.visibility = View.VISIBLE
                    EditorTab.TIMELINE  -> {
                        timelineContainer.visibility = View.VISIBLE
                        // Zpožděné generování Proxy při prvním vstupu na Timeline
                        val readyState = viewModel.uiState.value as? SrtEditorUiState.Ready
                        if (readyState != null && readyState.proxyState == com.example.llama.srt.ui.models.ProxyState.PENDING && readyState.videoUriString.isNotEmpty()) {
                            // Místo přímého startu jen spustíme OneTimeWorkRequest
                            val proxyWorkRequest = androidx.work.OneTimeWorkRequestBuilder<com.example.llama.srt.proxy.ProxyWorker>()
                                .setInputData(
                                    androidx.work.workDataOf(
                                        com.example.llama.srt.proxy.ProxyWorker.KEY_INPUT_URI to readyState.videoUriString
                                    )
                                )
                                .build()
                            androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                "ProxyWorker_${readyState.videoUriString}",
                                androidx.work.ExistingWorkPolicy.KEEP,
                                proxyWorkRequest
                            )
                        }
                    }
                    EditorTab.EXPORT    -> exportContainer.visibility = View.VISIBLE
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}

            override fun onTabReselected(tab: TabLayout.Tab?) {
                val selectedTab = tab?.tag as? EditorTab ?: return
                // Pokud uživatel klikne znovu na styl, zajistíme že je vidět (např. po zavření timeline)
                if (selectedTab == EditorTab.STYLE) {
                    styleCarouselContainer.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun formatSelectedText(transform: (String) -> String) {
        val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return
        val idx = state.selectedBlockIndex
        if (idx in state.srtBlocks.indices) {
            val text = state.srtBlocks[idx].text
            viewModel.onEvent(SrtEditorEvent.UpdateBlockText(idx, transform(text)))
        } else if (editTextSubtitle.text?.isNotEmpty() == true) {
            // Pouze lokální edit bez uložení pokud není vybrán blok
            editTextSubtitle.setText(transform(editTextSubtitle.text.toString()))
        }
    }

    private fun setupTimelineAdapterAndTranslation() {
        timelineAdapter = SubtitleTimelineAdapter(
            onBlockSelected = { index ->
                viewModel.onEvent(SrtEditorEvent.SelectBlock(index))
                viewModel.onEvent(SrtEditorEvent.SeekTo((viewModel.uiState.value as? SrtEditorUiState.Ready)?.srtBlocks?.getOrNull(index)?.startTimeMs ?: 0L))
            },
            onBlockTextChanged = { index, newText ->
                isUpdatingTextFromCode = true
                viewModel.onEvent(SrtEditorEvent.UpdateBlockText(index, newText))
                isUpdatingTextFromCode = false
            },
            onBlockShifted = { index, offsetMs ->
                viewModel.shiftBlock(index, offsetMs)
            }
        )
        rvSrtBlocks.layoutManager = LinearLayoutManager(this)
        rvSrtBlocks.adapter = timelineAdapter

        btnVfrAutofix.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@setOnClickListener
            // VFR AutoFix calculation logic
            // Need to implement in ViewModel
            viewModel.onEvent(SrtEditorEvent.AutoFixVfr)
            Toast.makeText(this, "VFR AutoFix (A/V Drift) proveden.", Toast.LENGTH_SHORT).show()
        }

        btnShiftGlobalMinus.setOnClickListener {
            viewModel.shiftAllBlocks(-25L)
        }
        btnShiftGlobalPlus.setOnClickListener {
            viewModel.shiftAllBlocks(25L)
        }

        val showCustomShiftDialog = {
            val input = android.widget.EditText(this)
            input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            input.hint = "Např: -100 nebo 250"
            
            AlertDialog.Builder(this)
                .setTitle("Vlastní posun titulků (ms)")
                .setMessage("Zadejte hodnotu v milisekundách (1 s = 1000 ms). Kladná hodnota posune titulky do budoucna, záporná do minulosti.")
                .setView(input)
                .setPositiveButton("Posunout") { _, _ ->
                    val value = input.text.toString().toLongOrNull()
                    if (value != null) {
                        viewModel.shiftAllBlocks(value)
                        Toast.makeText(this, "Posunuto o ${value}ms", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Neplatná hodnota", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
            true
        }

        btnShiftGlobalMinus.setOnLongClickListener { showCustomShiftDialog() }
        btnShiftGlobalPlus.setOnLongClickListener { showCustomShiftDialog() }

        btnTranslate.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@setOnClickListener

            // Jednoduchý výběr cílového jazyka
            val languages = arrayOf("cs" to "Čeština", "en" to "Angličtina", "de" to "Němčina", "es" to "Španělština", "fr" to "Francouzština")
            val names = languages.map { it.second }.toTypedArray()

            AlertDialog.Builder(this)
                .setTitle("Vyberte cílový jazyk pro překlad")
                .setItems(names) { _, which ->
                    val targetLang = languages[which].first
                    translateSubtitlesTo(targetLang, state.srtBlocks)
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    private fun translateSubtitlesTo(targetLangCode: String, blocks: List<com.example.llama.srt.SrtBlock>) {
        // Zde by měla běžet detekce jazyka, ale pro zjednodušení předpokládáme EN zdroj nebo CS zdroj.
        // Cílem je ukázat ML Kit translate pipeline pro všechny bloky
        val sourceLangCode = if (targetLangCode == "cs") TranslateLanguage.ENGLISH else TranslateLanguage.CZECH

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLangCode)
            .setTargetLanguage(targetLangCode)
            .build()
        val translator = Translation.getClient(options)

        val progressView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(50, 50, 50, 50)
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(android.widget.ProgressBar(this@SrtVisualEditorActivity).apply {
                isIndeterminate = true
            })
            addView(android.widget.TextView(this@SrtVisualEditorActivity).apply {
                id = android.R.id.message
                text = "Příprava ML Kit modelu..."
                textSize = 16f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(50, 0, 0, 0)
            })
        }
        val pd = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .create()
        pd.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.parseColor("#1E1E1E")))
        pd.show()

        val tvMessage = progressView.findViewById<android.widget.TextView>(android.R.id.message)

        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                tvMessage.text = "Překládám titulky..."
                lifecycleScope.launch(Dispatchers.IO) {
                    blocks.forEachIndexed { index, block ->
                        try {
                            val translatedText = com.google.android.gms.tasks.Tasks.await(translator.translate(block.text))
                            withContext(Dispatchers.Main) {
                                viewModel.onEvent(SrtEditorEvent.UpdateBlockText(index, translatedText))
                            }
                        } catch (e: Exception) {
                            Log.e("SrtVisualEditorActivity", "Chyba překladu bloku $index: ${e.message}")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        pd.dismiss()
                        Toast.makeText(this@SrtVisualEditorActivity, "Překlad hotov!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .addOnFailureListener { e ->
                pd.dismiss()
                Toast.makeText(this, "Chyba stažení ML modelu: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun setupFontList() {
        rvFontList.layoutManager = GridLayoutManager(this, 3)
        // Seznam vestavěných fontů + těch z filesDir
        val fonts = mutableListOf<Pair<String, String>>()
        fonts.add("Roboto Bold" to "fonts/Roboto-Bold.ttf")
        fonts.add("Roboto Regular" to "fonts/Roboto-Regular.ttf")
        fonts.add("Roboto Black" to "fonts/Roboto-Black.ttf")
        fonts.add("Roboto Mono" to "fonts/Roboto-Mono-Regular.ttf")

        // Přidat importované z filesDir/custom_fonts
        val fontDir = File(filesDir, "custom_fonts")
        if (fontDir.exists()) {
            fontDir.listFiles { f -> f.extension.lowercase() in listOf("ttf", "otf") }?.forEach { f ->
                fonts.add(f.nameWithoutExtension to f.name) // Pouze jméno souboru (např. font.ttf)
            }
        }

        // Velmi jednoduchý adaptér inline pro rychlost a stabilitu
        rvFontList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class FontVH(view: View) : RecyclerView.ViewHolder(view) {
                val btn = view as MaterialButton
            }
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val b = MaterialButton(this@SrtVisualEditorActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    textSize = 11f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(8, 8, 8, 8)
                    layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(4, 4, 4, 4)
                    }
                }
                return FontVH(b)
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val (name, path) = fonts[position]
                (holder as FontVH).btn.text = name
                holder.btn.setOnClickListener {
                    val state = viewModel.uiState.value as? SrtEditorUiState.Ready
                    if (state != null) {
                        viewModel.onEvent(SrtEditorEvent.ApplyStyle(state.styleConfig.copy(
                            fontDisplayName = name,
                            fontAssetPath = path
                        )))
                    }
                }
            }
            override fun getItemCount() = fonts.size
        }
    }

    private var cachedFontPath: String? = null
    private var cachedTypeface: android.graphics.Typeface? = null

    private fun updateFontPreview(fontPath: String) {
        if (fontPath == cachedFontPath && cachedTypeface != null) {
            if (tvFontPreview.typeface != cachedTypeface) {
                tvFontPreview.typeface = cachedTypeface
            }
            return
        }
        try {
            val typeface = if (fontPath.startsWith("fonts/")) {
                android.graphics.Typeface.createFromAsset(assets, fontPath)
            } else {
                val fontFile = java.io.File(filesDir, "custom_fonts/$fontPath")
                if (fontFile.exists()) android.graphics.Typeface.createFromFile(fontFile) else android.graphics.Typeface.DEFAULT
            }
            cachedFontPath = fontPath
            cachedTypeface = typeface
            tvFontPreview.typeface = typeface
        } catch (e: Exception) {
            cachedFontPath = fontPath
            cachedTypeface = android.graphics.Typeface.DEFAULT
            tvFontPreview.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    private fun setupAnimationList() {
        rvAnimationList.layoutManager = GridLayoutManager(this, 4)
        val anims = SubtitleAnimationType.values()

        rvAnimationList.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            inner class AnimVH(view: View) : RecyclerView.ViewHolder(view) {
                val btn = view as MaterialButton
            }
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val b = MaterialButton(this@SrtVisualEditorActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    textSize = 9f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(4, 8, 4, 8)
                    layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).also {
                        it.setMargins(4, 4, 4, 4)
                    }
                }
                return AnimVH(b)
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val type = anims[position]
                (holder as AnimVH).btn.text = type.name.replace("_", " ")
                holder.btn.setOnClickListener {
                    val state = viewModel.uiState.value as? SrtEditorUiState.Ready
                    if (state != null) {
                        viewModel.onEvent(SrtEditorEvent.ApplyStyle(state.styleConfig.copy(animationType = type)))
                    }
                }
            }
            override fun getItemCount() = anims.size
        }
    }

    private fun setupPositionControls() {
        fun applyStyle(block: (com.example.llama.srt.ui.models.SubtitleStyleConfig) -> com.example.llama.srt.ui.models.SubtitleStyleConfig) {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return
            viewModel.onEvent(SrtEditorEvent.ApplyStyle(block(state.styleConfig)))
        }

        seekBarPosY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPosYValue.text = "$progress%"
                    viewModel.onEvent(SrtEditorEvent.RepositionSubtitle(progress / 100f))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarPosX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPosXValue.text = "$progress%"
                    applyStyle { it.copy(posXPercent = progress / 100f) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekBarMargin.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvMarginValue.text = "${progress}dp"
                    applyStyle { it.copy(marginHorizontalDp = progress) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3D rotace Y (stranou)
        seekBarRotY.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val deg = progress - 90
                    tvRotYValue.text = "${deg}°"
                    applyStyle { it.copy(rotationYDeg = deg.toFloat()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3D rotace X (náklon)
        seekBarRotX.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val deg = progress - 90
                    tvRotXValue.text = "${deg}°"
                    applyStyle { it.copy(rotationXDeg = deg.toFloat()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 3D stín
        seekBarShadow3d.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvShadow3dValue.text = "${progress}px"
                    applyStyle { it.copy(shadow3dDepthPx = progress) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private var availableCodecs: List<Pair<Int, String>> = emptyList()
    private var availableContainers: List<Pair<Int, String>> = emptyList()

    private fun setupExportControls() {
        // Kodeky
        availableCodecs = com.example.llama.media.ExportFormatRegistry.getAvailableVideoCodecsUi(this@SrtVisualEditorActivity)
        val codecs = availableCodecs.map { it.second }
        val codecAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, codecs)
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExportCodec.adapter = codecAdapter

        // Kontejnery pro export
        availableContainers = com.example.llama.media.ExportFormatRegistry.getAvailableContainersUi()
        val containers = availableContainers.map { it.second }
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, containers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExportContainer.adapter = adapter

        // Kvalita exportu
        val qualities = listOf(
            "Nejvyšší (CRF 18 / 30 Mbps) — Ideální pro archivaci",
            "Střední (CRF 23 / 15 Mbps) — Pro sociální sítě",
            "Nízká / Rychlá (CRF 28 / 8 Mbps) — Rychlý náhled"
        )
        val qualAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, qualities)
        qualAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExportQuality.adapter = qualAdapter

        // Thermal Mode
        val thermals = com.example.llama.srt.ui.models.ExportThermalMode.entries.map { it.displayName }
        val thermalAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, thermals)
        thermalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerExportThermal.adapter = thermalAdapter
        spinnerExportThermal.setSelection(com.example.llama.srt.ui.models.ExportThermalMode.BALANCED.ordinal)
        spinnerExportThermal.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return
                val selectedThermal = com.example.llama.srt.ui.models.ExportThermalMode.entries.getOrNull(position) ?: com.example.llama.srt.ui.models.ExportThermalMode.BALANCED
                if (state.exportConfig.thermalMode != selectedThermal) {
                    viewModel.onEvent(SrtEditorEvent.UpdateExportConfig(state.exportConfig.copy(thermalMode = selectedThermal)))
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Pipeline (Engine Selection)
        val pipelines = listOf(
            "Auto (Doporučeno) — Dálnice A s auto-fallbackem na B",
            "Pouze Dálnice A (GPU Zero-Copy) — Pro debug HW",
            "Pouze Dálnice B (FFmpeg Canvas) — Pro debug SW"
        )
        val pipelineAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, pipelines)
        pipelineAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        findViewById<android.widget.Spinner>(R.id.spinnerExportPipeline).adapter = pipelineAdapter
        findViewById<android.widget.Spinner>(R.id.spinnerExportPipeline).setSelection(0)

        switchHwAccel.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            viewModel.onEvent(SrtEditorEvent.UpdateExportConfig(
                (viewModel.uiState.value as? SrtEditorUiState.Ready)?.exportConfig?.copy(forceSoftware = !isChecked) ?: com.example.llama.srt.ui.models.ExportConfig()
            ))
            tvHwAccelDesc.text = if (isChecked) {
                "Dekodér + Enkodér přes GPU/VPU (rychlejší)"
            } else {
                "SW dekodér (CPU) + SW enkodér MPEG-4 (záchranný LGPL režim)"
            }
            if (!isChecked && switchExport1To1.isChecked) {
                switchExport1To1.isChecked = false
            }
        }

        btnExportBurnIn.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready
            if (state != null) {
                // Překlopí to na export logiku
                startBurnInExport(state)
            }
        }
    }

    private fun setupStyleControls() {
        fun applyStyle(block: (com.example.llama.srt.ui.models.SubtitleStyleConfig) -> com.example.llama.srt.ui.models.SubtitleStyleConfig) {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return
            viewModel.onEvent(SrtEditorEvent.ApplyStyle(block(state.styleConfig)))
        }

        // Seekbar: Velikost textu
        seekBarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvTextSizeValue.text = "${progress}sp"
                    applyStyle { it.copy(textSizeSp = progress.toFloat()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Switch: Obrys textu
        switchStroke.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            strokeOptionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyStyle { it.copy(strokeEnabled = isChecked) }
        }

        // Seekbar: Síla obrysu
        seekBarStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvStrokeValue.text = "${progress}px"
                    applyStyle { it.copy(strokeWidthPx = progress.toFloat()) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Switch: Pozadí za textem
        switchBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            bgOptionsContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
            applyStyle { it.copy(hasBackground = isChecked) }
        }

        // Seekbar: Průhlednost pozadí
        seekBarBgAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyStyle { it.copy(bgAlpha = progress / 100f) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Switch: Velká písmena
        switchAllCaps.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            applyStyle { it.copy(isAllCaps = isChecked) }
        }

        // Switch: Stín textu
        switchShadow.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            applyStyle {
                if (isChecked) it.copy(shadowRadius = 8f, shadowDx = 2f, shadowDy = 2f, shadowColor = Color.parseColor("#AA000000"))
                else it.copy(shadowRadius = 0f, shadowDx = 0f, shadowDy = 0f, shadowColor = Color.TRANSPARENT)
            }
        }

        // Toggle: Tučné
        btnBold.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@setOnClickListener
            val newBold = !state.styleConfig.isBold
            applyStyle { it.copy(isBold = newBold) }
            btnBold.isSelected = newBold
        }

        // Toggle: Kurzíva
        btnItalic.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@setOnClickListener
            val newItalic = !state.styleConfig.isItalic
            applyStyle { it.copy(isItalic = newItalic) }
            btnItalic.isSelected = newItalic
        }

        // Kognitivní: Bounding Box
        switchActiveWordBg.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            applyStyle { it.copy(activeWordBgEnabled = isChecked) }
        }

        // Kognitivní: Anchor Line
        val anchors = listOf(
            "Vypnuto (Bez linky)",
            "Podtržení (Pod textem)",
            "Plná šířka (Přes celý obraz)",
            "Vodítko vlevo (Svislé)"
        )
        val anchorAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, anchors)
        anchorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAnchorLine.adapter = anchorAdapter
        spinnerAnchorLine.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val pos = com.example.llama.srt.ui.models.AnchorLinePosition.entries[position]
                applyStyle { it.copy(anchorLinePosition = pos) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // Seekbar: Rychlost animace
        seekBarAnimSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val multiplier = progress / 5f  // 0..10 -> 0.0..2.0x, střed=5=1.0x
                    tvAnimSpeedValue.text = String.format("%.1fx", multiplier)
                    applyStyle { it.copy(animSpeedMultiplier = multiplier) }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Předdefinované barvy textu
        val colorMap = mapOf(
            R.id.colorBtnWhite  to Color.WHITE,
            R.id.colorBtnBlack  to Color.BLACK,
            R.id.colorBtnYellow to Color.parseColor("#FFEA00"),
            R.id.colorBtnNeon   to Color.parseColor("#39FF14"),
            R.id.colorBtnOrange to Color.parseColor("#FF6D00"),
            R.id.colorBtnGreen  to Color.parseColor("#00C853"),
            R.id.colorBtnBlue   to Color.parseColor("#2196F3"),
            R.id.colorBtnPink   to Color.parseColor("#FF4081"),
            R.id.colorBtnPurple to Color.parseColor("#AA00FF")
        )
        colorMap.forEach { (viewId, color) ->
            findViewById<View>(viewId).setOnClickListener {
                applyStyle { it.copy(textColor = color) }
            }
        }

        // Plný ColorPicker dialog (hex input)
        btnColorPicker.setOnClickListener {
            val state = viewModel.uiState.value as? SrtEditorUiState.Ready ?: return@setOnClickListener
            val currentColor = state.styleConfig.textColor
            val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
                id = android.R.id.edit
                setText(String.format("#%06X", 0xFFFFFF and currentColor))
            }
            val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = "např. #FF6D00"
                addView(editText)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("Barva textu (hex)")
                .setView(inputLayout)
                .setPositiveButton("Použít") { _, _ ->
                    try {
                        val parsed = Color.parseColor(editText.text?.toString() ?: return@setPositiveButton)
                        applyStyle { it.copy(textColor = parsed) }
                    } catch (e: Exception) {
                        Toast.makeText(this, "Neplatný hex kód", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Zrušit", null)
                .show()
        }
    }

    private fun setupEdgeToBorder() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(SubtitleExportService.BROADCAST_EXPORT_PROGRESS)
            addAction(SubtitleExportService.BROADCAST_EXPORT_DONE)
            addAction(SubtitleExportService.BROADCAST_EXPORT_ERROR)
        }
        ContextCompat.registerReceiver(this, exportReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        // OPRAVA: Obnovení PlayerView při návratu do popředí
        videoSurface.player = exoPlayer

        if (androidx.media3.common.util.Util.SDK_INT > 23) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (androidx.media3.common.util.Util.SDK_INT <= 23) {
            exoPlayer?.playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (androidx.media3.common.util.Util.SDK_INT <= 23) {
            exoPlayer?.playWhenReady = false
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(exportReceiver)
        if (androidx.media3.common.util.Util.SDK_INT > 23) {
            exoPlayer?.playWhenReady = false
        }
        // OPRAVA: Odpojení PlayerView zabrání pádu MediaCodec dekodéru při zničení SurfaceView (na pozadí),
        // což by shodilo celou aplikaci včetně služby pro export videa!
        videoSurface.player = null
        exoPlayer?.clearVideoSurface()
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopPlaybackSyncLoop()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineEditorComponent(
    state: SrtEditorUiState,
    onEvent: (SrtEditorEvent) -> Unit,
    enableGreenScreen: Boolean,
    extractObjects: Boolean,
    onStudioSelected: (SrtEditorUiState.ActiveStudio) -> Unit = {}
) {
    val readyState = state as? SrtEditorUiState.Ready

    var showGreenScreenSheet by remember { mutableStateOf(false) }
    var showObjectExtractionSheet by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ComposeColor(0xFF121212))
            .padding(vertical = 4.dp)
    ) {
        // Hlavička a ovládání
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = if (isExpanded) "Studio Timeline (Multi-track)" else "Časová osa", color = ComposeColor.White)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { isExpanded = !isExpanded }, modifier = Modifier.height(36.dp)) {
                    Text(if (isExpanded) "Sbalit" else "Rozbalit stopy")
                }
                if (isExpanded && enableGreenScreen) {
                    Button(onClick = { showGreenScreenSheet = true }, modifier = Modifier.height(36.dp)) {
                        Text("Vylíčovat")
                    }
                }
                if (isExpanded) {
                    Button(onClick = { showObjectExtractionSheet = true }, modifier = Modifier.height(36.dp)) {
                        Text("Objekty")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Interaktivní plocha časové osy (Zoom & Pan)
        var pixelsPerMs by remember { mutableStateOf(0.02) }
        var offsetX by remember { mutableStateOf(0.0) }
        var draggingPlayheadMs by remember { mutableStateOf<Long?>(null) }
        val displayPositionMs = draggingPlayheadMs ?: readyState?.currentPositionMs ?: 0L

        // Loadery (Lazy-loaded proxies pro reálná data z videa a audia)
        val context = LocalContext.current

        val videoUriString = (readyState as? SrtEditorUiState.Ready)?.videoUriString
        val videoLoader = remember(videoUriString) { videoUriString?.let { VideoFrameLoader(context, Uri.parse(it)) } }

        // 2. Reaktní Hot-Swap Datového Zdroje
        LaunchedEffect(readyState?.videoUriString, readyState?.proxyUriString, readyState?.proxyState) {
            val currentState    = readyState
            val currentVideoUri = currentState?.videoUriString
            if (currentState != null && currentVideoUri != null) {
                val useProxy    = currentState.proxyState == com.example.llama.srt.ui.models.ProxyState.READY && currentState.proxyUriString != null
                val targetUriStr = if (useProxy) currentState.proxyUriString!! else currentVideoUri

                // Toto volání je bezpečné, zamyká mutex a čistí starou cache
                videoLoader?.setDataSource(Uri.parse(targetUriStr), useProxy)
            }
        }
        val audioLoader = remember(readyState?.pcmProxyPath) {
            // Fáze 2: Cesta k PCM proxy z ViewModelu (zapíše se po extrakci na pozadí)
            // null = soubor ještě neexistuje → A1 zobrazí fallback dummy vlnu
            val pcmPath = readyState?.pcmProxyPath
            if (pcmPath != null) AudioChunkLoader(File(pcmPath)) else null
        }

        DisposableEffect(videoLoader) {
            onDispose { videoLoader?.release() }
        }

        // --- OPRAVA 1: Zahrnutí titulků do maxDuration, a čtení HARD-METADAT ze souboru ---
        val fileDurationMs = remember(readyState?.videoUriString) {
            var duration = 0L
            val uriStr = readyState?.videoUriString
            if (uriStr != null) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(context, android.net.Uri.parse(uriStr))
                    val timeString = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    duration = timeString?.toLongOrNull() ?: 0L
                    retriever.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            duration
        }

        val lastSubtitleMs = readyState?.srtBlocks?.maxOfOrNull { it.endTimeMs } ?: 0L
        val actualMaxMs = maxOf(fileDurationMs, lastSubtitleMs)

        val maxDuration = if (actualMaxMs > 0L) {
            // Přidáme 5 sekund rezervu na konec osy, aby šlo odrolovat za poslední titulek
            actualMaxMs + 5000L
        } else {
            7200000L
        }

        val screenWidthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.toPx() }

        // 🚀 Optimalizace: Chunking viditelného okna přes derivedStateOf
        // Tímto zabráníme recomposition při každém pixelu posunu. Recompose se spustí jen při přechodu do nové sekundy.
        // OPRAVA Chyba 3: remember BEZ klíčů — derivedStateOf sleduje MutableState
        // závislosti (offsetX, pixelsPerMs) sám přes jejich backing field.
        // Předchozí verze s klíči (maxDuration, pixelsPerMs, screenWidthPx) DESTRUOVALA
        // a ZNOVU VYTVOŘILA celý derivedStateOf při každém pixelu zoomu.
        val visibleWindowMs by remember {
            androidx.compose.runtime.derivedStateOf {
                val startPx = -offsetX
                val endPx = -offsetX + screenWidthPx
                val startMs = (startPx / pixelsPerMs).toLong().coerceAtLeast(0L)
                val endMs = (endPx / pixelsPerMs).toLong().coerceAtMost(maxDuration)

                // Zaokrouhlíme na celé vteřiny, s přesahem (bufferem)
                val chunkedStart = ((startMs / 1000L) - 2L).coerceAtLeast(0L) * 1000L
                val chunkedEnd = ((endMs / 1000L) + 2L) * 1000L

                chunkedStart to chunkedEnd
            }
        }
        val visibleStartMs = visibleWindowMs.first
        val visibleEndMs = visibleWindowMs.second

        // --- OPRAVA 2: AUTO-SCROLL BĚHEM PŘEHRÁVÁNÍ ---
        val isPlaying = readyState?.isPlaying == true
        LaunchedEffect(displayPositionMs, isPlaying) {
            if (isPlaying) {
                val playheadPx = displayPositionMs * pixelsPerMs
                val playheadScreenX = offsetX + playheadPx

                // Pokud se přehrávací hlava dostane za 70 % šířky obrazovky, posuneme osu doleva
                val thresholdRight = screenWidthPx * 0.7
                if (playheadScreenX > thresholdRight) {
                    offsetX -= (playheadScreenX - thresholdRight)
                }
            }
        }

        val headerWidthDp = 48.dp

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (isExpanded) 204.dp else 88.dp) // Zvětšíme výšku o 24dp kvůli časomíře
                .clipToBounds()
        ) {
            // Fixní hlavičky stop (Zůstanou vždy vlevo, neodscrolují)
            Column(
                modifier = Modifier
                    .width(headerWidthDp)
                    .fillMaxHeight()
                    .background(ComposeColor(0xFF2A2A2A))
            ) {
                // Prázdná hlavička pro časomíru (aby byla odsazená)
                Box(modifier = Modifier.fillMaxWidth().height(24.dp).background(ComposeColor(0xFF1A1A1A)))

                if (isExpanded) {
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp).background(ComposeColor(0xFF1E1E1E), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("V1", color = ComposeColor.White) }
                }
                Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp).background(ComposeColor(0xFF1E1E1E), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("S1", color = ComposeColor.White) }
                if (isExpanded) {
                    Box(modifier = Modifier.fillMaxWidth().height(56.dp).padding(vertical = 4.dp).background(ComposeColor(0xFF1E1E1E), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) { Text("A1", color = ComposeColor.White) }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

        // ČASOVÁ OSA - ZDE JE HLAVNÍ OPRAVA
        val density = LocalDensity.current.density
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clipToBounds()
                .pointerInput(Unit) {
                    // OPRAVENÝ ZOOM: Nyní se zoomuje přesně pod tvé prsty a neuskakuje!
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val zoomFactor = if (zoom > 1f) 1.1 else 0.9
                        val oldPixelsPerMs = pixelsPerMs

                        pixelsPerMs = (pixelsPerMs * zoomFactor).coerceIn(0.001, 5.0)

                        // Centrování zoomu pod prstem
                        val msAtCentroid = (centroid.x - offsetX) / oldPixelsPerMs
                        offsetX = centroid.x - (msAtCentroid * pixelsPerMs)
                        offsetX += pan.x.toDouble()

                        val trackPixelWidth = maxDuration * pixelsPerMs
                        val minOffset = -trackPixelWidth + (screenWidthPx / 2.0)
                        val maxOffset = screenWidthPx / 2.0
                        offsetX = offsetX.coerceIn(minOffset, maxOffset)
                    }
                }
                .pointerInput(Unit) {
                    // BONUS: Ťuknutí na osu skočí na daný čas a vybere studio!
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val tapMs = ((tapOffset.x - offsetX) / pixelsPerMs).toLong().coerceIn(0L, maxDuration)
                            onEvent(SrtEditorEvent.SeekTo(tapMs))

                            // Detekce, na jakou stopu se kliklo
                            // Výšky: TimeRuler (24.dp)
                            // VideoTrack (56.dp + 8.dp = 64.dp)
                            // SubtitleTrack (64.dp)
                            // AudioTrack (64.dp)
                            val y = tapOffset.y
                            val yDp = y / density

                            if (isExpanded) {
                                when {
                                    yDp in 24f..88f -> onStudioSelected(SrtEditorUiState.ActiveStudio.VIDEO)
                                    yDp in 88f..152f -> onStudioSelected(SrtEditorUiState.ActiveStudio.SUBTITLE)
                                    yDp > 152f -> onStudioSelected(SrtEditorUiState.ActiveStudio.AUDIO)
                                }
                            } else {
                                // Jen Ruler a SubtitleTrack
                                if (yDp > 24f) onStudioSelected(SrtEditorUiState.ActiveStudio.SUBTITLE)
                            }
                        }
                    )
                }
        ) {
            val offsetXProvider = remember { { offsetX } }
            Column(
                modifier = Modifier
                    .fillMaxSize()
            ) {
                TimeRuler(durationMs = maxDuration, pixelsPerMs = pixelsPerMs, visibleStartMs = visibleStartMs, visibleEndMs = visibleEndMs, offsetXProvider = offsetXProvider)

                if (isExpanded) {
                    val videoFps = readyState?.videoFps ?: 30f
                    VideoTrack(durationMs = maxDuration, pixelsPerMs = pixelsPerMs, videoLoader = videoLoader, visibleStartMs = visibleStartMs, visibleEndMs = visibleEndMs, offsetXProvider = offsetXProvider, videoFps = videoFps)
                }

                val blocks = readyState?.srtBlocks ?: emptyList()
                SubtitleTrack(blocks = blocks, durationMs = maxDuration, pixelsPerMs = pixelsPerMs, visibleStartMs = visibleStartMs, visibleEndMs = visibleEndMs, offsetXProvider = offsetXProvider)

                if (isExpanded) {
                    AudioTrack(durationMs = maxDuration, pixelsPerMs = pixelsPerMs, audioLoader = audioLoader, visibleStartMs = visibleStartMs, visibleEndMs = visibleEndMs, offsetXProvider = offsetXProvider)
                }
            }

            // OPRAVENÝ PLAYHEAD (Červená čára)
            if (readyState != null) {
                val playheadPx = displayPositionMs * pixelsPerMs
                val touchTargetWidthDp = 48.dp

                var dragStartMs by remember { mutableStateOf(0L) }
                var accumulatedDragPx by remember { mutableStateOf(0.0) }

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .requiredWidth(touchTargetWidthDp)
                        // KLÍČOVÁ OPRAVA: Offset místo graphicsLayer pohybuje i fyzickou zónou pro prst!
                        .offset {
                            val pxX = (offsetX + playheadPx).roundToInt()
                            val halfWidth = touchTargetWidthDp.toPx().roundToInt() / 2
                            androidx.compose.ui.unit.IntOffset(pxX - halfWidth, 0)
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { _ ->
                                    dragStartMs = displayPositionMs
                                    accumulatedDragPx = 0.0
                                    draggingPlayheadMs = dragStartMs
                                },
                                onDragEnd = { draggingPlayheadMs = null },
                                onDragCancel = { draggingPlayheadMs = null }
                            ) { change, dragAmount ->
                                change.consume()
                                accumulatedDragPx += dragAmount
                                val msDelta = (accumulatedDragPx / pixelsPerMs).toLong()
                                val newMs = (dragStartMs + msDelta).coerceIn(0L, maxDuration)
                                draggingPlayheadMs = newMs
                                onEvent(SrtEditorEvent.SeekTo(newMs))
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.fillMaxHeight().width(2.dp).background(ComposeColor.Red))
                    Box(modifier = Modifier.align(Alignment.TopCenter).width(14.dp).height(14.dp).background(ComposeColor.Red, RoundedCornerShape(2.dp)))
                }
            }
        }
        }
    }

    if (showGreenScreenSheet) {
        ModalBottomSheet(
            onDismissRequest = { showGreenScreenSheet = false },
            sheetState = sheetState,
            containerColor = ComposeColor(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                Text("GreenScreen (Chroma Key)", color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                Text("Tolerance klíčování", color = ComposeColor.LightGray)
                var tolerance by remember { mutableStateOf(0.5f) }
                Slider(value = tolerance, onValueChange = { tolerance = it })

                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Hladkost okrajů (Smoothness)", color = ComposeColor.LightGray)
                    var smooth by remember { mutableStateOf(true) }
                    Switch(checked = smooth, onCheckedChange = { smooth = it })
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { showGreenScreenSheet = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Aplikovat Filtr")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showObjectExtractionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showObjectExtractionSheet = false },
            sheetState = sheetState,
            containerColor = ComposeColor(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
                Text("AI Detekce a Vyříznutí", color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

                Text("Vyberte cílový objekt:", color = ComposeColor.LightGray)
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = {}) { Text("Osoby") }
                    Button(onClick = {}) { Text("Vozidla") }
                    Button(onClick = {}) { Text("Zvířata") }
                    Button(onClick = {}) { Text("Pozadí") }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = ComposeColor.DarkGray)
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Auto-Tracking masky", color = ComposeColor.LightGray)
                    var track by remember { mutableStateOf(true) }
                    Switch(checked = track, onCheckedChange = { track = it })
                }

                Spacer(modifier = Modifier.height(32.dp))
                Button(onClick = { showObjectExtractionSheet = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Zahájit AI Detekci")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
    if (readyState?.activeStudio == SrtEditorUiState.ActiveStudio.AUDIO) {
        ModalBottomSheet(
            onDismissRequest = { onStudioSelected(SrtEditorUiState.ActiveStudio.NONE) },
            sheetState = sheetState,
            containerColor = ComposeColor(0xFF1E1E1E)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Audio Studio", color = ComposeColor.White, style = MaterialTheme.typography.titleLarge)
                    Button(onClick = { onStudioSelected(SrtEditorUiState.ActiveStudio.NONE) }) {
                        Text("Zavřít")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Karta 1: Track Routing
                Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Směrování stop (Routing)", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    readyState?.audioTracks?.forEach { track ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = track.isSelected,
                                onCheckedChange = { onEvent(SrtEditorEvent.UpdateAudioTrackConfig(track.copy(isSelected = it))) }
                            )
                            Text("A${track.streamIndex + 1} - Audio Stopa", color = ComposeColor.White)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val proxyTrack = readyState?.audioTracks?.firstOrNull { it.isSelected }?.streamIndex ?: 0
                            onEvent(SrtEditorEvent.LoadAudioStream(proxyTrack))
                            onStudioSelected(SrtEditorUiState.ActiveStudio.NONE)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = ComposeColor(0xFF2196F3))
                    ) {
                        Text("Načíst waveformu zaškrtnutých stop")
                    }
                }
            }

            // Karta 2: Zesilovač a Dynamika
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Zesilovač a Dynamika (DSP)", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val editTrack = readyState?.audioTracks?.firstOrNull { it.isSelected } ?: readyState?.audioTracks?.firstOrNull()
                    if (editTrack != null) {
                        Text("Zisk (Gain) - A${editTrack.streamIndex + 1}: ${(editTrack.volumeGain * 100).toInt()}%", color = ComposeColor.LightGray)
                        Slider(
                            value = editTrack.volumeGain,
                            onValueChange = { onEvent(SrtEditorEvent.UpdateAudioTrackConfig(editTrack.copy(volumeGain = it))) },
                            valueRange = 0f..2f
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Kompresor (Vocal smoothing)", color = ComposeColor.LightGray)
                            Switch(
                                checked = editTrack.compressorEnabled,
                                onCheckedChange = { onEvent(SrtEditorEvent.UpdateAudioTrackConfig(editTrack.copy(compressorEnabled = it))) }
                            )
                        }
                    } else {
                        Text("Zaškrtněte stopu pro úpravu DSP.", color = ComposeColor.Gray)
                    }
                }
            }

            // Karta 3: Vysílací standardy EBU R128
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFF2A2A2A))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Vysílací standardy (EBU R128)", color = ComposeColor.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))

                    val editTrack = readyState?.audioTracks?.firstOrNull { it.isSelected } ?: readyState?.audioTracks?.firstOrNull()
                    if (editTrack != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Loudness Normalization", color = ComposeColor.LightGray)
                            Switch(
                                checked = editTrack.ebuR128Enabled,
                                onCheckedChange = { onEvent(SrtEditorEvent.UpdateAudioTrackConfig(editTrack.copy(ebuR128Enabled = it))) }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OPRAVA Chyba 1 + Chyba 2 — VideoTrack
//
// Chyba 1: LaunchedEffect(timeMs, widthPx, pixelsPerMs) → pixelsPerMs rušil
//          coroutinu při každém pixelu zoom gesta → snímky se nikdy nenačetly.
//          OPRAVA: LaunchedEffect(timeMs) — reload POUZE při novém slotu.
//
// Chyba 2: getFrameAtTime(timeMs, widthPx, heightPx) → dynamický widthPx
//          způsoboval LRU cache miss na každý zoom level → OOM.
//          OPRAVA: Fixní proxy 160×90 vždy. Zoom mění jen Box.width() vizuálně.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun VideoTrack(
    durationMs: Long,
    pixelsPerMs: Double,
    videoLoader: VideoFrameLoader?,
    visibleStartMs: Long,
    visibleEndMs: Long,
    offsetXProvider: () -> Double,
    videoFps: Float
) {
    // ─── PROFESIONÁLNÍ TIMEBASE (Adobe/CapCut princip) ───
    val msPerFrame: Double = 1000.0 / videoFps.toDouble()
    val targetSpacingPx = 160.0
    val idealIntervalMs = targetSpacingPx / pixelsPerMs
    val idealFrames = Math.max(1, Math.round(idealIntervalMs / msPerFrame).toInt())

    val framesPerSlot = when {
        idealFrames <= 1 -> 1
        idealFrames <= 2 -> 2
        idealFrames <= 5 -> 5
        idealFrames <= 15 -> 10
        idealFrames <= 45 -> videoFps.toInt()
        idealFrames <= 100 -> videoFps.toInt() * 2
        idealFrames <= 250 -> videoFps.toInt() * 5
        idealFrames <= 750 -> videoFps.toInt() * 15
        else -> videoFps.toInt() * 60
    }
    val frameIntervalMs: Long = (msPerFrame * framesPerSlot).toLong()
    
    val firstVisibleGridMs = (visibleStartMs / frameIntervalMs) * frameIntervalMs
    val lastVisibleGridMs = (visibleEndMs / frameIntervalMs) * frameIntervalMs + frameIntervalMs

    val visibleTimestamps = androidx.compose.runtime.remember(firstVisibleGridMs, lastVisibleGridMs, frameIntervalMs) {
        val list = mutableListOf<Long>()
        var current = firstVisibleGridMs
        while (current <= lastVisibleGridMs && current <= durationMs) {
            list.add(current)
            current += frameIntervalMs
        }
        list
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp)
            .background(ComposeColor(0xFF4CAF50).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .clipToBounds()
    ) {
        val chunkWidthDp = with(LocalDensity.current) {
            (frameIntervalMs * pixelsPerMs).toFloat().toDp()
        }

        visibleTimestamps.forEach { timeMs ->
            if (timeMs > durationMs) return@forEach

            // key(timeMs): composable se recykluje pouze při IDENTICKÉM timeMs.
            // Při změně frameIntervalMs (zoom level) se sety timeMs změní → nové composables.
            androidx.compose.runtime.key(timeMs) {
                var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

                // KRITICKÁ OPRAVA: LaunchedEffect POUZE na timeMs.
                // pixelsPerMs záměrně chybí — zoom NESPOUŠTÍ nové IO.
                // bitmap == null guard: pokud composable přežil zoom (stejný timeMs),
                // bitmap je již v paměti (LRU hit) → nespouštíme IO zbytečně.
                LaunchedEffect(timeMs, frameIntervalMs) {
                    if (videoLoader != null && bitmap == null) {
                        // Ochrana sběrnice (Debounce):
                        // Čekáme 100ms. Pokud uživatel dál zoomuje nebo posouvá osu (a composable se zruší),
                        // toto IO se vůbec nespustí.
                        kotlinx.coroutines.delay(100)

                        // Vždy fixní proxy 160×90 → LRU cache hit při každém zoom level
                        bitmap = videoLoader.getFrameAtTime(timeMs, 160, 90, frameIntervalMs)
                    }
                }

                Box(
                    modifier = Modifier
                        .offset {
                            val physicalX = (timeMs * pixelsPerMs) + offsetXProvider()
                            androidx.compose.ui.unit.IntOffset(physicalX.roundToInt(), 0)
                        }
                        // Šířka slotu se mění se zoomem — POUZE vizuální transform, žádné IO
                        .width(chunkWidthDp)
                        .fillMaxHeight()
                        .border(0.5.dp, ComposeColor.DarkGray)
                ) {
                    if (bitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = bitmap!!.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            // Crop: bitmap 160×90 vyplní slot libovolné šířky bez deformace
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    } else {
                        // Placeholder tmavě zelená — vizuálně čistá mezera při načítání
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(ComposeColor(0xFF1A3A1A))
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SubtitleTrack(blocks: List<com.example.llama.srt.SrtBlock>, durationMs: Long, pixelsPerMs: Double, visibleStartMs: Long, visibleEndMs: Long, offsetXProvider: () -> Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp)
            .background(ComposeColor(0xFF1E1E1E), RoundedCornerShape(4.dp))
            .clipToBounds()
    ) {
        val visibleBlocks = blocks.filter { it.endTimeMs >= visibleStartMs && it.startTimeMs <= visibleEndMs }
        visibleBlocks.forEach { block ->
            androidx.compose.runtime.key(block.startTimeMs, block.endTimeMs) {
                val widthDp = with(LocalDensity.current) { ((block.endTimeMs - block.startTimeMs) * pixelsPerMs).toFloat().toDp().coerceAtLeast(4.dp) }
                Box(
                    modifier = Modifier
                        .offset {
                            val itemX = (block.startTimeMs * pixelsPerMs) + offsetXProvider()
                            androidx.compose.ui.unit.IntOffset(itemX.roundToInt(), 0)
                        }
                        .width(widthDp)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp)
                        .background(ComposeColor(0xFFFF9800), RoundedCornerShape(4.dp))
                        .border(1.dp, ComposeColor(0xFFFFB74D), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = block.text.replace("\n", " "),
                        color = ComposeColor.White,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// OPRAVA Chyba 1 + Chyba 5 + Chyba 6 — AudioTrack
//
// Chyba 1: LaunchedEffect(timeMs, widthPx, pixelsPerMs) → pixelsPerMs rušil
//          coroutinu při každém zoomu → amplitudes se nikdy nenaplnily.
//          OPRAVA: LaunchedEffect(timeMs, chunkIntervalMs) — stabilní klíče.
//
// Chyba 5: Falešná sin vlnka (Math.sin hardcoded) — symptom chyb 1+4.
//          Po opravě příčin se amplitudes.isEmpty() stane přechodným stavem.
//          OPRAVA: Sin vlnka odstraněna, nahrazena transparentním placeholderem.
//
// Chyba 6: requestedBars závisel na widthPx (dynamický) → nestabilní počet barů.
//          OPRAVA: barsPerChunk odvozeno od chunkIntervalMs (zoom-invariantní).
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun AudioTrack(
    durationMs: Long,
    pixelsPerMs: Double,
    audioLoader: AudioChunkLoader?,
    visibleStartMs: Long,
    visibleEndMs: Long,
    offsetXProvider: () -> Double
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(vertical = 4.dp)
            .background(ComposeColor(0xFF2196F3).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
            .clipToBounds()
    ) {
        val chunkIntervalMs: Long = when {
            pixelsPerMs > 2.0  -> 33L
            pixelsPerMs > 1.0  -> 100L
            pixelsPerMs > 0.5  -> 500L
            pixelsPerMs > 0.1  -> 1000L
            pixelsPerMs > 0.02 -> 5000L
            else               -> 15000L
        }

        val chunkWidthDp = with(LocalDensity.current) {
            (chunkIntervalMs * pixelsPerMs).toFloat().toDp()
        }

        // OPRAVA Chyba 6: Fixní počet barů dle zoom úrovně — nezávisí na pixelové šířce displeje.
        // Canvas je rozetáhne přes reálnou šířku slotu automaticky.
        val barsPerChunk: Int = when {
            chunkIntervalMs <= 33L    -> 16
            chunkIntervalMs <= 100L   -> 24
            chunkIntervalMs <= 500L   -> 40
            chunkIntervalMs <= 1000L  -> 56
            chunkIntervalMs <= 5000L  -> 72
            else                      -> 88
        }

        val firstChunk = ((visibleStartMs / chunkIntervalMs) - 1L).coerceAtLeast(0L)
        val lastChunk  = (visibleEndMs / chunkIntervalMs) + 1L

        for (i in firstChunk..lastChunk) {
            val timeMs = i * chunkIntervalMs
            if (timeMs > durationMs) continue

            // key(timeMs, chunkIntervalMs): při změně zoom úrovně (jiný chunkIntervalMs)
            // → nové composables, nový IO request pro správný interval.
            androidx.compose.runtime.key(timeMs, chunkIntervalMs) {
                var amplitudes by remember { mutableStateOf(FloatArray(0)) }

                // KRITICKÁ OPRAVA: LaunchedEffect na (timeMs, chunkIntervalMs).
                // pixelsPerMs ZÁMĚRNĚ chybí — zoom NESPOUŠTÍ nové IO.
                // amplitudes.isEmpty() guard — idempotence: načteme jen jednou per slot.
                LaunchedEffect(timeMs, chunkIntervalMs) {
                    if (audioLoader != null && amplitudes.isEmpty()) {
                        val amps = audioLoader.getAmplitudesForChunk(timeMs, chunkIntervalMs, barsPerChunk)
                        if (amps.isNotEmpty()) {
                            amplitudes = amps
                        }
                    }
                }

                Canvas(
                    modifier = Modifier
                        .offset {
                            val itemX = (timeMs * pixelsPerMs) + offsetXProvider()
                            androidx.compose.ui.unit.IntOffset(itemX.roundToInt(), 0)
                        }
                        .width(chunkWidthDp)
                        .fillMaxHeight()
                ) {
                    val waveColor = ComposeColor(0xFF64B5F6)
                    val h = size.height
                    val mid = h / 2f

                    if (amplitudes.isEmpty()) {
                        // OPRAVA Chyba 5: Loading placeholder — subtilní modrý tón, žádná falešná data
                        drawRect(color = waveColor.copy(alpha = 0.07f), size = size)
                    } else {
                        val totalBars = amplitudes.size.coerceAtLeast(1)
                        val step = size.width / totalBars.toFloat()
                        // Střed prvního baru na step/2 (ne 0) → nevzniká mezera na pravém okraji
                        var x = step / 2f

                        for (amp in amplitudes) {
                            val barH = (amp * h * 0.9f).coerceAtLeast(1.dp.toPx())
                            drawLine(
                                color = waveColor,
                                start = Offset(x, mid - barH / 2f),
                                end   = Offset(x, mid + barH / 2f),
                                strokeWidth = (step * 0.72f).coerceAtLeast(1f)
                            )
                            x += step
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimeRuler(durationMs: Long, pixelsPerMs: Double, visibleStartMs: Long, visibleEndMs: Long, offsetXProvider: () -> Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(ComposeColor(0xFF1A1A1A))
            .clipToBounds()
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentOffsetX = offsetXProvider().toFloat()
            val stepMs = when {
                pixelsPerMs > 2.0 -> 100L
                pixelsPerMs > 1.0 -> 500L
                pixelsPerMs > 0.1 -> 1000L
                pixelsPerMs > 0.02 -> 5000L
                else -> 15000L
            }

            val firstTick = (visibleStartMs / stepMs) * stepMs
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.LTGRAY
                textSize = 10.sp.toPx()
                isAntiAlias = true
            }

            for (time in firstTick..visibleEndMs step stepMs) {
                if (time > durationMs) break
                val x = (time * pixelsPerMs).toFloat() + currentOffsetX

                drawLine(
                    color = ComposeColor.DarkGray,
                    start = Offset(x, size.height - 15f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f
                )

                val totalSecs = time / 1000
                val text = when {
                    stepMs >= 60000L -> String.format("%02d:00", totalSecs / 60) // Velmi oddáleno: po minutách
                    stepMs >= 1000L -> String.format("%02d:%02d", totalSecs / 60, totalSecs % 60) // Běžný náhled
                    else -> {
                        // Precizní náhled: ukáže vteřiny a milisekundy (frame-level)
                        val msPart = time % 1000
                        val frameApprox = (msPart / 33.333f).toInt() // Odhad pro 30 FPS
                        if (pixelsPerMs > 2.0) {
                            // Chirurgický zoom: Ukaž i hrubý odhad snímku (F)
                            String.format("%02d.%03d (%df)", totalSecs % 60, msPart, frameApprox)
                        } else {
                            String.format("%02d.%03d", totalSecs % 60, msPart)
                        }
                    }
                }
                drawContext.canvas.nativeCanvas.drawText(text, x + 4f, size.height - 4f, textPaint)
            }
        }
    }
}
