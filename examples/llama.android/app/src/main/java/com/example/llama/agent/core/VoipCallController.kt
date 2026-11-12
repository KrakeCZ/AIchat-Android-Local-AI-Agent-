package com.example.llama.agent.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * VoipCallController — Critical Infrastructure for VoIP Calls
 *
 * Zodpovědnost: Detekce a přijímání/odmítání hovorů aplikací třetích stran
 * (WhatsApp, Discord, Signal, Messenger, Viber, Telegram).
 *
 * VoIP hovory NEJSOU standardní telefonní hovory — jsou to Activities/Notifications.
 * Nemají standardní Telecom API pro "zvednutí zvenčí".
 *
 * Strategie (Multi-modal, Profile-based):
 * 1. Detekce balíčku (Package Match) → najdi profil
 * 2. Detekce stavu "Příchozí hovor" (Keyword Scan v UI tree)
 * 3. Exekuce:
 *    Strategy A (Semantic): contentDescription match → ACTION_CLICK
 *    Strategy B (Text): viditelný text match → ACTION_CLICK
 *    Strategy C (Parent Click): clickable rodič elementu
 *    Strategy D (Heuristic Gesture): Swipe Up / Swipe Right fallback
 *
 * Google Play Compliance:
 * - Accessibility Declaration: "Assists users with motor impairments
 *   to answer VoIP calls via voice commands."
 * - Nepotřebuje ANSWER_PHONE_CALLS permission
 * - 100% přes AccessibilityService
 *
 * DevOps Note: V produkci by `profiles` měly být stahovány z Remote Config
 * (Firebase/JSON) pro rychlou reakci na UI změny bez app update.
 */
class VoipCallController(private val service: AccessibilityService) {

    companion object {
        private const val TAG = "VoipCallController"
    }

    // ==================== PROFILE DEFINITION ====================

    /**
     * Definice profilu pro VoIP aplikaci.
     * Každá app má své klíčové slova a preferovanou akci.
     */
    data class VoipProfile(
        val packageName: String,
        val incomingKeywords: List<String>,  // Texty indikující příchozí hovor
        val answerKeywords: List<String>,    // Text/Desc pro tlačítko přijetí
        val rejectKeywords: List<String>,    // Text/Desc pro tlačítko odmítnutí
        val answerAction: AnswerAction,      // Preferovaná answer akce (fallback)
        val rejectAction: AnswerAction       // Preferovaná reject akce (fallback)
    )

    enum class AnswerAction {
        CLICK_BUTTON,   // Najdi a klikni na tlačítko
        SWIPE_UP,       // Vertikální swipe zespodu nahoru
        SWIPE_RIGHT     // Horizontální swipe zleva doprava
    }

    // ==================== APP PROFILES ====================
    // DevOps: V produkci stahovat z Remote Config (Firebase/JSON)!

    private val profiles = listOf(
        // WhatsApp
        VoipProfile(
            packageName = "com.whatsapp",
            incomingKeywords = listOf(
                "incoming voice call", "incoming video call",
                "příchozí hovor", "whatsapp call", "příchozí videohovor"
            ),
            answerKeywords = listOf("answer", "přijmout", "accept", "zvednout"),
            rejectKeywords = listOf("decline", "odmítnout", "reject", "zavěsit"),
            answerAction = AnswerAction.SWIPE_UP,
            rejectAction = AnswerAction.CLICK_BUTTON
        ),
        // Discord
        VoipProfile(
            packageName = "com.discord",
            incomingKeywords = listOf("incoming call", "příchozí hovor"),
            answerKeywords = listOf("join", "accept", "přijmout", "join call"),
            rejectKeywords = listOf("decline", "dismiss", "odmítnout", "ignore"),
            answerAction = AnswerAction.CLICK_BUTTON,
            rejectAction = AnswerAction.CLICK_BUTTON
        ),
        // Messenger (Facebook)
        VoipProfile(
            packageName = "com.facebook.orca",
            incomingKeywords = listOf(
                "incoming call", "příchozí hovor", "messenger audio",
                "messenger video"
            ),
            answerKeywords = listOf("answer", "přijmout", "accept"),
            rejectKeywords = listOf("decline", "odmítnout", "reject"),
            answerAction = AnswerAction.CLICK_BUTTON,
            rejectAction = AnswerAction.CLICK_BUTTON
        ),
        // Signal
        VoipProfile(
            packageName = "org.thoughtcrime.securesms",
            incomingKeywords = listOf("signal call", "incoming call", "příchozí hovor"),
            answerKeywords = listOf("answer", "přijmout", "accept"),
            rejectKeywords = listOf("decline", "odmítnout", "reject"),
            answerAction = AnswerAction.SWIPE_UP,
            rejectAction = AnswerAction.CLICK_BUTTON
        ),
        // Telegram
        VoipProfile(
            packageName = "org.telegram.messenger",
            incomingKeywords = listOf("incoming call", "příchozí hovor", "telegram call"),
            answerKeywords = listOf("answer", "přijmout", "accept"),
            rejectKeywords = listOf("decline", "odmítnout", "reject", "cancel"),
            answerAction = AnswerAction.CLICK_BUTTON,
            rejectAction = AnswerAction.CLICK_BUTTON
        ),
        // Viber
        VoipProfile(
            packageName = "com.viber.voip",
            incomingKeywords = listOf("incoming call", "příchozí hovor", "viber call"),
            answerKeywords = listOf("answer", "přijmout", "accept"),
            rejectKeywords = listOf("decline", "odmítnout", "reject"),
            answerAction = AnswerAction.SWIPE_RIGHT,
            rejectAction = AnswerAction.CLICK_BUTTON
        )
    )

