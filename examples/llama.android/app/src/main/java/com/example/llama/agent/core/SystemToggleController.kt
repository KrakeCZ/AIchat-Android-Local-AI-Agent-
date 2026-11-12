package com.example.llama.agent.core

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.service.AiAgentAccessibilityService
import kotlinx.coroutines.*

/**
 * SystemToggleController — Automation for System Settings (Non-Root)
 *
 * Řeší problém "Android Limitation": Aplikace nemohou měnit NFC/Wifi/Airplane přímo.
 * Tento controller automatizuje interakci s UI nastavení:
 * 1. Agent otevře Panel/Nastavení.
 * 2. Controller detekuje okno.
 * 3. Najde přepínač (Switch) a klikne na něj.
 * 4. (Volitelně) Vrátí se zpět.
 */
class SystemToggleController(private val service: AiAgentAccessibilityService) {

    companion object {
        private const val TAG = "SystemToggleController"
        // TIMEOUT_MS přesunuto do settingsManager.agentToggleTimeout
    }

    private var pendingAction: ActionType? = null
    private var targetState: Boolean = true // true = zapnout, false = vypnout
    private var actionTimestamp: Long = 0
    private val scope = CoroutineScope(Dispatchers.Default)

    /**
     * Zavolá ActionExecutor před otevřením nastavení.
     * Říká controlleru: "Čekej na okno nastavení a přepni X".
     */
    fun prepareForToggle(actionType: ActionType, enable: Boolean) {
        pendingAction = actionType
        targetState = enable
        actionTimestamp = System.currentTimeMillis()
        Log.i(TAG, "🟢 Waiting for Settings UI/Panel to toggle $actionType -> $enable")
        
        // Auto-reset po timeoutu
        scope.launch {
            val toggleTimeout = service.settingsManager.agentToggleTimeout
            delay(toggleTimeout)
            if (pendingAction == actionType && System.currentTimeMillis() - actionTimestamp >= toggleTimeout) {
                Log.w(TAG, "⏰ Toggle timeout - clearing pending action")
                pendingAction = null
            }
        }
    }

    /**
     * Rychlý guard — volat PŘED onWindowChanged() v onAccessibilityEvent.
     * Zabrání zbytečnému IPC rootInActiveWindow + rekurzivnímu findSwitch()
     * při každém window eventu když žádný toggle nečeká.
     */
    fun hasPendingAction(): Boolean = pendingAction != null

    /**
     * Voláno z onAccessibilityEvent při změně okna.
     */
    fun onWindowChanged(rootNode: AccessibilityNodeInfo?) {
        val action = pendingAction ?: return
        Log.d(TAG, "🔍 Processing pending toggle: $action")
        if (rootNode == null) return

        // Check timeout
        val toggleTimeout = service.settingsManager.agentToggleTimeout
        if (System.currentTimeMillis() - actionTimestamp > toggleTimeout) {
            pendingAction = null
            return
        }

        // Najít Switch widget
        val switchNode = findSwitch(rootNode)
        
        if (switchNode != null) {
            val isChecked = switchNode.isChecked
            
            // Logika: Pokud stav neodpovídá cíli -> klikni
            if (isChecked != targetState) {
                Log.i(TAG, "⚡ Found switch (checked=$isChecked), clicking to set to $targetState")
                val success = switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.i(TAG, "✓ Switch clicked successfully")
                    pendingAction = null // Done

                    // [INTEGRATION] ActionResultBus.tryEmit — non-suspend kontext (callback)
                    ActionResultBus.tryEmit(ActionResultBus.ActionResult(
                        actionType = action,
                        success = true,
                        thought = "Toggle $action -> $targetState"
                    ))

                    // Auto-back po přepnutí — vrátí uživatele zpět (využití AccessibilityService import)
                    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                } else {
                    Log.w(TAG, "⚠️ Click on switch failed")
                    // Zkusit parent click
                    switchNode.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } else {
                Log.i(TAG, "✓ Switch already in target state ($targetState)")
                pendingAction = null // Done
            }
        }
    }

    private fun findSwitch(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 10) return null  // Limit hloubky pro výkon

        val className = node.className?.toString() ?: ""

        // Strategie 1: Standardní Switch/CompoundButton className (AOSP)
        if (className.contains("Switch", ignoreCase = true) ||
            className.contains("CompoundButton", ignoreCase = true)) {
            return node
        }

        // Strategie 2: isCheckable — OEM přepínače bez standardního className
        // (Samsung OneUI, Xiaomi MIUI, OPPO ColorOS používají vlastní Switch widgety)
        if (node.isCheckable && node.isEnabled) {
            return node
        }

        // Strategie 3: contentDescription nebo text obsahuje toggle klíčová slova
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if ((desc.contains("toggle") || desc.contains("switch") || desc.contains("prepinac")) ||
            (text.contains("toggle") || text.contains("switch"))) {
            if (node.isClickable) return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSwitch(child, depth + 1)
            if (found != null) return found
        }
        return null
    }
}
