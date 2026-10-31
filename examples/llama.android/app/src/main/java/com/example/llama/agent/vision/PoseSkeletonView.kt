package com.example.llama.agent.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * PoseSkeletonView — Hardwarově akcelerované vykreslování lidské kinematiky pro IZS.
 * Zero-Allocation onDraw smyčka s filtry spolehlivosti a prevencí cross-linking šumu.
 * Očekává standardní 33-keypoint BlazePose / COCO topologii (MediaPipe PoseLandmarker).
 */
class PoseSkeletonView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val MIN_CONFIDENCE = 0.5f // Zvíšený filtr jistoty kloubů (50%) pro IZS spolehlivost
        private const val MIN_VALID_JOINTS = 5 // Požadujeme min. 5 platných kloubů pro vykreslení celé postavové kostry
        private const val MAX_BONE_NORM_DIST_SQ = 0.40f * 0.40f // Max nerealistická délka kosti (40% rozměru obrazovky)

        // BlazePose 33-point topologie (MediaPipe PoseLandmarker)
        private val BONE_PAIRS = listOf(
            // Obličej
            Pair(0, 1), Pair(1, 2), Pair(2, 3), Pair(3, 7),
            Pair(0, 4), Pair(4, 5), Pair(5, 6), Pair(6, 8),
            Pair(9, 10),
            // Trup
            Pair(11, 12), Pair(11, 23), Pair(12, 24), Pair(23, 24),
            // Levá paže + ruka
            Pair(11, 13), Pair(13, 15), Pair(15, 17), Pair(15, 19), Pair(15, 21), Pair(17, 19),
            // Pravá paže + ruka
            Pair(12, 14), Pair(14, 16), Pair(16, 18), Pair(16, 20), Pair(16, 22), Pair(18, 20),
            // Levá noha + chodidlo
            Pair(23, 25), Pair(25, 27), Pair(27, 29), Pair(29, 31), Pair(27, 31),
            // Pravá noha + chodidlo
            Pair(24, 26), Pair(26, 28), Pair(28, 30), Pair(30, 32), Pair(28, 32)
        )
    }

    private val lock = Any()
    private var currentSkeletons: List<com.example.llama.agent.perception.Skeleton> = emptyList()

    // Pre-alokované Paint objekty
    private val bonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF00") // Neonově zelená pro kosti
        strokeWidth = dpToPx(3f)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000") // Červená pro klouby
        style = Paint.Style.FILL
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun updateSkeletons(skeletons: List<com.example.llama.agent.perception.Skeleton>) {
        synchronized(lock) {
            currentSkeletons = skeletons.toList() // Defensive copy
        }
        postInvalidate()
    }

    fun clearSkeletons() {
        synchronized(lock) { currentSkeletons = emptyList() }
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val widthF = width.toFloat()
        val heightF = height.toFloat()
        val jointRadius = dpToPx(4f)

        val skeletonsSnapshot = synchronized(lock) { currentSkeletons.toList() }

        for (skeleton in skeletonsSnapshot) {
            val pts = skeleton.keyPoints
            if (pts.size < 33) continue // Bezpečnostní pojistka — BlazePose vyžaduje plných 33 bodů

            // Spočítáme platné klouby s dostatečnou jistotou
            val validJointsCount = pts.count { it.confidence >= MIN_CONFIDENCE }
            if (validJointsCount < MIN_VALID_JOINTS) continue // Zamezí náhodným skvrnám na pozadí

            // 1. Vykreslení kostí (Line) s filtrem nerealistické délky kosti (cross-linking mitigation)
            for (pair in BONE_PAIRS) {
                val p1 = pts[pair.first]
                val p2 = pts[pair.second]

                if (p1.confidence >= MIN_CONFIDENCE && p2.confidence >= MIN_CONFIDENCE) {
                    val dx = p1.x - p2.x
                    val dy = p1.y - p2.y
                    val distSq = dx * dx + dy * dy

                    // Pokud vzdálenost kloubů nepřesáhne limit pro lidskou anatomii na obrazovce
                    if (distSq <= MAX_BONE_NORM_DIST_SQ) {
                        canvas.drawLine(
                            p1.x * widthF, p1.y * heightF,
                            p2.x * widthF, p2.y * heightF,
                            bonePaint
                        )
                    }
                }
            }

            // 2. Vykreslení kloubů (Circle) navrch
            for (pt in pts) {
                if (pt.confidence >= MIN_CONFIDENCE) {
                    canvas.drawCircle(pt.x * widthF, pt.y * heightF, jointRadius, jointPaint)
                }
            }
        }
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
