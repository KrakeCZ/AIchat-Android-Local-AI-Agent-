package com.example.llama.agent.utils

import android.view.accessibility.AccessibilityNodeInfo

/**
 * CompatibilityUtils - Řeší rozdíly mezi verzemi Androidu
 *
 * Izoluje volání methods, které jsou deprecated v novějších verzích (API 33+),
 * ale jsou nutné pro starší verze nebo specifické případy.
 */
object CompatibilityUtils {

    /**
     * Bezpečně recykluje AccessibilityNodeInfo.
     *
     * Na API 33+ (Android 13) je recycle() deprecated a systém ho řeší často sám,
     * ale pro prevenci memory leaků a kompatibilitu ho zde voláme bezpečně.
     */
    fun recycleNode(node: AccessibilityNodeInfo?) {
        if (node == null) return

        // I když je deprecated na 33+, volání není chyba runtime.
        // Pro klid duše a "zprovoznění funkce" to zavoláme,
        // ale odděleně od hlavní logiky aby nesvítilo warning v hlavních souborech.
        @Suppress("DEPRECATION")
        node.recycle()
    }

    /**
     * Získá stav "checked" bezpečně napříč verzemi.
     *
     * @return true pokud je element zaškrtnutý
     */
    fun isNodeChecked(node: AccessibilityNodeInfo): Boolean {
        // Na API 33+ je isChecked deprecated ve prospěch stateDescription,
        // ale stateDescription je text. Pro logiku potřebujeme boolean.
        // Proto stále používáme native property, ale izolovaně.
        @Suppress("DEPRECATION")
        return node.isChecked
    }

    /**
     * Vytvoří kopii AccessibilityNodeInfo bezpečným a kompatibilním způsobem.
     *
     * Řeší deprecation varování "AccessibilityNodeInfo.obtain(AccessibilityNodeInfo)".
     * Na API 30+ (Android 11) používá copy constructor.
     * V tomto projektu (minSdk=33) používáme přímo constructor.
     *
     * @param node Zdrojový node
     * @return Nová instance (kopie) AccessibilityNodeInfo
     */
    fun obtainCopy(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        // Na minSdk 33+ je constructor vždy dostupný a obtain() je deprecated.
        return AccessibilityNodeInfo(node)
    }
}