    // ==================== PUBLIC API ====================

    /**
     * Hlavní metoda — detekce a přijetí/odmítnutí VoIP hovoru.
     *
     * Volána z ActionExecutor při ANSWER_CALL / REJECT_CALL.
     * Prohledá aktuální UI tree, najde profil, a provede akci.
     *
     * @param rootNode Kořenový AccessibilityNodeInfo (rootInActiveWindow)
     * @param isAnswer true = přijmout hovor, false = odmítnout
     * @return true pokud hovor detekován a akce provedena
     */
    suspend fun handleCall(
        rootNode: AccessibilityNodeInfo?,
        isAnswer: Boolean
    ): Boolean = withContext(Dispatchers.Default) {
        if (rootNode == null) {
            Log.w(TAG, "❌ handleCall failed: no root window")
            return@withContext false
        }

        val packageName = rootNode.packageName?.toString()
        if (packageName == null) {
            Log.w(TAG, "❌ handleCall failed: no package name")
            return@withContext false
        }

        // 1. Najdi profil pro aktuální aplikaci
        val profile = profiles.find { it.packageName == packageName }

        if (profile != null) {
            // Known VoIP app — použij profil
            Log.i(TAG, "📞 VoIP package match: ${profile.packageName}")

            // 2. Ověř, zda je to skutečně příchozí hovor (keyword scan)
            val isRinging = scanForKeywords(rootNode, profile.incomingKeywords, depth = 0)
            if (isRinging) {
                Log.i(TAG, "📞 VoIP Ringing Detected: ${profile.packageName}")
                return@withContext performAction(rootNode, profile, isAnswer)
            } else {
                // Možná je to jiná obrazovka (chat, settings) — zkusíme generic
                Log.w(TAG, "⚠️ Package match but no ringing keywords found — trying generic")
            }
        }

        // 3. Generic fallback — neznámá app nebo profil nezabral
        Log.i(TAG, "📞 Generic VoIP fallback for package: $packageName")
        return@withContext performGenericAction(rootNode, isAnswer)
    }

    /**
     * Detect-only: Zjistí, zda aktuální obrazovka ukazuje příchozí VoIP hovor.
     * Volána z onAccessibilityEvent pro detekci bez akce.
     *
     * @param rootNode Kořenový node
     * @return Pár (detekován, packageName) nebo null
     */
    fun detectIncomingCall(rootNode: AccessibilityNodeInfo?): Pair<Boolean, String>? {
        if (rootNode == null) return null

        val packageName = rootNode.packageName?.toString() ?: return null
        val profile = profiles.find { it.packageName == packageName } ?: return null

        val isRinging = scanForKeywords(rootNode, profile.incomingKeywords, depth = 0)
        return if (isRinging) Pair(true, packageName) else null
    }

    // ==================== PRIVATE: ACTION EXECUTION ====================

