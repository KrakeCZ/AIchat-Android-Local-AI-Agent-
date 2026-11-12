package com.example.llama.agent.models

import androidx.annotation.Keep

/**
 * Akce, kterou má agent vykonat (parsed z LLM JSON outputu)
 */
@Keep
data class AgentAction(
    val thought: String,              // LLM reasoning (proč tuto akci dělá)
    val type: ActionType,             // Typ akce
    val targetId: Int? = null,        // ID UI elementu (pro click/input)
    val inputText: String? = null,    // Text pro input (pouze pro INPUT_TEXT)
    val x: Int? = null,               // Y souřadnice (pro Touch Injection)
    val y: Int? = null,               // Y souřadnice (pro Touch Injection)
    val detections: List<VisionDetection>? = null // Vizuální detekce (bounding boxy)
) {
    /**
     * Vizuální detekce (Bounding Box)
     */
    @Keep
    data class VisionDetection(
        val label: String,
        val confidence: Float,
        val xMin: Float, // 0.0 - 1.0 (relativní k obrazovce)
        val yMin: Float,
        val xMax: Float,
        val yMax: Float
    )

    /**
     * Validace akce
     */
    @Suppress("unused")
    fun validate(): Result<Unit> = runCatching {
        when (type) {
            ActionType.CLICK -> {
                // Click is valid if it has targetId OR (x and y)
                if (targetId == null && (x == null || y == null)) {
                     throw IllegalArgumentException("CLICK action requires targetId OR coordinates (x, y)")
                }
            }
            ActionType.INPUT_TEXT -> {
                // targetId je volitelný — IME (HyperKeyboardService) injektuje text
                // do aktuálně fokusovaného pole přes InputConnection bez targetId.
                // Pokud targetId chybí, performTextInput() najde editable element automaticky.
                require(!inputText.isNullOrBlank()) { "INPUT_TEXT action requires inputText" }
            }
            ActionType.OPEN_APP -> {
                require(!inputText.isNullOrBlank()) { "OPEN_APP action requires inputText (Package Name or App Label)" }
            }
            // === Communication validace ===
            ActionType.CALL -> {
                require(!inputText.isNullOrBlank()) { "CALL action requires inputText (phone number or contact name)" }
            }
            ActionType.SMS -> {
                require(!inputText.isNullOrBlank()) { "SMS action requires inputText (phone number)" }
            }
            ActionType.OPEN_URL -> {
                require(!inputText.isNullOrBlank()) { "OPEN_URL action requires inputText (URL)" }
            }
            ActionType.KILL_APP -> {
                require(!inputText.isNullOrBlank()) { "KILL_APP action requires inputText (app name)" }
            }
            ActionType.COMPOSE_EMAIL -> {
                require(!inputText.isNullOrBlank()) { "COMPOSE_EMAIL action requires inputText (email address)" }
            }
            // === Systémové akce bez validace ===

            // === Systémové akce bez validace ===

            ActionType.OPEN_SETTINGS,
            ActionType.SET_WIFI, ActionType.SET_BLUETOOTH, ActionType.SET_FLASHLIGHT,
            ActionType.SET_NFC, ActionType.SET_AIRPLANE,
            ActionType.SET_DEFAULT_IME,
            ActionType.SET_VOLUME, ActionType.SET_BRIGHTNESS, ActionType.SET_ROTATION,
            ActionType.VOLUME_UP, ActionType.VOLUME_DOWN,
            ActionType.BRIGHTNESS_UP, ActionType.BRIGHTNESS_DOWN,
            ActionType.ANSWER_CALL, ActionType.REJECT_CALL -> {
                // No validation needed
            }
            
            // === Gesta a Navigace ===
            ActionType.SCROLL_DOWN, ActionType.SCROLL_UP,
            ActionType.SCROLL_TO_TOP, ActionType.SCROLL_TO_BOTTOM,
            ActionType.SCROLL_LITTLE_UP, ActionType.SCROLL_LITTLE_DOWN,
            ActionType.SCROLL_PAGE_UP, ActionType.SCROLL_PAGE_DOWN,
            ActionType.ZOOM_IN, ActionType.ZOOM_OUT, ActionType.PINCH_OPEN, ActionType.PINCH_CLOSE,
            ActionType.BACK, ActionType.HOME, ActionType.RECENT_APPS,
            ActionType.NOTIFICATIONS, ActionType.SPEAK, ActionType.UNKNOWN,
            ActionType.NAV_UP, ActionType.NAV_DOWN, ActionType.NAV_LEFT, ActionType.NAV_RIGHT,
            ActionType.NAV_CENTER, ActionType.NAV_ENTER, ActionType.NAV_BACK, 
            ActionType.NAV_HOME, ActionType.NAV_RECENTS, ActionType.NAV_TAB,
            
            // === Text Edit ===
            ActionType.EDIT_COPY, ActionType.EDIT_PASTE, ActionType.EDIT_DELETE_WORD,
            ActionType.EDIT_DELETE_ALL, ActionType.EDIT_SELECT_ALL, ActionType.EDIT_ENTER,
            ActionType.EDIT_UNDO, ActionType.EDIT_MOVE_CURSOR_START, ActionType.EDIT_MOVE_CURSOR_END,

            // Systémové akce
            ActionType.SCREENSHOT, ActionType.LOCK_SCREEN, ActionType.QUICK_SETTINGS,
            ActionType.SPLIT_SCREEN, ActionType.POWER_DIALOG,
            // Herní gesta
            ActionType.SWIPE_LEFT, ActionType.SWIPE_RIGHT,
            ActionType.SWIPE_UP, ActionType.SWIPE_DOWN,
            ActionType.LONG_PRESS, ActionType.DOUBLE_TAP, ActionType.RAPID_TAP,
            // Média
            ActionType.MEDIA_PLAY_PAUSE, ActionType.MEDIA_NEXT,
            ActionType.MEDIA_PREVIOUS -> {
                // No validation needed — tyto akce nepotřebují targetId ani inputText
            }
            // === NLP / AICore GenAI akce (Tier 2.5+) ===
            ActionType.NLP_PROOFREAD, ActionType.NLP_REWRITE,
            ActionType.NLP_TRANSLATE, ActionType.NLP_SUMMARIZE,
            ActionType.NLP_SENTIMENT, ActionType.NLP_SMART_REPLY,
            ActionType.NLP_QA, ActionType.NLP_NLI,
            ActionType.NLP_MLM, ActionType.NLP_NER, ActionType.NLP_PROMPT -> {
                require(!inputText.isNullOrBlank()) { "${type.name} action requires inputText" }
            }
        }
    }
    
    override fun toString(): String {
        return "AgentAction(type=$type, targetId=$targetId, text='$inputText')"
    }
}

