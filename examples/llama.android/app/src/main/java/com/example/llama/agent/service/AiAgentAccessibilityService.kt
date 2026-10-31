package com.example.llama.agent.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ServiceLifecycleDispatcher
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.example.llama.SettingsManager
import com.example.llama.agent.core.ActionExecutor
import com.example.llama.agent.core.ActionResultBus
import com.example.llama.agent.core.AgentCoordinator
import com.example.llama.agent.core.AppLayoutCache
import com.example.llama.agent.core.AppResolutionEngine
import com.example.llama.agent.core.EngineProvider
import com.example.llama.agent.core.InstalledAppsRegistry
import com.example.llama.agent.core.SafetyGuard
import com.example.llama.agent.core.ScreenContextMemory
import com.example.llama.agent.core.ScreenParser
import com.example.llama.agent.core.SystemController
import com.example.llama.agent.core.SystemToggleController
import com.example.llama.agent.core.VoipCallController
import com.example.llama.agent.irismotion.IrisMotionController
import com.example.llama.agent.macro.MacroEngine
import com.example.llama.agent.macro.MacroParser
import com.example.llama.agent.models.AgentAction
import com.example.llama.agent.models.AgentMode
import com.example.llama.agent.perception.AudioPerceptionEngine
import com.example.llama.agent.perception.PerceptionEngine
import com.example.llama.agent.perception.PerceptionStateRepository
import com.example.llama.agent.ui.FloatingAgentButton
import com.example.llama.agent.voice.AgentVoiceSTT
import com.example.llama.agent.voice.AgentVoiceSTT.STTState
import com.example.llama.agent.voice.AgentVoiceTTS
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * AI Agent Accessibility Service - Heart of the Agent
 *
 * Tento service běží na system-level a má plný přístup k UI všech aplikací. Je zodpovědný za:
 * - Listening na UI eventy (změny oken, kliknutí)
 * - Poskytování UI tree pro ScreenParser
 * - Vykonávání akcí (kliknutí, scrollování)
 *
 * SECURITY: Service automaticky blokuje citlivé aplikace (banking, passwords)
 *
 * COMPLIANCE & PRIVACY: This service is an Assistive Technology designed to help users control
 * their device via voice commands processed locally on-device.
 * - It does NOT collect personal data for cloud processing (100% offline).
 * - It respects Android Accessibility API Policy guidelines.
 * - Used strictly with explicit user consent via System Settings.
 */
class AiAgentAccessibilityService : AccessibilityService(), LifecycleOwner {

    private val dispatcher = ServiceLifecycleDispatcher(this)
    // [RESTORED] Manual Lifecycle Registry for precise control (fixing unused import
    // androidx.lifecycle.LifecycleRegistry)
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry


    /** Debounce Job pro restart STT po změně nastavení — prevence vícenásobného restartu. */
    private var sttSettingsRestartJob: Job? = null

