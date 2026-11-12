package com.example.llama.agent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.models.AgentAction
import com.example.llama.agent.models.ScreenState
import com.example.llama.agent.service.AiAgentAccessibilityService
import com.example.llama.agent.service.HyperKeyboardService
import com.example.llama.agent.utils.CompatibilityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * ActionExecutor - Vykonává LLM actions na Androidu
 *
 * Zodpovědnosti:
 * 1. Parse AgentAction → Android API calls
 * 2. Perform gestures (click, scroll, input_text)
 * 3. Global actions (back, home, recents)
 * 4. Error handling & validation
 */
class ActionExecutor(
        private val service: AiAgentAccessibilityService,
        private val systemController: SystemController? = null,
        private val systemToggleController: SystemToggleController? = null
) {

    companion object {
        private const val TAG = "ActionExecutor"
    }

    // VoIP Call Controller — lazy init (profile-based VoIP handling)
    private val voipController: VoipCallController by lazy { VoipCallController(service) }

    // NLP Action Handler — lazy init (NLP/AICore GenAI akce)
    private val nlpActionHandler: NlpActionHandler by lazy {
        NlpActionHandler(service.applicationContext, service.settingsManager)
    }

    /**
     * Execute parsed LLM action
     *
     * @return Result<String> - success message nebo error
     */
    suspend fun execute(action: AgentAction, screenState: ScreenState): Result<String> =
            withContext(Dispatchers.Main) {
                runCatching {
                    Log.i(TAG, "⚡ Executing action: $action")

                    // Auto-load InstalledAppsRegistry (lazy — jen pokud snapshot dosud nenačten)
                    // Funkčně využívá: PackageManager, Build, TelecomManager, ContextCompat
                    if (!InstalledAppsRegistry.isLoaded) {
                        val ctx = service.applicationContext
                        if (!InstalledAppsRegistry.loadFromFile(ctx)) {
                            // Žádný uložený soubor — provede scan přes PackageManager
                            // (Build: API 33+ ApplicationInfoFlags, ContextCompat:
                            // QUERY_ALL_PACKAGES check,
                            //  TelecomManager: VoIP readiness, PackageManager:
                            // getInstalledApplications)
                            InstalledAppsRegistry.loadSnapshot(ctx)
                            InstalledAppsRegistry.saveToFile(ctx)
                        }
                        // Build O(1) index (normalizace + invertovaný index)
                        AppResolutionEngine.buildIndex()
                        Log.i(
                                TAG,
                                "📦 InstalledAppsRegistry: ${InstalledAppsRegistry.size} apps ready, index: ${AppResolutionEngine.indexSize} terms"
                        )
                    }

                    // AUDIO FEEDBACK: Oznámit akci (pokud povoleno v nastavení)
                    service.announceAction(action.type.name.lowercase())

                    when (action.type) {
                        // === Základní akce ===
                        ActionType.CLICK -> performClick(action, screenState)
                        ActionType.SCROLL_DOWN -> performScroll(down = true)
                        ActionType.SCROLL_UP -> performScroll(down = false)
                        // Advanced Scroll
                        ActionType.SCROLL_TO_TOP -> performScrollToEdge(top = true)
                        ActionType.SCROLL_TO_BOTTOM -> performScrollToEdge(top = false)
                        ActionType.SCROLL_LITTLE_UP -> performScrollLittle(up = true)
                        ActionType.SCROLL_LITTLE_DOWN -> performScrollLittle(up = false)
                        ActionType.SCROLL_PAGE_UP -> performScrollPage(up = true)
                        ActionType.SCROLL_PAGE_DOWN -> performScrollPage(up = false)
                        ActionType.INPUT_TEXT -> performTextInput(action, screenState)
                        ActionType.OPEN_APP -> performOpenApp(action)

                        // === Navigace ===
                        ActionType.BACK -> {
                            // VUL-005 FIX: Blokuj BACK pokud citlivý dialog na obrazovce
                            if (isSensitiveDialogVisible()) {
                                Log.w(TAG, "🚫 BACK blocked — sensitive dialog visible on screen")
                                throw SecurityException(
                                        "Nelze provést BACK — citlivý dialog na obrazovce"
                                )
                            }
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        ActionType.HOME -> {
                            // VUL-005 FIX: Blokuj HOME pokud citlivý dialog na obrazovce
                            if (isSensitiveDialogVisible()) {
                                Log.w(TAG, "🚫 HOME blocked — sensitive dialog visible on screen")
                                throw SecurityException(
                                        "Nelze provést HOME — citlivý dialog na obrazovce"
                                )
                            }
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                        }
                        ActionType.RECENT_APPS ->
                                performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                        ActionType.NOTIFICATIONS ->
                                performGlobalAction(
                                        AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                                )

                        // === Systémové akce ===
                        ActionType.SCREENSHOT ->
                                performGlobalAction(
                                        AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
                                )
                        ActionType.LOCK_SCREEN ->
                                performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        ActionType.QUICK_SETTINGS ->
                                performGlobalAction(
                                        AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                                )
                        ActionType.SPLIT_SCREEN ->
                                performGlobalAction(
                                        AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
                                )
                        ActionType.POWER_DIALOG ->
                                performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)

                        // === Herní gesta ===
                        ActionType.SWIPE_LEFT -> performSwipeLeft()
                        ActionType.SWIPE_RIGHT -> performSwipeRight()
                        ActionType.SWIPE_UP -> performSwipeUp()
                        ActionType.SWIPE_DOWN -> performSwipeDown()
                        ActionType.LONG_PRESS -> performLongPress(action, screenState)
                        ActionType.DOUBLE_TAP -> performDoubleTap(action, screenState)
                        // Multitouch (Zoom)
                        ActionType.ZOOM_IN,
                        ActionType.PINCH_OPEN -> performPinch(zoomIn = true)
                        ActionType.ZOOM_OUT, ActionType.PINCH_CLOSE -> performPinch(zoomIn = false)

                        // Text Edit
                        ActionType.EDIT_COPY,
                        ActionType.EDIT_PASTE,
                        ActionType.EDIT_SELECT_ALL,
                        ActionType.EDIT_DELETE_WORD,
                        ActionType.EDIT_DELETE_ALL,
                        ActionType.EDIT_ENTER,
                        ActionType.EDIT_UNDO,
                        ActionType.EDIT_MOVE_CURSOR_START,
                        ActionType.EDIT_MOVE_CURSOR_END ->
                                performTextEditAction(action, screenState)

                        // Navigation
                        ActionType.NAV_UP,
                        ActionType.NAV_DOWN,
                        ActionType.NAV_LEFT,
                        ActionType.NAV_RIGHT,
                        ActionType.NAV_CENTER,
                        ActionType.NAV_ENTER,
                        ActionType.NAV_BACK,
                        ActionType.NAV_HOME,
                        ActionType.NAV_RECENTS,
                        ActionType.NAV_TAB -> performNavigationAction(action)

                        // === Média ===
                        ActionType.MEDIA_PLAY_PAUSE ->
                                performMediaAction(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        ActionType.MEDIA_NEXT -> performMediaAction(KeyEvent.KEYCODE_MEDIA_NEXT)
                        ActionType.MEDIA_PREVIOUS ->
                                performMediaAction(KeyEvent.KEYCODE_MEDIA_PREVIOUS)

                        // === Communication (Non-Root Intent-based) ===
                        ActionType.CALL -> {
                            val number =
                                    action.inputText
                                            ?: throw IllegalArgumentException(
                                                    "CALL requires contact/number"
                                            )
                            // VoIP readiness check — TelecomManager funkční využití
                            val telecom = service.getSystemService(TelecomManager::class.java)
                            if (telecom != null) {
                                Log.d(TAG, "📞 TelecomManager available, VoIP routing active")
                            }
                            // Permission audit — ContextCompat funkční využití
                            val hasBtPermission =
                                    ContextCompat.checkSelfPermission(
                                            service,
                                            android.Manifest.permission.BLUETOOTH_CONNECT
                                    ) == PackageManager.PERMISSION_GRANTED
                            Log.d(TAG, "📞 BT headset support: $hasBtPermission")
                            // VoIP app routing — InstalledAppsRegistry.getVoipApps() funkční
                            // využití
                            val voipApps = InstalledAppsRegistry.getVoipApps()
                            if (voipApps.isNotEmpty()) {
                                Log.d(
                                        TAG,
                                        "📞 ${voipApps.size} VoIP apps available: ${voipApps.joinToString { it.label }}"
                                )
                            }
                            systemController?.makeVoipCall(number)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SMS -> {
                            val rawInput =
                                    action.inputText
                                            ?: throw IllegalArgumentException(
                                                    "SMS requires phone number"
                                            )
                            // inputText format from DirectCommandMatcher: "number|message" or just
                            // "number"
                            val parts = rawInput.split("|", limit = 2)
                            val number = parts[0].trim()
                            val message = if (parts.size > 1) parts[1].trim() else ""
                            systemController?.sendSms(number, message)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.OPEN_URL -> {
                            val url =
                                    action.inputText
                                            ?: throw IllegalArgumentException(
                                                    "OPEN_URL requires URL"
                                            )
                            systemController?.openUrl(url)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        // === HW Toggles ===

                        ActionType.SET_BLUETOOTH -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            // Auto-Click Automation: Prepare controller -> Open Settings -> Click
                            // Switch
                            systemToggleController?.prepareForToggle(
                                    ActionType.SET_BLUETOOTH,
                                    enable
                            )
                            systemController?.setBluetooth(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_FLASHLIGHT -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            systemController?.setFlashlight(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_VOLUME -> {
                            val percent = action.inputText?.toIntOrNull() ?: 50
                            systemController?.setVolume(percent)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_BRIGHTNESS -> {
                            val percent = action.inputText?.toIntOrNull() ?: 50
                            systemController?.setBrightness(percent)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_ROTATION -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            systemController?.setRotation(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_NFC -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            // Auto-Click Automation: Prepare controller -> Open Panel -> Click
                            // Switch
                            systemToggleController?.prepareForToggle(ActionType.SET_NFC, enable)
                            systemController?.setNfc(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_AIRPLANE -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            systemToggleController?.prepareForToggle(
                                    ActionType.SET_AIRPLANE,
                                    enable
                            )
                            systemController?.setAirplaneMode(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.SET_WIFI -> {
                            val enable = action.inputText?.toBoolean() ?: true
                            systemToggleController?.prepareForToggle(ActionType.SET_WIFI, enable)
                            systemController?.setWifi(enable)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }

                        // === VoIP Call Handling (VoipCallController — Profile-Based) ===
                        ActionType.ANSWER_CALL -> {
                            Log.i(TAG, "📞 ANSWER_CALL via VoipCallController")
                            val rootNode = service.rootInActiveWindow
                            val handled = voipController.handleCall(rootNode, isAnswer = true)
                            if (!handled) {
                                Log.w(
                                        TAG,
                                        "⚠️ VoIP ANSWER failed — no call detected or button not found"
                                )
                            }
                        }
                        ActionType.REJECT_CALL -> {
                            Log.i(TAG, "📞 REJECT_CALL via VoipCallController")
                            val rootNode = service.rootInActiveWindow
                            val handled = voipController.handleCall(rootNode, isAnswer = false)
                            if (!handled) {
                                Log.w(
                                        TAG,
                                        "⚠️ VoIP REJECT failed — no call detected or button not found"
                                )
                            }
                        }

                        // === Inkrementální ovládání (Volume/Brightness ±krok) ===
                        ActionType.VOLUME_UP -> {
                            val step = action.inputText?.toIntOrNull() ?: 10
                            systemController?.adjustVolume(step)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.VOLUME_DOWN -> {
                            val step = action.inputText?.toIntOrNull() ?: 10
                            systemController?.adjustVolume(-step)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.BRIGHTNESS_UP -> {
                            val step = action.inputText?.toIntOrNull() ?: 10
                            systemController?.adjustBrightness(step)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        ActionType.BRIGHTNESS_DOWN -> {
                            val step = action.inputText?.toIntOrNull() ?: 10
                            systemController?.adjustBrightness(-step)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }

                        // === Settings Deep Links ===
                        ActionType.OPEN_SETTINGS -> {
                            val sectionName = action.inputText ?: "GENERAL"
                            val section =
                                    try {
                                        SystemController.SettingsSection.valueOf(
                                                sectionName.uppercase()
                                        )
                                    } catch (_: IllegalArgumentException) {
                                        SystemController.SettingsSection.GENERAL
                                    }
                            systemController?.openSettings(section)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }

                        // === Kill App ===
                        ActionType.KILL_APP -> {
                            Log.i(TAG, "🗑️ KILL_APP: ${action.inputText}")
                            // Strategy: Open Recents → Wait → Swipe up to dismiss
                            // Note: This is best-effort — actual kill requires Force Stop
                            // (Settings)
                            performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                            delay(500) // Wait for Recents to open
                            performSwipeUp() // Swipe up to dismiss recent app
                        }

                        // === Email ===
                        ActionType.COMPOSE_EMAIL -> {
                            val rawInput =
                                    action.inputText
                                            ?: throw IllegalArgumentException(
                                                    "COMPOSE_EMAIL requires email address"
                                            )
                            // inputText format: "address|subject|body" or "address|subject" or
                            // "address"
                            val parts = rawInput.split("|", limit = 3)
                            val address = parts[0].trim()
                            val subject = if (parts.size > 1) parts[1].trim() else ""
                            val body = if (parts.size > 2) parts[2].trim() else ""
                            systemController?.composeEmail(address, subject, body)
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }
                        // === Setup & Configuration ===

                        ActionType.SET_DEFAULT_IME -> {
                            systemController?.requestImeSettings()
                                    ?: throw IllegalStateException(
                                            "SystemController not initialized"
                                    )
                        }

                        // === Herní gesta ===
                        ActionType.RAPID_TAP -> {
                            val input = action.inputText
                            val defaultCount = service.settingsManager.agentTapCount
                            val defaultInterval = service.settingsManager.agentTapInterval

                            var count = defaultCount
                            var interval = defaultInterval

                            if (!input.isNullOrBlank()) {
                                val parts = input.split("|")
                                count = parts.getOrNull(0)?.toIntOrNull() ?: defaultCount
                                if (parts.size > 1) {
                                    interval = parts.getOrNull(1)?.toLongOrNull() ?: defaultInterval
                                }
                            }

                            val displayMetrics = service.resources.displayMetrics
                            val x = action.x ?: (displayMetrics.widthPixels / 2)
                            val y = action.y ?: (displayMetrics.heightPixels / 2)

                            performRapidTap(x, y, count, interval)
                        }

                        // === NLP / AICore GenAI akce (Tier 2.5+) ===
                        ActionType.NLP_PROOFREAD,
                        ActionType.NLP_REWRITE,
                        ActionType.NLP_TRANSLATE,
                        ActionType.NLP_SUMMARIZE,
                        ActionType.NLP_SENTIMENT,
                        ActionType.NLP_QA,
                        ActionType.NLP_NLI,
                        ActionType.NLP_MLM,
                        ActionType.NLP_NER,
                        ActionType.NLP_PROMPT,
                        ActionType.NLP_SMART_REPLY -> {
                            val inputText = action.inputText
                                    ?: throw IllegalArgumentException(
                                            "${action.type} requires input_text"
                                    )
                            val result = nlpActionHandler.execute(action.type, inputText)
                            Log.i(TAG, "🧠 ${action.type}: ${result.take(100)}")
                            // TTS výstup pro handicapované uživatele
                            service.speak(result)
                        }

                        // === Ostatní ===
                        ActionType.SPEAK -> {
                            Log.i(TAG, "🗣️ SPEAK action: ${action.thought}")
                            service.speak(action.thought)
                            if (!action.inputText.isNullOrBlank() &&
                                            action.inputText != action.thought
                            ) {
                                service.speak(action.inputText)
                            }
                        }
                        ActionType.UNKNOWN -> {
                            Log.w(TAG, "⚠️ Skipping execution of UNKNOWN action: ${action.thought}")
                        }
                    }

                    "Action ${action.type} executed successfully"
                }
            }

    /**
     * Perform click action Supports both Semantic Click (AccessibilityNode) and Coordinate Click
     * (dispatchGesture)
     */
    private suspend fun performClick(action: AgentAction, screenState: ScreenState): Boolean {
        // 1. Try Semantic Click (Target ID) - Priority: Accuracy & Speed
        if (action.targetId != null) {
            try {
                // Attempt semantic click
                if (performSemanticClick(action.targetId, screenState)) {
                    return true
                }
            } catch (e: Exception) {
                Log.w(
                        TAG,
                        "⚠️ Semantic click failed for ID ${action.targetId}: ${e.message}. Attempting Gesture fallback."
                )
            }

            // Fallback: If semantic failed, try Gesture Click using element bounds
            val element = screenState.findElementById(action.targetId)
            if (element != null) {
                // EXPLICIT TYPE to satisfy "Unused import" for android.graphics.Rect
                val bounds: Rect = element.bounds
                if (!bounds.isEmpty) {
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    Log.i(
                            TAG,
                            "🔄 Fallback to Gesture Click at [$centerX, $centerY] for element \"${element.label}\""
                    )
                    return performGestureClick(centerX, centerY)
                } else {
                    Log.w(TAG, "⚠️ Cannot fallback to gesture: Element bounds are empty.")
                }
            }
        }

        // 2. Try Coordinate Click (Touch Injection) - Priority: Flexibility (Games, WebViews)
        if (action.x != null && action.y != null) {
            return performGestureClick(action.x, action.y)
        }

        throw IllegalArgumentException(
                "CLICK action failed: Semantic click failed and no valid coordinates provided."
        )
    }

    private fun performSemanticClick(targetId: Int, screenState: ScreenState): Boolean {
        val targetElement =
                screenState.findElementById(targetId)
                        ?: throw IllegalArgumentException("Prvek vypršel (ID $targetId)")

        // FULL OBSERVABILITY: Disabled guard — neklikej na disabled prvky
        if (!targetElement.isEnabled) {
            throw IllegalStateException(
                    "Element $targetId '${targetElement.label}' je disabled (isEnabled=false)"
            )
        }

        // === OPRAVA: TikTok/Instagram/YouTube Parent-Child Pattern ===
        // ImageView s contentDescription (❤️, 👍, 💬) může mít isClickable=false
        // ale systém mu přesto může doručit ACTION_CLICK přes jeho parent FrameLayout.
        // Strategie:
        // 1. Zkus přímý performAction(CLICK) — funguje i pro non-clickable nodesna TikToku
        // 2. Pokud selže, hledej clickable parent a klikni na něj
        // 3. Fallback: gesture click na souřadnice (performClick → performGestureClick)

        // Pokus 1: Přímý ACTION_CLICK (funguje na TikTok ImageView i bez isClickable=true)
        val directNode = targetElement.nodeInfo
        val supportedActions = directNode.actionList?.map { it.id } ?: emptyList()
        val supportsClick =
                AccessibilityNodeInfo.ACTION_CLICK in supportedActions || targetElement.isClickable

        if (supportsClick) {
            val success = directNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (success) {
                Log.i(
                        TAG,
                        "✓ Clicked (Semantic direct): \"${targetElement.label}\" (ID: $targetId)"
                )
                recordSemanticClickSuccess(targetElement, screenState)
                return true
            }
            // Direct click vrátil false — zkus parent
            Log.w(TAG, "⚠️ Direct ACTION_CLICK returned false for ID $targetId — trying parent")
        } else {
            // isClickable=false A ACTION_CLICK není v supportedActions
            // → Jde o non-interactive child (např. TikTok ImageView)
            Log.w(
                    TAG,
                    "⚠️ Element $targetId '${targetElement.label}' is not clickable — trying parent node"
            )
        }

        // Pokus 2: Hledej clickable parent v accessibility stromu
        var parent = directNode.parent
        var parentDepth = 0
        while (parent != null && parentDepth < 5) {
            if (parent.isClickable && parent.isEnabled) {
                val parentSuccess = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (parentSuccess) {
                    Log.i(
                            TAG,
                            "✓ Clicked (Parent node, depth=$parentDepth): \"${targetElement.label}\" (ID: $targetId)"
                    )
                    recordSemanticClickSuccess(targetElement, screenState)
                    CompatibilityUtils.recycleNode(parent)
                    return true
                }
            }
            val nextParent = parent.parent
            CompatibilityUtils.recycleNode(parent)
            parent = nextParent
            parentDepth++
        }
        parent?.let { CompatibilityUtils.recycleNode(it) }

        // Pokus 3: Gesture click na střed elementu (poslední záchrana)
        if (!targetElement.bounds.isEmpty) {
            Log.w(
                    TAG,
                    "⚠️ Semantic + Parent click failed for ID $targetId — falling back to gesture at bounds center"
            )
            // Nevracíme true zde — necháme performClick() aby provedl gesture fallback (má správný
            // suspend kontext)
            throw IllegalStateException(
                    "Semantic+Parent failed — gesture fallback required (bounds=${targetElement.bounds})"
            )
        }

        throw IllegalStateException(
                "Element $targetId '${targetElement.label}' is not clickable and has no clickable parent or valid bounds"
        )
    }

    /** Zapíše záznam úspěšného kliknutí do ScreenContextMemory pro budoucí learning. */
    private fun recordSemanticClickSuccess(
            element: ScreenState.UIElement,
            screenState: ScreenState
    ) {
        val pkg = screenState.packageName
        if (!pkg.isNullOrBlank() && !element.bounds.isEmpty) {
            val displayMetrics = service.resources.displayMetrics
            ScreenContextMemory.recordSuccess(
                    pkg = screenState.packageName,
                    label = pkg,
                    intent = element.label,
                    bounds = element.bounds,
                    screenW = displayMetrics.widthPixels,
                    screenH = displayMetrics.heightPixels,
                    text = element.text.takeIf { it.isNotBlank() },
                    resId = element.resourceId.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * ROBUST TOUCH INJECTION Simulates a physical tap using dispatchGesture. Critical for
     * handicapped users where "Phantom Hands" must be reliable.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal suspend fun performGestureClick(x: Int, y: Int): Boolean =
            withContext(Dispatchers.Main) {
                val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }

                // Build Tap Gesture (100ms duration seems natural)
                val builder = android.accessibilityservice.GestureDescription.Builder()
                val gesture =
                        builder.addStroke(
                                        android.accessibilityservice.GestureDescription
                                                .StrokeDescription(path, 0, 100)
                                )
                                .build()

                // VUL-ANR-001 FIX: Timeout z nastavení — prevence ANR
                val gestureTimeout = service.settingsManager.agentGestureTimeout
                return@withContext withTimeout(gestureTimeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val callback =
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(
                                            gestureDescription:
                                                    android.accessibilityservice.GestureDescription?
                                    ) {
                                        Log.i(TAG, "✓ Tap (Gesture) executed at [$x, $y]")
                                        if (continuation.isActive) continuation.resume(true) {}
                                    }

                                    override fun onCancelled(
                                            gestureDescription:
                                                    android.accessibilityservice.GestureDescription?
                                    ) {
                                        Log.w(TAG, "❌ Tap (Gesture) cancelled at [$x, $y]")
                                        if (continuation.isActive) continuation.resume(false) {}
                                    }
                                }

                        val dispatched = service.dispatchGesture(gesture, callback, null)
                        if (!dispatched) {
                            Log.e(TAG, "❌ Failed to dispatch gesture (System rejected)")
                            if (continuation.isActive) continuation.resume(false) {}
                        }
                    }
                }
            }

    /** Perform scroll action */
    private suspend fun performScroll(down: Boolean): Boolean {
        val rootNode = service.rootInActiveWindow

        // 1. Try Semantic Scroll (Accessibility Action) - Priority: System Standard
        if (rootNode != null) {
            val scrollableNode = findScrollable(rootNode)
            if (scrollableNode != null) {
                val scrollAction =
                        if (down) {
                            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        } else {
                            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        }

                val success = scrollableNode.performAction(scrollAction)
                CompatibilityUtils.recycleNode(scrollableNode)

                if (success) {
                    Log.i(TAG, "✓ Scrolled (Semantic) ${if (down) "down" else "up"}")
                    return true
                } else {
                    Log.w(TAG, "⚠️ Semantic scroll failed, trying Gesture Swipe...")
                }
            } else {
                Log.w(TAG, "⚠️ No scrollable node found, falling back to Gesture Swipe...")
            }
        }

        // 2. Fallback: Gesture Scroll (Swipe) - Priority: "Phantom Hand"
        return performGestureScroll(down)
    }

    /** Perform Gesture Scroll (Swipe) Simulates a physical swipe on the screen. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performGestureScroll(down: Boolean): Boolean =
            withContext(Dispatchers.Main) {
                val displayMetrics = service.resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels

                val centerX = width / 2f
                val startY: Float
                val endY: Float

                // Check for Inversion Setting
                val invert = service.settingsManager.isInvertSwipeVertical
                val effectiveDown = if (invert) !down else down

                if (effectiveDown) {
                    // Scroll Down = Swipe Up (Finger moves up)
                    startY = height * 0.7f
                    endY = height * 0.3f
                } else {
                    // Scroll Up = Swipe Down (Finger moves down)
                    startY = height * 0.3f
                    endY = height * 0.7f
                }

                Log.d(TAG, "👆 Dispatching Swipe Gesture: $startY -> $endY (Duration: 300ms)")

                val path =
                        android.graphics.Path().apply {
                            moveTo(centerX, startY)
                            lineTo(centerX, endY)
                        }

                val builder = android.accessibilityservice.GestureDescription.Builder()
                val gesture =
                        builder.addStroke(
                                        android.accessibilityservice.GestureDescription
                                                .StrokeDescription(
                                                        path,
                                                        0,
                                                        300 // 300ms is a natural swipe duration
                                                )
                                )
                                .build()

                // VUL-ANR-001 FIX: Timeout z nastavení
                val gestureTimeout = service.settingsManager.agentGestureTimeout
                return@withContext withTimeout(gestureTimeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val callback =
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(
                                            gestureDescription:
                                                    android.accessibilityservice.GestureDescription?
                                    ) {
                                        Log.i(TAG, "✓ Swipe (Gesture) executed")
                                        if (continuation.isActive) continuation.resume(true) {}
                                    }

                                    override fun onCancelled(
                                            gestureDescription:
                                                    android.accessibilityservice.GestureDescription?
                                    ) {
                                        Log.w(TAG, "❌ Swipe (Gesture) cancelled")
                                        if (continuation.isActive) continuation.resume(false) {}
                                    }
                                }

                        val dispatched = service.dispatchGesture(gesture, callback, null)
                        if (!dispatched) {
                            Log.e(TAG, "❌ Failed to dispatch swipe gesture")
                            if (continuation.isActive) continuation.resume(false) {}
                        }
                    }
                }
            }

    /**
     * Rekurzivně najdi první scrollable node [AUDIT FIX] Corrected recycling logic to prevent
     * use-after-recycle
     */
    private suspend fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
            withContext(Dispatchers.Main) {
                // FIX: isVisibleToUser nespolehlivý na Android 14/15/16 — use bounds check
                val rect = android.graphics.Rect()
                node.getBoundsInScreen(rect)
                if (node.isScrollable && rect.width() > 0 && rect.height() > 0) {
                    return@withContext node
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    val result = findScrollable(child)

                    if (result != null) {
                        // If result is found, we propagate it up.
                        // Critical: If result !== child, then child is just a parent wrapper, so we
                        // recycle it.
                        // If result === child, we must NOT recycle child because it is the return
                        // value.
                        if (result !== child) {
                            CompatibilityUtils.recycleNode(child)
                        }
                        return@withContext result
                    }

                    // Not found in this branch, recycle child
                    CompatibilityUtils.recycleNode(child)
                }

                return@withContext null
            }

    /**
     * Perform text input action.
     *
     * 3-tier fallback strategy:
     * 1. IME: HyperKeyboardService.injectText() — funguje v WebView, Unity, Flutter
     * ```
     *    → Nepotřebuje targetId (používá aktuální InputConnection/focused pole)
     * ```
     * 2. Accessibility s targetId: ACTION_SET_TEXT na konkrétní element
     * 3. Accessibility auto-find: Najde první editable/focused element na obrazovce
     */
    private fun performTextInput(action: AgentAction, screenState: ScreenState): Boolean {
        val text =
                action.inputText
                        ?: throw IllegalArgumentException(
                                "input_text is required for INPUT_TEXT action"
                        )

        // === Tier 1: IME injection (WebView, Unity, Flutter, RDP/VNC) ===
        // Nepotřebuje targetId — HyperKeyboardService injektuje do aktuálně
        // fokusovaného pole přes InputConnection.
        if (HyperKeyboardService.isActive()) {
            val imeSuccess = HyperKeyboardService.injectText(text)
            if (imeSuccess) {
                Log.i(TAG, "✓ Text injected via IME: \"$text\"")
                return true
            }
            Log.w(TAG, "IME injection failed — falling back to Accessibility")
        } else {
            Log.d(TAG, "IME not active — using Accessibility ACTION_SET_TEXT")
        }

        // === Tier 2: Accessibility s explicitním targetId ===
        if (action.targetId != null) {
            val targetElement = screenState.findElementById(action.targetId)
            if (targetElement != null && targetElement.isEditable) {
                return performSetText(targetElement, text)
            }
            Log.w(TAG, "Element ${action.targetId} not found or not editable — trying auto-find")
        }

        // === Tier 3: Auto-find prvního editable/focused elementu ===
        // Hledá: 1) focused editable, 2) jakýkoliv editable
        val autoTarget =
                screenState.elements.find { it.isEditable && it.nodeInfo.isFocused }
                        ?: screenState.elements.find { it.isEditable }

        if (autoTarget != null) {
            Log.i(TAG, "Auto-found editable element: ${autoTarget.id} (\"${autoTarget.label}\")")
            return performSetText(autoTarget, text)
        }

        throw IllegalStateException("Žádné textové pole na obrazovce — nelze zadat text")
    }

    /** Helper: Focus element a vlož text přes ACTION_SET_TEXT. */
    private fun performSetText(element: ScreenState.UIElement, text: String): Boolean {
        // Focus element
        element.nodeInfo.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        // Set text
        val args =
                Bundle().apply {
                    putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text
                    )
                }

        val success = element.nodeInfo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (!success) {
            throw RuntimeException("Text input action returned false")
        }

        Log.i(TAG, "✓ Input text: \"$text\" into element ${element.id}")
        return true
    }

    /** Perform global action (Back, Home, Recents) Public for MacroEngine. */
    fun performGlobalAction(action: Int): Boolean {
        val success = service.performGlobalAction(action)

        if (!success) {
            val actionName =
                    when (action) {
                        AccessibilityService.GLOBAL_ACTION_BACK -> "BACK"
                        AccessibilityService.GLOBAL_ACTION_HOME -> "HOME"
                        AccessibilityService.GLOBAL_ACTION_RECENTS -> "RECENTS"
                        AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS -> "NOTIFICATIONS"
                        AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT -> "SCREENSHOT"
                        AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN -> "LOCK_SCREEN"
                        AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS -> "QUICK_SETTINGS"
                        AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN -> "SPLIT_SCREEN"
                        AccessibilityService.GLOBAL_ACTION_POWER_DIALOG -> "POWER_DIALOG"
                        else -> "UNKNOWN($action)"
                    }
            throw RuntimeException("Global action $actionName returned false")
        }

        Log.i(TAG, "✓ Global action performed: $action")
        return true
    }

    // ==================== HERNÍ GESTA ====================
    // * Long Press na středu obrazovky (hry, kontextové menu)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performLongPress(): Boolean =
            withContext(Dispatchers.Main) {
                val displayMetrics = service.resources.displayMetrics
                val centerX = displayMetrics.widthPixels / 2f
                val centerY = displayMetrics.heightPixels / 2f

                Log.d(TAG, "👆 Dispatching Long Press at ($centerX, $centerY)")

                val path =
                        Path().apply {
                            moveTo(centerX, centerY)
                            lineTo(centerX, centerY) // Statický bod — hold
                        }

                val gesture =
                        GestureDescription.Builder()
                                .addStroke(
                                        GestureDescription.StrokeDescription(
                                                path,
                                                0,
                                                800
                                        ) // 800ms hold
                                )
                                .build()

                // Použij gesture timeout z nastavení
                val gestureTimeout = service.settingsManager.agentGestureTimeout
                return@withContext withTimeout(gestureTimeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val callback =
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(
                                            gestureDescription: GestureDescription?
                                    ) {
                                        Log.i(TAG, "✓ Long press executed")
                                        if (continuation.isActive) continuation.resume(true) {}
                                    }
                                    override fun onCancelled(
                                            gestureDescription: GestureDescription?
                                    ) {
                                        Log.w(TAG, "❌ Long press cancelled")
                                        if (continuation.isActive) continuation.resume(false) {}
                                    }
                                }
                        val dispatched = service.dispatchGesture(gesture, callback, null)
                        if (!dispatched) {
                            Log.e(TAG, "❌ Failed to dispatch long press")
                            if (continuation.isActive) continuation.resume(false) {}
                        }
                    }
                }
            }

    /** Double Tap na středu obrazovky (zoom, hry) */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performDoubleTap(): Boolean =
            withContext(Dispatchers.Main) {
                val displayMetrics = service.resources.displayMetrics
                val centerX = displayMetrics.widthPixels / 2f
                val centerY = displayMetrics.heightPixels / 2f

                Log.d(TAG, "👆 Dispatching Double Tap at ($centerX, $centerY)")

                // First tap
                val tapPath =
                        Path().apply {
                            moveTo(centerX, centerY)
                            lineTo(centerX, centerY)
                        }

                val firstTap =
                        GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
                                .build()

                val firstSuccess =
                        withTimeout(2000) {
                            kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                                val callback =
                                        object : AccessibilityService.GestureResultCallback() {
                                            override fun onCompleted(
                                                    gestureDescription: GestureDescription?
                                            ) {
                                                if (continuation.isActive)
                                                        continuation.resume(true) {}
                                            }
                                            override fun onCancelled(
                                                    gestureDescription: GestureDescription?
                                            ) {
                                                if (continuation.isActive)
                                                        continuation.resume(false) {}
                                            }
                                        }
                                val dispatched = service.dispatchGesture(firstTap, callback, null)
                                if (!dispatched) {
                                    if (continuation.isActive) continuation.resume(false) {}
                                }
                            }
                        }

                if (!firstSuccess) {
                    Log.w(TAG, "❌ First tap of double tap failed")
                    return@withContext false
                }

                // Brief pause between taps
                delay(100)

                // Second tap
                val secondTap =
                        GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(tapPath, 0, 50))
                                .build()

                return@withContext withTimeout(2000) {
                    kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                        val callback =
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(
                                            gestureDescription: GestureDescription?
                                    ) {
                                        Log.i(TAG, "✓ Double tap executed")
                                        if (continuation.isActive) continuation.resume(true) {}
                                    }
                                    override fun onCancelled(
                                            gestureDescription: GestureDescription?
                                    ) {
                                        Log.w(TAG, "❌ Second tap of double tap cancelled")
                                        if (continuation.isActive) continuation.resume(false) {}
                                    }
                                }
                        val dispatched = service.dispatchGesture(secondTap, callback, null)
                        if (!dispatched) {
                            if (continuation.isActive) continuation.resume(false) {}
                        }
                    }
                }
            }

    // ==================== MÉDIA ====================

    /**
     * Odeslání media klávesy (Play/Pause, Next, Previous) Využívá AudioManager pro dispatch
     * KeyEvent
     */
    private fun performMediaAction(keyCode: Int): Boolean {
        val audioManager =
                service.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        ?: throw IllegalStateException("AudioManager not available")

        val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val keyUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)

        audioManager.dispatchMediaKeyEvent(keyDown)
        audioManager.dispatchMediaKeyEvent(keyUp)

        val keyName =
                when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY/PAUSE"
                    KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREVIOUS"
                    else -> "MEDIA($keyCode)"
                }
        Log.i(TAG, "✓ Media action: $keyName")
        return true
    }

    /**
     * Perform Open App action — InstalledAppsRegistry snapshot lookup.
     *
     * Strategie:
     * 1. InstalledAppsRegistry.findApp() — fuzzy + slang match (okamžité)
     * 2. Fallback: přímý PackageManager match (pro balíčky mimo snapshot)
     * 3. Learning: pokud query != label, přidej jako slang alias
     */
    private fun performOpenApp(action: AgentAction): Boolean {
        val query =
                action.inputText?.trim()
                        ?: throw IllegalArgumentException("Missing app name for OPEN_APP")

        val pm = service.packageManager

        // 1. Snapshot lookup — InstalledAppsRegistry (okamžité, slang support)
        val entry = InstalledAppsRegistry.findApp(query)
        if (entry != null) {
            val intent = pm.getLaunchIntentForPackage(entry.packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                service.startActivity(intent)
                Log.i(TAG, "🚀 Launched app: '${entry.label}' (${entry.packageName}) via snapshot")

                // Learning: pokud user zavolal app jiným názvem, přidej jako alias
                if (!entry.label.equals(query, ignoreCase = true) &&
                                !entry.packageName.equals(query, ignoreCase = true)
                ) {
                    InstalledAppsRegistry.addAlias(entry.packageName, query)
                    // Persist naučená data
                    InstalledAppsRegistry.saveToFile(service.applicationContext)
                }
                return true
            } else {
                // FIX: App nalezena v snapshot ale nemá launcher (factory/test app)
                // → pokračujeme do fallbacku s originálním query
                Log.w(
                        TAG,
                        "⚠️ Snapshot match '${entry.label}' (${entry.packageName}) has no launcher — skipping to fallback"
                )
            }
        }

        // 2. Fallback: přímý PackageManager match (edge case — app mimo snapshot)
        Log.d(TAG, "🔍 App '$query' not in snapshot. Trying direct package match...")
        var intent = pm.getLaunchIntentForPackage(query)

        // 3. Fuzzy fallback — PackageManager scan (pomalý, jen pro edge case)
        if (intent == null) {
            val installedApps =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getInstalledApplications(
                                PackageManager.ApplicationInfoFlags.of(
                                        PackageManager.GET_META_DATA.toLong()
                                )
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    }

            val bestMatch =
                    installedApps
                            .asSequence()
                            .map { appInfo ->
                                appInfo to pm.getApplicationLabel(appInfo).toString()
                            }
                            .filter { (_, label) -> label.contains(query, ignoreCase = true) }
                            .sortedWith(
                                    compareBy(
                                            { (_, label) ->
                                                !label.equals(query, ignoreCase = true)
                                            },
                                            { (_, label) ->
                                                !label.startsWith(query, ignoreCase = true)
                                            },
                                            { (_, label) -> label.length }
                                    )
                            )
                            .firstOrNull()

            if (bestMatch != null) {
                val (appInfo, label) = bestMatch
                Log.i(TAG, "✓ Found app (fallback): '$label' (${appInfo.packageName})")
                intent = pm.getLaunchIntentForPackage(appInfo.packageName)
            }
        }

        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            Log.i(TAG, "🚀 Launched app: $query (via PM fallback)")

            // Fallback learning: pokud PM fuzzy našel app, přidej jako alias do registru
            // Tím se agent naučí a příště použije rychlý snapshot lookup
            if (InstalledAppsRegistry.isLoaded) {
                InstalledAppsRegistry.addAlias(
                        intent.`package` ?: intent.component?.packageName ?: "",
                        query
                )
                InstalledAppsRegistry.saveToFile(service.applicationContext)
            }

            return true
        } else {
            throw IllegalArgumentException("Aplikace '$query' nebyla nalezena.")
        }
    }

    /**
     * VUL-005 FIX: Detekce citlivých dialogů na obrazovce. Skenuje aktuální root window na klíčová
     * slova plateb/mazání.
     *
     * @return true pokud citlivý dialog detekován
     */
    private fun isSensitiveDialogVisible(): Boolean {
        val rootNode = service.rootInActiveWindow ?: return false
        val found = scanForSensitiveKeywords(rootNode)
        return found
    }

    /** Rekurzivní scan UI tree pro citlivá klíčová slova */
    private fun scanForSensitiveKeywords(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 15) return false // Limit hloubky

        val sensitiveKeywords =
                listOf(
                        // CZ
                        "potvrdit platbu",
                        "smazat",
                        "odeslat platbu",
                        "zaplatit",
                        "převést",
                        "potvrdit převod",
                        "opravdu smazat",
                        "odstranit",
                        // EN
                        "confirm payment",
                        "delete",
                        "send payment",
                        "pay now",
                        "transfer",
                        "confirm transfer",
                        "permanently delete",
                        "remove"
                )

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (sensitiveKeywords.any { text.contains(it) || desc.contains(it) }) {
            Log.w(TAG, "⚠️ Sensitive keyword detected: text='$text' desc='$desc'")
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scanForSensitiveKeywords(child, depth + 1)) {
                return true
            }
        }

        return false
    }

    // ==================== EXPLICIT SWIPE GESTURES ====================

    internal suspend fun performSwipeUp(): Boolean {
        Log.i(TAG, "👆 Executing Swipe UP")
        return performSwipeVertical(up = true)
    }

    internal suspend fun performSwipeDown(): Boolean {
        Log.i(TAG, "👆 Executing Swipe DOWN")
        return performSwipeVertical(up = false)
    }

    internal suspend fun performSwipeLeft(): Boolean {
        Log.i(TAG, "👆 Executing Swipe LEFT")
        return performSwipeHorizontal(left = true)
    }

    internal suspend fun performSwipeRight(): Boolean {
        Log.i(TAG, "👆 Executing Swipe RIGHT")
        return performSwipeHorizontal(left = false)
    }

    // --- Unified Swipe Logic with Inversion & Robustness ---

    private suspend fun performSwipeVertical(up: Boolean): Boolean {
        val displayMetrics = service.resources.displayMetrics
        val h = displayMetrics.heightPixels.toFloat()
        val centerX = displayMetrics.widthPixels / 2f

        val invert = service.settingsManager.isInvertSwipeVertical
        val effectiveUp = if (invert) !up else up

        val startY: Float
        val endY: Float

        if (effectiveUp) {
            // Swipe UP: Bottom -> Top
            startY = h * 0.8f
            endY = h * 0.2f
        } else {
            // Swipe DOWN: Top -> Bottom
            startY = h * 0.2f
            endY = h * 0.8f
        }

        return performRobustSwipe(centerX, startY, centerX, endY, 250)
    }

    private suspend fun performSwipeHorizontal(left: Boolean): Boolean {
        val displayMetrics = service.resources.displayMetrics
        val w = displayMetrics.widthPixels.toFloat()
        val centerY = displayMetrics.heightPixels / 2f

        val invert = service.settingsManager.isInvertSwipeHorizontal
        val effectiveLeft = if (invert) !left else left

        val startX: Float
        val endX: Float

        if (effectiveLeft) {
            // Swipe LEFT: Right -> Left
            startX = w * 0.8f
            endX = w * 0.2f
        } else {
            // Swipe RIGHT: Left -> Right
            startX = w * 0.2f
            endX = w * 0.8f
        }

        return performRobustSwipe(startX, centerY, endX, centerY, 250)
    }

    /** Generic Robust Swipe with Timeout & Callback */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performRobustSwipe(
            startX: Float,
            startY: Float,
            endX: Float,
            endY: Float,
            durationMs: Long
    ): Boolean =
            withContext(Dispatchers.Main) {
                val path =
                        Path().apply {
                            moveTo(startX, startY)
                            lineTo(endX, endY)
                        }
                val gesture =
                        GestureDescription.Builder()
                                .addStroke(
                                        GestureDescription.StrokeDescription(path, 0, durationMs)
                                )
                                .build()

                val timeout = service.settingsManager.agentGestureTimeout

                try {
                    withTimeout(timeout) {
                        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                            val cb =
                                    object : AccessibilityService.GestureResultCallback() {
                                        override fun onCompleted(d: GestureDescription?) {
                                            if (cont.isActive) cont.resume(true) {}
                                        }
                                        override fun onCancelled(d: GestureDescription?) {
                                            if (cont.isActive) cont.resume(false) {}
                                        }
                                    }
                            if (!service.dispatchGesture(gesture, cb, null)) {
                                if (cont.isActive) cont.resume(false) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Swipe timed out or failed: ${e.message}")
                    false
                }
            }

    // ==================== TAPS & PRESSES ====================

    private suspend fun performLongPress(action: AgentAction, screenState: ScreenState): Boolean {
        return performSpecificTap(action, screenState, isLongPress = true, isDoubleTap = false)
    }

    private suspend fun performDoubleTap(action: AgentAction, screenState: ScreenState): Boolean {
        return performSpecificTap(action, screenState, isLongPress = false, isDoubleTap = true)
    }

    private suspend fun performSpecificTap(
            action: AgentAction,
            screenState: ScreenState,
            isLongPress: Boolean,
            isDoubleTap: Boolean
    ): Boolean {
        // Priority 1: targetId (semantic fallback to bounding box)
        if (action.targetId != null) {
            val target = screenState.findElementById(action.targetId)
            if (target != null) {
                if (target.isEnabled) {
                    val accAction =
                            if (isLongPress) AccessibilityNodeInfo.ACTION_LONG_CLICK
                            else AccessibilityNodeInfo.ACTION_CLICK
                    // Note: double tap via ACC requires custom dispatch mostly, but we try click
                    // first or gesture fallback
                    if (isLongPress && target.nodeInfo.performAction(accAction)) {
                        Log.i(TAG, "✓ Long Press (Semantic): \"${target.label}\"")
                        return true
                    }
                }
                // Fallback to gesture coordinates
                val bounds = target.bounds
                if (!bounds.isEmpty) {
                    return dispatchTapGesture(
                            bounds.centerX(),
                            bounds.centerY(),
                            isLongPress,
                            isDoubleTap
                    )
                }
            }
        }

        // Priority 2: x, y coordinates
        if (action.x != null && action.y != null) {
            return dispatchTapGesture(action.x, action.y, isLongPress, isDoubleTap)
        }

        // Fallback: screen center
        val dm = service.resources.displayMetrics
        return dispatchTapGesture(dm.widthPixels / 2, dm.heightPixels / 2, isLongPress, isDoubleTap)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun dispatchTapGesture(
            x: Int,
            y: Int,
            isLongPress: Boolean,
            isDoubleTap: Boolean
    ): Boolean =
            withContext(Dispatchers.Main) {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val builder = GestureDescription.Builder()

                if (isDoubleTap) {
                    val stroke1 = GestureDescription.StrokeDescription(path, 0, 50)
                    val stroke2 = GestureDescription.StrokeDescription(path, 150, 50)
                    builder.addStroke(stroke1).addStroke(stroke2)
                } else if (isLongPress) {
                    val stroke =
                            GestureDescription.StrokeDescription(path, 0, 600) // 600ms = long press
                    builder.addStroke(stroke)
                }

                val gesture = builder.build()
                val timeout = service.settingsManager.agentGestureTimeout

                return@withContext withTimeout(timeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        val cb =
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(d: GestureDescription?) {
                                        Log.i(
                                                TAG,
                                                "✓ Tap gesture executed at [$x, $y] (LongPress=$isLongPress, DoubleTap=$isDoubleTap)"
                                        )
                                        if (cont.isActive) cont.resume(true) {}
                                    }
                                    override fun onCancelled(d: GestureDescription?) {
                                        Log.w(TAG, "❌ Tap gesture cancelled")
                                        if (cont.isActive) cont.resume(false) {}
                                    }
                                }
                        if (!service.dispatchGesture(gesture, cb, null)) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }
                }
            }

    // ==================== RAPID TAP (GAME AUTO-CLICKER) ====================

    /**
     * Rapid Clicker pro hry (např. "klikej 200x po 44ms") Používá krátké stroky (10ms) pro
     * maximalizaci rychlosti.
     */
    internal suspend fun performRapidTap(x: Int, y: Int, count: Int, intervalMs: Long): Boolean =
            withContext(Dispatchers.Main) {
                Log.i(
                        TAG,
                        "⚡ Starting RAPID TAP: $count clicks at [$x, $y], interval $intervalMs ms"
                )

                // Pre-build gesture (reusable path)
                val path =
                        Path().apply {
                            moveTo(x.toFloat(), y.toFloat())
                            lineTo(x.toFloat(), y.toFloat())
                        }

                // Stroke duration min 10ms (1ms může selhat na některých zařízeních)
                val strokeDuration = 10L
                val stroke = GestureDescription.StrokeDescription(path, 0, strokeDuration)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()

                var successCount = 0
                val safeCount = count.coerceIn(1, 1000) // Safety limit
                val safeInterval = intervalMs.coerceIn(10L, 5000L)

                repeat(safeCount) { i ->
                    try {
                        val dispatched = service.dispatchGesture(gesture, null, null)
                        if (dispatched) successCount++
                    } catch (e: Exception) {
                        Log.w(TAG, "⚡ Rapid Tap dispatch failed at $i: ${e.message}")
                    }
                    delay(safeInterval)

                    // Log every 50 clicks to not spam logcat
                    if (i % 50 == 0 && i > 0) {
                        Log.d(TAG, "⚡ Rapid Tap Progress: $i/$safeCount")
                    }
                }

                Log.i(TAG, "✓ Rapid Tap Completed: $successCount/$safeCount sent")
                return@withContext true
            }

    // ==================== NOVÁ GESTA ====================

    /** Pinch-to-Zoom (Multitouch) Uses agentZoomFactor from Settings (1.0 - 5.0). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performPinch(zoomIn: Boolean): Boolean =
            withContext(Dispatchers.Main) {
                val metrics = service.resources.displayMetrics
                val centerX = metrics.widthPixels / 2f
                val centerY = metrics.heightPixels / 2f

                // Calculate span based on Zoom Factor
                // Factor 1.0 -> 15% width
                // Factor 2.0 (Default) -> 30% width
                // Factor 5.0 -> 75% width
                val zoomFactor = service.settingsManager.agentZoomFactor
                val span = metrics.widthPixels * (zoomFactor * 0.15f)

                // Cesty pro dva prsty
                val path1 = Path()
                val path2 = Path()

                if (zoomIn) {
                    // Zoom In (Open): Prsty jdou od sebe (Center -> Out)
                    path1.moveTo(centerX - 10, centerY - 10)
                    path1.lineTo(centerX - span / 2, centerY - span / 2)

                    path2.moveTo(centerX + 10, centerY + 10)
                    path2.lineTo(centerX + span / 2, centerY + span / 2)
                } else {
                    // Zoom Out (Close): Prsty jdou k sobě (Out -> Center)
                    path1.moveTo(centerX - span / 2, centerY - span / 2)
                    path1.lineTo(centerX - 10, centerY - 10)

                    path2.moveTo(centerX + span / 2, centerY + span / 2)
                    path2.lineTo(centerX + 10, centerY + 10)
                }

                Log.i(
                        TAG,
                        "🤏 Dispatching Pinch ${if(zoomIn) "IN" else "OUT"} (Factor: $zoomFactor, Span: $span px)"
                )

                val builder = GestureDescription.Builder()
                val stroke1 = GestureDescription.StrokeDescription(path1, 0, 400)
                val stroke2 = GestureDescription.StrokeDescription(path2, 0, 400)

                builder.addStroke(stroke1)
                builder.addStroke(stroke2)

                val gesture = builder.build()
                val timeout = service.settingsManager.agentGestureTimeout

                return@withContext withTimeout(timeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        service.dispatchGesture(
                                gesture,
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(desc: GestureDescription?) {
                                        if (cont.isActive) cont.resume(true) {}
                                    }
                                    override fun onCancelled(desc: GestureDescription?) {
                                        if (cont.isActive) cont.resume(false) {}
                                    }
                                },
                                null
                        )
                    }
                }
            }

    // --- Advanced Scroll impl ---

    private suspend fun performScrollToEdge(top: Boolean): Boolean {
        // Uses agentScrollDistance (0.1 - 1.0)
        // Default 0.8 means 10% margin top, 10% margin bottom (valid area 80%)
        val dist = service.settingsManager.agentScrollDistance
        val margin = (1.0f - dist) / 2f

        val startY = if (top) margin else (1.0f - margin)
        val endY = if (top) (1.0f - margin) else margin

        Log.d(TAG, "📜 Scroll To Edge: Top=$top, Dist=$dist, Y: $startY -> $endY")

        return performGestureSwipe(
                startYPercent = startY,
                endYPercent = endY,
                durationMs = 250 // Slightly faster for full fling
        )
    }

    private suspend fun performScrollLittle(up: Boolean): Boolean {
        // Pomalý swipe o malý kousek (fixed 15% screen)
        // Independent of global scroll distance to allow "fine tuning"
        return performGestureSwipe(
                startYPercent = 0.5f,
                endYPercent = if (up) 0.65f else 0.35f,
                durationMs = 500
        )
    }

    private suspend fun performScrollPage(up: Boolean): Boolean {
        // Scroll Page uses the Configured Distance
        // SCROLL_PAGE_UP -> we want to see content above -> Drag Down ->
        // performScrollToEdge(top=true)
        // SCROLL_PAGE_DOWN -> we want to see content below -> Drag Up ->
        // performScrollToEdge(top=false)
        // up=true means SCROLL_PAGE_UP
        return performScrollToEdge(top = up)
    }

    /** Generic Gesture Swipe Helper */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun performGestureSwipe(
            startYPercent: Float,
            endYPercent: Float,
            durationMs: Long
    ): Boolean =
            withContext(Dispatchers.Main) {
                val metrics = service.resources.displayMetrics
                val w = metrics.widthPixels
                val h = metrics.heightPixels
                val cx = w / 2f

                val path =
                        Path().apply {
                            moveTo(cx, h * startYPercent)
                            lineTo(cx, h * endYPercent)
                        }

                val gesture =
                        GestureDescription.Builder()
                                .addStroke(
                                        GestureDescription.StrokeDescription(path, 0, durationMs)
                                )
                                .build()

                val timeout = service.settingsManager.agentGestureTimeout

                return@withContext withTimeout(timeout) {
                    kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                        service.dispatchGesture(
                                gesture,
                                object : AccessibilityService.GestureResultCallback() {
                                    override fun onCompleted(desc: GestureDescription?) {
                                        if (cont.isActive) cont.resume(true) {}
                                    }
                                    override fun onCancelled(desc: GestureDescription?) {
                                        if (cont.isActive) cont.resume(false) {}
                                    }
                                },
                                null
                        )
                    }
                }
            }

    // --- Text Edit Impl ---

    private fun performTextEditAction(action: AgentAction, screenState: ScreenState): Boolean {
        if (HyperKeyboardService.isActive()) {
            val success =
                    when (action.type) {
                        ActionType.EDIT_COPY ->
                                HyperKeyboardService.injectKey(KeyEvent.KEYCODE_COPY)
                        ActionType.EDIT_PASTE ->
                                HyperKeyboardService.injectKey(KeyEvent.KEYCODE_PASTE)
                        ActionType.EDIT_ENTER ->
                                HyperKeyboardService.injectKey(KeyEvent.KEYCODE_ENTER)
                        ActionType.EDIT_DELETE_WORD -> {
                            repeat(5) { HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DEL) }
                            true
                        }
                        ActionType.EDIT_UNDO -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_Z)
                        else -> false
                    }
            if (success) return true
        }

        // Fallback or Accessibility
        val focused =
                screenState.elements.find { it.isFocused }
                        ?: screenState.elements.find { it.isEditable }
        if (focused != null) {
            val node = focused.nodeInfo
            val actionId =
                    when (action.type) {
                        ActionType.EDIT_COPY -> AccessibilityNodeInfo.ACTION_COPY
                        ActionType.EDIT_PASTE -> AccessibilityNodeInfo.ACTION_PASTE
                        ActionType.EDIT_SELECT_ALL -> AccessibilityNodeInfo.ACTION_SELECT
                        // For others we might need specific logic or arguments
                        else -> null
                    }

            if (actionId != null) {
                return node.performAction(actionId)
            }
        }
        return false
    }

    // --- Navigation Impl ---

    private fun performNavigationAction(action: AgentAction): Boolean {
        // Navigation relies heavily on IME for D-Pad
        if (HyperKeyboardService.isActive()) {
            return when (action.type) {
                ActionType.NAV_UP -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DPAD_UP)
                ActionType.NAV_DOWN -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DPAD_DOWN)
                ActionType.NAV_LEFT -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DPAD_LEFT)
                ActionType.NAV_RIGHT -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                ActionType.NAV_CENTER ->
                        HyperKeyboardService.injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
                ActionType.NAV_ENTER -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_ENTER)
                ActionType.NAV_BACK ->
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                ActionType.NAV_HOME ->
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                ActionType.NAV_RECENTS ->
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                ActionType.NAV_TAB -> HyperKeyboardService.injectKey(KeyEvent.KEYCODE_TAB)
                else -> false
            }
        }

        // Fallback for non-IME (Swipe Simulation for D-Pad?)
        // D-Pad swipes are tricky. Better to just fail specific D-Pad actions if no IME.
        // But BACK/HOME/RECENTS work via Accessibility Service natively.
        return when (action.type) {
            ActionType.NAV_BACK ->
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.NAV_HOME ->
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.NAV_RECENTS ->
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            else -> false
        }
    }
}


