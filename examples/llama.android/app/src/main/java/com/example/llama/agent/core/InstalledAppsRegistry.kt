package com.example.llama.agent.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * InstalledAppsRegistry — Pamětový / Learning Modul pro nainstalované aplikace.
 *
 * Zodpovědnost:
 * 1. Jednorázový scan nainstalovaných app → in-memory snapshot
 * 2. Fuzzy vyhledávání přes label, packageName i slang aliasy
 * 3. Learning: agent se naučí nový alias, když user zavolá app jinak
 * 4. Persistence do JSON souboru (app_packages.json) v interním storage
 * 5. Backup / Restore / Delete
 *
 * Google Play Compliance:
 * - Používá QUERY_ALL_PACKAGES (schváleno pro TTS/STT/3rd party accessibility)
 * - Fallback: queryIntentActivities(ACTION_MAIN, CATEGORY_LAUNCHER)
 *
 * Importy funkčně využité:
 * - PackageManager: getInstalledApplications(), queryIntentActivities()
 * - Build: API level branching (API 33+ ApplicationInfoFlags)
 * - TelecomManager: detekce VoIP capable apps
 * - ContextCompat: runtime permission check
 */
object InstalledAppsRegistry {

    private const val TAG = "InstalledAppsRegistry"
    private const val PACKAGES_FILE = "app_packages.json"
    private const val BACKUP_FILE = "app_packages_backup.json"

    // Známé VoIP balíčky pro označení isVoipCapable
    private val KNOWN_VOIP_PACKAGES =
            setOf(
                    "com.whatsapp",
                    "com.viber.voip",
                    "org.telegram.messenger",
                    "com.discord",
                    "org.thoughtcrime.securesms",
                    "com.facebook.orca",
                    "com.skype.raider",
                    "us.zoom.videomeetings",
                    "com.google.android.apps.meetings"
            )

