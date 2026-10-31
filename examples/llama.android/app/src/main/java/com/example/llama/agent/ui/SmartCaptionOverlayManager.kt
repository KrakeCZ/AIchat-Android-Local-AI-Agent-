package com.example.llama.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.llama.R
import com.example.llama.SettingsManager
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.TranscriptEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ======================================================================== SMART CAPTION OVERLAY
 * MANAGER — Enterprise-Grade A11y Captions
 * ========================================================================
 *
 * Plovoucí titulkový panel nad videem / jakoukoliv aplikací.
 *
 * Architektura (z RFC-004 + RFC-005):
 * - Vykresluje se přes TYPE_ACCESSIBILITY_OVERLAY (bez SYSTEM_ALERT_WINDOW oprávnění!)
 * - Poloprůhledný černý panel s vysokým kontrastem (A11y poměr > 7:1)
 * - Pinch-to-Zoom: 14sp → 36sp (pro slabozraké)
 * - Drag-and-Drop: Přesun panelu na libovolné místo na displeji
 * - Přijímá TranscriptEventy z PerceptionEventBus
 *
 * Bezpečnost:
 * - FLAG_NOT_FOCUSABLE: Dotyky MIMO panel padají skrz do videa pod ním
 * - FLAG_WATCH_OUTSIDE_TOUCH: Detekce doteku mimo panel pro auto-hide UX
 * - accessibilityLiveRegion="polite": TalkBack oznamuje nový text bez přerušení
 *
 * Thread Safety:
 * - UI operace vždy na MainLooper (Handler)
 * - EventBus collector na Dispatchers.Main (CoroutineScope)
 */
