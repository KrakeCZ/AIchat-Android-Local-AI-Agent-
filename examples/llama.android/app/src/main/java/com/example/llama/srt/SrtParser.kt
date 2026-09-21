package com.example.llama.srt

import android.util.Log

/**
 * Statický SRT parser — převádí textový SRT obsah na seznam [SrtBlock].
 *
 * Podporuje:
 * - Standardní SRT formát (index → čas → text → prázdný řádek)
 * - Čas s čárkou i tečkou ("00:01:23,456" i "00:01:23.456")
 * - UTF-8 BOM (automaticky oříznut)
 * - Víceřádkové titulky
 * - Robustní parsování (ignoruje neplatné bloky)
 */
object SrtParser {

    private const val TAG = "SrtParser"

    // Regex pro SRT timestamp řádek: "HH:MM:SS,mmm --> HH:MM:SS,mmm"
    private val TIME_PATTERN = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )

    /**
     * Parsuje SRT textový obsah na seznam [SrtBlock].
     *
     * @param srtContent Celý obsah .srt souboru jako String
     * @return Seznam SrtBlock seřazený dle pořadí v souboru
     */
    fun parse(srtContent: String): List<SrtBlock> {
        if (srtContent.isBlank()) return emptyList()

        val result = mutableListOf<SrtBlock>()

        // Odstranění BOM (UTF-8: EF BB BF)
        val cleaned = srtContent.trimStart('\uFEFF').trim()

        // Split na bloky (oddělené prázdným řádkem)
        val rawBlocks = cleaned.split(Regex("""\r?\n\r?\n"""))

        for (rawBlock in rawBlocks) {
            val lines = rawBlock.trim().lines().filter { it.isNotBlank() }
            if (lines.size < 2) continue

            try {
                // Řádek 1: index (číslo) — může být přeskočen pokud je neplatný
                val indexLine = lines[0].trim()
                val index = indexLine.toIntOrNull()

                // Hledáme řádek s timestamp (může být na pozici 1 nebo 0 pokud index chybí)
                var timeLineIdx = -1
                for (i in lines.indices) {
                    if (TIME_PATTERN.containsMatchIn(lines[i])) {
                        timeLineIdx = i
                        break
                    }
                }

                if (timeLineIdx == -1) continue // Žádný platný timestamp řádek

                val match = TIME_PATTERN.find(lines[timeLineIdx]) ?: continue

                val startMs = parseTimeToMs(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                    match.groupValues[4].toInt()
                )
                val endMs = parseTimeToMs(
                    match.groupValues[5].toInt(),
                    match.groupValues[6].toInt(),
                    match.groupValues[7].toInt(),
                    match.groupValues[8].toInt()
                )

                // Text: vše po timestamp řádku
                val textLines = lines.subList(timeLineIdx + 1, lines.size)
                val text = textLines.joinToString("\n").trim()

                if (text.isNotEmpty()) {
                    result.add(
                        SrtBlock(
                            index = index ?: (result.size + 1),
                            startTimeMs = startMs,
                            endTimeMs = endMs,
                            text = text
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Přeskočen neplatný SRT blok: ${e.message}")
            }
        }

        return result
    }

    /**
     * Převede seznam [SrtBlock] zpět do SRT textového formátu.
     */
    fun format(blocks: List<SrtBlock>): String {
        val builder = java.lang.StringBuilder()
        for ((index, block) in blocks.withIndex()) {
            builder.append(index + 1).append("\n")
            builder.append(formatTime(block.startTimeMs))
                .append(" --> ")
                .append(formatTime(block.endTimeMs))
                .append("\n")
            builder.append(block.text).append("\n\n")
        }
        return builder.toString().trimEnd()
    }

    /**
     * Převede HH:MM:SS,mmm na milisekundy.
     */
    private fun parseTimeToMs(h: Int, m: Int, s: Int, ms: Int): Long {
        return h * 3_600_000L + m * 60_000L + s * 1_000L + ms
    }

    /**
     * Převede milisekundy na HH:MM:SS,mmm.
     */
    private fun formatTime(ms: Long): String {
        val h = ms / 3_600_000
        val m = (ms % 3_600_000) / 60_000
        val s = (ms % 60_000) / 1_000
        val milli = ms % 1_000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }
}