    /**
     * SLANG_MAP — Statický slovník slang/deklinace aliasů pro běžné aplikace.
     *
     * Klíč: regex pattern (matchováno proti packageName + label, case-insensitive) Hodnota: CZ + EN
     * slang aliasy včetně českých deklinací
     *
     * Příklad: uživatel řekne "otevři foťák" → findApp("foťák") → Camera app
     */
    private val SLANG_MAP: Map<Regex, Set<String>> =
            mapOf(
                    // === Fotoaparát / Kamera ===
                    Regex("camera|fotoaparát", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "kamera",
                                    "kameru",
                                    "kamerou",
                                    "foťák",
                                    "fotak",
                                    "foťáku",
                                    "fotoaparát",
                                    "fotoaparat",
                                    "fotka",
                                    "foto",
                                    "fotky",
                                    "vyfotit",
                                    "vyfot",
                                    "foceni",
                                    "focaní",
                                    "cam"
                            ),
                    // === Galerie / Fotky ===
                    Regex("gallery|photos|galerie", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "galerie",
                                    "galerii",
                                    "fotky",
                                    "fotogalerie",
                                    "album",
                                    "snímky",
                                    "obrázky",
                                    "gallery",
                                    "photos",
                                    "fotografie",
                                    "pics",
                                    "pictures"
                            ),
                    // === Email / Pošta ===
                    Regex("gmail|email|outlook|mail", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "email",
                                    "mail",
                                    "mejl",
                                    "pošta",
                                    "poštu",
                                    "gmail",
                                    "maily",
                                    "emaily",
                                    "outlook",
                                    "inbox",
                                    "doručená",
                                    "schránka"
                            ),
                    // === Zprávy / SMS ===
                    Regex("messaging|messages|sms|mms", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "zprávy",
                                    "zprávu",
                                    "sms",
                                    "smska",
                                    "smsku",
                                    "textovky",
                                    "zpráva",
                                    "esemeska",
                                    "messages",
                                    "texty",
                                    "mms",
                                    "zpravicky"
                            ),
                    // === Hodiny / Budík ===
                    Regex("clock|alarm|timer|hodiny", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "hodiny",
                                    "čas",
                                    "budík",
                                    "budíku",
                                    "stopky",
                                    "časovač",
                                    "alarm",
                                    "timer",
                                    "budíky",
                                    "odpočítávání",
                                    "odpocitavani",
                                    "clock",
                                    "alarmek"
                            ),
                    // === Telefon / Dialer ===
                    Regex("phone|dialer|telecom|incallui", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "telefon",
                                    "volání",
                                    "dialer",
                                    "hovor",
                                    "číselník",
                                    "kontakt",
                                    "zavolat",
                                    "call",
                                    "phone",
                                    "telefónek",
                                    "vytočit"
                            ),
                    // === Prohlížeč ===
                    Regex(
                            "browser|chrome|firefox|opera|edge|brave|samsung.internet",
                            RegexOption.IGNORE_CASE
                    ) to
                            setOf(
                                    "prohlížeč",
                                    "prohlizec",
                                    "internet",
                                    "web",
                                    "browser",
                                    "stránky",
                                    "chrome",
                                    "firefox",
                                    "edge",
                                    "opera",
                                    "brave",
                                    "surfovat",
                                    "googlit"
                            ),
                    // === Kalendář ===
                    Regex("calendar|kalendář", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "kalendář",
                                    "kalendar",
                                    "diář",
                                    "diar",
                                    "události",
                                    "schůzky",
                                    "calendar",
                                    "termíny",
                                    "plán",
                                    "plánování"
                            ),
                    // === Kalkulačka ===
                    Regex("calculator|calc", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "kalkulačka",
                                    "kalkulátor",
                                    "počítadlo",
                                    "kalkulacka",
                                    "počítat",
                                    "calculator",
                                    "calc",
                                    "kalkulka"
                            ),
                    // === Kontakty ===
                    Regex("contacts|kontakty", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "kontakty",
                                    "adresář",
                                    "adresar",
                                    "lidi",
                                    "lidé",
                                    "contacts",
                                    "telefonní seznam",
                                    "seznam kontaktů"
                            ),
                    // === Mapy / Navigace ===
                    Regex("maps|navigation|waze|mapy[.]cz", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "mapy",
                                    "mapa",
                                    "mapu",
                                    "navigace",
                                    "navigaci",
                                    "navi",
                                    "maps",
                                    "google mapy",
                                    "waze",
                                    "trasa",
                                    "navigovat",
                                    "kde jsem",
                                    "gps"
                            ),
                    // === Hudba / Přehrávač ===
                    Regex(
                            "music|spotify|player|ytmusic|soundcloud|deezer|tidal",
                            RegexOption.IGNORE_CASE
                    ) to
                            setOf(
                                    "hudba",
                                    "hudbu",
                                    "muzika",
                                    "přehrávač",
                                    "player",
                                    "skladby",
                                    "písničky",
                                    "spotify",
                                    "music",
                                    "muziku",
                                    "ytmusic",
                                    "soundcloud",
                                    "deezer",
                                    "tidal"
                            ),
                    // === Soubory ===
                    Regex("files|filemanager|file.?manager|myfiles", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "soubory",
                                    "správce",
                                    "files",
                                    "manažer",
                                    "správce souborů",
                                    "file manager",
                                    "průzkumník",
                                    "složky",
                                    "dokumenty"
                            ),
                    // === Poznámky ===
                    Regex("notes|memo|keep|notepad|samsung.notes", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "poznámky",
                                    "poznámku",
                                    "notes",
                                    "memo",
                                    "zápisky",
                                    "notáček",
                                    "keep",
                                    "notepad",
                                    "poznámkový blok",
                                    "zapsat",
                                    "zapiš"
                            ),
                    // === YouTube ===
                    Regex("youtube", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "youtube",
                                    "yt",
                                    "yůtůb",
                                    "jutub",
                                    "ytb",
                                    "tubko",
                                    "youtubeska",
                                    "yutub"
                            ),
                    // === WhatsApp ===
                    Regex("whatsapp", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "whatsapp",
                                    "wapp",
                                    "vocap",
                                    "whatsap",
                                    "wotsap",
                                    "wotsep",
                                    "vatsap",
                                    "watsap",
                                    "zelená appka",
                                    "wa"
                            ),
                    // === Telegram ===
                    Regex("telegram", RegexOption.IGNORE_CASE) to
                            setOf("telegram", "telka", "tg", "telgra", "telegra", "telegramek"),
                    // === Viber ===
                    Regex("viber", RegexOption.IGNORE_CASE) to
                            setOf("viber", "vajber", "fialová appka"),
                    // === Signal ===
                    Regex("signal", RegexOption.IGNORE_CASE) to
                            setOf("signal", "signál", "bezpečná appka", "šifrovaná zpráva"),
                    // === Messenger (Facebook) ===
                    Regex("messenger|orca", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "messenger",
                                    "mesenger",
                                    "mesendžer",
                                    "fb chat",
                                    "facebook chat",
                                    "modrá appka",
                                    "msngr",
                                    "zprávy facebook"
                            ),
                    // === Snapchat ===
                    Regex("snapchat", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "snapchat",
                                    "snap",
                                    "snapek",
                                    "snapčat",
                                    "snapy",
                                    "snappy",
                                    "žlutá appka",
                                    "snapovat"
                            ),
                    // === Twitter / X ===
                    Regex("twitter|x[.]com|com[.]twitter", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "twitter",
                                    "twiter",
                                    "x",
                                    "tweety",
                                    "twitterek",
                                    "cvrlikání",
                                    "tweet",
                                    "tweetnout",
                                    "twitovat"
                            ),
                    // === Instagram ===
                    Regex("instagram", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "instagram",
                                    "insta",
                                    "ig",
                                    "instac",
                                    "instáč",
                                    "instagramek",
                                    "instagramovat",
                                    "fotky na instu",
                                    "instagrm"
                            ),
                    // === Facebook ===
                    Regex("facebook|katana", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "facebook",
                                    "fb",
                                    "fejsbuk",
                                    "fejsbůk",
                                    "fejsák",
                                    "fejsáček",
                                    "fejs",
                                    "modrá sociální síť"
                            ),
                    // === TikTok ===
                    Regex("tiktok|musical[.]ly", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "tiktok",
                                    "tik tok",
                                    "tiktak",
                                    "tik-tok",
                                    "tiktokovat",
                                    "tiktoček"
                            ),
                    // === LinkedIn ===
                    Regex("linkedin", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "linkedin",
                                    "linkedn",
                                    "pracovní síť",
                                    "profesní síť",
                                    "linked in"
                            ),
                    // === Pinterest ===
                    Regex("pinterest", RegexOption.IGNORE_CASE) to
                            setOf("pinterest", "pinterst", "piny", "pinovat", "inspirace"),
                    // === Reddit ===
                    Regex("reddit", RegexOption.IGNORE_CASE) to
                            setOf("reddit", "redit", "reddít", "subreddit", "redditovat"),
                    // === Discord ===
                    Regex("discord", RegexOption.IGNORE_CASE) to
                            setOf("discord", "disko", "dc", "discordo", "discordek"),
                    // === Spotify ===
                    Regex("spotify", RegexOption.IGNORE_CASE) to
                            setOf("spotify", "spotifaj", "spotifej", "spoti"),
                    // === Netflix ===
                    Regex("netflix", RegexOption.IGNORE_CASE) to
                            setOf("netflix", "netflik", "netfliks", "netflixovat"),
                    // === Amazon Prime Video ===
                    Regex("amazon|primevideo|prime.video", RegexOption.IGNORE_CASE) to
                            setOf("amazon", "prime video", "prime", "amazon video", "amazón"),
                    // === HBO Max / Max ===
                    Regex("hbo|max[.]hbo|hbomax", RegexOption.IGNORE_CASE) to
                            setOf("hbo", "hbo max", "max", "hbíčko"),
                    // === Disney+ ===
                    Regex("disney", RegexOption.IGNORE_CASE) to
                            setOf("disney", "disney plus", "disneyplus", "disney+"),
                    // === Zoom ===
                    Regex("zoom", RegexOption.IGNORE_CASE) to
                            setOf("zoom", "zům", "zoom hovor", "zoom meeting", "videohovor zoom"),
                    // === Microsoft Teams ===
                    Regex("teams|microsoftteams", RegexOption.IGNORE_CASE) to
                            setOf("teams", "týms", "microsoft teams", "ms teams", "teamsy"),
                    // === Skype ===
                    Regex("skype", RegexOption.IGNORE_CASE) to
                            setOf("skype", "skajp", "skajpovat", "skypovat"),
                    // === Google Meet ===
                    Regex("meet|google.meet", RegexOption.IGNORE_CASE) to
                            setOf("meet", "google meet", "meetovat", "videokonference"),
                    // === Google Drive ===
                    Regex("drive|googledrive", RegexOption.IGNORE_CASE) to
                            setOf("drive", "google drive", "disk", "google disk"),
                    // === Google Photos ===
                    Regex("google.photos|gphotos", RegexOption.IGNORE_CASE) to
                            setOf("google fotky", "google photos", "gphotos", "záloha fotek"),
                    // === Google Translate ===
                    Regex("google.translate|translate", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "překladač",
                                    "prekladac",
                                    "translator",
                                    "překládat",
                                    "google translate",
                                    "přeložit",
                                    "translate",
                                    "přeložení"
                            ),
                    // === Google Assistant ===
                    Regex("googlequicksearchbox|velvet|assistant", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "asistent",
                                    "google asistent",
                                    "assistant",
                                    "ok google",
                                    "hey google"
                            ),
                    // === Nastavení ===
                    Regex("settings|nastavení", RegexOption.IGNORE_CASE) to
                            setOf("nastavení", "nastaveni", "nastav", "settings"),
                    // === Obchod / Store ===
                    Regex("play.store|vending|market", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "obchod",
                                    "store",
                                    "krám",
                                    "obchůdek",
                                    "play store",
                                    "google play"
                            ),
                    // === Počasí ===
                    Regex("weather|počasí|accuweather|yr[.]no", RegexOption.IGNORE_CASE) to
                            setOf("počasí", "pocasi", "předpověď", "predpoved", "meteo", "weather"),
                    // === Nahrávání / Rekordér ===
                    Regex("recorder|recording|diktafon|voicerecorder", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "nahrávání",
                                    "diktafon",
                                    "rekordér",
                                    "záznam",
                                    "nahrát hlas",
                                    "voice recorder",
                                    "nahraj"
                            ),
                    // === Svítilna ===
                    Regex("flashlight|torch", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "svítilna",
                                    "svítilnu",
                                    "baterka",
                                    "baterku",
                                    "flashlight",
                                    "torch",
                                    "rozsvítit",
                                    "světlo"
                            ),
                    // === Uber / Bolt / Taxi ===
                    Regex("uber|bolt[.]eu|taxify", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "uber",
                                    "bolt",
                                    "taxi",
                                    "taxík",
                                    "objednat taxi",
                                    "odvézt",
                                    "ride",
                                    "svézt",
                                    "taxikář"
                            ),
                    // === Booking / Airbnb / Ubytování ===
                    Regex("booking|airbnb|hotels[.]com", RegexOption.IGNORE_CASE) to
                            setOf(
                                    "booking",
                                    "airbnb",
                                    "hotel",
                                    "ubytování",
                                    "rezervace hotelu",
                                    "přenocovat",
                                    "pokoj"
                            ),
                    // === Shazam ===
                    Regex("shazam", RegexOption.IGNORE_CASE) to setOf("shazam", "šazam"),
                    // === Duolingo ===
                    Regex("duolingo", RegexOption.IGNORE_CASE) to setOf("duolingo", "duo"),
                    // === Mapy.cz ===
                    Regex("mapy[.]cz|seznam.mapy", RegexOption.IGNORE_CASE) to
                            setOf("mapy cz", "seznam mapy", "mapy seznam")
            )

