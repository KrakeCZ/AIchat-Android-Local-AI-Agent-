package com.example.llama.agent.macro

import com.example.llama.agent.models.ActionType

/**
 * MacroAction - Sealed class definující kroky makra.
 * Vychází z požadavků "State-Aware Macro Engine".
 */
sealed class MacroAction {
    
    // Čekání na element (Event-Driven Wait)
    data class Wait(
        val text: String? = null, 
        val id: String? = null,
        val timeoutMs: Long = 5000L
    ) : MacroAction()

    // Kliknutí (s implicitním ověřením)
    data class Click(
        val text: String? = null,
        val id: String? = null,
        val timeoutMs: Long = 2000L
    ) : MacroAction()

    // Vstup textu
    data class Input(
        val text: String,
        val clearFirst: Boolean = true
    ) : MacroAction()

    // Posun (Gesture)
    data class Scroll(
        val direction: ActionType // SCROLL_DOWN / UP
    ) : MacroAction()

    // Globální akce
    data class Global(
        val action: Int // AccessibilityService.GLOBAL_ACTION_*
    ) : MacroAction()
    
    // Otevření aplikace
    data class OpenApp(
        val packageName: String
    ) : MacroAction()

    // Otevření URL v prohlížeči (SystemController.openUrl)
    data class OpenUrl(
        val url: String
    ) : MacroAction()
    
    // Zajištění, že jsme v správném balíčku (Safety Check)
    data class EnsurePackage(
        val packageName: String
    ) : MacroAction()

    // Autonomní kliknutí — hledá element dle intent labels na živé obrazovce
    // Příklad: "lajkni" → labels=["like","líbí","👍","❤"] → najde tlačítko → click
    data class SmartClick(
        val intent: String,              // Lidský záměr: "lajkni", "komentuj"
        val labels: List<String>,        // Text/contentDescription patterny k hledání
        val timeoutMs: Long = 3000L      // Max čekání na element
    ) : MacroAction()

    // Autonomní textový vstup — najde textové pole na obrazovce + zadá text
    // Příklad: "napiš zprávu ahoj" → fieldLabels=["zpráva","message"] → najde field → input "ahoj"
    data class SmartInput(
        val intent: String,              // Lidský záměr
        val text: String,                // Text k zadání
        val fieldLabels: List<String>,   // Patterny pro hledání textového pole
        val timeoutMs: Long = 3000L
    ) : MacroAction()

    // Přímé provedení AgentAction — pro akce, které nepotřebují screen context
    // (volume, brightness, swipe, system toggles) — ActionExecutor.execute() přímo
    data class DirectExecute(
        val action: com.example.llama.agent.models.AgentAction
    ) : MacroAction()
}

/**
 * Reprezentace celého makra.
 */
data class Macro(
    val name: String,
    val steps: List<MacroAction>
)
