package com.example.llama.srt

/**
 * SRT block s milisekundovými timestampy (Long) pro SRT LiveEditor.
 *
 * Koexistuje s com.example.llama.SrtBlock (starý editor, String timestampy).
 * Nový editor (SrtEditorActivity v2) pracuje výhradně s touto třídou.
 *
 * @param index       Pořadové číslo bloku (1-based v SRT formátu)
 * @param startTimeMs Začátek v milisekundách
 * @param endTimeMs   Konec v milisekundách
 * @param text        Textový obsah titulku
 * @param wordTimestamps Word-level timestamps (z Whisper/Sherpa-ONNX), null = nedostupné
 */
data class SrtBlock(
    val index: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    val wordTimestamps: List<WordTimestamp>? = null
)
