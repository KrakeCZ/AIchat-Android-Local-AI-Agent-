package com.example.llama.agent.perception

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * ======================================================================== SYSTEM AUDIO INTERCEPTOR
 * (Fáze H — Android 14/15/16 Compliant) ========================================================================
 *
 * Slouží k zachytávání systémového zvuku jiných aplikací (YouTube, TikTok), aniž by zvuk unikal z
 * reproduktoru a namáhal mikrofon (kvalita, soukromí).
 *
 * Využívá MediaProjection API (Android 10+) a AudioPlaybackCaptureConfiguration.
 *
 * KLÍČOVÝ PRINCIP (hlasitost 0):
 * AudioPlaybackCaptureConfiguration zachytává digitální PCM stream přímo z AudioFlinger
 * subsystému OS PŘED hardwarovým mixérem a aplikací Master Volume.
 * Uživatel může mít hlasitost nastavenou na 0 a engine přesto přijme plný audio stream.
 * Jedinou překážkou je FLAG_SECURE / DRM ochrana (Netflix, bankovnictví) — tam API vrací
 * stream ticha → PerceptionEventBus emituje SystemDrmEvent.
 *
 * LIFECYCLE:
 * - MediaProjection.Callback registrován PŘED vytvořením AudioRecord
 * - onStop() automaticky zavolá release() + notifikuje přes onProjectionStopped callback
 * - release() je idempotentní — vícenásobné volání je bezpečné
 *
 * Správa lifecycle: O `MediaProjection` objekt je nutné pečovat. Musí se uvolnit voláním `release()`.
 */
class SystemAudioInterceptor(private val context: Context) {

    companion object {
        private const val TAG = "SystemAudioInterceptor"
        var activeInstance: SystemAudioInterceptor? = null

        fun toggleWakeupHackVolume() {
            val track = activeInstance?.wakeupAudioTrack ?: return
            // Pokud je volume větší než 0, dáme 0. Jinak 0.01f.
            // Poznámka: Není tu přímý getter pro hlasitost, tak si to musíme trackovat
            // Nebo prostě zavoláme setVolume(0.01f) a necháme uživatele testovat. 
            // Pro jednoduchost budeme vždy zkoušet 0.01f
            // (nebo si můžeme přidat field isWakeupLoud do activeInstance)
            activeInstance?.isWakeupLoud = !(activeInstance?.isWakeupLoud ?: false)
            val newVol = if (activeInstance?.isWakeupLoud == true) 0.01f else 0.0f
            track.setVolume(newVol)
            android.util.Log.i(TAG, "🔊 Wakeup volume nastavena na $newVol")
        }
    }
    
    var isWakeupLoud = false

    private var mediaProjection: MediaProjection? = null
    private var isIntercepting = false
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Přehrávač absolutního ticha pro "Mixer Wakeup"
    private var wakeupAudioTrack: android.media.AudioTrack? = null

    /**
     * Zda aktuálně existuje platná MediaProjection relace, 
     * kterou lze recyklovat pro další Audio/Video streamy.
     */
    val hasActiveProjection: Boolean
        get() = mediaProjection != null

    /**
     * Optional callback — volán když systém ukončí MediaProjection session
     * (zamčení obrazovky, jiná session, uživatel klikl na systémový chip).
     * Registrovat PŘED voláním createVirtualAudioRecord().
     */
    var onProjectionStopped: (() -> Unit)? = null

    /**
     * MediaProjection.Callback — registrován PŘED vytvořením AudioRecord.
     * Android 14+ vyžaduje registraci callbacku. Callback onStop() je zavolán
     * systémem kdykoliv je session ukončena (ne jen naším voláním stop()).
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            super.onStop()
            Log.i(TAG, "🔄 MediaProjection.Callback.onStop() — systém ukončil session")
            // Nevoláme mediaProjection?.stop() — systém ji právě zastavil!
            // Pouze vyčistíme lokální stav.
            mediaProjection = null
            isIntercepting = false
            // Notifikujeme hlavní službu (TranscriptForegroundService)
            onProjectionStopped?.invoke()
        }
    }

    /** Zastaví zachytávání a uvolní oprávnění pro vnitřní zvuk. Idempotentní. */
    fun release() {
        if (activeInstance === this) activeInstance = null
        if (!isIntercepting && mediaProjection == null) return
        Log.i(TAG, "🛑 Uvolňuji MediaProjection token")
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {}
        try {
            mediaProjection?.stop()
        } catch (_: Exception) {}
        
        try {
            wakeupAudioTrack?.stop()
            wakeupAudioTrack?.release()
        } catch (_: Exception) {}
        wakeupAudioTrack = null
        
        mediaProjection = null
        isIntercepting = false
        onProjectionStopped = null
    }

