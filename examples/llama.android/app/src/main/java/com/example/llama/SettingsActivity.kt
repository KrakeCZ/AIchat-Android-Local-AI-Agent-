package com.example.llama

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.content.res.ColorStateList
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.llama.agent.tools.web.UrlAccessPolicy
import com.example.llama.agent.tools.web.UrlAccessType
import com.example.llama.agent.tools.web.WebAccessRuleManager
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.example.llama.agent.core.AgentHelper
import com.example.llama.managers.PermissionManager
import com.example.llama.managers.plugins.PrismLanguage
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

        private lateinit var settingsManager: SettingsManager
        private lateinit var permissionManager: PermissionManager

        private lateinit var layoutGeneral: LinearLayout
        private lateinit var layoutDisplay: LinearLayout
        private lateinit var layoutPrism: LinearLayout
        private lateinit var layoutStyle: LinearLayout
        private lateinit var layoutSampling: LinearLayout
        private lateinit var layoutAdvanced: LinearLayout
        private lateinit var layoutAgent: LinearLayout
        private lateinit var layoutIrisMotion: LinearLayout
        private lateinit var layoutVision: LinearLayout
        private lateinit var layoutPerception: LinearLayout
        private lateinit var layoutNlp: LinearLayout

        // Tabs
        private lateinit var tabGeneral: MaterialButton
        private lateinit var tabDisplay: MaterialButton
        private lateinit var tabPrism: MaterialButton
        private lateinit var tabStyle: MaterialButton
        private lateinit var tabSampling: MaterialButton
        private lateinit var tabAdvanced: MaterialButton
        private lateinit var tabAgent: MaterialButton
        private lateinit var tabIrisMotion: MaterialButton
        private lateinit var tabVision: MaterialButton
        private lateinit var tabPerception: MaterialButton
        private lateinit var tabNlp: MaterialButton
        private lateinit var layoutWhisper: LinearLayout
        private lateinit var tabWhisper: MaterialButton

        // Whisper repository & UI fields
        private lateinit var whisperRepository: com.example.llama.data.WhisperModelRepository
        private lateinit var tvWhisperActiveModel: android.widget.TextView
        private lateinit var tvWhisperModelSize: android.widget.TextView
        private lateinit var containerWhisperModels: LinearLayout

        // Advanced STT Properties
        private lateinit var tvWhisperAdvancedHeader: android.widget.TextView
        private lateinit var layoutWhisperAdvanced: android.widget.LinearLayout
        private lateinit var sliderWhisperHistorySec: com.google.android.material.slider.Slider
        private lateinit var tvWhisperHistorySecValue: android.widget.TextView
        private lateinit var sliderWhisperProcessingIntervalMs:
                com.google.android.material.slider.Slider
        private lateinit var tvWhisperProcessingIntervalValue: android.widget.TextView
        private lateinit var sliderWhisperNThreads: com.google.android.material.slider.Slider
        private lateinit var tvWhisperNthreadsValue: android.widget.TextView
        private lateinit var sliderWhisperSoftwareGain: com.google.android.material.slider.Slider
        private lateinit var tvWhisperSoftwareGainValue: android.widget.TextView
        private lateinit var spinnerWhisperLanguage: android.widget.Spinner
        private lateinit var chipgroupSettingsWhisperAccel: com.google.android.material.chip.ChipGroup

        // Advanced SHERPA STT Properties
        private lateinit var tvSherpaAdvancedHeader: android.widget.TextView
        private lateinit var layoutSherpaAdvanced: android.widget.LinearLayout
        private lateinit var sliderSherpaHistorySec: com.google.android.material.slider.Slider
        private lateinit var tvSherpaHistorySecValue: android.widget.TextView
        private lateinit var sliderSherpaProcessingIntervalMs:
                com.google.android.material.slider.Slider
        private lateinit var tvSherpaProcessingIntervalValue: android.widget.TextView
        private lateinit var sliderSherpaNThreads: com.google.android.material.slider.Slider
        private lateinit var tvSherpaNthreadsValue: android.widget.TextView
        private lateinit var sliderSherpaSoftwareGain: com.google.android.material.slider.Slider
        private lateinit var tvSherpaSoftwareGainValue: android.widget.TextView
        private lateinit var spinnerSherpaLanguage: android.widget.Spinner
        private lateinit var chipgroupSettingsSherpaAccel: com.google.android.material.chip.ChipGroup
        
        private var whisperRestartJob: kotlinx.coroutines.Job? = null

        private val importWhisperModelLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                val modelId = whisperRepository.importNewModel(uri)
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "✅ Whisper model importován",
                                                                        Toast.LENGTH_SHORT
                                                                )
                                                                .show()
                                                        refreshWhisperModels()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "❌ Import selhal: ${e.message}",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        }
                }

        // SAF folder picker pro import Sherpa-ONNX modelu (encoder.onnx, decoder.onnx, joiner.onnx,
        // tokens.txt)
        private val sherpaImportLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                                // Persist URI permission pro budoucí přístup
                                contentResolver.takePersistableUriPermission(
                                        uri,
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                                lifecycleScope.launch {
                                        try {
                                                val manager =
                                                        com.example.llama.agent.perception
                                                                .SherpaModelManager(
                                                                        this@SettingsActivity,
                                                                        settingsManager
                                                                )
                                                val (success, detectedType) =
                                                        manager.importModelFromTreeUri(uri)
                                                withContext(Dispatchers.Main) {
                                                        if (success) {
                                                                val typeName =
                                                                        when {
                                                                                detectedType
                                                                                        .isStt ->
                                                                                        "STT"
                                                                                detectedType
                                                                                        .isTts ->
                                                                                        "TTS"
                                                                                else -> "Neznámý"
                                                                        }
                                                                Toast.makeText(
                                                                                this@SettingsActivity,
                                                                                "✅ Sherpa-ONNX $typeName model importován",
                                                                                Toast.LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                                refreshSherpaModels()
                                                        } else {
                                                                Toast.makeText(
                                                                                this@SettingsActivity,
                                                                                "❌ Import selhal — složka neobsahuje potřebné soubory",
                                                                                Toast.LENGTH_LONG
                                                                        )
                                                                        .show()
                                                        }
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "❌ Chyba importu: ${e.message}",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        }
                }

        // SAF folder picker pro User Document RAG (procházení externích složek a sémantický chunking 512/64)
        private val ragFolderPickerLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG: Zahajuji delta-indexaci vybrané složky..."
                                                        Toast.makeText(this@SettingsActivity, "Indexuji SAF složku...", Toast.LENGTH_SHORT).show()
                                                }
                                                val manager = com.example.llama.agent.tools.workspace.SafDocumentRagManager(this@SettingsActivity)
                                                val chunks = manager.onFolderTreeSelected(uri)
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG: Složka zaindexována ($chunks chunků 512/64)"
                                                        Toast.makeText(this@SettingsActivity, "✅ RAG: $chunks sémantických chunků připraveno", Toast.LENGTH_LONG).show()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG Chyba: ${e.message}"
                                                        Toast.makeText(this@SettingsActivity, "❌ Chyba SAF indexace: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                        }
                                }
                        }
                }

        // SAF multiple documents picker pro User Document RAG
        private val ragFilesPickerLauncher =
                registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                        if (!uris.isNullOrEmpty()) {
                                lifecycleScope.launch {
                                        try {
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG: Indexuji ${uris.size} souborů..."
                                                }
                                                val manager = com.example.llama.agent.tools.workspace.SafDocumentRagManager(this@SettingsActivity)
                                                val chunks = manager.onMultipleDocumentsSelected(uris)
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG: Naimportováno $chunks chunků z ${uris.size} souborů"
                                                        Toast.makeText(this@SettingsActivity, "✅ RAG: $chunks chunků (512/64)", Toast.LENGTH_LONG).show()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        tvWebImportStatus.text = "RAG Chyba: ${e.message}"
                                                        Toast.makeText(this@SettingsActivity, "❌ Chyba: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                        }
                                }
                        }
                }

        private val sherpaVocoderImportLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                val manager =
                                                        com.example.llama.agent.perception
                                                                .SherpaModelManager(
                                                                        this@SettingsActivity,
                                                                        settingsManager
                                                                )
                                                val success = manager.importSharedVocoder(uri)
                                                withContext(Dispatchers.Main) {
                                                        if (success) {
                                                                Toast.makeText(
                                                                                this@SettingsActivity,
                                                                                "✅ Sdílený vocoder úspěšně importován",
                                                                                Toast.LENGTH_SHORT
                                                                        )
                                                                        .show()
                                                        } else {
                                                                Toast.makeText(
                                                                                this@SettingsActivity,
                                                                                "❌ Import vocoderu selhal (neplatný soubor)",
                                                                                Toast.LENGTH_LONG
                                                                        )
                                                                        .show()
                                                        }
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "❌ Chyba importu: ${e.message}",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        }
                }

        // ==================== BERT Model Repository ====================
        // BertDatabase + BertModelRepository pro správu TFLite BERT modelů a vocab souborů
        private val bertRepository by lazy {
                com.example.llama.data.BertModelRepository(
                        com.example.llama.data.BertDatabase.getDatabase(this).bertModelDao()
                )
        }

        // ==================== NLP Model Repository ====================
        // NlpDatabase + NlpModelRepository pro správu MediaPipe LLM / NLP modelů
        private val nlpModelRepository by lazy { com.example.llama.data.NlpModelRepository(this) }

        // Dočasný stav mezi kroky importu (HuggingFace 2-krokový wizard)
        private var pendingBertModelSource: String =
                com.example.llama.data.BertModel.Companion.ModelSource.HUGGINGFACE
        private var pendingBertModelId: String? = null // ID modelu čekajícího na vocab

        // ==================== BERT — Import model launcher (.tflite) ====================
        private val importBertModelLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                handleBertModelImport(uri, pendingBertModelSource)
                        }
                }

        // ==================== BERT — Import vocab launcher (.txt / tokenizer.json)
        // ====================
        // Spustí se automaticky po importu modelu pokud TfliteVocabDetector detekuje REQUIRED
        private val importBertVocabLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                val modelId = pendingBertModelId
                                if (modelId != null) {
                                        handleBertVocabImport(uri, modelId)
                                } else {
                                        Toast.makeText(
                                                        this,
                                                        "⚠️ Chyba: nenalezen parent model pro vocab",
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                }
                        }
                }

        // SAF file picker pro import MediaPipe LLM modelu (.task / .bin / .gguf / .tflite)
        private val mediaPipeLlmImportLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                val modelId =
                                                        nlpModelRepository.importMediaPipeLlmModel(
                                                                uri
                                                        )
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "✅ MediaPipe LLM model importován",
                                                                        Toast.LENGTH_SHORT
                                                                )
                                                                .show()
                                                        refreshMediaPipeLlmStatus()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "❌ Import selhal: ${e.message}",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        }
                }

        // ==================== OD Model Manager — Import .tflite launcher ====================
        // Single-file SAF picker (identický vzor jako importBertModelLauncher)
        private val importOdModelLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        val odManager =
                                                com.example.llama.agent.perception.OdModelManager(
                                                        this@SettingsActivity,
                                                        settingsManager
                                                )
                                        try {
                                                val result = odManager.importModelFromUri(uri)
                                                withContext(Dispatchers.Main) {
                                                        when (result) {
                                                                is com.example.llama.agent.perception.OdImportResult.Success -> {
                                                                        Toast.makeText(
                                                                                        this@SettingsActivity,
                                                                                        "✅ OD model importován: ${result.record.displayName}",
                                                                                        Toast.LENGTH_SHORT
                                                                                )
                                                                                .show()
                                                                        refreshOdModels()
                                                                }
                                                                is com.example.llama.agent.perception.OdImportResult.Error -> {
                                                                        Toast.makeText(
                                                                                        this@SettingsActivity,
                                                                                        "❌ Import selhal: ${result.reason}",
                                                                                        Toast.LENGTH_LONG
                                                                                )
                                                                                .show()
                                                                }
                                                        }
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "❌ Chyba importu: ${e.message}",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        }
                }

        // ==================== YAMNet CSV Import launcher ====================
        private val importYamnetCsvLauncher =
                registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                val contentResolver = applicationContext.contentResolver
                                                val cursor = contentResolver.query(uri, null, null, null, null)
                                                var fileName = "imported_lang.csv"
                                                if (cursor != null && cursor.moveToFirst()) {
                                                        val displayNameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                                        if (displayNameIndex >= 0) fileName = cursor.getString(displayNameIndex)
                                                        cursor.close()
                                                }
                                                if (!fileName.lowercase().endsWith(".csv")) fileName += ".csv"

                                                val yamnetDbDir = java.io.File(getExternalFilesDir(null), "yamnet_db")
                                                if (!yamnetDbDir.exists()) yamnetDbDir.mkdirs()
                                                val destFile = java.io.File(yamnetDbDir, fileName)

                                                contentResolver.openInputStream(uri)?.use { input ->
                                                        java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                                                }
                                                com.example.llama.agent.perception.AudioLabelTranslations.init(applicationContext, forceReload = true)
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@SettingsActivity, "✅ YAMNet CSV importována: $fileName", Toast.LENGTH_LONG).show()
                                                        refreshYamnetDatabases()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@SettingsActivity, "❌ Chyba importu: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                        }
                                }
                        }
                }

        // ==================== YAMNet CSV Export launcher ====================
        private val exportYamnetCsvLauncher =
                registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                        if (uri != null) {
                                lifecycleScope.launch {
                                        try {
                                                applicationContext.contentResolver.openOutputStream(uri)?.use { output ->
                                                        val sourceFile = java.io.File(getExternalFilesDir(null), "yamnet_db/eng.csv")
                                                        if (sourceFile.exists()) {
                                                                java.io.FileInputStream(sourceFile).use { it.copyTo(output) }
                                                        } else {
                                                                applicationContext.assets.open("yamnet_db/eng.csv").use { it.copyTo(output) }
                                                        }
                                                }
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@SettingsActivity, "✅ Vzorový slovník (eng.csv) exportován", Toast.LENGTH_LONG).show()
                                                }
                                        } catch (e: Exception) {
                                                withContext(Dispatchers.Main) {
                                                        Toast.makeText(this@SettingsActivity, "❌ Chyba exportu: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                        }
                                }
                        }
                }

        // ==================== YAMNet Database List Refresh ====================
        private fun refreshYamnetDatabases() {
                val activeLang = settingsManager.yamnetActiveLang
                tvYamnetActiveLang.text = "Aktivní: $activeLang"
                containerYamnetDatabases.removeAllViews()

                // Scan externalFilesDir/yamnet_db
                val dbDir = java.io.File(getExternalFilesDir(null), "yamnet_db")
                val files = dbDir.listFiles { _, name -> name.endsWith(".csv") }
                        ?.sortedBy { it.nameWithoutExtension } ?: emptyList()

                if (files.isEmpty()) {
                        val tvEmpty = android.widget.TextView(this)
                        tvEmpty.text = "  (Žádné databáze — přidejte import)"
                        tvEmpty.setTextColor(android.graphics.Color.parseColor("#888888"))
                        tvEmpty.textSize = 12f
                        containerYamnetDatabases.addView(tvEmpty)
                        return
                }

                for (file in files) {
                        val langCode = file.nameWithoutExtension
                        val isActive = langCode.equals(activeLang, ignoreCase = true)
                        val isOem = langCode.equals("eng", ignoreCase = true)

                        val row = android.widget.LinearLayout(this)
                        row.orientation = android.widget.LinearLayout.HORIZONTAL
                        row.gravity = android.view.Gravity.CENTER_VERTICAL
                        val rowParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        rowParams.bottomMargin = (4 * resources.displayMetrics.density).toInt()
                        row.layoutParams = rowParams
                        row.setPadding(
                                (8 * resources.displayMetrics.density).toInt(), 
                                (6 * resources.displayMetrics.density).toInt(),
                                (8 * resources.displayMetrics.density).toInt(),
                                (6 * resources.displayMetrics.density).toInt()
                        )
                        val bgColor = if (isActive) android.graphics.Color.parseColor("#1A76FF03")
                                     else android.graphics.Color.parseColor("#1AFFFFFF")
                        row.setBackgroundColor(bgColor)

                        val tvName = android.widget.TextView(this)
                        val oemSuffix = if (isOem) " (OEM)" else ""
                        val activeSuffix = if (isActive) " ✓" else ""
                        tvName.text = "  ${langCode.uppercase()}$oemSuffix$activeSuffix"
                        tvName.setTextColor(
                                if (isActive) android.graphics.Color.parseColor("#76FF03")
                                else android.graphics.Color.WHITE
                        )
                        tvName.textSize = 13f
                        val tvParams = android.widget.LinearLayout.LayoutParams(
                                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        tvName.layoutParams = tvParams
                        row.addView(tvName)

                        // Tlačítko Aktivovat (jen pokud není aktivní)
                        if (!isActive) {
                                val btnActivate = com.google.android.material.button.MaterialButton(this)
                                btnActivate.text = "Aktivovat"
                                btnActivate.textSize = 11f
                                btnActivate.setPadding(16, 4, 16, 4)
                                val btnParams = android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                btnParams.marginStart = (8 * resources.displayMetrics.density).toInt()
                                btnActivate.layoutParams = btnParams
                                btnActivate.setOnClickListener {
                                        settingsManager.yamnetActiveLang = langCode
                                        com.example.llama.agent.perception.AudioLabelTranslations.init(applicationContext, forceReload = true)
                                        refreshYamnetDatabases()
                                        android.widget.Toast.makeText(
                                                this,
                                                "✅ Aktivní databáze: $langCode",
                                                android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                }
                                row.addView(btnActivate)
                        }

                        // Tlačítko Smazat (zakázáno pro OEM eng)
                        if (!isOem) {
                                val btnDelete = com.google.android.material.button.MaterialButton(this)
                                btnDelete.text = "🗑"
                                btnDelete.textSize = 11f
                                val delParams = android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                delParams.marginStart = (4 * resources.displayMetrics.density).toInt()
                                btnDelete.layoutParams = delParams
                                btnDelete.setBackgroundColor(android.graphics.Color.parseColor("#55FF5252"))
                                btnDelete.setOnClickListener {
                                        androidx.appcompat.app.AlertDialog.Builder(this)
                                                .setTitle("Smazat $langCode.csv?")
                                                .setMessage("Tato akce je nevratná.")
                                                .setPositiveButton("Smazat") { _, _ ->
                                                        file.delete()
                                                        if (isActive) settingsManager.yamnetActiveLang = "eng"
                                                        com.example.llama.agent.perception.AudioLabelTranslations.init(applicationContext, forceReload = true)
                                                        refreshYamnetDatabases()
                                                }
                                                .setNegativeButton("Zrušit", null)
                                                .show()
                                }
                                row.addView(btnDelete)
                        }

                        containerYamnetDatabases.addView(row)
                }
        }

        // Perception Engine UI fields
        private lateinit var cbPerceptionEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var actvPerceptionCameraSource: AutoCompleteTextView
        private lateinit var sliderPerceptionOdConfidence: com.google.android.material.slider.Slider
        private lateinit var tvPerceptionOdConfidenceValue: android.widget.TextView
        private lateinit var etPerceptionOdMaxResults:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionOdTimeout:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionOcrTimeout:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionOcrMaxLength:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionThermalSkip:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionThermalGate1:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionThermalGate2:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionThreadPriority:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var sliderPerceptionResolution:
                com.google.android.material.slider.Slider
        private lateinit var tvPerceptionResolutionValue: android.widget.TextView
        private lateinit var cbPerceptionPoseEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var sliderPerceptionPoseConfidence:
                com.google.android.material.slider.Slider
        private lateinit var tvPerceptionPoseConfidenceValue: android.widget.TextView
        private lateinit var etPerceptionPoseMaxPoses:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionPoseResolution:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionPoseTimeout:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var cbPerceptionAudioEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var sliderPerceptionAudioConfidence:
                com.google.android.material.slider.Slider
        private lateinit var tvPerceptionAudioConfidenceValue: android.widget.TextView
        private lateinit var etPerceptionAudioMaxResults:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionAudioInterval:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etPerceptionAudioCooldown:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var cbPerceptionTtsScene:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbPerceptionTtsCritical:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var sliderPerceptionSceneMinEntities:
                com.google.android.material.slider.Slider
        private lateinit var tvPerceptionSceneMinEntitiesValue: android.widget.TextView

        // OD Model Manager UI fields
        private lateinit var tvOdActiveModelName: android.widget.TextView
        private lateinit var tvOdActiveModelInfo: android.widget.TextView
        private lateinit var containerOdModels: android.widget.LinearLayout

        // YAMNet Database Manager UI fields
        private lateinit var containerYamnetDatabases: android.widget.LinearLayout
        private lateinit var tvYamnetActiveLang: android.widget.TextView

        // NLP Tier 2.5 UI fields
        private lateinit var actvSummarizationBackend: AutoCompleteTextView
        private lateinit var cbNlpTranslateEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbNlpTranslateAutoDownload:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var tilNlpLangSelector:
                com.google.android.material.textfield.TextInputLayout
        private lateinit var actvNlpLangSelector: AutoCompleteTextView
        private lateinit var actvNlpMyLang: AutoCompleteTextView        // Můj jazyk překladu
        private lateinit var actvNlpForeignLang: AutoCompleteTextView   // Cizí jazyk překladu
        private lateinit var btnNlpDownloadLang: com.google.android.material.button.MaterialButton
        private lateinit var progressNlpDownload:
                com.google.android.material.progressindicator.LinearProgressIndicator
        private lateinit var containerNlpDownloadedLangs: LinearLayout
        private lateinit var tvNlpLangsStatus: android.widget.TextView
        private lateinit var cbNlpSentimentEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var tvNlpSentimentStatus: android.widget.TextView
        private lateinit var cbNlpSmartReplyEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbNlpGenaiProofread:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbNlpGenaiRewrite:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var tvNlpAicoreStatus: android.widget.TextView
        private lateinit var tvNlpAicoreVersion: android.widget.TextView
        private lateinit var btnNlpAicoreSettings: com.google.android.material.button.MaterialButton

        // NLP Linguistic Tuner (v2026.1)
        private lateinit var sbNlpWeightTasks: android.widget.SeekBar
        private lateinit var tvNlpWeightTasksVal: android.widget.TextView
        private lateinit var sbNlpWeightSlang: android.widget.SeekBar
        private lateinit var tvNlpWeightSlangVal: android.widget.TextView
        private lateinit var sbNlpWeightTech: android.widget.SeekBar
        private lateinit var tvNlpWeightTechVal: android.widget.TextView
        private lateinit var sbNlpWeightUrgency: android.widget.SeekBar
        private lateinit var tvNlpWeightUrgencyVal: android.widget.TextView

        // ZipVoice Settings UI fields
        private lateinit var spinnerSherpaZipvoiceThreads: android.widget.Spinner
        private lateinit var tvSherpaZipvoiceThreadsValue: android.widget.TextView
        private lateinit var spinnerSherpaZipvoicePriority: android.widget.Spinner
        private lateinit var spinnerSherpaZipvoiceSampleRate: android.widget.Spinner
        private lateinit var etSherpaZipvoicePromptText: TextInputEditText
        private lateinit var etSherpaTestText: TextInputEditText
        private lateinit var btnSherpaRecordPrompt10s: MaterialButton
        private lateinit var btnSherpaRecordPromptDyn: MaterialButton
        private lateinit var btnSherpaPlayPrompt: com.google.android.material.button.MaterialButton
        private lateinit var btnSherpaClearPrompt: com.google.android.material.button.MaterialButton
        private lateinit var btnSherpaTestClone: com.google.android.material.button.MaterialButton
        private lateinit var tvSherpaPromptStatus: android.widget.TextView

        // Agent Worker Settings UI fields
        private lateinit var sliderParserMaxDepth: com.google.android.material.slider.Slider
        private lateinit var tvParserMaxDepthValue: android.widget.TextView
        private lateinit var etParserMaxElements:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etCacheMaxApps: com.google.android.material.textfield.TextInputEditText
        private lateinit var etCacheMaxElements:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etSafetyMaxHistory:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var sliderSafetyLoopThreshold: com.google.android.material.slider.Slider
        private lateinit var tvSafetyLoopThresholdValue: android.widget.TextView
        private lateinit var sliderFloatingSize: com.google.android.material.slider.Slider
        private lateinit var tvFloatingSizeValue: android.widget.TextView
        private lateinit var etFloatingClickThreshold:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var etFloatingMoveThreshold:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var sliderFloatingAutoHide: com.google.android.material.slider.Slider
        private lateinit var tvFloatingAutoHideValue: android.widget.TextView
        private lateinit var cbFloatingLockscreenHide:
                com.google.android.material.checkbox.MaterialCheckBox

        // Agent Notifier (Floating Bubbles)
        private lateinit var swFloatingNotifyEnabled:
                com.google.android.material.switchmaterial.SwitchMaterial
        private lateinit var spinFloatingNotifyStyle: android.widget.Spinner
        private lateinit var spinFloatingNotifyPosition: android.widget.Spinner
        private lateinit var cbFloatingNotifyWarnings:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbFloatingNotifyVision:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbFloatingNotifyAudio:
                com.google.android.material.checkbox.MaterialCheckBox

        // SAF file picker pro import app registru
        private val importAppListLauncher =
                registerForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.GetContent()
                ) { uri: android.net.Uri? ->
                        if (uri != null) {
                                val count =
                                        com.example.llama.agent.core.InstalledAppsRegistry
                                                .importFromUri(this, uri)
                                if (count >= 0) {
                                        Toast.makeText(
                                                        this,
                                                        "📂 Importováno $count app",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                } else {
                                        Toast.makeText(
                                                        this,
                                                        "⚠️ Import selhal — neplatný JSON",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                }
                        }
                }

        // NLP Linguistic Tuner bindings
        // Assuming this code is part of a `bindViews()` function or similar initialization block
        // that is called from onCreate. The closing brace `} // end bindViews()` is a placeholder
        // indicating the end of such a block.
        // If this is not inside a function, it would be a syntax error.
        // For the purpose of this edit, I'm placing it where the instruction implies it belongs
        // within an existing initialization context.
        // If this is meant to be in onCreate, the structure would be different.
        // Given the context, I'll assume it's within a function that ends with the comment.
        // This is a placeholder for where the actual function body would end.
        // If this is not within a function, the user's instruction is syntactically incorrect.
        // I will insert it as if it's within a function.
        // If there's no `bindViews()` function, this will be a syntax error.
        // I will assume the user wants this inside a function that ends with the comment.

        // Inputs (General)
        private lateinit var etSystemMessage: TextInputEditText
        private lateinit var cbShowSystem: MaterialCheckBox
        private lateinit var etMaxImportSize: TextInputEditText

        // Inputs (General -> Performance)
        private lateinit var etContextSize: TextInputEditText
        private lateinit var actvBatchSize: AutoCompleteTextView
        private lateinit var actvUBatchSize: AutoCompleteTextView
        private lateinit var actvKvCache: AutoCompleteTextView
        private lateinit var cbFlashAttn: MaterialCheckBox
        private lateinit var cbUseMmap: MaterialCheckBox
        private lateinit var actvLlamaBackend: AutoCompleteTextView
        private lateinit var cbStrictBackend: MaterialCheckBox
        private lateinit var etGpuLayers: TextInputEditText
        private lateinit var btnCheckBackends: com.google.android.material.button.MaterialButton
        private lateinit var tvBackendInfo: android.widget.TextView
        private lateinit var sliderWebExtractTokenLimit: com.google.android.material.slider.Slider
        private lateinit var tvWebExtractTokenLimitValue: android.widget.TextView
        private lateinit var actvWebSearchMaxResults: AutoCompleteTextView
        private lateinit var cbWebDataSaver: MaterialCheckBox
        private lateinit var cbWebThrottleBandwidth: MaterialCheckBox
        private lateinit var cbWebEnableDuckDuckGo: MaterialCheckBox
        private lateinit var cbWebEnableWikipedia: MaterialCheckBox
        private lateinit var cbWebEnableScience: MaterialCheckBox
        private lateinit var cbWebEnableImportedDb: MaterialCheckBox
        private lateinit var cbWebEnableSqliteFts5: MaterialCheckBox
        private lateinit var cbWebPreferOfflineCache: MaterialCheckBox
        private lateinit var cbWebEnableHeadlessWebView: MaterialCheckBox
        private lateinit var cbWebReaderDomDistiller: MaterialCheckBox
        private lateinit var cbWebExtractJsonLd: MaterialCheckBox
        private lateinit var cbWebSmartReranking: MaterialCheckBox
        private lateinit var cbWebUserAgentRotation: MaterialCheckBox
        private lateinit var cbWebDevBypassSecurity: MaterialCheckBox
        private lateinit var btnWebImportSources: MaterialButton
        private lateinit var btnWebReindexSandbox: MaterialButton
        private lateinit var tvWebImportStatus: android.widget.TextView

        // Inputs (Display)
        private lateinit var cbShowStats: MaterialCheckBox
        private lateinit var cbShowThoughts: MaterialCheckBox
        private lateinit var cbKeepStats: MaterialCheckBox
        private lateinit var cbMediaPreview: MaterialCheckBox
        private lateinit var cbRenderMarkdown: MaterialCheckBox
        private lateinit var cbDisableScroll: MaterialCheckBox
        private lateinit var cbAlwaysSidebar: MaterialCheckBox
        private lateinit var cbAutoSidebar: MaterialCheckBox
        private lateinit var cbBubbleCopy: MaterialCheckBox
        private lateinit var cbBackgroundGen: MaterialCheckBox

        private lateinit var cbHideStatusBar: MaterialCheckBox

        // Smart Vision Settings
        private lateinit var cbSmartVisionBoundingBoxes: MaterialCheckBox
        private lateinit var cbSmartVisionOmniparser: MaterialCheckBox
        private lateinit var cbSmartVisionFilterEmptyImageView: MaterialCheckBox
        private lateinit var cbSmartVisionFilterNonInteractive: MaterialCheckBox
        private lateinit var cbSmartVisionFilterScrollable: MaterialCheckBox
        private lateinit var cbSmartVisionFilterWebView: MaterialCheckBox
        private lateinit var cbSmartVisionFilterEmptyEditText: MaterialCheckBox
        private lateinit var cbSmartVisionFilterOffscreen: MaterialCheckBox
        private lateinit var cbSmartVisionFilterContainers: MaterialCheckBox
        private lateinit var sliderSmartVisionMaxDepth: com.google.android.material.slider.Slider
        private lateinit var tvSmartVisionMaxDepthValue: android.widget.TextView
        private lateinit var sliderSmartVisionTargetResolution:
                com.google.android.material.slider.Slider
        private lateinit var tvSmartVisionTargetResolutionValue: android.widget.TextView
        private lateinit var cbSmartVisionCameraLive: MaterialCheckBox
        private lateinit var sliderSmartVisionFps: com.google.android.material.slider.Slider
        private lateinit var tvSmartVisionFpsValue: TextView
        private lateinit var actvSmartVisionModel: AutoCompleteTextView
        private lateinit var cbSmartVisionNativeModel: MaterialCheckBox
        private val visionModelsMap = mutableMapOf<String, String>() // DisplayName -> UUID

        // Inputs (Agent Gestures)
        private lateinit var sliderAgentScroll: com.google.android.material.slider.Slider
        private lateinit var sliderAgentZoom: com.google.android.material.slider.Slider
        private lateinit var etAgentTapInterval: TextInputEditText
        private lateinit var etAgentTapCount: TextInputEditText
        private lateinit var cbInvertSwipeVertical: MaterialCheckBox
        private lateinit var cbInvertSwipeHorizontal: MaterialCheckBox
        private lateinit var cbShowTokenCount: MaterialCheckBox
        private lateinit var cbShowGenTime: MaterialCheckBox
        private lateinit var spinnerChatStyle: Spinner

        // Inputs (Sampling - Full Set)
        private lateinit var etTemp: TextInputEditText
        private lateinit var etDynTempRange: TextInputEditText
        private lateinit var etDynTempExp: TextInputEditText
        private lateinit var etTopK: TextInputEditText
        private lateinit var etTopP: TextInputEditText
        private lateinit var etMinP: TextInputEditText
        private lateinit var etXtcProb: TextInputEditText
        private lateinit var etXtcThresh: TextInputEditText
        private lateinit var etTypicalP: TextInputEditText

        // Cache Inputs
        private lateinit var etCacheTtl: TextInputEditText
        private lateinit var etAgentTimeout: TextInputEditText
        private lateinit var btnClearCache: MaterialButton

        // Memory Settings
        private lateinit var etMemoryMaxApps: TextInputEditText
        private lateinit var etMemoryMaxPatterns: TextInputEditText
        private lateinit var etMemoryFlushInterval: TextInputEditText
        private lateinit var etMemoryPatternMaxAge: TextInputEditText
        private lateinit var etMemoryMinConfidence: TextInputEditText
        private lateinit var etMemoryRamLimit: TextInputEditText

        // Inputs (Sampling - Penalties)
        private lateinit var etPenaltyRepeat: TextInputEditText
        private lateinit var etPenaltyFreq: TextInputEditText
        private lateinit var etPenaltyPresent: TextInputEditText
        private lateinit var etPenaltyLastN: TextInputEditText

        // Inputs (Mirostat)
        private lateinit var spinnerMirostatMode: Spinner
        private lateinit var etMirostatTau: TextInputEditText
        private lateinit var etMirostatEta: TextInputEditText

        private lateinit var etMaxTokens: TextInputEditText
        private lateinit var etSamplers: TextInputEditText
        private lateinit var cbBackendSampling: MaterialCheckBox

        // Inputs (Advanced - Voice)
        private lateinit var cbEnableTts: MaterialCheckBox

        // Inputs (Prism - Markdown Plugins)
        private lateinit var cbMarkdownTables: MaterialCheckBox
        private lateinit var cbMarkdownStrikethrough: MaterialCheckBox
        private lateinit var cbMarkdownTasklist: MaterialCheckBox
        private lateinit var cbMarkdownHtml: MaterialCheckBox
        private lateinit var cbMarkdownLatex: MaterialCheckBox
        private lateinit var cbMarkdownSyntax: MaterialCheckBox

        private lateinit var containerPrismLanguages: LinearLayout

        // Inputs (Agent)
        private lateinit var cbAgentEnabled: MaterialCheckBox
        private lateinit var cbAgentBackground: MaterialCheckBox
        private lateinit var spinnerAgentScreenParserMode: Spinner
        private lateinit var spinnerIrisMotionScreenParserMode: Spinner
        private lateinit var cbAgentLlmFallback: MaterialCheckBox
        private lateinit var cbAgentTts: MaterialCheckBox
        private lateinit var cbAgentStt: MaterialCheckBox
        private lateinit var cbAgentVoiceFeedback: MaterialCheckBox
        private lateinit var cbAgentWakeWord: MaterialCheckBox
        private lateinit var etAgentWakeWord: TextInputEditText
        private lateinit var actvAgentLangOnline: AutoCompleteTextView
        private lateinit var actvAgentLangOffline: AutoCompleteTextView

        private lateinit var spinnerAgentSpeechEngine: Spinner
        private lateinit var spinnerAgentTtsEngine: Spinner
        private lateinit var spinnerTranscriptEngine: Spinner
        private lateinit var spinnerTranscriptAndroidEngine: Spinner
        private lateinit var layoutTranscriptAndroidEngine: LinearLayout
        private lateinit var rgAgentVoiceMode: RadioGroup
        private lateinit var rbModePushToTalk: MaterialRadioButton
        private lateinit var rbModeOpenMic: MaterialRadioButton
        private lateinit var tvAgentStatus: TextView
        private lateinit var btnOpenAccessibility: MaterialButton
        private lateinit var btnBatteryOptimization: MaterialButton

        private lateinit var btnAgentTestVoice: MaterialButton

        private lateinit var btnSave: MaterialButton
        private lateinit var btnReset: MaterialButton

        // IrisMotion (Tier 2.2) Views
        private lateinit var cbIrisMotionEnabled:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbIrisMotionNeuralCore:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbSphericalCompensation:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbTargetSnap: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var sliderTargetSnapRadius: com.google.android.material.slider.Slider
        private lateinit var tvTargetSnapRadiusValue: android.widget.TextView
        private lateinit var tvIrisMotionCalibrationStatus: android.widget.TextView
        private lateinit var btnIrisMotionCalibrate: MaterialButton
        private lateinit var btnIrisMotionResetCalibration: MaterialButton
        private var cbIrisMotionDwellEnabled:
                com.google.android.material.checkbox.MaterialCheckBox? =
                null
        private lateinit var sliderIrisMotionDwell: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionDwellValue: android.widget.TextView

        // Anti-Snapback ReEngagement
        private lateinit var sliderIrisMotionReengagement: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionReengagementValue: android.widget.TextView
        private lateinit var sliderIrisMotionSensitivity: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionSensitivityValue: android.widget.TextView
        private lateinit var sliderIrisMotionBtSensitivity: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionBtSensitivityValue: android.widget.TextView
        private lateinit var sliderIrisMotionBtDeadzone: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionBtDeadzoneValue: android.widget.TextView
        private lateinit var sliderIrisMotionBtAcceleration: com.google.android.material.slider.Slider
        private lateinit var tvIrisMotionBtAccelerationValue: android.widget.TextView
        private lateinit var cbIrisMotionBtInvertX: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbIrisMotionBtInvertY: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var etIrisMotionBtRemoteWidth: com.google.android.material.textfield.TextInputEditText
        private lateinit var etIrisMotionBtRemoteHeight: com.google.android.material.textfield.TextInputEditText
        private lateinit var etIrisMotionCursorSize:
                com.google.android.material.textfield.TextInputEditText
        private lateinit var spinnerIrisMotionTrackedEye: Spinner
        private lateinit var spinnerIrisMotionHeadTrackingDoF: Spinner
        private lateinit var spinnerIrisMotionCameraZoom: Spinner
        private lateinit var cbIrisMotionCameraMirror:
                com.google.android.material.checkbox.MaterialCheckBox

        // Dynamická Filter Pipeline Checkboxy
        private lateinit var cbFilterMedian: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbFilterAlphaBeta:
                com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbFilterKalman: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbFilterOneEuro: com.google.android.material.checkbox.MaterialCheckBox

        private lateinit var cbExprOneEuro: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbExprCrosstalk: com.google.android.material.checkbox.MaterialCheckBox
        private lateinit var cbExprAbt: com.google.android.material.checkbox.MaterialCheckBox

        // IrisMotion CalibrationActivity launcher
        private val calibrationLauncher =
                registerForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts
                                .StartActivityForResult()
                ) { result ->
                        if (result.resultCode == RESULT_OK) {
                                Toast.makeText(this, "✅ Kalibrace dokončena!", Toast.LENGTH_SHORT)
                                        .show()
                                updateIrisMotionCalibrationStatus()
                        } else {
                                Toast.makeText(this, "⚠️ Kalibrace zrušena", Toast.LENGTH_SHORT)
                                        .show()
                        }
                }

        // Data for Speech Engines
        private var availableSpeechEngines: List<Pair<String, String>> = emptyList()
        private var availableTtsEngines: List<Pair<String, String>> = emptyList()

        // Unified Language State
        private val installedSttLanguages = mutableSetOf<String>()
        private val supportedSttLanguages = mutableSetOf<String>()
        private val installedTtsLanguages = mutableSetOf<String>() // From CHECK_TTS_DATA

        // TTS Data Check Launcher
        private val ttsDataLauncher =
                registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result
                        ->
                        if (result.resultCode == TextToSpeech.Engine.CHECK_VOICE_DATA_PASS) {
                                val available =
                                        result.data?.getStringArrayListExtra(
                                                TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES
                                        )
                                if (available != null) {
                                        Log.i(
                                                "SettingsActivity",
                                                "🗣️ TTS Check Passed! Found ${available.size} voices."
                                        )
                                        installedTtsLanguages.clear()
                                        installedTtsLanguages.addAll(available)
                                        // Use empty set for 'supported' as we just merge TTS into
                                        // installed
                                        finalizeLanguageSelection(
                                                installedSttLanguages + installedTtsLanguages,
                                                supportedSttLanguages
                                        )
                                } else {
                                        Log.w(
                                                "SettingsActivity",
                                                "⚠️ TTS Check passed but returned no voices."
                                        )
                                }
                        } else {
                                // If check failed (e.g. data missing), we might be prompted to
                                // install.
                                // But for now, just log it. MIGHT mean engine is not ready.
                                Log.w(
                                        "SettingsActivity",
                                        "⚠️ TTS Check Failed (Result Code: ${result.resultCode})"
                                )
                        }
                }

        override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                WebAccessRuleManager.initialize(applicationContext)
                enableEdgeToEdge()
                setContentView(R.layout.activity_settings)

                val container = findViewById<View>(R.id.settings_container)
                ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
                        val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                        view.updatePadding(
                                left = systemBars.left,
                                top = systemBars.top,
                                right = systemBars.right,
                                bottom = systemBars.bottom
                        )
                        insets
                }

                settingsManager = SettingsManager(this)
                permissionManager = com.example.llama.managers.PermissionManager(this)

                bindViews()
                setupDropdowns()
                setupSyncedCheckboxes()
                loadSettings()
                setupPermissionListeners() // Moved here to avoid triggering on load
                setupZipVoiceListeners()

                // Handle intent to open specific tab (e.g. from Toolbar)
                val openTab = intent.getIntExtra("open_tab", -1)
                if (openTab >= 0) {
                        switchTab(openTab)
                } else {
                        switchTab(0) // Default to General
                }

                tabGeneral.setOnClickListener { switchTab(0) }
                tabDisplay.setOnClickListener { switchTab(1) }
                tabPrism.setOnClickListener { switchTab(2) }
                tabStyle.setOnClickListener { switchTab(3) }
                tabSampling.setOnClickListener { switchTab(4) }
                tabAdvanced.setOnClickListener { switchTab(5) }
                tabAgent.setOnClickListener { switchTab(6) }
                tabIrisMotion.setOnClickListener { switchTab(7) }
                tabVision.setOnClickListener { switchTab(8) }
                tabPerception.setOnClickListener { switchTab(9) }
                tabNlp.setOnClickListener { switchTab(10) }
                tabWhisper.setOnClickListener {
                        switchTab(11)
                        lifecycleScope.launch {
                                refreshWhisperModels()
                                refreshSherpaModels()
                        }
                }

                // Setup Prism language checkboxes
                setupPrismLanguageCheckboxes()

                // Initialize Speech Engine Spinner Listener
                spinnerAgentSpeechEngine.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        if (position >= 0 && position < availableSpeechEngines.size
                                        ) {
                                                val selectedEngine =
                                                        availableSpeechEngines[position].second
                                                if (selectedEngine !=
                                                                settingsManager.agentSpeechEngine
                                                ) {
                                                        Log.d(
                                                                "SettingsActivity",
                                                                "🔄 Speech Engine changed to: $selectedEngine. Refreshing languages..."
                                                        )
                                                        settingsManager.agentSpeechEngine =
                                                                selectedEngine
                                                        populateAvailableLanguages()
                                                }
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }

                // Initialize TTS Engine Spinner Listener
                spinnerAgentTtsEngine.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        if (position >= 0 && position < availableTtsEngines.size) {
                                                val selectedEngine =
                                                        availableTtsEngines[position].second
                                                if (selectedEngine != settingsManager.agentTtsEngine
                                                ) {
                                                        Log.d(
                                                                "SettingsActivity",
                                                                "🔄 TTS Engine changed to: $selectedEngine. Refreshing languages..."
                                                        )
                                                        settingsManager.agentTtsEngine =
                                                                selectedEngine
                                                        // Trigger language check for the new engine
                                                        populateAvailableLanguages()
                                                }
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }

                btnSave.setOnClickListener { saveSettings() }
                btnReset.setOnClickListener {
                        settingsManager.resetToDefaults()
                        loadSettings()
                        Toast.makeText(this, getString(R.string.msg_reset), Toast.LENGTH_SHORT)
                                .show()
                }
        }

        private fun bindViews() {
                layoutGeneral = findViewById(R.id.layout_general)
                layoutDisplay = findViewById(R.id.layout_display)
                layoutPrism = findViewById(R.id.layout_prism)
                layoutStyle = findViewById(R.id.layout_style)
                layoutSampling = findViewById(R.id.layout_sampling)
                layoutAdvanced = findViewById(R.id.layout_advanced)
                layoutAgent = findViewById(R.id.layout_agent)
                layoutIrisMotion = findViewById(R.id.layout_irismotion)
                layoutVision = findViewById(R.id.layout_vision)
                layoutPerception = findViewById(R.id.layout_perception)
                layoutNlp = findViewById(R.id.layout_nlp)

                tabGeneral = findViewById(R.id.tab_general)
                tabDisplay = findViewById(R.id.tab_display)
                tabPrism = findViewById(R.id.tab_prism)
                tabStyle = findViewById(R.id.tab_style)
                tabSampling = findViewById(R.id.tab_sampling)
                tabAdvanced = findViewById(R.id.tab_advanced)
                tabAgent = findViewById(R.id.tab_agent)
                tabIrisMotion = findViewById(R.id.tab_irismotion)
                tabVision = findViewById(R.id.tab_vision)
                tabPerception = findViewById(R.id.tab_perception)
                tabNlp = findViewById(R.id.tab_nlp)
                tabWhisper = findViewById(R.id.tab_whisper)
                layoutWhisper = findViewById(R.id.layout_whisper)

                // Whisper repository a UI
                whisperRepository = com.example.llama.data.WhisperModelRepository(this)
                tvWhisperActiveModel = findViewById(R.id.tv_whisper_active_model)
                tvWhisperModelSize = findViewById(R.id.tv_whisper_model_size)
                containerWhisperModels = findViewById(R.id.container_whisper_models)

                // Advanced Whisper Bindings
                tvWhisperAdvancedHeader = findViewById(R.id.tv_whisper_advanced_header)
                layoutWhisperAdvanced = findViewById(R.id.layout_whisper_advanced)
                sliderWhisperHistorySec = findViewById(R.id.slider_whisper_history_sec)
                tvWhisperHistorySecValue = findViewById(R.id.tv_whisper_history_sec_value)
                sliderWhisperProcessingIntervalMs =
                        findViewById(R.id.slider_whisper_processing_interval_ms)
                tvWhisperProcessingIntervalValue =
                        findViewById(R.id.tv_whisper_processing_interval_value)
                sliderWhisperNThreads = findViewById(R.id.slider_whisper_nthreads)
                tvWhisperNthreadsValue = findViewById(R.id.tv_whisper_nthreads_value)
                sliderWhisperSoftwareGain = findViewById(R.id.slider_whisper_software_gain)
                tvWhisperSoftwareGainValue = findViewById(R.id.tv_whisper_software_gain_value)
                spinnerWhisperLanguage = findViewById(R.id.spinner_whisper_language)
                chipgroupSettingsWhisperAccel = findViewById(R.id.chipgroup_settings_whisper_accel)

                tvWhisperAdvancedHeader.setOnClickListener {
                        val isVis = layoutWhisperAdvanced.visibility == android.view.View.VISIBLE
                        layoutWhisperAdvanced.visibility =
                                if (isVis) android.view.View.GONE else android.view.View.VISIBLE
                        tvWhisperAdvancedHeader.text =
                                if (isVis) "⚙️ Pokročilé nastavení: WHISPER STT (Klouzavé Okno) ▼"
                                else "⚙️ Pokročilé nastavení: WHISPER STT (Klouzavé Okno) ▲"
                }

                val triggerRestart = {
                        whisperRestartJob?.cancel()
                        whisperRestartJob =
                                lifecycleScope.launch {
                                        kotlinx.coroutines.delay(500)
                                        saveSettings()
                                        @android.annotation.SuppressLint("UnsafeImplicitIntentLaunch")
                                        val intent = android.content.Intent("com.example.llama.ACTION_RESTART_STT")
                                        sendBroadcast(intent)
                                }
                }

                sliderWhisperHistorySec.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvWhisperHistorySecValue.text = "${value.toLong()} ms"
                                settingsManager.whisperHistorySec = value.toLong()
                        }
                }

                chipgroupSettingsWhisperAccel.setOnCheckedStateChangeListener { _, checkedIds ->
                    val mode = when {
                        checkedIds.contains(R.id.chip_settings_whisper_cpu) -> "CPU"
                        checkedIds.contains(R.id.chip_settings_whisper_gpu) -> "GPU"
                        checkedIds.contains(R.id.chip_settings_whisper_nnapi) -> "NNAPI"
                        else -> "CPU"
                    }
                    if (settingsManager.whisperAcceleratorMode != mode) {
                        settingsManager.whisperAcceleratorMode = mode
                        triggerRestart()
                    }
                }

                sliderWhisperProcessingIntervalMs.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvWhisperProcessingIntervalValue.text = "${value.toLong()} ms"
                                settingsManager.whisperProcessingIntervalMs = value.toLong()
                        }
                }
                sliderWhisperNThreads.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvWhisperNthreadsValue.text = "${value.toInt()}"
                                settingsManager.whisperNThreads = value.toInt()
                        }
                }
                sliderWhisperSoftwareGain.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvWhisperSoftwareGainValue.text = "${value}x"
                                settingsManager.whisperSoftwareGain = value
                        }
                }

                // Attach Save button Whisper
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_save_whisper_config
                        )
                        ?.setOnClickListener {
                                triggerRestart()
                                android.widget.Toast.makeText(
                                                this,
                                                "Aplikováno (Restart STT úsp. Odeslán)",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }

                // Advanced Sherpa Bindings
                tvSherpaAdvancedHeader = findViewById(R.id.tv_sherpa_advanced_header)
                layoutSherpaAdvanced = findViewById(R.id.layout_sherpa_advanced)
                sliderSherpaHistorySec = findViewById(R.id.slider_sherpa_history_sec)
                tvSherpaHistorySecValue = findViewById(R.id.tv_sherpa_history_sec_value)
                sliderSherpaProcessingIntervalMs =
                        findViewById(R.id.slider_sherpa_processing_interval_ms)
                tvSherpaProcessingIntervalValue =
                        findViewById(R.id.tv_sherpa_processing_interval_value)
                sliderSherpaNThreads = findViewById(R.id.slider_sherpa_nthreads)
                tvSherpaNthreadsValue = findViewById(R.id.tv_sherpa_nthreads_value)
                sliderSherpaSoftwareGain = findViewById(R.id.slider_sherpa_software_gain)
                tvSherpaSoftwareGainValue = findViewById(R.id.tv_sherpa_software_gain_value)
                spinnerSherpaLanguage = findViewById(R.id.spinner_sherpa_language)
                chipgroupSettingsSherpaAccel = findViewById(R.id.chipgroup_settings_sherpa_accel)

                tvSherpaAdvancedHeader.setOnClickListener {
                        val isVis = layoutSherpaAdvanced.visibility == android.view.View.VISIBLE
                        layoutSherpaAdvanced.visibility =
                                if (isVis) android.view.View.GONE else android.view.View.VISIBLE
                        tvSherpaAdvancedHeader.text =
                                if (isVis) "⚙️ Pokročilé nastavení: SHERPA C++ (Klouzavé Okno) ▼"
                                else "⚙️ Pokročilé nastavení: SHERPA C++ (Klouzavé Okno) ▲"
                }

                sliderSherpaHistorySec.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvSherpaHistorySecValue.text = "${value.toLong()} ms"
                                settingsManager.sherpaHistorySec = value.toLong()
                        }
                }

                chipgroupSettingsSherpaAccel.setOnCheckedStateChangeListener { _, checkedIds ->
                    val mode = when {
                        checkedIds.contains(R.id.chip_settings_sherpa_cpu) -> "CPU"
                        checkedIds.contains(R.id.chip_settings_sherpa_gpu) -> "GPU"
                        checkedIds.contains(R.id.chip_settings_sherpa_nnapi) -> "NNAPI"
                        else -> "CPU"
                    }
                    if (settingsManager.sherpaAcceleratorMode != mode) {
                        settingsManager.sherpaAcceleratorMode = mode
                        triggerRestart()
                    }
                }

                sliderSherpaProcessingIntervalMs.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvSherpaProcessingIntervalValue.text = "${value.toLong()} ms"
                                settingsManager.sherpaProcessingIntervalMs = value.toLong()
                        }
                }
                sliderSherpaNThreads.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvSherpaNthreadsValue.text = "${value.toInt()}"
                                settingsManager.sherpaNThreads = value.toInt()
                        }
                }
                sliderSherpaSoftwareGain.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvSherpaSoftwareGainValue.text = "${value}x"
                                settingsManager.sherpaSoftwareGain = value
                        }
                }

                spinnerSherpaLanguage.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        val langs =
                                                listOf(
                                                        "auto",
                                                        "en",
                                                        "cs",
                                                        "de",
                                                        "fr",
                                                        "es",
                                                        "it",
                                                        "pl",
                                                        "sk",
                                                        "uk",
                                                        "ru"
                                                )
                                        if (position >= 0 && position < langs.size) {
                                                settingsManager.sherpaLanguage = langs[position]
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }

                // Attach Save button Sherpa
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_save_sherpa_config
                        )
                        ?.setOnClickListener {
                                triggerRestart()
                                android.widget.Toast.makeText(
                                                this,
                                                "Aplikováno (Restart STT úsp. Odeslán)",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }

                spinnerTranscriptEngine = findViewById(R.id.spinner_transcript_engine)
                spinnerTranscriptAndroidEngine =
                        findViewById(R.id.spinner_transcript_android_engine)
                layoutTranscriptAndroidEngine = findViewById(R.id.layout_transcript_android_engine)

                // Import tlačítko
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_whisper_import
                        )
                        .setOnClickListener { importWhisperModelLauncher.launch(arrayOf("*/*")) }

                // Smazat vše
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_whisper_delete_all
                        )
                        .setOnClickListener {
                                lifecycleScope.launch {
                                        whisperRepository.allModels.firstOrNull()?.forEach { model
                                                ->
                                                whisperRepository.deleteModel(model)
                                        }
                                        withContext(Dispatchers.Main) {
                                                Toast.makeText(
                                                                this@SettingsActivity,
                                                                "🗑️ Všechny Whisper modely smazány",
                                                                Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                                refreshWhisperModels()
                                        }
                                }
                        }

                // Sherpa-ONNX Import tlačítko + status
                val tvSherpaStatus =
                        findViewById<android.widget.TextView>(R.id.tv_sherpa_model_status)
                val sherpaModelManager =
                        com.example.llama.agent.perception.SherpaModelManager(this, settingsManager)

                // Aktualizace statusu při načtení
                val sttAvailable = sherpaModelManager.getSttModelPaths() != null
                val ttsAvailable = sherpaModelManager.getTtsModelPaths() != null

                if (sttAvailable || ttsAvailable) {
                        val sttName =
                                if (sttAvailable)
                                        "STT: ${java.io.File(settingsManager.sherpaModelPath).name}"
                                else ""
                        val ttsName =
                                if (ttsAvailable)
                                        "TTS: ${java.io.File(settingsManager.sherpaTtsModelPath).name}"
                                else ""
                        val combinedNames =
                                listOf(sttName, ttsName)
                                        .filter { it.isNotEmpty() }
                                        .joinToString(" | ")

                        val sharedVocoderFiles =
                                sherpaModelManager.getSharedDir().listFiles { file ->
                                        file.extension == "onnx"
                                }
                        val vocoderNames = sharedVocoderFiles?.joinToString(", ") { it.name } ?: ""
                        val vocoderText =
                                if (vocoderNames.isNotEmpty())
                                        "\n💽 Sdílené Vocodery: $vocoderNames"
                                else ""

                        tvSherpaStatus.text = "✅ $combinedNames$vocoderText"
                        tvSherpaStatus.setTextColor(android.graphics.Color.parseColor("#66BB6A"))
                } else {
                        tvSherpaStatus.text = "⚠️ Žádný Sherpa model nenačten"
                        tvSherpaStatus.setTextColor(android.graphics.Color.parseColor("#FFB74D"))
                }

                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_sherpa_import
                        )
                        .setOnClickListener { sherpaImportLauncher.launch(android.net.Uri.EMPTY) }

                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_sherpa_import_vocoder
                        )
                        .setOnClickListener { sherpaVocoderImportLauncher.launch(arrayOf("*/*")) }

                // Vocoder spinner: naplnit seznam z _shared/, nastavit aktuální výběr, reagovat na změnu
                val spinnerSherpaVocoder =
                        findViewById<android.widget.Spinner>(R.id.spinner_sherpa_vocoder)
                val sharedVocoderFiles =
                        sherpaModelManager
                                .getSharedDir()
                                .listFiles { f -> f.isFile && f.extension == "onnx" }
                                ?.sortedBy { it.name }
                                ?: emptyList()
                val vocoderItems = mutableListOf("Ze složky modelu (výchozí)")
                sharedVocoderFiles.forEach { vocoderItems.add(it.name) }
                val vocoderAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_item,
                                vocoderItems
                        )
                vocoderAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerSherpaVocoder.adapter = vocoderAdapter
                val currentVocoderPath = settingsManager.sherpaSharedVocoderPath
                val currentVocoderName =
                        if (currentVocoderPath.isNotBlank())
                                java.io.File(currentVocoderPath).name
                        else ""
                val vocoderIdx =
                        if (currentVocoderName.isEmpty()) 0
                        else vocoderItems.indexOf(currentVocoderName).coerceAtLeast(0)
                spinnerSherpaVocoder.setSelection(vocoderIdx)
                spinnerSherpaVocoder.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                            override fun onItemSelected(
                                    parent: android.widget.AdapterView<*>?,
                                    view: android.view.View?,
                                    position: Int,
                                    id: Long
                            ) {
                                if (position == 0) {
                                    settingsManager.sherpaSharedVocoderPath = ""
                                } else {
                                    settingsManager.sherpaSharedVocoderPath =
                                            sharedVocoderFiles[position - 1].absolutePath
                                }
                            }
                            override fun onNothingSelected(
                                    parent: android.widget.AdapterView<*>?
                            ) {}
                        }

                // Perception Engine bindings
                cbPerceptionEnabled = findViewById(R.id.cb_perception_enabled)
                actvPerceptionCameraSource = findViewById(R.id.actv_perception_camera_source)
                sliderPerceptionOdConfidence = findViewById(R.id.slider_perception_od_confidence)
                tvPerceptionOdConfidenceValue = findViewById(R.id.tv_perception_od_confidence_value)
                etPerceptionOdMaxResults = findViewById(R.id.et_perception_od_max_results)
                etPerceptionOdTimeout = findViewById(R.id.et_perception_od_timeout)
                etPerceptionOcrTimeout = findViewById(R.id.et_perception_ocr_timeout)
                etPerceptionOcrMaxLength = findViewById(R.id.et_perception_ocr_max_length)
                etPerceptionThermalSkip = findViewById(R.id.et_perception_thermal_skip)
                etPerceptionThermalGate1 = findViewById(R.id.et_perception_thermal_gate1)
                etPerceptionThermalGate2 = findViewById(R.id.et_perception_thermal_gate2)
                etPerceptionThreadPriority = findViewById(R.id.et_perception_thread_priority)
                sliderPerceptionResolution = findViewById(R.id.slider_perception_resolution)
                tvPerceptionResolutionValue = findViewById(R.id.tv_perception_resolution_value)
                
                sliderPerceptionResolution.valueFrom = 160f
                sliderPerceptionResolution.valueTo = 4000f
                sliderPerceptionResolution.addOnChangeListener { _, value, _ ->
                        tvPerceptionResolutionValue.text = "Aktuálně: ${value.toInt()}px"
                }

                // Pose Estimation UI bindings
                cbPerceptionPoseEnabled = findViewById(R.id.cb_perception_pose_enabled)
                sliderPerceptionPoseConfidence = findViewById(R.id.slider_perception_pose_confidence)
                tvPerceptionPoseConfidenceValue = findViewById(R.id.tv_perception_pose_confidence_value)
                etPerceptionPoseMaxPoses = findViewById(R.id.et_perception_pose_max_poses)
                etPerceptionPoseResolution = findViewById(R.id.et_perception_pose_resolution)
                etPerceptionPoseTimeout = findViewById(R.id.et_perception_pose_timeout)

                sliderPerceptionPoseConfidence.addOnChangeListener { _, value, _ ->
                        tvPerceptionPoseConfidenceValue.text = String.format(java.util.Locale.US, "%.2f", value)
                }

                cbPerceptionAudioEnabled = findViewById(R.id.cb_perception_audio_enabled)
                sliderPerceptionAudioConfidence =
                        findViewById(R.id.slider_perception_audio_confidence)
                tvPerceptionAudioConfidenceValue =
                        findViewById(R.id.tv_perception_audio_confidence_value)
                etPerceptionAudioMaxResults = findViewById(R.id.et_perception_audio_max_results)
                etPerceptionAudioInterval = findViewById(R.id.et_perception_audio_interval)
                etPerceptionAudioCooldown = findViewById(R.id.et_perception_audio_cooldown)
                cbPerceptionTtsScene = findViewById(R.id.cb_perception_tts_scene)
                cbPerceptionTtsCritical = findViewById(R.id.cb_perception_tts_critical)
                sliderPerceptionSceneMinEntities =
                        findViewById(R.id.slider_perception_scene_min_entities)
                tvPerceptionSceneMinEntitiesValue =
                        findViewById(R.id.tv_perception_scene_min_entities_value)

                // OD Model Manager bindings
                tvOdActiveModelName = findViewById(R.id.tv_od_active_model_name)
                tvOdActiveModelInfo = findViewById(R.id.tv_od_active_model_info)
                containerOdModels = findViewById(R.id.container_od_models)

                // YAMNet CSV Import button
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_perception_import_yamnet_csv
                        )
                        ?.setOnClickListener { importYamnetCsvLauncher.launch(arrayOf("*/*")) }

                // YAMNet CSV Export button
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_perception_export_yamnet_csv
                        )
                        ?.setOnClickListener { exportYamnetCsvLauncher.launch("yamnet_vzor_prekladu.csv") }

                // YAMNet Database list bindings
                tvYamnetActiveLang = findViewById(R.id.tv_yamnet_active_lang)
                containerYamnetDatabases = findViewById(R.id.container_yamnet_databases)
                refreshYamnetDatabases()

                // OD Import button
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_od_import_model
                        )
                        .setOnClickListener { importOdModelLauncher.launch(arrayOf("*/*")) }

                // Agent Worker Settings binding
                sliderParserMaxDepth = findViewById(R.id.slider_parser_max_depth)
                tvParserMaxDepthValue = findViewById(R.id.tv_parser_max_depth_value)
                etParserMaxElements = findViewById(R.id.et_parser_max_elements)
                etCacheMaxApps = findViewById(R.id.et_cache_max_apps)
                etCacheMaxElements = findViewById(R.id.et_cache_max_elements)
                etSafetyMaxHistory = findViewById(R.id.et_safety_max_history)
                sliderSafetyLoopThreshold = findViewById(R.id.slider_safety_loop_threshold)
                tvSafetyLoopThresholdValue = findViewById(R.id.tv_safety_loop_threshold_value)
                sliderFloatingSize = findViewById(R.id.slider_floating_size)
                tvFloatingSizeValue = findViewById(R.id.tv_floating_size_value)
                etFloatingClickThreshold = findViewById(R.id.et_floating_click_threshold)
                etFloatingMoveThreshold = findViewById(R.id.et_floating_move_threshold)
                sliderFloatingAutoHide = findViewById(R.id.slider_floating_auto_hide)
                tvFloatingAutoHideValue = findViewById(R.id.tv_floating_auto_hide_value)
                cbFloatingLockscreenHide = findViewById(R.id.cb_floating_lockscreen_hide)

                // Agent Notifier binding
                swFloatingNotifyEnabled = findViewById(R.id.sw_floating_notify_enabled)
                spinFloatingNotifyStyle = findViewById(R.id.spin_floating_notify_style)
                spinFloatingNotifyPosition = findViewById(R.id.spin_floating_notify_position)
                cbFloatingNotifyWarnings = findViewById(R.id.cb_floating_notify_warnings)
                cbFloatingNotifyVision = findViewById(R.id.cb_floating_notify_vision)
                cbFloatingNotifyAudio = findViewById(R.id.cb_floating_notify_audio)

                // NLP Tier 2.5 Bindings
                actvSummarizationBackend = findViewById(R.id.actv_summarization_backend)
                cbNlpTranslateEnabled = findViewById(R.id.cb_nlp_translate_enabled)
                cbNlpTranslateAutoDownload = findViewById(R.id.cb_nlp_translate_auto_download)
                tilNlpLangSelector = findViewById(R.id.til_nlp_lang_selector)
                actvNlpLangSelector = findViewById(R.id.actv_nlp_lang_selector)
                btnNlpDownloadLang = findViewById(R.id.btn_nlp_download_lang)
                progressNlpDownload = findViewById(R.id.progress_nlp_download)
                containerNlpDownloadedLangs = findViewById(R.id.container_nlp_downloaded_langs)
                tvNlpLangsStatus = findViewById(R.id.tv_nlp_langs_status)
                cbNlpSentimentEnabled = findViewById(R.id.cb_nlp_sentiment_enabled)
                tvNlpSentimentStatus = findViewById(R.id.tv_nlp_sentiment_status)
                cbNlpSmartReplyEnabled = findViewById(R.id.cb_nlp_smart_reply_enabled)
                cbNlpGenaiProofread = findViewById(R.id.cb_nlp_genai_proofread)
                cbNlpGenaiRewrite = findViewById(R.id.cb_nlp_genai_rewrite)
                tvNlpAicoreStatus = findViewById(R.id.tv_nlp_aicore_status)
                tvNlpAicoreVersion = findViewById(R.id.tv_nlp_aicore_version)
                btnNlpAicoreSettings = findViewById(R.id.btn_nlp_aicore_settings)

                // BERT Model Custom Import Bindings
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_bert_import_huggingface
                        )
                        .setOnClickListener {
                                pendingBertModelSource =
                                        com.example.llama.data.BertModel.Companion.ModelSource
                                                .HUGGINGFACE
                                importBertModelLauncher.launch(arrayOf("*/*"))
                        }

                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_bert_import_kaggle
                        )
                        .setOnClickListener {
                                pendingBertModelSource =
                                        com.example.llama.data.BertModel.Companion.ModelSource
                                                .KAGGLE
                                importBertModelLauncher.launch(arrayOf("*/*"))
                        }

                // NLP Linguistic Tuner bindings
                sbNlpWeightTasks = findViewById(R.id.sb_nlp_weight_tasks)
                tvNlpWeightTasksVal = findViewById(R.id.tv_nlp_weight_tasks_val)
                sbNlpWeightSlang = findViewById(R.id.sb_nlp_weight_slang)
                tvNlpWeightSlangVal = findViewById(R.id.tv_nlp_weight_slang_val)
                sbNlpWeightTech = findViewById(R.id.sb_nlp_weight_tech)
                tvNlpWeightTechVal = findViewById(R.id.tv_nlp_weight_tech_val)
                sbNlpWeightUrgency = findViewById(R.id.sb_nlp_weight_urgency)
                tvNlpWeightUrgencyVal = findViewById(R.id.tv_nlp_weight_urgency_val)

                // Setup NLP Tuner SeekBars listeners
                sbNlpWeightTasks.setOnSeekBarChangeListener(
                        object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                        if (fromUser)
                                                tvNlpWeightTasksVal.text =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%.1f",
                                                                progress / 10f
                                                        )
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                        }
                )
                sbNlpWeightSlang.setOnSeekBarChangeListener(
                        object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                        if (fromUser)
                                                tvNlpWeightSlangVal.text =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%.1f",
                                                                progress / 10f
                                                        )
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                        }
                )
                sbNlpWeightTech.setOnSeekBarChangeListener(
                        object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                        if (fromUser)
                                                tvNlpWeightTechVal.text =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%.1f",
                                                                progress / 10f
                                                        )
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                        }
                )
                sbNlpWeightUrgency.setOnSeekBarChangeListener(
                        object : android.widget.SeekBar.OnSeekBarChangeListener {
                                override fun onProgressChanged(
                                        seekBar: android.widget.SeekBar?,
                                        progress: Int,
                                        fromUser: Boolean
                                ) {
                                        if (fromUser)
                                                tvNlpWeightUrgencyVal.text =
                                                        String.format(
                                                                java.util.Locale.US,
                                                                "%.1f",
                                                                progress / 10f
                                                        )
                                }
                                override fun onStartTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                                override fun onStopTrackingTouch(
                                        seekBar: android.widget.SeekBar?
                                ) {}
                        }
                )

                // Perception slider change listeners
                sliderPerceptionOdConfidence.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvPerceptionOdConfidenceValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }
                sliderPerceptionAudioConfidence.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvPerceptionAudioConfidenceValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }
                sliderPerceptionSceneMinEntities.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvPerceptionSceneMinEntitiesValue.text = "${value.toInt()} objekty"
                }

                // Agent Worker slider change listeners
                sliderParserMaxDepth.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvParserMaxDepthValue.text = value.toInt().toString()
                }
                sliderSafetyLoopThreshold.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvSafetyLoopThresholdValue.text = value.toInt().toString()
                }
                sliderFloatingSize.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvFloatingSizeValue.text = "${value.toInt()} dp"
                }
                sliderFloatingAutoHide.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvFloatingAutoHideValue.text =
                                        if (value.toInt() == 0) "Vypnuto" else "${value.toInt()} s"
                }

                etSystemMessage = findViewById(R.id.et_system_message)
                cbShowSystem = findViewById(R.id.cb_show_system)
                etSystemMessage = findViewById(R.id.et_system_message)
                cbShowSystem = findViewById(R.id.cb_show_system)
                etMaxImportSize = findViewById(R.id.et_max_import_size)

                etSystemMessage.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val text = s?.toString() ?: ""
                        if (text.isNotBlank()) {
                            settingsManager.systemMessage = text
                        }
                    }
                })
                cbShowSystem.setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.showSystemMessage = isChecked
                }

                etContextSize = findViewById(R.id.et_context_size)
                actvBatchSize = findViewById(R.id.actv_batch_size)
                actvUBatchSize = findViewById(R.id.actv_ubatch_size)
                actvKvCache = findViewById(R.id.actv_kv_cache)
                cbFlashAttn = findViewById(R.id.cb_flash_attn)
                cbUseMmap = findViewById(R.id.cb_use_mmap)
                actvLlamaBackend = findViewById(R.id.actv_llama_backend)
                cbStrictBackend = findViewById(R.id.cb_strict_backend)
                etGpuLayers = findViewById(R.id.et_gpu_layers)
                btnCheckBackends = findViewById(R.id.btn_check_backends)
                tvBackendInfo = findViewById(R.id.tv_backend_info)

                btnCheckBackends.setOnClickListener {
                    try {
                        val engine = com.example.llama.agent.core.EngineOrchestrator.getEngineRaw(applicationContext)
                        val info = engine.getLoadedBackendInfo()
                        tvBackendInfo.text = info
                    } catch (e: Exception) {
                        tvBackendInfo.text = "Chyba zjištění backendů: ${e.message}"
                    }
                }

                sliderWebExtractTokenLimit = findViewById(R.id.slider_web_extract_token_limit)
                tvWebExtractTokenLimitValue = findViewById(R.id.tv_web_extract_token_limit_value)
                actvWebSearchMaxResults = findViewById(R.id.actv_web_search_max_results)

                val currentTokenLimit = settingsManager.webExtractTokenLimit
                sliderWebExtractTokenLimit.value = currentTokenLimit.toFloat().coerceIn(sliderWebExtractTokenLimit.valueFrom, sliderWebExtractTokenLimit.valueTo)
                tvWebExtractTokenLimitValue.text = "$currentTokenLimit tokenů"

                sliderWebExtractTokenLimit.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val intVal = value.toInt()
                        tvWebExtractTokenLimitValue.text = "$intVal tokenů"
                        settingsManager.webExtractTokenLimit = intVal
                    }
                }

                val currentMaxResults = settingsManager.webSearchMaxResults
                actvWebSearchMaxResults.setText("$currentMaxResults zdrojů", false)
                val searchResultsOptions = listOf("5 zdrojů", "10 zdrojů", "15 zdrojů", "20 zdrojů")
                val searchResultsAdapter = android.widget.ArrayAdapter(
                    this,
                    android.R.layout.simple_dropdown_item_1line,
                    searchResultsOptions
                )
                actvWebSearchMaxResults.setAdapter(searchResultsAdapter)
                actvWebSearchMaxResults.setOnItemClickListener { _, _, position, _ ->
                    val chosen = when (position) {
                        0 -> 5
                        1 -> 10
                        2 -> 15
                        3 -> 20
                        else -> 5
                    }
                    settingsManager.webSearchMaxResults = chosen
                }

                // Primární vyhledávač Spinner
                val spinnerPrimaryEngine = findViewById<Spinner>(R.id.spinner_web_primary_engine)
                val engineOptions = listOf(
                    "DuckDuckGo (výchozí)" to "duckduckgo",
                    "Brave Search Web" to "brave",
                    "Google Search Lite" to "google",
                    "SearXNG Federated JSON" to "searxng",
                    "Seznam.cz Vyhledávání" to "seznam",
                    "Wikipedie (CS & EN)" to "wikipedia",
                    "Hybridní multi-engine (všechny)" to "hybrid"
                )
                val engineAdapter = ArrayAdapter(
                    this,
                    android.R.layout.simple_spinner_dropdown_item,
                    engineOptions.map { it.first }
                )
                spinnerPrimaryEngine.adapter = engineAdapter
                val currentPrimary = settingsManager.webPrimarySearchEngine
                val selectedIndex = engineOptions.indexOfFirst { it.second.equals(currentPrimary, ignoreCase = true) }
                if (selectedIndex >= 0) {
                    spinnerPrimaryEngine.setSelection(selectedIndex)
                }
                spinnerPrimaryEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val chosenCode = engineOptions[position].second
                        settingsManager.webPrimarySearchEngine = chosenCode
                        updateWebImportStatusText()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }

                cbWebDataSaver = findViewById(R.id.cb_web_data_saver)
                cbWebThrottleBandwidth = findViewById(R.id.cb_web_throttle_bandwidth)
                cbWebEnableDuckDuckGo = findViewById(R.id.cb_web_enable_duckduckgo)
                cbWebEnableWikipedia = findViewById(R.id.cb_web_enable_wikipedia)
                cbWebEnableScience = findViewById(R.id.cb_web_enable_science)
                cbWebEnableImportedDb = findViewById(R.id.cb_web_enable_imported_db)
                cbWebEnableSqliteFts5 = findViewById(R.id.cb_web_enable_sqlite_fts5)
                cbWebPreferOfflineCache = findViewById(R.id.cb_web_prefer_offline_cache)
                cbWebEnableHeadlessWebView = findViewById(R.id.cb_web_enable_headless_webview)
                cbWebReaderDomDistiller = findViewById(R.id.cb_web_reader_dom_distiller)
                cbWebExtractJsonLd = findViewById(R.id.cb_web_extract_json_ld)
                cbWebSmartReranking = findViewById(R.id.cb_web_smart_reranking)
                cbWebUserAgentRotation = findViewById(R.id.cb_web_user_agent_rotation)
                cbWebDevBypassSecurity = findViewById(R.id.cb_web_dev_bypass_security)
                btnWebImportSources = findViewById(R.id.btn_web_import_sources)
                btnWebReindexSandbox = findViewById(R.id.btn_web_reindex_sandbox)
                tvWebImportStatus = findViewById(R.id.tv_web_import_status)
                val cbAutonomousBot = findViewById<MaterialCheckBox>(R.id.cb_web_autonomous_bot_deep_read)
                cbAutonomousBot?.isChecked = settingsManager.webAutonomousBotDeepRead
                cbAutonomousBot?.setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.webAutonomousBotDeepRead = isChecked
                    updateWebImportStatusText()
                }
                renderWebEnginesPriorityList()

                // Inicializace stavů přepínačů ze SettingsManager
                cbWebDataSaver.isChecked = settingsManager.webDataSaverEnabled
                cbWebThrottleBandwidth.isChecked = settingsManager.webThrottleBandwidthEnabled
                cbWebEnableDuckDuckGo.isChecked = settingsManager.webEnableDuckDuckGo
                cbWebEnableWikipedia.isChecked = settingsManager.webEnableWikipedia
                cbWebEnableScience.isChecked = settingsManager.webEnableScienceProviders
                cbWebEnableImportedDb.isChecked = settingsManager.webEnableImportedDatabase
                cbWebEnableSqliteFts5.isChecked = settingsManager.webEnableSqliteFts5
                cbWebPreferOfflineCache.isChecked = settingsManager.webPreferOfflineCache
                cbWebEnableHeadlessWebView.isChecked = settingsManager.webEnableHeadlessWebView
                cbWebReaderDomDistiller.isChecked = settingsManager.webReaderDomDistiller
                cbWebExtractJsonLd.isChecked = settingsManager.webExtractJsonLd
                cbWebSmartReranking.isChecked = settingsManager.webSmartReranking
                cbWebUserAgentRotation.isChecked = settingsManager.webUserAgentRotation
                cbWebDevBypassSecurity.isChecked = settingsManager.webDisableSecurityForDev

                cbWebDataSaver.setOnCheckedChangeListener { _, isChecked -> settingsManager.webDataSaverEnabled = isChecked }
                cbWebThrottleBandwidth.setOnCheckedChangeListener { _, isChecked -> settingsManager.webThrottleBandwidthEnabled = isChecked }
                cbWebEnableDuckDuckGo.setOnCheckedChangeListener { _, isChecked -> settingsManager.webEnableDuckDuckGo = isChecked }
                cbWebEnableWikipedia.setOnCheckedChangeListener { _, isChecked -> settingsManager.webEnableWikipedia = isChecked }
                cbWebEnableScience.setOnCheckedChangeListener { _, isChecked -> settingsManager.webEnableScienceProviders = isChecked }
                cbWebEnableImportedDb.setOnCheckedChangeListener { _, isChecked -> settingsManager.webEnableImportedDatabase = isChecked }
                cbWebEnableSqliteFts5.setOnCheckedChangeListener { _, isChecked ->
                    settingsManager.webEnableSqliteFts5 = isChecked
                    updateWebImportStatusText()
                }
                cbWebPreferOfflineCache.setOnCheckedChangeListener { _, isChecked -> settingsManager.webPreferOfflineCache = isChecked }
                cbWebEnableHeadlessWebView.setOnCheckedChangeListener { _, isChecked -> settingsManager.webEnableHeadlessWebView = isChecked }
                cbWebReaderDomDistiller.setOnCheckedChangeListener { _, isChecked -> settingsManager.webReaderDomDistiller = isChecked }
                cbWebExtractJsonLd.setOnCheckedChangeListener { _, isChecked -> settingsManager.webExtractJsonLd = isChecked }
                cbWebSmartReranking.setOnCheckedChangeListener { _, isChecked -> settingsManager.webSmartReranking = isChecked }
                cbWebUserAgentRotation.setOnCheckedChangeListener { _, isChecked -> settingsManager.webUserAgentRotation = isChecked }
                cbWebDevBypassSecurity.setOnCheckedChangeListener { _, isChecked -> settingsManager.webDisableSecurityForDev = isChecked }

                updateWebImportStatusText()

                btnWebImportSources.setOnClickListener {
                    showWebImportDialog()
                }

                btnWebReindexSandbox.setOnClickListener {
                    showRagManagementDialog()
                }

                cbShowStats = findViewById(R.id.cb_show_stats)
                cbShowThoughts = findViewById(R.id.cb_show_thoughts)
                cbKeepStats = findViewById(R.id.cb_keep_stats)
                cbMediaPreview = findViewById(R.id.cb_media_preview)
                cbRenderMarkdown = findViewById(R.id.cb_render_markdown)
                cbDisableScroll = findViewById(R.id.cb_disable_scroll)
                cbAlwaysSidebar = findViewById(R.id.cb_always_sidebar)
                cbAutoSidebar = findViewById(R.id.cb_auto_sidebar)
                cbBubbleCopy = findViewById(R.id.cb_bubble_copy)
                cbBackgroundGen = findViewById(R.id.cb_background_gen)
                cbHideStatusBar = findViewById(R.id.cb_hide_status_bar)
                cbSmartVisionBoundingBoxes = findViewById(R.id.cb_smartvision_bounding_boxes)
                cbSmartVisionCameraLive = findViewById(R.id.cb_smartvision_camera_live)

                // OMNIPARSER
                val tvOmniParserHeader =
                        findViewById<android.widget.TextView>(R.id.tv_smartvision_omniparser_header)
                val layoutOmniParser =
                        findViewById<android.widget.LinearLayout>(
                                R.id.layout_smartvision_omniparser
                        )
                tvOmniParserHeader?.setOnClickListener {
                        val isVisible = layoutOmniParser?.visibility == android.view.View.VISIBLE
                        layoutOmniParser?.visibility =
                                if (isVisible) android.view.View.GONE else android.view.View.VISIBLE
                        tvOmniParserHeader.text =
                                if (isVisible) "🧠 OmniParser Fúze (Zlepšení přesnosti) ▼"
                                else "🧠 OmniParser Fúze (Zlepšení přesnosti) ▲"
                }

                cbSmartVisionOmniparser = findViewById(R.id.cb_smartvision_omniparser)
                cbSmartVisionFilterEmptyImageView =
                        findViewById(R.id.cb_smartvision_filter_empty_imageview)
                cbSmartVisionFilterNonInteractive =
                        findViewById(R.id.cb_smartvision_filter_non_interactive)
                cbSmartVisionFilterScrollable = findViewById(R.id.cb_smartvision_filter_scrollable)
                cbSmartVisionFilterWebView = findViewById(R.id.cb_smartvision_filter_webview)
                cbSmartVisionFilterEmptyEditText =
                        findViewById(R.id.cb_smartvision_filter_empty_edittext)
                cbSmartVisionFilterOffscreen = findViewById(R.id.cb_smartvision_filter_offscreen)
                cbSmartVisionFilterContainers = findViewById(R.id.cb_smartvision_filter_containers)
                sliderSmartVisionMaxDepth = findViewById(R.id.slider_smartvision_max_depth)
                tvSmartVisionMaxDepthValue = findViewById(R.id.tv_smartvision_max_depth_value)

                sliderSmartVisionMaxDepth.addOnChangeListener { _, value, _ ->
                        tvSmartVisionMaxDepthValue.text = "Max hloubka: ${value.toInt()}"
                }

                sliderSmartVisionFps = findViewById(R.id.slider_smartvision_fps)
                tvSmartVisionFpsValue = findViewById(R.id.tv_smartvision_fps_value)

                sliderSmartVisionTargetResolution =
                        findViewById(R.id.slider_smartvision_target_resolution)
                tvSmartVisionTargetResolutionValue =
                        findViewById(R.id.tv_smartvision_target_resolution_value)

                sliderSmartVisionTargetResolution.valueFrom = 160f
                sliderSmartVisionTargetResolution.valueTo = 4000f
                sliderSmartVisionTargetResolution.addOnChangeListener { _, value, _ ->
                        tvSmartVisionTargetResolutionValue.text = "Aktuálně: ${value.toInt()}px"
                }

                actvSmartVisionModel = findViewById(R.id.actv_smartvision_model)
                cbSmartVisionNativeModel = findViewById(R.id.cb_smartvision_native_model)
                cbShowTokenCount = findViewById(R.id.cb_show_token_count)
                cbShowGenTime = findViewById(R.id.cb_show_gen_time)
                spinnerChatStyle = findViewById(R.id.spinner_chat_style)

                etTemp = findViewById(R.id.et_temp)
                etDynTempRange = findViewById(R.id.et_dyn_temp_range)
                etDynTempExp = findViewById(R.id.et_dyn_temp_exp)
                etTopK = findViewById(R.id.et_top_k)
                etTopP = findViewById(R.id.et_top_p)
                etMinP = findViewById(R.id.et_min_p)
                etXtcProb = findViewById(R.id.et_xtc_prob)
                etXtcThresh = findViewById(R.id.et_xtc_thresh)
                etTypicalP = findViewById(R.id.et_typical_p)

                // Binding new Penalty inputs
                etPenaltyRepeat = findViewById(R.id.et_penalty_repeat)
                etPenaltyFreq = findViewById(R.id.et_penalty_freq)
                etPenaltyPresent = findViewById(R.id.et_penalty_present)
                etPenaltyLastN = findViewById(R.id.et_penalty_last_n)

                // Binding Mirostat inputs
                spinnerMirostatMode = findViewById(R.id.spinner_mirostat_mode)
                etMirostatTau = findViewById(R.id.et_mirostat_tau)
                etMirostatEta = findViewById(R.id.et_mirostat_eta)

                etMaxTokens = findViewById(R.id.et_max_tokens)
                etSamplers = findViewById(R.id.et_samplers)
                cbBackendSampling = findViewById(R.id.cb_backend_sampling)

                cbEnableTts = findViewById(R.id.cb_enable_tts)

                // ZipVoice bindings
                spinnerSherpaZipvoiceThreads = findViewById(R.id.spinner_sherpa_zipvoice_threads)
                tvSherpaZipvoiceThreadsValue = findViewById(R.id.tv_sherpa_zipvoice_threads_value)
                spinnerSherpaZipvoicePriority = findViewById(R.id.spinner_sherpa_zipvoice_priority)
                spinnerSherpaZipvoiceSampleRate = findViewById(R.id.spinner_sherpa_zipvoice_sample_rate)
                etSherpaZipvoicePromptText = findViewById(R.id.et_sherpa_zipvoice_prompt_text)
                etSherpaTestText = findViewById(R.id.et_sherpa_test_text)
                btnSherpaRecordPrompt10s = findViewById(R.id.btn_sherpa_record_prompt_10s)
                btnSherpaRecordPromptDyn = findViewById(R.id.btn_sherpa_record_prompt_dyn)
                btnSherpaPlayPrompt = findViewById(R.id.btn_sherpa_play_prompt)
                btnSherpaClearPrompt = findViewById(R.id.btn_sherpa_clear_prompt)
                btnSherpaTestClone = findViewById(R.id.btn_sherpa_test_clone)
                tvSherpaPromptStatus = findViewById(R.id.tv_sherpa_prompt_status)

                // Prism markdown plugin checkboxes
                cbMarkdownTables = findViewById(R.id.cb_markdown_tables)
                cbMarkdownStrikethrough = findViewById(R.id.cb_markdown_strikethrough)
                cbMarkdownTasklist = findViewById(R.id.cb_markdown_tasklist)
                cbMarkdownHtml = findViewById(R.id.cb_markdown_html)
                cbMarkdownLatex = findViewById(R.id.cb_markdown_latex)
                cbMarkdownSyntax = findViewById(R.id.cb_markdown_syntax)

                containerPrismLanguages = findViewById(R.id.container_prism_languages)

                // Agent views
                cbAgentEnabled = findViewById(R.id.cb_agent_enabled)
                cbAgentBackground = findViewById(R.id.cb_agent_background)
                spinnerAgentScreenParserMode = findViewById(R.id.spinner_agent_screen_parser_mode)
                spinnerIrisMotionScreenParserMode = findViewById(R.id.spinner_irismotion_screen_parser_mode)
                cbAgentLlmFallback = findViewById(R.id.cb_agent_llm_fallback)
                cbAgentTts = findViewById(R.id.cb_agent_tts)
                cbAgentStt = findViewById(R.id.cb_agent_stt)
                cbAgentVoiceFeedback = findViewById(R.id.cb_agent_voice_feedback)
                cbAgentWakeWord = findViewById(R.id.cb_agent_wake_word)
                cbAgentWakeWord = findViewById(R.id.cb_agent_wake_word)
                etAgentWakeWord = findViewById(R.id.et_agent_wake_word)
                actvAgentLangOnline = findViewById(R.id.actv_agent_lang_online)
                actvAgentLangOffline = findViewById(R.id.actv_agent_lang_offline)
                spinnerAgentSpeechEngine = findViewById(R.id.spinner_agent_speech_engine)
                spinnerAgentTtsEngine = findViewById(R.id.spinner_agent_tts_engine)
                rgAgentVoiceMode = findViewById(R.id.rg_agent_voice_mode)
                rbModePushToTalk = findViewById(R.id.rb_mode_push_to_talk)
                rbModeOpenMic = findViewById(R.id.rb_mode_open_mic)
                tvAgentStatus = findViewById(R.id.tv_agent_status)
                btnOpenAccessibility = findViewById(R.id.btn_open_accessibility)
                btnBatteryOptimization = findViewById(R.id.btn_battery_optimization)

                btnAgentTestVoice = findViewById(R.id.btn_agent_test_voice)

                // Agent Gestures
                sliderAgentScroll = findViewById(R.id.slider_agent_scroll)
                sliderAgentZoom = findViewById(R.id.slider_agent_zoom)
                etAgentTapInterval = findViewById(R.id.et_agent_tap_interval)
                etAgentTapCount = findViewById(R.id.et_agent_tap_count)

                cbInvertSwipeVertical = findViewById(R.id.cb_invert_swipe_vertical)
                cbInvertSwipeHorizontal = findViewById(R.id.cb_invert_swipe_horizontal)

                // Listeners moved to setupPermissionListeners()

                // Agent button listeners
                btnOpenAccessibility.setOnClickListener {
                        // INTEGRACE: Použití helperu místo hardcoded Intentu
                        startActivity(
                                com.example.llama.agent.core.AgentHelper
                                        .getAccessibilitySettingsIntent()
                        )
                }

                btnBatteryOptimization.setOnClickListener {
                        val intent =
                                Intent(
                                        android.provider.Settings
                                                .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                                )
                        intent.data = "package:$packageName".toUri()
                        startActivity(intent)
                }

                btnAgentTestVoice.setOnClickListener { testAgentVoice() }

                // Cache Controls
                etCacheTtl = findViewById(R.id.et_agent_cache_ttl)
                etAgentTimeout = findViewById(R.id.et_agent_timeout)
                btnClearCache = findViewById(R.id.btn_agent_clear_cache)

                btnClearCache.setOnClickListener {
                        com.example.llama.agent.core.AppLayoutCache.clear()
                        Toast.makeText(this, "Cache snapshoty vymazány", Toast.LENGTH_SHORT).show()
                }

                // Memory Settings
                etMemoryMaxApps = findViewById(R.id.et_memory_max_apps)
                etMemoryMaxPatterns = findViewById(R.id.et_memory_max_patterns)
                etMemoryFlushInterval = findViewById(R.id.et_memory_flush_interval)
                etMemoryPatternMaxAge = findViewById(R.id.et_memory_pattern_max_age)
                etMemoryMinConfidence = findViewById(R.id.et_memory_min_confidence)
                etMemoryRamLimit = findViewById(R.id.et_memory_ram_limit)

                // App Package Registry — binding + listeners
                val btnLoadApps =
                        findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_agent_load_apps
                        )
                val btnBackupApps =
                        findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_agent_backup_apps
                        )
                val btnDeleteApps =
                        findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_agent_delete_apps
                        )

                btnLoadApps.setOnClickListener {
                        com.example.llama.agent.core.InstalledAppsRegistry.loadSnapshot(this)
                        com.example.llama.agent.core.InstalledAppsRegistry.saveToFile(this)
                        com.example.llama.agent.core.AppResolutionEngine.buildIndex()
                        val allApps = com.example.llama.agent.core.InstalledAppsRegistry.getAll()
                        val voipCount =
                                com.example.llama.agent.core.InstalledAppsRegistry.getVoipApps()
                                        .size
                        Toast.makeText(
                                        this,
                                        "📥 Načteno ${allApps.size} app ($voipCount VoIP, index: ${com.example.llama.agent.core.AppResolutionEngine.indexSize})",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                }

                btnBackupApps.setOnClickListener {
                        val path =
                                com.example.llama.agent.core.InstalledAppsRegistry.backupToFile(
                                        this
                                )
                        if (path != null) {
                                // Share Intent — uživatel si vybere kam uložit (jako export chatu)
                                val backupFile = java.io.File(path)
                                val uri =
                                        androidx.core.content.FileProvider.getUriForFile(
                                                this,
                                                "${packageName}.fileprovider",
                                                backupFile
                                        )
                                val shareIntent =
                                        android.content.Intent(android.content.Intent.ACTION_SEND)
                                                .apply {
                                                        type = "application/json"
                                                        putExtra(
                                                                android.content.Intent.EXTRA_STREAM,
                                                                uri
                                                        )
                                                        putExtra(
                                                                android.content.Intent
                                                                        .EXTRA_SUBJECT,
                                                                "App Registry Backup"
                                                        )
                                                        addFlags(
                                                                android.content.Intent
                                                                        .FLAG_GRANT_READ_URI_PERMISSION
                                                        )
                                                }
                                startActivity(
                                        android.content.Intent.createChooser(
                                                shareIntent,
                                                "Export zálohy app registru"
                                        )
                                )
                        } else {
                                Toast.makeText(
                                                this,
                                                "⚠️ Záloha selhala — nejdřív načti app",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }
                }

                btnDeleteApps.setOnClickListener {
                        com.example.llama.agent.core.InstalledAppsRegistry.deleteFile(this)
                        Toast.makeText(this, "🗑️ Seznam app smazán", Toast.LENGTH_SHORT).show()
                }

                // Import — otevře systémový file picker (SAF)
                val btnImportApps =
                        findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_agent_import_apps
                        )
                btnImportApps.setOnClickListener {
                        importAppListLauncher.launch("application/json")
                }

                btnSave = findViewById(R.id.btn_save)
                btnReset = findViewById(R.id.btn_reset)

                // IrisMotion Bindings
                cbIrisMotionEnabled = findViewById(R.id.cb_irismotion_enabled)
                cbIrisMotionNeuralCore = findViewById(R.id.cb_irismotion_neural_core)
                tvIrisMotionCalibrationStatus = findViewById(R.id.tv_irismotion_calibration_status)
                btnIrisMotionCalibrate = findViewById(R.id.btn_irismotion_calibrate)
                btnIrisMotionResetCalibration = findViewById(R.id.btn_irismotion_reset_calibration)
                val resId = resources.getIdentifier("cb_dwell_master", "id", packageName)
                if (resId != 0) {
                        cbIrisMotionDwellEnabled = findViewById(resId)
                }
                sliderIrisMotionDwell = findViewById(R.id.slider_irismotion_dwell)
                tvIrisMotionDwellValue = findViewById(R.id.tv_irismotion_dwell_value)
                sliderIrisMotionReengagement = findViewById(R.id.slider_irismotion_reengagement)
                tvIrisMotionReengagementValue = findViewById(R.id.tv_irismotion_reengagement_value)
                sliderIrisMotionSensitivity = findViewById(R.id.slider_irismotion_sensitivity)
                tvIrisMotionSensitivityValue = findViewById(R.id.tv_irismotion_sensitivity_value)
                sliderIrisMotionBtSensitivity = findViewById(R.id.slider_irismotion_bt_sensitivity)
                tvIrisMotionBtSensitivityValue = findViewById(R.id.tv_irismotion_bt_sensitivity_value)
                sliderIrisMotionBtDeadzone = findViewById(R.id.slider_irismotion_bt_deadzone)
                tvIrisMotionBtDeadzoneValue = findViewById(R.id.tv_irismotion_bt_deadzone_value)
                sliderIrisMotionBtAcceleration = findViewById(R.id.slider_irismotion_bt_acceleration)
                tvIrisMotionBtAccelerationValue = findViewById(R.id.tv_irismotion_bt_acceleration_value)
                cbIrisMotionBtInvertX = findViewById(R.id.cb_irismotion_bt_invert_x)
                cbIrisMotionBtInvertY = findViewById(R.id.cb_irismotion_bt_invert_y)
                etIrisMotionBtRemoteWidth = findViewById(R.id.et_irismotion_bt_remote_width)
                etIrisMotionBtRemoteHeight = findViewById(R.id.et_irismotion_bt_remote_height)
                etIrisMotionCursorSize = findViewById(R.id.et_irismotion_cursor_size)
                spinnerIrisMotionTrackedEye = findViewById(R.id.spinner_irismotion_tracked_eye)
                spinnerIrisMotionHeadTrackingDoF =
                        findViewById(R.id.spinner_irismotion_head_tracking_dof)

                // Head Compensation Gain slider + value label + wrapper
                val sliderHeadCompGain =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_head_comp_gain
                        )
                val tvHeadCompGainValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_head_comp_gain_value
                        )
                val layoutHeadComp =
                        findViewById<android.widget.LinearLayout>(R.id.layout_irismotion_head_comp)
                sliderHeadCompGain.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvHeadCompGainValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }

                // DoF spinner → dynamická visibility pro Head Compensation sekci
                spinnerIrisMotionHeadTrackingDoF.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        // 0=2DoF, 1=4DoF, 2=6DoF
                                        layoutHeadComp.visibility =
                                                if (position > 0) android.view.View.VISIBLE
                                                else android.view.View.GONE
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }
                cbIrisMotionCameraMirror = findViewById(R.id.cb_irismotion_camera_mirror)

                spinnerIrisMotionCameraZoom = findViewById(R.id.spinner_irismotion_camera_zoom)

                cbSphericalCompensation = findViewById(R.id.cb_irismotion_spherical_compensation)
                cbTargetSnap = findViewById(R.id.cb_irismotion_target_snap)
                sliderTargetSnapRadius = findViewById(R.id.slider_irismotion_target_snap_radius)
                tvTargetSnapRadiusValue = findViewById(R.id.tv_irismotion_target_snap_radius_value)

                val tvMagneticHeader =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_magnetic_header)
                val layoutMagnetic =
                        findViewById<android.widget.LinearLayout>(R.id.layout_irismotion_magnetic)

                tvMagneticHeader.setOnClickListener {
                        val isVisible = layoutMagnetic.visibility == android.view.View.VISIBLE
                        layoutMagnetic.visibility =
                                if (isVisible) android.view.View.GONE else android.view.View.VISIBLE
                        tvMagneticHeader.text =
                                if (isVisible) "🧲 Pokročilý Magnetic Gaze (Target Lock) ▼"
                                else "🧲 Pokročilý Magnetic Gaze (Target Lock) ▲"
                }

                sliderTargetSnapRadius.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvTargetSnapRadiusValue.text = "${value.toInt()} px"
                }

                // --- Nové slidery: Saccade Filter, Filter Sensitivity, Edge Margin ---
                val sliderSaccadeVelocity =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_saccade_velocity
                        )
                val tvSaccadeVelocityValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_saccade_velocity_value
                        )
                val sliderSaccadeBox =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_saccade_box
                        )
                val tvSaccadeBoxValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_saccade_box_value)
                val sliderFixationDrift =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_fixation_drift
                        )
                val tvFixationDriftValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_fixation_drift_value
                        )
                val sliderFilterSensitivity =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_sensitivity
                        )
                val tvFilterSensitivityValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_filter_sensitivity_value
                        )
                val sliderEdgeMargin =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_edge_margin
                        )
                val tvEdgeMarginValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_edge_margin_value)

                // OnChange listenery pro nové slidery
                sliderSaccadeVelocity.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvSaccadeVelocityValue.text =
                                        String.format(java.util.Locale.US, "%.1f", value)
                }
                sliderSaccadeBox.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvSaccadeBoxValue.text =
                                        String.format(java.util.Locale.US, "%.3f", value)
                }
                sliderFixationDrift.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvFixationDriftValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }
                sliderFilterSensitivity.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvFilterSensitivityValue.text =
                                        String.format(java.util.Locale.US, "%.1f×", value)
                }
                sliderEdgeMargin.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvEdgeMarginValue.text =
                                        String.format(java.util.Locale.US, "%.0f%%", value * 100f)
                }

                // --- Fyziologické konstanty (skládací sekce) ---
                val tvPhysioHeader =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_physio_header)
                val layoutPhysio =
                        findViewById<android.widget.LinearLayout>(R.id.layout_irismotion_physio)
                val sliderPhysioRestX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_physio_rest_x
                        )
                val tvPhysioRestXValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_physio_rest_x_value
                        )
                val sliderPhysioRestY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_physio_rest_y
                        )
                val tvPhysioRestYValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_physio_rest_y_value
                        )
                val sliderIntrinsicAmpX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_intrinsic_amp_x
                        )
                val tvIntrinsicAmpXValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_intrinsic_amp_x_value
                        )
                val sliderIntrinsicAmpY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_intrinsic_amp_y
                        )
                val tvIntrinsicAmpYValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_intrinsic_amp_y_value
                        )

                // Toggle pro skládací fyziologickou sekci
                tvPhysioHeader.setOnClickListener {
                        val isVisible = layoutPhysio.visibility == android.view.View.VISIBLE
                        layoutPhysio.visibility =
                                if (isVisible) android.view.View.GONE else android.view.View.VISIBLE
                        tvPhysioHeader.text =
                                if (isVisible) "🧬 Pokročilé fyziologické konstanty ▼"
                                else "🧬 Pokročilé fyziologické konstanty ▲"
                }

                // OnChange listenery pro fyziologické slidery
                sliderPhysioRestX.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvPhysioRestXValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }
                sliderPhysioRestY.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvPhysioRestYValue.text =
                                        String.format(java.util.Locale.US, "%.2f", value)
                }
                sliderIntrinsicAmpX.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvIntrinsicAmpXValue.text =
                                        String.format(java.util.Locale.US, "%.1f×", value)
                }
                sliderIntrinsicAmpY.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvIntrinsicAmpYValue.text =
                                        String.format(java.util.Locale.US, "%.1f×", value)
                }

                // Dynamická Filter Pipeline Bindings
                cbFilterMedian = findViewById(R.id.cb_irismotion_filter_median)
                cbFilterAlphaBeta = findViewById(R.id.cb_irismotion_filter_alphabeta)
                cbFilterKalman = findViewById(R.id.cb_irismotion_filter_kalman)
                cbFilterOneEuro = findViewById(R.id.cb_irismotion_filter_oneeuro)

                cbExprOneEuro = findViewById(R.id.cb_irismotion_expr_oneeuro)
                cbExprCrosstalk = findViewById(R.id.cb_irismotion_expr_crosstalk)
                cbExprAbt = findViewById(R.id.cb_irismotion_expr_abt)

                // IrisMotion — Filtrační parametry bindings
                val sliderFilterBeta =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_beta
                        )
                val tvFilterBetaValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_filter_beta_value)
                val sliderFilterCutoff =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_cutoff
                        )
                val tvFilterCutoffValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_filter_cutoff_value
                        )
                val sliderGazeAmpX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_x
                        )
                val tvGazeAmpXValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_gaze_amp_x_value)
                val sliderGazeAmpY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_y
                        )
                val tvGazeAmpYValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_gaze_amp_y_value)
                val sliderDeadzone =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_deadzone
                        )
                val tvDeadzoneValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_deadzone_value)
                val switchAutoCenter =
                        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                                R.id.switch_irismotion_auto_center
                        )
                val sliderAutoCenterSpeed =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_auto_center_speed
                        )
                val tvAutoCenterSpeedValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_auto_center_speed_value
                        )
                val sliderAutoCenterDelayMs =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_auto_center_delay_ms
                        )
                val tvAutoCenterDelayMsValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_auto_center_delay_ms_value
                        )
                val sliderMedian =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_median
                        )
                val tvMedianValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_median_value)

                // IrisMotion Slider live labels
                sliderIrisMotionDwell.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvIrisMotionDwellValue.text = "${value.toInt()} ms"
                }
                sliderIrisMotionReengagement.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvIrisMotionReengagementValue.text = "${value.toInt()} ms"
                }
                sliderIrisMotionSensitivity.addOnChangeListener { _, value, fromUser ->
                        if (fromUser)
                                tvIrisMotionSensitivityValue.text = String.format("%.1f×", value)
                }
                sliderIrisMotionBtSensitivity.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvIrisMotionBtSensitivityValue.text = String.format(java.util.Locale.US, "%.1f", value)
                        }
                }
                sliderIrisMotionBtDeadzone.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvIrisMotionBtDeadzoneValue.text = String.format(java.util.Locale.US, "%.1f", value)
                        }
                }
                sliderIrisMotionBtAcceleration.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) {
                                tvIrisMotionBtAccelerationValue.text = String.format(java.util.Locale.US, "%.1f", value)
                        }
                }
                sliderFilterBeta.addOnChangeListener { _, value, fromUser ->
                        tvFilterBetaValue.text = String.format("%.1f", value)
                }
                sliderFilterCutoff.addOnChangeListener { _, value, fromUser ->
                        tvFilterCutoffValue.text = String.format("%.3f Hz", value)
                }

                val sliderFilterDCutoff =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_d_cutoff
                        )
                val tvFilterDCutoffValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_filter_d_cutoff_value
                        )
                sliderFilterDCutoff.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvFilterDCutoffValue.text = String.format("%.3f", value)
                }

                val sliderKalmanQ =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_kalman_q
                        )
                val tvKalmanQValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_kalman_q_value)
                sliderKalmanQ.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvKalmanQValue.text = String.format("%.5f", value / 100000f)
                }

                val sliderKalmanR =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_kalman_r
                        )
                val tvKalmanRValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_kalman_r_value)
                sliderKalmanR.addOnChangeListener { _, value, fromUser ->
                        if (fromUser) tvKalmanRValue.text = String.format("%.2f", value)
                }

                sliderGazeAmpX.addOnChangeListener { _, value, fromUser ->
                        tvGazeAmpXValue.text = String.format("%.1f×", value)
                }
                sliderGazeAmpY.addOnChangeListener { _, value, fromUser ->
                        tvGazeAmpYValue.text = String.format("%.1f×", value)
                }

                // Střed X Offset Listener
                val sliderCenterXOffset =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_center_x_offset
                        )
                val tvCenterXOffsetValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_center_x_offset_value
                        )
                sliderCenterXOffset.addOnChangeListener { _, value, fromUser ->
                        tvCenterXOffsetValue.text = String.format("%.2f", value)
                }

                // Střed Y Offset Listener
                val sliderCenterYOffset =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_center_y_offset
                        )
                val tvCenterYOffsetValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_center_y_offset_value
                        )
                sliderCenterYOffset.addOnChangeListener { _, value, fromUser ->
                        tvCenterYOffsetValue.text = String.format("%.2f", value)
                }

                // ═══ Asymetrická kalibrace: Toggle + Slider listenery ═══
                val tvAsymHeader =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_asymmetric_header)
                val layoutAsym =
                        findViewById<android.widget.LinearLayout>(R.id.layout_irismotion_asymmetric)
                tvAsymHeader.setOnClickListener {
                        if (layoutAsym.visibility == View.GONE) {
                                layoutAsym.visibility = View.VISIBLE
                                tvAsymHeader.text = "🔬 Pokročilá asymetrická kalibrace ▲"
                        } else {
                                layoutAsym.visibility = View.GONE
                                tvAsymHeader.text = "🔬 Pokročilá asymetrická kalibrace ▼"
                        }
                }

                val sLeftEyeLeft =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_left_eye_left
                        )
                val tvLeftEyeLeftVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_left_value
                        )
                sLeftEyeLeft.addOnChangeListener { _, value, _ ->
                        tvLeftEyeLeftVal.text = String.format("%.1f×", value)
                }

                val sLeftEyeRight =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_left_eye_right
                        )
                val tvLeftEyeRightVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_right_value
                        )
                sLeftEyeRight.addOnChangeListener { _, value, _ ->
                        tvLeftEyeRightVal.text = String.format("%.1f×", value)
                }

                val sRightEyeLeft =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_right_eye_left
                        )
                val tvRightEyeLeftVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_right_eye_left_value
                        )
                sRightEyeLeft.addOnChangeListener { _, value, _ ->
                        tvRightEyeLeftVal.text = String.format("%.1f×", value)
                }

                val sRightEyeRight =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_right_eye_right
                        )
                val tvRightEyeRightVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_right_eye_right_value
                        )
                sRightEyeRight.addOnChangeListener { _, value, _ ->
                        tvRightEyeRightVal.text = String.format("%.1f×", value)
                }

                sliderDeadzone.addOnChangeListener { _, value, fromUser ->
                        tvDeadzoneValue.text = "${value.toInt()} px"
                }
                sliderAutoCenterSpeed.addOnChangeListener { _, value, fromUser ->
                        tvAutoCenterSpeedValue.text = "${value} (rychlost posunu)"
                }
                sliderAutoCenterDelayMs.addOnChangeListener { _, value, fromUser ->
                        tvAutoCenterDelayMsValue.text = "${value.toLong()} ms"
                }
                sliderMedian.addOnChangeListener { _, value, fromUser ->
                        val ws = value.toInt()
                        tvMedianValue.text = if (ws <= 1) "VYP" else "$ws framů"
                }

                // IrisMotion Calibrate button
                btnIrisMotionCalibrate.setOnClickListener {
                        val intent =
                                Intent(
                                        this,
                                        com.example.llama.agent.irismotion
                                                        .CalibrationActivity::class
                                                .java
                                )
                        calibrationLauncher.launch(intent)
                }

                // IrisMotion Reset Calibration
                btnIrisMotionResetCalibration.setOnClickListener {
                        com.example.llama.agent.irismotion.CalibrationRepository(this)
                                .clearCalibration()
                        settingsManager.isIrisMotionCalibrated = false
                        updateIrisMotionCalibrationStatus()
                        Toast.makeText(this, "🗑️ Kalibrace smazána", Toast.LENGTH_SHORT).show()
                }
                // NLP Tier 2.5 bindings
                actvSummarizationBackend = findViewById(R.id.actv_summarization_backend)
                cbNlpTranslateEnabled = findViewById(R.id.cb_nlp_translate_enabled)
                cbNlpTranslateAutoDownload = findViewById(R.id.cb_nlp_translate_auto_download)
                tilNlpLangSelector = findViewById(R.id.til_nlp_lang_selector)
                actvNlpLangSelector = findViewById(R.id.actv_nlp_lang_selector)
                btnNlpDownloadLang = findViewById(R.id.btn_nlp_download_lang)
                progressNlpDownload = findViewById(R.id.progress_nlp_download)
                containerNlpDownloadedLangs = findViewById(R.id.container_nlp_downloaded_langs)
                tvNlpLangsStatus = findViewById(R.id.tv_nlp_langs_status)
                cbNlpSentimentEnabled = findViewById(R.id.cb_nlp_sentiment_enabled)
                tvNlpSentimentStatus = findViewById(R.id.tv_nlp_sentiment_status)
                cbNlpSmartReplyEnabled = findViewById(R.id.cb_nlp_smart_reply_enabled)
                cbNlpGenaiProofread = findViewById(R.id.cb_nlp_genai_proofread)
                cbNlpGenaiRewrite = findViewById(R.id.cb_nlp_genai_rewrite)
                tvNlpAicoreStatus = findViewById(R.id.tv_nlp_aicore_status)
                tvNlpAicoreVersion = findViewById(R.id.tv_nlp_aicore_version)
                btnNlpAicoreSettings = findViewById(R.id.btn_nlp_aicore_settings)

                // Summarization backend dropdown
                val summBackends = arrayOf("aicore", "mediapipe", "mlkit", "heuristic", "llm")
                val summAdapter = ArrayAdapter(this, R.layout.dropdown_item, summBackends)
                actvSummarizationBackend.setAdapter(summAdapter)

                // MediaPipe LLM Model Loader — tlačítko pro import modelu
                findViewById<com.google.android.material.button.MaterialButton>(
                                R.id.btn_load_mediapipe_model
                        )
                        .setOnClickListener { mediaPipeLlmImportLauncher.launch(arrayOf("*/*")) }
                // Úvodní načtení stavu MediaPipe modelu
                refreshMediaPipeLlmStatus()

                // Initialize NLP Language Selector
                val availableLangs =
                        com.example.llama.agent.perception.NlpTextProcessor.getAvailableLanguages()
                val langAdapter =
                        ArrayAdapter(
                                this,
                                R.layout.dropdown_item,
                                availableLangs.values.toList().sorted()
                        )
                actvNlpLangSelector.setAdapter(langAdapter)

                // Jazykové pickery překladu: Můj jazyk ↔ Cizí jazyk
                // Generovány programaticky — napojeny na translateMyLang / translateForeignLang
                val langNames = availableLangs.values.toList().sorted()

                // Picker "Můj jazyk"
                val tilMyLang = com.google.android.material.textfield.TextInputLayout(
                    this,
                    null,
                    com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
                ).apply {
                    hint = "🇿️ Můj jazyk (zdroj/cíl překladu)"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (8 * resources.displayMetrics.density).toInt() }
                }
                val actvMy = AutoCompleteTextView(this).apply {
                    setAdapter(ArrayAdapter(this@SettingsActivity, R.layout.dropdown_item, langNames))
                    inputType = 0
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setOnItemClickListener { _, _, pos, _ ->
                        val code = availableLangs.filterValues { it == langNames[pos] }.keys.firstOrNull() ?: return@setOnItemClickListener
                        settingsManager.translateMyLang = code
                    }
                }
                tilMyLang.addView(actvMy)
                actvNlpMyLang = actvMy

                // Picker "Cizí jazyk"
                val tilForeignLang = com.google.android.material.textfield.TextInputLayout(
                    this,
                    null,
                    com.google.android.material.R.attr.textInputOutlinedExposedDropdownMenuStyle
                ).apply {
                    hint = "🌐 Cizí jazyk (cíl/zdroj reverze)"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.topMargin = (4 * resources.displayMetrics.density).toInt() }
                }
                val actvForeign = AutoCompleteTextView(this).apply {
                    setAdapter(ArrayAdapter(this@SettingsActivity, R.layout.dropdown_item, langNames))
                    inputType = 0
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setOnItemClickListener { _, _, pos, _ ->
                        val code = availableLangs.filterValues { it == langNames[pos] }.keys.firstOrNull() ?: return@setOnItemClickListener
                        settingsManager.translateForeignLang = code
                    }
                }
                tilForeignLang.addView(actvForeign)
                actvNlpForeignLang = actvForeign

                // Vlož oba pickery za existující tilNlpLangSelector
                (tilNlpLangSelector.parent as? android.view.ViewGroup)?.let { parent ->
                    val insertIdx = parent.indexOfChild(tilNlpLangSelector) + 1
                    parent.addView(tilMyLang, insertIdx)
                    parent.addView(tilForeignLang, insertIdx + 1)
                }

                // Download selected language model button
                btnNlpDownloadLang.setOnClickListener {
                        val selectedDisplay = actvNlpLangSelector.text.toString()
                        val selectedCode =
                                availableLangs
                                        .filterValues { it == selectedDisplay }
                                        .keys
                                        .firstOrNull()

                        if (selectedCode != null) {
                                downloadSelectedLanguage(selectedCode, selectedDisplay)
                        } else {
                                Toast.makeText(
                                                this,
                                                "⚠️ Vyberte prosím jazyk ze seznamu",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                        }
                }

                // Initial refresh of NLP and AICore status
                refreshNlpStatus()
                checkAICoreStatus()

                // AICore System Settings button
                btnNlpAicoreSettings.setOnClickListener {
                        try {
                                val intent =
                                        Intent(
                                                "android.settings.AI_CORE_SETTINGS"
                                        ) // System intent for 2026
                                startActivity(intent)
                        } catch (e: Exception) {
                                try {
                                        val intent =
                                                Intent(
                                                        android.provider.Settings
                                                                .ACTION_APPLICATION_DETAILS_SETTINGS
                                                )
                                        intent.data =
                                                android.net.Uri.parse(
                                                        "package:com.google.android.aicore"
                                                )
                                        startActivity(intent)
                                } catch (e2: Exception) {
                                        Toast.makeText(
                                                        this,
                                                        "Nebylo možné otevřít nastavení AICore",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                }
                        }
                }

                // Sentiment model status check (bundled + custom)
                lifecycleScope.launch(Dispatchers.IO) {
                        val models = bertRepository.getBertModelsOnly()
                        val activeModel = models.maxByOrNull { it.lastAccessTime }
                        val bundledAvailable =
                                try {
                                        assets.open(
                                                        com.example.llama.agent.perception
                                                                .NlpTextProcessor
                                                                .BUNDLED_SENTIMENT_MODEL
                                                )
                                                .use { true }
                                } catch (e: Exception) {
                                        false
                                }

                        withContext(Dispatchers.Main) {
                                if (activeModel != null) {
                                        val f = java.io.File(activeModel.sourceUri)
                                        val size = if (f.exists()) f.length() / 1024 else 0
                                        tvNlpSentimentStatus.text =
                                                "✅ Aktivní BERT: ${activeModel.displayName} ($size KB)"
                                        tvNlpSentimentStatus.setTextColor(
                                                android.graphics.Color.parseColor("#4CAF50")
                                        )
                                } else if (bundledAvailable) {
                                        tvNlpSentimentStatus.text =
                                                "✅ Bundled MobileBERT (assets/nlp_models/bert_classifier.tflite)"
                                        tvNlpSentimentStatus.setTextColor(
                                                android.graphics.Color.parseColor("#4CAF50")
                                        )
                                } else {
                                        tvNlpSentimentStatus.text =
                                                "❌ BERT model chybí — spusťte Gradle task 'downloadBertClassifier'"
                                        tvNlpSentimentStatus.setTextColor(
                                                android.graphics.Color.parseColor("#F44336")
                                        )
                                }
                        }
                }

                // NLP Linguistic Tuner bindings
                sbNlpWeightTasks = findViewById(R.id.sb_nlp_weight_tasks)
                tvNlpWeightTasksVal = findViewById(R.id.tv_nlp_weight_tasks_val)
                sbNlpWeightSlang = findViewById(R.id.sb_nlp_weight_slang)
                tvNlpWeightSlangVal = findViewById(R.id.tv_nlp_weight_slang_val)
                sbNlpWeightTech = findViewById(R.id.sb_nlp_weight_tech)
                tvNlpWeightTechVal = findViewById(R.id.tv_nlp_weight_tech_val)
                sbNlpWeightUrgency = findViewById(R.id.sb_nlp_weight_urgency)
                tvNlpWeightUrgencyVal = findViewById(R.id.tv_nlp_weight_urgency_val)

                setupNlpTunerListeners()
        } // end bindViews()

        private fun setupNlpTunerListeners() {
                val tunerPairs =
                        listOf(
                                sbNlpWeightTasks to tvNlpWeightTasksVal,
                                sbNlpWeightSlang to tvNlpWeightSlangVal,
                                sbNlpWeightTech to tvNlpWeightTechVal,
                                sbNlpWeightUrgency to tvNlpWeightUrgencyVal
                        )

                tunerPairs.forEach { (sb, tv) ->
                        sb.setOnSeekBarChangeListener(
                                object : android.widget.SeekBar.OnSeekBarChangeListener {
                                        override fun onProgressChanged(
                                                seekBar: android.widget.SeekBar?,
                                                progress: Int,
                                                fromUser: Boolean
                                        ) {
                                                val weight = (progress / 10.0) - 1.0
                                                tv.text = String.format("%.1f", weight)
                                                if (weight < 1.0)
                                                        tv.setTextColor(
                                                                android.graphics.Color.parseColor(
                                                                        "#FF9800"
                                                                )
                                                        )
                                                else if (weight > 1.0)
                                                        tv.setTextColor(
                                                                android.graphics.Color.parseColor(
                                                                        "#00BCD4"
                                                                )
                                                        )
                                                else tv.setTextColor(android.graphics.Color.WHITE)
                                        }
                                        override fun onStartTrackingTouch(
                                                seekBar: android.widget.SeekBar?
                                        ) {}
                                        override fun onStopTrackingTouch(
                                                seekBar: android.widget.SeekBar?
                                        ) {}
                                }
                        )
                }
        }

        private fun setupDropdowns() {

                // Whisper Language Setup
                val whisperLangs =
                        listOf("auto", "en", "cs", "de", "fr", "es", "it", "pl", "sk", "uk", "ru")
                val whisperLangAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_item,
                                whisperLangs
                        )
                whisperLangAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerWhisperLanguage.adapter = whisperLangAdapter
                spinnerSherpaLanguage.adapter = whisperLangAdapter

                spinnerWhisperLanguage.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        if (position >= 0 && position < whisperLangs.size) {
                                                settingsManager.whisperLanguage =
                                                        whisperLangs[position]
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }

                val kvNames = resources.getStringArray(R.array.kv_cache_names)
                val kvAdapter = ArrayAdapter(this, R.layout.dropdown_item, kvNames)
                actvKvCache.setAdapter(kvAdapter)

                val batchValues = resources.getStringArray(R.array.batch_size_values)
                val batchAdapter = ArrayAdapter(this, R.layout.dropdown_item, batchValues)
                actvBatchSize.setAdapter(batchAdapter)
                actvUBatchSize.setAdapter(batchAdapter)

                // GGML Compute Backend Dropdown
                val backendOptions = SettingsManager.LlamaBackend.entries.map { it.displayName }
                val backendAdapter = ArrayAdapter(this, R.layout.dropdown_item, backendOptions)
                actvLlamaBackend.setAdapter(backendAdapter)
                actvLlamaBackend.setOnItemClickListener { _, _, position, _ ->
                    if (position in SettingsManager.LlamaBackend.entries.indices) {
                        val selected = SettingsManager.LlamaBackend.entries[position]
                        settingsManager.llamaBackend = selected
                        saveSettings()
                    }
                }

                val chatStyleHeadless = resources.getStringArray(R.array.chat_style_options)
                val chatStyleAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, chatStyleHeadless)
                chatStyleAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerChatStyle.adapter = chatStyleAdapter

                // Mirostat Spinner
                val mirostatOptions = resources.getStringArray(R.array.mirostat_options)
                val mirostatAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, mirostatOptions)
                mirostatAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerMirostatMode.adapter = mirostatAdapter

                // Populate Speech Engines
                populateSpeechEngines()
                populateTtsEngines()

                // IrisMotion Tracked Eye Spinner
                val trackedEyeOptions = arrayOf("Obě (Averaged)", "Levé", "Pravé")
                val trackedEyeAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, trackedEyeOptions)
                trackedEyeAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerIrisMotionTrackedEye.adapter = trackedEyeAdapter

                // IrisMotion Head Tracking DoF Spinner
                val dofOptions =
                        arrayOf("2DoF (Yaw + Pitch)", "4DoF (+Roll + Zoom)", "6DoF (Plný 6DoF)")
                val dofAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, dofOptions)
                dofAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerIrisMotionHeadTrackingDoF.adapter = dofAdapter

                // IrisMotion Camera Zoom Spinner
                val zoomOptions = arrayOf("1.0x (Bez zoomu)", "1.5x", "2.0x", "3.0x")
                val zoomAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, zoomOptions)
                zoomAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerIrisMotionCameraZoom.adapter = zoomAdapter

                // ASR Engine Spinner
                val transcriptOptions = resources.getStringArray(R.array.transcript_engine_options)
                val transcriptAdapter =
                        ArrayAdapter(this, android.R.layout.simple_spinner_item, transcriptOptions)
                transcriptAdapter.setDropDownViewResource(
                        android.R.layout.simple_spinner_dropdown_item
                )
                spinnerTranscriptEngine.adapter = transcriptAdapter

                // [INTEGRATION] Immediate update for ASR Engine selection
                spinnerTranscriptEngine.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        val engine =
                                                when (position) {
                                                        0 -> "android"
                                                        1 -> "whisper"
                                                        2 -> "sherpa"
                                                        else -> "android"
                                                }

                                        layoutTranscriptAndroidEngine.visibility =
                                                if (engine == "android") android.view.View.VISIBLE
                                                else android.view.View.GONE

                                        // Only update if changed to avoid redundant restarts
                                        if (settingsManager.transcriptEngine != engine) {
                                                Log.i(
                                                        "SettingsActivity",
                                                        "🔄 ASR Engine changed to: $engine"
                                                )
                                                settingsManager.transcriptEngine = engine
                                                saveSettings() // Persist immediately for
                                                // responsiveness
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }
        }

        private fun setupPermissionListeners() {
                // Permission Logic for Voice Features
                cbAgentStt.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                                permissionManager.checkAndRequestAudioPermission { granted: Boolean
                                        ->
                                        if (!granted) cbAgentStt.isChecked = false
                                }
                        }
                }

                // Permission Logic for IrisMotion (Kamera)
                cbIrisMotionEnabled.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                                permissionManager.checkAndRequestCameraPermission { granted: Boolean
                                        ->
                                        if (!granted) cbIrisMotionEnabled.isChecked = false
                                }
                        }
                }
                cbAgentWakeWord.setOnCheckedChangeListener { _, isChecked ->
                        if (isChecked) {
                                permissionManager.checkAndRequestAudioPermission { granted: Boolean
                                        ->
                                        if (!granted) {
                                                cbAgentWakeWord.isChecked = false
                                        } else {
                                                // SYNC: Wake Word ON -> Auto-switch to Open Mic
                                                if (rgAgentVoiceMode.checkedRadioButtonId !=
                                                                R.id.rb_mode_open_mic
                                                ) {
                                                        rbModeOpenMic.isChecked = true
                                                }
                                        }
                                }
                        } else {
                                // SYNC: Wake Word OFF -> Auto-switch to Push to Talk
                                if (rgAgentVoiceMode.checkedRadioButtonId !=
                                                R.id.rb_mode_push_to_talk
                                ) {
                                        rbModePushToTalk.isChecked = true
                                }
                        }
                }

                // SYNC: Manual Radio Button selection affects Wake Word
                rgAgentVoiceMode.setOnCheckedChangeListener { _, checkedId ->
                        if (checkedId == R.id.rb_mode_open_mic) {
                                if (!cbAgentWakeWord.isChecked) cbAgentWakeWord.isChecked = true
                        } else if (checkedId == R.id.rb_mode_push_to_talk) {
                                if (cbAgentWakeWord.isChecked) cbAgentWakeWord.isChecked = false
                        }
                }
        }

        private fun setupSyncedCheckboxes() {
                // Mirror sync between General and Agent background settings
                cbAgentBackground.setOnCheckedChangeListener { _, isChecked ->
                        if (cbBackgroundGen.isChecked != isChecked) {
                                cbBackgroundGen.isChecked = isChecked
                        }
                }
                cbBackgroundGen.setOnCheckedChangeListener { _, isChecked ->
                        if (cbAgentBackground.isChecked != isChecked) {
                                cbAgentBackground.isChecked = isChecked
                        }
                }
        }

        private fun switchTab(index: Int) {
                layoutGeneral.visibility = if (index == 0) View.VISIBLE else View.GONE
                layoutDisplay.visibility = if (index == 1) View.VISIBLE else View.GONE
                layoutPrism.visibility = if (index == 2) View.VISIBLE else View.GONE
                layoutStyle.visibility = if (index == 3) View.VISIBLE else View.GONE
                layoutSampling.visibility = if (index == 4) View.VISIBLE else View.GONE
                layoutAdvanced.visibility = if (index == 5) View.VISIBLE else View.GONE
                layoutAgent.visibility = if (index == 6) View.VISIBLE else View.GONE
                layoutIrisMotion.visibility = if (index == 7) View.VISIBLE else View.GONE
                layoutVision.visibility = if (index == 8) View.VISIBLE else View.GONE
                layoutPerception.visibility = if (index == 9) View.VISIBLE else View.GONE
                layoutNlp.visibility = if (index == 10) View.VISIBLE else View.GONE
                layoutWhisper.visibility = if (index == 11) View.VISIBLE else View.GONE

                val activeColor = "#333333".toColorInt()
                tabGeneral.setBackgroundColor(if (index == 0) activeColor else Color.TRANSPARENT)
                tabDisplay.setBackgroundColor(if (index == 1) activeColor else Color.TRANSPARENT)
                tabPrism.setBackgroundColor(if (index == 2) activeColor else Color.TRANSPARENT)
                tabStyle.setBackgroundColor(if (index == 3) activeColor else Color.TRANSPARENT)
                tabSampling.setBackgroundColor(if (index == 4) activeColor else Color.TRANSPARENT)
                tabAdvanced.setBackgroundColor(if (index == 5) activeColor else Color.TRANSPARENT)
                val activeAgentColor = "#333333".toColorInt()
                tabAgent.setBackgroundColor(if (index == 6) activeAgentColor else Color.TRANSPARENT)
                tabIrisMotion.setBackgroundColor(
                        if (index == 7) "#4D1A2A".toColorInt() else Color.TRANSPARENT
                )
                tabVision.setBackgroundColor(if (index == 8) activeColor else Color.TRANSPARENT)
                tabPerception.setBackgroundColor(
                        if (index == 9) "#00433A".toColorInt() else Color.TRANSPARENT
                )
                tabNlp.setBackgroundColor(
                        if (index == 10) "#3949AB".toColorInt() else Color.TRANSPARENT
                )
                tabWhisper.setBackgroundColor(
                        if (index == 11) "#00424A".toColorInt() else Color.TRANSPARENT
                )

                // Update agent status when tab opens
                if (index == 6) {
                        updateAgentStatus()
                }
                // Update IrisMotion calibration status when tab opens
                if (index == 7) {
                        updateIrisMotionCalibrationStatus()
                }
        }

        /**
         * Obnoví seznam Whisper modelů v Settings UI. Volá se po Import / Delete operaci a při
         * otevření tabu.
         */
        private suspend fun refreshWhisperModels() {
                val models = whisperRepository.allModels.firstOrNull() ?: emptyList()
                val activeId = whisperRepository.getActiveModelId()

                withContext(Dispatchers.Main) {
                        // Aktualizace hlavičkového stavu
                        if (models.isEmpty()) {
                                tvWhisperActiveModel.text = "⚠️ Žádný model nenačten"
                                tvWhisperActiveModel.setTextColor(
                                        android.graphics.Color.parseColor("#FFB74D")
                                )
                                tvWhisperModelSize.text = ""
                        } else {
                                val activeModel =
                                        models.firstOrNull { it.id == activeId } ?: models.first()
                                tvWhisperActiveModel.text = "✅ ${activeModel.displayName}"
                                tvWhisperActiveModel.setTextColor(
                                        android.graphics.Color.parseColor("#4CAF50")
                                )
                                val sizeMb = activeModel.fileSize / (1024.0 * 1024.0)
                                tvWhisperModelSize.text =
                                        String.format(
                                                java.util.Locale.US,
                                                "Velikost: %.1f MB | ID: %s",
                                                sizeMb,
                                                activeModel.id.take(8)
                                        )
                        }

                        // Vyplnění dynamického seznamu modelů
                        containerWhisperModels.removeAllViews()
                        models.forEach { model ->
                                val itemView =
                                        layoutInflater.inflate(
                                                android.R.layout.simple_list_item_1,
                                                containerWhisperModels,
                                                false
                                        )
                                val tv =
                                        itemView.findViewById<android.widget.TextView>(
                                                android.R.id.text1
                                        )
                                val sizeMb = model.fileSize / (1024.0 * 1024.0)
                                tv.text =
                                        if (model.id == activeId)
                                                "▶ ${model.displayName} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                        else
                                                "  ${model.displayName} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                tv.setTextColor(
                                        if (model.id == activeId)
                                                android.graphics.Color.parseColor("#00BCD4")
                                        else android.graphics.Color.WHITE
                                )
                                tv.setPadding(16, 12, 16, 12)

                                // Klepnutí = nastavit jako aktivní
                                itemView.setOnClickListener {
                                        whisperRepository.setActiveModelId(model.id)
                                        lifecycleScope.launch { refreshWhisperModels() }
                                }

                                // Dlouhý stisk = nabídnout smazání
                                itemView.setOnLongClickListener {
                                        androidx.appcompat.app.AlertDialog.Builder(
                                                        this@SettingsActivity
                                                )
                                                .setTitle("Smazat model?")
                                                .setMessage(
                                                        "Smazat '${model.displayName}'? Soubor bude nenávratně odstraněn."
                                                )
                                                .setPositiveButton("Smazat") { _, _ ->
                                                        lifecycleScope.launch {
                                                                whisperRepository.deleteModel(model)
                                                                withContext(Dispatchers.Main) {
                                                                        Toast.makeText(
                                                                                        this@SettingsActivity,
                                                                                        "🗑️ Model smazán",
                                                                                        Toast.LENGTH_SHORT
                                                                                )
                                                                                .show()
                                                                        refreshWhisperModels()
                                                                }
                                                        }
                                                }
                                                .setNegativeButton("Zrušit", null)
                                                .show()
                                        true
                                }

                                containerWhisperModels.addView(itemView)
                        }
                }
        }

        /** Obnoví seznam STT a TTS modely pro Sherpa-ONNX. */
        private suspend fun refreshSherpaModels() {
                val manager =
                        com.example.llama.agent.perception.SherpaModelManager(
                                this@SettingsActivity,
                                settingsManager
                        )
                // Udelej off-thread scan
                val models = withContext(Dispatchers.IO) { manager.getImportedModels() }
                val sttActivePath = settingsManager.sherpaModelPath
                val ttsActivePath = settingsManager.sherpaTtsModelPath

                withContext(Dispatchers.Main) {
                        // Kontejnery STT
                        val containerStt =
                                findViewById<LinearLayout>(R.id.container_sherpa_stt_models)
                        containerStt?.removeAllViews()
                        val sttModels = models.filter { it.type.isStt }

                        if (sttModels.isEmpty()) {
                                val tv = android.widget.TextView(this@SettingsActivity)
                                tv.text = "⚠️ Žádný STT model nenačten"
                                tv.setTextColor(android.graphics.Color.parseColor("#FFB74D"))
                                tv.setPadding(16, 12, 16, 12)
                                containerStt?.addView(tv)
                        } else {
                                sttModels.forEach { model ->
                                        val tv = android.widget.TextView(this@SettingsActivity)
                                        val sizeMb = model.sizeBytes / (1024.0 * 1024.0)
                                        val isActive = model.path == sttActivePath
                                        tv.text =
                                                if (isActive)
                                                        "▶ ${model.name} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                                else
                                                        "  ${model.name} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                        tv.setTextColor(
                                                if (isActive)
                                                        android.graphics.Color.parseColor("#00BCD4")
                                                else android.graphics.Color.WHITE
                                        )
                                        tv.setPadding(16, 12, 16, 12)
                                        tv.isClickable = true
                                        tv.setOnClickListener {
                                                settingsManager.sherpaModelPath = model.path
                                                lifecycleScope.launch { refreshSherpaModels() }
                                        }
                                        tv.setOnLongClickListener {
                                                androidx.appcompat.app.AlertDialog.Builder(
                                                                this@SettingsActivity
                                                        )
                                                        .setTitle("Smazat Sherpa STT model?")
                                                        .setMessage("Smazat '${model.name}'?")
                                                        .setPositiveButton("Smazat") { _, _ ->
                                                                lifecycleScope.launch(
                                                                        Dispatchers.IO
                                                                ) {
                                                                        manager.deleteModel(
                                                                                model.path
                                                                        )
                                                                        withContext(
                                                                                Dispatchers.Main
                                                                        ) { refreshSherpaModels() }
                                                                }
                                                        }
                                                        .setNegativeButton("Zrušit", null)
                                                        .show()
                                                true
                                        }
                                        containerStt?.addView(tv)
                                }
                        }

                        // Kontejnery TTS
                        val containerTts =
                                findViewById<LinearLayout>(R.id.container_sherpa_tts_models)
                        containerTts?.removeAllViews()
                        val ttsModels = models.filter { it.type.isTts }

                        if (ttsModels.isEmpty()) {
                                val tv = android.widget.TextView(this@SettingsActivity)
                                tv.text = "⚠️ Žádný TTS model nenačten"
                                tv.setTextColor(android.graphics.Color.parseColor("#FFB74D"))
                                tv.setPadding(16, 12, 16, 12)
                                containerTts?.addView(tv)
                        } else {
                                ttsModels.forEach { model ->
                                        val tv = android.widget.TextView(this@SettingsActivity)
                                        val sizeMb = model.sizeBytes / (1024.0 * 1024.0)
                                        val isActive = model.path == ttsActivePath
                                        tv.text =
                                                if (isActive)
                                                        "▶ ${model.name} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                                else
                                                        "  ${model.name} (${String.format(java.util.Locale.US, "%.1f", sizeMb)} MB)"
                                        tv.setTextColor(
                                                if (isActive)
                                                        android.graphics.Color.parseColor("#00BCD4")
                                                else android.graphics.Color.WHITE
                                        )
                                        tv.setPadding(16, 12, 16, 12)
                                        tv.isClickable = true
                                        tv.setOnClickListener {
                                                settingsManager.sherpaTtsModelPath = model.path
                                                lifecycleScope.launch { refreshSherpaModels() }
                                        }
                                        tv.setOnLongClickListener {
                                                androidx.appcompat.app.AlertDialog.Builder(
                                                                this@SettingsActivity
                                                        )
                                                        .setTitle("Smazat Sherpa TTS model?")
                                                        .setMessage("Smazat '${model.name}'?")
                                                        .setPositiveButton("Smazat") { _, _ ->
                                                                lifecycleScope.launch(
                                                                        Dispatchers.IO
                                                                ) {
                                                                        manager.deleteModel(
                                                                                model.path
                                                                        )
                                                                        withContext(
                                                                                Dispatchers.Main
                                                                        ) { refreshSherpaModels() }
                                                                }
                                                        }
                                                        .setNegativeButton("Zrušit", null)
                                                        .show()
                                                true
                                        }
                                        containerTts?.addView(tv)
                                }
                        }
                }
        }

        /**
         * Safely aligns a Float value to the stepSize requirement of a Material Slider. Prevents
         * java.lang.IllegalStateException caused by float precision loss across SharedPreferences.
         */
        private fun Float.alignToSlider(slider: com.google.android.material.slider.Slider): Float {
                val step = slider.stepSize
                if (step <= 0f) return this.coerceIn(slider.valueFrom, slider.valueTo)
                val min = slider.valueFrom
                val steps = Math.round((this - min) / step)
                return (min + steps * step).coerceIn(slider.valueFrom, slider.valueTo)
        }

        // ==================== OD MODEL MANAGER — Refresh UI ====================

        /**
         * Aktualizuje UI sekci OD Model Manager v Perception tabu.
         *
         * Vzor: refreshSherpaModels() — identická architektura. Spouštět z lifecycleScope (suspend,
         * off-thread scan modelů, Main-thread UI update).
         */
        private suspend fun refreshOdModels() {
                val odManager =
                        com.example.llama.agent.perception.OdModelManager(
                                this@SettingsActivity,
                                settingsManager
                        )

                // Off-thread: skenování souborů na disku
                val allModels = withContext(Dispatchers.IO) { odManager.getAllModels() }
                val activeId = settingsManager.activeOdModelId

                withContext(Dispatchers.Main) {
                        // Aktualizace aktivního modelu header
                        val activeRecord =
                                allModels.find { it.id == activeId } ?: odManager.getBundledRecord()
                        val typeBadge =
                                when (activeRecord.modelType) {
                                        com.example.llama.agent.perception.OdModelType.MEDIAPIPE ->
                                                "[MP]"
                                        com.example.llama.agent.perception.OdModelType.RAW_SSD ->
                                                "[SSD]"
                                        com.example.llama.agent.perception.OdModelType
                                                .RAW_UNKNOWN -> "[RAW]"
                                }
                        tvOdActiveModelName.text = "$typeBadge ${activeRecord.displayName}"
                        val sizeStr =
                                if (activeRecord.fileSizeBytes > 0)
                                        "${activeRecord.fileSizeBytes / (1024 * 1024)} MB"
                                else "N/A"
                        val labelStr =
                                if (activeRecord.labelCount > 0) "${activeRecord.labelCount} tříd"
                                else "N/A tříd"
                        tvOdActiveModelInfo.text =
                                "${activeRecord.inputResolution}px · $labelStr · $sizeStr"

                        // Vyprázdni seznam a znovu sestav
                        containerOdModels.removeAllViews()

                        allModels.forEach { model ->
                                val isActive =
                                        model.id == activeId ||
                                                (activeId.isBlank() &&
                                                        model.id ==
                                                                com.example.llama.agent.perception
                                                                        .OdModelManager
                                                                        .BUNDLED_MODEL_ID)

                                // --- Karta modelu ---
                                val row = android.widget.LinearLayout(this@SettingsActivity)
                                row.orientation = android.widget.LinearLayout.HORIZONTAL
                                row.setPadding(0, 8, 0, 8)

                                val tv = android.widget.TextView(this@SettingsActivity)
                                val sizeMb =
                                        if (model.fileSizeBytes > 0)
                                                String.format(
                                                        java.util.Locale.US,
                                                        "%.1f MB",
                                                        model.fileSizeBytes / (1024.0 * 1024.0)
                                                )
                                        else "?"
                                val infoStr =
                                        if (model.inputResolution > 0)
                                                "${model.inputResolution}px · $sizeMb"
                                        else sizeMb
                                val typeBadgeModel =
                                        when (model.modelType) {
                                                com.example.llama.agent.perception.OdModelType
                                                        .MEDIAPIPE -> "[MP]"
                                                com.example.llama.agent.perception.OdModelType
                                                        .RAW_SSD -> "[SSD]"
                                                com.example.llama.agent.perception.OdModelType
                                                        .RAW_UNKNOWN -> "[RAW]"
                                        }
                                tv.text =
                                        if (isActive)
                                                "▶ $typeBadgeModel ${model.displayName}\n   $infoStr"
                                        else "  $typeBadgeModel ${model.displayName}\n   $infoStr"
                                tv.setTextColor(
                                        if (isActive) android.graphics.Color.parseColor("#00BCD4")
                                        else android.graphics.Color.WHITE
                                )
                                tv.setPadding(16, 12, 0, 12)
                                tv.layoutParams =
                                        android.widget.LinearLayout.LayoutParams(
                                                0,
                                                android.widget.LinearLayout.LayoutParams
                                                        .WRAP_CONTENT,
                                                1f
                                        )

                                // Klik = Aktivovat
                                if (!isActive) {
                                        tv.isClickable = true
                                        tv.setOnClickListener {
                                                odManager.activateModel(model.id)
                                                lifecycleScope.launch { refreshOdModels() }
                                        }
                                }

                                row.addView(tv)

                                // Tlačítko Smazat (jen pro importované modely, ne bundled)
                                if (!model.isDefault) {
                                        val btnDelete =
                                                com.google.android.material.button.MaterialButton(
                                                        this@SettingsActivity,
                                                        null,
                                                        com.google
                                                                .android
                                                                .material
                                                                .R
                                                                .attr
                                                                .materialButtonOutlinedStyle
                                                )
                                        btnDelete.text = "🗑"
                                        btnDelete.setPadding(12, 0, 12, 0)
                                        btnDelete.setOnClickListener {
                                                androidx.appcompat.app.AlertDialog.Builder(
                                                                this@SettingsActivity
                                                        )
                                                        .setTitle("Smazat OD model?")
                                                        .setMessage(
                                                                "Smazat '${model.displayName}'?"
                                                        )
                                                        .setPositiveButton("Smazat") { _, _ ->
                                                                lifecycleScope.launch(
                                                                        Dispatchers.IO
                                                                ) {
                                                                        odManager.deleteModel(
                                                                                model.id
                                                                        )
                                                                        withContext(
                                                                                Dispatchers.Main
                                                                        ) { refreshOdModels() }
                                                                }
                                                        }
                                                        .setNegativeButton("Zrušit", null)
                                                        .show()
                                        }
                                        row.addView(btnDelete)
                                }

                                containerOdModels.addView(row)
                        }
                }
        }

        private fun loadSettings() {
                etSystemMessage.setText(settingsManager.systemMessage)
                cbShowSystem.isChecked = settingsManager.showSystemMessage
                etMaxImportSize.setText(settingsManager.maxImportSize.toString())

                etContextSize.setText(settingsManager.contextSize.toString())
                cbFlashAttn.isChecked = settingsManager.flashAttention
                cbUseMmap.isChecked = settingsManager.useMmap
                actvLlamaBackend.setText(settingsManager.llamaBackend.displayName, false)
                cbStrictBackend.isChecked = settingsManager.strictBackend
                etGpuLayers.setText(settingsManager.gpuLayers.toString())

                sliderWebExtractTokenLimit.value = settingsManager.webExtractTokenLimit.toFloat().coerceIn(50f, 999f)
                tvWebExtractTokenLimitValue.text = "${settingsManager.webExtractTokenLimit} tokenů"
                actvWebSearchMaxResults.setText("${settingsManager.webSearchMaxResults} zdrojů", false)

                cbWebDataSaver.isChecked = settingsManager.webDataSaverEnabled
                cbWebThrottleBandwidth.isChecked = settingsManager.webThrottleBandwidthEnabled
                cbWebEnableDuckDuckGo.isChecked = settingsManager.webEnableDuckDuckGo
                cbWebEnableWikipedia.isChecked = settingsManager.webEnableWikipedia
                cbWebEnableScience.isChecked = settingsManager.webEnableScienceProviders
                cbWebEnableImportedDb.isChecked = settingsManager.webEnableImportedDatabase
                cbWebEnableSqliteFts5.isChecked = settingsManager.webEnableSqliteFts5
                cbWebPreferOfflineCache.isChecked = settingsManager.webPreferOfflineCache
                cbWebEnableHeadlessWebView.isChecked = settingsManager.webEnableHeadlessWebView
                cbWebReaderDomDistiller.isChecked = settingsManager.webReaderDomDistiller
                cbWebExtractJsonLd.isChecked = settingsManager.webExtractJsonLd
                cbWebSmartReranking.isChecked = settingsManager.webSmartReranking
                cbWebUserAgentRotation.isChecked = settingsManager.webUserAgentRotation
                cbWebDevBypassSecurity.isChecked = settingsManager.webDisableSecurityForDev
                updateWebImportStatusText()

                val savedKvValue = settingsManager.kvCacheMode
                val kvValues = resources.getStringArray(R.array.kv_cache_values)
                val kvNames = resources.getStringArray(R.array.kv_cache_names)
                var kvIndex = 0
                for (i in kvValues.indices) {
                        if (kvValues[i] == savedKvValue.toString()) {
                                kvIndex = i
                                break
                        }
                }
                actvKvCache.setText(kvNames[kvIndex], false)

                val savedBatchValue = settingsManager.batchSize.toString()
                actvBatchSize.setText(savedBatchValue, false)

                // Gestures
                val scrollDist = settingsManager.agentScrollDistance
                sliderAgentScroll.value = scrollDist.alignToSlider(sliderAgentScroll)

                val zoomFactor = settingsManager.agentZoomFactor
                sliderAgentZoom.value = zoomFactor.alignToSlider(sliderAgentZoom)

                val tapInterval = settingsManager.agentTapInterval
                etAgentTapInterval.setText(tapInterval.toString())

                val tapCount = settingsManager.agentTapCount
                etAgentTapCount.setText(tapCount.toString())

                val savedUBatchValue = settingsManager.ubatchSize.toString()
                actvUBatchSize.setText(savedUBatchValue, false)

                cbShowStats.isChecked = settingsManager.showStats
                cbShowThoughts.isChecked = settingsManager.showThoughtInProgress
                cbKeepStats.isChecked = settingsManager.keepStatsVisible
                cbMediaPreview.isChecked = settingsManager.isMediaPreviewEnabled
                cbRenderMarkdown.isChecked = settingsManager.renderMarkdown
                cbDisableScroll.isChecked = settingsManager.disableAutoScroll
                cbAlwaysSidebar.isChecked = settingsManager.alwaysShowSidebar
                cbAutoSidebar.isChecked = settingsManager.autoShowSidebar
                cbBubbleCopy.isChecked = settingsManager.enableBubbleCopy
                cbBackgroundGen.isChecked = settingsManager.backgroundGeneration
                cbHideStatusBar.isChecked = settingsManager.hideStatusBar
                cbShowTokenCount.isChecked = settingsManager.showTokenCount
                cbShowGenTime.isChecked = settingsManager.showGenTime

                val chatStyleValue = settingsManager.chatStyle
                val chatStyleIndex =
                        when (chatStyleValue) {
                                "oem" -> 0
                                "terminal90s" -> 1
                                "modern_terminal" -> 2
                                else -> 0
                        }
                spinnerChatStyle.setSelection(chatStyleIndex)

                etTemp.setText(settingsManager.temperature.toString())
                etDynTempRange.setText(settingsManager.dynTempRange.toString())
                etDynTempExp.setText(settingsManager.dynTempExp.toString())
                etTopK.setText(settingsManager.topK.toString())
                etTopP.setText(settingsManager.topP.toString())
                etMinP.setText(settingsManager.minP.toString())
                etXtcProb.setText(settingsManager.xtcProb.toString())
                etXtcThresh.setText(settingsManager.xtcThreshold.toString())
                etTypicalP.setText(settingsManager.typicalP.toString())

                // Load Penalty values
                etPenaltyRepeat.setText(settingsManager.penaltyRepeat.toString())
                etPenaltyFreq.setText(settingsManager.penaltyFreq.toString())
                etPenaltyPresent.setText(settingsManager.penaltyPresent.toString())
                etPenaltyLastN.setText(settingsManager.penaltyLastN.toString())

                // Load Mirostat values
                val mirostatMode = settingsManager.mirostatMode
                // 0, 1, 2 map directly to index because array is [0-Disabled, 1-v1, 2-v2]
                spinnerMirostatMode.setSelection(mirostatMode.coerceIn(0, 2))

                etMirostatTau.setText(settingsManager.mirostatTau.toString())
                etMirostatEta.setText(settingsManager.mirostatEta.toString())

                val maxTokensValue = settingsManager.maxTokens
                etMaxTokens.setText(if (maxTokensValue > 0) maxTokensValue.toString() else "")
                etMaxTokens.hint = SamplingConstants.DEFAULT_MAX_TOKENS.toString()

                etSamplers.setText(settingsManager.samplersSequence)
                cbBackendSampling.isChecked = settingsManager.backendSampling

                cbEnableTts.isChecked = settingsManager.isTtsEnabled

                // Load ZipVoice settings
                // Setup ZipVoice Threads Spinner
                val zipThreadsAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..8).toList())
                zipThreadsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerSherpaZipvoiceThreads.adapter = zipThreadsAdapter
                spinnerSherpaZipvoiceThreads.setSelection(settingsManager.sherpaTtsZipvoiceThreads - 1)
                tvSherpaZipvoiceThreadsValue.text = settingsManager.sherpaTtsZipvoiceThreads.toString()
                spinnerSherpaZipvoiceThreads.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        tvSherpaZipvoiceThreadsValue.text = (position + 1).toString()
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

                // Setup ZipVoice Priority Spinner
                val zipPriorityAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("Background", "Default", "Urgent"))
                zipPriorityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerSherpaZipvoicePriority.adapter = zipPriorityAdapter
                spinnerSherpaZipvoicePriority.setSelection(settingsManager.sherpaTtsZipvoicePriority)

                // Setup ZipVoice Sample Rate Spinner
                val zipSampleRates = listOf(8000, 16000, 22050, 24000)
                val zipRateAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, zipSampleRates)
                zipRateAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerSherpaZipvoiceSampleRate.adapter = zipRateAdapter
                spinnerSherpaZipvoiceSampleRate.setSelection(zipSampleRates.indexOf(settingsManager.sherpaTtsZipvoiceSampleRate).coerceAtLeast(0))
                
                cbAgentBackground.isChecked = settingsManager.isAgentBackgroundEnabled
                
                // Screen Parser Mód
                val modes = arrayOf("ON", "OFF", "AUTO")
                val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerAgentScreenParserMode.adapter = adapter
                spinnerIrisMotionScreenParserMode.adapter = adapter
                
                val currentMode = settingsManager.screenParserMode.name
                val pos = modes.indexOf(currentMode).takeIf { it >= 0 } ?: 2 // Default AUTO
                spinnerAgentScreenParserMode.setSelection(pos)
                spinnerIrisMotionScreenParserMode.setSelection(pos)
                
                spinnerAgentScreenParserMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        if (spinnerIrisMotionScreenParserMode.selectedItemPosition != position) {
                            spinnerIrisMotionScreenParserMode.setSelection(position)
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }
                
                spinnerIrisMotionScreenParserMode.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                        if (spinnerAgentScreenParserMode.selectedItemPosition != position) {
                            spinnerAgentScreenParserMode.setSelection(position)
                        }
                    }
                    override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
                }

                cbAgentLlmFallback.isChecked = settingsManager.agentUseLlmFallback
                etSherpaZipvoicePromptText.setText(settingsManager.sherpaTtsCustomPromptText)
                updateSherpaPromptStatus()

                // Load Prism markdown plugin settings
                loadPrismSettings()

                // Load Agent settings
                cbAgentEnabled.isChecked = settingsManager.isAgentEnabled
                cbAgentBackground.isChecked = settingsManager.isAgentBackgroundEnabled
                cbAgentLlmFallback.isChecked = settingsManager.agentUseLlmFallback
                cbAgentTts.isChecked = settingsManager.isAgentTtsEnabled
                cbAgentStt.isChecked = settingsManager.isAgentSttEnabled
                cbAgentVoiceFeedback.isChecked = settingsManager.agentVoiceFeedback
                cbAgentWakeWord.isChecked = settingsManager.isWakeWordEnabled
                etAgentWakeWord.setText(settingsManager.wakeWord)

                // Load Smart Vision Settings
                cbSmartVisionBoundingBoxes.isChecked =
                        settingsManager.isSmartVisionBoundingBoxEnabled
                cbSmartVisionCameraLive.isChecked = settingsManager.isSmartVisionCameraLiveEnabled

                cbSmartVisionOmniparser.isChecked = settingsManager.smartVisionOmniParserEnabled
                cbSmartVisionFilterEmptyImageView.isChecked =
                        settingsManager.smartVisionFilterEmptyImageView
                cbSmartVisionFilterNonInteractive.isChecked =
                        settingsManager.smartVisionFilterNonInteractive
                cbSmartVisionFilterScrollable.isChecked =
                        settingsManager.smartVisionFilterScrollable
                cbSmartVisionFilterWebView.isChecked = settingsManager.smartVisionFilterWebView
                cbSmartVisionFilterEmptyEditText.isChecked =
                        settingsManager.smartVisionFilterEmptyEditText
                cbSmartVisionFilterOffscreen.isChecked = settingsManager.smartVisionFilterOffscreen
                cbSmartVisionFilterContainers.isChecked =
                        settingsManager.smartVisionFilterContainers

                sliderSmartVisionMaxDepth.value =
                        settingsManager
                                .smartVisionMaxDepth
                                .toFloat()
                                .coerceIn(
                                        sliderSmartVisionMaxDepth.valueFrom,
                                        sliderSmartVisionMaxDepth.valueTo
                                )
                tvSmartVisionMaxDepthValue.text =
                        "Max hloubka: ${settingsManager.smartVisionMaxDepth}"

                sliderSmartVisionTargetResolution.value =
                        settingsManager
                                .smartVisionTargetResolution
                                .toFloat()
                                .alignToSlider(sliderSmartVisionTargetResolution)
                tvSmartVisionTargetResolutionValue.text =
                        "Aktuálně: ${settingsManager.smartVisionTargetResolution}px"

                sliderSmartVisionFps.value =
                        settingsManager
                                .smartVisionLiveFps
                                .toFloat()
                                .alignToSlider(sliderSmartVisionFps)
                tvSmartVisionFpsValue.text = "Aktuálně: ${settingsManager.smartVisionLiveFps} FPS"
                sliderSmartVisionFps.addOnChangeListener { _, value, _ ->
                        tvSmartVisionFpsValue.text = "Aktuálně: ${value.toInt()} FPS"
                }

                cbSmartVisionNativeModel.isChecked = settingsManager.isNativeVisionModel

                // === Perception Engine Load ===
                cbPerceptionEnabled.isChecked = settingsManager.isPerceptionEnabled

                val cameraSources = resources.getStringArray(R.array.perception_camera_sources)
                val cameraAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_dropdown_item_1line,
                                cameraSources
                        )
                actvPerceptionCameraSource.setAdapter(cameraAdapter)
                val currentCameraIdx =
                        settingsManager.perceptionCameraSource.coerceIn(0, cameraSources.size - 1)
                actvPerceptionCameraSource.setText(cameraSources[currentCameraIdx], false)

                sliderPerceptionOdConfidence.value =
                        settingsManager.perceptionOdConfidence.alignToSlider(
                                sliderPerceptionOdConfidence
                        )
                tvPerceptionOdConfidenceValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.2f",
                                settingsManager.perceptionOdConfidence
                        )
                etPerceptionOdMaxResults.setText(settingsManager.perceptionOdMaxResults.toString())
                etPerceptionOdTimeout.setText(settingsManager.perceptionOdTimeoutMs.toString())
                etPerceptionOcrTimeout.setText(settingsManager.perceptionOcrTimeoutMs.toString())
                etPerceptionOcrMaxLength.setText(settingsManager.perceptionOcrMaxLength.toString())
                etPerceptionThermalSkip.setText(settingsManager.perceptionThermalSkip.toString())
                etPerceptionThermalGate1.setText(settingsManager.perceptionThermalGate1.toString())
                etPerceptionThermalGate2.setText(settingsManager.perceptionThermalGate2.toString())
                etPerceptionThreadPriority.setText(settingsManager.perceptionThreadPriority.toString())
                sliderPerceptionResolution.value =
                        settingsManager.perceptionResolution.toFloat().alignToSlider(sliderPerceptionResolution)
                tvPerceptionResolutionValue.text = "Aktuálně: ${settingsManager.perceptionResolution}px"

                // Pose Estimation Load
                cbPerceptionPoseEnabled.isChecked = settingsManager.isPoseEstimationEnabled
                sliderPerceptionPoseConfidence.value =
                        settingsManager.perceptionPoseConfidence.alignToSlider(sliderPerceptionPoseConfidence)
                tvPerceptionPoseConfidenceValue.text =
                        String.format(java.util.Locale.US, "%.2f", settingsManager.perceptionPoseConfidence)
                etPerceptionPoseMaxPoses.setText(settingsManager.perceptionPoseMaxPoses.toString())
                etPerceptionPoseResolution.setText(settingsManager.perceptionPoseResolution.toString())
                etPerceptionPoseTimeout.setText(settingsManager.perceptionPoseTimeoutMs.toString())

                cbPerceptionAudioEnabled.isChecked = settingsManager.isAudioPerceptionEnabled
                sliderPerceptionAudioConfidence.value =
                        settingsManager.perceptionAudioConfidence.alignToSlider(
                                sliderPerceptionAudioConfidence
                        )
                tvPerceptionAudioConfidenceValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.2f",
                                settingsManager.perceptionAudioConfidence
                        )
                etPerceptionAudioMaxResults.setText(
                        settingsManager.perceptionAudioMaxResults.toString()
                )
                etPerceptionAudioInterval.setText(
                        settingsManager.perceptionAudioIntervalMs.toString()
                )
                etPerceptionAudioCooldown.setText(
                        settingsManager.perceptionAudioCooldownMs.toString()
                )
                cbPerceptionTtsScene.isChecked = settingsManager.perceptionTtsSceneChange
                cbPerceptionTtsCritical.isChecked = settingsManager.perceptionTtsCriticalAudio
                sliderPerceptionSceneMinEntities.value =
                        settingsManager
                                .perceptionSceneMinNewEntities
                                .toFloat()
                                .alignToSlider(sliderPerceptionSceneMinEntities)
                tvPerceptionSceneMinEntitiesValue.text =
                        "${settingsManager.perceptionSceneMinNewEntities} objekty"

                // OD Model Manager — načti stav při otevření Perception tabu
                lifecycleScope.launch { refreshOdModels() }

                // Load NLP Tier 2.5 Settings
                val nlpBackends = arrayOf("aicore", "mlkit", "heuristic", "llm")
                val nlpBackendAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_dropdown_item_1line,
                                nlpBackends
                        )
                actvSummarizationBackend.setAdapter(nlpBackendAdapter)

                // Whisper STT Advanced Load
                sliderWhisperHistorySec.value = settingsManager.whisperHistorySec.toFloat().coerceIn(sliderWhisperHistorySec.valueFrom, sliderWhisperHistorySec.valueTo)
                tvWhisperHistorySecValue.text = "${settingsManager.whisperHistorySec} ms"
                sliderWhisperProcessingIntervalMs.value =
                        settingsManager.whisperProcessingIntervalMs.toFloat()
                tvWhisperProcessingIntervalValue.text =
                        "${settingsManager.whisperProcessingIntervalMs} ms"
                sliderWhisperNThreads.value = settingsManager.whisperNThreads.toFloat()
                tvWhisperNthreadsValue.text = "${settingsManager.whisperNThreads}"
                sliderWhisperSoftwareGain.value = settingsManager.whisperSoftwareGain
                tvWhisperSoftwareGainValue.text = "${settingsManager.whisperSoftwareGain}x"
                when (settingsManager.whisperAcceleratorMode) {
                    "CPU" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_cpu)
                    "GPU" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_gpu)
                    "NNAPI" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_nnapi)
                    else -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_cpu)
                }

                // Sherpa STT Advanced Load
                sliderSherpaHistorySec.value = settingsManager.sherpaHistorySec.toFloat().coerceIn(sliderSherpaHistorySec.valueFrom, sliderSherpaHistorySec.valueTo)
                tvSherpaHistorySecValue.text = "${settingsManager.sherpaHistorySec} ms"
                sliderSherpaProcessingIntervalMs.value =
                        settingsManager.sherpaProcessingIntervalMs.toFloat()
                tvSherpaProcessingIntervalValue.text =
                        "${settingsManager.sherpaProcessingIntervalMs} ms"
                sliderSherpaNThreads.value = settingsManager.sherpaNThreads.toFloat()
                tvSherpaNthreadsValue.text = "${settingsManager.sherpaNThreads}"
                sliderSherpaSoftwareGain.value = settingsManager.sherpaSoftwareGain
                tvSherpaSoftwareGainValue.text = "${settingsManager.sherpaSoftwareGain}x"
                when (settingsManager.sherpaAcceleratorMode) {
                    "CPU" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_cpu)
                    "GPU" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_gpu)
                    "NNAPI" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_nnapi)
                    else -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_cpu)
                }

                // Sherpa Language Load
                val sLangA = settingsManager.sherpaLanguage
                val sLangValsA =
                        listOf("auto", "en", "cs", "de", "fr", "es", "it", "pl", "sk", "uk", "ru")
                val sLangIndexA = sLangValsA.indexOf(sLangA).takeIf { it >= 0 } ?: 0
                spinnerSherpaLanguage.setSelection(sLangIndexA)

                // Whisper Language Load
                val wLang = settingsManager.whisperLanguage
                val wLangIndex = sLangValsA.indexOf(wLang).takeIf { it >= 0 } ?: 0
                spinnerWhisperLanguage.setSelection(wLangIndex)

                actvSummarizationBackend.setText(settingsManager.summarizationBackend, false)

                cbNlpTranslateEnabled.isChecked = settingsManager.translateEnabled
                cbNlpTranslateAutoDownload.isChecked = settingsManager.translateAutoDownload
                cbNlpSentimentEnabled.isChecked = settingsManager.sentimentAnalysisEnabled
                cbNlpSmartReplyEnabled.isChecked = settingsManager.smartReplyEnabled
                cbNlpGenaiProofread.isChecked = settingsManager.genaiProofreadEnabled
                cbNlpGenaiRewrite.isChecked = settingsManager.genaiRewriteEnabled

                // Load NLP Linguistic Tuner Settings
                val wtTasks = settingsManager.nlpWeightTasks
                sbNlpWeightTasks.progress = (wtTasks * 10).toInt()
                tvNlpWeightTasksVal.text = String.format(java.util.Locale.US, "%.1f", wtTasks)

                val wtSlang = settingsManager.nlpWeightSlang
                sbNlpWeightSlang.progress = (wtSlang * 10).toInt()
                tvNlpWeightSlangVal.text = String.format(java.util.Locale.US, "%.1f", wtSlang)

                val wtTech = settingsManager.nlpWeightTech
                sbNlpWeightTech.progress = (wtTech * 10).toInt()
                tvNlpWeightTechVal.text = String.format(java.util.Locale.US, "%.1f", wtTech)

                val wtUrgency = settingsManager.nlpWeightUrgency
                sbNlpWeightUrgency.progress = (wtUrgency * 10).toInt()
                tvNlpWeightUrgencyVal.text = String.format(java.util.Locale.US, "%.1f", wtUrgency)

                // Load Agent Worker Settings
                sliderParserMaxDepth.value =
                        settingsManager
                                .screenParserMaxDepth
                                .toFloat()
                                .alignToSlider(sliderParserMaxDepth)
                tvParserMaxDepthValue.text = settingsManager.screenParserMaxDepth.toString()
                etParserMaxElements.setText(settingsManager.screenParserMaxElements.toString())
                etCacheMaxApps.setText(settingsManager.appCacheMaxApps.toString())
                etCacheMaxElements.setText(settingsManager.appCacheMaxElements.toString())
                etSafetyMaxHistory.setText(settingsManager.safetyGuardMaxHistory.toString())
                sliderSafetyLoopThreshold.value =
                        settingsManager
                                .safetyGuardLoopThreshold
                                .toFloat()
                                .alignToSlider(sliderSafetyLoopThreshold)
                tvSafetyLoopThresholdValue.text =
                        settingsManager.safetyGuardLoopThreshold.toString()
                sliderFloatingSize.value =
                        settingsManager
                                .floatingButtonSizeDp
                                .toFloat()
                                .alignToSlider(sliderFloatingSize)
                tvFloatingSizeValue.text = "${settingsManager.floatingButtonSizeDp} dp"
                etFloatingClickThreshold.setText(
                        settingsManager.floatingButtonClickThresholdMs.toString()
                )
                etFloatingMoveThreshold.setText(
                        settingsManager.floatingButtonMoveThresholdPx.toString()
                )
                sliderFloatingAutoHide.value =
                        settingsManager
                                .floatingButtonAutoHideSec
                                .toFloat()
                                .alignToSlider(sliderFloatingAutoHide)
                tvFloatingAutoHideValue.text =
                        if (settingsManager.floatingButtonAutoHideSec == 0) "Vypnuto"
                        else "${settingsManager.floatingButtonAutoHideSec} s"
                cbFloatingLockscreenHide.isChecked = settingsManager.floatingButtonLockscreenHide

                // Load Agent Notifier Settings
                swFloatingNotifyEnabled.isChecked = settingsManager.floatingNotifyEnabled
                spinFloatingNotifyStyle.setSelection(settingsManager.floatingNotifyStyle)
                spinFloatingNotifyPosition.setSelection(settingsManager.floatingNotifyPosition)
                cbFloatingNotifyWarnings.isChecked = settingsManager.floatingNotifyShowWarnings
                cbFloatingNotifyVision.isChecked = settingsManager.floatingNotifyShowVision
                cbFloatingNotifyAudio.isChecked = settingsManager.floatingNotifyShowAudio

                lifecycleScope.launch(Dispatchers.IO) {
                        try {
                                val db =
                                        com.example.llama.data.LlamaDatabase.getDatabase(
                                                applicationContext
                                        )
                                val allModels =
                                        db.aiModelDao().getAllModels().firstOrNull() ?: emptyList()

                                withContext(Dispatchers.Main) {
                                        visionModelsMap.clear()
                                        // Filter models with mmproj in name, or just let user pick
                                        // any. We filter to help UX.
                                        val visionModels =
                                                allModels
                                                        .filter {
                                                                it.displayName.contains(
                                                                        "mmproj",
                                                                        ignoreCase = true
                                                                )
                                                        }
                                                        .ifEmpty { allModels }

                                        val modelNames =
                                                visionModels
                                                        .map {
                                                                visionModelsMap[it.displayName] =
                                                                        it.id
                                                                it.displayName
                                                        }
                                                        .toTypedArray()

                                        val adapter =
                                                android.widget.ArrayAdapter(
                                                        this@SettingsActivity,
                                                        android.R.layout.simple_dropdown_item_1line,
                                                        modelNames
                                                )
                                        actvSmartVisionModel.setAdapter(adapter)

                                        val activeId = settingsManager.activeVisionModelId
                                        val activeModel = allModels.find { it.id == activeId }
                                        if (activeModel != null) {
                                                actvSmartVisionModel.setText(
                                                        activeModel.displayName,
                                                        false
                                                )
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e("SettingsActivity", "Error loading vision models", e)
                        }
                }

                // Load Transcript Engine
                val transcriptEngine = settingsManager.transcriptEngine
                val transcriptIndex =
                        when (transcriptEngine) {
                                "whisper" -> 1
                                "sherpa" -> 2
                                else -> 0
                        }
                spinnerTranscriptEngine.setSelection(transcriptIndex)

                // Load Cache TTL
                etCacheTtl.setText(settingsManager.appLayoutCacheTtl.toString())
                // Load Agent Timeout (v sekundách)
                etAgentTimeout.setText((settingsManager.agentTimeoutMs / 1000).toString())

                when (settingsManager.agentVoiceMode) {
                        SettingsManager.VOICEMODE_OPEN_MIC -> rbModeOpenMic.isChecked = true
                        else -> rbModePushToTalk.isChecked = true
                }

                cbInvertSwipeVertical.isChecked = settingsManager.isInvertSwipeVertical
                cbInvertSwipeHorizontal.isChecked = settingsManager.isInvertSwipeHorizontal

                updateAgentStatus()

                // Load Speech Engine Selection
                val savedEngine = settingsManager.agentSpeechEngine
                if (savedEngine.isNotEmpty()) {
                        val index = availableSpeechEngines.indexOfFirst { it.second == savedEngine }
                        if (index >= 0) {
                                spinnerAgentSpeechEngine.setSelection(
                                        index,
                                        false
                                ) // false to avoid immediate redundant trigger
                        }
                }

                // Load Transcript Android Engine Selection
                val savedTranscriptEngine = settingsManager.transcriptAndroidEngine
                if (savedTranscriptEngine.isNotEmpty()) {
                        val tiIndex =
                                availableSpeechEngines.indexOfFirst {
                                        it.second == savedTranscriptEngine
                                }
                        if (tiIndex >= 0) {
                                spinnerTranscriptAndroidEngine.setSelection(tiIndex, false)
                        }
                }

                // Load TTS Engine Selection
                val savedTtsEngine = settingsManager.agentTtsEngine
                if (savedTtsEngine.isNotEmpty()) {
                        val index = availableTtsEngines.indexOfFirst { it.second == savedTtsEngine }
                        if (index >= 0) {
                                spinnerAgentTtsEngine.setSelection(index, false)
                        }
                }

                // Load Dual Language Settings
                actvAgentLangOnline.setText(settingsManager.agentLanguageOnline, false)
                actvAgentLangOffline.setText(settingsManager.agentLanguageOffline, false)
                findViewById<MaterialCheckBox>(R.id.cb_agent_force_offline).isChecked =
                        settingsManager.isSttForceOffline

                // Load Agent Timeouts
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_timeout
                        )
                        .setText(settingsManager.agentTimeoutMs.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_llm_cancel_timeout
                        )
                        .setText(settingsManager.agentLlmCancelTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_smartclick_timeout
                        )
                        .setText(settingsManager.agentSmartClickTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_app_load_wait
                        )
                        .setText(settingsManager.agentAppLoadWait.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_gesture_timeout
                        )
                        .setText(settingsManager.agentGestureTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_agent_toggle_timeout
                        )
                        .setText(settingsManager.agentToggleTimeout.toString())

                // Load Memory Settings
                etMemoryMaxApps.setText(settingsManager.memoryMaxApps.toString())
                etMemoryMaxPatterns.setText(settingsManager.memoryMaxPatterns.toString())
                etMemoryFlushInterval.setText(
                        (settingsManager.memoryFlushInterval / 1000).toString()
                )
                etMemoryPatternMaxAge.setText(settingsManager.memoryPatternMaxAge.toString())
                etMemoryMinConfidence.setText(settingsManager.memoryMinConfidence.toString())
                etMemoryRamLimit.setText(settingsManager.memoryRamLimitKb.toString())

                populateAvailableLanguages()

                // Load IrisMotion Settings
                cbIrisMotionEnabled.isChecked = settingsManager.isIrisMotionEnabled
                cbIrisMotionNeuralCore.isChecked = settingsManager.isIrisMotionNeuralCoreEnabled
                cbIrisMotionDwellEnabled?.isChecked = settingsManager.isIrisMotionDwellEnabled
                sliderIrisMotionDwell.value =
                        settingsManager
                                .irisMotionDwellMs
                                .toFloat()
                                .alignToSlider(sliderIrisMotionDwell)
                tvIrisMotionDwellValue.text = "${settingsManager.irisMotionDwellMs} ms"

                sliderIrisMotionReengagement.value =
                        settingsManager
                                .irisMotionReEngagementTimeout
                                .toFloat()
                                .alignToSlider(sliderIrisMotionReengagement)
                tvIrisMotionReengagementValue.text =
                        "${settingsManager.irisMotionReEngagementTimeout} ms"

                // P8: Blink Sensitivity UI
                val sliderBlinkSens =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_blink_sensitivity
                        )
                val tvBlinkSensVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_blink_sensitivity_value
                        )
                val tvPhysioEAR =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_physio_open_ear)

                sliderBlinkSens.value =
                        settingsManager.irisMotionBlinkSensitivity.alignToSlider(sliderBlinkSens)
                tvBlinkSensVal.text =
                        "${(settingsManager.irisMotionBlinkSensitivity * 100).toInt()}%"
                tvPhysioEAR.text =
                        "Změřený Baseline EAR: ${String.format(java.util.Locale.US, "%.3f", settingsManager.irisMotionPhysioOpenEAR)} (Auto-kalibrováno)"

                sliderBlinkSens.addOnChangeListener { _, value, _ ->
                        tvBlinkSensVal.text = "${(value * 100).toInt()}%"
                }

                // P7: Time-Shifted Click UI
                val cbTimeShift =
                        findViewById<com.google.android.material.checkbox.MaterialCheckBox>(
                                R.id.cb_irismotion_timeshift_enabled
                        )
                cbTimeShift.isChecked = settingsManager.irisMotionTimeShiftEnabled

                val sliderTimeShift =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_timeshift
                        )
                val tvTimeShiftVal =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_timeshift_value)
                sliderTimeShift.value =
                        settingsManager
                                .irisMotionTimeShiftMs
                                .toFloat()
                                .alignToSlider(sliderTimeShift)
                tvTimeShiftVal.text = "${settingsManager.irisMotionTimeShiftMs} ms"

                sliderTimeShift.addOnChangeListener { _, value, _ ->
                        tvTimeShiftVal.text = "${value.toInt()} ms"
                }

                // P7: Intent Filter UI
                val cbIntentFilter =
                        findViewById<com.google.android.material.checkbox.MaterialCheckBox>(
                                R.id.cb_irismotion_intent_filter_enabled
                        )
                cbIntentFilter.isChecked = settingsManager.irisMotionIntentFilterEnabled

                val sliderIntentFilter =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_intent_filter_threshold
                        )
                val tvIntentFilterVal =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_intent_filter_value
                        )
                sliderIntentFilter.value =
                        settingsManager.irisMotionIntentFilterThresholdDeg.alignToSlider(
                                sliderIntentFilter
                        )
                tvIntentFilterVal.text =
                        String.format(
                                java.util.Locale.US,
                                "%.1f°",
                                settingsManager.irisMotionIntentFilterThresholdDeg
                        )

                sliderIntentFilter.addOnChangeListener { _, value, _ ->
                        tvIntentFilterVal.text = String.format(java.util.Locale.US, "%.1f°", value)
                }

                sliderIrisMotionSensitivity.value =
                        settingsManager.irisMotionSensitivity.alignToSlider(
                                sliderIrisMotionSensitivity
                        )
                sliderIrisMotionBtSensitivity.value =
                        settingsManager.irismotionBtSensitivity.alignToSlider(
                                sliderIrisMotionBtSensitivity
                        )
                tvIrisMotionBtSensitivityValue.text =
                        String.format(java.util.Locale.US, "%.1f", settingsManager.irismotionBtSensitivity)

                sliderIrisMotionBtDeadzone.value =
                        settingsManager.irismotionBtDeadzone.alignToSlider(
                                sliderIrisMotionBtDeadzone
                        )
                tvIrisMotionBtDeadzoneValue.text =
                        String.format(java.util.Locale.US, "%.1f", settingsManager.irismotionBtDeadzone)

                sliderIrisMotionBtAcceleration.value =
                        settingsManager.irismotionBtAcceleration.alignToSlider(
                                sliderIrisMotionBtAcceleration
                        )
                tvIrisMotionBtAccelerationValue.text =
                        String.format(java.util.Locale.US, "%.1f", settingsManager.irismotionBtAcceleration)
                
                cbIrisMotionBtInvertX.isChecked = settingsManager.irismotionBtInvertX
                cbIrisMotionBtInvertY.isChecked = settingsManager.irismotionBtInvertY
                etIrisMotionBtRemoteWidth.setText(settingsManager.irismotionBtRemoteWidth.toString())
                etIrisMotionBtRemoteHeight.setText(settingsManager.irismotionBtRemoteHeight.toString())

                tvIrisMotionSensitivityValue.text =
                        String.format("%.1f×", settingsManager.irisMotionSensitivity)
                // Nové slidery: Saccade Filter, Filter Sensitivity, Edge Margin, Fyziologické
                // konstanty

                // ==================== SENSOR FUSION ENGINE UI ====================
                val switchTrackingAbsolute =
                        findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
                                R.id.switch_irismotion_tracking_absolute
                        )
                switchTrackingAbsolute.isChecked = settingsManager.irisMotionTrackingAbsolute

                val sliderNeuralWeight =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_neural_weight
                        )
                val tvNeuralWeightValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_neural_weight_value
                        )
                sliderNeuralWeight.value =
                        settingsManager.irisMotionNeuralWeight.alignToSlider(sliderNeuralWeight)
                val nwVal = settingsManager.irisMotionNeuralWeight
                val ikPct = ((1f - nwVal) * 100).toInt()
                val neuralPct = (nwVal * 100).toInt()
                tvNeuralWeightValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.2f (%d%% IK + %d%% Neural)",
                                nwVal,
                                ikPct,
                                neuralPct
                        )

                sliderNeuralWeight.addOnChangeListener { _, value, _ ->
                        val ik = ((1f - value) * 100).toInt()
                        val neural = (value * 100).toInt()
                        tvNeuralWeightValue.text =
                                String.format(
                                        java.util.Locale.US,
                                        "%.2f (%d%% IK + %d%% Neural)",
                                        value,
                                        ik,
                                        neural
                                )
                }

                val sliderSaccadeVelocity =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_saccade_velocity
                        )
                val tvSaccadeVelocityValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_saccade_velocity_value
                        )
                sliderSaccadeVelocity.value =
                        settingsManager.irisMotionSaccadeVelocity.alignToSlider(
                                sliderSaccadeVelocity
                        )
                tvSaccadeVelocityValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.1f",
                                settingsManager.irisMotionSaccadeVelocity
                        )

                val sliderSaccadeBox =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_saccade_box
                        )
                val tvSaccadeBoxValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_saccade_box_value)
                sliderSaccadeBox.value =
                        settingsManager.irisMotionSaccadeBox.alignToSlider(sliderSaccadeBox)
                tvSaccadeBoxValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.3f",
                                settingsManager.irisMotionSaccadeBox
                        )

                val sliderFixationDrift =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_fixation_drift
                        )
                val tvFixationDriftValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_fixation_drift_value
                        )
                sliderFixationDrift.value =
                        settingsManager.irisMotionFixationDrift.alignToSlider(sliderFixationDrift)
                tvFixationDriftValue.text =
                        String.format(
                                java.util.Locale.US,
                                "%.2f",
                                settingsManager.irisMotionFixationDrift
                        )

                val sliderFilterSensitivity =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_sensitivity
                        )
                val tvFilterSensitivityValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_filter_sensitivity_value
                        )
                sliderFilterSensitivity.value =
                        settingsManager.irisMotionFilterSensitivity.alignToSlider(
                                sliderFilterSensitivity
                        )
                tvFilterSensitivityValue.text =
                        String.format("%.1f×", settingsManager.irisMotionFilterSensitivity)

                val sliderEdgeMargin =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_edge_margin
                        )
                val tvEdgeMarginValue =
                        findViewById<android.widget.TextView>(R.id.tv_irismotion_edge_margin_value)
                sliderEdgeMargin.value =
                        settingsManager.irisMotionEdgeMargin.alignToSlider(sliderEdgeMargin)
                tvEdgeMarginValue.text =
                        String.format("%.0f%%", settingsManager.irisMotionEdgeMargin * 100f)

                val sliderPhysioRestX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_physio_rest_x
                        )
                val tvPhysioRestXValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_physio_rest_x_value
                        )
                sliderPhysioRestX.value =
                        settingsManager.irisMotionPhysioRestX.alignToSlider(sliderPhysioRestX)
                tvPhysioRestXValue.text =
                        String.format("%.2f", settingsManager.irisMotionPhysioRestX)

                val sliderPhysioRestY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_physio_rest_y
                        )
                val tvPhysioRestYValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_physio_rest_y_value
                        )
                sliderPhysioRestY.value =
                        settingsManager.irisMotionPhysioRestY.alignToSlider(sliderPhysioRestY)
                tvPhysioRestYValue.text =
                        String.format("%.2f", settingsManager.irisMotionPhysioRestY)

                val sliderIntrinsicAmpX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_intrinsic_amp_x
                        )
                val tvIntrinsicAmpXValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_intrinsic_amp_x_value
                        )
                sliderIntrinsicAmpX.value =
                        settingsManager.irisMotionIntrinsicAmpX.alignToSlider(sliderIntrinsicAmpX)
                tvIntrinsicAmpXValue.text =
                        String.format("%.1f×", settingsManager.irisMotionIntrinsicAmpX)

                val sliderIntrinsicAmpY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_intrinsic_amp_y
                        )
                val tvIntrinsicAmpYValue =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_intrinsic_amp_y_value
                        )
                sliderIntrinsicAmpY.value =
                        settingsManager.irisMotionIntrinsicAmpY.alignToSlider(sliderIntrinsicAmpY)
                tvIntrinsicAmpYValue.text =
                        String.format("%.1f×", settingsManager.irisMotionIntrinsicAmpY)

                etIrisMotionCursorSize.setText(settingsManager.irisMotionCursorSize.toString())
                spinnerIrisMotionTrackedEye.setSelection(settingsManager.irisMotionTrackedEye)
                // DoF spinner: 2→index 0, 4→index 1, 6→index 2
                spinnerIrisMotionHeadTrackingDoF.setSelection(
                        when (settingsManager.irisMotionHeadTrackingDoF) {
                                4 -> 1
                                6 -> 2
                                else -> 0
                        }
                )

                // Zoom spinner: 1.0x→0, 1.5x→1, 2.0x→2, 3.0x→3
                spinnerIrisMotionCameraZoom.setSelection(
                        when (settingsManager.irisMotionCameraZoom) {
                                1.0f -> 0
                                1.5f -> 1
                                2.0f -> 2
                                3.0f -> 3
                                4.0f -> 4
                                else -> 0
                        }
                )

                // Head Compensation Gain slider load
                val sliderHeadCompGainLoad =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_head_comp_gain
                        )
                val tvHeadCompGainLoad =
                        findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_head_comp_gain_value
                        )
                sliderHeadCompGainLoad.value =
                        settingsManager.irisMotionHeadCompensationGain.alignToSlider(
                                sliderHeadCompGainLoad
                        )
                tvHeadCompGainLoad.text =
                        String.format(
                                java.util.Locale.US,
                                "%.2f",
                                settingsManager.irisMotionHeadCompensationGain
                        )
                // Visibility závisí na aktuálním DoF (0=2DoF→gone, 1+=visible)
                val layoutHeadCompLoad =
                        findViewById<android.widget.LinearLayout>(R.id.layout_irismotion_head_comp)
                layoutHeadCompLoad.visibility =
                        if (settingsManager.irisMotionHeadTrackingDoF > 2) android.view.View.VISIBLE
                        else android.view.View.GONE

                cbIrisMotionCameraMirror.isChecked = settingsManager.isIrisMotionCameraMirrored
                cbSphericalCompensation.isChecked = settingsManager.irisMotionSphericalCompensation
                cbTargetSnap.isChecked = settingsManager.irisMotionTargetSnap
                sliderTargetSnapRadius.value = settingsManager.irisMotionTargetSnapRadius
                tvTargetSnapRadiusValue.text =
                        "${settingsManager.irisMotionTargetSnapRadius.toInt()} px"

                // Načtení stávajících filtrů z posloupnosti
                val filters = settingsManager.irisMotionFilterSequence
                cbFilterMedian.isChecked = filters.contains("MEDIAN")
                cbFilterAlphaBeta.isChecked = filters.contains("ALPHABETA")
                cbFilterKalman.isChecked = filters.contains("KALMAN")
                cbFilterOneEuro.isChecked = filters.contains("ONEEURO")

                cbExprOneEuro.isChecked = settingsManager.irisMotionExpressionsOneEuroEnabled
                cbExprCrosstalk.isChecked = settingsManager.irisMotionExpressionsCrosstalkEnabled
                cbExprAbt.isChecked = settingsManager.irisMotionExpressionsAbtEnabled

                // Load IrisMotion Filtrační parametry
                val sBeta =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_beta
                        )
                sBeta.value = settingsManager.irisMotionFilterBeta.alignToSlider(sBeta)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_filter_beta_value).text =
                        String.format("%.1f", settingsManager.irisMotionFilterBeta)

                val sCutoff =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_cutoff
                        )
                sCutoff.value = settingsManager.irisMotionFilterCutoff.alignToSlider(sCutoff)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_filter_cutoff_value).text =
                        String.format("%.3f Hz", settingsManager.irisMotionFilterCutoff)

                val sDCutoff =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_filter_d_cutoff
                        )
                sDCutoff.value = settingsManager.irisMotionFilterDCutoff.alignToSlider(sDCutoff)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_filter_d_cutoff_value)
                        .text = String.format("%.3f", settingsManager.irisMotionFilterDCutoff)

                val sKalmanQ =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_kalman_q
                        )
                sKalmanQ.value =
                        (settingsManager.irisMotionKalmanQNoise * 100000f).alignToSlider(sKalmanQ)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_kalman_q_value).text =
                        String.format("%.5f", settingsManager.irisMotionKalmanQNoise)

                val sKalmanR =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_kalman_r
                        )
                sKalmanR.value = settingsManager.irisMotionKalmanRNoise.alignToSlider(sKalmanR)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_kalman_r_value).text =
                        String.format("%.2f", settingsManager.irisMotionKalmanRNoise)

                val sAmpX =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_x
                        )
                sAmpX.value = settingsManager.irisMotionGazeAmpX.alignToSlider(sAmpX)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_gaze_amp_x_value).text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpX)

                val sAmpY =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_y
                        )
                sAmpY.value = settingsManager.irisMotionGazeAmpY.alignToSlider(sAmpY)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_gaze_amp_y_value).text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpY)

                val sCenterXOffset =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_center_x_offset
                        )
                sCenterXOffset.value =
                        settingsManager.irisMotionCenterXOffset.alignToSlider(sCenterXOffset)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_center_x_offset_value)
                        .text = String.format("%.2f", settingsManager.irisMotionCenterXOffset)

                val sCenterYOffset =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_center_y_offset
                        )
                sCenterYOffset.value =
                        settingsManager.irisMotionCenterYOffset.alignToSlider(sCenterYOffset)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_center_y_offset_value)
                        .text = String.format("%.2f", settingsManager.irisMotionCenterYOffset)

                // Asymetrické per-eye slidery
                val sLEL =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_left_eye_left
                        )
                sLEL.value = settingsManager.irisMotionGazeAmpLeftEyeLeft.alignToSlider(sLEL)
                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_left_value
                        )
                        .text = String.format("%.1f×", settingsManager.irisMotionGazeAmpLeftEyeLeft)

                val sLER =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_left_eye_right
                        )
                sLER.value = settingsManager.irisMotionGazeAmpLeftEyeRight.alignToSlider(sLER)
                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_right_value
                        )
                        .text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpLeftEyeRight)

                val sREL =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_right_eye_left
                        )
                val sRER =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_gaze_amp_right_eye_right
                        )

                sLEL.value = settingsManager.irisMotionGazeAmpLeftEyeLeft.alignToSlider(sLEL)
                sLER.value = settingsManager.irisMotionGazeAmpLeftEyeRight.alignToSlider(sLER)
                sREL.value = settingsManager.irisMotionGazeAmpRightEyeLeft.alignToSlider(sREL)
                sRER.value = settingsManager.irisMotionGazeAmpRightEyeRight.alignToSlider(sRER)

                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_left_value
                        )
                        .text = String.format("%.1f×", settingsManager.irisMotionGazeAmpLeftEyeLeft)
                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_left_eye_right_value
                        )
                        .text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpLeftEyeRight)
                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_right_eye_left_value
                        )
                        .text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpRightEyeLeft)
                findViewById<android.widget.TextView>(
                                R.id.tv_irismotion_gaze_amp_right_eye_right_value
                        )
                        .text =
                        String.format("%.1f×", settingsManager.irisMotionGazeAmpRightEyeRight)

                val sDeadzone =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_deadzone
                        )
                sDeadzone.value = settingsManager.irisMotionDeadzonePx.alignToSlider(sDeadzone)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_deadzone_value).text =
                        "${settingsManager.irisMotionDeadzonePx.toInt()} px"

                val swAutoCenter =
                        findViewById<com.google.android.material.materialswitch.MaterialSwitch>(
                                R.id.switch_irismotion_auto_center
                        )
                swAutoCenter.isChecked = settingsManager.irisMotionAutoCenter
                val sAutoCenterSpeed =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_auto_center_speed
                        )
                sAutoCenterSpeed.value =
                        settingsManager.irisMotionAutoCenterSpeed.alignToSlider(sAutoCenterSpeed)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_auto_center_speed_value)
                        .text = "${settingsManager.irisMotionAutoCenterSpeed} (rychlost posunu)"

                val sAutoCenterDelayMs =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_auto_center_delay_ms
                        )
                sAutoCenterDelayMs.value =
                        settingsManager
                                .irisMotionAutoCenterDelayMs
                                .toFloat()
                                .alignToSlider(sAutoCenterDelayMs)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_auto_center_delay_ms_value)
                        .text = "${settingsManager.irisMotionAutoCenterDelayMs} ms"

                val sMedian =
                        findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_median
                        )
                val medianVal = settingsManager.irisMotionMedianWindow.coerceIn(1, 9)
                // Zaokrouhli na nejbližší liché (stepSize=2)
                val medianSliderVal =
                        if (medianVal % 2 == 0) (medianVal - 1).coerceAtLeast(1) else medianVal
                sMedian.value = medianSliderVal.toFloat().alignToSlider(sMedian)
                findViewById<android.widget.TextView>(R.id.tv_irismotion_median_value).text =
                        if (medianSliderVal <= 1) "VYP" else "$medianSliderVal framů"

                // --- Load IrisMotion Eye Gestures Interakce ---
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_left_timeout
                        )
                        .setText(settingsManager.irisMotionLeftBlinkTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_right_timeout
                        )
                        .setText(settingsManager.irisMotionRightBlinkTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_both_timeout
                        )
                        .setText(settingsManager.irisMotionBothBlinkTimeout.toString())

                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_smile_timeout
                        )
                        .setText(settingsManager.irisMotionSmileTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_frown_timeout
                        )
                        .setText(settingsManager.irisMotionFrownTimeout.toString())
                findViewById<com.google.android.material.textfield.TextInputEditText>(
                                R.id.et_irismotion_raise_eyebrows_timeout
                        )
                        .setText(settingsManager.irisMotionRaiseEyebrowsTimeout.toString())

                findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_smile_threshold
                        )
                        .value = (settingsManager.irisMotionSmileThreshold * 100f).coerceIn(5f, 100f)

                findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_frown_threshold
                        )
                        .value = (settingsManager.irisMotionFrownThreshold * 100f).coerceIn(5f, 100f)

                findViewById<com.google.android.material.slider.Slider>(
                                R.id.slider_irismotion_raise_eyebrows_threshold
                        )
                        .value = (settingsManager.irisMotionRaiseEyebrowsThreshold * 100f).coerceIn(5f, 100f)

                val actionsArray =
                        arrayOf(
                                "Žádná Akce",
                                "Left Click",
                                "Back",
                                "Home",
                                "Flashlight",
                                "Notifications",
                                "Quick Settings",
                                "Launch App",
                                "Swipe Up",
                                "Swipe Down",
                                "Double Click"
                        )
                val actionAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_item,
                                actionsArray
                        )
                actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

                val spinnerLeft = findViewById<Spinner>(R.id.spinner_irismotion_left_action)
                val spinnerRight = findViewById<Spinner>(R.id.spinner_irismotion_right_action)
                val spinnerBoth = findViewById<Spinner>(R.id.spinner_irismotion_both_action)
                val spinnerSmile = findViewById<Spinner>(R.id.spinner_irismotion_smile_action)
                val spinnerFrown = findViewById<Spinner>(R.id.spinner_irismotion_frown_action)
                val spinnerBrowUp = findViewById<Spinner>(R.id.spinner_irismotion_raise_eyebrows_action)

                spinnerLeft.adapter = actionAdapter
                spinnerRight.adapter = actionAdapter
                spinnerBoth.adapter = actionAdapter
                spinnerSmile.adapter = actionAdapter
                spinnerFrown.adapter = actionAdapter
                spinnerBrowUp.adapter = actionAdapter

                spinnerLeft.setSelection(settingsManager.irisMotionLeftBlinkAction)
                spinnerRight.setSelection(settingsManager.irisMotionRightBlinkAction)
                spinnerBoth.setSelection(settingsManager.irisMotionBothBlinkAction)
                spinnerSmile.setSelection(settingsManager.irisMotionSmileAction)
                spinnerFrown.setSelection(settingsManager.irisMotionFrownAction)
                spinnerBrowUp.setSelection(settingsManager.irisMotionRaiseEyebrowsAction)

                val tilAppPicker =
                        findViewById<com.google.android.material.textfield.TextInputLayout>(
                                R.id.til_irismotion_app_picker
                        )
                val actvAppPicker =
                        findViewById<AutoCompleteTextView>(R.id.actv_irismotion_app_picker)

                actvAppPicker.setText(settingsManager.irisMotionBothBlinkApp, false)

                // Visibility logic for App Picker AutoCompleteTextView based on selection
                val onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>,
                                        view: android.view.View?,
                                        position: Int,
                                        id: Long
                                ) {
                                        if (spinnerLeft.selectedItemPosition ==
                                                        SettingsManager.GESTURE_ACTION_LAUNCH_APP ||
                                                        spinnerRight.selectedItemPosition ==
                                                                SettingsManager
                                                                        .GESTURE_ACTION_LAUNCH_APP ||
                                                        spinnerBoth.selectedItemPosition ==
                                                                SettingsManager
                                                                        .GESTURE_ACTION_LAUNCH_APP
                                        ) {
                                                tilAppPicker.visibility = android.view.View.VISIBLE
                                        } else {
                                                tilAppPicker.visibility = android.view.View.GONE
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>
                                ) {}
                        }

                spinnerLeft.onItemSelectedListener = onItemSelectedListener
                spinnerRight.onItemSelectedListener = onItemSelectedListener
                spinnerBoth.onItemSelectedListener = onItemSelectedListener

                // Trigger visibility check on load
                onItemSelectedListener.onItemSelected(
                        spinnerBoth,
                        null,
                        spinnerBoth.selectedItemPosition,
                        0L
                )

                // Load Installed Apps to AutoComplete
                com.example.llama.agent.core.InstalledAppsRegistry.loadSnapshot(this)
                val apps = com.example.llama.agent.core.InstalledAppsRegistry.getAll()
                val appNames = apps.map { it.label }.toTypedArray()
                val actvAdapter =
                        android.widget.ArrayAdapter(
                                this,
                                android.R.layout.simple_dropdown_item_1line,
                                appNames
                        )
                actvAppPicker.setAdapter(actvAdapter)

                // === Load NLP Tier 2.5 Settings ===

                // Whisper STT Advanced Load
                sliderWhisperHistorySec.value = settingsManager.whisperHistorySec.toFloat().coerceIn(sliderWhisperHistorySec.valueFrom, sliderWhisperHistorySec.valueTo)
                tvWhisperHistorySecValue.text = "${settingsManager.whisperHistorySec} ms"
                sliderWhisperProcessingIntervalMs.value =
                        settingsManager.whisperProcessingIntervalMs.toFloat()
                tvWhisperProcessingIntervalValue.text =
                        "${settingsManager.whisperProcessingIntervalMs} ms"
                sliderWhisperNThreads.value = settingsManager.whisperNThreads.toFloat()
                tvWhisperNthreadsValue.text = "${settingsManager.whisperNThreads}"
                sliderWhisperSoftwareGain.value = settingsManager.whisperSoftwareGain
                tvWhisperSoftwareGainValue.text = "${settingsManager.whisperSoftwareGain}x"
                when (settingsManager.whisperAcceleratorMode) {
                    "CPU" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_cpu)
                    "GPU" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_gpu)
                    "NNAPI" -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_nnapi)
                    else -> chipgroupSettingsWhisperAccel.check(R.id.chip_settings_whisper_cpu)
                }

                // Sherpa STT Advanced Load
                sliderSherpaHistorySec.value = settingsManager.sherpaHistorySec.toFloat().coerceIn(sliderSherpaHistorySec.valueFrom, sliderSherpaHistorySec.valueTo)
                tvSherpaHistorySecValue.text = "${settingsManager.sherpaHistorySec} ms"
                sliderSherpaProcessingIntervalMs.value =
                        settingsManager.sherpaProcessingIntervalMs.toFloat()
                tvSherpaProcessingIntervalValue.text =
                        "${settingsManager.sherpaProcessingIntervalMs} ms"
                sliderSherpaNThreads.value = settingsManager.sherpaNThreads.toFloat()
                tvSherpaNthreadsValue.text = "${settingsManager.sherpaNThreads}"
                sliderSherpaSoftwareGain.value = settingsManager.sherpaSoftwareGain
                tvSherpaSoftwareGainValue.text = "${settingsManager.sherpaSoftwareGain}x"
                when (settingsManager.sherpaAcceleratorMode) {
                    "CPU" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_cpu)
                    "GPU" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_gpu)
                    "NNAPI" -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_nnapi)
                    else -> chipgroupSettingsSherpaAccel.check(R.id.chip_settings_sherpa_cpu)
                }

                // Sherpa Language Load
                val sLangB = settingsManager.sherpaLanguage
                val sLangValsB =
                        listOf("auto", "en", "cs", "de", "fr", "es", "it", "pl", "sk", "uk", "ru")
                val sLangIndexB = sLangValsB.indexOf(sLangB).takeIf { it >= 0 } ?: 0
                spinnerSherpaLanguage.setSelection(sLangIndexB)

                actvSummarizationBackend.setText(settingsManager.summarizationBackend, false)
                cbNlpTranslateEnabled.isChecked = settingsManager.translateEnabled
                cbNlpTranslateAutoDownload.isChecked = settingsManager.translateAutoDownload
                cbNlpSentimentEnabled.isChecked = settingsManager.sentimentAnalysisEnabled
                cbNlpSmartReplyEnabled.isChecked = settingsManager.smartReplyEnabled
                cbNlpGenaiProofread.isChecked = settingsManager.genaiProofreadEnabled
                cbNlpGenaiRewrite.isChecked = settingsManager.genaiRewriteEnabled

                // Jazyky překladu — načteme aktuální hodnoty ze SettingsManager
                if (::actvNlpMyLang.isInitialized) {
                    val availLangs = com.example.llama.agent.perception.NlpTextProcessor.getAvailableLanguages()
                    actvNlpMyLang.setText(availLangs[settingsManager.translateMyLang] ?: settingsManager.translateMyLang, false)
                    actvNlpForeignLang.setText(availLangs[settingsManager.translateForeignLang] ?: settingsManager.translateForeignLang, false)
                }

                // NLP Tuner load (Weight to Progress: p = (w + 1.0) * 10)
                sbNlpWeightTasks.progress = ((settingsManager.nlpWeightTasks + 1.0) * 10).toInt()
                sbNlpWeightSlang.progress = ((settingsManager.nlpWeightSlang + 1.0) * 10).toInt()
                sbNlpWeightTech.progress = ((settingsManager.nlpWeightTech + 1.0) * 10).toInt()
                sbNlpWeightUrgency.progress =
                        ((settingsManager.nlpWeightUrgency + 1.0) * 10).toInt()

                // Refresh text values
                tvNlpWeightTasksVal.text = String.format("%.1f", settingsManager.nlpWeightTasks)
                tvNlpWeightSlangVal.text = String.format("%.1f", settingsManager.nlpWeightSlang)
                tvNlpWeightTechVal.text = String.format("%.1f", settingsManager.nlpWeightTech)
                tvNlpWeightUrgencyVal.text = String.format("%.1f", settingsManager.nlpWeightUrgency)
                // Refresh BERT models list
                refreshBertModels()
        }

        private fun saveSettings() {
                // ... (Existing Save Logic) ...

                // Save Agent Timeouts
                val agentTimeoutStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_timeout
                                )
                                .text
                                .toString()
                settingsManager.agentTimeoutMs =
                        agentTimeoutStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_AGENT_TIMEOUT_MS

                val llmCancelTimeoutStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_llm_cancel_timeout
                                )
                                .text
                                .toString()
                settingsManager.agentLlmCancelTimeout =
                        llmCancelTimeoutStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_LLM_CANCEL_TIMEOUT

                val smartClickTimeoutStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_smartclick_timeout
                                )
                                .text
                                .toString()
                settingsManager.agentSmartClickTimeout =
                        smartClickTimeoutStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_SMARTCLICK_TIMEOUT

                val appLoadWaitStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_app_load_wait
                                )
                                .text
                                .toString()
                settingsManager.agentAppLoadWait =
                        appLoadWaitStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_APP_LOAD_WAIT

                val gestureTimeoutStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_gesture_timeout
                                )
                                .text
                                .toString()
                settingsManager.agentGestureTimeout =
                        gestureTimeoutStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_GESTURE_TIMEOUT

                val toggleTimeoutStr =
                        findViewById<com.google.android.material.textfield.TextInputEditText>(
                                        R.id.et_agent_toggle_timeout
                                )
                                .text
                                .toString()
                settingsManager.agentToggleTimeout =
                        toggleTimeoutStr.toLongOrNull()
                                ?: com.example.llama.SettingsManager.DEFAULT_TOGGLE_TIMEOUT

                settingsManager.isInvertSwipeVertical = cbInvertSwipeVertical.isChecked
                settingsManager.isInvertSwipeHorizontal = cbInvertSwipeHorizontal.isChecked
                try {
                        settingsManager.systemMessage = etSystemMessage.text.toString()
                        settingsManager.showSystemMessage = cbShowSystem.isChecked
                        settingsManager.maxImportSize =
                                etMaxImportSize.text.toString().toLongOrNull()
                                        ?: settingsManager.maxImportSize

                        settingsManager.contextSize =
                                etContextSize.text.toString().toIntOrNull()
                                        ?: settingsManager.contextSize
                        settingsManager.flashAttention = cbFlashAttn.isChecked
                        settingsManager.useMmap = cbUseMmap.isChecked
                        settingsManager.strictBackend = cbStrictBackend.isChecked
                        settingsManager.gpuLayers = etGpuLayers.text.toString().toIntOrNull() ?: settingsManager.gpuLayers
                        val backendName = actvLlamaBackend.text.toString()
                        val matchedBackend = SettingsManager.LlamaBackend.entries.find { it.displayName == backendName }
                        if (matchedBackend != null) {
                            settingsManager.llamaBackend = matchedBackend
                        }

                        settingsManager.webExtractTokenLimit = sliderWebExtractTokenLimit.value.toInt()
                        val parsedMaxResults = actvWebSearchMaxResults.text.toString().substringBefore(" ").toIntOrNull()
                        if (parsedMaxResults != null) {
                            settingsManager.webSearchMaxResults = parsedMaxResults
                        }

                        settingsManager.webDataSaverEnabled = cbWebDataSaver.isChecked
                        settingsManager.webThrottleBandwidthEnabled = cbWebThrottleBandwidth.isChecked
                        settingsManager.webEnableDuckDuckGo = cbWebEnableDuckDuckGo.isChecked
                        settingsManager.webEnableWikipedia = cbWebEnableWikipedia.isChecked
                        settingsManager.webEnableScienceProviders = cbWebEnableScience.isChecked
                        settingsManager.webEnableImportedDatabase = cbWebEnableImportedDb.isChecked
                        settingsManager.webEnableSqliteFts5 = cbWebEnableSqliteFts5.isChecked
                        settingsManager.webPreferOfflineCache = cbWebPreferOfflineCache.isChecked
                        settingsManager.webEnableHeadlessWebView = cbWebEnableHeadlessWebView.isChecked
                        settingsManager.webReaderDomDistiller = cbWebReaderDomDistiller.isChecked
                        settingsManager.webExtractJsonLd = cbWebExtractJsonLd.isChecked
                        settingsManager.webSmartReranking = cbWebSmartReranking.isChecked
                        settingsManager.webUserAgentRotation = cbWebUserAgentRotation.isChecked
                        settingsManager.webDisableSecurityForDev = cbWebDevBypassSecurity.isChecked

                        val kvSelectedName = actvKvCache.text.toString()
                        val kvNames = resources.getStringArray(R.array.kv_cache_names)
                        val kvValues = resources.getStringArray(R.array.kv_cache_values)
                        val kvIndex = kvNames.indexOf(kvSelectedName)

                        if (kvIndex >= 0 && kvIndex < kvValues.size) {
                                settingsManager.kvCacheMode = kvValues[kvIndex].toInt()
                        }

                        settingsManager.batchSize =
                                actvBatchSize.text.toString().toIntOrNull()
                                        ?: settingsManager.batchSize

                        settingsManager.ubatchSize =
                                actvUBatchSize.text.toString().toIntOrNull()
                                        ?: settingsManager.ubatchSize

                        settingsManager.showStats = cbShowStats.isChecked
                        settingsManager.showThoughtInProgress = cbShowThoughts.isChecked
                        settingsManager.keepStatsVisible = cbKeepStats.isChecked
                        settingsManager.isMediaPreviewEnabled = cbMediaPreview.isChecked
                        settingsManager.renderMarkdown = cbRenderMarkdown.isChecked
                        settingsManager.disableAutoScroll = cbDisableScroll.isChecked
                        settingsManager.alwaysShowSidebar = cbAlwaysSidebar.isChecked
                        settingsManager.autoShowSidebar = cbAutoSidebar.isChecked
                        settingsManager.enableBubbleCopy = cbBubbleCopy.isChecked
                        settingsManager.backgroundGeneration = cbBackgroundGen.isChecked
                        settingsManager.hideStatusBar = cbHideStatusBar.isChecked
                        settingsManager.showTokenCount = cbShowTokenCount.isChecked
                        settingsManager.showGenTime = cbShowGenTime.isChecked

                        val chatStyleIndex = spinnerChatStyle.selectedItemPosition
                        val chatStyleValue =
                                when (chatStyleIndex) {
                                        0 -> "oem"
                                        1 -> "terminal90s"
                                        2 -> "modern_terminal"
                                        else -> "oem"
                                }
                        settingsManager.chatStyle = chatStyleValue

                        settingsManager.temperature =
                                etTemp.text.toString().toFloatOrNull()
                                        ?: settingsManager.temperature
                        settingsManager.dynTempRange =
                                etDynTempRange.text.toString().toFloatOrNull()
                                        ?: settingsManager.dynTempRange
                        settingsManager.dynTempExp =
                                etDynTempExp.text.toString().toFloatOrNull()
                                        ?: settingsManager.dynTempExp
                        settingsManager.topK =
                                etTopK.text.toString().toIntOrNull() ?: settingsManager.topK
                        settingsManager.topP =
                                etTopP.text.toString().toFloatOrNull() ?: settingsManager.topP
                        settingsManager.minP =
                                etMinP.text.toString().toFloatOrNull() ?: settingsManager.minP
                        settingsManager.xtcProb =
                                etXtcProb.text.toString().toFloatOrNull() ?: settingsManager.xtcProb
                        settingsManager.xtcThreshold =
                                etXtcThresh.text.toString().toFloatOrNull()
                                        ?: settingsManager.xtcThreshold
                        settingsManager.typicalP =
                                etTypicalP.text.toString().toFloatOrNull()
                                        ?: settingsManager.typicalP

                        // Save Penalty values
                        settingsManager.penaltyRepeat =
                                etPenaltyRepeat.text.toString().toFloatOrNull()
                                        ?: settingsManager.penaltyRepeat
                        settingsManager.penaltyFreq =
                                etPenaltyFreq.text.toString().toFloatOrNull()
                                        ?: settingsManager.penaltyFreq
                        settingsManager.penaltyPresent =
                                etPenaltyPresent.text.toString().toFloatOrNull()
                                        ?: settingsManager.penaltyPresent
                        settingsManager.penaltyLastN =
                                etPenaltyLastN.text.toString().toIntOrNull()
                                        ?: settingsManager.penaltyLastN

                        // Save Mirostat values
                        val mirostatPos = spinnerMirostatMode.selectedItemPosition
                        settingsManager.mirostatMode = mirostatPos.coerceIn(0, 2)

                        settingsManager.mirostatTau =
                                etMirostatTau.text.toString().toFloatOrNull()
                                        ?: settingsManager.mirostatTau
                        settingsManager.mirostatEta =
                                etMirostatEta.text.toString().toFloatOrNull()
                                        ?: settingsManager.mirostatEta

                        settingsManager.maxTokens =
                                etMaxTokens.text.toString().toIntOrNull()
                                        ?: SamplingConstants.DEFAULT_MAX_TOKENS
                        settingsManager.samplersSequence = etSamplers.text.toString()
                        settingsManager.backendSampling = cbBackendSampling.isChecked

                        settingsManager.isSttEnabled = cbAgentStt.isChecked
                        settingsManager.isTtsEnabled = cbEnableTts.isChecked

                        // Save ZipVoice Settings
                        settingsManager.sherpaTtsZipvoiceThreads = spinnerSherpaZipvoiceThreads.selectedItemPosition + 1
                        settingsManager.sherpaTtsZipvoicePriority = spinnerSherpaZipvoicePriority.selectedItemPosition
                        val sampleRates = listOf(8000, 16000, 22050, 24000)
                        val selectedRateIdx = spinnerSherpaZipvoiceSampleRate.selectedItemPosition
                        if (selectedRateIdx in sampleRates.indices) {
                            settingsManager.sherpaTtsZipvoiceSampleRate = sampleRates[selectedRateIdx]
                        }
                        settingsManager.sherpaTtsCustomPromptText =
                                etSherpaZipvoicePromptText.text.toString()
                        
                        settingsManager.isAgentEnabled = cbAgentEnabled.isChecked
                        settingsManager.isAgentBackgroundEnabled = cbAgentBackground.isChecked
                        
                        val selectedModeStr = spinnerAgentScreenParserMode.selectedItem?.toString() ?: "AUTO"
                        settingsManager.screenParserMode = SettingsManager.ScreenParserMode.valueOf(selectedModeStr)
                        
                        settingsManager.agentUseLlmFallback = cbAgentLlmFallback.isChecked

                        // Auto-enable Mic if Agent or STT is enabled
                        settingsManager.showMicrophone =
                                cbAgentEnabled.isChecked || cbAgentStt.isChecked

                        // Save Prism markdown plugin settings
                        savePrismSettings()

                        // Save Agent settings
                        settingsManager.isAgentEnabled = cbAgentEnabled.isChecked
                        settingsManager.isAgentBackgroundEnabled = cbAgentBackground.isChecked
                        settingsManager.agentUseLlmFallback = cbAgentLlmFallback.isChecked
                        settingsManager.isAgentTtsEnabled = cbAgentTts.isChecked
                        settingsManager.isAgentSttEnabled = cbAgentStt.isChecked
                        settingsManager.agentVoiceFeedback = cbAgentVoiceFeedback.isChecked
                        settingsManager.isWakeWordEnabled = cbAgentWakeWord.isChecked
                        settingsManager.wakeWord = etAgentWakeWord.text.toString()

                // Save NLP Tier 2.5 Settings
                settingsManager.summarizationBackend =
                        actvSummarizationBackend.text.toString()
                settingsManager.translateEnabled = cbNlpTranslateEnabled.isChecked
                settingsManager.translateAutoDownload = cbNlpTranslateAutoDownload.isChecked
                settingsManager.sentimentAnalysisEnabled = cbNlpSentimentEnabled.isChecked
                settingsManager.smartReplyEnabled = cbNlpSmartReplyEnabled.isChecked
                settingsManager.genaiProofreadEnabled = cbNlpGenaiProofread.isChecked
                settingsManager.genaiRewriteEnabled = cbNlpGenaiRewrite.isChecked
                // Jazyky překladu se ukládají okamžitě při výběru (setOnItemClickListener)
                // Zde jen ověříme inicializaci pro případ prvního spuštění bez využítí pickeru
                if (!::actvNlpMyLang.isInitialized) {
                    // Pickery nebyly inicializovány (např. tab nebyl zobrazen) — hodnoty zachováme
                    Log.d("SettingsActivity", "actvNlpMyLang not initialized — skip translate lang save")
                }

                        // Save NLP Linguistic Tuner Settings
                        settingsManager.nlpWeightTasks = sbNlpWeightTasks.progress / 10f
                        settingsManager.nlpWeightSlang = sbNlpWeightSlang.progress / 10f
                        settingsManager.nlpWeightTech = sbNlpWeightTech.progress / 10f
                        settingsManager.nlpWeightUrgency = sbNlpWeightUrgency.progress / 10f

                        // Save Smart Vision Settings
                        settingsManager.isSmartVisionBoundingBoxEnabled =
                                cbSmartVisionBoundingBoxes.isChecked
                        settingsManager.isSmartVisionCameraLiveEnabled =
                                cbSmartVisionCameraLive.isChecked

                        settingsManager.smartVisionOmniParserEnabled =
                                cbSmartVisionOmniparser.isChecked
                        settingsManager.smartVisionFilterEmptyImageView =
                                cbSmartVisionFilterEmptyImageView.isChecked
                        settingsManager.smartVisionFilterNonInteractive =
                                cbSmartVisionFilterNonInteractive.isChecked
                        settingsManager.smartVisionFilterScrollable =
                                cbSmartVisionFilterScrollable.isChecked
                        settingsManager.smartVisionFilterWebView =
                                cbSmartVisionFilterWebView.isChecked
                        settingsManager.smartVisionFilterEmptyEditText =
                                cbSmartVisionFilterEmptyEditText.isChecked
                        settingsManager.smartVisionFilterOffscreen =
                                cbSmartVisionFilterOffscreen.isChecked
                        settingsManager.smartVisionFilterContainers =
                                cbSmartVisionFilterContainers.isChecked
                        settingsManager.smartVisionMaxDepth =
                                sliderSmartVisionMaxDepth.value.toInt()
                        settingsManager.smartVisionTargetResolution =
                                sliderSmartVisionTargetResolution.value.toInt()

                        settingsManager.smartVisionLiveFps = sliderSmartVisionFps.value.toInt()
                        settingsManager.isNativeVisionModel = cbSmartVisionNativeModel.isChecked

                        val selectedVisionName = actvSmartVisionModel.text.toString()
                        val selectedVisionId = visionModelsMap[selectedVisionName] ?: ""
                        settingsManager.activeVisionModelId = selectedVisionId

                        // Save Transcript Engine
                        settingsManager.transcriptEngine =
                                when (spinnerTranscriptEngine.selectedItemPosition) {
                                        1 -> "whisper"
                                        2 -> "sherpa"
                                        else -> "android"
                                }

                        // Save Gestures
                        settingsManager.agentScrollDistance = sliderAgentScroll.value
                        settingsManager.agentZoomFactor = sliderAgentZoom.value
                        settingsManager.agentTapInterval =
                                etAgentTapInterval.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_TAP_INTERVAL
                        settingsManager.agentTapCount =
                                etAgentTapCount.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_TAP_COUNT

                        // Save Cache TTL
                        val newTtl =
                                etCacheTtl.text.toString().toLongOrNull()
                                        ?: settingsManager.appLayoutCacheTtl
                        settingsManager.appLayoutCacheTtl = newTtl
                        // Update singleton immediately (iff in same process)
                        com.example.llama.agent.core.AppLayoutCache.cacheTtlMs = newTtl

                        // Save Agent Timeout (input v sekundách → ukládá se v ms)
                        val newTimeoutSec =
                                etAgentTimeout.text.toString().toLongOrNull()
                                        ?: (settingsManager.agentTimeoutMs / 1000)
                        settingsManager.agentTimeoutMs = newTimeoutSec * 1000

                        // Save Voice Mode - EXPLICIT LOGIC FIX
                        val selectedVoiceMode =
                                if (rbModeOpenMic.isChecked) {
                                        SettingsManager.VOICEMODE_OPEN_MIC
                                } else {
                                        // Default to PTT if Open Mic is not checked (covers PTT
                                        // checked or neither)
                                        SettingsManager.VOICEMODE_PUSH_TO_TALK
                                }
                        settingsManager.agentVoiceMode = selectedVoiceMode
                        Log.d("SettingsActivity", "Saved Voice Mode: $selectedVoiceMode")

                        if (availableSpeechEngines.isNotEmpty()) {
                                val idx = spinnerAgentSpeechEngine.selectedItemPosition
                                if (idx >= 0 && idx < availableSpeechEngines.size) {
                                        settingsManager.agentSpeechEngine =
                                                availableSpeechEngines[idx].second
                                }
                        }

                        // Save TTS Engine
                        if (availableTtsEngines.isNotEmpty()) {
                                val idx = spinnerAgentTtsEngine.selectedItemPosition
                                if (idx >= 0 && idx < availableTtsEngines.size) {
                                        settingsManager.agentTtsEngine =
                                                availableTtsEngines[idx].second
                                }
                        }

                        // Save Dual Language Settings
                        settingsManager.agentLanguageOnline = actvAgentLangOnline.text.toString()
                        settingsManager.agentLanguageOffline = actvAgentLangOffline.text.toString()
                        settingsManager.isSttForceOffline =
                                findViewById<MaterialCheckBox>(R.id.cb_agent_force_offline)
                                        .isChecked

                        // Save Memory Settings
                        settingsManager.memoryMaxApps =
                                etMemoryMaxApps.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_MEMORY_MAX_APPS
                        settingsManager.memoryMaxPatterns =
                                etMemoryMaxPatterns.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_MEMORY_MAX_PATTERNS
                        val flushSec =
                                etMemoryFlushInterval.text.toString().toLongOrNull()
                                        ?: (SettingsManager.DEFAULT_MEMORY_FLUSH_INTERVAL / 1000)
                        settingsManager.memoryFlushInterval = flushSec * 1000
                        settingsManager.memoryPatternMaxAge =
                                etMemoryPatternMaxAge.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_MEMORY_PATTERN_MAX_AGE
                        settingsManager.memoryMinConfidence =
                                etMemoryMinConfidence.text.toString().toFloatOrNull()
                                        ?: SettingsManager.DEFAULT_MEMORY_MIN_CONFIDENCE
                        settingsManager.memoryRamLimitKb =
                                etMemoryRamLimit.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_MEMORY_RAM_LIMIT_KB
                        // Apply memory settings to runtime
                        com.example.llama.agent.core.ScreenContextMemory.applySettings(
                                maxApps = settingsManager.memoryMaxApps,
                                maxPatternsPerApp = settingsManager.memoryMaxPatterns,
                                flushIntervalMs = settingsManager.memoryFlushInterval,
                                patternMaxAgeDays = settingsManager.memoryPatternMaxAge,
                                minRecallConfidence = settingsManager.memoryMinConfidence,
                                ramLimitKb = settingsManager.memoryRamLimitKb
                        )

                        // Save IrisMotion Settings — MUSÍ BÝT PŘED finish() (prefsListener v
                        // Service)
                        // Pokud by bylo za finish(), SharedPreferences commit neproběhne včas
                        // a AccessibilityService nikdy nezachytí změnu 'irismotion_enabled'.
                        settingsManager.isIrisMotionEnabled = cbIrisMotionEnabled.isChecked
                        settingsManager.isIrisMotionNeuralCoreEnabled =
                                cbIrisMotionNeuralCore.isChecked
                        cbIrisMotionDwellEnabled?.let {
                                settingsManager.isIrisMotionDwellEnabled = it.isChecked
                        }
                        settingsManager.irisMotionDwellMs = sliderIrisMotionDwell.value.toLong()
                        settingsManager.irisMotionReEngagementTimeout =
                                sliderIrisMotionReengagement.value.toLong()

                        // P8: Blink Sensitivity Save
                        settingsManager.irisMotionBlinkSensitivity =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_blink_sensitivity
                                        )
                                        .value

                        // P7: Time-Shifted Click Save
                        settingsManager.irisMotionTimeShiftEnabled =
                                findViewById<com.google.android.material.checkbox.MaterialCheckBox>(
                                                R.id.cb_irismotion_timeshift_enabled
                                        )
                                        .isChecked
                        settingsManager.irisMotionTimeShiftMs =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_timeshift
                                        )
                                        .value
                                        .toLong()

                        // P7: Intent Filter Save
                        settingsManager.irisMotionIntentFilterEnabled =
                                findViewById<com.google.android.material.checkbox.MaterialCheckBox>(
                                                R.id.cb_irismotion_intent_filter_enabled
                                        )
                                        .isChecked
                        settingsManager.irisMotionIntentFilterThresholdDeg =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_intent_filter_threshold
                                        )
                                        .value

                        settingsManager.irisMotionSensitivity = sliderIrisMotionSensitivity.value
                        settingsManager.irismotionBtSensitivity = sliderIrisMotionBtSensitivity.value
                        settingsManager.irismotionBtDeadzone = sliderIrisMotionBtDeadzone.value
                        settingsManager.irismotionBtAcceleration = sliderIrisMotionBtAcceleration.value
                        settingsManager.irismotionBtInvertX = cbIrisMotionBtInvertX.isChecked
                        settingsManager.irismotionBtInvertY = cbIrisMotionBtInvertY.isChecked
                        settingsManager.irismotionBtRemoteWidth = etIrisMotionBtRemoteWidth.text.toString().toIntOrNull() ?: 1920
                        settingsManager.irismotionBtRemoteHeight = etIrisMotionBtRemoteHeight.text.toString().toIntOrNull() ?: 1080
                        // Nové slidery save
                        // Sensor Fusion save
                        settingsManager.irisMotionTrackingAbsolute =
                                findViewById<
                                                com.google.android.material.switchmaterial.SwitchMaterial>(
                                                R.id.switch_irismotion_tracking_absolute
                                        )
                                        .isChecked
                        settingsManager.irisMotionNeuralWeight =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_neural_weight
                                        )
                                        .value
                        settingsManager.irisMotionSaccadeVelocity =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_saccade_velocity
                                        )
                                        .value
                        settingsManager.irisMotionSaccadeBox =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_saccade_box
                                        )
                                        .value
                        settingsManager.irisMotionFixationDrift =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_fixation_drift
                                        )
                                        .value
                        settingsManager.irisMotionFilterSensitivity =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_filter_sensitivity
                                        )
                                        .value
                        settingsManager.irisMotionEdgeMargin =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_edge_margin
                                        )
                                        .value
                        settingsManager.irisMotionPhysioRestX =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_physio_rest_x
                                        )
                                        .value
                        settingsManager.irisMotionPhysioRestY =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_physio_rest_y
                                        )
                                        .value
                        settingsManager.irisMotionIntrinsicAmpX =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_intrinsic_amp_x
                                        )
                                        .value
                        settingsManager.irisMotionIntrinsicAmpY =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_intrinsic_amp_y
                                        )
                                        .value
                        settingsManager.irisMotionCursorSize =
                                etIrisMotionCursorSize.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_CURSOR_SIZE
                        settingsManager.irisMotionTrackedEye =
                                spinnerIrisMotionTrackedEye.selectedItemPosition
                        // DoF spinner: index 0→2, 1→4, 2→6
                        settingsManager.irisMotionHeadTrackingDoF =
                                when (spinnerIrisMotionHeadTrackingDoF.selectedItemPosition) {
                                        1 -> 4
                                        2 -> 6
                                        else -> 2
                                }

                        settingsManager.irisMotionCameraZoom =
                                when (spinnerIrisMotionCameraZoom.selectedItemPosition) {
                                        1 -> 1.5f
                                        2 -> 2.0f
                                        3 -> 3.0f
                                        4 -> 4.0f
                                        else -> 1.0f
                                }

                        // Head Compensation Gain save
                        settingsManager.irisMotionHeadCompensationGain =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_head_comp_gain
                                        )
                                        .value
                        settingsManager.isIrisMotionCameraMirrored =
                                cbIrisMotionCameraMirror.isChecked
                        settingsManager.irisMotionSphericalCompensation =
                                cbSphericalCompensation.isChecked
                        settingsManager.irisMotionTargetSnap = cbTargetSnap.isChecked
                        settingsManager.irisMotionTargetSnapRadius = sliderTargetSnapRadius.value

                        // Uložení Filter Pipeline sekvence do Stringu (zachováme pořadí z definice,
                        // ať nejsou zádrhely)
                        val activeFilters = mutableListOf<String>()
                        if (cbFilterMedian.isChecked) activeFilters.add("MEDIAN")
                        if (cbFilterAlphaBeta.isChecked) activeFilters.add("ALPHABETA")
                        if (cbFilterKalman.isChecked) activeFilters.add("KALMAN")
                        if (cbFilterOneEuro.isChecked) activeFilters.add("ONEEURO")
                        settingsManager.irisMotionFilterSequence = activeFilters

                        settingsManager.irisMotionExpressionsOneEuroEnabled = cbExprOneEuro.isChecked
                        settingsManager.irisMotionExpressionsCrosstalkEnabled = cbExprCrosstalk.isChecked
                        settingsManager.irisMotionExpressionsAbtEnabled = cbExprAbt.isChecked

                        // Save IrisMotion Filtrační parametry
                        settingsManager.irisMotionFilterBeta =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_filter_beta
                                        )
                                        .value
                        settingsManager.irisMotionFilterCutoff =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_filter_cutoff
                                        )
                                        .value
                        settingsManager.irisMotionFilterDCutoff =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_filter_d_cutoff
                                        )
                                        .value
                        settingsManager.irisMotionKalmanQNoise =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_kalman_q
                                        )
                                        .value / 100000f
                        settingsManager.irisMotionKalmanRNoise =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_kalman_r
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpX =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_x
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpY =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_y
                                        )
                                        .value
                        settingsManager.irisMotionCenterXOffset =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_center_x_offset
                                        )
                                        .value
                        settingsManager.irisMotionCenterYOffset =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_center_y_offset
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpLeftEyeLeft =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_left_eye_left
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpLeftEyeRight =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_left_eye_right
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpRightEyeLeft =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_right_eye_left
                                        )
                                        .value
                        settingsManager.irisMotionGazeAmpRightEyeRight =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_gaze_amp_right_eye_right
                                        )
                                        .value

                        settingsManager.irisMotionDeadzonePx =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_deadzone
                                        )
                                        .value
                        settingsManager.irisMotionAutoCenter =
                                findViewById<
                                                com.google.android.material.materialswitch.MaterialSwitch>(
                                                R.id.switch_irismotion_auto_center
                                        )
                                        .isChecked
                        settingsManager.irisMotionAutoCenterSpeed =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_auto_center_speed
                                        )
                                        .value
                        settingsManager.irisMotionAutoCenterDelayMs =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_auto_center_delay_ms
                                        )
                                        .value
                                        .toLong()
                        settingsManager.irisMotionMedianWindow =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_median
                                        )
                                        .value
                                        .toInt()

                        // Save IrisMotion Interakce
                        settingsManager.irisMotionLeftBlinkTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_left_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_LEFT_BLINK_TIMEOUT

                        settingsManager.irisMotionRightBlinkTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_right_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_RIGHT_BLINK_TIMEOUT

                        settingsManager.irisMotionBothBlinkTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_both_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_BOTH_BLINK_TIMEOUT

                        settingsManager.irisMotionSmileTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_smile_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_SMILE_TIMEOUT

                        settingsManager.irisMotionFrownTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_frown_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_FROWN_TIMEOUT

                        settingsManager.irisMotionRaiseEyebrowsTimeout =
                                findViewById<
                                                com.google.android.material.textfield.TextInputEditText>(
                                                R.id.et_irismotion_raise_eyebrows_timeout
                                        )
                                        .text
                                        .toString()
                                        .toLongOrNull()
                                        ?: SettingsManager.DEFAULT_IRISMOTION_RAISE_EYEBROWS_TIMEOUT

                        settingsManager.irisMotionSmileThreshold =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_smile_threshold
                                        ).value / 100f

                        settingsManager.irisMotionFrownThreshold =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_frown_threshold
                                        ).value / 100f

                        settingsManager.irisMotionRaiseEyebrowsThreshold =
                                findViewById<com.google.android.material.slider.Slider>(
                                                R.id.slider_irismotion_raise_eyebrows_threshold
                                        ).value / 100f

                        settingsManager.irisMotionLeftBlinkAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_left_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionRightBlinkAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_right_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionBothBlinkAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_both_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionSmileAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_smile_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionFrownAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_frown_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionRaiseEyebrowsAction =
                                findViewById<Spinner>(R.id.spinner_irismotion_raise_eyebrows_action)
                                        .selectedItemPosition

                        settingsManager.irisMotionBothBlinkApp =
                                findViewById<AutoCompleteTextView>(R.id.actv_irismotion_app_picker)
                                        .text
                                        .toString()

                        // === Save Perception Engine Settings ===
                        settingsManager.isPerceptionEnabled = cbPerceptionEnabled.isChecked

                        val cameraSources =
                                resources.getStringArray(R.array.perception_camera_sources)
                        val selectedCameraText = actvPerceptionCameraSource.text.toString()
                        val cameraIdx =
                                cameraSources.indexOf(selectedCameraText).takeIf { it >= 0 }
                                        ?: SettingsManager.DEFAULT_PERCEPTION_CAMERA_SOURCE
                        settingsManager.perceptionCameraSource = cameraIdx

                        settingsManager.perceptionOdConfidence = sliderPerceptionOdConfidence.value
                        settingsManager.perceptionOdMaxResults =
                                etPerceptionOdMaxResults.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_OD_MAX_RESULTS
                        settingsManager.perceptionOdTimeoutMs =
                                etPerceptionOdTimeout.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_OD_TIMEOUT_MS
                        settingsManager.perceptionOcrTimeoutMs =
                                etPerceptionOcrTimeout.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_OCR_TIMEOUT_MS
                        settingsManager.perceptionOcrMaxLength =
                                etPerceptionOcrMaxLength.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_OCR_MAX_LENGTH
                        settingsManager.perceptionThermalSkip =
                                etPerceptionThermalSkip.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_THERMAL_SKIP
                        settingsManager.perceptionThermalGate1 =
                                etPerceptionThermalGate1.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_THERMAL_GATE_1
                        settingsManager.perceptionThermalGate2 =
                                etPerceptionThermalGate2.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_THERMAL_GATE_2
                        settingsManager.perceptionThreadPriority =
                                etPerceptionThreadPriority.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_THREAD_PRIORITY
                        settingsManager.perceptionResolution =
                                sliderPerceptionResolution.value.toInt()

                        // Save Pose Estimation Settings
                        settingsManager.isPoseEstimationEnabled = cbPerceptionPoseEnabled.isChecked
                        settingsManager.perceptionPoseConfidence = sliderPerceptionPoseConfidence.value
                        settingsManager.perceptionPoseMaxPoses =
                                etPerceptionPoseMaxPoses.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_POSE_MAX_POSES
                        settingsManager.perceptionPoseResolution =
                                etPerceptionPoseResolution.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_POSE_RESOLUTION
                        settingsManager.perceptionPoseTimeoutMs =
                                etPerceptionPoseTimeout.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_POSE_TIMEOUT_MS

                        settingsManager.isAudioPerceptionEnabled =
                                cbPerceptionAudioEnabled.isChecked
                        settingsManager.perceptionAudioConfidence =
                                sliderPerceptionAudioConfidence.value
                        settingsManager.perceptionAudioMaxResults =
                                etPerceptionAudioMaxResults.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_AUDIO_MAX_RESULTS
                        settingsManager.perceptionAudioIntervalMs =
                                etPerceptionAudioInterval.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_AUDIO_INTERVAL_MS
                        settingsManager.perceptionAudioCooldownMs =
                                etPerceptionAudioCooldown.text.toString().toLongOrNull()
                                        ?: SettingsManager.DEFAULT_PERCEPTION_AUDIO_COOLDOWN_MS
                        settingsManager.perceptionTtsSceneChange = cbPerceptionTtsScene.isChecked
                        settingsManager.perceptionTtsCriticalAudio =
                                cbPerceptionTtsCritical.isChecked
                        settingsManager.perceptionSceneMinNewEntities =
                                sliderPerceptionSceneMinEntities.value.toInt()

                        // Save Agent Worker Settings
                        settingsManager.screenParserMaxDepth = sliderParserMaxDepth.value.toInt()
                        settingsManager.screenParserMaxElements =
                                etParserMaxElements.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_SCREEN_PARSER_MAX_ELEMENTS
                        settingsManager.appCacheMaxApps =
                                etCacheMaxApps.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_APP_CACHE_MAX_APPS
                        settingsManager.appCacheMaxElements =
                                etCacheMaxElements.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_APP_CACHE_MAX_ELEMENTS
                        settingsManager.safetyGuardMaxHistory =
                                etSafetyMaxHistory.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_SAFETY_GUARD_MAX_HISTORY
                        settingsManager.safetyGuardLoopThreshold =
                                sliderSafetyLoopThreshold.value.toInt()
                        settingsManager.floatingButtonSizeDp = sliderFloatingSize.value.toInt()
                        settingsManager.floatingButtonClickThresholdMs =
                                etFloatingClickThreshold.text.toString().toIntOrNull()
                                        ?: SettingsManager
                                                .DEFAULT_FLOATING_BUTTON_CLICK_THRESHOLD_MS
                        settingsManager.floatingButtonMoveThresholdPx =
                                etFloatingMoveThreshold.text.toString().toIntOrNull()
                                        ?: SettingsManager.DEFAULT_FLOATING_BUTTON_MOVE_THRESHOLD_PX
                        settingsManager.floatingButtonAutoHideSec =
                                sliderFloatingAutoHide.value.toInt()
                        settingsManager.floatingButtonLockscreenHide =
                                cbFloatingLockscreenHide.isChecked

                        // Save Agent Notifier Settings
                        settingsManager.floatingNotifyEnabled = swFloatingNotifyEnabled.isChecked
                        settingsManager.floatingNotifyStyle =
                                spinFloatingNotifyStyle.selectedItemPosition
                        settingsManager.floatingNotifyPosition =
                                spinFloatingNotifyPosition.selectedItemPosition
                        settingsManager.floatingNotifyShowWarnings =
                                cbFloatingNotifyWarnings.isChecked
                        settingsManager.floatingNotifyShowVision = cbFloatingNotifyVision.isChecked
                        settingsManager.floatingNotifyShowAudio = cbFloatingNotifyAudio.isChecked

                        // === Save NLP Tier 2.5 Settings ===
                        val selectedBackend = actvSummarizationBackend.text.toString()
                        if (selectedBackend.isNotBlank()) {
                                settingsManager.summarizationBackend = selectedBackend
                        }
                        settingsManager.translateEnabled = cbNlpTranslateEnabled.isChecked
                        settingsManager.translateAutoDownload = cbNlpTranslateAutoDownload.isChecked
                        settingsManager.sentimentAnalysisEnabled = cbNlpSentimentEnabled.isChecked
                        settingsManager.smartReplyEnabled = cbNlpSmartReplyEnabled.isChecked
                        settingsManager.genaiProofreadEnabled = cbNlpGenaiProofread.isChecked
                        settingsManager.genaiRewriteEnabled = cbNlpGenaiRewrite.isChecked

                        // Save NLP Tuner weights (Progress to Weight: w = (p / 10.0) - 1.0)
                        settingsManager.nlpWeightTasks = (sbNlpWeightTasks.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightSlang = (sbNlpWeightSlang.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightTech = (sbNlpWeightTech.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightUrgency =
                                (sbNlpWeightUrgency.progress / 10.0f) - 1.0f

                        // Save NLP Tuner weights (Progress to Weight: w = (p / 10.0) - 1.0)
                        settingsManager.nlpWeightTasks = (sbNlpWeightTasks.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightSlang = (sbNlpWeightSlang.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightTech = (sbNlpWeightTech.progress / 10.0f) - 1.0f
                        settingsManager.nlpWeightUrgency =
                                (sbNlpWeightUrgency.progress / 10.0f) - 1.0f

                        Toast.makeText(this, getString(R.string.msg_saved), Toast.LENGTH_SHORT)
                                .show()
                        finish()
                } catch (e: Exception) {
                        Log.e("SettingsActivity", "Error saving settings", e)
                        Toast.makeText(this, getString(R.string.msg_error), Toast.LENGTH_LONG)
                                .show()
                }
        }

        /** Aktualizuje status kalibrace na IrisMotion záložce. */
        private fun updateIrisMotionCalibrationStatus() {
                val isCalibrated = settingsManager.isIrisMotionCalibrated
                if (isCalibrated) {
                        tvIrisMotionCalibrationStatus.text = "✅ Nakalibrováno"
                        tvIrisMotionCalibrationStatus.setTextColor("#00E676".toColorInt())
                } else {
                        tvIrisMotionCalibrationStatus.text =
                                "⚠️ Vyžaduje kalibraci — klikni na 'Spustit kalibraci'"
                        tvIrisMotionCalibrationStatus.setTextColor("#FFB74D".toColorInt())
                }
        }

        /** Update agent status based on accessibility service enablement */
        private fun updateAgentStatus() {
                val isAccessibilityEnabled = AgentHelper.isAccessibilityServiceEnabled(this)

                if (isAccessibilityEnabled) {
                        tvAgentStatus.text = getString(R.string.agent_status_ready)
                        tvAgentStatus.setTextColor("#00E676".toColorInt())
                } else {
                        tvAgentStatus.text = getString(R.string.agent_status_not_enabled)
                        tvAgentStatus.setTextColor("#FF5252".toColorInt())
                }

                // Battery Optimization Check
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                val isIgnored = pm.isIgnoringBatteryOptimizations(packageName)
                if (isIgnored) {
                        btnBatteryOptimization.text = "Optimalizace Vypnuta (OK)"
                        btnBatteryOptimization.isEnabled = false
                        btnBatteryOptimization.backgroundTintList =
                                android.content.res.ColorStateList.valueOf(
                                        "#00E676".toColorInt()
                                ) // Green
                        btnBatteryOptimization.setTextColor(Color.BLACK)
                } else {
                        btnBatteryOptimization.text = getString(R.string.btn_battery_optim)
                        btnBatteryOptimization.isEnabled = true
                        btnBatteryOptimization.backgroundTintList =
                                android.content.res.ColorStateList.valueOf(
                                        "#FFB74D".toColorInt()
                                ) // Orange
                        btnBatteryOptimization.setTextColor(Color.BLACK)
                }
        }

        /** Test Agent Voice TTS */
        private fun testAgentVoice() {
                if (!cbAgentTts.isChecked) {
                        Toast.makeText(
                                        this,
                                        getString(R.string.msg_agent_tts_disabled),
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        return
                }

                // Create temp TTS instance for testing
                val testTTS =
                        com.example.llama.agent.voice.sherpa.SherpaTtsEngine(
                                applicationContext,
                                settingsManager
                        )
                if (!testTTS.initialize()) {
                        Toast.makeText(
                                        this,
                                        "❌ TTS engine se nepodařilo inicializovat",
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                        return
                }

                // Speak test message
                btnAgentTestVoice.isEnabled = false
                btnAgentTestVoice.text = getString(R.string.msg_agent_speaking)

                testTTS.speak(
                        getString(R.string.agent_test_voice_message),
                        interrupt = true,
                        onDone = {
                                // Callback přijde PO dokončení C++ inference + přehrání audia
                                btnAgentTestVoice.post {
                                        btnAgentTestVoice.isEnabled = true
                                        btnAgentTestVoice.text =
                                                getString(R.string.agent_test_voice)
                                        testTTS.shutdown()
                                }
                        }
                )
        }

        /**
         * Dynamically generate checkboxes for all supported Prism4j languages. Top 15 languages
         * hardcoded for offline support.
         */
        private fun setupPrismLanguageCheckboxes() {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)
                containerPrismLanguages.removeAllViews()

                // Get all supported languages from PrismLanguage enum
                val supportedLanguages = PrismLanguage.entries.sortedBy { it.displayName }

                supportedLanguages.forEach { lang ->
                        val checkbox =
                                MaterialCheckBox(this).apply {
                                        text = lang.displayName
                                        textSize = 14f
                                        setTextColor(Color.WHITE)
                                        buttonTintList =
                                                android.content.res.ColorStateList.valueOf(
                                                        Color.WHITE
                                                )
                                        tag = lang.key // Store language key in tag for saving later

                                        // Load saved state
                                        val prefKey = "prism_lang_${lang.key}"
                                        isChecked =
                                                prefs.getBoolean(
                                                        prefKey,
                                                        true
                                                ) // Default: all enabled
                                }
                        containerPrismLanguages.addView(checkbox)
                }

                // Show/hide languages section based on syntax highlighting master checkbox
                cbMarkdownSyntax.setOnCheckedChangeListener { _, isChecked ->
                        containerPrismLanguages.visibility =
                                if (isChecked) View.VISIBLE else View.GONE
                        findViewById<View>(R.id.lbl_prism_languages).visibility =
                                if (isChecked) View.VISIBLE else View.GONE
                        findViewById<View>(R.id.desc_prism_languages).visibility =
                                if (isChecked) View.VISIBLE else View.GONE
                }
        }

        /** Load Prism markdown plugin settings from SharedPreferences. */
        private fun loadPrismSettings() {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)

                cbMarkdownTables.isChecked = prefs.getBoolean("markdown_enable_tables", true)
                cbMarkdownStrikethrough.isChecked =
                        prefs.getBoolean("markdown_enable_strikethrough", true)
                cbMarkdownTasklist.isChecked = prefs.getBoolean("markdown_enable_tasklist", true)
                cbMarkdownHtml.isChecked = prefs.getBoolean("markdown_enable_html", true)
                cbMarkdownLatex.isChecked = prefs.getBoolean("markdown_enable_latex", true)
                cbMarkdownSyntax.isChecked =
                        prefs.getBoolean("markdown_enable_syntax_highlight", true)

                // Setup Theme Dropdown
                val themes = com.example.llama.managers.plugins.PrismThemes.Theme.entries
                val adapter =
                        android.widget.ArrayAdapter(
                                this,
                                R.layout.item_dropdown_theme,
                                themes.map { it.displayName }
                        )
                val dropdown =
                        findViewById<android.widget.AutoCompleteTextView>(R.id.dropdown_prism_theme)
                dropdown.setAdapter(adapter)

                // Select current theme
                val currentThemeValue = prefs.getString("markdown_theme", "default")
                val currentTheme =
                        themes.find { it.preferenceValue == currentThemeValue } ?: themes.first()
                dropdown.setText(currentTheme.displayName, false)

                // Save selection when clicked (logic handles mapping back to value)
                // Note: Actual saving happens in savePrismSettings based on selected text

                // Show/hide language section based on syntax highlighting state
                val showLanguages = cbMarkdownSyntax.isChecked
                val themeVisibility = if (showLanguages) View.VISIBLE else View.GONE

                containerPrismLanguages.visibility = themeVisibility
                findViewById<View>(R.id.lbl_prism_languages).visibility = themeVisibility
                findViewById<View>(R.id.desc_prism_languages).visibility = themeVisibility
                findViewById<View>(R.id.menu_prism_theme).visibility =
                        themeVisibility // Show/hide theme selector
        }

        /** Save Prism markdown plugin settings to SharedPreferences. */
        private fun savePrismSettings() {
                val prefs = PreferenceManager.getDefaultSharedPreferences(this)

                prefs.edit {
                        // Save markdown plugin states
                        putBoolean("markdown_enable_tables", cbMarkdownTables.isChecked)
                        putBoolean(
                                "markdown_enable_strikethrough",
                                cbMarkdownStrikethrough.isChecked
                        )
                        putBoolean("markdown_enable_tasklist", cbMarkdownTasklist.isChecked)
                        putBoolean("markdown_enable_html", cbMarkdownHtml.isChecked)
                        putBoolean("markdown_enable_latex", cbMarkdownLatex.isChecked)
                        putBoolean("markdown_enable_syntax_highlight", cbMarkdownSyntax.isChecked)

                        // Save selected theme
                        val dropdown = findViewById<AutoCompleteTextView>(R.id.dropdown_prism_theme)
                        val selectedName = dropdown.text.toString()
                        val selectedTheme =
                                com.example.llama.managers.plugins.PrismThemes.Theme.entries.find {
                                        it.displayName == selectedName
                                }
                        val themeValue = selectedTheme?.preferenceValue ?: "default"
                        putString("markdown_theme", themeValue)

                        // Save language preferences from dynamically generated checkboxes
                        for (i in 0 until containerPrismLanguages.childCount) {
                                val checkbox =
                                        containerPrismLanguages.getChildAt(i) as? MaterialCheckBox
                                checkbox?.let {
                                        val langKey = it.tag as? String
                                        if (langKey != null) {
                                                putBoolean("prism_lang_$langKey", it.isChecked)
                                        }
                                }
                        }
                }
        }

        private fun populateAvailableLanguages() {
                Log.d("SettingsActivity", "🔍 Starting STABILIZED REAL language detection...")
                val currentSttEngine = settingsManager.agentSpeechEngine
                val currentTtsEngine = settingsManager.agentTtsEngine

                val sttPackage =
                        if (currentSttEngine.isNotEmpty()) currentSttEngine.substringBefore("/")
                        else null
                val ttsPackage = if (currentTtsEngine.isNotEmpty()) currentTtsEngine else null

                // Clear previous state
                installedSttLanguages.clear()
                supportedSttLanguages.clear()
                installedTtsLanguages.clear()

                // 1. Launch TTS Check
                checkTtsData(ttsPackage)

                // 2. Launch STT Check (Async)
                // API 33+ Modern Check (Truly On-Device)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        try {
                                val recognizer =
                                        if (sttPackage != null) {
                                                SpeechRecognizer.createSpeechRecognizer(
                                                        this,
                                                        ComponentName.unflattenFromString(
                                                                currentSttEngine
                                                        )
                                                )
                                        } else {
                                                SpeechRecognizer.createSpeechRecognizer(this)
                                        }

                                val supportIntent =
                                        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                if (sttPackage != null) setPackage(sttPackage)
                                        }

                                recognizer.checkRecognitionSupport(
                                        supportIntent,
                                        mainExecutor,
                                        object : android.speech.RecognitionSupportCallback {
                                                override fun onSupportResult(
                                                        recognitionSupport:
                                                                android.speech.RecognitionSupport
                                                ) {
                                                        // OFFLINE: Strictly only truly installed
                                                        // on-device packages
                                                        installedSttLanguages.addAll(
                                                                recognitionSupport
                                                                        .installedOnDeviceLanguages
                                                        )

                                                        // ONLINE: All supported online/offline
                                                        // packages
                                                        // We must explicitly include online
                                                        // languages, otherwise the list
                                                        // looks just like the offline one
                                                        supportedSttLanguages.addAll(
                                                                recognitionSupport.onlineLanguages
                                                        )
                                                        supportedSttLanguages.addAll(
                                                                recognitionSupport
                                                                        .supportedOnDeviceLanguages
                                                        )

                                                        Log.d(
                                                                "SettingsActivity",
                                                                "✅ API 33+ scan for [${sttPackage ?: "default"}]: ${installedSttLanguages.size} installed, ${supportedSttLanguages.size} supported"
                                                        )
                                                        // Merge with TTS results (if any arrived
                                                        // yet, or wait for them)
                                                        finalizeLanguageSelection(
                                                                installedSttLanguages +
                                                                        installedTtsLanguages,
                                                                supportedSttLanguages
                                                        )
                                                        recognizer.destroy()
                                                }

                                                override fun onError(error: Int) {
                                                        Log.w(
                                                                "SettingsActivity",
                                                                "checkRecognitionSupport error: $error. Falling back to simple query."
                                                        )
                                                        recognizer.destroy()
                                                        queryLanguagesViaBroadcast(sttPackage)
                                                }
                                        }
                                )
                        } catch (e: Exception) {
                                Log.e("SettingsActivity", "Modern language check failed", e)
                                queryLanguagesViaBroadcast(sttPackage)
                        }
                } else {
                        queryLanguagesViaBroadcast(sttPackage)
                }
        }

        private fun queryLanguagesViaBroadcast(targetPackage: String?) {
                try {
                        val detailsIntent = Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS)
                        if (targetPackage != null) detailsIntent.setPackage(targetPackage)

                        sendOrderedBroadcast(
                                detailsIntent,
                                null,
                                object : android.content.BroadcastReceiver() {
                                        override fun onReceive(context: Context, intent: Intent) {
                                                val results = getResultExtras(true)
                                                val engineSupported =
                                                        results.getStringArrayList(
                                                                RecognizerIntent
                                                                        .EXTRA_SUPPORTED_LANGUAGES
                                                        )
                                                val pref =
                                                        results.getString(
                                                                RecognizerIntent
                                                                        .EXTRA_LANGUAGE_PREFERENCE
                                                        )

                                                if (engineSupported != null) {
                                                        supportedSttLanguages.addAll(
                                                                engineSupported
                                                        )
                                                }
                                                if (pref != null) {
                                                        installedSttLanguages.add(pref)
                                                }

                                                finalizeLanguageSelection(
                                                        installedSttLanguages +
                                                                installedTtsLanguages,
                                                        supportedSttLanguages
                                                )
                                        }
                                },
                                null,
                                android.app.Activity.RESULT_OK,
                                null,
                                null
                        )
                } catch (e: Exception) {
                        Log.e("SettingsActivity", "Broadcast language query failed", e)
                }
        }

        private fun checkTtsData(enginePackage: String?) {
                if (enginePackage in listOf("sherpa", "whisper", "android")) {
                        Log.d(
                                "SettingsActivity",
                                "Skipping TTS check for STT-only identifier: $enginePackage"
                        )
                        return
                }
                try {
                        val checkIntent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
                        if (enginePackage != null && enginePackage.isNotBlank()) {
                                checkIntent.setPackage(enginePackage)
                        }
                        Log.d(
                                "SettingsActivity",
                                "🚀 Launching TTS Data Check for: ${enginePackage ?: "default"}"
                        )
                        ttsDataLauncher.launch(checkIntent)
                } catch (e: Exception) {
                        Log.e("SettingsActivity", "Failed to launch TTS check", e)
                }
        }

        private fun finalizeLanguageSelection(installed: Set<String>, supported: Set<String>) {
                runOnUiThread {
                        // Helper to format: "Czech (cs-CZ)"
                        fun formatLocale(tag: String): String {
                                if (tag.isEmpty()) return "Default"
                                val locale = java.util.Locale.forLanguageTag(tag)
                                return "${locale.displayName} ($tag)"
                        }

                        // OFFLINE: Strictly only installed packages
                        val offlineList =
                                if (installed.isNotEmpty()) {
                                        installed.map { formatLocale(it) }.sorted()
                                } else {
                                        // FORCE TRUTH: Do not make up a language if none are found.
                                        // This informs the user they need to download a pack.
                                        listOf("❌ NO OFFLINE LANGUAGES FOUND")
                                }

                        // ONLINE: Full list (supported + installed)
                        val onlineList =
                                if (supported.isNotEmpty()) {
                                        (supported + installed)
                                                .map { formatLocale(it) }
                                                .toSet()
                                                .sorted()
                                } else {
                                        listOf("❌ NO ONLINE LANGUAGES FOUND")
                                }

                        val offlineAdapter =
                                ArrayAdapter(
                                        this,
                                        android.R.layout.simple_dropdown_item_1line,
                                        offlineList
                                )
                        val onlineAdapter =
                                ArrayAdapter(
                                        this,
                                        android.R.layout.simple_dropdown_item_1line,
                                        onlineList
                                )

                        actvAgentLangOffline.setAdapter(offlineAdapter)
                        actvAgentLangOnline.setAdapter(onlineAdapter)

                        // Allow user to clear and see full list
                        actvAgentLangOffline.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) actvAgentLangOffline.showDropDown()
                        }
                        actvAgentLangOnline.setOnFocusChangeListener { _, hasFocus ->
                                if (hasFocus) actvAgentLangOnline.showDropDown()
                        }

                        Log.i(
                                "SettingsActivity",
                                "🌍 Sync finished. Offline: ${offlineList.size}, Online: ${onlineList.size}"
                        )
                }
        }

        private fun populateSpeechEngines() {
                val pm = packageManager
                // Use MATCH_ALL (131072) to ensure we see all services, including system ones that
                // might be
                // hidden
                val flags = android.content.pm.PackageManager.MATCH_ALL
                val services =
                        pm.queryIntentServices(
                                Intent(android.speech.RecognitionService.SERVICE_INTERFACE),
                                flags
                        )

                Log.i(
                        "SettingsActivity",
                        "🔍 STT Service Query returned ${services.size} services."
                )

                // DEEP DIAGNOSTIC: Check for Activity-based engines vs Service-based
                // Many apps (like old SVoice or some 3rd party) might only be Activities, not
                // Services.
                // We cannot use them, but we should know they exist to explain to the user.
                try {
                        val activityIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        val activities = pm.queryIntentActivities(activityIntent, flags)

                        Log.i(
                                "SettingsActivity",
                                "📦 DIAGNOSTIC: Found ${activities.size} apps capable of 'Voice Search' (Activities)."
                        )
                        activities.forEach {
                                Log.d(
                                        "SettingsActivity",
                                        "   -> Activity: ${it.activityInfo.packageName}/${it.activityInfo.name}"
                                )
                        }

                        // Samsung/Specific Check
                        val knownPackages =
                                listOf(
                                        "com.samsung.android.bixby.agent",
                                        "com.samsung.android.svoice",
                                        "com.google.android.googlequicksearchbox",
                                        "com.google.android.tts"
                                )

                        knownPackages.forEach { pkg ->
                                try {
                                        val info =
                                                pm.getPackageInfo(
                                                        pkg,
                                                        android.content.pm.PackageManager
                                                                .GET_SERVICES
                                                )
                                        val hasService =
                                                info.services?.any { s ->
                                                        s.name.contains("Recognition", true)
                                                } == true
                                        Log.d(
                                                "SettingsActivity",
                                                "🕵️ SPECIFIC CHECK [$pkg]: Installed? YES. Has RecognitionService? $hasService"
                                        )
                                } catch (_: Exception) {
                                        Log.d(
                                                "SettingsActivity",
                                                "🕵️ SPECIFIC CHECK [$pkg]: Not Installed."
                                        )
                                }
                        }
                } catch (e: Exception) {
                        Log.e("SettingsActivity", "Diagnostic check failed", e)
                }

                val engines = mutableListOf<Pair<String, String>>()
                // Always add "Default" as first option
                engines.add(getString(R.string.engines_default) to "")
                engines.add("Llama Sherpa-ONNX (Offline) (\uD83D\uDD25)" to "sherpa")

                // Add SERVICES (The only ones we can actually use)
                for (resolveInfo in services) {
                        val serviceInfo = resolveInfo.serviceInfo
                        val packageName = serviceInfo.packageName
                        val className = serviceInfo.name
                        val label = resolveInfo.loadLabel(pm).toString()

                        Log.d(
                                "SettingsActivity",
                                "Found STT Service: $label ($packageName/$className)"
                        )

                        // Format: "Google App (com.google...)"
                        engines.add("$label ($packageName)" to "$packageName/$className")
                }

                availableSpeechEngines = engines

                val adapter =
                        ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                engines.map { it.first }
                        )
                spinnerAgentSpeechEngine.adapter = adapter

                val transcriptAdapter =
                        ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                engines.map { it.first }
                        )
                spinnerTranscriptAndroidEngine.adapter = transcriptAdapter
                spinnerTranscriptAndroidEngine.onItemSelectedListener =
                        object : android.widget.AdapterView.OnItemSelectedListener {
                                override fun onItemSelected(
                                        parent: android.widget.AdapterView<*>?,
                                        view: android.view.View?,
                                        pos: Int,
                                        id: Long
                                ) {
                                        if (pos >= 0 && pos < availableSpeechEngines.size) {
                                                settingsManager.transcriptAndroidEngine =
                                                        availableSpeechEngines[pos].second
                                        }
                                }
                                override fun onNothingSelected(
                                        parent: android.widget.AdapterView<*>?
                                ) {}
                        }
        }

        private fun populateTtsEngines() {
                val pm = packageManager
                val intent =
                        Intent(android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                // Use MATCH_ALL to ensure we find all installed TTS engines
                val flags = android.content.pm.PackageManager.MATCH_ALL
                val services = pm.queryIntentServices(intent, flags)

                Log.i("SettingsActivity", "🔍 TTS Query returned ${services.size} services.")

                // DIAGNOSTIC LOOP: Check what is actually installed versus what we see for TTS
                try {
                        val allPackages = pm.getInstalledPackages(flags)
                        val ttsPackages =
                                allPackages.filter {
                                        it.services?.any { s ->
                                                s.name.contains("TTS", true) ||
                                                        s.name.contains("TextToSpeech", true)
                                        } == true
                                }
                        Log.d(
                                "SettingsActivity",
                                "📦 DIAGNOSTIC: Found ${ttsPackages.size} potential TTS packages installed locally."
                        )
                        ttsPackages.forEach { Log.d("SettingsActivity", "   -> ${it.packageName}") }
                } catch (e: Exception) {
                        Log.e("SettingsActivity", "TTS Diagnostic check failed", e)
                }

                val engines = mutableListOf<Pair<String, String>>()
                // Always add "Default" as first option
                engines.add(getString(R.string.engines_default) to "")
                engines.add("Llama Sherpa-ONNX (Offline) (\uD83D\uDD25)" to "sherpa")

                for (resolveInfo in services) {
                        val serviceInfo = resolveInfo.serviceInfo
                        val packageName = serviceInfo.packageName
                        val label = resolveInfo.loadLabel(pm).toString()

                        Log.d("SettingsActivity", "Found TTS Service: $label ($packageName)")

                        // Format: "Google TTS (com.google...)"
                        engines.add("$label ($packageName)" to packageName)

                        Log.i("SettingsActivity", "Found TTS Engine: $label ($packageName)")
                }

                availableTtsEngines = engines

                val adapter =
                        ArrayAdapter(
                                this,
                                android.R.layout.simple_spinner_dropdown_item,
                                engines.map { it.first }
                        )
                spinnerAgentTtsEngine.adapter = adapter
        }

        /**
         * Načte stav aktuálně importovaného MediaPipe LLM modelu z NlpModelRepository a aktualizuje
         * UI elementy (tv_mediapipe_model_status, tv_mediapipe_model_path).
         */
        private fun refreshMediaPipeLlmStatus() {
                lifecycleScope.launch {
                        try {
                                val model = nlpModelRepository.getMediaPipeLlmInfo()
                                withContext(Dispatchers.Main) {
                                        val tvStatus =
                                                findViewById<android.widget.TextView>(
                                                        R.id.tv_mediapipe_model_status
                                                )
                                        val tvPath =
                                                findViewById<android.widget.TextView>(
                                                        R.id.tv_mediapipe_model_path
                                                )

                                        if (model != null) {
                                                val sizeMb = model.fileSize / (1024 * 1024)
                                                tvStatus.text =
                                                        "✅ ${model.displayName} (${sizeMb} MB)"
                                                tvStatus.setTextColor(
                                                        android.graphics.Color.parseColor("#4CAF50")
                                                )
                                                tvPath.text = model.sourceUri
                                        } else {
                                                tvStatus.text =
                                                        "⚠️ Žádný model — klikněte na tlačítko níže pro import"
                                                tvStatus.setTextColor(
                                                        android.graphics.Color.parseColor("#FF9800")
                                                )
                                                tvPath.text = ""
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e("SettingsActivity", "MediaPipe LLM status refresh failed", e)
                        }
                }
        }
        /**
         * refreshBertModels — Aktualizuje UI seznam importovaných BERT modelů v NLP tabu.
         *
         * Načítá z BertModelRepository všechny model záznamy (isVocabFile = false) a zobrazuje je v
         * containerBertModels LinearLayout.
         */
        private fun refreshBertModels() {
                lifecycleScope.launch {
                        try {
                                val models =
                                        withContext(Dispatchers.IO) {
                                                bertRepository.getBertModelsOnly()
                                        }
                                withContext(Dispatchers.Main) {
                                        val container =
                                                findViewById<android.widget.LinearLayout>(
                                                        R.id.container_bert_models
                                                )
                                        val tvStatus =
                                                findViewById<android.widget.TextView>(
                                                        R.id.tv_bert_active_model
                                                )

                                        container?.removeAllViews()

                                        if (models.isEmpty()) {
                                                tvStatus?.text =
                                                        "⚠️ Žádný vlastní model — používá se vestavěný Google BERT"
                                                tvStatus?.setTextColor(
                                                        android.graphics.Color.parseColor("#FF9800")
                                                )
                                                return@withContext
                                        }

                                        val activeModel = models.maxByOrNull { it.lastAccessTime }
                                        tvStatus?.text =
                                                "✅ Aktivní: ${activeModel?.displayName ?: "Neznámý"}"
                                        tvStatus?.setTextColor(
                                                android.graphics.Color.parseColor("#4CAF50")
                                        )

                                        models.forEach { model ->
                                                val row =
                                                        android.widget.LinearLayout(
                                                                        this@SettingsActivity
                                                                )
                                                                .apply {
                                                                        orientation =
                                                                                android.widget
                                                                                        .LinearLayout
                                                                                        .HORIZONTAL
                                                                        setPadding(0, 8, 0, 8)
                                                                }
                                                val tv =
                                                        android.widget.TextView(
                                                                        this@SettingsActivity
                                                                )
                                                                .apply {
                                                                        val sizeMb =
                                                                                model.fileSize /
                                                                                        (1024 *
                                                                                                1024)
                                                                        val vocabInfo =
                                                                                when (model.vocabStrategy
                                                                                ) {
                                                                                        com.example
                                                                                                .llama
                                                                                                .data
                                                                                                .BertModel
                                                                                                .Companion
                                                                                                .VocabStrategy
                                                                                                .SEPARATE_TXT ->
                                                                                                "ext.vocab"
                                                                                        com.example
                                                                                                .llama
                                                                                                .data
                                                                                                .BertModel
                                                                                                .Companion
                                                                                                .VocabStrategy
                                                                                                .EMBEDDED_METADATA ->
                                                                                                "emb.vocab"
                                                                                        else ->
                                                                                                "bundled"
                                                                                }
                                                                        text =
                                                                                "📦 ${model.displayName} (${sizeMb}MB, $vocabInfo)"
                                                                        layoutParams =
                                                                                android.widget
                                                                                        .LinearLayout
                                                                                        .LayoutParams(
                                                                                                0,
                                                                                                android.widget
                                                                                                        .LinearLayout
                                                                                                        .LayoutParams
                                                                                                        .WRAP_CONTENT,
                                                                                                1f
                                                                                        )
                                                                }
                                                val btnDelete =
                                                        com.google.android.material.button
                                                                .MaterialButton(
                                                                        this@SettingsActivity,
                                                                        null,
                                                                        com.google
                                                                                .android
                                                                                .material
                                                                                .R
                                                                                .attr
                                                                                .materialButtonOutlinedStyle
                                                                )
                                                                .apply {
                                                                        text = "🗑️"
                                                                        setOnClickListener {
                                                                                lifecycleScope
                                                                                        .launch {
                                                                                                withContext(
                                                                                                        Dispatchers
                                                                                                                .IO
                                                                                                ) {
                                                                                                        // Smazat i navázaný vocab záznam
                                                                                                        val vocabRecord =
                                                                                                                bertRepository
                                                                                                                        .getVocabForModel(
                                                                                                                                model.id
                                                                                                                        )
                                                                                                        vocabRecord
                                                                                                                ?.let {
                                                                                                                        bertRepository
                                                                                                                                .delete(
                                                                                                                                        it
                                                                                                                                )
                                                                                                                }
                                                                                                        bertRepository
                                                                                                                .delete(
                                                                                                                        model
                                                                                                                )
                                                                                                }
                                                                                                withContext(
                                                                                                        Dispatchers
                                                                                                                .Main
                                                                                                ) {
                                                                                                        Toast.makeText(
                                                                                                                        this@SettingsActivity,
                                                                                                                        "🗑️ Model smazán",
                                                                                                                        Toast.LENGTH_SHORT
                                                                                                                )
                                                                                                                .show()
                                                                                                        refreshBertModels()
                                                                                                }
                                                                                        }
                                                                        }
                                                                }
                                                row.addView(tv)
                                                row.addView(btnDelete)
                                                container?.addView(row)
                                        }
                                }
                        } catch (e: Exception) {
                                Log.e("SettingsActivity", "BERT model refresh selhal", e)
                        }
                }
        }

        /**
         * handleBertModelImport — Krok 1 importu: zkopíruje .tflite soubor do interního úložiště.
         *
         * 1. Zkopíruje soubor přes ContentResolver do filesDir/bert_models/
         * 2. Spustí TfliteVocabDetector na zkopírovaném souboru
         * 3. Vytvoří BertModel záznam v DB se správnou vocabStrategy
         * 4. Pokud detekce = REQUIRED → spustí importBertVocabLauncher (krok 2)
         * 5. Pokud detekce = NOT_REQUIRED → import je hotov
         *
         * @param uri SAF Uri vybraného souboru
         * @param modelSource Konstanta z BertModel.ModelSource (huggingface / kaggle / ...)
         */
        private fun handleBertModelImport(uri: android.net.Uri, modelSource: String) {
                lifecycleScope.launch {
                        try {
                                // Krok 1: Zkopírovat soubor do interního úložiště
                                val destDir =
                                        java.io.File(filesDir, "bert_models").also { it.mkdirs() }
                                val displayName =
                                        contentResolver.query(
                                                        uri,
                                                        arrayOf(
                                                                android.provider.OpenableColumns
                                                                        .DISPLAY_NAME
                                                        ),
                                                        null,
                                                        null,
                                                        null
                                                )
                                                ?.use { cursor ->
                                                        if (cursor.moveToFirst())
                                                                cursor.getString(0)
                                                        else null
                                                }
                                                ?: "bert_model_${System.currentTimeMillis()}.tflite"

                                val destFile = java.io.File(destDir, displayName)

                                withContext(Dispatchers.IO) {
                                        contentResolver.openInputStream(uri)?.use { input ->
                                                destFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                }
                                        }
                                                ?: throw IllegalStateException(
                                                        "Nelze otevřít Uri: $uri"
                                                )
                                }

                                // Krok 2: Auto-detekce vocab požadavku
                                val vocabReq =
                                        withContext(Dispatchers.IO) {
                                                com.example.llama.agent.perception
                                                        .TfliteVocabDetector.detectFromFile(
                                                        destFile.absolutePath
                                                )
                                        }
                                val vocabStrategy =
                                        com.example.llama.agent.perception.TfliteVocabDetector
                                                .toVocabStrategy(vocabReq, modelSource)

                                Log.i(
                                        "SettingsActivity",
                                        "BERT import: $displayName, vocabReq=$vocabReq, strategy=$vocabStrategy"
                                )

                                // Krok 3: Uložit model záznam do DB
                                val modelRecord =
                                        com.example.llama.data.BertModel(
                                                displayName = displayName.removeSuffix(".tflite"),
                                                sourceUri = destFile.absolutePath,
                                                fileSize = destFile.length(),
                                                isVocabFile = false,
                                                modelSource = modelSource,
                                                vocabStrategy = vocabStrategy,
                                                linkedModelId = null
                                        )
                                withContext(Dispatchers.IO) { bertRepository.insert(modelRecord) }

                                withContext(Dispatchers.Main) {
                                        refreshBertModels()

                                        when (vocabReq) {
                                                com.example.llama.agent.perception
                                                        .TfliteVocabDetector.VocabRequirement
                                                        .REQUIRED -> {
                                                        // Krok 4: Model vyžaduje external vocab —
                                                        // spustit krok 2 wizard
                                                        pendingBertModelId = modelRecord.id
                                                        androidx.appcompat.app.AlertDialog.Builder(
                                                                        this@SettingsActivity
                                                                )
                                                                .setTitle("📚 Přidat vocab soubor?")
                                                                .setMessage(
                                                                        "Model '${modelRecord.displayName}' vyžaduje slovník (vocab.txt / tokenizer.json)." +
                                                                                "\n\nBez vocab souboru bude použit vestavěný Google BERT slovník jako fallback." +
                                                                                "\n\nChcete nyní vybrat vocab soubor?"
                                                                )
                                                                .setPositiveButton(
                                                                        "✅ Vybrat vocab"
                                                                ) { _, _ ->
                                                                        importBertVocabLauncher
                                                                                .launch(
                                                                                        arrayOf(
                                                                                                "*/*"
                                                                                        )
                                                                                )
                                                                }
                                                                .setNegativeButton(
                                                                        "⏭ Přeskočit (použít bundled)"
                                                                ) { _, _ ->
                                                                        pendingBertModelId = null
                                                                        Toast.makeText(
                                                                                        this@SettingsActivity,
                                                                                        "✅ Model importován — používá se bundled vocab",
                                                                                        Toast.LENGTH_SHORT
                                                                                )
                                                                                .show()
                                                                }
                                                                .show()
                                                }
                                                com.example.llama.agent.perception
                                                        .TfliteVocabDetector.VocabRequirement
                                                        .NOT_REQUIRED -> {
                                                        // Model má embedded vocab — import hotov
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "✅ Model importován — vocab je embedded v modelu",
                                                                        Toast.LENGTH_SHORT
                                                                )
                                                                .show()
                                                }
                                                else -> {
                                                        // UNKNOWN — import hotov s bundled fallback
                                                        Toast.makeText(
                                                                        this@SettingsActivity,
                                                                        "✅ Model importován — vocab strategie: bundled fallback",
                                                                        Toast.LENGTH_LONG
                                                                )
                                                                .show()
                                                }
                                        }
                                }
                        } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                        Log.e("SettingsActivity", "BERT model import selhal", e)
                                        Toast.makeText(
                                                        this@SettingsActivity,
                                                        "❌ Import selhal: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                }
                        }
                }
        }

        /**
         * handleBertVocabImport — Krok 2 importu: zkopíruje vocab.txt / tokenizer.json.
         *
         * Vytvoří BertModel záznam s isVocabFile=true a linkedModelId=parentModelId. Aktualizuje
         * parent model record: vocabStrategy = SEPARATE_TXT.
         *
         * @param uri SAF Uri vybraného vocab souboru
         * @param parentModelId ID BertModel záznamu modelu (.tflite) čekajícího na vocab
         */
        private fun handleBertVocabImport(uri: android.net.Uri, parentModelId: String) {
                lifecycleScope.launch {
                        try {
                                val destDir =
                                        java.io.File(filesDir, "bert_models").also { it.mkdirs() }
                                val displayName =
                                        contentResolver.query(
                                                        uri,
                                                        arrayOf(
                                                                android.provider.OpenableColumns
                                                                        .DISPLAY_NAME
                                                        ),
                                                        null,
                                                        null,
                                                        null
                                                )
                                                ?.use { cursor ->
                                                        if (cursor.moveToFirst())
                                                                cursor.getString(0)
                                                        else null
                                                }
                                                ?: "bert_vocab_${System.currentTimeMillis()}.txt"

                                val destFile = java.io.File(destDir, displayName)

                                withContext(Dispatchers.IO) {
                                        contentResolver.openInputStream(uri)?.use { input ->
                                                destFile.outputStream().use { output ->
                                                        input.copyTo(output)
                                                }
                                        }
                                                ?: throw IllegalStateException(
                                                        "Nelze otevřít Uri: $uri"
                                                )
                                }

                                // Uložit vocab záznam navázaný na parent model
                                val vocabRecord =
                                        com.example.llama.data.BertModel(
                                                displayName = displayName,
                                                sourceUri = destFile.absolutePath,
                                                fileSize = destFile.length(),
                                                isVocabFile = true,
                                                modelSource =
                                                        com.example.llama.data.BertModel.Companion
                                                                .ModelSource.CUSTOM,
                                                vocabStrategy =
                                                        com.example.llama.data.BertModel.Companion
                                                                .VocabStrategy.SEPARATE_TXT,
                                                linkedModelId = parentModelId
                                        )
                                withContext(Dispatchers.IO) { bertRepository.insert(vocabRecord) }

                                pendingBertModelId = null

                                withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                                        this@SettingsActivity,
                                                        "✅ Vocab soubor importován: $displayName",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                        refreshBertModels()
                                }
                        } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                        Log.e("SettingsActivity", "BERT vocab import selhal", e)
                                        Toast.makeText(
                                                        this@SettingsActivity,
                                                        "❌ Vocab import selhal: ${e.message}",
                                                        Toast.LENGTH_LONG
                                                )
                                                .show()
                                }
                        }
                }
        }

        private fun checkAICoreStatus() {
                lifecycleScope.launch {
                        try {
                                val isAvailable =
                                        com.example.llama.agent.perception.NlpTextProcessor
                                                .isAICoreAvailable(this@SettingsActivity)
                                withContext(Dispatchers.Main) {
                                        if (isAvailable) {
                                                tvNlpAicoreStatus.text =
                                                        "✅ AICore (Gemini Nano) dostupný"
                                                tvNlpAicoreStatus.setTextColor(
                                                        android.graphics.Color.parseColor("#4CAF50")
                                                )

                                                // Detekce verze - v roce 2026 je Gemini Nano 2
                                                // (e2/e4) standardem
                                                try {
                                                        val pInfo =
                                                                packageManager.getPackageInfo(
                                                                        "com.google.android.aicore",
                                                                        0
                                                                )
                                                        val version = pInfo.versionName
                                                        tvNlpAicoreVersion.text =
                                                                "Verze: $version | Model: ${if (version?.contains("e4") == true) "Gemma 4 e4 (Pro)" else "Gemma 4 e2"}"
                                                        tvNlpAicoreVersion.visibility =
                                                                android.view.View.VISIBLE
                                                } catch (_: Exception) {
                                                        tvNlpAicoreVersion.text =
                                                                "Verze: Detekována (Legacy)"
                                                }
                                        } else {
                                                tvNlpAicoreStatus.text =
                                                        "❌ AICore nedostupný (použije se ML Kit fallback)"
                                                tvNlpAicoreStatus.setTextColor(
                                                        android.graphics.Color.parseColor("#FF9800")
                                                )
                                                tvNlpAicoreVersion.visibility =
                                                        android.view.View.GONE
                                        }
                                }
                        } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                        tvNlpAicoreStatus.text = "⚠️ Detekce AICore selhala"
                                        tvNlpAicoreStatus.setTextColor(
                                                android.graphics.Color.parseColor("#FF9800")
                                        )
                                }
                        }
                }
        }

        private fun refreshNlpStatus() {
                lifecycleScope.launch {
                        val processor =
                                com.example.llama.agent.perception.NlpTextProcessor(
                                        this@SettingsActivity
                                )
                        val downloadedCodes = processor.getDownloadedLanguages()
                        val allLangs =
                                com.example.llama.agent.perception.NlpTextProcessor
                                        .getAvailableLanguages()

                        withContext(Dispatchers.Main) {
                                containerNlpDownloadedLangs.removeAllViews()

                                if (downloadedCodes.isEmpty()) {
                                        val emptyTv =
                                                android.widget.TextView(this@SettingsActivity)
                                                        .apply {
                                                                text = "Žádné stažené modely"
                                                                setTextColor(
                                                                        android.graphics.Color.GRAY
                                                                )
                                                                setPadding(16, 16, 16, 16)
                                                        }
                                        containerNlpDownloadedLangs.addView(emptyTv)
                                        tvNlpLangsStatus.text = "⚪ Připraveno"
                                } else {
                                        tvNlpLangsStatus.text =
                                                "✅ Počet modelů: ${downloadedCodes.size}"

                                        downloadedCodes.forEach { code ->
                                                val displayName = allLangs[code] ?: code
                                                val row =
                                                        android.widget.LinearLayout(
                                                                        this@SettingsActivity
                                                                )
                                                                .apply {
                                                                        orientation =
                                                                                android.widget
                                                                                        .LinearLayout
                                                                                        .HORIZONTAL
                                                                        setPadding(0, 8, 0, 8)
                                                                        gravity =
                                                                                android.view.Gravity
                                                                                        .CENTER_VERTICAL
                                                                }

                                                val nameTv =
                                                        android.widget.TextView(
                                                                        this@SettingsActivity
                                                                )
                                                                .apply {
                                                                        text =
                                                                                "• $displayName ($code)"
                                                                        layoutParams =
                                                                                android.widget
                                                                                        .LinearLayout
                                                                                        .LayoutParams(
                                                                                                0,
                                                                                                android.widget
                                                                                                        .LinearLayout
                                                                                                        .LayoutParams
                                                                                                        .WRAP_CONTENT,
                                                                                                1f
                                                                                        )
                                                                        setTextColor(
                                                                                android.graphics
                                                                                        .Color.WHITE
                                                                        )
                                                                        textSize = 15f
                                                                }

                                                val deleteBtn =
                                                        com.google.android.material.button
                                                                .MaterialButton(
                                                                        this@SettingsActivity,
                                                                        null,
                                                                        com.google
                                                                                .android
                                                                                .material
                                                                                .R
                                                                                .attr
                                                                                .materialIconButtonStyle
                                                                )
                                                                .apply {
                                                                        icon =
                                                                                androidx.core
                                                                                        .content
                                                                                        .ContextCompat
                                                                                        .getDrawable(
                                                                                                this@SettingsActivity,
                                                                                                android.R
                                                                                                        .drawable
                                                                                                        .ic_menu_delete
                                                                                        )
                                                                        setIconTintResource(
                                                                                android.R
                                                                                        .color
                                                                                        .holo_red_light
                                                                        )
                                                                        setOnClickListener {
                                                                                lifecycleScope
                                                                                        .launch {
                                                                                                if (processor
                                                                                                                .deleteLanguageModel(
                                                                                                                        code
                                                                                                                )
                                                                                                ) {
                                                                                                        android.widget
                                                                                                                .Toast
                                                                                                                .makeText(
                                                                                                                        this@SettingsActivity,
                                                                                                                        "🗑️ Smazáno: $displayName",
                                                                                                                        android.widget
                                                                                                                                .Toast
                                                                                                                                .LENGTH_SHORT
                                                                                                                )
                                                                                                                .show()
                                                                                                        refreshNlpStatus()
                                                                                                }
                                                                                        }
                                                                        }
                                                                }

                                                row.addView(nameTv)
                                                row.addView(deleteBtn)
                                                containerNlpDownloadedLangs.addView(row)
                                        }
                                }
                        }
                }
        }

        private fun downloadSelectedLanguage(code: String, display: String) {
                lifecycleScope.launch {
                        try {
                                withContext(Dispatchers.Main) {
                                        progressNlpDownload.visibility = android.view.View.VISIBLE
                                        progressNlpDownload.isIndeterminate = true
                                        btnNlpDownloadLang.isEnabled = false
                                        tvNlpLangsStatus.text = "⬇️ Stahování: $display..."
                                }

                                val processor =
                                        com.example.llama.agent.perception.NlpTextProcessor(
                                                this@SettingsActivity
                                        )
                                val success = processor.downloadLanguageModel(code)

                                withContext(Dispatchers.Main) {
                                        progressNlpDownload.visibility = android.view.View.GONE
                                        btnNlpDownloadLang.isEnabled = true

                                        if (success) {
                                                android.widget.Toast.makeText(
                                                                this@SettingsActivity,
                                                                "✅ Model $display byl úspěšně stažen a ověřen",
                                                                android.widget.Toast.LENGTH_LONG
                                                        )
                                                        .show()
                                                refreshNlpStatus()
                                        } else {
                                                tvNlpLangsStatus.text = "❌ Chyba stahování $display"
                                                android.widget.Toast.makeText(
                                                                this@SettingsActivity,
                                                                "❌ Stažení $display selhalo (zkontrolujte připojení)",
                                                                android.widget.Toast.LENGTH_LONG
                                                        )
                                                        .show()
                                        }
                                }
                        } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                        progressNlpDownload.visibility = android.view.View.GONE
                                        btnNlpDownloadLang.isEnabled = true
                                        tvNlpLangsStatus.text = "❌ Chyba: ${e.message}"
                                }
                        }
                }
        }

        // ==================== ZipVoice Recording & Playback ====================
        private var isRecordingPrompt = false
        private var promptAudioRecord: android.media.AudioRecord? = null
        private var promptMediaPlayer: android.media.MediaPlayer? = null
        private var promptRecordingThread: Thread? = null

        private fun setupZipVoiceListeners() {
                btnSherpaRecordPrompt10s.setOnClickListener { handleZipVoiceMicClick(10, btnSherpaRecordPrompt10s) }
                btnSherpaRecordPromptDyn.setOnClickListener { handleZipVoiceMicClick(180, btnSherpaRecordPromptDyn) }

                btnSherpaPlayPrompt.setOnClickListener {
                        val path = settingsManager.sherpaTtsCustomPromptPath
                        if (path.isEmpty() || !java.io.File(path).exists()) {
                                android.widget.Toast.makeText(
                                                this,
                                                "Záznam neexistuje",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                                return@setOnClickListener
                        }
                        try {
                                promptMediaPlayer?.release()
                                promptMediaPlayer =
                                        android.media.MediaPlayer().apply {
                                                setDataSource(path)
                                                prepare()
                                                start()
                                                setOnCompletionListener {
                                                        release()
                                                        promptMediaPlayer = null
                                                        tvSherpaPromptStatus.text =
                                                                "Stav: Přehrávání dokončeno"
                                                }
                                        }
                                tvSherpaPromptStatus.text = "Stav: Přehrávám..."
                        } catch (e: Exception) {
                                tvSherpaPromptStatus.text = "❌ Chyba přehrávání: ${e.message}"
                        }
                }

                btnSherpaClearPrompt.setOnClickListener {
                        val path = settingsManager.sherpaTtsCustomPromptPath
                        if (path.isNotEmpty() && java.io.File(path).exists()) {
                                java.io.File(path).delete()
                        }
                        settingsManager.sherpaTtsCustomPromptPath = ""
                        settingsManager.sherpaTtsCustomPromptText = ""
                        etSherpaZipvoicePromptText.setText("")
                        updateSherpaPromptStatus()
                        android.widget.Toast.makeText(
                                        this,
                                        "Vlastní prompt vymazán",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
                }

                btnSherpaTestClone.setOnClickListener {
                        val activeTtsModelId = settingsManager.sherpaTtsModelPath
                        if (activeTtsModelId.isEmpty()) {
                                android.widget.Toast.makeText(
                                                this,
                                                "Nejprve vyberte TTS model v seznamu výše.",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                                return@setOnClickListener
                        }

                        val userInputText = etSherpaTestText.text?.toString()?.trim()
                        val testText =
                                if (!userInputText.isNullOrEmpty()) {
                                        userInputText
                                } else {
                                        "Ahoj, toto je zkouška. Já jsem tvůj nový klonovaný hlas."
                                }

                        tvSherpaPromptStatus.text = "Stav: Generuji testovací klon (počkejte...)"
                        btnSherpaTestClone.isEnabled = false

                        Thread {
                                        try {
                                                val engine =
                                                        com.example.llama.agent.voice.sherpa
                                                                .SherpaTtsEngine(
                                                                        this@SettingsActivity,
                                                                        settingsManager
                                                                )
                                                engine.initialize()

                                                engine.speak(
                                                        text = testText,
                                                        interrupt = true,
                                                        onStart = {
                                                                runOnUiThread {
                                                                        tvSherpaPromptStatus.text =
                                                                                "Stav: Přehrávám vygenerovaný klon..."
                                                                }
                                                        },
                                                        onDone = {
                                                                runOnUiThread {
                                                                        btnSherpaTestClone
                                                                                .isEnabled = true
                                                                        tvSherpaPromptStatus.text =
                                                                                "Stav: Přehrávání dokončeno."
                                                                        engine.shutdown()
                                                                }
                                                        }
                                                )
                                        } catch (e: Exception) {
                                                runOnUiThread {
                                                        btnSherpaTestClone.isEnabled = true
                                                        tvSherpaPromptStatus.text =
                                                                "❌ Chyba: ${e.message}"
                                                }
                                        }
                                }
                                .start()
                }
        }

        private fun updateSherpaPromptStatus() {
                val path = settingsManager.sherpaTtsCustomPromptPath
                if (path.isNotEmpty() && java.io.File(path).exists()) {
                        tvSherpaPromptStatus.text = "Stav: Vlastní prompt aktivní"
                        tvSherpaPromptStatus.setTextColor(
                                android.graphics.Color.parseColor("#00E676")
                        )
                } else {
                        tvSherpaPromptStatus.text = "Stav: Výchozí prompt.wav z modelu"
                        tvSherpaPromptStatus.setTextColor(android.graphics.Color.DKGRAY)
                }
        }

        private var activeRecordingButton: com.google.android.material.button.MaterialButton? = null
        private var activeRecordingLimitSec: Int = 10

        private fun handleZipVoiceMicClick(limitSec: Int, btn: com.google.android.material.button.MaterialButton) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
                        return
                }
                if (isRecordingPrompt) {
                        stopRecordingPrompt()
                } else {
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

                        if (promptAudioRecord?.state != android.media.AudioRecord.STATE_INITIALIZED
                        ) {
                                android.widget.Toast.makeText(
                                                this,
                                                "Chyba inicializace mikrofonu",
                                                android.widget.Toast.LENGTH_SHORT
                                        )
                                        .show()
                                return
                        }

                        promptAudioRecord?.startRecording()
                        isRecordingPrompt = true
                        
                        btnSherpaRecordPrompt10s.isEnabled = activeRecordingButton == btnSherpaRecordPrompt10s
                        btnSherpaRecordPromptDyn.isEnabled = activeRecordingButton == btnSherpaRecordPromptDyn
                        activeRecordingButton?.text = "🔴 STOP"
                        
                        tvSherpaPromptStatus.text = "Stav: Nahrávám (max ${activeRecordingLimitSec}s)..."

                        promptRecordingThread = Thread { writeAudioDataToFile(sampleRate) }
                        promptRecordingThread?.start()

                        lifecycleScope.launch {
                                kotlinx.coroutines.delay(activeRecordingLimitSec * 1000L)
                                if (isRecordingPrompt) {
                                        withContext(Dispatchers.Main) {
                                                stopRecordingPrompt()
                                                android.widget.Toast.makeText(
                                                                this@SettingsActivity,
                                                                "Dosažen limit ${activeRecordingLimitSec}s",
                                                                android.widget.Toast.LENGTH_SHORT
                                                        )
                                                        .show()
                                        }
                                }
                        }
                } catch (e: SecurityException) {
                        android.widget.Toast.makeText(
                                        this,
                                        "Chybí oprávnění mikrofonu",
                                        android.widget.Toast.LENGTH_SHORT
                                )
                                .show()
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

                btnSherpaRecordPrompt10s.isEnabled = true
                btnSherpaRecordPromptDyn.isEnabled = true
                btnSherpaRecordPrompt10s.text = "Mic 10s"
                btnSherpaRecordPromptDyn.text = "Mic 3m"

                val path = java.io.File(filesDir, "sherpa_custom_prompt.wav").absolutePath
                settingsManager.sherpaTtsCustomPromptPath = path
                updateSherpaPromptStatus()
        }

        private fun writeAudioDataToFile(sampleRate: Int) {
                val data = ByteArray(2048)
                val file = java.io.File(filesDir, "sherpa_custom_prompt.wav")
                var os: java.io.FileOutputStream? = null
                try {
                        os = java.io.FileOutputStream(file)
                        // Write dummy header
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

                        // Overwrite with real header
                        val randAccess = java.io.RandomAccessFile(file, "rw")
                        randAccess.seek(0)
                        writeWavHeader(
                                randAccess,
                                totalAudioLen,
                                totalAudioLen + 36,
                                sampleRate.toLong(),
                                1,
                                (sampleRate * 2).toLong()
                        )
                        randAccess.close()
                } catch (e: Exception) {
                        e.printStackTrace()
                }
        }

        private fun writeWavHeader(
                out: java.io.RandomAccessFile,
                totalAudioLen: Long,
                totalDataLen: Long,
                longSampleRate: Long,
                channels: Int,
                byteRate: Long
        ) {
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
                header[22] = channels.toByte()
                header[23] = 0
                header[24] = (longSampleRate and 0xff).toByte()
                header[25] = (longSampleRate shr 8 and 0xff).toByte()
                header[26] = (longSampleRate shr 16 and 0xff).toByte()
                header[27] = (longSampleRate shr 24 and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte()
                header[29] = (byteRate shr 8 and 0xff).toByte()
                header[30] = (byteRate shr 16 and 0xff).toByte()
                header[31] = (byteRate shr 24 and 0xff).toByte()
                header[32] = (channels * 16 / 8).toByte()
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
                out.write(header, 0, 44)
        }

        private fun renderWebEnginesPriorityList() {
            val container = findViewById<LinearLayout>(R.id.container_web_engines_priority) ?: return
            container.removeAllViews()
            val labels = mapOf(
                "duckduckgo" to "🦆 DuckDuckGo (Lite HTML & Instant API)",
                "wikipedia" to "📚 Wikipedie (Čeština & Angličtina)",
                "seznam" to "🇨🇿 Seznam.cz (České vyhledávání)",
                "brave" to "🦁 Brave Search (Nezávislý web index)",
                "google" to "🔍 Google Search Lite",
                "searxng" to "🌐 SearXNG Federated Meta-Search",
                "science" to "🔬 Vědecké zdroje (OpenAlex, arXiv, Semantic Scholar)",
                "imported_db" to "🗂️ Vlastní & Importované weby (site: / JSON)"
            )
            val ordered = settingsManager.webSearchEnginesOrder.toMutableList()
            val dp = resources.displayMetrics.density

            ordered.forEachIndexed { index, engineId ->
                val isEnabled = settingsManager.isWebEngineEnabled(engineId)
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                    setBackgroundColor(if (isEnabled) Color.parseColor("#1E293B") else Color.parseColor("#141414"))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = (6 * dp).toInt() }
                }

                val tvRank = TextView(this).apply {
                    text = "#${index + 1}"
                    setTextColor(if (isEnabled) Color.parseColor("#29B6F6") else Color.GRAY)
                    textSize = 13f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams((32 * dp).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                row.addView(tvRank)

                val cb = MaterialCheckBox(this).apply {
                    text = labels[engineId] ?: engineId
                    isChecked = isEnabled
                    setTextColor(if (isEnabled) Color.WHITE else Color.GRAY)
                    textSize = 12.5f
                    buttonTintList = ColorStateList.valueOf(Color.parseColor("#29B6F6"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnCheckedChangeListener { _, checked ->
                        settingsManager.setWebEngineEnabled(engineId, checked)
                        when (engineId) {
                            "duckduckgo" -> if (::cbWebEnableDuckDuckGo.isInitialized) cbWebEnableDuckDuckGo.isChecked = checked
                            "wikipedia" -> if (::cbWebEnableWikipedia.isInitialized) cbWebEnableWikipedia.isChecked = checked
                            "science" -> if (::cbWebEnableScience.isInitialized) cbWebEnableScience.isChecked = checked
                            "imported_db" -> if (::cbWebEnableImportedDb.isInitialized) cbWebEnableImportedDb.isChecked = checked
                        }
                        renderWebEnginesPriorityList()
                        updateWebImportStatusText()
                    }
                }
                row.addView(cb)

                val btnUp = MaterialButton(this).apply {
                    text = "▲"
                    textSize = 11f
                    this.isEnabled = index > 0
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minHeight = 0
                    setBackgroundColor(Color.parseColor("#263238"))
                    setTextColor(if (index > 0) Color.WHITE else Color.DKGRAY)
                    layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (34 * dp).toInt()).apply {
                        marginStart = (4 * dp).toInt()
                    }
                    setOnClickListener {
                        if (index > 0) {
                            val list = settingsManager.webSearchEnginesOrder.toMutableList()
                            val item = list.removeAt(index)
                            list.add(index - 1, item)
                            settingsManager.webSearchEnginesOrder = list
                            settingsManager.webPrimarySearchEngine = list.firstOrNull { settingsManager.isWebEngineEnabled(it) } ?: list.first()
                            renderWebEnginesPriorityList()
                            updateWebImportStatusText()
                        }
                    }
                }
                row.addView(btnUp)

                val btnDown = MaterialButton(this).apply {
                    text = "▼"
                    textSize = 11f
                    this.isEnabled = index < ordered.size - 1
                    setPadding(0, 0, 0, 0)
                    minWidth = 0
                    minHeight = 0
                    setBackgroundColor(Color.parseColor("#263238"))
                    setTextColor(if (index < ordered.size - 1) Color.WHITE else Color.DKGRAY)
                    layoutParams = LinearLayout.LayoutParams((36 * dp).toInt(), (34 * dp).toInt()).apply {
                        marginStart = (4 * dp).toInt()
                    }
                    setOnClickListener {
                        if (index < ordered.size - 1) {
                            val list = settingsManager.webSearchEnginesOrder.toMutableList()
                            val item = list.removeAt(index)
                            list.add(index + 1, item)
                            settingsManager.webSearchEnginesOrder = list
                            settingsManager.webPrimarySearchEngine = list.firstOrNull { settingsManager.isWebEngineEnabled(it) } ?: list.first()
                            renderWebEnginesPriorityList()
                            updateWebImportStatusText()
                        }
                    }
                }
                row.addView(btnDown)

                container.addView(row)
            }
        }

        private fun updateWebImportStatusText() {
            if (!::tvWebImportStatus.isInitialized) return
            val readCount = WebAccessRuleManager.getRules(UrlAccessType.READ).size
            val execCount = WebAccessRuleManager.getRules(UrlAccessType.EXECUTE).size
            val activeOrder = settingsManager.webSearchEnginesOrder.filter { settingsManager.isWebEngineEnabled(it) }
            val orderText = if (activeOrder.isEmpty()) "Žádný" else activeOrder.joinToString(" → ") { it.uppercase() }
            val (webPagesCount, userChunksCount) = runCatching {
                com.example.llama.agent.tools.workspace.WebArchiveSqliteFts5.getInstance(applicationContext).getArchiveStatsSummary()
            }.getOrDefault(Pair(0, 0))
            val botText = if (settingsManager.webAutonomousBotDeepRead) "Kotlin Bot ZAP" else "Kotlin Bot VYP"
            tvWebImportStatus.text = "Pořadí: $orderText\nSQLite RAG: $webPagesCount webů | $userChunksCount dokumentů | Pravidla: $readCount Read / $execCount Exec | $botText"
        }

        private fun LegacyUnusedUpdateWebImportStatusText() {
            if (!::tvWebImportStatus.isInitialized) return
            val readCount = WebAccessRuleManager.getRules(UrlAccessType.READ).size
            val execCount = WebAccessRuleManager.getRules(UrlAccessType.EXECUTE).size
            val ftsStatus = if (settingsManager.webEnableSqliteFts5) "FTS5 Aktivní" else "FTS5 Vypnuto"
            val engine = settingsManager.webPrimarySearchEngine.uppercase()
            tvWebImportStatus.text = "Stav pravidel: $readCount Read | $execCount Execute | Engine: $engine | $ftsStatus"
        }

        private fun showWebImportDialog() {
            var currentTab = UrlAccessType.READ

            val dialogView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 24, 32, 16)
            }

            // 1. Přepínač záložek: [ Read URLs (Čtení) ] vs [ Execute URLs (Akce/Kliky) ]
            val tabLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 16 }
            }

            val btnTabRead = MaterialButton(this).apply {
                text = "📖 Read URLs"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                strokeColor = ColorStateList.valueOf(Color.parseColor("#29B6F6"))
            }

            val btnTabExecute = MaterialButton(this).apply {
                text = "⚡ Execute URLs"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 12
                }
                strokeColor = ColorStateList.valueOf(Color.parseColor("#00E676"))
            }

            tabLayout.addView(btnTabRead)
            tabLayout.addView(btnTabExecute)
            dialogView.addView(tabLayout)

            // Informační nápis
            val tvTabInfo = TextView(this).apply {
                text = "Pravidla pro prohlížení webu a čtení obsahu (ALLOW = povoleno, BLOCK = blokováno):"
                setTextColor(Color.LTGRAY)
                textSize = 12f
                setPadding(0, 0, 0, 12)
            }
            dialogView.addView(tvTabInfo)

            // 2. Seznam pravidel (ScrollView s kontejnerem pro dynamické řádky)
            val scrollView = ScrollView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (260 * resources.displayMetrics.density).toInt()
                )
            }
            val rulesContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            scrollView.addView(rulesContainer)
            dialogView.addView(scrollView)

            fun refreshRuleRows() {
                rulesContainer.removeAllViews()
                val rules = WebAccessRuleManager.getRules(currentTab)

                if (rules.isEmpty()) {
                    val emptyTv = TextView(this).apply {
                        text = "Žádná pravidla pro tento typ (ve výchozím stavu povoleno vše)."
                        setTextColor(Color.GRAY)
                        textSize = 13f
                        setPadding(16, 24, 16, 24)
                    }
                    rulesContainer.addView(emptyTv)
                    return
                }

                for (rule in rules) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 8, 8, 8)
                        setBackgroundColor(Color.parseColor("#1AFFFFFF"))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = 8 }
                    }

                    // Přepínač politiky [ALLOW / BLOCK]
                    val btnPolicy = MaterialButton(this).apply {
                        val isAllow = rule.policy == UrlAccessPolicy.ALLOW
                        text = if (isAllow) "ALLOW" else "BLOCK"
                        textSize = 11f
                        setPadding(12, 4, 12, 4)
                        setBackgroundColor(if (isAllow) Color.parseColor("#2E7D32") else Color.parseColor("#C62828"))
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(
                            (85 * resources.displayMetrics.density).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setOnClickListener {
                            val newPolicy = if (rule.policy == UrlAccessPolicy.ALLOW) UrlAccessPolicy.BLOCK else UrlAccessPolicy.ALLOW
                            WebAccessRuleManager.updateRule(rule.id, rule.pattern, newPolicy)
                            refreshRuleRows()
                            updateWebImportStatusText()
                        }
                    }
                    row.addView(btnPolicy)

                    // Doména / URL text
                    val tvPattern = TextView(this).apply {
                        text = rule.pattern
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = 16
                            marginEnd = 8
                        }
                    }
                    row.addView(tvPattern)

                    // ✏️ Edit tlačítko
                    val btnEdit = MaterialButton(this).apply {
                        text = "✏️"
                        textSize = 12f
                        setPadding(8, 4, 8, 4)
                        setBackgroundColor(Color.parseColor("#37474F"))
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(
                            (48 * resources.displayMetrics.density).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        setOnClickListener {
                            val editInput = EditText(this@SettingsActivity).apply {
                                setText(rule.pattern)
                                setTextColor(Color.WHITE)
                                setPadding(24, 16, 24, 16)
                            }
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(this@SettingsActivity)
                                .setTitle("Upravit URL / doménu")
                                .setView(editInput)
                                .setPositiveButton("Uložit") { _, _ ->
                                    val newText = editInput.text?.toString().orEmpty().trim()
                                    if (newText.isNotBlank()) {
                                        WebAccessRuleManager.updateRule(rule.id, newText, rule.policy)
                                        refreshRuleRows()
                                        updateWebImportStatusText()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    row.addView(btnEdit)

                    // ❌ Smazat tlačítko
                    val btnDelete = MaterialButton(this).apply {
                        text = "✕"
                        textSize = 12f
                        setPadding(8, 4, 8, 4)
                        setBackgroundColor(Color.parseColor("#4A154B"))
                        setTextColor(Color.parseColor("#FF5252"))
                        layoutParams = LinearLayout.LayoutParams(
                            (44 * resources.displayMetrics.density).toInt(),
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { marginStart = 6 }
                        setOnClickListener {
                            WebAccessRuleManager.deleteRule(rule.id)
                            refreshRuleRows()
                            updateWebImportStatusText()
                        }
                    }
                    row.addView(btnDelete)

                    rulesContainer.addView(row)
                }
            }

            fun updateTabStyles() {
                if (currentTab == UrlAccessType.READ) {
                    btnTabRead.setBackgroundColor(Color.parseColor("#0288D1"))
                    btnTabRead.setTextColor(Color.WHITE)
                    btnTabExecute.setBackgroundColor(Color.TRANSPARENT)
                    btnTabExecute.setTextColor(Color.parseColor("#00E676"))
                    tvTabInfo.text = "Pravidla pro prohlížení webu a čtení obsahu (READ URLs):"
                } else {
                    btnTabRead.setBackgroundColor(Color.TRANSPARENT)
                    btnTabRead.setTextColor(Color.parseColor("#29B6F6"))
                    btnTabExecute.setBackgroundColor(Color.parseColor("#2E7D32"))
                    btnTabExecute.setTextColor(Color.WHITE)
                    tvTabInfo.text = "Pravidla pro interakce, kliky bota a spouštění akcí (EXECUTE URLs):"
                }
                refreshRuleRows()
            }

            btnTabRead.setOnClickListener {
                currentTab = UrlAccessType.READ
                updateTabStyles()
            }
            btnTabExecute.setOnClickListener {
                currentTab = UrlAccessType.EXECUTE
                updateTabStyles()
            }

            // 3. Řádek pro rychlé přidání nové domény/URL
            val addLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            }

            val spinnerPolicy = Spinner(this).apply {
                val polAdapter = ArrayAdapter(
                    this@SettingsActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("ALLOW", "BLOCK")
                )
                adapter = polAdapter
                layoutParams = LinearLayout.LayoutParams(
                    (95 * resources.displayMetrics.density).toInt(),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            addLayout.addView(spinnerPolicy)

            val etNewPattern = EditText(this).apply {
                hint = "domena.cz nebo URL"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 8
                    marginEnd = 8
                }
            }
            addLayout.addView(etNewPattern)

            val btnAdd = MaterialButton(this).apply {
                text = "+ Přidat"
                textSize = 12f
                setBackgroundColor(Color.parseColor("#0288D1"))
                setOnClickListener {
                    val inputStr = etNewPattern.text?.toString().orEmpty().trim()
                    if (inputStr.isNotBlank()) {
                        val pol = if (spinnerPolicy.selectedItemPosition == 1) UrlAccessPolicy.BLOCK else UrlAccessPolicy.ALLOW
                        WebAccessRuleManager.addRule(currentTab, inputStr, pol)
                        etNewPattern.text.clear()
                        refreshRuleRows()
                        updateWebImportStatusText()
                    }
                }
            }
            addLayout.addView(btnAdd)
            dialogView.addView(addLayout)

            updateTabStyles()

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("🌐 Správa URL & Bezpečnostních pravidel")
                .setView(dialogView)
                .setPositiveButton("📥 Importovat TXT") { _, _ ->
                    showImportTxtDialog(currentTab) {
                        refreshRuleRows()
                        updateWebImportStatusText()
                    }
                }
                .setNeutralButton("📤 Exportovat") { _, _ ->
                    val exported = WebAccessRuleManager.exportToText(currentTab)
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("URL Rules", exported))
                    Toast.makeText(this, "Pravidla pro $currentTab zkopírována do schránky", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Zavřít", null)
                .show()
        }

        private fun showImportTxtDialog(type: UrlAccessType, onDone: () -> Unit) {
            val input = EditText(this).apply {
                hint = "Vložte řádky ve formátu:\nroot.cz\nallow:lupa.cz\nblock:reklama.cz"
                minLines = 6
                setPadding(32, 24, 32, 24)
                setTextColor(Color.WHITE)
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("📥 Importovat pravidla pro $type z TXT")
                .setMessage("Zadejte domény nebo URL adresy oddělené novým řádkem. Můžete uvést prefix 'allow:' nebo 'block:':")
                .setView(input)
                .setPositiveButton("Načíst do seznamu") { _, _ ->
                    val raw = input.text?.toString().orEmpty()
                    if (raw.isNotBlank()) {
                        val imported = WebAccessRuleManager.importFromRawText(type, raw)
                        Toast.makeText(this, "Naimportováno $imported pravidel do $type", Toast.LENGTH_SHORT).show()
                        onDone()
                    }
                }
                .setNeutralButton("Vymazat pravidla pro $type") { _, _ ->
                    WebAccessRuleManager.clearRules(type)
                    Toast.makeText(this, "Pravidla pro $type byla vymazána", Toast.LENGTH_SHORT).show()
                    onDone()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        private fun showRagManagementDialog() {
                val options = arrayOf(
                        "📂 Vybrat externí složku dokumentů (SAF Tree)",
                        "📄 Vybrat jednotlivé soubory (SAF Files)",
                        "🔄 Přeindexovat interní sandbox souborů",
                        "🗑️ Vymazat index dokumentů (User RAG)"
                )
                com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Správa Uživatelského RAG")
                        .setItems(options) { _, which ->
                                when (which) {
                                        0 -> ragFolderPickerLauncher.launch(null)
                                        1 -> ragFilesPickerLauncher.launch(arrayOf(
                                                "text/*", "application/json", "application/xml", "*/*"
                                        ))
                                        2 -> reindexUserWorkspaceRag()
                                        3 -> {
                                                lifecycleScope.launch(Dispatchers.IO) {
                                                        val fts = com.example.llama.agent.tools.workspace.WebArchiveSqliteFts5.getInstance(applicationContext)
                                                        val cleared = fts.clearAllUserDocuments()
                                                        withContext(Dispatchers.Main) {
                                                                tvWebImportStatus.text = "User RAG: Vymazán index ($cleared dokumentů)"
                                                                Toast.makeText(this@SettingsActivity, "RAG index vymazán", Toast.LENGTH_SHORT).show()
                                                        }
                                                }
                                        }
                                }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
        }

        private fun reindexUserWorkspaceRag() {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val fts = com.example.llama.agent.tools.workspace.WebArchiveSqliteFts5.getInstance(applicationContext)
                    val sandboxDir = com.example.llama.agent.tools.workspace.FileWorkspaceTool.getWorkspaceDir(applicationContext)
                    val count = fts.reindexWorkspaceFolder(sandboxDir)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Přeindexováno $count dokumentů v User RAG", Toast.LENGTH_SHORT).show()
                        tvWebImportStatus.text = "User RAG: Přeindexováno $count dokumentů v sandboxu"
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SettingsActivity, "Chyba reindexace: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
}

