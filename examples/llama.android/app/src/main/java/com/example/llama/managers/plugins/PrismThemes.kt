package com.example.llama.managers.plugins

import androidx.core.graphics.toColorInt

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import io.noties.markwon.syntax.Prism4jTheme
import io.noties.prism4j.Prism4j

/**
 * Factory for Prism4j syntax highlighting themes.
 * Provides standard themes and custom implementations for popular schemes.
 * Uses manual implementation of Prism4jTheme interface since builders are limited.
 */
interface PrismThemeWithColor : Prism4jTheme {
    fun getTokenColor(type: String): Int
}

object PrismThemes {

    enum class Theme(val displayName: String, val preferenceValue: String) {
        DEFAULT("Default (Light)", "default"),
        DARK("Dark (Material)", "dark"),
        OKAIDIA("Okaidia (Matrix)", "okaidia"),
        TWILIGHT("Twilight (Orange/Purple)", "twilight"),
        DRACULA("Dracula (Vampire)", "dracula"),
        SOLARIZED_LIGHT("Solarized Light", "solarized_light"),
        TERMINAL_90S("Terminal 90s", "terminal_90s"),
        COY("Coy (Blue/Green)", "coy"),
        FUNKY("Funky (Bold)", "funky"),
        TOMORROW_NIGHT("Tomorrow Night", "tomorrow_night")
    }

    fun getTheme(context: android.content.Context, themeName: String?): PrismThemeWithColor {
        return when (themeName) {
            "dark" -> createDarkTheme(context)
            "okaidia" -> createOkaidiaTheme(context)
            "twilight" -> createTwilightTheme(context)
            "dracula" -> createDraculaTheme(context)
            "solarized_light" -> createSolarizedLightTheme(context)
            "terminal_90s" -> createTerminal90sTheme(context)
            "coy" -> createCoyTheme(context)
            "funky" -> createFunkyTheme(context)
            "tomorrow_night" -> createTomorrowNightTheme(context)
            else -> createDefaultTheme(context) // Manual default with full token support
        }
    }

    private fun createDefaultTheme(context: android.content.Context): PrismThemeWithColor {
        // Standard PrismJS Light Theme
        val styles = HashMap<String, Int>()
        val base = getColor(context, com.example.llama.R.color.prism_default_text)
        val comment = getColor(context, com.example.llama.R.color.prism_default_comment)
        val punctuation = getColor(context, com.example.llama.R.color.prism_default_punctuation)
        val property = getColor(context, com.example.llama.R.color.prism_default_property) // Red-ish
        val selector = getColor(context, com.example.llama.R.color.prism_default_selector) // Green-ish
        val string = getColor(context, com.example.llama.R.color.prism_default_string) // Green
        val keyword = getColor(context, com.example.llama.R.color.prism_default_keyword) // Blue
        val function = getColor(context, com.example.llama.R.color.prism_default_function) // Pink/Red
        val variable = getColor(context, com.example.llama.R.color.prism_default_variable) // Brown-ish or Darker

        styles["comment"] = comment
        styles["prolog"] = comment
        styles["doctype"] = comment
        styles["cdata"] = comment

        styles["punctuation"] = punctuation

        styles["namespace"] = base // Opacity .7
        styles["property"] = property
        styles["tag"] = property
        styles["boolean"] = property
        styles["number"] = property
        styles["constant"] = property
        styles["symbol"] = property
        styles["deleted"] = property

        styles["selector"] = selector
        styles["attr-name"] = selector
        styles["string"] = string
        styles["char"] = string
        styles["builtin"] = string
        styles["inserted"] = string
        styles["url"] = string
        styles["string-interpolation"] = string
        styles["triple-quoted-string"] = string

        styles["entity"] = base // cursor?
        styles["operator"] = base // usually slightly lighter background
        styles["variable"] = variable

        styles["atrule"] = keyword
        styles["attr-value"] = keyword
        styles["keyword"] = keyword
        styles["class-name"] = keyword
        styles["function"] = function
        styles["decorator"] = function

        styles["regex"] = "#ee9900".toColorInt() // Orange
        styles["important"] = "#ee9900".toColorInt() // Orange

        // Custom & Missing
        styles["header"] = keyword
        styles["title"] = keyword
        styles["bold"] = base
        styles["italic"] = base
        styles["annotation"] = function

        // SQL & LOGIC operators (AND, OR, IS) -> Make them visible!
        styles["operator"] = selector // Was keyword(Blue). Now Selector(Green/Teal) -> Distinct!
        
        // Types (INT, VARCHAR) use class-name
        styles["class-name"] = function // Was keyword(Blue). Now Function(Pink) -> Distinct!

        // Extended Aliases (Ensure nothing is lost!)
        styles["parameter"] = variable
        styles["field"] = variable
        styles["interface"] = styles["class-name"]!!
        styles["type-alias"] = styles["class-name"]!!
        styles["trait"] = styles["class-name"]!! // Rust
        styles["doc-comment"] = comment
        styles["static-property"] = property
        styles["static-method"] = function
        styles["package"] = styles["namespace"]!!
        styles["import"] = styles["namespace"]!!

        val bg = getColor(context, com.example.llama.R.color.prism_default_bg)
        return SimpleTheme(bg, base, styles)
    }

