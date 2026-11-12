package com.example.llama.managers.plugins.lexers


import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.syntax.Prism4jTheme
import org.commonmark.node.FencedCodeBlock

import android.content.SharedPreferences
import com.example.llama.managers.plugins.PrismLanguage

/**
 * A Hybrid Syntax Highlighter that uses:
 * 1. NativeLexer (Regex-free) for complex languages (C++, JSON, etc)
 * 2. Prism4j (Standard) for simple languages (HTML, Bash)
 */
class NativeSyntaxHighlightPlugin(
    private val theme: Prism4jTheme, // Kept for theme abstraction, but Prism4j logic removed
    private val prefs: SharedPreferences
) : AbstractMarkwonPlugin() {

    // Native Lexers (100% Coverage)
    private val cppLexer = CppLexer()
    private val pythonLexer = PythonLexer()
    private val jsLexer = JavascriptLexer()
    private val jsonLexer = JsonLexer()
    private val yamlLexer = YamlLexer()
    private val sqlLexer = SqlLexer()
    private val asmLexer = AsmLexer()

    // New Migration Lexers
    private val bashLexer = BashLexer()
    private val psLexer = PowerShellLexer()
    private val markupLexer = MarkupLexer()
    private val cssLexer = CssLexer()
    private val goLexer = GoLexer()
    private val rustLexer = RustLexer()
    private val csharpLexer = CSharpLexer()
    private val markdownLexer = MarkdownLexer()

    override fun configure(registry: MarkwonPlugin.Registry) {
        registry.require(CorePlugin::class.java)
    }

    override fun afterSetText(textView: TextView) {
        // No-op
    }

    private fun isLanguageEnabled(language: String): Boolean {
        val normalized = language.trim().lowercase(java.util.Locale.ROOT)
        // Find matching PrismLanguage entry
        val langEnum = PrismLanguage.entries.find {
            it.key == normalized || it.aliases.contains(normalized)
        }

        // If found, check prefs. Default to true if not found.
        return if (langEnum != null) {
            prefs.getBoolean("prism_lang_${langEnum.key}", true)
        } else {
            true
        }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.on(FencedCodeBlock::class.java) { visitor, block ->
             try {
                val code = block.literal
                val language = block.info ?: ""

                // 1. Visit the code block text so it appears
                visitor.ensureNewLine()
                visitor.builder().append('\n') // External Margin

                val start = visitor.length()
                visitor.builder().append('\n') // Internal Top Padding

                val codeStart = visitor.length()
                visitor.builder().append(code)
                val codeEnd = visitor.length()

                visitor.builder().append('\n') // Internal Bottom Padding
                val end = visitor.length()

                visitor.builder().append('\n') // External separation
                visitor.builder().append('\n')
                visitor.ensureNewLine()

                // DP Calculations for Layout
                val density = android.content.res.Resources.getSystem().displayMetrics.density
                val marginPx = (22 * density).toInt()
                val radiusPx = 8f * density

                // Indentation
                visitor.builder().setSpan(
                     android.text.style.LeadingMarginSpan.Standard(marginPx),
                     start,
                     end,
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Background
                val bgColor = theme.background()
                if (bgColor != 0) {
                     visitor.builder().setSpan(
                         RoundedCodeBlockSpan(theme.background(), start, end, radiusPx),
                         start,
                         end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }

                // Monospace
                visitor.builder().setSpan(
                    android.text.style.TypefaceSpan("monospace"),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )

                // Spacing Adjustments
                visitor.builder().setSpan(android.text.style.RelativeSizeSpan(0.38f), start, codeStart, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                visitor.builder().setSpan(android.text.style.RelativeSizeSpan(0.85f), codeStart, codeEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                visitor.builder().setSpan(android.text.style.RelativeSizeSpan(0.07f), codeEnd, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                // 3. Apply Highlighting (PURE NATIVE) - ONLY IF ENABLED
                if (isLanguageEnabled(language)) {
                    highlight(visitor, language, codeStart, code)
                }
              } catch (_: Throwable) {
                  // Fallback
              }
        }
    }

    private fun highlight(visitor: MarkwonVisitor, language: String, start: Int, code: String) {
        val builder = visitor.builder()
        val normalized = language.trim().lowercase(java.util.Locale.ROOT)

        try {
            // NATIVE ENGINE DIRECT ROUTING

            // 1. Assembly Heuristics (Priority)
            if (normalized.contains("asm") || normalized.contains("low") ||
                code.contains("section .data") || code.contains("global start")) {
                 applyNativeTokens(builder, start, code.length, asmLexer.tokenize(code))
                 return
            }

            when (normalized) {
                // C-Family
                "c", "h", "arduino" -> applyNativeTokens(builder, start, code.length, cppLexer.tokenize(code))
                "cpp", "c++", "hpp" -> applyNativeTokens(builder, start, code.length, cppLexer.tokenize(code))
                "java", "kotlin", "kt" -> applyNativeTokens(builder, start, code.length, cppLexer.tokenize(code)) // Kotlin/Java share enough C-syntax for now, or split later.
                "swift" -> applyNativeTokens(builder, start, code.length, cppLexer.tokenize(code)) // Swift fits C-style well enough for V1

                // Dedicated
                "python", "py", "py3" -> applyNativeTokens(builder, start, code.length, pythonLexer.tokenize(code))
                "javascript", "js", "typescript", "ts", "tsx", "jsx" -> applyNativeTokens(builder, start, code.length, jsLexer.tokenize(code))
                "json" -> applyNativeTokens(builder, start, code.length, jsonLexer.tokenize(code))
                "yaml", "yml" -> applyNativeTokens(builder, start, code.length, yamlLexer.tokenize(code))
                "sql" -> applyNativeTokens(builder, start, code.length, sqlLexer.tokenize(code))

                // New Migration
                "bash", "sh", "shell", "zsh" -> applyNativeTokens(builder, start, code.length, bashLexer.tokenize(code))
                "powershell", "ps", "ps1", "pwsh" -> applyNativeTokens(builder, start, code.length, psLexer.tokenize(code))
                "html", "xml", "svg", "markup", "mathml" -> applyNativeTokens(builder, start, code.length, markupLexer.tokenize(code))
                "css", "scss" -> applyNativeTokens(builder, start, code.length, cssLexer.tokenize(code))
                "go", "golang" -> applyNativeTokens(builder, start, code.length, goLexer.tokenize(code))
                "rust", "rs" -> applyNativeTokens(builder, start, code.length, rustLexer.tokenize(code))
                "csharp", "cs", "dotnet" -> applyNativeTokens(builder, start, code.length, csharpLexer.tokenize(code))

                // Markdown Support
                "markdown", "md" -> applyNativeTokens(builder, start, code.length, markdownLexer.tokenize(code))

                // Fallback for Plain Text
                "txt", "text" -> {
                     renderPlainText(visitor, start, code)
                }

                else -> {
                    // Unknown language? Try CppLexer as generic syntax highlighter?
                    // Often works better than nothing for unknown C-like languages.
                     applyNativeTokens(builder, start, code.length, cppLexer.tokenize(code))
                }
            }

        } catch (e: Throwable) {
             android.util.Log.e("NativeSyntax", "Error highlighting code", e)
             // Fail-Safe: Render as plain text if lexer crashes
             renderPlainText(visitor, start, code)
        }
    }

    private fun renderPlainText(visitor: MarkwonVisitor, start: Int, code: String) {
        val builder = visitor.builder()
        // Gap Logic will apply baseColor to the whole range since tokens list is empty
        applyNativeTokens(builder, start, code.length, emptyList())
    }

    private fun applyNativeTokens(builder: io.noties.markwon.SpannableBuilder, offset: Int, codeLen: Int, tokens: List<SyntaxToken>) {
        val maxLen = offset + codeLen
        val baseColor = theme.textColor()
        var lastEnd = offset

        for (token in tokens) {
            val tokenStart = offset + token.start
            val tokenEnd = offset + token.end

            // 1. Fill GAP with Base Color (if baseColor is set)
            if (baseColor != 0 && tokenStart > lastEnd) {
                 if (lastEnd >= 0 && tokenStart <= maxLen) {
                     builder.setSpan(
                         ForegroundColorSpan(baseColor),
                         lastEnd,
                         tokenStart,
                         Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                     )
                 }
            }

            // 2. Apply Token Color
            val color = getThemeColor(token.type)
            if (color != 0) {
                if (tokenStart >= 0 && tokenEnd <= maxLen && tokenStart < tokenEnd) {
                    builder.setSpan(
                        ForegroundColorSpan(color),
                        tokenStart,
                        tokenEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }

            // 3. Apply Token Type Styles (Bold/Italic)
            if (token.type == "bold" || token.type == "important" || token.type == "title") {
                 builder.setSpan(
                     android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                     tokenStart,
                     tokenEnd,
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                 )
            }
            if (token.type == "italic" || token.type == "comment") {
                 // Comments often look good in italics, matching some Prism themes
                 builder.setSpan(
                     android.text.style.StyleSpan(android.graphics.Typeface.ITALIC),
                     tokenStart,
                     tokenEnd,
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                 )
            }

            // 4. Strikethrough (Deleted/Strike)
            if (token.type == "deleted" || token.type == "strike") {
                 builder.setSpan(
                     android.text.style.StrikethroughSpan(),
                     tokenStart,
                     tokenEnd,
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                 )
            }

            // 5. Underline (Link/Url/Underline)
            if (token.type == "underline" || token.type == "link" || token.type == "url") {
                 builder.setSpan(
                     android.text.style.UnderlineSpan(),
                     tokenStart,
                     tokenEnd,
                     Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                 )
            }

            // Advance cursor
            if (tokenEnd > lastEnd) {
                lastEnd = tokenEnd
            }
        }

        // 3. Fill FINAL GAP with Base Color
        if (baseColor != 0 && lastEnd < maxLen) {
             builder.setSpan(
                 ForegroundColorSpan(baseColor),
                 lastEnd,
                 maxLen,
                 Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
             )
        }
    }


    // Reuse PrismTheme logic but manually map types
    private fun getThemeColor(type: String): Int {
        if (theme is com.example.llama.managers.plugins.PrismThemeWithColor) {
            return theme.getTokenColor(type)
        }
        return 0
    }

    // Custom Span for Rounded Background Box
    private class RoundedCodeBlockSpan(
        private val backgroundColor: Int,
        private val blockStart: Int,
        private val blockEnd: Int,
        private val radius: Float
    ) : android.text.style.LineBackgroundSpan {

        override fun drawBackground(
            c: android.graphics.Canvas,
            p: android.graphics.Paint,
            left: Int, right: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, lnum: Int
        ) {
            val oldColor = p.color
            p.color = backgroundColor

            val rectLeft = left.toFloat()
            // Extend right slightly to cover full width or add padding
            // Use logical density padding for width if possible, but hardcoded generic is fine for now
            // Or remove extension if LeadingMargin handles it.
            // Previous code used 1.2 * padding. We removed local padding var.
            // Let's us a safe default extension ~20px or better 0 if LeadingMargin spans full width.
            // Let's keep a small extension to avoid clipping cursor/selection handles.
            val rectRight = right.toFloat() + 20f

            // Base dimensions derived from line height
            val rectTop = top.toFloat()
            val rectBottom = bottom.toFloat()

            val isFirstLine = (start <= blockStart)
            val isLastLine = (end >= blockEnd)

            // NO MANUAL PADDING MODIFICATION of rectTop/rectBottom
            // The empty newlines at start/end provide the space.

            val rect = android.graphics.RectF(rectLeft, rectTop, rectRight, rectBottom)

            // 1. Draw Rounded Rect (Base)
            c.drawRoundRect(rect, radius, radius, p)

            // 2. Corner Patching
            if (!isFirstLine) {
                 c.drawRect(rectLeft, rectTop, rectRight, rectTop + radius, p)
            }
            if (!isLastLine) {
                 c.drawRect(rectLeft, rectBottom - radius, rectRight, rectBottom, p)
            }

            p.color = oldColor
        }
    }
}
