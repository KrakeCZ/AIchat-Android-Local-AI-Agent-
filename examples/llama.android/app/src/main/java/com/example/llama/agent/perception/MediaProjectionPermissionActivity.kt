package com.example.llama.agent.perception

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * ========================================================================
 * MEDIA PROJECTION PERMISSION ACTIVITY
 * ========================================================================
 *
 * Transparentní průchozí aktivita, která má jediný úkol:
 * Zobrazit uživateli systémový dialog "Povolit nahrávání obrazovky"
 * a vrátit výsledek (Token) zpět do SystemAudioInterceptor.
 *
 * Protože jsme AccessibilityService, nemůžeme přímo vyvolat tento dialog.
 * Musíme použít Activity. Aktivita se ihned po zavření dialogu zničí.
 *
 * TOKEN PŘEDÁVÁNÍ (Android 14+ Workaround):
 * Intent z onActivityResult obsahuje MediaProjection token.
 * Na Android 14+ se tento Intent NEDÁ spolehlivě předat přes
 * putExtra(key, Intent) do jiného Intent (Binder Parcel lazy unmarshalling).
 * Proto ukládáme resultCode + data do statického companion object pole
 * [pendingResultCode] / [pendingResultData] a service si je přečte
 * přímo z paměti (same process, same ClassLoader, zero-copy).
 */
class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val TAG = "MediaProjectionActivity"
        private const val REQUEST_CODE = 1001

        // Callback zpět do Interceptoru
        var onResultCallback: ((Int, Intent?) -> Unit)? = null

        /**
         * Pending MediaProjection token — uložený v paměti procesu.
         * Service si přečte tyto hodnoty namísto intent extras.
         * Vynulováno po konzumaci v TranscriptForegroundService.
         */
        @Volatile
        var pendingResultCode: Int = Activity.RESULT_CANCELED
            private set

        @Volatile
        var pendingResultData: Intent? = null
            private set

        /** Konzumace tokenu — voláno TranscriptForegroundService po přečtení. */
        fun consumePendingToken(): Pair<Int, Intent?> {
            val code = pendingResultCode
            val data = pendingResultData
            // Vynulování — token je one-time-use
            pendingResultCode = Activity.RESULT_CANCELED
            pendingResultData = null
            return Pair(code, data)
        }

        fun start(context: Context, callback: (Int, Intent?) -> Unit) {
            onResultCallback = callback
            val intent = Intent(context, MediaProjectionPermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Zabráníme spuštění bez callbacku (např. obnova systémem)
        if (onResultCallback == null) {
            Log.e(TAG, "❌ Spuštěno bez callbacku, ukončuji.")
            finish()
            return
        }

        try {
            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Nelze spustit vytvoření ScreenCaptureIntent: ${e.message}")
            onResultCallback?.invoke(Activity.RESULT_CANCELED, null)
            onResultCallback = null
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE) {
            Log.i(TAG, "🎤 MediaProjection dialog result: $resultCode (data=${data != null})")

            // Uložíme token do statického pole (same process memory)
            // Tím obcházíme problém s Binder Parcel lazy unmarshalling
            pendingResultCode = resultCode
            pendingResultData = data

            onResultCallback?.invoke(resultCode, data)
            onResultCallback = null
        }

        // Android 14 workaround: Ponecháme transparentní aktivitu naživu dalších 500ms,
        // aby byla aplikace stále považována za "Foreground", zatímco Service zpracovává
        // 150ms delay a volá getMediaProjection().
        window?.decorView?.postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, 500)
    }
}
