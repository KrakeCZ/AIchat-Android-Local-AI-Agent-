package com.example.llama.ui.theme

import android.graphics.Typeface
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes

/**
 * Data class defining the visual theme of the application.
 * Contains all color, resource, and font definitions previously hardcoded in MainActivity/Adapter.
 */
data class AppTheme(
    val id: String,
    
    // --- Window & Status Bat ---
    @get:ColorInt val windowBackground: Int,
    @get:ColorInt val statusNavColor: Int,
    val isLightBars: Boolean, // True = dark icons (for light bg), False = light icons (for dark bg)

    // --- Toolbar & Navigation ---
    @get:ColorInt val toolbarColor: Int,
    @get:ColorInt val toolbarTextColor: Int,
    @get:ColorInt val navViewBackground: Int,
    @get:ColorInt val navItemColor: Int,

    // --- Main Content ---
    @get:ColorInt val contentBackground: Int,

    // --- User Input Field ---
    @get:ColorInt val inputBackground: Int, // Used if inputBackgroundRes is null
    @get:DrawableRes val inputBackgroundRes: Int? = null, // Used for OEM/Drawable backgrounds
    @get:ColorInt val inputTextColor: Int,
    @get:ColorInt val hintTextColor: Int,

    // --- Message Bubbles (MessageAdapter) ---
    @get:DrawableRes val userBubbleDrawable: Int,
    @get:DrawableRes val assistantBubbleDrawable: Int,
    @get:ColorInt val userBubbleTextColor: Int,
    @get:ColorInt val assistantBubbleTextColor: Int,

    // --- Fonts & Statistics ---
    val typeface: Typeface,
    @get:ColorInt val terminalTextColor: Int // For GGUF/Speed stats
)
