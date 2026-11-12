package com.example.llama.managers

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.WindowCompat
import com.example.llama.R
import com.example.llama.ui.theme.AppTheme
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView

/**
 * Singleton manager for handling application themes.
 * Centralizes all theming logic previously scattered across MainActivity and MessageAdapter.
 */
object ThemeManager {

    // --- Theme Definitions (Migrated from MainActivity & MessageAdapter) ---
    private val themes = mapOf(
        "terminal90s" to AppTheme(
            id = "terminal90s",
            // Window
            windowBackground = 0xFF000000.toInt(),
            statusNavColor = 0xFF000000.toInt(),
            isLightBars = false,
            // Toolbar & Nav
            toolbarColor = 0xFF000000.toInt(),
            toolbarTextColor = 0xFF484747.toInt(),
            navViewBackground = 0xFF151515.toInt(),
            navItemColor = 0xFFCCCCCC.toInt(),
            // Content
            contentBackground = 0xFF000000.toInt(),
            // Input
            inputBackground = 0xFF1A1A1A.toInt(),
            inputTextColor = 0xFFCCCCCC.toInt(),
            hintTextColor = 0xFF666666.toInt(),
            // Bubbles
            userBubbleDrawable = R.drawable.bg_user_message_terminal90s,
            assistantBubbleDrawable = R.drawable.bg_assistant_message_terminal90s,
            userBubbleTextColor = 0xFFCCCCCC.toInt(),
            assistantBubbleTextColor = 0xFF00FF00.toInt(), // Green
            // Fonts
            typeface = Typeface.MONOSPACE,
            terminalTextColor = 0xFF484747.toInt()
        ),
        "modern_terminal" to AppTheme(
            id = "modern_terminal",
            // Window
            windowBackground = 0xFF121212.toInt(),
            statusNavColor = 0xFF121212.toInt(),
            isLightBars = false,
            // Toolbar & Nav
            toolbarColor = 0xFF121212.toInt(),
            toolbarTextColor = 0xFF484747.toInt(),
            navViewBackground = 0xFF1A1A1A.toInt(),
            navItemColor = 0xFFE0E0E0.toInt(),
            // Content
            contentBackground = 0xFF121212.toInt(),
            // Input
            inputBackground = 0xFF1A1A1A.toInt(),
            inputTextColor = 0xFFE0E0E0.toInt(),
            hintTextColor = 0xFF888888.toInt(),
            // Bubbles
            userBubbleDrawable = R.drawable.bg_user_message_modern_terminal,
            assistantBubbleDrawable = R.drawable.bg_assistant_message_modern_terminal,
            userBubbleTextColor = 0xFFE0E0E0.toInt(),
            assistantBubbleTextColor = 0xFFE0E0E0.toInt(),
            // Fonts
            typeface = Typeface.MONOSPACE,
            terminalTextColor = 0xFF484747.toInt()
        ),
        "oem" to AppTheme(
            id = "oem",
            // Window
            windowBackground = 0xFF121212.toInt(),
            statusNavColor = 0xFF121212.toInt(),
            isLightBars = false,
            // Toolbar & Nav
            toolbarColor = 0xFF121212.toInt(),
            toolbarTextColor = 0xFF484747.toInt(),
            navViewBackground = 0xFF1E1E1E.toInt(),
            navItemColor = 0xFFFFFFFF.toInt(),
            // Content
            contentBackground = android.graphics.Color.TRANSPARENT,
            // Input
            inputBackground = 0xFFFFFFFF.toInt(), // Unused because inputBackgroundRes is set
            inputBackgroundRes = R.drawable.bg_input_oem,
            inputTextColor = 0xFF000000.toInt(),
            hintTextColor = 0xFF999999.toInt(),
            // Bubbles
            userBubbleDrawable = R.drawable.bg_user_message,
            assistantBubbleDrawable = R.drawable.bg_assistant_message,
            userBubbleTextColor = 0xFFFFFFFF.toInt(),
            assistantBubbleTextColor = 0xFF000000.toInt(),
            // Fonts
            typeface = Typeface.DEFAULT,
            terminalTextColor = 0xFF484747.toInt()
        )
    )

    /**
     * Retrieves a theme definition by ID. Falls back to "oem" if not found.
     */
    fun getTheme(themeId: String): AppTheme {
        return themes[themeId] ?: themes["oem"]!!
    }

    /**
     * Applies the specified theme to the entire Activity and its core views.
     * Note: MessageAdapter handles its own theming via getTheme().
     */
    fun applyTheme(activity: Activity, themeId: String) {
        val theme = getTheme(themeId)

        // Locate Views (Assumes standard MainActivity layout IDs)
        val toolbar = activity.findViewById<MaterialToolbar>(R.id.toolbar)
        val navView = activity.findViewById<NavigationView>(R.id.nav_view)
        val mainContent = activity.findViewById<View>(R.id.main_content)
        val userInputEt = activity.findViewById<EditText>(R.id.user_input)
        val messagesRv = activity.findViewById<View>(R.id.messages)

        // Stats Views
        val ggufTv = activity.findViewById<TextView>(R.id.gguf)
        val speedTv = activity.findViewById<TextView>(R.id.tv_speed)
        val tokenCountTv = activity.findViewById<TextView>(R.id.tv_token_count)
        val genTimeTv = activity.findViewById<TextView>(R.id.tv_gen_time)

        // 1. Window & System Bars
        val window = activity.window
        val wic = WindowCompat.getInsetsController(window, window.decorView)

        // Suppress deprecation: We intentionally set opaque colors for specific themes (Terminal),
        // overriding the default Edge-to-Edge transparency where needed for the retro look.
        @Suppress("DEPRECATION")
        window.statusBarColor = theme.statusNavColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = theme.statusNavColor

        window.decorView.setBackgroundColor(theme.windowBackground)

        wic.isAppearanceLightStatusBars = theme.isLightBars
        wic.isAppearanceLightNavigationBars = theme.isLightBars

        // 2. Toolbar & Navigation
        toolbar.setBackgroundColor(theme.toolbarColor)
        toolbar.setTitleTextColor(theme.toolbarTextColor)

        navView.setBackgroundColor(theme.navViewBackground)
        navView.itemTextColor = ColorStateList.valueOf(theme.navItemColor)
        navView.itemIconTintList = ColorStateList.valueOf(theme.navItemColor)

        // 3. Main Content & RecyclerView
        mainContent.setBackgroundColor(theme.contentBackground)

        // Specific logic for RecyclerView background (retained from original)
        if (theme.id == "oem") {
            messagesRv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        } else {
            messagesRv.setBackgroundColor(theme.windowBackground)
        }

        // 4. Input Field
        val bgRes = theme.inputBackgroundRes
        if (bgRes != null) {
            userInputEt.setBackgroundResource(bgRes)
        } else {
            userInputEt.setBackgroundColor(theme.inputBackground)
        }
        userInputEt.setTextColor(theme.inputTextColor)
        userInputEt.setHintTextColor(theme.hintTextColor)
        userInputEt.typeface = theme.typeface

        // 5. Stats
        ggufTv?.setTextColor(0xFF888888.toInt()) // Hardcoded in original for all themes
        ggufTv?.typeface = theme.typeface // Applies monospace/default

        val statsViews = listOf(speedTv, tokenCountTv, genTimeTv)
        statsViews.forEach { tv ->
            tv?.setTextColor(theme.terminalTextColor)
            tv?.typeface = theme.typeface
        }
    }
}
