package com.example.llama.agent.core

import android.util.Log
import com.example.llama.agent.models.ActionType
import com.example.llama.agent.models.AgentAction
import com.example.llama.agent.models.ScreenState
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * DirectCommandMatcher — Pre-LLM Command Interception (Tier 1 + Tier 2)
 *
 * Inspirováno Samsung/Google Voice Access: Jednoduché příkazy (domů, zpět, notifikace, screenshot,
 * média) → okamžitá akce (<1ms) Složité příkazy (hledání, vyplňování, vícekrokové) → fallback na
 * LLM pipeline
 *
 * Podporuje: CZ + EN + synonyma + český slang + diacritics normalizace
 *
 * Třívrstvá architektura:
 * - Tier 1: Přímé příkazy BEZ kontextu (HOME, BACK, CALL, SMS...) — <1ms
 * - Tier 2: Screen-aware matching (klikni na Wi-Fi, přepni Dark Mode...) — <50ms
 * - Tier 3: LLM pipeline (komplexní/nejednoznačné příkazy) — 3-6s
 *
 * Kategorie:
 * 1. Navigace (HOME, BACK, NOTIFICATIONS, RECENT_APPS)
 * 2. Scroll (DOWN, UP)
 * 3. Systémové akce (SCREENSHOT, LOCK, QUICK_SETTINGS, SPLIT_SCREEN, POWER)
 * 4. Herní gesta (SWIPE_LEFT, SWIPE_RIGHT, LONG_PRESS, DOUBLE_TAP)
 * 5. Média (PLAY/PAUSE, NEXT, PREVIOUS)
 * 6. Aplikace (OPEN_APP)
 * 7. Communication (CALL, SMS, OPEN_URL) — NEW
 * 8. HW Toggles (SET_WIFI, SET_BLUETOOTH, SET_FLASHLIGHT) — NEW
 * 9. Settings Deep Links (OPEN_SETTINGS) — NEW
 * 10. Kill App (KILL_APP) — NEW
 */
class DirectCommandMatcher(private val systemController: SystemController? = null) {

        companion object {
                private const val TAG = "DirectCommandMatcher"

                // Regex patterns pro Communication (compiled once, reused)
                // Call: zavolej, volej, brnkni, cinkni, vytoč, call, ring
                private val P_CALL =
                        Pattern.compile(
                                "^(zavolej|volej|brnkni|cinkni|vytoc|volat|call|ring|hovor)\\s+(?:na\\s+)?(.+)$",
                                Pattern.CASE_INSENSITIVE
                        )

                // SMS: napiš zprávu, pošli sms, textni, smskou
                private val P_SMS =
                        Pattern.compile(
                                "^(napis|posli\\s+(?:sms|zpravu)|sms|textni|zprava\\s+pro|napis\\s+sms)\\s+(?:na\\s+|pro\\s+)?(.+?)(?:\\s+(?:text|zprava|ze|obsah)\\s+(.+))?$",
                                Pattern.CASE_INSENSITIVE
                        )

                // Web: jdi na, otevři web, web, stránka
                private val P_WEB =
                        Pattern.compile(
                                "^(?:jdi\\s+na|otevri\\s+web|web|stranka|browse|otevri\\s+stranku|otevri\\s+url)\\s+(.+)$",
                                Pattern.CASE_INSENSITIVE
                        )

                // Kill: zavři, ukonči, zabij, kill, stop
                private val P_KILL =
                        Pattern.compile(
                                "^(zavri|ukonci|zabij|kill|stop|vypni\\s+aplikaci|zabit)\\s+(?:aplikaci\\s+)?(.+)$",
                                Pattern.CASE_INSENSITIVE
                        )

                // Toggle: zapni/vypni (imperativ), zapnout/vypnout (infinitiv), nahoď/shoď,
                // aktivuj/deaktivuj, rozsviť/zhasni
                private val P_TOGGLE =
                        Pattern.compile(
                                "^(zapni|vypni|zapnout|vypnout|nahod|shod|aktivuj|deaktivuj|rozsvit|zhasni|turn\\s+on|turn\\s+off|enable|disable)\\s+(.+)$",
                                Pattern.CASE_INSENSITIVE
                        )

                // Answer Call: zvedni/přijmi/answer/pick up/accept (hovor/call je volitelné)
                private val P_ANSWER_CALL =
                        Pattern.compile(
                                "^(zvedni|prijmi|prijmout|zvednout|answer|pick\\s*up|accept)\\s*(hovor|call|telefon)?",
                                Pattern.CASE_INSENSITIVE
                        )

                // Reject Call: odmítni/polož/zavěs/reject/hang up/decline
                private val P_REJECT_CALL =
                        Pattern.compile(
                                "^(odmitni|odmitnout|poloz|polozit|zaves|zavesit|reject|hang\\s*up|decline)\\s*(hovor|call|telefon)?",
                                Pattern.CASE_INSENSITIVE
                        )
        }

        /**
         * Výsledek matchování příkazu — wrapper pro partial compound match.
         *
         * @param action První akce k provedení (Tier 1)
         * @param remainingCommand Zbytek příkazu pro LLM pipeline (null = celý příkaz byl Tier 1)
         * @param completedSteps Popis provedených kroků pro LLM kontext
         */
        data class MatchResult(
                val action: AgentAction,
                val remainingCommand: String? = null,
                val completedSteps: List<String> = emptyList()
        )

        /**
         * Tier 1 match s kontextem — rozpozná partial compound příkazy.
         *
         * Příklad: "Otevři nastavení a zapni Wi-Fi" → action = OPEN_APP("nastavení") →
         * remainingCommand = "zapni Wi-Fi" → completedSteps = ["Otevři nastavení"]
         *
         * AgentCoordinator provede action, pak pošle do LLM jen remainingCommand s kontextem
         * completedSteps — LLM nedostane zastaralé instrukce.
         *
         * @return MatchResult pokud partial nebo full match, null pokud žádný match
         */
        fun matchWithContext(command: String): MatchResult? {
                val normalized = normalizeText(command)
                if (normalized.isEmpty()) return null

                // Separátory compound příkazů (stejné jako matchCompound)
                // OPRAVA: ' a ' přidáno AŽ ZA ' a potom ' a ' a pak ' — jinak by ' a potom ' bylo
                // splitnuto
                // předčasně
                val separators =
                        listOf(
                                ", ",
                                " a potom ",
                                " a pak ",
                                " a ",
                                " potom ",
                                " pak ",
                                " - ",
                                " / "
                        )

                // Zkus rozdělit na části
                var parts: List<String>? = null
                for (sep in separators) {
                        if (normalized.contains(sep)) {
                                parts =
                                        normalized.split(sep).map { it.trim() }.filter {
                                                it.isNotEmpty()
                                        }
                                break
                        }
                }

                // Pokud není compound příkaz, použij standardní match()
                if (parts == null || parts.size < 2) {
                        val action = match(command) ?: return null
                        return MatchResult(action = action)
                }

                // Compound: zkus matchnout každou část
                val tier1Parts = mutableListOf<Pair<String, AgentAction>>() // (popis, akce)
                val unknownParts = mutableListOf<String>()

                // Detekce: pokud první část je OPEN_APP, zbylé části potřebují screen kontext
                // (navigace uvnitř otevřené app) → pošli je do remainingCommand (Tier 2/LLM)
                // VÝJIMKA: HW toggley (zapni wifi a bluetooth) fungují vždy Tier 1.
                var firstIsOpenApp = false

                for ((index, part) in parts.withIndex()) {
                        // PRE-CHECK: URL detekce PŘED matchSingleCommand
                        // (tečka je zachována po opravě normalizeText)
                        if (looksLikeUrl(part)) {
                                Log.i(TAG, "⚡ Compound URL pre-check: '$part' → OPEN_URL")
                                tier1Parts.add(
                                        part to
                                                AgentAction(
                                                        thought = "Otevírám web: $part",
                                                        type = ActionType.OPEN_URL,
                                                        inputText = part
                                                )
                                )
                                continue
                        }

                        val action = matchSingleCommand(part) ?: matchImplicitIntent(part)

                        if (index == 0 && action?.type == ActionType.OPEN_APP) {
                                firstIsOpenApp = true
                        }

                        if (action != null) {
                                // Pokud první část = OPEN_APP a toto je další část:
                                // navigační příkazy (NOTIFICATIONS, RECENT_APPS, HOME, BACK) mají
                                // být
                                // v kontextu app → poslat do remainingCommand jako screen-aware
                                // Tier 2
                                // VÝJIMKA: HW toggley a systémové akce fungují globálně i v
                                // compound
                                if (firstIsOpenApp &&
                                                index > 0 &&
                                                isNavigationAmbiguous(action.type)
                                ) {
                                        Log.i(
                                                TAG,
                                                "⚡ Compound OPEN_APP context: '$part' (${action.type}) → remainingCommand (Tier 2)"
                                        )
                                        unknownParts.add(part)
                                } else {
                                        tier1Parts.add(part to action)
                                }
                        } else {
                                unknownParts.add(part)
                        }
                }

                // Žádný Tier 1 match → standardní match() (může vrátit null → LLM)
                if (tier1Parts.isEmpty()) return null

                // Všechny části jsou Tier 1 → přímý compound (BEZ zbytečné rekurze přes match())
                if (unknownParts.isEmpty()) {
                        if (tier1Parts.size == 1) {
                                return MatchResult(action = tier1Parts.first().second)
                        }
                        // Více Tier 1 akcí → první jako hlavní, zbytek jako pending
                        Log.i(
                                TAG,
                                "⚡ All-Tier1 compound: ${tier1Parts.size} akcí — přímý pendingCompoundActions"
                        )
                        pendingCompoundActions = tier1Parts.drop(1).map { it.second }
                        return MatchResult(action = tier1Parts.first().second)
                }

                // PARTIAL MATCH: část je Tier 1, část jde do LLM s kontextem
                // DŮLEŽITÉ: pendingCompoundActions se NENASTAVUJE při partial match.
                // AgentCoordinator zpracuje první Tier 1 akci, pak rekurzivně pošle
                // remainingCommand do LLM. Tier 1 akce za první se ignorují — LLM
                // dostane kontext a rozhodne sám (je to složitý příkaz, LLM to zvládne).
                // Pokud chceme provést více Tier 1 akcí před LLM, musí být v completedSteps.
                Log.i(
                        TAG,
                        "⚡ Partial compound: ${tier1Parts.size} Tier1 + ${unknownParts.size} LLM parts"
                )
                return MatchResult(
                        action = tier1Parts.first().second,
                        remainingCommand = unknownParts.joinToString(" a pak "),
                        completedSteps = tier1Parts.map { it.first }
                )
        }

        /**
         * Tier 1: Pokusí se namatchovat příkaz na přímou akci BEZ LLM a BEZ screen state.
         *
         * @param command Rozpoznaný hlasový příkaz (raw text)
         * @return AgentAction pokud match, null pokud command vyžaduje LLM nebo Tier 2
         */
        fun match(command: String): AgentAction? {
                val normalized = normalizeText(command)
                Log.d(TAG, "🔍 match() input='$command' normalized='$normalized'")
                if (normalized.isEmpty()) return null

                // === 0. COMPOUND PŘÍKAZY ("otevři X, zadej Y") ===
                // Pokud příkaz obsahuje separátor, zkusíme rozdělit na více akcí.
                // matchCompound vrátí první akci; zbytek se uloží pro sekvenční provedení.
                val compoundActions = matchCompound(normalized)
                if (compoundActions != null && compoundActions.isNotEmpty()) {
                        return compoundActions.first()
                }

                // === 1. NAVIGACE ===
                matchHome(normalized)?.let {
                        return it
                }
                matchBack(normalized)?.let {
                        return it
                }
                matchNotifications(normalized)?.let {
                        return it
                }
                matchRecentApps(normalized)?.let {
                        return it
                }

                // === 2. VOLUME / BRIGHTNESS (inkrementální — specifické patterny, vysoká priorita)
                // ===
                matchVolumeUp(normalized)?.let {
                        return it
                }
                matchVolumeDown(normalized)?.let {
                        return it
                }
                matchBrightnessUp(normalized)?.let {
                        return it
                }
                matchBrightnessDown(normalized)?.let {
                        return it
                }

                // === 3. SWIPE GESTA (swipe nahoru/dolu je gesto, NE scroll) ===
                matchSwipeLeft(normalized)?.let {
                        return it
                }
                matchSwipeRight(normalized)?.let {
                        return it
                }
                matchSwipeUp(normalized)?.let {
                        return it
                }
                matchSwipeDown(normalized)?.let {
                        return it
                }

                // === 4. SCROLL (Advanced) ===
                // Priorita: Specifické (Top/Bottom) > Obecné (Down/Up)
                matchScrollAdvanced(normalized)?.let {
                        return it
                }
                matchScrollDown(normalized)?.let {
                        return it
                }
                matchScrollUp(normalized)?.let {
                        return it
                }

                // === 4.5. ZOOM / MULTITOUCH ===
                matchZoom(normalized)?.let {
                        return it
                }

                // === 4.6. TEXT EDIT & NAVIGATION ===
                matchTextEdit(normalized)?.let {
                        return it
                }
                matchNavigation(normalized)?.let {
                        return it
                }

                // === 5. SYSTÉMOVÉ AKCE ===
                matchScreenshot(normalized)?.let {
                        return it
                }
                matchLockScreen(normalized)?.let {
                        return it
                }
                matchQuickSettings(normalized)?.let {
                        return it
                }
                matchSplitScreen(normalized)?.let {
                        return it
                }
                matchPowerDialog(normalized)?.let {
                        return it
                }

                // === 6. HERNÍ GESTA (long press, double tap, rapid tap) ===
                matchLongPress(normalized)?.let {
                        return it
                }
                matchDoubleTap(normalized)?.let {
                        return it
                }
                matchRapidTap(normalized)?.let {
                        return it
                }

                // === 7. MÉDIA ===
                matchMediaPlayPause(normalized)?.let {
                        return it
                }
                matchMediaNext(normalized)?.let {
                        return it
                }
                matchMediaPrevious(normalized)?.let {
                        return it
                }

                // === 8. TEXT INPUT (okamžitý přepis hlasu na text, <1ms) ===
                matchInput(normalized)?.let {
                        return it
                }

                // === 9. COMMUNICATION (CALL, SMS, WEB, EMAIL) ===
                matchCall(normalized)?.let {
                        return it
                }
                matchSms(normalized)?.let {
                        return it
                }
                matchWeb(normalized)?.let {
                        return it
                }
                matchEmail(normalized)?.let {
                        return it
                }

                // === 10. KILL APP ===
                matchKillApp(normalized)?.let {
                        return it
                }

                // === 11. ANSWER / REJECT CALL (VoIP) ===
                matchAnswerCall(normalized)?.let {
                        return it
                }
                matchRejectCall(normalized)?.let {
                        return it
                }

                // === 12. HW TOGGLES (zapni/vypni WiFi/BT/NFC/Airplane/Flashlight) ===
                matchToggle(normalized)?.let {
                        return it
                }

                // === 13. STANDALONE → SETTINGS (wifi/nfc/letadlo/volume/jas bez direction) ===
                matchStandaloneSettings(normalized)?.let {
                        return it
                }

                // === 14. SETTINGS DEEP LINKS ===
                matchSettings(normalized)?.let {
                        return it
                }

                // === 15. APLIKACE (OPEN_APP) ===
                matchOpenApp(normalized)?.let {
                        return it
                }

                // === 12. TIER 1.5: IMPLICIT INTENT (deferred → SmartClick/SmartInput) ===
                // Standalone příkazy: "lajkni", "komentuj", "sdílej", "odpověz", "hledej"
                // Vrací deferred akci — AgentCoordinator ji konvertuje na SmartClick/SmartInput
                matchImplicitIntent(normalized)?.let {
                        Log.i(TAG, "🎯 Tier 1.5 MATCH: '${it.thought}' for '$normalized'")
                        return it
                }

                // === 16. FUZZY APP MATCH (bez prefixu "otevři") ===
                // Příkazy jako "kontakty", "Google zprávy", "tiktok" bez jakéhokoliv prefixu
                // Hledáme v InstalledAppsRegistry/SystemController zda se jedná o název aplikace
                if (normalized.length >= 2) {
                        val fuzzyPkg = systemController?.findAppPackage(normalized)
                        if (fuzzyPkg != null) {
                                Log.i(
                                        TAG,
                                        "⚡ Fuzzy app match (bez prefixu): \"$normalized\" → pkg=$fuzzyPkg"
                                )
                                return AgentAction(
                                        thought = "Otevírám aplikaci: $normalized ($fuzzyPkg)",
                                        type = ActionType.OPEN_APP,
                                        targetId = null,
                                        inputText = fuzzyPkg
                                )
                        }
                }

                // === NO MATCH → Tier 2 or LLM pipeline ===
                Log.w(
                        TAG,
                        "❌ No Tier 1/1.5 match for: \"$command\" (normalized='$normalized') → Tier 2 or LLM"
                )
                return null
        }