    private fun createTerminal90sTheme(context: android.content.Context): PrismThemeWithColor {
        // Matrix/Hacker style - Enhanced with Amber/Cyan for readability
        val styles = HashMap<String, Int>()
        val green = getColor(context, com.example.llama.R.color.prism_terminal_green)
        val brightGreen = getColor(context, com.example.llama.R.color.prism_terminal_bright_green)
        val darkGreen = getColor(context, com.example.llama.R.color.prism_terminal_dark_green)
        
        // Manual "Retro CRT" colors
        val amber = 0xFFFFB000.toInt() // Classic Amber Monitor
        val cyan = 0xFF00FFFF.toInt()  // Digital Cyan
        val red = 0xFFFF0000.toInt()   // Error Red

        styles["comment"] = darkGreen
        styles["doc-comment"] = darkGreen
        styles["prolog"] = darkGreen

        styles["punctuation"] = green
        styles["operator"] = green
        
        // Strings in Amber (Contrast)
        styles["string"] = amber
        styles["string-interpolation"] = amber
        styles["triple-quoted-string"] = amber
        styles["char"] = amber

        // Keywords in Bright Neon
        styles["keyword"] = brightGreen
        styles["boolean"] = cyan
        styles["number"] = cyan
        
        styles["variable"] = green
        styles["parameter"] = green
        styles["field"] = green
        
        styles["function"] = brightGreen
        styles["class-name"] = brightGreen
        styles["interface"] = brightGreen
        
        styles["decorator"] = amber
        styles["annotation"] = amber

        styles["property"] = green
        styles["key"] = green
        styles["scalar"] = cyan // Values often cyan
        styles["regex"] = amber // Regex is string-like
        styles["namespace"] = brightGreen

        // RUST / NASM / C++ Support (Terminal 90s)
        styles["macro"] = brightGreen
        styles["attribute"] = amber
        styles["lifetime"] = green
        styles["instruction"] = brightGreen // ASM Mov
        styles["register"] = cyan       // ASM Eax -> Cyan
        styles["label"] = amber         // Label -> Amber

        // Extended (Terminal 90s)
        styles["parameter"] = green
        styles["field"] = green
        styles["interface"] = brightGreen
        styles["doc-comment"] = darkGreen
        styles["operator"] = green 
        styles["class-name"] = brightGreen
        
        styles["deleted"] = red
        styles["inserted"] = brightGreen


        val bg = getColor(context, com.example.llama.R.color.prism_terminal_bg)
        
        // Fail-Safe Color: Explicit Alpha 0xFF (Now from Resource)
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_terminal_text_dim)
        
