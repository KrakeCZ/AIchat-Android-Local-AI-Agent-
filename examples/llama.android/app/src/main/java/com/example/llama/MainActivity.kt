package com.example.llama

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import com.arm.aichat.isUninterruptible
import com.example.llama.data.AiModel
import com.example.llama.infrastructure.voice.AndroidVoiceManager
import com.example.llama.managers.ChatSessionManager
import com.example.llama.managers.FileSystemManager
import com.example.llama.managers.LlamaServiceManager
import com.example.llama.managers.ModelMetadataManager
import com.example.llama.managers.PermissionManager
import com.example.llama.managers.ThemeManager
import com.example.llama.agent.vision.VisionSourceManager
import com.example.llama.service.GenerationState
import com.example.llama.service.LlamaGenerationService
import com.example.llama.service.LlamaNotificationHelper
import com.example.llama.ui.ModelInfoDialogFragment
import com.example.llama.ui.dialogs.DialogManager
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private val viewModel: MainViewModel by viewModels()
    private lateinit var voiceManager: AndroidVoiceManager
    private lateinit var voiceInputManager: com.example.llama.agent.voice.AgentVoiceSTT
    private lateinit var ggufTv: TextView
    private lateinit var messagesRv: RecyclerView
    private lateinit var userInputEt: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var speedTv: TextView
    private lateinit var tokenCountTv: TextView
    private lateinit var genTimeTv: TextView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var mainContentContainer: ConstraintLayout
    private lateinit var engine: InferenceEngine
    private var generationJob: Job? = null
    private val messages = mutableListOf<Message>()
    private lateinit var messageAdapter: MessageAdapter
    private lateinit var historyManager: ChatHistoryManager
    private lateinit var settingsManager: SettingsManager
    private lateinit var fileManager: FileSystemManager
    private lateinit var sessionManager: ChatSessionManager
    private lateinit var permissionManager: PermissionManager
    private var currentModelPath: String? = null
    private lateinit var serviceManager: LlamaServiceManager
    private var isFirstAgentEmit = true
    // Pending image flag: Když je true, bypass Agent routing a jdi přímo do LLM chat
    // (C++ vrstva má g_cached_bmp a auto-prepend <__media__> marker)
    @Volatile private var hasPendingImage = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        drawerLayout = findViewById(R.id.drawer_layout)
        ViewCompat.setOnApplyWindowInsetsListener(drawerLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(
                    left = systemBars.left,
                    right = systemBars.right,
                    bottom = systemBars.bottom
            )
            insets
        }

        historyManager = ChatHistoryManager(this)
        settingsManager = SettingsManager(this)
        fileManager = FileSystemManager(this)
        permissionManager = PermissionManager(this)

        // CRITICAL: Inicializovat audio orchestrátor pro chat TTS
        // (Idempotentní — bezpečné volat i pokud již inicializován z AccessibilityService)
        com.example.llama.agent.core.audio.AgentAudioOrchestrator.initialize(applicationContext)

        voiceManager = AndroidVoiceManager(this, lifecycleScope)
        lifecycle.addObserver(voiceManager)

        // INTEGRACE: Standard Chat Dictation
        voiceInputManager = com.example.llama.agent.voice.AgentVoiceSTT(this)
        // Race Condition Fix: Do NOT initialize here if Agent might be using it.
        // Initialization happens lazily in Observer or explicitly later.

        messageAdapter =
                MessageAdapter(messages, this) { text ->
                    if (settingsManager.isTtsEnabled) {
                        val currentState = voiceManager.state.value
                        if (currentState is com.example.llama.domain.voice.VoiceState.Speaking &&
                                        currentState.text == text
                        ) {
                            voiceManager.stopSpeaking()
                        } else {
                            voiceManager.speak(text)
                        }
                    } else {
                        Toast.makeText(this, "TTS is disabled in settings", Toast.LENGTH_SHORT)
                                .show()
                    }
                }
        messageAdapter.setHasStableIds(true)

        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.layoutManager = layoutManager
        messagesRv.itemAnimator = null // CRITICAL for performance
        messagesRv.adapter = messageAdapter
        userInputEt = findViewById(R.id.user_input)
        sendButton = findViewById(R.id.send_button)
        speedTv = findViewById(R.id.tv_speed)
        tokenCountTv = findViewById(R.id.tv_token_count)
        genTimeTv = findViewById(R.id.tv_gen_time)
        navView = findViewById(R.id.nav_view)
        toolbar = findViewById(R.id.toolbar)
        mainContentContainer = findViewById(R.id.main_content)
        sessionManager =
                ChatSessionManager(
                        activity = this,
                        adapter = messageAdapter,
                        messages = messages,
                        historyManager = historyManager,
                        settingsManager = settingsManager,
                        drawerLayout = drawerLayout,
                        navView = navView
                )
        sessionManager.onSettingsClicked = { openSettings() }
        sessionManager.onModelManagementClicked = { showModelSelector() }

        setupToolbar()
        sessionManager.refreshNavigationMenu()
        setupInputListeners()
        setupVoiceInputObserver() // Moved here to ensure Views are initialized
        setupVoiceObserver() // Moved here
        setupViewModelObservers()
        permissionManager.checkAndRequestNotificationPermission()
        ggufTv.setOnClickListener { handleModelInfoClick() }
        sendButton.setOnClickListener { handleSendButtonClick() }
        
        setupMultiModalCallbacks()

        serviceManager = LlamaServiceManager(this, lifecycleScope)
        val runBackground = settingsManager.backgroundGeneration
        serviceManager.connect(asResident = runBackground)

        lifecycleScope.launch {
            serviceManager.generationState.collect { state -> handleGenerationState(state) }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                engine = AiChat.getInferenceEngine(applicationContext)
                withContext(Dispatchers.Main) {
                    setupEngineStateObserver()
                    setupRobustOnBackPressed()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine init failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                                    this@MainActivity,
                                    "Engine Init Failed: ${e.message}",
                                    Toast.LENGTH_LONG
                            )
                            .show()
                }
            }
        }
        updateSendButtonState()
    }

    private fun setupMultiModalCallbacks() {
        // Zpracování textových souborů (klasické chování)
        fileManager.onTextAttachedCallback = { textToAppend ->
            val currentText = userInputEt.text.toString()
            userInputEt.setText("$currentText$textToAppend")
            userInputEt.setSelection(userInputEt.text.length)
        }

        // Zpracování statických obrázků do kontextu
        fileManager.onImageAttachedCallback = { imageBytes ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val compressed = com.example.llama.agent.vision.VisionSourceManager.processAndCompressImageBlocking(imageBytes)
                    if (compressed != null) {
                        engine.processImage(compressed)
                        hasPendingImage = true // Signalizace pro handleUserInput()

                        // ============ MEDIA PREVIEW (podmíněné) ============
                        // Thumbnail se vytváří JEN pokud je preview zapnutý.
                        // inSampleSize=4 → ~4x menší Bitmap → nízká RAM zátěž.
                        val thumbnail = if (settingsManager.isMediaPreviewEnabled) {
                            try {
                                val opts = android.graphics.BitmapFactory.Options().apply {
                                    inSampleSize = 4 // Downscale: 4000x3000 → 1000x750
                                    inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 // 16-bit = 50% RAM vs ARGB_8888
                                }
                                android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, opts)
                            } catch (e: Exception) {
                                Log.w(TAG, "Thumbnail decode failed (non-critical)", e)
                                null
                            }
                        } else null

                        withContext(Dispatchers.Main) {
                            // Přidat user zprávu s náhledem do chatu
                            val msg = Message(
                                id = java.util.UUID.randomUUID().toString(),
                                contentBuilder = java.lang.StringBuilder("[📷 Obrázek přijat — zeptej se 'Co je na obrázku?']"),
                                isUser = true,
                                attachmentBitmap = thumbnail,
                                attachmentType = if (thumbnail != null) AttachmentType.IMAGE else AttachmentType.NONE
                            )
                            messages.add(msg)
                            messageAdapter.notifyItemInserted(messages.size - 1)
                            messagesRv.scrollToPosition(messages.size - 1)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Nepodařilo se dekódovat obrázek", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Chyba Image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Zpracování videa (extrakce klíčového snímku pro LLM, plný stream v plánu)
        fileManager.onVideoAttachedCallback = { videoUri ->
            Log.i(TAG, "Video attached: $videoUri")
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(this@MainActivity, videoUri)
                    // Vytáhnu první reprezentativní snímek z 1. sekundy
                    val frameBitmap = retriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()

                    if (frameBitmap != null) {
                        val compressed = com.example.llama.agent.vision.VisionSourceManager().processAndCompressImageBlocking(frameBitmap, recycleSource = true)
                        if (compressed != null) {
                            engine.processImage(compressed)
                            hasPendingImage = true // Signalizace pro handleUserInput() (Video Bypass)

                            // ============ VIDEO THUMBNAIL (podmíněné) ============
                            val thumbnail = if (settingsManager.isMediaPreviewEnabled) {
                                try {
                                    // Nový retriever pro thumbnail (originální bitmap byl recyklován uvnitř VisionSourceMngr)
                                    val thumbRetriever = android.media.MediaMetadataRetriever()
                                    thumbRetriever.setDataSource(this@MainActivity, videoUri)
                                    val thumb = thumbRetriever.getFrameAtTime(1000000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    thumbRetriever.release()
                                    // Downscale pro šetření RAM
                                    if (thumb != null) {
                                        val scaled = android.graphics.Bitmap.createScaledBitmap(thumb, 300, 200, true)
                                        if (scaled !== thumb) thumb.recycle()
                                        scaled
                                    } else null
                                } catch (e: Exception) {
                                    Log.w(TAG, "Video thumbnail failed (non-critical)", e)
                                    null
                                }
                            } else null

                            withContext(Dispatchers.Main) {
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contentBuilder = java.lang.StringBuilder("[🎬 Video frame přijat — zeptej se 'Analyzuj video']"),
                                    isUser = true,
                                    attachmentBitmap = thumbnail,
                                    attachmentType = if (thumbnail != null) AttachmentType.VIDEO else AttachmentType.NONE
                                )
                                messages.add(msg)
                                messageAdapter.notifyItemInserted(messages.size - 1)
                                messagesRv.scrollToPosition(messages.size - 1)
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Nelze získat náhled videa", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Video extraction failed: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Chyba u videa: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Zpracování audia — mtmd automaticky detekuje formát přes magic bytes (wav/mp3/flac)
        fileManager.onAudioAttachedCallback = { audioBytes ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    if (::engine.isInitialized && engine.state.value.isModelLoaded) {
                        // mtmd_helper_bitmap_init_from_buf rozpozná audio přes magic bytes
                        // a vytvoří audio mel spectrogram automaticky
                        val success = engine.processImage(audioBytes)
                        if (success) hasPendingImage = true // Signalizace pro handleUserInput() (Audio Bypass)
                        withContext(Dispatchers.Main) {
                            if (success) {
                                val msg = Message(
                                    id = java.util.UUID.randomUUID().toString(),
                                    contentBuilder = java.lang.StringBuilder("[🎵 Audio přijato — zeptej se 'Co je řečeno v nahrávce?']"),
                                    isUser = true,
                                    attachmentType = AttachmentType.AUDIO
                                    // Audio nemá bitmap thumbnail — AttachmentType.AUDIO zapne audio formát bubliny
                                )
                                messages.add(msg)
                                messageAdapter.notifyItemInserted(messages.size - 1)
                                messagesRv.scrollToPosition(messages.size - 1)
                            } else {
                                Toast.makeText(this@MainActivity,
                                    "Audio processing selhal \u2014 model nemusí podporovat audio vstup.",
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity,
                                "Model není načten", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Audio processing failed", e)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity,
                            "Chyba Audio: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // --- SERVICE STATE HANDLER OPTIMIZED ---
    private fun handleGenerationState(state: GenerationState) {
        when (state) {
            is GenerationState.Idle -> {
                updateSendButtonState()
            }
            is GenerationState.Standby -> {
                updateSendButtonState()
            }
            is GenerationState.Generating -> {
                // 1. APPEND MODE (Fastest)
                if (state.token.isNotEmpty()) {
                    messageAdapter.appendTokenToLastMessage(state.token)
                }

                // 2. DESYNC CHECK (Optimized)
                // Don't sync every token for massive text. Sync only if significant drift detected.
                val lastMsg = messages.lastOrNull()
                val currentLength = lastMsg?.length ?: 0
                val serviceLength = state.partialResponse.length

                // Drift > 10 chars is suspicious (packet loss/race condition), then force sync.
                // Otherwise trust the append.
                if (abs(currentLength - serviceLength) > 10) {
                    Log.w(
                            TAG,
                            "Sync drift detected: Local=$currentLength, Service=$serviceLength. Forcing update."
                    )
                    messageAdapter.updateLastMessageContent(state.partialResponse)
                }

                if (!settingsManager.disableAutoScroll) {
                    // Smart Autoscroll: Scroll only if near bottom
                    if (!messagesRv.canScrollVertically(1) && messages.isNotEmpty()) {
                        messagesRv.post {
                            val count = messageAdapter.itemCount
                            if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                        }
                    }
                }

                // Update Stats
                if (settingsManager.showStats) {
                    speedTv.text = getString(R.string.info_speed_format, state.tokensPerSecond)
                }
                if (settingsManager.showTokenCount) {
                    tokenCountTv.text = getString(R.string.stats_tokens_format, state.tokenCount)
                }
                if (settingsManager.showGenTime) {
                    genTimeTv.text = getString(R.string.stats_time_format, state.elapsedTime)
                }
                updateSendButtonState()
            }
            is GenerationState.Complete -> {
                // Final hard sync to ensure markdown processing
                if (messages.isNotEmpty() && !messages.last().isUser) {
                    messageAdapter.setMessageStreaming(messages.last().id, false)
                }
                messageAdapter.updateLastMessageContent(state.fullText)
                sessionManager.saveCurrentState()
                updateSendButtonState()
            }
            is GenerationState.Error -> {
                Toast.makeText(this, "Generation Error: ${state.message}", Toast.LENGTH_LONG).show()
                updateSendButtonState()
            }
        }
    }

    private fun setupVoiceObserver() {
        lifecycleScope.launch {
            voiceManager.state.collect { state ->
                val speakingText =
                        if (state is com.example.llama.domain.voice.VoiceState.Speaking) state.text
                        else null
                messageAdapter.updateSpeakingState(speakingText)

                if (state is com.example.llama.domain.voice.VoiceState.Error) {
                    val msg = state.message
                    if (state.isCritical) {
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    } else {
                        Log.w("VoiceManager", msg)
                    }
                }
            }
        }
    }

    private fun setupVoiceInputObserver() {
        lifecycleScope.launch {
            voiceInputManager.state.collect { state ->
                when (state) {
                    is com.example.llama.agent.voice.AgentVoiceSTT.STTState.Listening -> {
                        userInputEt.hint = "Poslouchám..."
                    }
                    is com.example.llama.agent.voice.AgentVoiceSTT.STTState.Result -> {
                        val currentText = userInputEt.text.toString()
                        val newText =
                                if (currentText.isEmpty()) state.text
                                else "$currentText ${state.text}"
                        userInputEt.setText(newText)
                        userInputEt.setSelection(userInputEt.text.length)
                        userInputEt.hint = null
                    }
                    is com.example.llama.agent.voice.AgentVoiceSTT.STTState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_SHORT).show()
                        userInputEt.hint = null
                    }
                    else -> {
                        if (userInputEt.hint == "Poslouchám...") userInputEt.hint = null
                    }
                }
            }
        }
    }

    private fun handleModelInfoClick() {
        if (currentModelPath == null) {
            Toast.makeText(this, "No model loaded", Toast.LENGTH_SHORT).show()
            return
        }
        val currentModels = viewModel.savedModels.value
        val model = currentModels.find { it.sourceUri == currentModelPath }
        if (model != null) {
            viewModel.showModelInfo(model.id)
        } else {
            Toast.makeText(this, "Model info not available in DB", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupRobustOnBackPressed() {
        onBackPressedDispatcher.addCallback(this) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else if (::engine.isInitialized && engine.state.value.isUninterruptible) {
                Toast.makeText(this@MainActivity, R.string.msg_wait_operation, Toast.LENGTH_SHORT)
                        .show()
            } else {
                Log.w(TAG, "Back press ignored to prevent accidental exit.")
            }
        }
    }

    private fun setupEngineStateObserver() {
        lifecycleScope.launch {
            if (!::engine.isInitialized) return@launch
            engine.state.collect { state ->
                val isModelLoaded = state.isModelLoaded
                userInputEt.isEnabled = isModelLoaded
                updateSendButtonState()

                if (state is InferenceEngine.State.Error) {
                    val errorMsg = getString(R.string.status_error_prefix, state.exception.message)
                    ggufTv.text = errorMsg
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupViewModelObservers() {
        lifecycleScope.launch {
            viewModel.modelStatus.collect { status ->
                if (status.startsWith("Error") || status.startsWith("Importing")) {
                    if (!status.startsWith("Info Error")) {
                        ggufTv.text = status
                    } else {
                        Toast.makeText(this@MainActivity, status, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                if (loading) {
                    userInputEt.hint = getString(R.string.status_importing)
                    userInputEt.isEnabled = false
                } else {
                    userInputEt.hint = null
                    if (::engine.isInitialized && engine.state.value.isModelLoaded) {
                        userInputEt.isEnabled = true
                    }
                    updateSendButtonState()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.modelDetail.collect { detail ->
                val tag = "model_info_dialog"
                if (supportFragmentManager.findFragmentByTag(tag) == null) {
                    ModelInfoDialogFragment.newInstance(detail).show(supportFragmentManager, tag)
                }
            }
        }

        lifecycleScope.launch {
            viewModel.savedModels.collect { models ->
                Log.d(TAG, "Database updated. Available models: ${models.size}")
            }
        }

        lifecycleScope.launch {
            viewModel.isAgentEnabled.collect { isEnabled ->
                try {
                    // Basic UI Update - Safe Call
                    updateToolbarAgentIcon(isEnabled)

                    if (isFirstAgentEmit) {
                        isFirstAgentEmit = false
                        // Initial state sync (silent)
                    } else {
                        // User Action / State Change
                        val msg = if (isEnabled) "Agent zapnut" else "Agent vypnut"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        applyDisplaySettings() // Update bottom bar mic visibility

                        // Lazy Load / Unload Agent Modules
                        if (isEnabled) {
                            // INTEGRACE: Auto-start Open Mic if enabled
                            if (settingsManager.agentVoiceMode == SettingsManager.VOICEMODE_OPEN_MIC
                            ) {
                                // Check permission again just in case (though we checked before
                                // toggle)
                                if (permissionManager.hasAudioPermission()) {
                                    // voiceInputManager.startContinuousListening()
                                    Log.i(
                                            "MainActivity",
                                            "Agent enabled - delegating Open Mic to Service"
                                    )
                                    Toast.makeText(
                                                    this@MainActivity,
                                                    "Open Mic aktivován (Service)",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                            }

                            // INTEGRACE: Immediate Permission Check on Enable
                            checkStartupPermissions()
                        } else {
                            if (::voiceInputManager.isInitialized) {
                                voiceInputManager.shutdown()
                            }
                        }

                        // Notify Service to refresh its state/notification
                        if (::serviceManager.isInitialized && serviceManager.isServiceConnected()) {
                            try {
                                val intent =
                                        Intent(
                                                        this@MainActivity,
                                                        LlamaGenerationService::class.java
                                                )
                                                .apply {
                                                    action =
                                                            LlamaNotificationHelper
                                                                    .ACTION_START_RESIDENT
                                                }
                                startService(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to start service intent", e)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Error in Agent State Observer", e)
                    // Don't crash app, just log
                }
            }
        }

        lifecycleScope.launch {
            com.example.llama.agent.perception.PerceptionEventBus.events.collect { event ->
                if (event is com.example.llama.agent.perception.UiActionEvent && event.action == com.example.llama.agent.perception.UiAction.TOGGLE_FULLSCREEN_BOXES) {
                    val newState = !settingsManager.isSmartVisionBoundingBoxEnabled
                    settingsManager.isSmartVisionBoundingBoxEnabled = newState
                    updateToolbarSmartVisionIcon(newState)
                    val msg = if (newState) "SmartVision ZAPNUT" else "SmartVision VYPNUT"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var isReturningFromSettings = false

    override fun onResume() {
        super.onResume()
        applyDisplaySettings()
        messageAdapter.refreshMarkwon()
        updateSendButtonState()

        if (!settingsManager.alwaysShowSidebar && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }

        // CRITICAL FIX (2026-02-17):
        // Re-evaluate background/foreground nastavení po návratu ze Settings.
        // Dříve toto maskovalo HyperCallService (InCallService) s vysokou OOM_ADJ prioritou.
        // Po jejím odstranění je NUTNÉ explicitně (re)startovat foreground službu.
        val shouldBeResident = settingsManager.backgroundGeneration
        if (shouldBeResident) {
            // Zajistit, že služba běží jako Foreground (pokud ještě neběží)
            val intent =
                    android.content.Intent(
                                    this,
                                    com.example.llama.service.LlamaGenerationService::class.java
                            )
                            .apply {
                                action =
                                        com.example.llama.service.LlamaNotificationHelper
                                                .ACTION_START_RESIDENT
                            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } else {
            // Background OFF → zastavit foreground režim (služba zůstane pro binding, ale bez
            // priority)
            try {
                val stopIntent =
                        android.content.Intent(
                                        this,
                                        com.example.llama.service.LlamaGenerationService::class.java
                                )
                                .apply {
                                    action =
                                            com.example.llama.service.LlamaNotificationHelper
                                                    .ACTION_STOP_RESIDENT
                                }
                startService(stopIntent)
            } catch (e: Exception) {
                // Služba nemusí běžet — ignorovat
            }
        }

        // INTEGRACE: Startup Checks (Accessibility & Battery)
        // checkStartupPermissions() // DISABLED per user request
    }

    private fun checkStartupPermissions() {
        // 1. Check Accessibility Service
        val isServiceEnabled =
                com.example.llama.agent.core.AgentHelper.isAccessibilityServiceEnabled(this)

        if (viewModel.isAgentEnabled.value) {
            if (!isServiceEnabled) {
                speakAndOpenSettings(
                        "Potřebuji povolení pro usnadnění přístupu, otevírám nastavení.",
                        com.example.llama.agent.core.AgentHelper.getAccessibilitySettingsIntent()
                )
                return // Prioritize one at a time
            }
        }
    }

    private var lastSpokenTime = 0L
    private fun speakAndOpenSettings(text: String, intent: Intent) {
        // Debounce to prevent loops
        val now = System.currentTimeMillis()
        if (now - lastSpokenTime < 3000) return
        lastSpokenTime = now

        Toast.makeText(this, text, Toast.LENGTH_LONG).show()

        if (settingsManager.isTtsEnabled) {
            voiceManager.speak(text)
        }

        // Delay intent slightly to let TTS start
        lifecycleScope.launch {
            kotlinx.coroutines.delay(2000)
            try {
                isReturningFromSettings = true // SET FLAG
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open settings", e)
            }
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        toolbar.inflateMenu(R.menu.toolbar_menu)
        // Sync initial state of Agent Icon
        updateToolbarAgentIcon(viewModel.isAgentEnabled.value)
        updateToolbarIrisIcon(settingsManager.isIrisMotionEnabled)
        updateToolbarSmartVisionIcon(settingsManager.isSmartVisionBoundingBoxEnabled)
        updateToolbarWorkersState()

        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_agent -> {
                    try {
                        // Prevent crash: Check dependencies before enabling
                        if (viewModel.isAgentEnabled.value) {
                            viewModel.toggleAgentMode() // Turning OFF - safe
                        } else {
                            // Turning ON - Check Runtime Permissions (Audio, Call, SMS...)
                            permissionManager.checkAndRequestAgentPermissions { granted ->
                                try {
                                    if (granted) {
                                        // 2. Check System Settings Permission (Write Settings) -
                                        // Category C
                                        if (!permissionManager.hasWriteSettingsPermission()) {
                                            permissionManager.requestWriteSettingsPermission()
                                            // Non-blocking, but user needs to grant it for
                                            // brightness/rotation
                                        }

                                        // 3. Check Accessibility Service - Category A/General
                                        val isServiceEnabled =
                                                com.example.llama.agent.core.AgentHelper
                                                        .isAccessibilityServiceEnabled(
                                                                this@MainActivity
                                                        )
                                        if (!isServiceEnabled) {
                                            Toast.makeText(
                                                            this@MainActivity,
                                                            "Pro plnou funkčnost povolte Agenta v Usnadnění (Accessibility).",
                                                            Toast.LENGTH_LONG
                                                    )
                                                    .show()
                                            // Optional: Open Accessibility Settings directly?
                                            // startActivity(com.example.llama.agent.core.AgentHelper.getAccessibilitySettingsIntent())
                                        }

                                        viewModel.toggleAgentMode()
                                    } else {
                                        Toast.makeText(
                                                        this@MainActivity,
                                                        "Agent vyžaduje oprávnění (Mikrofon, Kontakty) pro plnou funkčnost.",
                                                        Toast.LENGTH_SHORT
                                                )
                                                .show()
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Error in permission callback", e)
                                    Toast.makeText(
                                                    this@MainActivity,
                                                    "Chyba při inicializaci agenta: ${e.message}",
                                                    Toast.LENGTH_SHORT
                                            )
                                            .show()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error processing agent toggle", e)
                        Toast.makeText(this@MainActivity, "Chyba: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    true
                }
                R.id.action_smart_vision -> {
                    toggleSmartVision()
                    true
                }
                R.id.action_iris_motion -> {
                    toggleIrisMotion()
                    true
                }
                R.id.action_load_model -> {
                    showModelSelector()
                    true
                }
                R.id.action_eject_model -> {
                    handleEjectModel()
                    true
                }
                R.id.action_attach_file -> {
                    if (::engine.isInitialized && engine.state.value.isModelLoaded) {
                        fileManager.pickMultiModalAsset()
                    } else {
                        Toast.makeText(this, R.string.user_input_hint_no_model, Toast.LENGTH_SHORT)
                                .show()
                    }
                    true
                }
                R.id.action_export -> {
                    showExportSelector()
                    true
                }
                R.id.action_delete -> {
                    sessionManager.confirmDeleteCurrentSession()
                    true
                }
                R.id.action_toggle_vision -> {
                    settingsManager.isPerceptionEnabled = !settingsManager.isPerceptionEnabled
                    updateToolbarWorkersState()
                    true
                }
                R.id.action_toggle_audio -> {
                    settingsManager.isAudioPerceptionEnabled = !settingsManager.isAudioPerceptionEnabled
                    updateToolbarWorkersState()
                    true
                }
                R.id.action_toggle_ocr -> {
                    settingsManager.isOcrEnabled = !settingsManager.isOcrEnabled
                    updateToolbarWorkersState()
                    true
                }
                R.id.action_toggle_summarization -> {
                    settingsManager.isSummarizationEnabled = !settingsManager.isSummarizationEnabled
                    updateToolbarWorkersState()
                    true
                }
                R.id.action_toggle_transcript -> {
                    settingsManager.isTranscriptEnabled = !settingsManager.isTranscriptEnabled
                    updateToolbarWorkersState()
                    true
                }
                else -> false
            }
        }
    }

    private fun updateToolbarWorkersState() {
        val menu = toolbar.menu ?: return
        val visionItem = menu.findItem(R.id.action_toggle_vision)
        val audioItem = menu.findItem(R.id.action_toggle_audio)
        val ocrItem = menu.findItem(R.id.action_toggle_ocr)
        val sumItem = menu.findItem(R.id.action_toggle_summarization)
        val transItem = menu.findItem(R.id.action_toggle_transcript)
        
        visionItem?.title = if (settingsManager.isPerceptionEnabled) "Vision: ON" else "Vision: OFF"
        audioItem?.title = if (settingsManager.isAudioPerceptionEnabled) "Audio: ON" else "Audio: OFF"
        ocrItem?.title = if (settingsManager.isOcrEnabled) "OCR: ON" else "OCR: OFF"
        sumItem?.title = if (settingsManager.isSummarizationEnabled) "Sum: ON" else "Sum: OFF"
        transItem?.title = if (settingsManager.isTranscriptEnabled) "Titulky: ON" else "Titulky: OFF"
    }

    private fun updateToolbarAgentIcon(isEnabled: Boolean) {
        try {
            val menu = toolbar.menu
            val item = menu.findItem(R.id.action_agent)
            if (item != null) {
                if (isEnabled) {
                    item.setIcon(R.drawable.ic_smart_toy_24)
                    item.title = "Agent (ON)"
                    // Visual indication: Full Opacity or Tint
                    item.icon?.alpha = 255
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary,
                            typedValue,
                            true
                    )
                    item.icon?.setTint(typedValue.data)
                } else {
                    item.setIcon(R.drawable.ic_smart_toy_24)
                    item.title = "Agent (OFF)"
                    // Visual indication: Dimmed or Gray
                    item.icon?.alpha = 120
                    item.icon?.setTintList(null) // Remove tint to show original/gray
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating toolbar icon", e)
        }
    }

    private fun toggleIrisMotion() {
        val newState = !settingsManager.isIrisMotionEnabled

        if (newState) {
            permissionManager.checkAndRequestCameraPermission { granted ->
                if (granted) {
                    settingsManager.isIrisMotionEnabled = true
                    updateToolbarIrisIcon(true)
                    Toast.makeText(this, "IrisMotion ZAPNUT", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Oprávnění kamery zamítnuto", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            settingsManager.isIrisMotionEnabled = false
            updateToolbarIrisIcon(false)
            Toast.makeText(this, "IrisMotion VYPNUT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSmartVision() {
        val newState = !settingsManager.isSmartVisionBoundingBoxEnabled

        if (newState) {
            // Check pro Accessibility Service / SYSTEM_ALERT_WINDOW by mohl být tu
            settingsManager.isSmartVisionBoundingBoxEnabled = true
            updateToolbarSmartVisionIcon(true)
            Toast.makeText(this, "SmartVision ZAPNUT", Toast.LENGTH_SHORT).show()
        } else {
            settingsManager.isSmartVisionBoundingBoxEnabled = false
            updateToolbarSmartVisionIcon(false)
            Toast.makeText(this, "SmartVision VYPNUT", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateToolbarSmartVisionIcon(isEnabled: Boolean) {
        try {
            val menu = toolbar.menu
            val item = menu.findItem(R.id.action_smart_vision)
            if (item != null) {
                if (isEnabled) {
                    item.title = "SmartVision (ON)"
                    item.icon?.alpha = 255
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary,
                            typedValue,
                            true
                    )
                    item.icon?.setTint(typedValue.data)
                } else {
                    item.title = "SmartVision (OFF)"
                    item.icon?.alpha = 120
                    item.icon?.setTintList(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating SmartVision icon", e)
        }
    }


    private fun updateToolbarIrisIcon(isEnabled: Boolean) {
        try {
            val menu = toolbar.menu
            val item = menu.findItem(R.id.action_iris_motion)
            if (item != null) {
                if (isEnabled) {
                    item.title = "Iris (ON)"
                    item.icon?.alpha = 255
                    val typedValue = android.util.TypedValue()
                    theme.resolveAttribute(
                            com.google.android.material.R.attr.colorPrimary,
                            typedValue,
                            true
                    )
                    item.icon?.setTint(typedValue.data)
                } else {
                    item.title = "Iris (OFF)"
                    item.icon?.alpha = 120
                    item.icon?.setTintList(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating iris toolbar icon", e)
        }
    }

    private fun showModelSelector() {
        val models = viewModel.savedModels.value

        DialogManager.showModelSelector(
                context = this,
                models = models,
                onModelSelect = { selectedModel -> 
                    if (selectedModel.displayName.contains("mmproj", ignoreCase = true)) {
                        Toast.makeText(this, "Toto je Vision projektor (\uD83D\uDC41\uFE0F mmproj).\nNelze ho načíst jako hlavní textový model. Zvolte ho v Nastavení -> SmartVision.", Toast.LENGTH_LONG).show()
                    } else {
                        handleLoadLocalModel(selectedModel) 
                    }
                },
                onImportClick = { fileManager.pickModel { uri -> handleSelectedModelImport(uri) } },
                onDeleteClick = { showDeleteModelConfirm(models) }
        )
    }

    private fun showDeleteModelConfirm(models: List<AiModel>) {
        DialogManager.showDeleteModelConfirm(
                context = this,
                models = models,
                onConfirmDelete = { modelToDelete ->
                    viewModel.deleteModel(modelToDelete)
                    Toast.makeText(this, R.string.msg_model_deleted, Toast.LENGTH_SHORT).show()
                }
        )
    }

    private fun showExportSelector() {
        if (messages.isEmpty()) {
            Toast.makeText(this, "Chat is empty", Toast.LENGTH_SHORT).show()
            return
        }

        DialogManager.showExportFormatSelector(this) { format -> exportChat(format) }
    }

    private fun exportChat(format: ChatHistoryManager.ExportFormat) {
        lifecycleScope.launch {
            fileManager.exportAndShare(
                    historyManager,
                    sessionManager.currentSessionId,
                    messages,
                    format
            )
        }
    }

    private fun handleLoadLocalModel(model: AiModel) {
        if (!::engine.isInitialized) {
            Toast.makeText(this, "Engine is initializing, please wait...", Toast.LENGTH_SHORT)
                    .show()
            return
        }
        ggufTv.text = getString(R.string.status_loading_named_model, model.displayName)
        viewModel.loadLocalModel(
                modelId = model.id,
                onPathReady = { nativePath ->
                    lifecycleScope.launch { loadModelToEngine(nativePath) }
                },
                onError = { error ->
                    Toast.makeText(
                                    this,
                                    getString(R.string.status_error_prefix, error),
                                    Toast.LENGTH_LONG
                            )
                            .show()
                    ggufTv.text = getString(R.string.status_error_prefix, error)
                }
        )
    }

    private suspend fun loadModelToEngine(procPath: String) =
            withContext(Dispatchers.IO) {
                currentModelPath = procPath

                withContext(Dispatchers.Main) {
                    userInputEt.hint = getString(R.string.info_loading_model)
                    ggufTv.text = "Reading metadata..."
                }
                Log.i(TAG, "Loading model from: $procPath")

                withContext(Dispatchers.Main) {
                    ModelMetadataManager.displayMetadata(procPath, ggufTv)
                }

                // ===================== MULTIMODAL: Database / Settings Managed mmproj =====================
                var discoveredProjectorPath: String? = null
                val visionModelId = settingsManager.activeVisionModelId
                
                if (settingsManager.isNativeVisionModel) {
                    Log.i(TAG, "\uD83D\uDD0D All-In-One Vision Mode: Using main model path as projector ($procPath)")
                    discoveredProjectorPath = procPath
                } else if (visionModelId.isNotEmpty()) {
                    try {
                        val db = com.example.llama.data.LlamaDatabase.getDatabase(applicationContext)
                        val visionModel = db.aiModelDao().getModelById(visionModelId)
                        if (visionModel != null && java.io.File(visionModel.sourceUri).exists()) {
                            discoveredProjectorPath = visionModel.sourceUri
                            Log.i(TAG, "\uD83D\uDD0D Database-selected vision projector: $discoveredProjectorPath")
                        } else {
                            Log.w(TAG, "\u26A0\uFE0F Selected vision model not found on disk or DB")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to load vision model from DB", e)
                    }
                }
                
                if (discoveredProjectorPath == null) {
                    Log.i(TAG, "\u2139\uFE0F No mmproj selected or found \u2192 text-only mode")
                }

                val useMmap = settingsManager.useMmap
                val kvCacheMode = settingsManager.kvCacheMode
                val nCtx = settingsManager.contextSize
                val nBatch = settingsManager.batchSize
                val nUBatch = settingsManager.ubatchSize
                val flashAttn = settingsManager.flashAttention
                try {
                    if (!::engine.isInitialized) {
                        throw IllegalStateException("Engine not initialized")
                    }
                    val config = com.example.llama.agent.core.EngineConfiguration(
                        modelPath = procPath,
                        projectorPath = discoveredProjectorPath,
                        nCtx = nCtx,
                        useMmap = useMmap,
                        nBatch = nBatch,
                        nUBatch = nUBatch,
                        flashAttn = flashAttn,
                        kvCacheMode = kvCacheMode
                    )
                    com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                        context = applicationContext,
                        owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                        config = config
                    ) { orchestratorEngine ->
                        // Orchestrator automatically handles cleanUp and loadModel based on config changes.
                        // Nothing extra to do here.
                    }
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = null
                        if (messages.isEmpty()) sessionManager.startNewSession()
                        val successMsg = if (discoveredProjectorPath != null) {
                            getString(R.string.msg_load_success) + " \uD83D\uDC41\uFE0F Multimodal"
                        } else {
                            getString(R.string.msg_load_success)
                        }
                        Toast.makeText(
                                        this@MainActivity,
                                        successMsg,
                                        Toast.LENGTH_SHORT
                                )
                                .show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "CRITICAL: Engine Load Failed", e)
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = null
                        if (!ggufTv.text.contains("Metadata") && !ggufTv.text.contains("GGUF")) {
                            ggufTv.text = getString(R.string.status_error_prefix, e.message)
                        }
                    }
                }
            }

    private fun setupInputListeners() {
        userInputEt.addTextChangedListener(
                object : TextWatcher {
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {
                        updateSendButtonState()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                }
        )
    }

    private fun updateSendButtonState() {
        val isAgentActive = settingsManager.isAgentEnabled
        val inputEmpty = userInputEt.text.isNullOrEmpty()

        // Agent mode: send button funguje i bez engine (Tier 1/2 nepotřebují LLM)
        if (!::engine.isInitialized) {
            sendButton.setImageResource(R.drawable.outline_send_24)
            sendButton.isEnabled = isAgentActive && !inputEmpty
            return
        }

        val isGeneratingViaService =
                serviceManager.generationState.value is GenerationState.Generating
        val isGeneratingInteractive = generationJob?.isActive == true
        val isGenerating = isGeneratingViaService || isGeneratingInteractive

        val isModelLoaded = engine.state.value.isModelLoaded

        if (isGenerating) {
            sendButton.setImageResource(R.drawable.ic_stop_24)
            sendButton.isEnabled = true
        } else {
            sendButton.setImageResource(R.drawable.outline_send_24)
            sendButton.isEnabled = (isModelLoaded || isAgentActive) && !inputEmpty
        }
    }

    private fun handleUserInput() {
        // PENDING IMAGE BYPASS: Pokud uživatel uploadnul obrázek, bypass Agent
        // a pošli prompt přímo do LLM chat pipeline. C++ vrstva má g_cached_bmp
        // a auto-prepend <__media__> marker zajistí, že model obrázek uvidí.
        if (hasPendingImage) {
            Log.i(TAG, "📷 Pending image detected — bypassing Agent, routing to LLM chat pipeline")
            hasPendingImage = false
            // Padáme do standardního LLM chat (agent je ignorován)
        } else if (settingsManager.isAgentEnabled) {
            // AGENT ROUTING: Pokud je agent zapnutý a NENÍ pending image
            val userMsg = userInputEt.text.toString().trim()
            if (userMsg.isEmpty()) {
                Toast.makeText(this, getString(R.string.info_input_empty), Toast.LENGTH_SHORT)
                        .show()
                return
            }
            userInputEt.text = null
            val agentService =
                    com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
            if (agentService != null) {
                Log.i(TAG, "🤖 Routing text to Agent: \"$userMsg\"")
                agentService.activateAgent(userMsg)
            } else {
                Log.e(TAG, "⚠️ Agent enabled but Accessibility Service not running!")
                Toast.makeText(
                                this,
                                "Agent Service není aktivní — povolte v Usnadnění přístupu",
                                Toast.LENGTH_LONG
                        )
                        .show()
            }
            return
        }
        // Standard LLM chat (agent je vypnutý)
        if (settingsManager.backgroundGeneration) {
            delegateGenerationToService()
        } else {
            runGenerationInteractive()
        }
    }

    private fun delegateGenerationToService() {
        val userMsg = userInputEt.text.toString()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, getString(R.string.info_input_empty), Toast.LENGTH_SHORT).show()
            return
        }

        if (!serviceManager.isServiceConnected()) {
            Toast.makeText(this, "Service not connected yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (settingsManager.backgroundGeneration) {
            serviceManager.connect(asResident = true)
        }

        userInputEt.text = null
        applyDisplaySettings()

        val sc = settingsManager.loadSamplingConfig()
        Log.i(TAG, getString(R.string.info_generation_params_log, sc.temperature, sc.topK, sc.topP))
        var promptToSend = userMsg
        val sysMsg = settingsManager.systemMessage
        if (messages.none { it.isUser } && sysMsg.isNotBlank()) {
            promptToSend = "System: $sysMsg\n\nUser: $userMsg"
        }

        messages.add(Message(UUID.randomUUID().toString(), StringBuilder(userMsg), true))
        val assistantMsgId = UUID.randomUUID().toString()
        messages.add(Message(assistantMsgId, StringBuilder(""), false, isStreaming = true))

        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        if (!settingsManager.disableAutoScroll) {
            messagesRv.post {
                val count = messageAdapter.itemCount
                if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
            }
        }

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val targetConfig = checkAndAdjustContextLimit()
                if (targetConfig != null) {
                    // Force reload with new context size by acquiring lock temporarily
                    com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                        context = applicationContext,
                        owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                        config = targetConfig
                    ) {}
                }

                engine.configureSampling(
                        sc.temperature,
                        sc.topK,
                        sc.topP,
                        sc.minP,
                        sc.xtcProbability,
                        sc.xtcThreshold,
                        sc.typicalP,
                        sc.dynamicTempRange,
                        sc.dynamicTempExponent,
                        sc.penaltyRepeat,
                        sc.penaltyFreq,
                        sc.penaltyPresent,
                        sc.penaltyLastN,
                        sc.mirostat,
                        sc.mirostatTau,
                        sc.mirostatEta,
                        sc.samplersSequence,
                        sc.useBackendSampling
                )

                serviceManager.startGeneration(
                        sessionId = sessionManager.currentSessionId,
                        currentHistory = messages.dropLast(1),
                        prompt = promptToSend,
                        maxTokens = sc.maxTokens,
                        assistantMessageId = assistantMsgId
                )
            } catch (e: Exception) {
                Log.e(TAG, "Configuration Error", e)
            }
        }
    }

    fun resetEngineContext() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (::engine.isInitialized && engine.state.value.isModelLoaded) {
                engine.resetContext()
                withContext(Dispatchers.Main) { Log.i(TAG, "Engine context reset requested.") }
            }
        }
    }

    private fun runGenerationInteractive() {
        val userMsg = userInputEt.text.toString()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, getString(R.string.info_input_empty), Toast.LENGTH_SHORT).show()
            return
        }
        userInputEt.text = null
        applyDisplaySettings()

        val sc = settingsManager.loadSamplingConfig()
        Log.i(TAG, getString(R.string.info_generation_params_log, sc.temperature, sc.topK, sc.topP))
        var promptToSend = userMsg
        val sysMsg = settingsManager.systemMessage
        if (messages.none { it.isUser } && sysMsg.isNotBlank()) {
            promptToSend = "System: $sysMsg\n\nUser: $userMsg"
        }
        messages.add(Message(UUID.randomUUID().toString(), StringBuilder(userMsg), true))
        messages.add(
                Message(UUID.randomUUID().toString(), StringBuilder(""), false, isStreaming = true)
        )
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        val fullResponseBuilder = StringBuilder()

        generationJob =
                lifecycleScope.launch(Dispatchers.Default) {
                    try {
                        val targetConfig = checkAndAdjustContextLimit()
                        com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                            context = applicationContext,
                            owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                            config = targetConfig
                        ) { orchestratorEngine ->
                            val sc = settingsManager.loadSamplingConfig()
                            orchestratorEngine.configureSampling(
                                    sc.temperature,
                                    sc.topK,
                                    sc.topP,
                                    sc.minP,
                                    sc.xtcProbability,
                                    sc.xtcThreshold,
                                    sc.typicalP,
                                    sc.dynamicTempRange,
                                    sc.dynamicTempExponent,
                                    sc.penaltyRepeat,
                                    sc.penaltyFreq,
                                    sc.penaltyPresent,
                                    sc.penaltyLastN,
                                    sc.mirostat,
                                    sc.mirostatTau,
                                    sc.mirostatEta,
                                    sc.samplersSequence,
                                    sc.useBackendSampling
                            )
                            var startTime = 0L
                            var tokenCount = 0
                            val uiBuffer = StringBuilder()
                            var lastUiUpdate = 0L

                            orchestratorEngine.sendUserPrompt(promptToSend, sc.maxTokens)
                                    .onCompletion {
                                        generationJob = null
                                        if (uiBuffer.isNotEmpty()) {
                                            withContext(Dispatchers.Main) {
                                                messageAdapter.appendTokenToLastMessage(
                                                        uiBuffer.toString()
                                                )
                                                messageAdapter.setMessageStreaming(
                                                        messages.last().id,
                                                        false
                                                )
                                                sessionManager.saveCurrentState()
                                                updateSendButtonState()
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                messageAdapter.setMessageStreaming(
                                                        messages.last().id,
                                                        false
                                                )
                                                sessionManager.saveCurrentState()
                                                updateSendButtonState()
                                            }
                                        }
                                        val fullResponse = fullResponseBuilder.toString()
                                        sessionManager.saveCurrentState()
                                        Log.i(TAG, "Generation complete. Total length: ${fullResponse.length}")

                                        withContext(Dispatchers.Main) { 
                                            if (!settingsManager.disableAutoScroll && messages.isNotEmpty()) {
                                                messagesRv.post {
                                                    val count = messageAdapter.itemCount
                                                    if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                                }
                                            }
                                        }
                                    }
                                    .collect { token ->
                                        if (startTime == 0L) startTime = System.currentTimeMillis()
                                        tokenCount++

                                        uiBuffer.append(token)
                                        fullResponseBuilder.append(token)

                                        val now = System.currentTimeMillis()
                                        if (now - lastUiUpdate >= 60L) {
                                            val chunk = uiBuffer.toString()
                                            uiBuffer.setLength(0)
                                            lastUiUpdate = now

                                            withContext(Dispatchers.Main) {
                                                messageAdapter.appendTokenToLastMessage(chunk)

                                                val elapsedTime = (now - startTime) / 1000.0
                                                val speed = if (elapsedTime > 0) tokenCount / elapsedTime else 0.0

                                                if (!settingsManager.disableAutoScroll) {
                                                    if (!messagesRv.canScrollVertically(1) && messages.isNotEmpty()) {
                                                        messagesRv.post {
                                                            val count = messageAdapter.itemCount
                                                            if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                                        }
                                                    }
                                                }
                                                if (settingsManager.showStats)
                                                        speedTv.text = getString(R.string.info_speed_format, speed)
                                                if (settingsManager.showTokenCount)
                                                        tokenCountTv.text = getString(R.string.stats_tokens_format, tokenCount)
                                                if (settingsManager.showGenTime)
                                                        genTimeTv.text = getString(R.string.stats_time_format, elapsedTime)
                                            }
                                        }
                                    }
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Generation cancelled: ${e.message}")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Generation Error", e)
                    }
                }
        updateSendButtonState()
    }

    private fun handleSendButtonClick() {
        val isGeneratingViaService =
                serviceManager.generationState.value is GenerationState.Generating
        val isGeneratingInteractive = generationJob?.isActive == true

        if (isGeneratingViaService || isGeneratingInteractive) {
            stopGeneration()
        } else {
            handleUserInput()
        }
    }

    private suspend fun checkAndAdjustContextLimit(): com.example.llama.agent.core.EngineConfiguration? {
        val totalChars = messages.sumOf { it.content.length }
        val estimatedTokens = totalChars / 3 + 1024
        val currentCtx = settingsManager.contextSize
        val activeCtx = if (currentCtx <= 0) 8192 else currentCtx
        
        if (estimatedTokens > activeCtx) {
            val newCtx = estimatedTokens + 1024
            Log.i(TAG, "🔄 Auto-resizing context window to $newCtx (estimated tokens: $estimatedTokens)")
            settingsManager.contextSize = newCtx
            
            val baseConfig = com.example.llama.agent.core.EngineOrchestrator.activeConfig
            if (baseConfig != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Zvětšuji kontext na $newCtx tokenů...", Toast.LENGTH_SHORT).show()
                }
                return baseConfig.copy(nCtx = newCtx)
            }
        }
        return null
    }

    private fun stopGeneration() {
        if (settingsManager.backgroundGeneration) {
            serviceManager.stopGeneration()
        } else {
            generationJob?.cancel()
        }
        updateSendButtonState()
        Toast.makeText(this, getString(R.string.msg_generation_stopped), Toast.LENGTH_SHORT).show()
    }

    private fun handleEjectModel() {
        if (!::engine.isInitialized || !engine.state.value.isModelLoaded) {
            Toast.makeText(this, "No model loaded to eject.", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val intent =
                    Intent(this@MainActivity, LlamaGenerationService::class.java).apply {
                        action = LlamaNotificationHelper.ACTION_RELEASE_RAM
                    }
            startService(intent)

            withContext(Dispatchers.IO) { engine.cleanUp() }

            withContext(Dispatchers.Main) {
                ggufTv.text = "Model Ejected"
                userInputEt.hint = getString(R.string.user_input_hint_no_model)
                updateSendButtonState()
                Toast.makeText(
                                this@MainActivity,
                                "Model ejected & RAM released.",
                                Toast.LENGTH_SHORT
                        )
                        .show()
            }
        }
    }

    private fun applyDisplaySettings() {
        speedTv.visibility = if (settingsManager.showStats) View.VISIBLE else View.GONE
        tokenCountTv.visibility = if (settingsManager.showTokenCount) View.VISIBLE else View.GONE
        genTimeTv.visibility = if (settingsManager.showGenTime) View.VISIBLE else View.GONE

        // Enable Mic if Agent Enabled OR STT Enabled (and strictly NOT forced hidden if both are
        // off)
        val isStt = settingsManager.isSttEnabled
        val isAgent = settingsManager.isAgentEnabled
        // Only show if explicitly enabled features need it
        val showMic = isAgent || isStt

        val micButton = findViewById<ImageButton>(R.id.mic_button)
        micButton.visibility = if (showMic) View.VISIBLE else View.GONE

        micButton.setOnClickListener {
            val pm = permissionManager
            pm.checkAndRequestAudioPermission { granted: Boolean ->
                if (granted) {
                    // INTEGRACE: Pokud je Agent zapnutý
                    if (settingsManager.isAgentEnabled) {
                        val voiceMode = settingsManager.agentVoiceMode

                        if (voiceMode == SettingsManager.VOICEMODE_PUSH_TO_TALK) {
                            // Push to Talk -> Aktivovat Agent Voice Command
                            val agentService =
                                    com.example.llama.agent.service.AiAgentAccessibilityService
                                            .getInstance()
                            if (agentService != null) {
                                if (agentService.isVoiceSpeaking()) {
                                    agentService.deactivateAgent("User interrupted speaking")
                                } else {
                                    agentService.startVoiceCommand()
                                    Toast.makeText(this, "Poslouchám...", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                // Fallback/Error state
                                Toast.makeText(
                                                this,
                                                "Agent Service není aktivní",
                                                Toast.LENGTH_SHORT
                                        )
                                        .show()
                            }
                        } else {
                            // Open Mic -> Button click might Toggle mute/unmute or restart
                            // listening?
                            // Prozatím restart poslechu nebo vizuální feedback
                            Toast.makeText(
                                            this,
                                            "Režim Open Mic je aktivní (Stále poslouchám)",
                                            Toast.LENGTH_SHORT
                                    )
                                    .show()
                            // Optional: Restart listening if stuck
                            voiceInputManager.startContinuousListening()
                        }
                        return@checkAndRequestAudioPermission
                    }

                    // Standardní diktování (Chat) - Fallback if Agent OFF but Mic clicked (e.g. STT
                    // enabled)
                    if (voiceInputManager.isListening()) {
                        voiceInputManager.stopListening()
                    } else {
                        voiceInputManager.startListening()
                    }
                }
            }
        }

        if (settingsManager.alwaysShowSidebar) {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_OPEN)
            drawerLayout.setScrimColor(android.graphics.Color.TRANSPARENT)
        } else {
            drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (settingsManager.hideStatusBar) {
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }

        // Reset stats text if visible to avoid empty view or stale state
        if (settingsManager.showStats) speedTv.text = getString(R.string.info_speed_format, 0.0)
        if (settingsManager.showTokenCount)
                tokenCountTv.text = getString(R.string.stats_tokens_format, 0)
        if (settingsManager.showGenTime) genTimeTv.text = getString(R.string.stats_time_format, 0.0)

        messageAdapter.renderMarkdown = settingsManager.renderMarkdown
        messageAdapter.enableBubbleCopy = settingsManager.enableBubbleCopy
        messageAdapter.chatStyle = settingsManager.chatStyle
        messageAdapter.showThoughts = settingsManager.showThoughtInProgress

        messageAdapter.isTtsEnabled = settingsManager.isTtsEnabled
        if (messages.isNotEmpty()) {
            messageAdapter.notifyItemRangeChanged(0, messages.size)
        }

        ThemeManager.applyTheme(this, settingsManager.chatStyle)
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun handleSelectedModelImport(uri: Uri) {
        Toast.makeText(this, getString(R.string.msg_import_wait), Toast.LENGTH_LONG).show()
        viewModel.importAndLoadModel(
                uri = uri,
                onPathReady = { nativePath ->
                    lifecycleScope.launch { loadModelToEngine(nativePath) }
                },
                onError = { error ->
                    val errText = getString(R.string.msg_import_failed, error)
                    Toast.makeText(this, errText, Toast.LENGTH_LONG).show()
                    ggufTv.text = getString(R.string.status_error_prefix, error)
                }
        )
    }

    override fun onStop() {
        super.onStop()
        if (!settingsManager.backgroundGeneration) {
            generationJob?.cancel()
        }
    }

    override fun onDestroy() {
        if (::voiceInputManager.isInitialized) {
            voiceInputManager.shutdown()
        }
        val agentService = com.example.llama.agent.service.AiAgentAccessibilityService.getInstance()
        agentService?.deactivateAgent("App destroyed")

        messageAdapter.cleanup()
        if (isFinishing) {
            Log.i(TAG, "Activity finishing - Killing service.")
            serviceManager.stopService()
        } else {
            Log.i(TAG, "Rotation - Detaching only.")
            serviceManager.disconnect()
        }
        super.onDestroy()
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }
}
