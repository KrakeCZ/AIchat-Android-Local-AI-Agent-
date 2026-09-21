package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.method.LinkMovementMethod
import android.text.method.ArrowKeyMovementMethod
import kotlin.text.RegexOption
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.llama.managers.MarkdownFactory
import com.example.llama.managers.ThemeManager
import com.example.llama.ui.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android-AP-Master Optimized Message Entity
 */
/**
 * AttachmentType — typ přílohy v chatové zprávě.
 * NONE = žádná příloha (výchozí, zero alloc).
 */
enum class AttachmentType { NONE, IMAGE, VIDEO, AUDIO }

data class Message(
    val id: String,
    internal val contentBuilder: StringBuilder = StringBuilder(),
    val isUser: Boolean,
    var isStreaming: Boolean = false,
    // Attachment preview — nullable pro zero alloc při vypnutém media preview.
    // Bitmap je SDÍLENÝ s GC — žádný manuální recycle! (recykluje se v onViewRecycled)
    var attachmentBitmap: android.graphics.Bitmap? = null,
    var attachmentType: AttachmentType = AttachmentType.NONE
) {
    var content: String
        get() = contentBuilder.toString()
        set(value) {
            contentBuilder.setLength(0) // Optimized clear
            contentBuilder.append(value)
        }
    
    fun appendContent(chunk: String) {
        contentBuilder.append(chunk)
    }

    // Explicit content length accessor to avoid String allocation check
    val length: Int
        get() = contentBuilder.length
    
    fun copy(
        id: String = this.id,
        content: String = this.content,
        isUser: Boolean = this.isUser,
        isStreaming: Boolean = this.isStreaming
    ) = Message(id, StringBuilder(content), isUser, isStreaming,
        attachmentBitmap = this.attachmentBitmap, attachmentType = this.attachmentType)

    /** Uvolní bitmap přílohy pro snížení paměťové zátěže. Idempotentní. */
    fun recycleAttachment() {
        val bmp = attachmentBitmap
        if (bmp != null && !bmp.isRecycled) {
            bmp.recycle()
        }
        attachmentBitmap = null
    }
}

