package com.example.llama.agent.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WorkerSubMenuPopup
 *
 * Slouží k plynulému zobrazení svislého (vertikálního) podmenu (kapsy) nad
 * specifickým tlačítkem ve FloatingAgentButtonu.
 */
class WorkerSubMenuPopup(private val serviceContext: Context, private val themeContext: Context) {

    companion object {
        private const val TAG = "WorkerSubMenuPopup"
    }

    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var containerView: FrameLayout? = null
    private val isShowing = AtomicBoolean(false)

    data class SubMenuItem(
        val emoji: String,
        val text: String,
        val onLongClick: (() -> Boolean)? = null,
        val onClick: () -> Unit
    )

    /**
     * Zobrazí podmenu vázané na pozici anchorView (tlačítko ve FloatingToolbaru)
     */
    fun show(anchorX: Int, anchorY: Int, anchorWidth: Int, items: List<SubMenuItem>) {
        mainHandler.post {
            // Pokud je popup zrovna otevřený jinde, musíme ho první skrýt
            if (isShowing.get()) {
                hideImmediate()
            }

            try {
                buildUiElements(items)
                val params = createLayoutParams(anchorX, anchorY, anchorWidth)
                windowManager.addView(containerView, params)
                isShowing.set(true)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba zobrazení SubMenu", e)
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
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba skrytí SubMenu", e)
        }
    }

    val isVisible: Boolean
        get() = isShowing.get()

    private fun buildUiElements(items: List<SubMenuItem>) {
        // Wrapper, který při dotyku mimo tlačítka menu zavře
        containerView = FrameLayout(themeContext).apply {
            setOnClickListener { hide() }
        }

        val menuContainer = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            
            // Design kapsy - polo-transparentní tmavý
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E01E1E1E"))
                cornerRadius = dpToPx(24).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#44FFFFFF"))
            }

            // Nechrání před proklikem přes samotné menu do background wrapperu
            setOnClickListener { /* consume so background clicking is not triggered */ }
        }

        // Tvorba tlačítek
        items.forEach { item ->
            val row = LinearLayout(themeContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(48)
                ).apply {
                    setMargins(0, dpToPx(4), 0, dpToPx(4))
                }
                
                // Animace na taputí
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = dpToPx(16).toFloat()
                }

                setPadding(dpToPx(8), 0, dpToPx(16), 0)

                setOnClickListener {
                    // vizuální flash (oranž)
                    it.setBackgroundColor(Color.parseColor("#FF9800"))
                    mainHandler.postDelayed({
                        it.setBackgroundColor(Color.TRANSPARENT)
                        hide()
                        item.onClick()
                    }, 50)
                }

                if (item.onLongClick != null) {
                    setOnLongClickListener {
                        // vizuální flash (modrá pro long-click)
                        it.setBackgroundColor(Color.parseColor("#2196F3"))
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        mainHandler.postDelayed({
                            it.setBackgroundColor(Color.TRANSPARENT)
                            hide()
                            item.onLongClick.invoke()
                        }, 50)
                        true
                    }
                }
            }

            val iconTx = TextView(themeContext).apply {
                text = item.emoji
                textSize = 20f
                gravity = Gravity.CENTER
            }

            val labelTx = TextView(themeContext).apply {
                text = item.text
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dpToPx(8), 0, 0, 0)
            }

            row.addView(iconTx)
            row.addView(labelTx)
            menuContainer.addView(row)
        }

        val wrapperParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        wrapperParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        
        containerView?.addView(menuContainer, wrapperParams)
    }

    private fun createLayoutParams(anchorX: Int, anchorY: Int, anchorWidth: Int): WindowManager.LayoutParams {
        val wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        // Zarovnání nahoru
        wmParams.gravity = Gravity.TOP or Gravity.START
        
        // Zkorigujeme ukotvení (vycentrujeme kapsu nad tlačítko)
        // Musíme odhadnout šířku kapsy (~160dp)
        val menuEstimatedWidthPx = dpToPx(160)
        
        val xIdeal = anchorX + (anchorWidth / 2) - (menuEstimatedWidthPx / 2)
        // Pozicovat cca 20dp nad tlačítkem
        val yIdeal = anchorY - dpToPx(20) - dpToPx(200) // approx height offset pro BOTTOM layoutgravity wrapperu

        // Orez okrajů
        val screenWidth = serviceContext.resources.displayMetrics.widthPixels
        val safeX = xIdeal.coerceIn(dpToPx(16), screenWidth - menuEstimatedWidthPx - dpToPx(16))
        
        // Pozor, pokud je UI zobrazeno nahoře k okraji, musí jít tooltip pod něj
        val safeY = if (yIdeal < dpToPx(40)) anchorY + anchorWidth + dpToPx(20) else anchorY - dpToPx(16) // Bottom anchor gravity fixes height

        wmParams.x = safeX
        wmParams.y = safeY

        return wmParams
    }

    private fun dpToPx(dp: Int): Int {
        val density = serviceContext.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