        // User Request: Terminal 90s uses baseTextGray
        return SimpleTheme(bg, baseTextGray, styles)
    }

    // --- Custom Theme Implementation ---

    private class SimpleTheme(
        private val backgroundColor: Int,
        private val textColor: Int,
        private val styles: Map<String, Int>
    ) : PrismThemeWithColor {
        override fun background(): Int = backgroundColor
        override fun textColor(): Int = textColor

        override fun getTokenColor(type: String): Int {
             // Direct match
             val color = styles[type]
             if (color != null) return color

             // Simple fallback logic (aliases)
             // In Native engine we don't have aliases fully, but we can map common ones
             // e.g. "keyword" is standard. "class-name" is standard.
             return 0
        }

        override fun apply(
            language: String,
            syntax: Prism4j.Syntax,
            builder: SpannableStringBuilder,
            start: Int,
            end: Int
        ) {
            // Try to find color for token type, fallback to alias
            val color = styles[syntax.type()] ?: syntax.alias()?.let { styles[it] }

            // Apply span if color found
            if (color != null) {
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    // --- Theme Factories ---

    private fun getColor(context: android.content.Context, colorResId: Int): Int {
        return androidx.core.content.ContextCompat.getColor(context, colorResId)
    }

    private fun createDarkTheme(context: android.content.Context): PrismThemeWithColor {
        // Material Dark-ish
        val styles = HashMap<String, Int>()
        val green = getColor(context, com.example.llama.R.color.prism_dark_green)
        val blue = getColor(context, com.example.llama.R.color.prism_dark_blue)
        val purple = getColor(context, com.example.llama.R.color.prism_dark_purple)
        val orange = getColor(context, com.example.llama.R.color.prism_dark_orange)
        val grey = getColor(context, com.example.llama.R.color.prism_dark_comment)
        val cyan = getColor(context, com.example.llama.R.color.prism_dark_cyan)
        val red = getColor(context, com.example.llama.R.color.prism_dark_red)

        styles["comment"] = grey
        styles["doc-comment"] = grey
        styles["prolog"] = grey
        styles["doctype"] = grey
        styles["cdata"] = grey
        styles["punctuation"] = Color.WHITE

        styles["property"] = cyan
        styles["tag"] = purple
        styles["boolean"] = purple
        styles["number"] = orange
        styles["constant"] = orange
        styles["symbol"] = orange
        styles["deleted"] = red

        styles["selector"] = purple
        styles["attr-name"] = orange
        styles["string"] = green
        styles["char"] = green
        styles["builtin"] = cyan
        styles["inserted"] = green

        styles["operator"] = cyan // Was purple. SQL operators (AND, OR) need distinction from purple keywords!
        styles["entity"] = Color.WHITE
        styles["url"] = blue
        styles["string-interpolation"] = green
        styles["triple-quoted-string"] = green

        styles["keyword"] = purple
        styles["atrule"] = purple
        styles["function"] = blue
        styles["class-name"] = blue
        styles["regex"] = orange
        styles["important"] = orange
        styles["variable"] = orange

        // Extended Aliases (Dark)
        styles["parameter"] = orange
        styles["field"] = cyan
        styles["interface"] = blue
        styles["type-alias"] = blue
        styles["trait"] = blue
        styles["package"] = purple
        styles["import"] = purple
        styles["annotation"] = orange

        // Custom
        styles["decorator"] = blue
        styles["annotation"] = orange
        styles["key"] = cyan // YAML key
        styles["scalar"] = green // YAML value

        // Markdown Support (Dark)
        styles["title"] = red       // Headers -> Red
        styles["list"] = orange     // Bullets -> Orange (Fix: Yellow not defined in Dark)
        styles["bold"] = orange     // Bold -> Orange
        styles["italic"] = orange   // Italic -> Orange
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = grey // Quote -> Grey
        styles["url"] = blue        // Link -> Blue

        // RUST Support
        styles["macro"] = blue      // println! -> blue (like function)
        styles["attribute"] = orange // #[derive] -> orange (like annotation)
        styles["lifetime"] = orange // 'a -> orange (like annotation/variable)

        // ASSEMBLY (NASM) Support
        styles["instruction"] = purple // mov, eax -> purple (keyword)
        styles["register"] = orange    // eax -> orange (variable)
        styles["label"] = blue         // start: -> blue (function)

        val bg = getColor(context, com.example.llama.R.color.prism_dark_bg)
        // User Request: Base text resource
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        return SimpleTheme(bg, baseTextGray, styles)
    }

    private fun createOkaidiaTheme(context: android.content.Context): PrismThemeWithColor {
        // Monokai-ish
        val styles = HashMap<String, Int>()
        val grey = getColor(context, com.example.llama.R.color.prism_okaidia_grey)
        val pink = getColor(context, com.example.llama.R.color.prism_okaidia_pink)
        val purple = getColor(context, com.example.llama.R.color.prism_okaidia_purple)
        val green = getColor(context, com.example.llama.R.color.prism_okaidia_green)
        val yellow = getColor(context, com.example.llama.R.color.prism_okaidia_yellow)
        val blue = getColor(context, com.example.llama.R.color.prism_okaidia_blue)
        val orange = getColor(context, com.example.llama.R.color.prism_okaidia_orange)
        val punctuation = getColor(context, com.example.llama.R.color.prism_okaidia_text) // reusing text color for punct in okaidia commonly

        styles["comment"] = grey
        styles["prolog"] = grey
        styles["doctype"] = grey
        styles["cdata"] = grey
        styles["punctuation"] = punctuation

        styles["namespace"] = pink
        styles["property"] = pink
        styles["tag"] = pink
        styles["constant"] = pink
        styles["symbol"] = pink
        styles["deleted"] = pink

        styles["boolean"] = purple
        styles["number"] = purple

        styles["selector"] = green
        styles["attr-name"] = green
        styles["string"] = green
        styles["char"] = green
        styles["builtin"] = green
        styles["inserted"] = green
        styles["string-interpolation"] = green
        styles["triple-quoted-string"] = green
        styles["url"] = green

        styles["operator"] = punctuation
        styles["entity"] = punctuation

        styles["atrule"] = yellow
        styles["attr-value"] = yellow
        styles["function"] = yellow
        styles["class-name"] = yellow
        styles["decorator"] = yellow

        styles["keyword"] = blue

        styles["regex"] = orange
        styles["important"] = orange
        styles["variable"] = orange
        styles["annotation"] = orange

        styles["key"] = pink
        styles["scalar"] = green

        // Markdown Support (Okaidia)
        styles["title"] = pink      // Headers -> Pink
        styles["list"] = purple     // Bullets -> Purple
        styles["bold"] = orange     // Bold -> Orange
        styles["italic"] = orange   // Italic -> Orange
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = grey // Quote -> Grey
        styles["url"] = blue        // Link -> Blue

        // RUST / NASM / C++ Support (Okaidia)
        styles["macro"] = blue
        styles["attribute"] = orange
        styles["lifetime"] = orange
        styles["instruction"] = blue // ASM Mov -> Keyword (Blue)
        styles["register"] = orange  // ASM Eax -> Variable (Orange)
        styles["label"] = yellow

        // Extended (Okaidia)
        styles["parameter"] = orange // Matches variable
        styles["field"] = pink       // Matches property
        styles["interface"] = blue   // Matches class-name
        styles["doc-comment"] = grey
        styles["operator"] = punctuation // Restore Authentic: Operators are White in Okaidia (Ghost White)
        styles["class-name"] = blue

        val bg = getColor(context, com.example.llama.R.color.prism_okaidia_bg)
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        return SimpleTheme(bg, baseTextGray, styles)
    }

    private fun createTwilightTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        val grey = getColor(context, com.example.llama.R.color.prism_twilight_grey)
        val red = getColor(context, com.example.llama.R.color.prism_twilight_red)
        val yellow = getColor(context, com.example.llama.R.color.prism_twilight_yellow)
        val green = getColor(context, com.example.llama.R.color.prism_twilight_green)
        val orange = getColor(context, com.example.llama.R.color.prism_twilight_orange)
        val blue = getColor(context, com.example.llama.R.color.prism_twilight_blue)
        val punct = getColor(context, com.example.llama.R.color.prism_twilight_punctuation)

        styles["comment"] = grey
        styles["prolog"] = grey
        styles["doctype"] = grey
        styles["cdata"] = grey
        styles["punctuation"] = punct

        styles["tag"] = orange
        styles["boolean"] = red
        styles["number"] = red
        styles["deleted"] = red

        styles["keyword"] = orange
        styles["selector"] = orange
        styles["important"] = orange
        styles["atrule"] = orange
        styles["builtin"] = orange
        styles["operator"] = orange // Twilight standard: Operators are Orange (same as Keyword)
        styles["entity"] = orange
        styles["url"] = orange

        styles["attr-value"] = green
        styles["string"] = green
        styles["char"] = green
        styles["inserted"] = green
        styles["string-interpolation"] = green
        styles["triple-quoted-string"] = green

        styles["function"] = blue
        styles["class-name"] = yellow
        styles["decorator"] = yellow
        styles["annotation"] = yellow

        styles["variable"] = blue
        styles["property"] = orange
        styles["regex"] = green

        styles["key"] = orange
        styles["scalar"] = green

        // Markdown Support (Twilight)
        styles["title"] = red       // Headers -> Red
        styles["list"] = yellow     // Bullets -> Yellow
        styles["bold"] = orange     // Bold -> Orange
        styles["italic"] = orange   // Italic -> Orange
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = grey // Quote -> Grey
        styles["url"] = blue        // Link -> Blue

        // RUST / NASM / C++ Support (Twilight)
        styles["macro"] = blue
        styles["attribute"] = yellow
        styles["lifetime"] = orange
        styles["instruction"] = orange // ASM instruction -> Keyword (Orange)
        styles["register"] = blue      // ASM Register -> Variable (Blue)
        styles["label"] = green

        // Extended (Twilight)
        styles["parameter"] = blue     // Matches variable
        styles["field"] = orange       // Matches property
        styles["interface"] = yellow   // Matches class-name
        styles["doc-comment"] = grey
        // Operator kept as Orange (Authentic)
        
       val bg = getColor(context, com.example.llama.R.color.prism_twilight_bg)
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        return SimpleTheme(bg, baseTextGray, styles)
    }

    private fun createDraculaTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        val comment = getColor(context, com.example.llama.R.color.prism_dracula_comment)
        val pink = getColor(context, com.example.llama.R.color.prism_dracula_pink)
        val purple = getColor(context, com.example.llama.R.color.prism_dracula_purple)
        val cyan = getColor(context, com.example.llama.R.color.prism_dracula_cyan)
        val green = getColor(context, com.example.llama.R.color.prism_dracula_green)
        val yellow = getColor(context, com.example.llama.R.color.prism_dracula_yellow)
        val fg = getColor(context, com.example.llama.R.color.prism_dracula_text)

        styles["comment"] = comment
        styles["prolog"] = comment
        styles["doctype"] = comment
        styles["cdata"] = comment
        styles["punctuation"] = fg

        styles["delimiter"] = pink
        styles["boolean"] = purple
        styles["keyword"] = pink
        styles["selector"] = pink
        styles["important"] = pink
        styles["atrule"] = pink
        styles["operator"] = pink // Dracula Standard: Operators are Pink
        styles["tag"] = pink
        styles["property"] = pink

        styles["builtin"] = cyan
        styles["class-name"] = cyan
        styles["decorator"] = cyan

        styles["attr-name"] = green
        styles["function"] = green

        styles["string"] = yellow
        styles["char"] = yellow
        styles["attr-value"] = yellow
        styles["string-interpolation"] = yellow
        styles["triple-quoted-string"] = yellow

        styles["variable"] = fg
        styles["regex"] = yellow
        styles["number"] = purple

        styles["annotation"] = pink
        styles["key"] = pink
        styles["scalar"] = yellow

        // Markdown Support (Dracula)
        styles["title"] = pink      // Headers -> Pink
        styles["list"] = purple     // Bullets -> Purple
        styles["bold"] = yellow     // Bold -> Yellow
        styles["italic"] = yellow   // Italic -> Yellow
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = comment // Quote -> Grey
        styles["url"] = cyan        // Link -> Cyan

        // RUST / NASM / C++ Support (Dracula)
        styles["macro"] = green
        styles["attribute"] = pink
        styles["lifetime"] = purple
        styles["instruction"] = pink   // ASM -> Keyword
        styles["register"] = cyan      // ASM -> Type/Constant (Cyan) OR Orange (User Pref)
        styles["label"] = green

        // Extended (Dracula)
        // Manual override for Orange (User Request)
        val orange = 0xFFFFB86C.toInt() 

        styles["parameter"] = orange   // Distinct user feature
        styles["field"] = purple
        styles["interface"] = cyan
        styles["doc-comment"] = comment
        // styles["operator"] = pink // Kept authentic
        styles["register"] = orange // User wants this

        val bg = getColor(context, com.example.llama.R.color.prism_dracula_bg)
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        return SimpleTheme(bg, baseTextGray, styles)
    }

    private fun createSolarizedLightTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        val base01 = getColor(context, com.example.llama.R.color.prism_solarized_base01)
        val yellow = getColor(context, com.example.llama.R.color.prism_solarized_yellow)
        val orange = getColor(context, com.example.llama.R.color.prism_solarized_orange)
        val red = getColor(context, com.example.llama.R.color.prism_solarized_red)
        val magenta = getColor(context, com.example.llama.R.color.prism_solarized_magenta)
        val violet = getColor(context, com.example.llama.R.color.prism_solarized_violet)
        val blue = getColor(context, com.example.llama.R.color.prism_solarized_blue)
        val cyan = getColor(context, com.example.llama.R.color.prism_solarized_cyan)
        val green = getColor(context, com.example.llama.R.color.prism_solarized_green)

        styles["comment"] = base01
        styles["prolog"] = base01
        styles["punctuation"] = base01

        styles["string"] = cyan
        styles["char"] = cyan
        styles["attr-value"] = cyan
        styles["string-interpolation"] = cyan
        styles["triple-quoted-string"] = cyan

        // Restore standard Prism Solarized
        styles["keyword"] = green
        styles["url"] = green
        styles["operator"] = yellow // Solarized Standard: Yellow
        styles["entity"] = yellow

        styles["number"] = magenta
        styles["boolean"] = magenta
        styles["variable"] = magenta

        styles["function"] = blue
        styles["class-name"] = blue
        styles["decorator"] = blue
        styles["property"] = blue
        styles["key"] = blue
        styles["attr-name"] = blue

        styles["regex"] = red
        styles["important"] = red

        // Use Orange and Violet for specific tokens
        styles["symbol"] = orange
        styles["constant"] = orange
        styles["deleted"] = orange
        styles["tag"] = orange

        styles["builtin"] = violet
        styles["namespace"] = violet
        styles["scalar"] = violet

        // Markdown Support (Solarized)
        styles["title"] = red       // Headers -> Red
        styles["list"] = yellow     // Bullets -> Yellow
        styles["bold"] = base01     // Bold -> Base01
        styles["italic"] = base01   // Italic -> Base01
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = base01 // Quote -> Base01
        styles["url"] = blue        // Link -> Blue

        // RUST / NASM / C++ Support (Solarized)
        styles["macro"] = blue
        styles["attribute"] = yellow
        styles["lifetime"] = magenta
        styles["instruction"] = green // ASM -> Keyword
        styles["register"] = magenta  // ASM -> Variable
        styles["label"] = blue

        // Extended (Solarized)
        styles["parameter"] = magenta
        styles["field"] = blue
        styles["interface"] = yellow // Maybe Blue? usually classes are blue. Yellow is for types?
        styles["doc-comment"] = base01
        // styles["operator"] = yellow // Kept authentic
        
        // Restore Magenta preference
        styles["boolean"] = magenta
        styles["annotation"] = magenta

        val bg = getColor(context, com.example.llama.R.color.prism_solarized_bg)
        val text = getColor(context, com.example.llama.R.color.prism_solarized_text)
        return SimpleTheme(bg, text, styles)
    }

    private fun createCoyTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        val text = getColor(context, com.example.llama.R.color.prism_coy_text)
        val comment = getColor(context, com.example.llama.R.color.prism_coy_comment)
        val blue = getColor(context, com.example.llama.R.color.prism_coy_blue)
        val green = getColor(context, com.example.llama.R.color.prism_coy_green)
        val red = getColor(context, com.example.llama.R.color.prism_coy_number_bool)
        val operator = getColor(context, com.example.llama.R.color.prism_coy_operator)

        styles["comment"] = comment
        styles["prolog"] = comment
        styles["doctype"] = comment
        styles["cdata"] = comment

        styles["punctuation"] = text

        styles["property"] = blue
        styles["keyword"] = blue
        styles["tag"] = blue

        styles["class-name"] = blue
        styles["boolean"] = red
        styles["number"] = red
        styles["symbol"] = red
        styles["constant"] = red
        styles["deleted"] = red

        styles["selector"] = green
        styles["attr-name"] = green
        styles["string"] = green
        styles["char"] = green
        styles["builtin"] = green
        styles["url"] = green
        styles["inserted"] = green

        styles["string-interpolation"] = green

        styles["operator"] = operator
        styles["entity"] = operator
        styles["variable"] = operator

        // RUST / NASM / C++ Support (Coy)
        styles["macro"] = red
        styles["attribute"] = blue
        styles["lifetime"] = operator
        styles["instruction"] = blue
        styles["register"] = operator
        styles["label"] = red

        // Extended (Coy)
        styles["parameter"] = blue
        styles["field"] = blue
        styles["interface"] = blue
        styles["doc-comment"] = comment
        styles["operator"] = operator // Coy operator is distinct (Brown)
        styles["class-name"] = blue

        styles["function"] = red
        styles["decorator"] = red
        styles["annotation"] = red

        styles["regex"] = operator
        styles["important"] = operator

        val bg = getColor(context, com.example.llama.R.color.prism_coy_bg)
        return SimpleTheme(bg, text, styles)
    }

    private fun createTomorrowNightTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        // User Request: Use resource
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        val comment = getColor(context, com.example.llama.R.color.prism_tomorrow_comment)
        val red = getColor(context, com.example.llama.R.color.prism_tomorrow_red)
        val orange = getColor(context, com.example.llama.R.color.prism_tomorrow_orange)
        val yellow = getColor(context, com.example.llama.R.color.prism_tomorrow_yellow)
        val green = getColor(context, com.example.llama.R.color.prism_tomorrow_green)
        val blue = getColor(context, com.example.llama.R.color.prism_tomorrow_blue)
        val purple = getColor(context, com.example.llama.R.color.prism_tomorrow_purple)
        val aqua = getColor(context, com.example.llama.R.color.prism_tomorrow_aqua)

        styles["comment"] = comment
        styles["prolog"] = comment
        styles["punctuation"] = baseTextGray

        styles["property"] = red // Wait, standard Tomorrow says Classes/Keywords are yellow/purple.
        // Prism mapping:
        // Tag, Attr-Name, Deleted -> Red
        styles["tag"] = red
        styles["attr-name"] = red
        styles["deleted"] = red
        styles["namespace"] = red

        // Function -> Blue
        styles["function"] = blue

        // Boolean, Number -> Orange
        styles["boolean"] = orange
        styles["number"] = orange

        // Keyword, Selector -> Purple
        styles["keyword"] = purple
        styles["selector"] = purple
        styles["important"] = purple
        styles["atrule"] = purple

        // String, Char -> Green
        styles["string"] = green
        styles["char"] = green
        styles["inserted"] = green

        // Operator, Entity -> Aqua/Text? Usually Aqua in Tomorrow Eighties, but standard Tomorrow Night for Prism might differ.
        // Prism source often puts Operator as standard text or specific color.
        // I will use Aqua for Operator to distinct it.
        styles["operator"] = aqua
        styles["entity"] = aqua
        styles["url"] = aqua

        // Class Name, Regex -> Yellow
        styles["class-name"] = yellow
        styles["regex"] = yellow
        styles["variable"] = yellow // Variables often red in some, yellow in others. Using Yellow for const/var match.

        // Custom
        styles["string-interpolation"] = green
        styles["decorator"] = yellow // Like function/class
        styles["annotation"] = yellow
        styles["key"] = red // YAML key like tag/property
        styles["scalar"] = green

        // RUST / NASM / C++ Support (Tomorrow)
        styles["macro"] = blue
        styles["attribute"] = orange
        styles["lifetime"] = yellow
        styles["instruction"] = purple
        styles["register"] = orange
        styles["label"] = yellow

        // Extended (Tomorrow)
        styles["parameter"] = red
        styles["field"] = red
        styles["interface"] = yellow
        styles["doc-comment"] = comment
        styles["operator"] = aqua // Distinct
        styles["class-name"] = yellow

        // Property (JSON keys) -> Often Red or Blue. I'll use Red to match 'Tag' logic or Blue to match 'Function'.
        // Let's use Red for Property as it's structurally similar to HTML Attributes/Tags in coloring logic often.
        // Property (JSON keys) -> Often Red or Blue. I'll use Red to match 'Tag' logic or Blue to match 'Function'.
        // Let's use Red for Property as it's structuraly similar to HTML Attributes/Tags in coloring logic often.
        styles["property"] = red

        // Markdown Support (Tomorrow Night)
        styles["title"] = red       // Headers -> Red
        styles["list"] = yellow     // Bullets -> Yellow
        styles["bold"] = orange     // Bold -> Orange
        styles["italic"] = orange   // Italic -> Orange
        styles["code"] = green      // Inline Code -> Green
        styles["blockquote"] = comment // Quote -> Comment
        styles["url"] = blue        // Link -> Blue

        val bg = getColor(context, com.example.llama.R.color.prism_tomorrow_bg)
        // Text defined at top
        return SimpleTheme(bg, baseTextGray, styles)
    }

    private fun createFunkyTheme(context: android.content.Context): PrismThemeWithColor {
        val styles = HashMap<String, Int>()
        val pink = getColor(context, com.example.llama.R.color.prism_funky_pink)
        val blue = getColor(context, com.example.llama.R.color.prism_funky_blue)
        val orange = getColor(context, com.example.llama.R.color.prism_funky_orange)
        val yellow = getColor(context, com.example.llama.R.color.prism_funky_yellow)
        val green = getColor(context, com.example.llama.R.color.prism_funky_green)
        val purple = getColor(context, com.example.llama.R.color.prism_funky_purple)
        // User Request: Fail-Safe Gray for consistency
        val baseTextGray = getColor(context, com.example.llama.R.color.prism_code_text_safe)
        val comment = getColor(context, com.example.llama.R.color.prism_funky_comment)

        styles["comment"] = comment
        styles["prolog"] = comment
        styles["doctype"] = comment
        styles["cdata"] = comment
        styles["punctuation"] = baseTextGray

        styles["property"] = blue
        styles["tag"] = blue
        styles["constant"] = blue
        styles["symbol"] = blue
        styles["deleted"] = pink

        styles["boolean"] = pink
        styles["number"] = pink
        styles["selector"] = pink
        styles["attr-name"] = pink
        styles["string"] = yellow
        styles["char"] = yellow
        styles["builtin"] = yellow
        styles["inserted"] = green

        styles["operator"] = orange
        styles["entity"] = orange
        styles["url"] = orange

        styles["keyword"] = blue
        styles["atrule"] = yellow
        styles["attr-value"] = yellow
        styles["function"] = yellow
        styles["class-name"] = yellow
        styles["decorator"] = yellow

        styles["regex"] = yellow
        styles["important"] = orange
        styles["variable"] = orange
        styles["annotation"] = orange

        styles["namespace"] = purple

        // Markdown Support (Funky)
        styles["title"] = pink
        styles["list"] = yellow
        styles["bold"] = blue
        styles["italic"] = blue
        styles["code"] = green
        styles["blockquote"] = comment
        styles["url"] = orange
        styles["prolog"] = purple

        // RUST / NASM / C++ Support (Funky)
        styles["macro"] = yellow
        styles["attribute"] = pink
        styles["lifetime"] = orange
        styles["instruction"] = blue
        styles["register"] = orange
        styles["label"] = yellow

        // Extended (Funky)
        styles["parameter"] = orange
        styles["field"] = blue
        styles["interface"] = yellow
        styles["doc-comment"] = comment
        styles["operator"] = orange // Distinct from Blue keyword
        styles["class-name"] = yellow

        val bg = getColor(context, com.example.llama.R.color.prism_funky_bg)
        return SimpleTheme(bg, baseTextGray, styles)
    }
}
