package com.example.llama.agent.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.example.llama.R
import com.example.llama.agent.perception.TranscriptAgent
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.util.concurrent.atomic.AtomicBoolean

class MixerOverlayPopup(private val serviceContext: Context, private val themeContext: Context) {

    private val windowManager = serviceContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var containerView: FrameLayout? = null
    private val isShowing = AtomicBoolean(false)

    fun show() {
        if (isShowing.getAndSet(true)) return

        val container = FrameLayout(serviceContext)
        this.containerView = container

        // Odchytneme kliknutí mimo okno -> zavře popup
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hide()
            }
            true
        }

        // Nafoukneme layout dialog_mixer pomocí themeContext (pro Material Sliders)
        val view = LayoutInflater.from(themeContext).inflate(R.layout.dialog_mixer, container, false)
        
        // Zabrání prokliku skrz samotný panel, aby se nezavřel
        view.setOnTouchListener { _, _ -> true }

        setupUI(view)

        // Zarovnání na střed obrazovky
        val viewParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            val margin = (16 * serviceContext.resources.displayMetrics.density).toInt()
            setMargins(margin, margin, margin, margin)
        }

        container.addView(view, viewParams)

        val layoutFlag = if (serviceContext is android.accessibilityservice.AccessibilityService) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        mainHandler.post {
            try {
                windowManager.addView(container, params)
                
                // Animace nástupu
                view.scaleX = 0.8f
                view.scaleY = 0.8f
                view.alpha = 0f
                val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 0.8f, 1f)
                val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 0.8f, 1f)
                val alpha = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
                AnimatorSet().apply {
                    playTogether(scaleX, scaleY, alpha)
                    duration = 250
                    interpolator = OvershootInterpolator(1.0f)
                    start()
                }
            } catch (e: Exception) {
                Log.e("MixerOverlayPopup", "Chyba při zobrazení", e)
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

    private fun setupUI(view: View) {
        val sliderMicGain = view.findViewById<Slider>(R.id.slider_mic_gain)
        val tvMicGainVal = view.findViewById<TextView>(R.id.tv_mic_gain_val)
        val sliderSysGain = view.findViewById<Slider>(R.id.slider_sys_gain)
        val tvSysGainVal = view.findViewById<TextView>(R.id.tv_sys_gain_val)

        // Inicializace hodnot z TranscriptAgent
        sliderMicGain.value = TranscriptAgent.microphoneGain
        tvMicGainVal.text = String.format("%.1fx", TranscriptAgent.microphoneGain)
        
        sliderSysGain.value = TranscriptAgent.systemAudioGain
        tvSysGainVal.text = String.format("%.1fx", TranscriptAgent.systemAudioGain)

        // Listenery
        sliderMicGain.addOnChangeListener { _, value, _ ->
            TranscriptAgent.microphoneGain = value
            tvMicGainVal.text = String.format("%.1fx", value)
        }

        sliderSysGain.addOnChangeListener { _, value, _ ->
            TranscriptAgent.systemAudioGain = value
            tvSysGainVal.text = String.format("%.1fx", value)
        }

        val switchEqEnable = view.findViewById<SwitchMaterial>(R.id.switch_eq_enable)
        switchEqEnable.isChecked = TranscriptAgent.dspConfig.eqEnabled

        switchEqEnable.setOnCheckedChangeListener { _, isChecked ->
            val oldConfig = TranscriptAgent.dspConfig
            val newConfig = oldConfig.copy(eqEnabled = isChecked)
            TranscriptAgent.dspConfig = newConfig
            
            // Okamžitá propagace do běžícího chainu
            TranscriptAgent.instance?.dspChain?.updateConfig(newConfig)
        }

        // Zavírací tlačítko
        view.findViewById<View>(R.id.btn_close_mixer)?.setOnClickListener {
            hide()
        }

        setupEqualizerBands(view)
    }

    private fun setupEqualizerBands(view: View) {
        val layoutEqBands = view.findViewById<LinearLayout>(R.id.layout_eq_bands)
        val tvEqInfo = view.findViewById<TextView>(R.id.tv_eq_info)

        var equalizer = getLiveEqualizer()
        var tempEq = false

        if (equalizer == null) {
            try {
                equalizer = Equalizer(0, 0)
                tempEq = true
            } catch (e: Exception) {
                tvEqInfo.text = "Zařízení nepodporuje HW Ekvalizér nebo není aktuálně dostupný."
                return
            }
        }

        try {
            val numBands = equalizer.numberOfBands
            val minLevel = equalizer.bandLevelRange[0]
            val maxLevel = equalizer.bandLevelRange[1]

            for (i in 0 until numBands) {
                val band = i.toShort()
                val freqHz = equalizer.getCenterFreq(band) / 1000

                val bandLayout = LayoutInflater.from(themeContext).inflate(R.layout.item_eq_band, layoutEqBands, false)
                val tvFreq = bandLayout.findViewById<TextView>(R.id.tv_eq_freq)
                val slider = bandLayout.findViewById<Slider>(R.id.slider_eq_band)

                tvFreq.text = "${freqHz}Hz"
                slider.valueFrom = minLevel.toFloat()
                slider.valueTo = maxLevel.toFloat()
                slider.stepSize = 100f 
                
                try {
                    val savedLevel = if (band < TranscriptAgent.eqBandLevels.size) {
                        TranscriptAgent.eqBandLevels[band.toInt()]
                    } else {
                        0
                    }
                    slider.value = savedLevel.toFloat().coerceIn(minLevel.toFloat(), maxLevel.toFloat())
                } catch (e: Exception) {
                    slider.value = 0f
                }

                slider.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val newLevel = value.toInt().toShort()
                        if (band < TranscriptAgent.eqBandLevels.size) {
                            TranscriptAgent.eqBandLevels[band.toInt()] = newLevel
                        }
                        try {
                            getLiveEqualizer()?.setBandLevel(band, newLevel)
                        } catch (e: Exception) { }
                    }
                }

                layoutEqBands.addView(bandLayout)
            }
        } catch (e: Exception) {
            tvEqInfo.text = "Chyba při čtení HW parametrů: ${e.message}"
        } finally {
            if (tempEq) {
                equalizer?.release()
            }
        }
    }

    private fun getLiveEqualizer(): Equalizer? {
        return TranscriptAgent.instance?.dspChain?.equalizer
    }
}
