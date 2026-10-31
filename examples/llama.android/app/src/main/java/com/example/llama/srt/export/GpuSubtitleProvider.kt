package com.example.llama.srt.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff

/**
 * Standardní GPU Pipeline (Industry Standard).
 * Kreslí do Bitmapy v paměti a posílá ji do C++ OpenGL k prolnutí s videem.
 */
class GpuSubtitleProvider(val width: Int, val height: Int) {

    // Vytvoříme klasickou, recyklovatelnou Bitmapu a Canvas
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)

    fun renderFrame(ptsMs: Long, drawCallback: (Canvas) -> Unit): Bitmap {
        // Rychlé smazání předchozího snímku (Funguje všude, žádný Vulkan crash)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        
        // Vykreslení UI/Titulků
        drawCallback(canvas)
        
        return bitmap
    }

    fun release() {
        if (!bitmap.isRecycled) {
            bitmap.recycle()
        }
    }
}
