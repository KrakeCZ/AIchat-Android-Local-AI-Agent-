package com.example.llama.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class ProfessionalVuMeterView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Theme {
        CLASSIC,
        MODERN
    }

    var currentTheme: Theme = Theme.CLASSIC
        set(value) {
            field = value
            invalidate()
        }

    private var targetDb: Float = -60f
    private var currentDb: Float = -60f
    private var targetPeakDb: Float = -60f
    private var currentPeakDb: Float = -60f
    private var peakHoldDb: Float = -60f
    private var peakHoldFrames: Int = 0

    private val minDb = -60f
    private val maxDb = 5f
    private val clipDb = -3f

    // Pre-allocated objects for zero GC
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1A1A1A") }
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()
    
    // Gradients
    private var classicGradient: Shader? = null
    private var modernGradient: Shader? = null

    fun updateDb(rmsDb: Float, peakDb: Float) {
        // Prevent NaN issues with valid numbers
        val validRms = if (rmsDb.isNaN() || rmsDb.isInfinite()) minDb else rmsDb
        val validPeak = if (peakDb.isNaN() || peakDb.isInfinite()) minDb else peakDb
        
        targetDb = validRms.coerceIn(minDb, maxDb)
        targetPeakDb = validPeak.coerceIn(minDb, maxDb)
        
        if (targetPeakDb > peakHoldDb) {
            peakHoldDb = targetPeakDb
            peakHoldFrames = 60 // Hold peak for ~1 second (at 60fps)
        }
        
        postInvalidateOnAnimation()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        
        // Classic: Green -> Yellow -> Red
        classicGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00FF00"), 
                Color.parseColor("#FFFF00"), 
                Color.parseColor("#FF0000")
            ),
            floatArrayOf(0.0f, 0.7f, 0.95f),
            Shader.TileMode.CLAMP
        )
        
        // Modern: Cyan/Blue -> Magenta -> Red
        modernGradient = LinearGradient(
            0f, 0f, w.toFloat(), 0f,
            intArrayOf(
                Color.parseColor("#00E5FF"), 
                Color.parseColor("#D500F9"), 
                Color.parseColor("#FF1744")
            ),
            floatArrayOf(0.0f, 0.6f, 0.9f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Interpolation (Smoothing)
        currentDb += (targetDb - currentDb) * 0.3f
        currentPeakDb += (targetPeakDb - currentPeakDb) * 0.4f
        
        if (peakHoldFrames > 0) {
            peakHoldFrames--
        } else {
            peakHoldDb -= 1.0f // Falloff
        }
        if (peakHoldDb < minDb) peakHoldDb = minDb

        // Draw Background
        if (currentTheme == Theme.MODERN) {
            bgPaint.color = Color.parseColor("#0A0A1A") // Deeper, bluish dark for modern
        } else {
            bgPaint.color = Color.parseColor("#1A1A1A")
        }
        rectF.set(0f, 0f, w, h)
        canvas.drawRoundRect(rectF, 8f, 8f, bgPaint)

        // Calculate ratios
        val dbRange = maxDb - minDb
        val ratio = max(0f, (currentDb - minDb) / dbRange)
        val peakRatio = max(0f, (peakHoldDb - minDb) / dbRange)
        val clipRatio = max(0f, (clipDb - minDb) / dbRange)

        val barRight = w * ratio
        val peakRight = w * peakRatio
        val clipLeft = w * clipRatio

        if (currentTheme == Theme.CLASSIC) {
            barPaint.shader = classicGradient
            barPaint.alpha = 255
            peakPaint.color = if (peakHoldDb > clipDb) Color.RED else Color.YELLOW
            
            // Draw continuous bar
            rectF.set(0f, 0f, barRight, h)
            canvas.drawRoundRect(rectF, 8f, 8f, barPaint)
            
            // Draw Peak Line
            rectF.set(peakRight - 4f, 0f, peakRight + 2f, h)
            canvas.drawRect(rectF, peakPaint)
            
            // Draw Clip Threshold marker
            peakPaint.color = Color.parseColor("#44FF0000")
            rectF.set(clipLeft, 0f, clipLeft + 2f, h)
            canvas.drawRect(rectF, peakPaint)

        } else {
            barPaint.shader = modernGradient
            peakPaint.color = if (peakHoldDb > clipDb) Color.parseColor("#FF1744") else Color.parseColor("#00E5FF")
            
            // Draw segmented bar (Modern look)
            val segmentCount = 40
            val gap = 4f
            val segmentWidth = (w - (gap * (segmentCount - 1))) / segmentCount
            
            val segmentsToDraw = (ratio * segmentCount).toInt()
            
            for (i in 0 until segmentCount) {
                val left = i * (segmentWidth + gap)
                val right = left + segmentWidth
                rectF.set(left, h * 0.1f, right, h * 0.9f) // Slight padding top/bottom
                
                if (i < segmentsToDraw) {
                    barPaint.alpha = 255
                } else {
                    barPaint.alpha = 40 // Ghost effect
                }
                canvas.drawRoundRect(rectF, 4f, 4f, barPaint)
            }
            
            // Draw Peak Segment
            val peakSegmentIndex = (peakRatio * segmentCount).toInt().coerceIn(0, segmentCount - 1)
            val pLeft = peakSegmentIndex * (segmentWidth + gap)
            val pRight = pLeft + segmentWidth
            rectF.set(pLeft, h * 0.1f, pRight, h * 0.9f)
            canvas.drawRoundRect(rectF, 4f, 4f, peakPaint)
        }

        // Request next frame if still moving
        if (kotlin.math.abs(targetDb - currentDb) > 0.5f || peakHoldFrames > 0 || peakHoldDb > minDb) {
            postInvalidateOnAnimation()
        }
    }
}
