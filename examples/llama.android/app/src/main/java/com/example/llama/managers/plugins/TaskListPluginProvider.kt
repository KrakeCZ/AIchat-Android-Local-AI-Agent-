package com.example.llama.managers.plugins

import android.content.Context
import io.noties.markwon.MarkwonPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin

/**
 * Provider for Markdown Task Lists plugin.
 * Enables rendering of GitHub-style task lists.
 * 
 * Example:
 * - [x] Completed task
 * - [ ] Uncompleted task
 */
object TaskListPluginProvider : BasePluginProvider {
    override val preferenceKey = "markdown_enable_tasklist"
    override val displayName = "Task Lists (- [x])"
    
    override fun getPlugin(context: Context): MarkwonPlugin {
        return TaskListPlugin.create(context)
    }
}
