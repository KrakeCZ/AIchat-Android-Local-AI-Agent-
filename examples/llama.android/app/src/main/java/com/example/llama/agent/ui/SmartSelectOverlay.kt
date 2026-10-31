package com.example.llama.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import com.example.llama.agent.perception.EventPriority
import com.example.llama.agent.perception.PerceptionEventBus
import com.example.llama.agent.perception.SmartSelectCompleteEvent
import com.example.llama.agent.utils.CompatibilityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SmartSelectOverlay — Systémová komponenta pro zachycení volných tahů kreslení nad obrazovkou.
 * Odpovídající Circle-to-Search / Scribble to Select UI.
 *
 * Implementuje Bounding Box extrakci z Freehand Path.
 */
class SmartSelectOverlay(
    private val context: Context,
    private val rootNodeProvider: () -> AccessibilityNodeInfo?,
    private val screenshotProvider: suspend () -> Bitmap?
) {
    companion object {
        private const val TAG = "SmartSelectOverlay"
        private const val STROKE_WIDTH_DP = 6f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var containerView: FrameLayout? = null
    private var scribbleView: ScribbleView? = null
    private var isShowing = AtomicBoolean(false)

    /**
     * Zobrazí Transparentní Overlay přes celou obrazovku, připravený přijmout dotyky.
     */
    fun show() {
        mainHandler.post {
            if (!isShowing.compareAndSet(false, true)) {
                Log.d(TAG, "SmartSelectOverlay již běží.")
                return@post
            }

            try {
                buildUiElements()
                val params = createLayoutParams()
                windowManager.addView(containerView, params)
                Log.i(TAG, "✅ SmartSelectOverlay ZOBRAZEN — Čekám na tah.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při zobrazení okna", e)
                isShowing.set(false)
            }
        }
    }

    /**
     * Okamžité schování a garbage collection (Zero-Leak principle).
     */
    fun hide() {
        mainHandler.post {
            if (!isShowing.compareAndSet(true, false)) return@post
            try {
                if (containerView != null) {
                    windowManager.removeView(containerView)
                    containerView = null
                    scribbleView = null
                }
                Log.i(TAG, "✅ SmartSelectOverlay SKRYT.")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Chyba při skrývání okna", e)
            }
        }
    }

    private fun buildUiElements() {
        containerView = FrameLayout(context).apply {
            setBackgroundColor(Color.parseColor("#40000000")) // Dimming effect (25% black)
        }

        val hintText = TextView(context).apply {
            text = "Zakreslete oblast prstem... (Circle to Select)"
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(60), 0, 0)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        val textParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        textParams.gravity = Gravity.TOP

        scribbleView = ScribbleView(context) { boundsF ->
            processSelection(boundsF)
        }

        containerView?.addView(scribbleView)
        containerView?.addView(hintText, textParams)
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
    }

    /**
     * Zpracování zachycených bounds
     */
    private fun processSelection(boundsF: RectF) {
        val rect = Rect()
        boundsF.roundOut(rect) // Zahrnutí případných zlomků
        
        Log.i(TAG, "🔍 Oblast vybrána: Rect(${rect.left}, ${rect.top}, ${rect.right}, ${rect.bottom})")

        // 1. Získat nativní text z Accessibility Tree
        val extractedText = extractTextFromRect(rect)
        
        // Asynchronně získat OCR obrázek, a pak odeslat event
        overlayScope.launch {
            try {
                // 1. Zmizíme kreslící čáru napřed, ať nepřekáží screenshotu
                scribbleView?.clearDrawing()
                containerView?.setBackgroundColor(Color.TRANSPARENT) // Zrušit ztmavnutí
                
                // Přepnutí kontextu a screenshot
                kotlinx.coroutines.delay(100) 
                
                val fullBitmap = screenshotProvider.invoke()
                var croppedBitmap: Bitmap? = null
                
                if (fullBitmap != null) {
                    val safeLeft = rect.left.coerceAtLeast(0)
                    val safeTop = rect.top.coerceAtLeast(0)
                    val safeWidth = rect.width().coerceAtMost(fullBitmap.width - safeLeft)
                    val safeHeight = rect.height().coerceAtMost(fullBitmap.height - safeTop)
                    
                    if (safeWidth > 0 && safeHeight > 0) {
                        croppedBitmap = Bitmap.createBitmap(fullBitmap, safeLeft, safeTop, safeWidth, safeHeight)
                    }
                }
                
                // 2. Vykreslíme vysledek (rámeček + text)
                scribbleView?.showResult(boundsF, extractedText)
                
                PerceptionEventBus.emit(SmartSelectCompleteEvent(
                    selectedRect = rect,
                    extractedText = extractedText,
                    croppedBitmap = croppedBitmap,
                    priority = EventPriority.HIGH
                ))
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ processSelection selhalo: ${e.message}", e)
                hide()
            }
        }
    }

    /**
     * Traversuje A11y Tree a hledá Node info, které se protíná s vybraným obdélníkem.
     */
    private fun extractTextFromRect(selectionRect: Rect): String {
        val rootNode = rootNodeProvider.invoke() ?: return ""
        val resultBuilder = StringBuilder()
        findNodesInRegion(rootNode, selectionRect, resultBuilder)
        return resultBuilder.toString().trim()
    }

    private fun findNodesInRegion(node: AccessibilityNodeInfo, region: Rect, output: StringBuilder) {
        val nodeRect = Rect()
        node.getBoundsInScreen(nodeRect)

        if (Rect.intersects(region, nodeRect)) {
            val txt = node.text?.toString()
            val desc = node.contentDescription?.toString()
            
            if (!txt.isNullOrBlank()) {
                output.appendLine(txt)
            } else if (!desc.isNullOrBlank()) {
                output.appendLine(desc)
            }
        }

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodesInRegion(child, region, output)
                CompatibilityUtils.recycleNode(child)
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }

    // =========================================================================
    // CUSTOM VIEW PRO KRESLENÍ (Scribble)
    // =========================================================================
    @SuppressLint("ViewConstructor")
    private inner class ScribbleView(
        context: Context,
        private val onGestureComplete: (RectF) -> Unit
    ) : View(context) {
        
        private val path = Path()
        private val paint = Paint().apply {
            color = Color.parseColor("#4285F4") // Google Blue
            style = Paint.Style.STROKE
            strokeWidth = dpToPx(STROKE_WIDTH_DP.toInt()).toFloat()
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#804285F4")) // Glow effect
        }

        private var isFinished = false
        private var resultRect: RectF? = null
        private var resultText: String? = null
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = dpToPx(14).toFloat()
            isAntiAlias = true
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }
        private val bgPaint = Paint().apply {
            color = Color.parseColor("#80000000") // Tmavé pozadí pod textem
        }

        fun clearDrawing() {
            path.reset()
            invalidate()
        }

        fun showResult(rect: RectF, text: String) {
            resultRect = rect
            resultText = text
            isFinished = true
            invalidate()
            
            mainHandler.post {
                val params = containerView?.layoutParams as? WindowManager.LayoutParams
                if (params != null) {
                    params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    windowManager.updateViewLayout(containerView, params)
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Suppress("DEPRECATION")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (isFinished) return false // Propagovat dál (na toolbar)

            val x = event.x
            val y = event.y

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    path.reset()
                    path.moveTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    path.lineTo(x, y)
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val bounds = RectF()
                    // Compute mathematically (deprecated exact flag removed)
                    path.computeBounds(bounds, true)
                    
                    // Přidáme tolerance obalového paddingu o velikosti tloušťky pera
                    val padding = paint.strokeWidth
                    bounds.inset(-padding, -padding)
                    
                    onGestureComplete(bounds)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // Kreslíme čáru, pokud ještě není hotovo
            if (!isFinished) {
                canvas.drawPath(path, paint)
            } else {
                // Hotovo -> Rámeček kolem výběru
                resultRect?.let { r ->
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dpToPx(3).toFloat()
                    canvas.drawRect(r, paint)
                    
                    // Modrý nádech uvnitř výběru (optional indikátor)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.parseColor("#204285F4") // 12% Modrá
                    canvas.drawRect(r, paint)
                    paint.color = Color.parseColor("#4285F4") // Vratime zpet
                    
                    // Plovoucí textovka uvnitř/nad boxem
                    resultText?.let { txt ->
                        val snippet = if (txt.length > 50) txt.take(50) + "..." else txt
                        if (snippet.isNotBlank()) {
                            val textWidth = textPaint.measureText(snippet)
                            val textX = r.left
                            val textY = r.top - dpToPx(8) // Kousek nad rámečkem
                            
                            // Nakreslení bg placičky
                            canvas.drawRect(
                                textX - dpToPx(4),
                                textY - textPaint.textSize,
                                textX + textWidth + dpToPx(4),
                                textY + dpToPx(4),
                                bgPaint
                            )
                            canvas.drawText(snippet, textX, textY, textPaint)
                        }
                    }
                }
            }
        }
    }
}
