package com.example.llama.srt.ui.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.OverScroller
import com.example.llama.srt.SrtBlock
import kotlin.math.abs
import kotlin.math.max

/**
 * Enterprise-grade Canvas Timeline pro SRT bloky (CapCut style).
 * * Vlastnosti:
 * - Zero-Allocation v onDraw() a onTouchEvent()
 * - Plná podpora fling scrollování (OverScroller)
 * - Pinch-to-Zoom pro změnu měřítka (msPerPx)
 * - Přesný Hit-Testing pro výběr bloků a tahání (trimming) hran
 * - Auto-scroll k playheadu (kurzoru přehrávání)
 */
class SubtitleTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "SubtitleTimelineView"
        private const val MIN_MS_PER_PX = 1f      // Extrémní zoom (1px = 1ms)
        private const val MAX_MS_PER_PX = 100f    // Oddáleno (1px = 100ms)
        private const val HANDLE_WIDTH_DP = 16f   // Šířka dotykové hrany pro trim
        private const val TRACK_HEIGHT_DP = 60f   // Výška samotného bloku titulků
    }

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onBlockSelected: ((index: Int) -> Unit)? = null
    var onBlockTrimmed: ((index: Int, newStartMs: Long, newEndMs: Long) -> Unit)? = null
    var onSeekRequested: ((positionMs: Long) -> Unit)? = null
    var onStudioSelected: ((String) -> Unit)? = null

    // ── Stavová Data (UDF) ────────────────────────────────────────────────────
    private var blocks: List<SrtBlock> = emptyList()
    private var totalDurationMs: Long = 60_000L
    private var currentPositionMs: Long = 0L
    private var selectedIndex: Int = -1
    
    // NLE Multi-Track State
    private var isExpanded: Boolean = false
    private val expandBtnRect = RectF()

    // ── Fyzika a Měřítko ──────────────────────────────────────────────────────
    private var msPerPx: Float = 10f
    private var scrollOffsetPx: Float = 0f
    private val scroller = OverScroller(context)

    // ── Pre-alokované Paint objekty a Rects (ZERO-ALLOCATION) ─────────────────
    private val blockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val selectedBlockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(3f)
        color = Color.parseColor("#FFEA00") // TikTok/CapCut Yellow
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(14f)
        isFakeBoldText = true
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = dpToPx(2f)
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#121212") }
    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
    private val uiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAAAAA")
        textSize = dpToPx(11f)
    }

    private val rulerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        strokeWidth = dpToPx(1f)
        style = Paint.Style.STROKE
    }
    private val rulerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#888888")
        textSize = dpToPx(9f)
        textAlign = Paint.Align.CENTER
    }

    private val drawRect = RectF()
    private val handleRect = RectF()

    // ── Dotykové stavy ────────────────────────────────────────────────────────
    private enum class TouchMode { IDLE, SCROLLING, DRAG_LEFT_EDGE, DRAG_RIGHT_EDGE, DRAG_BODY }
    private var touchMode = TouchMode.IDLE
    private var lastTouchX = 0f
    private var dragTempStartMs = 0L
    private var dragTempEndMs = 0L


    // ── Gestures ──────────────────────────────────────────────────────────────
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            if (scaleFactor.isNaN() || scaleFactor == 0f) return false

            // ZJEMNĚNÍ: Ořízneme extrémní výkyvy senzitivních displejů
            val clampedScale = scaleFactor.coerceIn(0.85f, 1.15f)

            // 1. Zjistíme, kde PŘESNĚ má uživatel prsty (pixel na displeji)
            val focusX = detector.focusX
            
            // 2. Zjistíme, jaký ČAS VE VIDEU leží přesně pod tímto pixelem
            val timeUnderFocusMs = pxToMs(scrollOffsetPx + focusX)

            // 3. Změníme měřítko (Zoom)
            msPerPx = (msPerPx / clampedScale).coerceIn(MIN_MS_PER_PX, MAX_MS_PER_PX)

            // 4. MAGIE: Vypočítáme nový scrollOffsetPx tak, aby čas timeUnderFocusMs
            // zůstal zafixovaný přesně pod pixelem focusX i po změně měřítka!
            scrollOffsetPx = msToPx(timeUnderFocusMs) - focusX
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())

            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            // ZLATÉ PRAVIDLO: Neskrolovat osu, když uživatel zoomuje nebo když tahá titulek!
            if (scaleDetector.isInProgress || isDragging()) return true

            // Přičteme posun a ořízneme ho, abychom nevyjeli mimo osu
            scrollOffsetPx += distanceX
            scrollOffsetPx = scrollOffsetPx.coerceIn(0f, maxScrollPx())
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaleDetector.isInProgress || isDragging()) return true

            // Setrvačník pro plynulý dojezd (jako na mapách)
            scroller.fling(
                scrollOffsetPx.toInt(), 0,
                (-velocityX).toInt(), 0,
                0, maxScrollPx().toInt(),
                0, 0
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Hit test pro Expand/Collapse tlačítko
            if (expandBtnRect.contains(e.x, e.y)) {
                isExpanded = !isExpanded
                requestLayout() // Změna výšky view
                invalidate()
                return true
            }

            val touchTimeMs = pxToMs(e.x + scrollOffsetPx)
            
            // Vypočítáme polohy stop
            val v1Top = if (isExpanded) dpToPx(36f) else 0f
            val v1Bottom = v1Top + dpToPx(40f)
            
            val a1Top = if (isExpanded) v1Bottom + dpToPx(4f) else 0f
            val a1Bottom = a1Top + dpToPx(40f)

            val s1Top = if (isExpanded) a1Bottom + dpToPx(4f) else (height - dpToPx(TRACK_HEIGHT_DP)) / 2f
            val s1Bottom = if (isExpanded) s1Top + dpToPx(40f) else s1Top + dpToPx(TRACK_HEIGHT_DP)
            
            if (isExpanded) {
                if (e.y in v1Top..v1Bottom) {
                    onStudioSelected?.invoke("VIDEO")
                    onSeekRequested?.invoke(touchTimeMs)
                    return true
                }
                if (e.y in a1Top..a1Bottom) {
                    onStudioSelected?.invoke("AUDIO")
                    onSeekRequested?.invoke(touchTimeMs)
                    return true
                }
            }

            // Kliknul uživatel na S1 stopu?
            if (e.y in s1Top..s1Bottom) {
                onStudioSelected?.invoke("SUBTITLE")
                val clickedIndex = blocks.indexOfFirst { touchTimeMs in it.startTimeMs..it.endTimeMs }
                if (clickedIndex != -1) {
                    onBlockSelected?.invoke(clickedIndex)
                    return true
                }
            }
            
            // Seek logic on empty space or other tracks
            onSeekRequested?.invoke(touchTimeMs)
            return true
        }
    })

    init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h: Int = if (isExpanded) {
            // btn(28) + gap(8) + V1(40) + gap(8) + S1(40) + gap(8) + A1(40) + bottom(8)
            dpToPx(28f + 8f + 40f + 8f + 40f + 8f + 40f + 8f).toInt()
        } else {
            dpToPx(TRACK_HEIGHT_DP + 16f).toInt()
        }
        setMeasuredDimension(w, h)
    }

    // ── Veřejné API (UDF) ─────────────────────────────────────────────────────

    // Voláno z Fragment/Activity při každém tiku SyncLoop (16ms).
    // isPlaying řídí auto-scroll — při pauze se netahá k playheadu.
    fun setPlaybackState(positionMs: Long, isPlaying: Boolean) {
        currentPositionMs = positionMs
        if (isPlaying) autoScrollToPlayhead()
        invalidate()
    }

    fun updateState(newBlocks: List<SrtBlock>, durationMs: Long, positionMs: Long, selectedIdx: Int) {
        blocks = newBlocks
        totalDurationMs = durationMs
        currentPositionMs = positionMs
        selectedIndex = selectedIdx

        if (touchMode == TouchMode.IDLE && !scroller.computeScrollOffset()) {
            val playheadPx = msToPx(currentPositionMs)
            val visibleStartPx = scrollOffsetPx
            val visibleEndPx = scrollOffsetPx + width
            
            // Auto-scroll pouze pokud playhead vyjede mimo viditelnou oblast (a uživatel s tím právě nehýbe)
            if (playheadPx < visibleStartPx || playheadPx > visibleEndPx) {
                autoScrollToPlayhead()
            }
        }
        invalidate()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollOffsetPx = scroller.currX.toFloat().coerceIn(0f, maxScrollPx())
            postInvalidateOnAnimation()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 1. VŽDY předáme dotyky systémovým detektorům (ty udělají složitou matematiku)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        val x = event.x

        // 2. Řešíme jen naše specifické NLE stavy (zastavení setrvačnosti a tahání bloků)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // FIX: Když se uživatel dotkne obrazovky během rolování, 
                // OKAMŽITĚ zastavíme setrvačník. Zabrání to "skákání".
                scroller.forceFinished(true)
                lastTouchX = x
                touchMode = TouchMode.IDLE

                // Hit-Testing: Pokud je blok vybrán, zjistíme, zda se nedotýkáme jeho hran
                if (selectedIndex in blocks.indices) {
                    val block = blocks[selectedIndex]
                    val blockStartPx = msToPx(block.startTimeMs) - scrollOffsetPx
                    val blockEndPx = msToPx(block.endTimeMs) - scrollOffsetPx
                    val handlePx = dpToPx(HANDLE_WIDTH_DP)

                    when {
                        abs(x - blockStartPx) <= handlePx -> {
                            touchMode = TouchMode.DRAG_LEFT_EDGE
                            dragTempStartMs = block.startTimeMs
                            dragTempEndMs = block.endTimeMs
                        }
                        abs(x - blockEndPx) <= handlePx -> {
                            touchMode = TouchMode.DRAG_RIGHT_EDGE
                            dragTempStartMs = block.startTimeMs
                            dragTempEndMs = block.endTimeMs
                        }
                        x in blockStartPx..blockEndPx -> {
                            touchMode = TouchMode.DRAG_BODY
                            dragTempStartMs = block.startTimeMs
                            dragTempEndMs = block.endTimeMs
                        }
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Tady řešíš POUZE posouvání bloku (Trim/Move), 
                // posouvání celé osy necháváš na gestureDetector (onScroll) výše!
                if (isDragging()) {
                    val deltaX = x - lastTouchX
                    val deltaMs = pxToMs(deltaX)

                    // Kolizní mantinely (Collision Detection)
                    val prevBlock = if (selectedIndex > 0) blocks[selectedIndex - 1] else null
                    val nextBlock = if (selectedIndex < blocks.size - 1) blocks[selectedIndex + 1] else null

                    val minStartMs = prevBlock?.endTimeMs ?: 0L
                    val maxEndMs = nextBlock?.startTimeMs ?: totalDurationMs

                    when (touchMode) {
                        TouchMode.DRAG_LEFT_EDGE -> {
                            // Ochrana: Levá hrana nesmí přejít přes pravou (minimální délka 200ms) a nesmí kolidovat s předchozím blokem
                            dragTempStartMs = (dragTempStartMs + deltaMs).coerceIn(minStartMs, dragTempEndMs - 200L)
                        }
                        TouchMode.DRAG_RIGHT_EDGE -> {
                            dragTempEndMs = (dragTempEndMs + deltaMs).coerceIn(dragTempStartMs + 200L, maxEndMs)
                        }
                        TouchMode.DRAG_BODY -> {
                            val duration = dragTempEndMs - dragTempStartMs
                            dragTempStartMs = (dragTempStartMs + deltaMs).coerceIn(minStartMs, maxEndMs - duration)
                            dragTempEndMs = dragTempStartMs + duration
                        }
                        else -> {}
                    }
                    
                    // Auto-scroll osy při tažení u okraje
                    val edgeMargin = dpToPx(40f)
                    if (x < edgeMargin) {
                        scrollOffsetPx = (scrollOffsetPx - dpToPx(8f)).coerceIn(0f, maxScrollPx())
                    } else if (x > width - edgeMargin) {
                        scrollOffsetPx = (scrollOffsetPx + dpToPx(8f)).coerceIn(0f, maxScrollPx())
                    }

                    invalidate()
                }
                lastTouchX = x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging()) {
                    // Tah byl dokončen -> Notifikujeme ViewModel (UDF)
                    onBlockTrimmed?.invoke(selectedIndex, dragTempStartMs, dragTempEndMs)
                }
                touchMode = TouchMode.IDLE
                invalidate()
            }
        }
        return true
    }

    // ── Vykreslování (Zero-Allocation Loop) ───────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgPaint.color)

        // 0. Vykreslení UI pro Multi-Track
        expandBtnRect.set(dpToPx(8f), dpToPx(8f), dpToPx(100f), dpToPx(28f))
        blockPaint.color = Color.parseColor("#333333")
        canvas.drawRoundRect(expandBtnRect, dpToPx(4f), dpToPx(4f), blockPaint)
        val btnText = if (isExpanded) "[-] Sbalit Stopy" else "[+] Studio Timeline"
        canvas.drawText(btnText, dpToPx(16f), dpToPx(22f), uiTextPaint)

        val trackTop: Float
        val trackBottom: Float
        
        if (isExpanded) {
            val trackH = dpToPx(40f)
            val gap = dpToPx(8f)
            val startY = dpToPx(36f) // pod tlačítkem Expand
            
            // V1 (Video Track Placeholder)
            val v1Top = startY
            canvas.drawRect(0f, v1Top, width.toFloat(), v1Top + trackH, trackBgPaint)
            canvas.drawText("V1", dpToPx(8f), v1Top + trackH/2f + dpToPx(4f), textPaint)
            // Vizualizace video klipu přes celou šířku
            val v1ClipRight = msToPx(totalDurationMs) - scrollOffsetPx
            if (v1ClipRight > dpToPx(30f)) {
                blockPaint.color = Color.parseColor("#1B5E20") // Zelený video klip
                canvas.drawRoundRect(dpToPx(30f), v1Top + dpToPx(2f), v1ClipRight, v1Top + trackH - dpToPx(2f), dpToPx(4f), dpToPx(4f), blockPaint)
                canvas.drawText("Hlavní Video Klip", dpToPx(38f), v1Top + trackH/2f + dpToPx(4f), uiTextPaint)
            }
            
            // S1 (Subtitle Track)
            trackTop = v1Top + trackH + gap
            trackBottom = trackTop + trackH
            canvas.drawRect(0f, trackTop, width.toFloat(), trackBottom, bgPaint)
            canvas.drawText("S1", dpToPx(8f), trackTop + trackH/2f + dpToPx(4f), textPaint)
            
            // A1 (Audio Track Placeholder)
            val a1Top = trackBottom + gap
            canvas.drawRect(0f, a1Top, width.toFloat(), a1Top + trackH, trackBgPaint)
            canvas.drawText("A1", dpToPx(8f), a1Top + trackH/2f + dpToPx(4f), textPaint)
            if (v1ClipRight > dpToPx(30f)) {
                blockPaint.color = Color.parseColor("#01579B") // Modrý audio klip
                canvas.drawRoundRect(dpToPx(30f), a1Top + dpToPx(2f), v1ClipRight, a1Top + trackH - dpToPx(2f), dpToPx(4f), dpToPx(4f), blockPaint)
                // Naznačení waveformy
                canvas.drawLine(dpToPx(40f), a1Top + trackH/2f, v1ClipRight - dpToPx(10f), a1Top + trackH/2f, uiTextPaint)
            }
            
        } else {
            // Kompaktní režim
            trackTop = (height - dpToPx(TRACK_HEIGHT_DP)) / 2f
            trackBottom = trackTop + dpToPx(TRACK_HEIGHT_DP)
        }

        val handleWidth = dpToPx(HANDLE_WIDTH_DP)

        val visibleStartMs = pxToMs(scrollOffsetPx)
        val visibleEndMs = pxToMs(scrollOffsetPx + width)

        // 0b. Časová osa (Metronom / Ruler) — adaptivní tiky podle zoomu (msPerPx)
        val rulerY = trackTop - dpToPx(14f)
        val (tickIntervalMs, labelIntervalMs) = when {
            msPerPx <= 2f  -> 100L   to 1_000L
            msPerPx <= 10f -> 500L   to 2_000L
            msPerPx <= 30f -> 1_000L to 5_000L
            else           -> 5_000L to 10_000L
        }
        val firstTickMs = (visibleStartMs / tickIntervalMs) * tickIntervalMs
        var tickMs = firstTickMs
        while (tickMs <= visibleEndMs + tickIntervalMs) {
            val tickPx = msToPx(tickMs) - scrollOffsetPx
            val isMajor = tickMs % labelIntervalMs == 0L
            val tickTop = rulerY + if (isMajor) dpToPx(0f) else dpToPx(5f)
            canvas.drawLine(tickPx, tickTop, tickPx, rulerY + dpToPx(10f), rulerPaint)
            if (isMajor) {
                val labelSec = tickMs / 1000L
                val label = "%d:%02d".format(labelSec / 60, labelSec % 60)
                canvas.drawText(label, tickPx, rulerY - dpToPx(2f), rulerLabelPaint)
            }
            tickMs += tickIntervalMs
        }

        // 1. Vykreslení bloků (Binary Search optimalizace pro tisíce titulků)
        var startIndex = blocks.binarySearch { it.endTimeMs.compareTo(visibleStartMs) }
        if (startIndex < 0) startIndex = -(startIndex + 1)

        for (i in startIndex until blocks.size) {
            val block = blocks[i]

            // Určení vykreslovacích časů (lokální drag override)
            val renderStartMs = if (i == selectedIndex && isDragging()) dragTempStartMs else block.startTimeMs
            val renderEndMs = if (i == selectedIndex && isDragging()) dragTempEndMs else block.endTimeMs

            // Frustum Culling (jakmile narazíme na blok ZA obrazovkou, cyklus ukončíme)
            if (renderStartMs > visibleEndMs) break

            val leftPx = msToPx(renderStartMs) - scrollOffsetPx
            val rightPx = msToPx(renderEndMs) - scrollOffsetPx

            drawRect.set(leftPx, trackTop, rightPx, trackBottom)

            // Základní barva bloku
            blockPaint.color = if (i == selectedIndex) Color.parseColor("#33FFEA00") else Color.parseColor("#444444")
            canvas.drawRoundRect(drawRect, dpToPx(8f), dpToPx(8f), blockPaint)

            // Ořezání textu (Clipping), aby nepřetekl ven z bloku
            canvas.save()
            canvas.clipRect(drawRect)
            val textY = trackTop + (dpToPx(TRACK_HEIGHT_DP) / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText(block.text, leftPx + dpToPx(8f), textY, textPaint)
            canvas.restore()

            // Vykreslení obrysu a madel (Handles) pro vybraný blok
            if (i == selectedIndex) {
                canvas.drawRoundRect(drawRect, dpToPx(8f), dpToPx(8f), selectedBlockPaint)

                // Levé madlo
                handleRect.set(leftPx - handleWidth/2, trackTop, leftPx + handleWidth/2, trackBottom)
                canvas.drawRoundRect(handleRect, dpToPx(4f), dpToPx(4f), handlePaint)

                // Pravé madlo
                handleRect.set(rightPx - handleWidth/2, trackTop, rightPx + handleWidth/2, trackBottom)
                canvas.drawRoundRect(handleRect, dpToPx(4f), dpToPx(4f), handlePaint)
            }
        }

        // 2. Vykreslení Playheadu (Current Position)
        val playheadPx = msToPx(currentPositionMs) - scrollOffsetPx
        if (playheadPx in 0f..width.toFloat()) {
            canvas.drawLine(playheadPx, 0f, playheadPx, height.toFloat(), playheadPaint)
        }
    }

    // ── Utils ─────────────────────────────────────────────────────────────────
    private fun isDragging() = touchMode == TouchMode.DRAG_LEFT_EDGE || touchMode == TouchMode.DRAG_RIGHT_EDGE || touchMode == TouchMode.DRAG_BODY

    private fun pxToMs(px: Float): Long = (px * msPerPx).toLong()
    private fun msToPx(ms: Long): Float = ms / msPerPx

    private fun maxScrollPx(): Float = max(0f, msToPx(totalDurationMs) - width)

    private fun autoScrollToPlayhead() {
        val playX = msToPx(currentPositionMs)
        val centerScreen = width / 2f
        val targetScroll = playX - centerScreen
        scrollOffsetPx = targetScroll.coerceIn(0f, maxScrollPx())
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}