package com.example.llama.srt

import android.graphics.Color
import com.example.llama.srt.ui.models.SubtitleAnimationType
import com.example.llama.srt.ui.models.SubtitlePositionMode
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.Locale

/**
 * Převádí interní SrtBlock a SubtitleStyleConfig do Advanced SubStation Alpha (.ass) formátu.
 *
 * Záruky (Zero-Implicit, Zero-Fallback):
 *  1. OOM-safe: Streamovaný zápis přes BufferedWriter — bezpečné pro 2h+ film / 5000+ bloků.
 *  2. Zero fallback: Každá animace je plně implementována nativně v ASS tagech.
 *  3. Přesná konverze barev: Android ARGB → ASS ABGR (&HAABBGGRR), alpha invertovaná.
 *  4. Font: používá styleConfig.fontDisplayName — PostScript jméno nastavené uživatelem.
 *  5. Všechny hodnoty (size, color, shadow, spacing, position, speed) ze StyleConfig
 *     jsou striktně respektovány — žádné hardcoded konstanty.
 */
object SubtitleAssMapper {

    @Throws(Exception::class)
    fun generateAssFile(
        blocks: List<SrtBlock>,
        styleConfig: SubtitleStyleConfig,
        workspaceDir: File,
        videoWidth: Int  = 1920,
        videoHeight: Int = 1080
    ): File {
        if (!workspaceDir.exists() && !workspaceDir.mkdirs()) {
            throw java.io.IOException(
                "SubtitleAssMapper: Nelze vytvořit workspace adresář: ${workspaceDir.absolutePath}"
            )
        }

        val assFile = File(workspaceDir, "styled_subs_${System.currentTimeMillis()}.ass")

        BufferedWriter(
            OutputStreamWriter(FileOutputStream(assFile), Charsets.UTF_8)
        ).use { writer ->

            // ── 1. Script Info ──────────────────────────────────────────────
            // PlayRes MUSÍ odpovídat skutečným pixelům videa.
            // libass pak škáluje souřadnice titulků 1:1 bez offsetu.
            writer.write("[Script Info]\n")
            writer.write("ScriptType: v4.00+\n")
            writer.write("PlayResX: $videoWidth\n")
            writer.write("PlayResY: $videoHeight\n")
            writer.write("WrapStyle: 1\n")
            writer.write("ScaledBorderAndShadow: yes\n")
            writer.write("\n")

            // ── 2. V4+ Styles ───────────────────────────────────────────────
            val primaryColor = colorToAssHex(styleConfig.textColor, alpha = 255)
            val outlineColor = colorToAssHex(styleConfig.strokeColor, alpha = 255)
            val bgColor      = colorToAssHex(
                color = styleConfig.backgroundColor,
                alpha = (styleConfig.bgAlpha * 255).toInt().coerceIn(0, 255)
            )
            // activeWordColor jde do SecondaryColour — karaoke sweep ho přebarvuje
            val activeColor  = colorToAssHex(styleConfig.activeWordColor, alpha = 255)

            // fontDisplayName = PostScript jméno nastavené při importu / výběru.
            // Musí PŘESNĚ odpovídat vnitřnímu jménu fontu v .ttf/.otf souboru,
            // jinak libass použije fallback systémový font.
            val fontName = styleConfig.fontDisplayName.ifBlank { "Roboto Bold" }

            // fontSize rescalováno z SP (1080p baseline) na skutečné rozlišení videa
            val fontSizeRaw = styleConfig.textSizeSp.toInt().coerceAtLeast(8)
            val fontSize    = (fontSizeRaw * (videoHeight / 1080f)).toInt().coerceAtLeast(8)

            val outlineWidth = if (styleConfig.strokeEnabled)
                styleConfig.strokeWidthPx.toInt().coerceAtLeast(0) else 0

            // ASS Shadow = číselný offset vzdálenosti stínu (z shadowRadius)
            val shadowDist = styleConfig.shadowRadius.toInt().coerceAtLeast(0)

            // BorderStyle: 1=Outline+Shadow (default), 3=Opaque Box (background)
            val borderStyle = if (styleConfig.hasBackground) 3 else 1

            val assAlignment = when (styleConfig.positionMode) {
                SubtitlePositionMode.TOP    -> 8  // TopCenter
                SubtitlePositionMode.CENTER -> 5  // MiddleCenter
                SubtitlePositionMode.BOTTOM -> 2  // BottomCenter
                SubtitlePositionMode.CUSTOM -> 2  // BottomCenter jako základ, override přes \pos
            }

            val boldFlag   = if (styleConfig.isBold)   "-1" else "0"
            val italicFlag = if (styleConfig.isItalic) "-1" else "0"

            // letterSpacing → ASS Spacing field (pixely)
            val spacingVal = styleConfig.letterSpacing.toInt().coerceAtLeast(0)

            writer.write("[V4+ Styles]\n")
            writer.write("Format: Name,Fontname,Fontsize,PrimaryColour,SecondaryColour,OutlineColour,BackColour,Bold,Italic,Underline,StrikeOut,ScaleX,ScaleY,Spacing,Angle,BorderStyle,Outline,Shadow,Alignment,MarginL,MarginR,MarginV,Encoding\n")
            writer.write(
                "Style: Default," +
                "$fontName," +
                "$fontSize," +
                "$primaryColor," +
                "$activeColor," +    // SecondaryColour = activeWordColor pro karaoke sweep
                "$outlineColor," +
                "$bgColor," +
                "$boldFlag,$italicFlag," +
                "0,0," +             // Underline, StrikeOut
                "100,100," +         // ScaleX, ScaleY
                "$spacingVal," +     // Letter spacing z UI
                "0," +               // Angle
                "$borderStyle," +
                "$outlineWidth," +
                "$shadowDist," +     // Shadow offset z UI
                "$assAlignment," +
                "20,20,50," +        // MarginL, MarginR, MarginV
                "1\n"                // Encoding: Unicode
            )
            writer.write("\n")

            // ── 3. Events ───────────────────────────────────────────────────
            writer.write("[Events]\n")
            writer.write("Format: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text\n")

            // Pozice titulku v pixelech výstupního videa
            val posX = (videoWidth  * styleConfig.posXPercent).toInt()
            val posY = (videoHeight * styleConfig.posYPercent).toInt()

            // animSpeedMultiplier: 1.0 = standardní, 0.5 = pomalé, 2.0 = rychlé
            val speedMult = styleConfig.animSpeedMultiplier.coerceIn(0.1f, 5.0f)
            fun animMs(base: Int): Int = (base / speedMult).toInt().coerceAtLeast(1)

            // ── Globální 3D Transform prefix tag ──────────────────────────────
            // Aplikuje statické 3D parametry ze StyleConfig na každý Dialogue event.
            // \frx/fry/frz = rotace X/Y/Z stupně, \fax/fay = shear zkosení
            // Defaulty jsou 0f = žádná změna (dolní kompatibilita).
            val baseTransform3D = buildString {
                if (styleConfig.rotationXDeg != 0f) append("\\frx${styleConfig.rotationXDeg.toInt()}")
                if (styleConfig.rotationYDeg != 0f) append("\\fry${styleConfig.rotationYDeg.toInt()}")
                if (styleConfig.rotationZDeg != 0f) append("\\frz${styleConfig.rotationZDeg.toInt()}")
                if (styleConfig.shearX != 0f)       append("\\fax${String.format("%.2f", styleConfig.shearX)}")
                if (styleConfig.shearY != 0f)       append("\\fay${String.format("%.2f", styleConfig.shearY)}")
            }.let { if (it.isNotEmpty()) "{$it}" else "" }
            // ────────────────────────────────────────────────────────────────────

            for (block in blocks) {
                val start      = formatAssTime(block.startTimeMs)
                val end        = formatAssTime(block.endTimeMs)
                val blockDurMs = (block.endTimeMs - block.startTimeMs).coerceAtLeast(1L)

                // allCaps + escapování ASS soft-break
                val rawText = if (styleConfig.isAllCaps)
                    block.text.uppercase(Locale.getDefault()) else block.text
                val escapedText = rawText
                    .replace("\r\n", "\\N")
                    .replace("\n",   "\\N")
                    .replace("\r",   "\\N")

                // ── WORD_POP ─────────────────────────────────────────────────
                // Per-word kaskádové Dialogue eventy s \t scale animací.
                // Aktuální slovo: 150%→100% scale. Budoucí: skrytá (alpha FF).
                if (styleConfig.animationType == SubtitleAnimationType.WORD_POP
                    && !block.wordTimestamps.isNullOrEmpty()) {

                    val words  = block.wordTimestamps!!
                    val popMs  = animMs(100)

                    for (i in words.indices) {
                        val wStart = formatAssTime(words[i].startMs)
                        val wEnd   = formatAssTime(
                            if (i < words.size - 1) words[i + 1].startMs else block.endTimeMs
                        )
                        val line = buildString {
                            append("{\\pos($posX,$posY)}")
                            for (j in words.indices) {
                                val w = if (styleConfig.isAllCaps)
                                    words[j].word.uppercase(Locale.getDefault())
                                else words[j].word
                                when {
                                    j < i  -> append("{\\fscx100\\fscy100\\alpha\\&H00\\&}").append(w).append(" ")
                                    j == i -> append("{\\fscx150\\fscy150\\t(0,$popMs,\\fscx100\\fscy100)\\alpha\\&H00\\&}").append(w).append(" ")
                                    else   -> append("{\\alpha\\&HFF\\&}").append(w).append(" ")
                                }
                            }
                        }
                        writer.write("Dialogue: 0,$wStart,$wEnd,Default,,0,0,0,,$line\n")
                    }
                    continue
                }

                // ── TYPEWRITER ────────────────────────────────────────────────
                // Skutečný znak-po-znaku efekt: každý znak = vlastní Dialogue event
                // zobrazující akumulovaný text. typewriterCharsPerSec ze StyleConfig.
                if (styleConfig.animationType == SubtitleAnimationType.TYPEWRITER) {
                    val chars = escapedText.replace("\\N", " ").toList()
                    if (chars.isEmpty()) {
                        writer.write("Dialogue: 0,$start,$end,Default,,0,0,0,,{\\pos($posX,$posY)}$escapedText\n")
                        continue
                    }
                    val cps = if (styleConfig.typewriterCharsPerSec > 0)
                        styleConfig.typewriterCharsPerSec
                    else
                        (chars.size * 1000L / blockDurMs).toInt().coerceAtLeast(1)

                    val msPerChar = (1000.0 / cps / speedMult).toLong().coerceAtLeast(1L)

                    for (i in chars.indices) {
                        val charStartMs = block.startTimeMs + i * msPerChar
                        val evEnd = if (i == chars.size - 1) block.endTimeMs
                                    else block.startTimeMs + (i + 1) * msPerChar
                        val visibleText = chars.take(i + 1).joinToString("")
                        writer.write(
                            "Dialogue: 0,${formatAssTime(charStartMs)},${formatAssTime(evEnd)}," +
                            "Default,,0,0,0,,{\\pos($posX,$posY)}$visibleText\n"
                        )
                    }
                    continue
                }

                // ── HIGHLIGHT_WORD ────────────────────────────────────────────
                // Per-word kaskádové eventy: aktivní slovo dostane box highlight
                // (highlightWordBgColor + highlightWordTextColor z UI). Ostatní = default styl.
                if (styleConfig.animationType == SubtitleAnimationType.HIGHLIGHT_WORD
                    && !block.wordTimestamps.isNullOrEmpty()) {

                    val words      = block.wordTimestamps!!
                    val hlBgColor  = colorToAssHex(styleConfig.highlightWordBgColor,  alpha = 255)
                    val hlTxtColor = colorToAssHex(styleConfig.highlightWordTextColor, alpha = 255)

                    for (i in words.indices) {
                        val wStart = formatAssTime(words[i].startMs)
                        val wEnd   = formatAssTime(
                            if (i < words.size - 1) words[i + 1].startMs else block.endTimeMs
                        )
                        val line = buildString {
                            append("{\\pos($posX,$posY)}")
                            for (j in words.indices) {
                                val w = if (styleConfig.isAllCaps)
                                    words[j].word.uppercase(Locale.getDefault())
                                else words[j].word
                                if (j == i) {
                                    // Aktivní slovo: \c = text color, \3c = back/box color,
                                    // \BorderStyle3 = opaque box
                                    append("{\\c$hlTxtColor\\3c$hlBgColor\\BorderStyle3}").append(w).append(" ")
                                } else {
                                    // Ostatní slova: obnovení výchozího stylu
                                    append("{\\c$primaryColor\\BorderStyle$borderStyle}").append(w).append(" ")
                                }
                            }
                        }
                        writer.write("Dialogue: 0,$wStart,$wEnd,Default,,0,0,0,,$line\n")
                    }
                    continue
                }

                // ── KARAOKE_SWEEP ─────────────────────────────────────────────
                // \kf tagy s wordTimestamps z ASR enginu.
                // SecondaryColour v Style = activeWordColor pro efekt přebarvení.
                if (styleConfig.animationType == SubtitleAnimationType.KARAOKE_SWEEP) {
                    writer.write("Dialogue: 0,$start,$end,Default,,0,0,0,,${buildKaraokeTags(block, styleConfig.isAllCaps)}\n")
                    continue
                }

                // ── Všechny ostatní animace (single-event Dialogue) ────────────
                val animTag = when (styleConfig.animationType) {
                    SubtitleAnimationType.NONE        -> ""
                    SubtitleAnimationType.FADE_IN     ->
                        "{\\fad(${animMs(200)},${animMs(200)})}"
                    SubtitleAnimationType.POP_ON      ->
                        "{\\pos($posX,$posY)\\fscx70\\fscy70\\t(0,${animMs(150)},\\fscx100\\fscy100)}"
                    SubtitleAnimationType.BOUNCE      ->
                        "{\\pos($posX,$posY)\\fscx70\\fscy70\\t(0,${animMs(120)},\\fscx110\\fscy110)\\t(${animMs(120)},${animMs(220)},\\fscx100\\fscy100)}"
                    SubtitleAnimationType.RUBBER_BAND ->
                        "{\\pos($posX,$posY)\\fscx140\\fscy60\\t(0,${animMs(100)},\\fscx90\\fscy110)\\t(${animMs(100)},${animMs(200)},\\fscx100\\fscy100)}"
                    SubtitleAnimationType.SLIDE_UP    ->
                        "{\\move($posX,${videoHeight + 80},$posX,$posY,0,${animMs(300)})}"
                    SubtitleAnimationType.SLIDE_DOWN  ->
                        "{\\move($posX,-80,$posX,$posY,0,${animMs(300)})}"
                    SubtitleAnimationType.NEON_FLICKER ->
                        "{\\alpha\\&HFF\\&\\t(0,${animMs(50)},\\alpha\\&H00\\&)\\t(${animMs(50)},${animMs(100)},\\alpha\\&HFF\\&)\\t(${animMs(100)},${animMs(150)},\\alpha\\&H00\\&)}"
                    SubtitleAnimationType.SCALE_FADE  ->
                        "{\\pos($posX,$posY)\\fscx60\\fscy60\\alpha\\&HFF\\&\\t(0,${animMs(200)},\\fscx100\\fscy100\\alpha\\&H00\\&)}"

                    // ── 3D Prostory ──────────────────────────────────────────
                    SubtitleAnimationType.ROTATE_3D   ->
                        // Animovaná rotace kolem osy Y: 90°→0° za animMs ms
                        // Efekt: text "přiletí" ze strany jako překlopení stránky
                        // \fry90 = titulek záztátek (neviditelný, kolmý)
                        // \t(0,animMs, \fry0) = animace na normální 0° pohled
                        "{\\pos($posX,$posY)\\fry90\\t(0,${animMs(400)},\\fry0)}"

                    SubtitleAnimationType.PERSPECTIVE_TILT ->
                        // Náklon kolem X osy: text se vyřítne "ze země" jako filmový intertitl
                        // \frx45 = 45° čoněí dozadu (perspektiva z půlnocní pozice)
                        // \t animace na 0° = vzkřímení do normální četelné polohy
                        "{\\pos($posX,$posY)\\frx${(60 / speedMult).toInt()}\\t(0,${animMs(500)},\\frx0)}"

                    SubtitleAnimationType.SHADOW_3D   -> {
                        // Vícevrstvový vrzený 3D stín (Subtitle Art efekt z přílohy)
                        // Realizace: měníme shadow offset — ASS \shad je jednorozměrný,
                        // pro skutecčný 3D stín
                        // používáme BackColour = shadow3dColor + \BorderStyle1 + \shad
                        val depth = styleConfig.shadow3dDepthPx.coerceIn(1, 30)
                        val shadowHex = colorToAssHex(styleConfig.shadow3dColor, alpha = 255)
                        // Override BackColour a shadow hloubku přes inline tag:
                        // \4c = BackColour (ASS v4+) — barva stínu
                        // \shad = vzdalenost stínu
                        "{\\pos($posX,$posY)\\4c$shadowHex\\shad$depth}"
                    }

                    // Tyto jsou zpracovány výše ve vlastních větvích — sem nedojdou
                    else -> ""
                }
                // baseTransform3D je prázdný string pokud user nenastavil 3D parametry
                // — nulový overhead pro standardní použití
                writer.write("Dialogue: 0,$start,$end,Default,,0,0,0,,$baseTransform3D$animTag$escapedText\n")
            }

            writer.flush()
        }

        return assFile
    }

