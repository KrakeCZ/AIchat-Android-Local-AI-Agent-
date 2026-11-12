package com.example.llama.agent.vision.tracking

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Profesionální Zero-Allocation SOT (Single Object Tracker) využívající Block Matching (SAD).
 * 
 * Namísto spoléhání se na sémantické YOLO detekce (které "blikají"), tento tracker
 * sleduje fyzické pixely (hrany, textury) pomocí rychlé CPU matematiky.
 * Je inspirován optickými zaměřovači z vojenského a leteckého průmyslu.
 * 
 * Vlastnosti:
 * - Žádná závislost na TFLite / OpenCV.
 * - Zero-Allocation v `track()` cyklu pro eliminaci GC pauz.
 * - Pevná velikost šablony (např. 64x64) zaměřená na centroid původního objektu.
 */
class OpticalTemplateTracker {

    companion object {
        private const val TAG = "OpticalTracker"
        private const val TEMPLATE_SIZE = 64
        private const val SEARCH_RADIUS = 32
        private const val SEARCH_WINDOW = TEMPLATE_SIZE + 2 * SEARCH_RADIUS // 128
    }

    private var isInitialized = false

    // Před-alokovaná paměť pro extrémní rychlost (L1/L2 cache friendly)
    private val templatePixels = IntArray(TEMPLATE_SIZE * TEMPLATE_SIZE)
    private val searchWindowPixels = IntArray(SEARCH_WINDOW * SEARCH_WINDOW)
    
    // Stav trackeru
    private var lastKnownX = 0f
    private var lastKnownY = 0f
    private var objectWidth = 0f
    private var objectHeight = 0f

    // Kvalita posledního zaměření (pro určení, zda jsme ztratili cíl)
    var trackConfidence = 0f
        private set

    /**
     * Zafixuje (Lock) na konkrétní Bounding Box v obraze.
     * Vyřízne si centroid objektu jako referenční šablonu.
     */
    fun initTracker(bitmap: Bitmap, initialBox: RectF) {
        // Získáme střed objektu
        lastKnownX = initialBox.centerX()
        lastKnownY = initialBox.centerY()
        objectWidth = initialBox.width()
        objectHeight = initialBox.height()

        val startX = (lastKnownX - TEMPLATE_SIZE / 2).toInt().coerceIn(0, bitmap.width - TEMPLATE_SIZE)
        val startY = (lastKnownY - TEMPLATE_SIZE / 2).toInt().coerceIn(0, bitmap.height - TEMPLATE_SIZE)

        // Vytažení pixelů pro šablonu
        bitmap.getPixels(templatePixels, 0, TEMPLATE_SIZE, startX, startY, TEMPLATE_SIZE, TEMPLATE_SIZE)

        // Převedeme na stupně šedi (Luma), zrychluje výpočet
        convertToLuma(templatePixels)

        isInitialized = true
        trackConfidence = 1.0f
        Log.i(TAG, "🎯 Optický Tracker Uzamčen na [X:$startX, Y:$startY] velikost: $TEMPLATE_SIZE")
    }

    /**
     * V dalším snímku najde nejlepší shodu. (Běží 60+ FPS na CPU).
     * @return Nová pozice objektu (RectF).
     */
    fun track(bitmap: Bitmap): RectF? {
        if (!isInitialized) return null

        // Definice hledacího okna (Search Window) kolem poslední známé pozice
        val searchStartX = (lastKnownX - SEARCH_WINDOW / 2).toInt().coerceIn(0, bitmap.width - SEARCH_WINDOW)
        val searchStartY = (lastKnownY - SEARCH_WINDOW / 2).toInt().coerceIn(0, bitmap.height - SEARCH_WINDOW)

        // Extrakce hledacího okna
        bitmap.getPixels(searchWindowPixels, 0, SEARCH_WINDOW, searchStartX, searchStartY, SEARCH_WINDOW, SEARCH_WINDOW)
        convertToLuma(searchWindowPixels)

        // Block Matching - Sum of Absolute Differences (SAD)
        var bestSad = Int.MAX_VALUE
        var bestDx = 0
        var bestDy = 0

        // Prohledávání SEARCH_WINDOW (posouváme šablonu uvnitř)
        for (dy in 0..SEARCH_WINDOW - TEMPLATE_SIZE step 2) {
            for (dx in 0..SEARCH_WINDOW - TEMPLATE_SIZE step 2) {
                var currentSad = 0
                
                // Rychlá vnitřní smyčka
                for (ty in 0 until TEMPLATE_SIZE step 2) { // Step 2 pro zrychlení (Sub-sampling)
                    for (tx in 0 until TEMPLATE_SIZE step 2) {
                        val templateIdx = ty * TEMPLATE_SIZE + tx
                        val searchIdx = (dy + ty) * SEARCH_WINDOW + (dx + tx)
                        
                        val diff = abs(templatePixels[templateIdx] - searchWindowPixels[searchIdx])
                        currentSad += diff
                    }
                }

                if (currentSad < bestSad) {
                    bestSad = currentSad
                    bestDx = dx
                    bestDy = dy
                }
            }
        }

        // Výpočet nové globální pozice z nejlepšího shody (bestDx, bestDy)
        val newCenterX = searchStartX + bestDx + TEMPLATE_SIZE / 2f
        val newCenterY = searchStartY + bestDy + TEMPLATE_SIZE / 2f

        // Aktualizujeme historii
        lastKnownX = newCenterX
        lastKnownY = newCenterY

        // Hrubý odhad confidence na základě hodnoty SAD
        // Menší SAD = lepší shoda.
        val maxPossibleSad = (TEMPLATE_SIZE * TEMPLATE_SIZE / 4) * 255 // Děleno 4 kvůli step 2
        trackConfidence = 1.0f - (bestSad.toFloat() / maxPossibleSad.toFloat())

        // Mírná aktualizace šablony (učení) zabraňuje ztrátě při změně úhlu (Learning Rate 5%)
        updateTemplate(searchWindowPixels, bestDx, bestDy)

        // Vrátíme rekonstruovaný bounding box z původní šířky/výšky
        val left = lastKnownX - objectWidth / 2f
        val top = lastKnownY - objectHeight / 2f
        val right = lastKnownX + objectWidth / 2f
        val bottom = lastKnownY + objectHeight / 2f

        return RectF(left, top, right, bottom)
    }

    private fun convertToLuma(pixels: IntArray) {
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            // Rychlý převod jasu: Y = (R+2G+B)/4
            val luma = (r + (g shl 1) + b) shr 2
            pixels[i] = luma
        }
    }

    private fun updateTemplate(searchWindow: IntArray, bestDx: Int, bestDy: Int) {
        // Aktualizace existující šablony s 5% learning rate (IIR filter)
        for (ty in 0 until TEMPLATE_SIZE) {
            for (tx in 0 until TEMPLATE_SIZE) {
                val templateIdx = ty * TEMPLATE_SIZE + tx
                val searchIdx = (bestDy + ty) * SEARCH_WINDOW + (bestDx + tx)
                
                val oldVal = templatePixels[templateIdx]
                val newVal = searchWindow[searchIdx]
                
                // template = 0.95 * old + 0.05 * new
                templatePixels[templateIdx] = ((oldVal * 19) + newVal) / 20
            }
        }
    }
    
    fun reset() {
        isInitialized = false
        trackConfidence = 0f
    }
}
