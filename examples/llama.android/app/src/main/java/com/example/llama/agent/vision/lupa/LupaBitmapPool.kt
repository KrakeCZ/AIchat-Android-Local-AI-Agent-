package com.example.llama.agent.vision.lupa

import android.graphics.Bitmap
import android.util.Log

/**
 * Zero-GC pre-allocated Bitmap pool — analogie k hardware DMA buffer v průmyslových kamerách.
 * GenICam standard: "StreamDefaultBufferCount" = N pre-pinned memory segments.
 *
 * Acquire = vezmi slot z poolu. Release = vrať. Žádné GC alokace za běhu po warmup.
 */
class LupaBitmapPool(var capacity: Int) {

    companion object {
        private const val TAG = "LupaBitmapPool"
    }

    private val available = ArrayDeque<Bitmap>(capacity)
    private val lock = Object()

    @Volatile private var poolWidth = 0
    @Volatile private var poolHeight = 0

    /**
     * Získá Bitmap z poolu. Pokud se změní rozměry (jiný crop), pool se invaliduje.
     * Thread-safe.
     */
    fun acquire(width: Int, height: Int): Bitmap {
        synchronized(lock) {
            // Invalidace při změně rozměrů (uživatel přepnul crop area / zoom)
            if (width != poolWidth || height != poolHeight) {
                available.forEach { if (!it.isRecycled) it.recycle() }
                available.clear()
                poolWidth = width
                poolHeight = height
                Log.d(TAG, "Pool re-init: ${width}x${height}")
            }
            return if (available.isNotEmpty()) {
                available.removeFirst()
            } else {
                // Pool vyčerpán (burst) — alokuj nový slot (GC hit jen při warmup)
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    /** Vrátí Bitmap do poolu. Pokud je pool plný nebo má jiné rozměry, bitmap se recykluje. */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        synchronized(lock) {
            if (bitmap.width == poolWidth && bitmap.height == poolHeight
                && available.size < capacity) {
                available.addLast(bitmap)
            } else {
                bitmap.recycle()
            }
        }
    }

    fun release(bitmaps: List<Bitmap>) = bitmaps.forEach { release(it) }

    fun destroy() {
        synchronized(lock) {
            available.forEach { if (!it.isRecycled) it.recycle() }
            available.clear()
        }
    }
}
