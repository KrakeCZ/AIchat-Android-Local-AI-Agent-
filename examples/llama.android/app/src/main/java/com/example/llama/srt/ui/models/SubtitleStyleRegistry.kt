package com.example.llama.srt.ui.models

import android.content.Context
import android.graphics.Color
import android.util.Log
import org.json.JSONObject
import java.io.File

object SubtitleStyleRegistry {

    private const val TAG = "SubtitleRegistry"
    private const val CUSTOM_TEMPLATES_DIR = "subtitle_templates"

    private val availableTemplates = mutableListOf<SubtitleStyleConfig>()

    /**
     * Inicializace - zavolej z Activity nebo ViewModelu při startu.
     */
    fun initialize(context: Context) {
        availableTemplates.clear()
        
        // 1. Záchranné tvrdé šablony (vždy přítomny)
        availableTemplates.add(tiktokWordPop())
        availableTemplates.add(karaokeSweep())
        availableTemplates.add(neonGlow())
        availableTemplates.add(hormoziBold())
        availableTemplates.add(minimalClean())
        availableTemplates.add(classicFilm())
        // Trending 2025 rozšíření
        availableTemplates.add(instagramAesthetic())
        availableTemplates.add(podcastClear())
        availableTemplates.add(filmNoirDark())
        availableTemplates.add(tiktokHighlight())
        availableTemplates.add(rubberBandFun())
        availableTemplates.add(typewriterRetro())
        availableTemplates.add(retroNeonFlicker())
        availableTemplates.add(gradientWave())
        availableTemplates.add(whiteBoxClean())
        availableTemplates.add(youtubeClassic())

        // 2. Načtení JSON open-source šablon z disku
        loadDynamicTemplates(context)
    }

    /**
     * Vrací kompletní seznam pro UI.
     */
    fun all(): List<SubtitleStyleConfig> {
        if (availableTemplates.isEmpty()) {
            availableTemplates.addAll(listOf(tiktokWordPop(), karaokeSweep(), neonGlow(), hormoziBold(), minimalClean(), classicFilm()))
        }
        return availableTemplates.toList()
    }

