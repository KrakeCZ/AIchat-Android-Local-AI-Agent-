package com.example.llama.agent.ui.studio.skywatcher

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llama.agent.vision.BoundingBoxView.Detection
import kotlin.math.max
import kotlin.math.sqrt
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun SkyWatcherScreenUi(
    detections: List<Detection>,
    lockedId: String?,
    rotatedBitmap: Bitmap?,
    isLupaActive: Boolean,
    isLabMode: Boolean,
    lupaResultBitmap: Bitmap?,
    activeTrackerName: String,
    showTrace: Boolean,
    showVector: Boolean,
    frameCount: Long,
    onUnlock: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Stav pro zvětšení teleskopu (velké vs malé kukátko)
    var isPipExpanded by remember { mutableStateOf(true) } // výchozí podle staré verze velké
    // Uživatelský zoom pomocí gest (výchozí 1.5x)
    var userZoom by remember { mutableStateOf(1.5f) }
    // Stav pro zobrazení Kalmanovy matematiky
    var isKalmanExpanded by remember { mutableStateOf(false) } // vyskakovací

    // Historie pozic pro zelené trasování
    val traceHistory = remember { mutableStateListOf<Offset>() }

    // Aktualizace historie podle frameCount
    LaunchedEffect(frameCount) {
        val target = detections.find { it.label == lockedId }
        if (target != null) {
            val cx = (target.rect.left + target.rect.right) / 2f
            val cy = (target.rect.top + target.rect.bottom) / 2f
            traceHistory.add(Offset(cx, cy))
            if (traceHistory.size > 80) {
                traceHistory.removeAt(0)
            }
        } else {
            if (traceHistory.isNotEmpty()) {
                // Postupné mizení
                traceHistory.removeAt(0)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // --- POZADÍ A HLAVNÍ MŘÍŽKA ---
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Použijeme frameCount, aby to Compose "přečetlo" a vynutilo překreslení
            val currentFrame = frameCount

            // Jemný mřížkový grid
            val step = 150f
            for (x in 0..size.width.toInt() step step.toInt()) {
                drawLine(Color(0x2200FFCC), start = Offset(x.toFloat(), 0f), end = Offset(x.toFloat(), size.height), strokeWidth = 1f)
            }
            for (y in 0..size.height.toInt() step step.toInt()) {
                drawLine(Color(0x2200FFCC), start = Offset(0f, y.toFloat()), end = Offset(size.width, y.toFloat()), strokeWidth = 1f)
            }

            // Velký centrální kruh a kříž
            drawCircle(Color(0x3300FFCC), radius = size.width * 0.35f, center = Offset(size.width/2, size.height/2), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawLine(Color(0x8800FFCC), start = Offset(size.width/2 - 30, size.height/2), end = Offset(size.width/2 + 30, size.height/2), strokeWidth = 2f)
            drawLine(Color(0x8800FFCC), start = Offset(size.width/2, size.height/2 - 30), end = Offset(size.width/2, size.height/2 + 30), strokeWidth = 2f)

            // --- ZELENÉ TRASOVÁNÍ (GREEN TAIL BLIPS) ---
            val target = detections.find { it.label == lockedId }
            if (target != null) {
                // Nakreslit box kolem cíle
                val bx = target.rect.left * size.width
                val by = target.rect.top * size.height
                val bw = target.rect.width() * size.width
                val bh = target.rect.height() * size.height
                drawRect(Color.Yellow, topLeft = Offset(bx, by), size = androidx.compose.ui.geometry.Size(bw, bh), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))

                // Zelená historie
                if (showTrace) {
                    for (i in 0 until traceHistory.size) {
                        val pt = traceHistory[i]
                        val alpha = (i.toFloat() / traceHistory.size.toFloat()).coerceIn(0.1f, 1f)
                        drawCircle(Color(0f, 1f, 0f, alpha), radius = 5f, center = Offset(pt.x * size.width, pt.y * size.height))
                    }
                }
            }
        }

        // TEXTY OKOLO OBJEKTU (LOCKED // TAP LOCK)
        val target = detections.find { it.label == lockedId }
        if (target != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                val bx = target.rect.left
                val by = target.rect.top
                Column(modifier = Modifier.fillMaxSize()) {
                    Spacer(modifier = Modifier.fillMaxHeight(by.coerceIn(0f, 0.9f)))
                    Row {
                        Spacer(modifier = Modifier.fillMaxWidth((bx + 0.05f).coerceIn(0f, 0.9f)))
                        Text("LOCKED // TAP LOCK", color = Color.Black, fontSize = 11.sp, modifier = Modifier.background(Color(0xFF00FFCC)).padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
            }
        }

        // --- 1. KUKÁTKO (Main PiP Teleskop) ---
        // Poklepáním se zvětší/zmenší
        val pipModifier = if (isPipExpanded) {
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 8.dp, start = 8.dp, end = 8.dp)
                .fillMaxWidth()
                .height(320.dp)
        } else {
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 16.dp)
                .size(240.dp, 160.dp)
        }

        Box(
            modifier = pipModifier
                .border(2.dp, Color(0xFF667788))
                .background(Color(0xFF001122))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { isPipExpanded = !isPipExpanded }
                    )
                }
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        userZoom = (userZoom * zoom).coerceIn(0.1f, 50f)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val currentFrame = frameCount // Zajišťuje recomposition

                if (isLupaActive && isLabMode && lupaResultBitmap != null) {
                    // LABS ON: SkyWatcher PiP přebírá LUPA matematiku (Lanczos, BlindDeconv, atd.)
                    val dstRect = Rect(0, 0, size.width.toInt(), size.height.toInt())
                    drawContext.canvas.nativeCanvas.drawBitmap(lupaResultBitmap, null, dstRect, null)

                    // Rámeček LUPA aktivní
                    drawRect(Color(0xFFFFCC00), style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
                } else {
                    // Fallback na nativní výřez z kamery
                    val bmp = rotatedBitmap ?: return@Canvas
                    val t = detections.find { it.label == lockedId }

                    val (cx, cy) = if (t != null) {
                        val cxPx = (t.rect.left + t.rect.right) / 2f * bmp.width
                        val cyPx = (t.rect.top + t.rect.bottom) / 2f * bmp.height
                        Pair(cxPx, cyPx)
                    } else {
                        Pair(bmp.width / 2f, bmp.height / 2f)
                    }

                    // Fixní zoom s podporou uživatelského gesta (vydělíme měřítko userZoom)
                    val scale = (if (isPipExpanded) 0.35f else 0.15f) / userZoom
                    val cropW = bmp.width * scale
                    val cropH = bmp.height * scale

                    val srcLeft = (cx - cropW/2).toInt().coerceIn(0, bmp.width)
                    val srcTop = (cy - cropH/2).toInt().coerceIn(0, bmp.height)
                    val srcRight = (cx + cropW/2).toInt().coerceIn(0, bmp.width)
                    val srcBottom = (cy + cropH/2).toInt().coerceIn(0, bmp.height)

                    val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                    val dstRect = Rect(0, 0, size.width.toInt(), size.height.toInt())
                    drawContext.canvas.nativeCanvas.drawBitmap(bmp, srcRect, dstRect, null)

                    // Kříž kukátka (stejný jako v oldGEN - žlutý kříž a kroužek)
                    drawLine(Color(0xFFFFCC00), Offset(size.width/2 - 25, size.height/2), Offset(size.width/2 + 25, size.height/2), strokeWidth = 2f)
                    drawLine(Color(0xFFFFCC00), Offset(size.width/2, size.height/2 - 25), Offset(size.width/2, size.height/2 + 25), strokeWidth = 2f)
                    drawCircle(Color(0xFFFFCC00), radius = 20f, center = Offset(size.width/2, size.height/2), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                }
            }
            Text(
                text = "ENGINE: $activeTrackerName",
                color = Color(0xFFFFCC00),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0x88000000))
            )
            val info = if (target != null) "X${(target.rect.left*1000).toInt()} Y${(target.rect.top*1000).toInt()} A5  TARGET ZOOM:MACRO" else "NO TARGET"
            Text(
                text = info,
                color = Color(0xFFFFCC00),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).background(Color(0x88000000))
            )

            // UNLOCK křížek, pokud je uzamčený cíl
            if (target != null) {
                Text(
                    text = "✖",
                    color = Color.Red,
                    fontSize = 26.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color(0x88000000), RoundedCornerShape(4.dp))
                        .clickable { onUnlock() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        // --- 2. ŠIROKOÚHLÝ TRACKER (Dříve Kalman Matematika) ---
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = if (isPipExpanded) 260.dp else 40.dp, start = 16.dp)
                .clickable { isKalmanExpanded = !isKalmanExpanded }
                .border(1.dp, Color(0xFF00FFCC))
                .background(Color(0xFF001122))
                .animateContentSize()
        ) {
            if (isKalmanExpanded) {
                // Sekundární PiP - "oddálený SW zoom"
                Box(modifier = Modifier.size(180.dp, 120.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val currentFrame = frameCount
                        val bmp = rotatedBitmap ?: return@Canvas
                        val t = detections.find { it.label == lockedId }

                        val (cx, cy) = if (t != null) {
                            val cxPx = (t.rect.left + t.rect.right) / 2f * bmp.width
                            val cyPx = (t.rect.top + t.rect.bottom) / 2f * bmp.height
                            Pair(cxPx, cyPx)
                        } else {
                            Pair(bmp.width / 2f, bmp.height / 2f)
                        }

                        // Oddálený zoom (širokoúhlý) - např. 0.8x
                        val wideScale = 0.8f
                        val cropW = bmp.width * wideScale
                        val cropH = bmp.height * wideScale

                        val srcLeft = (cx - cropW/2).toInt().coerceIn(0, bmp.width)
                        val srcTop = (cy - cropH/2).toInt().coerceIn(0, bmp.height)
                        val srcRight = (cx + cropW/2).toInt().coerceIn(0, bmp.width)
                        val srcBottom = (cy + cropH/2).toInt().coerceIn(0, bmp.height)

                        val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                        val dstRect = Rect(0, 0, size.width.toInt(), size.height.toInt())
                        drawContext.canvas.nativeCanvas.drawBitmap(bmp, srcRect, dstRect, null)

                        // Vykreslení bounding boxu v oddáleném pohledu
                        if (t != null) {
                            // Přepočet souřadnic cíle do lokálního Canvasu širokoúhlého PiP
                            val localTargetX = ((t.rect.left * bmp.width - srcLeft) / cropW) * size.width
                            val localTargetY = ((t.rect.top * bmp.height - srcTop) / cropH) * size.height
                            val localTargetW = ((t.rect.width() * bmp.width) / cropW) * size.width
                            val localTargetH = ((t.rect.height() * bmp.height) / cropH) * size.height

                            drawRect(
                                color = Color.Red,
                                topLeft = Offset(localTargetX, localTargetY),
                                size = androidx.compose.ui.geometry.Size(localTargetW, localTargetH),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(2f)
                            )
                        }
                    }
                    Text(
                        "▼ WIDE ANGLE TRACKER",
                        color = Color(0xFF00FFCC),
                        fontSize = 10.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(Color(0xAA000000))
                    )
                }
            } else {
                // Sbalený stav
                Text("▶ TRACKING DATA", color = Color(0xFF00FFCC), fontSize = 10.sp, modifier = Modifier.padding(8.dp))
            }
        }
    }
}
