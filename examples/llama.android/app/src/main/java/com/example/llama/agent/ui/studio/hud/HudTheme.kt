package com.example.llama.agent.ui.studio.hud

import androidx.compose.ui.graphics.Color

enum class HudTheme(
    val displayName: String,
    val crosshairColor: Color,
    val traceColor: Color,
    val leadColor: Color,
    val crosshairStyle: CrosshairStyle
) {
    STANDARD(
        displayName = "Standard",
        crosshairColor = Color.White,
        traceColor = Color.LightGray,
        leadColor = Color.Yellow,
        crosshairStyle = CrosshairStyle.CORNERS_ONLY
    ),
    TACTICAL_MILITARY(
        displayName = "Tactical Military",
        crosshairColor = Color(0xFF00FFCC), // Tyrkysová
        traceColor = Color.Green,
        leadColor = Color.Red,
        crosshairStyle = CrosshairStyle.FULL_RETICLE
    ),
    NATURE_TRACKER(
        displayName = "Nature Tracker",
        crosshairColor = Color(0xFFFF9800), // Oranžová
        traceColor = Color(0x88FF9800),
        leadColor = Color.Transparent, // No lead indicator for nature
        crosshairStyle = CrosshairStyle.CIRCLE_ONLY
    ),
    SKY_WATCHER(
        displayName = "Sky Watcher (UAP)",
        crosshairColor = Color(0xFF00E5FF), // Cyan
        traceColor = Color(0xAA00E5FF),
        leadColor = Color.Red, // Červená pro anomální vektory
        crosshairStyle = CrosshairStyle.CORNERS_ONLY
    ),
    CLASSIC_OFF(
        displayName = "Classic (No HUD)",
        crosshairColor = Color.Transparent,
        traceColor = Color.Transparent,
        leadColor = Color.Transparent,
        crosshairStyle = CrosshairStyle.NONE
    )
}

enum class CrosshairStyle {
    CORNERS_ONLY,
    FULL_RETICLE,
    CIRCLE_ONLY,
    NONE
}
