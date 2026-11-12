package com.example.llama.agent.core

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * AgentHelper - Utility funkce pro AI Agent
 * 
 * Obsahuje:
 * - Check zda je Accessibility Service enabled
 * - Model readiness check
 * - Permission helpers
 */
object AgentHelper {
    
    private const val TAG = "AgentHelper"
    
    /**
     * Check jestli je Accessibility Service enabled pro naši aplikaci
     * 
     * @param context Application context
     * @return true pokud service je enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == context.packageName &&
                service.resolveInfo.serviceInfo.name.contains("AiAgentAccessibilityService")) {
                Log.d(TAG, "Accessibility service check: TRUE")
                return true
            }
        }
        
        Log.d(TAG, "Accessibility service check: FALSE")
        return false
    }
    
    /**
     * Check jestli je model loaded a ready pro inference
     * 
     * @param engineState Current InferenceEngine.State
     * @return true pokud model je loaded
     */
    fun isModelReady(engineState: com.arm.aichat.InferenceEngine.State): Boolean {
        return engineState is com.arm.aichat.InferenceEngine.State.ModelReady
    }
    
    /**
     * Vytvoř Intent pro otevření Accessibility Settings
     * 
     * @return Intent pro Settings
     */
    fun getAccessibilitySettingsIntent(): android.content.Intent {
        return android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }
}
