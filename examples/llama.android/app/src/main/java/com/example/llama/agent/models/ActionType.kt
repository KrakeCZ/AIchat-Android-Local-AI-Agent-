package com.example.llama.agent.models

import androidx.annotation.Keep

/**
 * Typy akcí, které agent může vykonat
 */
@Keep
enum class ActionType {
    // === Základní akce ===
    CLICK,          // Kliknutí na element
    SCROLL_DOWN,    // Scroll dolů (standard swipe)
    SCROLL_UP,      // Scroll nahoru (standard swipe)
    SCROLL_TO_TOP,    // Scroll úplně nahoru (dlouhý fling)
    SCROLL_TO_BOTTOM, // Scroll úplně dolů (dlouhý fling)
    SCROLL_LITTLE_UP, // Scroll o kousek nahoru
    SCROLL_LITTLE_DOWN, // Scroll o kousek dolů
    SCROLL_PAGE_UP,   // Scroll o stránku nahoru
    SCROLL_PAGE_DOWN, // Scroll o stránku dolů
    INPUT_TEXT,     // Zadání textu do pole

    // === Multitouch / Zoom ===
    ZOOM_IN,        // Pinch Open (přiblížit)
    ZOOM_OUT,       // Pinch Close (oddálit)
    PINCH_OPEN,     // Alias pro ZOOM_IN
    PINCH_CLOSE,    // Alias pro ZOOM_OUT

    // === Precision Text Editing ===
    EDIT_DELETE_WORD, // Smazat poslední slovo
    EDIT_DELETE_ALL,  // Vymazat celý text
    EDIT_SELECT_ALL,  // Vybrat vše (CTRL+A / Long press -> Select All)
    EDIT_COPY,        // Kopírovat výběr
    EDIT_PASTE,       // Vložit ze schránky
    EDIT_ENTER,       // Stisknout Enter/Odeslat
    EDIT_UNDO,        // Zpět (CTRL+Z)
    EDIT_MOVE_CURSOR_START, // Skok na začátek
    EDIT_MOVE_CURSOR_END,   // Skok na konec

    // === D-Pad & System Navigation ===
    NAV_UP,         // Šipka nahoru
    NAV_DOWN,       // Šipka dolů
    NAV_LEFT,       // Šipka vlevo
    NAV_RIGHT,      // Šipka vpravo
    NAV_CENTER,     // Potvrzení středu (DPAD_CENTER / Enter)
    NAV_TAB,        // Tabulátor (skok na další pole)
    NAV_ENTER,      // Enter (pro navigaci)
    NAV_BACK,       // Zpět (pro navigaci)
    NAV_HOME,       // Domů (pro navigaci)
    NAV_RECENTS,    // Recents (pro navigaci)

    // === Navigace ===
    BACK,           // Systémové tlačítko Zpět
    HOME,           // Systémové tlačítko Domů
    RECENT_APPS,    // Přehled nedávných aplikací
    NOTIFICATIONS,  // Stažení notifikační lišty
    OPEN_APP,       // Otevření aplikace (dle názvu/package)

    // === Systémové akce ===
    SCREENSHOT,     // Snímek obrazovky (API 28+)
    LOCK_SCREEN,    // Zamknutí obrazovky (API 28+)
    QUICK_SETTINGS, // Rychlé nastavení (WiFi, BT, atd.)
    SPLIT_SCREEN,   // Rozdělená obrazovka (API 24+)
    POWER_DIALOG,   // Dialog vypnutí/restartu (API 21+)

    // === Herní gesta ===
    SWIPE_LEFT,     // Swipe doleva (dispatchGesture)
    SWIPE_RIGHT,    // Swipe doprava (dispatchGesture)
    SWIPE_UP,       // Swipe nahoru (dispatchGesture)
    SWIPE_DOWN,     // Swipe dolů (dispatchGesture)
    LONG_PRESS,     // Dlouhý stisk na středu obrazovky
    DOUBLE_TAP,     // Dvojité klepnutí na středu obrazovky
    RAPID_TAP,      // Rychlé opakované klikání (herní automatizace)

