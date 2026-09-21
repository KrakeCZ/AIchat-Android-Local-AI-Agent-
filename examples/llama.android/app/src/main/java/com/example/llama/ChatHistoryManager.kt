package com.example.llama

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Manager pro ukládání historie chatu.
 *
 * OPRAVA (DeepWriteShell-Explicit):
 * 1. Odstraněno 'private val' u parametru context.
 *    - Důvod: Context je potřeba POUZE při inicializaci 'historyDir'.
 *    - Držení reference na Context (zejména pokud je to Activity Context) v poli třídy
 *      by způsobilo Memory Leak, pokud by instance ChatHistoryManager žila déle než Activity.
 *      Tímto je reference okamžitě po konstrukci uvolněna.
 */
class ChatHistoryManager(context: Context) {

    // Použijeme context jen zde v inicializéru.
    // Pro absolutní bezpečnost bereme filesDir, což je safe cesta k internímu úložišti.
    private val historyDir = File(context.filesDir, "chat_history")

    // Veřejný adresář pro exporty (např. Downloads nebo Documents by bylo lepší, ale pro jednoduchost zatím filesDir/exports)
    // Pokud chceme, aby si to uživatel stáhl, musíme použít ContentProvider nebo zapsat do veřejného úložiště.
    // Pro teď připravíme metody, které vrátí File objekt s exportovaným obsahem.
    private val exportDir = File(context.getExternalFilesDir(null), "exports")

    init {
        // Validace stavu systému: Zajistíme, že adresář existuje
        if (!historyDir.exists()) {
            val created = historyDir.mkdirs()
            if (!created && !historyDir.exists()) {
                 Log.e("ChatHistory", "CRITICAL: Failed to create history directory: ${historyDir.absolutePath}")
            }
        }
        if (!exportDir.exists()) {
            exportDir.mkdirs()
        }
    }

    fun saveSession(sessionId: String, messages: List<Message>) {
        try {
            val jsonArray = JSONArray()
            messages.forEach { msg ->
                val jsonObj = JSONObject().apply {
                    put("id", msg.id)
                    put("content", msg.content)
                    put("isUser", msg.isUser)
                }
                jsonArray.put(jsonObj)
            }

            val file = File(historyDir, "$sessionId.json")
            // Atomický zápis není v Javě triviální bez API 26+, ale writeText je standardní Kotlin ext.
            file.writeText(jsonArray.toString())
            Log.d("ChatHistory", "Saved session $sessionId")
        } catch (e: Exception) {
            Log.e("ChatHistory", "Error saving session", e)
        }
    }