    /**
     * AppEntry — záznam jedné nainstalované aplikace.
     *
     * @param packageName Systémový identifikátor (com.whatsapp)
     * @param label User-visible název (WhatsApp Messenger)
     * @param aliases Slang aliasy — learning modul (wapp, vocap, whatsap)
     * @param isVoipCapable Má VoIP schopnosti (pro makeVoipCall routing)
     * @param isSystemApp Systémová app (pre-installed)
     * @param installedAt Timestamp prvního scanu
     */
    data class AppEntry(
            val packageName: String,
            val label: String,
            val aliases: MutableSet<String>,
            val isVoipCapable: Boolean,
            val isSystemApp: Boolean,
            val hasLauncher: Boolean = true, // default compatibility
            val installedAt: Long = System.currentTimeMillis()
    )

    // === IN-MEMORY SNAPSHOT ===
    @Volatile private var snapshot: MutableList<AppEntry> = mutableListOf()

    val isLoaded: Boolean
        get() = snapshot.isNotEmpty()
    val size: Int
        get() = snapshot.size

    // ==================== PUBLIC API ====================

    /**
     * Načte snapshot nainstalovaných app z PackageManager.
     *
     * Používá:
     * - PackageManager.getInstalledApplications() (QUERY_ALL_PACKAGES)
     * - Build.VERSION.SDK_INT pro API level branching
     * - ContextCompat pro permission check
     * - TelecomManager pro VoIP detekci
     *
     * Merge logika: Existující aliasy se zachovají (learning data).
     */
    @Synchronized
    fun loadSnapshot(context: Context) {
        val pm = context.packageManager
        val startTime = System.currentTimeMillis()

        // Permission check — ContextCompat funkční využití
        val hasQueryPermission =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // API 30+: QUERY_ALL_PACKAGES or manifest <queries>
                    ContextCompat.checkSelfPermission(
                            context,
                            "android.permission.QUERY_ALL_PACKAGES"
                    ) == PackageManager.PERMISSION_GRANTED
                } else {
                    true // Pre-API 30: plná viditelnost bez oprávnění
                }

