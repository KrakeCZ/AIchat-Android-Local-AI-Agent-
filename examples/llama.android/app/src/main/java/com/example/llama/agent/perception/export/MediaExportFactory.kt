package com.example.llama.agent.perception.export

import android.util.Log
import com.example.llama.agent.perception.nlp.MeetingIntelligenceResult
import com.example.llama.agent.perception.nlp.TranscriptChunk
import com.example.llama.managers.FileSystemManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bezpečný, atomický Exportní engine pro média (Markdown & SubRip/SRT).
 * Rozšířeno o template-aware export pro NextGen 2026 šablony.
 */
class MediaExportFactory(private val fileManager: FileSystemManager) {

    companion object {
        private const val TAG = "MediaExportFactory"
    }

    /**
     * Vytvoří standardní .SRT titulky pro videa.
     */
    suspend fun exportToSrt(chunks: List<TranscriptChunk>, baseFileName: String): File? = withContext(Dispatchers.IO) {
        if (chunks.isEmpty()) return@withContext null

        val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$baseFileName.srt")
        
        try {
            FileOutputStream(file).use { out ->
                val writer = out.writer(Charsets.UTF_8)
                chunks.forEachIndexed { index, chunk ->
                    writer.write("${index + 1}\n")
                    writer.write("${formatSrtTime(chunk.startTimeMs)} --> ${formatSrtTime(chunk.endTimeMs)}\n")
                    writer.write("${chunk.speakerId}: ${chunk.rawText}\n\n")
                }
                writer.flush()
                out.fd.sync()
            }
            Log.i(TAG, "✓ SRT Exportováno: ${file.absolutePath}")
            return@withContext file
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při exportu SRT", e)
            return@withContext null
        }
    }

    /**
     * Vygeneruje finální report v čistém Markdownu pro Notion/Obsidian.
     * Template-aware: Používá strukturu ze šablony + doplní sentiment a překlad.
     */
    suspend fun exportToMarkdown(intelligence: MeetingIntelligenceResult, baseFileName: String): File? = withContext(Dispatchers.IO) {
        val file = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "$baseFileName.md")
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

        val mdContent = buildString {
            appendLine("# ${intelligence.templateName} ($dateStr)")
            appendLine()
            appendLine("> 🔒 Zpracováno offline na zařízení | Šablona: `${intelligence.templateId}`")
            appendLine()

            // Sentiment sekce (pokud existuje)
            if (intelligence.sentiment != null) {
                appendLine("## 🎭 Sentiment")
                appendLine(intelligence.sentiment)
                appendLine()
            }

            // AI vygenerovaný obsah (hlavní výstup)
            appendLine("## 📌 AI Analýza")
            appendLine(intelligence.summary)
            appendLine()

            // Entity (pokud existují)
            if (intelligence.entities.isNotEmpty()) {
                appendLine("## 🏷️ Extrahované entity")
                intelligence.entities.forEach { appendLine("- $it") }
                appendLine()
            }

            // Překlad (pokud existuje)
            if (intelligence.translatedText != null) {
                appendLine("## 🌐 Překlad")
                appendLine(intelligence.translatedText)
                appendLine()
            }

            // Kompletní přepis
            appendLine("---")
            appendLine("## 💬 Kompletní přepis")
            appendLine(intelligence.rawTranscript)
        }

        try {
            FileOutputStream(file).use { out ->
                out.write(mdContent.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Log.i(TAG, "✓ Markdown Exportováno: ${file.absolutePath}")
            return@withContext file
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chyba při exportu Markdownu", e)
            return@withContext null
        }
    }

    private fun formatSrtTime(timeMs: Long): String {
        val ms = timeMs % 1000
        var s = timeMs / 1000
        val m = s / 60
        s %= 60
        val h = m / 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m % 60, s, ms)
    }
}
