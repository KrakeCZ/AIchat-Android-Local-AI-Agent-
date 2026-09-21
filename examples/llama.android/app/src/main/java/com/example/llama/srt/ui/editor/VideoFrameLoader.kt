package com.example.llama.srt.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * NLE Video Frame Extractor (System Level / Proxy Engine)
 *
 * Architektura: Lazy-loading, LRU In-Memory Cache (1/8 RAM).
 * Zajišťuje HW-akcelerovanou nebo SW extrakci I-framů a P-framů.
 *
 * OPRAVA (v2):
 * - Snímky se vždy načítají v fixní proxy velikosti PROXY_W × PROXY_H (160×90).
 *   Předchozí verze předávala dynamický widthPx ze zoomu → každá zoom úroveň
 *   způsobila cache miss (jiný klíč), čímž se LRU naplnila duplikáty.
 * - getFrameAtTime() ignoruje reqWidth/reqHeight (interní konstanty), zachováno
 *   v signatuře pro zpětnou kompatibilitu s Compose callsite.
 *
 * Film-strip princip:
 * - Compose mění pouze vizuální šířku Box slotu (CSS-like transform).
 * - Bitmap zůstává fixních 160×90; ContentScale.Crop ho vizuálně vyplní.
 * - Žádné nové IO při zoom / pan.
 */
class VideoFrameLoader(val context: Context, uri: Uri) {

    companion object {
        // Fixní proxy rozlišení — nikdy se nemění bez ohledu na zoom úroveň
        private const val PROXY_W = 160
        private const val PROXY_H = 90
        private const val TAG = "VideoFrameLoader"
    }

    private val retriever = MediaMetadataRetriever()
    private val retrieverMutex = Mutex()
    private val memoryCache: LruCache<Long, Bitmap>

    // Stavové proměnné (chraněné mutexem)
    private var isUsingProxy = false
    private var currentUri: Uri? = uri

    init {
        try {
            retriever.setDataSource(context, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Chyba inicializace MMR: ${e.message}")
        }

        val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxMemoryKb / 8

        memoryCache = object : LruCache<Long, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: Long, bitmap: Bitmap): Int {
                return bitmap.byteCount / 1024
            }
            override fun entryRemoved(evicted: Boolean, key: Long, oldValue: Bitmap, newValue: Bitmap?) {
                if (evicted) {
                    oldValue.recycle()
                }
            }
        }
    }

    /**
     * Hot-Swap datového zdroje pro proxy.
     * Uvolní starou cache a nastaví nový zdroj bez OOM.
     */
    suspend fun setDataSource(uri: Uri, isProxy: Boolean) {
        retrieverMutex.withLock {
            if (currentUri == uri) return@withLock
            try {
                // EXPLICITNÍ UVOLNĚNÍ PAMĚTI: Agresivní výmaz starých (potenciálně 4K) snímků
                // před načtením proxy. Zabrání OOM (Out Of Memory) při přepnutí.
                memoryCache.evictAll()

                // Připojení nového zdroje - v SDK >= 29 není nutné uvolňovat celý retriever,
                // ale pro jistotu (jak uvádí plán) jej můžeme i vyměnit, ovšem zde jen nastavíme zdroj
                retriever.setDataSource(context, uri)
                currentUri = uri
                isUsingProxy = isProxy
                
                Log.i(TAG, "DataSource hot-swap na: ${uri.lastPathSegment}, proxy: $isProxy")
            } catch (e: Exception) {
                Log.e(TAG, "Selhalo hot-swap připojení zdroje: ${e.message}")
            }
        }
    }

    suspend fun getFrameAtTime(
        timeMs: Long,
        @Suppress("UNUSED_PARAMETER") reqWidth: Int,
        @Suppress("UNUSED_PARAMETER") reqHeight: Int,
        frameIntervalMs: Long
    ): Bitmap? {
        // 1. Rychlý L1 Cache Hit (Pokud už je v RAM, okamžitě vracíme)
        memoryCache.get(timeMs)?.let { return it }

        // 2. MAGIE: Oddělení od Compose UI. 
        // NonCancellable zajistí, že se extrakce DOPOČÍTÁ a ULOŽÍ DO CACHE, 
        // i když uživatel scrolloval/zoomoval pryč.
        return withContext(Dispatchers.IO + NonCancellable) {
            
            // Dvojitá kontrola po přepnutí kontextu vlákna (prevence race-condition)
            memoryCache.get(timeMs)?.let { return@withContext it }

            try {
                val timeUs = timeMs * 1000L
                
                // Pokud čteme z našeho All-Intra Proxy, můžeme použít OPTION_CLOSEST 
                // pro bleskovou a přesnou extrakci bez keyframe penalty.
                val extractOption = if (isUsingProxy) {
                    MediaMetadataRetriever.OPTION_CLOSEST
                } else {
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                }

                // Extrakce pod zámkem (chrání nativní C++ FFmpeg/MediaCodec)
                val rawBitmap = retrieverMutex.withLock {
                    retriever.getFrameAtTime(timeUs, extractOption)
                }

                if (rawBitmap != null) {
                    // Striktní proxy downscale na fixní šířku
                    val scaledBitmap = Bitmap.createScaledBitmap(rawBitmap, PROXY_W, PROXY_H, true)
                    if (scaledBitmap !== rawBitmap) {
                        rawBitmap.recycle() // Kritické: zahození 4K originálu
                    }
                    
                    // Uložení do RAM Cache (Příště to bude instantní!)
                    memoryCache.put(timeMs, scaledBitmap)
                    scaledBitmap
                } else {
                    null
                }

            } catch (e: Exception) {
                // Nyní už sem Compose CancellationException nespadne!
                Log.e(TAG, "Extrakce selhala na čase $timeMs ms: ${e.message}")
                null
            }
        }
    }

    /** Uvolní MediaMetadataRetriever a evictuje celou LRU cache. */
    fun release() {
        try {
            retriever.release()
        } catch (e: Exception) {
            Log.e(TAG, "Chyba při release retriever: ${e.message}")
        }
        memoryCache.evictAll()
        Log.d(TAG, "VideoFrameLoader uvolněn, cache evictována.")
    }
}
