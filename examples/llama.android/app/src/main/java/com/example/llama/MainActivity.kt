package com.example.llama

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
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
import com.arm.aichat.ThinkingProfile
import com.arm.aichat.isModelLoaded
import com.arm.aichat.isUninterruptible
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.example.llama.agent.skills.SkillRegistry
import com.example.llama.agent.skills.SkillsUiManager
import com.example.llama.agent.tools.web.WebToolsDispatcher
import com.example.llama.agent.tools.web.ToolCallRequest
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
    private lateinit var toggleThinkingProfile: MaterialButtonToggleGroup
    private lateinit var btnSkillsSelector: MaterialButton
    private lateinit var btnToolsSelector: MaterialButton
    private lateinit var btnWebPipSelector: MaterialButton
    private var pipStub: ViewStub? = null
    private var pipRootView: View? = null
    private var pipContainer: FrameLayout? = null
    private var pipWebView: android.webkit.WebView? = null
    private var pipUrlTv: TextView? = null
    private var pipProgressBar: ProgressBar? = null
    private var pipCloseBtn: ImageButton? = null
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
    private var currentModelArchitecture: String? = null
    private lateinit var serviceManager: LlamaServiceManager
    private var isFirstAgentEmit = true
    // Pending image flag: Když je true, bypass Agent routing a jdi přímo do LLM chat
    // (C++ vrstva má g_cached_bmp a auto-prepend <__media__> marker)
    @Volatile private var hasPendingImage = false
    private var lastAppliedSkillId: String? = null
    private var lastAppliedToolsMode: SettingsManager.ToolsMode? = null
    private var lastAppliedSystemMessage: String? = null
    private var isSessionSystemPromptSet = false
    private var lastActiveSessionId: String? = null

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

        // Web Tools Dispatcher & Search Engine Config Init (DataSaver, FTS5 cache, User RAG)
        WebToolsDispatcher.initialize(applicationContext)
        settingsManager.syncToWebSearchEngineConfig()
        // Synchronize settings on resume (keep lastAppliedSystemMessage for diff check in buildEffectivePromptToSend)
        if (lastAppliedSystemMessage == null) {
            lastAppliedSystemMessage = settingsManager.systemMessage
        }

        com.example.llama.agent.tools.web.AutonomousBrowserSubagent.pipController =
            object : com.example.llama.agent.tools.web.PipBrowserController {
                override fun onLoadUrl(url: String) {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        showPipWithUrl(url)
                    }
                }

                override fun onScroll(direction: String, steps: Int) {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        val wv = pipWebView ?: return@runOnUiThread
                        val dir = direction.lowercase().trim()
                        val px = (480 * steps.coerceIn(1, 6))
                        val js = when (dir) {
                            "up" -> "window.scrollBy({top: -$px, behavior: 'smooth'});"
                            "top" -> "window.scrollTo({top: 0, behavior: 'smooth'});"
                            "bottom" -> "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
                            else -> "window.scrollBy({top: $px, behavior: 'smooth'});"
                        }
                        wv.evaluateJavascript(js, null)
                    }
                }

                override fun onDismissBanners() {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        val wv = pipWebView ?: return@runOnUiThread
                        wv.postDelayed({
                            val dismissJs = """
                                (function() {
                                    try {
                                        const sels = ['#onetrust-accept-btn-handler','.cookie-accept','#accept-cookies','[aria-label*="souhlas" i]','[aria-label*="accept" i]','button[id*="cookie" i]','button[id*="accept" i]'];
                                        for (let s of sels) {
                                            let b = document.querySelector(s);
                                            if (b && typeof b.click === 'function') { b.click(); break; }
                                        }
                                        window.scrollBy({top: 320, behavior: 'smooth'});
                                    } catch(e) {}
                                })();
                            """.trimIndent()
                            pipWebView?.evaluateJavascript(dismissJs, null)
                        }, 900L)
                    }
                }

                override fun onNavigateBack() {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        if (pipWebView?.canGoBack() == true) {
                            pipWebView?.goBack()
                        }
                    }
                }

                override suspend fun navigateScrollAndExtractDom(
                    url: String,
                    scrollSteps: Int,
                    waitMs: Long
                ): com.example.llama.agent.tools.workspace.WebViewHeadlessBridge.DomSnapshotResult? {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return null
                    return try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            showPipWithUrl(url)
                        }
                        kotlinx.coroutines.delay(waitMs.coerceIn(900L, 4500L))
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val wv = pipWebView ?: return@withContext null
                            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                                val extractJs = """
                                    (function() {
                                        try {
                                            const sels = ['#onetrust-accept-btn-handler','.cookie-accept','#accept-cookies','[aria-label*="souhlas" i]','[aria-label*="accept" i]','button[id*="cookie" i]','button[id*="accept" i]'];
                                            for (let s of sels) {
                                                let b = document.querySelector(s);
                                                if (b && typeof b.click === 'function') { b.click(); break; }
                                            }
                                            window.scrollTo({top: ${scrollSteps.coerceIn(1, 5) * 420}, behavior: 'smooth'});
                                        } catch(e) {}
                                        const links = [];
                                        const seen = new Set();
                                        document.querySelectorAll('a[href]').forEach((a) => {
                                            if (links.length >= 35) return;
                                            const t = (a.innerText || a.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
                                            const h = a.href || '';
                                            if (t.length > 3 && h.startsWith('http') && !seen.has(h)) {
                                                seen.add(h);
                                                let ctx = '';
                                                try {
                                                    const p = a.closest('article, div, li, section, tr') || a.parentElement;
                                                    if (p) ctx = (p.innerText || '').replace(/\s+/g, ' ').trim().substring(0, 260);
                                                } catch(e2) {}
                                                links.push({text: t.substring(0, 110) + (ctx.length > t.length + 12 ? ' — ' + ctx : ''), url: h});
                                            }
                                        });
                                        const clone = document.body ? document.body.cloneNode(true) : null;
                                        if (clone) {
                                            clone.querySelectorAll('script, style, noscript, svg, nav, footer').forEach(el => el.remove());
                                        }
                                        const visibleText = clone ? (clone.innerText || '') : (document.body ? document.body.innerText : '');
                                        return JSON.stringify({
                                            title: document.title || '',
                                            url: window.location.href || '',
                                            text: visibleText.replace(/\n{3,}/g, '\n\n').trim().substring(0, 14000),
                                            links: links
                                        });
                                    })();
                                """.trimIndent()
                                wv.evaluateJavascript(extractJs) { rawResult ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    try {
                                        if (rawResult.isNullOrBlank() || rawResult == "null") {
                                            cont.resume(null, null)
                                        } else {
                                            val unescaped = org.json.JSONTokener(rawResult).nextValue()?.toString() ?: rawResult
                                            val obj = org.json.JSONObject(unescaped)
                                            val linksArr = obj.optJSONArray("links")
                                            val linkList = mutableListOf<com.example.llama.agent.tools.web.ExtractedWebLink>()
                                            if (linksArr != null) {
                                                for (i in 0 until linksArr.length()) {
                                                    val item = linksArr.optJSONObject(i)
                                                    if (item != null) {
                                                        val lUrl = item.optString("url", "")
                                                        val lText = item.optString("text", "")
                                                        if (lUrl.isNotBlank() && lText.isNotBlank()) {
                                                            linkList.add(com.example.llama.agent.tools.web.ExtractedWebLink(index = linkList.size + 1, label = lText, url = lUrl))
                                                        }
                                                    }
                                                }
                                            }
                                            cont.resume(
                                                com.example.llama.agent.tools.workspace.WebViewHeadlessBridge.DomSnapshotResult(
                                                    title = obj.optString("title", ""),
                                                    cleanText = obj.optString("text", ""),
                                                    links = linkList
                                                ),
                                                null
                                            )
                                        }
                                    } catch (e: Exception) {
                                        cont.resume(null, null)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }

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
        messageAdapter.thinkingProfile = settingsManager.thinkingProfile

        ggufTv = findViewById(R.id.gguf)
        messagesRv = findViewById(R.id.messages)
        val layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        messagesRv.layoutManager = layoutManager
        messagesRv.itemAnimator = null // CRITICAL for performance
        messagesRv.adapter = messageAdapter
        messageAdapter.fontSizeSp = settingsManager.chatFontSizeSp

        val chatScaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                if (scaleFactor != 1.0f) {
                    val currentSize = messageAdapter.fontSizeSp
                    val newSize = (currentSize * scaleFactor).coerceIn(11.0f, 34.0f)
                    if (kotlin.math.abs(newSize - currentSize) >= 0.2f) {
                        messageAdapter.fontSizeSp = newSize
                        settingsManager.chatFontSizeSp = newSize
                    }
                }
                return true
            }
        })

        messagesRv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                if (e.pointerCount >= 2) {
                    chatScaleDetector.onTouchEvent(e)
                    return chatScaleDetector.isInProgress
                }
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                chatScaleDetector.onTouchEvent(e)
            }
        })
        userInputEt = findViewById(R.id.user_input)
        sendButton = findViewById(R.id.send_button)
        speedTv = findViewById(R.id.tv_speed)
        toggleThinkingProfile = findViewById(R.id.toggle_thinking_profile)
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
        sessionManager.onSkillsClicked = {
            SkillsUiManager.showSkillsManager(this) {
                updateSkillsButtonLabel()
            }
        }

        setupToolbar()
        sessionManager.refreshNavigationMenu()
        setupInputListeners()
        setupThinkingProfileToggle()
        btnSkillsSelector = findViewById(R.id.btn_skills_selector)
        updateSkillsButtonLabel()
        btnSkillsSelector.setOnClickListener {
            SkillsUiManager.showQuickSkillSelector(this) {
                updateSkillsButtonLabel()
            }
        }
        btnToolsSelector = findViewById(R.id.btn_tools_selector)
        updateToolsButtonLabel()
        btnToolsSelector.setOnClickListener {
            showToolsSelectorDialog()
        }
        btnToolsSelector.setOnLongClickListener {
            toggleToolsQuick()
            true
        }
        btnWebPipSelector = findViewById(R.id.btn_web_pip_selector)
        pipStub = findViewById(R.id.stub_in_chat_pip)
        updateWebPipButtonLabel()
        btnWebPipSelector.setOnClickListener {
            showWebPipSelectorDialog()
        }
        btnWebPipSelector.setOnLongClickListener {
            toggleWebPipQuick()
            true
        }
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
                val tp = settingsManager.thinkingProfile
                engine.configureThinking(tp.isEnabled, tp.maxTokens, tp.effort)
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
        handleIntentExtras(intent)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntentExtras(intent)
    }

    private fun handleIntentExtras(intent: android.content.Intent?) {
        val modelTarget = intent?.getStringExtra("load_model")
        val promptToSend = intent?.getStringExtra("send_prompt")

        if (modelTarget != null) {
            lifecycleScope.launch {
                var attempts = 0
                while (attempts < 30 && (!::engine.isInitialized || viewModel.savedModels.value.isEmpty())) {
                    kotlinx.coroutines.delay(200)
                    attempts++
                }
                val models = viewModel.savedModels.value
                val matched = models.firstOrNull { it.displayName.contains(modelTarget, ignoreCase = true) }
                if (matched != null) {
                    Log.i(TAG, "🤖 Intent Auto-Load Model: ${matched.displayName}")
                    withContext(Dispatchers.Main) {
                        handleLoadLocalModel(matched)
                    }
                    if (promptToSend != null) {
                        while (!::engine.isInitialized || !engine.state.value.isModelLoaded) {
                            kotlinx.coroutines.delay(300)
                        }
                        kotlinx.coroutines.delay(800)
                        withContext(Dispatchers.Main) {
                            userInputEt.setText(promptToSend)
                            handleUserInput()
                        }
                    }
                } else {
                    Log.w(TAG, "⚠️ Model matching '$modelTarget' not found in saved models: ${models.map { it.displayName }}")
                }
            }
        } else if (promptToSend != null) {
            lifecycleScope.launch {
                while (!::engine.isInitialized || !engine.state.value.isModelLoaded) {
                    kotlinx.coroutines.delay(300)
                }
                withContext(Dispatchers.Main) {
                    userInputEt.setText(promptToSend)
                    handleUserInput()
                }
            }
        }
    }

    private fun setupMultiModalCallbacks() {
        // Zpracování textových souborů a dokumentů
        fileManager.onTextAttachedCallback = { textToAppend ->
            val currentText = userInputEt.text.toString().trim()
            val formatted = if (currentText.isNotBlank()) {
                "$currentText\n\n[Přiložený text / soubor k analýze]:\n```\n$textToAppend\n```"
            } else {
                "[Přiložený text / soubor k analýze]:\n```\n$textToAppend\n```\n"
            }
            userInputEt.setText(formatted)
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
                        applyDisplaySettings()
        if (lastAppliedSystemMessage != null && lastAppliedSystemMessage != settingsManager.systemMessage) {
            Log.i(TAG, "🔄 User System Prompt changed in Settings. Marking session system prompt for refresh.")
            isSessionSystemPromptSet = false
            if (messages.isNotEmpty() && messages[0].content.startsWith("SYSTEM:")) {
                messages[0].content = "SYSTEM: ${settingsManager.systemMessage}"
                messageAdapter.notifyItemChanged(0)
            }
        } // Update bottom bar mic visibility

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
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_quick_backend)?.let { btn ->
            updateQuickBackendButton(btn, settingsManager.llamaBackend)
        }
        applyDisplaySettings()
        if (lastAppliedSystemMessage != null && lastAppliedSystemMessage != settingsManager.systemMessage) {
            Log.i(TAG, "🔄 User System Prompt changed in Settings. Marking session system prompt for refresh.")
            isSessionSystemPromptSet = false
            if (messages.isNotEmpty() && messages[0].content.startsWith("SYSTEM:")) {
                messages[0].content = "SYSTEM: ${settingsManager.systemMessage}"
                messageAdapter.notifyItemChanged(0)
            }
        }
        settingsManager.syncToWebSearchEngineConfig()
        // Synchronize settings on resume (keep lastAppliedSystemMessage for diff check in buildEffectivePromptToSend)
        if (lastAppliedSystemMessage == null) {
            lastAppliedSystemMessage = settingsManager.systemMessage
        }

        com.example.llama.agent.tools.web.AutonomousBrowserSubagent.pipController =
            object : com.example.llama.agent.tools.web.PipBrowserController {
                override fun onLoadUrl(url: String) {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        showPipWithUrl(url)
                    }
                }

                override fun onScroll(direction: String, steps: Int) {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        val wv = pipWebView ?: return@runOnUiThread
                        val dir = direction.lowercase().trim()
                        val px = (480 * steps.coerceIn(1, 6))
                        val js = when (dir) {
                            "up" -> "window.scrollBy({top: -$px, behavior: 'smooth'});"
                            "top" -> "window.scrollTo({top: 0, behavior: 'smooth'});"
                            "bottom" -> "window.scrollTo({top: document.body.scrollHeight, behavior: 'smooth'});"
                            else -> "window.scrollBy({top: $px, behavior: 'smooth'});"
                        }
                        wv.evaluateJavascript(js, null)
                    }
                }

                override fun onDismissBanners() {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        val wv = pipWebView ?: return@runOnUiThread
                        wv.postDelayed({
                            val dismissJs = """
                                (function() {
                                    try {
                                        const sels = ['#onetrust-accept-btn-handler','.cookie-accept','#accept-cookies','[aria-label*="souhlas" i]','[aria-label*="accept" i]','button[id*="cookie" i]','button[id*="accept" i]'];
                                        for (let s of sels) {
                                            let b = document.querySelector(s);
                                            if (b && typeof b.click === 'function') { b.click(); break; }
                                        }
                                        window.scrollBy({top: 320, behavior: 'smooth'});
                                    } catch(e) {}
                                })();
                            """.trimIndent()
                            pipWebView?.evaluateJavascript(dismissJs, null)
                        }, 900L)
                    }
                }

                override fun onNavigateBack() {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return
                    runOnUiThread {
                        if (pipWebView?.canGoBack() == true) {
                            pipWebView?.goBack()
                        }
                    }
                }

                override suspend fun navigateScrollAndExtractDom(
                    url: String,
                    scrollSteps: Int,
                    waitMs: Long
                ): com.example.llama.agent.tools.workspace.WebViewHeadlessBridge.DomSnapshotResult? {
                    if (settingsManager.webDisplayMode != SettingsManager.WebDisplayMode.PIP) return null
                    return try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            showPipWithUrl(url)
                        }
                        kotlinx.coroutines.delay(waitMs.coerceIn(900L, 4500L))
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            val wv = pipWebView ?: return@withContext null
                            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                                val extractJs = """
                                    (function() {
                                        try {
                                            const sels = ['#onetrust-accept-btn-handler','.cookie-accept','#accept-cookies','[aria-label*="souhlas" i]','[aria-label*="accept" i]','button[id*="cookie" i]','button[id*="accept" i]'];
                                            for (let s of sels) {
                                                let b = document.querySelector(s);
                                                if (b && typeof b.click === 'function') { b.click(); break; }
                                            }
                                            window.scrollTo({top: ${scrollSteps.coerceIn(1, 5) * 420}, behavior: 'smooth'});
                                        } catch(e) {}
                                        const links = [];
                                        const seen = new Set();
                                        document.querySelectorAll('a[href]').forEach((a) => {
                                            if (links.length >= 35) return;
                                            const t = (a.innerText || a.getAttribute('aria-label') || '').replace(/\s+/g, ' ').trim();
                                            const h = a.href || '';
                                            if (t.length > 3 && h.startsWith('http') && !seen.has(h)) {
                                                seen.add(h);
                                                let ctx = '';
                                                try {
                                                    const p = a.closest('article, div, li, section, tr') || a.parentElement;
                                                    if (p) ctx = (p.innerText || '').replace(/\s+/g, ' ').trim().substring(0, 260);
                                                } catch(e2) {}
                                                links.push({text: t.substring(0, 110) + (ctx.length > t.length + 12 ? ' — ' + ctx : ''), url: h});
                                            }
                                        });
                                        const clone = document.body ? document.body.cloneNode(true) : null;
                                        if (clone) {
                                            clone.querySelectorAll('script, style, noscript, svg, nav, footer').forEach(el => el.remove());
                                        }
                                        const visibleText = clone ? (clone.innerText || '') : (document.body ? document.body.innerText : '');
                                        return JSON.stringify({
                                            title: document.title || '',
                                            url: window.location.href || '',
                                            text: visibleText.replace(/\n{3,}/g, '\n\n').trim().substring(0, 14000),
                                            links: links
                                        });
                                    })();
                                """.trimIndent()
                                wv.evaluateJavascript(extractJs) { rawResult ->
                                    if (!cont.isActive) return@evaluateJavascript
                                    try {
                                        if (rawResult.isNullOrBlank() || rawResult == "null") {
                                            cont.resume(null, null)
                                        } else {
                                            val unescaped = org.json.JSONTokener(rawResult).nextValue()?.toString() ?: rawResult
                                            val obj = org.json.JSONObject(unescaped)
                                            val linksArr = obj.optJSONArray("links")
                                            val linkList = mutableListOf<com.example.llama.agent.tools.web.ExtractedWebLink>()
                                            if (linksArr != null) {
                                                for (i in 0 until linksArr.length()) {
                                                    val item = linksArr.optJSONObject(i)
                                                    if (item != null) {
                                                        val lUrl = item.optString("url", "")
                                                        val lText = item.optString("text", "")
                                                        if (lUrl.isNotBlank() && lText.isNotBlank()) {
                                                            linkList.add(com.example.llama.agent.tools.web.ExtractedWebLink(index = linkList.size + 1, label = lText, url = lUrl))
                                                        }
                                                    }
                                                }
                                            }
                                            cont.resume(
                                                com.example.llama.agent.tools.workspace.WebViewHeadlessBridge.DomSnapshotResult(
                                                    title = obj.optString("title", ""),
                                                    cleanText = obj.optString("text", ""),
                                                    links = linkList
                                                ),
                                                null
                                            )
                                        }
                                    } catch (e: Exception) {
                                        cont.resume(null, null)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }
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

        val btnQuickBackend: com.google.android.material.button.MaterialButton? = findViewById(R.id.btn_quick_backend)
        btnQuickBackend?.let { btn ->
            updateQuickBackendButton(btn, settingsManager.llamaBackend)

            btn.setOnClickListener {
                val current = settingsManager.llamaBackend
                val all = SettingsManager.LlamaBackend.entries
                val nextIdx = (all.indexOf(current) + 1) % all.size
                val next = all[nextIdx]
                settingsManager.llamaBackend = next
                updateQuickBackendButton(btn, next)
                Toast.makeText(this, "GGML Backend: ${next.displayName}", Toast.LENGTH_SHORT).show()

                // Pokud je načten model, přenačteme jej s novým backendem
                currentModelPath?.let { modelPath ->
                    lifecycleScope.launch {
                        loadModelToEngine(modelPath)
                    }
                }
            }

            btn.setOnLongClickListener {
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    putExtra("open_tab", 5) // Tab Advanced (Pokročilé)
                }
                startActivity(intent)
                true
            }
        }

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
                    val meta = ModelMetadataManager.readMetadata(procPath)
                    currentModelArchitecture = meta?.architecture?.architecture
                    if (meta != null && ModelMetadataManager.isMultimodalModel(meta)) {
                        Log.i(TAG, "🔍 Metadata auto-detected Multimodal/Omni architecture (${meta.architecture?.architecture}): Using main model as projector ($procPath)")
                        discoveredProjectorPath = procPath
                    }
                } else {
                    val meta = ModelMetadataManager.readMetadata(procPath)
                    currentModelArchitecture = meta?.architecture?.architecture
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
                val llamaBackend = settingsManager.llamaBackend
                val strictBackend = settingsManager.strictBackend
                val gpuLayers = settingsManager.gpuLayers
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
                        kvCacheMode = kvCacheMode,
                        llamaBackend = llamaBackend,
                        strictBackend = strictBackend,
                        gpuLayers = gpuLayers
                    )
                    com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                        context = applicationContext,
                        owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                        config = config
                    ) { orchestratorEngine ->
                        orchestratorEngine.resetContext()
                    }
                    isSessionSystemPromptSet = false
                    withContext(Dispatchers.Main) {
                        userInputEt.hint = null
                        if (messages.isEmpty()) sessionManager.startNewSession(skipEngineReset = true)
                        val isMultimodal = discoveredProjectorPath != null || (::engine.isInitialized && engine.hasProjector())
                        val successMsg = if (isMultimodal) {
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
                        val isBackendError = e is com.arm.aichat.BackendInitializationException
                        val displayMsg = if (isBackendError) {
                            "❌ Selhala inicializace backendu (${settingsManager.llamaBackend.displayName}): ${e.message}"
                        } else {
                            getString(R.string.status_error_prefix, e.message)
                        }
                        Toast.makeText(this@MainActivity, displayMsg, Toast.LENGTH_LONG).show()
                        if (!ggufTv.text.contains("Metadata") && !ggufTv.text.contains("GGUF")) {
                            ggufTv.text = displayMsg
                        }
                    }
                }
            }

    private fun updateQuickBackendButton(
        btn: com.google.android.material.button.MaterialButton,
        backend: SettingsManager.LlamaBackend
    ) {
        btn.text = backend.shortName
        val colorHex = when (backend) {
            SettingsManager.LlamaBackend.CPU -> "#00E676"     // Neon Green
            SettingsManager.LlamaBackend.OPENCL -> "#29B6F6"  // Cyan / Blue
            SettingsManager.LlamaBackend.HEXAGON -> "#AB47BC" // Purple
            SettingsManager.LlamaBackend.HTP_V75 -> "#FF7043" // Deep Orange
            SettingsManager.LlamaBackend.HTP_V73 -> "#FFA726" // Amber
        }
        val colorInt = android.graphics.Color.parseColor(colorHex)
        btn.setTextColor(colorInt)
        btn.strokeColor = android.content.res.ColorStateList.valueOf(colorInt)
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

    private fun setupThinkingProfileToggle() {
        if (!::toggleThinkingProfile.isInitialized) return
        val currentProfile = settingsManager.thinkingProfile
        val checkId = when (currentProfile) {
            ThinkingProfile.OEM -> R.id.btn_think_oem
            ThinkingProfile.OFF -> R.id.btn_think_off
            ThinkingProfile.LOW -> R.id.btn_think_low
            ThinkingProfile.MEDIUM -> R.id.btn_think_med
            ThinkingProfile.DEEP -> R.id.btn_think_deep
        }
        toggleThinkingProfile.check(checkId)

        toggleThinkingProfile.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val newProfile = when (checkedId) {
                    R.id.btn_think_oem -> ThinkingProfile.OEM
                    R.id.btn_think_off -> ThinkingProfile.OFF
                    R.id.btn_think_low -> ThinkingProfile.LOW
                    R.id.btn_think_med -> ThinkingProfile.MEDIUM
                    R.id.btn_think_deep -> ThinkingProfile.DEEP
                    else -> ThinkingProfile.MEDIUM
                }
                settingsManager.thinkingProfile = newProfile
                if (::messageAdapter.isInitialized) {
                    messageAdapter.thinkingProfile = newProfile
                }
                if (::engine.isInitialized) {
                    engine.configureThinking(newProfile.isEnabled, newProfile.maxTokens, newProfile.effort)
                }
                Log.i(TAG, "Thinking Profile updated: $newProfile (enabled=${newProfile.isEnabled}, budget=${newProfile.maxTokens})")
            }
        }
    }

    private fun updateSkillsButtonLabel() {
        if (!::btnSkillsSelector.isInitialized) return
        val registry = SkillRegistry.getInstance(this)
        val activeId = registry.activeSkillId
        val label = when (activeId) {
            SkillRegistry.SKILL_AUTO -> "🧠 Skills: Auto"
            SkillRegistry.SKILL_NONE -> "🧠 Skills: Off"
            else -> {
                val skill = registry.getSkillById(activeId)
                val shortName = (skill?.name ?: activeId).take(10)
                "🧠 $shortName"
            }
        }
        btnSkillsSelector.text = label
    }

    private fun updateToolsButtonLabel() {
        if (!::btnToolsSelector.isInitialized) return
        when (settingsManager.toolsMode) {
            SettingsManager.ToolsMode.AUTO -> {
                val total = com.example.llama.agent.tools.web.WebToolsDispatcher.ALL_TOOL_ITEMS.size
                val active = settingsManager.enabledAgentTools.size
                val suffix = if (active < total) " ($active/$total)" else ""
                btnToolsSelector.text = "🛠️ Tools: Auto$suffix"
                val color = android.graphics.Color.parseColor("#FFA726")
                btnToolsSelector.setTextColor(color)
                btnToolsSelector.strokeColor = android.content.res.ColorStateList.valueOf(color)
            }
            SettingsManager.ToolsMode.ON -> {
                val total = com.example.llama.agent.tools.web.WebToolsDispatcher.ALL_TOOL_ITEMS.size
                val active = settingsManager.enabledAgentTools.size
                val suffix = if (active < total) " ($active/$total)" else ""
                btnToolsSelector.text = "🛠️ Tools: ON$suffix"
                val color = android.graphics.Color.parseColor("#00E676")
                btnToolsSelector.setTextColor(color)
                btnToolsSelector.strokeColor = android.content.res.ColorStateList.valueOf(color)
            }
            SettingsManager.ToolsMode.OFF -> {
                btnToolsSelector.text = "🛠️ Tools: Off"
                val color = android.graphics.Color.parseColor("#757575")
                btnToolsSelector.setTextColor(color)
                btnToolsSelector.strokeColor = android.content.res.ColorStateList.valueOf(color)
            }
        }
    }

    private fun showToolsSelectorDialog() {
        val modes = arrayOf(
            "⚡ Auto (dle dotazu na internet / věda / fakta)",
            "🟢 Vždy zapnuto (ON - model má vždy web tools)",
            "🔴 Vypnuto (OFF - offline bez nástrojů)",
            "🎛️ Výběr jednotlivých nástrojů (Realtime kombinace)...",
            "ℹ️ Informace o dostupných nástrojích..."
        )
        val currentIdx = when (settingsManager.toolsMode) {
            SettingsManager.ToolsMode.AUTO -> 0
            SettingsManager.ToolsMode.ON -> 1
            SettingsManager.ToolsMode.OFF -> 2
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🛠️ Autonomní Web Nástroje")
            .setSingleChoiceItems(modes, currentIdx) { dialog, which ->
                when (which) {
                    0 -> {
                        settingsManager.toolsMode = SettingsManager.ToolsMode.AUTO
                        updateToolsButtonLabel()
                        applyToolsModeToEngine(SettingsManager.ToolsMode.AUTO)
                        dialog.dismiss()
                    }
                    1 -> {
                        settingsManager.toolsMode = SettingsManager.ToolsMode.ON
                        updateToolsButtonLabel()
                        applyToolsModeToEngine(SettingsManager.ToolsMode.ON)
                        dialog.dismiss()
                    }
                    2 -> {
                        settingsManager.toolsMode = SettingsManager.ToolsMode.OFF
                        updateToolsButtonLabel()
                        applyToolsModeToEngine(SettingsManager.ToolsMode.OFF)
                        dialog.dismiss()
                    }
                    3 -> {
                        dialog.dismiss()
                        showIndividualToolsToggleDialog()
                    }
                    4 -> {
                        dialog.dismiss()
                        showToolsHelpDialog()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyToolsModeToEngine(mode: SettingsManager.ToolsMode) {
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                    context = applicationContext,
                    owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT
                ) { eng ->
                    when (mode) {
                        SettingsManager.ToolsMode.OFF -> {
                            eng.setToolPolicy(2)
                            eng.setToolDefinitions("")
                        }
                        SettingsManager.ToolsMode.ON -> {
                            eng.setToolPolicy(1)
                            eng.setToolDefinitions(com.example.llama.agent.tools.web.WebToolsDispatcher.getToolsJson())
                        }
                        SettingsManager.ToolsMode.AUTO -> {
                            eng.setToolPolicy(0)
                            eng.setToolDefinitions(com.example.llama.agent.tools.web.WebToolsDispatcher.getToolsJson())
                        }
                    }
                    Log.i(TAG, "Applied tools policy $mode to engine.")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "applyToolsModeToEngine note: ${e.message}")
            }
        }
    }

    private fun toggleToolsQuick() {
        val nextMode = when (settingsManager.toolsMode) {
            SettingsManager.ToolsMode.AUTO -> SettingsManager.ToolsMode.ON
            SettingsManager.ToolsMode.ON -> SettingsManager.ToolsMode.OFF
            SettingsManager.ToolsMode.OFF -> SettingsManager.ToolsMode.AUTO
        }
        settingsManager.toolsMode = nextMode
        updateToolsButtonLabel()
        applyToolsModeToEngine(nextMode)
        Toast.makeText(this, "Nástroje: ${nextMode.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showIndividualToolsToggleDialog() {
        val allItems = com.example.llama.agent.tools.web.WebToolsDispatcher.ALL_TOOL_ITEMS
        val labels = allItems.map { it.second }.toTypedArray()
        val currentEnabled = settingsManager.enabledAgentTools.toMutableSet()
        val checkedItems = BooleanArray(allItems.size) { i ->
            currentEnabled.contains(allItems[i].first)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🎛️ Kombinace nástrojů (Realtime: ${currentEnabled.size}/${allItems.size})")
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                val toolId = allItems[which].first
                if (isChecked) {
                    currentEnabled.add(toolId)
                } else {
                    currentEnabled.remove(toolId)
                }
                settingsManager.enabledAgentTools = currentEnabled
                isSessionSystemPromptSet = false
                applyToolsModeToEngine(settingsManager.toolsMode)
                updateToolsButtonLabel()
            }
            .setPositiveButton("Hotovo") { d, _ ->
                updateToolsButtonLabel()
                Toast.makeText(
                    this,
                    "Aktivní nástroje: ${currentEnabled.size}/${allItems.size} (aplikováno v reálném čase)",
                    Toast.LENGTH_SHORT
                ).show()
                d.dismiss()
            }
            .setNeutralButton("Zapnout vše") { _, _ ->
                val allSet = allItems.map { it.first }.toSet()
                settingsManager.enabledAgentTools = allSet
                isSessionSystemPromptSet = false
                applyToolsModeToEngine(settingsManager.toolsMode)
                updateToolsButtonLabel()
                Toast.makeText(this, "Všech ${allSet.size} nástrojů zapnuto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Jen Web+RAG") { _, _ ->
                val coreSet = setOf("web_search", "rag_search", "browser_research_page", "read_url_content", "realtime_status")
                settingsManager.enabledAgentTools = coreSet
                isSessionSystemPromptSet = false
                applyToolsModeToEngine(settingsManager.toolsMode)
                updateToolsButtonLabel()
                Toast.makeText(this, "Zvolen profil: Web + Bot + RAG (5 nástrojů)", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showToolsHelpDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🛠️ Autonomní nástroje modelu (Agentic Tools)")
            .setMessage(
                "Model má k dispozici 13 vestavěných nástrojů ve 4 oblastech:\n\n" +
                "🌐 Web & Hledání:\n" +
                "• web_search - Paralelní multi-source vyhledávání (DDG, Wikipedie, OpenAlex, arXiv, Scholar).\n" +
                "• read_url_content - Extrakce textu a článků s DataSaver kompresí (Opera Mini styl).\n" +
                "• browser_open / scroll / click / find / back - Virtuální prohlížeč pro navigaci po webu.\n\n" +
                "📁 Souborový Sandbox & Lokální RAG:\n" +
                "• file_write / read / list / delete - Bezpečné úložiště v sandboxu aplikace.\n" +
                "• rag_search - Fulltextové vyhledávání v dokumentech sandboxu bez nutnosti sítě.\n\n" +
                "⏱️ Systémová Telemetrie:\n" +
                "• realtime_status - Přesný čas, časová zóna, baterie, RAM a stav připojení.\n\n" +
                "Režimy:\n" +
                "• AUTO: Nástroje se aktivují automaticky při dotazech na fakta, novinky, soubory či čas.\n" +
                "• ON: Nástroje jsou aktivní v každém dotazu modelu.\n" +
                "• OFF: Čistý offline režim bez volání nástrojů."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("⚙️ Nastavení Web Agenta") { _, _ ->
                val intent = Intent(this, SettingsActivity::class.java).apply {
                    putExtra("open_tab", 5) // Tab Rozšířené (Advanced) s konfigurací Web Agenta a Deep Research
                }
                startActivity(intent)
            }
            .show()
    }

    private fun updateWebPipButtonLabel() {
        if (!::btnWebPipSelector.isInitialized) return
        when (settingsManager.webDisplayMode) {
            SettingsManager.WebDisplayMode.DIRECT -> {
                btnWebPipSelector.text = "🌐 Web: Direct"
                val color = android.graphics.Color.parseColor("#80CBC4")
                btnWebPipSelector.setTextColor(color)
                btnWebPipSelector.strokeColor = android.content.res.ColorStateList.valueOf(color)
            }
            SettingsManager.WebDisplayMode.PIP -> {
                btnWebPipSelector.text = "🌐 Web: PiP"
                val color = android.graphics.Color.parseColor("#00E5FF")
                btnWebPipSelector.setTextColor(color)
                btnWebPipSelector.strokeColor = android.content.res.ColorStateList.valueOf(color)
            }
        }
    }

    private fun showWebPipSelectorDialog() {
        val modes = arrayOf(
            "⚡ Direct (Tichý headless režim na pozadí)",
            "📺 In-Chat PiP (Živé okno prohlížeče v chatu)",
            "ℹ️ Informace o webovém zobrazení..."
        )
        val currentIdx = when (settingsManager.webDisplayMode) {
            SettingsManager.WebDisplayMode.DIRECT -> 0
            SettingsManager.WebDisplayMode.PIP -> 1
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🌐 Režim zobrazení webu")
            .setSingleChoiceItems(modes, currentIdx) { dialog, which ->
                when (which) {
                    0 -> {
                        settingsManager.webDisplayMode = SettingsManager.WebDisplayMode.DIRECT
                        destroyPipWebView()
                        updateWebPipButtonLabel()
                        dialog.dismiss()
                    }
                    1 -> {
                        settingsManager.webDisplayMode = SettingsManager.WebDisplayMode.PIP
                        updateWebPipButtonLabel()
                        dialog.dismiss()
                    }
                    2 -> {
                        dialog.dismiss()
                        showWebPipHelpDialog()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggleWebPipQuick() {
        val nextMode = when (settingsManager.webDisplayMode) {
            SettingsManager.WebDisplayMode.DIRECT -> SettingsManager.WebDisplayMode.PIP
            SettingsManager.WebDisplayMode.PIP -> {
                destroyPipWebView()
                SettingsManager.WebDisplayMode.DIRECT
            }
        }
        settingsManager.webDisplayMode = nextMode
        updateWebPipButtonLabel()
        Toast.makeText(this, "Režim webu: ${nextMode.name}", Toast.LENGTH_SHORT).show()
    }

    private fun showWebPipHelpDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("🌐 Režim zobrazení webu")
            .setMessage(
                "Možnosti zobrazení při práci agenta na webu:\n\n" +
                "1. Direct (Doporučeno pro maximální rychlost):\n" +
                "   Model načítá data z webu na pozadí přes rychlý headless parser. Spotřebovává 0 MB RAM pro vykreslování a data okamžitě předává modelu.\n\n" +
                "2. In-Chat PiP (Picture-in-Picture):\n" +
                "   Přímo v okně chatu se otevře kompaktní WebView, kde vidíte načítanou stránku v reálném čase. " +
                "Jakmile je stránka načtena nebo okno zavřeno křížkem, WebView je kompletně zničeno pro úsporu RAM."
            )
            .setPositiveButton("Rozumím", null)
            .show()
    }

    @androidx.annotation.MainThread
    private fun showPipWithUrl(url: String) {

        // Lazy inflate ViewStub if not yet inflated
        if (pipRootView == null && pipStub != null) {
            try {
                pipRootView = pipStub?.inflate()
                pipContainer = pipRootView?.findViewById(R.id.container_pip_webview)
                pipUrlTv = pipRootView?.findViewById(R.id.tv_pip_url)
                pipProgressBar = pipRootView?.findViewById(R.id.pb_pip_loading)
                pipCloseBtn = pipRootView?.findViewById(R.id.btn_pip_close)

                pipCloseBtn?.setOnClickListener {
                    destroyPipWebView()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inflate PiP ViewStub: ${e.message}")
            }
        }

        pipRootView?.visibility = View.VISIBLE
        pipUrlTv?.text = "🌐 $url"
        pipProgressBar?.visibility = View.VISIBLE

        // Lazy create WebView if not created
        if (pipWebView == null && pipContainer != null) {
            pipContainer?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            val isDataSaver = settingsManager.webDataSaverEnabled
            val wv = android.webkit.WebView(this).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                isFocusable = false
                isFocusableInTouchMode = false
                clearFocus()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    if (isDataSaver) {
                        blockNetworkImage = true
                        loadsImagesAutomatically = false
                    } else {
                        blockNetworkImage = false
                        loadsImagesAutomatically = true
                    }
                    userAgentString = "Mozilla/5.0 (Linux; Android 15; SM-S928B Build/AP3A.240905.015.A2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.7049.38 Mobile Safari/537.36"
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: android.webkit.WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        if (settingsManager.webDataSaverEnabled) {
                            val reqUrl = request?.url?.toString()?.lowercase() ?: return null
                            val blockedExts = listOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg", ".ico", ".mp4", ".webm", ".mp3", ".woff", ".ttf")
                            if (blockedExts.any { reqUrl.contains(it) } ||
                                reqUrl.contains("google-analytics") ||
                                reqUrl.contains("doubleclick") ||
                                reqUrl.contains("facebook.net") ||
                                reqUrl.contains("adnxs")
                            ) {
                                return android.webkit.WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        pipProgressBar?.visibility = View.VISIBLE
                        pipUrlTv?.text = "🌐 Načítám: $pageUrl"
                        view?.evaluateJavascript(
                            "try { Object.defineProperty(navigator, 'webdriver', {get: () => false}); } catch(e) {}",
                            null
                        )
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, pageUrl: String?) {
                        pipProgressBar?.visibility = View.GONE
                        val title = view?.title
                        pipUrlTv?.text = if (!title.isNullOrBlank()) "🌐 $title" else "🌐 $pageUrl"
                        userInputEt.post { userInputEt.requestFocus() }

                        // Automatické zavření cookie lišt a odstranění fixed overlay prvků
                        val autoDismissJs = """
                            (function() {
                                try {
                                    const acceptSelectors = [
                                        '#onetrust-accept-btn-handler', '.cookie-accept', '#accept-cookies', '.cc-accept',
                                        '[aria-label*="cookie" i]', '[aria-label*="accept" i]', '[aria-label*="souhlas" i]',
                                        'button[id*="cookie" i]', 'button[id*="consent" i]', 'button[id*="accept" i]',
                                        'button[class*="cookie" i]', 'button[class*="consent" i]', 'button[class*="accept" i]',
                                        'a[id*="cookie" i]', 'a[class*="cookie" i]', 'a[class*="accept" i]',
                                        '[data-testid*="cookie" i]', '[data-testid*="consent" i]',
                                        '#didomi-notice-agree-button', '#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll'
                                    ];
                                    for (let sel of acceptSelectors) {
                                        let btn = document.querySelector(sel);
                                        if (btn && typeof btn.click === 'function') {
                                            btn.click();
                                            break;
                                        }
                                    }
                                    document.querySelectorAll('div, section, aside').forEach(el => {
                                        let st = window.getComputedStyle(el);
                                        if (st && (st.position === 'fixed' || st.position === 'sticky')) {
                                            let z = parseInt(st.zIndex || '0');
                                            if (z > 999 && el.innerText && (el.innerText.toLowerCase().includes('cookie') || el.innerText.toLowerCase().includes('souhlas') || el.innerText.toLowerCase().includes('privacy'))) {
                                                el.remove();
                                            }
                                        }
                                    });
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(autoDismissJs, null)

                        if (settingsManager.webDataSaverEnabled) {
                            view?.evaluateJavascript(
                                "document.querySelectorAll('img, video, iframe, picture, svg').forEach(e => e.style.display = 'none');",
                                null
                            )
                        }
                    }

                    override fun onReceivedError(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                        pipProgressBar?.visibility = View.GONE
                        pipUrlTv?.text = "⚠️ Chyba načtení: ${error?.description}"
                        userInputEt.post { userInputEt.requestFocus() }
                    }
                }
            }
            pipContainer?.removeAllViews()
            pipContainer?.addView(wv)
            pipWebView = wv
        }

        pipWebView?.loadUrl(url)
        userInputEt.post { userInputEt.requestFocus() }
    }

    @androidx.annotation.MainThread
    private fun destroyPipWebView() {
        try {
            pipWebView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                removeAllViews()
                destroy()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error destroying PiP WebView: ${e.message}")
        }
        pipContainer?.removeAllViews()
        pipWebView = null
        pipRootView?.visibility = View.GONE
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
            serviceManager.connect(asResident = true)
        }
        runGenerationInteractive()
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
        if (lastAppliedSystemMessage != null && lastAppliedSystemMessage != settingsManager.systemMessage) {
            Log.i(TAG, "🔄 User System Prompt changed in Settings. Marking session system prompt for refresh.")
            isSessionSystemPromptSet = false
            if (messages.isNotEmpty() && messages[0].content.startsWith("SYSTEM:")) {
                messages[0].content = "SYSTEM: ${settingsManager.systemMessage}"
                messageAdapter.notifyItemChanged(0)
            }
        }

        val sc = settingsManager.loadSamplingConfig()
        Log.i(TAG, getString(R.string.info_generation_params_log, sc.temperature, sc.topK, sc.topP))
        val promptToSend = buildEffectivePromptToSend(userMsg)

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
                val targetConfig = evaluateContextExpansion(userMsg)
                if (targetConfig != null) {
                    com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                        context = applicationContext,
                        owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                        config = targetConfig
                    ) { /* expanded context in orchestrator */ }
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

                val tp = settingsManager.thinkingProfile
                engine.configureThinking(tp.isEnabled, tp.maxTokens, tp.effort)

                val effectiveSysPrompt = buildEffectiveSystemPrompt(userMsg)
                serviceManager.startGeneration(
                        sessionId = sessionManager.currentSessionId,
                        currentHistory = messages.dropLast(1),
                        prompt = promptToSend,
                        maxTokens = sc.maxTokens,
                        assistantMessageId = assistantMsgId,
                        systemPrompt = effectiveSysPrompt.ifBlank { null }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Configuration Error", e)
            }
        }
    }

    private fun buildEffectiveSystemPrompt(
        userMsg: String,
        architecture: String? = null
    ): String {
        val registry = SkillRegistry.getInstance(this)
        val sysMsg = settingsManager.systemMessage
        val skillsInstruction = registry.getPromptInjection(userMsg)
        val toolsMode = settingsManager.toolsMode
        val toolsEnabled = toolsMode != SettingsManager.ToolsMode.OFF

        val modelCaps = if (::engine.isInitialized) {
            try { engine.getModelCapabilities() } catch (_: Exception) { null }
        } else null
        val adapter = com.example.llama.agent.adapter.ModelAdapterFactory.getAdapter(
            architecture = architecture ?: currentModelArchitecture,
            modelPath = currentModelPath,
            handlerName = modelCaps?.handlerName,
            capabilities = modelCaps
        )

        return adapter.buildSystemPrompt(
            context = this,
            userSystemMessage = sysMsg,
            skillsInstruction = skillsInstruction,
            toolsEnabled = toolsEnabled
        )
    }

    private fun buildEffectivePromptToSend(userMsg: String): String {
        val registry = SkillRegistry.getInstance(this)
        val activeId = registry.activeSkillId
        val toolsMode = settingsManager.toolsMode
        val sysMsg = settingsManager.systemMessage
        val skillsInstruction = registry.getPromptInjection(userMsg)
        val toolsEnabled = toolsMode != SettingsManager.ToolsMode.OFF

        // 1. První uživatelská zpráva relace -> plný System Prompt (včetně dovedností a datumu)
        // je nastaven nativně přes orchestratorEngine.setSystemPrompt() v C++ jádru (s podporou Jinja/ROLE_SYSTEM
        // a automatického sloučení v ai_chat.cpp). Do uživatelské zprávy se posílá čistý userMsg, aby nedocházelo
        // ke zdvojení systémového kontextu v KV cache!
        if (messages.none { it.isUser }) {
            lastAppliedSystemMessage = sysMsg
            lastAppliedSkillId = activeId
            lastAppliedToolsMode = toolsMode
            return userMsg
        }

        // 2. Dynamická změna skillu nebo toolsMode uprostřed existujícího chatu (Mid-Chat Switch)
        val isExplicitSkill = activeId != SkillRegistry.SKILL_AUTO && activeId != SkillRegistry.SKILL_NONE
        val skillChanged = lastAppliedSkillId != null && lastAppliedSkillId != activeId
        val toolsChanged = lastAppliedToolsMode != null && lastAppliedToolsMode != toolsMode
        val sysMsgChanged = lastAppliedSystemMessage != null && lastAppliedSystemMessage != sysMsg
        if (sysMsgChanged && sysMsg.isNotBlank()) {
            lastAppliedSystemMessage = sysMsg
            isSessionSystemPromptSet = false
            return "[Prioritní Systémová Instrukce Uživatele (System Prompt) byla aktualizována — říď se jí s nejvyšší prioritou:]\n$sysMsg\n\n$userMsg"
        }

        if (skillChanged || toolsChanged) {
            lastAppliedSkillId = activeId
            lastAppliedToolsMode = toolsMode

            val noticeParts = mutableListOf<String>()

            if (skillChanged) {
                when {
                    activeId == SkillRegistry.SKILL_NONE -> {
                        noticeParts.add("[System Notification: Skills disabled. Proceed with standard direct responses.]")
                    }
                    isExplicitSkill && skillsInstruction.isNotBlank() -> {
                        val skillName = registry.getSkillById(activeId)?.name ?: activeId
                        noticeParts.add("[System Notification: Active agent skill switched to \"$skillName\". Follow these specialized instructions for subsequent responses:]\n$skillsInstruction")
                    }
                }
            }

            if (toolsChanged) {
                if (toolsEnabled) {
                    noticeParts.add("[System Notification: Autonomous web tools enabled:]\n${com.example.llama.agent.tools.web.WebToolsDispatcher.COMPACT_WEB_TOOL_HINT}")
                } else {
                    noticeParts.add("[System Notification: Autonomous web tools disabled. Rely only on internal offline knowledge.]")
                }
            }

            if (noticeParts.isNotEmpty()) {
                val fullNotice = noticeParts.joinToString("\n\n") + "\n\n"
                return "$fullNotice$userMsg"
            }
        }

        // 3. Režim AUTO: Pokud zpráva obsahuje trigger slova skillu, vloží se direktiva pro daný dotaz
        if (activeId == SkillRegistry.SKILL_AUTO && skillsInstruction.isNotBlank()) {
            val hasSearch = com.example.llama.agent.tools.web.WebToolsDispatcher.hasSearchIntent(userMsg)
            val toolsDirective = if (toolsEnabled && hasSearch && !skillsInstruction.contains("<tools>")) {
                "\n\n" + com.example.llama.agent.tools.web.WebToolsDispatcher.COMPACT_WEB_TOOL_HINT
            } else ""
            return "[System Directive: Triggered specialized skill for this request:]\n$skillsInstruction$toolsDirective\n\n$userMsg"
        }

        // 4. Běžná zpráva (systémový kontext i definice nástrojů jsou zavedeny v KV cache)
        return userMsg
    }

    fun resetEngineContext() {
        stopGeneration()
        lastAppliedSkillId = null
        lastAppliedToolsMode = null
        isSessionSystemPromptSet = false
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                if (::engine.isInitialized && engine.state.value.isModelLoaded) {
                    com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                        context = applicationContext,
                        owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                        config = null
                    ) { orchEngine ->
                        orchEngine.resetContext()
                    }
                    withContext(Dispatchers.Main) { Log.i(TAG, "Engine context reset completed.") }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "resetEngineContext note: ${e.message}")
            }
        }
    }

    private suspend fun evaluateContextExpansion(userMsg: String): com.example.llama.agent.core.EngineConfiguration? {
        val totalChars = messages.sumOf { it.content.length + it.reasoning.length } + userMsg.length
        // Předsazený rozpočtovač tokenů: 2.5 znaku na token + 1024 tokenů rezerva na odpověď
        val hasSearch = com.example.llama.agent.tools.web.WebToolsDispatcher.hasSearchIntent(userMsg)
        val toolReserve = if (hasSearch) 1500 else 0
        val estimatedTokens = (totalChars / 2.5).toInt() + 1024 + toolReserve
        val baseConfig = com.example.llama.agent.core.EngineOrchestrator.activeConfig
        if (baseConfig != null) {
            val currentCtx = if (baseConfig.nCtx <= 0) 8192 else baseConfig.nCtx
            val maxAllowedCtx = 124576
            if (estimatedTokens > currentCtx && currentCtx < maxAllowedCtx) {
                // Zaokrouhlení nahoru na nejbližší násobek 4096
                val targetStep = ((estimatedTokens + 4095) / 4096) * 4096
                val newCtx = targetStep.coerceIn(currentCtx, maxAllowedCtx)
                if (newCtx > currentCtx) {
                    Log.i(TAG, "📈 Dynamické navýšení kontextu: $currentCtx -> $newCtx tokenů (odhad: $estimatedTokens)")
                    settingsManager.contextSize = newCtx
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Navýšení kontextu na $newCtx tokenů...", Toast.LENGTH_SHORT).show()
                    }
                    return baseConfig.copy(nCtx = newCtx)
                }
            }
        }
        // DŮLEŽITÉ: Pokud odhad nepřekračuje currentCtx, vrací null -> ŽÁDNÉ ZMENŠOVÁNÍ!
        return null
    }

    private suspend fun checkAndAdjustContextLimit(): com.example.llama.agent.core.EngineConfiguration? {
        val lastUserMsg = messages.lastOrNull { it.isUser }?.content?.toString() ?: ""
        return evaluateContextExpansion(lastUserMsg)
    }

    private fun runGenerationInteractive() {
        val userMsg = userInputEt.text.toString()
        if (userMsg.isEmpty()) {
            Toast.makeText(this, getString(R.string.info_input_empty), Toast.LENGTH_SHORT).show()
            return
        }
        userInputEt.text = null
        applyDisplaySettings()
        if (lastAppliedSystemMessage != null && lastAppliedSystemMessage != settingsManager.systemMessage) {
            Log.i(TAG, "🔄 User System Prompt changed in Settings. Marking session system prompt for refresh.")
            isSessionSystemPromptSet = false
            if (messages.isNotEmpty() && messages[0].content.startsWith("SYSTEM:")) {
                messages[0].content = "SYSTEM: ${settingsManager.systemMessage}"
                messageAdapter.notifyItemChanged(0)
            }
        }

        val currentSessionId = sessionManager.currentSessionId
        if (lastActiveSessionId != currentSessionId) {
            Log.i(TAG, "Session change detected ($lastActiveSessionId -> $currentSessionId). Invalidating context.")
            lastActiveSessionId = currentSessionId
            isSessionSystemPromptSet = false
            lastAppliedSkillId = null
            lastAppliedToolsMode = null
        }

        val sc = settingsManager.loadSamplingConfig()
        Log.i(TAG, getString(R.string.info_generation_params_log, sc.temperature, sc.topK, sc.topP))
        val promptToSend = buildEffectivePromptToSend(userMsg)
        messages.add(Message(UUID.randomUUID().toString(), StringBuilder(userMsg), true))
        messages.add(
                Message(UUID.randomUUID().toString(), StringBuilder(""), false, isStreaming = true)
        )
        messageAdapter.notifyItemRangeInserted(messages.size - 2, 2)
        val fullResponseBuilder = StringBuilder()

        generationJob =
                lifecycleScope.launch(Dispatchers.Default) {
                    val uiBuffer = StringBuilder()
                    val reasoningBuffer = StringBuilder()
                    val targetConfig = evaluateContextExpansion(userMsg)
                    try {
                        com.example.llama.agent.core.EngineOrchestrator.withForegroundLock(
                            context = applicationContext,
                            owner = com.example.llama.agent.core.EngineOwner.FOREGROUND_CHAT,
                            config = targetConfig
                        ) { orchestratorEngine ->
                            // Dynamické navýšení kontextu: Pokud došlo ke změně nCtx, KV cache byla vyčištěna
                            // a musíme obnovit systémový prompt i dosavadní historii relace (klouzavé okno)!
                            if (targetConfig != null) {
                                Log.i(TAG, "📈 Dynamické navýšení kontextu na ${targetConfig.nCtx}. Obnovuji kontext session.")
                                isSessionSystemPromptSet = false
                            }

                            val toolsMode = settingsManager.toolsMode
                            val toolsEnabled = toolsMode != SettingsManager.ToolsMode.OFF
                            val modelCaps = try { orchestratorEngine.getModelCapabilities() } catch (_: Exception) { null }
                            val modelArch = currentModelArchitecture
                            val adapter = com.example.llama.agent.adapter.ModelAdapterFactory.getAdapter(
                                architecture = modelArch,
                                modelPath = currentModelPath,
                                handlerName = modelCaps?.handlerName,
                                capabilities = modelCaps
                            )
                            Log.i(TAG, "🤖 Active Model Adapter: ${adapter.familyName}, toolsEnabled=$toolsEnabled, arch=$modelArch")

                            if (adapter is com.example.llama.agent.adapter.ModelAgentController) {
                                adapter.configureEngine(orchestratorEngine, toolsEnabled)
                            } else {
                                orchestratorEngine.setToolPolicy(2) // NONE
                                orchestratorEngine.setToolDefinitions("")
                            }

                            // 1. Prefill Native System Prompt on first message of session nebo po navýšení kontextu
                            val isFirstUserMsg = messages.count { it.isUser } <= 1
                            if (isFirstUserMsg || !isSessionSystemPromptSet) {
                                val sysPrompt = buildEffectiveSystemPrompt(userMsg, modelArch)
                                if (sysPrompt.isNotBlank()) {
                                    try {
                                        orchestratorEngine.setSystemPrompt(sysPrompt)
                                        Log.i(TAG, "System Prompt successfully set in llama.cpp engine via ${adapter.familyName} adapter.")
                                        isSessionSystemPromptSet = true
                                    } catch (e: Exception) {
                                        Log.w(TAG, "Could not set native system prompt: ${e.message}")
                                    }
                                }
                            }
                            isSessionSystemPromptSet = true

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
                            val tp = settingsManager.thinkingProfile
                            orchestratorEngine.configureThinking(tp.isEnabled, tp.maxTokens, tp.effort)
                            var startTime = 0L
                            var tokenCount = 0
                            var lastUiUpdate = 0L
                            var lastReasoningUiUpdate = 0L

                            // Pokud došlo k dynamickému navýšení kontextu a máme v relaci historii, obnovíme ji v nově zvětšeném KV okně
                            val effectivePrompt = if (targetConfig != null && messages.count { it.isUser } > 1) {
                                val pastTurns = messages.dropLast(2).takeLast(10).joinToString("\n") { msg ->
                                    val role = if (msg.isUser) "User" else "Assistant"
                                    "$role: ${msg.content.trim()}"
                                }
                                val effectiveSysPrompt = buildEffectiveSystemPrompt(userMsg, modelArch)
                                val sysPrefix = if (effectiveSysPrompt.isNotBlank()) "System: $effectiveSysPrompt\n\n" else ""
                                if (pastTurns.isNotBlank()) {
                                    "$sysPrefix[Kontext konverzace v této relaci:\n$pastTurns]\n\n$userMsg"
                                } else {
                                    promptToSend
                                }
                            } else {
                                promptToSend
                            }

                            // Čistá autonomní AI inference: Uživatelský prompt směřuje IHNED přímo do modelu.
                            // Žádné předřazené síťové zdržení ani preemptivní vyhledávání na webu.
                            // Pokud uživatel připojil soubor nebo se ptá offline, AI reaguje okamžitě v řádu milisekund.
                            // Pokud AI potřebuje externí fakta, zavolá autonomně nástroj (web_search) v agentní smyčce níže.
                            val isFirstTurn = messages.count { it.isUser } <= 1
                            val isFollowUpSearchCmd = userMsg.trim().lowercase() in setOf(
                                "vyhledej to", "najdi to", "hledej to", "prohledej to", "zkus to najít", "zkus to vyhledat", "vyhledej", "hledej"
                            )
                            val prevUserMsgForSearch = if (isFollowUpSearchCmd) {
                                messages.dropLast(2).lastOrNull { it.isUser && it.content.toString().trim().length > 12 }?.content?.toString()?.trim() ?: ""
                            } else ""
                            val effectiveSearchUserMsg = if (prevUserMsgForSearch.isNotBlank()) prevUserMsgForSearch else userMsg
                            val resolvedEffectivePrompt = if (prevUserMsgForSearch.isNotBlank()) {
                                "$effectivePrompt (Téma k vyhledání na webu z předchozí zprávy: $prevUserMsgForSearch)"
                            } else {
                                effectivePrompt
                            }
                            var currentPrompt = if (adapter is com.example.llama.agent.adapter.ModelAgentController) {
                                val sysPrompt = buildEffectiveSystemPrompt(effectiveSearchUserMsg, modelArch)
                                adapter.prepareUserPrompt(resolvedEffectivePrompt, sysPrompt, isFirstTurn)
                            } else {
                                resolvedEffectivePrompt
                            }
                            var hasExecutedTool = false

                            var agentStep = 0
                            val maxAgentSteps = 4
                            val executedToolSignatures = mutableSetOf<String>()
                            var botDeepResearchCompleted = false
                            var lastToolResponseContent = ""

                            while (agentStep < maxAgentSteps) {
                                agentStep++
                                val stepContentBuilder = StringBuilder()
                                val stepReasoningBuilder = StringBuilder()
                                var nativeToolCallId: String? = null
                                var nativeToolName: String? = null
                                val nativeToolArgsBuilder = StringBuilder()
                                var stepStartTime = 0L
                                var stepTokenCount = 0

                                orchestratorEngine.sendUserPromptEvents(currentPrompt, sc.maxTokens)
                                    .collect { event ->
                                        when (event) {
                                            is com.arm.aichat.GenerationEvent.ReasoningDelta -> {
                                                val cleanReasoningText = event.text
                                                if (settingsManager.thinkingProfile == ThinkingProfile.OFF) {
                                                    if (startTime == 0L) startTime = System.currentTimeMillis()
                                                    if (stepStartTime == 0L) stepStartTime = System.currentTimeMillis()
                                                    tokenCount++
                                                    stepTokenCount++
                                                    uiBuffer.append(cleanReasoningText)
                                                    fullResponseBuilder.append(cleanReasoningText)
                                                    stepContentBuilder.append(cleanReasoningText)
                                                    val now = System.currentTimeMillis()
                                                    if (now - lastUiUpdate >= 40L || cleanReasoningText.contains("\n")) {
                                                        val chunk = uiBuffer.toString()
                                                        uiBuffer.setLength(0)
                                                        lastUiUpdate = now
                                                        withContext(Dispatchers.Main) {
                                                             messageAdapter.appendTokenToLastMessage(chunk)
                                                             val stepElapsed = (now - stepStartTime) / 1000.0
                                                             val speed = if (stepElapsed > 0) stepTokenCount / stepElapsed else 0.0
                                                             if (!settingsManager.disableAutoScroll && messages.isNotEmpty()) {
                                                                 messagesRv.post {
                                                                     val count = messageAdapter.itemCount
                                                                     if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                                                 }
                                                             }
                                                             if (settingsManager.showStats)
                                                                 speedTv.text = getString(R.string.info_speed_format, speed)
                                                             if (settingsManager.showTokenCount)
                                                                 tokenCountTv.text = getString(R.string.stats_tokens_format, tokenCount)
                                                             if (settingsManager.showGenTime)
                                                                 genTimeTv.text = getString(R.string.stats_time_format, (now - startTime) / 1000.0)
                                                        }
                                                    }
                                                } else {
                                                if (startTime == 0L) startTime = System.currentTimeMillis()
                                                if (stepStartTime == 0L) stepStartTime = System.currentTimeMillis()
                                                tokenCount++
                                                stepTokenCount++

                                                reasoningBuffer.append(cleanReasoningText)
                                                stepReasoningBuilder.append(cleanReasoningText)
                                                if (toolsEnabled && agentStep < maxAgentSteps &&
                                                    (stepReasoningBuilder.contains("</tool_call>", ignoreCase = true) || stepReasoningBuilder.contains("[/TOOL_CALLS]", ignoreCase = true))
                                                ) {
                                                    if (WebToolsDispatcher.findToolCallInText(stepReasoningBuilder.toString()) != null) {
                                                        Log.i(TAG, "⚡ Detected complete </tool_call> in ReasoningDelta, stopping step generation early to execute tool.")
                                                        orchestratorEngine.cancelGeneration()
                                                    }
                                                }
                                                val now = System.currentTimeMillis()
                                                if (now - lastReasoningUiUpdate >= 40L || cleanReasoningText.contains("\n")) {
                                                    val chunk = reasoningBuffer.toString()
                                                    reasoningBuffer.setLength(0)
                                                    lastReasoningUiUpdate = now

                                                    withContext(Dispatchers.Main) {
                                                        messageAdapter.appendReasoningToLastMessage(chunk)

                                                        val stepElapsed = (now - stepStartTime) / 1000.0
                                                        val speed = if (stepElapsed > 0) stepTokenCount / stepElapsed else 0.0

                                                        if (!settingsManager.disableAutoScroll) {
                                                            if (!messagesRv.canScrollVertically(1) && messages.isNotEmpty()) {
                                                                messagesRv.post {
                                                                    val count = messageAdapter.itemCount
                                                                    if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                                                }
                                                            }
                                                        }
                                                        //TODO: RAM metrika viz setting_vzhled "trvale zobrazit RAM statistiky na pozadí"
                                                        if (settingsManager.showStats)
                                                            speedTv.text = getString(R.string.info_speed_format, speed)
                                                        if (settingsManager.showTokenCount)
                                                            tokenCountTv.text = getString(R.string.stats_tokens_format, tokenCount)
                                                        if (settingsManager.showGenTime)
                                                            genTimeTv.text = getString(R.string.stats_time_format, (now - startTime) / 1000.0)
                                                    }
                                                }
                                                }
                                            }
                                            is com.arm.aichat.GenerationEvent.ContentDelta -> {
                                                val cleanContentText = event.text
                                                if (startTime == 0L) startTime = System.currentTimeMillis()
                                                if (stepStartTime == 0L) stepStartTime = System.currentTimeMillis()
                                                tokenCount++
                                                stepTokenCount++

                                                if (reasoningBuffer.isNotEmpty()) {
                                                    val rChunk = reasoningBuffer.toString()
                                                    reasoningBuffer.setLength(0)
                                                    withContext(Dispatchers.Main) {
                                                        if (settingsManager.thinkingProfile == ThinkingProfile.OFF) {
                                                            messageAdapter.appendTokenToLastMessage(rChunk)
                                                        } else {
                                                        messageAdapter.appendReasoningToLastMessage(rChunk)
                                                        }
                                                    }
                                                }

                                                uiBuffer.append(cleanContentText)
                                                fullResponseBuilder.append(cleanContentText)
                                                stepContentBuilder.append(cleanContentText)
                                                if (toolsEnabled && agentStep < maxAgentSteps) {
                                                    val curContent = stepContentBuilder.toString()
                                                    if ((curContent.contains("</tool_call>", ignoreCase = true) || curContent.contains("[/TOOL_CALLS]", ignoreCase = true)) &&
                                                        WebToolsDispatcher.findToolCallInText(curContent) != null
                                                    ) {
                                                        Log.i(TAG, "⚡ Detected complete </tool_call> in ContentDelta, stopping step generation early to execute tool.")
                                                        orchestratorEngine.cancelGeneration()
                                                    } else if (curContent.length > 220 && !curContent.contains("<tool_call", ignoreCase = true) &&
                                                        com.example.llama.agent.tools.parser.LenientToolCallParser.extractProseToolCall(curContent, effectiveSearchUserMsg) != null
                                                    ) {
                                                        Log.i(TAG, "⚡ Detected prose tool call description in ContentDelta, stopping fake prose to execute real tool.")
                                                        orchestratorEngine.cancelGeneration()
                                                    }
                                                }

                                                val now = System.currentTimeMillis()
                                                if (now - lastUiUpdate >= 40L || cleanContentText.contains("\n")) {
                                                    val chunk = uiBuffer.toString()
                                                    uiBuffer.setLength(0)
                                                    lastUiUpdate = now

                                                    withContext(Dispatchers.Main) {
                                                        messageAdapter.appendTokenToLastMessage(chunk)

                                                        val stepElapsed = (now - stepStartTime) / 1000.0
                                                        val speed = if (stepElapsed > 0) stepTokenCount / stepElapsed else 0.0

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
                                                            genTimeTv.text = getString(R.string.stats_time_format, (now - startTime) / 1000.0)
                                                    }
                                                }
                                            }
                                            is com.arm.aichat.GenerationEvent.Finished -> {
                                                Log.i(TAG, "Generation finished event: reason=${event.reason}, promptTokens=${event.promptTokens}, genTokens=${event.generatedTokens}")
                                            }
                                            is com.arm.aichat.GenerationEvent.ToolCallDelta -> {
                                                nativeToolCallId = event.id
                                                if (!event.name.isNullOrBlank()) nativeToolName = event.name
                                                nativeToolArgsBuilder.append(event.argumentsDelta)
                                            }
                                            is com.arm.aichat.GenerationEvent.Error -> {
                                                Log.e(TAG, "Generation error event: ${event.message}")
                                            }
                                            else -> {}
                                        }
                                    }

                                // Flush residual buffers after this turn
                                if (reasoningBuffer.isNotEmpty()) {
                                    val rChunk = reasoningBuffer.toString()
                                    reasoningBuffer.setLength(0)
                                    withContext(Dispatchers.Main) {
                                        if (settingsManager.thinkingProfile == ThinkingProfile.OFF) {
                                            messageAdapter.appendTokenToLastMessage(rChunk)
                                        } else {
                                        messageAdapter.appendReasoningToLastMessage(rChunk)
                                        }
                                    }
                                }
                                if (uiBuffer.isNotEmpty()) {
                                    val cChunk = uiBuffer.toString()
                                    uiBuffer.setLength(0)
                                    withContext(Dispatchers.Main) {
                                        messageAdapter.appendTokenToLastMessage(cChunk)
                                    }
                                }

                                // Autonomous Agent: Check for tool calls (Native > Content channel only)
                                val toolsMode = settingsManager.toolsMode
                                val toolsEnabled = toolsMode != SettingsManager.ToolsMode.OFF && !botDeepResearchCompleted
                                var toolRequest: ToolCallRequest? = null

                                if (toolsEnabled) {
                                    if (adapter is com.example.llama.agent.adapter.ModelAgentController) {
                                        toolRequest = adapter.extractToolCallEx(
                                            content = stepContentBuilder.toString(),
                                            reasoning = stepReasoningBuilder.toString(),
                                            nativeToolName = nativeToolName,
                                            nativeToolArgs = nativeToolArgsBuilder.toString()
                                        )
                                    } else {
                                        if (!nativeToolName.isNullOrBlank()) {
                                            val argsMap = WebToolsDispatcher.parseArguments(nativeToolArgsBuilder.toString())
                                            toolRequest = ToolCallRequest(nativeToolName!!, argsMap)
                                        } else if (nativeToolArgsBuilder.isNotEmpty()) {
                                            val directCall = WebToolsDispatcher.findToolCallInText(nativeToolArgsBuilder.toString())
                                            if (directCall != null) {
                                                nativeToolName = directCall.name
                                                toolRequest = directCall
                                            }
                                        }
                                        if (toolRequest == null) {
                                            toolRequest = adapter.extractToolCall(
                                                content = stepContentBuilder.toString(),
                                                reasoning = stepReasoningBuilder.toString()
                                            )
                                        }
                                    }
                                    if (toolRequest == null) {
                                        toolRequest = com.example.llama.agent.tools.parser.LenientToolCallParser.extractProseToolCall(
                                            text = stepContentBuilder.toString() + "\n" + stepReasoningBuilder.toString(),
                                            userPromptFallback = effectiveSearchUserMsg
                                        )
                                    }
                                    if (toolRequest == null && agentStep == 1 &&
                                        WebToolsDispatcher.hasSearchIntent(effectiveSearchUserMsg) &&
                                        !userMsg.contains("[Přiložený text") && !userMsg.contains("[Soubor:") &&
                                        WebToolsDispatcher.isToolEnabled("web_search")
                                    ) {
                                        val autoQ = WebToolsDispatcher.extractSearchQueryFromUserPrompt(effectiveSearchUserMsg)
                                        if (autoQ.isNotBlank()) {
                                            Log.i(TAG, "🛡️ Search Intent Guarantee: Model omitted <tool_call> for explicit search request. Auto-invoking web_search('$autoQ').")
                                            toolRequest = ToolCallRequest("web_search", mapOf("query" to autoQ))
                                        }
                                    }
                                }

                                if (toolRequest != null && agentStep < maxAgentSteps) {
                                    hasExecutedTool = true
                                    Log.i(TAG, "🔧 Autonomous Agent Tool Call detected: ${toolRequest.name}, args=${toolRequest.arguments}")
                                    val toolName = toolRequest.name
                                    // Sanitizace dotazu + ochrana proti zkomolení dotazu malým modelem (Query Fidelity Guard)
                                    val currentQueryArg = toolRequest.arguments["query"] ?: toolRequest.arguments["q"] ?: ""
                                    val rawCleanQ = WebToolsDispatcher.extractCleanSearchQuery(toolRequest.arguments, currentQueryArg)
                                    val cleanQ = if (toolName.contains("search", ignoreCase = true) || toolName == "browser_research_page") {
                                        WebToolsDispatcher.reconcileModelQueryWithUserIntent(rawCleanQ, effectiveSearchUserMsg)
                                    } else {
                                        rawCleanQ
                                    }
                                    val finalArgs = if (toolName.contains("search", ignoreCase = true) && cleanQ.isNotBlank()) {
                                        toolRequest.arguments.toMutableMap().apply { put("query", cleanQ) }
                                    } else if (toolName.contains("search", ignoreCase = true) && cleanQ.isBlank()) {
                                        val fallbackQ = WebToolsDispatcher.extractSearchQueryFromUserPrompt(effectiveSearchUserMsg)
                                        Log.w(TAG, "⚠️ Model dodal neplatný search query '$currentQueryArg', nahrazeno fallbackem: '$fallbackQ'")
                                        toolRequest.arguments.toMutableMap().apply { put("query", fallbackQ) }
                                    } else {
                                        toolRequest.arguments
                                    }
                                    toolRequest = ToolCallRequest(toolName, finalArgs)

                                    val toolSig = "${toolName.lowercase()}:${cleanQ.lowercase().trim()}"
                                    if (executedToolSignatures.contains(toolSig)) {
                                        Log.w(TAG, "🔁 Loop Guard: Model attempted duplicate tool call '$toolSig'. Breaking agent loop to prevent cycle.")
                                        break
                                    }
                                    executedToolSignatures.add(toolSig)

                                    val isFileAnalysis = userMsg.contains("[Přiložený text") || userMsg.contains("[Soubor:")
                                    val queryLooksLikeFile = cleanQ.contains("[Soubor:") || cleanQ.contains("[Přiložený text") ||
                                            cleanQ.endsWith(".txt") || cleanQ.endsWith(".kt") || cleanQ.endsWith(".json") || cleanQ.endsWith(".pdf") || cleanQ.endsWith(".csv")
                                    val shouldSuppressWebSearchForFile = toolName.contains("search", ignoreCase = true) &&
                                            isFileAnalysis && (queryLooksLikeFile || !WebToolsDispatcher.hasSearchIntent(userMsg))

                                    // In-Chat PiP Web display if enabled (pouze pro reálné webové dotazy a vyhledávání)
                                    val isWebNavigationTool = toolName in listOf("web_search", "read_url_content", "browser_open", "browser_research_page", "academic_patent_search")
                                    if (settingsManager.webDisplayMode == SettingsManager.WebDisplayMode.PIP && isWebNavigationTool && !shouldSuppressWebSearchForFile) {
                                        val pipTargetUrl = if (toolName == "read_url_content" || toolName == "browser_open") {
                                            val u = (toolRequest.arguments["url"] ?: "").trim()
                                            if (u.isNotBlank() && u != "{}" && u != "null" && (u.startsWith("http://") || u.startsWith("https://"))) u else ""
                                        } else {
                                            val q = cleanQ.ifBlank { (toolRequest.arguments["query"] ?: toolRequest.arguments["q"] ?: "").trim() }
                                            if (q.isNotBlank() && q != "{}" && q != "[]" && q != "null") {
                                                val encodedQ = java.net.URLEncoder.encode(q, "UTF-8")
                                                val topEngine = settingsManager.webSearchEnginesOrder.firstOrNull { settingsManager.webEnabledEngines.contains(it) } ?: settingsManager.webPrimarySearchEngine
                                                when (topEngine.lowercase().trim()) {
                                                    "brave" -> "https://search.brave.com/search?q=$encodedQ"
                                                    "google", "google_lite" -> "https://www.google.com/search?q=$encodedQ"
                                                    "searxng" -> "https://searx.be/search?q=$encodedQ"
                                                    "seznam" -> "https://search.seznam.cz/?q=$encodedQ"
                                                    "wikipedia" -> "https://cs.wikipedia.org/wiki/Special:Search?search=$encodedQ"
                                                    else -> "https://duckduckgo.com/?q=$encodedQ"
                                                }
                                            } else ""
                                        }
                                        if (pipTargetUrl.isNotBlank()) {
                                            withContext(Dispatchers.Main) {
                                                showPipWithUrl(pipTargetUrl)
                                            }
                                        }
                                    }

                                    // Clean up assistant bubble for fresh final answer synthesis (preserving step reasoning)
                                    withContext(Dispatchers.Main) {
                                        val lastAssistant = messages.lastOrNull { !it.isUser }
                                        if (lastAssistant != null) {
                                            // Pokud reasoning již obsahuje uvažování modelu (z LOGi / ReasoningDelta), uchováme ho čisté.
                                            // Pouze pro modely bez uvažovacího kanálu přeneseme případný text z contentu:
                                            if (lastAssistant.reasoning.isBlank() && lastAssistant.content.isNotBlank()) {
                                                val stepThoughts = com.example.llama.agent.tools.web.WebToolsDispatcher.cleanToolCallMarkup(lastAssistant.content).trim()
                                                if (stepThoughts.isNotBlank() && stepThoughts.length <= 350) {
                                                    lastAssistant.reasoning = stepThoughts
                                                }
                                            }
                                            lastAssistant.content = ""
                                            lastAssistant.isThoughtsExpanded = true
                                            lastAssistant.isThoughtsCollapsedByUser = false
                                            if (lastAssistant.reasoning.isNotBlank()) {
                                                lastAssistant.reasoning = com.example.llama.agent.tools.web.WebToolsDispatcher.cleanToolCallMarkup(lastAssistant.reasoning)
                                            }
                                            val idx = messages.indexOf(lastAssistant)
                                            if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                        }
                                    }

                                    val toolResponse = if (shouldSuppressWebSearchForFile) {
                                        Log.i(TAG, "🛡️ Suppressing web search for local attached document. Returning instant local guidance.")
                                        com.example.llama.agent.tools.web.ToolCallResponse(
                                            toolName = toolName,
                                            queryOrUrl = cleanQ,
                                            content = "Tento soubor je přiložen lokálně přímo v uživatelském promptu výše. Není na veřejném internetu. Zpracuj a analyzuj přímo poskytnutý text z promptu bez volání webových nástrojů.",
                                            results = emptyList<com.example.llama.agent.tools.web.WebSearchResult>(),
                                            isSuccess = true
                                        )
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            val rawArgs = if (nativeToolArgsBuilder.isNotEmpty() && finalArgs == toolRequest.arguments) {
                                                nativeToolArgsBuilder.toString()
                                            } else {
                                                org.json.JSONObject(toolRequest.arguments as Map<*, *>).toString()
                                            }
                                            WebToolsDispatcher.executeTool(
                                                name = toolName,
                                                rawArgs = rawArgs,
                                                maxResults = settingsManager.webSearchMaxResults,
                                                maxTokens = settingsManager.webExtractTokenLimit,
                                                userPromptFallback = userMsg
                                            )
                                        }
                                    }

                                    if (toolResponse.content.isNotBlank()) {
                                        lastToolResponseContent = toolResponse.content
                                    }
                                    if (toolName in listOf("web_search", "browser_research_page", "academic_patent_search") &&
                                        toolResponse.isSuccess && toolResponse.content.length > 160
                                    ) {
                                        botDeepResearchCompleted = true
                                    }

                                    // Add SearchReport to assistant message UI
                                    withContext(Dispatchers.Main) {
                                        val lastAssistant = messages.lastOrNull { !it.isUser }
                                        if (lastAssistant != null && (toolResponse.results.isNotEmpty() || lastAssistant.searchReports.isEmpty())) {
                                            val reportQuery = if (toolResponse.queryOrUrl.isNotBlank()) toolResponse.queryOrUrl else toolRequest.name
                                            lastAssistant.searchReports.add(
                                                SearchReport(
                                                    query = reportQuery,
                                                    results = toolResponse.results,
                                                    isExpanded = false
                                                )
                                            )
                                            val idx = messages.indexOf(lastAssistant)
                                            if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                        }
                                        if (!settingsManager.disableAutoScroll && messages.isNotEmpty()) {
                                            messagesRv.post {
                                                val count = messageAdapter.itemCount
                                                if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                            }
                                        }
                                    }

                                    val isFinalStep = (agentStep >= maxAgentSteps - 1)
                                    val czechDateNow = WebToolsDispatcher.getCzechCurrentDateTimeString()
                                    val callId = nativeToolCallId ?: "call_00000001"
                                    val tName = nativeToolName ?: toolRequest.name
                                    var submittedNative = false

                                    // Lazy uvolnění sampleru: Ihned po vykonání nástroje držíme čisté samplování (POLICY_NONE = 2)
                                    orchestratorEngine.setToolPolicy(2) // NONE
                                    if (botDeepResearchCompleted) {
                                        val currentTp = settingsManager.thinkingProfile
                                        if (currentTp != ThinkingProfile.OFF) {
                                            orchestratorEngine.configureThinking(true, 96, "low")
                                        }
                                    }

                                    currentPrompt = if (submittedNative) {
                                        Log.i(TAG, "⚡ Scenario A: Native tool result committed to KV. Continuing with empty prompt at full speed.")
                                        ""
                                    } else {
                                        Log.i(TAG, "📄 Scenario B (${adapter.familyName}): Textual tool response injected into prompt.")
                                        adapter.formatToolResponse(
                                            userMsg = userMsg,
                                            toolResponse = toolResponse,
                                            currentDateTime = czechDateNow
                                        )
                                    }
                                } else {
                                    break
                                }
                            }

                            // Pojistka: Pokud model po web vyhledávání vrátil pouze tool_call nebo <think> a obsah odpovědi pro uživatele zůstal prázdný
                            val lastAssistantCheck = messages.lastOrNull { !it.isUser }
                            val cleanContent = lastAssistantCheck?.content?.let { com.example.llama.agent.tools.web.WebToolsDispatcher.cleanToolCallMarkup(it).trim() } ?: ""
                            if (hasExecutedTool && cleanContent.isBlank()) {
                                Log.i(TAG, "🔧 Agent completed tools but visible answer content is blank. Running final direct answer synthesis pass (thinking OFF)...")
                                withContext(Dispatchers.Main) {
                                    lastAssistantCheck?.content = ""
                                    val idx = messages.indexOf(lastAssistantCheck)
                                    if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                }
                                orchestratorEngine.setToolPolicy(2) // NONE: Disable tools for final answer synthesis
                                orchestratorEngine.configureThinking(false, 0, "none")
                                val finalPrompt = "Nyní napiš přímo finální odpověď v češtině na dotaz \"$userMsg\" podle výše uvedených výsledků z webu (bez <think> a bez volání nástrojů):"

                                val synthUiBuffer = StringBuilder()
                                val synthReasoningBuffer = StringBuilder()

                                orchestratorEngine.sendUserPromptEvents(finalPrompt, sc.maxTokens)
                                    .collect { event ->
                                        when (event) {
                                            is com.arm.aichat.GenerationEvent.ContentDelta -> {
                                                val cleanContentText = event.text.replace("\uFFFD\uFE0F", "").replace("\uFFFD", "")
                                                tokenCount++
                                                synthUiBuffer.append(cleanContentText)
                                                fullResponseBuilder.append(cleanContentText)

                                                val now = System.currentTimeMillis()
                                                if (now - lastUiUpdate >= 40L || cleanContentText.contains("\n")) {
                                                    val chunk = synthUiBuffer.toString()
                                                    synthUiBuffer.setLength(0)
                                                    lastUiUpdate = now
                                                    withContext(Dispatchers.Main) {
                                                        messageAdapter.appendTokenToLastMessage(chunk)
                                                        val elapsedTime = (now - startTime) / 1000.0
                                                        val speed = if (elapsedTime > 0) tokenCount / elapsedTime else 0.0
                                                        if (settingsManager.showStats)
                                                            speedTv.text = getString(R.string.info_speed_format, speed)
                                                        if (settingsManager.showTokenCount)
                                                            tokenCountTv.text = getString(R.string.stats_tokens_format, tokenCount)
                                                        if (settingsManager.showGenTime)
                                                            genTimeTv.text = getString(R.string.stats_time_format, elapsedTime)
                                                    }
                                                }
                                            }
                                            is com.arm.aichat.GenerationEvent.ReasoningDelta -> {
                                                val cleanReasoningText = event.text
                                                synthReasoningBuffer.append(cleanReasoningText)
                                                fullResponseBuilder.append(cleanReasoningText)
                                                val now = System.currentTimeMillis()
                                                if (now - lastUiUpdate >= 40L || cleanReasoningText.contains("\n")) {
                                                    val chunk = synthReasoningBuffer.toString()
                                                    synthReasoningBuffer.setLength(0)
                                                    lastUiUpdate = now
                                                    withContext(Dispatchers.Main) {
                                                        messageAdapter.appendReasoningToLastMessage(chunk)
                                                    }
                                                }
                                            }
                                            else -> {}
                                        }
                                    }

                                if (synthReasoningBuffer.isNotEmpty()) {
                                    val chunk = synthReasoningBuffer.toString()
                                    synthReasoningBuffer.setLength(0)
                                    withContext(Dispatchers.Main) {
                                        messageAdapter.appendReasoningToLastMessage(chunk)
                                    }
                                }
                                if (synthUiBuffer.isNotEmpty()) {
                                    val chunk = synthUiBuffer.toString()
                                    synthUiBuffer.setLength(0)
                                    withContext(Dispatchers.Main) {
                                        messageAdapter.appendTokenToLastMessage(chunk)
                                    }
                                }
                            }

                            val lastAssistantFin = messages.lastOrNull { !it.isUser }
                            if (lastAssistantFin != null && hasExecutedTool) {
                                val cleaned = com.example.llama.agent.tools.web.WebToolsDispatcher.cleanToolCallMarkup(lastAssistantFin.content).trim()
                                if (cleaned.isBlank()) {
                                    val nonEmptyReport = lastAssistantFin.searchReports.lastOrNull { it.results.isNotEmpty() } ?: lastAssistantFin.searchReports.lastOrNull()
                                    val fallbackSummary = if (lastToolResponseContent.isNotBlank()) {
                                        lastToolResponseContent
                                    } else if (nonEmptyReport != null && nonEmptyReport.results.isNotEmpty()) {
                                        buildString {
                                            append("Nalezené ověřené informace z webu (${nonEmptyReport.query}):\n\n")
                                            nonEmptyReport.results.take(4).forEach { res ->
                                                append("- **${res.title}**: ${res.snippet}\n")
                                            }
                                        }
                                    } else {
                                        ""
                                    }
                                    if (fallbackSummary.isNotBlank()) {
                                        withContext(Dispatchers.Main) {
                                            lastAssistantFin.content = fallbackSummary
                                            val idx = messages.indexOf(lastAssistantFin)
                                            if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                        }
                                    }
                                } else if (cleaned != lastAssistantFin.content.trim() && cleaned.isNotBlank()) {
                                    withContext(Dispatchers.Main) {
                                        lastAssistantFin.content = cleaned
                                        val idx = messages.indexOf(lastAssistantFin)
                                        if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                    }
                                }
                            }

                            val fullResponse = fullResponseBuilder.toString()
                            Log.i(TAG, "Generation complete. Total length: ${fullResponse.length}")
                            val currentTp = settingsManager.thinkingProfile
                            orchestratorEngine.configureThinking(currentTp.isEnabled, currentTp.maxTokens, currentTp.effort)
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        Log.i(TAG, "Generation cancelled: ${e.message}")
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Generation Error", e)
                    } finally {
                        generationJob = null
                        try {
                            if (::engine.isInitialized) {
                                val currentTp = settingsManager.thinkingProfile
                                engine.configureThinking(currentTp.isEnabled, currentTp.maxTokens, currentTp.effort)
                            }
                        } catch (_: Exception) {}
                        withContext(Dispatchers.Main) {
                            if (reasoningBuffer.isNotEmpty()) {
                                val rChunk = reasoningBuffer.toString()
                                reasoningBuffer.setLength(0)
                                if (settingsManager.thinkingProfile == ThinkingProfile.OFF) {
                                    messageAdapter.appendTokenToLastMessage(rChunk)
                                } else {
                                    messageAdapter.appendReasoningToLastMessage(rChunk)
                                }
                            }
                            if (uiBuffer.isNotEmpty()) {
                                val cChunk = uiBuffer.toString()
                                uiBuffer.setLength(0)
                                messageAdapter.appendTokenToLastMessage(cChunk)
                            }
                            if (messages.isNotEmpty()) {
                                messageAdapter.setMessageStreaming(messages.last().id, false)
                                val lastAssistant = messages.lastOrNull { !it.isUser }
                                if (lastAssistant != null) {
                                    val (extractedContent, extractedThought) = com.example.llama.agent.tools.web.WebToolsDispatcher.extractCleanContentAndReasoning(lastAssistant.content)
                                    if (extractedContent.isNotBlank()) {
                                        lastAssistant.content = extractedContent
                                    }
                                    if (extractedThought != null && extractedThought.isNotBlank()) {
                                        if (lastAssistant.reasoning.isBlank()) {
                                            lastAssistant.reasoning = extractedThought
                                        } else {
                                            lastAssistant.reasoning += "\n" + extractedThought
                                        }
                                    } else if (lastAssistant.reasoning.isNotBlank()) {
                                        lastAssistant.reasoning = com.example.llama.agent.tools.web.WebToolsDispatcher.cleanToolCallMarkup(lastAssistant.reasoning)
                                    }
                                    // Záchrana prázdného obsahu v UI: Pokud content zůstal prázdný, ale model vygeneroval odpověď uvnitř reasoning (např. neuzavřený think tag)
                                    if (lastAssistant.content.isBlank() && lastAssistant.reasoning.isNotBlank()) {
                                        val rText = lastAssistant.reasoning.trim()
                                        val answerSplitRegex = Regex("""(?m)^(?:\s*(?:Odpověď|Shrnutí|Závěr|Zde je|Celkově|Podle|Na základě|Výsledek|Answer|Summary|Conclusion|Here is|Based on|In summary)[:\s]|#{1,4}\s+)""")
                                        val match = answerSplitRegex.find(rText)
                                        if (match != null && match.range.first > 0) {
                                            val thoughtPart = rText.substring(0, match.range.first).trim()
                                            val answerPart = rText.substring(match.range.first).trim()
                                            lastAssistant.reasoning = thoughtPart
                                            lastAssistant.content = answerPart
                                            Log.i(TAG, "Split unclosed reasoning into thought (${thoughtPart.length} chars) and answer (${answerPart.length} chars)")
                                        } else {
                                            val paragraphs = rText.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
                                            if (paragraphs.size >= 2) {
                                                val thoughtPart = paragraphs.dropLast(1).joinToString("\n\n")
                                                val answerPart = paragraphs.last()
                                                lastAssistant.reasoning = thoughtPart
                                                lastAssistant.content = answerPart
                                                Log.i(TAG, "Split unclosed reasoning by paragraphs: thought (${thoughtPart.length} chars), answer (${answerPart.length} chars)")
                                            } else {
                                                Log.w(TAG, "⚠️ Final check: single reasoning block, promoting to content while preserving thought.")
                                                lastAssistant.content = rText
                                            }
                                        }
                                    }
                                    // Sbalíme myšlenkový proces po dokončení odpovědi, aby uživatel ihned nahoře viděl výslednou odpověď a souhrn z webu
                                    lastAssistant.isThoughtsExpanded = lastAssistant.content.isBlank()
                                    lastAssistant.isThoughtsCollapsedByUser = lastAssistant.content.isNotBlank()
                                    val idx = messages.indexOf(lastAssistant)
                                    if (idx != -1) messageAdapter.notifyItemChanged(idx, MessageAdapter.PAYLOAD_TEXT_UPDATE)
                                }
                            }
                            sessionManager.saveCurrentState()
                            updateSendButtonState()
                            if (!settingsManager.disableAutoScroll && messages.isNotEmpty()) {
                                messagesRv.post {
                                    val count = messageAdapter.itemCount
                                    if (count > 0) messagesRv.smoothScrollToPosition(count - 1)
                                }
                            }
                        }
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



    fun stopGeneration() {
        val wasGeneratingViaService =
                serviceManager.generationState.value is GenerationState.Generating
        val wasGeneratingInteractive = generationJob?.isActive == true
        val wasGenerating = wasGeneratingViaService || wasGeneratingInteractive

        if (wasGeneratingViaService) {
            serviceManager.stopGeneration()
        }
        generationJob?.cancel()
        generationJob = null
        if (::engine.isInitialized) {
            engine.cancelGeneration()
        }
        updateSendButtonState()
        if (wasGenerating) {
            Toast.makeText(this, getString(R.string.msg_generation_stopped), Toast.LENGTH_SHORT).show()
        }
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

        // Initialize stats text only if not already displaying valid metrics
        if (settingsManager.showStats && (speedTv.text.isNullOrBlank() || speedTv.text == "..")) {
            speedTv.text = getString(R.string.info_speed_format, 0.0)
        }
        if (settingsManager.showTokenCount && (tokenCountTv.text.isNullOrBlank() || tokenCountTv.text == "..")) {
            tokenCountTv.text = getString(R.string.stats_tokens_format, 0)
        }
        if (settingsManager.showGenTime && (genTimeTv.text.isNullOrBlank() || genTimeTv.text == "..")) {
            genTimeTv.text = getString(R.string.stats_time_format, 0.0)
        }

        messageAdapter.renderMarkdown = settingsManager.renderMarkdown
        messageAdapter.enableBubbleCopy = settingsManager.enableBubbleCopy
        messageAdapter.chatStyle = settingsManager.chatStyle
        messageAdapter.fontSizeSp = settingsManager.chatFontSizeSp
        messageAdapter.showThoughts = settingsManager.showThoughtInProgress
        messageAdapter.thinkingProfile = settingsManager.thinkingProfile

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
        destroyPipWebView()
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