    // === Média ===
    MEDIA_PLAY_PAUSE, // Play/Pause hudby/videa
    MEDIA_NEXT,       // Další skladba/video
    MEDIA_PREVIOUS,   // Předchozí skladba/video

    // === Communication (Non-Root Intent-based) ===
    CALL,             // VoIP volání přes appku (WhatsApp/Viber/Telegram/Signal)
    SMS,              // Odeslání SMS (ACTION_SENDTO smsto: schéma)
    OPEN_URL,         // Otevření URL v prohlížeči (ACTION_VIEW)
    COMPOSE_EMAIL,    // Odeslání emailu (ACTION_SENDTO mailto: schéma)

    // === Systémové rozšíření (Non-Root) ===
    OPEN_SETTINGS,    // Deep link do systémového nastavení (WiFi, BT, Display, Sound, Apps, Battery)
    SET_WIFI,         // Zapnout/Vypnout WiFi (WifiManager < Q / Settings.Panel ≥ Q)
    SET_BLUETOOTH,    // Zapnout/Vypnout Bluetooth (BluetoothAdapter)
    SET_FLASHLIGHT,   // Zapnout/Vypnout svítilnu (CameraManager.setTorchMode)
    KILL_APP,         // Zavřít/ukončit aplikaci (Recents swipe — best-effort bez root)

    // === System Control (Volume/Display) ===
    SET_VOLUME,       // Nastavit hlasitost absolutně (AudioManager) — percent 0-100
    SET_BRIGHTNESS,   // Nastavit jas absolutně (Settings.System) — percent 0-100
    SET_ROTATION,     // Nastavit auto-rotaci (Settings.System) - vyžaduje WRITE_SETTINGS

    // === Inkrementální ovládání (Volume/Brightness ±krok) ===
    VOLUME_UP,        // Zvýšit hlasitost o krok (+10%)
    VOLUME_DOWN,      // Snížit hlasitost o krok (-10%)
    BRIGHTNESS_UP,    // Zvýšit jas o krok (+10%)
    BRIGHTNESS_DOWN,  // Snížit jas o krok (-10%)
    
    // === Connectivity Toggles (Non-Root) ===
    SET_NFC,          // Zapnout/Vypnout NFC (NfcAdapter / Settings.Panel fallback)
    SET_AIRPLANE,     // Zapnout/Vypnout letový režim (Settings.Panel)

    // === VoIP Call Handling (Accessibility SmartClick) ===
    ANSWER_CALL,      // Přijmout hovor — klikne na zelené tlačítko na obrazovce
    REJECT_CALL,      // Odmítnout hovor — klikne na červené tlačítko na obrazovce

    // === Konfigurace (Setup) ===
    SET_DEFAULT_IME,    // Vyžádat nastavení klávesnice (InputMethod)

    // === NLP / AICore GenAI akce (Tier 2.5+) ===
    NLP_PROOFREAD,    // Korektura textu (AICore Gemini Nano — Proofreader API)
    NLP_REWRITE,      // Přepis textu v jiném tónu (AICore Rewriter: ELABORATE/SHORTEN/PROFESSIONAL)
    NLP_TRANSLATE,    // Překlad textu (ML Kit Translate, 59 jazyků, offline)
    NLP_SUMMARIZE,    // On-demand sumarizace (AICore Summarizer / heuristika fallback)
    NLP_SENTIMENT,    // Sentiment analýza vstupu (MediaPipe TextClassifier, MobileBERT)
    NLP_SMART_REPLY,  // Generování chytrých odpovědí (ML Kit Smart Reply, EN only)
    NLP_NER,          // M2 — Extrakce entit
    NLP_QA,           // M3 — Extractive QA (ToolbarBertWorker)
    NLP_NLI,          // M4 — Detekce rozporů NLI (ToolbarBertWorker)
    NLP_MLM,          // M5 — Doplnění maskovaného textu MLM (ToolbarBertWorker)
    NLP_PROMPT,       // Volny prompt (AICore / MediaPipe LLM)

    // === Ostatní ===
    SPEAK,          // Pouze mluvit (odpověď na otázku)
    UNKNOWN         // Neznámá/nepodporovaná akce (fallback)
}
