package com.example.llama.srt.ui.graphics

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.io.File

object FontRegistry {
    private const val TAG = "FontRegistry"
    private val typefaceCache = mutableMapOf<String, Typeface>()

    val DEFAULT_FONTS = listOf("Roboto-Bold.ttf", "Impact.ttf", "Roboto-Regular.ttf", "Roboto-Black.ttf")

    /**
     * Vrátí Typeface ze souboru, nebo defaultní, pokud soubor chybí/je poškozený.
     * Cachuje výsledek, aby OOM (Memory Leak) nezruinoval 60fps render.
     */
    fun getTypeface(context: Context, fontPath: String): Typeface {
        return typefaceCache.getOrPut(fontPath) {
            try {
                if (fontPath.startsWith("fonts/")) {
                    Typeface.createFromAsset(context.assets, fontPath)
                } else {
                    val file = File(fontPath)
                    if (file.exists()) {
                        Typeface.createFromFile(file)
                    } else {
                        // Fallback: Pokud je to jen název souboru nebo rozbitá absolutní cesta z jiného zařízení,
                        // zkusíme ho najít přímo ve složce custom_fonts
                        val customFontsDir = File(context.filesDir, "custom_fonts")
                        val fallbackFile = File(customFontsDir, file.name)
                        
                        if (fallbackFile.exists()) {
                            Typeface.createFromFile(fallbackFile)
                        } else {
                            Log.w(TAG, "Font nenalezen: $fontPath ani jako $fallbackFile")
                            Typeface.DEFAULT_BOLD
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba načítání fontu: $fontPath", e)
                Typeface.DEFAULT_BOLD
            }
        }
    }

    /**
     * Prohledá složku a vrátí seznam dostupných písem pro UI (StylePickerBottomSheet).
     * Vrací Pair<Jméno_Fontu, Absolutní_Cesta>
     */
    fun getAvailableFonts(context: Context): List<Pair<String, String>> {
        val fontsDir = File(context.filesDir, "custom_fonts").apply { mkdirs() }
        val customFonts = fontsDir.listFiles { f -> f.extension.lowercase() in listOf("ttf", "otf") }
            ?.map { it.nameWithoutExtension to it.absolutePath } ?: emptyList()
            
        val assetFonts = DEFAULT_FONTS.map { it.substringBeforeLast(".") to "fonts/$it" }
        return assetFonts + customFonts
    }
}
