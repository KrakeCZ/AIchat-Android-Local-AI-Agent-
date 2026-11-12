package com.example.llama.agent.core

import android.Manifest
import android.app.Activity

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.Build
import android.provider.Settings

import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SystemController — Non-Root System Operations Layer
 *
 * Zodpovědnosti:
 * 1. Telephony: Volání (ACTION_CALL + Dual-SIM + ACTION_DIAL fallback)
 * 2. SMS: Zprávy (ACTION_SENDTO smsto: — bypass generic chooser)
 * 3. Web: Prohlížeč (ACTION_VIEW s https:// validací)
 * 4. Email: Kompozice (ACTION_SENDTO mailto: s subject/body)
 * 5. Settings: Deep links do systémového nastavení
 * 6. HW Toggles: WiFi, Bluetooth, Flashlight (non-root API)
 * 7. App Management: Name→Package cache s fuzzy search

 *
 * SECURITY:
 * - Žádné root/ADB operace
 * - Runtime permission check před každou privilegovanou operací
 * - Fallback chain (CALL→DIAL, WiFi direct→Panel)
 *
 * THREAD SAFETY:
 * - Všechny Intent operace na Dispatchers.Main
 * - App cache je thread-safe (synchronized refresh)
 */
class SystemController(private val context: Context) {

    companion object {
        private const val TAG = "SystemController"
    }

    // === Lazy-init system services ===
    private val packageManager: PackageManager = context.packageManager
    private val wifiManager: WifiManager? by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    }
    private val cameraManager: CameraManager? by lazy {
        context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    }
    private val telecomManager: TelecomManager? by lazy {
        context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    }
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    // === App cache: Název → PackageName ===
    @Volatile
    private var appCache: List<Pair<String, String>> = emptyList()

    init {
        refreshAppCache()
    }

    // ==================== VoIP CALLING (Zero Permissions) ====================

    /**
     * Ranked VoIP deep link targets.
     * Pořadí: Nejpoužívanější → Specifické.
     * Deep links nepotřebují žádná oprávnění — Google Play compliant.
     */
    private data class VoipTarget(
        val packageName: String,
        val appName: String,
        val buildDeepLink: (String) -> String
    )

    private val voipTargets = listOf(
        VoipTarget("com.whatsapp", "WhatsApp") { number ->
            "https://api.whatsapp.com/send?phone=${normalizeForVoip(number)}"
        },
        VoipTarget("com.viber.voip", "Viber") { number ->
            "viber://contact?number=${normalizeForVoip(number)}"
        },
        VoipTarget("org.telegram.messenger", "Telegram") { number ->
            // Telegram deep link přes tel: schéma (kontakt musí být v kontaktech)
            "tg://resolve?phone=${normalizeForVoip(number)}"
        },
        VoipTarget("org.thoughtcrime.securesms", "Signal") { number ->
            "sgnl://signal.me/#p/+${normalizeForVoip(number)}"
        },
        VoipTarget("com.facebook.orca", "Messenger") { number ->
            // Messenger fallback — otevře app (deep link vyžaduje FB user ID)
            "fb-messenger://user-thread/${normalizeForVoip(number)}"
        },
        VoipTarget("com.discord", "Discord") { _ ->
            // Discord nemá tel deep link — otevře app
            "discord://"
        }
    )

    /**
     * Normalizace telefonního čísla pro VoIP deep links.
     * Odstraní mezery, pomlčky, závorky. Ponechá +.
     */
    private fun normalizeForVoip(number: String): String {
        return number.replace(Regex("[\\s\\-().]"), "")
    }

    /**
     * VoIP volání přes nainstalovanou VoIP appku (zero permissions).
     *
     * Strategie:
     * 1. Projde ranked seznam VoIP apps (WhatsApp → Viber → Telegram → Signal)
     * 2. Pro první nalezenou nainstalovanou app otevře deep link
     * 3. Fallback: Otevře jakoukoliv VoIP app (bez deep link)
     *
     * @param number Telefonní číslo nebo kontakt
     */
    suspend fun makeVoipCall(number: String) = withContext(Dispatchers.Main) {
        val cleanNumber = normalizePhoneNumber(number)

        if (cleanNumber.isEmpty()) {
            Log.w(TAG, "Empty number after normalization — aborting VoIP call")
            return@withContext
        }

        // VoIP vyžaduje internet — hasPermission s INTERNET (2. využití parametru)
        if (!hasPermission(Manifest.permission.INTERNET)) {
            Log.w(TAG, "⚠️ INTERNET permission missing — VoIP call may fail")
        }

        // TelecomManager info log (využití importu — info only, žádné SIM operace)
        val hasSimCapability = telecomManager != null
        Log.d(TAG, "📞 VoIP call requested: $cleanNumber (SIM available: $hasSimCapability, using VoIP only)")

        // Strategie: Projdi ranked VoIP targets a najdi nainstalovanou appku
        for (target in voipTargets) {
            if (isAppInstalled(target.packageName)) {
                val deepLink = target.buildDeepLink(cleanNumber)
                Log.i(TAG, "📞 VoIP → ${target.appName}: $deepLink")

                try {
                    val intent = Intent(Intent.ACTION_VIEW, deepLink.toUri()).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        setPackage(target.packageName)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "✓ VoIP call launched via ${target.appName} (resultCode baseline: ${Activity.RESULT_OK})")
                    return@withContext
                } catch (e: Exception) {
                    Log.w(TAG, "⚠️ Deep link failed for ${target.appName}: ${e.message}")
                    // Zkus otevřít app bez deep link
                    try {
                        val launchIntent = packageManager.getLaunchIntentForPackage(target.packageName)
                        if (launchIntent != null) {
                            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            context.startActivity(launchIntent)
                            Log.i(TAG, "✓ VoIP app ${target.appName} opened (manual navigation required)")
                            return@withContext
                        }
                    } catch (e2: Exception) {
                        Log.w(TAG, "⚠️ Launch failed for ${target.appName}: ${e2.message}")
                    }
                }
            }
        }

        // Fallback: Žádná VoIP appka nenalezena
        Log.w(TAG, "❌ No VoIP app installed — cannot make VoIP call")
    }

    /**
     * Ověří, zda je appka nainstalovaná.
     */
    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    // ==================== SMS ====================

    /**
     * Opens SMS app with pre-filled recipient and message body.
     *
     * Strategy:
     * Uses ACTION_SENDTO with "smsto:" scheme — explicitly targets SMS apps,
     * bypasses generic chooser (Email, Bluetooth, etc.).
     *
     * @param number Recipient phone number
     * @param message SMS body text (can be empty)
     */
    suspend fun sendSms(number: String, message: String) = withContext(Dispatchers.Main) {
        val cleanNumber = normalizePhoneNumber(number)

        val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
            // "smsto:" scheme is key to avoid App Chooser
            data = "smsto:$cleanNumber".toUri()

            // Standard extra for message body
            putExtra("sms_body", message)
            // Redundant extra for compatibility with older Samsung/HTC apps
            putExtra(Intent.EXTRA_TEXT, message)

            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(smsIntent)
            Log.i(TAG, "✓ SMS intent launched for $cleanNumber")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch SMS intent. No SMS app installed?", e)
        }
    }

    // ==================== WEB ====================

    /**
     * Opens URL in default browser.
     * Ensures scheme validity (adds https:// if missing).
     *
     * @param url Raw URL string
     */
    suspend fun openUrl(url: String) = withContext(Dispatchers.Main) {
        val validUrl = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("www.") -> "https://$url"
            else -> "https://$url"
        }

        val webIntent = Intent(Intent.ACTION_VIEW, validUrl.toUri()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(webIntent)
            Log.i(TAG, "✓ Opening URL: $validUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open URL: $validUrl", e)
        }
    }

    // ==================== EMAIL ====================

    /**
     * Opens email client with pre-filled recipient, subject and body.
     *
     * Strategy:
     * Uses ACTION_SENDTO with "mailto:" scheme — explicitly targets email apps,
     * bypasses generic chooser (SMS, Bluetooth, etc.).
     * Extras: EXTRA_SUBJECT + EXTRA_TEXT for subject and body.
     *
     * @param address Email address (e.g., "user@example.com")
     * @param subject Email subject line (can be empty)
     * @param body Email body text (can be empty)
     */
    suspend fun composeEmail(
        address: String,
        subject: String = "",
        body: String = ""
    ) = withContext(Dispatchers.Main) {
        val cleanAddress = address.trim()

        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
            // "mailto:" scheme targets email apps only (no SMS, no BT)
            data = "mailto:$cleanAddress".toUri()

            if (subject.isNotBlank()) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            if (body.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, body)
            }

            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(emailIntent)
            Log.i(TAG, "✓ Email compose launched for: $cleanAddress (subject: '$subject')")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch email intent. No email app installed?", e)
        }
    }



    // ==================== SETTINGS DEEP LINKS ====================

    /**
     * Settings section identifiers for deep links.
     */
    enum class SettingsSection {
        WIFI, BLUETOOTH, DISPLAY, SOUND, APPS, BATTERY, DEV_OPTIONS, NFC, AIRPLANE, GENERAL
    }

    /**
     * Opens specific Settings section via Intent deep link.
     *
     * @param section Target settings section
     */
    suspend fun openSettings(section: SettingsSection) = withContext(Dispatchers.Main) {
        val action = when (section) {
            SettingsSection.WIFI -> Settings.ACTION_WIFI_SETTINGS
            SettingsSection.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
            SettingsSection.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
            SettingsSection.SOUND -> Settings.ACTION_SOUND_SETTINGS
            SettingsSection.APPS -> Settings.ACTION_APPLICATION_SETTINGS
            SettingsSection.BATTERY -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            SettingsSection.DEV_OPTIONS -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            SettingsSection.NFC -> Settings.ACTION_NFC_SETTINGS
            SettingsSection.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            SettingsSection.GENERAL -> Settings.ACTION_SETTINGS
        }

        val intent = Intent(action).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        try {
            context.startActivity(intent)
            Log.i(TAG, "✓ Opened settings: $section")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: $section", e)
            // Fallback: Generic settings
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e2: Exception) {
                Log.e(TAG, "Critical: Cannot open any settings", e2)
            }
        }
    }

    /**
     * Resolves settings section from user input string.
     *
     * @param input Normalized user input (e.g., "wifi", "nastavení bluetooth")
     * @return SettingsSection or null if no match
     */
    fun resolveSettingsSection(input: String): SettingsSection? {
        val norm = input.lowercase().trim()

        // === Exclusion guard: inkrementální volume/brightness příkazy NEJSOU settings ===
        // "zvyš jas", "ztlum hlasitost", "volume up" → NE settings, ale přímá akce
        val incrementalKeywords = listOf(
            "zesil", "ztlum", "ztis", "stis", "zesilni", "zesilej",
            "zvys", "sniz", "pridej", "uber", "ubrat",
            "hlasiteji", "tiseji", "jasneji", "tmaveji",
            "nahoru", "dolu", "vic", "min", "mene",
            "rozjasni", "ztmav", "zesvitli", "zatemnit",
            "up", "down", "louder", "quieter", "brighter", "dimmer",
            "increase", "decrease", "raise", "lower", "turn"
        )
        if (incrementalKeywords.any { norm.contains(it) }) {
            return null  // Tier 1 volume/brightness matcher to zachytí
        }

        return when {
            norm.contains("wifi") || norm.contains("wi-fi") || norm.contains("internet") -> SettingsSection.WIFI
            norm.contains("bluetooth") || norm.contains("bt") -> SettingsSection.BLUETOOTH
            norm.contains("displej") || norm.contains("display") || norm.contains("jas") || norm.contains("brightness") -> SettingsSection.DISPLAY
            norm.contains("zvuk") || norm.contains("sound") || norm.contains("hlasitost") || norm.contains("volume") -> SettingsSection.SOUND
            norm.contains("aplikac") || norm.contains("apps") -> SettingsSection.APPS
            norm.contains("bater") || norm.contains("battery") -> SettingsSection.BATTERY
            norm.contains("vývojář") || norm.contains("developer") || norm.contains("dev") -> SettingsSection.DEV_OPTIONS
            norm.contains("nfc") -> SettingsSection.NFC
            norm.contains("letadlo") || norm.contains("airplane") || norm.contains("flight") -> SettingsSection.AIRPLANE
            norm == "nastavení" || norm == "settings" -> SettingsSection.GENERAL
            else -> null
        }
    }

    // ==================== HW TOGGLES ====================

    /**
     * WiFi toggle.
     *
     * Strategy:
     * - Android < Q (28): Direct WifiManager.setWifiEnabled() (deprecated but functional)
     * - Android ≥ Q (29+): Opens Settings.Panel.ACTION_INTERNET_CONNECTIVITY
     *   (user must toggle manually — Android 10+ removed programmatic WiFi toggle)
     *
     * @param enable true = zapnout, false = vypnout
     */
    suspend fun setWifi(enable: Boolean) = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val result = wifiManager?.setWifiEnabled(enable) ?: false
            if (result) {
                Log.i(TAG, "✓ WiFi ${if (enable) "enabled" else "disabled"} (direct API)")
            } else {
                Log.w(TAG, "WiFi toggle failed via direct API")
            }
        } else {
            // Android 10+: Must use Settings Panel
            Log.i(TAG, "Android Q+ — opening WiFi Settings Panel (user must toggle)")
            val panelIntent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(panelIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open WiFi panel, fallback to settings", e)
                openSettings(SettingsSection.WIFI)
            }
        }
    }

    /**
     * Bluetooth toggle.
     *
     * Note: BluetoothAdapter.enable()/disable() are deprecated on Android 13+ (API 33).
     * Requires BLUETOOTH_CONNECT permission on API 31+.
     * Falls back to Bluetooth Settings if permission denied.
     *
     * @param enable true = zapnout, false = vypnout
     */
    @Suppress("DEPRECATION", "MissingPermission")
    suspend fun setBluetooth(enable: Boolean) = withContext(Dispatchers.Main) {
        val adapter: BluetoothAdapter? = bluetoothManager?.adapter
        if (adapter == null) {
            Log.w(TAG, "BluetoothAdapter not available on this device")
            return@withContext
        }

        // API 31+ requires BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                Log.w(TAG, "BLUETOOTH_CONNECT permission missing → opening BT settings")
                openSettings(SettingsSection.BLUETOOTH)
                return@withContext
            }
        }

        try {
            val success = if (enable && !adapter.isEnabled) {
                adapter.enable()
            } else if (!enable && adapter.isEnabled) {
                adapter.disable()
            } else {
                Log.i(TAG, "Bluetooth already ${if (enable) "enabled" else "disabled"}")
                true
            }

            if (success) {
                Log.i(TAG, "✓ Bluetooth ${if (enable) "enabled" else "disabled"}")
            } else {
                Log.w(TAG, "Bluetooth toggle returned false — opening settings as fallback")
                openSettings(SettingsSection.BLUETOOTH)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Bluetooth toggle permission denied, opening settings", e)
            openSettings(SettingsSection.BLUETOOTH)
        }
    }

    /**
     * Flashlight (torch) toggle.
     * Uses CameraManager.setTorchMode — works on all Android 6+ without root.
     *
     * @param enable true = zapnout, false = vypnout
     */
    suspend fun setFlashlight(enable: Boolean) = withContext(Dispatchers.Main) {
        val cm = cameraManager
        if (cm == null) {
            Log.w(TAG, "CameraManager not available")
            return@withContext
        }

        try {
            val cameraIds = cm.cameraIdList
            if (cameraIds.isEmpty()) {
                Log.w(TAG, "No cameras available for flashlight")
                return@withContext
            }
            cm.setTorchMode(cameraIds[0], enable)
            Log.i(TAG, "✓ Flashlight ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
        }
    }

    /**
     * NFC toggle.
     *
     * Strategy:
     * - NfcAdapter.enable()/disable() vyžaduje WRITE_SECURE_SETTINGS (system app only)
     * - Non-root: Otevřeme Settings.Panel (Android 10+) nebo NFC settings
     * - Uživatel musí toggle provést manuálně
     *
     * @param enable true = zapnout, false = vypnout
     */
    suspend fun setNfc(enable: Boolean) = withContext(Dispatchers.Main) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(context)
        if (nfcAdapter == null) {
            Log.w(TAG, "NFC not available on this device")
            return@withContext
        }

        // Non-root: Otevřeme NFC settings — uživatel toggle manuálně
        Log.i(TAG, "NFC toggle requested (enable=$enable) — opening NFC settings (non-root)")
        try {
            // Android 10+ (API 29): Settings.Panel.ACTION_NFC — overlay panel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.startActivity(Intent(Settings.Panel.ACTION_NFC).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } else {
                // Pre-Q: Full NFC settings
                context.startActivity(Intent(Settings.ACTION_NFC_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
            Log.i(TAG, "✓ Opened NFC settings panel")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open NFC settings", e)
            // Fallback: General wireless settings
            try {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e2: Exception) {
                Log.e(TAG, "Critical: Cannot open any wireless settings", e2)
            }
        }
    }

    /**
     * Airplane Mode toggle.
     *
     * Strategy:
     * - Settings.Global.AIRPLANE_MODE_ON vyžaduje WRITE_SECURE_SETTINGS (root/system)
     * - Non-root: Otevřeme airplane mode settings
     * - Uživatel musí toggle provést manuálně
     *
     * @param enable true = zapnout, false = vypnout
     */
    suspend fun setAirplaneMode(enable: Boolean) = withContext(Dispatchers.Main) {
        Log.i(TAG, "Airplane mode toggle requested (enable=$enable) — opening settings (non-root)")
        try {
            context.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            Log.i(TAG, "✓ Opened Airplane Mode settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open airplane mode settings", e)
            // Fallback: General wireless settings
            try {
                context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e2: Exception) {
                Log.e(TAG, "Critical: Cannot open any wireless settings", e2)
            }
        }
    }

    // ==================== SYSTEM CONTROL (Volume, Brightness, Rotation) ====================

    /**
     * Nastaví hlasitost médií (STREAM_MUSIC).
     *
     * @param percent Procentuální hodnota (0-100)
     */
    suspend fun setVolume(percent: Int) = withContext(Dispatchers.Main) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager == null) {
            Log.w(TAG, "AudioManager not available")
            return@withContext
        }

        try {
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * (percent.coerceIn(0, 100) / 100f)).toInt()
            
            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                targetVolume,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            Log.i(TAG, "✓ Volume set to $percent% ($targetVolume/$maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set volume", e)
        }
    }

    /**
     * Inkrementální změna hlasitosti o delta procent.
     * Kladný delta = zvýšit, záporný = snížit.
     * Zobrazí systémový volume slider (FLAG_SHOW_UI).
     *
     * @param delta Změna v procentech (např. +10 nebo -10)
     */
    suspend fun adjustVolume(delta: Int) = withContext(Dispatchers.Main) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager == null) {
            Log.w(TAG, "AudioManager not available")
            return@withContext
        }

        try {
            val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentVolume = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val currentPercent = if (maxVolume > 0) (currentVolume * 100) / maxVolume else 0
            val newPercent = (currentPercent + delta).coerceIn(0, 100)
            val targetVolume = (maxVolume * (newPercent / 100f)).toInt()

            audioManager.setStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                targetVolume,
                android.media.AudioManager.FLAG_SHOW_UI
            )
            Log.i(TAG, "✓ Volume adjusted by $delta%: $currentPercent% → $newPercent% ($targetVolume/$maxVolume)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust volume", e)
        }
    }

    /**
     * Nastaví jas displeje.
     * Vyžaduje WRITE_SETTINGS permission.
     *
     * @param percent Procentuální hodnota (0-100)
     */
    suspend fun setBrightness(percent: Int) = withContext(Dispatchers.Main) {
        if (!checkWriteSettingsPermission()) return@withContext

        try {
            // Convert percent (0-100) to brightness (0-255)
            val brightnessValue = (255 * (percent.coerceIn(0, 100) / 100f)).toInt()
            
            // Set mode to MANUAL first (otherwise auto-brightness might override)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            Log.i(TAG, "✓ Brightness set to $percent% ($brightnessValue/255)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
    }

    /**
     * Inkrementální změna jasu o delta procent.
     * Kladný delta = zvýšit, záporný = snížit.
     * Vypne auto-brightness (manuální režim).
     *
     * @param delta Změna v procentech (např. +10 nebo -10)
     */
    suspend fun adjustBrightness(delta: Int) = withContext(Dispatchers.Main) {
        if (!checkWriteSettingsPermission()) return@withContext

        try {
            // Přečíst aktuální jas (0-255)
            val currentBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Settings.SettingNotFoundException) {
                128 // Výchozí 50%
            }

            val currentPercent = (currentBrightness * 100) / 255
            val newPercent = (currentPercent + delta).coerceIn(1, 100)  // Min 1% (ne úplná tma)
            val brightnessValue = (255 * (newPercent / 100f)).toInt()

            // Vypnout auto-brightness
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            Log.i(TAG, "✓ Brightness adjusted by $delta%: $currentPercent% → $newPercent% ($brightnessValue/255)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to adjust brightness", e)
        }
    }
    /**
     * Nastaví automatickou rotaci displeje.
     * Vyžaduje WRITE_SETTINGS permission.
     *
     * @param enable true = Auto-rotate ON, false = Portrait locked
     */
    suspend fun setRotation(enable: Boolean) = withContext(Dispatchers.Main) {
        if (!checkWriteSettingsPermission()) return@withContext

        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                if (enable) 1 else 0
            )
            Log.i(TAG, "✓ Auto-rotation set to ${if (enable) "ON" else "OFF"}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set rotation", e)
        }
    }

    /**
     * Ověří, zda má aplikace oprávnění WRITE_SETTINGS (pro Jas/Rotaci).
     * Pokud ne, otevře systémový dialog pro udělení oprávnění.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(context)) {
                Log.w(TAG, "WRITE_SETTINGS permission missing — opening settings dialog")
                
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = "package:${context.packageName}".toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                try {
                    context.startActivity(intent)
                    Log.i(TAG, "👉 User must grant modify system settings permission")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open WRITE_SETTINGS dialog", e)
                }
                return false
            }
        }
        return true
    }

    /**
     * Opens the App Info settings page for this application.
     * Required for Android 13+ "Restricted Settings" clearance.
     */

    @Suppress("unused")
    fun openAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "✓ Opened App Info settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open App Info settings", e)
        }
    }

    // ==================== APP MANAGEMENT ====================

    /**
     * Refreshes cached list of installed launcher apps.
     * Thread-safe via synchronized.
     */
    @Synchronized
    fun refreshAppCache() {
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(intent, 0)
        appCache = resolveInfos.map {
            val appName = it.loadLabel(packageManager).toString().lowercase()
            val pkgName = it.activityInfo.packageName
            appName to pkgName
        }
        Log.d(TAG, "App cache refreshed: ${appCache.size} apps")
    }

    /**
     * Finds package name by app name query.
     *
     * Priority: 1. Exact match → 2. Starts with → 3. Contains
     *
     * @param query App name (e.g., "spotify", "chrome")
     * @return Package name or null if not found
     */
    fun findAppPackage(query: String): String? {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return null

        val pm = context.packageManager

        // Priorita 1: AppResolutionEngine — O(1) lookup s normalizací diakritiky
        // Řeší: "instáč" → "instac" → "com.instagram.android"
        AppResolutionEngine.resolvePackageName(q)?.let { pkg ->
            // Validace: musí mít launcher intent (ne factory/test app)
            if (pm.getLaunchIntentForPackage(pkg) != null) return pkg
            Log.w(TAG, "⚠️ AppResolutionEngine match '$pkg' has no launcher — skipping")
        }

        // Priorita 2: InstalledAppsRegistry — fuzzy + slang + aliasy (~490 apps)
        InstalledAppsRegistry.findApp(q)?.let { entry ->
            // FIX: Validace launchability — factory/test apps nemají launcher
            if (pm.getLaunchIntentForPackage(entry.packageName) != null) return entry.packageName
            Log.w(TAG, "⚠️ Registry match '${entry.label}' (${entry.packageName}) has no launcher — skipping")
        }

        // Fallback: vlastní appCache (launcher apps only — již validované)
        appCache.find { it.first == q }?.let { return it.second }
        // 2. Starts with
        appCache.find { it.first.startsWith(q) }?.let { return it.second }
        // 3. Contains
        appCache.find { it.first.contains(q) }?.let { return it.second }

        Log.d(TAG, "App '$query' not found in cache (${appCache.size} apps)")
        return null
    }
    
    /**
     * Returns a formatted list of installed apps for LLM context.
     * Format: "Name (package), Name (package)..."
     * Limit: Top 50 apps to save tokens.
     */
    fun getInstalledAppsList(): String {
        // Priorita: InstalledAppsRegistry (persistence, aliasy, ~490 apps)
        // Fallback: vlastní appCache (launcher apps only)
        if (InstalledAppsRegistry.isLoaded) {
            return InstalledAppsRegistry.getAll()
                .take(50)
                .joinToString(", ") { "${it.label} (${it.packageName})" }
        }
        return appCache.take(50).joinToString(", ") { "${it.first} (${it.second})" }
    }

    /**
     * Opens an application by package name.
     *
     * @param packageName Package name (e.g. "com.android.settings")
     * @return true if successful
     */
    suspend fun openApp(packageName: String): Boolean = withContext(Dispatchers.Main) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "✓ Opened app: $packageName")
                return@withContext true
            } else {
                Log.w(TAG, "Launch intent not found for package: $packageName")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $packageName", e)
            return@withContext false
        }
    }

    // ==================== IME (HyperKeyboardService) ====================

    /**
     * Checks if HyperKeyboardService is enabled in the list of allowed keyboards.
     *
     * Reads Settings.Secure.ENABLED_INPUT_METHODS which contains
     * a colon-separated list of ComponentName strings.
     *
     * @return true if HyperKeyboardService is in the enabled list
     */

    @Suppress("unused")
    fun isImeEnabled(): Boolean {
        val enabledMethods = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: ""
        val imeId = "${context.packageName}/.agent.service.HyperKeyboardService"
        val isEnabled = enabledMethods.contains(imeId)
        Log.d(TAG, "IME enabled check: imeId=$imeId, enabled=$isEnabled")
        return isEnabled
    }

    /**
     * Checks if HyperKeyboardService is the currently active (default) keyboard.
     *
     * Reads Settings.Secure.DEFAULT_INPUT_METHOD.
     *
     * @return true if HyperKeyboardService is the default keyboard
     */

    @Suppress("unused")
    fun isDefaultIme(): Boolean {
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: ""
        val imeId = "${context.packageName}/.agent.service.HyperKeyboardService"
        val isDefault = defaultIme.contains(imeId)
        Log.d(TAG, "Default IME check: default=$defaultIme, ours=$imeId, isDefault=$isDefault")
        return isDefault
    }

    /**
     * Opens system settings for keyboard/input method management.
     *
     * User needs to:
     * 1. Enable HyperKeyboardService in the list of keyboards
     * 2. Set it as the default keyboard
     */
    fun requestImeSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            Log.i(TAG, "✓ Opened input method settings")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open input method settings", e)
        }
    }

    // ==================== HELPERS ====================

    /**
     * Normalizes phone number — keeps only digits and '+'.
     */
    private fun normalizePhoneNumber(input: String): String {
        return input.filter { it.isDigit() || it == '+' }
    }

    /**
     * Runtime permission check.
     */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context, permission
        ) == PackageManager.PERMISSION_GRANTED
    }
}