        /**
         * Tier 2: Screen-aware matching. Hledá UI element na aktuální obrazovce, který odpovídá
         * příkazu.
         *
         * Uses cases:
         * - "Klikni na Wi-Fi" → najde element s textem "Wi-Fi" a klikne
         * - "Přepni Dark Mode" → najde toggle s textem "Dark Mode"
         *
         * @param command Rozpoznaný hlasový příkaz
         * @param screenState Aktuální stav obrazovky
         * @return AgentAction pokud match, null pokud vyžaduje LLM
         */
        fun matchWithScreen(command: String, screenState: ScreenState): AgentAction? {
                val normalized = normalizeText(command)
                if (normalized.isEmpty() || screenState.elements.isEmpty()) return null

                // A. "Klikni na X" / "Ťukni na X" / "Stiskni X" / "Click X"
                val clickPrefixes =
                        listOf(
                                "klikni na ",
                                "klikni ",
                                "tukni na ",
                                "tukni ",
                                "stiskni ",
                                "jdi na ",
                                "tapni na ",
                                "tapni ",
                                "zmackni ",
                                "click ",
                                "tap ",
                                "press ",
                                "mackni ",
                                "cvakni " // Phonetic
                        )

                for (prefix in clickPrefixes) {
                        if (normalized.startsWith(prefix)) {
                                val targetText = normalized.removePrefix(prefix).trim()
                                if (targetText.isNotEmpty()) {
                                        val element = findElementByText(screenState, targetText)
                                        if (element != null) {
                                                Log.i(
                                                        TAG,
                                                        "⚡ Tier 2 Click match: \"${element.label}\" (ID: ${element.id})"
                                                )
                                                return AgentAction(
                                                        thought = "Klikám na \"${element.label}\"",
                                                        type = ActionType.CLICK,
                                                        targetId = element.id
                                                )
                                        }
                                }
                        }
                }

                // B. "Napiš X do Y" / "Vlož X" — text input s screen context
                val inputPrefixes =
                        listOf(
                                "napis ",
                                "vloz ",
                                "vypln ",
                                "zadej ",
                                "hod tam ",
                                "type ",
                                "enter ",
                                "komentuj "
                        )

                for (prefix in inputPrefixes) {
                        if (normalized.startsWith(prefix)) {
                                val remainder = normalized.removePrefix(prefix).trim()
                                if (remainder.isNotEmpty()) {
                                        // Priorita 1: fokusované editovatelné pole
                                        val focusedField =
                                                screenState.elements.find {
                                                        it.isEditable && it.isFocused
                                                }
                                        // Priorita 2: první editovatelné pole (Android native)
                                        val editableField =
                                                focusedField
                                                        ?: screenState.elements.find {
                                                                it.isEditable
                                                        }
                                                        // Priorita 3: pole identifikované přes
                                                        // hintText (prázdné
                                                        // nativní EditText)
                                                        ?: screenState.elements.find {
                                                                it.hintText.isNotBlank() &&
                                                                        !it.isPassword
                                                        }
                                                        // Priorita 4: WebView INPUT pole
                                                        // (html_tag:INPUT nemá
                                                        // isEditable=true)
                                                        ?: screenState.elements.find {
                                                                it.metadata["tag_name"]
                                                                        ?.uppercase() == "INPUT" ||
                                                                        it.metadata["html_role"] ==
                                                                                "textbox" ||
                                                                        it.metadata["html_role"] ==
                                                                                "searchbox"
                                                        }

                                        if (editableField != null) {
                                                // Hledáme "do pole X" pattern
                                                val doPoleIdx = remainder.indexOf(" do ")
                                                val (text, _) =
                                                        if (doPoleIdx > 0) {
                                                                remainder
                                                                        .substring(0, doPoleIdx)
                                                                        .trim() to
                                                                        remainder
                                                                                .substring(
                                                                                        doPoleIdx +
                                                                                                4
                                                                                )
                                                                                .trim()
                                                        } else {
                                                                remainder to null
                                                        }

                                                Log.i(
                                                        TAG,
                                                        "⚡ Tier 2 Input match: \"$text\" into field ${editableField.id} (hint='${editableField.hintText}')"
                                                )
                                                return AgentAction(
                                                        thought = "Vkládám text \"$text\"",
                                                        type = ActionType.INPUT_TEXT,
                                                        targetId = editableField.id,
                                                        inputText = text
                                                )
                                        }
                                }
                        }
                }

                // C. Comprehensive Implicit Intent Patterns — 100% pokrytí BEZ LLM
                // Každý záznam: commands = co uživatel říká | labels = co hledáme v UI | isInput =
                // text
                // field
                // Pokrytí: TikTok, YouTube, Instagram, Kick, Twitch, Chrome, Kamera, Obecná
                // tlačítka
                data class IntentEntry(
                        val commands: List<String>,
                        val labels: List<String>,
                        val isInput: Boolean = false
                )

                val comprehensiveIntents =
                        listOf(

                                // === SOCIAL — UNIVERSAL ===
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "lajkni",
                                                        "like",
                                                        "palec nahoru",
                                                        "to se mi libi",
                                                        "dej srdce",
                                                        "dej like",
                                                        "lajk",
                                                        "oblibene",
                                                        "pridej oblibene"
                                                ),
                                        labels =
                                                listOf(
                                                        "like",
                                                        "líbí",
                                                        "❤",
                                                        "❤️",
                                                        "👍",
                                                        "Líbí se mi to",
                                                        "like_button",
                                                        "btn_like",
                                                        "action_like",
                                                        "heart",
                                                        "favorite",
                                                        "item_act_like",
                                                        "video_like_button",
                                                        "btn_heart",
                                                        "ic_like",
                                                        "Like",
                                                        "iv_like",
                                                        "likeBtn",
                                                        "like_count"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "dislajkni",
                                                        "dislike",
                                                        "palec dolu",
                                                        "nelibí se mi to",
                                                        "to se mi nelibi",
                                                        "dej dislike"
                                                ),
                                        labels =
                                                listOf(
                                                        "dislike",
                                                        "👎",
                                                        "Nelíbí se mi to",
                                                        "dislike_button",
                                                        "btn_dislike",
                                                        "item_act_dislike",
                                                        "video_dislike_button",
                                                        "ic_dislike",
                                                        "dislikes"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "komentuj",
                                                        "komentar",
                                                        "pridej komentar",
                                                        "add comment",
                                                        "comment",
                                                        "zobraz komentare",
                                                        "komentare"
                                                ),
                                        labels =
                                                listOf(
                                                        "komentář",
                                                        "comment",
                                                        "Přidat komentář",
                                                        "💬",
                                                        "comments",
                                                        "item_act_comment",
                                                        "comment_button",
                                                        "btn_comment",
                                                        "ic_comment",
                                                        "video_comment_button",
                                                        "chat_bubble",
                                                        "Comment",
                                                        "commentsBtn"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("napis komentar", "zadej komentar"),
                                        labels =
                                                listOf(
                                                        "Přidat komentář…",
                                                        "Přidat komentář",
                                                        "Add a comment…",
                                                        "comment_input",
                                                        "et_comment",
                                                        "edt_comment",
                                                        "Komentář"
                                                ),
                                        isInput = true
                                ),
                                IntentEntry(
                                        commands =
                                                listOf("sdilej", "share", "posdilej", "posli dal"),
                                        labels =
                                                listOf(
                                                        "sdílet",
                                                        "share",
                                                        "Share",
                                                        "🔗",
                                                        "Sdílet",
                                                        "item_act_share",
                                                        "btn_share",
                                                        "share_button",
                                                        "ic_share",
                                                        "video_share_button",
                                                        "shareBtn"
                                                )
                                ),

                                // === TIKTOK ===
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "followuj",
                                                        "follow",
                                                        "sleduj",
                                                        "odebírej",
                                                        "sledovat",
                                                        "pridej sledovani"
                                                ),
                                        labels =
                                                listOf(
                                                        "follow",
                                                        "Follow",
                                                        "Sledovat",
                                                        "Odebírat",
                                                        "btn_follow",
                                                        "follow_button",
                                                        "item_act_follow",
                                                        "follow_btn",
                                                        "profile_follow_btn",
                                                        "Sledovat uzivatele"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "unfollowuj",
                                                        "unfollow",
                                                        "prestant sledovat"
                                                ),
                                        labels =
                                                listOf(
                                                        "unfollow",
                                                        "Unfollow",
                                                        "Přestat sledovat",
                                                        "Following",
                                                        "btn_unfollow"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "uloz video",
                                                        "save video",
                                                        "uloz",
                                                        "ulozit",
                                                        "pridej do oblíbených",
                                                        "bookmark"
                                                ),
                                        labels =
                                                listOf(
                                                        "uložit",
                                                        "save",
                                                        "Save",
                                                        "🔖",
                                                        "Přidat do oblíbených",
                                                        "btn_save",
                                                        "save_button",
                                                        "item_act_collect",
                                                        "item_act_save",
                                                        "ic_save",
                                                        "bookmark",
                                                        "Bookmark"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("duet", "udelej duet"),
                                        labels = listOf("duet", "Duet", "item_act_duet")
                                ),
                                IntentEntry(
                                        commands = listOf("stitch", "udelej stitch"),
                                        labels = listOf("stitch", "Stitch", "item_act_stitch")
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "otevri zvuk",
                                                        "sound",
                                                        "zvuk videa",
                                                        "pouzij zvuk"
                                                ),
                                        labels =
                                                listOf(
                                                        "zvuk",
                                                        "Sound",
                                                        "Use this sound",
                                                        "item_act_music",
                                                        "ic_music_note"
                                                )
                                ),

                                // === YOUTUBE ===
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "subscribuj",
                                                        "subscribe",
                                                        "odebírej kanal",
                                                        "odebírej",
                                                        "prihlasit k odberu"
                                                ),
                                        labels =
                                                listOf(
                                                        "odebírat",
                                                        "subscribe",
                                                        "Subscribe",
                                                        "Přihlásit k odběru",
                                                        "subscribe_button",
                                                        "btn_subscribe",
                                                        "sub_button",
                                                        "subscribe_btn",
                                                        "subscription_button",
                                                        "Odebírat",
                                                        "SUBSCRIBE"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "zvonek",
                                                        "bell",
                                                        "zapni notifikace",
                                                        "notifikace na kanal"
                                                ),
                                        labels =
                                                listOf(
                                                        "Upozornění",
                                                        "Notification",
                                                        "bell_button",
                                                        "notification_button",
                                                        "🔔",
                                                        "notifications_bell"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "fullscreen",
                                                        "cela obrazovka",
                                                        "maximalizuj"
                                                ),
                                        labels =
                                                listOf(
                                                        "fullscreen",
                                                        "Celá obrazovka",
                                                        "full_screen",
                                                        "fullscreen_button",
                                                        "player_fullscreen_button"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("titulky", "captions", "zapni titulky"),
                                        labels =
                                                listOf(
                                                        "CC",
                                                        "Titulky",
                                                        "captions_button",
                                                        "closed_caption",
                                                        "player_cc_button",
                                                        "subtitle"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf("kvalita", "quality", "720p", "1080p", "4k"),
                                        labels =
                                                listOf(
                                                        "Nastavení",
                                                        "quality",
                                                        "settings",
                                                        "player_settings_button",
                                                        "overflow_menu_button"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("pridej do playlistu", "watch later"),
                                        labels =
                                                listOf(
                                                        "Uložit",
                                                        "Save to playlist",
                                                        "watch_later_button",
                                                        "add_to_playlist",
                                                        "btn_add_playlist",
                                                        "playlist_add"
                                                )
                                ),

                                // === INSTAGRAM ===
                                IntentEntry(
                                        commands = listOf("story", "pridej story", "pridat pribeh"),
                                        labels =
                                                listOf(
                                                        "Přidat příběh",
                                                        "Add to story",
                                                        "Your story",
                                                        "story_plus",
                                                        "add_story_button"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("direct zprava", "dm"),
                                        labels =
                                                listOf(
                                                        "Zpráva",
                                                        "Message",
                                                        "send_message",
                                                        "DM",
                                                        "direct_message",
                                                        "btn_message",
                                                        "dm_button"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("reels", "reel", "zobraz reels"),
                                        labels = listOf("Reels", "reels_tab", "reels_button")
                                ),

                                // === KICK / TWITCH ===
                                IntentEntry(
                                        commands = listOf("sub", "prihlasit odber"),
                                        labels =
                                                listOf(
                                                        "Subscribe",
                                                        "Sub",
                                                        "SUBSCRIBE",
                                                        "subscribe_button"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "clip",
                                                        "klip",
                                                        "vytvor klip",
                                                        "udelej klip"
                                                ),
                                        labels =
                                                listOf(
                                                        "Clip",
                                                        "Create Clip",
                                                        "clip_button",
                                                        "create_clip"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "napis do chatu",
                                                        "posli zpravu v chatu",
                                                        "chat zprava"
                                                ),
                                        labels =
                                                listOf(
                                                        "Napište zprávu…",
                                                        "Type a message",
                                                        "chat_input",
                                                        "message_input"
                                                ),
                                        isInput = true
                                ),

                                // === CHROME / WEBVIEW ===
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "adresni radek",
                                                        "url bar",
                                                        "zadej url",
                                                        "vyhledej v chrome",
                                                        "hledej v prohlizeci",
                                                        "jdi na stranku",
                                                        "zadej adresu"
                                                ),
                                        labels =
                                                listOf(
                                                        "Vyhledejte nebo zadejte adresu",
                                                        "Search or type URL",
                                                        "url_bar",
                                                        "address_bar",
                                                        "location_bar",
                                                        "omnibox",
                                                        "search_box",
                                                        "Vyhledat nebo zadat adresu URL",
                                                        "url_field"
                                                ),
                                        isInput = true
                                ),
                                IntentEntry(
                                        commands = listOf("nova zalozka", "novy tab", "new tab"),
                                        labels =
                                                listOf(
                                                        "Nová záložka",
                                                        "New tab",
                                                        "new_tab_button",
                                                        "tab_switcher_button"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("obnovit stranku", "refresh", "reload"),
                                        labels =
                                                listOf(
                                                        "Obnovit",
                                                        "Reload",
                                                        "refresh_button",
                                                        "stop_reload_button"
                                                )
                                ),

                                // === KAMERA ===
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "fot",
                                                        "vyfot",
                                                        "fotka",
                                                        "udelej foto",
                                                        "udelej snimek",
                                                        "take photo",
                                                        "capture",
                                                        "snap"
                                                ),
                                        labels =
                                                listOf(
                                                        "Pořidit fotografii",
                                                        "Take photo",
                                                        "shutter_button",
                                                        "btn_shutter",
                                                        "camera_button",
                                                        "CaptureButton",
                                                        "shutter"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "natacet",
                                                        "video mod",
                                                        "prepni na video",
                                                        "nahraj video",
                                                        "record"
                                                ),
                                        labels =
                                                listOf(
                                                        "Video",
                                                        "video_mode",
                                                        "video_button",
                                                        "btn_video",
                                                        "mode_video",
                                                        "Nahrát video"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "prepni kameru",
                                                        "otocit kameru",
                                                        "otoc kameru",
                                                        "selfie",
                                                        "predni kamera",
                                                        "zadni kamera",
                                                        "flip camera"
                                                ),
                                        labels =
                                                listOf(
                                                        "přepnout fotoaparát",
                                                        "flip",
                                                        "camera_switch_button",
                                                        "btn_toggle_camera",
                                                        "switch_camera",
                                                        "front_back",
                                                        "ic_flip",
                                                        "selfie_button"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "blesk",
                                                        "flash",
                                                        "zapni blesk",
                                                        "vypni blesk"
                                                ),
                                        labels =
                                                listOf(
                                                        "blesk",
                                                        "flash",
                                                        "flash_button",
                                                        "btn_flash",
                                                        "ic_flash",
                                                        "FlashButton",
                                                        "auto_flash"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf("casovac", "timer", "nastav timer kamery"),
                                        labels =
                                                listOf(
                                                        "Časovač",
                                                        "Timer",
                                                        "timer_button",
                                                        "btn_timer",
                                                        "ic_timer"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "galerie",
                                                        "otevri galerii",
                                                        "zobraz fotky",
                                                        "moje fotky"
                                                ),
                                        labels =
                                                listOf(
                                                        "Galerie",
                                                        "Gallery",
                                                        "gallery_button",
                                                        "thumbnail_button",
                                                        "recent_photo"
                                                )
                                ),

                                // === OBECNÁ UI TLAČÍTKA ===
                                IntentEntry(
                                        commands = listOf("hledej", "search", "vyhledej", "najdi"),
                                        labels =
                                                listOf(
                                                        "hledat",
                                                        "search",
                                                        "Search",
                                                        "Hledat",
                                                        "vyhledávání",
                                                        "search_view",
                                                        "search_button",
                                                        "🔍",
                                                        "btn_search",
                                                        "searchBtn"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "nastav",
                                                        "nastaveni",
                                                        "settings",
                                                        "otevri nastaveni"
                                                ),
                                        labels =
                                                listOf(
                                                        "nastavení",
                                                        "settings",
                                                        "Settings",
                                                        "Nastavení",
                                                        "⚙",
                                                        "menu_settings",
                                                        "action_settings",
                                                        "btn_settings"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("odpovez", "reply", "reaguj"),
                                        labels =
                                                listOf(
                                                        "Odpovědět",
                                                        "reply",
                                                        "Reply",
                                                        "reagovat",
                                                        "respond"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("smaz", "delete", "odstran"),
                                        labels =
                                                listOf(
                                                        "smazat",
                                                        "delete",
                                                        "Delete",
                                                        "Remove",
                                                        "Smazat",
                                                        "odstranit",
                                                        "🗑",
                                                        "btn_delete",
                                                        "action_delete"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("uprav", "edit", "edituj"),
                                        labels =
                                                listOf(
                                                        "Upravit",
                                                        "edit",
                                                        "Edit",
                                                        "pencil",
                                                        "btn_edit",
                                                        "✏"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("ok", "potvrdit", "ano", "pokracovat"),
                                        labels =
                                                listOf(
                                                        "OK",
                                                        "Ano",
                                                        "Pokračovat",
                                                        "Potvrdit",
                                                        "Continue",
                                                        "button_ok",
                                                        "btn_confirm",
                                                        "btn_continue",
                                                        "Yes",
                                                        "Confirm"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("zrusit", "cancel", "ne", "storno"),
                                        labels =
                                                listOf(
                                                        "Zrušit",
                                                        "cancel",
                                                        "Cancel",
                                                        "Ne",
                                                        "storno",
                                                        "button_cancel",
                                                        "btn_cancel",
                                                        "dismiss"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "profil",
                                                        "zobraz profil",
                                                        "otevri profil",
                                                        "muj profil"
                                                ),
                                        labels =
                                                listOf(
                                                        "Profil",
                                                        "Profile",
                                                        "profile_tab",
                                                        "nav_profile",
                                                        "profile_button",
                                                        "btn_profile",
                                                        "avatar"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "domu",
                                                        "home tab",
                                                        "hlavni stranka",
                                                        "feed"
                                                ),
                                        labels =
                                                listOf(
                                                        "Domů",
                                                        "Home",
                                                        "home_tab",
                                                        "nav_home",
                                                        "home_button",
                                                        "feed_tab"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "menu",
                                                        "otevri menu",
                                                        "hamburger",
                                                        "nabidka"
                                                ),
                                        labels =
                                                listOf(
                                                        "Menu",
                                                        "more_options",
                                                        "overflow_menu",
                                                        "hamburger_menu",
                                                        "navigation_drawer",
                                                        "btn_menu"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("zavri", "zavrit", "close"),
                                        labels =
                                                listOf(
                                                        "Zavřít",
                                                        "Close",
                                                        "dismiss",
                                                        "close_button",
                                                        "btn_close",
                                                        "ic_close"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("play", "prehrat", "spustit", "pustit"),
                                        labels =
                                                listOf(
                                                        "Přehrát",
                                                        "Play",
                                                        "play_button",
                                                        "btn_play",
                                                        "play_pause",
                                                        "ic_play",
                                                        "▶"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "pauza",
                                                        "pause",
                                                        "zapauzovat",
                                                        "pozastavit"
                                                ),
                                        labels =
                                                listOf(
                                                        "Pozastavit",
                                                        "Pause",
                                                        "pause_button",
                                                        "btn_pause",
                                                        "play_pause",
                                                        "ic_pause",
                                                        "⏸"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf(
                                                        "ztlumit",
                                                        "mute",
                                                        "vypnout zvuk",
                                                        "vypni zvuk"
                                                ),
                                        labels =
                                                listOf(
                                                        "Ztlumit",
                                                        "Mute",
                                                        "mute_button",
                                                        "btn_mute",
                                                        "volume_mute",
                                                        "🔇"
                                                )
                                ),
                                IntentEntry(
                                        commands =
                                                listOf("oznameni", "upozorneni", "zobraz oznameni"),
                                        labels =
                                                listOf(
                                                        "Oznámení",
                                                        "Notifications",
                                                        "notification_bell",
                                                        "nav_notifications",
                                                        "🔔",
                                                        "notifications_tab"
                                                )
                                ),
                                IntentEntry(
                                        commands = listOf("zprava", "napis zpravu", "message"),
                                        labels =
                                                listOf(
                                                        "Napište zprávu…",
                                                        "Aa",
                                                        "zpráva",
                                                        "message",
                                                        "type a message",
                                                        "chat_input",
                                                        "message_input",
                                                        "et_message"
                                                ),
                                        isInput = true
                                )
                        )

                // Command matching engine: přesná shoda START → hledej labels v live UI
                for (intent in comprehensiveIntents) {
                        val commandMatched =
                                intent.commands.any { cmd ->
                                        normalized == cmd ||
                                                normalized.startsWith("$cmd ") ||
                                                (normalized.startsWith(cmd) &&
                                                        normalized.length == cmd.length) ||
                                                normalized.endsWith(" $cmd")
                                }
                        if (!commandMatched) continue

                        val target = findBestElement(screenState, intent.labels, intent.isInput)
                        if (target != null) {
                                if (intent.isInput) {
                                        Log.i(
                                                TAG,
                                                "⚡ Tier 2 Input: '${intent.commands.first()}' → '${target.label}' (ID: ${target.id})"
                                        )
                                        return AgentAction(
                                                thought =
                                                        "${normalized.replaceFirstChar { it.uppercase() }} — '${target.label}'",
                                                type = ActionType.INPUT_TEXT,
                                                targetId = target.id
                                        )
                                }
                                val actionType =
                                        if (target.isEditable) ActionType.INPUT_TEXT
                                        else ActionType.CLICK
                                Log.i(
                                        TAG,
                                        "⚡ Tier 2 Semantic: '${intent.commands.first()}' → $actionType '${target.label}' (ID: ${target.id})"
                                )
                                return AgentAction(
                                        thought =
                                                "${normalized.replaceFirstChar { it.uppercase() }} — '${target.label}'",
                                        type = actionType,
                                        targetId = target.id
                                )
                        }
                }

                // D. Generic text match — command přímo odpovídá textu/popis elementu na obrazovce
                val element = findBestElement(screenState, listOf(normalized), preferInput = false)
                if (element != null && element.isEnabled) {
                        Log.i(
                                TAG,
                                "⚡ Tier 2 Generic click: \"${element.label}\" (ID: ${element.id})"
                        )
                        return AgentAction(
                                thought = "Klikám na \"${element.label}\"",
                                type = ActionType.CLICK,
                                targetId = element.id
                        )
                }

                Log.d(
                        TAG,
                        "❌ No Tier 2 match for: \"$command\" on screen with ${screenState.elements.size} elements"
                )
                return null
        }

        /**
         * Tier 2 helper: Vyhledá nejlepší UI element podle seznamu labels. MULTI-PASS filtrování
         * zamezuje "spadnutí do prvního záchytu".
         */
        private fun findBestElement(
                screenState: ScreenState,
                labels: List<String>,
                preferInput: Boolean = false
        ): ScreenState.UIElement? {
                // Bounds filtr odstraněn — semantické UI WebView links a Compose fields často
                // nemají platné bounds a spoléhají na node.performAction
                val eligible = screenState.elements.filter { it.isEnabled }
                val normalizedLabels = labels.filter { it.isNotBlank() }.map { normalizeText(it) }

                if (normalizedLabels.isEmpty() && !preferInput) return null

                // Průchod 1: Přesná shoda (Text / ContentDescription)
                for (normLabel in normalizedLabels) {
                        val exact =
                                eligible.find { el ->
                                        normalizeText(el.text) == normLabel ||
                                                normalizeText(el.contentDescription) == normLabel
                                }
                        if (exact != null) return exact
                }

                // Průchod 2: Částečná shoda (Contains)
                for (normLabel in normalizedLabels) {
                        val contains =
                                eligible.find { el ->
                                        normalizeText(el.text).contains(normLabel) ||
                                                normalizeText(el.contentDescription)
                                                        .contains(normLabel)
                                }
                        if (contains != null) return contains
                }

                // Průchod 3: HintText
                for (normLabel in normalizedLabels) {
                        val hintMatch =
                                eligible.find { el ->
                                        el.hintText.isNotBlank() &&
                                                normalizeText(el.hintText).contains(normLabel)
                                }
                        if (hintMatch != null) return hintMatch
                }

                // Průchod 4: ResourceId (Nejnižší priorita pro potlačení First Catch Syndrome)
                for (normLabel in normalizedLabels) {
                        val resMatch =
                                eligible.find { el ->
                                        el.resourceId.isNotBlank() &&
                                                normalizeText(el.resourceId).contains(normLabel)
                                }
                        if (resMatch != null) return resMatch
                }

                // Fallback pro input: první editovatelné pole
                if (preferInput) {
                        return eligible.find { el ->
                                el.isEditable ||
                                        el.hintText.isNotBlank() ||
                                        el.metadata["html_role"] == "textbox" ||
                                        el.metadata["tag_name"]?.uppercase() == "INPUT"
                        }
                }
                return null
        }

        // ==================== 1. NAVIGACE ====================

        private fun matchHome(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "domu",
                                "na plochu",
                                "plocha",
                                "plochu",
                                "hlavni obrazovka",
                                "jdi domu",
                                "jit domu",
                                "vrat na plochu",
                                "bez domu",
                                "na uvodni obrazovku",
                                "uvodni obrazovka",
                                "barak",
                                "home button",
                                "domov",
                                "na zacatek",
                                "skoc na plochu",
                                // EN
                                "home",
                                "go home",
                                "go to home",
                                "home screen",
                                "go to home screen",
                                "main screen",
                                "launcher"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.HOME, "Přecházím na plochu")
                }
                return null
        }

        private fun matchBack(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "zpet",
                                "zpatky",
                                "vrat se",
                                "vratit se",
                                "jdi zpet",
                                "krok zpet",
                                "navrat",
                                "vrat",
                                "predchozi obrazovka",
                                "zrusit",
                                // EN
                                "back",
                                "go back",
                                "step back",
                                "navigate back",
                                "previous screen",
                                "return"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.BACK, "Vracím se zpět")
                }
                return null
        }

        private fun matchNotifications(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "notifikace",
                                "oznameni",
                                "upozorneni",
                                "lista",
                                "listu",
                                "stahni listu",
                                "zobraz notifikace",
                                "ukaz notifikace",
                                "ukaz oznameni",
                                "zobraz oznameni",
                                "stahni notifikace",
                                "otevri notifikace",
                                "otevri oznameni",
                                // EN
                                "notifications",
                                "show notifications",
                                "open notifications",
                                "notification panel",
                                "notification bar",
                                "notification shade",
                                "pull down",
                                "pull down notifications"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.NOTIFICATIONS, "Otevírám notifikace")
                }
                return null
        }

        private fun matchRecentApps(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "nedavne",
                                "nedavne aplikace",
                                "posledni aplikace",
                                "prepni",
                                "prepni aplikaci",
                                "prepnout",
                                "prepnout aplikaci",
                                "prehled aplikaci",
                                "otevrene aplikace",
                                // EN
                                "recent",
                                "recents",
                                "recent apps",
                                "show recent",
                                "show recent apps",
                                "switch app",
                                "app switcher",
                                "overview",
                                "task switcher"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.RECENT_APPS, "Otevírám nedávné aplikace")
                }
                return null
        }

        // ==================== 4. SCROLL (Advanced) ====================

        private fun matchScrollAdvanced(cmd: String): AgentAction? {
                // Top / Bottom (úplně)
                if (matchesAny(
                                cmd,
                                listOf(
                                        "uplne dolu",
                                        "na konec",
                                        "skoc dolu",
                                        "scroll bottom",
                                        "bottom",
                                        "az dolu",
                                        "konec",
                                        "patička"
                                )
                        )
                ) {
                        return createAction(ActionType.SCROLL_TO_BOTTOM, "Scroll úplně dolů")
                }
                if (matchesAny(
                                cmd,
                                listOf(
                                        "uplne nahoru",
                                        "na zacatek",
                                        "skoc nahoru",
                                        "scroll top",
                                        "top",
                                        "az nahoru",
                                        "zacatek",
                                        "hlavička"
                                )
                        )
                ) {
                        return createAction(ActionType.SCROLL_TO_TOP, "Scroll úplně nahoru")
                }
                // Little (kousek)
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "kousek dolu",
                                        "popojed dolu",
                                        "little down",
                                        "bit down",
                                        "jemne dolu",
                                        "malej scroll dolu",
                                        "posun kousek"
                                )
                        )
                ) {
                        return createAction(ActionType.SCROLL_LITTLE_DOWN, "Scroll o kousek dolů")
                }
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "kousek nahoru",
                                        "popojed nahoru",
                                        "little up",
                                        "bit up",
                                        "jemne nahoru",
                                        "malej scroll nahoru",
                                        "posun kousek nahoru"
                                )
                        )
                ) {
                        return createAction(ActionType.SCROLL_LITTLE_UP, "Scroll o kousek nahoru")
                }
                // Page (stránka)
                if (matchesAnyContains(cmd, listOf("o stranku dolu", "stranka dolu", "page down"))
                ) {
                        return createAction(ActionType.SCROLL_PAGE_DOWN, "Scroll o stránku dolů")
                }
                if (matchesAnyContains(cmd, listOf("o stranku nahoru", "stranka nahoru", "page up"))
                ) {
                        return createAction(ActionType.SCROLL_PAGE_UP, "Scroll o stránku nahoru")
                }
                return null
        }

        private fun matchScrollDown(cmd: String): AgentAction? {
                // Standardní scroll (zbytek po Advanced)
                val strictPatterns = listOf("dolu", "scroll down", "move down", "go down")
                val containsPatterns =
                        listOf(
                                "scrolluj dolu",
                                "posun dolu",
                                "jed dolu",
                                "dolu",
                                "sjed dolu",
                                "roluj dolu",
                                "skroluj dolu",
                                "scroluj dolu",
                                "skrol",
                                "scrol" // Phonetic
                        )
                if (matchesAny(cmd, strictPatterns) || matchesAnyContains(cmd, containsPatterns)) {
                        return createAction(ActionType.SCROLL_DOWN, "Scrolluji dolů")
                }
                return null
        }

        private fun matchScrollUp(cmd: String): AgentAction? {
                val strictPatterns = listOf("nahoru", "scroll up", "move up", "go up")
                val containsPatterns =
                        listOf(
                                "scrolluj nahoru",
                                "posun nahoru",
                                "jed nahoru",
                                "nahoru",
                                "vyjed nahoru",
                                "roluj nahoru",
                                "skroluj nahoru",
                                "scroluj nahoru" // Phonetic
                        )
                if (matchesAny(cmd, strictPatterns) || matchesAnyContains(cmd, containsPatterns)) {
                        return createAction(ActionType.SCROLL_UP, "Scrolluji nahoru")
                }
                return null
        }

        // ==================== 4.5 ZOOM & MULTITOUCH ====================

        private fun matchZoom(cmd: String): AgentAction? {
                // Zoom In — všechny CZ slovesné tvary (infinitiv, imperativ, konjugace)
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "pribliz",
                                        "priblizit",
                                        "priblizuj",
                                        "priblizeni",
                                        "priblizovat",
                                        "zvetsi",
                                        "zvetsit",
                                        "zvetsuj",
                                        "zvetsovat",
                                        "zvetseni",
                                        "zoomuj dovnitr",
                                        "zoom in",
                                        "enlarge",
                                        "magnify",
                                        "plus",
                                        "blize",
                                        "detail"
                                )
                        )
                ) {
                        return createAction(ActionType.ZOOM_IN, "Přibližuji (Zoom In)")
                }
                // Zoom Out — všechny CZ slovesné tvary
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "oddal",
                                        "oddalit",
                                        "oddaluj",
                                        "oddalovat",
                                        "oddaleni",
                                        "zmensi",
                                        "zmensit",
                                        "zmensuj",
                                        "zmensovat",
                                        "zmenseni",
                                        "zoomuj ven",
                                        "zoom out",
                                        "shrink",
                                        "minus",
                                        "celkovy pohled"
                                )
                        )
                ) {
                        return createAction(ActionType.ZOOM_OUT, "Oddaluji (Zoom Out)")
                }
                return null
        }

        // ==================== 4.6 TEXT EDIT & NAVIGATION ====================

        private fun matchTextEdit(cmd: String): AgentAction? {
                // Delete
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "smaz slovo",
                                        "smazat slovo",
                                        "odstran slovo",
                                        "odstranit slovo",
                                        "delete word",
                                        "vymaz slovo",
                                        "vymazat slovo",
                                        "backspace slovo"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_DELETE_WORD, "Mažu slovo")
                }
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "vymaz vse",
                                        "vymazat vse",
                                        "smazat vsechno",
                                        "smaz vse",
                                        "delete all",
                                        "clear all",
                                        "smazat text",
                                        "vycistit",
                                        "vycisti",
                                        "vsechno pryc"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_DELETE_ALL, "Mažu veškerý text")
                }
                // Selection
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "vyber vse",
                                        "vybrat vse",
                                        "oznac vse",
                                        "oznacit vse",
                                        "select all",
                                        "ctrl a"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_SELECT_ALL, "Vybírám vše")
                }
                // Clipboard
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "kopiruj",
                                        "kopirovat",
                                        "zkopiruj",
                                        "zkopirovat",
                                        "copy",
                                        "ctrl c",
                                        "do schranky"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_COPY, "Kopíruji")
                }
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "vloz",
                                        "vlozit",
                                        "vlozit text",
                                        "vlozeni",
                                        "paste",
                                        "ctrl v",
                                        "placni to tam",
                                        "hod to tam"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_PASTE, "Vkládám")
                }
                // Misc
                if (matchesAny(
                                cmd,
                                listOf(
                                        "potvrd",
                                        "potvrdit",
                                        "odradkuj",
                                        "odradkovat",
                                        "enter",
                                        "odeslat",
                                        "submit",
                                        "return",
                                        "novy radek",
                                        "go"
                                )
                        )
                ) {
                        return createAction(ActionType.EDIT_ENTER, "Zadávám Enter")
                }
                if (matchesAnyContains(
                                cmd,
                                listOf(
                                        "undo",
                                        "vrat zmenu",
                                        "vratit zmenu",
                                        "o krok zpet",
                                        "ctrl z"
                                )
                        )
                ) { // "zpet" samotné jde do BACK, "vrat zmenu" je undo
                        return createAction(ActionType.EDIT_UNDO, "Undo")
                }
                return null
        }

        private fun matchNavigation(cmd: String): AgentAction? {
                // Arrows
                if (matchesAny(cmd, listOf("sipka nahoru", "nahoru", "up"))
                ) { // "nahoru" je i scroll? POZOR. Scroll má prioritu před Nav? Ne, Scroll je
                        // nahoře v
                        // match().
                        // Scroll "nahoru" je matchnut v sekci 4. Pokud to propadne sem, je to
                        // divné.
                        // Ale context: v menu/hře může být "nahoru" navigace.
                        // Řešení: Scroll "nahoru" bere vše. NAV_UP jen explicitně "šipka nahoru"
                        return createAction(ActionType.NAV_UP, "Šipka nahoru")
                }
                if (matchesAny(cmd, listOf("sipka dolu", "dolu", "down")))
                        return createAction(
                                ActionType.NAV_DOWN,
                                "Šipka dolů"
                        ) // Scroll matchne "dolu" dříve.
                if (matchesAny(cmd, listOf("sipka vlevo", "doleva", "left")))
                        return createAction(
                                ActionType.NAV_LEFT,
                                "Šipka vlevo"
                        ) // Swipe matchne "doleva" dříve? SWIPE_LEFT má "doleva".
                if (matchesAny(cmd, listOf("sipka vpravo", "doprava", "right")))
                        return createAction(ActionType.NAV_RIGHT, "Šipka vpravo")

                // Center / Tab
                // CRITICAL FIX: "klikni" ODSTRANĚNO — matchesAny startsWith("klikni ")
                // chytalo VŠECHNY "klikni na X" příkazy a zabíjelo celý Tier 2 screen matching!
                // "enter"/"ok" přesunuty pryč — kolidují s EDIT_ENTER a potvrzovacími matchery.
                if (matchesAny(cmd, listOf("potvrd stred", "stredove tlacitko", "center", "stred"))
                ) {
                        return createAction(ActionType.NAV_CENTER, "Potvrzuji (Center)")
                }
                if (matchesAny(cmd, listOf("tabulator", "tab", "dalsi pole", "skoc"))) {
                        return createAction(ActionType.NAV_TAB, "Tabulátor")
                }
                return null
        }

        // ==================== 3. SYSTÉMOVÉ AKCE ====================

        private fun matchScreenshot(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "screenshot",
                                "snimek",
                                "snimek obrazovky",
                                "udelej snimek",
                                "vyfot obrazovku",
                                "vyfotit obrazovku",
                                "snimecek",
                                "printscreen",
                                "screen shot",
                                "vyfot to",
                                // EN
                                "take screenshot",
                                "take a screenshot",
                                "capture screen",
                                "screen capture",
                                "print screen"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.SCREENSHOT, "Pořizuji snímek obrazovky")
                }
                return null
        }

        private fun matchLockScreen(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "zamkni",
                                "zamknout",
                                "zamkni telefon",
                                "zamkni obrazovku",
                                "zamek",
                                "uzamkni",
                                "uzamknout",
                                "uspi",
                                // EN
                                "lock",
                                "lock screen",
                                "lock phone",
                                "lock device"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.LOCK_SCREEN, "Zamykám obrazovku")
                }
                return null
        }

        private fun matchQuickSettings(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "rychle nastaveni",
                                "nastaveni lista",
                                "stahni nastaveni",
                                "prepinace",
                                "toggle",
                                "systemove prepinace",
                                // EN
                                "quick settings",
                                "quick toggles",
                                "settings panel",
                                "toggle panel",
                                "system toggles"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.QUICK_SETTINGS, "Otevírám rychlé nastavení")
                }
                return null
        }

        private fun matchSplitScreen(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "rozdel obrazovku",
                                "split screen",
                                "rozdelena obrazovka",
                                "rozdelit",
                                "dva okna",
                                "dve okna",
                                "multitasking",
                                // EN
                                "split screen",
                                "split view",
                                "multi window",
                                "dual screen",
                                "two windows"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(
                                ActionType.SPLIT_SCREEN,
                                "Přepínám rozdělenou obrazovku"
                        )
                }
                return null
        }

        private fun matchPowerDialog(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ — POZOR: "vypni" bez objektu = power dialog
                                "restart",
                                "restartuj",
                                "vypni telefon",
                                "power menu",
                                "menu vypnuti",
                                "restartovat",
                                "reboot",
                                // EN
                                "power off",
                                "shut down",
                                "shutdown",
                                "power menu",
                                "power dialog",
                                "restart",
                                "reboot",
                                "turn off"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.POWER_DIALOG, "Otevírám dialog vypnutí")
                }
                return null
        }

        // ==================== 4. HERNÍ GESTA ====================

        private fun matchSwipeLeft(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ — uživatel řekne "doleva" = prst swipe doleva = obsah jde
                                // DOPRAVA
                                // ZRCADLENÍ: "doleva" → SWIPE_LEFT (prst jde doleva)
                                "swipe vlevo",
                                "swajpni vlevo",
                                "prejed vlevo",
                                "tahni vlevo",
                                "doleva",
                                "jed vlevo",
                                "posun vlevo",
                                "presun vlevo",
                                "svajpni vlevo",
                                "svajp vlevo", // Phonetic
                                // EN
                                "swipe left",
                                "slide left",
                                "move left",
                                "go left",
                                "drag left"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.SWIPE_LEFT, "Swipe doleva")
                }
                return null
        }

        private fun matchSwipeRight(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "swipe vpravo",
                                "swajpni vpravo",
                                "prejed vpravo",
                                "tahni vpravo",
                                "doprava",
                                "jed vpravo",
                                "posun vpravo",
                                "presun vpravo",
                                "svajpni vpravo",
                                "svajp vpravo",
                                // EN
                                "swipe right",
                                "slide right",
                                "move right",
                                "go right",
                                "drag right"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.SWIPE_RIGHT, "Swipe doprava")
                }
                return null
        }

        private fun matchSwipeUp(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "swipe nahoru",
                                "swajpni nahoru",
                                "prejed nahoru",
                                "tahni nahoru",
                                "jed nahoru prstem",
                                "potahni nahoru",
                                "presun nahoru",
                                "svajpni nahoru",
                                "svajp nahoru",
                                // EN
                                "swipe up",
                                "slide up",
                                "drag up"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.SWIPE_UP, "Swipe nahoru")
                }
                return null
        }

        private fun matchSwipeDown(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "swipe dolu",
                                "swipe dol",
                                "swajpni dolu",
                                "prejed dolu",
                                "tahni dolu",
                                "jed dolu prstem",
                                "potahni dolu",
                                "presun dolu",
                                "svajpni dolu",
                                "svajp dolu", // Phonetic
                                // EN
                                "swipe down",
                                "slide down",
                                "drag down"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.SWIPE_DOWN, "Swipe dolů")
                }
                return null
        }

        private fun matchLongPress(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "dlouhy stisk",
                                "drz",
                                "podrzet",
                                "podrz",
                                "dlouze stiskni",
                                "dlouhy tap",
                                "dlouhe kliknuti",
                                "drz prst",
                                "drzet",
                                // EN
                                "long press",
                                "long tap",
                                "long click",
                                "press and hold",
                                "hold",
                                "touch and hold"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.LONG_PRESS, "Dlouhý stisk")
                }
                return null
        }

        private fun matchDoubleTap(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "dvakrat klikni",
                                "dvojite kliknuti",
                                "double tap",
                                "dvakrat tukni",
                                "dvojity tap",
                                "dvakrat",
                                "dvojklik",
                                "double click",
                                "dvoj tap",
                                // EN
                                "double tap",
                                "double click",
                                "tap twice",
                                "two taps",
                                "double touch"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        return createAction(ActionType.DOUBLE_TAP, "Dvojité klepnutí")
                }
                return null
        }

        private fun matchRapidTap(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "rychle klikej",
                                "rychle klikni",
                                "klikej rychle",
                                "rapid tap",
                                "auto klik",
                                "autoklik",
                                "autoklikr",
                                "autokliker",
                                "lajkuj",
                                "laikuj",
                                "lajkuj to",
                                "laikuj to",
                                "automaticke klikani",
                                "klikej opakovane",
                                "opakuj klik",
                                "klikej vicekrat",
                                "herni klikac",
                                "herni klik",
                                "rychly klik",
                                // EN
                                "rapid tap",
                                "rapid click",
                                "auto click",
                                "auto tap",
                                "spam click",
                                "spam tap",
                                "fast click",
                                "fast tap",
                                "repeat click",
                                "repeat tap",
                                "click repeatedly"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        // Výchozí: 10 kliků po 50ms — uživatel může specifikovat jinak přes LLM
                        Log.i(
                                TAG,
                                "⚡ Direct command match: RAPID_TAP — \"Rychlé opakované klikání\""
                        )
                        return AgentAction(
                                thought = "Rychlé opakované klikání",
                                type = ActionType.RAPID_TAP,
                                targetId = null,
                                inputText = "150|50" // count|intervalMs
                        )
                }
                return null
        }

        // ==================== 5. MÉDIA ====================

        private fun matchMediaPlayPause(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "prehraj",
                                "pustit",
                                "pust",
                                "pauza",
                                "pozastavit",
                                "pozastav",
                                "zastavit hudbu",
                                "spust hudbu",
                                "play",
                                "pause",
                                "stop hudbu",
                                "pustit hudbu",
                                "prehrat",
                                "pust muziku",
                                "zastavit",
                                // EN
                                "play",
                                "pause",
                                "play pause",
                                "play music",
                                "stop music",
                                "resume",
                                "resume music",
                                "pause music",
                                "unpause"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.MEDIA_PLAY_PAUSE, "Play/Pause")
                }
                return null
        }

        private fun matchMediaNext(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "dalsi skladba",
                                "dalsi pisnicka",
                                "dalsi song",
                                "preskoc",
                                "preskocit",
                                "dalsi",
                                "next",
                                "dalsi track",
                                // EN
                                "next",
                                "next track",
                                "next song",
                                "skip",
                                "skip track",
                                "skip song",
                                "forward"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.MEDIA_NEXT, "Další skladba")
                }
                return null
        }

        private fun matchMediaPrevious(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "predchozi skladba",
                                "predchozi pisnicka",
                                "predchozi song",
                                "predchozi",
                                "predchozi track",
                                "vrat skladbu",
                                // EN
                                "previous",
                                "previous track",
                                "previous song",
                                "go back track",
                                "rewind"
                        )
                if (matchesAny(cmd, patterns)) {
                        return createAction(ActionType.MEDIA_PREVIOUS, "Předchozí skladba")
                }
                return null
        }

        // ==================== 6. COMMUNICATION (CALL, SMS, WEB) ====================

        private fun matchCall(cmd: String): AgentAction? {
                val matcher = P_CALL.matcher(cmd)
                if (matcher.find()) {
                        val target = matcher.group(2)?.trim() ?: return null
                        Log.i(TAG, "⚡ Direct CALL match: \"$target\"")
                        return AgentAction(
                                thought = "Volám: $target",
                                type = ActionType.CALL,
                                inputText = target
                        )
                }
                return null
        }

        private fun matchSms(cmd: String): AgentAction? {
                val matcher = P_SMS.matcher(cmd)
                if (matcher.find()) {
                        val target = matcher.group(2)?.trim() ?: return null
                        val messageBody = matcher.group(3)?.trim() ?: ""
                        Log.i(TAG, "⚡ Direct SMS match: to=\"$target\" body=\"$messageBody\"")
                        // inputText format: "number|message" — ActionExecutor parses this
                        val inputText =
                                if (messageBody.isNotEmpty()) "$target|$messageBody" else target
                        return AgentAction(
                                thought = "Posílám SMS na: $target",
                                type = ActionType.SMS,
                                inputText = inputText
                        )
                }
                return null
        }

        private fun matchWeb(cmd: String): AgentAction? {
                val matcher = P_WEB.matcher(cmd)
                if (matcher.find()) {
                        val url = matcher.group(1)?.trim() ?: return null
                        Log.i(TAG, "⚡ Direct WEB match: \"$url\"")
                        return AgentAction(
                                thought = "Otevírám web: $url",
                                type = ActionType.OPEN_URL,
                                inputText = url
                        )
                }
                return null
        }

        // ==================== COMMUNICATION: EMAIL ====================

        /**
         * Email: "napiš email", "pošli email", "email", "mail"
         *
         * Formát rozpoznání:
         * - "napiš email na user@example.com" → address=user@example.com
         * - "pošli email" → prázdná adresa (LLM doplní)
         * - "email user@example.com" → address=user@example.com
         */
        private fun matchEmail(cmd: String): AgentAction? {
                val emailWithTarget =
                        Regex(
                                "^(napis email|posli email|napsat email|email|mail|posli mail|napis mail)\\s+(?:na\\s+)?(.+)"
                        )
                val emailSimple =
                        Regex("^(napis email|posli email|napsat email|posli mail|napis mail)$")

                // Pattern s argumentem (email adresa)
                emailWithTarget.find(cmd)?.let { match ->
                        val target = match.groupValues[2].trim()
                        if (target.isNotEmpty()) {
                                Log.i(TAG, "⚡ Direct command: COMPOSE_EMAIL → \"$target\"")
                                return AgentAction(
                                        thought = "Uživatel chce odeslat email na $target",
                                        type = ActionType.COMPOSE_EMAIL,
                                        inputText = target
                                )
                        }
                }

                // Pattern bez argumentu (jen "napiš email")
                emailSimple.find(cmd)?.let {
                        Log.i(TAG, "⚡ Direct command: COMPOSE_EMAIL (no address)")
                        return AgentAction(
                                thought = "Uživatel chce odeslat email",
                                type = ActionType.COMPOSE_EMAIL,
                                inputText = "" // LLM doplní adresu
                        )
                }

                return null
        }

        // ==================== 8. KILL APP ====================

        private fun matchKillApp(cmd: String): AgentAction? {
                val matcher = P_KILL.matcher(cmd)
                if (matcher.find()) {
                        val appName = matcher.group(2)?.trim() ?: return null
                        Log.i(TAG, "⚡ Direct KILL_APP match: \"$appName\"")
                        return AgentAction(
                                thought = "Ukončuji aplikaci: $appName",
                                type = ActionType.KILL_APP,
                                inputText = appName
                        )
                }
                return null
        }

        // ==================== 9. VOLUME / BRIGHTNESS (inkrementální) ====================

        private fun matchVolumeUp(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ — přirozené hlasové příkazy (normalizované bez diakritiky)
                                "zesil",
                                "zesilni",
                                "zesilej",
                                "zesilit",
                                "pridej hlasitost",
                                "zvys hlasitost",
                                "hlasitost nahoru",
                                "vic hlasitost",
                                "hlasiteji",
                                "pridej volume",
                                "hlasitost pricist",
                                "zvuk nahoru",
                                "zvuk vic",
                                "o krok hlasitost",
                                "hlasitost pridej",
                                "dej hlasitost nahoru",
                                "pridej na hlasitosti",
                                "pridej zvuk",
                                "zvysit hlasitost",
                                "pridat hlasitost",
                                // CZ slang / hovorové
                                "hlasitost na max",
                                "na plny",
                                "plnej pecky",
                                "dej to hlasitejc",
                                "dej to nahlas",
                                "nahlas",
                                "hlasitejc",
                                "hlasnejc",
                                "zvysit zvuk",
                                "pridat zvuk",
                                "dej vic",
                                "trochu hlasitejc",
                                "hodne hlasitejc",
                                // EN
                                "volume up",
                                "louder",
                                "turn up volume",
                                "raise volume",
                                "increase volume",
                                "turn it up"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        // Parsovat počet kroků: "zesil o 3 kroky" → 30%
                        val steps = extractSteps(cmd)
                        val delta = steps * 10
                        return AgentAction(
                                thought = "Zvyšuji hlasitost o $delta%",
                                type = ActionType.VOLUME_UP,
                                inputText = delta.toString()
                        )
                }
                return null
        }

        private fun matchVolumeDown(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "ztlum",
                                "ztis",
                                "ztisni",
                                "stis",
                                "utlum",
                                "ztisit",
                                "sniz hlasitost",
                                "snizit hlasitost",
                                "hlasitost dolu",
                                "min hlasitost",
                                "tiseji",
                                "uber hlasitost",
                                "hlasitost ubrat",
                                "zvuk dolu",
                                "zvuk min",
                                "o krok tis",
                                "ubrat hlasitost",
                                "ubrat zvuk",
                                "dej hlasitost dolu",
                                "snizit zvuk",
                                "sniz zvuk",
                                // CZ slang / hovorové
                                "zeslabit",
                                "zeslab",
                                "utisit",
                                "stisit",
                                "hlasitost na min",
                                "hlasitost na nulu",
                                "dej to tis",
                                "dej to polohlasem",
                                "trochu tis",
                                "hodne ztlum",
                                "ztis to",
                                "dej min zvuk",
                                "snizit zvuk",
                                "ubrat na zvuku",
                                // EN
                                "volume down",
                                "quieter",
                                "turn down volume",
                                "lower volume",
                                "decrease volume",
                                "turn it down",
                                "mute",
                                "silence"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        // "mute" / "ticho" / "ztisit" → 100% snížení (na nulu)
                        if (cmd == "mute" ||
                                        cmd == "silence" ||
                                        cmd == "ztisit" ||
                                        cmd.contains("ztlum na nulu") ||
                                        cmd.contains("bezzvuku") ||
                                        cmd.contains("ticho")
                        ) {
                                return AgentAction(
                                        thought = "Ztlumení na nulu",
                                        type = ActionType.SET_VOLUME,
                                        inputText = "0"
                                )
                        }
                        val steps = extractSteps(cmd)
                        val delta = steps * 10
                        return AgentAction(
                                thought = "Snižuji hlasitost o $delta%",
                                type = ActionType.VOLUME_DOWN,
                                inputText = delta.toString()
                        )
                }
                return null
        }

        private fun matchBrightnessUp(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "zvys jas",
                                "pridej jas",
                                "vic jas",
                                "jasneji",
                                "jas nahoru",
                                "zvetsi jas",
                                "zesvitli",
                                "zesvětli",
                                "svetleji",
                                "svetlejsi",
                                "rozjasni",
                                "o krok jas",
                                "jas pridej",
                                "jas vic",
                                "dej jas nahoru",
                                "pridej na jasu",
                                "zvysit jas",
                                "pridat jas",
                                "dej vic jasu",
                                // CZ slang / hovorové
                                "jas na max",
                                "jas na plno",
                                "plnej jas",
                                "svetlejc",
                                "svetlejsi displej",
                                "rozsvit displej",
                                "dej to svetlejc",
                                "trochu svetlejc",
                                "hodne svetlejc",
                                "zvysit display",
                                "pridat na jasu",
                                // EN
                                "brightness up",
                                "brighter",
                                "increase brightness",
                                "raise brightness",
                                "more brightness",
                                "turn up brightness"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        val steps = extractSteps(cmd)
                        val delta = steps * 10
                        return AgentAction(
                                thought = "Zvyšuji jas o $delta%",
                                type = ActionType.BRIGHTNESS_UP,
                                inputText = delta.toString()
                        )
                }
                return null
        }

        private fun matchBrightnessDown(cmd: String): AgentAction? {
                val patterns =
                        listOf(
                                // CZ
                                "snizit jas",
                                "sniz jas",
                                "uber jas",
                                "min jas",
                                "tmaveji",
                                "jas dolu",
                                "ztmav",
                                "ztmavit",
                                "zmensi jas",
                                "zatemnit",
                                "tmavsi",
                                "ztlumat jas",
                                "jas mensi",
                                "jas ubrat",
                                "jas mene",
                                "dej jas dolu",
                                "snizit jas",
                                "ubrat jas",
                                "dej min jasu",
                                "uber jas",
                                "ubrat na jasu",
                                // CZ slang / hovorové
                                "jas min",
                                "jas minimum",
                                "tmaveji displej",
                                "tmavejc",
                                "ztmavit",
                                "zhasnout displej",
                                "dej to tmavejc",
                                "trochu tmavejc",
                                "hodne ztmav",
                                "snizit display",
                                "ubrat na jasu",
                                // EN
                                "brightness down",
                                "dimmer",
                                "decrease brightness",
                                "lower brightness",
                                "less brightness",
                                "turn down brightness",
                                "dim"
                        )
                if (matchesAnyContains(cmd, patterns)) {
                        val steps = extractSteps(cmd)
                        val delta = steps * 10
                        return AgentAction(
                                thought = "Snižuji jas o $delta%",
                                type = ActionType.BRIGHTNESS_DOWN,
                                inputText = delta.toString()
                        )
                }
                return null
        }

        /** Extrahuje počet kroků z příkazu: "zesil o 3 kroky" → 3, "zesil" → 1 */
        private fun extractSteps(cmd: String): Int {
                val stepRegex = Regex("""o\s+(\d+)\s*(krok|step)""")
                val match = stepRegex.find(cmd)
                return match?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 10) ?: 1
        }

        // ==================== 10. HW TOGGLES ====================

        private fun matchToggle(cmd: String): AgentAction? {
                val matcher = P_TOGGLE.matcher(cmd)
                if (!matcher.find()) return null

                val action = matcher.group(1)?.lowercase() ?: return null
                val target = matcher.group(2)?.lowercase()?.trim() ?: return null
                val shouldEnable =
                        action.startsWith("zap") ||
                                action.startsWith("nah") ||
                                action.startsWith("akt") ||
                                action.startsWith("roz") ||
                                action.contains("on") ||
                                action.startsWith("enab")

                // Resolve target to specific HW action
                return when {
                        target.contains("svitilna") ||
                                target.contains("baterka") ||
                                target.contains("baterku") ||
                                target.contains("světlo") ||
                                target.contains("žárovka") ||
                                target.contains("svitilnu") ||
                                target.contains("flashlight") ||
                                target.contains("torch") -> {
                                Log.i(TAG, "⚡ Direct FLASHLIGHT toggle: enable=$shouldEnable")
                                AgentAction(
                                        thought =
                                                "${if (shouldEnable) "Zapínám" else "Vypínám"} svítilnu",
                                        type = ActionType.SET_FLASHLIGHT,
                                        inputText = shouldEnable.toString()
                                )
                        }
                        target.contains("wifi") ||
                                target.contains("wi-fi") ||
                                target.contains("internet") -> {
                                Log.i(TAG, "⚡ Direct WIFI toggle: enable=$shouldEnable")
                                AgentAction(
                                        thought =
                                                "${if (shouldEnable) "Zapínám" else "Vypínám"} WiFi",
                                        type = ActionType.SET_WIFI,
                                        inputText = shouldEnable.toString()
                                )
                        }
                        target.contains("bluetooth") || target.contains("bt") -> {
                                Log.i(TAG, "⚡ Direct BLUETOOTH toggle: enable=$shouldEnable")
                                AgentAction(
                                        thought =
                                                "${if (shouldEnable) "Zapínám" else "Vypínám"} Bluetooth",
                                        type = ActionType.SET_BLUETOOTH,
                                        inputText = shouldEnable.toString()
                                )
                        }
                        target.contains("nfc") -> {
                                Log.i(TAG, "⚡ Direct NFC toggle: enable=$shouldEnable")
                                AgentAction(
                                        thought =
                                                "${if (shouldEnable) "Zapínám" else "Vypínám"} NFC",
                                        type = ActionType.SET_NFC,
                                        inputText = shouldEnable.toString()
                                )
                        }
                        target.contains("letadlo") ||
                                target.contains("airplane") ||
                                target.contains("flight") -> {
                                Log.i(TAG, "⚡ Direct AIRPLANE toggle: enable=$shouldEnable")
                                AgentAction(
                                        thought =
                                                "${if (shouldEnable) "Zapínám" else "Vypínám"} letový režim",
                                        type = ActionType.SET_AIRPLANE,
                                        inputText = shouldEnable.toString()
                                )
                        }
                        else -> {
                                Log.d(TAG, "Toggle target '$target' not recognized as HW toggle")
                                null
                        }
                }
        }

        // ==================== 10.5 ANSWER / REJECT CALL (VoIP Accessibility) ====================

        private fun matchAnswerCall(cmd: String): AgentAction? {
                if (!P_ANSWER_CALL.matcher(cmd).find()) return null
                Log.i(TAG, "⚡ Direct ANSWER_CALL match")
                return AgentAction(thought = "Přijímám hovor", type = ActionType.ANSWER_CALL)
        }

        private fun matchRejectCall(cmd: String): AgentAction? {
                if (!P_REJECT_CALL.matcher(cmd).find()) return null
                Log.i(TAG, "⚡ Direct REJECT_CALL match")
                return AgentAction(thought = "Odmítám hovor", type = ActionType.REJECT_CALL)
        }

        // ==================== 10.6 STANDALONE → SETTINGS ====================

        /**
         * Standalone nouns BEZ direction/toggle → otevřít příslušné nastavení. Exact match only
         * (cmd == "wifi") — aby nechytal "zapni wifi" (to jde do matchToggle).
         *
         * DŮLEŽITÉ: Volume/zvuk ODDĚLENÉ od jas/displej!
         */
        private fun matchStandaloneSettings(cmd: String): AgentAction? {
                val section =
                        when (cmd) {
                                // Connectivity
                                "wifi",
                                "wlan" -> "WIFI"
                                "bluetooth", "bt" -> "BLUETOOTH"
                                "nfc" -> "NFC"
                                "letadlo", "airplane", "flight mode" -> "AIRPLANE"
                                // Volume/Zvuk — ODDĚLENÉ od Display/Jas
                                "volume",
                                "hlasitost",
                                "zvuk",
                                "audio" -> "SOUND"
                                // Jas/Displej — ODDĚLENÉ od Volume/Zvuk
                                "jas",
                                "brightness",
                                "displej",
                                "display" -> "DISPLAY"
                                else -> return null
                        }
                Log.i(TAG, "⚡ Standalone → OPEN_SETTINGS $section")
                return AgentAction(
                        thought = "Otevírám nastavení: $section",
                        type = ActionType.OPEN_SETTINGS,
                        inputText = section
                )
        }

        // ==================== 10. SETTINGS DEEP LINKS ====================

        private fun matchSettings(cmd: String): AgentAction? {
                val section = systemController?.resolveSettingsSection(cmd)
                if (section != null) {
                        Log.i(TAG, "⚡ Direct SETTINGS match: $section")
                        return AgentAction(
                                thought = "Otevírám nastavení: $section",
                                type = ActionType.OPEN_SETTINGS,
                                inputText = section.name
                        )
                }

                // Fallback: generic "nastavení" / "settings"
                if (cmd == "nastaveni" || cmd == "settings") {
                        return AgentAction(
                                thought = "Otevírám nastavení",
                                type = ActionType.OPEN_SETTINGS,
                                inputText = "GENERAL"
                        )
                }

                return null
        }

        // ==================== 11. OPEN APP ====================

        private fun matchOpenApp(cmd: String): AgentAction? {
                val prefixes =
                        listOf(
                                // CZ
                                "otevri ",
                                "spust ",
                                "zapni ",
                                "otevrit ",
                                "spustit ",
                                "zapnout ",
                                "pusti ",
                                "pustit ",
                                "bez do ",
                                "prejdi do ",
                                "otevri mi ",
                                "nahod ",
                                "jdi do ",
                                // EN
                                "open ",
                                "launch ",
                                "start ",
                                "run ",
                                "go to ",
                                "switch to "
                        )

                for (prefix in prefixes) {
                        if (cmd.startsWith(prefix)) {
                                val remainder = cmd.removePrefix(prefix).trim()
                                if (remainder.isEmpty()) continue

                                // Smart URL detekce: "otevři seznam.cz" → OPEN_URL
                                // looksLikeUrl kontroluje že text NEOBSAHUJE mezery (URL nemá
                                // mezery)
                                if (looksLikeUrl(remainder)) {
                                        Log.i(
                                                TAG,
                                                "⚡ Smart URL detection in OPEN_APP: \"$remainder\""
                                        )
                                        return AgentAction(
                                                thought = "Otevírám web: $remainder",
                                                type = ActionType.OPEN_URL,
                                                inputText = remainder
                                        )
                                }

                                // "otevři chrome na adrese/na X" pattern
                                val urlSeparators =
                                        listOf(" na adrese ", " adresa ", " url ", " web ")
                                for (sep in urlSeparators) {
                                        val sepIdx = remainder.indexOf(sep)
                                        if (sepIdx > 0) {
                                                val potentialUrl =
                                                        remainder
                                                                .substring(sepIdx + sep.length)
                                                                .trim()
                                                if (potentialUrl.isNotEmpty() &&
                                                                looksLikeUrl(potentialUrl)
                                                ) {
                                                        val appPart =
                                                                remainder
                                                                        .substring(0, sepIdx)
                                                                        .trim()
                                                        Log.i(
                                                                TAG,
                                                                "⚡ App+URL separator pattern: app=\"$appPart\" url=\"$potentialUrl\""
                                                        )
                                                        return AgentAction(
                                                                thought =
                                                                        "Otevírám $potentialUrl v $appPart",
                                                                type = ActionType.OPEN_URL,
                                                                inputText = potentialUrl
                                                        )
                                                }
                                        }
                                }

                                // "otevři chrome seznam.cz" → app + URL (poslední slovo je URL)
                                if (remainder.contains(" ")) {
                                        val lastSpace = remainder.lastIndexOf(' ')
                                        val lastWord = remainder.substring(lastSpace + 1).trim()
                                        val appPart = remainder.substring(0, lastSpace).trim()
                                        if (lastWord.isNotEmpty() &&
                                                        appPart.isNotEmpty() &&
                                                        looksLikeUrl(lastWord)
                                        ) {
                                                Log.i(
                                                        TAG,
                                                        "⚡ App+space+URL split: app=\"$appPart\" url=\"$lastWord\""
                                                )
                                                // Uložíme OPEN_URL jako pending compound akci
                                                pendingCompoundActions =
                                                        listOf(
                                                                AgentAction(
                                                                        thought =
                                                                                "Otevírám $lastWord",
                                                                        type = ActionType.OPEN_URL,
                                                                        inputText = lastWord
                                                                )
                                                        )
                                                val pkg = systemController?.findAppPackage(appPart)
                                                return AgentAction(
                                                        thought =
                                                                "Otevírám $appPart a pak $lastWord",
                                                        type = ActionType.OPEN_APP,
                                                        targetId = null,
                                                        inputText = pkg ?: appPart
                                                )
                                        }
                                }

                                // Guard: "nastaveni"/"settings" bez prefixu →
                                // OPEN_SETTINGS(GENERAL)
                                // Zabrání selhání ActionExecutor při hledání balíčku "nastaveni"
                                if (remainder == "nastaveni" ||
                                                remainder == "settings" ||
                                                remainder == "nastaveni systemu"
                                ) {
                                        Log.i(
                                                TAG,
                                                "⚡ OPEN_APP guard: '$remainder' → OPEN_SETTINGS(GENERAL)"
                                        )
                                        return AgentAction(
                                                thought = "Otevírám nastavení",
                                                type = ActionType.OPEN_SETTINGS,
                                                targetId = null,
                                                inputText = "GENERAL"
                                        )
                                }

                                // === SMART COMPOUND DETECTION ===
                                // Detekce akčních slov v remainder — 'otevri tiktok lajkni video'
                                // → OPEN_APP('tiktok') + pending deferred('lajkni video')
                                // Akční slova musí být BEZ diakritiky (normalizeText() ji
                                // strippuje)
                                val actionWords =
                                        listOf(
                                                "lajkni",
                                                "like",
                                                "dej like",
                                                "dej lajk",
                                                "komentuj",
                                                "komentar",
                                                "sdilej",
                                                "share",
                                                "posilej",
                                                "hledej",
                                                "vyhledej",
                                                "najdi",
                                                "search",
                                                "sleduj",
                                                "subscribe",
                                                "odeber",
                                                "follow",
                                                "klikni",
                                                "stiskni",
                                                "tukni",
                                                "zapni",
                                                "vypni",
                                                "prepni",
                                                "zadej",
                                                "napis",
                                                "vloz",
                                                "scrolluj",
                                                "scrollni",
                                                "posun"
                                        )

                                if (remainder.contains(" ")) {
                                        // Hledej akční slovo v remainder
                                        for (actionWord in actionWords) {
                                                val actionIdx = remainder.indexOf(" $actionWord ")
                                                // Fallback: akční slovo na poslední pozici (bez
                                                // trailing mezery)
                                                val actionIdxEnd =
                                                        if (actionIdx < 0) {
                                                                val suffixCheck = " $actionWord"
                                                                if (remainder.endsWith(suffixCheck))
                                                                        remainder.length -
                                                                                suffixCheck.length
                                                                else -1
                                                        } else actionIdx

                                                if (actionIdxEnd > 0) {
                                                        val appName =
                                                                remainder
                                                                        .substring(0, actionIdxEnd)
                                                                        .trim()
                                                        val deferredPart =
                                                                remainder
                                                                        .substring(actionIdxEnd)
                                                                        .trim()
                                                        if (appName.isNotEmpty() &&
                                                                        deferredPart.isNotEmpty()
                                                        ) {
                                                                Log.i(
                                                                        TAG,
                                                                        "⚡ Smart compound split: app='$appName' deferred='$deferredPart'"
                                                                )
                                                                val deferredAction =
                                                                        matchImplicitIntent(
                                                                                deferredPart
                                                                        )
                                                                if (deferredAction != null) {
                                                                        pendingCompoundActions =
                                                                                listOf(
                                                                                        deferredAction
                                                                                )
                                                                }
                                                                val pkg =
                                                                        systemController
                                                                                ?.findAppPackage(
                                                                                        appName
                                                                                )
                                                                return AgentAction(
                                                                        thought =
                                                                                "Otevírám $appName${if (deferredAction != null) " + ${deferredPart}" else ""}",
                                                                        type = ActionType.OPEN_APP,
                                                                        targetId = null,
                                                                        inputText = pkg ?: appName
                                                                )
                                                        }
                                                }
                                        }
                                }

                                // Standardní OPEN_APP (bez compound)
                                val pkg = systemController?.findAppPackage(remainder)
                                Log.i(
                                        TAG,
                                        "⚡ Direct OPEN_APP match: \"$remainder\" → pkg=${pkg ?: "will-resolve-later"}"
                                )
                                return AgentAction(
                                        thought =
                                                "Otevírám aplikaci: ${if (pkg != null) "$remainder ($pkg)" else remainder}",
                                        type = ActionType.OPEN_APP,
                                        targetId = null,
                                        inputText = pkg ?: remainder
                                )
                        }
                }
                return null
        }

        // ==================== COMPOUND CONTEXT HELPERS ====================

        /**
         * Rozhoduje, zda akce je "navigačně nejednoznačná" v kontextu compound příkazu.
         *
         * Pokud compound začíná OPEN_APP("budík"), pak "upozornění" může znamenat:
         * - GLOBÁLNĚ: Stáhni notification shade (NOTIFICATIONS)
         * - V KONTEXTU: Přejdi na záložku "Upozornění" v budíku
         *
         * Typy, které jsou nejednoznačné a měly by jít do remainingCommand (Tier 2 screen-aware):
         * - NOTIFICATIONS, RECENT_APPS — mohou být in-app navigace
         * - BACK, HOME — v compound s OPEN_APP nedávají smysl jako globální akce
         * - OPEN_SETTINGS — může být in-app nastavení
         * - SCROLL_DOWN, SCROLL_UP — může být specifický scroll v app
         * - OPEN_APP — druhý OPEN_APP = přepnutí bez čekání na screen
         *
         * Typy, které NEJSOU nejednoznačné (vždy globální, i v compound):
         * - HW toggley (SET_WIFI, SET_BLUETOOTH, SET_NFC, SET_FLASHLIGHT, SET_AIRPLANE)
         * - Média (MEDIA_PLAY_PAUSE, MEDIA_NEXT, MEDIA_PREVIOUS)
         * - Volume/Brightness (VOLUME_UP/DOWN, BRIGHTNESS_UP/DOWN)
         * - SCREENSHOT, LOCK_SCREEN, POWER_DIALOG
         * - CALL, SMS, OPEN_URL
         */
        private fun isNavigationAmbiguous(type: ActionType): Boolean {
                return when (type) {
                        ActionType.NOTIFICATIONS,
                        ActionType.RECENT_APPS,
                        ActionType.BACK,
                        ActionType.HOME,
                        ActionType.OPEN_SETTINGS,
                        ActionType.SCROLL_DOWN,
                        ActionType.SCROLL_UP,
                        ActionType.SCROLL_TO_TOP,
                        ActionType.SCROLL_TO_BOTTOM,
                        ActionType.OPEN_APP -> true
                        else -> false
                }
        }

        // ==================== MATCHING HELPERS ====================

        /**
         * Strict match: celý command musí být roven patternu, nebo pattern je na začátku/konci
         * commandu. Zabraňuje false-positives (např. "dolu" v "klikni dolu na tlačítko").
         */
        private fun matchesAny(cmd: String, patterns: List<String>): Boolean {
                return patterns.any { pattern ->
                        cmd == pattern || cmd.startsWith("$pattern ") || cmd.endsWith(" $pattern")
                }
        }

        /**
         * Loose match: pattern se nachází kdekoliv v commandu (contains). Pro patterny které jsou
         * dostatečně specifické (např. "notifikace", "screenshot").
         */
        private fun matchesAnyContains(cmd: String, patterns: List<String>): Boolean {
                return patterns.any { pattern -> cmd == pattern || cmd.contains(pattern) }
        }

        // ==================== TEXT NORMALIZATION ====================

        /**
         * Normalizuje text — odstraní diakritiku a převede na lowercase.
         *
         * "Zavolej Mámě" → "zavolej mame" "Přejeď vpravo" → "prejed vpravo"
         *
         * Toto je klíčové pro robustní matching češtiny:
         * - STT engine může vrátit text s/bez diakritiky
         * - Uživatel může psát s/bez diakritiky
         * - České morfologické variace (mámě/mame) se snarovnají
         */
        private fun normalizeText(input: String): String {
                val nfd = Normalizer.normalize(input.trim(), Normalizer.Form.NFD)
                val pattern = Pattern.compile("\\p{InCombiningDiacriticalMarks}+")
                var stripped = pattern.matcher(nfd).replaceAll("").lowercase()

                // FIX 1: Replace sentence terminators with space, ALE zachovej tečky v URL
                // kontextu.
                // Tečka mezi alfanumerickými znaky (abc.cz, google.com) = URL → zachovat.
                // Tečka na konci věty nebo za mezerou = strip.
                stripped = stripped.replace(Regex("[?!;]"), " ")
                // Tečka: strip jen pokud NENÍ v URL patternu (alfanum.alfanum)
                stripped = stripped.replace(Regex("""(?<![a-z0-9])\.|\.(?![a-z0-9])"""), " ")

                // FIX 2: Remove other non-separator punctuation, keep [a-z0-9 space , . - /]
                // Tečka přidána do povolených znaků pro URL detekci
                return stripped.replace(Regex("[^a-z0-9\\s,./-]"), "")
                        .trim()
                        .replace(Regex("\\s+"), " ")
        }

        // ==================== SCREEN SEARCH HELPERS ====================

        /**
         * Hledá UI element na obrazovce podle textu (fuzzy match).
         *
         * Priority:
         * 1. Exact match (normalizedSearchText == query)
         * 2. Contains (normalizedSearchText contains query)
         */
        private fun findElementByText(
                screenState: ScreenState,
                query: String
        ): ScreenState.UIElement? {
                val normQuery = normalizeText(query)
                if (normQuery.isEmpty()) return null

                // Filtruj pouze enabled prvky (zrušen bounds filter)
                val eligible = screenState.elements.filter { it.isEnabled }

                // 1. Exact match - text or contentDescription
                eligible
                        .find { element ->
                                normalizeText(element.text) == normQuery ||
                                        normalizeText(element.contentDescription) == normQuery
                        }
                        ?.let {
                                return it
                        }

                // 1.5. StartsWith match (dlouhé hypertextové odkazy, první 2-3 slova)
                eligible
                        .find { element ->
                                val normText = normalizeText(element.text)
                                val normDesc = normalizeText(element.contentDescription)
                                normText.startsWith("$normQuery ") ||
                                        normDesc.startsWith("$normQuery ")
                        }
                        ?.let {
                                return it
                        }

                // 2. Contains match - text or contentDescription
                eligible
                        .find { element ->
                                normalizeText(element.text).contains(normQuery) ||
                                        normalizeText(element.contentDescription)
                                                .contains(normQuery)
                        }
                        ?.let {
                                return it
                        }

                // 3. HintText match
                eligible
                        .find { element -> element.hintText.contains(normQuery, ignoreCase = true) }
                        ?.let {
                                return it
                        }

                // 4. ResourceId match (Nejnižší priorita - brání kontejnerům "sežrat" klik)
                eligible
                        .find { element ->
                                element.resourceId.contains(normQuery, ignoreCase = true)
                        }
                        ?.let {
                                return it
                        }

                // 5. AllSearchableText
                eligible.find { element -> element.allSearchableText.contains(normQuery) }?.let {
                        return it
                }

                // 4. App Slang Resolution fallback (např. "fotak" -> rozliší na
                // "com.sec.android.app.camera", získá label "Fotoaparát" -> hledá "fotoaparat")
                val appEntry = AppResolutionEngine.resolveEntry(query)
                if (appEntry != null) {
                        val appLabelNorm = normalizeText(appEntry.label)
                        if (appLabelNorm.isNotEmpty() && appLabelNorm != normQuery) {
                                eligible
                                        .find { element ->
                                                normalizeText(element.text) == appLabelNorm ||
                                                        normalizeText(element.contentDescription) ==
                                                                appLabelNorm ||
                                                        element.allSearchableText.contains(
                                                                appLabelNorm
                                                        )
                                        }
                                        ?.let {
                                                Log.i(
                                                        TAG,
                                                        "⚡ App Slang resolved: '$query' -> '${appEntry.label}'"
                                                )
                                                return it
                                        }
                        }
                }

                return null
        }

        // ==================== TEXT INPUT MATCHER ====================

        /**
         * Tier 1: Okamžitý text input — "zadej ahoj" → INPUT_TEXT("ahoj").
         *
         * Funguje jako Google Voice Access / Samsung Voice Control:
         * - Rozpozná prefix ("zadej", "napiš", "vlož", "vyplň", "type")
         * - Extrahuje text za prefixem
         * - Vrátí INPUT_TEXT akci BEZ targetId (IME injektuje do fokusovaného pole)
         *
         * Pokud IME není aktivní, ActionExecutor automaticky najde editable element.
         */
        private fun matchInput(normalized: String): AgentAction? {
                val inputPrefixes =
                        listOf(
                                "napis ",
                                "vloz ",
                                "vypln ",
                                "zadej ",
                                "hod tam ",
                                "type ",
                                "enter ",
                                "napsat ",
                                "vlozit ",
                                "zadat ",
                                "vyplnit ",
                                "text "
                        )

                for (prefix in inputPrefixes) {
                        if (normalized.startsWith(prefix)) {
                                val remainder = normalized.removePrefix(prefix).trim()
                                if (remainder.isEmpty()) continue

                                // === Sub-patterny: kvalifikátory mění typ akce ===
                                // "zadej adresu seznam.cz" → OPEN_URL("seznam.cz")
                                val urlQualifiers =
                                        listOf(
                                                "adresu ",
                                                "url ",
                                                "webovou adresu ",
                                                "stranku ",
                                                "web "
                                        )
                                for (q in urlQualifiers) {
                                        if (remainder.startsWith(q)) {
                                                val url = remainder.removePrefix(q).trim()
                                                if (url.isNotEmpty()) {
                                                        Log.i(
                                                                TAG,
                                                                "⚡ Tier 1 URL sub-pattern: \"$url\""
                                                        )
                                                        return AgentAction(
                                                                thought =
                                                                        "Otevírám adresu \"$url\"",
                                                                type = ActionType.OPEN_URL,
                                                                inputText = url
                                                        )
                                                }
                                        }
                                }

                                // "zadej heslo X" / "zadej text X" / "zadej číslo X" →
                                // INPUT_TEXT("X")
                                val textQualifiers =
                                        listOf(
                                                "heslo ",
                                                "text ",
                                                "cislo ",
                                                "jmeno ",
                                                "nazev ",
                                                "email "
                                        )
                                for (q in textQualifiers) {
                                        if (remainder.startsWith(q)) {
                                                val text = remainder.removePrefix(q).trim()
                                                if (text.isNotEmpty()) {
                                                        Log.i(
                                                                TAG,
                                                                "⚡ Tier 1 Input sub-pattern ($q): \"$text\""
                                                        )
                                                        return AgentAction(
                                                                thought = "Zadávám $q\"$text\"",
                                                                type = ActionType.INPUT_TEXT,
                                                                targetId = null,
                                                                inputText = text
                                                        )
                                                }
                                        }
                                }

                                // Přímý text bez kvalifikátoru → INPUT_TEXT
                                Log.i(TAG, "⚡ Tier 1 Input match: \"$remainder\"")
                                return AgentAction(
                                        thought = "Zadávám text \"$remainder\"",
                                        type = ActionType.INPUT_TEXT,
                                        targetId = null,
                                        inputText = remainder
                                )
                        }
                }

                return null
        }

        // ==================== COMPOUND COMMAND SPLITTER ====================

        /**
         * Compound příkazy: "otevři chrome, zadej adresu seznam.cz" Splituje na separátorech: ',' /
         * ' a potom ' / ' pak ' / ' potom '
         *
         * Vrátí seznam akcí pro sekvenční provedení, nebo null pokud compound není.
         * AgentCoordinator provede akce sekvenčně s delay 500ms mezi kroky.
         */
        private var pendingCompoundActions: List<AgentAction>? = null

        fun consumePendingActions(): List<AgentAction>? {
                val actions = pendingCompoundActions
                pendingCompoundActions = null
                return actions
        }

        private fun matchCompound(normalized: String): List<AgentAction>? {
                // Separátory pro compound příkazy (včetně '-', '/', ' a ')
                val separators =
                        listOf(
                                ", ", // čárka: "otevři chrome, zadej X"
                                " a potom ", // "otevři chrome a potom zadej X"
                                " a pak ", // "jdi domů a pak otevři..."
                                " a ", // OPRAVA: "zapni wifi a bluetooth", "jdi domů a otevři
                                // nastavení"
                                " potom ", // "otevři X potom Y"
                                " pak ", // "otevři X pak Y"
                                " - ", // pomlčka: "otevři X - napiš Y"
                                " / " // lomítko: "otevři X / nastav Y"
                        )

                var parts: List<String>? = null
                for (sep in separators) {
                        if (normalized.contains(sep)) {
                                parts =
                                        normalized.split(sep).map { it.trim() }.filter {
                                                it.isNotEmpty()
                                        }
                                break
                        }
                }

                if (parts == null || parts.size < 2) return null

                // Zkusíme matchnout každou část jako samostatnou akci
                val actions = mutableListOf<AgentAction>()
                for (part in parts) {
                        // PRE-CHECK: URL detekce PŘED matchSingleCommand
                        if (looksLikeUrl(part)) {
                                Log.i(TAG, "⚡ Compound URL pre-check: '$part' → OPEN_URL")
                                actions.add(
                                        AgentAction(
                                                thought = "Otevírám web: $part",
                                                type = ActionType.OPEN_URL,
                                                inputText = part
                                        )
                                )
                                continue
                        }

                        // Tier 1: přímý pattern match
                        val action = matchSingleCommand(part)
                        if (action != null) {
                                actions.add(action)
                        } else {
                                // Tier 1.5: implicit intent match (deferred — bude resolvován přes
                                // SmartClick/SmartInput)
                                val deferred = matchImplicitIntent(part)
                                if (deferred != null) {
                                        actions.add(deferred)
                                        Log.i(
                                                TAG,
                                                "⚡ Compound deferred: '$part' → ${deferred.type} (will resolve via MacroEngine)"
                                        )
                                } else {
                                        // Opravdu neznámý příkaz → LLM pipeline
                                        Log.d(
                                                TAG,
                                                "Compound part '$part' has no Tier 1/1.5 match — aborting compound"
                                        )
                                        return null
                                }
                        }
                }

                if (actions.size >= 2) {
                        Log.i(
                                TAG,
                                "⚡ Compound command: ${actions.size} actions — ${actions.map { it.type }}"
                        )
                        // Uloží zbylé akce pro sekvenční provedení
                        pendingCompoundActions = actions.drop(1)
                        return actions
                }

                return null
        }

        /**
         * Matchne jeden příkaz bez compound logiky. Používá se uvnitř matchCompound() pro každou
         * část.
         */
        private fun matchSingleCommand(normalized: String): AgentAction? {
                if (normalized.isEmpty()) return null

                matchHome(normalized)?.let {
                        return it
                }
                matchBack(normalized)?.let {
                        return it
                }
                matchNotifications(normalized)?.let {
                        return it
                }
                matchRecentApps(normalized)?.let {
                        return it
                }
                matchVolumeUp(normalized)?.let {
                        return it
                }
                matchVolumeDown(normalized)?.let {
                        return it
                }
                matchBrightnessUp(normalized)?.let {
                        return it
                }
                matchBrightnessDown(normalized)?.let {
                        return it
                }
                matchSwipeLeft(normalized)?.let {
                        return it
                }
                matchSwipeRight(normalized)?.let {
                        return it
                }
                matchSwipeUp(normalized)?.let {
                        return it
                }
                matchSwipeDown(normalized)?.let {
                        return it
                }
                matchScrollDown(normalized)?.let {
                        return it
                }
                matchScrollUp(normalized)?.let {
                        return it
                }
                matchScreenshot(normalized)?.let {
                        return it
                }
                matchLockScreen(normalized)?.let {
                        return it
                }
                matchQuickSettings(normalized)?.let {
                        return it
                }
                matchSplitScreen(normalized)?.let {
                        return it
                }
                matchPowerDialog(normalized)?.let {
                        return it
                }
                matchLongPress(normalized)?.let {
                        return it
                }
                matchDoubleTap(normalized)?.let {
                        return it
                }
                matchRapidTap(normalized)?.let {
                        return it
                }
                matchMediaPlayPause(normalized)?.let {
                        return it
                }
                matchMediaNext(normalized)?.let {
                        return it
                }
                matchMediaPrevious(normalized)?.let {
                        return it
                }
                matchInput(normalized)?.let {
                        return it
                }
                matchCall(normalized)?.let {
                        return it
                }
                matchSms(normalized)?.let {
                        return it
                }
                matchWeb(normalized)?.let {
                        return it
                }
                matchEmail(normalized)?.let {
                        return it
                }
                matchKillApp(normalized)?.let {
                        return it
                }
                matchAnswerCall(normalized)?.let {
                        return it
                }
                matchRejectCall(normalized)?.let {
                        return it
                }
                matchToggle(normalized)?.let {
                        return it
                }
                matchStandaloneSettings(normalized)?.let {
                        return it
                }
                matchSettings(normalized)?.let {
                        return it
                }
                matchOpenApp(normalized)?.let {
                        return it
                }

                return null
        }

        // ==================== URL DETECTION HELPER ====================

        /**
         * Detekce zda text vypadá jako URL. Rozpozná: "seznam.cz", "google.com", "http://...",
         * "www...."
         */
        private fun looksLikeUrl(text: String): Boolean {
                val t = text.lowercase().trim()
                // URL nikdy neobsahuje mezery
                if (t.contains(' ')) return false
                // Explicitní URL scheme
                if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www."))
                        return true
                // TLD detekce — text končí doménovou koncovkou nebo obsahuje TLD s / za ní
                val tlds =
                        listOf(
                                // Regionální
                                ".cz",
                                ".sk",
                                ".eu",
                                ".de",
                                ".at",
                                ".pl",
                                ".hu",
                                ".uk",
                                ".me",
                                ".co",
                                // Tech & AIRPLANE
                                ".ai",
                                ".io",
                                ".dev",
                                ".app",
                                ".tech",
                                ".cloud",
                                ".systems",
                                ".network",
                                ".digital",
                                ".data",
                                ".software",
                                ".host",
                                ".online",
                                ".link",
                                ".click",
                                ".run",
                                // Obecné
                                ".com",
                                ".org",
                                ".net",
                                ".info",
                                ".biz",
                                ".name",
                                ".pro",
                                ".xyz",
                                ".icu",
                                ".top",
                                ".site",
                                ".website",
                                ".space",
                                ".one",
                                ".plus",
                                ".vip",
                                ".fun",
                                // Business & Marketing
                                ".shop",
                                ".store",
                                ".marketing",
                                ".events",
                                ".agency",
                                ".solutions",
                                ".services",
                                ".company",
                                ".enterprise",
                                ".inc",
                                ".ltd",
                                ".group",
                                ".team",
                                ".support",
                                ".expert",
                                ".contact",
                                ".email",
                                ".center",
                                ".press",
                                ".news",
                                ".media",
                                ".global",
                                ".world",
                                ".blog",
                                ".studio",
                                ".design",
                                ".art",
                                ".photography",
                                ".live",
                                ".chat",
                                ".community",
                                ".today",
                                // Instituce
                                ".edu",
                                ".gov"
                        )
                return tlds.any { tld -> t.endsWith(tld) || t.contains("$tld/") }
        }

        // ==================== ACTION FACTORY ====================

        private fun createAction(type: ActionType, thought: String): AgentAction {
                Log.i(TAG, "⚡ Direct command match: $type — \"$thought\"")
                return AgentAction(
                        thought = thought,
                        type = type,
                        targetId = null,
                        inputText = null
                )
        }

        // ==================== IMPLICIT INTENT MATCHER (Tier 1.5) ====================

        /**
         * Rozpozná záměr uživatele BEZ screen contextu. Vrací deferred akci s labels pro
         * SmartClick/SmartInput v MacroEngine.
         *
         * Použití: compound command vyřešení těch částí, které nemá Tier 1 match.
         *
         * Format: thought="[DEFERRED:intent]" inputText="label1|label2|label3" AgentCoordinator
         * toto rozpozná a konvertuje na MacroAction.SmartClick/SmartInput.
         */
        fun matchImplicitIntent(normalized: String): AgentAction? {
                if (normalized.isEmpty()) return null

                // Definice intentů: příkazů → labels pro hledání na obrazovce
                data class ImplicitIntent(
                        val commands: List<String>, // Příkazy uživatele
                        val labels: List<String>, // Text/contentDescription k hledání
                        val isInput: Boolean = false, // true = hledej textové pole (SmartInput)
                        val intentName: String // Lidský popis
                )

                val intents =
                        listOf(
                                // === Sociální interakce ===
                                ImplicitIntent(
                                        // CRITICAL: commands MUSÍ být BEZ diakritiky —
                                        // normalizeText() ji
                                        // strippuje!
                                        commands =
                                                listOf(
                                                        "lajkni",
                                                        "like",
                                                        "dej like",
                                                        "lajk",
                                                        "dej lajk",
                                                        "pridej lajk",
                                                        "osrdickuj",
                                                        "olajkuj",
                                                        "laikni to",
                                                        "lajkni to",
                                                        "to se mi libi",
                                                        "libi se mi",
                                                        "palec nahoru",
                                                        "dej srdicko",
                                                        "osrdickovat",
                                                        "olikuj",
                                                        "olajkuj video",
                                                        "olajkuj",
                                                        "olajkuj to",
                                                        "olijkuj to video",
                                                        "olajkuj to video",
                                                        "srdicko",
                                                        "osrdickuj to video",
                                                        "osrdickuj to",
                                                        "srdce"
                                                ),
                                        labels =
                                                listOf(
                                                        // CZ labels
                                                        "like",
                                                        "líbí",
                                                        "to se mi líbí",
                                                        "osrdíčkovat",
                                                        "lajkovat",
                                                        "lajkovat video",
                                                        "olikovat",
                                                        "olajkovat",
                                                        "olajkuj",
                                                        "olajkuj video",
                                                        "olikuj",
                                                        "olikuj video",
                                                        "olikuj to",
                                                        // EN contentDescription z accessibility
                                                        // tree
                                                        // (YouTube/TikTok/FB/IG/X)
                                                        "like this video",
                                                        "like video",
                                                        "like this",
                                                        "love",
                                                        "react",
                                                        "heart",
                                                        "heartme",
                                                        // Resource-ID patterny (partial match ve
                                                        // findSmartElement L3)
                                                        "like_button",
                                                        "btn_like",
                                                        "ic_like",
                                                        "like_icon",
                                                        // Emoji
                                                        "👍",
                                                        "❤",
                                                        "❤️",
                                                        "♥",
                                                        "♡",
                                                        "🩷",
                                                        "🤍",
                                                        "🖤",
                                                        "💙",
                                                        "💜",
                                                        "🧡"
                                                ),
                                        intentName = "lajk"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "dislike",
                                                        "nelibi",
                                                        "palec dolu",
                                                        "nelibi se mi",
                                                        "dej dislike"
                                                ),
                                        labels =
                                                listOf(
                                                        "dislike",
                                                        "nelíbí",
                                                        "dislike this video",
                                                        "dislike_button",
                                                        "btn_dislike",
                                                        "👎"
                                                ),
                                        intentName = "dislike"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "komentuj",
                                                        "komentar",
                                                        "pridej komentar",
                                                        "add comment",
                                                        "comment",
                                                        "napis komentar",
                                                        "dej komentar"
                                                ),
                                        labels =
                                                listOf(
                                                        // CZ
                                                        "komentář",
                                                        "přidat komentář",
                                                        // EN contentDescription (YT/TT/FB/IG/X)
                                                        "comment",
                                                        "comments",
                                                        "add a comment",
                                                        "view comments",
                                                        "write a comment",
                                                        "reply",
                                                        "view all comments",
                                                        // Resource-ID patterny
                                                        "comment_button",
                                                        "btn_comment",
                                                        "ic_comment",
                                                        // Emoji
                                                        "💬"
                                                ),
                                        intentName = "komentář"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "sdilej",
                                                        "share",
                                                        "posdilej",
                                                        "posilej",
                                                        "repost",
                                                        "zasdilej",
                                                        "zasdilej to",
                                                        "dej sdilet",
                                                        "posli dal"
                                                ),
                                        labels =
                                                listOf(
                                                        // CZ
                                                        "sdílet",
                                                        "sdílení",
                                                        // EN contentDescription (YT/TT/FB/IG/X)
                                                        "share",
                                                        "share video",
                                                        "share to",
                                                        "send to",
                                                        "repost",
                                                        "retweet",
                                                        "share this",
                                                        // Resource-ID patterny
                                                        "share_button",
                                                        "btn_share",
                                                        "ic_share",
                                                        // Emoji/Symboly
                                                        "🔗",
                                                        "➔",
                                                        "->"
                                                ),
                                        intentName = "sdílení"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "ulozit",
                                                        "save",
                                                        "oblibene",
                                                        "favorite",
                                                        "zalozka",
                                                        "bookmark",
                                                        "pridat do oblibenych",
                                                        "uloz to",
                                                        "uloz",
                                                        "dej ulozit"
                                                ),
                                        labels =
                                                listOf(
                                                        // CZ
                                                        "uložit",
                                                        "oblíbené",
                                                        "ukládání",
                                                        "uložit do seznamu",
                                                        // EN contentDescription (YT/TT/FB/IG)
                                                        "save",
                                                        "save to playlist",
                                                        "add to favorites",
                                                        "bookmark",
                                                        "bookmark this",
                                                        "collect",
                                                        "favorite",
                                                        "save to watch later",
                                                        "add to library",
                                                        // Resource-ID patterny
                                                        "save_button",
                                                        "btn_save",
                                                        "bookmark_button",
                                                        "btn_bookmark",
                                                        "sym_action_bookmark",
                                                        // Emoji
                                                        "★",
                                                        "🔖"
                                                ),
                                        intentName = "uložení"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "odpovez",
                                                        "reply",
                                                        "reaguj",
                                                        "odpoved",
                                                        "dej odpoved"
                                                ),
                                        labels =
                                                listOf(
                                                        "reply",
                                                        "odpovědět",
                                                        "reagovat",
                                                        "odpověď",
                                                        "reply to",
                                                        "reply_button",
                                                        "btn_reply"
                                                ),
                                        intentName = "odpověď"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "odeslat",
                                                        "send",
                                                        "poslat",
                                                        "odeslej",
                                                        "post",
                                                        "odesli to",
                                                        "posli to"
                                                ),
                                        labels =
                                                listOf(
                                                        "send",
                                                        "odeslat",
                                                        "poslat",
                                                        "post",
                                                        "send message",
                                                        "paper plane",
                                                        "send_button",
                                                        "btn_send",
                                                        "ic_send",
                                                        "➤",
                                                        "✉"
                                                ),
                                        intentName = "odeslání"
                                ),
                                // === Navigace v UI ===
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "hledej",
                                                        "search",
                                                        "vyhledej",
                                                        "najdi",
                                                        "hledat",
                                                        "vyhledat"
                                                ),
                                        labels =
                                                listOf(
                                                        "search",
                                                        "hledat",
                                                        "vyhledávání",
                                                        "🔍",
                                                        "hledej"
                                                ),
                                        intentName = "hledání"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "menu",
                                                        "nabidka",
                                                        "hamburger",
                                                        "tri tecky",
                                                        "vice",
                                                        "more",
                                                        "moznosti"
                                                ),
                                        labels =
                                                listOf(
                                                        "menu",
                                                        "more",
                                                        "více",
                                                        "⋮",
                                                        "☰",
                                                        "more options",
                                                        "další možnosti"
                                                ),
                                        intentName = "menu"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "smaz",
                                                        "delete",
                                                        "odstran",
                                                        "vymaz",
                                                        "smazat",
                                                        "vymazat"
                                                ),
                                        labels =
                                                listOf(
                                                        "delete",
                                                        "smazat",
                                                        "odstranit",
                                                        "🗑",
                                                        "remove"
                                                ),
                                        intentName = "smazání"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "potvrdit",
                                                        "ok",
                                                        "ano",
                                                        "confirm",
                                                        "yes",
                                                        "souhlasim",
                                                        "hotovo",
                                                        "done"
                                                ),
                                        labels =
                                                listOf(
                                                        "ok",
                                                        "potvrdit",
                                                        "ano",
                                                        "confirm",
                                                        "yes",
                                                        "souhlasím",
                                                        "přijmout",
                                                        "hotovo",
                                                        "done"
                                                ),
                                        intentName = "potvrzení"
                                ),
                                // === Média ovládání (YouTube/TikTok/Spotify) ===
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "play",
                                                        "pause",
                                                        "prehrat",
                                                        "pauza",
                                                        "stop",
                                                        "spustit",
                                                        "spust",
                                                        "pozastavit"
                                                ),
                                        labels =
                                                listOf(
                                                        "play",
                                                        "pause",
                                                        "přehrát",
                                                        "pozastavit",
                                                        "stop",
                                                        "resume",
                                                        // EN contentDescription (YouTube)
                                                        "play video",
                                                        "pause video",
                                                        "play/pause",
                                                        "player_control_play_pause",
                                                        // Emoji
                                                        "▶",
                                                        "⏸"
                                                ),
                                        intentName = "přehrávání"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "dalsi",
                                                        "next",
                                                        "preskoc",
                                                        "skip",
                                                        "dalsi video",
                                                        "pristi"
                                                ),
                                        labels =
                                                listOf(
                                                        "next",
                                                        "další",
                                                        "skip",
                                                        "přeskočit",
                                                        "⏭",
                                                        "next video"
                                                ),
                                        intentName = "další"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "zrusit",
                                                        "cancel",
                                                        "ne",
                                                        "zrus",
                                                        "storno",
                                                        "zavrit",
                                                        "zavri"
                                                ),
                                        labels =
                                                listOf(
                                                        "cancel",
                                                        "zrušit",
                                                        "ne",
                                                        "storno",
                                                        "zavřít",
                                                        "close"
                                                ),
                                        intentName = "zrušení"
                                ),
                                // === Textový vstup (SmartInput) ===
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "napis zpravu",
                                                        "zprava",
                                                        "message",
                                                        "posli zpravu"
                                                ),
                                        labels =
                                                listOf(
                                                        "zpráva",
                                                        "message",
                                                        "napište zprávu",
                                                        "type a message"
                                                ),
                                        isInput = true,
                                        intentName = "zpráva"
                                ),

                                // === SOCIAL MEDIA SmartClick (Tier 1.5 → MacroEngine SmartClick)
                                // ===
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "lajkni",
                                                        "like",
                                                        "dej like",
                                                        "dej srdce",
                                                        "to se mi libi",
                                                        "dej lajk",
                                                        "palec nahoru"
                                                ),
                                        labels =
                                                listOf(
                                                        "like",
                                                        "líbí",
                                                        "❤",
                                                        "❤️",
                                                        "👍",
                                                        "Like",
                                                        "like_button",
                                                        "btn_like",
                                                        "item_act_like",
                                                        "iv_like",
                                                        "video_like_button",
                                                        "heart",
                                                        "btn_heart",
                                                        "ic_like"
                                                ),
                                        intentName = "like"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "dislajkni",
                                                        "dislike",
                                                        "palec dolu",
                                                        "to se mi nelibi",
                                                        "dej dislike"
                                                ),
                                        labels =
                                                listOf(
                                                        "dislike",
                                                        "👎",
                                                        "dislike_button",
                                                        "btn_dislike",
                                                        "item_act_dislike",
                                                        "ic_dislike"
                                                ),
                                        intentName = "dislike"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "komentuj",
                                                        "komentar",
                                                        "pridej komentar",
                                                        "zobraz komentare",
                                                        "comment"
                                                ),
                                        labels =
                                                listOf(
                                                        "komentář",
                                                        "comment",
                                                        "💬",
                                                        "Comment",
                                                        "item_act_comment",
                                                        "btn_comment",
                                                        "video_comment_button"
                                                ),
                                        intentName = "komentář"
                                ),
                                ImplicitIntent(
                                        commands = listOf("sdilej", "share", "posdilej"),
                                        labels =
                                                listOf(
                                                        "sdílet",
                                                        "share",
                                                        "Share",
                                                        "🔗",
                                                        "item_act_share",
                                                        "btn_share",
                                                        "share_button",
                                                        "ic_share"
                                                ),
                                        intentName = "sdílení"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "followuj",
                                                        "follow",
                                                        "sleduj",
                                                        "odebírej",
                                                        "sledovat"
                                                ),
                                        labels =
                                                listOf(
                                                        "follow",
                                                        "Follow",
                                                        "Sledovat",
                                                        "Odebírat",
                                                        "btn_follow",
                                                        "follow_button",
                                                        "item_act_follow",
                                                        "profile_follow_btn"
                                                ),
                                        intentName = "follow"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "subscribuj",
                                                        "subscribe",
                                                        "odebírej kanal",
                                                        "prihlasit k odberu"
                                                ),
                                        labels =
                                                listOf(
                                                        "subscribe",
                                                        "Subscribe",
                                                        "Odebírat",
                                                        "subscribe_button",
                                                        "btn_subscribe",
                                                        "SUBSCRIBE"
                                                ),
                                        intentName = "subscribe"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "uloz",
                                                        "save",
                                                        "ulozit",
                                                        "uloz video",
                                                        "bookmark"
                                                ),
                                        labels =
                                                listOf(
                                                        "uložit",
                                                        "save",
                                                        "Save",
                                                        "🔖",
                                                        "btn_save",
                                                        "item_act_collect",
                                                        "item_act_save"
                                                ),
                                        intentName = "uložení"
                                ),
                                // === KAMERA SmartClick ===
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "fot",
                                                        "vyfot",
                                                        "fotka",
                                                        "udelej foto",
                                                        "take photo",
                                                        "capture"
                                                ),
                                        labels =
                                                listOf(
                                                        "Pořidit fotografii",
                                                        "shutter_button",
                                                        "btn_shutter",
                                                        "camera_button",
                                                        "CaptureButton"
                                                ),
                                        intentName = "focení"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "prepni kameru",
                                                        "selfie",
                                                        "otoc kameru",
                                                        "flip camera"
                                                ),
                                        labels =
                                                listOf(
                                                        "přepnout fotoaparát",
                                                        "switch_camera",
                                                        "camera_switch_button",
                                                        "btn_toggle_camera",
                                                        "ic_flip"
                                                ),
                                        intentName = "přepnutí kamery"
                                ),
                                ImplicitIntent(
                                        commands =
                                                listOf(
                                                        "blesk",
                                                        "flash",
                                                        "zapni blesk",
                                                        "vypni blesk"
                                                ),
                                        labels =
                                                listOf(
                                                        "flash",
                                                        "blesk",
                                                        "flash_button",
                                                        "btn_flash",
                                                        "ic_flash",
                                                        "FlashButton"
                                                ),
                                        intentName = "blesk"
                                )
                        )

                for (intent in intents) {
                        // Flexibilní matching: exact → startsWith → word boundary
                        // OPRAVA: normalized.contains(cmd) nahrazen word boundary regexem
                        // — zabrání false positive: "play" uvnitř "replay", "display" atd.
                        val matchedCmd =
                                intent.commands.find { cmd ->
                                        normalized == cmd ||
                                                normalized.startsWith(cmd + " ") ||
                                                normalized.startsWith(cmd) &&
                                                        normalized.length == cmd.length ||
                                                " $normalized ".contains(" $cmd ") ||
                                                normalized.endsWith(" $cmd") ||
                                                Regex("""\b${Regex.escape(cmd)}\b""")
                                                        .containsMatchIn(normalized)
                                }
                        Log.d(
                                TAG,
                                "🔍 ImplicitIntent check: intent='${intent.intentName}' commands=${intent.commands.take(3)} matched=${matchedCmd != null}"
                        )
                        if (matchedCmd != null) {
                                // Extrahuj text za příkazem (např. "napis zpravu ahoj" → "ahoj")
                                val afterCmd = normalized.removePrefix(matchedCmd).trim()

                                if (intent.isInput && afterCmd.isNotEmpty()) {
                                        // SmartInput: máme text k zadání
                                        Log.i(
                                                TAG,
                                                "🎯 Tier 1.5 Implicit SmartInput: '${intent.intentName}' text='$afterCmd'"
                                        )
                                        return AgentAction(
                                                thought =
                                                        "[DEFERRED:smartinput:${intent.intentName}]",
                                                type = ActionType.INPUT_TEXT,
                                                targetId = null,
                                                inputText =
                                                        "${intent.labels.joinToString("|")}|||$afterCmd"
                                                // Format: "labels|||text" — rozparšuje
                                                // AgentCoordinator
                                                )
                                } else {
                                        // SmartClick: klikni na element
                                        Log.i(
                                                TAG,
                                                "🎯 Tier 1.5 Implicit SmartClick: '${intent.intentName}'"
                                        )
                                        return AgentAction(
                                                thought =
                                                        "[DEFERRED:smartclick:${intent.intentName}]",
                                                type = ActionType.CLICK,
                                                targetId = null,
                                                inputText = intent.labels.joinToString("|")
                                                // Labels oddelené '|' — rozparšuje AgentCoordinator
                                                )
                                }
                        }
                }

                return null
        }
}
