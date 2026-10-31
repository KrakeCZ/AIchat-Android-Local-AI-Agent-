package com.example.llama.agent.vision

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Zdroje zraku agenta */
enum class VisionSource {
    SCREEN, // Screenshot UI
    WORLD // Fyzický svět (Back/Front Camera)
}

/**
 * VisionSourceManager
 *
 * Implementuje ZERO-COPY architekturu. Odstraňuje JPEG kompresi z procesovací smyčky
 * a distribuuje data POUZE do jedné společné percepční vrstvy (Tensor/MediaPipe).
 * LLM nezískává žádný streamovaný obraz.
 */
class VisionSourceManager {

    companion object {
        private const val TAG = "VisionSourceManager"

        /**
         * Distribuuje zmenšenou Bitmap do PerceptionEngine pro asynchronní
         * MediaPipe/Tensor inferenci. Toto je HLAVNÍ cesta pro Object Detection (Percepci).
         * @param recycleSource Zda má konzument (PerceptionEngine) po použití bitmapu recyklovat.
         */
        var onPerceptionFrame: ((bitmap: android.graphics.Bitmap, recycleSource: Boolean) -> Unit)? = null

        /** 
         * Callback pro komunikaci mezi Studio UI a Background Servicem ohledně vlastnictví kamery. 
         */
        var onStudioStateChanged: ((Boolean) -> Unit)? = null
        var isStudioActive: Boolean = false

        /**
         * Delegát pro vynucení resetu trackeru (např. při přepnutí LAB MODE).
         */
        var onResetTrackers: (() -> Unit)? = null

        // --- FRAME GATE (Anti-Overload) ---
        private val isProcessing = AtomicBoolean(false)

        fun shouldProcessFrame(): Boolean {
            // Pokud engine běží, zahodíme snímek (nepřetěžujeme CPU)
            return isProcessing.compareAndSet(false, true)
        }

        fun onProcessComplete() {
            isProcessing.set(false)
        }

        /** Overload pro ad-hoc zpracování ByteArray z FileSystemManageru (např. z galerie). */
        fun processAndCompressImageBlocking(
                imageBytes: ByteArray,
                targetResolution: Int = 1024
        ): ByteArray? {
            return try {
                val bitmap =
                        android.graphics.BitmapFactory.decodeByteArray(
                                imageBytes,
                                0,
                                imageBytes.size
                        )
                VisionSourceManager()
                        .processAndCompressImageBlocking(
                                bitmap,
                                targetResolution,
                                recycleSource = true
                        )
            } catch (e: Exception) {
                Log.e(TAG, "ByteArray -> Bitmap failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Resizuje a komprimuje Bitmapu na ideální velikost pro LLM (Používá se jen ad-hoc při kliku, ne ve streamu!)
     */
    fun processAndCompressImageBlocking(
            bitmap: Bitmap,
            targetResolution: Int = 1024,
            recycleSource: Boolean = false
    ): ByteArray? {
        return try {
            val scale = targetResolution.toFloat() / Math.max(bitmap.width, bitmap.height)
            var newWidth = Math.round(bitmap.width * scale)
            var newHeight = Math.round(bitmap.height * scale)
            if (newWidth <= 0) newWidth = 1
            if (newHeight <= 0) newHeight = 1

            val scaledBitmap =
                    if (scale < 1.0f) {
                        Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                    } else {
                        bitmap
                    }

            val out = java.io.ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)

            if (scaledBitmap !== bitmap) {
                scaledBitmap.recycle()
            }
            if (recycleSource && !bitmap.isRecycled) {
                // NEVOLAT .recycle() - necháme na Android GC
                // bitmap.recycle()
            }
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "processAndCompressImageBlocking failed: ${e.message}", e)
            null
        }
    }

    /**
     * Vnitřní vrstva pro zpracování streamovaných CameraX Framů (ImageProxy). 
     */
    class LiveVideoStreamer(private val context: android.content.Context) {
        private var lastProcessedFrameTimestamp: Long = 0
        private val settingsManager by lazy { com.example.llama.SettingsManager(context) }
        
        // ZERO-COPY BUFFER
        private var sharedBitmap: Bitmap? = null

        var onFrameProcessed: ((ByteArray) -> Unit)? = null // Zpětná kompatibilita

        private val perceptionTargetResolution: Int
            get() = settingsManager.perceptionResolution

        private val msIntervalBetweenFrames: Long
            get() {
                val fps = settingsManager.smartVisionLiveFps.coerceIn(1, 60)
                return 1000L / fps
            }

        @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
        fun processImageProxy(imageProxy: androidx.camera.core.ImageProxy): ByteArray? {
            // 1. Časový interval check
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessedFrameTimestamp < msIntervalBetweenFrames) {
                imageProxy.close() // Skip frame
                return null
            }
            
            // 2. FRAME GATE (Atomic Check)
            if (!VisionSourceManager.shouldProcessFrame()) {
                imageProxy.close() // Engine je zaneprázdněn, CPU odpočívá
                return null
            }

            try {
                val mediaImage = imageProxy.image
                if (mediaImage != null) {
                    // ZERO-COPY PIXEL COPY (Očekáváme RGBA_8888 z CameraX konfigurace)
                    val width = imageProxy.width
                    val height = imageProxy.height
                    
                    if (sharedBitmap == null || sharedBitmap!!.width != width || sharedBitmap!!.height != height) {
                        Log.i(TAG, "Vytvářím sdílenou bitmapu ${width}x${height} (POUZE JEDNOU)")
                        sharedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    }
                    
                    val buffer = imageProxy.planes[0].buffer
                    buffer.rewind()
                    sharedBitmap!!.copyPixelsFromBuffer(buffer)
                    
                    lastProcessedFrameTimestamp = currentTime

                    // Aplikovat rotaci pro MediaPipe/Tensor pokud držíme telefon na výšku
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    var finalBitmap = sharedBitmap!!
                    if (rotation != 0) {
                        val matrix = Matrix()
                        matrix.postRotate(rotation.toFloat())
                        finalBitmap = Bitmap.createBitmap(sharedBitmap!!, 0, 0, width, height, matrix, false)
                    }

                    // ====== PERCEPTION DISTRIBUTION (Tensor/MLKit) ======
                    // Posíláme do Object Detectoru
                    if (VisionSourceManager.onPerceptionFrame != null) {
                        try {
                            val perceptionScale = perceptionTargetResolution.toFloat() / Math.max(finalBitmap.width, finalBitmap.height)
                            if (perceptionScale < 1.0f) {
                                val tbWidth = Math.round(finalBitmap.width * perceptionScale).coerceAtLeast(1)
                                val tbHeight = Math.round(finalBitmap.height * perceptionScale).coerceAtLeast(1)
                                val scaledBitmap = Bitmap.createScaledBitmap(finalBitmap, tbWidth, tbHeight, true)
                                if (scaledBitmap === finalBitmap) {
                                    VisionSourceManager.onPerceptionFrame?.invoke(scaledBitmap, false)
                                } else {
                                    VisionSourceManager.onPerceptionFrame?.invoke(scaledBitmap, true)
                                }
                            } else {
                                VisionSourceManager.onPerceptionFrame?.invoke(finalBitmap.copy(Bitmap.Config.ARGB_8888, false)!!, true)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Perception frame distribution failed", e)
                        }
                    }
                    // ====== END PERCEPTION ======
                    
                    // LLM agent si vezme obraz jen explicitně (ad-hoc) ze snapshotu kamery přes Toolbar.
                    
                    // Uvolníme otočenou bitmapu, sdílená (sharedBitmap) zůstává pro další frame
                    if (finalBitmap !== sharedBitmap!!) {
                        finalBitmap.recycle()
                    }
                    
                    return null
                } else {
                    return null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při parsování Live Video Framu. Zkontroluj zda CameraX má format OUTPUT_IMAGE_FORMAT_RGBA_8888!", e)
                return null
            } finally {
                // VŽDY Uvolníme FRAME GATE a ImageProxy
                VisionSourceManager.onProcessComplete()
                imageProxy.close()
            }
        }
    }

    /**
     * Vnitřní vrstva pro zpracování streamovaných obrazovek.
     */
    class ScreenVideoStreamer(private val context: android.content.Context) {
        private var lastProcessedFrameTimestamp: Long = 0
        private val settingsManager by lazy { com.example.llama.SettingsManager(context) }
        private var sharedBitmap: Bitmap? = null

        var onFrameProcessed: ((ByteArray) -> Unit)? = null // Zpětná kompatibilita

        private val perceptionTargetResolution: Int
            get() = settingsManager.perceptionResolution

        private val msIntervalBetweenFrames: Long
            get() {
                val fps = settingsManager.smartVisionLiveFps.coerceIn(1, 60)
                return 1000L / fps
            }

        fun processImage(image: android.media.Image): ByteArray? {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessedFrameTimestamp < msIntervalBetweenFrames) {
                image.close()
                return null
            }
            
            if (!VisionSourceManager.shouldProcessFrame()) {
                image.close()
                return null
            }

            return try {
                val planes = try {
                    image.planes
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "ImageReader closed", e)
                    return null
                }
                
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                
                val width = rowStride / pixelStride
                val height = image.height
                
                if (sharedBitmap == null || sharedBitmap!!.width != width || sharedBitmap!!.height != height) {
                    sharedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }

                // Oprava SIGSEGV: Kopie do direct bufferu
                val targetCapacity = rowStride * image.height
                val safeBuffer = java.nio.ByteBuffer.allocateDirect(targetCapacity)
                buffer.rewind()
                safeBuffer.put(buffer)
                safeBuffer.rewind()

                sharedBitmap!!.copyPixelsFromBuffer(safeBuffer)
                
                val croppedBitmap = Bitmap.createBitmap(sharedBitmap!!, 0, 0, image.width, image.height)

                lastProcessedFrameTimestamp = currentTime

                // ====== PERCEPTION DISTRIBUTION ======
                if (VisionSourceManager.onPerceptionFrame != null) {
                    try {
                        val perceptionScale = perceptionTargetResolution.toFloat() / Math.max(croppedBitmap.width, croppedBitmap.height)
                        if (perceptionScale < 1.0f) {
                            val tbWidth = Math.round(croppedBitmap.width * perceptionScale).coerceAtLeast(1)
                            val tbHeight = Math.round(croppedBitmap.height * perceptionScale).coerceAtLeast(1)
                            val scaledBitmap = Bitmap.createScaledBitmap(croppedBitmap, tbWidth, tbHeight, true)
                            if (scaledBitmap === croppedBitmap) {
                                VisionSourceManager.onPerceptionFrame?.invoke(scaledBitmap, false)
                            } else {
                                VisionSourceManager.onPerceptionFrame?.invoke(scaledBitmap, true)
                            }
                        } else {
                            VisionSourceManager.onPerceptionFrame?.invoke(croppedBitmap.copy(Bitmap.Config.ARGB_8888, false)!!, true)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Perception frame distribution failed", e)
                    }
                }
                
                if (croppedBitmap !== sharedBitmap!!) {
                    croppedBitmap.recycle()
                }

                null // Streamování videa do LLM bylo odstraněno
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při parsování Screen Framu", e)
                null
            } finally {
                VisionSourceManager.onProcessComplete()
                image.close()
            }
        }
    }
}
