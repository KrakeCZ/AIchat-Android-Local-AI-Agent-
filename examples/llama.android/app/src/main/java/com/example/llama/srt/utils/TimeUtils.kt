package com.example.llama.srt.utils

import java.util.Locale
import kotlin.math.roundToLong

object TimeUtils {
    /**
     * Konvertuje milisekundy na frame index (Long) s maximální přesností (Double).
     *
     * Výpočet: millis * FPS / 1000
     * Násobíme před dělením pro minimalizaci ztráty přesnosti.
     */
    fun msToFrame(ms: Long, fps: Float): Long {
        if (fps <= 0f) return 0L
        val result = (ms * fps.toDouble()) / 1000.0
        return result.roundToLong()
    }

    /**
     * Konvertuje frame index na milisekundy.
     * 
     * Výpočet: (frame * 1000) / FPS
     */
    fun frameToMs(frame: Long, fps: Float): Long {
        if (fps <= 0f) return 0L
        return ((frame * 1000.0) / fps.toDouble()).roundToLong()
    }

    /**
     * Formátuje milisekundy do HH:MM:SS,mmm stringu.
     */
    fun formatTimeFromMs(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalSeconds = clamped / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val millis = clamped % 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