    /**
     * Provedení akce pro známý profil.
     * Strategy: Button Click → Parent Click → Gesture Fallback
     */
    private fun performAction(
        rootNode: AccessibilityNodeInfo,
        profile: VoipProfile,
        isAnswer: Boolean
    ): Boolean {
        val keywords = if (isAnswer) profile.answerKeywords else profile.rejectKeywords
        val fallbackAction = if (isAnswer) profile.answerAction else profile.rejectAction
        val actionLabel = if (isAnswer) "ANSWER" else "REJECT"

        // Strategy A+B: Najdi tlačítko podle contentDescription nebo textu
        val targetNode = findNodeByKeywords(rootNode, keywords, depth = 0)

        if (targetNode != null) {
            Log.i(TAG, "✓ Found $actionLabel button: " +
                    "text='${targetNode.text}' desc='${targetNode.contentDescription}'")

            // Strategy A: Přímý click
            if (targetNode.isClickable) {
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.i(TAG, "✓ $actionLabel call — button clicked successfully")
                    return true
                }
            }

            // Strategy C: Clickable rodič
            val parent = targetNode.parent
            if (parent != null && parent.isClickable) {
                val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) {
                    Log.i(TAG, "✓ $actionLabel call — parent button clicked")
                    return true
                }
            }
        }

        // Strategy D: Gesture fallback
        Log.i(TAG, "⚠️ $actionLabel button not found or click failed — " +
                "executing gesture fallback: $fallbackAction")

        return executeGestureFallback(fallbackAction)
    }

    /**
     * Generic akce pro neznámé VoIP aplikace.
     * Používá univerzální keywords (CZ + EN) a swipe up fallback.
     */
    private fun performGenericAction(
        rootNode: AccessibilityNodeInfo,
        isAnswer: Boolean
    ): Boolean {
        val actionLabel = if (isAnswer) "ANSWER" else "REJECT"

        val keywords = if (isAnswer) {
            listOf(
                "answer", "accept", "přijmout", "prijmout", "zvednout",
                "pick up", "odpovědět", "odpovedět"
            )
        } else {
            listOf(
                "decline", "reject", "dismiss", "odmítnout", "odmitnout",
                "zavěsit", "zavesit", "položit", "polozit", "hang up"
            )
        }

        val targetNode = findNodeByKeywords(rootNode, keywords, depth = 0)
        if (targetNode != null) {
            Log.i(TAG, "✓ Generic $actionLabel button found: " +
                    "'${targetNode.text ?: targetNode.contentDescription}'")

            if (targetNode.isClickable) {
                val success = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }

            val parent = targetNode.parent
            if (parent != null && parent.isClickable) {
                val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            }
        }

        // Generic gesture fallback
        return if (isAnswer) {
            Log.i(TAG, "📞 Generic $actionLabel fallback: swipe up")
            executeGestureFallback(AnswerAction.SWIPE_UP)
        } else {
            Log.i(TAG, "📞 Generic $actionLabel: no button found, no swipe fallback for reject")
            false
        }
    }

    // ==================== PRIVATE: UI TREE SCANNING ====================

    /**
     * Rekurzivní scan UI tree pro klíčová slova.
     * Hledá v textu a contentDescription všech nodů.
     */
    private fun scanForKeywords(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        depth: Int
    ): Boolean {
        if (depth > 15) return false

        val text = node.text?.toString()?.lowercase(Locale.ROOT)
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)

        if (keywords.any { k -> text?.contains(k) == true || desc?.contains(k) == true }) {
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (scanForKeywords(child, keywords, depth + 1)) {
                return true
            }
        }
        return false
    }

    /**
     * Najde konkrétní interakční uzel podle keywords.
     * Priorita: contentDescription → text → viewIdResourceName
     */
    private fun findNodeByKeywords(
        node: AccessibilityNodeInfo,
        keywords: List<String>,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 15) return null

        val text = node.text?.toString()?.lowercase(Locale.ROOT)
        val desc = node.contentDescription?.toString()?.lowercase(Locale.ROOT)
        val id = node.viewIdResourceName?.lowercase(Locale.ROOT)

        // Priorita: Content Description (často 'Answer' i v CZ lokaci) → Text → ID
        if (keywords.any { k ->
                desc?.contains(k) == true ||
                text?.contains(k) == true ||
                (id != null && keywords.any { kw -> id.contains(kw) })
            }) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeByKeywords(child, keywords, depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ==================== PRIVATE: GESTURE EXECUTION ====================

    /**
     * Provede gesture fallback (swipe) přes dispatchGesture.
     */
    private fun executeGestureFallback(action: AnswerAction): Boolean {
        return when (action) {
            AnswerAction.SWIPE_UP -> performSwipe(
                startYRatio = 0.8f, endYRatio = 0.2f,
                startXRatio = 0.5f, endXRatio = 0.5f
            )
            AnswerAction.SWIPE_RIGHT -> performSwipe(
                startYRatio = 0.8f, endYRatio = 0.8f,
                startXRatio = 0.2f, endXRatio = 0.8f
            )
            AnswerAction.CLICK_BUTTON -> false // Click strategie selhala, nic dalšího
        }
    }

    /**
     * Provede swipe gesto přes dispatchGesture.
     * Parametry jsou poměrové (0.0 - 1.0) vůči rozměrům displeje.
     */
    private fun performSwipe(
        startYRatio: Float,
        endYRatio: Float,
        startXRatio: Float,
        endXRatio: Float
    ): Boolean {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val path = Path().apply {
            moveTo(width * startXRatio, height * startYRatio)
            lineTo(width * endXRatio, height * endYRatio)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()

        return service.dispatchGesture(gesture, null, null)
    }
}
