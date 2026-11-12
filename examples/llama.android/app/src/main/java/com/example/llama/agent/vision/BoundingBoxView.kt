package com.example.llama.agent.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

/**
 * BoundingBoxView — Enterprise-grade vizuální feedback vrstva
 *
 * Custom View pro vykreslování detekcí (obdélníky, labely, confidence) na transparentní overlay nad
 * ostatními aplikacemi.
 *
 * Inspirováno:
 * - Google ML Kit overlay pattern (coordinate-mapped custom View)
 * - Moderní UI principy: zaoblené rohy, semi-transparentní badge, anti-alias
 *
 * THREAD SAFETY:
 * - `updateDetections()` je thread-safe (synchronized + postInvalidate)
 * - `onDraw()` běží na UI threadu, kopíruje snapshot detekcí
 *
 * PERFORMANCE:
 * - Paint objekty jsou pre-alokované (žádné GC pressure v onDraw)
 * - Hardware-accelerated Canvas (LAYER_TYPE_HARDWARE)
 */
class BoundingBoxView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "BoundingBoxView"

        // Defaultní barvy pro různé kategorie detekcí
        val COLORS =
                intArrayOf(
                        Color.rgb(76, 175, 80), // Zelená
                        Color.rgb(33, 150, 243), // Modrá
                        Color.rgb(255, 152, 0), // Oranžová
                        Color.rgb(156, 39, 176), // Fialová
                        Color.rgb(0, 188, 212), // Cyan
                        Color.rgb(244, 67, 54), // Červená
                        Color.rgb(255, 235, 59), // Žlutá
                        Color.rgb(121, 85, 72) // Hnědá
                )

        // Konverze dp na px
        private fun dpToPx(dp: Float, context: Context): Float {
            return dp * context.resources.displayMetrics.density
        }

        // Minimal threshold for rendering to eliminate low-confidence noise (< 30%)
        const val MIN_DISPLAY_CONFIDENCE = 0.30f

        // Non-Maximum Suppression IoU threshold for merging overlapping detections of same class
        const val DEFAULT_IOU_THRESHOLD = 0.45f

        /**
         * Vypočítá IoU (Intersection over Union) mezi dvěma obdélníky.
         */
        fun calculateIoU(a: RectF, b: RectF): Float {
            val interLeft = maxOf(a.left, b.left)
            val interTop = maxOf(a.top, b.top)
            val interRight = minOf(a.right, b.right)
            val interBottom = minOf(a.bottom, b.bottom)

            if (interLeft >= interRight || interTop >= interBottom) return 0f

            val interArea = (interRight - interLeft) * (interBottom - interTop)
            val areaA = (a.right - a.left) * (a.bottom - a.top)
            val areaB = (b.right - b.left) * (b.bottom - b.top)
            val unionArea = areaA + areaB - interArea

            return if (unionArea > 0f) interArea / unionArea else 0f
        }

        /**
         * Aplikuje NMS (Non-Maximum Suppression) a filtr minimální konfidence.
         */
        fun filterDetections(
            rawDetections: List<Detection>,
            minConfidence: Float = MIN_DISPLAY_CONFIDENCE,
            iouThreshold: Float = DEFAULT_IOU_THRESHOLD
        ): List<Detection> {
            val valid = rawDetections.filter { it.confidence >= minConfidence }
            if (valid.size <= 1) return valid

            val result = mutableListOf<Detection>()
            val byClass = valid.groupBy { it.label }

            for ((_, classDetections) in byClass) {
                val sorted = classDetections.sortedByDescending { it.confidence }.toMutableList()

                while (sorted.isNotEmpty()) {
                    val best = sorted.removeAt(0)
                    result.add(best)

                    val iterator = sorted.iterator()
                    while (iterator.hasNext()) {
                        val other = iterator.next()
                        if (calculateIoU(best.rect, other.rect) > iouThreshold) {
                            iterator.remove()
                        }
                    }
                }
            }
            return result
        }
    }

    /**
     * Datová třída reprezentující jednu detekci objektu.
     *
     * @param rect Obdélník detekce v souřadnicích View (px)
     * @param label Textový popis detekce (např. "cat", "Tlačítko Nastavení")
     * @param confidence Spolehlivost detekce (0.0–1.0)
     * @param colorIndex Index do pole COLORS (automatický round-robin)
     * @param isPredictive True pokud box pochází z predikce (okluze), ne z reálné detekce.
     *        Vykreslí se jako poloprůhledný čárkovaný obdélník (Ghost Box).
     */
    data class Detection(
            val id: Int = -1,
            val rect: RectF,
            val label: String,
            val confidence: Float = 1.0f,
            val colorIndex: Int = 0,
            val isPredictive: Boolean = false,
            // Kinematika extrahovaná z Kalmanova filtru
            val vx: Float = 0f,
            val vy: Float = 0f,
            val vw: Float = 0f,
            val vh: Float = 0f,
            // Vizuální modifikátory (Threat, Swarm, TTC)
            val threatLevel: Float = 0f, 
            val customTags: List<String> = emptyList(),
            val trailPath: android.graphics.Path? = null
    )

    // Thread-safe snapshot detekcí pro onDraw
    @Volatile private var detections: List<Detection> = emptyList()
    private val lock = Any()

    // Pre-alokované Paint objekty (žádné alokace v onDraw → zero GC pressure)
    private val boxPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2.5f, context)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = dpToPx(12f, context)
                isFakeBoldText = true
                setShadowLayer(dpToPx(1f, context), 0f, dpToPx(0.5f, context), Color.BLACK)
            }

    private val heatmapPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(3f, context)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

    private val cornerRadius = dpToPx(6f, context)
    private val badgePaddingH = dpToPx(6f, context)
    private val badgePaddingV = dpToPx(3f, context)

    // Pre-alokovaný Paint pro Ghost Box (prediktivní dashed obdélník)
    private val ghostBoxPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dpToPx(2f, context)
                pathEffect = android.graphics.DashPathEffect(floatArrayOf(
                    dpToPx(8f, context), dpToPx(4f, context)
                ), 0f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }

    // Pre-alokované objekty pro onDraw, aby nevznikal GC tlak (DrawAllocation fix)
    private val drawRect = RectF()
    private val badgeRect = RectF()
    private val transformMatrix = android.graphics.Matrix()
    private val drawPath = android.graphics.Path()

    init {
        // Transparentní pozadí, hardware-accelerated
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Thread-safe aktualizace seznamu detekcí. Volá se z libovolného vlákna (CameraX analyzer,
     * coroutine, ...).
     *
     * @param newDetections Nový seznam detekcí k vykreslení
     */
    fun updateDetections(newDetections: List<Detection>) {
        val filtered = filterDetections(newDetections)
        synchronized(lock) {
            detections = filtered
        }
        postInvalidate() // UI-thread safe redraw trigger
    }

    /** Vyčistí všechny detekce a překreslí (prázdný overlay). */
    fun setResults(newDetections: List<Detection>) {
        val filtered = if (newDetections.isEmpty()) emptyList() else filterDetections(newDetections)
        synchronized(lock) { 
            detections = filtered
        }
        postInvalidate()
    }

    /** Zpětně kompatibilní alias — volají SmartVisionOverlayManager a FloatingResultPopup. */
    fun clearDetections() {
        setResults(emptyList())
    }

    /**
     * Vrací klikatelnou oblast (Region) složenou pouze z okrajů (hran) všech bounding boxů.
     * Vnitřní část boxu není součástí regionu, což umožňuje "prokliknutí" skrz overlay na OS vrstvu.
     */
    fun getTouchableRegion(): android.graphics.Region {
        val region = android.graphics.Region()
        val currentDetections: List<Detection>
        synchronized(lock) { currentDetections = detections }

        val currentWidth = width.toFloat()
        val currentHeight = height.toFloat()
        
        // Klikatelná hrana bude mít tloušťku 30px (15px dovnitř, 15px ven od čáry)
        val tolerance = dpToPx(15f, context).toInt()

        for (detection in currentDetections) {
            val left = (detection.rect.left * currentWidth).toInt()
            val top = (detection.rect.top * currentHeight).toInt()
            val right = (detection.rect.right * currentWidth).toInt()
            val bottom = (detection.rect.bottom * currentHeight).toInt()

            // Horní hrana
            region.op(left - tolerance, top - tolerance, right + tolerance, top + tolerance, android.graphics.Region.Op.UNION)
            // Dolní hrana
            region.op(left - tolerance, bottom - tolerance, right + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
            // Levá hrana
            region.op(left - tolerance, top - tolerance, left + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
            // Pravá hrana
            region.op(right - tolerance, top - tolerance, right + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
        }
        return region
    }

    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_DOWN) {
            val x = event.x.toInt()
            val y = event.y.toInt()
            
            // Ověříme, zda kliknutí padlo na některou z hran
            val region = getTouchableRegion()
            if (region.contains(x, y)) {
                val currentWidth = width.toFloat()
                val currentHeight = height.toFloat()
                val tolerance = dpToPx(15f, context).toInt()
                
                var clickedDetection: Detection? = null
                
                synchronized(lock) {
                    for (detection in detections) {
                        val left = (detection.rect.left * currentWidth).toInt()
                        val top = (detection.rect.top * currentHeight).toInt()
                        val right = (detection.rect.right * currentWidth).toInt()
                        val bottom = (detection.rect.bottom * currentHeight).toInt()
                        
                        val boxRegion = android.graphics.Region()
                        boxRegion.op(left - tolerance, top - tolerance, right + tolerance, top + tolerance, android.graphics.Region.Op.UNION)
                        boxRegion.op(left - tolerance, bottom - tolerance, right + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
                        boxRegion.op(left - tolerance, top - tolerance, left + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
                        boxRegion.op(right - tolerance, top - tolerance, right + tolerance, bottom + tolerance, android.graphics.Region.Op.UNION)
                        
                        if (boxRegion.contains(x, y)) {
                            clickedDetection = detection
                            break
                        }
                    }
                }
                
                if (clickedDetection != null) {
                    android.util.Log.i(TAG, "👆 Kliknuto na hranu objektu: ${clickedDetection?.label}")
                    com.example.llama.agent.perception.PerceptionEventBus.tryEmit(
                        com.example.llama.agent.perception.UiActionEvent(
                            action = com.example.llama.agent.perception.UiAction.OBJECT_CLICKED,
                            payload = clickedDetection?.label
                        )
                    )
                    return true // Dotyk je zkonzumován (neprojde na OS)
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentDetections = synchronized(lock) { detections }

        if (currentDetections.isEmpty()) return

        val currentWidth = width.toFloat()
        val currentHeight = height.toFloat()

        for (detection in currentDetections) {
            // Mírná úprava barvy podle threatLevel
            val safeColorIndex = maxOf(0, detection.colorIndex) // Pojistka proti záporným indexům
            var baseColor = COLORS[safeColorIndex % COLORS.size]
            if (detection.threatLevel > 0.5f) {
                // Přechod do červené barvy varování
                baseColor = Color.RED
            }
            
            // Zde překládáme normalizované souřadnice (0-1) na fyzické pixely displeje
            drawRect.set(
                detection.rect.left * currentWidth,
                detection.rect.top * currentHeight,
                detection.rect.right * currentWidth,
                detection.rect.bottom * currentHeight
            )
            
            // Scalovani Heatmap (Trail) Path z 0-1 na pixely displeje
            if (detection.trailPath != null) {
                drawPath.set(detection.trailPath) // Fast copy bez nové alokace
                transformMatrix.setScale(currentWidth, currentHeight)
                drawPath.transform(transformMatrix)
                
                heatmapPaint.color = Color.argb(150, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor))
                canvas.drawPath(drawPath, heatmapPaint)
            }

            drawDetection(canvas, detection, drawRect, baseColor)
        }
    }

    /** Vykreslí jednu detekci: obdélník se zaoblenými rohy + label badge. */
    private fun drawDetection(canvas: Canvas, detection: Detection, rect: RectF, color: Int) {
        if (detection.isPredictive) {
            // Ghost Box: poloprůhledný čárkovaný obdélník (predikce při okluzi)
            val ghostAlpha = 90 // ~35% průhlednost
            ghostBoxPaint.color = Color.argb(ghostAlpha, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, ghostBoxPaint)
            
            // Jemný průhledný fill
            fillPaint.color = Color.argb(10, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        } else {
            // Standardní pevný bounding box
            // 1. Obdélník — zaoblené rohy, stroke
            boxPaint.color = color
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, boxPaint)

            // 2. Tenký fill uvnitř boxu (10% alpha pro vizuální kontrast)
            fillPaint.color = Color.argb(25, Color.red(color), Color.green(color), Color.blue(color))
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint)
        }

        // 3. Label badge (nad horní hranou boxu)
            // Sloučíme base label a případné vlastní tagy ze Swarm nebo TTC
            val displayTags = mutableListOf(detection.label)
            displayTags.addAll(detection.customTags)
            
            val displayText = displayTags.joinToString(" | ") + String.format(
                Locale.US,
                " (%.1f%%)",
                detection.confidence * 100
            )

            if (displayText.isNotBlank()) {
                // Calculate text bounds
                val textWidth = textPaint.measureText(displayText)
                val textHeight = textPaint.textSize

                val badgeLeft = rect.left
                val badgeTop = max(0f, rect.top - textHeight - badgePaddingV * 2)
                val badgeRight = badgeLeft + textWidth + badgePaddingH * 2
                val badgeBottom = rect.top

                // Badge pozadí (semi-transparentní)
                fillPaint.color =
                        Color.argb(200, Color.red(color), Color.green(color), Color.blue(color))
                badgeRect.set(badgeLeft, badgeTop, badgeRight, badgeBottom)
                canvas.drawRoundRect(badgeRect, cornerRadius / 2, cornerRadius / 2, fillPaint)

                // Badge text
                canvas.drawText(
                        displayText,
                        badgeLeft + badgePaddingH,
                        badgeBottom - badgePaddingV,
                        textPaint
                )
            }
    }
}