class SmartCaptionOverlayManager(
        private val context: Context,
        private val settingsManager: SettingsManager
) {

    companion object {
        private const val TAG = "SmartCaptionOverlay"

        /** Minimální a maximální velikost textu (sp) */
        private const val MIN_TEXT_SIZE_SP = 14f
        private const val MAX_TEXT_SIZE_SP = 36f
        private const val DEFAULT_TEXT_SIZE_SP = 18f

        /** Maximální počet znaků v panelu (ochrana před OOM při extrémně dlouhém textu) */
        private const val MAX_DISPLAY_CHARS = 1000

        /** Auto-fade po X ms bez nového textu (0 = vypnuto) */
        private const val AUTO_FADE_DELAY_MS = 15_000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // Views
    private var overlayView: View? = null
    private var captionText: TextView? = null
    private var captionStatus: TextView? = null
    private var dragHandle: View? = null

    // Gesture detectors
    private var scaleGestureDetector: ScaleGestureDetector? = null

    // State
    private var isShowing = false
    private var currentTextSizeSp = DEFAULT_TEXT_SIZE_SP
    private var layoutParams: WindowManager.LayoutParams? = null

    // EventBus collector
    private var collectorScope: CoroutineScope? = null

    // Auto-fade timer
    private val autoFadeRunnable = Runnable {
        overlayView?.animate()?.alpha(0.3f)?.setDuration(600)?.start()
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /** Zobrazí plovoucí titulkový panel. Idempotentní — opakované volání je bezpečné. */
    fun show() {
        if (isShowing) {
            Log.w(TAG, "⚠️ SmartCaptionOverlay již zobrazen, ignoruji duplicitní show()")
            return
        }

        mainHandler.post {
            try {
                createOverlayView()
                val params = createLayoutParams()
                layoutParams = params
                windowManager.addView(overlayView, params)
                isShowing = true
                startEventCollector()
                resetAutoFadeTimer()
                Log.i(TAG, "✅ SmartCaptionOverlay zobrazen (TYPE_ACCESSIBILITY_OVERLAY)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Nelze zobrazit SmartCaptionOverlay", e)
                isShowing = false
            }
        }
    }

    /** Skryje plovoucí titulkový panel a uvolní prostředky. Idempotentní. */
    fun hide() {
        if (!isShowing) return

        mainHandler.post {
            try {
                stopEventCollector()
                mainHandler.removeCallbacks(autoFadeRunnable)
                windowManager.removeView(overlayView)
                overlayView = null
                captionText = null
                captionStatus = null
                dragHandle = null
                scaleGestureDetector = null
                layoutParams = null
                isShowing = false
                Log.i(TAG, "🔇 SmartCaptionOverlay skryt")
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při skrývání SmartCaptionOverlay", e)
            }
        }
    }

    /**
     * Aktualizuje zobrazený text (volá se z EventBus collectoru nebo externě). Thread-safe — vždy
     * switchne na Main thread.
     * @param isPartial true = partial result (kurzíva, šedá), false = final (normální, bílá)
     */
    fun updateText(newText: String, isPartial: Boolean = false) {
        mainHandler.post {
            if (!isShowing) return@post

            // Ořízni na MAX_DISPLAY_CHARS (ochrana proti OOM)
            val displayText =
                    if (newText.length > MAX_DISPLAY_CHARS) {
                        "…" + newText.takeLast(MAX_DISPLAY_CHARS - 1)
                    } else {
                        newText
                    }

            captionText?.text = displayText

            // Vizuální rozlišení: partial = kurzíva + šedá, final = normální + bílá
            if (isPartial) {
                captionText?.setTypeface(null, android.graphics.Typeface.ITALIC)
                captionText?.setTextColor(0xFFB0B0B0.toInt()) // Světle šedá
            } else {
                captionText?.setTypeface(null, android.graphics.Typeface.NORMAL)
                captionText?.setTextColor(0xFFFFFFFF.toInt()) // Bílá
            }

            // Obnovit plnou viditelnost pokud byl auto-faded
            overlayView?.animate()?.alpha(1.0f)?.setDuration(200)?.start()
            resetAutoFadeTimer()
        }
    }

    /** Vrací true pokud je panel momentálně zobrazen */
    fun isVisible(): Boolean = isShowing

    // ========================================================================
    // PRIVATE — Inicializace View
    // ========================================================================

    @SuppressLint("ClickableViewAccessibility")
    private fun createOverlayView() {
        val inflater = LayoutInflater.from(context)
        overlayView = inflater.inflate(R.layout.layout_smart_caption, null)

        captionText = overlayView?.findViewById(R.id.caption_text)
        captionStatus = overlayView?.findViewById(R.id.caption_status)
        dragHandle = overlayView?.findViewById(R.id.caption_drag_handle)

        // Nastav výchozí velikost písma
        captionText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
        updateStatusText()

        // Inicializuj ScaleGestureDetector (Pinch-to-Zoom)
        scaleGestureDetector = ScaleGestureDetector(context, PinchToZoomListener())

        // Touch handling: Drag + Pinch
        setupTouchHandling()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        overlayView?.setOnTouchListener { _, event ->
            // Vždy předej do ScaleGestureDetectoru
            scaleGestureDetector?.onTouchEvent(event)

            // Nepřesouvej pokud probíhá pinch
            if (scaleGestureDetector?.isInProgress == true) {
                return@setOnTouchListener true
            }

            val params = layoutParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()

                    if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                        isDragging = true
                    }

                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(overlayView, params)
                        } catch (_: Exception) {
                            // View již neexistuje
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        // Krátký tap — toggle viditelnost status řádku
                        val statusView = captionStatus ?: return@setOnTouchListener true
                        statusView.visibility =
                                if (statusView.visibility == View.VISIBLE) {
                                    View.GONE
                                } else {
                                    View.VISIBLE
                                }
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ========================================================================
    // PRIVATE — Pinch-to-Zoom
    // ========================================================================

    private inner class PinchToZoomListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            currentTextSizeSp =
                    (currentTextSizeSp * scaleFactor).coerceIn(MIN_TEXT_SIZE_SP, MAX_TEXT_SIZE_SP)

            captionText?.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentTextSizeSp)
            updateStatusText()

            Log.v(TAG, "🔍 Pinch-to-Zoom: ${currentTextSizeSp.toInt()}sp")
            return true
        }
    }

    // ========================================================================
    // PRIVATE — WindowManager LayoutParams
    // ========================================================================

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val panelWidth = (displayMetrics.widthPixels * 0.92f).toInt() // 92% šířky displeje

        return WindowManager.LayoutParams(
                        panelWidth,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        // KLÍČOVÉ: TYPE_ACCESSIBILITY_OVERLAY — žádné SYSTEM_ALERT_WINDOW
                        // oprávnění!
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        // Flags:
                        // FLAG_NOT_FOCUSABLE: Dotyky mimo panel padají do videa
                        // FLAG_NOT_TOUCH_MODAL: Neblokuje touch eventy pro aplikace pod ním
                        // FLAG_LAYOUT_IN_SCREEN: Umožňuje překrytí systémových barů
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                )
                .apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    // Výchozí pozice: 80dp od spodního okraje (nad navigační lištou)
                    y = dpToPx(80)
                    x = 0
                }
    }

    // ========================================================================
    // PRIVATE — EventBus Collector (přijímání TranscriptEventů)
    // ========================================================================

    private fun startEventCollector() {
        stopEventCollector() // Idempotentní restart

        collectorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        collectorScope?.launch {
            Log.i(TAG, "📡 SmartCaption EventBus collector spuštěn (partial + final)")
            PerceptionEventBus.events.collect { event ->
                if (event is TranscriptEvent) {
                    // Zobrazit JAK partial TAK final results
                    // Partial = okamžitý text (kurzíva), Final = definitivní (normální)
                    updateText(event.text, isPartial = !event.isFinal)
                }
            }
        }
    }

    private fun stopEventCollector() {
        collectorScope?.cancel()
        collectorScope = null
    }

    // ========================================================================
    // PRIVATE — Utility
    // ========================================================================

    private fun updateStatusText() {
        captionStatus?.text = "CZ/EN • ${currentTextSizeSp.toInt()}sp"
    }

    private fun resetAutoFadeTimer() {
        mainHandler.removeCallbacks(autoFadeRunnable)
        if (AUTO_FADE_DELAY_MS > 0) {
            mainHandler.postDelayed(autoFadeRunnable, AUTO_FADE_DELAY_MS)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
