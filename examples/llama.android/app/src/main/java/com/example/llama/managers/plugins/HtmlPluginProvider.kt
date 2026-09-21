package com.example.llama.managers.plugins

import android.content.Context
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.html.HtmlPlugin

/**
 * Provider for HTML support in Markdown.
 * Allows basic HTML tags to be rendered within markdown content.
 * 
 * Example: <strong>Bold text</strong>, <em>Italic</em>
 */
object HtmlPluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_html"
    override val displayName = "HTML Support"
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        return HtmlPlugin.create()
    }
}
