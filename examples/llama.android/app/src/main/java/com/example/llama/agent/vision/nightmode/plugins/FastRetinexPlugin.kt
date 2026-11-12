package com.example.llama.agent.vision.nightmode.plugins

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.example.llama.agent.vision.nightmode.NightModePlugin

/**
 * CPU-based Single-Scale Retinex (SSR) aproximace kombinovaná se saturací.
 * Slouží jako rychlý fallback pro "Night Mode", který nepotřebuje TFLite model.
 * Vhodný pro zvýraznění tvarů (hran) ve velmi špatných světelných podmínkách.
 */
class FastRetinexPlugin : NightModePlugin {
    override val pluginId = "night_retinex"
    override val displayName = "Fast Retinex (CPU Math)"

    private val paint = Paint()

    override suspend fun init() {
        // Inicializace ColorMatrix pro prosvětlení a zvýšení kontrastu
        val matrix = ColorMatrix()
        
        // 1. Zvýšení jasu (Lift)
        val brightness = 40f
        
        // 2. Zvýšení kontrastu (Contrast)
        val contrast = 1.5f
        val contrastTranslation = (-.5f * contrast + .5f) * 255f
        
        matrix.set(floatArrayOf(
            contrast, 0f, 0f, 0f, brightness + contrastTranslation,
            0f, contrast, 0f, 0f, brightness + contrastTranslation,
            0f, 0f, contrast, 0f, brightness + contrastTranslation,
            0f, 0f, 0f, 1f, 0f
        ))

        // 3. Saturace (ve tmě se ztrácí barvy, mírně je obnovíme)
        val satMatrix = ColorMatrix()
        satMatrix.setSaturation(1.3f)
        matrix.postConcat(satMatrix)

        paint.colorFilter = ColorMatrixColorFilter(matrix)
    }

    override suspend fun process(input: Bitmap): Bitmap {
        // Vytvoříme novou mutovatelnou kopii s aplikovaným filtrem
        // V reálu bychom použili RenderScript nebo in-place byte buffer úpravu,
        // ale Canvas s ColorMatrix je pro Android CPU dostatečně rychlý fallback.
        if (paint.colorFilter == null) {
            init()
        }
        val output = Bitmap.createBitmap(input.width, input.height, input.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawBitmap(input, 0f, 0f, paint)
        return output
    }

    override suspend fun release() {
        // CPU plugin nemá velkou alokaci
    }
}
