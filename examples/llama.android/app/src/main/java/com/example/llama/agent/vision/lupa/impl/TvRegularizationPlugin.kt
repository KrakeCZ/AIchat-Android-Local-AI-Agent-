package com.example.llama.agent.vision.lupa.impl

import android.graphics.Bitmap
import com.example.llama.agent.vision.camera.math.FastTotalVariation
import com.example.llama.agent.vision.lupa.LupaCategory
import com.example.llama.agent.vision.lupa.LupaPlugin

/**
 * Total Variation (TV) Regularization plugin.
 * Minimalizuje celkovou variaci obrazu. Efektivně odstraňuje šum a aliasing,
 * přičemž zachovává absolutně ostré hrany. Ideální pro velké přiblížení objektů.
 */
class TvRegularizationPlugin : LupaPlugin {
    override val pluginId = "lupa_tv_regularization"
    override val displayName = "TV Regularization (Hrany)"
    override val category = LupaCategory.SINGLE_FRAME
    override val expectedFpsHint = 20

    override fun process(input: Bitmap, scaleFactor: Float): Bitmap {
        val dstW = (input.width * scaleFactor).toInt().coerceAtLeast(1)
        val dstH = (input.height * scaleFactor).toInt().coerceAtLeast(1)
        
        val dstBitmap = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        
        // Využijeme již existující C-like optimalizovaný engine s Zero-Allocation pristupem (interni buffery)
        FastTotalVariation.processZoom(
            srcBitmap = input,
            dstBitmap = dstBitmap,
            cropX = 0f,
            cropY = 0f,
            cropW = input.width.toFloat(),
            cropH = input.height.toFloat()
        )
        
        return dstBitmap
    }

    override fun reset() {}
    override fun release() {}
}