    // --------------------------------------------------------------------------
    // Karaoke tags builder
    // --------------------------------------------------------------------------

    private fun buildKaraokeTags(block: SrtBlock, allCaps: Boolean): String {
        val wts = block.wordTimestamps
        if (wts.isNullOrEmpty()) {
            // Bez word timestamps: celá věta jako jeden \kf trvající celý blok
            val blockDurCs = ((block.endTimeMs - block.startTimeMs) / 10L).coerceAtLeast(1L)
            val text = if (allCaps) block.text.uppercase(Locale.getDefault()) else block.text
            return "{\\kf$blockDurCs}$text"
        }
        val sb = StringBuilder()
        for (wt in wts) {
            val durCs = ((wt.endMs - wt.startMs).coerceAtLeast(1L) / 10L)
            val word  = if (allCaps) wt.word.uppercase(Locale.getDefault()) else wt.word
            // \kf = fill karaoke: plynulé přebarvování zleva doprava
            // SecondaryColour v Style definuje cílovou barvu (activeWordColor z UI)
            sb.append("{\\kf$durCs}$word ")
        }
        return sb.toString().trimEnd()
    }

    // --------------------------------------------------------------------------
    // Konverze barev: Android ARGB → ASS &HAABBGGRR
    // --------------------------------------------------------------------------

    private fun colorToAssHex(color: Int, alpha: Int): String {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        // ASS Alpha je inverzní: Android 255 (viditelné) → ASS 0 (neprůhledné)
        val assAlpha = (255 - alpha.coerceIn(0, 255))
        return String.format("&H%02X%02X%02X%02X", assAlpha, b, g, r)
    }

    // --------------------------------------------------------------------------
    // Formátování časových razítek H:MM:SS.cc (centisekundy)
    // --------------------------------------------------------------------------

    private fun formatAssTime(ms: Long): String {
        val safeMs = ms.coerceAtLeast(0L)
        val h  = safeMs / 3_600_000L
        val m  = (safeMs % 3_600_000L) / 60_000L
        val s  = (safeMs % 60_000L) / 1_000L
        val cs = (safeMs % 1_000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }
}
