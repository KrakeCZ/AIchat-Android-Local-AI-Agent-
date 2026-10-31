package com.example.llama.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.SubMenuAction
import com.example.llama.agent.perception.SubMenuActionEvent
import com.example.llama.agent.perception.UiAction
import com.example.llama.agent.perception.UiActionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Plovoucí kontextové menu zobrazené po dokončení Smart Select tahů.
 * Zobrazuje tlačítkový panel poblíž místa výběru.
 */
class ContextMenuToolbar(
    private val context: Context
) {
    companion object {
        private const val TAG = "ContextMenuToolbar"
        // Offset, aby menu nebylo přesně pod prstem a krylo obsah
        private const val OFFSET_Y_DP = 20
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var containerView: FrameLayout? = null
    private val isShowing = AtomicBoolean(false)
    
    // Uložená data pro odeslání akce
    private var currentText: String = ""
    private var currentBitmap: Bitmap? = null

    /**
     * Zobrazí toolbar asociovaný se specifickým výběrem.
     * x, y definují ideální startovací souřadnici ukotvení (např. spodní okraj Rect)
     */
    fun show(anchorRect: Rect, text: String, bitmap: Bitmap?) {
        mainHandler.post {
            if (isShowing.get()) {
                hideImmediate()
            }

            currentText = text
            currentBitmap = bitmap

            try {
                buildUiElements()
                val params = createLayoutParams(anchorRect)
                windowManager.addView(containerView, params)
                isShowing.set(true)
                Log.i(TAG, "✅ ContextMenuToolbar zobrazen na ose [Y=${anchorRect.bottom}]")
                
                // Navážeme subscription pro HIDE_CONTEXT_MENU external request
                subscribeToCancelEvent()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při zobrazení ContextMenuToolbaru", e)
                isShowing.set(false)
            }
        }
    }

    fun hide() {
        mainHandler.post { hideImmediate() }
    }

    private fun hideImmediate() {
        if (!isShowing.compareAndSet(true, false)) return
        try {
            if (containerView != null) {
                windowManager.removeView(containerView)
                containerView = null
            }
            Log.i(TAG, "✅ ContextMenuToolbar skryt.")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při skrývání Toolbaru", e)
        }
    }

    private fun subscribeToCancelEvent() {
        scope.launch {
            PerceptionEventBus.events.collect { event ->
                if (event is UiActionEvent && event.action == UiAction.HIDE_CONTEXT_MENU) {
                    hide()
                }
            }
        }
    }

    private fun buildUiElements() {
        // Hlavní wrapper pro zachycení klilnutí bokem
        containerView = FrameLayout(context).apply {
            // Tiché schování, pokud tapneme mimo menu
            setOnClickListener { hide() }
        }

        val toolbarGroup = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            
            // Premium background: Glassmorphism / Dark pill
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E01E1E1E")) // Semi-transparent dark
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"))
            }

            // Zachycení dotyku aby onclick neprobublal do kontejneru a nezavřel to
            setOnClickListener { /* Consume */ }
        }

        // Tlačítka
        val buttonSize = dpToPx(48)
        toolbarGroup.addView(createButton("🧠", SubMenuAction.SUMMARIZE, buttonSize))
        toolbarGroup.addView(createButton("🌐", SubMenuAction.TRANSLATE, buttonSize))
        toolbarGroup.addView(createButton("✏️", SubMenuAction.PROOFREAD, buttonSize))
        toolbarGroup.addView(createButton("✍️", SubMenuAction.REWRITE, buttonSize))
        toolbarGroup.addView(createButton("💜", SubMenuAction.SENTIMENT, buttonSize))
        toolbarGroup.addView(createButton("💬", SubMenuAction.SMART_REPLY, buttonSize))
        toolbarGroup.addView(createButton("🏷", SubMenuAction.EXTRACT_ENTITIES, buttonSize))
        toolbarGroup.addView(createButton("🔍", SubMenuAction.QA_SEARCH, buttonSize))
        toolbarGroup.addView(createButton("⚖️", SubMenuAction.NLI_CHECK, buttonSize))
        toolbarGroup.addView(createButton("🎭", SubMenuAction.MLM_FILL, buttonSize))
        toolbarGroup.addView(createButton("👁️", SubMenuAction.VISION_ANALYZE, buttonSize))

        // Ukřížení tlačítka dismiss
        val btnClose = createButton("✕", null, buttonSize).apply {
            setOnClickListener { 
                scope.launch {
                    PerceptionEventBus.emit(UiActionEvent(UiAction.HIDE_CONTEXT_MENU))
                }
                hide() 
            }
        }
        toolbarGroup.addView(btnClose)

        val wrapperParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        wrapperParams.gravity = Gravity.CENTER
        containerView?.addView(toolbarGroup, wrapperParams)
    }

    private fun createButton(iconEmoji: String, action: SubMenuAction?, size: Int): TextView {
        return TextView(context).apply {
            text = iconEmoji
            textSize = 20f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(dpToPx(4), 0, dpToPx(4), 0)
            }
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = size / 2f
            }
            
            setOnClickListener {
                if (action != null) {
                    // Flash effect (premium feel)
                    it.setBackgroundColor(Color.parseColor("#4285F4"))
                    mainHandler.postDelayed({ 
                        it.setBackgroundColor(Color.TRANSPARENT) 
                        dispatchAction(action)
                    }, 50)
                }
            }
        }
    }

    private fun dispatchAction(action: SubMenuAction) {
        scope.launch {
            Log.i(TAG, "🚀 Dispatching action z ContextToolbaru: $action")
            PerceptionEventBus.emit(SubMenuActionEvent(action, currentText, currentBitmap))
            // Action odeslána, můžeme schovat menu
            hide()
        }
    }

    private fun createLayoutParams(anchorRect: Rect): WindowManager.LayoutParams {
        val wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        // Positioning
        wmParams.gravity = Gravity.TOP or Gravity.START
        
        // Zkorigujeme ukotvení (střed X, dole Y)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val menuEstimatedWidth = dpToPx(6 * 56) // 6 tlačítek * (48+8 offset) px 
        
        var xIdeal = anchorRect.centerX() - (menuEstimatedWidth / 2)
        var yIdeal = anchorRect.bottom + dpToPx(OFFSET_Y_DP)
        
        // Zabráníme ořezání boků a spodku
        xIdeal = xIdeal.coerceIn(dpToPx(16), screenWidth - menuEstimatedWidth - dpToPx(16))
        val maxH = context.resources.displayMetrics.heightPixels
        if (yIdeal > maxH - dpToPx(80)) {
            // Pokud jsme příliš dole, hodíme to NAD výběr
            yIdeal = anchorRect.top - dpToPx(80) 
        }

        wmParams.x = xIdeal
        wmParams.y = yIdeal

        return wmParams
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
