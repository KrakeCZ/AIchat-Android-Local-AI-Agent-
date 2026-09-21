package com.example.llama.agent.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.llama.R

/**
 * FloatingAgentButton — Plovoucí Push-to-Talk tlačítko
 *
 * Overlay přes celý systém (TYPE_ACCESSIBILITY_OVERLAY). Tap → toggle mikrofon, Drag → přetažení po
 * obrazovce.
 *
 * 3 stavy:
 * - IDLE: tmavé pozadí, mic ikona
 * - LISTENING: zelené pozadí, pulsující animace
 * - PROCESSING: oranžové pozadí
 */
class FloatingAgentButton(
    private val context: Context,
    private val settingsManager: com.example.llama.SettingsManager,
    private val onTapListener: () -> Unit
) {
    companion object {
        private const val TAG = "FloatingAgentButton"
    }

    enum class State {
        IDLE,
        LISTENING,
        PROCESSING,
        /** Audio Studio je aktivní — agent engine je blokován, FAB je zešedlý */
        STUDIO_BUSY
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: android.widget.LinearLayout? = null
    private var mainButtonView: FrameLayout? = null
    private var toolbarView: android.widget.LinearLayout? = null
    private var iconView: ImageView? = null
    private var pulseAnimator: ObjectAnimator? = null
    private var _currentState = State.IDLE
    private var isShowing = false
    private var isToolbarVisible = false

    /** Veřejně čitelný aktuální stav FAB (pro AiAgentAccessibilityService guard). */
    val currentState: State get() = _currentState

    // Buttons for toolbar (musí být TextView, protože ikony předáváme jako Emoji řetězce)
    private var btnVision: android.widget.TextView? = null
    private var btnPose: android.widget.TextView? = null
    private var btnAudio: android.widget.TextView? = null
    private var btnOcr: android.widget.TextView? = null
    private var btnTranscript: android.widget.TextView? = null // Zcela nové tlačítko
    private var btnSum: android.widget.TextView? = null
    private var btnTrans: android.widget.TextView? = null
    private var btnPopup: android.widget.TextView? = null
    private var btnSmartSelect: android.widget.TextView? = null

    // Rozbalovací vertikální podmenu (WorkerSubMenuPopup)
    private val subMenuPopup by lazy { com.example.llama.agent.ui.WorkerSubMenuPopup(context, android.view.ContextThemeWrapper(context, com.example.llama.R.style.Theme_AiChatSample)) }
    private val gainSliderPopup by lazy { com.example.llama.agent.ui.GainSliderPopup(context, android.view.ContextThemeWrapper(context, com.example.llama.R.style.Theme_AiChatSample)) }
    private val mixerPopup by lazy { com.example.llama.agent.ui.MixerOverlayPopup(context, android.view.ContextThemeWrapper(context, com.example.llama.R.style.Theme_AiChatSample)) }

    // OLED Ochrana: Auto-hide (fade out) po X vteřinách
    private val inactivityRunnable = Runnable {
        if (_currentState == State.IDLE) {
            overlayView?.animate()?.alpha(0.2f)?.setDuration(500)?.start()
        }
    }

    private fun resetInactivityTimer() {
        mainHandler.removeCallbacks(inactivityRunnable)
        if (_currentState == State.IDLE) {
            overlayView?.alpha = 0.85f
        }
        val autoHideSec = settingsManager.floatingButtonAutoHideSec
        if (autoHideSec > 0) {
            mainHandler.postDelayed(inactivityRunnable, autoHideSec * 1000L)
        }
    }

    // OLED Ochrana: Lock screen hide
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: android.content.Intent?) {
            if (!settingsManager.floatingButtonLockscreenHide) return
            when (intent?.action) {
                android.content.Intent.ACTION_SCREEN_OFF -> {
                    overlayView?.visibility = android.view.View.GONE
                }
                android.content.Intent.ACTION_USER_PRESENT, android.content.Intent.ACTION_SCREEN_ON -> {
                    val km = context?.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    if (km != null && !km.isKeyguardLocked) {
                        overlayView?.visibility = android.view.View.VISIBLE
                    }
                }
            }
        }
    }

    /** Zobrazí plovoucí tlačítko na obrazovce */
    fun show() {
        if (isShowing) return

        try {
            createOverlayView()
            val params = createLayoutParams()
            windowManager.addView(overlayView, params)
            isShowing = true
            updateVisualState(State.IDLE)
            resetInactivityTimer() // Start auto-hide timer

            // Register screen receiver
            try {
                context.registerReceiver(screenReceiver, android.content.IntentFilter().apply {
                    addAction(android.content.Intent.ACTION_SCREEN_OFF)
                    addAction(android.content.Intent.ACTION_SCREEN_ON)
                    addAction(android.content.Intent.ACTION_USER_PRESENT)
                })
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register screen receiver", e)
            }

            Log.i(TAG, "✓ Floating button shown")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show floating button", e)
        }
    }

    /** Odebere plovoucí tlačítko z obrazovky */
    fun hide() {
        if (!isShowing) return

        mainHandler.post {
            try {
                pulseAnimator?.cancel()
                pulseAnimator = null
                windowManager.removeView(overlayView)
                overlayView = null
                mainButtonView = null
                toolbarView = null
                iconView = null
                isShowing = false
                isToolbarVisible = false
                mainHandler.removeCallbacks(inactivityRunnable)
                try {
                    context.unregisterReceiver(screenReceiver)
                } catch (_: Exception) {
                    // Ignore if not registered
                }
                Log.i(TAG, "✓ Floating button hidden")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to hide floating button", e)
            }
        }
    }

    /** Aktualizace vizuálního stavu tlačítka */
    fun updateState(newState: State) {
        if (_currentState == newState) return
        _currentState = newState
        mainHandler.post { updateVisualState(newState) }
    }

    /** Výchozí stav — zda je overlay zobrazen */
    fun isVisible(): Boolean = isShowing

    // ==================== VYTVOŘENÍ OVERLAY ====================

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val buttonSizePx = dpToPx(settingsManager.floatingButtonSizeDp)
        val toolbarSizePx = (buttonSizePx * 0.8f).toInt()

        // Hlavní kontejner
        overlayView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }

        // Hlavní spouštěcí tlačítko
        mainButtonView = FrameLayout(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(buttonSizePx, buttonSizePx)
        }

        iconView = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_mic_24))
            setColorFilter(android.graphics.Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val padding = dpToPx(14)
            setPadding(padding, padding, padding, padding)
        }
        mainButtonView?.addView(iconView)

        // Mini Toolbar
        toolbarView = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = android.view.View.GONE
            val margin = dpToPx(8)
            setPadding(margin, 0, margin, 0)
        }

        // ============ TOGGLE TLAČÍTKA (ON/OFF stav — zelená/červená) ============
        btnVision = createToolbarButton(toolbarSizePx, "👁️") {
            settingsManager.isPerceptionEnabled = !settingsManager.isPerceptionEnabled
            updateToolbarButtonsState()
            val state = if (settingsManager.isPerceptionEnabled) "ON ✅" else "OFF ❌"
            android.widget.Toast.makeText(context, "Vision Engine: $state", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnVision?.setOnLongClickListener {
            val location = IntArray(2)
            btnVision?.getLocationOnScreen(location)
            
            val items = listOf(
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("👁️", "Master Perception: " + if(settingsManager.isPerceptionEnabled) "ON" else "OFF") {
                    settingsManager.isPerceptionEnabled = !settingsManager.isPerceptionEnabled
                    updateToolbarButtonsState()
                    android.widget.Toast.makeText(context, "Master Perception: ${settingsManager.isPerceptionEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("📷", "Live Camera: " + if(settingsManager.isSmartVisionCameraLiveEnabled) "ON" else "OFF") {
                    settingsManager.isSmartVisionCameraLiveEnabled = !settingsManager.isSmartVisionCameraLiveEnabled
                    android.widget.Toast.makeText(context, "Live Camera: ${settingsManager.isSmartVisionCameraLiveEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem(
                    emoji = "📦", 
                    text = "Object Detector: " + if(settingsManager.isObjectDetectionEnabled) "ON" else "OFF",
                    onClick = {
                        settingsManager.isObjectDetectionEnabled = !settingsManager.isObjectDetectionEnabled
                        android.widget.Toast.makeText(context, "Object Detector: ${settingsManager.isObjectDetectionEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                        
                        if (!settingsManager.isObjectDetectionEnabled && settingsManager.perceptionCameraSource == 2) {
                            settingsManager.perceptionCameraSource = 1
                            val intent = android.content.Intent(context, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                                action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
                            }
                            context.startService(intent)
                        }
                    },
                    onLongClick = {
                        settingsManager.isSmartVisionBoundingBoxEnabled = !settingsManager.isSmartVisionBoundingBoxEnabled
                        android.widget.Toast.makeText(context, "BoundingBox: " + if(settingsManager.isSmartVisionBoundingBoxEnabled) "ON" else "OFF", android.widget.Toast.LENGTH_SHORT).show()
                        true
                    }
                ),
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🦴", "Pose Estimation: " + if(settingsManager.isPoseEstimationEnabled) "ON" else "OFF") {
                    settingsManager.isPoseEstimationEnabled = !settingsManager.isPoseEstimationEnabled
                    if (settingsManager.isPoseEstimationEnabled && !settingsManager.isPerceptionEnabled) {
                        settingsManager.isPerceptionEnabled = true
                    }
                    updateToolbarButtonsState()
                    android.widget.Toast.makeText(context, "Pose Estimation: ${settingsManager.isPoseEstimationEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("📱", "Screen Detection: " + if(settingsManager.perceptionCameraSource == 2) "ON" else "OFF") {
                    if (settingsManager.perceptionCameraSource == 2) {
                        settingsManager.isSmartVisionCameraLiveEnabled = false // Vypneme i live engine NEJPRVE, aby nezačala běžet zadní kamera!
                        settingsManager.perceptionCameraSource = 0 // back to rear camera
                        
                        android.widget.Toast.makeText(context, "Screen Detection OFF", android.widget.Toast.LENGTH_SHORT).show()
                        val intent = android.content.Intent(context, com.example.llama.agent.perception.TranscriptForegroundService::class.java).apply {
                            action = com.example.llama.agent.perception.TranscriptForegroundService.ACTION_STOP_SYSTEM_VIDEO
                        }
                        context.startService(intent)
                    } else {
                        settingsManager.perceptionCameraSource = 2 // screen
                        
                        // Uděláme to jednoduše. Tímto se zavolá onSharedPreferenceChanged v AiAgentAccessibilityService,
                        // která to korektně vypne a zapne.
                        if (!settingsManager.isSmartVisionCameraLiveEnabled) {
                             settingsManager.isSmartVisionCameraLiveEnabled = true
                        }

                        
                        android.widget.Toast.makeText(context, "Screen Detection ON", android.widget.Toast.LENGTH_SHORT).show()
                        // Odpálení eventu ke spuštění MediaProjection Permission okna teď už dělá AiAgentAccessibilityService
                    }
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🔍", "OCR Scanner: " + if(settingsManager.isOcrEnabled) "ON" else "OFF") {
                    settingsManager.isOcrEnabled = !settingsManager.isOcrEnabled
                    updateToolbarButtonsState()
                    android.widget.Toast.makeText(context, "OCR: ${settingsManager.isOcrEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🖼️", "Camera Preview: " + if(settingsManager.isCameraPreviewEnabled) "ON" else "OFF") {
                    settingsManager.isCameraPreviewEnabled = !settingsManager.isCameraPreviewEnabled
                    android.widget.Toast.makeText(context, "Camera Preview: ${settingsManager.isCameraPreviewEnabled}", android.widget.Toast.LENGTH_SHORT).show()
                        // Pokud uživatel vypnul preview, pošleme null pro Zero-Leak cleanup v UI
                    if (!settingsManager.isCameraPreviewEnabled) {
                        com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                            com.example.llama.agent.perception.CameraPreviewEvent(null)
                        )
                    }
                }
            )
            subMenuPopup.show(location[0], location[1], btnVision?.width ?: 0, items)
            true
        }
        btnAudio = createToolbarButton(toolbarSizePx, "🎵") {
            settingsManager.isAudioPerceptionEnabled = !settingsManager.isAudioPerceptionEnabled
            updateToolbarButtonsState()
            val state = if (settingsManager.isAudioPerceptionEnabled) "ON ✅" else "OFF ❌"
            android.widget.Toast.makeText(context, "Audio Engine: $state", android.widget.Toast.LENGTH_SHORT).show()
        }
        // 🦴 Pose Estimation — Toggle
        btnPose = createToolbarButton(toolbarSizePx, "🦴") {
            settingsManager.isPoseEstimationEnabled = !settingsManager.isPoseEstimationEnabled
            if (settingsManager.isPoseEstimationEnabled && !settingsManager.isPerceptionEnabled) {
                settingsManager.isPerceptionEnabled = true
            }
            updateToolbarButtonsState()
            val state = if (settingsManager.isPoseEstimationEnabled) "ON ✅" else "OFF ❌"
            android.widget.Toast.makeText(context, "Pose Estimation: $state", android.widget.Toast.LENGTH_SHORT).show()
        }

        // 🔍 OCR — sub-feature PerceptionEngine (funguje jen když Vision běží)
        btnOcr = createToolbarButton(toolbarSizePx, "🔍") {
            settingsManager.isOcrEnabled = !settingsManager.isOcrEnabled
            updateToolbarButtonsState()
            val ocrState = if (settingsManager.isOcrEnabled) "ON ✅" else "OFF ❌"
            val warning = if (settingsManager.isOcrEnabled && !settingsManager.isPerceptionEnabled)
                " ⚠️ Vision Engine je vypnutý!" else ""
            android.widget.Toast.makeText(context, "OCR Scanner: $ocrState$warning", android.widget.Toast.LENGTH_SHORT).show()
        }

        // ✍️ Transkript — Toggle
        btnTranscript = createToolbarButton(toolbarSizePx, "✍️") {
            settingsManager.isTranscriptEnabled = !settingsManager.isTranscriptEnabled
            updateToolbarButtonsState()
            val state = if (settingsManager.isTranscriptEnabled) "ON ✅" else "OFF ❌"
            android.widget.Toast.makeText(context, "Transkript: $state", android.widget.Toast.LENGTH_SHORT).show()
        }
        btnTranscript?.setOnLongClickListener {
            val location = IntArray(2)
            btnTranscript?.getLocationOnScreen(location)
            
            val isSystemAudio = settingsManager.transcriptSource == "system_audio"
            val sourceText = if (isSystemAudio) "System Audio" else "Mic"

            val items = listOf(
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎙️", "Zdroj: $sourceText") {
                    val next = if (isSystemAudio) "mic" else "system_audio"
                    settingsManager.transcriptSource = next
                    android.widget.Toast.makeText(context, "Zdroj zvuku pro Transkript/Titulky: $next", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎚️", "Rychlý Gain (Zesílení)") {
                    // Otevře vertikální slider poblíž tlačítka
                    gainSliderPopup.show(location[0], location[1], settingsManager.transcriptSource == "system_audio")
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎛️", "Otevřít Mixážní pult") {
                    mixerPopup.show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎭", "Profil: Výchozí (Reset)") {
                    android.util.Log.i("FloatingAgentButton", "🔘 Klik: Profil Výchozí")
                    val defConfig = com.example.llama.agent.perception.DspFilterChain.DspConfig()
                    com.example.llama.agent.perception.TranscriptAgent.dspConfig = defConfig
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.updateConfig(defConfig)
                    com.example.llama.agent.perception.TranscriptAgent.microphoneGain = 1.0f
                    com.example.llama.agent.perception.TranscriptAgent.systemAudioGain = 1.0f
                    val bands = com.example.llama.agent.perception.TranscriptAgent.eqBandLevels
                    for (i in bands.indices) bands[i] = 0
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.equalizer?.let { eq ->
                        try {
                            for (i in 0 until eq.numberOfBands) {
                                eq.setBandLevel(i.toShort(), 0)
                            }
                        } catch (e: Exception) {}
                    }
                    android.widget.Toast.makeText(context, "Aplikován profil: Výchozí", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎭", "Profil: MHD (Hlučné okolí)") {
                    android.util.Log.i("FloatingAgentButton", "🔘 Klik: Profil MHD")
                    val mhdConfig = com.example.llama.agent.perception.DspFilterChain.DspConfig(eqEnabled = true)
                    com.example.llama.agent.perception.TranscriptAgent.dspConfig = mhdConfig
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.updateConfig(mhdConfig)
                    com.example.llama.agent.perception.TranscriptAgent.microphoneGain = 2.5f
                    // Uložení do globálního pole pro případ restartu streamu
                    val bands = com.example.llama.agent.perception.TranscriptAgent.eqBandLevels
                    if (bands.size >= 5) {
                        bands[0] = 1000
                        bands[4] = 500
                    }
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.equalizer?.let { eq ->
                        try {
                            if (eq.numberOfBands >= 5) {
                                eq.setBandLevel(0, 1000) // +10.0 dB
                                eq.setBandLevel(4, 500)  // +5.0 dB
                            }
                        } catch (e: Exception) {}
                    }
                    android.widget.Toast.makeText(context, "Aplikován profil: MHD", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎭", "Profil: TV & Streamer") {
                    android.util.Log.i("FloatingAgentButton", "🔘 Klik: Profil TV & Streamer")
                    val tvConfig = com.example.llama.agent.perception.DspFilterChain.DspConfig(eqEnabled = true)
                    com.example.llama.agent.perception.TranscriptAgent.dspConfig = tvConfig
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.updateConfig(tvConfig)
                    com.example.llama.agent.perception.TranscriptAgent.systemAudioGain = 1.5f
                    // Uložení do globálního pole pro případ restartu streamu
                    val bands = com.example.llama.agent.perception.TranscriptAgent.eqBandLevels
                    if (bands.size >= 5) {
                        bands[0] = -500
                        bands[4] = 800
                    }
                    com.example.llama.agent.perception.TranscriptAgent.instance?.dspChain?.equalizer?.let { eq ->
                        try {
                            if (eq.numberOfBands >= 5) {
                                eq.setBandLevel(0, -500) // -5.0 dB
                                eq.setBandLevel(4, 800)  // +8.0 dB
                            }
                        } catch (e: Exception) {}
                    }
                    android.widget.Toast.makeText(context, "Aplikován profil: TV & Streamer", android.widget.Toast.LENGTH_SHORT).show()
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🔊", "Expertní TEST: 1% Vol Test") {
                    android.util.Log.i("FloatingAgentButton", "🔘 Klik: Expertní TEST: 1% Vol Test")
                    // Pošleme event přes PerceptionEventBus do SystemAudioInterceptor (nebo do TranscriptForegroundService)
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                        com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TOGGLE_WAKEUP_HACK)
                    )
                    android.widget.Toast.makeText(context, "Wakeup TEST volume toggled", android.widget.Toast.LENGTH_SHORT).show()
                }
            )
            subMenuPopup.show(location[0], location[1], btnTranscript?.width ?: 0, items)
            true
        }

        // ============ AKČNÍ TLAČÍTKA (single-shot NLP — oranžová) ============
        // ✂️ Smart Select (Volné kreslení nad obrazovkou) — nahrazuje Long-Press na Mozku
        btnSmartSelect = createToolbarButton(toolbarSizePx, "✂️") {
            flashSmartSelectButton(btnSmartSelect)
            com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_SMART_SELECT)
            )
        }
        btnSmartSelect?.setOnLongClickListener {
            android.widget.Toast.makeText(context, "Smart Select: Kreslení výřezu obrazovky", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        // 🧠 Mozek (NLP) — Tap: Otevře plné SubMenu všech NLP funkcí
        btnSum = createToolbarButton(toolbarSizePx, "🧠") {
            flashActionButton(btnSum)
            val location = IntArray(2)
            btnSum?.getLocationOnScreen(location)

            val items = listOf(
                // ── AICore / Gemini Nano funkce ──
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🧠", "Shrnutí obrazovky") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_SUMMARIZE))
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("✏️", "Korektura textu") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_PROOFREAD))
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("✍️", "Přepis textu (Professional)") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_REWRITE))
                },
                // ── ML Kit funkce ──
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("💜", "Sentiment analýza") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_SENTIMENT))
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("💬", "Návrhy odpovědí") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_SMART_REPLY))
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🏷️", "Extrakce entit (NER)") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_NER))
                },
                // ── ToolbarBertWorker (on-device TFLite) ──
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("⚖️", "Logická kontrola (NLI)") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_NLI))
                },
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🎭", "Doplnění [MASK] tokenu") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_MLM))
                }
            )
            subMenuPopup.show(location[0], location[1], btnSum?.width ?: 0, items)
        }
        btnSum?.setOnLongClickListener {
            android.widget.Toast.makeText(context, "Mozek NLP: 8 AI funkcí — Shrnutí, Korektura, Přepis, Sentiment, Odpovědi, Entity, NLI, MLM", android.widget.Toast.LENGTH_LONG).show()
            true
        }

        // 🌐 Překlad — Tap: Submenu (Překlad do cílového jazyka)
        btnTrans = createToolbarButton(toolbarSizePx, "🌐") {
            flashActionButton(btnTrans)
            val location = IntArray(2)
            btnTrans?.getLocationOnScreen(location)

            val items = listOf(
                com.example.llama.agent.ui.WorkerSubMenuPopup.SubMenuItem("🌐", "Přeložit celou obrazovku") {
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TRIGGER_TRANSLATE))
                }
            )
            subMenuPopup.show(location[0], location[1], btnTrans?.width ?: 0, items)
        }
        btnTrans?.setOnLongClickListener {
            android.widget.Toast.makeText(context, "Překlad: ML Kit Translate — 59 jazyků offline", android.widget.Toast.LENGTH_SHORT).show()
            true
        }



        // 🪟 PopUp — Tap: Toggle PopUp
        btnPopup = createToolbarButton(toolbarSizePx, "🪟") {
            com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TOGGLE_POPUP)
            )
        }

        toolbarView?.addView(btnPopup)
        toolbarView?.addView(btnTrans)
        toolbarView?.addView(btnSum)
        toolbarView?.addView(btnSmartSelect)
        toolbarView?.addView(btnOcr)
        toolbarView?.addView(btnPose)
        toolbarView?.addView(btnTranscript)
        toolbarView?.addView(btnVision)
        toolbarView?.addView(btnAudio)

        // Rozhodnutí o orientaci podle X souřadnice proběhne při Touch
        overlayView?.addView(mainButtonView)
        overlayView?.addView(toolbarView)

        setupTouchHandling()
    }

    private fun createToolbarButton(sizePx: Int, textEmoji: String, onClick: () -> Unit): android.widget.TextView {
        return android.widget.TextView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = dpToPx(4)
                marginEnd = dpToPx(4)
            }
            background = ContextCompat.getDrawable(context, R.drawable.fab_agent_idle)
            text = textEmoji
            textSize = 18f
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }
    }

    /**
     * Aktualizace vizuálních stavů všech tlačítek v toolbaru.
     * Toggle: zelená (ON) / červená (OFF)
     * Akční: oranžová (klidový stav)
     */
    fun updateToolbarButtonsState() {
        val onColor = android.graphics.Color.parseColor("#4CAF50") // Zelená = ON
        val offColor = android.graphics.Color.parseColor("#F44336") // Červená = OFF

        // Toggle tlačítka — reflektují aktuální stav enginu
        btnVision?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (settingsManager.isPerceptionEnabled) onColor else offColor
        )
        btnAudio?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (settingsManager.isAudioPerceptionEnabled) onColor else offColor
        )
        // OCR: Zelená jen pokud OCR=ON A Vision=ON (bez Vision OCR nefunguje)
        val ocrEffective = settingsManager.isOcrEnabled && settingsManager.isPerceptionEnabled
        btnOcr?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (settingsManager.isOcrEnabled) {
                if (ocrEffective) onColor else android.graphics.Color.parseColor("#FF9800") // Oranžová = zapnuto ale Vision OFF
            } else offColor
        )

        // Pose: Zelená jen pokud POSE=ON A Vision=ON
        val poseEffective = settingsManager.isPoseEstimationEnabled && settingsManager.isPerceptionEnabled
        btnPose?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (settingsManager.isPoseEstimationEnabled) {
                if (poseEffective) onColor else android.graphics.Color.parseColor("#FF9800") // Oranžová = zapnuto ale Vision OFF
            } else offColor
        )
        
        btnTranscript?.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (settingsManager.isTranscriptEnabled) onColor else offColor
        )

        // Akční tlačítka — oranžová (připravené k akci)
        val actionColor = android.graphics.Color.parseColor("#FF9800") // Oranžová
        val modraColor = android.graphics.Color.parseColor("#2196F3") // Modrá
        
        btnSum?.backgroundTintList = android.content.res.ColorStateList.valueOf(actionColor)
        btnTrans?.backgroundTintList = android.content.res.ColorStateList.valueOf(actionColor)
        btnPopup?.backgroundTintList = android.content.res.ColorStateList.valueOf(actionColor)
        btnSmartSelect?.backgroundTintList = android.content.res.ColorStateList.valueOf(modraColor)
    }

    /**
     * Vizuální flash feedback — krátký zelený pulse při kliknutí na akční tlačítko.
     * Potvrzuje uživateli, že akce byla přijata.
     */
    private fun flashActionButton(btn: android.widget.TextView?) {
        btn ?: return
        val flashColor = android.graphics.Color.parseColor("#4CAF50") // Zelená
        val originalColor = android.graphics.Color.parseColor("#FF9800") // Oranžová
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(flashColor)
        mainHandler.postDelayed({
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(originalColor)
        }, 600) // 600ms flash
    }

    /**
     * Vizuální flash feedback — modrý pulse při aktivaci Smart Select.
     * Upozorní uživatele o přechodu do kreslícího režimu.
     */
    private fun flashSmartSelectButton(btn: android.widget.TextView?) {
        btn ?: return
        val flashColor = android.graphics.Color.parseColor("#2196F3") // Modrá
        val originalColor = android.graphics.Color.parseColor("#FF9800") // Oranžová
        btn.backgroundTintList = android.content.res.ColorStateList.valueOf(flashColor)
        btn.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        mainHandler.postDelayed({
            btn.backgroundTintList = android.content.res.ColorStateList.valueOf(originalColor)
        }, 800) // 800ms flash pro delší vizuální feedback
    }

    private fun toggleToolbar() {
        isToolbarVisible = !isToolbarVisible
        toolbarView?.visibility = if (isToolbarVisible) android.view.View.VISIBLE else android.view.View.GONE
        updateToolbarButtonsState()

        // Arrange views (levá/pravá)
        val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
        overlayView?.removeAllViews()
        val displayWidth = context.resources.displayMetrics.widthPixels
        if (params.x > displayWidth / 2) {
            // Tlačítko je vpravo, toolbar se vysune doleva
            overlayView?.addView(toolbarView)
            overlayView?.addView(mainButtonView)
        } else {
            // Tlačítko je vlevo, toolbar doprava
            overlayView?.addView(mainButtonView)
            overlayView?.addView(toolbarView)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var touchStartTime = 0L
        var isDragging = false
        var isLongClickFired = false

        var longClickRunnable: Runnable? = null

        mainButtonView?.setOnTouchListener { _, event ->
            val params =
                    overlayView?.layoutParams as? WindowManager.LayoutParams
                            ?: return@setOnTouchListener false

            resetInactivityTimer()

            val moveThreshold = settingsManager.floatingButtonMoveThresholdPx
            val clickThreshold = settingsManager.floatingButtonClickThresholdMs

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    touchStartTime = System.currentTimeMillis()
                    isDragging = false
                    isLongClickFired = false

                    longClickRunnable = Runnable {
                        if (!isDragging) {
                            toggleToolbar()
                            isLongClickFired = true
                        }
                    }
                    longClickRunnable?.let { mainHandler.postDelayed(it, 500) } // 500ms for long press
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > moveThreshold || Math.abs(dy) > moveThreshold) {
                        isDragging = true
                        longClickRunnable?.let { mainHandler.removeCallbacks(it) }
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longClickRunnable?.let { mainHandler.removeCallbacks(it) }
                    val elapsed = System.currentTimeMillis() - touchStartTime
                    if (!isDragging && !isLongClickFired && elapsed < clickThreshold) {
                        Log.i(TAG, "👆 Tap detected — invoking listener")
                        onTapListener()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ==================== VIZUÁLNÍ STAVY ====================

    private fun updateVisualState(state: State) {
        val view = mainButtonView ?: return

        // Stop previous animation
        pulseAnimator?.cancel()
        pulseAnimator = null

        when (state) {
            State.IDLE -> {
                view.background = ContextCompat.getDrawable(context, R.drawable.fab_agent_idle)
                view.alpha = 1.0f
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                iconView?.setColorFilter(android.graphics.Color.WHITE)
                // Re-enable tap listener pro IDLE stav
                mainButtonView?.setOnLongClickListener(null)
                resetInactivityTimer()
            }
            State.LISTENING -> {
                view.background = ContextCompat.getDrawable(context, R.drawable.fab_agent_listening)
                view.alpha = 1.0f
                iconView?.setColorFilter(android.graphics.Color.WHITE)

                pulseAnimator =
                        ObjectAnimator.ofFloat(view, "alpha", 1.0f, 0.6f).apply {
                            duration = 800
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.REVERSE
                            start()
                        }
            }
            State.PROCESSING -> {
                view.background =
                        ContextCompat.getDrawable(context, R.drawable.fab_agent_processing)
                view.alpha = 1.0f
                iconView?.setColorFilter(android.graphics.Color.WHITE)

                pulseAnimator =
                        ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                            duration = 2000
                            repeatCount = ValueAnimator.INFINITE
                            repeatMode = ValueAnimator.RESTART
                            start()
                        }
            }
            State.STUDIO_BUSY -> {
                // FAB zešedne — Audio Studio vlastní HW zdroje
                view.background = ContextCompat.getDrawable(context, R.drawable.fab_agent_idle)
                view.alpha = 0.45f
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                iconView?.setColorFilter(android.graphics.Color.parseColor("#888888"))
                // Long-press = informativní tooltip co běží v Studiu
                mainButtonView?.setOnLongClickListener {
                    val taskLabel = com.example.llama.AudioStudioEngineGate.currentTask.label
                    android.widget.Toast.makeText(
                        context,
                        "⏳ $taskLabel probíhá v Audio Studiu\nAgent STT/TTS je pozastaven.",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }
        }
    }

    // ==================== LAYOUT PARAMS ====================

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val buttonSizePx = dpToPx(settingsManager.floatingButtonSizeDp)
        return WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        // TYPE_ACCESSIBILITY_OVERLAY — nepotřebuje SYSTEM_ALERT_WINDOW permission
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        // Flags: neblokuje focus, průhledné pozadí, propouští dotyky mimo tlačítko
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                )
                .apply {
                    gravity = Gravity.TOP or Gravity.START
                    // Výchozí pozice: pravý dolní roh (offset od hrany)
                    val displayMetrics = context.resources.displayMetrics
                    x = displayMetrics.widthPixels - buttonSizePx - dpToPx(16)
                    y = displayMetrics.heightPixels - buttonSizePx - dpToPx(120)
                }
    }

    // ==================== STUDIO BUSY SUPPORT ====================

    /**
     * Aktivuje nebo deaktivuje všechna toolbar tlačítka.
     * Volá se při příjmu StudioBusyEvent z AiAgentAccessibilityService.
     *
     * @param enabled true = normální interakce, false = Studio je aktivní (disable)
     * @param busyLabel pokud disabled, nastaví tooltip pro každé tlačítko
     */
    fun setToolbarEnabled(enabled: Boolean, busyLabel: String? = null) {
        val buttons = listOf(btnVision, btnAudio, btnOcr, btnTranscript, btnSum, btnTrans, btnPopup, btnSmartSelect)
        buttons.forEach { btn ->
            btn?.isEnabled = enabled
            btn?.alpha = if (enabled) 1.0f else 0.4f
            if (!enabled && busyLabel != null) {
                btn?.setOnLongClickListener {
                    android.widget.Toast.makeText(context, busyLabel, android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            } else if (enabled) {
                // Obnova původních long-click listenerů proběhne při dalším toggleToolbar()
                btn?.setOnLongClickListener(null)
            }
        }
    }

    // ==================== UTILS ====================

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
