package com.example.llama.agent.utils

import android.util.Log
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.models.AgentAction
import org.json.JSONObject

/**
 * JsonParser - Parsuje LLM output (JSON) → AgentAction
 *
 * ROBUSTNÍ VERZE:
 * - Safe Parse (necrashne na invalidním JSONu)
 * - Synonym Mapping (zvládá 'tap', 'type', 'scroll'...)
 * - Fallback na ActionType.UNKNOWN
 */
class JsonParser {

        companion object {
                private const val TAG = "JsonParser"
        }

        /**
         * Parse LLM output → AgentAction
         *
         * @return AgentAction (typ UNKNOWN v případě chyby, nikdy necrashne)
         */
        fun parse(llmOutput: String): AgentAction = parse(llmOutput, maxElementId = null)

        /**
         * Parse LLM output → AgentAction s validací target_id rozsahu
         *
         * @param maxElementId Maximální platné element ID z ScreenState (null = bez validace)
         * @return AgentAction (typ UNKNOWN v případě chyby, nikdy necrashne)
         */
        fun parse(llmOutput: String, maxElementId: Int?): AgentAction {
                Log.d(TAG, "Parsing LLM output (${llmOutput.length} chars)")

                try {
                        // Extract JSON z LLM response
                        val jsonString = extractJson(llmOutput)
                        if (jsonString.isEmpty()) {
                                Log.w(TAG, "⚠️ No JSON found in output")
                                return createFallbackAction("No JSON found", llmOutput)
                        }

                        // Parse JSON
                        val jsonObj = JSONObject(jsonString)

                        // Extract fields using flexible keys
                        val thought =
                                optStringFlexible(jsonObj, "thought", "reasoning", "reason", "plan")
                                        ?: "Acting based on command"
                        var actionStr =
                                optStringFlexible(
                                        jsonObj,
                                        "action",
                                        "tool",
                                        "function",
                                        "command",
                                        "type"
                                )
                                        ?: ""

                        // [ROBUST FIX] If action is empty but thought is present, assume it's just
                        // a
                        // thought/reply
                        if (actionStr.isEmpty()) {
                                Log.w(
                                        TAG,
                                        "⚠️ Action string is empty in JSON. Defaulting to SPEAK."
                                )
                                actionStr = "speak"
                        }

                        // Safe mapping
                        val actionType = parseActionType(actionStr)

                        // Validate required fields logic based on Type
                        val targetId = jsonObj.optInt("target_id", -1).takeIf { it != -1 }
                        val inputText =
                                optStringFlexible(jsonObj, "input_text", "text", "value", "input")
                                        ?.takeIf { it.isNotBlank() }
                        val x = jsonObj.optInt("x", -1).takeIf { it != -1 }
                        val y = jsonObj.optInt("y", -1).takeIf { it != -1 }

                        // Parse detections (Bounding Boxes pro Smart Vision)
                        val detectionsList = mutableListOf<com.example.llama.agent.models.AgentAction.VisionDetection>()
                        val detectionsArray = jsonObj.optJSONArray("detections")
                        if (detectionsArray != null) {
                            for (i in 0 until detectionsArray.length()) {
                                try {
                                    val dObj = detectionsArray.getJSONObject(i)
                                    detectionsList.add(com.example.llama.agent.models.AgentAction.VisionDetection(
                                        label = dObj.optString("label", "obj"),
                                        confidence = dObj.optDouble("confidence", 1.0).toFloat(),
                                        xMin = dObj.optDouble("xMin", 0.0).toFloat(),
                                        yMin = dObj.optDouble("yMin", 0.0).toFloat(),
                                        xMax = dObj.optDouble("xMax", 1.0).toFloat(),
                                        yMax = dObj.optDouble("yMax", 1.0).toFloat()
                                    ))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse detection at index $i")
                                }
                            }
                        }

                        // SECURITY: Validace target_id rozsahu — obrana proti injekci
                        if (targetId != null && maxElementId != null) {
                                if (targetId !in 1..maxElementId) {
                                        Log.w(
                                                TAG,
                                                "⚠️ SECURITY: target_id=$targetId mimo rozsah (1..$maxElementId) — možná injekce!"
                                        )
                                        return createFallbackAction(
                                                "target_id $targetId mimo rozsah (max: $maxElementId)",
                                                llmOutput
                                        )
                                }
                        }

                        val action =
                                AgentAction(
                                        thought = thought,
                                        type = actionType,
                                        targetId = targetId,
                                        inputText = inputText,
                                        x = x,
                                        y = y,
                                        detections = if (detectionsList.isNotEmpty()) detectionsList else null
                                )

                        // Soft Validation - log warning but return object (ActionExecutor handles
                        // execution
                        // validity)
                        if (actionType == ActionType.UNKNOWN) {
                                Log.w(TAG, "⚠️ Parsed UNKNOWN action: '$actionStr'")
                        }

                        Log.i(TAG, "✓ Parsed action: $action")
                        return action
                } catch (e: Exception) {
                        Log.e(TAG, "❌ JSON Parse Error", e)
                        return createFallbackAction("Parse Error: ${e.message}", llmOutput)
                }
        }