    /**
     * Vygeneruje nakonfigurovaný AudioRecord pro nahrávání interního systému. Tento AudioRecord
     * může okamžitě obsluhovat klasický AudioRecord.startRecording().
     *
     * PREREKVIZITA: Musí být volán z kontextu Foreground Service s foregroundServiceType="mediaProjection"!
     * Bez toho Android 14+ vyhodí SecurityException.
     *
     * @param resultCode Výsledek z MediaProjectionPermissionActivity
     * @param data Intent s tokenem z MediaProjectionManager
     * @param sampleRate 16000 pro Whisper/Sherpa
     * @param bufferSize Velikost interního bufferu AudioRecord (alespoň MinBufferSize)
     */
    @SuppressLint(
            "MissingPermission"
    ) // Oprávnění FOREGROUND_SERVICE_MEDIA_PROJECTION deklarováno v Manifestu
    fun createVirtualAudioRecord(
            resultCode: Int,
            data: Intent,
            sampleRate: Int,
            bufferSize: Int
    ): AudioRecord? {
        activeInstance = this
        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "❌ MediaProjection zamítnuta uživatelem: $resultCode")
            return null
        }

        try {
            if (mediaProjection == null) {
                val projectionManager =
                        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as
                                MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)

                if (mediaProjection == null) {
                    Log.e(TAG, "❌ Selhalo vytvoření MediaProjection z tokenu.")
                    return null
                }

                Log.i(TAG, "✅ MediaProjection token úspěšně získán. Vytvářím Virtual AudioRecord...")

                // KRITICKÉ: Registrace callbacku PŘED vytvořením AudioRecord (Android best practice)
                // Handler na MainThread — callback bude volán na UI vlákně
                mediaProjection!!.registerCallback(projectionCallback, mainHandler)
            } else {
                Log.i(TAG, "✅ Recykluji existující MediaProjection token pro AudioRecord...")
            }

            // --- MIXER WAKEUP ---
            // Nativní hardware offload (používaný TikTokem a Chrome WebView pro úsporu baterie)
            // bypassuje MediaProjection. Abychom vynutili použití softwarového AudioFlinger mixéru,
            // spustíme statickou smyčku absolutního ticha. 
            // DŮLEŽITÉ: Spouštíme PŘED samotným zachytáváním, aby byl mixér už probuzený a připravený
            // (bráníme tak OS Race Condition, kdy aplikace hrající před startem získají mute).
            try {
                val sampleRateWakeup = 44100
                val frameCount = sampleRateWakeup / 2 // Půl sekundy ticha
                val silenceData = ByteArray(frameCount * 2) // 16-bit = 2 bytes per frame
                
                wakeupAudioTrack = android.media.AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRateWakeup)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(silenceData.size)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build()
                
                wakeupAudioTrack?.write(silenceData, 0, silenceData.size)
                wakeupAudioTrack?.setLoopPoints(0, frameCount, -1) // Nekonečná smyčka
                wakeupAudioTrack?.play()
                Log.i(TAG, "🔊 Mixer Wakeup spuštěn (Silent AudioTrack).")
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Nepodařilo se spustit Mixer Wakeup ", e)
            }
            // --------------------------

            // Nastavíme konfiguraci: Zachytáváme všechny relevantní audio zdroje.
            // USAGE_MEDIA     — YouTube, MediaPlayer, Spotify, Netflix (pokud nemá DRM)
            // USAGE_GAME      — herní zvuky
            // USAGE_UNKNOWN   — Chrome WebView, neznámé audio pipeline
            // USAGE_ASSISTANT — Google Assistant, Bixby, TTS z asistentů
            //
            // SYSTÉMEM BLOKOVANÉ (nelze zachytit ani s tímto nastavením):
            // USAGE_VOICE_COMMUNICATION, USAGE_ALARM, USAGE_NOTIFICATION_RINGTONE
            // → zachovává soukromí telefonních hovorů a systémových zvuků
            val config =
                    AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .addMatchingUsage(AudioAttributes.USAGE_ASSISTANT)
                            .excludeUid(android.os.Process.myUid()) // NEZACHYTÁVAT SÁM SEBE (Wakeup ticho)
                            .build()

            val audioFormat =
                    AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()

            val audioRecord =
                    AudioRecord.Builder()
                            .setAudioFormat(audioFormat)
                            .setBufferSizeInBytes(bufferSize)
                            .setAudioPlaybackCaptureConfig(config)
                            .build()

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(
                        TAG,
                        "❌ Vytvoření AudioRecord pro MediaProjection selhalo. State = ${audioRecord.state}"
                )
                release()
                return null
            }

            // Okamžitý start nahrávání. Eliminuje zpoždění OS, které by mohlo způsobit zrušení 
            // audio streamu (Samsung OneUI / Android 14+ bug)
            audioRecord.startRecording()

            isIntercepting = true
            Log.i(TAG, "🎙️ Virtual AudioRecord připraven pro 16-bit PCM @ $sampleRate Hz")
            return audioRecord
        } catch (e: SecurityException) {
            Log.e(
                    TAG,
                    "❌ SecurityException: Systém zabil nahrávání! Chybí Foreground Service typu mediaProjection.",
                    e
            )
            release()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Výjimka při zakládání AudioPlaybackCapture", e)
            release()
            return null
        }
    }

    /**
     * Vytvoří VirtualDisplay z MediaProjection tokenu.
     * Umožňuje zachytávat obrazovku bez ovlivnění zvuku.
     * Výstup směřuje do předaného Surface (typicky z ImageReaderu).
     *
     * @param resultCode Výsledek z MediaProjectionPermissionActivity
     * @param data Intent s tokenem z MediaProjectionManager
     * @param width Šířka obrazovky (ideálně upravená přes DisplayMetrics pro zachování poměru 1:1)
     * @param height Výška obrazovky
     * @param densityDpi Hustota pixelů obrazovky
     * @param surface Cílový surface (např. ImageReader.surface)
     */
    fun createVirtualDisplay(
        resultCode: Int,
        data: Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: android.view.Surface
    ): android.hardware.display.VirtualDisplay? {
        if (resultCode != Activity.RESULT_OK) {
            Log.e(TAG, "❌ MediaProjection zamítnuta uživatelem: $resultCode")
            return null
        }

        try {
            if (mediaProjection == null) {
                val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)
                
                if (mediaProjection == null) {
                    Log.e(TAG, "❌ Selhalo vytvoření MediaProjection z tokenu pro obraz.")
                    return null
                }
                
                Log.i(TAG, "✅ MediaProjection token úspěšně získán. Vytvářím VirtualDisplay...")
                mediaProjection!!.registerCallback(projectionCallback, mainHandler)
            } else {
                Log.i(TAG, "✅ Recykluji existující MediaProjection token pro VirtualDisplay...")
            }

            val virtualDisplay = mediaProjection!!.createVirtualDisplay(
                "LlamaVisionScreen",
                width,
                height,
                densityDpi,
                android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                mainHandler
            )
            
            isIntercepting = true
            Log.i(TAG, "🖥️ VirtualDisplay úspěšně vytvořen (${width}x${height}).")
            return virtualDisplay
        } catch (e: SecurityException) {
            Log.e(TAG, "❌ SecurityException: Systém zabil nahrávání! Chybí Foreground Service typu mediaProjection.", e)
            release()
            return null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Výjimka při zakládání VirtualDisplay", e)
            release()
            return null
        }
    }
}
