package com.example.llama.agent.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.llama.agent.perception.TranscriptAgent
import com.google.android.material.slider.Slider
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class GainSliderPopup(private val serviceContext: Context, private val themeContext: Context) {

    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var containerView: FrameLayout? = null
    private val isShowing = AtomicBoolean(false)

    fun show(anchorX: Int, anchorY: Int, isSystemAudio: Boolean) {
        if (isShowing.getAndSet(true)) return

        val container = FrameLayout(serviceContext)
        this.containerView = container

        // Odchytneme kliknutí mimo slider -> zavře popup
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hide()
            }
            true
        }

        val card = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = themeContext.getDrawable(android.R.drawable.dialog_holo_light_frame)
            setPadding(16.dp(), 32.dp(), 16.dp(), 32.dp())
            // Aby se card sám o sobě nekliknul jako zavření
            setOnTouchListener { _, _ -> true }
        }

        val titleView = TextView(themeContext).apply {
            text = if (isSystemAudio) "Sys Gain" else "Mic Gain"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16.dp())
        }

        val valView = TextView(themeContext).apply {
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 16.dp(), 0, 0)
        }

        val slider = Slider(themeContext).apply {
            valueFrom = -6f
            valueTo = 12f
            stepSize = 0.5f
            
            val currentGain = if (isSystemAudio) TranscriptAgent.systemAudioGain else TranscriptAgent.microphoneGain
            value = currentGain.coerceIn(-6f, 12f)
            valView.text = String.format("%.1fx", value)

            addOnChangeListener { _, newValue, _ ->
                valView.text = String.format("%.1fx", newValue)
                if (isSystemAudio) {
                    TranscriptAgent.systemAudioGain = newValue
                } else {
                    TranscriptAgent.microphoneGain = newValue
                }
            }
        }

        // Rotace slideru na svislý
        slider.rotation = 270f
        
        // Pevná velikost (délka) pro vertical slider 
        val sliderParams = LinearLayout.LayoutParams(200.dp(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            topMargin = 80.dp()
            bottomMargin = 80.dp()
        }

        card.addView(titleView)
        card.addView(slider, sliderParams)
        card.addView(valView)

        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Offset card next to anchor
            leftMargin = anchorX + 100.dp()
            topMargin = anchorY - 150.dp()
        }

        container.addView(card, cardParams)

        val layoutFlag = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        mainHandler.post {
            try {
                windowManager.addView(container, params)
                
                // Animace
                card.scaleX = 0f
                card.scaleY = 0f
                card.alpha = 0f
                val scaleX = ObjectAnimator.ofFloat(card, "scaleX", 0f, 1f)
                val scaleY = ObjectAnimator.ofFloat(card, "scaleY", 0f, 1f)
                val alpha = ObjectAnimator.ofFloat(card, "alpha", 0f, 1f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY, alpha)
                    duration = 200
                    interpolator = OvershootInterpolator(1.2f)
                    start()
                }
            } catch (e: Exception) {
                isShowing.set(false)
            }
        }
    }

    private fun hide() {
        if (!isShowing.get()) return
        val c = containerView ?: return

        mainHandler.post {
            try {
                windowManager.removeView(c)
            } catch (e: Exception) {}
            containerView = null
            isShowing.set(false)
        }
    }

    private fun Int.dp(): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            this.toFloat(),
            serviceContext.resources.displayMetrics
        ).roundToInt()
    }
}
