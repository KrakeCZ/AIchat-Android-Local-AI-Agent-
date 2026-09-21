package com.example.llama.agent.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.llama.R
import com.example.llama.SettingsManager

/**
 * Zodpovídá za zobrazování plovoucích vizuálních notifikací ("Bublin") pro agenta.
 * Zobrazuje stavové změny v tichosti např. při detekci nového obrazu nebo zvuku, 
 * bez narušení uživatelovy aktivity.
 */
class AgentNotifier(
    private val context: Context,
    private val settingsManager: SettingsManager
) {
    enum class NotifyType {
        WARNING, VISION, AUDIO
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var tvEmoji: TextView? = null
    private var tvText: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var isVisible = false

    init {
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initView()
    }

    private fun initView() {
        if (overlayView != null) return
        
        try {
            val inflater = LayoutInflater.from(context)
            overlayView = inflater.inflate(R.layout.layout_agent_notifier, null)
            tvEmoji = overlayView?.findViewById(R.id.tv_notifier_emoji)
            tvText = overlayView?.findViewById(R.id.tv_notifier_text)
            
            // Default skrytý
            overlayView?.alpha = 0f
            overlayView?.visibility = View.GONE
            
            applyLayoutParams()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyLayoutParams() {
        if (overlayView == null) return

        // 0=TopCenter, 1=TopLeft, 2=TopRight, 3=BotLeft, 4=BotRight
        val gravity = when (settingsManager.floatingNotifyPosition) {
            1 -> Gravity.TOP or Gravity.START
            2 -> Gravity.TOP or Gravity.END
            3 -> Gravity.BOTTOM or Gravity.START
            4 -> Gravity.BOTTOM or Gravity.END
            else -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val yOffset = if (gravity and Gravity.TOP == Gravity.TOP) {
            (48 * density).toInt() // Vyhnout se status baru
        } else {
            (48 * density).toInt() // Vyhnout se nav baru
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = gravity
        params.x = if (gravity and Gravity.CENTER_HORIZONTAL != Gravity.CENTER_HORIZONTAL) padding else 0
        params.y = yOffset

        try {
            if (overlayView?.windowToken == null) {
                windowManager?.addView(overlayView, params)
            } else {
                windowManager?.updateViewLayout(overlayView, params)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Bezpečné zrušení při vypnutí služby.
     */
    fun destroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            if (overlayView?.windowToken != null) {
                windowManager?.removeView(overlayView)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        overlayView = null
    }

    /**
     * Primární metoda pro odeslání požadavku na zobrazení notifikace.
     * Je thread-safe - přesouvá logiku do Main Threadu.
     */
    fun notify(type: NotifyType, emoji: String, text: String, durationMs: Long = 3000L) {
        if (!settingsManager.floatingNotifyEnabled) return

        // Kontrola uživatelských filtrů
        when (type) {
            NotifyType.WARNING -> if (!settingsManager.floatingNotifyShowWarnings) return
            NotifyType.VISION -> if (!settingsManager.floatingNotifyShowVision) return
            NotifyType.AUDIO -> if (!settingsManager.floatingNotifyShowAudio) return
        }

        mainHandler.post {
            showOnMainThread(emoji, text, durationMs)
        }
    }

    private fun showOnMainThread(emoji: String, text: String, durationMs: Long) {
        if (overlayView == null) initView()
        
        // Aktualizace gravity za pochodu, pokud by ji uživatel změnil
        applyLayoutParams()

        // Podle stylů: 0=Emoji+Text, 1=Emoji, 2=Text
        when (settingsManager.floatingNotifyStyle) {
            1 -> { // Pouze Emoji
                tvEmoji?.visibility = View.VISIBLE
                tvText?.visibility = View.GONE
            }
            2 -> { // Pouze Text
                tvEmoji?.visibility = View.GONE
                tvText?.visibility = View.VISIBLE
            }
            else -> { // Emoji + Text
                tvEmoji?.visibility = View.VISIBLE
                tvText?.visibility = View.VISIBLE
            }
        }

        tvEmoji?.text = emoji
        tvText?.text = text

        animateShow()

        hideRunnable?.let { mainHandler.removeCallbacks(it) }
        hideRunnable = Runnable { animateHide() }
        mainHandler.postDelayed(hideRunnable!!, durationMs)
    }

    private fun animateShow() {
        if (isVisible) return
        isVisible = true
        overlayView?.visibility = View.VISIBLE

        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.8f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.8f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)

        ObjectAnimator.ofPropertyValuesHolder(overlayView, scaleX, scaleY, alpha).apply {
            duration = 200
            start()
        }
    }

    private fun animateHide() {
        if (!isVisible) return
        isVisible = false

        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 0.8f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 0.8f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f)

        ObjectAnimator.ofPropertyValuesHolder(overlayView, scaleX, scaleY, alpha).apply {
            duration = 200
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isVisible) {
                        overlayView?.visibility = View.GONE
                    }
                }
            })
            start()
        }
    }
}
