package com.example.llama.agent.service

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Log

/**
 * AiAgentSession — Interakce digitálního asistenta
 *
 * Propojení VoiceInteractionService ↔ AccessibilityService:
 * 1. onHandleAssist() → dostane AssistStructure (UI snapshot od systému)
 * 2. Deleguje příkaz na AiAgentAccessibilityService.getInstance()?.activateAgent()
 * 3. AS pak použije rootInActiveWindow + coordinator pro execution
 *
 * Toto je BRIDGE mezi ROLE_ASSISTANT a AccessibilityService.
 *
 * Flow: Home long press → VIS.showSession() → SessionService.onNewSession() →
 * AiAgentSession.onShow() → TTS "Poslouchám" → STT → onHandleAssist() → activateAgent(text) → Tier
 * 1/2/3
 */
class AiAgentSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AiAgentSession"
    }

    /**
     * Session se zobrazuje — uživatel stiskl HW tlačítko nebo řekl hotword. Startujeme hlasové
     * rozpoznávání přes propojený AccessibilityService.
     */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "📱 Session shown — flags=0x${showFlags.toString(16)}")

        // PROPOJENÍ: Deleguj na AccessibilityService pro voice command
        val agentService = AiAgentAccessibilityService.getInstance()
        if (agentService != null) {
            Log.i(TAG, "🔗 Delegating to AccessibilityService.startVoiceCommand()")
            agentService.startVoiceCommand()
        } else {
            Log.e(TAG, "❌ AccessibilityService not running — cannot process voice command")
            // Fallback: Zobraz info že je potřeba povolit Accessibility
            try {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                                    context,
                                    "Agent Service není aktivní — povolte v Usnadnění přístupu",
                                    android.widget.Toast.LENGTH_LONG
                            )
                            .show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Toast failed", e)
            }
        }

        // Zavřít session UI (nepotřebujeme vizuální overlay — vše přes TTS/STT)
        hide()
    }

    /**
     * AssistStructure — systém poskytuje UI snapshot aktuální app. Toto je DOPLNĚK k
     * rootInActiveWindow z AccessibilityService. Může být užitečné pro Tier 3 (LLM potřebuje
     * kontext obrazovky).
     */
    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)

        val structure: AssistStructure? = state.assistStructure
        val content: AssistContent? = state.assistContent
        val data: Bundle? = state.assistData

        Log.i(
                TAG,
                "📋 AssistStructure: ${if (structure != null) "${structure.windowNodeCount} windows" else "NULL"}"
        )
        Log.i(TAG, "📋 AssistContent: uri=${content?.webUri}, intent=${content?.intent}")

        // Zapsání kontextu do Coordinatoru (Implementováno)
        AiAgentAccessibilityService.getInstance()?.updateAssistContext(structure, content, data)
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            Log.i(TAG, "📸 Screenshot received: ${screenshot.width}x${screenshot.height}")
            // Místo uvolnění přímo zde, předáme do služby. Služba/VisionSourceManager po užití
            // zavolá recycle()
            val agentService = AiAgentAccessibilityService.getInstance()
            if (agentService != null) {
                // Služba se postará o kompresi a injekci obrazu do JNI inference engine
                agentService.processVisionScreenshot(screenshot)
            } else {
                Log.w(
                        TAG,
                        "⚠️ Služba Accessibility není dostupná pro zpracování screenshotu. Recykluji..."
                )
                screenshot.recycle()
            }
        }
    }
}