    private fun loadDynamicTemplates(context: Context) {
        try {
            val dir = File(context.filesDir, CUSTOM_TEMPLATES_DIR)
            if (!dir.exists()) dir.mkdirs()

            val jsonFiles = dir.listFiles { file -> file.extension.lowercase() == "json" } ?: return

            for (file in jsonFiles) {
                try {
                    val jsonString = file.readText(Charsets.UTF_8)
                    val json = JSONObject(jsonString)
                    
                    val dynamicConfig = SubtitleStyleConfig(
                        presetId = SubtitlePresetId.CUSTOM,
                        fontDisplayName = json.optString("fontDisplayName", "Custom Font"),
                        fontAssetPath = json.optString("fontAssetPath", "fonts/Roboto-Bold.ttf"),
                        textSizeSp = json.optDouble("textSizeSp", 36.0).toFloat(),
                        textColor = parseColorSafe(json.optString("textColor", "#FFFFFF")),
                        activeWordColor = parseColorSafe(json.optString("activeWordColor", "#FFEA00")),
                        strokeWidthPx = json.optDouble("strokeWidthPx", 0.0).toFloat(),
                        strokeColor = parseColorSafe(json.optString("strokeColor", "#000000")),
                        hasBackground = json.optBoolean("hasBackground", false),
                        backgroundColor = parseColorSafe(json.optString("backgroundColor", "#80000000")),
                        backgroundCornerRadiusPx = json.optDouble("backgroundCornerRadiusPx", 8.0).toFloat(),
                        backgroundHPaddingPx = json.optDouble("backgroundHPaddingPx", 20.0).toFloat(),
                        backgroundVPaddingPx = json.optDouble("backgroundVPaddingPx", 10.0).toFloat(),
                        shadowRadius = json.optDouble("shadowRadius", 0.0).toFloat(),
                        shadowDx = json.optDouble("shadowDx", 0.0).toFloat(),
                        shadowDy = json.optDouble("shadowDy", 0.0).toFloat(),
                        shadowColor = parseColorSafe(json.optString("shadowColor", "#00000000")),
                        animationType = parseAnimationType(json.optString("animationType", "FADE_IN")),
                        animDurationMs = json.optLong("animDurationMs", 200L),
                        posYPercent = json.optDouble("posYPercent", 0.8).toFloat(),
                        isAllCaps = json.optBoolean("isAllCaps", false),
                        letterSpacing = json.optDouble("letterSpacing", 0.0).toFloat()
                    )
                    availableTemplates.add(dynamicConfig)
                } catch (e: Exception) {
                    Log.e(TAG, "Chyba při parsování šablony ${file.name}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kritická chyba adresáře šablon", e)
        }
    }

    private fun parseColorSafe(hex: String): Int {
        return try { Color.parseColor(hex) } catch (e: Exception) { Color.WHITE }
    }

    private fun parseAnimationType(type: String): SubtitleAnimationType {
        return try { SubtitleAnimationType.valueOf(type.uppercase()) } catch (e: Exception) { SubtitleAnimationType.NONE }
    }

    /**
     * Smaže custom šablonu z disku a paměti na základě shody jména.
     */
    fun deleteTemplate(context: Context, config: SubtitleStyleConfig): Boolean {
        if (config.presetId != SubtitlePresetId.CUSTOM) return false // Nelze smazat hardcoded šablony
        try {
            val dir = File(context.filesDir, CUSTOM_TEMPLATES_DIR)
            val jsonFiles = dir.listFiles { file -> file.extension.lowercase() == "json" } ?: return false
            for (file in jsonFiles) {
                val jsonString = file.readText(Charsets.UTF_8)
                val json = JSONObject(jsonString)
                // Pro smazání musí sedět fontDisplayName, textColor a backgroundColor (jako unikátní heuristika)
                if (json.optString("fontDisplayName") == config.fontDisplayName &&
                    parseColorSafe(json.optString("textColor")) == config.textColor) {
                    
                    val deleted = file.delete()
                    if (deleted) {
                        availableTemplates.remove(config)
                    }
                    return deleted
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při mazání šablony: ${e.message}")
        }
        return false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JÁDRO (CORE) ZÁCHRANNÉ ŠABLONY 
    // ─────────────────────────────────────────────────────────────────────────

    private fun tiktokWordPop() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.TIKTOK_WORD_POP,
        fontAssetPath         = "fonts/Roboto-Black.ttf",
        fontDisplayName       = "Roboto Black",
        textSizeSp            = 40f,
        textColor             = Color.WHITE,
        activeWordColor       = Color.parseColor("#FFEA00"),
        strokeWidthPx         = 10f,
        strokeColor           = Color.BLACK,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#CC000000"),
        backgroundCornerRadiusPx = 22f,
        backgroundHPaddingPx  = 28f,
        backgroundVPaddingPx  = 14f,
        animationType         = SubtitleAnimationType.WORD_POP,
        animDurationMs        = 120L,
        posYPercent           = 0.82f,
        positionMode          = SubtitlePositionMode.BOTTOM,
    )

    private fun karaokeSweep() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.KARAOKE_SWEEP,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "Roboto Bold",
        textSizeSp            = 36f,
        textColor             = Color.parseColor("#CCFFFFFF"),
        activeWordColor       = Color.parseColor("#FFD700"),
        strokeWidthPx         = 6f,
        strokeColor           = Color.parseColor("#BB000000"),
        hasBackground         = false,
        animationType         = SubtitleAnimationType.KARAOKE_SWEEP,
        animDurationMs        = 200L,
        posYPercent           = 0.85f,
        positionMode          = SubtitlePositionMode.BOTTOM,
        shadowRadius          = 4f,
        shadowDy              = 2f,
        shadowColor           = Color.parseColor("#88000000"),
    )

    private fun neonGlow() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Black.ttf",
        fontDisplayName       = "Cyberpunk Neon",
        textSizeSp            = 44f,
        textColor             = Color.WHITE,
        activeWordColor       = Color.parseColor("#00FFFF"), // Cyan glow word
        strokeWidthPx         = 2f,
        strokeColor           = Color.parseColor("#000000"),
        hasBackground         = false,
        shadowRadius          = 25f,
        shadowDx              = 0f,
        shadowDy              = 0f,
        shadowColor           = Color.parseColor("#FF00FF"), // Magenta heavy glow
        animationType         = SubtitleAnimationType.POP_ON,
        animDurationMs        = 180L,
        posYPercent           = 0.80f,
        positionMode          = SubtitlePositionMode.BOTTOM,
        isAllCaps             = true
    )

    private fun hormoziBold() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.HORMOZI_BOLD,
        fontAssetPath         = "fonts/Impact.ttf",
        fontDisplayName       = "Impact",
        textSizeSp            = 52f,
        isAllCaps             = true,
        textColor             = Color.WHITE,
        activeWordColor       = Color.parseColor("#FFEA00"),
        strokeWidthPx         = 14f,
        strokeColor           = Color.BLACK,
        hasBackground         = false,
        animationType         = SubtitleAnimationType.POP_ON,
        animDurationMs        = 150L,
        posYPercent           = 0.78f,
        positionMode          = SubtitlePositionMode.BOTTOM,
    )

    private fun minimalClean() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.MINIMAL_CLEAN,
        fontAssetPath         = "fonts/Roboto-Regular.ttf",
        fontDisplayName       = "Roboto",
        textSizeSp            = 28f,
        textColor             = Color.WHITE,
        activeWordColor       = Color.WHITE,
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#99000000"),
        backgroundCornerRadiusPx = 8f,
        backgroundHPaddingPx  = 20f,
        backgroundVPaddingPx  = 10f,
        animationType         = SubtitleAnimationType.FADE_IN,
        animDurationMs        = 250L,
        posYPercent           = 0.90f,
        positionMode          = SubtitlePositionMode.BOTTOM,
    )

