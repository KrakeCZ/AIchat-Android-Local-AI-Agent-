package com.example.llama.agent.core

import com.example.llama.agent.models.ScreenState
import android.util.Log

/**
 * SafetyGuard - Bezpečnostní vrstva pro AI Agent
 *
 * Zodpovědnosti:
 * 1. Blacklist citlivých aplikací (banking, passwords)
 * 2. Loop detection (agent se zacyklí při opakovaných akcích)
 * 3. Privacy protection
 *
 * CRITICAL: Tato komponenta NIKDY nesmí být vypnuta - je to první obranná linie.
 */
class SafetyGuard(
    private val settingsManager: com.example.llama.SettingsManager,
    private val onWarning: ((String) -> Unit)? = null
) {

    companion object {
        private const val TAG = "SafetyGuard"

        // Blacklist citlivých aplikací
        // DŮLEŽITÉ: Toto je důvodný základ - uživatelé mohou přidat vlastní
        private val SENSITIVE_PACKAGES = setOf(
            // ==== SYSTEM APPS ====
            // "com.android.settings",              // System settings (passwords)
            // "com.android.systemui",              // System UI
            //"com.google.android.gms",            // Google Play Services
            "com.android.keychain",              // System keychain
            "com.android.packageinstaller",      // Package installer

            // ==== BANKING - CZECH ====
            "cz.csob.smartbanking",              // ČSOB Smart Banking
            "com.kb.mobilni.app",                // Komerční Banka
            "air.AirApplication9BAE6152050",     // George (Česká spořitelna)
            "eu.inmite.prj.kb.mobilebanka",      // KB Mobilní Banka
            "cz.mbank",                          // mBank
            "cz.airbank.android",                // Air Bank
            "cz.moneta.smart",                   // Moneta SmartBanking
            "cz.rb.app",                         // Raiffeisenbank
            "cz.unicreditbank.bo",               // UniCredit Bank

            // ==== PASSWORD MANAGERS ====
            "com.lastpass.lpandroid",            // LastPass
            "com.onepassword.android",           // 1Password
            "com.dashlane",                      // Dashlane
            "com.agilebits.onepassword",         // 1Password (alt)
            "com.bitwarden.app",                 // Bitwarden
            "keepass2android.keepass2android",   // KeePass2Android

            // ==== PAYMENT APPS ====
            "com.google.android.apps.walletnfcrel",  // Google Wallet
            "com.samsung.android.spay",          // Samsung Pay

            // ==== 2FA / SECURITY ====
            "com.google.android.apps.authenticator2", // Google Authenticator
            "org.fedorahosted.freeotp",          // FreeOTP
            "com.authy.authy",                   // Authy
            "com.azure.authenticator"            // Microsoft Authenticator
        )

    }

    // History posledních UI states (pro loop detection)
    private val stateHistory = ArrayDeque<String>()

    /**
     * Check if package is safe to operate in
     *
     * @return true pokud je safe, false pokud je to sensitive app
     */
    fun isSafePackage(packageName: String): Boolean {
        if (packageName in SENSITIVE_PACKAGES) {
            Log.w(TAG, "🚫 BLOCKED - Sensitive package detected: $packageName")
            onWarning?.invoke("Blokována citlivá aplikace ($packageName)")
            return false
        }

        // VUL-003 FIX: Keyword heuristika nyní BLOKUJE (ne jen warning)
        val lowerPackage = packageName.lowercase()
        val suspiciousKeywords = listOf("bank", "pay", "wallet", "finance", "password", "auth", "authenticator")

        if (suspiciousKeywords.any { lowerPackage.contains(it) }) {
            Log.w(TAG, "🚫 BLOCKED - Keyword match detected in package: $packageName")
            onWarning?.invoke("Blokována chráněná aplikace ($packageName)")
            return false
        }

        return true
    }

    /**
     * VUL-003/VUL-004 FIX: Pre-execute safety re-check.
     * Voláno TĚSNĚ PŘED actionExecutor.execute() s AKTUÁLNÍM packageName
     * (ne s tím co byl zachycen při screen parse).
     *
     * @param currentPackageName aktuální foreground package z rootInActiveWindow
     * @return true pokud je stále safe provést akci
     */
    fun isSafeToExecute(currentPackageName: String): Boolean {
        return isSafePackage(currentPackageName)
    }

    /**
     * Detect pokud agent je stuck v loop (opakuje stejnou akci)
     *
     * @return true pokud loop detekován, false pokud je vše OK
     */
    fun detectLoop(screenState: ScreenState): Boolean {
        val stateSignature = createStateSignature(screenState)

        // Dynamic settings
        val maxHistory = settingsManager.safetyGuardMaxHistory
        val loopThreshold = settingsManager.safetyGuardLoopThreshold

        // Přidej do history
        stateHistory.addLast(stateSignature)
        if (stateHistory.size > maxHistory) {
            stateHistory.removeFirst()
        }

        // Check pokud poslední N states jsou identické
        if (stateHistory.size >= loopThreshold) {
            val lastN = stateHistory.takeLast(loopThreshold)
            if (lastN.all { it == lastN.first() }) {
                Log.e(TAG, "🔄 LOOP DETECTED! Same state $loopThreshold times in a row")
                Log.e(TAG, "State signature: $stateSignature")
                onWarning?.invoke("Detekováno zacyklení agenta")
                return true
            }
        }

        return false
    }

    /**
     * Vytvoř hash signature z UI state
     * Pokud se UI nemění, signature bude stejný
     */
    private fun createStateSignature(screenState: ScreenState): String {
        // Concatenate důležité vlastnosti všech elementů
        val elementsHash = screenState.elements.joinToString("|") { element ->
            "${element.type}:${element.text}:${element.contentDescription}:${element.isClickable}"
        }

        // Simple hash (používáme String hashCode)
        return "${screenState.packageName}:${elementsHash.hashCode()}"
    }

    /**
     * Reset history (volá se při úspěšném dokončení command)
     */
    fun resetHistory() {
        stateHistory.clear()
        Log.d(TAG, "History cleared")
    }

    /**
     * Get blacklist pro debugging/display
     */
    fun getSensitivePackages(): Set<String> = SENSITIVE_PACKAGES
}
