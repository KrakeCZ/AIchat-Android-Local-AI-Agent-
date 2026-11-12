package com.example.llama.agent.vision.models

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * OdLabelRegistry — Idempotentní a paměťově efektivní správce popisků (tříd).
 * Načítá a drží v paměti COCO/ImageNet tabulky, pokud model nedodává vlastní.
 * Zajišťuje O(1) rychlost mapování ID -> String během real-time inference (60fps).
 */
object OdLabelRegistry {
    private const val TAG = "OdLabelRegistry"

    // Mapy držíme v paměti jako SparseArray-like strukturu pro rychlý přístup
    @Volatile
    private var coco80Labels: Map<Int, String>? = null

    /**
     * Získá textový label pro daný index podle zadané strategie.
     * Garantuje, že nikdy nevyhodí výjimku (vrací raw index v nejhorším případě).
     * * @param context Application context pro přístup do assets
     * @param strategy Zvolená strategie mapování
     * @param classIndex Syrový index z výstupního tenzoru modelu
     * @param customLabelMap Mapa poskytnutá uživatelem (pokud je strategie EXTERNAL_TXT)
     */
    @JvmStatic
    fun resolveLabel(
        context: Context,
        strategy: OdLabelStrategy,
        classIndex: Int,
        customLabelMap: List<String>? = null
    ): String {
        return try {
            when (strategy) {
                OdLabelStrategy.EMBEDDED_METADATA -> {
                    // Tohle obvykle řeší MediaPipe interně. Pokud jsme zde, je to chyba designu volajícího.
                    "OBJ_$classIndex"
                }
                OdLabelStrategy.FALLBACK_COCO_80 -> {
                    ensureCocoLoaded(context)
                    coco80Labels?.get(classIndex) ?: "COCO_$classIndex"
                }
                OdLabelStrategy.FALLBACK_IMAGENET_1000 -> {
                    // Prozatím vracíme formát ID, lze rozšířit o imagenet.txt do assets
                    "IMGNET_$classIndex"
                }
                OdLabelStrategy.EXTERNAL_TXT -> {
                    customLabelMap?.getOrNull(classIndex) ?: "CUSTOM_$classIndex"
                }
                OdLabelStrategy.UNKNOWN -> "OBJ_$classIndex"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při resolvování labelu $classIndex (Strategy: $strategy)", e)
            "ERROR_$classIndex"
        }
    }

    /**
     * Bezpečné (Thread-Safe) načtení COCO datasetu z /assets/vision_labels/coco_80.txt.
     * Volá se pouze při prvním požadavku (Lazy Loading).
     */
    private fun ensureCocoLoaded(context: Context) {
        if (coco80Labels != null) return

        synchronized(this) {
            if (coco80Labels != null) return // Double-checked locking

            val tempMap = mutableMapOf<Int, String>()
            try {
                // Předpokládá soubor assets/vision_labels/coco_80.txt (jeden label na řádek)
                context.assets.open("vision_labels/coco_80.txt").use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                        var index = 0
                        reader.forEachLine { line ->
                            val cleanLine = line.trim()
                            if (cleanLine.isNotEmpty() && !cleanLine.startsWith("#")) {
                                tempMap[index] = cleanLine
                                index++
                            }
                        }
                    }
                }
                coco80Labels = tempMap
                Log.i(TAG, "✅ COCO-80 Labely úspěšně načteny do RAM (${tempMap.size} položek)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Nelze načíst coco_80.txt z assets. Ujistěte se, že soubor existuje.", e)
                // Prevence opakovaného načítání při selhání
                coco80Labels = emptyMap() 
            }
        }
    }
}
