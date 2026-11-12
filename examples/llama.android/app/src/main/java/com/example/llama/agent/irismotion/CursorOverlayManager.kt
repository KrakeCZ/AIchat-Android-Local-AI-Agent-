package com.example.llama.agent.irismotion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * CursorOverlayManager — Vykresluje iris tracking kurzor nad všemi aplikacemi.
 *
 * Využívá TYPE_ACCESSIBILITY_OVERLAY (vyžaduje AccessibilityService kontext). Kurzor je
 * FLAG_NOT_TOUCHABLE — neblokuje dotykové vstupy ostatních aplikací.
 *
 * Design kurzoru:
 * - Vnější kruh (outline) — poloprůhledný, 80px průměr (konfigurovatelný)
 * - Vnitřní tečka — bílá, 12px průměr (cross-hair center)
 * - Dwell arc — žlutý progress arc narůstající po obvodu (0→360° = dwell splněn)
 * - Click animace — mžikový červený blink
 */
class CursorOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "CursorOverlay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Dynamická HW detekce rozlišení displeje
    private val maxScreenWidth: Int
    private val maxScreenHeight: Int

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            maxScreenWidth = bounds.width()
            maxScreenHeight = bounds.height()
        } else {
            @Suppress("DEPRECATION") val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(metrics)
            maxScreenWidth = metrics.widthPixels
            maxScreenHeight = metrics.heightPixels
        }
        Log.d(TAG, "📐 HW rozlišení detekováno: ${maxScreenWidth}×${maxScreenHeight}")
    }

    // Kurzor View (custom Canvas drawing)
    private val cursorView: CursorView = CursorView(context)

    private val params =
            WindowManager.LayoutParams(
                            150,
                            150, // viewport size (px) — větší než kurzor pro anti-clip marginy
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
                                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                            PixelFormat.TRANSLUCENT
                    )
                    .apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 0
                        y = 0
                    }

    private var isAttached = false
    private var cursorSizePx = 80 // Výchozí, konfigurovatelné z Settings

    // ==================== PUBLIC API ====================

    /** Zobrazí kurzor na obrazovce. Idempotentní — bezpečné pro opakované volání. */
    fun show() {
        if (isAttached) return
        try {
            windowManager.addView(cursorView, params)
            isAttached = true
            Log.d(TAG, "👁️ Cursor overlay zobrazen")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nelze přidat cursor overlay: ${e.message}")
        }
    }

    /** Skryje a odstraní kurzor. Idempotentní. */
    fun hide() {
        if (!isAttached) return
        try {
            windowManager.removeView(cursorView)
            isAttached = false
            Log.d(TAG, "👁️ Cursor overlay skryt")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nelze odebrat cursor overlay: ${e.message}")
        }
    }

    /**
     * Aktualizuje pozici kurzoru. Voláno ~48x/s z IrisMotionController. Optimalizováno: pouze
     * updateViewLayout() bez removeView/addView.
     *
     * Oboustranný edge clamp: kurzor se omezuje na [0, maxScreen - viewportSize] na obou osách.
     * Dynamicky detekované HW rozlišení displeje.
     *
     * @param screenX Absolutní X souřadnice pohledu (px)
     * @param screenY Absolutní Y souřadnice pohledu (px)
     */
    fun updatePosition(screenX: Int, screenY: Int) {
        if (!isAttached) return
        val halfSize = params.width / 2

        // [MASTER IT FIX]: Dynamická detekce orientace displeje za runtime (State/Logic Lock Fix)
        // Použijeme HW rozlišení získané při startu služby v init bloku, avšak při rotaci
        // tyto hodnoty prohodíme (swap). To zabrání chybě WindowMetrics v AccessibilityService,
        // která chybně detekuje poloviční rozměry obrazovky při překreslování a ořízne tak kurzor.
        val isLandscape = context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        
        val currentMaxWidth = if (isLandscape) maxOf(maxScreenWidth, maxScreenHeight) else minOf(maxScreenWidth, maxScreenHeight)
        val currentMaxHeight = if (isLandscape) minOf(maxScreenWidth, maxScreenHeight) else maxOf(maxScreenWidth, maxScreenHeight)

        params.x = (screenX - halfSize).coerceIn(0, currentMaxWidth - params.width)
        params.y = (screenY - halfSize).coerceIn(0, currentMaxHeight - params.height)
        try {
            windowManager.updateViewLayout(cursorView, params)
        } catch (e: Exception) {
            Log.w(TAG, "updatePosition error: ${e.message}")
        }
    }

    /**
     * Aktualizuje dwell progress arc (0.0 = start, 1.0 = kliknutí).
     * @param progress Float 0.0–1.0
     */
    fun updateDwellProgress(progress: Float) {
        cursorView.dwellProgress = progress
        cursorView.invalidate()
    }

    /** Mžikový vizuální efekt při kliknutí (červený flash 150ms). */
    fun animateClick() {
        cursorView.isClickFlash = true
        cursorView.invalidate()
        cursorView.postDelayed(
                {
                    cursorView.isClickFlash = false
                    cursorView.dwellProgress = 0f
                    cursorView.invalidate()
                },
                150
        )
    }

    /** Vizuální efekt pro dvojité mrknutí (dvě rychlá červená bliknutí). */
    fun animateDoubleBlink() {
        // První bliknutí
        cursorView.isClickFlash = true
        cursorView.invalidate()

        cursorView.postDelayed(
                {
                    // Zhasni
                    cursorView.isClickFlash = false
                    cursorView.invalidate()

                    // Druhé bliknutí (po 100ms pauze)
                    cursorView.postDelayed(
                            {
                                cursorView.isClickFlash = true
                                cursorView.invalidate()

                                // Finální zhasnutí
                                cursorView.postDelayed(
                                        {
                                            cursorView.isClickFlash = false
                                            cursorView.dwellProgress = 0f
                                            cursorView.invalidate()
                                        },
                                        150
                                )
                            },
                            100
                    )
                },
                150
        )
    }

    /** Nastaví velikost kurzoru za runtime. */
    fun setCursorSize(sizePx: Int) {
        cursorSizePx = sizePx.coerceIn(40, 120)
        cursorView.cursorRadius = cursorSizePx / 2f
        if (isAttached) cursorView.invalidate()
    }

    // ==================== INNER VIEW ====================

    /**
     * Custom View pro vykreslení kurzoru (ring + dot + dwell arc). Veškeré kreslení je v onDraw →
     * žádné alokace za runtime (Paint pools).
     */
    // inner je redundantní pokud je třída přímo v těle CursorOverlayManager — stačí private
    private class CursorView(context: Context) : View(context) {

        var dwellProgress: Float = 0f // 0.0–1.0
        var isClickFlash: Boolean = false
        var cursorRadius: Float = 40f // Polovina velikosti kurzoru

        // Paint objekty — inicializovány jednou (no alloc v onDraw)
        private val paintRing =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.argb(200, 255, 255, 255) // Bílý poloprůhledný ring
                }
        private val paintDot =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.WHITE
                }
        private val paintDwell =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    color = Color.YELLOW
                    strokeCap = Paint.Cap.ROUND
                }
        private val paintFlash =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    color = Color.argb(180, 255, 80, 80) // Červený flash
                }
        private val arcRect = RectF()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f

            if (isClickFlash) {
                canvas.drawCircle(cx, cy, cursorRadius * 0.8f, paintFlash)
            }

            // Vnější ring
            canvas.drawCircle(cx, cy, cursorRadius, paintRing)

            // Středová tečka
            canvas.drawCircle(cx, cy, 6f, paintDot)

            // Dwell progress arc (žlutý, narůstá po CW ze 12 hodin)
            if (dwellProgress > 0f) {
                val arcInset = 6f
                arcRect.set(
                        cx - cursorRadius + arcInset,
                        cy - cursorRadius + arcInset,
                        cx + cursorRadius - arcInset,
                        cy + cursorRadius - arcInset
                )
                canvas.drawArc(arcRect, -90f, dwellProgress * 360f, false, paintDwell)
            }
        }
    }
}
