package com.example.llama.agent.macro

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.llama.agent.core.ActionExecutor
import com.example.llama.agent.core.AppLayoutCache
import com.example.llama.agent.core.ScreenContextMemory
import com.example.llama.agent.core.ScreenParser
import com.example.llama.agent.core.SystemController
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.models.AgentAction
import com.example.llama.agent.models.ScreenState
import com.example.llama.agent.utils.CompatibilityUtils
import kotlinx.coroutines.delay

/**
 * MacroEngine - Jádro pro State-Aware Automation
 *
 * Implementuje:
 * - JSON State Machine (Snapshot-Process-Act)
 * - Wait-For-Element logic (Polling)
 * - Exponential Backoff
 */
class MacroEngine(
        private val screenParserProvider: () -> ScreenParser?,
        private val actionExecutor: ActionExecutor,
        private val systemController: SystemController,
        private val rootNodeProvider: () -> AccessibilityNodeInfo?,
        private val appContext: android.content.Context
) {
    companion object {
        private const val TAG = "MacroEngine"
    }

    /** Spustí makro. */
    suspend fun executeMacro(macro: Macro): Boolean {
        Log.i(TAG, "Starting Macro: ${macro.name} (${macro.steps.size} steps)")

        for ((index, step) in macro.steps.withIndex()) {
            Log.d(TAG, "Step ${index + 1}: $step")

            // Každý krok může selhat, zde to zachytíme
            val success =
                    try {
                        executeStep(step)
                    } catch (e: Exception) {
                        Log.e(TAG, "Step failed with exception", e)
                        false
                    }

            if (!success) {
                Log.e(TAG, "Macro failed at step $index: $step")
                return false
            }
        }

        Log.i(TAG, "Macro completed successfully: ${macro.name}")
        return true
    }

    private suspend fun executeStep(step: MacroAction): Boolean {
        return when (step) {
            is MacroAction.Wait -> waitForElement(step.text, step.id, step.timeoutMs)
            is MacroAction.Click -> performClick(step)
            is MacroAction.Input -> performInput(step)
            is MacroAction.Scroll -> performScroll(step)
            is MacroAction.Global -> performGlobal(step)
            is MacroAction.OpenApp -> performOpenApp(step)
            is MacroAction.OpenUrl -> performOpenUrl(step)
            is MacroAction.EnsurePackage -> ensurePackage(step)
            is MacroAction.SmartClick -> performSmartClick(step)
            is MacroAction.SmartInput -> performSmartInput(step)
            is MacroAction.DirectExecute -> performDirectExecute(step)
        }
    }

    // --- Core Logic: Wait-For-Element with Backoff ---

    private suspend fun waitForElement(text: String?, id: String?, timeoutMs: Long): Boolean {
        Log.d(TAG, "Waiting for element: text='$text', id='$id', to=$timeoutMs")

        var currentBackoff = 100L
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            // 1. Snapshot Current State
            val root = rootNodeProvider()
            if (root != null) {
                // Parse screen to find element
                // Note: Parsing entire screen might be heavy, but it's safe.
                // Optimálně bychom použili findAccessibilityNodeInfosByText přímo na rootu,
                // ale ScreenParser nám dává normalizovaný 'ScreenState'.
                // Pro robustnost použijeme ScreenParser (stejně jako Agent).

                val currentParser = screenParserProvider()
                if (currentParser == null) {
                    Log.e(TAG, "Macro selhalo: ScreenParser je offline (režim OFF/slepý).")
                    return false
                }
                val screenState = currentParser.parseScreen(root)
                if (screenState != null) {
                    val found = findElementInState(screenState, text, id)
                    screenState.recycle() // Clean up!

                    if (found) {
                        Log.d(TAG, "Element found!")
                        return true
                    }
                }
            } else {
                Log.v(TAG, "Root node is null (transient state)")
            }

            // Backoff
            delay(currentBackoff)
            currentBackoff = (currentBackoff * 1.5).toLong().coerceAtMost(1000L)
        }

        Log.w(TAG, "Timeout waiting for element")
        return false
    }

    private fun findElementInState(state: ScreenState, text: String?, id: String?): Boolean {
        return state.elements.any { element ->
            val matchText =
                    text != null &&
                            (element.text.contains(text, ignoreCase = true) ||
                                    element.contentDescription.contains(text, ignoreCase = true))
            val matchId =
                    id != null &&
                            element.nodeInfo.viewIdResourceName?.contains(id, ignoreCase = true) ==
                                    true

            when {
                text != null && id != null -> matchText && matchId
                text != null -> matchText
                id != null -> matchId
                else -> true // Should not happen
            }
        }
    }

    private suspend fun performClick(step: MacroAction.Click): Boolean {
        // 1. Wait for it first (implicit wait)
        if (!waitForElement(step.text, step.id, step.timeoutMs)) return false

        // 2. Refresh screen state to get valid ID for execution
        val root = rootNodeProvider() ?: return false
        val currentParser = screenParserProvider()
        if (currentParser == null) {
            Log.e(TAG, "Macro selhalo: ScreenParser je offline (režim OFF/slepý).")
            return false
        }
        val screenState = currentParser.parseScreen(root) ?: return false

        try {
            // Find ID again in the fresh state
            val targetId =
                    screenState.elements
                            .firstOrNull { element ->
                                val matchText =
                                        step.text != null &&
                                                (element.text.contains(
                                                        step.text,
                                                        ignoreCase = true
                                                ) ||
                                                        element.contentDescription.contains(
                                                                step.text,
                                                                ignoreCase = true
                                                        ))
                                val matchId =
                                        step.id != null &&
                                                element.nodeInfo.viewIdResourceName?.contains(
                                                        step.id,
                                                        ignoreCase = true
                                                ) == true

                                // Prefer clickable elements
                                val match =
                                        when {
                                            step.text != null && step.id != null ->
                                                    matchText && matchId
                                            step.text != null -> matchText
                                            step.id != null -> matchId
                                            else -> false
                                        }
                                match
                            }
                            ?.id

            if (targetId != null) {
                val action =
                        AgentAction(
                                thought = "Macro Click: ${step.text ?: step.id}",
                                type = ActionType.CLICK,
                                targetId = targetId,
                                inputText = step.text
                        )
                return actionExecutor.execute(action, screenState).isSuccess
            } else {
                Log.e(TAG, "Element disappeared before click execution!")
                return false
            }
        } finally {
            screenState.recycle()
        }
    }

    private suspend fun performInput(step: MacroAction.Input): Boolean {
        // Input assumes focused element or just uses ActionExecutor's input logic
        val action =
                AgentAction(
                        thought = "Macro Input: ${step.text}",
                        type = ActionType.INPUT_TEXT,
                        inputText = step.text
                )
        // ScreenState is needed for execute, passing generic state if input doesn't need target
        // But ActionExecutor might need target for INPUT_TEXT if not global.
        // Assuming global input injection or focused element.
        val root = rootNodeProvider() ?: return false
        val currentParser = screenParserProvider()
        if (currentParser == null) {
            Log.e(TAG, "Macro selhalo: ScreenParser je offline (režim OFF/slepý).")
            return false
        }
        val screenState = currentParser.parseScreen(root) ?: return false
        try {
            return actionExecutor.execute(action, screenState).isSuccess
        } finally {
            screenState.recycle()
        }
    }

    private suspend fun performScroll(step: MacroAction.Scroll): Boolean {
        val action = AgentAction(thought = "Macro Scroll: ${step.direction}", type = step.direction)
        val root = rootNodeProvider() ?: return false
        val currentParser = screenParserProvider()
        if (currentParser == null) {
            Log.e(TAG, "Macro selhalo: ScreenParser je offline (režim OFF/slepý).")
            return false
        }
        val screenState = currentParser.parseScreen(root) ?: return false
        try {
            return actionExecutor.execute(action, screenState).isSuccess
        } finally {
            screenState.recycle()
        }
    }

    private fun performGlobal(step: MacroAction.Global): Boolean {
        return actionExecutor.performGlobalAction(step.action)
    }

    private suspend fun performOpenApp(step: MacroAction.OpenApp): Boolean {
        return systemController.openApp(step.packageName)
    }

    private suspend fun performOpenUrl(step: MacroAction.OpenUrl): Boolean {
        return try {
            systemController.openUrl(step.url)
            Log.i(TAG, "✓ Macro OpenUrl: ${step.url}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Macro OpenUrl failed: ${step.url}", e)
            false
        }
    }

    private fun ensurePackage(step: MacroAction.EnsurePackage): Boolean {
        val root = rootNodeProvider() ?: return false
        val pkg = root.packageName?.toString()
        CompatibilityUtils.recycleNode(root)
        return pkg == step.packageName
    }

    // ==================== SMART CLICK (Autonomní hledání tlačítka) ====================

    /**
     * Autonomně vyhledá element na živé obrazovce dle intent labels a klikne na něj.
     *
     * Polling loop s exponential backoff:
     * 1. Parse screen
     * 2. Hledej element dle labels (text NEBO contentDescription)
     * 3. Pokud nalezen → click
     * 4. Pokud nenalezen → čekej + retry (scroll down pokud 2x miss)
     *
     * Příklad: SmartClick("lajkni", ["like","líbí","👍","❤"]) → najde Like tlačítko v
     * YouTube/TikTok/FB
     */
    private suspend fun performSmartClick(step: MacroAction.SmartClick): Boolean {
        Log.i(TAG, "🎯 SmartClick: intent='${step.intent}' labels=${step.labels}")

        // File-based trace pro diagnostiku (Samsung logcat omezení)
        val traceLines = StringBuilder()
        traceLines.appendLine("=== SmartClick Trace ===")
        traceLines.appendLine("Intent: ${step.intent}")
        traceLines.appendLine("Labels: ${step.labels}")
        traceLines.appendLine("Timeout: ${step.timeoutMs}ms")
        traceLines.appendLine(
                "Timestamp: ${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())}"
        )

        fun writeSmartClickTrace() {
            try {
                val f = java.io.File(appContext.filesDir, "agent_smartclick_trace.txt")
                f.writeText(traceLines.toString())
            } catch (_: Exception) {}
        }

        // === VRSTVA -1: Memory Recall (naučená pozice — 0ms, 0 IO) ===
        val cachedPkg = getCurrentPackageName()
        traceLines.appendLine("currentPkg: $cachedPkg")
        if (cachedPkg != null) {
            val remembered = ScreenContextMemory.recallPosition(cachedPkg, step.intent)
            if (remembered != null) {
                Log.i(TAG, "🧠 SmartClick MEMORY HIT: '${step.intent}' @ $remembered")
                traceLines.appendLine("MEMORY HIT: '${step.intent}' bounds=$remembered")
                writeSmartClickTrace()
                val success =
                        actionExecutor.performGestureClick(
                                remembered.centerX(),
                                remembered.centerY()
                        )
                if (success) return true
                // Memory hit selhalo → element se přesunul → decay
                ScreenContextMemory.recordFailure(cachedPkg, step.intent)
                traceLines.appendLine("MEMORY HIT FAILED → decay, continuing scan")
            }

            // === CACHE CHECK: instant lookup (<1ms) ===
            val cached = AppLayoutCache.findElement(cachedPkg, step.labels, clickableOnly = true)
            if (cached != null) {
                Log.i(TAG, "⚡ SmartClick CACHE HIT: '${cached.label}' @ ${cached.bounds}")
                traceLines.appendLine("CACHE HIT: '${cached.label}' bounds=${cached.bounds}")
                writeSmartClickTrace()
                val centerX = cached.bounds.centerX()
                val centerY = cached.bounds.centerY()
                val success = actionExecutor.performGestureClick(centerX, centerY)
                if (success) {
                    // Cross-learning: krátkodobý cache HIT trénuje dlouhodobou paměť
                    ScreenContextMemory.recordSuccess(
                            pkg = cachedPkg,
                            label = cachedPkg,
                            intent = step.intent,
                            bounds = cached.bounds,
                            screenW = getScreenWidth(),
                            screenH = getScreenHeight(),
                            text = cached.text.takeIf { it.isNotBlank() },
                            resId = cached.resourceId.takeIf { it.isNotBlank() }
                    )
                }
                return success
            }
            traceLines.appendLine("CACHE MISS")
        }

        // === CACHE MISS: screen parse + cache update ===
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var delayMs = 300L // Exponential backoff: 300, 600, 1200...

        while (System.currentTimeMillis() - startTime < step.timeoutMs) {
            attempt++
            val root = rootNodeProvider()
            if (root == null) {
                traceLines.appendLine("[attempt $attempt] rootNode=NULL")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(2000L)
                continue
            }

            traceLines.appendLine(
                    "[attempt $attempt] rootNode OK: pkg=${root.packageName}, children=${root.childCount}"
            )

            val currentParser = screenParserProvider()
            if (currentParser == null) {
                Log.e(TAG, "SmartClick selhal: ScreenParser je offline.")
                traceLines.appendLine("FAILED: ScreenParser offline")
                writeSmartClickTrace()
                return false
            }

            // === VRSTVA 0: Systémové API findAccessibilityNodeInfosByText ===
            // Rychlejší než parseScreen — systém prohledá celý tree interně
            for (label in step.labels) {
                val sysNodes = currentParser.findBySystemApi(root, label)
                val clickableNode = sysNodes.firstOrNull { it.isClickable }
                if (clickableNode != null) {
                    val rect = android.graphics.Rect()
                    clickableNode.getBoundsInScreen(rect)
                    Log.i(TAG, "⚡ SmartClick SYS_API HIT: '$label' @ $rect")
                    traceLines.appendLine("SYS_API HIT: '$label' bounds=$rect")
                    writeSmartClickTrace()
                    val success = actionExecutor.performGestureClick(rect.centerX(), rect.centerY())
                    if (success) {
                        // LEARN: Ulož pozici pro příště
                        val pkg = root.packageName?.toString() ?: ""
                        ScreenContextMemory.recordSuccess(
                                pkg = pkg,
                                label = pkg,
                                intent = step.intent,
                                bounds = rect,
                                screenW = getScreenWidth(),
                                screenH = getScreenHeight(),
                                text = label,
                                resId = clickableNode.viewIdResourceName
                        )
                    }
                    return success
                }
            }

            // Systémové API nenašlo → fallback na plný parseScreen
            val screenState = currentParser.parseScreen(root)
            if (screenState == null) {
                traceLines.appendLine("[attempt $attempt] parseScreen=NULL")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(2000L)
                continue
            }

            traceLines.appendLine(
                    "[attempt $attempt] parseScreen OK: ${screenState.elements.size} elements, pkg=${screenState.packageName}"
            )

            try {
                // Cache update po každém úspěšném parse
                AppLayoutCache.updateCache(screenState.packageName, screenState)
                // Cross-learning: snap → bulk-learn všech clickable elementů
                bulkLearnFromSnapshot(screenState.packageName)

                // Hledáme element dle labels (clickable NEBO s contentDescription)
                val target = findSmartElement(screenState, step.labels, clickableOnly = true)

                if (target != null) {
                    Log.i(
                            TAG,
                            "🎯 SmartClick FOUND: '${target.label}' (ID: ${target.id}) after $attempt attempts"
                    )
                    traceLines.appendLine(
                            "FOUND: '${target.label}' id=${target.id} bounds=${target.bounds} clickable=${target.isClickable}"
                    )
                    writeSmartClickTrace()
                    val action =
                            AgentAction(
                                    thought = "SmartClick: ${step.intent} → '${target.label}'",
                                    type = ActionType.CLICK,
                                    targetId = target.id,
                                    inputText = null
                            )
                    val result = actionExecutor.execute(action, screenState).isSuccess
                    if (result) {
                        // LEARN: Ulož pozici pro příště (KV Cache princip)
                        ScreenContextMemory.recordSuccess(
                                pkg = screenState.packageName,
                                label = screenState.packageName,
                                intent = step.intent,
                                bounds = target.bounds,
                                screenW = getScreenWidth(),
                                screenH = getScreenHeight(),
                                text = target.text.takeIf { it.isNotBlank() },
                                resId = target.resourceId.takeIf { it.isNotBlank() }
                        )
                    }
                    return result
                }

                // Dump top elements pro diagnostiku
                val clickableElements =
                        screenState.elements.filter {
                            it.isClickable || it.contentDescription.isNotBlank()
                        }
                traceLines.appendLine(
                        "[attempt $attempt] MISS — clickable/desc elements: ${clickableElements.size}"
                )
                clickableElements.take(8).forEachIndexed { i, el ->
                    traceLines.appendLine(
                            "  [$i] text='${el.text.take(25)}' desc='${el.contentDescription.take(25)}' resId='${el.resourceId.takeLast(25)}'"
                    )
                }

                // Pokud po 2 pokusech není nalezen, zkus scroll down (element může být níže)
                if (attempt == 2) {
                    Log.d(TAG, "🎯 SmartClick: element not visible, trying scroll down")
                    traceLines.appendLine("[attempt $attempt] auto-scroll down")
                    val scrollAction =
                            AgentAction(
                                    thought = "Auto-scroll pro SmartClick",
                                    type = ActionType.SCROLL_DOWN
                            )
                    actionExecutor.execute(scrollAction, screenState)
                }
            } finally {
                screenState.recycle()
            }

            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(2000L)
        }

        Log.e(TAG, "🎯 SmartClick FAILED: '${step.intent}' not found after $attempt attempts")
        traceLines.appendLine(
                "FAILED: '${step.intent}' not found after $attempt attempts (${System.currentTimeMillis() - startTime}ms)"
        )
        writeSmartClickTrace()
        return false
    }

    // ==================== SMART INPUT (Autonomní hledání textového pole) ====================

    /**
     * Autonomně vyhledá textové pole na obrazovce a zadá text.
     *
     * 1. Hledá editable field dle fieldLabels (hint text, contentDescription)
     * 2. Fallback: jakékoliv editable pole na obrazovce
     * 3. Zadá text přes ActionExecutor (IME inject)
     *
     * Příklad: SmartInput("napiš zprávu", "ahoj", ["zpráva","message"])
     */
    private suspend fun performSmartInput(step: MacroAction.SmartInput): Boolean {
        Log.i(
                TAG,
                "✍️ SmartInput: intent='${step.intent}' text='${step.text}' fieldLabels=${step.fieldLabels}"
        )

        // === CACHE CHECK: instant editable field lookup ===
        val cachedPkg = getCurrentPackageName()
        if (cachedPkg != null) {
            val cached = AppLayoutCache.findEditableField(cachedPkg, step.fieldLabels)
            if (cached != null) {
                Log.i(TAG, "⚡ SmartInput CACHE HIT: editable '${cached.label}' @ ${cached.bounds}")
                // Klikni na cached bounds pro fokus
                val centerX = cached.bounds.centerX()
                val centerY = cached.bounds.centerY()
                actionExecutor.performGestureClick(centerX, centerY)
                delay(200L) // Počkej na fokus
                // Zadej text přes IME
                val inputAction =
                        AgentAction(
                                thought = "SmartInput (cache): ${step.intent}",
                                type = ActionType.INPUT_TEXT,
                                inputText = step.text
                        )
                return actionExecutor.execute(inputAction, ScreenState.empty()).isSuccess
            }
        }

        // === CACHE MISS: screen parse + cache update ===
        val startTime = System.currentTimeMillis()
        var attempt = 0
        var delayMs = 300L

        while (System.currentTimeMillis() - startTime < step.timeoutMs) {
            attempt++
            val root = rootNodeProvider()
            if (root == null) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(2000L)
                continue
            }

            val currentParser = screenParserProvider()
            if (currentParser == null) {
                Log.e(TAG, "SmartInput selhal: ScreenParser je offline.")
                return false
            }
            val screenState = currentParser.parseScreen(root)
            if (screenState == null) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(2000L)
                continue
            }

            try {
                // Cache update po každém úspěšném parse
                AppLayoutCache.updateCache(screenState.packageName, screenState)
                // Cross-learning: snap → bulk-learn
                bulkLearnFromSnapshot(screenState.packageName)

                // 1. Hledej editable field dle fieldLabels
                var targetField =
                        screenState.elements.find { el ->
                            el.isEditable &&
                                    step.fieldLabels.any { label ->
                                        el.text.contains(label, ignoreCase = true) ||
                                                el.contentDescription.contains(
                                                        label,
                                                        ignoreCase = true
                                                ) ||
                                                (el.nodeInfo
                                                        .hintText
                                                        ?.toString()
                                                        ?.contains(label, ignoreCase = true) ==
                                                        true)
                                    }
                        }

                // 2. Fallback: jakékoliv editable pole
                if (targetField == null) {
                    targetField = screenState.elements.find { it.isEditable }
                }

                if (targetField != null) {
                    Log.i(
                            TAG,
                            "✍️ SmartInput FOUND field: '${targetField.label}' (ID: ${targetField.id}) after $attempt attempts"
                    )

                    // Klikni na pole pro fokus
                    val focusAction =
                            AgentAction(
                                    thought = "Fokus na textové pole",
                                    type = ActionType.CLICK,
                                    targetId = targetField.id
                            )
                    actionExecutor.execute(focusAction, screenState)
                    delay(200L) // Počkej na fokus

                    // Zadej text
                    val inputAction =
                            AgentAction(
                                    thought = "SmartInput: ${step.intent}",
                                    type = ActionType.INPUT_TEXT,
                                    targetId = targetField.id,
                                    inputText = step.text
                            )
                    return actionExecutor.execute(inputAction, screenState).isSuccess
                }

                // Pokud po 2 pokusech není pole, zkus kliknout na label (někdy je to tlačítko
                // "Napište zprávu")
                if (attempt == 2) {
                    val clickTarget =
                            findSmartElement(screenState, step.fieldLabels, clickableOnly = false)
                    if (clickTarget != null) {
                        Log.d(
                                TAG,
                                "✍️ SmartInput: clicking label '${clickTarget.label}' to reveal input"
                        )
                        val clickAction =
                                AgentAction(
                                        thought =
                                                "Klik na '${clickTarget.label}' pro otevření vstupu",
                                        type = ActionType.CLICK,
                                        targetId = clickTarget.id
                                )
                        actionExecutor.execute(clickAction, screenState)
                    }
                }
            } finally {
                screenState.recycle()
            }

            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(2000L)
        }

        Log.e(
                TAG,
                "✍️ SmartInput FAILED: no suitable field found for '${step.intent}' after $attempt attempts"
        )
        return false
    }

    // ==================== DIRECT EXECUTE ====================

    /**
     * Přímé provedení AgentAction přes ActionExecutor. Pro akce které nepotřebují screen context:
     * volume, brightness, swipe, system toggles.
     */
    private suspend fun performDirectExecute(step: MacroAction.DirectExecute): Boolean {
        Log.i(TAG, "⚡ DirectExecute: ${step.action.type}")
        return try {
            actionExecutor.execute(step.action, ScreenState.empty()).isSuccess
        } catch (e: Exception) {
            Log.e(TAG, "DirectExecute failed: ${step.action.type}", e)
            false
        }
    }

    // ==================== HELPER: Smart Element Finder ====================

    /**
     * MULTI-PASS SMART ELEMENT FINDER s Parent Chain Traversal Zamezuje chybě "first catch" díky
     * izolaci přesného hledání od hrubého hledání (např. resourceId).
     */
    private fun findSmartElement(
            screenState: ScreenState,
            labels: List<String>,
            clickableOnly: Boolean
    ): ScreenState.UIElement? {
        Log.d(
                TAG,
                "🔍 SmartElement search: labels=$labels clickableOnly=$clickableOnly elements=${screenState.elements.size}"
        )

        val eligible =
                if (clickableOnly) {
                    // Optimalizace: Element nemusí být nutně clickable, pokud hledáme přes parenta.
                    // Nicméně přednost mají klikatelné prvky.
                    screenState.elements
                } else {
                    screenState.elements
                }

        // === VRSTVA 1: PŘESNÁ SHODA (Exact) Text / ContentDescription ===
        for (label in labels) {
            val match =
                    eligible.find { el ->
                        el.text.equals(label, ignoreCase = true) ||
                                el.contentDescription.equals(label, ignoreCase = true)
                    }
            if (match != null) {
                if (!clickableOnly || match.isClickable) {
                    Log.i(
                            TAG,
                            "✅ L1 match (exact text/desc): '${match.label}' clickable=${match.isClickable}"
                    )
                    return match
                }
                val clickableParent = findClickableParent(match)
                if (clickableParent != null) {
                    Log.i(
                            TAG,
                            "✅ L1+Parent: '${match.label}' → parent '${clickableParent.label}' clickable=true"
                    )
                    return clickableParent
                }
                Log.i(
                        TAG,
                        "⚠️ L1 match non-clickable (gesture fallback): '${match.label}' bounds=${match.bounds}"
                )
                return match
            }
        }

        // === VRSTVA 2: ČÁSTEČNÁ SHODA (Contains) Text / ContentDescription ===
        for (label in labels) {
            val match =
                    eligible.find { el ->
                        el.text.contains(label, ignoreCase = true) ||
                                el.contentDescription.contains(label, ignoreCase = true)
                    }
            if (match != null) {
                if (!clickableOnly || match.isClickable) {
                    Log.i(
                            TAG,
                            "✅ L2 match (contains text/desc): '${match.label}' clickable=${match.isClickable}"
                    )
                    return match
                }
                val clickableParent = findClickableParent(match)
                if (clickableParent != null) return clickableParent
                return match
            }
        }

        // === VRSTVA 3: HintText ===
        for (label in labels) {
            val match = eligible.find { el -> el.hintText.contains(label, ignoreCase = true) }
            if (match != null) {
                Log.i(TAG, "✅ L3 match (hint): '${match.hintText}' editable=${match.isEditable}")
                return match
            }
        }

        // === VRSTVA 4: ResourceId (Nejnižší priorita, brání falším shodám u parent kontejnerů) ===
        for (label in labels) {
            val match =
                    eligible.find { el ->
                        el.resourceId.isNotEmpty() &&
                                el.resourceId.contains(label, ignoreCase = true)
                    }
            if (match != null) {
                if (!clickableOnly || match.isClickable) {
                    Log.i(
                            TAG,
                            "✅ L4 match (resourceId): '${match.resourceId}' label='${match.label}'"
                    )
                    return match
                }
                val clickableParent = findClickableParent(match)
                if (clickableParent != null) return clickableParent
                return match
            }
        }

        // === VRSTVA 5: AllSearchableText (ultra-wide search) ===
        for (label in labels) {
            val lowerLabel = label.lowercase()
            val match = eligible.find { el -> el.allSearchableText.contains(lowerLabel) }
            if (match != null) {
                if (!clickableOnly || match.isClickable) return match
                val clickableParent = findClickableParent(match)
                if (clickableParent != null) return clickableParent
                return match // Gesture fallback
            }
        }

        // === VRSTVA 6: Position-Based Clickable Matching (Voice Access styl) ===
        // Pro obfuskované apps (TikTok, Instagram) kde elementy nemají text
        val posHint = getPositionHints(labels)
        if (posHint != null) {
            val screenW = screenState.elements.maxOfOrNull { it.bounds.right }?.toFloat() ?: 1080f
            val screenH = screenState.elements.maxOfOrNull { it.bounds.bottom }?.toFloat() ?: 2400f

            val candidates =
                    screenState
                            .elements
                            .filter {
                                it.isClickable && it.bounds.width() > 0 && it.bounds.height() > 0
                            }
                            .filter { el ->
                                val cx = el.bounds.centerX().toFloat() / screenW
                                val cy = el.bounds.centerY().toFloat() / screenH
                                cx in posHint.xRange && cy in posHint.yRange
                            }
                            .sortedBy { el ->
                                val cx = el.bounds.centerX().toFloat() / screenW
                                val cy = el.bounds.centerY().toFloat() / screenH
                                Math.hypot(
                                        (cx - posHint.idealX).toDouble(),
                                        (cy - posHint.idealY).toDouble()
                                )
                            }

            val best = candidates.firstOrNull()
            if (best != null) {
                Log.i(
                        TAG,
                        "✅ L6 match (position): zone=${posHint.zone} bounds=${best.bounds} type=${best.type}"
                )
                return best
            }
        }

        // === MISS: Diagnostický dump ===
        dumpScreenState(screenState, labels)
        return null
    }

    /**
     * Parent Chain Traversal: Najde nejbližšího clickable předka.
     *
     * KRITICKÉ pro Compose/Flutter: ImageView(contentDesc="Like", clickable=FALSE) └── Parent:
     * FrameLayout(clickable=TRUE) ← chceme TENTO
     *
     * Traverzuje max 5 úrovní nahoru. Vrátí UIElement s bounds rodiče pro kliknutí.
     */
    private fun findClickableParent(element: ScreenState.UIElement): ScreenState.UIElement? {
        var currentNode: android.view.accessibility.AccessibilityNodeInfo? = element.nodeInfo
        var depth = 0
        val maxParentDepth = 5

        while (depth < maxParentDepth) {
            val parent =
                    try {
                        currentNode?.parent
                    } catch (_: Exception) {
                        null
                    } ?: break
            depth++

            if (parent.isClickable) {
                // Nalezen clickable parent — vytvoř UIElement s jeho bounds
                val parentBounds = android.graphics.Rect()
                parent.getBoundsInScreen(parentBounds)
                Log.d(
                        TAG,
                        "🔗 Parent chain: depth=$depth class=${parent.className} bounds=$parentBounds clickable=true"
                )

                return ScreenState.UIElement(
                        id = element.id,
                        type = parent.className?.toString()?.substringAfterLast('.') ?: "View",
                        className = parent.className?.toString() ?: "",
                        packageName = parent.packageName?.toString() ?: "",
                        resourceId = parent.viewIdResourceName ?: element.resourceId,
                        xpath = "", // Parent fallback — XPath N/A
                        text = element.text,
                        contentDescription = element.contentDescription,
                        hintText = "",
                        errorText = "",
                        tooltipText = "",
                        stateDescription = "",
                        depth = element.depth,
                        indexInParent = 0,
                        childCount = parent.childCount,
                        parentId = null,
                        bounds = parentBounds,
                        isEnabled = parent.isEnabled,
                        isSelected = parent.isSelected,
                        isChecked = false,
                        isCheckable = parent.isCheckable,
                        isFocused = parent.isFocused,
                        isVisibleToUser = parent.isVisibleToUser,
                        isDismissable = parent.isDismissable,
                        isContentInvalid = false,
                        isClickable = true,
                        isLongClickable = parent.isLongClickable,
                        isEditable = false,
                        isScrollable = false,
                        inputType = null,
                        supportedActions = emptyList(),
                        rangeCurrent = null,
                        rangeMin = null,
                        rangeMax = null,
                        isPassword = false,
                        isSensitive = false,
                        metadata = emptyMap(),
                        nodeInfo = parent // Parent node pro akci
                )
            }

            // Recyklovat intermediate parent by bylo nebezpečné — systém ho vlastní
            currentNode = parent
        }

        Log.d(TAG, "⚠️ No clickable parent found after $depth levels")
        return null
    }

    /**
     * Diagnostický dump: Loguje co engine vidí na obrazovce. Voláno při SmartClick MISS — pomáhá
     * identifikovat proč element nebyl nalezen.
     */
    private fun dumpScreenState(screenState: ScreenState, searchedLabels: List<String>) {
        Log.w(TAG, "📋 ====== SCREEN DUMP (SmartClick MISS) ======")
        Log.w(TAG, "📋 Package: ${screenState.packageName}")
        Log.w(TAG, "📋 Total elements: ${screenState.elements.size}")
        Log.w(TAG, "📋 Searched labels: $searchedLabels")
        Log.w(TAG, "📋 --- Clickable elements ---")

        val clickableElements = screenState.elements.filter { it.isClickable || it.isLongClickable }
        clickableElements.take(15).forEachIndexed { i, el ->
            Log.w(
                    TAG,
                    "📋 [$i] type=${el.type} text='${el.text.take(30)}' desc='${el.contentDescription.take(30)}' resId='${el.resourceId.takeLast(30)}' bounds=${el.bounds}"
            )
        }

        val withDesc = screenState.elements.filter { it.contentDescription.isNotBlank() }
        if (withDesc.isNotEmpty()) {
            Log.w(TAG, "📋 --- Elements with contentDescription ---")
            withDesc.take(10).forEachIndexed { i, el ->
                Log.w(
                        TAG,
                        "📋 [D$i] desc='${el.contentDescription.take(40)}' clickable=${el.isClickable} type=${el.type}"
                )
            }
        }

        Log.w(TAG, "📋 ====== END SCREEN DUMP ======")
    }

    // ==================== POSITION HINTS (Voice Access styl) ====================

    /**
     * Position hint pro VRSTVU 5 — typická pozice elementu na obrazovce. Založeno na analýze
     * layoutů sociálních sítí (TikTok, Instagram, YouTube, FB).
     */
    data class PositionHint(
            val zone: String,
            val xRange: ClosedFloatingPointRange<Float>,
            val yRange: ClosedFloatingPointRange<Float>,
            val idealX: Float,
            val idealY: Float
    )

    /**
     * Mapuje intent labels na typickou pozici na obrazovce. Voice Access styl — i bez textu víme
     * KDE tlačítko typicky je.
     */
    private fun getPositionHints(labels: List<String>): PositionHint? {
        val norm = labels.map { it.lowercase() }
        return when {
            // Like/Heart — pravá strana, střed (v moderních apps je lajk níže kvůli profilu)
            norm.any { it in listOf("like", "líbí", "❤", "👍", "lajk", "heart", "srdce") } ->
                    PositionHint("right-like", 0.75f..1.0f, 0.4f..0.85f, 0.92f, 0.65f)
            // Comment — pravá strana, pod like
            norm.any { it in listOf("comment", "komentář", "komentar", "💬", "koment") } ->
                    PositionHint("right-comment", 0.75f..1.0f, 0.5f..0.9f, 0.92f, 0.75f)
            // Share — pravá strana, pod comment
            norm.any { it in listOf("share", "sdílet", "sdilej", "🔗", "poslat") } ->
                    PositionHint("right-share", 0.75f..1.0f, 0.6f..0.95f, 0.92f, 0.85f)
            // Search — horní část
            norm.any { it in listOf("search", "hledat", "vyhledávání", "🔍", "hledej") } ->
                    PositionHint("top-search", 0.1f..1.0f, 0.0f..0.15f, 0.5f, 0.05f)
            // Profile/Menu — pravý horní roh
            norm.any { it in listOf("profile", "profil", "menu", "☰", "hamburger") } ->
                    PositionHint("top-right", 0.8f..1.0f, 0.0f..0.1f, 0.95f, 0.03f)
            // Play/Pause — střed obrazovky
            norm.any { it in listOf("play", "přehrát", "pause", "pauza", "▶", "⏸") } ->
                    PositionHint("center", 0.3f..0.7f, 0.3f..0.7f, 0.5f, 0.5f)
            // Back — levý horní roh
            norm.any { it in listOf("back", "zpět", "←", "arrow_back") } ->
                    PositionHint("top-left", 0.0f..0.2f, 0.0f..0.1f, 0.05f, 0.03f)
            else -> null
        }
    }

    // ==================== SCREEN DIMENSIONS ====================

    /** Získá šířku obrazovky z resources */
    private fun getScreenWidth(): Int {
        return try {
            appContext.resources.displayMetrics.widthPixels
        } catch (_: Exception) {
            1080
        }
    }

    /** Získá výšku obrazovky z resources */
    private fun getScreenHeight(): Int {
        return try {
            appContext.resources.displayMetrics.heightPixels
        } catch (_: Exception) {
            2400
        }
    }

    // ==================== CROSS-LEARNING: AppLayoutCache → ScreenContextMemory
    // ====================

    /** Throttle: bulk-learn max 1× za 3s per package (prevence spamu) */
    private val lastBulkLearnTime = mutableMapOf<String, Long>()
    private val BULK_LEARN_THROTTLE_MS = 3000L

    /**
     * Automaticky naučí ScreenContextMemory z AppLayoutCache snapshotu. Po každém parseScreen +
     * updateCache se zavolá tato metoda.
     *
     * Učí: clickable elementy s text/desc/resourceId → jejich pozice. Intent = normalizovaný text
     * elementu (lowercase, max 30 znaků). Max 10 elementů per snap (prevence zahlcení paměti).
     * Throttle: max 1× za 3s per package.
     */
    private fun bulkLearnFromSnapshot(packageName: String) {
        // Throttle: max 1× za 3s per package
        val now = System.currentTimeMillis()
        val lastTime = lastBulkLearnTime[packageName] ?: 0L
        if (now - lastTime < BULK_LEARN_THROTTLE_MS) return
        lastBulkLearnTime[packageName] = now

        val elements = AppLayoutCache.extractLearnableElements(packageName)
        if (elements.isEmpty()) return

        val screenW = getScreenWidth()
        val screenH = getScreenHeight()
        var learnedCount = 0

        for (el in elements.take(10)) {
            // Intent = nejlepší identifikátor elementu
            val intent =
                    when {
                        el.contentDescription.isNotBlank() ->
                                el.contentDescription.lowercase().take(30)
                        el.text.isNotBlank() -> el.text.lowercase().take(30)
                        el.resourceId.isNotBlank() -> el.resourceId.substringAfterLast("/").take(30)
                        else -> continue
                    }

            ScreenContextMemory.recordSuccess(
                    pkg = packageName,
                    label = packageName,
                    intent = intent,
                    bounds = el.bounds,
                    screenW = screenW,
                    screenH = screenH,
                    text = el.text.takeIf { it.isNotBlank() },
                    resId = el.resourceId.takeIf { it.isNotBlank() }
            )
            learnedCount++
        }

        if (learnedCount > 0) {
            Log.d(TAG, "🧠 Bulk-learn: $packageName → $learnedCount elements learned")
        }
    }

    // ==================== HELPER: Package Name ====================

    /**
     * Získá aktuální package name z rootNodeProvider. Používá se pro cache lookup (která appka je
     * na popředí).
     */
    private fun getCurrentPackageName(): String? {
        val root = rootNodeProvider() ?: return null
        val pkg = root.packageName?.toString()
        com.example.llama.agent.utils.CompatibilityUtils.recycleNode(root)
        return pkg
    }
}
