package com.example.llama.srt.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.llama.srt.SrtBlock
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import kotlin.math.abs

/**
 * Enterprise-grade, hardware-accelerated subtitle overlay view.
 *
 * Delegate rendering directly to [SubtitleOverlayRenderer] for 100% parity
 * between UI preview and Export video, including line-breaking, WCAG boxes,
 * and word-level kinetic animations.
 */
class SubtitleOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    var onPositionChanged: ((newYPercent: Float) -> Unit)? = null
    var onSizeChanged: ((newSizeSp: Float) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var activeBlock: SrtBlock? = null
    private var currentVideoPositionMs: Long = 0L
    private var styleConfig: SubtitleStyleConfig = SubtitleStyleConfig()
    
    // Renderer instance
    private var renderer: SubtitleOverlayRenderer? = null

    // Animation state
    private var animStartMs: Long = 0L
    private var lastActiveBlockId: Int = -1

    // ── Video frame geometry ──────────────────────────────────────────────
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private val videoFrameRect = RectF()

    // ── Touch drag state ──────────────────────────────────────────────────────
    private var dragStartRawY: Float = 0f
    private var dragStartPosYPercent: Float = 0f
    private var isDragging: Boolean = false
    private var scaleDetector: android.view.ScaleGestureDetector? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        setStyle(styleConfig)

        scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val currentSize = styleConfig.textSizeSp
                var newSize = currentSize * detector.scaleFactor
                newSize = newSize.coerceIn(10f, 150f)
                if (abs(newSize - currentSize) > 0.5f) {
                    onSizeChanged?.invoke(newSize)
                }
                return true
            }
        })
    }

    fun setVideoDimensions(videoW: Int, videoH: Int) {
        if (videoW <= 0 || videoH <= 0) return
        videoWidth = videoW
        videoHeight = videoH
        recomputeVideoFrameRect()
        invalidate()
    }

    private fun recomputeVideoFrameRect() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || videoWidth <= 0 || videoHeight <= 0) {
            videoFrameRect.set(0f, 0f, vw.coerceAtLeast(1f), vh.coerceAtLeast(1f))
            return
        }
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val viewAspect  = vw / vh
        val frameW: Float
        val frameH: Float
        if (videoAspect > viewAspect) {
            frameW = vw
            frameH = vw / videoAspect
        } else {
            frameH = vh
            frameW = vh * videoAspect
        }
        val left   = (vw - frameW) / 2f
        val top    = (vh - frameH) / 2f
        videoFrameRect.set(left, top, left + frameW, top + frameH)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeVideoFrameRect()
    }

    fun updatePlaybackState(block: SrtBlock?, positionMs: Long) {
        val blockChanged = (activeBlock?.hashCode() != block?.hashCode())
        val posChanged   = (currentVideoPositionMs != positionMs)

        if (!blockChanged && !posChanged) return

        val newId = block?.hashCode() ?: -1
        if (newId != lastActiveBlockId) {
            animStartMs = System.currentTimeMillis()
            lastActiveBlockId = newId
        }

        activeBlock = block
        currentVideoPositionMs = positionMs
        invalidate()
    }

    fun setStyle(config: SubtitleStyleConfig) {
        styleConfig = config
        renderer = SubtitleOverlayRenderer(context, config)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val block = activeBlock ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return

        if (videoFrameRect.isEmpty) recomputeVideoFrameRect()

        val frameW = videoFrameRect.width()
        val frameH = videoFrameRect.height()
        val frameL = videoFrameRect.left
        val frameT = videoFrameRect.top

        val elapsedMs = System.currentTimeMillis() - animStartMs

        val currentRenderer = renderer ?: return

        canvas.save()
        // Odsadíme canvas na začátek (top-left) video letterbox framu
        canvas.translate(frameL, frameT)
        // Předáme frameW a frameH do rendereru, aby měl stejné okraje jako exporter
        currentRenderer.drawFrame(canvas, block, currentVideoPositionMs, elapsedMs, frameW, frameH)
        canvas.restore()

        // Pokud animace není statická (např. probíhá WordPop, neon blikání, Typewriter),
        // vynutíme překreslení na další snímek (~60fps)
        if (!currentRenderer.isAnimationStatic(styleConfig.animationType)) {
            invalidate()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector?.onTouchEvent(event)

        if (scaleDetector?.isInProgress == true) {
            isDragging = false
            return true
        }

        if (videoFrameRect.isEmpty) recomputeVideoFrameRect()
        val frameH = videoFrameRect.height().coerceAtLeast(1f)
        val frameT = videoFrameRect.top

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val subtitleAbsY = frameT + frameH * styleConfig.posYPercent
                if (abs(event.y - subtitleAbsY) < 150f) {
                    dragStartRawY = event.rawY
                    dragStartPosYPercent = styleConfig.posYPercent
                    isDragging = true
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaY = event.rawY - dragStartRawY
                    val newPercent = (dragStartPosYPercent + deltaY / frameH).coerceIn(0.02f, 0.98f)
                    onPositionChanged?.invoke(newPercent)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return isDragging || scaleDetector?.isInProgress == true || super.onTouchEvent(event)
    }

    override fun getAccessibilityClassName(): CharSequence = SubtitleOverlayView::class.java.name
}