    fun loadSession(sessionId: String): MutableList<Message> {
        val messages = mutableListOf<Message>()
        val file = File(historyDir, "$sessionId.json")

        // Defenzivní kontrola: Pokud soubor neexistuje, vracíme prázdný seznam (bez chyby)
        if (!file.exists()) return messages

        try {
            val text = file.readText()
            // Validace prázdného souboru před parsováním JSON
            if (text.isBlank()) return messages

            val jsonArray = JSONArray(text)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                messages.add(
                    Message(
                        id = obj.getString("id"),
                        contentBuilder = StringBuilder(obj.getString("content")),
                        isUser = obj.getBoolean("isUser")
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("ChatHistory", "Error loading session", e)
        }
        return messages
    }

    fun getSessions(): List<String> {
        // Defenzivní kontrola: listFiles() může vrátit null, pokud cesta není adresář nebo IO error
        return historyDir.listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { it.nameWithoutExtension }
            ?: emptyList()
    }

    fun deleteSession(sessionId: String) {
        val file = File(historyDir, "$sessionId.json")
        if (file.exists()) {
            file.delete()
        }
    }

    // ============================================================================================
    // EXPORT FEATURES (Added by DeepWriteShell-Explicit)
    // ============================================================================================

    /**
     * Exportuje session do souboru ve zvoleném formátu.
     * Vrací absolutní cestu k vytvořenému souboru.
     */
    fun exportChat(sessionId: String, format: ExportFormat): File {
        val messages = loadSession(sessionId)
        if (messages.isEmpty()) {
             throw IOException("Chat session $sessionId is empty or does not exist.")
        }

        val timestamp = System.currentTimeMillis()
        val fileName = "export_${sessionId}_$timestamp.${format.extension}"
        val destFile = File(exportDir, fileName)

        val content = when (format) {
            ExportFormat.JSON -> generateJsonExport(messages)
            ExportFormat.MARKDOWN -> generateMarkdownExport(sessionId, messages)
            ExportFormat.HTML -> generateHtmlExport(sessionId, messages)
            ExportFormat.TXT -> generateTxtExport(sessionId, messages)
        }

        try {
            destFile.writeText(content)
            Log.i("ChatHistory", "Exported session $sessionId to ${destFile.absolutePath}")
            return destFile
        } catch (e: Exception) {
            Log.e("ChatHistory", "Failed to write export file", e)
            throw IOException("Failed to export chat: ${e.message}", e)
        }
    }

    private fun generateJsonExport(messages: List<Message>): String {
        val jsonArray = JSONArray()
        messages.forEach { msg ->
            val jsonObj = JSONObject().apply {
                put("role", if (msg.isUser) "user" else "assistant")
                put("content", msg.content)
            }
            jsonArray.put(jsonObj)
        }
        return jsonArray.toString(2) // Pretty print s indentací 2
    }

    private fun generateMarkdownExport(sessionId: String, messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("# Chat Session: $sessionId\n\n")
        messages.forEach { msg ->
            val role = if (msg.isUser) "**User**" else "**Assistant**"
            sb.append("$role:\n${msg.content}\n\n---\n\n")
        }
        return sb.toString()
    }

    private fun generateTxtExport(sessionId: String, messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("Chat Session: $sessionId\n")
        sb.append("========================================\n\n")
        messages.forEach { msg ->
            val role = if (msg.isUser) "User" else "Assistant"
            sb.append("[$role]:\n${msg.content}\n\n----------------------------------------\n\n")
        }
        return sb.toString()
    }

    private fun generateHtmlExport(sessionId: String, messages: List<Message>): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html>\n<html>\n<head>\n")
        sb.append("<meta charset=\"UTF-8\">\n")
        sb.append("<title>Chat Export - $sessionId</title>\n")
        sb.append("<style>\n")
        // Upravené CSS: Pozadí body na světle šedou (#f0f2f5), text tmavý (#1c1e21)
        sb.append("body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 800px; margin: 0 auto; padding: 20px; background-color: #f0f2f5; color: #1c1e21; }\n")
        sb.append(".message { margin-bottom: 20px; padding: 15px; border-radius: 18px; line-height: 1.5; box-shadow: 0 1px 2px rgba(0,0,0,0.1); }\n")
        // User (Zelená bublina - WhatsApp style): #d9fdd3
        sb.append(".user { background-color: #d9fdd3; text-align: right; margin-left: 20%; border-bottom-right-radius: 4px; }\n")
        // Assistant (Bílá bublina): #ffffff
        sb.append(".assistant { background-color: #ffffff; text-align: left; margin-right: 20%; border-bottom-left-radius: 4px; }\n")
        sb.append(".role { font-weight: bold; margin-bottom: 5px; font-size: 0.85em; color: #65676b; text-transform: uppercase; letter-spacing: 0.5px; }\n")
        sb.append(".content { white-space: pre-wrap; word-wrap: break-word; }\n")
        sb.append("h1 { color: #1c1e21; text-align: center; margin-bottom: 30px; font-size: 1.5em; }\n")
        sb.append("</style>\n</head>\n<body>\n")
        sb.append("<h1>Chat Session: $sessionId</h1>\n")

        messages.forEach { msg ->
            val cssClass = if (msg.isUser) "user" else "assistant"
            val roleName = if (msg.isUser) "User" else "AI Assistant"
            sb.append("<div class=\"message $cssClass\">\n")
            sb.append("<div class=\"role\">$roleName</div>\n")
            // Jednoduchý escape HTML entit pro bezpečnost
            val safeContent = msg.content
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
            sb.append("<div class=\"content\">$safeContent</div>\n")
            sb.append("</div>\n")
        }

        sb.append("</body>\n</html>")
        return sb.toString()
    }

    enum class ExportFormat(val extension: String) {
        TXT("txt"),
        MARKDOWN("md"),
        HTML("html"),
        JSON("json")
    }
}