        /** Helper pro flexibilní čtení klíčů (první nalezený vyhrává) */
        private fun optStringFlexible(json: JSONObject, vararg keys: String): String? {
                for (key in keys) {
                        if (json.has(key)) {
                                val value = json.optString(key)
                                if (value.isNotEmpty()) return value
                        }
                }
                return null
        }

        private fun createFallbackAction(reason: String, originalOutput: String): AgentAction {
                return AgentAction(
                        thought = "Error parsing LLM output: $reason",
                        type = ActionType.UNKNOWN,
                        inputText = originalOutput
                )
        }

        /**
         * Extract JSON object z textu (hledá POSLEDNÍ validní blok {}) Důvod: LLM často "opravuje"
         * samo sebe a na konci je správný JSON.
         */
        private fun extractJson(text: String): String {
                try {
                        var cleanText = text
                        // Remove markdown syntax hints - simple replace
                        cleanText = cleanText.replace("```json", "").replace("```", "")

                        val lastClose = cleanText.lastIndexOf('}')
                        if (lastClose == -1) return ""

                        // Find outermost open brace matching the last close brace
                        var balance = 0
                        for (i in lastClose downTo 0) {
                                val char = cleanText[i]
                                if (char == '}') {
                                        balance++
                                } else if (char == '{') {
                                        balance--
                                        if (balance == 0) {
                                                return cleanText.substring(i, lastClose + 1)
                                        }
                                }
                        }

                        // Fallback: simple finder if nesting logic fails (e.g. malformed)
                        val firstOpen = cleanText.indexOf('{')
                        if (firstOpen != -1 && firstOpen < lastClose) {
                                return cleanText.substring(firstOpen, lastClose + 1)
                        }
                } catch (e: Exception) {
                        Log.w(TAG, "Failed to extract JSON regex", e)
                }
                return ""
        }