    private fun classicFilm() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CLASSIC_FILM,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "Roboto Bold",
        textSizeSp            = 32f,
        textColor             = Color.parseColor("#FFFDE7"),
        activeWordColor       = Color.parseColor("#FFFDE7"),
        strokeWidthPx         = 3f,
        strokeColor           = Color.parseColor("#66000000"),
        hasBackground         = false,
        shadowRadius          = 8f,
        shadowDy              = 3f,
        shadowColor           = Color.parseColor("#AA000000"),
        animationType         = SubtitleAnimationType.FADE_IN,
        animDurationMs        = 300L,
        posYPercent           = 0.92f,
        positionMode          = SubtitlePositionMode.BOTTOM,
    )

    // ─────────────────────────────────────────────────────────────────────────
    // ROZŠÍŘENÉ ŠABLONY (20+) — Trending 2025
    // ─────────────────────────────────────────────────────────────────────────

    private fun instagramAesthetic() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Light.ttf",
        fontDisplayName       = "Instagram Aesthetic",
        textSizeSp            = 30f,
        textColor             = Color.parseColor("#FFFFFF"),
        activeWordColor       = Color.parseColor("#FF6B9D"),
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#4D000000"),
        backgroundCornerRadiusPx = 24f,
        backgroundHPaddingPx  = 32f,
        backgroundVPaddingPx  = 12f,
        animationType         = SubtitleAnimationType.SCALE_FADE,
        animDurationMs        = 280L,
        posYPercent           = 0.82f,
        letterSpacing         = 0.06f,
        useGradientText       = true,
        gradientColorStart    = Color.parseColor("#FF6B6B"),
        gradientColorEnd      = Color.parseColor("#FFE66D"),
    )

    private fun podcastClear() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Medium.ttf",
        fontDisplayName       = "Podcast Clear",
        textSizeSp            = 26f,
        textColor             = Color.parseColor("#F0F0F0"),
        activeWordColor       = Color.parseColor("#7EB8F7"),
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#CC1A1A2E"),
        backgroundCornerRadiusPx = 12f,
        backgroundHPaddingPx  = 24f,
        backgroundVPaddingPx  = 10f,
        shadowRadius          = 2f,
        shadowDy              = 1f,
        shadowColor           = Color.parseColor("#33000000"),
        animationType         = SubtitleAnimationType.HIGHLIGHT_WORD,
        animDurationMs        = 200L,
        posYPercent           = 0.88f,
        highlightWordBgColor  = Color.parseColor("#7EB8F7"),
        highlightWordTextColor = Color.parseColor("#0D0D1A"),
    )

    private fun filmNoirDark() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "Film Noir",
        textSizeSp            = 28f,
        textColor             = Color.parseColor("#E8DCC8"),
        activeWordColor       = Color.parseColor("#C9A84C"),
        strokeWidthPx         = 2f,
        strokeColor           = Color.parseColor("#1A1A1A"),
        hasBackground         = false,
        shadowRadius          = 12f,
        shadowDx              = 2f,
        shadowDy              = 4f,
        shadowColor           = Color.parseColor("#CC000000"),
        animationType         = SubtitleAnimationType.SLIDE_DOWN,
        animDurationMs        = 320L,
        posYPercent           = 0.86f,
        letterSpacing         = 0.04f,
    )

    private fun tiktokHighlight() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Black.ttf",
        fontDisplayName       = "TikTok Highlight",
        textSizeSp            = 42f,
        isAllCaps             = true,
        textColor             = Color.parseColor("#FFFFFF"),
        activeWordColor       = Color.parseColor("#FFE500"),
        strokeWidthPx         = 8f,
        strokeColor           = Color.parseColor("#000000"),
        hasBackground         = false,
        animationType         = SubtitleAnimationType.HIGHLIGHT_WORD,
        animDurationMs        = 100L,
        posYPercent           = 0.75f,
        highlightWordBgColor  = Color.parseColor("#FFE500"),
        highlightWordTextColor = Color.parseColor("#000000"),
    )

    private fun rubberBandFun() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "Rubber Band Fun",
        textSizeSp            = 38f,
        textColor             = Color.parseColor("#FFFFFF"),
        activeWordColor       = Color.parseColor("#FF6B6B"),
        strokeWidthPx         = 5f,
        strokeColor           = Color.parseColor("#1A0000"),
        hasBackground         = false,
        animationType         = SubtitleAnimationType.RUBBER_BAND,
        animDurationMs        = 400L,
        posYPercent           = 0.78f,
    )

    private fun typewriterRetro() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Mono-Regular.ttf",
        fontDisplayName       = "Typewriter Retro",
        textSizeSp            = 24f,
        textColor             = Color.parseColor("#33FF00"), // Terminálová zelená
        activeWordColor       = Color.parseColor("#33FF00"),
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#E6000000"),
        backgroundCornerRadiusPx = 0f, // Bez zaoblení — retro terminál
        backgroundHPaddingPx  = 20f,
        backgroundVPaddingPx  = 8f,
        animationType         = SubtitleAnimationType.TYPEWRITER,
        animDurationMs        = 80L,
        typewriterCharsPerSec = 24,
        posYPercent           = 0.85f,
        letterSpacing         = 0.08f,
    )

    private fun retroNeonFlicker() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "Retro Neon Flicker",
        textSizeSp            = 46f,
        isAllCaps             = true,
        textColor             = Color.parseColor("#FF00FF"),
        activeWordColor       = Color.parseColor("#00FFFF"),
        strokeWidthPx         = 3f,
        strokeColor           = Color.parseColor("#330033"),
        hasBackground         = false,
        shadowRadius          = 20f,
        shadowDx              = 0f,
        shadowDy              = 0f,
        shadowColor           = Color.parseColor("#FF00FF"),
        animationType         = SubtitleAnimationType.NEON_FLICKER,
        animDurationMs        = 600L,
        posYPercent           = 0.78f,
        glowLayerCount        = 3,
    )

    private fun gradientWave() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Black.ttf",
        fontDisplayName       = "Gradient Wave",
        textSizeSp            = 40f,
        textColor             = Color.WHITE,
        activeWordColor       = Color.parseColor("#A78BFA"),
        strokeWidthPx         = 0f,
        hasBackground         = false,
        shadowRadius          = 6f,
        shadowDy              = 2f,
        shadowColor           = Color.parseColor("#7C3AED"),
        animationType         = SubtitleAnimationType.BOUNCE,
        animDurationMs        = 350L,
        posYPercent           = 0.80f,
        useGradientText       = true,
        gradientColorStart    = Color.parseColor("#A78BFA"),
        gradientColorEnd      = Color.parseColor("#38BDF8"),
    )

    private fun whiteBoxClean() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Bold.ttf",
        fontDisplayName       = "White Box Clean",
        textSizeSp            = 28f,
        textColor             = Color.parseColor("#111111"),
        activeWordColor       = Color.parseColor("#1E40AF"),
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#EEFFFFFF"),
        backgroundCornerRadiusPx = 10f,
        backgroundHPaddingPx  = 24f,
        backgroundVPaddingPx  = 10f,
        animationType         = SubtitleAnimationType.SLIDE_UP,
        animDurationMs        = 200L,
        posYPercent           = 0.90f,
    )

    private fun youtubeClassic() = SubtitleStyleConfig(
        presetId              = SubtitlePresetId.CUSTOM,
        fontAssetPath         = "fonts/Roboto-Regular.ttf",
        fontDisplayName       = "YouTube Classic",
        textSizeSp            = 24f,
        textColor             = Color.WHITE,
        activeWordColor       = Color.WHITE,
        strokeWidthPx         = 0f,
        hasBackground         = true,
        backgroundColor       = Color.parseColor("#CC000000"),
        backgroundCornerRadiusPx = 2f,
        backgroundHPaddingPx  = 16f,
        backgroundVPaddingPx  = 6f,
        animationType         = SubtitleAnimationType.NONE,
        animDurationMs        = 0L,
        posYPercent           = 0.93f,
    )
}

