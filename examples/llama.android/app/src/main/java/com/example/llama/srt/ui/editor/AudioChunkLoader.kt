package com.example.llama.srt.ui.editor

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/**
 * NLE Audio PCM Proxy Decoder (System Level / Proxy Engine)
 *
 * Architektura: Zero-Memory-Leak, RandomAccessFile O(1) seek.
 * Dekóduje amplitudy z WAV/PCM souboru vygenerovaného C++ FFmpeg enginem.
 *
 * OPRAVA: Předchozí verze ignorovala WAV container a četla PCM od byte 0,
 * čímž parsovala RIFF/WAVE/fmt header jako audio data → falešná vlnka.
 * Nyní dynamicky detekuje offset "data" chunku a čte čistá PCM data.
 *
 * PCM parametry (musí odpovídat nativeExtractAudio16kHz):
 *   16 kHz, 16-bit signed little-endian, Mono
 *   → 32 000 bytů/s = 32 bytů/ms
 */
class AudioChunkLoader(private val pcmFile: File) {

    // 16 kHz × 16-bit × 1ch = 32 000 bytes/s = 32 bytes/ms
    private val BYTES_PER_MS = 32L

    // Lazy-init: offset PCM dat v souboru (za WAV hlavičkou).
    // -1L = ještě nedetekován. Volatile pro thread-safety mezi IO korutinami.
    @Volatile private var wavDataOffset: Long = -1L

    // ─────────────────────────────────────────────────────────────────────────
    // WAV "data" chunk detector
    // Prochází RIFF strukturu a vrátí byte-offset prvního vzorku PCM.
    // Fallback: 44 (standard PCM WAV header) pokud parsování selže.
    // ─────────────────────────────────────────────────────────────────────────

    private fun findWavDataOffset(raf: RandomAccessFile): Long {
        return try {
            raf.seek(0L)

            // Ověření RIFF signatury
            val riffTag = ByteArray(4).also { raf.read(it) }
            if (String(riffTag) != "RIFF") {
                Log.d("AudioChunkLoader", "Soubor není WAV (žádný RIFF tag) — PCM offset = 0")
                return 0L // Čistý raw PCM, bez hlavičky
            }
            raf.skipBytes(4) // RIFF chunk size (nepoužíváme)

            val waveTag = ByteArray(4).also { raf.read(it) }
            if (String(waveTag) != "WAVE") {
                Log.w("AudioChunkLoader", "RIFF není WAVE formát — fallback offset 44")
                return 44L
            }

            // Iterace sub-chunků (fmt, data, ...) až do nalezení "data"
            var searchPos = 12L // Za "RIFF" + size + "WAVE"
            while (searchPos + 8L < raf.length()) {
                raf.seek(searchPos)
                val chunkId = ByteArray(4).also { raf.read(it) }
                val sizeBytes = ByteArray(4).also { raf.read(it) }

                // Little-endian 32-bit chunk size
                val chunkSize = (sizeBytes[0].toInt() and 0xFF) or
                        ((sizeBytes[1].toInt() and 0xFF) shl 8) or
                        ((sizeBytes[2].toInt() and 0xFF) shl 16) or
                        ((sizeBytes[3].toInt() and 0xFF) shl 24)

                if (String(chunkId) == "data") {
                    val dataStart = searchPos + 8L // Za chunk ID (4B) + chunk size (4B)
                    Log.d("AudioChunkLoader", "WAV 'data' chunk nalezen na offset $dataStart B (chunk size: $chunkSize B)")
                    return dataStart
                }

                // Přeskočit neznámý chunk; +1 pro liché zarovnání (RIFF spec)
                val paddedSize = if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize
                searchPos += 8L + paddedSize.toLong().coerceAtLeast(0L)
            }

            Log.w("AudioChunkLoader", "'data' chunk nenalezen — fallback offset 44")
            44L

        } catch (e: Exception) {
            Log.w("AudioChunkLoader", "WAV header parse selhal (fallback 44B): ${e.message}")
            44L
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Hlavní API: amplitudy pro daný časový úsek
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun getAmplitudesForChunk(
        startTimeMs: Long,
        durationMs: Long,
        requestedBars: Int
    ): FloatArray {
        return withContext(Dispatchers.IO) {
            val amplitudes = FloatArray(requestedBars)

            if (!pcmFile.exists() || requestedBars <= 0 || durationMs <= 0L) {
                return@withContext amplitudes
            }

            try {
                RandomAccessFile(pcmFile, "r").use { raf ->

                    // Lazy WAV header detection — jednou za životní cyklus loaderu
                    if (wavDataOffset < 0L) {
                        wavDataOffset = findWavDataOffset(raf)
                    }

                    // Absolutní byte-pozice v souboru pro daný čas
                    val startByte = wavDataOffset + startTimeMs * BYTES_PER_MS
                    val bytesToRead = durationMs * BYTES_PER_MS
                    // Každý bar dostane minimálně 2 byty (1 sample) pro dekódování
                    val bytesPerBar = (bytesToRead / requestedBars).coerceAtLeast(2L)

                    // Validace: nepřekračujeme délku souboru
                    if (startByte >= raf.length()) {
                        return@withContext amplitudes
                    }

                    // O(1) seek — přeskočíme přímo na časový offset
                    raf.seek(startByte)

                    val buffer = ByteArray(bytesPerBar.toInt())

                    for (i in 0 until requestedBars) {
                        val read = raf.read(buffer)
                        if (read <= 0) break

                        var maxAmplitude = 0

                        // Dekódování 16-bit little-endian PCM → abs. amplituda
                        var j = 0
                        while (j + 1 < read) {
                            val low  = buffer[j].toInt() and 0xFF
                            val high = buffer[j + 1].toInt() shl 8
                            val sample = (high or low).toShort()
                            // Abs bez Math.abs — inlinuje se, žádný FP boxing
                            val absVal = if (sample < 0) (-sample).toInt() else sample.toInt()
                            if (absVal > maxAmplitude) maxAmplitude = absVal
                            j += 2
                        }

                        // Normalizace na 0..1; max 16-bit signed = 32767
                        amplitudes[i] = (maxAmplitude / 32767f).coerceIn(0f, 1f)
                    }
                }
            } catch (e: Exception) {
                // EOF při kratším videu než okno — normální, nevadí
                Log.d("AudioChunkLoader", "Read ukončen (EOF nebo chyba): ${e.message}")
            }

            amplitudes
        }
    }
}