        /** Map action string → ActionType enum (Robust Synonym Mapping) */
        private fun parseActionType(actionStr: String): ActionType {
                val normalized =
                        actionStr
                                .lowercase()
                                .trim()
                                .replace("_", "") // scroll_down -> scrolldown
                                .replace("-", "")

                return when (normalized) {
                        // CLICK
                        in setOf("click", "klikni", "tap", "touch", "press", "select") ->
                                ActionType.CLICK

                        // SCROLL DOWN
                        in setOf(
                                "scrolldown",
                                "scrollforward",
                                "dolů",
                                "dolu",
                                "godown",
                                "nextpage"
                        ) -> ActionType.SCROLL_DOWN

                        // SCROLL UP
                        in setOf("scrollup", "scrollbackward", "goup", "prevpage") ->
                                ActionType.SCROLL_UP

                        // INPUT TEXT
                        in setOf(
                                "inputtext",
                                "input",
                                "type",
                                "write",
                                "napiš",
                                "entertext",
                                "fill"
                        ) -> ActionType.INPUT_TEXT

                        // BACK
                        in setOf("back", "goback", "return", "escape", "zpět", "zpatky", "zpet") ->
                                ActionType.BACK

                        // HOME
                        in setOf(
                                "home",
                                "gohome",
                                "domů",
                                "jdidomů",
                                "homescreen",
                                "desktop",
                                "plocha"
                        ) -> ActionType.HOME

                        // RECENTS
                        in setOf("recentapps", "recents", "overview", "switcher", "tasks") ->
                                ActionType.RECENT_APPS

                        // SHADE
                        in setOf(
                                "notifications",
                                "notif",
                                "notifikace",
                                "stáhni",
                                "lištu",
                                "listu",
                                "shade",
                                "status bar",
                                "expandnotifications"
                        ) -> ActionType.NOTIFICATIONS

                        // OPEN APP
                        in setOf(
                                "openapp",
                                "open",
                                "launch",
                                "start",
                                "run",
                                "spustit",
                                "otevrit",
                                "otevřít",
                                "zapni"
                        ) -> ActionType.OPEN_APP

                        // SPEAK
                        in setOf(
                                "speak",
                                "say",
                                "talk",
                                "reply",
                                "answer",
                                "odpovedet",
                                "rekni",
                                "povídej",
                                "mluv"
                        ) -> ActionType.SPEAK

                        // === Communication ===
                        in setOf("call", "zavolej", "dial", "ring", "brnkni", "volej", "hovor") ->
                                ActionType.CALL
                        in setOf("sms", "message", "text", "napiszpravu", "textni", "zprava") ->
                                ActionType.SMS
                        in setOf("openurl", "browse", "web", "website", "url", "stranku") ->
                                ActionType.OPEN_URL
                        in setOf(
                                "composeemail",
                                "email",
                                "sendemail",
                                "mailto",
                                "poslimail",
                                "napismail",
                                "mail"
                        ) -> ActionType.COMPOSE_EMAIL

                        // === Systémové rozšíření ===
                        in setOf("opensettings", "settings", "nastaveni") ->
                                ActionType.OPEN_SETTINGS
                        in setOf("setwifi", "wifi") -> ActionType.SET_WIFI
                        in setOf("setbluetooth", "bluetooth", "bt") -> ActionType.SET_BLUETOOTH
                        in setOf("setflashlight", "flashlight", "torch", "svitilna", "baterka") ->
                                ActionType.SET_FLASHLIGHT
                        in setOf("killapp", "forceclose", "zavrapp", "ukonci", "zabij") ->
                                ActionType.KILL_APP

                        // === VoIP Call Handling ===
                        in setOf("answercall", "answer", "zvedni", "prijmi", "pickup") ->
                                ActionType.ANSWER_CALL
                        in setOf(
                                "rejectcall",
                                "reject",
                                "odmitni",
                                "zaves",
                                "poloz",
                                "hangup",
                                "decline"
                        ) -> ActionType.REJECT_CALL

                        // === Connectivity Toggles ===
                        in setOf("setnfc", "nfc") -> ActionType.SET_NFC
                        in setOf("setairplane", "airplane", "letadlo", "flightmode") ->
                                ActionType.SET_AIRPLANE

                        // === Konfigurace (Setup) ===
                        in setOf("volume", "hlasitost", "zvuk", "audio", "setvolume") ->
                                ActionType.SET_VOLUME
                        in setOf(
                                "brightness",
                                "jas",
                                "podsviceni",
                                "display",
                                "displej",
                                "setbrightness"
                        ) -> ActionType.SET_BRIGHTNESS
                        in setOf("rotation", "rotace", "otoceni", "autorotate", "setrotation") ->
                                ActionType.SET_ROTATION

                        // === Inkrementální ovládání ===
                        in setOf("volumeup", "louder", "zesil", "zesilni", "hlasiteji") ->
                                ActionType.VOLUME_UP
                        in setOf("volumedown", "quieter", "ztlum", "tiseji", "mute") ->
                                ActionType.VOLUME_DOWN
                        in setOf("brightnessup", "brighter", "jasneji", "rozjasni") ->
                                ActionType.BRIGHTNESS_UP
                        in setOf("brightnessdown", "dimmer", "dim", "tmaveji", "ztmav") ->
                                ActionType.BRIGHTNESS_DOWN

                        // === Swipe gesta ===
                        in setOf("swipeup", "slideup") -> ActionType.SWIPE_UP
                        in setOf("swipedown", "slidedown") -> ActionType.SWIPE_DOWN
                        in setOf("swipeleft", "slideleft") -> ActionType.SWIPE_LEFT
                        in setOf("swiperight", "slideright") -> ActionType.SWIPE_RIGHT

                        // === Rozšířený Scroll ===
                        in setOf("scrolltotop", "scrolltop", "top") -> ActionType.SCROLL_TO_TOP
                        in setOf("scrolltobottom", "scrollbottom", "bottom") ->
                                ActionType.SCROLL_TO_BOTTOM
                        in setOf("scrolllittleup", "littleup") -> ActionType.SCROLL_LITTLE_UP
                        in setOf("scrolllittledown", "littledown") -> ActionType.SCROLL_LITTLE_DOWN
                        in setOf("scrollpageup", "pageup") -> ActionType.SCROLL_PAGE_UP
                        in setOf("scrollpagedown", "pagedown") -> ActionType.SCROLL_PAGE_DOWN

                        // === Zoom / Multitouch ===
                        in setOf("zoomin", "pinchopen", "zoom", "zvetsi", "pribliz") ->
                                ActionType.ZOOM_IN
                        in setOf("zoomout", "pinchclose", "zmensit", "oddalit") ->
                                ActionType.ZOOM_OUT

                        // === Text Edit ===
                        in setOf("editdeleteword", "deleteword", "smazslovo") ->
                                ActionType.EDIT_DELETE_WORD
                        in setOf("editdeleteall", "deleteall", "smazvse", "cleartext") ->
                                ActionType.EDIT_DELETE_ALL
                        in setOf("editselectall", "selectall", "vybervse", "oznacvse") ->
                                ActionType.EDIT_SELECT_ALL
                        in setOf("editcopy", "copy", "kopiruj", "zkopiruj") -> ActionType.EDIT_COPY
                        in setOf("editpaste", "paste", "vloz", "vlozit") -> ActionType.EDIT_PASTE
                        in setOf("editenter", "enter", "submit", "newline") -> ActionType.EDIT_ENTER
                        in setOf("editundo", "undo", "zpettext") -> ActionType.EDIT_UNDO
                        in setOf("editmovecursorstart", "cursorstart") ->
                                ActionType.EDIT_MOVE_CURSOR_START
                        in setOf("editmovecursorend", "cursorend") ->
                                ActionType.EDIT_MOVE_CURSOR_END

                        // === D-Pad Navigace ===
                        in setOf("navup", "dpadup", "sipkanahoru") -> ActionType.NAV_UP
                        in setOf("navdown", "dpaddown", "sipkadolu") -> ActionType.NAV_DOWN
                        in setOf("navleft", "dpadleft", "sipkavlevo") -> ActionType.NAV_LEFT
                        in setOf("navright", "dpadright", "sipkavpravo") -> ActionType.NAV_RIGHT
                        in setOf("navcenter", "dpadcenter", "potvrd") -> ActionType.NAV_CENTER
                        in setOf("navtab", "tab") -> ActionType.NAV_TAB

                        // === Systémové akce ===
                        in setOf("screenshot", "printscreen", "snimek") -> ActionType.SCREENSHOT
                        in setOf("lockscreen", "lock", "zamkni") -> ActionType.LOCK_SCREEN
                        in setOf("quicksettings", "toggles", "prepinace") ->
                                ActionType.QUICK_SETTINGS
                        in setOf("splitscreen", "multiwindow") -> ActionType.SPLIT_SCREEN
                        in setOf("powerdialog", "poweroff", "shutdown", "restart") ->
                                ActionType.POWER_DIALOG
                        in setOf("longpress", "longclick", "podrz") -> ActionType.LONG_PRESS
                        in setOf("doubletap", "doubleclick") -> ActionType.DOUBLE_TAP
                        in setOf("mediaplaypause", "playpause", "pause", "play") ->
                                ActionType.MEDIA_PLAY_PAUSE
                        in setOf("medianext", "nextsong", "nexttrack") -> ActionType.MEDIA_NEXT
                        in setOf("mediaprevious", "previoussong", "prevtrack") ->
                                ActionType.MEDIA_PREVIOUS
                        in setOf(
                                "ime",
                                "keyboard",
                                "klavesnice",
                                "inputmethod",
                                "vychoziklavesnice",
                                "method"
                        ) -> ActionType.SET_DEFAULT_IME
                        else -> ActionType.UNKNOWN
                }
        }
}
