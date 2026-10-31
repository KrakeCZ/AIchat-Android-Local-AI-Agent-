package com.example.llama.managers.plugins

import android.content.Context
import android.content.SharedPreferences
import io.noties.markwon.MarkwonPlugin

/**
 * Base interface for all Markdown plugin providers.
 * Each provider encapsulates logic for:
 * - Checking if the plugin is enabled via SharedPreferences
 * - Creating and returning the appropriate MarkwonPlugin instance
 */
interface BasePluginProvider {
    /**
     * SharedPreferences key for enabling/disabling this plugin.
     * Example: "markdown_enable_tables"
     */
    val preferenceKey: String
    
    /**
     * Human-readable display name for UI.
     * Example: "Tables (Tabulky)"
     */
    val displayName: String
    
    /**
     * Check if this plugin is enabled in user preferences.
     * @param prefs SharedPreferences instance
     * @return true if enabled, false otherwise. Default: true for backwards compatibility.
     */
    fun isEnabled(prefs: SharedPreferences): Boolean {
        return prefs.getBoolean(preferenceKey, true) // Default: všechny pluginy zapnuté
    }
    
    /**
     * Get the MarkwonPlugin instance for this provider.
     * @param context Android Context
     * @return MarkwonPlugin or null if it cannot be created
     */
    fun getPlugin(context: Context): MarkwonPlugin?
}
