package com.example.llama.srt

import com.example.llama.SrtBlock

// ============================================================================
// Datový model pro word-level timestamps (Whisper / Sherpa-ONNX výstup)
// ============================================================================

data class WordTimestamp(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

// ============================================================================
// SegmentationEngine — SOLID interface (Open/Closed Principle)
// Přidání nového algoritmu = nová třída, žádný zásah do stávajícího kódu.
// ============================================================================

interface SegmentationEngine {
    /**
     * Rozdělí text z jednoho ASR chunku do seznamu SrtBlock.
     *
     * @param text            Transkribovaný text celého chunku
     * @param chunkStartMs    Absolutní začátek chunku v ms (v rámci média)
     * @param chunkDurationMs Délka chunku v ms (vypočítána z PCM samples / sampleRate)
     * @param wordTimestamps  Word-level timestamps pokud jsou dostupné (null = nejsou)
     * @return                Seznam SrtBlock — indexy jsou 0, přečíslování zajistí Activity
     */
    fun segment(
        text: String,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        wordTimestamps: List<WordTimestamp>? = null
    ): List<SrtBlock>

    /** Lidsky čitelný název pro UI Spinner */
    val displayName: String
}

// ============================================================================
// Režim B — Interpunkce + Max slov (bez závislostí, vždy funkční)
// ============================================================================

class PunctuationSegmentationEngine(
    /** Maximální počet slov v jednom bloku (1–10, výchozí 7) */
    val maxWords: Int = 7
) : SegmentationEngine {

    override val displayName = "Interpunkce (mód B)"

    override fun segment(
        text: String,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        wordTimestamps: List<WordTimestamp>?
    ): List<SrtBlock> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Krok 1: Split na větné hranice (., !, ?) — zachovat oddělovač na konci věty
        val sentences = trimmed
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        // Krok 2: Každou větu dále rozdělit pokud překračuje maxWords
        val finalBlocks = mutableListOf<String>()
        for (sentence in sentences) {
            val words = sentence.split(Regex("\\s+"))
            if (words.size <= maxWords) {
                finalBlocks.add(sentence)
            } else {
                // Chunked split na maxWords skupiny
                words.chunked(maxWords).forEach { chunk ->
                    finalBlocks.add(chunk.joinToString(" "))
                }
            }
        }

        if (finalBlocks.isEmpty()) return emptyList()

        // Krok 3: Rovnoměrné rozložení timestampů — aproximace v rámci chunku
        val msPerBlock = chunkDurationMs / finalBlocks.size
        return finalBlocks.mapIndexed { i, blockText ->
            val startMs = chunkStartMs + i * msPerBlock
            // Jemný gap 50ms mezi bloky pro přirozenou čitelnost; min 300ms délka bloku
            val endMs = (startMs + msPerBlock - 50).coerceAtLeast(startMs + 300L)
            SrtBlock(
                index     = 0,           // Přečísluje Activity
                startTime = formatMs(startMs),
                endTime   = formatMs(endMs),
                text      = blockText
            )
        }
    }
}

// ============================================================================
// Režim C — Word Timestamps (Whisper / Sherpa-ONNX výstup)
// Nejpřesnější — každé slovo má svůj timestamp z ASR enginu.
// Pokud wordTimestamps == null → automatický fallback na Režim B.
// ============================================================================

class WordTimestampSegmentationEngine(
    /** Maximální počet slov v jednom bloku */
    val maxWords: Int = 7,
    /** Fallback engine pokud model nepodporuje word timestamps */
    private val fallback: SegmentationEngine = PunctuationSegmentationEngine(maxWords)
) : SegmentationEngine {

    override val displayName = "Word Timestamps (mód C)"

    override fun segment(
        text: String,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        wordTimestamps: List<WordTimestamp>?
    ): List<SrtBlock> {
        // Fallback: model nepodporuje word timestamps → použij Režim B
        if (wordTimestamps.isNullOrEmpty()) {
            return fallback.segment(text, chunkStartMs, chunkDurationMs, null)
        }

        val blocks = mutableListOf<SrtBlock>()
        var groupStart = 0

        while (groupStart < wordTimestamps.size) {
            val groupEnd = minOf(groupStart + maxWords, wordTimestamps.size)
            val group    = wordTimestamps.subList(groupStart, groupEnd)
            val blockText = group.joinToString(" ") { it.word.trim() }

            if (blockText.isNotBlank()) {
                blocks.add(
                    SrtBlock(
                        index     = 0,
                        startTime = formatMs(group.first().startMs),
                        endTime   = formatMs(group.last().endMs),
                        text      = blockText
                    )
                )
            }
            groupStart = groupEnd
        }
        return blocks
    }
}

// ============================================================================
// Režim A — VAD (Silero VAD) — placeholder pro budoucí implementaci
// Nyní: automatický fallback na Režim B.
// ============================================================================

class VadSegmentationEngine(
    val maxWords: Int = 7
) : SegmentationEngine {

    override val displayName = "VAD — Detekce pauz (mód A)"

    override fun segment(
        text: String,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        wordTimestamps: List<WordTimestamp>?
    ): List<SrtBlock> {
        // TODO: Silero VAD model integrace (Phase 0A rozšíření)
        // Prozatím: fallback na interpunkci — chování je transparentní pro uživatele
        return PunctuationSegmentationEngine(maxWords).segment(
            text, chunkStartMs, chunkDurationMs, wordTimestamps
        )
    }
}

// ============================================================================
// Factory — vytvoří správný engine dle indexu Spinneru
// ============================================================================

object SegmentationEngineFactory {

    /** Pořadí musí odpovídat pořadí v array/spinner v activity_srt_editor.xml */
    fun fromSpinnerIndex(index: Int, maxWords: Int): SegmentationEngine = when (index) {
        0    -> VadSegmentationEngine(maxWords)
        1    -> PunctuationSegmentationEngine(maxWords)
        2    -> WordTimestampSegmentationEngine(maxWords)
        else -> PunctuationSegmentationEngine(maxWords)
    }

    val displayNames = listOf(
        "🔊 VAD — Detekce pauz (A)",
        "✂️ Interpunkce + Max slov (B)",
        "⚡ Word Timestamps / Whisper (C)"
    )
}

// ============================================================================
// Pomocná funkce formátování ms → SRT čas "HH:MM:SS,mmm"
// Sdílená pro všechny engine implementace v tomto balíčku
// ============================================================================

internal fun formatMs(ms: Long): String {
    val clamped = ms.coerceAtLeast(0L)
    val h       = clamped / 3_600_000
    val m       = (clamped % 3_600_000) / 60_000
    val s       = (clamped % 60_000) / 1000
    val millis  = clamped % 1000
    return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
}
