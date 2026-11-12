package com.example.llama.agent.vision.camera.math

import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.max

/**
 * Enterprise-grade transformátor souřadnic pro eliminaci "Misalignmentu".
 * Řeší rozdíl mezi tvarem senzoru (např. 16:9), vstupem do ML (např. 1:1 Squash) 
 * a UI vykreslovacím plátnem (např. 20:9 FILL_CENTER).
 * [Zero I/O Allocation]: Používá pre-alokované primitivní pole a matice.
 */
object ViewportCoordinateTransformer {

    private val transformMatrix = Matrix()
    private val srcArray = FloatArray(4) // [left, top, right, bottom]
    private val dstArray = FloatArray(4)

    /**
     * Vypočítá transformační matici pro aktuální frame.
     * Tuto metodu volej jen při změně rozlišení kamery nebo UI (např. otočení displeje).
     * @param imageW Reálná šířka framu z kamery (např. 1280)
     * @param imageH Reálná výška framu z kamery (např. 720)
     * @param viewW Šířka Compose plátna (displeje)
     * @param viewH Výška Compose plátna
     */
    fun computeFillCenterMatrix(imageW: Float, imageH: Float, viewW: Float, viewH: Float): Matrix {
        transformMatrix.reset()
        
        // 1. Zrušení "Squash" efektu modelu. Model vrací [0..1].
        // Roztažením na imageW x imageH získáme původní polohu pixelů v nezdeformovaném framu.
        transformMatrix.postScale(imageW, imageH)

        // 2. Aplikace "FILL_CENTER" logiky (to, co dělá PreviewView / Compose ContentScale.Crop)
        val scale = max(viewW / imageW, viewH / imageH)
        val dx = (viewW - imageW * scale) / 2f
        val dy = (viewH - imageH * scale) / 2f

        transformMatrix.postScale(scale, scale)
        transformMatrix.postTranslate(dx, dy)

        return transformMatrix
    }

    /**
     * Rychlá transformace ML souřadnic [0.0 - 1.0] na fyzické pixely displeje.
     * @param outRect RectF, do kterého se přímo zapíšou opravené UI souřadnice (Zero-GC)
     */
    fun mapMlToUi(
        normLeft: Float, normTop: Float, normRight: Float, normBottom: Float,
        matrix: Matrix,
        outRect: RectF
    ) {
        srcArray[0] = normLeft
        srcArray[1] = normTop
        srcArray[2] = normRight
        srcArray[3] = normBottom

        // HW akcelerovaný přepočet
        matrix.mapPoints(dstArray, srcArray)

        // dstArray může mít kvůli ořezu hodnoty mimo displej, Compose Canvas si s tím poradí.
        outRect.set(dstArray[0], dstArray[1], dstArray[2], dstArray[3])
    }
}
