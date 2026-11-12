package com.example.llama.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.llama.R
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.TranscriptEvent
import com.example.llama.agent.perception.VisionUpdateEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FloatingResultPopup — Nezávislé plovoucí okno pro zobrazení NLP a Vision výsledků.
 *
 * Respektuje:
 * - TYPE_ACCESSIBILITY_OVERLAY (Implicitní systémová práva z AccessibilityService)
 * - StateFlow/SharedFlow Event Driven Architecture (PerceptionEventBus)
 * - Idempotentní operace nad WindowManagerem
 */
class FloatingResultPopup(
    private val context: Context
) {
    companion object {
        private const val TAG = "FloatingResultPopup"
        private const val MIN_WIDTH_DP = 200
        private const val MIN_HEIGHT_DP = 150
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // UI Komponenty
    private var containerView: FrameLayout? = null
    private var contentTextView: TextView? = null
    private var camContainer: FrameLayout? = null
    private var camPreviewImageView: ImageView? = null
    private var localBoundingBoxView: com.example.llama.agent.vision.BoundingBoxView? = null
    private var resizeHandleView: ImageView? = null
    
    // Běhový stav
    private val isShowing = AtomicBoolean(false)
    private var popupScope: CoroutineScope? = null
    private var currentLayoutParams: WindowManager.LayoutParams? = null

    /**
     * Idempotentní inicializace a zobrazení okna.
     */
    fun show() {
        mainHandler.post {
            if (!isShowing.compareAndSet(false, true)) {
                Log.d(TAG, "Popup již existuje, ignoruji show().")
                return@post
            }

            try {
                buildUiElements()
                currentLayoutParams = createInitialLayoutParams()
                
                // Validace před přidáním do WindowManageru
                if (containerView != null && currentLayoutParams != null) {
                    windowManager.addView(containerView, currentLayoutParams)
                    startEventSubscription()
                    Log.i(TAG, "✅ FloatingResultPopup zobrazen.")
                } else {
                    throw IllegalStateException("UI elementy nebyly správně inicializovány.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při zobrazení okna", e)
                isShowing.set(false)
            }
        }
    }

    /**
     * Atomické přepnutí stavu viditelnosti (Toggle).
     */
    fun toggle() {
        mainHandler.post {
            if (isShowing.get()) {
                hide() // volá se z main handleru
            } else {
                show() // volá se z main handleru
            }
        }
    }

    /**
     * Bezpečné zničení a úklid (Zero-Leak principle).
     */
    fun hide() {
        mainHandler.post {
            if (!isShowing.compareAndSet(true, false)) return@post

            try {
                popupScope?.cancel()
                popupScope = null

                if (containerView != null) {
                    windowManager.removeView(containerView)
                    containerView = null
                    contentTextView = null
                    camPreviewImageView?.setImageDrawable(null)
                    camPreviewImageView = null
                    localBoundingBoxView = null
                    camContainer = null
                    resizeHandleView = null
                    currentLayoutParams = null
                }
                Log.i(TAG, "✅ FloatingResultPopup skryt a paměť uvolněna.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při skrývání okna", e)
            }
        }
    }

    /**
     * Nastavuje naslouchání na globální sběrnici (PerceptionEventBus).
     */
    private fun startEventSubscription() {
        popupScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        popupScope?.launch {
            PerceptionEventBus.events.collect { event ->
                when (event) {
                    is TranscriptEvent -> updateContentText("🗣️ Přepis:\n${event.text}")
                    is VisionUpdateEvent -> {
                        val ctx = event.snapshot.toReadableContext()
                        if (ctx.isNotBlank()) updateContentText("👁️ Vidím:\n$ctx")
                        
                        if (event.updateSource == com.example.llama.agent.perception.VisionUpdateSource.LIVE_CAM) {
                            localBoundingBoxView?.updateDetections(event.detections)
                        }
                    }
                    is com.example.llama.agent.perception.CameraPreviewEvent -> {
                        mainHandler.post {
                            if (!isShowing.get()) return@post
                            if (event.bitmap != null) {
                                camPreviewImageView?.setImageBitmap(event.bitmap)
                                camContainer?.visibility = View.VISIBLE
                            } else {
                                camPreviewImageView?.setImageDrawable(null)
                                camContainer?.visibility = View.GONE
                                localBoundingBoxView?.clearDetections()
                            }
                        }
                    }
                    is com.example.llama.agent.perception.NlpResultEvent -> {
                        updateContentText("${event.title}:\n${event.resultText}")
                    }
                    // Ignorujeme ostatní typy událostí (jsme zacíleni na výsledky Workery)
                    else -> {}
                }
            }
        }
    }

    private fun updateContentText(newText: String) {
        mainHandler.post {
            if (!isShowing.get()) return@post
            contentTextView?.text = newText
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUiElements() {
        // Hlavní kontejner
        containerView = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#E6212121")) // Semi-transparent dark
            setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
        }

        // Header (Draggable Area)
        val headerView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#333333"))
            val headerParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 
                dpToPx(30)
            )
            headerParams.gravity = Gravity.TOP
            layoutParams = headerParams
        }

        val titleView = TextView(context).apply {
            text = "AI Result Hub"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        
        val fullscreenButton = TextView(context).apply {
            text = "⛶" // ikona pro fullscreen
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(30), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { 
                com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                    com.example.llama.agent.perception.UiActionEvent(com.example.llama.agent.perception.UiAction.TOGGLE_FULLSCREEN_BOXES)
                )
            }
        }

        val closeButton = TextView(context).apply {
            text = "✖"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dpToPx(30), LinearLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener { hide() }
        }

        headerView.addView(titleView)
        headerView.addView(fullscreenButton)
        headerView.addView(closeButton)

        // Textový obsah (Scrollable)
        val scrollView = ScrollView(context).apply {
            val scrollParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scrollParams.setMargins(0, dpToPx(30), 0, dpToPx(20)) // Místo pro header a resize handle
            layoutParams = scrollParams
        }

        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        camContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(8))
            }
            visibility = View.GONE
        }

        camPreviewImageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        
        localBoundingBoxView = com.example.llama.agent.vision.BoundingBoxView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        camContainer?.addView(camPreviewImageView)
        camContainer?.addView(localBoundingBoxView)

        contentTextView = TextView(context).apply {
            text = "Čekám na data z enginů..."
            setTextColor(Color.LTGRAY)
            textSize = 14f
        }
        
        scrollContent.addView(camContainer)
        scrollContent.addView(contentTextView)
        scrollView.addView(scrollContent)

        // Resize Handle (pravý dolní roh)
        resizeHandleView = ImageView(context).apply {
            // Použití standardního systému ikony (placeholder pro custom draw/drawable)
            setBackgroundColor(Color.parseColor("#888888")) 
            val handleParams = FrameLayout.LayoutParams(dpToPx(20), dpToPx(20))
            handleParams.gravity = Gravity.BOTTOM or Gravity.END
            layoutParams = handleParams
        }

        containerView?.addView(headerView)
        containerView?.addView(scrollView)
        containerView?.addView(resizeHandleView)

        // Připojení dotykové logiky
        setupDragLogic(headerView)
        setupResizeLogic(resizeHandleView!!)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupDragLogic(draggableView: View) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        draggableView.setOnTouchListener { _, event ->
            val params = currentLayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    safeUpdateViewLayout(containerView, params)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupResizeLogic(resizeView: View) {
        var initialWidth = 0
        var initialHeight = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        val minWidthPx = dpToPx(MIN_WIDTH_DP)
        val minHeightPx = dpToPx(MIN_HEIGHT_DP)

        resizeView.setOnTouchListener { _, event ->
            val params = currentLayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialWidth = params.width
                    initialHeight = params.height
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newWidth = initialWidth + (event.rawX - initialTouchX).toInt()
                    val newHeight = initialHeight + (event.rawY - initialTouchY).toInt()
                    
                    // Transakční kontrola rozměrů (Zamezení pádů z nulových či negativních hodnot)
                    params.width = newWidth.coerceAtLeast(minWidthPx)
                    params.height = newHeight.coerceAtLeast(minHeightPx)
                    
                    safeUpdateViewLayout(containerView, params)
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Atomický obal nad WindowManager.updateViewLayout zabraňující pádům při race conditions.
     */
    private fun safeUpdateViewLayout(view: View?, params: WindowManager.LayoutParams) {
        if (view == null || !isShowing.get()) return
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Nelze updatovat WindowManager layout: ${e.message}")
        }
    }

    private fun createInitialLayoutParams(): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val w = dpToPx(250)
        val h = dpToPx(200)

        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (displayMetrics.widthPixels - w) / 2
            y = dpToPx(50)
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
