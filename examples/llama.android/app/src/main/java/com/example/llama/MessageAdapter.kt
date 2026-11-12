package com.example.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
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
import com.arm.aichat.ThinkingProfile
import com.example.llama.managers.MarkdownFactory
import io.noties.markwon.Markwon
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
import kotlinx.coroutines.withContext

import android.text.style.URLSpan
import android.text.style.ClickableSpan
import android.text.TextPaint
import com.example.llama.agent.tools.web.WebSearchResult

/**
 * Android-AP-Master Optimized Message Entity
 */
/**
 * AttachmentType — typ přílohy v chatové zprávě.
 * NONE = žádná příloha (výchozí, zero alloc).
 */
enum class AttachmentType { NONE, IMAGE, VIDEO, AUDIO }

data class SearchReport(
    val query: String,
    val results: List<WebSearchResult>,
    var isExpanded: Boolean = false
)

data class Message(
    val id: String,
    internal val contentBuilder: StringBuilder = StringBuilder(),
    val isUser: Boolean,
    var isStreaming: Boolean = false,
    // Attachment preview — nullable pro zero alloc při vypnutém media preview.
    // Bitmap je SDÍLENÝ s GC — žádný manuální recycle! (recykluje se v onViewRecycled)
    var attachmentBitmap: android.graphics.Bitmap? = null,
    var attachmentType: AttachmentType = AttachmentType.NONE,
    internal val reasoningBuilder: StringBuilder = StringBuilder(),
    val searchReports: MutableList<SearchReport> = mutableListOf(),
    var isThoughtsExpanded: Boolean = true,
    var isThoughtsCollapsedByUser: Boolean = false,
    var translatedContent: String? = null,
    var translatedReasoning: String? = null,
    var isTranslationShown: Boolean = false,
    var sourceLanguageCode: String? = null,
    var targetLanguageCode: String? = null,
    var isTranslating: Boolean = false
) {
    var content: String
        get() = contentBuilder.toString()
        set(value) {
            contentBuilder.setLength(0) // Optimized clear
            contentBuilder.append(value)
        }

    var reasoning: String
        get() = reasoningBuilder.toString()
        set(value) {
            reasoningBuilder.setLength(0)
            reasoningBuilder.append(value)
        }

    fun appendContent(chunk: String) {
        contentBuilder.append(chunk)
    }

    fun appendReasoning(chunk: String) {
        reasoningBuilder.append(chunk)
    }

    val hasReasoning: Boolean
        get() = reasoningBuilder.isNotEmpty()

    // Explicit content length accessor to avoid String allocation check
    val length: Int
        get() = contentBuilder.length + reasoningBuilder.length

    fun copy(
        id: String = this.id,
        content: String = this.content,
        isUser: Boolean = this.isUser,
        isStreaming: Boolean = this.isStreaming,
        reasoning: String = this.reasoning,
        searchReports: MutableList<SearchReport> = this.searchReports.toMutableList(),
        isThoughtsExpanded: Boolean = this.isThoughtsExpanded,
        isThoughtsCollapsedByUser: Boolean = this.isThoughtsCollapsedByUser,
        translatedContent: String? = this.translatedContent,
        translatedReasoning: String? = this.translatedReasoning,
        isTranslationShown: Boolean = this.isTranslationShown,
        sourceLanguageCode: String? = this.sourceLanguageCode,
        targetLanguageCode: String? = this.targetLanguageCode,
        isTranslating: Boolean = this.isTranslating
    ) = Message(
        id = id,
        contentBuilder = StringBuilder(content),
        isUser = isUser,
        isStreaming = isStreaming,
        attachmentBitmap = this.attachmentBitmap,
        attachmentType = this.attachmentType,
        reasoningBuilder = StringBuilder(reasoning),
        searchReports = searchReports,
        isThoughtsExpanded = isThoughtsExpanded,
        isThoughtsCollapsedByUser = isThoughtsCollapsedByUser,
        translatedContent = translatedContent,
        translatedReasoning = translatedReasoning,
        isTranslationShown = isTranslationShown,
        sourceLanguageCode = sourceLanguageCode,
        targetLanguageCode = targetLanguageCode,
        isTranslating = isTranslating
    )

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

    private fun createMarkwonInstance(): Markwon {
        return MarkdownFactory.create(context) { view, link ->
            if (link.startsWith("app://toggle_thoughts/")) {
                val msgId = link.removePrefix("app://toggle_thoughts/")
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx != -1) {
                    val currentlyShowing = if (messages[idx].isThoughtsCollapsedByUser) {
                        false
                    } else {
                        showThoughts || messages[idx].isThoughtsExpanded
                    }
                    if (currentlyShowing) {
                        messages[idx].isThoughtsCollapsedByUser = true
                        messages[idx].isThoughtsExpanded = false
                    } else {
                        messages[idx].isThoughtsCollapsedByUser = false
                        messages[idx].isThoughtsExpanded = true
                    }
                    notifyItemChanged(idx)
                }
            } else if (link.startsWith("app://toggle_sources/")) {
                val msgId = link.removePrefix("app://toggle_sources/")
                val idx = messages.indexOfFirst { it.id == msgId }
                if (idx != -1) {
                    val msg = messages[idx]
                    val newState = !msg.searchReports.any { it.isExpanded }
                    msg.searchReports.forEach { it.isExpanded = newState }
                    notifyItemChanged(idx)
                }
            } else {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.w("MessageAdapter", "Cannot open link: $link", e)
                }
            }
        }
    }

    private var markwon = createMarkwonInstance()

    var renderMarkdown: Boolean = false
    var enableBubbleCopy: Boolean = false
    var enableBubbleTranslate: Boolean = true

    private val nlpTextProcessor by lazy { com.example.llama.agent.perception.NlpTextProcessor(context.applicationContext) }
    private val settingsManager by lazy { com.example.llama.SettingsManager(context.applicationContext) }
    var isTtsEnabled: Boolean = true
    var showThoughts: Boolean = true
    var thinkingProfile: ThinkingProfile = ThinkingProfile.DEEP

    var chatStyle: String = "oem"
    var fontSizeSp: Float = 15.0f
        set(value) {
            val clamped = value.coerceIn(11.0f, 34.0f)
            if (field != clamped) {
                field = clamped
                notifyItemRangeChanged(0, messages.size, PAYLOAD_TEXT_UPDATE)
            }
        }
    private var currentSpeakingText: String? = null
    
    private val markdownRenderJobs = mutableMapOf<String, Job>()
    private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Refresh Markwon instance after settings changed.
     */
    fun refreshMarkwon() {
        markwon = createMarkwonInstance()
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
                        bindBubbleActions(holder, message)
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
        textView?.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSizeSp)

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
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val msg = messages[pos]
                    val textToSpeak = if (msg.isTranslationShown && !msg.translatedContent.isNullOrBlank()) {
                        msg.translatedContent!!
                    } else {
                        msg.content
                    }
                    val plainText = stripMarkdownForTTS(textToSpeak)
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
        }
        bindBubbleActions(holder, message)
    }

    private fun bindBubbleActions(holder: RecyclerView.ViewHolder, message: Message) {
        val copyButton: ImageButton? = when (holder) {
            is UserMessageViewHolder -> holder.copyButton
            is AssistantMessageViewHolder -> holder.copyButton
            else -> null
        }
        val translateButton: ImageButton? = when (holder) {
            is UserMessageViewHolder -> holder.translateButton
            is AssistantMessageViewHolder -> holder.translateButton
            else -> null
        }
        val translationInfo: TextView? = when (holder) {
            is UserMessageViewHolder -> holder.translationInfo
            is AssistantMessageViewHolder -> holder.translationInfo
            else -> null
        }

        val isStreaming = message.isStreaming
        val hasContent = message.content.isNotEmpty() || message.reasoning.isNotEmpty()
        val hasActiveTranslation = message.isTranslationShown &&
                (!message.translatedContent.isNullOrBlank() || !message.translatedReasoning.isNullOrBlank())

        if (enableBubbleCopy && hasContent && !isStreaming) {
            copyButton?.visibility = View.VISIBLE
            copyButton?.setOnClickListener {
                val textToCopy = if (hasActiveTranslation) {
                    val trContent = message.translatedContent ?: ""
                    val trReasoning = message.translatedReasoning
                    if (!trReasoning.isNullOrBlank()) {
                        if (trContent.isNotBlank()) "<think>\n$trReasoning\n</think>\n\n$trContent" else "<think>\n$trReasoning\n</think>"
                    } else {
                        trContent
                    }
                } else {
                    message.content
                }
                copyTextToClipboard(textToCopy)
            }
        } else {
            copyButton?.visibility = View.GONE
            copyButton?.setOnClickListener(null)
        }

        if (enableBubbleTranslate && hasContent && !isStreaming) {
            translateButton?.visibility = View.VISIBLE
            if (hasActiveTranslation) {
                translateButton?.setColorFilter(android.graphics.Color.parseColor("#00E5FF"))
            } else if (message.isTranslating) {
                translateButton?.setColorFilter(android.graphics.Color.parseColor("#FFD54F"))
            } else {
                translateButton?.clearColorFilter()
            }

            translateButton?.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    handleTranslateClick(messages[pos], pos)
                }
            }

            translateButton?.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    showLanguageSelectionDialog(messages[pos], pos)
                }
                true
            }
        } else {
            translateButton?.visibility = View.GONE
            translateButton?.setOnClickListener(null)
            translateButton?.setOnLongClickListener(null)
        }

        if (hasActiveTranslation) {
            val src = message.sourceLanguageCode?.uppercase() ?: "AUTO"
            val tgt = message.targetLanguageCode?.uppercase() ?: "CS"
            translationInfo?.text = "🌐 Přeloženo ($src ➔ $tgt)"
            translationInfo?.visibility = View.VISIBLE
        } else if (message.isTranslating) {
            translationInfo?.text = "⏳ Překládám..."
            translationInfo?.visibility = View.VISIBLE
        } else {
            translationInfo?.visibility = View.GONE
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
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
        val isStreaming = message.isStreaming
        val hasActiveTranslation = message.isTranslationShown &&
                (!message.translatedContent.isNullOrBlank() || !message.translatedReasoning.isNullOrBlank())
        val displayLength = if (hasActiveTranslation) {
            (message.translatedContent?.length ?: 0) + (message.translatedReasoning?.length ?: 0)
        } else {
            message.length
        }
        val isHuge = displayLength > HUGE_TEXT_THRESHOLD

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

        // Always enable linksClickable and LinkMovementMethod so ClickableSpan and URLSpan work seamlessly!
        tv.linksClickable = true
        tv.movementMethod = LinkMovementMethod.getInstance()

        // 2. CRITICAL OPTIMIZATION: Streaming is rendered with fast Spannable immediately (Italic reasoning + Normal content)
        if (isStreaming) {
            markdownRenderJobs[message.id]?.cancel()
            tv.text = formatStreamingSpannable(message)
            return
        }

        // 3. FINISHED (Generation completed): Render full Markwon Markdown
        if (renderMarkdown) {
            val markdownText = formatFinishedMarkdown(message)
            markwon.setMarkdown(tv, markdownText)
        } else {
            tv.text = formatStreamingSpannable(message)
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
        
        if (lastMsg.length != newContent.length || lastMsg.content != newContent) {
            messages[lastIndex] = lastMsg.copy(content = newContent)
            notifyItemChanged(lastIndex, PAYLOAD_TEXT_UPDATE)
        }
    }

    fun appendReasoningToLastMessage(chunk: String) {
        if (messages.isEmpty()) return
        val lastIndex = messages.lastIndex
        messages[lastIndex].appendReasoning(chunk)
        notifyItemChanged(lastIndex, PAYLOAD_TEXT_UPDATE)
    }

    fun updateSpeakingState(speakingText: String?) {
        this.currentSpeakingText = speakingText
        notifyItemRangeChanged(0, messages.size, PAYLOAD_TTS_UPDATE)
    }

    /**
     * Extracts reasoning and user-facing content into clean separate channels,
     * robustly handling embedded <think> tags and explicit Czech/English answer transitions.
     */
    fun extractReasoningAndContent(message: Message, explicitContent: String? = null): Pair<String, String> {
        var reasoning = message.reasoning.trim()
        var content = (explicitContent ?: message.content).trim()

        // 1. Check if model emitted reasoning termination inside reasoning channel
        if (reasoning.isNotEmpty()) {
            val closeTags = listOf("</think>", "[/THINK]", "</thought>", "<end_of_thought>", "<|channel|>", "<channel|>", "</channel>")
            for (tag in closeTags) {
                if (reasoning.contains(tag)) {
                    val parts = reasoning.split(tag, limit = 2)
                    reasoning = parts[0].trim()
                    val leaked = parts[1].trim()
                    if (leaked.isNotEmpty()) {
                        content = if (content.isNotEmpty() && !content.startsWith(leaked)) "$leaked\n\n$content" else if (content.isEmpty()) leaked else content
                    }
                    break
                }
            }
        }

        // 2. Check if content contains legacy inline CoT tags from models (e.g. Qwen, Mistral, Gemma, MoonAI, Phi)
        val cotTagPairs = listOf(
            Pair("<think>", "</think>"),
            Pair("[THINK]", "[/THINK]"),
            Pair("[THOUGHT]", "[/THOUGHT]"),
            Pair("<thought>", "</thought>"),
            Pair("<start_of_thought>", "<end_of_thought>"),
            Pair("<|channel|>thought", "<channel|>"),
            Pair("<|channel|>thought", "<|channel|>"),
            Pair("<|channel|>thought", "</channel>"),
            Pair("<channel>thought", "<channel|>"),
            Pair("<channel>thought", "</channel>"),
            Pair("<|thought|>", "<|thought|>")
        )
        for ((openTag, closeTag) in cotTagPairs) {
            if (content.contains(openTag)) {
                val thinkStart = content.indexOf(openTag)
                val thinkEnd = content.indexOf(closeTag)
                if (thinkEnd != -1 && thinkEnd > thinkStart) {
                    val extractedThink = content.substring(thinkStart + openTag.length, thinkEnd).trim()
                    val rest = (content.substring(0, thinkStart) + "\n" + content.substring(thinkEnd + closeTag.length)).trim()
                    reasoning = if (reasoning.isNotEmpty()) "$reasoning\n$extractedThink" else extractedThink
                    content = rest
                } else if (thinkEnd == -1) {
                    val extractedThink = content.substring(thinkStart + openTag.length).trim()
                    val rest = content.substring(0, thinkStart).trim()
                    reasoning = if (reasoning.isNotEmpty()) "$reasoning\n$extractedThink" else extractedThink
                    content = rest
                }
                break
            } else if (content.contains(closeTag)) {
                val parts = content.split(closeTag, limit = 2)
                val extractedThink = parts[0].trim()
                val rest = parts[1].trim()
                reasoning = if (reasoning.isNotEmpty()) "$reasoning\n$extractedThink" else extractedThink
                content = rest
                break
            }
        }

        // 3. Fallback pro přirozený myšlenkový proces (např. Gemma / Llama uvažování v textu bez formálních tagů)
        if (reasoning.isEmpty() && content.isNotEmpty()) {
            val thoughtStartMarkers = listOf(
                "Wait, ", "Wait...", "Wait\n",
                "Thinking Process:", "Thinking process:",
                "Plan for Content:", "Plan for content:",
                "Thought Process:", "Thought process:"
            )
            val startsWithThought = thoughtStartMarkers.any { content.startsWith(it, ignoreCase = true) }
            if (startsWithThought) {
                val transitionMarkers = listOf(
                    "\n\nAhoj", "\n\nZdravím", "\n\nZde je", "\n\nJistě", "\n\nJasně",
                    "\n\n# ", "\n\n## ", "\n\n### ",
                    "\n\nHere is", "\n\nSure,", "\n\nHello"
                )
                var bestSplitIdx = -1
                for (trans in transitionMarkers) {
                    val idx = content.indexOf(trans, ignoreCase = true)
                    if (idx != -1 && (bestSplitIdx == -1 || idx < bestSplitIdx)) {
                        bestSplitIdx = idx
                    }
                }
                if (bestSplitIdx != -1) {
                    val extractedThink = content.substring(0, bestSplitIdx).trim()
                    val rest = content.substring(bestSplitIdx).trim()
                    reasoning = extractedThink
                    content = rest
                }
            }
        }

        return Pair(reasoning, content)
    }

    /**
     * RAW streaming representation: clean, fast, instant text without full markdown parse overhead,
     * but richly stylized with Spannables so reasoning is clearly rendered in ITALIC (kurzíva)
     * and the assistant response is rendered in standard NORMAL font!
     */
    fun formatStreamingSpannable(message: Message, explicitContent: String? = null): CharSequence {
        val (rawReasoning, rawContent) = extractReasoningAndContent(message, explicitContent)
        val hasActiveTranslation = message.isTranslationShown &&
                (!message.translatedContent.isNullOrBlank() || !message.translatedReasoning.isNullOrBlank())

        val reasoning = if (hasActiveTranslation && message.translatedReasoning != null) {
            message.translatedReasoning!!
        } else {
            rawReasoning
        }
        val content = if (hasActiveTranslation && message.translatedContent != null) {
            message.translatedContent!!
        } else {
            rawContent
        }

        if (reasoning.isEmpty() && content.isEmpty() && message.searchReports.isEmpty()) {
            if (thinkingProfile == ThinkingProfile.OFF) {
                return SpannableStringBuilder("...")
            }
            val placeholder = if (showThoughts) "🧠 Přemýšlím..." else "*[${context.getString(R.string.info_thinking)}]*"
            val ssb = SpannableStringBuilder(placeholder)
            ssb.setSpan(StyleSpan(Typeface.ITALIC), 0, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return ssb
        }

        val ssb = SpannableStringBuilder()

        // 1. Web Search Reports (Expandable / Collapsible sources)
        if (message.searchReports.isNotEmpty()) {
            message.searchReports.forEach { report ->
                if (ssb.isNotEmpty()) ssb.append("\n\n")

                val headerStart = ssb.length
                val headerPrefix = "🔍 Prohledán web: \"${report.query}\" (${report.results.size} zdrojů)  "
                ssb.append(headerPrefix)
                ssb.setSpan(StyleSpan(Typeface.BOLD), headerStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                val toggleText = if (report.isExpanded) "[▲ Skrýt zdroje]" else "[▼ Rozbalit zdroje]"
                val toggleStart = ssb.length
                ssb.append(toggleText)
                ssb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        report.isExpanded = !report.isExpanded
                        val idx = messages.indexOfFirst { it.id == message.id }
                        if (idx != -1) notifyItemChanged(idx, PAYLOAD_TEXT_UPDATE)
                    }
                    override fun updateDrawState(ds: TextPaint) {
                        super.updateDrawState(ds)
                        ds.isUnderlineText = false
                        ds.color = 0xFF1976D2.toInt() // Blue link color
                    }
                }, toggleStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                if (report.isExpanded) {
                    if (report.results.isEmpty()) {
                        ssb.append("\n  ⚠️ Žádné ověřené zdroje nebyly pro tento dotaz nalezeny.")
                    } else {
                        report.results.forEachIndexed { i, res ->
                            ssb.append("\n  ${i + 1}. ")
                            val titleStart = ssb.length
                            ssb.append(res.title)
                            ssb.setSpan(URLSpan(res.url), titleStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            ssb.setSpan(StyleSpan(Typeface.BOLD), titleStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

                            if (res.snippet.isNotBlank()) {
                                ssb.append("\n     ")
                                val snipStart = ssb.length
                                ssb.append(res.snippet)
                                ssb.setSpan(StyleSpan(Typeface.ITALIC), snipStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                    }
                }
            }
        }

        // 2. CoT Reasoning Process (Expandable / Collapsible thinking - collapsed by default)
        if (reasoning.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n\n")

            val effectiveShowThoughts = if (message.isThoughtsCollapsedByUser) {
                false
            } else {
                showThoughts || message.isThoughtsExpanded || message.isStreaming
            }
            val wordsCount = reasoning.trim().split(Regex("""\s+""")).count { it.isNotBlank() }

            val thoughtHeaderStart = ssb.length
            val thoughtTitle = if (message.isStreaming && content.isEmpty()) {
                "🧠 Přemýšlím... ($wordsCount slov):  "
            } else {
                "🧠 Myšlenkový proces ($wordsCount slov):  "
            }
            ssb.append(thoughtTitle)
            ssb.setSpan(StyleSpan(Typeface.BOLD), thoughtHeaderStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val toggleText = if (effectiveShowThoughts) "[▲ Skrýt myšlenky]" else "[▼ Rozbalit myšlenky]"
            val toggleStart = ssb.length
            ssb.append(toggleText)
            ssb.setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val currentlyShowing = if (message.isThoughtsCollapsedByUser) {
                        false
                    } else {
                        showThoughts || message.isThoughtsExpanded || message.isStreaming
                    }
                    if (currentlyShowing) {
                        message.isThoughtsCollapsedByUser = true
                        message.isThoughtsExpanded = false
                    } else {
                        message.isThoughtsCollapsedByUser = false
                        message.isThoughtsExpanded = true
                    }
                    val idx = messages.indexOfFirst { it.id == message.id }
                    if (idx != -1) notifyItemChanged(idx, PAYLOAD_TEXT_UPDATE)
                }
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.isUnderlineText = false
                    ds.color = 0xFF7B1FA2.toInt() // Purple thought color
                }
            }, toggleStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            if (effectiveShowThoughts) {
                ssb.append("\n")
                val rStart = ssb.length
                ssb.append(reasoning)
                if (content.isEmpty() && message.isStreaming) {
                    ssb.append("\n...")
                }
                val rEnd = ssb.length
                ssb.setSpan(StyleSpan(Typeface.ITALIC), rStart, rEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // 3. Fallback / Placeholder when message is completely empty
        if (ssb.isEmpty() && content.isEmpty()) {
            if (thinkingProfile == ThinkingProfile.OFF) {
                return SpannableStringBuilder("...")
            }
            val placeholder = if (showThoughts) "🧠 Přemýšlím..." else "*[${context.getString(R.string.info_thinking)}]*"
            val emptySsb = SpannableStringBuilder(placeholder)
            emptySsb.setSpan(StyleSpan(Typeface.ITALIC), 0, emptySsb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return emptySsb
        }

        // 4. Content (Final Assistant Answer)
        if (content.isNotEmpty()) {
            if (ssb.isNotEmpty()) ssb.append("\n\n")

            if (renderMarkdown && !message.isStreaming) {
                try {
                    val mdSpanned = markwon.toMarkdown(preprocessMarkdown(content))
                    val contentStart = ssb.length
                    ssb.append(mdSpanned)
                    ssb.setSpan(StyleSpan(Typeface.NORMAL), contentStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } catch (e: Exception) {
                    val contentStart = ssb.length
                    ssb.append(content)
                    ssb.setSpan(StyleSpan(Typeface.NORMAL), contentStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            } else {
                // Parse Markdown links [Title](https://...) into clickable URLSpans
                val linkRegex = Regex("""\[([^\]]+)\]\((https?://[^\s\)]+)\)""")
                val matches = linkRegex.findAll(content).toList()
                if (matches.isNotEmpty()) {
                    var lastIndex = 0
                    val formattedContent = SpannableStringBuilder()
                    for (match in matches) {
                        formattedContent.append(content.substring(lastIndex, match.range.first))
                        val title = match.groupValues[1]
                        val url = match.groupValues[2]
                        val startSpan = formattedContent.length
                        formattedContent.append(title)
                        formattedContent.setSpan(URLSpan(url), startSpan, formattedContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        formattedContent.setSpan(StyleSpan(Typeface.BOLD), startSpan, formattedContent.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        lastIndex = match.range.last + 1
                    }
                    if (lastIndex < content.length) {
                        formattedContent.append(content.substring(lastIndex))
                    }
                    val contentStart = ssb.length
                    ssb.append(formattedContent)
                    ssb.setSpan(StyleSpan(Typeface.NORMAL), contentStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else {
                    val contentStart = ssb.length
                    ssb.append(content)
                    ssb.setSpan(StyleSpan(Typeface.NORMAL), contentStart, ssb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }

        return ssb
    }

    fun formatStreamingRaw(message: Message): String {
        return formatStreamingSpannable(message).toString()
    }

    /**
     * Finished markdown representation: formatted blockquote strictly for the reasoning block
     * with reasoning text styled in ITALIC (kurzíva), while the assistant answer remains
     * clean, unquoted standard markdown.
     */
    fun formatFinishedMarkdown(message: Message, explicitContent: String? = null): String {
        val (rawReasoning, rawContent) = extractReasoningAndContent(message, explicitContent)
        val hasActiveTranslation = message.isTranslationShown &&
                (!message.translatedContent.isNullOrBlank() || !message.translatedReasoning.isNullOrBlank())

        val reasoning = if (hasActiveTranslation && message.translatedReasoning != null) {
            message.translatedReasoning!!
        } else {
            rawReasoning
        }
        val content = if (hasActiveTranslation && message.translatedContent != null) {
            message.translatedContent!!
        } else {
            rawContent
        }
        val cleanContent = preprocessMarkdown(content)
        val sb = StringBuilder()

        if (message.searchReports.isNotEmpty()) {
            val isAnyExpanded = message.searchReports.any { it.isExpanded }
            val toggle = if (isAnyExpanded) "[▲ Skrýt zdroje](app://toggle_sources/${message.id})" else "[▼ Rozbalit zdroje](app://toggle_sources/${message.id})"
            message.searchReports.forEach { report ->
                sb.append("🔍 **Prohledán web:** \"${report.query}\" (${report.results.size} zdrojů)  $toggle\n\n")
                if (report.isExpanded) {
                    report.results.forEachIndexed { i, res ->
                        sb.append("${i + 1}. [${res.title}](${res.url})\n")
                        if (res.snippet.isNotBlank()) {
                            sb.append("   *${res.snippet}*\n")
                        }
                    }
                    sb.append("\n\n")
                }
            }
        }

        if (reasoning.isNotEmpty()) {
            val wordsCount = reasoning.trim().split(Regex("""\s+""")).count { it.isNotBlank() }
            val effectiveShow = if (message.isThoughtsCollapsedByUser) {
                false
            } else {
                showThoughts || message.isThoughtsExpanded
            }
            val toggle = if (effectiveShow) "[▲ Skrýt myšlenky](app://toggle_thoughts/${message.id})" else "[▼ Rozbalit myšlenky](app://toggle_thoughts/${message.id})"
            sb.append("🧠 **Myšlenkový proces ($wordsCount slov):**  $toggle\n\n")
            if (effectiveShow) {
                sb.append(">\n")
                reasoning.lines().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) {
                        sb.append(">\n")
                    } else {
                        sb.append("> *").append(trimmed).append("*\n")
                    }
                }
                sb.append("\n\n")
            }
        }

        if (cleanContent.isNotEmpty()) {
            sb.append(cleanContent)
        }

        return sb.toString().trimEnd()
    }

    fun formatMessageContent(message: Message, contentToUse: String = message.content): String {
        return if (renderMarkdown && !message.isStreaming) {
            formatFinishedMarkdown(message)
        } else {
            formatStreamingRaw(message)
        }
    }

    fun formatMessageContent(rawText: String): String {
        val dummyMsg = Message(id = "", contentBuilder = StringBuilder(rawText), isUser = false)
        return formatMessageContent(dummyMsg, rawText)
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

    private fun handleTranslateClick(message: Message, position: Int) {
        if (message.isStreaming) {
            Toast.makeText(context, "Počkejte na dokončení generování zprávy", Toast.LENGTH_SHORT).show()
            return
        }

        if (message.isTranslationShown) {
            message.isTranslationShown = false
            notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
            Toast.makeText(context, "Zobrazen originál", Toast.LENGTH_SHORT).show()
            return
        }

        val hasSavedTranslation = !message.translatedContent.isNullOrBlank() || !message.translatedReasoning.isNullOrBlank()
        if (hasSavedTranslation) {
            message.isTranslationShown = true
            notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
            val tgt = message.targetLanguageCode?.uppercase() ?: "CS"
            Toast.makeText(context, "Zobrazen překlad ($tgt)", Toast.LENGTH_SHORT).show()
            return
        }

        executeTranslation(message, position)
    }

    private fun sanitizeMarkdownTokens(text: String): String {
        var s = text
        // Fix spaces inside bold asterisks: ** text ** -> **text**
        s = Regex("""\*\*\s+([^\*]+?)\s+\*\*""").replace(s, "**$1**")
        // Fix spaces inside italic asterisks: * text * -> *text*
        s = Regex("""(?<!\*)\*\s+([^\*]+?)\s+\*(?!\*)""").replace(s, "*$1*")
        // Fix spaces inside backticks: ` code ` -> `code`
        s = Regex("""`\s+([^`]+?)\s+`""").replace(s, "`$1`")
        return s
    }

    private suspend fun translateMarkdownPreserving(
        markdown: String,
        sourceLang: String,
        targetLang: String,
        nlp: com.example.llama.agent.perception.NlpTextProcessor
    ): String {
        if (markdown.isBlank()) return markdown
        if (sourceLang.equals(targetLang, ignoreCase = true)) return markdown

        val lines = markdown.lines()
        val resultLines = mutableListOf<String>()

        var inCodeBlock = false
        var codeFenceMarker = "```"

        // Buffer for accumulating normal paragraph lines
        val paragraphBuffer = mutableListOf<String>()

        suspend fun flushParagraph() {
            if (paragraphBuffer.isEmpty()) return
            val joined = paragraphBuffer.joinToString(" ").trim()
            paragraphBuffer.clear()
            if (joined.isNotBlank()) {
                val translated = nlp.translateText(joined, sourceLang, targetLang)
                resultLines.add(sanitizeMarkdownTokens(translated))
            }
        }

        val headingRegex = Regex("""^(#{1,6}\s+)(.*)$""")
        val listRegex = Regex("""^([ \t]*([-*+]|\d+\.)(\s+\[[ xX]\])?\s+)(.*)$""")
        val quoteRegex = Regex("""^([ \t]*>\s*)(.*)$""")
        val hrRegex = Regex("""^[ \t]*([-*_]){3,}[ \t]*$""")

        for (line in lines) {
            val trimmed = line.trim()

            // 1. Code Fence check
            if (!inCodeBlock && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                flushParagraph()
                inCodeBlock = true
                codeFenceMarker = if (trimmed.startsWith("```")) "```" else "~~~"
                resultLines.add(line)
                continue
            }

            if (inCodeBlock) {
                resultLines.add(line)
                if (trimmed.startsWith(codeFenceMarker)) {
                    inCodeBlock = false
                }
                continue
            }

            // 2. Blank line -> flush paragraph and keep empty line
            if (trimmed.isEmpty()) {
                flushParagraph()
                resultLines.add("")
                continue
            }

            // 3. Horizontal Rule (---, ***, ___)
            if (hrRegex.matches(line)) {
                flushParagraph()
                resultLines.add(line)
                continue
            }

            // 4. Table Row (| Col 1 | Col 2 |)
            if (trimmed.startsWith("|") && trimmed.endsWith("|")) {
                flushParagraph()
                val cells = line.split("|")
                val translatedCells = cells.mapIndexed { idx, cell ->
                    if (idx == 0 || idx == cells.lastIndex) {
                        cell
                    } else if (cell.trim().matches(Regex("""^:?-+:?$"""))) {
                        cell
                    } else if (cell.isNotBlank()) {
                        val leadingSpace = cell.takeWhile { it == ' ' }
                        val trailingSpace = cell.takeLastWhile { it == ' ' }
                        val textToTr = cell.trim()
                        val tr = nlp.translateText(textToTr, sourceLang, targetLang)
                        "$leadingSpace${sanitizeMarkdownTokens(tr)}$trailingSpace"
                    } else {
                        cell
                    }
                }
                resultLines.add(translatedCells.joinToString("|"))
                continue
            }

            // 5. Heading (# Heading)
            val headingMatch = headingRegex.find(line)
            if (headingMatch != null) {
                flushParagraph()
                val prefix = headingMatch.groupValues[1]
                val text = headingMatch.groupValues[2].trim()
                val translated = if (text.isNotBlank()) {
                    nlp.translateText(text, sourceLang, targetLang)
                } else ""
                resultLines.add("$prefix${sanitizeMarkdownTokens(translated)}")
                continue
            }

            // 6. Blockquote (> Quote)
            val quoteMatch = quoteRegex.find(line)
            if (quoteMatch != null) {
                flushParagraph()
                val prefix = quoteMatch.groupValues[1]
                val text = quoteMatch.groupValues[2].trim()
                val translated = if (text.isNotBlank()) {
                    nlp.translateText(text, sourceLang, targetLang)
                } else ""
                resultLines.add("$prefix${sanitizeMarkdownTokens(translated)}")
                continue
            }

            // 7. List Item (- item, 1. item)
            val listMatch = listRegex.find(line)
            if (listMatch != null) {
                flushParagraph()
                val prefix = listMatch.groupValues[1]
                val text = listMatch.groupValues[4].trim()
                val translated = if (text.isNotBlank()) {
                    nlp.translateText(text, sourceLang, targetLang)
                } else ""
                resultLines.add("$prefix${sanitizeMarkdownTokens(translated)}")
                continue
            }

            // 8. Normal paragraph text line
            paragraphBuffer.add(line.trim())
        }

        // Flush any remaining paragraph
        flushParagraph()

        return resultLines.joinToString("\n")
    }

    private fun executeTranslation(message: Message, position: Int) {
        if (message.isTranslating) return

        val (origReasoning, origContent) = extractReasoningAndContent(message)
        val textForLangDetection = origContent.ifBlank { origReasoning }.ifBlank { message.content }.trim()
        if (textForLangDetection.isBlank()) {
            Toast.makeText(context, "Zpráva neobsahuje žádný text k překladu", Toast.LENGTH_SHORT).show()
            return
        }

        message.isTranslating = true
        notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
        Toast.makeText(context, "Překládám offline přes ML Kit...", Toast.LENGTH_SHORT).show()

        val desiredTargetLang = settingsManager.chatTranslateTargetLang.ifBlank { "cs" }

        adapterScope.launch(Dispatchers.IO) {
            try {
                // 1. Automatická detekce jazyka
                val detectedLang = nlpTextProcessor.identifyLanguage(textForLangDetection)

                // 2. Volba zdrojového a cílového jazyka
                val targetLang: String
                val sourceLang: String

                if (detectedLang != "und" && detectedLang.equals(desiredTargetLang, ignoreCase = true)) {
                    // Text je již v cílovém jazyce -> reverzujeme překlad (např. cs -> en)
                    targetLang = settingsManager.translateForeignLang.ifBlank { "en" }
                    sourceLang = detectedLang
                } else {
                    targetLang = desiredTargetLang
                    sourceLang = if (detectedLang != "und") detectedLang else (if (targetLang == "cs") "en" else "cs")
                }

                // 3. Spuštění ML Kit překladu se zachováním Markdown struktury a myšlenek
                val translatedContent = if (origContent.isNotBlank()) {
                    translateMarkdownPreserving(origContent, sourceLang, targetLang, nlpTextProcessor)
                } else null

                val translatedReasoning = if (origReasoning.isNotBlank()) {
                    translateMarkdownPreserving(origReasoning, sourceLang, targetLang, nlpTextProcessor)
                } else null

                withContext(Dispatchers.Main) {
                    message.isTranslating = false
                    val hasContentTr = !translatedContent.isNullOrBlank() && translatedContent != origContent
                    val hasReasoningTr = !translatedReasoning.isNullOrBlank() && translatedReasoning != origReasoning

                    if (hasContentTr || hasReasoningTr) {
                        message.translatedContent = translatedContent
                        message.translatedReasoning = translatedReasoning
                        message.sourceLanguageCode = sourceLang
                        message.targetLanguageCode = targetLang
                        message.isTranslationShown = true
                        notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
                        val avail = com.example.llama.agent.perception.NlpTextProcessor.getAvailableLanguages()
                        val targetName = avail[targetLang] ?: targetLang.uppercase()
                        Toast.makeText(context, "✅ Přeloženo do $targetName ($sourceLang ➔ $targetLang)", Toast.LENGTH_SHORT).show()
                    } else {
                        notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
                        Toast.makeText(context, "⚠️ Překlad se nezdařil nebo model $targetLang není stažen", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    message.isTranslating = false
                    notifyItemChanged(position, PAYLOAD_TEXT_UPDATE)
                    Toast.makeText(context, "Chyba překladu: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLanguageSelectionDialog(message: Message, position: Int) {
        val availableLangs = com.example.llama.agent.perception.NlpTextProcessor.getAvailableLanguages()

        // Prioritní jazyky na začátku pro rychlý výběr
        val priorityCodes = listOf("cs", "sk", "en", "de", "uk", "pl", "es", "fr", "it", "ru")
        val otherCodes = availableLangs.keys.filter { it !in priorityCodes }.sortedBy { availableLangs[it] }
        val allCodes = (priorityCodes.filter { it in availableLangs } + otherCodes).toTypedArray()
        val allNames = allCodes.map { code ->
            val name = availableLangs[code] ?: code
            if (code in priorityCodes) "⭐ $name ($code)" else "$name ($code)"
        }.toTypedArray()

        val currentTarget = settingsManager.chatTranslateTargetLang.ifBlank { "cs" }
        val checkedIndex = allCodes.indexOf(currentTarget).let { if (it >= 0) it else 0 }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
            .setTitle("Cílový jazyk překladu (ML Kit)")
            .setSingleChoiceItems(allNames, checkedIndex) { dialog, which ->
                val chosenCode = allCodes[which]
                val chosenName = availableLangs[chosenCode] ?: chosenCode
                settingsManager.chatTranslateTargetLang = chosenCode
                dialog.dismiss()
                Toast.makeText(context, "🌐 Cílový jazyk nastaven: $chosenName ($chosenCode)", Toast.LENGTH_SHORT).show()

                // Pokud zpráva není ve streamu, ihned přeložíme do nového jazyka
                if (!message.isStreaming && (message.content.isNotBlank() || message.reasoning.isNotBlank())) {
                    message.translatedContent = null
                    message.translatedReasoning = null
                    message.isTranslationShown = false
                    executeTranslation(message, position)
                }
            }
            .setNegativeButton("Zrušit", null)
            .show()
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
            // Trigger final render when streaming stops (converts Raw -> Markdown, binds actions)
            if (!isStreaming) {
                notifyItemChanged(index)
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
        val translateButton: ImageButton? = view.findViewById(R.id.action_translate_bubble)
        val translationInfo: TextView? = view.findViewById(R.id.tv_translation_info)
        // Nullable — layout nemusí obsahovat tento element při budoucí refaktorizaci
        val attachmentPreview: ImageView? = view.findViewById(R.id.attachment_preview)
    }

    class AssistantMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.msg_content)
        val speakerButton: ImageButton = view.findViewById(R.id.action_speak)
        val copyButton: ImageButton = view.findViewById(R.id.action_copy_bubble)
        val translateButton: ImageButton? = view.findViewById(R.id.action_translate_bubble)
        val translationInfo: TextView? = view.findViewById(R.id.tv_translation_info)
    }
}
