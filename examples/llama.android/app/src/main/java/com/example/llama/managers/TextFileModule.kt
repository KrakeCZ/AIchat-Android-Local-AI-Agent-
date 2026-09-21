package com.example.llama.managers

import android.content.Context
import android.net.Uri
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Modul pro robustní čtení textových souborů.
 * Podporuje detekci BOM (UTF-8, UTF-16, UTF-32) a fallback na Windows-1250/1252.
 */
object TextFileModule {



    /**
     * Načte obsah souboru z URI s automatickou detekcí kódování.
     * @param maxSize Maximální povolená velikost v bajtech.
     * @throws Exception pokud je soubor příliš velký nebo nelze přečíst.
     */
    fun readContent(context: Context, uri: Uri, maxSize: Long): String {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            var bytesRead: Int
            var totalBytes = 0L
            
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                totalBytes += bytesRead
                if (totalBytes > maxSize) {
                    throw Exception("File too large (>${maxSize / 1024}KB)")
                }
                output.write(buffer, 0, bytesRead)
            }
            
            val bytes = output.toByteArray()
            val rawContent = decode(bytes)
            return rawContent.replace("\u0000", "") 
        }
        throw Exception("Unable to open content stream")
    }

    /**
     * Dekóduje bajty na String s využitím heuristiky pro detekci kódování.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""

        // 1. BOM Detekce (Byte Order Mark)

        // UTF-32LE (FF FE 00 00)
        if (bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() && bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) {
            return String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32LE"))
        }
        // UTF-32BE (00 00 FE FF)
        if (bytes.size >= 4 && bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() && bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()) {
            return String(bytes, 4, bytes.size - 4, Charset.forName("UTF-32BE"))
        }
        // UTF-8 (EF BB BF)
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        }
        // UTF-16LE (FF FE)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        // UTF-16BE (FE FF)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }

        // 2. UTF-8 Validace
        // Zkusíme dekódovat jako UTF-8. Pokud obsahuje neplatné sekvence, decoder vyhodí výjimku.
        if (isValidUtf8(bytes)) {
            return String(bytes, StandardCharsets.UTF_8)
        }

        // 3. Fallback pro "Office/Legacy" soubory
        // Pokud to není validní UTF-8, předpokládáme Windows kódování.
        // Zkusíme Windows-1250 (Středoevropské - ČR/SK)
        try {
            return String(bytes, Charset.forName("windows-1250"))
        } catch (_: Exception) {
            // Ignorovat, zkusíme další
        }

        // Fallback na Windows-1252 (Západní Evropa / Default ANSI)
        try {
            return String(bytes, Charset.forName("windows-1252"))
        } catch (_: Exception) {
            // Pokud ani to ne, vrátíme systémový default nebo ISO-8859-1
            return String(bytes, Charset.defaultCharset())
        }
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean {
        try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
            // Nastavíme chování na chybu, jinak by defaultně nahrazoval znaky
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            return true
        } catch (_: java.nio.charset.CharacterCodingException) {
            return false
        }
    }
}