class MessageAdapter(
    private val messages: MutableList<Message>,
    private val context: Context,
    private val onMessageSpeakerClicked: (text: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var markwon = MarkdownFactory.create(context)

    var renderMarkdown: Boolean = false
    var enableBubbleCopy: Boolean = false
    var isTtsEnabled: Boolean = true
    var showThoughts: Boolean = false

    var chatStyle: String = "oem"
    private var currentSpeakingText: String? = null
    
    private val markdownRenderJobs = mutableMapOf<String, Job>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Refresh Markwon instance after settings changed.
     */
    fun refreshMarkwon() {
        markwon = MarkdownFactory.create(context)
        if (renderMarkdown && messages.isNotEmpty()) {
            notifyItemRangeChanged(0, messages.size, PAYLOAD_TEXT_UPDATE)
        }
    }

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        
        const val PAYLOAD_TEXT_UPDATE = "PAYLOAD_TEXT_UPDATE"
        const val PAYLOAD_TTS_UPDATE = "PAYLOAD_TTS_UPDATE"

        // PERFORMANCE THRESHOLD:
        // Pokud je text delší než tento limit (znaků) a streamuje se, vypneme Regex/Markdown pro plynulost UI.
        private const val HUGE_TEXT_THRESHOLD = 8192 

        // PRE-COMPILED REGEX PATTERNS
        private val REGEX_THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val REGEX_THINK_OPEN = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
        private val REGEX_TABLE_PIPES = Regex("\\|+")
        private val REGEX_HR = Regex("^[-=]{3,}$", RegexOption.MULTILINE)
        private val REGEX_BOLD = Regex("\\*\\*(.+?)\\*\\*")
        private val REGEX_ITALIC = Regex("\\*(.+?)\\*")
        private val REGEX_BOLD_UNDERSCORE = Regex("__(.+?)__")
        private val REGEX_ITALIC_UNDERSCORE = Regex("_(.+?)_")
        private val REGEX_STRIKETHROUGH = Regex("~~(.+?)~~")
        private val REGEX_HEADINGS = Regex("^#{1,6}\\s+", RegexOption.MULTILINE)
        private val REGEX_CODE_BLOCK = Regex("```[\\s\\S]*?```")
        private val REGEX_INLINE_CODE = Regex("`(.+?)`")
        private val REGEX_LINKS = Regex("\\[(.+?)\\]\\(.+?\\)")
        private val REGEX_LISTS = Regex("^[\\-\\*\\+]\\s+", RegexOption.MULTILINE)
        private val REGEX_TASK_LISTS = Regex("\\[[ xX]\\]\\s*")
        private val REGEX_WHITESPACE = Regex("\\s+")
        private val REGEX_BOLD_HEADER = Regex("^\\s*\\*\\*(.+?)\\*\\*\\s*$", RegexOption.MULTILINE)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_ASSISTANT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = layoutInflater.inflate(R.layout.item_message_user, parent, false)
            UserMessageViewHolder(view)
        } else {
            val view = layoutInflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val message = messages[position]
            for (payload in payloads) {
                when (payload) {
                    PAYLOAD_TEXT_UPDATE -> {
                        val textView = when (holder) {
                            is UserMessageViewHolder -> holder.messageText
                            is AssistantMessageViewHolder -> holder.messageText
                            else -> null
                        }
                        textView?.let { renderText(it, message) }
                    }
                    PAYLOAD_TTS_UPDATE -> {
                        if (holder is AssistantMessageViewHolder) {
                            updateSpeakerIcon(holder, message)
                        }
                    }
                }
            }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        val textView: TextView? = when (holder) {
            is UserMessageViewHolder -> holder.itemView.findViewById(R.id.msg_content)
            is AssistantMessageViewHolder -> holder.itemView.findViewById(R.id.msg_content)
            else -> null
        }

        val copyButton: ImageButton? = when (holder) {
            is UserMessageViewHolder -> holder.copyButton
            is AssistantMessageViewHolder -> holder.copyButton
            else -> null
        }

        val bubbleContainer = when (holder) {
            is UserMessageViewHolder -> holder.itemView.findViewById<View>(R.id.msg_content)?.parent as? View
            is AssistantMessageViewHolder -> holder.itemView.findViewById<View>(R.id.msg_content)?.parent as? View
            else -> null
        }
        
        val currentTheme = ThemeManager.getTheme(chatStyle)
        
        bubbleContainer?.let { 
            applyChatStyle(it, message.isUser, textView, currentTheme) 
        }

        if (holder is AssistantMessageViewHolder) {
            holder.speakerButton.visibility = if (isTtsEnabled) View.VISIBLE else View.GONE
            updateSpeakerIcon(holder, message)

            holder.speakerButton.setOnClickListener {
                val pos = holder.absoluteAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val plainText = stripMarkdownForTTS(messages[pos].content)
                    onMessageSpeakerClicked(plainText)
                }
            }
        }

        // ==================== ATTACHMENT PREVIEW ====================
        // Podmíněné zobrazení — pokud isMediaPreviewEnabled=false, ImageView zůstane GONE.
        // Bitmap reference je přímo v Message — recyklace v onViewRecycled.
        val attachmentView: ImageView? = when (holder) {
            is UserMessageViewHolder -> holder.attachmentPreview
            else -> null // Assistant bubliny nemají přílohy
        }
        val bmp = message.attachmentBitmap
        if (attachmentView != null) {
            if (bmp != null && !bmp.isRecycled) {
                attachmentView.setImageBitmap(bmp)
                attachmentView.visibility = View.VISIBLE
                // Audio ikona: menší výška
                val lp = attachmentView.layoutParams
                if (message.attachmentType == AttachmentType.AUDIO) {
                    lp.height = (48 * context.resources.displayMetrics.density).toInt()
                } else {
                    lp.height = (160 * context.resources.displayMetrics.density).toInt()
                }
                attachmentView.layoutParams = lp
            } else {
                attachmentView.setImageDrawable(null)
                attachmentView.visibility = View.GONE
            }
        }

        textView?.let { tv ->
            renderText(tv, message)

            if (enableBubbleCopy) {
                copyButton?.visibility = View.VISIBLE
                copyButton?.setOnClickListener {
                     copyTextToClipboard(message.content)
                }
            } else {
                copyButton?.visibility = View.GONE
                copyButton?.setOnClickListener(null)
            }
        }
    }

    override fun getItemCount(): Int = messages.size

    private fun applyChatStyle(container: View, isUser: Boolean, textView: TextView?, theme: AppTheme) {
        if (isUser) {
            container.setBackgroundResource(theme.userBubbleDrawable)
            textView?.setTextColor(theme.userBubbleTextColor)
        } else {
            container.setBackgroundResource(theme.assistantBubbleDrawable)
            textView?.setTextColor(theme.assistantBubbleTextColor)
        }
        textView?.typeface = theme.typeface
    }

    /**
     * CORE OPTIMIZATION: Robust Rendering Logic
     * Handles Massive Text Contexts by disabling Regex/Markdown during fast streaming.
     */
    private fun renderText(tv: TextView, message: Message) {
        val content = message.content
        val isStreaming = message.isStreaming
        val isHuge = content.length > HUGE_TEXT_THRESHOLD

        // 1. PERFORMANCE TWEAK: Set Simple Break Strategy for massive text
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (isHuge) {
                tv.breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
            } else {
                tv.breakStrategy = LineBreaker.BREAK_STRATEGY_HIGH_QUALITY
                tv.hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            }
        }

        // 2. CRITICAL OPTIMIZATION: Bypass Regex pipeline for streaming huge text
        // If text is > 8KB and streaming, simply show raw text. Regex on 50KB+ string every 50ms kills UI thread.
        if (isStreaming && isHuge) {
            tv.movementMethod = null // Disable movement method during fast stream
            tv.text = content // Always set content
            return
        }

        // 3. Normal Processing
        val preprocessedContent = preprocessMarkdown(content)
        val displayText = formatMessageContent(preprocessedContent)
        
        if (renderMarkdown) {
            if (isStreaming) {
                // STREAMING: Plain text immediately + Debounced Markdown
                tv.text = displayText
                
                markdownRenderJobs[message.id]?.cancel()
                markdownRenderJobs[message.id] = adapterScope.launch {
                    delay(300L) // Debounce
                    if (isActive) {
                        tv.movementMethod = LinkMovementMethod.getInstance()
                        markwon.setMarkdown(tv, displayText)
                    }
                }
            } else {
                // STATIC (Finished): Markdown Immediately
                tv.movementMethod = LinkMovementMethod.getInstance()
                markwon.setMarkdown(tv, displayText)
            }
        } else {
            tv.movementMethod = ArrowKeyMovementMethod.getInstance()
            tv.text = displayText
        }
    }

    private fun preprocessMarkdown(text: String): String {
        if (!text.contains("**")) return text
        return REGEX_BOLD_HEADER.replace(text, "### $1")
    }

    private fun updateSpeakerIcon(holder: AssistantMessageViewHolder, message: Message) {
        val isSpeakingThis = currentSpeakingText == message.content
        val iconRes = if (isSpeakingThis) R.drawable.ic_stop_24 else R.drawable.ic_speaker_24
        holder.speakerButton.setImageResource(iconRes)
        holder.speakerButton.alpha = if (isSpeakingThis) 1.0f else 0.7f
    }

    /**
     * Optimized partial update - only updates the internal string builder.
     * UI is notified via payload.
     */
    fun updateLastMessageContent(newContent: String) {
        if (messages.isEmpty()) return
        val lastIndex = messages.lastIndex
        val lastMsg = messages[lastIndex]
        
        // Only update if length differs significantly or strings don't match
        // to avoid unnecessary object copying for identical states
        if (lastMsg.length != newContent.length || lastMsg.content != newContent) {
            // IMPORTANT: We must update the contentBuilder with the new content
            // The copy() method creates a NEW Message with a NEW contentBuilder containing the string.
            messages[lastIndex] = lastMsg.copy(content = newContent)
            // Use PAYLOAD_TEXT_UPDATE to trigger efficient partial bind
            notifyItemChanged(lastIndex, PAYLOAD_TEXT_UPDATE)
        }
    }

    fun updateSpeakingState(speakingText: String?) {
        this.currentSpeakingText = speakingText
        notifyItemRangeChanged(0, messages.size, PAYLOAD_TTS_UPDATE)
    }

    private fun formatMessageContent(rawText: String): String {
         if (!rawText.contains("<think>")) return rawText

         if (showThoughts) {
            return rawText
                .replace("<think>", "> **\uD83E\uDDE0 Myšlenkový proces:**\n> ")
                .replace("</think>", "\n\n")
        }

        var processed = REGEX_THINK.replace(rawText, context.getString(R.string.info_thought_hidden))
        if (processed.contains("<think>")) {
            processed = REGEX_THINK_OPEN.replace(processed, context.getString(R.string.info_thinking))
        }
        return processed
    }
    
    private fun stripMarkdownForTTS(markdown: String): String {
        var text = formatMessageContent(markdown)
        text = REGEX_TABLE_PIPES.replace(text, " ")
        text = REGEX_HR.replace(text, "")
        text = REGEX_BOLD.replace(text, "$1")
        text = REGEX_ITALIC.replace(text, "$1")
        text = REGEX_BOLD_UNDERSCORE.replace(text, "$1")
        text = REGEX_ITALIC_UNDERSCORE.replace(text, "$1")
        text = REGEX_STRIKETHROUGH.replace(text, "$1")
        text = REGEX_HEADINGS.replace(text, "")
        text = REGEX_CODE_BLOCK.replace(text, " kód blok ")
        text = REGEX_INLINE_CODE.replace(text, "$1")
        text = REGEX_LINKS.replace(text, "$1")
        text = REGEX_LISTS.replace(text, "")
        text = REGEX_TASK_LISTS.replace(text, "")
        return REGEX_WHITESPACE.replace(text, " ").trim()
    }

    private fun copyTextToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AiChat", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, context.getString(R.string.msg_copied), Toast.LENGTH_SHORT).show()
    }

    fun appendTokenToLastMessage(token: String) {
        if (messages.isEmpty()) return
        val lastIndex = messages.lastIndex
        
        messages[lastIndex].appendContent(token)
        notifyItemChanged(lastIndex, PAYLOAD_TEXT_UPDATE)
    }
    
    fun cleanup() {
        markdownRenderJobs.values.forEach { it.cancel() }
        markdownRenderJobs.clear()
        adapterScope.cancel()
    }
    
    fun setMessageStreaming(messageId: String, isStreaming: Boolean) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            messages[index].isStreaming = isStreaming
            // Trigger final render when streaming stops (converts Raw -> Markdown)
            if (!isStreaming) {
                notifyItemChanged(index, PAYLOAD_TEXT_UPDATE)
            }
        }
    }
    
    override fun getItemId(position: Int): Long {
        return messages[position].id.hashCode().toLong()
    }

    /** Recyklace bitmap při scroll-out ViewHolderu — klíčové pro low RAM. */
    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is UserMessageViewHolder) {
            holder.attachmentPreview?.setImageDrawable(null) // Uvolní referenci ImageView→Bitmap
        }
    }

    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.msg_content)
        val copyButton: ImageButton = view.findViewById(R.id.action_copy_bubble)
        // Nullable — layout nemusí obsahovat tento element při budoucí refaktorizaci
        val attachmentPreview: ImageView? = view.findViewById(R.id.attachment_preview)
    }

    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.msg_content)
        val speakerButton: ImageButton = view.findViewById(R.id.action_speak)
        val copyButton: ImageButton = view.findViewById(R.id.action_copy_bubble)
    }
}
