package com.example.llama.managers.plugins

import android.content.Context
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.tables.TablePlugin
import androidx.preference.PreferenceManager

/**
 * Provider for Markdown Tables plugin.
 * Enables rendering of markdown tables (GitHub Flavored Markdown).
 * 
 * Example:
 * | Header 1 | Header 2 |
 * |----------|----------|
 * | Cell 1   | Cell 2   |
 */
object TablePluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_tables"
    override val displayName = "Tables (Tabulky)"
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        val density = context.resources.displayMetrics.density
        return TablePlugin.create { builder ->
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val appSettings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

            val themeName = prefs.getString("markdown_theme", "default")
            val appTheme = appSettings.getString("chat_style", "oem")

            // Force Dark Tables for Terminal themes (White Text needs Dark Bg)
            // Even if user selected "Default (Light)" syntax theme.
            val isTerminalAppTheme = (appTheme == "terminal90s" || appTheme == "modern_terminal")
            
            val isDark = isTerminalAppTheme || when(themeName) {
                "dark", "okaidia", "twilight", "dracula", "terminal_90s", "tomorrow_night", "funky" -> true
                else -> false
            }

            if (isDark) {
                 // Dark Theme Styling (User Request: #252525 for odd rows)
                 builder
                    .tableBorderColor(0xFF555555.toInt())
                    .tableBorderWidth((1 * density).toInt())
                    .tableCellPadding((8 * density).toInt())
                    .tableHeaderRowBackgroundColor(0xFF303030.toInt()) // Slightly lighter than odd row
                    .tableOddRowBackgroundColor(0xFF252525.toInt())    // User requested #252525
                    .tableEvenRowBackgroundColor(0x00000000.toInt())   // Transparent (shows standard dark bg)
            } else {
                 // Light Theme Styling
                 builder
                    .tableBorderColor(0xFF8A8A8A.toInt())
                    .tableBorderWidth((1 * density).toInt())
                    .tableCellPadding((8 * density).toInt())
                    .tableHeaderRowBackgroundColor(0xFFDDDDDD.toInt())
                    .tableOddRowBackgroundColor(0xFFF9F9F9.toInt())
            }
        }
    }
}
