package com.example.llama.srt

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Zajišťuje, že zabudované fonty z assets/fonts a všechny uživatelsky importované fonty
 * jsou fyzicky dostupné na diskovém oddílu (filesDir/fonts).
 * 
 * Toto je kritické pro C++ vrstvu (libass/FFmpeg), která nedokáže číst přímo
 * komprimované soubory uvnitř .apk (assets) kvůli SELinux restrikcím a absenci C++ FileDescriptoru.
 */
object FontProvisioner {
    private const val TAG = "FontProvisioner"
    private const val ASSET_FONTS_DIR = "fonts"
    private const val DISK_FONTS_DIR = "custom_fonts"

    /**
     * Zkopíruje všechny .ttf/.otf soubory z APK assets/fonts do fyzické složky
     * a vrátí absolutní cestu k této složce. Mělo by být voláno při startu aplikace
     * nebo před zahájením exportu.
     *
     * @return Absolutní cesta k adresáři s fonty, kterou lze předat FFmpegu
     */
    suspend fun ensureFontsProvisioned(context: Context): File = withContext(Dispatchers.IO) {
        val fontsDir = File(context.filesDir, DISK_FONTS_DIR)
        if (!fontsDir.exists()) {
            fontsDir.mkdirs()
        }

        try {
            val assetManager = context.assets
            val fontAssets = assetManager.list(ASSET_FONTS_DIR) ?: emptyArray()

            for (fontFileName in fontAssets) {
                if (!fontFileName.endsWith(".ttf", ignoreCase = true) && 
                    !fontFileName.endsWith(".otf", ignoreCase = true)) {
                    continue
                }

                val destFile = File(fontsDir, fontFileName)
                // Idempotence: Pokud soubor už existuje a má nenulovou velikost, přeskočíme ho
                if (destFile.exists() && destFile.length() > 0) {
                    continue
                }

                Log.i(TAG, "Kopíruji zabudovaný font z assets do fyzické paměti: $fontFileName")
                
                assetManager.open("$ASSET_FONTS_DIR/$fontFileName").use { inputStream ->
                    FileOutputStream(destFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při kopírování fontů z assets: ${e.message}", e)
        }

        return@withContext fontsDir
    }

    /**
     * Helper funkce pro uživatelsky importované fonty.
     * Uživatelský import (např. z Desktopu/Sablony) by měl zkopírovat soubor
     * do stejného adresáře, jaký vrací getFontsDir().
     */
    fun getFontsDir(context: Context): File {
        return File(context.filesDir, DISK_FONTS_DIR).apply {
            if (!exists()) mkdirs()
        }
    }
}
