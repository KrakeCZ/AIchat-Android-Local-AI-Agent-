package com.example.llama.managers.plugins

import android.content.Context
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin

/**
 * Provider for LaTeX Math formula rendering.
 * Enables rendering of mathematical formulas using LaTeX syntax.
 * 
 * Example:
 * Inline: $E=mc^2$
 * Block: $$\frac{1}{2}mv^2$$
 */
object LaTeXPluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_latex"
    override val displayName = "LaTeX Math (\$E=mc^2\$)"
    
    private const val LATEX_TEXT_SIZE_SP = 14f
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        return JLatexMathPlugin.create(LATEX_TEXT_SIZE_SP)
    }
}
