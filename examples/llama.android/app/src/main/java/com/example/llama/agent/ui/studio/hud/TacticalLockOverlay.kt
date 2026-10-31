package com.example.llama.agent.ui.studio.hud

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.example.llama.agent.vision.BoundingBoxView.Detection

@Composable
fun TacticalLockOverlay(
    detections: List<Detection>,
    lockedId: String?,
    buffer: TacticalHistoryBuffer,
    theme: HudTheme,
    showTrace: Boolean,
    showVector: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (lockedId == null || theme == HudTheme.CLASSIC_OFF) {
        buffer.clear()
        return
    }

    val target = detections.find { it.label == lockedId } ?: return

    // Pre-alokované Zero-GC štětce, barvy se re-aplikují podle tématu
    val tracePaint = remember { android.graphics.Paint().apply { isAntiAlias = true } }
    val leadLinePaint = remember {
        android.graphics.Paint().apply {
            strokeWidth = 3f
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
        }
    }
    val crosshairPaint = remember {
        android.graphics.Paint().apply {
            strokeWidth = 4f
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        val cx = (target.rect.left + target.rect.right) / 2f * w
        val cy = (target.rect.top + target.rect.bottom) / 2f * h

        // 1. Záznam trasy do historie
        buffer.push(cx, cy, lockedId, System.currentTimeMillis())

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            
            // Aplikace aktuálních barev tématu do pre-alokovaných štětců
            tracePaint.color = theme.traceColor.toArgb()
            leadLinePaint.color = theme.leadColor.toArgb()
            crosshairPaint.color = theme.crosshairColor.toArgb()

            // 2. Motion Array (Trailing Path - Historie letu)
            if (showTrace) {
                var index = buffer.head - 1
                if (index < 0) index = buffer.maxPoints - 1

                for (i in 0 until buffer.size step 2) {
                    val px = buffer.historyX[index]
                    val py = buffer.historyY[index]

                    val ageRatio = 1f - (i.toFloat() / buffer.size)
                    tracePaint.alpha = (255 * ageRatio).toInt()
                    val radius = 5f * ageRatio

                    nativeCanvas.drawCircle(px, py, radius, tracePaint)

                    index--
                    if (index < 0) index = buffer.maxPoints - 1
                }
            }

            // 3. Lead Indicator (Predikce polohy za 0.5 vteřiny)
            if (showVector && theme.leadColor != Color.Transparent) {
                val (vx, vy) = buffer.getVelocityVector()
                if (kotlin.math.abs(vx) > 10f || kotlin.math.abs(vy) > 10f) {
                    val leadX = cx + vx * 0.5f
                    val leadY = cy + vy * 0.5f

                    nativeCanvas.drawLine(cx, cy, leadX, leadY, leadLinePaint)
                    nativeCanvas.drawCircle(leadX, leadY, 15f, leadLinePaint)
                }
            }

            // 4. Crosshair a Brackets
            val crosshairSize = 40f
            val bracketOffset = (target.rect.width() * w / 2f).coerceAtLeast(60f)

            when (theme.crosshairStyle) {
                CrosshairStyle.FULL_RETICLE -> {
                    // Středový kříž (s průhledem uprostřed 10px)
                    nativeCanvas.drawLine(cx - crosshairSize, cy, cx - 10f, cy, crosshairPaint)
                    nativeCanvas.drawLine(cx + 10f, cy, cx + crosshairSize, cy, crosshairPaint)
                    nativeCanvas.drawLine(cx, cy - crosshairSize, cx, cy - 10f, crosshairPaint)
                    nativeCanvas.drawLine(cx, cy + 10f, cx, cy + crosshairSize, crosshairPaint)
                    
                    // Kružnice kolem kříže
                    nativeCanvas.drawCircle(cx, cy, crosshairSize * 0.8f, crosshairPaint.apply { strokeWidth = 2f })
                    crosshairPaint.strokeWidth = 4f
                }
                CrosshairStyle.CIRCLE_ONLY -> {
                    nativeCanvas.drawCircle(cx, cy, bracketOffset, crosshairPaint)
                }
                CrosshairStyle.CORNERS_ONLY -> {
                    // Jen brackets, nic víc
                }
                CrosshairStyle.NONE -> {
                    // Žádný kříž
                }
            }

            // Fixační rohy (Brackets) - kreslíme vždy jako stabilní vizualizaci locku
            if (theme.crosshairStyle != CrosshairStyle.CIRCLE_ONLY) {
                val bSize = 25f
                nativeCanvas.drawLine(cx - bracketOffset, cy - bracketOffset, cx - bracketOffset + bSize, cy - bracketOffset, crosshairPaint)
                nativeCanvas.drawLine(cx - bracketOffset, cy - bracketOffset, cx - bracketOffset, cy - bracketOffset + bSize, crosshairPaint)
                nativeCanvas.drawLine(cx + bracketOffset, cy + bracketOffset, cx + bracketOffset - bSize, cy + bracketOffset, crosshairPaint)
                nativeCanvas.drawLine(cx + bracketOffset, cy + bracketOffset, cx + bracketOffset, cy + bracketOffset - bSize, crosshairPaint)
            }
            
            // 5. HUD Text pro cíl (Rychlost a vzdálenost - simulovaná)
            val textPaint = android.graphics.Paint().apply {
                color = theme.crosshairColor.toArgb()
                textSize = 30f
                typeface = android.graphics.Typeface.MONOSPACE
                setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
            }
            
            val hudText = "LOCKED // ${target.label.uppercase()}"
            nativeCanvas.drawText(hudText, cx + bracketOffset + 10f, cy - bracketOffset, textPaint)
        }
    }
}