        // VoIP readiness check — TelecomManager funkční využití
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        val hasVoipSupport = telecomManager != null
        Log.d(
                TAG,
                "📱 VoIP support available: $hasVoipSupport, queryPermission: $hasQueryPermission"
        )

        // Scan nainstalovaných app — Build funkční využití
        val installedApps: List<ApplicationInfo> =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: Nové ApplicationInfoFlags API
                    pm.getInstalledApplications(
                            PackageManager.ApplicationInfoFlags.of(
                                    PackageManager.GET_META_DATA.toLong()
                            )
                    )
                } else {
                    // Pre-API 33: Legacy API
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }

        // Zachovej existující aliasy (learning data)
        val existingAliases = snapshot.associate { it.packageName to it.aliases.toSet() }

        // Launcher apps pro info flag (apps s ikonou v launcheru)
        val launcherIntent =
                Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val launcherPackages =
                pm.queryIntentActivities(launcherIntent, 0)
                        .map { it.activityInfo.packageName }
                        .toSet()

        val newSnapshot = mutableListOf<AppEntry>()

        for (appInfo in installedApps) {
            val label = pm.getApplicationLabel(appInfo).toString()

            // Přeskoč app kde label == packageName (žádný user-visible název)
            if (label == appInfo.packageName) continue
            // Přeskoč prázdné labely
            if (label.isBlank()) continue

            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isVoip = appInfo.packageName in KNOWN_VOIP_PACKAGES
            val hasLauncher = appInfo.packageName in launcherPackages

            // Merge aliasy — zachovej naučená data
            val aliases = existingAliases[appInfo.packageName]?.toMutableSet() ?: mutableSetOf()
            // Auto-alias: jen celý lowercase label (NE word-split — "google" by matchl všechny
            // Google apps)
            aliases.add(label.lowercase(Locale.ROOT))
            // Auto-generátor slangu: SLANG_MAP + české stem varianty
            aliases.addAll(generateAutoAliases(appInfo.packageName, label))

            newSnapshot.add(
                    AppEntry(
                            packageName = appInfo.packageName,
                            label = label,
                            aliases = aliases,
                            isVoipCapable = isVoip,
                            isSystemApp = isSystem,
                            hasLauncher = hasLauncher
                    )
            )
        }

        snapshot = newSnapshot
        val launcherCount = newSnapshot.count { it.packageName in launcherPackages }
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(
                TAG,
                "✓ Snapshot loaded: ${snapshot.size} apps ($launcherCount with launcher) in ${elapsed}ms"
        )
    }

    /**
     * Vyhledá app podle query — fuzzy + slang match.
     *
     * Priorita:
     * 1. Exact packageName match
     * 2. Exact label match (case-insensitive)
     * 3. Exact alias match (slang)
     * 4. Label startsWith
     * 5. Label/alias contains
     *
     * @param query Uživatelský dotaz (název app, slang, nebo packageName)
     * @return Nejlepší shoda nebo null
     */
    fun findApp(query: String): AppEntry? {
        if (snapshot.isEmpty()) return null

        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return null

        // 1. Exact packageName
        snapshot.find { it.packageName.equals(q, ignoreCase = true) }?.let {
            return it
        }

        // 2. Exact label
        snapshot.find { it.label.equals(q, ignoreCase = true) }?.let {
            return it
        }

        // 3. Exact alias (slang)
        snapshot.find { entry -> entry.aliases.any { it.equals(q, ignoreCase = true) } }?.let {
            return it
        }

        // 3.5 Word match — hledá přesné slovo v label ("chrome" → "Google Chrome")
        snapshot
                .find { entry ->
                    entry.label.lowercase(Locale.ROOT).split(" ", "-", "_").any {
                        it.equals(q, ignoreCase = true)
                    }
                }
                ?.let {
                    return it
                }

        // 4. Label startsWith
        snapshot.find { it.label.startsWith(q, ignoreCase = true) }?.let {
            return it
        }

        // 5. Label/alias contains (sorted by shortest — nejbližší shoda)
        return snapshot
                .filter { entry ->
                    entry.label.contains(q, ignoreCase = true) ||
                            entry.aliases.any { it.contains(q, ignoreCase = true) }
                }
                .minByOrNull { it.label.length }
    }

    /** Vrátí VoIP capable apps (pro makeVoipCall routing). */
    fun getVoipApps(): List<AppEntry> = snapshot.filter { it.isVoipCapable }

    /** Vrátí všechny app entries. */
    fun getAll(): List<AppEntry> = snapshot.toList()

    /**
     * Learning — přidej nový slang alias pro app. Volá se z ActionExecutor, když user zavolá app
     * jiným jménem.
     *
     * @param packageName Cílový balíček
     * @param alias Nový slang alias
     */
    @Synchronized
    fun addAlias(packageName: String, alias: String) {
        val entry = snapshot.find { it.packageName == packageName } ?: return
        val normalizedAlias = alias.trim().lowercase(Locale.ROOT)
        if (normalizedAlias.isNotEmpty() && entry.aliases.add(normalizedAlias)) {
            Log.i(TAG, "🧠 Learning: '$normalizedAlias' → ${entry.label} (${entry.packageName})")
        }
    }

    /** Vymaže in-memory snapshot. */
    @Synchronized
    fun clear() {
        snapshot.clear()
        Log.i(TAG, "🗑️ Snapshot cleared")
    }

    // ==================== PERSISTENCE ====================

    /** Uloží snapshot do JSON souboru v interním storage. Soubor: app_packages.json */
    fun saveToFile(context: Context) {
        try {
            val jsonArray = JSONArray()
            for (entry in snapshot) {
                val obj =
                        JSONObject().apply {
                            put("packageName", entry.packageName)
                            put("label", entry.label)
                            put("aliases", JSONArray(entry.aliases.toList()))
                            put("isVoipCapable", entry.isVoipCapable)
                            put("isSystemApp", entry.isSystemApp)
                            put("hasLauncher", entry.hasLauncher)
                            put("installedAt", entry.installedAt)
                        }
                jsonArray.put(obj)
            }

            val file = File(context.filesDir, PACKAGES_FILE)
            file.writeText(jsonArray.toString(2))
            Log.i(TAG, "💾 Saved ${snapshot.size} apps to $PACKAGES_FILE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot", e)
        }
    }

    /**
     * Načte snapshot ze souboru (rychlé, bez PackageManager scan).
     *
     * @return true pokud soubor existoval a byl úspěšně načten
     */
    @Synchronized
    fun loadFromFile(context: Context): Boolean {
        val file = File(context.filesDir, PACKAGES_FILE)
        if (!file.exists()) {
            Log.d(TAG, "📂 No saved snapshot found")
            return false
        }

        try {
            val jsonArray = JSONArray(file.readText())
            val loaded = mutableListOf<AppEntry>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val aliasesArray = obj.getJSONArray("aliases")
                val aliases = mutableSetOf<String>()
                for (j in 0 until aliasesArray.length()) {
                    aliases.add(aliasesArray.getString(j))
                }

                loaded.add(
                        AppEntry(
                                packageName = obj.getString("packageName"),
                                label = obj.getString("label"),
                                aliases = aliases,
                                isVoipCapable = obj.optBoolean("isVoipCapable", false),
                                isSystemApp = obj.optBoolean("isSystemApp", false),
                                hasLauncher = obj.optBoolean("hasLauncher", true),
                                installedAt = obj.optLong("installedAt", System.currentTimeMillis())
                        )
                )
            }

            snapshot = loaded
            Log.i(TAG, "✓ Loaded ${snapshot.size} apps from file")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot from file", e)
            return false
        }
    }

    /**
     * Záloha do přístupného úložiště (getExternalFilesDir). Cesta:
     * Android/data/com.example.llama/files/app_packages_backup.json Uživatel může soubor vidět,
     * kopírovat, editovat.
     *
     * @return Absolutní cesta k záloze nebo null při chybě
     */
    fun backupToFile(context: Context): String? {
        try {
            // Primární zdroj: internal filesDir
            val source = File(context.filesDir, PACKAGES_FILE)
            if (!source.exists()) {
                // Nejdřív uloží aktuální snapshot do internal storage
                saveToFile(context)
            }

            val sourceFile = File(context.filesDir, PACKAGES_FILE)
            if (!sourceFile.exists()) {
                Log.w(TAG, "⚠️ No data to backup — snapshot is empty")
                return null
            }

            // Záloha do externího přístupného úložiště
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val target = File(externalDir, BACKUP_FILE)
                sourceFile.copyTo(target, overwrite = true)
                Log.i(TAG, "💾 Backup saved to: ${target.absolutePath} (${target.length()} bytes)")
                return target.absolutePath
            } else {
                // Fallback: internal storage
                val target = File(context.filesDir, BACKUP_FILE)
                sourceFile.copyTo(target, overwrite = true)
                Log.w(
                        TAG,
                        "💾 Backup saved to internal storage (external not available): ${target.absolutePath}"
                )
                return target.absolutePath
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            return null
        }
    }

    /**
     * Import vlastního seznamu z externího souboru. Čte app_packages_backup.json z
     * getExternalFilesDir. Uživatel může soubor editovat a pak naimportovat.
     *
     * @return Počet importovaných app nebo -1 při chybě
     */
    @Synchronized
    fun importFromFile(context: Context): Int {
        // Hledej backup v externím úložišti
        val externalDir = context.getExternalFilesDir(null)
        val externalFile = if (externalDir != null) File(externalDir, BACKUP_FILE) else null

        val importFile =
                when {
                    externalFile?.exists() == true -> externalFile
                    File(context.filesDir, BACKUP_FILE).exists() ->
                            File(context.filesDir, BACKUP_FILE)
                    else -> {
                        Log.w(
                                TAG,
                                "📂 No import file found (expected: ${externalFile?.absolutePath ?: BACKUP_FILE})"
                        )
                        return -1
                    }
                }

        try {
            val jsonArray = JSONArray(importFile.readText())
            val imported = mutableListOf<AppEntry>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val aliasesArray = obj.getJSONArray("aliases")
                val aliases = mutableSetOf<String>()
                for (j in 0 until aliasesArray.length()) {
                    aliases.add(aliasesArray.getString(j))
                }

                imported.add(
                        AppEntry(
                                packageName = obj.getString("packageName"),
                                label = obj.getString("label"),
                                aliases = aliases,
                                isVoipCapable = obj.optBoolean("isVoipCapable", false),
                                isSystemApp = obj.optBoolean("isSystemApp", false),
                                hasLauncher = obj.optBoolean("hasLauncher", true),
                                installedAt = obj.optLong("installedAt", System.currentTimeMillis())
                        )
                )
            }

            snapshot = imported
            // Uloží do internal storage pro auto-load při startu
            saveToFile(context)
            Log.i(TAG, "✓ Imported ${imported.size} apps from ${importFile.absolutePath}")
            return imported.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from file: ${importFile.absolutePath}", e)
            return -1
        }
    }

    /**
     * Import ze SAF Uri — uživatel vybere soubor přes file picker. Čte přes ContentResolver
     * (funguje s Downloads, Google Drive, atd.)
     *
     * @param context Context pro ContentResolver
     * @param uri SAF Uri z ActivityResult
     * @return Počet importovaných app nebo -1 při chybě
     */
    @Synchronized
    fun importFromUri(context: Context, uri: android.net.Uri): Int {
        try {
            val inputStream =
                    context.contentResolver.openInputStream(uri)
                            ?: run {
                                Log.e(TAG, "Failed to open URI: $uri")
                                return -1
                            }

            val jsonText = inputStream.bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonText)
            val imported = mutableListOf<AppEntry>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val aliasesArray = obj.getJSONArray("aliases")
                val aliases = mutableSetOf<String>()
                for (j in 0 until aliasesArray.length()) {
                    aliases.add(aliasesArray.getString(j))
                }

                imported.add(
                        AppEntry(
                                packageName = obj.getString("packageName"),
                                label = obj.getString("label"),
                                aliases = aliases,
                                isVoipCapable = obj.optBoolean("isVoipCapable", false),
                                isSystemApp = obj.optBoolean("isSystemApp", false),
                                hasLauncher = obj.optBoolean("hasLauncher", true),
                                installedAt = obj.optLong("installedAt", System.currentTimeMillis())
                        )
                )
            }

            snapshot = imported
            saveToFile(context)
            Log.i(TAG, "✓ Imported ${imported.size} apps from URI: $uri")
            return imported.size
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import from URI: $uri", e)
            return -1
        }
    }
    /** Vrátí cestu k backup souboru v externím úložišti (pro UI zobrazení). */
    fun getBackupPath(context: Context): String? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val file = File(externalDir, BACKUP_FILE)
        return if (file.exists()) file.absolutePath else null
    }

    /** Smaže JSON soubor a vyčistí in-memory snapshot. */
    fun deleteFile(context: Context) {
        try {
            val file = File(context.filesDir, PACKAGES_FILE)
            if (file.exists()) {
                file.delete()
                Log.i(TAG, "🗑️ Deleted $PACKAGES_FILE")
            }
            // Smaž i external backup
            val externalDir = context.getExternalFilesDir(null)
            if (externalDir != null) {
                val extFile = File(externalDir, BACKUP_FILE)
                if (extFile.exists()) {
                    extFile.delete()
                    Log.i(TAG, "🗑️ Deleted external backup: ${extFile.absolutePath}")
                }
            }
            clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete snapshot file", e)
        }
    }

    // ==================== AUTO-GENERÁTOR SLANGU ====================

    /**
     * Generuje auto-aliasy pro app na základě packageName a label.
     *
     * Vrstva 1: SLANG_MAP — statický slovník slang/deklinací Vrstva 2: České stem varianty — suffix
     * stripping pro fuzzy match
     *
     * @param packageName Systémový identifikátor (com.android.camera2)
     * @param label User-visible název (Camera)
     * @return Množina auto-generovaných aliasů
     */
    private fun generateAutoAliases(packageName: String, label: String): Set<String> {
        val result = mutableSetOf<String>()
        val searchText = "$packageName $label"

        // Vrstva 1: SLANG_MAP lookup
        for ((pattern, aliases) in SLANG_MAP) {
            if (pattern.containsMatchIn(searchText)) {
                result.addAll(aliases)
            }
        }

        // Vrstva 2: České stem varianty z label
        val labelLower = label.lowercase(Locale.ROOT)
        result.addAll(generateCzechStems(labelLower))

        // Vrstva 2b: Stem varianty z jednotlivých slov label (pro multi-word labels)
        labelLower.split(" ", "-", "_").forEach { word ->
            if (word.length >= 4) {
                result.addAll(generateCzechStems(word))
            }
        }

        return result
    }

    /**
     * Generuje české stem varianty — strip běžných deklinačních koncovek.
     *
     * Příklady: "kameru" → "kamer" (stem) "fotografií" → "fotografi" (stem) "budíku" → "budík"
     * (stem zachová základ)
     *
     * Varianty se přidávají ke stem — hledání pak funguje: findApp("kameru") → contains("kamer") →
     * matchne "kamera" (label)
     *
     * @param word České slovo (lowercase)
     * @return Množina stem variant
     */
    private fun generateCzechStems(word: String): Set<String> {
        if (word.length < 4) return emptySet()

        val stems = mutableSetOf<String>()

        // České deklinační koncovky (seřazeny od nejdelších — greedy match)
        val suffixes =
                listOf(
                        "ách",
                        "ami",
                        "ovi",
                        "ům", // 3-char
                        "ou",
                        "em",
                        "ěm",
                        "ek",
                        "ka",
                        "ku",
                        "ce",
                        "ci",
                        "ií", // 2-char
                        "u",
                        "ě",
                        "e",
                        "y",
                        "í",
                        "a",
                        "o" // 1-char
                )

        for (suffix in suffixes) {
            if (word.endsWith(suffix) && word.length - suffix.length >= 3) {
                stems.add(word.dropLast(suffix.length))
                break // Jen nejdelší matchující suffix
            }
        }

        return stems
    }
}
