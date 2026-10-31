package com.example.llama.managers.plugins

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import io.noties.markwon.MarkwonPlugin



/**
 * Provider for Syntax Highlighting plugin using the custom Native Engine.
 * Enables code syntax highlighting for all supported languages using 100% Native Lexers.
 *
 * Fully offline, high-performance, and regex-free implementation.
 * Supports rich styling (Bold, Italic, Strikethrough, Underline) via ThemeManager.
 */
object SyntaxHighlightPluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_syntax_highlight"
    override val displayName = "Syntax Highlighting"
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        
        // Load selected theme from preferences
        val themeName = prefs.getString("markdown_theme", "default")
        val theme = PrismThemes.getTheme(context, themeName)
       
        // Use our new Native Plugin (100% Native, No Prism4j)
        return com.example.llama.managers.plugins.lexers.NativeSyntaxHighlightPlugin(theme, prefs)
    }
}
