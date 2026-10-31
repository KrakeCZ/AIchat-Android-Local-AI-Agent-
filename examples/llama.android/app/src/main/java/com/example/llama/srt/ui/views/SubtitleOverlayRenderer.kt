package com.example.llama.srt.ui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.TypedValue
import com.example.llama.srt.SrtBlock
import com.example.llama.srt.ui.models.SubtitleAlignment
import com.example.llama.srt.ui.models.SubtitleAnimationType
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Enterprise-grade Canvas Renderer pro Video Export a Real-time náhled.
 *
 * Design contract:
 * - Zero object allocation uvnitř drawFrame() — žádné GC pauzy.
 * - Všechny Paint / RectF / shader objekty jsou pre-alokované jako class-level fields.
 * - Animační stav je vypočítán z [elapsedMs] — žádný ValueAnimator, žádná korutina.
 * - [isAnimationStatic] slouží exporteru pro Zero-Copy cache busting.
 * - [drawFrame] přijímá [videoW]/[videoH] pro správnou SP→PX scale bez hardcodu.
 */
class SubtitleOverlayRenderer(
    private val context: Context,
    private val styleConfig: SubtitleStyleConfig
) {

    // ── Pre-alokované Paint objekty (zero-alloc garantováno) ──────────────────
    private val fillPaint        = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val strokePaint      = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style      = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap  = Paint.Cap.ROUND
    }
    private val activeWordPaint  = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val sweepPaint       = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val glowPaint        = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bgPaint          = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val highlightBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val anchorLinePaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { 
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // Pre-alokované geometrické objekty
    private val bgRect            = RectF()
    private val highlightWordRect = RectF()

    // Typeface cache — vyhýbá se I/O na hot path
    private var cachedTypeface: Typeface? = null
    private var lastFontPath: String = ""

    // Flag: applyStyleInternal byl volán poprvé v init, ale ne s reálným videoH
    // Při prvním drawFrame() se style aplikuje znovu s reálným videoH
    private var isStyleApplied: Boolean = false
    
    // Faktor pro zvětšení UI pixelových hodnot (stroke, padding) při exportu do vyššího rozlišení
    private var pxScaleRatio: Float = 1f

    init {
        // Předaplikuje styl při inicializaci — font cache se naplní ihned.
        // drawFrame() pak při prvním volání spustí applyStyleInternal znovu
        // s reálnými rozměry canvasu (pro budoucí škálovací logiku).
        applyStyleInternal(styleConfig, videoH = 0)
        isStyleApplied = false  // resetujeme flag, aby drawFrame() spustil re-apply
    }

    data class WordToken(
        val word: String,
        val rawWord: String,
        var x: Float = 0f,
        var y: Float = 0f,
        val width: Float = 0f,
        var startMs: Long = -1L,
        var endMs: Long = -1L
    )

    private var cachedLayoutBlockHash: Int = -1
    private var cachedLayoutTokens: List<WordToken> = emptyList()
    private var cachedViewW: Float = -1f
    private var cachedText: String = ""

    private fun getLayout(text: String, block: SrtBlock, viewW: Float): List<WordToken> {
        val blockHash = System.identityHashCode(block)
        if (blockHash == cachedLayoutBlockHash && viewW == cachedViewW && text == cachedText) {
            return cachedLayoutTokens
        }
        
        cachedLayoutTokens = calculateLayout(text, block, viewW)
        cachedLayoutBlockHash = blockHash
        cachedViewW = viewW
        cachedText = text
        return cachedLayoutTokens
    }

    private fun calculateLayout(text: String, block: SrtBlock, viewW: Float): List<WordToken> {
        val REFERENCE_WIDTH_PX = 1280f
        val marginPx = if (viewW > 0f) {
            styleConfig.marginHorizontalDp * (viewW / REFERENCE_WIDTH_PX)
        } else {
            styleConfig.marginHorizontalDp * context.resources.displayMetrics.density
        }
        val maxWidth = (viewW - 2 * marginPx).coerceAtLeast(1f)

        // Split text by space
        val rawWords = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val tokens = mutableListOf<WordToken>()
        
        // Match with block.wordTimestamps if possible
        val wts = block.wordTimestamps
        val useWts = !wts.isNullOrEmpty() && wts.size == rawWords.size

        val totalChars = rawWords.sumOf { it.length }
        val durationMs = (block.endTimeMs - block.startTimeMs).coerceAtLeast(1L)
        var currentMs = block.startTimeMs

        for (i in rawWords.indices) {
            val rawWord = rawWords[i]
            // We append space to every word except maybe the last, but for measuring and visual spacing, appending space is fine.
            val wordStr = if (styleConfig.isAllCaps) "${rawWord.uppercase()} " else "$rawWord "
            val w = fillPaint.measureText(wordStr)
            
            var start = -1L
            var end = -1L
            if (useWts) {
                start = wts!![i].startMs
                end = wts[i].endMs
            } else if (totalChars > 0) {
                val wordDuration = (durationMs * rawWord.length) / totalChars
                start = currentMs
                end = currentMs + wordDuration
                currentMs = end
            }
            
            tokens.add(WordToken(word = wordStr, rawWord = rawWord, width = w, startMs = start, endMs = end))
        }

        // Line breaking
        val lines = mutableListOf<List<WordToken>>()
        var currentLine = mutableListOf<WordToken>()
        var currentLineWidth = 0f

        for (token in tokens) {
            if (currentLine.isEmpty()) {
                currentLine.add(token)
                currentLineWidth += token.width
            } else if (currentLineWidth + token.width <= maxWidth) {
                currentLine.add(token)
                currentLineWidth += token.width
            } else {
                lines.add(currentLine)
                currentLine = mutableListOf(token)
                currentLineWidth = token.width
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        // Assign X and Y
        val lineHeight = fillPaint.fontSpacing * 1.25f // 25% padding between lines so they don't overlap
        
        // posY in rendering methods is treated as the baseline of the text.
        // We will make posY the baseline of the FIRST line, and subsequent lines go downwards.
        // If we want it to grow upwards, we would make it baseline of the LAST line.
        // The original view grew downwards from posY. We'll stick to downward growth from the first line.
        
        var currentY = 0f
        
        for (line in lines) {
            val lineWidth = line.sumOf { it.width.toDouble() }.toFloat()
            var currentX = when (styleConfig.textAlignment) {
                SubtitleAlignment.LEFT -> marginPx
                SubtitleAlignment.RIGHT -> viewW - marginPx - lineWidth
                SubtitleAlignment.CENTER -> {
                    val base = viewW * styleConfig.posXPercent - lineWidth / 2f
                    base.coerceIn(marginPx, max(marginPx, viewW - marginPx - lineWidth))
                }
            }
            
            for (token in line) {
                token.x = currentX
                token.y = currentY
                currentX += token.width
            }
            currentY += lineHeight
        }
        return tokens
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interní aplikace stylu — volá se jednorázově při změně stylu nebo videoH
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyStyleInternal(cfg: SubtitleStyleConfig, videoH: Int) {
        // Typeface — načtení s cache pomocí FontRegistry (žádné filesystem hits na hot path)
        if (cfg.fontAssetPath != lastFontPath) {
            cachedTypeface = com.example.llama.srt.ui.graphics.FontRegistry.getTypeface(context, cfg.fontAssetPath)
            lastFontPath = cfg.fontAssetPath
        }

        val typefaceStyle = when {
            cfg.isBold && cfg.isItalic -> Typeface.BOLD_ITALIC
            cfg.isBold                 -> Typeface.BOLD
            cfg.isItalic               -> Typeface.ITALIC
            else                       -> Typeface.NORMAL
        }
        val styledTypeface = Typeface.create(cachedTypeface, typefaceStyle)

        // SP → PX — dvě cesty podle kontextu volání:
        //
        // A) EXPORT path (videoH > 0):
        //    Plně device-independent normalizace vůči výšce exportní bitmapy.
        //    Rozsah reálných zařízení: DPI 70–4200, displeje 320px–4K, různá nastavení
        //    minimální šířky (sw) → displayMetrics.scaledDensity se může lišit faktorem 60×.
        //    Použití displayMetrics v export path by způsobilo, že na 70dpi zařízení je text
        //    miniaturní a na 4200dpi obrovský v IDENTICKÉM výstupním videu — neakceptovatelné.
        //
        //    Referenční výška 720px (HD baseline):
        //      36sp @ 720p  → 36 × 1.00 = 36px  (5.0% výšky videa)
        //      36sp @ 1080p → 36 × 1.50 = 54px  (5.0% výšky videa)
        //      36sp @ 2160p → 36 × 3.00 = 108px (5.0% výšky videa)
        //    Text vždy zabírá identické % výšky videa — na každém zařízení.
        //
        // B) UI PREVIEW path (videoH = 0, voláno z init bloku):
        //    Standardní SP→PX přes displayMetrics — správné pro View canvas na fyzické obrazovce.
        //    drawFrame() spustí re-apply s reálným videoH při prvním snímku (viz isStyleApplied).
        val REFERENCE_HEIGHT_PX = 720f   // HD baseline: 1sp ≡ 1px @ 720p
        val scaledTextSize: Float
        if (videoH > 0) {
            val ratio = videoH.toFloat() / REFERENCE_HEIGHT_PX
            scaledTextSize = cfg.textSizeSp * ratio
            
            // Pro zachování vizuální proporce (stroke, shadow, padding) mezi preview (UI) a exportem (HD/4K video),
            // musíme najít poměr mezi vypočítaným exportním rozměrem a reálným DPI displeje.
            // Hodnoty v cfg (strokeWidthPx, shadowRadius) jsou ukládány jako fyzické pixely z UI, 
            // takže pro 4K export se musí úměrně zvětšit (např. 4px stroke na 4K videu by nebyl vidět).
            val uiTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSizeSp, context.resources.displayMetrics)
            pxScaleRatio = if (uiTextSize > 0f) scaledTextSize / uiTextSize else 1f
        } else {
            val dm = context.resources.displayMetrics
            scaledTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, cfg.textSizeSp, dm)
            pxScaleRatio = 1f
        }
        isStyleApplied = true

        val scaledStrokeWidth = cfg.strokeWidthPx * pxScaleRatio
        val scaledShadowRadius = cfg.shadowRadius * pxScaleRatio
        val scaledShadowDx = cfg.shadowDx * pxScaleRatio
        val scaledShadowDy = cfg.shadowDy * pxScaleRatio
        val hasShadow = scaledShadowRadius > 0f

        fillPaint.apply {
            typeface      = styledTypeface
            textSize      = scaledTextSize
            color         = cfg.textColor
            letterSpacing = cfg.letterSpacing
            if (hasShadow) setShadowLayer(scaledShadowRadius, scaledShadowDx, scaledShadowDy, cfg.shadowColor)
            else clearShadowLayer()
        }

        activeWordPaint.apply {
            typeface      = styledTypeface
            textSize      = scaledTextSize
            color         = cfg.activeWordColor
            letterSpacing = cfg.letterSpacing
            if (hasShadow) setShadowLayer(scaledShadowRadius, scaledShadowDx, scaledShadowDy, cfg.shadowColor)
            else clearShadowLayer()
        }

        sweepPaint.apply {
            typeface      = styledTypeface
            textSize      = scaledTextSize
            color         = cfg.activeWordColor
            letterSpacing = cfg.letterSpacing
            if (hasShadow) setShadowLayer(scaledShadowRadius, scaledShadowDx, scaledShadowDy, cfg.shadowColor)
            else clearShadowLayer()
        }

        strokePaint.apply {
            typeface      = styledTypeface
            textSize      = scaledTextSize
            color         = cfg.strokeColor
            strokeWidth   = scaledStrokeWidth
            letterSpacing = cfg.letterSpacing
        }

        glowPaint.apply {
            typeface = styledTypeface
            textSize = scaledTextSize
        }

        bgPaint.color = cfg.backgroundColor

        anchorLinePaint.apply {
            color = cfg.anchorLineColor
            strokeWidth = cfg.anchorLineThicknessPx * pxScaleRatio
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API — primární vykreslovací metoda
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Vykreslí jeden snímek titulku na [canvas].
     *
     * @param canvas   Cílový canvas (full-frame video bitmapa nebo View canvas)
     * @param block    Aktivní SRT blok
     * @param timeMs   Aktuální pozice přehrávání v ms (absolutní)
     * @param elapsedMs Čas od začátku zobrazení tohoto bloku (pro animace)
     * @param viewW    Šířka canvasu v px (= šířka videa při exportu)
     * @param viewH    Výška canvasu v px (= výška videa při exportu)
     */
    fun drawFrame(
        canvas: Canvas,
        block: SrtBlock,
        timeMs: Long,
        elapsedMs: Long,
        viewW: Float,
        viewH: Float
    ) {
        val cfg         = styleConfig
        val rawText     = block.text.ifBlank { return }
        val displayText = if (cfg.isAllCaps) rawText.uppercase() else rawText

        // První snímek exportu: init() nastavil isStyleApplied=false.
        // Re-aplikujeme styl zde — zajistí správný font/barvu/textSize na GL threadu.
        // Při všech dalších snímcích je isStyleApplied=true → zero overhead.
        if (!isStyleApplied) {
            applyStyleInternal(cfg, viewH.toInt())
        }

        val anim = computeAnimState(cfg.animationType, elapsedMs)

        // Transformační matice — zachovává identický přistup s SubtitleOverlayView
        val centerX = viewW * cfg.posXPercent
        val posY    = viewH * cfg.posYPercent
        canvas.save()

        // ── 3D Camera transformace (ROTATE_3D / PERSPECTIVE_TILT / StyleConfig 3D params) ──
        // Kombinujeme: 1) animační 3D (z computeAnimState), 2) statické 3D ze StyleConfig
        val rotY = anim.rotationY + cfg.rotationYDeg
        val rotX = anim.rotationX + cfg.rotationXDeg
        val rotZ = anim.rotationZ + cfg.rotationZDeg
        val has3D = rotY != 0f || rotX != 0f
        if (has3D) {
            val cam = android.graphics.Camera()
            val mat = android.graphics.Matrix()
            cam.save()
            if (rotY != 0f) cam.rotateY(rotY)
            if (rotX != 0f) cam.rotateX(rotX)
            cam.getMatrix(mat)
            cam.restore()
            // Střed rotace = střed textu (posX, posY)
            mat.preTranslate(-centerX, -posY)
            mat.postTranslate(centerX, posY)
            canvas.concat(mat)
        }
        if (rotZ != 0f) canvas.rotate(rotZ, centerX, posY)
        if (cfg.shearX != 0f || cfg.shearY != 0f) {
            val shearMat = android.graphics.Matrix()
            shearMat.setSkew(cfg.shearX, cfg.shearY, centerX, posY)
            canvas.concat(shearMat)
        }

        canvas.translate(centerX, posY + anim.translateY)
        canvas.scale(anim.scaleX, anim.scaleY)
        canvas.translate(-centerX, -(posY + anim.translateY))

        val tokens = getLayout(displayText, block, viewW)

        // Vykreslení kognitivní Anchor Line (pokud je povolena)
        if (cfg.anchorLinePosition != com.example.llama.srt.ui.models.AnchorLinePosition.DISABLED) {
            drawAnchorLine(canvas, tokens, viewW, posY, cfg)
        }

        when (cfg.animationType) {
            SubtitleAnimationType.WORD_POP       -> drawWordPop(canvas, tokens, timeMs, posY, anim)
            SubtitleAnimationType.KARAOKE_SWEEP  -> drawKaraokeSweep(canvas, tokens, timeMs, posY, anim)
            SubtitleAnimationType.HIGHLIGHT_WORD -> drawHighlightWord(canvas, tokens, timeMs, posY, anim)
            SubtitleAnimationType.TYPEWRITER     -> drawTypewriter(canvas, tokens, elapsedMs, timeMs, posY, anim)
            else                                  -> drawStaticBlock(canvas, tokens, timeMs, posY, anim)
        }

        if (cfg.glowLayerCount > 0) {
            drawGlowLayer(canvas, tokens, posY, anim)
        }

        canvas.restore()
    }

    /**
     * Vrací true pokud animace NEPOTŘEBUJE překreslení každý snímek.
     *
     * Pravidlo: POUZE [SubtitleAnimationType.NONE] je skutečně statický.
     * Exporter pak pro non-static typy vynucuje nový snímek každých 16 ms (≈ 60 fps),
     * čímž se karaoke sweep, typewriter i všechny kinetic efekty vypalují
     * jako plně animované sekvence.
     *
     * PROČ ne KARAOKE_SWEEP / WORD_POP / HIGHLIGHT_WORD:
     * - KARAOKE_SWEEP: sweep progress = (timeMs - block.startMs) / blockDuration → čistě časový,
     *   activeWordIdx zůstává -1 (block-level, ne word-level) → cache by nikdy nevypršela.
     * - WORD_POP: pop scale = overshootInterp(wordElapsed / animDurationMs) → časový per-word.
     * - HIGHLIGHT_WORD: zvýraznění se mění na word hranicích; pokud word timestamps chybí,
     *   renderuje se staticky přes drawStaticBlock → bezpečné.
     */
    fun isAnimationStatic(type: SubtitleAnimationType): Boolean =
        type == SubtitleAnimationType.NONE

    // ─────────────────────────────────────────────────────────────────────────
    // Sub-renderers (1:1 port z SubtitleOverlayView)
    // ─────────────────────────────────────────────────────────────────────────

    private fun drawAnchorLine(canvas: Canvas, tokens: List<WordToken>, viewW: Float, posY: Float, cfg: SubtitleStyleConfig) {
        if (tokens.isEmpty()) return
        val minX = tokens.minOf { it.x }
        val maxX = tokens.maxOf { it.x + it.width }
        val yOffset = posY + tokens.maxOf { it.y } + fillPaint.descent() + (cfg.backgroundVPaddingPx * pxScaleRatio)

        when (cfg.anchorLinePosition) {
            com.example.llama.srt.ui.models.AnchorLinePosition.UNDER_TEXT -> {
                canvas.drawLine(minX, yOffset, maxX, yOffset, anchorLinePaint)
            }
            com.example.llama.srt.ui.models.AnchorLinePosition.FULL_WIDTH -> {
                canvas.drawLine(0f, yOffset, viewW, yOffset, anchorLinePaint)
            }
            com.example.llama.srt.ui.models.AnchorLinePosition.LEFT_EDGE -> {
                val minTextY = posY + tokens.minOf { it.y } - fillPaint.textSize - (cfg.backgroundVPaddingPx * pxScaleRatio)
                val lineX = minX - (cfg.backgroundHPaddingPx * pxScaleRatio) - (cfg.anchorLineThicknessPx * pxScaleRatio * 2f)
                canvas.drawLine(lineX, minTextY, lineX, yOffset, anchorLinePaint)
            }
            com.example.llama.srt.ui.models.AnchorLinePosition.DISABLED -> {}
        }
    }

    private fun drawActiveWordBox(canvas: Canvas, token: WordToken, y: Float, anim: AnimState) {
        if (!styleConfig.activeWordBgEnabled) return
        val hp = styleConfig.backgroundHPaddingPx * pxScaleRatio
        val vp = styleConfig.backgroundVPaddingPx * pxScaleRatio
        val cornerRadius = styleConfig.backgroundCornerRadiusPx * pxScaleRatio

        highlightBgPaint.color = styleConfig.highlightWordBgColor
        highlightBgPaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
        highlightWordRect.set(token.x - hp, y - fillPaint.textSize - vp, token.x + token.width + hp, y + fillPaint.descent() + vp)
        canvas.drawRoundRect(highlightWordRect, cornerRadius, cornerRadius, highlightBgPaint)
    }

    private fun drawStaticBlock(
        canvas: Canvas, tokens: List<WordToken>, timeMs: Long, posY: Float, anim: AnimState
    ) {
        fillPaint.alpha   = (255 * anim.alpha).toInt().coerceIn(0, 255)
        strokePaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
        bgPaint.alpha     = (255 * anim.alpha).toInt().coerceIn(0, 255)

        if (tokens.isEmpty()) return

        val minX = tokens.minOf { it.x }
        val maxX = tokens.maxOf { it.x + it.width }
        val blockW = maxX - minX

        if (styleConfig.hasBackground) {
            val lines = tokens.groupBy { it.y }
            for ((y, lineTokens) in lines) {
                val lineMinX = lineTokens.first().x
                val lineMaxX = lineTokens.last().let { it.x + it.width }
                drawBackground(canvas, lineMinX, lineMaxX - lineMinX, posY + y, anim.alpha)
            }
        }

        if (styleConfig.useGradientText && blockW > 0f) {
            val minY = tokens.minOf { it.y }
            fillPaint.shader = LinearGradient(
                minX, posY + minY - fillPaint.textSize,
                maxX, posY + tokens.maxOf { it.y },
                intArrayOf(styleConfig.gradientColorStart, styleConfig.gradientColorEnd),
                null, Shader.TileMode.CLAMP
            )
        }

        for (token in tokens) {
            val y = posY + token.y
            
            val isActive = timeMs in token.startMs..token.endMs
            if (isActive && styleConfig.activeWordBgEnabled) {
                drawActiveWordBox(canvas, token, y, anim)
            }
            
            if (styleConfig.strokeEnabled && styleConfig.strokeWidthPx > 0f) {
                canvas.drawText(token.word, token.x, y, strokePaint)
            }
            canvas.drawText(token.word, token.x, y, fillPaint)
        }
        fillPaint.shader = null
    }

    private fun drawWordPop(
        canvas: Canvas, tokens: List<WordToken>,
        timeMs: Long, posY: Float, anim: AnimState
    ) {
        if (tokens.isEmpty()) return
        val hasTimestamps = tokens.any { it.startMs >= 0 }
        if (!hasTimestamps) { drawStaticBlock(canvas, tokens, timeMs, posY, anim); return }

        val lines = tokens.groupBy { it.y }
        if (styleConfig.hasBackground) {
            bgPaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
            for ((y, lineTokens) in lines) {
                val lineMinX = lineTokens.first().x
                val lineMaxX = lineTokens.last().let { it.x + it.width }
                drawBackground(canvas, lineMinX, lineMaxX - lineMinX, posY + y, anim.alpha)
            }
        }

        for (token in tokens) {
            val y = posY + token.y
            val isActive = timeMs in token.startMs..token.endMs
            val isFuture = timeMs < token.startMs

            val wordAlpha = when {
                isFuture -> 0.35f
                isActive -> 1.0f
                else     -> 0.85f
            }

            val wordElapsed = if (isActive) timeMs - token.startMs else Long.MAX_VALUE
            val popScale    = if (isActive && wordElapsed < styleConfig.animDurationMs) {
                val t = wordElapsed.toFloat() / styleConfig.animDurationMs
                0.75f + 0.25f * overshootInterp(t)
            } else 1.0f

            val paint         = if (isActive) activeWordPaint else fillPaint
            paint.alpha       = (255 * wordAlpha).toInt().coerceIn(0, 255)
            strokePaint.alpha = (255 * wordAlpha).toInt().coerceIn(0, 255)

            val pivotX = token.x + token.width / 2f
            canvas.save()
            canvas.scale(popScale, popScale, pivotX, y)
            
            if (isActive && styleConfig.activeWordBgEnabled) {
                drawActiveWordBox(canvas, token, y, anim)
            }
            
            if (styleConfig.strokeEnabled && styleConfig.strokeWidthPx > 0f) canvas.drawText(token.word, token.x, y, strokePaint)
            canvas.drawText(token.word, token.x, y, paint)
            canvas.restore()
        }
    }

    private fun drawKaraokeSweep(
        canvas: Canvas, tokens: List<WordToken>,
        timeMs: Long, posY: Float, anim: AnimState
    ) {
        fillPaint.alpha   = (255 * anim.alpha).toInt().coerceIn(0, 255)
        strokePaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)

        if (tokens.isEmpty()) return

        val minTime = tokens.first().startMs
        val maxTime = tokens.last().endMs
        val blockDuration = (maxTime - minTime).coerceAtLeast(1L)
        val sweepProgress = ((timeMs - minTime).toFloat() / blockDuration).coerceIn(0f, 1f)

        val totalW = tokens.sumOf { it.width.toDouble() }.toFloat()
        val currentSweepW = totalW * sweepProgress
        
        val lines = tokens.groupBy { it.y }
        if (styleConfig.hasBackground) {
            for ((y, lineTokens) in lines) {
                val lineMinX = lineTokens.first().x
                val lineMaxX = lineTokens.last().let { it.x + it.width }
                drawBackground(canvas, lineMinX, lineMaxX - lineMinX, posY + y, anim.alpha)
            }
        }

        var accumulatedW = 0f
        for (token in tokens) {
            val y = posY + token.y
            val isActive = timeMs in token.startMs..token.endMs
            if (isActive && styleConfig.activeWordBgEnabled) {
                drawActiveWordBox(canvas, token, y, anim)
            }

            if (styleConfig.strokeEnabled && styleConfig.strokeWidthPx > 0f) canvas.drawText(token.word, token.x, y, strokePaint)
            canvas.drawText(token.word, token.x, y, fillPaint)
            
            // Sweep logic per token
            val tokenStartW = accumulatedW
            val tokenEndW = accumulatedW + token.width
            if (currentSweepW > tokenStartW) {
                val sweepX = if (currentSweepW >= tokenEndW) {
                    token.x + token.width
                } else {
                    token.x + (currentSweepW - tokenStartW)
                }

                sweepPaint.color = styleConfig.activeWordColor
                sweepPaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
                val clipSave = canvas.save()
                canvas.clipRect(token.x, y - fillPaint.textSize * 1.2f, sweepX, y + fillPaint.descent())
                canvas.drawText(token.word, token.x, y, sweepPaint)
                canvas.restoreToCount(clipSave)
            }
            accumulatedW += token.width
        }
    }

    private fun drawHighlightWord(
        canvas: Canvas, tokens: List<WordToken>,
        timeMs: Long, posY: Float, anim: AnimState
    ) {
        if (tokens.isEmpty()) return
        val hasTimestamps = tokens.any { it.startMs >= 0 }
        if (!hasTimestamps) { drawStaticBlock(canvas, tokens, timeMs, posY, anim); return }

        val hp = styleConfig.backgroundHPaddingPx * pxScaleRatio
        val vp = styleConfig.backgroundVPaddingPx * pxScaleRatio
        val cornerRadius = styleConfig.backgroundCornerRadiusPx * pxScaleRatio

        for (token in tokens) {
            val y = posY + token.y
            val isActive = timeMs in token.startMs..token.endMs

            if (isActive) {
                highlightBgPaint.color = styleConfig.highlightWordBgColor
                highlightBgPaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
                highlightWordRect.set(token.x - hp, y - fillPaint.textSize - vp, token.x + token.width + hp, y + fillPaint.descent() + vp)
                canvas.drawRoundRect(highlightWordRect, cornerRadius, cornerRadius, highlightBgPaint)
                fillPaint.color = styleConfig.highlightWordTextColor
                fillPaint.alpha = (255 * anim.alpha).toInt().coerceIn(0, 255)
            } else {
                fillPaint.color = styleConfig.textColor
                fillPaint.alpha = (255 * anim.alpha * 0.80f).toInt().coerceIn(0, 255)
            }

            if (styleConfig.strokeEnabled && styleConfig.strokeWidthPx > 0f) canvas.drawText(token.word, token.x, y, strokePaint)
            canvas.drawText(token.word, token.x, y, fillPaint)
        }
        fillPaint.color = styleConfig.textColor
        fillPaint.alpha = 255
    }

    private fun drawTypewriter(
        canvas: Canvas, tokens: List<WordToken>, elapsedMs: Long, timeMs: Long,
        posY: Float, anim: AnimState
    ) {
        if (tokens.isEmpty()) return

        val charsPerSec  = styleConfig.typewriterCharsPerSec.takeIf { it > 0 } ?: 20
        val totalChars = tokens.sumOf { it.word.length }
        val visibleCount = ((elapsedMs / 1000f) * charsPerSec).toInt().coerceIn(0, totalChars)

        fillPaint.alpha   = (255 * anim.alpha).toInt().coerceIn(0, 255)
        strokePaint.alpha = fillPaint.alpha

        val lines = tokens.groupBy { it.y }
        if (styleConfig.hasBackground) {
            for ((y, lineTokens) in lines) {
                val lineMinX = lineTokens.first().x
                val lineMaxX = lineTokens.last().let { it.x + it.width }
                drawBackground(canvas, lineMinX, lineMaxX - lineMinX, posY + y, anim.alpha)
            }
        }

        var charsAccumulated = 0
        for (token in tokens) {
            val y = posY + token.y
            if (charsAccumulated >= visibleCount) break

            val remainingChars = visibleCount - charsAccumulated
            val visibleWord = if (remainingChars >= token.word.length) {
                token.word
            } else {
                token.word.substring(0, remainingChars)
            }

            val isActive = timeMs in token.startMs..token.endMs
            if (isActive && styleConfig.activeWordBgEnabled && remainingChars > 0) {
                drawActiveWordBox(canvas, token, y, anim)
            }

            if (styleConfig.strokeEnabled && styleConfig.strokeWidthPx > 0f) canvas.drawText(visibleWord, token.x, y, strokePaint)
            canvas.drawText(visibleWord, token.x, y, fillPaint)

            charsAccumulated += token.word.length
        }
    }

    private fun drawGlowLayer(canvas: Canvas, tokens: List<WordToken>, posY: Float, anim: AnimState) {
        if (tokens.isEmpty()) return
        val layers = styleConfig.glowLayerCount.coerceIn(1, 2)

        glowPaint.set(fillPaint)
        glowPaint.shader = null
        glowPaint.color  = styleConfig.shadowColor

        for (i in layers downTo 1) {
            val radius = styleConfig.shadowRadius * pxScaleRatio * i
            glowPaint.alpha = ((80 / layers) * anim.alpha).toInt().coerceIn(0, 255)
            glowPaint.setShadowLayer(radius, 0f, 0f, styleConfig.shadowColor)
            for (token in tokens) {
                canvas.drawText(token.word, token.x, posY + token.y, glowPaint)
            }
        }
        glowPaint.clearShadowLayer()
    }

    private fun drawBackground(canvas: Canvas, textX: Float, textW: Float, posY: Float, alpha: Float) {
        val hp = styleConfig.backgroundHPaddingPx * pxScaleRatio
        val vp = styleConfig.backgroundVPaddingPx * pxScaleRatio
        val cornerRadius = styleConfig.backgroundCornerRadiusPx * pxScaleRatio
        bgRect.set(textX - hp, posY - fillPaint.textSize - vp, textX + textW + hp, posY + fillPaint.descent() + vp)
        bgPaint.alpha = (255 * alpha * styleConfig.bgAlpha).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Animační stavový automat — pure math, zero allocation
    // Identický s SubtitleOverlayView.computeAnimState()
    // ─────────────────────────────────────────────────────────────────────────

    internal fun computeAnimState(type: SubtitleAnimationType, elapsedMs: Long): AnimState {
        val speedMult = styleConfig.animSpeedMultiplier.coerceIn(0.1f, 4.0f)
        val dur       = (styleConfig.animDurationMs / speedMult).coerceAtLeast(1f)

        return when (type) {
            SubtitleAnimationType.NONE,
            SubtitleAnimationType.WORD_POP,
            SubtitleAnimationType.KARAOKE_SWEEP,
            SubtitleAnimationType.HIGHLIGHT_WORD -> AnimState(alpha = 1f)

            SubtitleAnimationType.TYPEWRITER -> AnimState(alpha = 1f, needsNextFrame = true)

            SubtitleAnimationType.FADE_IN -> {
                val t = (elapsedMs / dur).coerceIn(0f, 1f)
                AnimState(alpha = fastOutSlowIn(t), needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.SCALE_FADE -> {
                val t     = (elapsedMs / dur).coerceIn(0f, 1f)
                val scale = 0.88f + 0.12f * fastOutSlowIn(t)
                AnimState(alpha = fastOutSlowIn(t), scaleX = scale, scaleY = scale, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.POP_ON -> {
                val t     = (elapsedMs / dur).coerceIn(0f, 1f)
                val scale = 0.70f + 0.30f * overshootInterp(t)
                AnimState(alpha = t.coerceIn(0f, 1f), scaleX = scale, scaleY = scale, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.RUBBER_BAND -> {
                val t      = (elapsedMs / dur).coerceIn(0f, 1f)
                val scaleX = when {
                    t < 0.3f -> 1f + 0.40f * (t / 0.3f)
                    t < 0.6f -> 1.40f - 0.55f * ((t - 0.3f) / 0.3f)
                    else     -> 0.85f + 0.15f * fastOutSlowIn((t - 0.6f) / 0.4f)
                }
                val scaleY = (1f / scaleX.coerceAtLeast(0.5f)).coerceIn(0.6f, 1.4f)
                AnimState(alpha = 1f, scaleX = scaleX, scaleY = scaleY, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.SLIDE_UP -> {
                val t    = (elapsedMs / (dur * 1.25f)).coerceIn(0f, 1f)
                val ease = fastOutSlowIn(t)
                AnimState(alpha = ease, translateY = (1f - ease) * 48f, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.SLIDE_DOWN -> {
                val t    = (elapsedMs / (dur * 1.25f)).coerceIn(0f, 1f)
                val ease = fastOutSlowIn(t)
                AnimState(alpha = ease, translateY = -(1f - ease) * 48f, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.BOUNCE -> {
                val bounceDur = 500f
                val t         = (elapsedMs / bounceDur).coerceIn(0f, 1f)
                val pulse     = 1f + 0.18f * sin(t * Math.PI.toFloat())
                AnimState(alpha = 1f, scaleX = pulse, scaleY = pulse, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.NEON_FLICKER -> {
                val flickerDur = dur * 0.6f
                val alpha      = if (elapsedMs < flickerDur) {
                    val phase = (elapsedMs / (flickerDur / 5f)).toInt() % 2
                    if (phase == 0) 0.15f else 1.0f
                } else 1.0f
                AnimState(alpha = alpha, needsNextFrame = elapsedMs < flickerDur)
            }

    // ── 3D Prostory ──────────────────────────────────────────────────────────
            SubtitleAnimationType.ROTATE_3D -> {
                val animDur = (400f / styleConfig.animSpeedMultiplier.coerceIn(0.1f, 5.0f))
                val t       = (elapsedMs / animDur).coerceIn(0f, 1f)
                // Y rotace 90°→0° za animDur ms — text "přiletí" ze strany
                val angleY  = 90f * (1f - fastOutSlowIn(t))
                AnimState(rotationY = angleY, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.PERSPECTIVE_TILT -> {
                val animDur = (500f / styleConfig.animSpeedMultiplier.coerceIn(0.1f, 5.0f))
                val t       = (elapsedMs / animDur).coerceIn(0f, 1f)
                // X náklon 60°→0° — filmový intertitl
                val angleX  = 60f * (1f - fastOutSlowIn(t))
                AnimState(rotationX = angleX, needsNextFrame = t < 1f)
            }

            SubtitleAnimationType.SHADOW_3D -> {
                AnimState(alpha = 1f, needsNextFrame = false)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Math helpers — identické s SubtitleOverlayView
    // ─────────────────────────────────────────────────────────────────────────

    private fun fastOutSlowIn(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t
        else 1f - (-2f * t + 2f).pow(3f) / 2f

    private fun overshootInterp(t: Float): Float {
        val c1 = 1.70158f
        val c3 = c1 + 1f
        return if (t >= 1f) 1f
        else 1f + c3 * (t - 1f).pow(3f) + c1 * (t - 1f).pow(2f)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Interní datová struktura — ne data class (žádné alokace na hot path)
    // ─────────────────────────────────────────────────────────────────────────

    internal class AnimState(
        val alpha: Float         = 1f,
        val scaleX: Float        = 1f,
        val scaleY: Float        = 1f,
        val translateY: Float    = 0f,
        val translateX: Float    = 0f,
        val rotationZ: Float     = 0f,
        val rotationY: Float     = 0f,   // 3D Y-osa (Camera API)
        val rotationX: Float     = 0f,   // 3D X-osa (Camera API)
        val needsNextFrame: Boolean = false
    )
}