    private val sttRestartReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == "com.example.llama.ACTION_RESTART_STT") {
                android.util.Log.i(TAG, "🔄 Restarting TranscriptAgent on Settings update...")

                // KRITICKÉ: Debounce — při rychlých změnách nastavení zahodíme předchozí restart
                sttSettingsRestartJob?.cancel()
                sttSettingsRestartJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    // 1. Zastavíme aktuálního agenta
                    transcriptAgent?.stop()

                    // 2. Zastavíme FG Service a uvolníme MediaProjection + AudioRecord
                    //    NUTNÉ: bez toho nový agent dostane STOPPED AudioRecord → hluchý engine
                    stopTranscriptForegroundService()

                    // 3. Čekáme na async JNI cleanup + onDestroy() FG service
                    //    300ms: JNI dealokace Sherpa/Whisper C++ objektů
                    //    Pozn: onDestroy() FG service se volá asynchronně po stopService()
                    kotlinx.coroutines.delay(400L)

                    // 4. Nová instance agenta (čistý stav)
                    transcriptAgent = com.example.llama.agent.perception.TranscriptAgent(
                        applicationContext,
                        settingsManager
                    )

                    // 5. Restart dle aktuálního zdroje (spustí consent dialog pokud system_audio)
                    startTranscriptWithSource()
                }
            }
        }
    }

    /**
     * BroadcastReceiver pro komunikaci s TranscriptForegroundService.
     * Přijímá výsledky MediaProjection flow:
     * - ACTION_SYSTEM_AUDIO_READY → AudioRecord je připraven, spustíme TranscriptAgent
     * - ACTION_SYSTEM_AUDIO_FAILED → Fallback na mikrofon
     * - ACTION_SYSTEM_AUDIO_STOPPED → Systém ukončil session (zamčení, jiná app)
     */
    private val systemAudioReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_READY -> {
                    val record = com.example.llama.agent.perception.TranscriptForegroundService.virtualAudioRecord
                    if (record != null) {
                        Log.i(TAG, "✅ System Audio ready — spouštím TranscriptAgent s Virtual AudioRecord")
                        
                        // Zkontrolujeme, zda diktafon nezanechal svůj profil
                        val profileToUse = pendingDictaphoneProfile
                        pendingDictaphoneProfile = null // Hned resetujeme
                        
                        if (profileToUse != null) {
                            Log.i(TAG, "🎯 Zjištěn Dictaphone profil! Používám ho místo výchozího.")
                            transcriptAgent?.start(record, profileToUse)
                        } else {
                            transcriptAgent?.start(record)
                        }
                    } else {
                        Log.e(TAG, "❌ System Audio READY broadcast ale AudioRecord je null! Fallback na mic.")
                        settingsManager.transcriptSource = "mic"
                        transcriptAgent?.start(null)
                    }
                    omniControlNotifier?.showOrUpdateNotification()
                }
                com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_FAILED -> {
                    val reason = intent.getStringExtra("reason") ?: "Neznámý důvod"
                    Log.e(TAG, "❌ System Audio selhalo: $reason → Fallback na mic")
                    showDiagnosticToast("Systémový zvuk selhal: $reason")
                    settingsManager.transcriptSource = "mic"
                    transcriptAgent?.start(null)
                    omniControlNotifier?.showOrUpdateNotification()
                }
                com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_STOPPED -> {
                    Log.i(TAG, "🔄 System Audio session ukončena systémem → zastavuji TranscriptAgent")
                    transcriptAgent?.stop()
                    // Reinicializujeme na mic pokud je transcript stále zapnutý
                    if (settingsManager.isTranscriptEnabled) {
                        settingsManager.transcriptSource = "mic"
                        transcriptAgent?.start(null)
                        showDiagnosticToast("Systémový zvuk byl ukončen — přepnuto na mikrofon")
                    }
                    omniControlNotifier?.showOrUpdateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        dispatcher.onServicePreSuperOnCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        super.onCreate()

        // Registrace sttRestartReceiver
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            sttRestartReceiver,
            android.content.IntentFilter("com.example.llama.ACTION_RESTART_STT"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Registrace systemAudioReceiver pro TranscriptForegroundService komunikaci
        val systemAudioFilter = android.content.IntentFilter().apply {
            addAction(com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_READY)
            addAction(com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_FAILED)
            addAction(com.example.llama.agent.perception.TranscriptForegroundService.ACTION_SYSTEM_AUDIO_STOPPED)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            systemAudioReceiver,
            systemAudioFilter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Useful for debugging or strict component isolation scenarios. */
    fun getDirectInferenceEngine(): InferenceEngine {
        return AiChat.getInferenceEngine(applicationContext)
    }

    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e(TAG, "🔥 Uncaught exception in serviceScope: ${exception.message}", exception)
    }
    val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)

    // Core components — NULLABLE for lazy initialization
    // Inicializovány jen při isAgentEnabled = true
    private var coordinator: AgentCoordinator? = null
    private var screenParser: ScreenParser? = null
    private var screenParserTimeoutJob: Job? = null

    /**
     * Vzbudí ScreenParser podle nastaveného módu.
     * OFF -> nedělá nic.
     * ON -> inicializuje napořád.
     * AUTO -> inicializuje, ale po 30s nečinnosti se uspí (zahodí instanci do null).
     */
    private fun wakeUpScreenParser() {
        if (!settingsManager.isAgentEnabled && !settingsManager.isIrisMotionEnabled) return
        
        when (settingsManager.screenParserMode) {
            SettingsManager.ScreenParserMode.OFF -> {
                screenParser = null
                screenParserTimeoutJob?.cancel()
                return
            }
            SettingsManager.ScreenParserMode.ON -> {
                if (screenParser == null) {
                    screenParser = ScreenParser(settingsManager)
                    Log.i(TAG, "ScreenParser PROBUZEN (ON mód).")
                }
                screenParserTimeoutJob?.cancel()
            }
            SettingsManager.ScreenParserMode.AUTO -> {
                if (screenParser == null) {
                    screenParser = ScreenParser(settingsManager)
                    Log.i(TAG, "ScreenParser PROBUZEN (AUTO mód).")
                }
                screenParserTimeoutJob?.cancel()
                screenParserTimeoutJob = serviceScope.launch {
                    kotlinx.coroutines.delay(30_000)
                    screenParser = null
                    Log.i(TAG, "ScreenParser USPÁN (AUTO mód timeout).")
                }
            }
        }
    }
    private var actionExecutor: ActionExecutor? = null
    private var safetyGuard: SafetyGuard? = null
    private var systemController: SystemController? = null
    private var macroEngine: MacroEngine? = null
    var voipController: VoipCallController? = null

    // Perception Engine — Vision (Track B) + Audio (YAMNet)
    // Lazy init spolu s coordinator (isAgentEnabled = true)
    private var perceptionRepository: PerceptionStateRepository? = null
    private var perceptionEngine: PerceptionEngine? = null
    private var audioPerceptionEngine: AudioPerceptionEngine? = null
    var systemToggleController: SystemToggleController? = null
    lateinit var settingsManager: SettingsManager // CHECK: Public pro přístup z ActionExecutor

    // Multimodal Vision Components
    val visionManager: com.example.llama.agent.vision.VisionSourceManager by lazy {
        com.example.llama.agent.vision.VisionSourceManager()
    }
    private var smartVisionOverlay: com.example.llama.agent.vision.SmartVisionOverlayManager? = null
    private var liveVideoStreamer:
            com.example.llama.agent.vision.VisionSourceManager.LiveVideoStreamer? =
            null
    private var smartVisionCameraProvider: androidx.camera.lifecycle.ProcessCameraProvider? = null
    private var cameraCoordinator: com.example.llama.agent.vision.camera.core.CameraCoordinator? = null
    private var smartVisionImageAnalysis: androidx.camera.core.ImageAnalysis? = null
    private var lastCameraFrame: android.graphics.Bitmap? = null

    // Perception & Audio components
    var transcriptAgent: com.example.llama.agent.perception.TranscriptAgent? = null
        private set
    private var systemAudioInterceptor: com.example.llama.agent.perception.SystemAudioInterceptor? =
            null

    // Voice components — NULLABLE for lazy initialization
    var voiceTTS: AgentVoiceTTS? = null
        private set
    var voiceSTT: AgentVoiceSTT? = null
        private set

    // UI components
    private var floatingButton: FloatingAgentButton? = null
    private var agentNotifier: com.example.llama.agent.ui.AgentNotifier? = null
    private var smartCaptionOverlay: com.example.llama.agent.ui.SmartCaptionOverlayManager? = null

    // Omni-Control Notifikace
    private var omniControlNotifier: com.example.llama.agent.ui.OmniControlNotificationHelper? =
            null

    // IrisMotion — nezávislý lifecycle (není vázáno na agent_enabled)
    private var irisController: IrisMotionController? = null
    // Transmiter je nyní ve ForegroundService, komunikujeme přes HidBridge
    private var remoteOrchestrator: com.example.llama.agent.irismotion.bluetooth.RemoteGestureOrchestrator? = null

    // State management
    private val _agentMode = MutableStateFlow<AgentMode>(AgentMode.Idle)
    val agentMode: StateFlow<AgentMode> = _agentMode.asStateFlow()

    private var wakeWordUpdateJob: Job? = null
    /** Debounce Job pro transcript toggle — prevence SIGSEGV z rapid ON/OFF */
    private var transcriptToggleJob: Job? = null

    /** Dočasná úschovna profilu pro diktafon, pokud se nahrává systémový zvuk přes MediaProjection. */
    var pendingDictaphoneProfile: com.example.llama.srt.TranscriptProfile? = null

    companion object {
        private const val TAG = "AiAgentService"

        @Volatile private var instance: AiAgentAccessibilityService? = null

        /** Získat running instance service (pokud je enabled) */
        fun getInstance(): AiAgentAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        // [LIFECYCLE FIX] Služby Accessibility nevolají onStartCommand.
        // Aby CameraX z IrisMotion mohl detekovat aktivitu a otevřít HW kameru,
        // musíme ručně přejít do stavu RESUMED, jinak by produkce framů nezačala.
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        instance = this

        Log.i(TAG, "🤖 AI Agent Accessibility Service connected & Lifecycle RESUMED!")

        // ==================== DYNAMICKÉ NASTAVENÍ FLAGŮ ====================
        // KRITICKÉ: XML config (accessibility_service_config.xml) NESTAČÍ na Samsung OneUI!
        // TalkBack, Voice Access a Bixby nastavují flagy PROGRAMATICKY.
        // Bez tohoto agent vidí jen 2 prvky (DecorView→FrameLayout) místo celého UI tree.
        serviceInfo?.let { info ->
            // Zachovat existující flagy z XML a přidat chybějící
            info.flags =
                    info.flags or
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                            AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                            AccessibilityServiceInfo.DEFAULT
            // Aplikovat zpět do systému
            serviceInfo = info
            Log.i(TAG, "🔧 ServiceInfo flags SET dynamicky: 0x${info.flags.toString(16)}")
        }
                ?: Log.e(TAG, "🚨 serviceInfo NULL v onServiceConnected — nelze nastavit flagy!")

        // Initialize components
        initializeComponents()

        // NOTIFICATION ANCHOR: Odstraněno spouštění LlamaGenerationService při bootu.
        // LLM (Tier 3) se bude lazy-loadovat a spouštět až na vyžádání.
        // Původní kód odstraněn pro zabránění automatického startu LLM notifikace a engine.

        // Activate "unused" property by observing it for debugging/audit
        serviceScope.launch {
            agentMode.collect { mode -> Log.v(TAG, "State Observer: Current agent mode is $mode") }
        }

        // ==================== FILE-BASED DIAGNOSTIKA ====================
        // Samsung OneUI potlačuje logcat — diagnostika se zapisuje do souboru
        // Čtení: adb shell run-as com.example.llama.aichat cat files/agent_diagnostics.txt
        val diagReport = StringBuilder()
        diagReport.appendLine("=== AI Agent Diagnostics Report ===")
        diagReport.appendLine(
                "Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}"
        )
        diagReport.appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
        diagReport.appendLine("Model: ${android.os.Build.MODEL}")
        diagReport.appendLine(
                "Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        )

        // Audit: Loguj aktuální konfiguraci service (použití AccessibilityServiceInfo)
        serviceInfo?.let { info ->
            val canRetrieve =
                    (info.capabilities and
                            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
            val hasInteractiveWindows =
                    (info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0
            Log.i(
                    TAG,
                    "ℹ️ Service config: flags=0x${info.flags.toString(16)}, capabilities=0x${info.capabilities.toString(16)}, canRetrieveWindowContent=$canRetrieve, interactiveWindows=$hasInteractiveWindows"
            )
            diagReport.appendLine("canRetrieveWindowContent: $canRetrieve")
            diagReport.appendLine("hasInteractiveWindows: $hasInteractiveWindows")
            diagReport.appendLine("flags: 0x${info.flags.toString(16)}")
            diagReport.appendLine("capabilities: 0x${info.capabilities.toString(16)}")
            if (!canRetrieve) {
                Log.e(
                        TAG,
                        "🚨 CRITICAL: canRetrieveWindowContent=FALSE! Engine NEBUDE vidět UI prvky!"
                )
                diagReport.appendLine("🚨 CRITICAL: canRetrieveWindowContent=FALSE!")
            }
        }
                ?: run {
                    Log.e(TAG, "🚨 serviceInfo je NULL — service není správně připojena")
                    diagReport.appendLine("🚨 serviceInfo=NULL")
                }

        // Runtime test: Ověření zda rootInActiveWindow vrací data
        val testRoot = rootInActiveWindow
        if (testRoot != null) {
            Log.i(
                    TAG,
                    "✅ rootInActiveWindow OK při startu: package=${testRoot.packageName}, childCount=${testRoot.childCount}"
            )
            diagReport.appendLine(
                    "rootInActiveWindow: OK (package=${testRoot.packageName}, children=${testRoot.childCount})"
            )
        } else {
            Log.w(TAG, "⚠️ rootInActiveWindow=NULL při startu")
            diagReport.appendLine("rootInActiveWindow: NULL (normální při startu)")
        }

        // Samsung Restricted Settings detection
        if (android.os.Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            val canRetrieveSamsung =
                    serviceInfo?.let {
                        (it.capabilities and
                                AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) !=
                                0
                    }
                            ?: false
            diagReport.appendLine(
                    "Samsung Restricted Settings: ${if (canRetrieveSamsung) "OK" else "🚨 MOŽNÁ BLOKOVÁNO"}"
            )
            if (!canRetrieveSamsung) {
                Log.e(
                        TAG,
                        "🚨 SAMSUNG DETECTED + canRetrieveWindowContent=FALSE → Samsung Restricted Settings!"
                )
            }
        }

        // Auto-load InstalledAppsRegistry
        if (!InstalledAppsRegistry.loadFromFile(applicationContext)) {
            InstalledAppsRegistry.loadSnapshot(applicationContext)
            InstalledAppsRegistry.saveToFile(applicationContext)
        }
        // Build O(1) index pro AppResolutionEngine (normalizace + invertovaný index)
        AppResolutionEngine.buildIndex()
        Log.i(
                TAG,
                "📦 App registry: ${InstalledAppsRegistry.size} apps loaded, index: ${AppResolutionEngine.indexSize} terms"
        )
        diagReport.appendLine("InstalledAppsRegistry: ${InstalledAppsRegistry.size} apps")
        diagReport.appendLine("AppResolutionEngine index: ${AppResolutionEngine.indexSize} terms")

        // EVOLUCE: Načti naučenou paměť z disku (1× read, poté jen RAM)
        ScreenContextMemory.loadFromDisk(applicationContext)
        diagReport.appendLine("ScreenContextMemory: loaded")
        diagReport.appendLine("=== END ===")

        // Zapis diagnostiku do souboru (Samsung potlačuje logcat)
        try {
            val diagFile = java.io.File(applicationContext.filesDir, "agent_diagnostics.txt")
            diagFile.writeText(diagReport.toString())
            Log.i(TAG, "📝 Diagnostics written to ${diagFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostics file", e)
        }

        Log.i(TAG, "✓ Service ready. Waiting for activation command...")
    }

    // LISTENER FIX: Moved Wake Word logic here to avoid infinite leak
    private fun startWakeWordListener() {
        serviceScope.launch {
            // [AUDIT FIX] Avoid processing old events on restart
            var lastProcessedTime = System.currentTimeMillis()

            val sttInstance = voiceSTT ?: return@launch
            sttInstance.state.collect { state ->
                if (state is STTState.Result) {
                    // FIX: Only activate if explicitly triggered by Wake Word (ignore manual
                    // commands)
                    // AND if timestamp is newer than last processed time
                    if (state.timestamp > lastProcessedTime &&
                                    state.isWakeWord &&
                                    _agentMode.value is AgentMode.Idle
                    ) {
                        lastProcessedTime = state.timestamp
                        Log.i(TAG, "🔔 Wake Word triggered activation: ${state.text}")
                        activateAgent(state.text)
                    }
                }
            }
        }
    }

    private val prefsListener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    "agent_wake_word_enabled", "agent_wake_word" -> {
                        Log.d(TAG, "⚙️ Wake Word settings changed ($key) -> Updating state")
                        updateWakeWordState()
                    }
                    "agent_enabled" -> {
                        val enabled = settingsManager.isAgentEnabled
                        Log.i(TAG, "⚙️ Agent state changed: $enabled")
                        if (enabled) {
                            initializeAgentComponents()
                        } else {
                            teardownAgentComponents()
                        }
                    }
                    "irismotion_remote_mode_enabled" -> {
                        val isEnabled = settingsManager.isRemoteModeEnabled
                        toggleBluetoothHidMode(isEnabled)
                    }
                    "active_remote_profile_id" -> {
                        val profileId = settingsManager.prefs.getString("active_remote_profile_id", "default_pc_mouse") ?: "default_pc_mouse"
                        Log.i(TAG, "⚙️ Active Remote Profile changed to: $profileId")
                        remoteOrchestrator?.switchProfile(profileId)
                    }
                    "irismotion_enabled" -> {
                        val enabled = settingsManager.isIrisMotionEnabled
                        Log.i(TAG, "⚙️ IrisMotion state changed: $enabled")
                        // CameraX vyžaduje Main Thread — používáme Dispatchers.Main
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled) initializeIrisMotion() else stopIrisMotion()
                        }
                    }
                    // BRIDGE: Po dokončení CalibrationActivity → automatický start/reload engine
                    // CalibrationActivity nastaví isIrisMotionCalibrated = true → prefsListener
                    // zachytí změnu tohoto klíče a spustí nebo obnoví controller
                    "irismotion_calibrated" -> {
                        val calibrated = settingsManager.isIrisMotionCalibrated
                        val enabled = settingsManager.isIrisMotionEnabled
                        Log.i(
                                TAG,
                                "⚙️ IrisMotion calibration flag changed: calibrated=$calibrated, enabled=$enabled"
                        )
                        if (calibrated && enabled) {
                            // CameraX vyžaduje Main Thread — wrap do Dispatchers.Main
                            serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                if (irisController == null) {
                                    Log.i(
                                            TAG,
                                            "🚀 Spouštím IrisMotionController po dokončení kalibrace"
                                    )
                                    initializeIrisMotion()
                                } else {
                                    Log.i(
                                            TAG,
                                            "🔄 Reloading kalibrace do běžícího IrisMotionController"
                                    )
                                    irisController?.reloadCalibration()
                                }
                            }
                        }
                    }
                    "app_layout_cache_ttl" -> {
                        com.example.llama.agent.core.AppLayoutCache.cacheTtlMs =
                                settingsManager.appLayoutCacheTtl
                        Log.d(
                                TAG,
                                "⚙️ Cache TTL updated to ${com.example.llama.agent.core.AppLayoutCache.cacheTtlMs}ms"
                        )
                    }
                    "irismotion_dwell_enabled", "irismotion_dwell_ms" -> {
                        val enabled = settingsManager.isIrisMotionDwellEnabled
                        val ms = settingsManager.irisMotionDwellMs
                        Log.i(
                                TAG,
                                "⚙️ IrisMotion Dwell settings updated: enabled=$enabled, ms=${ms}ms"
                        )
                        irisController?.reconfigureDwell(enabled, ms)
                    }
                    "irismotion_target_snap", "irismotion_target_snap_radius" -> {
                        val enabled = settingsManager.irisMotionTargetSnap
                        val radius = settingsManager.irisMotionTargetSnapRadius
                        Log.i(
                                TAG,
                                "⚙️ IrisMotion Target Snap updated: enabled=$enabled, radius=${radius}px"
                        )
                        irisController?.reconfigureTargetSnap(enabled, radius)
                    }
                    "smartvision_camera_live_enabled", "perception_camera_source" -> {
                        val cameraLive = settingsManager.isSmartVisionCameraLiveEnabled
                        Log.i(TAG, "⚙️ SmartVision Camera/Source updated: Live=$cameraLive, Source=${settingsManager.perceptionCameraSource}")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            // First stop the previous source gracefully
                            stopSmartVisionCamera()
                            
                            // Then start the new source if enabled
                            if (cameraLive) {
                                startSmartVisionCamera()
                            }
                        }
                    }
                    "smartvision_bounding_boxes_enabled" -> {
                        val overlayEnabled = settingsManager.isSmartVisionBoundingBoxEnabled
                        Log.i(TAG, "⚙️ SmartVision Overlay: $overlayEnabled")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (overlayEnabled) {
                                if (smartVisionOverlay == null) {
                                    smartVisionOverlay =
                                            com.example.llama.agent.vision
                                                    .SmartVisionOverlayManager(
                                                            this@AiAgentAccessibilityService
                                                    )
                                }
                                smartVisionOverlay?.showOverlay()
                            } else {
                                smartVisionOverlay?.hideOverlay()
                            }
                        }
                    }
                    "perception_enabled" -> {
                        val enabled = settingsManager.isPerceptionEnabled
                        Log.i(TAG, "⚙️ Perception (Vision) dynamická změna: $enabled")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled) {
                                if (perceptionEngine == null) {
                                    val repo = perceptionRepository
                                    if (repo == null) {
                                        Log.w(TAG, "perceptionRepository je null, ignoruji start PerceptionEngine zmáčknutím přepínače.")
                                        return@launch
                                    }
                                    val odMgr = com.example.llama.agent.perception.OdModelManager(
                                            applicationContext, settingsManager
                                    )
                                    perceptionEngine =
                                            PerceptionEngine(
                                                    applicationContext,
                                                    repo,
                                                    settingsManager,
                                                    odMgr
                                            )
                                    com.example.llama.agent.vision.VisionSourceManager
                                            .onPerceptionFrame =
                                            { bitmap, recycleSource ->
                                                perceptionEngine?.onNewFrame(bitmap, recycleSource)
                                            }
                                }
                                perceptionEngine?.start()
                            } else {
                                perceptionEngine?.stop()
                            }
                            omniControlNotifier?.showOrUpdateNotification()
                            floatingButton?.updateToolbarButtonsState()
                        }
                    }
                    "perception_audio_enabled" -> {
                        val enabled = settingsManager.isAudioPerceptionEnabled
                        Log.i(TAG, "⚙️ Perception (Audio) dynamická změna: $enabled")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            if (enabled) {
                                if (audioPerceptionEngine == null) {
                                    audioPerceptionEngine =
                                            AudioPerceptionEngine(
                                                    applicationContext,
                                                    settingsManager
                                            )
                                }
                                audioPerceptionEngine?.start()
                            } else {
                                audioPerceptionEngine?.stop()
                            }
                            omniControlNotifier?.showOrUpdateNotification()
                            floatingButton?.updateToolbarButtonsState()
                        }
                    }
                    "ocr_enabled" -> {
                        val enabled = settingsManager.isOcrEnabled
                        Log.i(TAG, "⚙️ Perception (OCR) dynamická změna: $enabled")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            omniControlNotifier?.showOrUpdateNotification()
                            floatingButton?.updateToolbarButtonsState()
                        }
                    }
                    "od_active_model_id", "perception_accelerator_mode" -> {
                        val activeId = settingsManager.activeOdModelId
                        val mode = settingsManager.perceptionAcceleratorMode
                        Log.i(TAG, "⚡ OD Model/HW změna: activeId=$activeId, HW=$mode")
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                            perceptionEngine?.reloadObjectDetector()
                        }
                    }
                    "safety_guard_enabled" -> {
                        val enabled = settingsManager.safetyGuardEnabled
                        Log.i(TAG, "⚙️ SafetyGuard dynamická změna: $enabled")
                        omniControlNotifier?.showOrUpdateNotification()
                    }
                    "transcript_enabled", "transcript_source", "transcript_engine" -> {
                        val enabled = settingsManager.isTranscriptEnabled
                        val source = settingsManager.transcriptSource
                        val engine = settingsManager.transcriptEngine
                        Log.i(
                                TAG,
                                "⚙️ Transcript dynamická změna: enabled=$enabled, source=$source, engine=$engine"
                        )

                        // DEBOUNCE: Prevence SIGSEGV z rapid ON/OFF toggle.
                        // Rapid klikání způsobuje race condition kde stop() native kódu
                        // (Sherpa/Whisper C++) koliduje se start() → use-after-free → SIGSEGV.
                        // Cancel předchozí pending akce, počkáme 500ms na stabilní stav.
                        transcriptToggleJob?.cancel()
                        transcriptToggleJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            delay(500L) // Debounce — ignoruje rapid toggle eventy

                            if (enabled) {
                                // 1. Inicializace UI panelu
                                if (smartCaptionOverlay == null) {
                                    smartCaptionOverlay =
                                            com.example.llama.agent.ui.SmartCaptionOverlayManager(
                                                    this@AiAgentAccessibilityService,
                                                    settingsManager
                                            )
                                }
                                smartCaptionOverlay?.show()

                                // 2. Zastavení předchozího běhu
                                transcriptAgent?.stop()
                                stopTranscriptForegroundService()

                                // KRITICKÉ: stop() spouští async JNI dealokaci (IO dispatcher).
                                // Musíme počkat, než C++ uvolní AudioRecord + Sherpa/Whisper objekty.
                                // Bez tohoto delay dostane nový TranscriptAgent stale reference → hluchý engine.
                                delay(300L)

                                // 3. Inicializace nového Agenta
                                transcriptAgent =
                                        com.example.llama.agent.perception.TranscriptAgent(
                                                applicationContext,
                                                settingsManager
                                        )

                                // 4. Spuštění dle zdroje (centralizovaná logika)
                                startTranscriptWithSource()
                            } else {
                                // Vypnutí: zastavit vše
                                smartCaptionOverlay?.hide()
                                transcriptAgent?.stop()
                                stopTranscriptForegroundService()
                            }
                            omniControlNotifier?.showOrUpdateNotification()
                            floatingButton?.updateToolbarButtonsState()
                        }
                    }
                }
            }

    private fun updateWakeWordState() {
        try {
            if (!::settingsManager.isInitialized || voiceSTT == null) return

            if (settingsManager.isWakeWordEnabled) {
                // DEBOUNCE: Cancel any pending restart job
                wakeWordUpdateJob?.cancel()
                wakeWordUpdateJob =
                        serviceScope.launch(Dispatchers.Main) {
                            // Restart listener if already running to apply potential phrase change
                            val stt = voiceSTT ?: return@launch
                            if (stt.isListening()) {
                                Log.i(TAG, "⚙️ Restarting Wake Word listener for settings update")
                                stt.stopListening()
                                delay(300) // Slightly longer wait for system to release mic
                            } else {
                                Log.i(TAG, "⚙️ Wake Word enabled via settings -> Starting listener")
                            }
                            stt.startContinuousListening()
                        }
            } else {
                wakeWordUpdateJob?.cancel()
                Log.i(TAG, "⚙️ Wake Word disabled via settings -> Stopping listener")
                voiceSTT?.stopListening()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in updateWakeWordState", e)
        }
    }

    /**
     * Fáze 1: Vždy inicializovat SettingsManager a prefsListener. Fáze 2: Agent komponenty jen
     * pokud isAgentEnabled = true (lazy).
     */
    private fun initializeComponents() {
        try {
            // === FÁZE 1: Vždy (potřeba pro flag check) ===
            settingsManager = SettingsManager(applicationContext)

            // Register listener PŘED agent init (aby zachytil i změnu během startu)
            applicationContext
                    .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .registerOnSharedPreferenceChangeListener(prefsListener)

            // [CACHE SYNC] Load Cache TTL immediately
            com.example.llama.agent.core.AppLayoutCache.cacheTtlMs =
                    settingsManager.appLayoutCacheTtl
            Log.d(
                    TAG,
                    "Initial Cache TTL: ${com.example.llama.agent.core.AppLayoutCache.cacheTtlMs}ms"
            )

            // Zkontrolujeme stav Remote Mode hned při startu
            // Zkontrolujeme stav Remote Mode hned při startu
            toggleBluetoothHidMode(settingsManager.isRemoteModeEnabled)

            // === FÁZE 2: Lazy — jen pokud agent povolený ===
            if (settingsManager.isAgentEnabled) {
                initializeAgentComponents()
            } else {
                Log.i(TAG, "⏸️ Agent disabled — skipping component init (lazy mode)")
            }

            // === FÁZE 3: IrisMotion — nezávislý na agent_enabled ===
            if (settingsManager.isIrisMotionEnabled) {
                initializeIrisMotion()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize components", e)
        }
    }

    /**
     * LAZY INIT: Inicializuje agent komponenty (TTS, STT, Coordinator). Volá se při:
     * 1. onServiceConnected() pokud isAgentEnabled = true
     * 2. prefsListener při přepnutí isAgentEnabled na true Thread-safe: Idempotentní (double-call
     * neprodukuje chybu)
     */
    private fun initializeAgentComponents() {
        if (coordinator != null) {
            Log.d(TAG, "Agent components already initialized — skipping")
            return // Idempotence
        }

        try {
            // [NEW] Inicializace Plovoucích Bublin
            agentNotifier =
                    com.example.llama.agent.ui.AgentNotifier(this@AiAgentAccessibilityService, settingsManager)

            // [NEW] Omni-Control Notifikace (Řídící centrum všech Perception / Action režimů)
            omniControlNotifier =
                    com.example.llama.agent.ui.OmniControlNotificationHelper(applicationContext, settingsManager)
            omniControlNotifier?.showOrUpdateNotification()

            // Core components
            safetyGuard =
                    SafetyGuard(settingsManager) { msg ->
                        agentNotifier?.notify(
                                com.example.llama.agent.ui.AgentNotifier.NotifyType.WARNING,
                                "⚠️",
                                msg
                        )
                    }
            Log.d(
                    TAG,
                    "SafetyGuard initialized with ${safetyGuard!!.getSensitivePackages().size} sensitive rules"
            )
            wakeUpScreenParser()
            AppLayoutCache.settingsManager = settingsManager
            systemController = SystemController(applicationContext)

            // Controllers Init
            voipController = VoipCallController(this)
            systemToggleController = SystemToggleController(this)

            // ActionExecutor (with systemToggleController)
            actionExecutor = ActionExecutor(this, systemController, systemToggleController)

            // CRITICAL: Inicializovat audio orchestrátor PŘED TTS/STT
            // (Bez tohoto volání je audioManager null → všechny focus requesty selhávají)
            com.example.llama.agent.core.audio.AgentAudioOrchestrator.initialize(applicationContext)

            // Voice components
            voiceTTS = AgentVoiceTTS(applicationContext).also { it.initialize() }
            voiceSTT = AgentVoiceSTT(applicationContext).also { it.initialize() }

            // InferenceEngine from SINGLETON Provider (sdílený s chat režimem)
            val inferenceEngine: InferenceEngine = EngineProvider.get(applicationContext)

            // [INTEGRATION] Verify Direct Access
            val directRef = getDirectInferenceEngine()
            Log.v(
                    TAG,
                    "Direct Engine Reference obtained: ${directRef.hashCode()} vs Provider: ${inferenceEngine.hashCode()}"
            )

            // [PERCEPTION] Instancování PerceptionStateRepository (Single Source of Truth)
            // Sdílený mezi PerceptionEngine (writer) a AgentCoordinator (reader via StateFlow)
            perceptionRepository = PerceptionStateRepository()

            coordinator =
                    AgentCoordinator(
                            appContext = this@AiAgentAccessibilityService, // Zde musí být Accessibility Context, aby PopupWindow mohl nasadit System Overlay
                            screenParserProvider = { screenParser },
                            actionExecutor = actionExecutor!!,
                            safetyGuard = safetyGuard!!,
                            inferenceEngine = inferenceEngine,
                            settingsManager = settingsManager,
                            systemController = systemController,
                            rootNodeProvider = { findActiveRoot() },
                            screenshotProvider = { captureScreenshotAsync() },
                            perceptionRepository = perceptionRepository,
                            ttsCallback = { text -> speak(text) }
                    )

            // ========================================================================
            // [ON-DEMAND PERCEPTION] MoE Hub — Lazy Init, NO Auto-Start
            // ========================================================================
            // Architektonické rozhodnutí: Enginy se NESTARTUJÍ automaticky.
            // Zůstávají ve stavu STOPPED a probudí se VÝHRADNĚ přes Omni-Control
            // (BroadcastReceiver handlery výše: "perception_enabled", "perception_audio_enabled").
            // Tím šetříme baterii, RAM a thermal budget — MoE Tier 2 filozofie.
            //
            // PerceptionEngine: Lazy-ready (instancován vždy, startován jen na vyžádání)
            val odModelManager = com.example.llama.agent.perception.OdModelManager(
                    applicationContext, settingsManager
            )
            perceptionEngine =
                    PerceptionEngine(
                            applicationContext,
                            perceptionRepository!!,
                            settingsManager,
                            odModelManager
                    )

            // Napojení Perception: VisionSourceManager → PerceptionEngine
            // Callback je registrován vždy, ale engine bez start() ignoruje framy.
            com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame = { bitmap, recycleSource ->
                perceptionEngine?.onNewFrame(bitmap, recycleSource)
            }
            
            com.example.llama.agent.vision.VisionSourceManager.onResetTrackers = {
                perceptionEngine?.resetTrackers()
            }

            com.example.llama.agent.vision.VisionSourceManager.onStudioStateChanged = { isStudioActive ->
                if (isStudioActive) {
                    stopSmartVisionCamera()
                } else {
                    // Reset track IDs to prevent memory leak and constant ID growth
                    perceptionEngine?.resetTrackers()
                    
                    if (settingsManager.isSmartVisionCameraLiveEnabled) {
                        serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startSmartVisionCamera()
                        }
                    }
                }
            }

            // AudioPerceptionEngine: Lazy-ready (instancován vždy, startován jen na vyžádání)
            audioPerceptionEngine = AudioPerceptionEngine(applicationContext, settingsManager)

            // On-Demand Start: Pokud uživatel MÁ zapnutý modul v Settings (např. z minulé session),
            // probudíme engine okamžitě, aby se obnovil poslední stav.
            if (settingsManager.isPerceptionEnabled) {
                perceptionEngine?.start()
                Log.i(TAG, "🔬 PerceptionEngine auto-resumed (was enabled in Settings)")
            } else {
                Log.i(TAG, "🔬 PerceptionEngine ready (STOPPED — čeká na Omni-Control)")
            }
            if (settingsManager.isAudioPerceptionEnabled) {
                audioPerceptionEngine?.start()
                Log.i(TAG, "🎙️ AudioPerceptionEngine auto-resumed (was enabled in Settings)")
            } else {
                Log.i(TAG, "🔇 AudioPerceptionEngine ready (STOPPED — čeká na Omni-Control)")
            }

            // Auto-resume SmartVision Camera (pokud bylo zapnuté v předchozí session)
            if (settingsManager.isSmartVisionCameraLiveEnabled) {
                serviceScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    startSmartVisionCamera()
                }
                Log.i(TAG, "📷 SmartVision Camera auto-resumed (was enabled in Settings)")
            }

            // Macro Engine Init
            macroEngine =
                    MacroEngine(
                            screenParserProvider = { screenParser },
                            actionExecutor = actionExecutor!!,
                            systemController = systemController!!,
                            rootNodeProvider = { findActiveRoot() },
                            appContext = applicationContext
                    )

            // [INTEGRATION] ActionResultBus.results subscriber — reaktivní monitoring výsledků akcí
            // Využívá ActionResultBus.results (SharedFlow) pro logování a budoucí proaktivní
            // chování
            serviceScope.launch {
                ActionResultBus.results.collect { result ->
                    if (!result.success) {
                        Log.w(
                                TAG,
                                "⚠️ ActionResultBus: Failed action [${result.actionType}] — ${result.errorMessage}"
                        )
                    } else {
                        Log.v(
                                TAG,
                                "✅ ActionResultBus: Success [${result.actionType}] — ${result.thought.take(40)}"
                        )
                    }
                }
            }

            // [INTEGRATION] PerceptionEventBus subscriber — reaktivní monitoring percepčních
            // událostí
            // Coordinator reaguje na SceneChange (TTS), CRITICAL audio events (alarm),
            // a lifecycle changes (engine state tracking)
            coordinator?.startPerceptionEventCollector(serviceScope)

            // [INTEGRATION] AgentNotifier - Vizuální Bubliny reagující na Perception
            serviceScope.launch {
                com.example.llama.agent.perception.PerceptionEventBus.events.collect { event ->
                    when (event) {
                        is com.example.llama.agent.perception.SceneChangeEvent -> {
                            val minEntities = settingsManager.perceptionSceneMinNewEntities
                            if (event.newEntities.size >= minEntities) {
                                agentNotifier?.notify(
                                        com.example.llama.agent.ui.AgentNotifier.NotifyType.VISION,
                                        "👁️",
                                        "Nové objekty v okolí: ${event.newEntities.take(2).joinToString(", ")}"
                                )
                            }
                        }
                        is com.example.llama.agent.perception.AudioDetectionEvent -> {
                            // Pro audio zobrazujeme výstražnější barvu nebo emoji dle priority
                            val czLabel =
                                    com.example.llama.agent.perception.AudioLabelTranslations
                                            .translate(event.label, settingsManager.yamnetActiveLang)
                            val emoji =
                                    if (event.priority ==
                                                    com.example.llama.agent.perception.EventPriority
                                                            .CRITICAL
                                    )
                                            "🚨"
                                    else "🔔"
                            agentNotifier?.notify(
                                    com.example.llama.agent.ui.AgentNotifier.NotifyType.AUDIO,
                                    emoji,
                                    czLabel
                            )
                        }
                        else -> {
                            // Ostatní eventy ignorujeme pro UI notifikace
                        }
                    }
                }
            }

            startWakeWordListener()

            // COORDINATION: Prevent Agent loop (hearing itself)
            setupAudioCoordination()

            // Start Continuous Listening (Wake Word) if enabled
            updateWakeWordState()

            // [NEW] Initialize Floating UI
            initializeFloatingButton()

            // [G2] Smart Caption Overlay — On-Demand (zobrazí se jen pokud isTranscriptEnabled)
            smartCaptionOverlay =
                    com.example.llama.agent.ui.SmartCaptionOverlayManager(this, settingsManager)
            if (settingsManager.isTranscriptEnabled) {
                smartCaptionOverlay?.show()

                // Inicializace TranscriptAgent (idempotentní)
                if (transcriptAgent == null) {
                    transcriptAgent =
                            com.example.llama.agent.perception.TranscriptAgent(
                                    applicationContext,
                                    settingsManager
                            )
                } else {
                    transcriptAgent?.stop()
                }

                // Centralizovaná logika: rozhodne mic vs system_audio
                startTranscriptWithSource()
            }

            Log.i(TAG, "✓ Agent components lazy-initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize agent components", e)
        }
    }

    /**
     * LAZY TEARDOWN: Uvolní agent komponenty (TTS, STT, Coordinator). Volá se při přepnutí
     * isAgentEnabled na false. Thread-safe: Idempotentní.
     */
    private fun teardownAgentComponents() {
        Log.i(TAG, "🧹 Tearing down agent components (lazy release)")
        wakeWordUpdateJob?.cancel()
        wakeWordUpdateJob = null
        voiceSTT?.shutdown()
        voiceTTS?.shutdown()
        voiceSTT = null
        voiceTTS = null

        // [PERCEPTION] Cleanup — zastavení a uvolnění perception engines
        // Pořadí: stop event collector → stop engines → null references
        coordinator?.stopPerceptionEventCollector()
        perceptionEngine?.destroy()
        audioPerceptionEngine?.destroy()
        omniControlNotifier?.destroy()
        omniControlNotifier = null
        com.example.llama.agent.vision.VisionSourceManager.onPerceptionFrame = null // Odpojení Perception callback
        perceptionEngine = null
        audioPerceptionEngine = null
        perceptionRepository = null

        coordinator = null
        actionExecutor = null
        screenParser = null
        screenParserTimeoutJob?.cancel()
        safetyGuard = null
        systemController = null
        macroEngine = null

        // UI Cleanup
        floatingButton?.hide()
        floatingButton = null
        // UI Cleanup
        floatingButton?.hide()
        floatingButton = null
        agentNotifier?.destroy()
        agentNotifier = null
        smartCaptionOverlay?.hide()
        smartCaptionOverlay = null

        transcriptAgent?.stop()
        transcriptAgent = null
        stopTranscriptForegroundService()

        // Odregistrace broadcast receiverů (memory leak prevence)
        try { unregisterReceiver(sttRestartReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(systemAudioReceiver) } catch (_: Exception) {}

        omniControlNotifier?.destroy()
        omniControlNotifier = null
        // IrisMotion lifecycle je nezávislý — zastavujeme jen pokud agent teardown
        // IrisMotion bude zastaveno přes vlastní prefsListener klíč irismotion_enabled
        Log.i(TAG, "✓ Agent components released — RAM freed")
    }

    /**
     * Inicializuje IrisMotionController. Volá se:
     * 1. Z initializeComponents() pokud isIrisMotionEnabled = true
     * 2. Z prefsListener při zapnutí irismotion_enabled Idempotentní — double-call neprodukuje
     * chybu.
     */
    private fun initializeIrisMotion() {
        if (irisController != null) {
            Log.d(TAG, "IrisMotionController already initialized — skipping")
            return
        }
        try {
            if (remoteOrchestrator == null) {
                remoteOrchestrator = com.example.llama.agent.irismotion.bluetooth.RemoteGestureOrchestrator(
                    this, settingsManager
                )
            } else {
                remoteOrchestrator?.updateSensitivity(settingsManager.irismotionBtSensitivity)
            }

            irisController =
                    IrisMotionController(
                            context = this, // MUSÍ BÝT service context, jinak
                            // TYPE_ACCESSIBILITY_OVERLAY selže!
                            lifecycleOwner = this,
                            packageNameProvider = {
                                rootInActiveWindow?.packageName?.toString() ?: ""
                            },
                            onElementClicked = { element, clickX, clickY ->
                                if (settingsManager.isRemoteModeEnabled) {
                                    // REMOTE MÓD: Klikneme myší na PC
                                    com.example.llama.agent.irismotion.bluetooth.HidBridge.transmitter?.sendMouseClick(com.example.llama.agent.irismotion.bluetooth.BluetoothHidTransmitter.LEFT_BUTTON)
                                    return@IrisMotionController
                                }
                                // Dwell click → dispatchGesture přímo na X, Y souřadnice kurzoru
                                // Už ŽÁDNÝ vynucený exactCenterX/Y, odteď plně respektujeme Target
                                // Lock.
                                Log.i(
                                        TAG,
                                        "👁️ IrisMotion dwell click: '${element.label}' @ ($clickX, $clickY)"
                                )
                                val cx =
                                        clickX.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.widthPixels
                                                                .toFloat()
                                                )
                                val cy =
                                        clickY.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.heightPixels
                                                                .toFloat()
                                                )
                                val path = android.graphics.Path().apply { moveTo(cx, cy) }
                                val stroke =
                                        android.accessibilityservice.GestureDescription
                                                .StrokeDescription(path, 0L, 50L)
                                val gesture =
                                        android.accessibilityservice.GestureDescription.Builder()
                                                .addStroke(stroke)
                                                .build()
                                dispatchGesture(gesture, null, null)
                            },
                            onEyeGesture = { actionId, appPackage ->
                                handleEyeGesture(actionId, appPackage)
                            },
                            onPhysicalGesture = { gesture ->
                                if (settingsManager.isRemoteModeEnabled) {
                                    remoteOrchestrator?.dispatchEyeGesture(gesture.id)
                                }
                            },
                            onGazeMiss = { x, y ->
                                if (settingsManager.isRemoteModeEnabled) {
                                    remoteOrchestrator?.dispatchEyeGesture(com.example.llama.agent.irismotion.PhysicalGesture.DWELL_CLICK.id)
                                    return@IrisMotionController
                                }
                                Log.d(TAG, "👁️ IrisMotion gaze miss @ ($x,$y) -> RAW TAP")
                                val safeX =
                                        x.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.widthPixels
                                                                .toFloat()
                                                )
                                val safeY =
                                        y.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.heightPixels
                                                                .toFloat()
                                                )
                                val path = android.graphics.Path().apply { moveTo(safeX, safeY) }
                                val stroke =
                                        android.accessibilityservice.GestureDescription
                                                .StrokeDescription(path, 0L, 50L)
                                val gesture =
                                        android.accessibilityservice.GestureDescription.Builder()
                                                .addStroke(stroke)
                                                .build()
                                dispatchGesture(gesture, null, null)
                            },
                            onRawTap = { x, y, isDouble ->
                                if (settingsManager.isRemoteModeEnabled) {
                                    val gesture = if (isDouble) com.example.llama.agent.irismotion.PhysicalGesture.DOUBLE_CLICK else com.example.llama.agent.irismotion.PhysicalGesture.DWELL_CLICK
                                    remoteOrchestrator?.dispatchEyeGesture(gesture.id)
                                    return@IrisMotionController
                                }
                                Log.i(
                                        TAG,
                                        "👁️ IrisMotion os-level RAW TAP @ ($x,$y), double=$isDouble"
                                )
                                val safeX =
                                        x.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.widthPixels
                                                                .toFloat()
                                                )
                                val safeY =
                                        y.toFloat()
                                                .coerceAtLeast(0f)
                                                .coerceAtMost(
                                                        resources.displayMetrics.heightPixels
                                                                .toFloat()
                                                )
                                val path = android.graphics.Path().apply { moveTo(safeX, safeY) }
                                val stroke1 =
                                        android.accessibilityservice.GestureDescription
                                                .StrokeDescription(path, 0L, 50L)
                                val builder =
                                        android.accessibilityservice.GestureDescription.Builder()
                                                .addStroke(stroke1)
                                if (isDouble) {
                                    val stroke2 =
                                            android.accessibilityservice.GestureDescription
                                                    .StrokeDescription(path, 150L, 50L)
                                    builder.addStroke(stroke2)
                                }
                                dispatchGesture(builder.build(), null, null)
                            },
                            dwellTimeMs = settingsManager.irisMotionDwellMs,
                            isDwellEnabled = settingsManager.isIrisMotionDwellEnabled,
                            cursorSizePx = settingsManager.irisMotionCursorSize,
                            enableTargetSnap = settingsManager.irisMotionTargetSnap,
                            targetSnapRadiusPx = settingsManager.irisMotionTargetSnapRadius,
                            findNearestElement = { x, y, maxDistancePx ->
                                val rootInfo =
                                        rootInActiveWindow ?: return@IrisMotionController null
                                val pkgName =
                                        rootInfo.packageName?.toString()
                                                ?: return@IrisMotionController null

                                val elements =
                                        com.example.llama.agent.core.AppLayoutCache
                                                .extractStrictClickableElements(pkgName)

                                elements
                                        .minByOrNull {
                                                element:
                                                        com.example.llama.agent.core.AppLayoutCache.CachedElement
                                            ->
                                            val cx = element.bounds.exactCenterX().toInt()
                                            val cy = element.bounds.exactCenterY().toInt()
                                            val dx = (cx - x).toFloat()
                                            val dy = (cy - y).toFloat()
                                            kotlin.math.sqrt(dx * dx + dy * dy)
                                        }
                                        ?.let {
                                                bestElement:
                                                        com.example.llama.agent.core.AppLayoutCache.CachedElement
                                            ->
                                            val cx = bestElement.bounds.exactCenterX().toInt()
                                            val cy = bestElement.bounds.exactCenterY().toInt()
                                            val dx = (cx - x).toFloat()
                                            val dy = (cy - y).toFloat()
                                            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                                            if (dist <= maxDistancePx) bestElement else null
                                        }
                            }
                    )

            // 3. Napojení na náš nový "plynulý port"
            irisController?.onGazeUpdate = { x, y ->
                if (settingsManager.isRemoteModeEnabled) {
                    // REMOTE MÓD: Delegováno na Orchestrátor (řeší přesně to, jaký je profil)
                    remoteOrchestrator?.dispatchGazeUpdate(x, y)
                    // Zajistíme, že je kurzor na telefonu skrytý
                    irisController?.setCursorVisibility(false)
                } else {
                    // LOKÁLNÍ MÓD: Běží normálně, update kurzoru si řeší controller sám uvnitř
                    remoteOrchestrator?.reset()
                    irisController?.setCursorVisibility(true)
                }
            }

            irisController?.start()
            Log.i(TAG, "✅ IrisMotionController inicializován a spuštěn")
        } catch (e: Exception) {
            Log.e(TAG, "❌ IrisMotionController init selhal", e)
            irisController = null
        }
    }

    private fun toggleBluetoothHidMode(enable: Boolean) {
        val bluetoothManager = getSystemService(android.content.Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val serviceIntent = android.content.Intent(this, com.example.llama.agent.irismotion.bluetooth.BluetoothHidForegroundService::class.java)
        
        if (enable) {
            // Lazy inicializace: Vytvoříme profil až při prvním zapnutí, a držíme ho
            if (com.example.llama.agent.irismotion.bluetooth.HidBridge.transmitter == null) {
                com.example.llama.agent.irismotion.bluetooth.HidBridge.transmitter = 
                    com.example.llama.agent.irismotion.bluetooth.BluetoothHidTransmitter(this)
            }
            
            if (bluetoothManager.adapter?.isEnabled == false) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(this, "Zapněte Bluetooth pro PC Myš!", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            Log.i(TAG, "🔌 Zapínám Remote Mode. Otevírám datový kanál do PC.")
            com.example.llama.agent.irismotion.bluetooth.HidBridge.transmitter?.connectToPc()
            
            serviceIntent.action = com.example.llama.agent.irismotion.bluetooth.BluetoothHidForegroundService.ACTION_START
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Log.i(TAG, "💤 Vypínám Remote Mode. Zavírám datový kanál, ale ZŮSTÁVÁM myší.")
            com.example.llama.agent.irismotion.bluetooth.HidBridge.transmitter?.disconnectFromPc()
            
            serviceIntent.action = com.example.llama.agent.irismotion.bluetooth.BluetoothHidForegroundService.ACTION_STOP
            startService(serviceIntent)
            remoteOrchestrator = null
        }
    }


    /**
     * Zastaví a uvolní IrisMotionController. Volá se z prefsListener při vypnutí irismotion_enabled
     * nebo z onDestroy. Idempotentní. destroy() = stop() + executor shutdown.
     */
    private fun stopIrisMotion() {
        irisController?.destroy()
        irisController = null
        Log.i(TAG, "🛑 IrisMotionController zastaven a uvolněn")
    }

    private var isFlashlightOn = false

    private fun handleEyeGesture(actionId: Int, appPackage: String) {
        if (settingsManager.isRemoteModeEnabled) {
            // REMOTE MÓD: Veškerá gesta jdou přes aktivní Remote Profil (Myš, Dron, atd.)
            // Fyzický event byl obsloužen přes onPhysicalGesture
            return // DŮLEŽITÉ: Zabraňuje vykonání lokálních Android akcí (např. scrollování notifikací) na telefonu
        }

        // === LOKÁLNÍ MÓD (Android) ===
        when (actionId) {
            SettingsManager.GESTURE_ACTION_NONE -> {
                Log.d(TAG, "Gesto očí: Žádná akce")
            }
            SettingsManager.GESTURE_ACTION_CLICK -> {
                // Should not reach here, handled directly in IrisMotionController
            }
            SettingsManager.GESTURE_ACTION_BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            SettingsManager.GESTURE_ACTION_HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            SettingsManager.GESTURE_ACTION_NOTIFICATIONS ->
                    performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            SettingsManager.GESTURE_ACTION_QUICK_SETTINGS ->
                    performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)
            SettingsManager.GESTURE_ACTION_TORCH -> toggleFlashlight()
            SettingsManager.GESTURE_ACTION_SWIPE_UP -> performSwipe(true)
            SettingsManager.GESTURE_ACTION_SWIPE_DOWN -> performSwipe(false)
            SettingsManager.GESTURE_ACTION_LAUNCH_APP -> {
                if (appPackage.isNotBlank()) {
                    // appPackage z UI je ve skutečnosti "Název Aplikace" (z AutoComplete)
                    // Potřebujeme zjistit její packageName z InstalledAppsRegistry
                    val apps = com.example.llama.agent.core.InstalledAppsRegistry.getAll()
                    val targetApp = apps.find { it.label.equals(appPackage, ignoreCase = true) }
                    val pkgToLaunch = targetApp?.packageName

                    if (pkgToLaunch != null) {
                        val intent = packageManager.getLaunchIntentForPackage(pkgToLaunch)
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                startActivity(intent)
                                Log.i(TAG, "Gesto: Spouštím aplikaci $pkgToLaunch")
                            } catch (e: Exception) {
                                Log.e(TAG, "Chyba spuštění aplikace gestem", e)
                            }
                        } else {
                            Log.e(TAG, "Gesto: Nelze získat LaunchIntent pro $pkgToLaunch")
                        }
                    } else {
                        Log.w(TAG, "Gesto: Aplikace nenalezena v registru pro název '$appPackage'")
                    }
                }
            }
            else -> Log.w(TAG, "Neznámé oční gesto: $actionId")
        }
    }

    private fun performSwipe(isUp: Boolean) {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f
        val startY = displayMetrics.heightPixels * 0.5f // Od poloviny
        // Pokud swajpujeme NAHORU (posouváme obsah dolů -> táhneme prstem dolů)
        // Uživatelský "Swipe Up" běžně znamená "Chci vidět obsah dole" -> což je tažení prstem
        // nahoru.
        // Gesture API funguje jako trackpad: táhneme nahoru (menší Y) -> text scrolluje dolů.
        val endY =
                if (isUp) displayMetrics.heightPixels * 0.2f else displayMetrics.heightPixels * 0.8f

        val path =
                android.graphics.Path().apply {
                    moveTo(centerX, startY)
                    lineTo(centerX, endY)
                }
        val stroke =
                android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 300L)
        val gesture =
                android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.i(TAG, "Gesto: Swipe ${if (isUp) "UP" else "DOWN"}")
    }

    private fun toggleFlashlight() {
        try {
            val cameraManager =
                    getSystemService(android.content.Context.CAMERA_SERVICE) as
                            android.hardware.camera2.CameraManager
            val cameraId = cameraManager.cameraIdList[0] // Obvykle zadní kamera
            isFlashlightOn = !isFlashlightOn
            cameraManager.setTorchMode(cameraId, isFlashlightOn)
            Log.i(TAG, "Gesto: Svítilna ${if (isFlashlightOn) "ZAPNUTA" else "VYPNUTA"}")
        } catch (e: Exception) {
            Log.e(TAG, "Gesto: Chyba přepínání svítilny", e)
        }
    }

    /**
     * Zpracuje přijatý screenshot ze systému, předá jej do VisionSourceManagera pro kompresi a
     * získání bajtů, dočasně je pošle do inference engine. Metoda brání Out of memory leakům.
     */
    fun processVisionScreenshot(screenshot: android.graphics.Bitmap) {
        serviceScope.launch(Dispatchers.Default) {
            try {
                Log.i(TAG, "👓 Multimodal: Služba převzala screenshot na analýzu")
                val imageBytes =
                        visionManager.processAndCompressImageBlocking(
                                screenshot,
                                recycleSource = true
                        )
                if (imageBytes != null) {
                    val directEngine = getDirectInferenceEngine()
                    Log.i(TAG, "Odesílám ${imageBytes.size} bajtů obrazu do Inference Engine.")
                    val success = directEngine.processImage(imageBytes)
                    if (success) {
                        Log.i(TAG, "Obrázek byl úspěšně zpracován jako vision tokens (mtmd).")
                    } else {
                        Log.e(TAG, "C++ backend selhal při zpracování obrázku.")
                    }
                } else {
                    Log.e(TAG, "VisionSourceManager vrátil null, obrazová analýza se nekoná.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Kritická chyba ve flow zpracování screenshotu", e)
            }
        }
    }

    /**
     * Asynchronní pořízení snímku obrazovky pomocí Accessibility API 30+ Slouží pro
     * `AgentCoordinator` jako vizuální kontext při dotazech typu "Co vidíš?".
     */
    /**
     * Zachytí aktuální obraz pro analýzu agentem. Prioritizuje Live Kameru (pokud je zapnutá a
     * SmartVision povolen), jinak bere Screenshot.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun captureScreenshotAsync(): android.graphics.Bitmap? {
        // [VISION] Pokud je zapnutá Live Camera a SmartVision, použijeme reálný svět
        if (settingsManager.isSmartVisionBoundingBoxEnabled &&
                        settingsManager.isSmartVisionCameraLiveEnabled
        ) {
            val cameraFrame = lastCameraFrame
            if (cameraFrame != null && !cameraFrame.isRecycled) {
                Log.i(TAG, "📸 Používám pro AI analýzu frame z Live Kamery")
                return cameraFrame.copy(
                        cameraFrame.config ?: android.graphics.Bitmap.Config.ARGB_8888,
                        false
                )
            }
            Log.w(TAG, "⚠️ Live Camera aktivní, ale nemám frame. Fallback na screenshot...")
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            return suspendCancellableCoroutine { continuation ->
                takeScreenshot(
                        android.view.Display.DEFAULT_DISPLAY,
                        applicationContext.mainExecutor,
                        object :
                                android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                            override fun onSuccess(
                                    screenshotResult:
                                            android.accessibilityservice.AccessibilityService.ScreenshotResult
                            ) {
                                try {
                                    val hardwareBuffer = screenshotResult.hardwareBuffer
                                    val bitmap =
                                            android.graphics.Bitmap.wrapHardwareBuffer(
                                                    hardwareBuffer,
                                                    screenshotResult.colorSpace
                                            )
                                    // Bitmap z hardwareBufferu bývá immutable, ale dá se
                                    // zkopírovat, pokud bychom manipulovali,
                                    // pro procesování bytes obvykle komprese funguje
                                    // Kopie software bitmapy uvolní buffer na 100% spolehlivě
                                    val swBitmap =
                                            bitmap?.copy(
                                                    android.graphics.Bitmap.Config.ARGB_8888,
                                                    false
                                            )
                                    hardwareBuffer.close() // Close HW buffer bezpečně
                                    continuation.resume(swBitmap)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Chyba při zpracování screenshot hw bufferu", e)
                                    continuation.resume(null)
                                }
                            }

                            override fun onFailure(errorCode: Int) {
                                Log.e(TAG, "TakeScreenshot failed with error code: $errorCode")
                                continuation.resume(null)
                            }
                        }
                )
            }
        } else {
            Log.w(TAG, "Screenshot vyžaduje Android 11+ (API 30+)")
            return null
        }
    }

    /**
     * Spustí CameraX ImageAnalysis pro SmartVision Live Camera. Používá BACK_CAMERA +
     * STRATEGY_KEEP_ONLY_LATEST pro real-time analýzu. MUSÍ být voláno z Main Threadu (CameraX
     * lifecycle requirement).
     */
    private fun startSmartVisionCamera() {
        if (com.example.llama.agent.vision.VisionSourceManager.isStudioActive) {
            Log.i(TAG, "Přeskakuji startSmartVisionCamera - Studio má vlastní kameru.")
            return
        }

        if (smartVisionCameraProvider != null) {
            Log.w(TAG, "SmartVision Camera již běží — přeskakuji duplicitní start")
            return
        }

        if (settingsManager.perceptionCameraSource == 2) {
            Log.i(TAG, "Přeskakuji CameraX - spouštím Screen Capture (MediaProjection).")
            if (settingsManager.isSmartVisionBoundingBoxEnabled) {
                if (smartVisionOverlay == null) {
                    smartVisionOverlay = com.example.llama.agent.vision.SmartVisionOverlayManager(this@AiAgentAccessibilityService)
                }
                smartVisionOverlay?.showOverlay()
            }
            // Zkontrolujeme, zda už MediaProjection neběží
            val isProjectionRunning = com.example.llama.agent.perception.TranscriptForegroundService.activeInterceptor?.hasActiveProjection == true
            if (!isProjectionRunning) {
                Log.i(TAG, "MediaProjection neběží, žádám o Token...")
                com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                    com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TOGGLE_SCREEN_DETECTION)
                )
            } else {
                Log.i(TAG, "MediaProjection už běží, posílám ACTION_START_SYSTEM_VIDEO...")
                val metrics = resources.displayMetrics
                val intent = android.content.Intent(applicationContext, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                    action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_START_SYSTEM_VIDEO
                    putExtra("width", metrics.widthPixels)
                    putExtra("height", metrics.heightPixels)
                    putExtra("density", metrics.densityDpi)
                }
                try {
                    androidx.core.content.ContextCompat.startForegroundService(applicationContext, intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Nepodařilo se spustit TranscriptForegroundService", e)
                }
            }
            return
        }

        try {
            val cameraProviderFuture =
                    androidx.camera.lifecycle.ProcessCameraProvider.getInstance(applicationContext)
            cameraProviderFuture.addListener(
                    {
                        try {
                            // Zabrání race condition: pokud se Studio zapnulo během čekání na Future
                            if (com.example.llama.agent.vision.VisionSourceManager.isStudioActive) {
                                Log.i(TAG, "SmartVision Camera start přerušen uvnitř listeneru - Studio je aktivní.")
                                return@addListener
                            }

                            val provider = cameraProviderFuture.get()
                            smartVisionCameraProvider = provider

                            // ImageAnalysis UseCase — YUV_420_888 pro LiveVideoStreamer
                            val imageAnalysis =
                                    androidx.camera.core.ImageAnalysis.Builder()
                                            .setOutputImageFormat(
                                                    androidx.camera.core.ImageAnalysis
                                                            .OUTPUT_IMAGE_FORMAT_RGBA_8888
                                            )
                                            .setBackpressureStrategy(
                                                    androidx.camera.core.ImageAnalysis
                                                            .STRATEGY_KEEP_ONLY_LATEST
                                            )
                                            .build()
                            smartVisionImageAnalysis = imageAnalysis

                            // Inicializace LiveVideoStreamer
                            val streamer =
                                    com.example.llama.agent.vision.VisionSourceManager
                                            .LiveVideoStreamer(applicationContext)
                            streamer.onFrameProcessed = { compressedBytes ->
                                // 1. Asynchronní odeslání do InferenceEngine (pro real-time tokeny)
                                serviceScope.launch {
                                    try {
                                        val engine = getDirectInferenceEngine()
                                        engine.processImage(compressedBytes)
                                    } catch (e: IllegalStateException) {
                                        // Ignorováno: LLM engine vyhodí výjimku (např. Cannot process image in Initialized!),
                                        // pokud právě neprovádí inferenci. U live feedu na pozadí je to normální stav.
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Chyba při odesílání live framu do engine", e)
                                    }
                                }

                                // 2. Uložit pro on-demand LLM query (Bitmap provider)
                                try {
                                    val bitmap =
                                            android.graphics.BitmapFactory.decodeByteArray(
                                                    compressedBytes,
                                                    0,
                                                    compressedBytes.size
                                            )
                                    if (bitmap != null) {
                                        updateLastCameraFrame(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(
                                            TAG,
                                            "Chyba při dekódování live framu pro lastCameraFrame",
                                            e
                                    )
                                }
                            }
                            liveVideoStreamer = streamer

                            // Navázání analyzéru
                            imageAnalysis.setAnalyzer(
                                    java.util.concurrent.Executors.newSingleThreadExecutor()
                            ) { imageProxy: androidx.camera.core.ImageProxy ->
                                streamer.processImageProxy(imageProxy)
                            }

                            // Získání správné kamery dle nastavení z UI
                            val lensFacing =
                                    if (settingsManager.perceptionCameraSource == 1) {
                                        androidx.camera.core.CameraSelector.LENS_FACING_FRONT
                                    } else {
                                        androidx.camera.core.CameraSelector.LENS_FACING_BACK
                                    }

                            // Bind to lifecycle via CameraCoordinator
                            if (cameraCoordinator == null) {
                                cameraCoordinator = com.example.llama.agent.vision.camera.core.CameraCoordinator(applicationContext)
                            }
                            cameraCoordinator?.bind(
                                cameraProvider = provider,
                                lifecycleOwner = this@AiAgentAccessibilityService,
                                preview = null,
                                imageAnalysis = imageAnalysis,
                                lensFacing = lensFacing
                            )

                            // Zobrazit overlay pokud je povolený
                            if (settingsManager.isSmartVisionBoundingBoxEnabled) {
                                if (smartVisionOverlay == null) {
                                    smartVisionOverlay =
                                            com.example.llama.agent.vision
                                                    .SmartVisionOverlayManager(this@AiAgentAccessibilityService)
                                }
                                smartVisionOverlay?.showOverlay()
                            }

                            val camText =
                                    if (settingsManager.perceptionCameraSource == 1) "Front Camera"
                                    else "Back Camera"
                            Log.i(TAG, "✅ SmartVision CameraX Live spuštěna ($camText)")
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Selhání inicializace SmartVision CameraX", e)
                            smartVisionCameraProvider = null
                        }
                    },
                    androidx.core.content.ContextCompat.getMainExecutor(applicationContext)
            )
        } catch (e: Exception) {
            Log.e(TAG, "❌ Selhání získání ProcessCameraProvider", e)
        }
    }

    /** Zastaví SmartVision CameraX pipeline a uvolní prostředky. MUSÍ být voláno z Main Threadu. */
    private fun stopSmartVisionCamera() {
        try {
            val isProjectionRunning = com.example.llama.agent.perception.TranscriptForegroundService.activeInterceptor?.hasActiveProjection == true
            if (isProjectionRunning || settingsManager.perceptionCameraSource == 2) {
                Log.i(TAG, "Zastavuji Screen Capture Video Mode...")
                val intent = android.content.Intent(applicationContext, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                    action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
                }
                applicationContext.startService(intent)
            }
            cameraCoordinator?.release()
            cameraCoordinator = null
            smartVisionImageAnalysis?.let { smartVisionCameraProvider?.unbind(it) }
            smartVisionImageAnalysis = null
            smartVisionCameraProvider = null
            liveVideoStreamer?.onFrameProcessed = null
            liveVideoStreamer = null
            smartVisionOverlay?.hideOverlay()
            Log.i(TAG, "🛑 SmartVision CameraX Live zastavena")
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při zastavování SmartVision CameraX", e)
        }
    }

    private fun initializeFloatingButton() {
        floatingButton =
                FloatingAgentButton(this, settingsManager) {
                    // Tap Listener: Toggle Voice
                    if (agentMode.value is AgentMode.Idle || agentMode.value is AgentMode.Error) {
                        // Start listening
                        startVoiceCommand()
                    } else {
                        // Stop/Reset
                        deactivateAgent("User tapped button")
                    }
                }
        floatingButton?.show()

        // Observe State for UI Updates
        serviceScope.launch(Dispatchers.Main) {
            agentMode.collect { mode ->
                val btnState =
                        when (mode) {
                            is AgentMode.Idle, is AgentMode.Error -> FloatingAgentButton.State.IDLE
                            is AgentMode.Thinking, is AgentMode.Executing ->
                                    FloatingAgentButton.State.PROCESSING
                            is AgentMode.Active -> {
                                if (mode.command.contains("Listening", ignoreCase = true)) {
                                    FloatingAgentButton.State.LISTENING
                                } else {
                                    FloatingAgentButton.State.PROCESSING
                                }
                            }
                        }
                // STUDIO_BUSY má prioritu — nepřepisujeme ho agentMode změnami
                if (floatingButton?.currentState != FloatingAgentButton.State.STUDIO_BUSY) {
                    floatingButton?.updateState(btnState)
                }
            }
        }

        // Nasloucháme StudioBusyEvent → FAB zešedne / odšedí
        serviceScope.launch(Dispatchers.Main) {
            com.example.llama.agent.perception.PerceptionEventBus.events.collect { event ->
                if (event is com.example.llama.agent.perception.StudioBusyEvent) {
                    val studioActive = event.task !=
                        com.example.llama.AudioStudioEngineGate.StudioTask.NONE
                    if (studioActive) {
                        floatingButton?.updateState(FloatingAgentButton.State.STUDIO_BUSY)
                        floatingButton?.setToolbarEnabled(false,
                            "⏳ ${event.task.label} probíhá v Audio Studiu")
                    } else {
                        floatingButton?.setToolbarEnabled(true)
                        floatingButton?.updateState(FloatingAgentButton.State.IDLE)
                    }
                }
            }
        }
    }


    // LOOP FIX: Mute STT when TTS is speaking
    private fun setupAudioCoordination() {
        serviceScope.launch {
            val tts = voiceTTS ?: return@launch
            tts.state.collect { ttsState ->
                val stt = voiceSTT // Capture nullable ref
                when (ttsState) {
                    is com.example.llama.agent.voice.AgentVoiceTTS.TTSState.Speaking -> {
                        if (stt != null && stt.isListening()) {
                            // RACE CONDITION FIX: Don't stop listening if we just started it
                            // (within last 2 seconds)
                            // This prevents "Posloucham" TTS from killing the verify listening
                            // session
                            val timeSinceStart = System.currentTimeMillis() - stt.getLastStartTime()
                            if (timeSinceStart > 2000) {
                                Log.i(
                                        TAG,
                                        "🔇 Muting Mic because Agent is speaking (Long running session)"
                                )
                                stt.stopListening()
                            } else {
                                Log.i(
                                        TAG,
                                        "🛡️ Ignoring TTS Mute - STT started recently ($timeSinceStart ms)"
                                )
                            }
                        }
                    }
                    is com.example.llama.agent.voice.AgentVoiceTTS.TTSState.Idle -> {
                        // If Agent finished speaking and Wake Word is enabled, re-enable mic
                        // Only if we are not currently processing a command (AgentMode should be
                        // Idle usually, or Active but waiting)
                        // If AgentMode is Active, we might be executing, so we might want mic off?
                        // Actually, if Agent is ACTIVE, we probably want Mic OFF unless explicitly
                        // asked.
                        // But for "Conversation", we might want it ON.
                        // For now, let's stick to: "If Wake Word Enabled AND Agent Idle, turn ON".

                        if (settingsManager.isWakeWordEnabled && _agentMode.value is AgentMode.Idle
                        ) {
                            // Check if already listening to avoid redundant calls
                            if (stt != null && !stt.isListening()) {
                                Log.i(TAG, "👂 Unmuting Mic (Agent finished speaking)")
                                delay(200) // Avoid echo
                                stt.startContinuousListening()
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        // EVOLUCE: Periodický flush naučené paměti (jen pokud dirty + 60s uplynulo)
        ScreenContextMemory.periodicFlushIfNeeded(applicationContext)

        // rootInActiveWindow je drahé IPC volání — voláme MAX 1x per event
        // a pouze pokud je skutečně potřeba (lazy val v lokálním scope)
        val rootNode: AccessibilityNodeInfo? by lazy { rootInActiveWindow }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString() ?: "unknown"
                Log.d(TAG, "📱 Window changed: $packageName")

                // Invalidace AppLayoutCache při přepnutí okna (nová obrazovka = stará cache
                // neplatná)
                AppLayoutCache.invalidate(packageName)

                // SystemToggleController: Volat POUZE pokud čeká pending toggle akce
                // Guard hasPendingAction() zabrání drahému rootInActiveWindow + rekurzivnímu
                // findSwitch()
                // při každém window eventu kdy žádný toggle nečeká
                if (systemToggleController?.hasPendingAction() == true) {
                    systemToggleController?.onWindowChanged(rootNode)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // UI content se změnil (např. po kliknutí)
                // Proactive agent support: Notify coordinator
                coordinator?.onScreenChanged(rootNode)

                // Toggle controller: reaguj i na content change (přepínač se může objevit po
                // animaci)
                if (systemToggleController?.hasPendingAction() == true) {
                    systemToggleController?.onWindowChanged(rootNode)
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                Log.d(TAG, "👆 Click detected")
            }
            else -> {}
        }

        // VoIP Call Detection — pouze pro VoIP balíčky (filtr před rootInActiveWindow)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val packageName = event.packageName?.toString()
            if (packageName != null && isVoipPackage(packageName)) {
                // rootNode je lazy — IPC volání proběhne jen pro VoIP balíčky
                val detection = voipController?.detectIncomingCall(rootNode)
                if (detection != null && detection.first) {
                    Log.i(TAG, "📞 VoIP incoming call detected: ${detection.second}")
                }
            }
        }

        // ODSTRANĚNO: coordinator?.onScreenChanged(rootInActiveWindow)
        // Důvod: onScreenChanged() neexistuje v AgentCoordinator — byl dead call
        // spouštějící coroutine při každém eventu v AgentMode.Active stavu.
        // Coordinator je aktivován výhradně přes executeCommand() z activateAgent().
    }

    /**
     * Rychlý filtr pro VoIP balíčky. Optimalizace: Kontroluje VoIP detekci jen pro známé balíčky.
     */
    private fun isVoipPackage(pkg: String): Boolean {
        return pkg.contains("whatsapp") ||
                pkg.contains("discord") ||
                pkg.contains("orca") ||
                pkg.contains("securesms") ||
                pkg.contains("telegram") ||
                pkg.contains("viber")
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ Service interrupted by system")
        // Stop feedback immediately
        voiceTTS?.stop()
        cancelVoiceListening()

        _agentMode.value = AgentMode.Error(message = "Service byl přerušen systémem")
    }

    /**
     * PUBLIC API: Aktivovat agenta s user command Volá se z MainActivity nebo AgentControlFragment
     */
    fun activateAgent(userCommand: String) {
        wakeUpScreenParser()
        
        if (!::settingsManager.isInitialized) {
            Log.e(TAG, "❌ Cannot activate agent: Settings not initialized")
            showDiagnosticToast("❌ Agent: settingsManager není inicializován")
            return
        }

        if (!settingsManager.isAgentEnabled) {
            Log.w(TAG, "🛑 Agent activation blocked: Agent is DISABLED in settings")
            voiceTTS?.speak("Agent je vypnutý v nastavení.", interrupt = true)
            showDiagnosticToast("🛑 Agent je vypnutý v nastavení")
            return
        }

        // LAZY INIT GUARD: Pokud komponenty ještě nebyly inicializovány, proveď on-demand
        if (coordinator == null) {
            Log.i(TAG, "🔄 Agent components not yet initialized — performing lazy init")
            showDiagnosticToast("🔄 Inicializuji agent komponenty...")
            initializeAgentComponents()
        }
        val activeCoordinator =
                coordinator
                        ?: run {
                            Log.e(TAG, "❌ Cannot activate agent: Coordinator failed to initialize")
                            showDiagnosticToast(
                                    "❌ Coordinator selhal při inicializaci! Zkontrolujte logy."
                            )
                            return
                        }

        Log.i(TAG, "🚀 Activating agent with command: \"$userCommand\"")

        // DIAGNOSTIC: Ověření stavu obrazovky PŘED executeCommand
        val preCheckRoot = findActiveRoot()
        if (preCheckRoot != null) {
            // FIX: parseScreen() rekurzivně prochází strom — childCount je jen root level
            // (DecorView=2-3)
            // Zobrazujeme reálný počet interaktivních elementů, ne childCount rootu
            val preCheckScreen = screenParser?.parseScreen(preCheckRoot)
            val elementCount = preCheckScreen?.elements?.size ?: 0
            preCheckScreen?.recycle()
            Log.i(
                    TAG,
                    "👁️ findActiveRoot LIVE: package=${preCheckRoot.packageName}, elements=$elementCount (rootChildren=${preCheckRoot.childCount})"
            )
            showDiagnosticToast("👁️ Obrazovka: ${preCheckRoot.packageName} ($elementCount prvků)")
        } else {
            Log.e(TAG, "🚨 findActiveRoot=NULL! Engine NEVIDÍ obrazovku!")
            showDiagnosticToast(
                    "🚨 Agent NEVIDÍ cílovou obrazovku!\nZkontrolujte Usnadnění přístupu"
            )
            Log.e(
                    TAG,
                    "🚨 Zkontrolujte: 1) Samsung Restricted Settings, 2) Accessibility Service je povolená, 3) Overlay app neblokuje focus"
            )
            serviceInfo?.let { info ->
                val canRetrieve =
                        (info.capabilities and
                                AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) !=
                                0
                Log.e(
                        TAG,
                        "🚨 Service capabilities: canRetrieveWindowContent=$canRetrieve, flags=0x${info.flags.toString(16)}"
                )
            }
        }

        // INTEGRACE: Debug log context
        Log.v(TAG, "Screen Context: ${getScreenContext().take(50)}...")
        val activeTTS = voiceTTS // Capture nullable ref for this scope

        serviceScope.launch {
            // File-based pipeline trace (Samsung potlačuje logcat)
            val trace = StringBuilder()
            trace.appendLine("=== Pipeline Trace ===")
            trace.appendLine("Command: \"$userCommand\"")
            trace.appendLine(
                    "Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
            )
            val rootCheck = rootInActiveWindow
            trace.appendLine(
                    "rootInActiveWindow: ${if (rootCheck != null) "OK (${rootCheck.packageName}, ${rootCheck.childCount} children)" else "NULL"}"
            )
            trace.appendLine("coordinator: OK")
            trace.appendLine("settingsManager.isAgentEnabled: ${settingsManager.isAgentEnabled}")
            val agentTimeout = settingsManager.agentTimeoutMs
            trace.appendLine("agentTimeoutMs: ${agentTimeout}ms (${agentTimeout / 1000}s)")

            try {
                // Timeout z nastavení (default 30s, konfigurovatelný 5-120s)
                withTimeout(agentTimeout) {
                    _agentMode.value =
                            AgentMode.Active(
                                    command = userCommand,
                                    startTime = System.currentTimeMillis()
                            )
                    trace.appendLine("[1] AgentMode set to Active")

                    // TTS: Oznámení startu
                    activeTTS?.speak("Vykonávám příkaz", interrupt = true)
                    trace.appendLine("[2] TTS spoke 'Vykonávám příkaz'")

                    // Execute command via coordinator
                    trace.appendLine("[3] Calling executeCommand()...")
                    val execStart = System.currentTimeMillis()
                    val result =
                            activeCoordinator.executeCommand(userCommand, findActiveRoot()) {
                                    newMode: AgentMode ->
                                // Callback for state updates (Thinking, Executing)
                                _agentMode.value = newMode
                                Log.d(TAG, "Agent state updated: $newMode")
                                trace.appendLine(
                                        "[3.x] State update: $newMode @${System.currentTimeMillis() - execStart}ms"
                                )
                            }
                    val execDuration = System.currentTimeMillis() - execStart
                    trace.appendLine("[4] executeCommand() returned in ${execDuration}ms")

                    if (result.isSuccess) {
                        val successMessage = result.getOrNull() ?: "Příkaz dokončen"
                        Log.i(TAG, "✅ Command completed: $successMessage")
                        trace.appendLine("[5] SUCCESS: $successMessage")

                        // TTS: Potvrzení úspěchu
                        activeTTS?.speak("Hotovo", interrupt = false)
                    } else {
                        val error = result.exceptionOrNull()
                        Log.e(TAG, "❌ Command failed: ${error?.message}", error)
                        trace.appendLine("[5] FAILURE: ${error?.message}")
                        trace.appendLine("[5.e] Exception type: ${error?.javaClass?.simpleName}")
                        trace.appendLine("[5.stack] ${error?.stackTraceToString()?.take(500)}")

                        // TTS: Oznámení chyby
                        activeTTS?.speak(
                                "Chyba: ${error?.message ?: "Neznámá chyba"}",
                                interrupt = true
                        )

                        // Show error state briefly before reset
                        _agentMode.value =
                                AgentMode.Error(
                                        message = error?.message ?: "Unknown error",
                                        cause = error
                                )
                        delay(2000) // Show error for 2s
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "⏰ Agent execution TIMED OUT!", e)
                trace.appendLine("[TIMEOUT] Pipeline exceeded 12.5s!")
                activeTTS?.speak("Příkaz trval příliš dlouho.", interrupt = true)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Agent execution failed", e)
                trace.appendLine("[EXCEPTION] ${e.javaClass.simpleName}: ${e.message}")
                trace.appendLine("[STACK] ${e.stackTraceToString().take(500)}")

                // TTS: Specific error message (Zero Silent Failures)
                val messageToSpeak =
                        when {
                            e is IllegalStateException || e is IllegalArgumentException -> e.message
                                            ?: "Chyba stavu"
                            else -> "Kritická chyba: ${e.message}"
                        }

                activeTTS?.speak(messageToSpeak, interrupt = true)
            } finally {
                // [CRITICAL FIX] Always reset to Idle to unlock Wake Word / Next Command
                Log.i(TAG, "🔄 Resetting Agent Mode to IDLE")
                _agentMode.value = AgentMode.Idle
                trace.appendLine("[FINAL] Reset to Idle")
                trace.appendLine("=== END ===")

                // [VISION] Vyčistit bounding boxy po dokončení akce
                smartVisionOverlay?.clearDetections()

                // Write trace to file
                try {
                    val traceFile =
                            java.io.File(applicationContext.filesDir, "agent_pipeline_trace.txt")
                    // Append (keep last 5 traces)
                    val existing = if (traceFile.exists()) traceFile.readText() else ""
                    val traces = existing.split("=== Pipeline Trace ===").filter { it.isNotBlank() }
                    val kept = traces.takeLast(4).joinToString("=== Pipeline Trace ===")
                    traceFile.writeText(
                            (if (kept.isNotBlank()) "=== Pipeline Trace ===$kept" else "") +
                                    trace.toString()
                    )
                } catch (_: Exception) {
                    /* best effort */
                }

                // Restart Wake Word listener if enabled
                updateWakeWordState()
            }
        }
    }

    /** PUBLIC API: Deaktivovat agenta */
    fun deactivateAgent(reason: String = "User requested") {
        Log.i(TAG, "🛑 Deactivating agent: $reason")
        voiceTTS?.stop() // Stop any ongoing TTS
        stopVoiceListening() // Stop any ongoing STT
        _agentMode.value = AgentMode.Idle
    }

    /** PUBLIC API: Stop voice listening (např. z Notification Action) */
    fun stopVoiceListening() {
        voiceSTT?.stopListening()
    }

    /** PUBLIC API: Cancel voice listening immediately (hard stop) */
    fun cancelVoiceListening() {
        voiceSTT?.cancel()
    }

    /** PUBLIC API: Předává kontext rozhraní Asistenta do Coordinatoru z AiAgentSession. */
    fun updateAssistContext(
            structure: android.app.assist.AssistStructure?,
            content: android.app.assist.AssistContent?,
            data: android.os.Bundle?
    ) {
        coordinator?.updateAssistContext(structure, content, data)
    }

    /** PUBLIC API: Check if agent is currently speaking */
    fun isVoiceSpeaking(): Boolean {
        return voiceTTS?.isSpeaking() ?: false
    }

    /**
     * Aktualizovat vizuální bounding boxy na overlayi. Převádí relativní souřadnice (0-1) na pixely
     * podle displeje.
     */
    fun showVisualDetections(detections: List<AgentAction.VisionDetection>) {
        val overlay = smartVisionOverlay ?: return
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val mapped =
                detections.orEmpty().map { d: AgentAction.VisionDetection ->
                    com.example.llama.agent.vision.BoundingBoxView.Detection(
                            rect =
                                    android.graphics.RectF(
                                            d.xMin * width,
                                            d.yMin * height,
                                            d.xMax * width,
                                            d.yMax * height
                                    ),
                            label = d.label,
                            confidence = d.confidence
                    )
                }
        overlay.drawDetections(mapped)
    }

    /**
     * PUBLIC API: Announce action feedback (e.g., "Klikám") Respektuje nastavení
     * KEY_AGENT_VOICE_FEEDBACK
     */
    fun announceAction(actionType: String) {
        if (!::settingsManager.isInitialized) return
        val tts = voiceTTS ?: return

        if (settingsManager.agentVoiceFeedback) {
            tts.speakActionFeedback(actionType)
        }
    }

    /** PUBLIC API: Speak text manually (e.g. from ActionExecutor) */
    fun speak(text: String) {
        // 1) Přehrát hlasem
        voiceTTS?.speak(text, interrupt = false)

        // 2) Pokud je text dlouhý (např. popis obrazovky od SmartVision),
        // chceme ho uživateli ukázat i vizuálně přes Dialog, aby nepřišel o detaily.
        if (text.length > 80) {
            showAgentDialog("Ai Agent", text)
        } else if (!settingsManager.isAgentTtsEnabled) {
            // Pokud je TTS vypnuté a text je krátký, stačí Toast
            showDiagnosticToast(text)
        }
    }

    /** Vytvoří a zobrazí plovoucí dialog (bez nutnosti aktivity) */
    private fun showAgentDialog(title: String, message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                // Musíme dodat téma, protože běžíme v Service a ne v Activity
                val ctx =
                        android.view.ContextThemeWrapper(
                                this,
                                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
                        )
                val dialog =
                        android.app.AlertDialog.Builder(ctx)
                                .setTitle(title)
                                .setMessage(message)
                                .setPositiveButton("OK", null)
                                .create()

                // Crucial: Okno typu Overlay, aby se dialog mohl ukázat z Accessibility Service nad
                // vším
                dialog.window?.setType(
                        android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                )
                dialog.show()
            } catch (e: Exception) {
                Log.e(TAG, "Nepodařilo se zobrazit Agent Dialog", e)
                // Fallback na obyčejný (byť oříznutý) Toast
                android.widget.Toast.makeText(
                                applicationContext,
                                message,
                                android.widget.Toast.LENGTH_LONG
                        )
                        .show()
            }
        }
    }

    /**
     * PUBLIC API: Start voice command listener Agent poslouchá hlasový příkaz a automaticky ho
     * vykoná
     */
    fun startVoiceCommand() {
        Log.i(TAG, "🎤 Starting voice command listener")

        try {
            // TTS: Prompt user
            voiceTTS?.speak("Poslouchám", interrupt = true)

            // [AUDIT FIX] Capture start time to ignore old events
            val commandStartTime = System.currentTimeMillis()

            serviceScope.launch(Dispatchers.Main) {
                // FIX: Delay to allow TTS "Poslouchám" to finish properly
                delay(2000)

                // Start listening AFTER TTS is likely done
                val stt = voiceSTT
                if (stt != null) {
                    // Set Mode to Active (temporary) to prevent Wake Word loop interference
                    _agentMode.value = AgentMode.Active("Listening...", System.currentTimeMillis())
                    stt.startListening()
                }
            }

            // Observe STT results (One-shot)
            serviceScope.launch {
                val sttRef = voiceSTT ?: return@launch
                sttRef.state.collect { state ->
                    when (state) {
                        is STTState.Result -> {
                            // [AUDIT FIX] Check timestamp to prevent re-execution of old commands
                            if (state.timestamp > commandStartTime) {
                                val command = state.text
                                Log.i(
                                        TAG,
                                        "✓ Voice command received: \"$command\" (${(state.confidence * 100).toInt()}%)"
                                )

                                // TTS: Confirm command
                                voiceTTS?.speak("Vykonávám: $command", interrupt = true)

                                // Execute command
                                activateAgent(command)

                                // Cancel this collector after success (optional but good practice)
                                this.cancel()
                            } else {
                                Log.d(
                                        TAG,
                                        "Skipping old STT result from timestamp ${state.timestamp}"
                                )
                            }
                        }
                        is STTState.Error -> {
                            Log.w(TAG, "⚠️ Voice recognition error: ${state.message}")
                            voiceTTS?.speak(state.message, interrupt = true)
                            // Reset mode on error
                            _agentMode.value = AgentMode.Idle
                        }
                        else -> {
                            // Idle, Listening, Processing - ignore
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting voice command", e)
            voiceTTS?.speak("Chyba hlasového vstupu", interrupt = true)
        }
    }

    /** Spustí makro (např. sekvenci kroků). */
    @Suppress("unused")
    fun runMacro(macroJson: String) {
        if (macroEngine == null) {
            Log.e(TAG, "Cannot run macro: Engine not initialized")
            return
        }

        serviceScope.launch {
            try {
                val macro = MacroParser.parseMacro(macroJson)
                Log.i(TAG, "▶️ Running Macro: ${macro.name}")
                val success = macroEngine?.executeMacro(macro) ?: false
                if (success) {
                    voiceTTS?.speak("Makro dokončeno", interrupt = false)
                } else {
                    voiceTTS?.speak("Makro selhalo", interrupt = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Macro execution error", e)
                voiceTTS?.speak("Chyba makra", interrupt = false)
            }
        }
    }

    /** Získat textový kontext obrazovky (pro LLM RAG) */
    fun getScreenContext(): String {
        val parser = screenParser ?: return ""
        return try {
            parser.parseSemanticContent(getCurrentScreen())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen context", e)
            ""
        }
    }

    /** Získat aktuální UI root node (pro screen parsing) */
    fun getCurrentScreen(): AccessibilityNodeInfo? = findActiveRoot()

    override fun onDestroy() {
        dispatcher.onServicePreSuperOnDestroy()
        Log.i(TAG, "🔴 Service destroyed")

        // EVOLUCE: Flush naučené paměti na disk (finální zápis)
        ScreenContextMemory.flushToDisk(applicationContext)

        // Unregister listener
        if (::settingsManager.isInitialized) {
            applicationContext
                    .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(prefsListener)
        }

        // IrisMotion cleanup (nezávislý lifecycle)
        stopIrisMotion()

        // Cleanup agent components (if initialized)
        teardownAgentComponents()

        updateLastCameraFrame(null)

        // [INTEGRATION] Cleanup Engine Singleton
        Log.i(TAG, "Clearing EngineProvider...")
        EngineProvider.clear()

        instance = null
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Robustní získání kořenového uzlu CÍLOVÉ aplikace.
     *
     * KRITICKÝ FIX: rootInActiveWindow může vrátit root NAŠÍ VLASTNÍ app (AccessibilityService
     * okno) místo cílové app. Musíme filtrovat packageName == naše a vybrat APPLICATION window s
     * JINOU package.
     *
     * TalkBack/Voice Access styl:
     * 1. windows scan → APPLICATION windows != naše package
     * 2. Vybere window s nejvíce children (= plný UI tree)
     * 3. Fallback: rootInActiveWindow pokud má > 3 children a NENÍ naše
     * 4. Fallback: jakýkoliv root (lepší něž null)
     */
    private fun findActiveRoot(): AccessibilityNodeInfo? {
        val ownPackage = applicationContext.packageName // com.example.llama.aichat

        try {
            val windowList = windows
            if (windowList.isNotEmpty()) {
                var bestRoot: AccessibilityNodeInfo? = null
                var bestScore = 0
                var bestPackage: CharSequence? = null

                for (window in windowList) {
                    if (window.type ==
                                    AccessibilityWindowInfo
                                            .TYPE_APPLICATION
                    ) {
                        val wRoot = window.root ?: continue
                        val wPackage = wRoot.packageName

                        if (wPackage != null && wPackage.toString() == ownPackage) {
                            continue
                        }

                        // Vybíráme okno s nejkomplexnější stromovou strukturou
                        // maxDepth=20 je nutné pro Samsung OneUI, kde menu položky začínají až na
                        // hloubce 4-5
                        val score = countDescendants(wRoot, maxDepth = 20)

                        if (score > bestScore) {
                            bestRoot = wRoot
                            bestScore = score
                            bestPackage = wPackage
                        }
                    }
                }

                if (bestRoot != null) {
                    Log.i(TAG, "📱 findActiveRoot: TARGET window → $bestPackage (score=$bestScore)")
                    return bestRoot
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findActiveRoot windows scan failed", e)
        }

        // Priority 2: rootInActiveWindow fallback — jen pokud NENÍ naše app
        val root = rootInActiveWindow
        if (root != null) {
            val rootPkg = root.packageName?.toString()
            if (rootPkg != null && rootPkg != ownPackage) {
                Log.i(
                        TAG,
                        "📱 findActiveRoot: rootInActiveWindow → $rootPkg (${root.childCount} children)"
                )
                return root
            }
            // Root je naše app — vrátit jen jako absolut fallback
            Log.w(
                    TAG,
                    "⚠️ findActiveRoot: rootInActiveWindow je NAŠE app ($rootPkg, ${root.childCount} children) — agent nevidí cílovou obrazovku"
            )
        } else {
            Log.e(
                    TAG,
                    "🚨 findActiveRoot: rootInActiveWindow=NULL a windows scan selhal — agent je SLEPÝ"
            )
        }

        // Absolutní fallback
        return root
    }

    /**
     * Rychlý odhad velikosti UI stromu — počet descendentů do [maxDepth] úrovní. Použito v
     * findActiveRoot() pro výběr okna s největším UI stromem. O(n) kde n = nodes do maxDepth,
     * typicky < 100. Bezpečný i pro hluboké stromy.
     */
    private fun countDescendants(
            node: AccessibilityNodeInfo,
            maxDepth: Int,
            currentDepth: Int = 0
    ): Int {
        if (currentDepth >= maxDepth) return 1
        var count = 1 // Počítej sám sebe
        try {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                count += countDescendants(child, maxDepth, currentDepth + 1)
                // Neprovádíme recycle — node info reference tu jen čteme pro counting
                // a root je vrácen do volajícího kódu který ho potřebuje
            }
        } catch (_: Exception) {
            // Bezpečný fallback bei crashe na stale node
        }
        return count
    }

    /**
     * Diagnostický Toast — zobrazí se na obrazovce pro debugging (Samsung potlačuje logcat, Toast
     * je jediná viditelná diagnostika)
     */
    private fun showDiagnosticToast(message: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                                applicationContext,
                                message,
                                android.widget.Toast.LENGTH_LONG
                        )
                        .show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showDiagnosticToast failed: ${e.message}")
        }
    }

    // ========================================================================
    // TRANSCRIPT MANAGEMENT — Centralizovaná logika pro mic / system_audio
    // ========================================================================

    /**
     * Centralizovaná metoda pro spuštění TranscriptAgent s korektním zdrojem zvuku.
     *
     * Rozhodovací logika:
     * - engine="android" → VŽDY mikrofon (SpeechRecognizer nelze propojit s custom AudioRecord)
     * - engine="whisper"|"sherpa" + source="mic" → standardní mikrofon (null AudioRecord)
     * - engine="whisper"|"sherpa" + source="system_audio" → spustí consent dialog,
     *   PO potvrzení spustí TranscriptForegroundService s tokenem v extras,
     *   čeká na broadcast ACTION_SYSTEM_AUDIO_READY, který dodá Virtual AudioRecord
     *
     * PREREKVIZITA: transcriptAgent musí být inicializovaný (non-null).
     *
     * ANDROID 14+ FLOW:
     * 1. MediaProjectionPermissionActivity → systémový dialog
     * 2. User přijme → project_media AppOps je uděleno
     * 3. Spustíme TranscriptForegroundService s tokenem v extras
     * 4. Service zavolá startForeground(mediaProjection) ← nyní povoleno
     * 5. Service vytvoří Virtual AudioRecord
     * 6. Broadcast READY → spustíme TranscriptAgent
     */
    private fun startTranscriptWithSource() {
        val source = settingsManager.transcriptSource
        val engine = settingsManager.transcriptEngine

        Log.i(TAG, "🎧 startTranscriptWithSource: source=$source, engine=$engine")

        if (engine == "android") {
            // Android SpeechRecognizer VŽDY používá vlastní mikrofon
            // Nelze mu podstrčit custom AudioRecord — hardwarová limitace API
            if (source == "system_audio") {
                Log.w(TAG, "⚠️ Engine 'android' nepodporuje system_audio → automatický fallback na mic")
            }
            transcriptAgent?.start(null)
            return
        }

        // Engine je "whisper" nebo "sherpa" → podporuje oba zdroje
        if (source == "system_audio") {
            // Zkontrolujeme, zda už existuje Virtual AudioRecord z předchozí relace
            val record = com.example.llama.agent.perception.TranscriptForegroundService.virtualAudioRecord
            if (record != null && record.state == android.media.AudioRecord.STATE_INITIALIZED) {
                android.util.Log.i(TAG, "♻️ System Audio už existuje, recyklujeme ho bez nového promptu.")
                transcriptAgent?.start(record)
                return
            }

            Log.i(TAG, "📱 Spouštím MediaProjection consent dialog pro System Audio...")

            // KROK 1: Získáme consent od uživatele (project_media AppOps)
            com.example.llama.agent.perception.MediaProjectionPermissionActivity.start(
                applicationContext
            ) { resultCode, data ->
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    Log.i(TAG, "✅ MediaProjection consent získán → startuji TranscriptForegroundService")

                    // KROK 2: Spustíme FG service — token je uložen v
                    // MediaProjectionPermissionActivity.pendingResultCode/Data (static field)
                    // Service si ho přečte přes consumePendingToken() (same process, zero-copy)
                    val serviceIntent = android.content.Intent(
                        applicationContext,
                        com.example.llama.agent.perception.TranscriptForegroundService::class.java
                    ).apply {
                        action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_START_SYSTEM_AUDIO
                    }
                    try {
                        androidx.core.content.ContextCompat.startForegroundService(applicationContext, serviceIntent)
                        // TranscriptAgent se spustí až po broadcastu ACTION_SYSTEM_AUDIO_READY
                        // (přijatém v systemAudioReceiver)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Nelze spustit TranscriptForegroundService: ${e.message}", e)
                        showDiagnosticToast("Systémový zvuk: nelze spustit službu")
                        settingsManager.transcriptSource = "mic"
                        transcriptAgent?.start(null)
                    }
                } else {
                    Log.w(TAG, "❌ MediaProjection consent zamítnut uživatelem → fallback na mic")
                    showDiagnosticToast("Nahrávání systémového zvuku zamítnuto")
                    settingsManager.transcriptSource = "mic"
                    transcriptAgent?.start(null)
                }
                omniControlNotifier?.showOrUpdateNotification()
            }

        } else {
            // Klasický MIC zdroj — žádný ForegroundService nepotřebujeme
            Log.i(TAG, "🎙️ Spouštím TranscriptAgent s mikrofonem")
            transcriptAgent?.start(null)
        }
    }

    /**
     * Bezpečně zastaví TranscriptForegroundService a uvolní SystemAudioInterceptor.
     * Používá graceful shutdown intent pro bezpečné ukončení čtecího cyklu.
     * Idempotentní — vícenásobné volání je bezpečné.
     */
    private fun stopTranscriptForegroundService() {
        try {
            val serviceIntent = android.content.Intent(
                applicationContext,
                com.example.llama.agent.perception.TranscriptForegroundService::class.java
            ).apply {
                action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_AUDIO_GRACEFULLY
            }
            applicationContext.startService(serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Zastavení TranscriptForegroundService: ${e.message}")
        }

        // Uvolnění starého interceptoru převezme sama ForegroundService po doběhnutí.
        // Není potřeba ho ničit tvrdě zde.
        systemAudioInterceptor = null
    }

    /**
     * SRT Editor: Vrátí existující TranscriptAgent nebo lazy-vytvoří nový BEZ recording loop.
     * Metoda NEZMĚNÍ stav zapisování — agent se nespustí, jen se inicializuje wrapper.
     * Modely (Sherpa/Whisper) se načtou až při prvním volání transcribeOfflineChunk() (lazy).
     *
     * VOLAT VÝHRADNĚ přes AiAgentAccessibilityService.getInstance()?.getOrCreateTranscriptAgentForSrt()
     * Nikdy nevytvářet TranscriptAgent instanci přímo v Activity — to by zdvojilo modely v RAM.
     */
    fun getOrCreateTranscriptAgentForSrt(): com.example.llama.agent.perception.TranscriptAgent {
        return transcriptAgent ?: com.example.llama.agent.perception.TranscriptAgent(
            applicationContext, settingsManager
        ).also { transcriptAgent = it }
    }

    fun updateLastCameraFrame(bitmap: android.graphics.Bitmap?) {
        val old = lastCameraFrame
        lastCameraFrame = bitmap
        old?.recycle()
    }
}
