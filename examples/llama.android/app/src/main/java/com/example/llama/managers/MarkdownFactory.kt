package com.example.llama.managers

import android.content.Context
import androidx.preference.PreferenceManager
import io.noties.markwon.Markwon
import com.example.llama.managers.plugins.BasePluginProvider
import com.example.llama.managers.plugins.TablePluginProvider
import com.example.llama.managers.plugins.StrikethroughPluginProvider
import com.example.llama.managers.plugins.TaskListPluginProvider
import com.example.llama.managers.plugins.HtmlPluginProvider
import com.example.llama.managers.plugins.LaTeXPluginProvider
import com.example.llama.managers.plugins.SyntaxHighlightPluginProvider


/**
 * Factory class to create a powerful Markwon instance with modular plugin system.
 * Plugins can be enabled/disabled via SharedPreferences in Settings UI.
 * 
 * Supported features:
 * - Tables (GitHub Flavored Markdown)
 * - Strikethrough (~~text~~)
 * - Task Lists (`- [x]`)
 * - HTML Support
 * - LaTeX Math formulas ($E=mc^2$)
 * - Syntax Highlighting (27+ programming languages via Prism4j)
 */
object MarkdownFactory {

    /**
     * Create a Markwon instance with dynamically loaded plugins.
     * Only plugins that are enabled in SharedPreferences will be added.
     * 
     * @param context Android Context for accessing preferences and resources
     * @return Configured Markwon instance
     */
    fun create(context: Context, linkResolver: io.noties.markwon.LinkResolver? = null): Markwon {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val builder = Markwon.builder(context)
        
        if (linkResolver != null) {
            builder.usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
                override fun configureConfiguration(configurationBuilder: io.noties.markwon.MarkwonConfiguration.Builder) {
                    configurationBuilder.linkResolver(linkResolver)
                }
            })
        }
        
        // --- MASTER CONFIGURATION (Typography & Layout) ---
        // Explicitly configure Heading sizes and spacing *before* other plugins.
        // This ensures H1-H6 are properly scaled and spaced, fixing "flat text" issues.
        builder.usePlugin(object : io.noties.markwon.AbstractMarkwonPlugin() {
            override fun configureTheme(themeBuilder: io.noties.markwon.core.MarkwonTheme.Builder) {
                // 1. Heading Sizes (H1=2.0x, H2=1.5x, H3=1.4x ...)
                themeBuilder.headingTextSizeMultipliers(floatArrayOf(
                    2.00f, // H1
                    1.60f, // H2
                    1.40f, // H3 (Most common for sections like "3. Backend")
                    1.20f, // H4
                    1.10f, // H5
                    1.00f  // H6
                ))
                
                // 2. Heading Spacing (Bottom Margin)
                // Adds ~12dp space under headers to separate them from content
                val density = context.resources.displayMetrics.density
                themeBuilder.headingBreakHeight((12 * density).toInt())

                // 3. Block & List Margin: 28dp guarantees ordered list digits ("1. ", "10. ") have full space and never clip!
                themeBuilder.blockMargin((28 * density).toInt())
                themeBuilder.blockQuoteWidth((3 * density).toInt())
                themeBuilder.bulletWidth((4 * density).toInt())
            }
        })
        
        // List of all available plugin providers
        val providers: List<BasePluginProvider> = listOf(
            TablePluginProvider,
            StrikethroughPluginProvider,
            TaskListPluginProvider,
            HtmlPluginProvider,
            LaTeXPluginProvider,

            SyntaxHighlightPluginProvider
        )
        
        // Dynamically add only enabled plugins
        providers.forEach { provider ->
            if (provider.isEnabled(prefs)) {
                provider.getPlugin(context)?.let { plugin ->
                    builder.usePlugin(plugin)
                }
            }
        }
        
        return builder.build()
    }
}
