package com.example.llama.managers.plugins

import android.content.Context
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin

/**
 * Provider for Markdown Strikethrough plugin.
 * Enables rendering of strikethrough text using ~~text~~.
 * 
 * Example: ~~This text is crossed out~~
 */
object StrikethroughPluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_strikethrough"
    override val displayName = "Strikethrough (~~text~~)"
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        return StrikethroughPlugin.create()
    }
}
