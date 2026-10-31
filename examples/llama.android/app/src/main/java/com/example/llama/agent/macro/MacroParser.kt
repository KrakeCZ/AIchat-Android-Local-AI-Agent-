package com.example.llama.agent.macro

import com.example.llama.agent.models.ActionType
import org.json.JSONObject

object MacroParser {

    fun parseMacro(json: String): Macro {
        val root = JSONObject(json)
        val name = root.optString("name", "Unnamed Macro")
        val stepsJson = root.getJSONArray("steps")
        val steps = mutableListOf<MacroAction>()

        for (i in 0 until stepsJson.length()) {
            val stepObj = stepsJson.getJSONObject(i)
            val type = stepObj.getString("type")
            
            val action = when (type.lowercase()) {
                "click" -> MacroAction.Click(
                    text = stepObj.optString("text").ifEmpty { null },
                    id = stepObj.optString("id").ifEmpty { null },
                    timeoutMs = stepObj.optLong("timeout", 2000L)
                )
                "wait" -> MacroAction.Wait(
                    text = stepObj.optString("text").ifEmpty { null },
                    id = stepObj.optString("id").ifEmpty { null },
                    timeoutMs = stepObj.optLong("timeout", 5000L)
                )
                "input" -> MacroAction.Input(
                    text = stepObj.getString("text"),
                    clearFirst = stepObj.optBoolean("clear", true)
                )
                "scroll" -> MacroAction.Scroll(
                    direction = if (stepObj.optString("direction", "down") == "up") ActionType.SCROLL_UP else ActionType.SCROLL_DOWN
                )
                "global", "back", "home" -> parseGlobal(type, stepObj)
                "open_app", "openapp" -> MacroAction.OpenApp(
                     packageName = stepObj.getString("package")
                )
                "ensure_package" -> MacroAction.EnsurePackage(
                     packageName = stepObj.getString("package")
                )
                else -> null
            }
            
            if (action != null) {
                steps.add(action)
            }
        }
        
        return Macro(name, steps)
    }
    
    private fun parseGlobal(type: String, obj: JSONObject): MacroAction {
         // Pokud je type přímo global action
         if (type.equals("back", ignoreCase = true)) return MacroAction.Global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
         if (type.equals("home", ignoreCase = true)) return MacroAction.Global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
         if (type.equals("recents", ignoreCase = true)) return MacroAction.Global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
         
         // Generic global
         val actionCode = obj.optInt("code", -1)
         if (actionCode != -1) return MacroAction.Global(actionCode)
         
         return MacroAction.Global(0) // No-op or unknown
    }
}
