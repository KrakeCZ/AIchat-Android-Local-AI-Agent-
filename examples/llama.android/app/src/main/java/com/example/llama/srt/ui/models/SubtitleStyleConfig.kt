package com.example.llama.srt.ui.models

import android.graphics.Color
import android.os.Parcelable
import androidx.annotation.ColorInt
import kotlinx.parcelize.Parcelize

// ─────────────────────────────────────────────────────────────────────────────
// Enums
// ─────────────────────────────────────────────────────────────────────────────

enum class SubtitlePresetId {
    TIKTOK_WORD_POP,
    KARAOKE_SWEEP,
    HORMOZI_BOLD,
    MINIMAL_CLEAN,
    CLASSIC_FILM,
    CUSTOM // Přidáno pro komunitní JSON šablony
}

enum class SubtitleAnimationType {
    // Základní
    NONE,
    FADE_IN,
    // Kinetické (scale-based)
    POP_ON,           // TikTok klasika — rázný nárůst z 70%
    BOUNCE,           // Průchodný skůček při vstupu
    RUBBER_BAND,      // Přetažení ve scale (gumový efekt) — Instagram trending
    // Pohybové
    SLIDE_UP,         // Material plynulý sjezd zdola
    SLIDE_DOWN,       // Sjezd shora (kino titulky)
    TYPEWRITER,       // Psaní znak po znaku — Podcast styl
    // Barevné / Word-level
    WORD_POP,         // Alex Hormozi styl — slovo po slovu s přeskakem
    KARAOKE_SWEEP,    // Plynulé přebarvování zleva doprava
    HIGHLIGHT_WORD,   // Každé aktivní slovo dostane barevný podsvit (box)
    // Neon / Glow efekty
    NEON_FLICKER,     // Rychlé blikání alfa při vstupu — retro neon efekt
    // Combo
    SCALE_FADE,       // Scale + Fade zároveň — premium čistý styl
    // ── 3D Prostory (libass native \frx \fry \frz \fax \fay) ────────────
    ROTATE_3D,        // Animovaná 3D rotace kolem osy Y (překlopení ze strany)
    PERSPECTIVE_TILT, // Perspektivní náklon textu kolem osy X (filmové kino titulky)
    SHADOW_3D         // Hluboký vrzený stín simulující 3D prostor
}

enum class SubtitleAlignment { LEFT, CENTER, RIGHT }

enum class SubtitlePositionMode { TOP, CENTER, BOTTOM, CUSTOM }

// Kognitivní kotevní bod (Vodící linka - WCAG 3.0 orientace)
enum class AnchorLinePosition { DISABLED, UNDER_TEXT, FULL_WIDTH, LEFT_EDGE }

// ─────────────────────────────────────────────────────────────────────────────
// Immutable State Config
// ─────────────────────────────────────────────────────────────────────────────

@Parcelize
data class SubtitleStyleConfig(
    val presetId: SubtitlePresetId = SubtitlePresetId.MINIMAL_CLEAN,
    val fontAssetPath: String = "fonts/Roboto-Bold.ttf",
    val fontDisplayName: String = "Roboto Bold",
    val textSizeSp: Float = 36f,
    @ColorInt val textColor: Int = Color.WHITE,
    @ColorInt val activeWordColor: Int = Color.parseColor("#FFEA00"),
    val strokeWidthPx: Float = 4f,
    @ColorInt val strokeColor: Int = Color.BLACK,
    val hasBackground: Boolean = false,
    @ColorInt val backgroundColor: Int = Color.parseColor("#80000000"),
    val backgroundCornerRadiusPx: Float = 16f,
    val backgroundHPaddingPx: Float = 20f,
    val backgroundVPaddingPx: Float = 10f,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    @ColorInt val shadowColor: Int = Color.TRANSPARENT,
    val animationType: SubtitleAnimationType = SubtitleAnimationType.WORD_POP,
    val animDurationMs: Long = 150L,
    val textAlignment: SubtitleAlignment = SubtitleAlignment.CENTER,
    val posYPercent: Float = 0.8f,
    val positionMode: SubtitlePositionMode = SubtitlePositionMode.BOTTOM,
    val isAllCaps: Boolean = false,
    val letterSpacing: Float = 0f,
    // Gradient text (2-color linear gradient — pro Instagram aesthetic styl)
    val useGradientText: Boolean = false,
    @ColorInt val gradientColorStart: Int = Color.parseColor("#FF6B6B"),
    @ColorInt val gradientColorEnd: Int = Color.parseColor("#FFE66D"),
    // Glow multi-layer (počet vrstev pro intenzivní neon efekt, 0 = disabled)
    val glowLayerCount: Int = 0,
    // Typewriter rychlost (znaky za vteřinu, 0 = celý text najednou)
    val typewriterCharsPerSec: Int = 0,
    // Highlight word background (pro HIGHLIGHT_WORD animaci)
    @ColorInt val highlightWordBgColor: Int = Color.parseColor("#FFFFFF"),
    @ColorInt val highlightWordTextColor: Int = Color.parseColor("#000000"),
    // ─── UI parametry ─────────────────────────────────────────
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val strokeEnabled: Boolean = false,
    val posXPercent: Float = 0.5f,
    val marginHorizontalDp: Int = 24,
    val animSpeedMultiplier: Float = 1.0f,
    val bgAlpha: Float = 0.5f,
    // ─── Kognitivní Přístupnost (WCAG 3.0) ────────────────────
    val anchorLinePosition: AnchorLinePosition = AnchorLinePosition.DISABLED,
    @ColorInt val anchorLineColor: Int = Color.parseColor("#40FFFFFF"), // Jemná alpha pro omezení vizuálního šumu
    val anchorLineThicknessPx: Float = 4f,
    val activeWordBgEnabled: Boolean = false,
    // ─── 3D Prostory (libass native ASS tagy) ─────────────────────────
    // \frx = naklonění kolem osy X (perspektiva shora/zdola, stupně)
    val rotationXDeg: Float = 0f,
    // \fry = rotace kolem osy Y (překlopení ze strany, stupně)
    val rotationYDeg: Float = 0f,
    // \frz = rotace v rovně obrazovky (klasické natočení, stupně)
    val rotationZDeg: Float = 0f,
    // \fax = shear (zkosí) podél osy X (0.0 = rovné, 0.3 = zkosené)
    val shearX: Float = 0f,
    // \fay = shear podél osy Y
    val shearY: Float = 0f,
    // Hloubka 3D stínu pro SHADOW_3D (počet vrstev a offset, 1-20)
    val shadow3dDepthPx: Int = 6,
    @ColorInt val shadow3dColor: Int = Color.parseColor("#80000000")
) : Parcelable
