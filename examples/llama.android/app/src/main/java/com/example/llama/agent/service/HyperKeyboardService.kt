package com.example.llama.agent.service

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout

/**
 * HyperKeyboardService — Neviditelná klávesnice pro univerzální text injection.
 *
 * Architektura:
 * - Dědí od InputMethodService → přímý přístup k InputConnection cílové aplikace.
 * - commitText() vkládá text přímo do input bufferu — funguje v WebView, Unity, Flutter.
 * - sendKeyEvent() posílá řídící klávesy (Enter, Backspace, Tab).
 * - onCreateInputView() vrací View s nulovou výškou → neviditelná klávesnice.
 *
 * 2-tier fallback (INPUT_TEXT v ActionExecutor):
 * 1. HyperKeyboardService.injectText()  ← univerzální (WebView, Unity, Flutter)
 * 2. ACTION_SET_TEXT přes Accessibility   ← fallback pro standardní EditText
 *
 * Requirements:
 * - BIND_INPUT_METHOD permission v Manifestu
 * - method.xml v res/xml/
 * - Uživatel musí povolit klávesnici v Nastavení → Jazyky a vstup → Klávesnice
 * - Uživatel musí nastavit jako aktivní klávesnici (nebo přepnout před operací)
 *
 * Non-Root: Ano. InputMethodService je standardní Android API.
 * Min SDK: API 14+ (prakticky všechny podporované verze).
 *
 * THREAD SAFETY:
 * - onCreateInputView/getCurrentInputConnection jsou volány na Main thread systémem.
 * - instance je @Volatile pro safe reads z worker threadů (ActionExecutor).
 */
class HyperKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "HyperKeyboardService"

        /**
         * Singleton reference na běžící instanci služby.
         * Null pokud klávesnice není aktivní / není bindovaná.
         *
         * @Volatile zajišťuje viditelnost změn mezi thready
         * (ActionExecutor čte z Dispatchers.Default, IME zapisuje z Main).
         */
        @Volatile
        var instance: HyperKeyboardService? = null
            private set

        /**
         * Vloží text do aktuálního input pole přes InputConnection.
         *
         * Funguje nezávisle na typu aplikace:
         * - Standard EditText ✓
         * - WebView ✓
         * - Unity/Unreal Engine ✓
         * - Flutter ✓
         * - RDP/VNC client ✓
         *
         * @param text Text k vložení
         * @return true pokud text byl úspěšně vložen, false pokud není InputConnection
         */
        fun injectText(text: String): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "injectText() — service not active (instance is null)")
                return false
            }

            val ic = service.currentInputConnection ?: run {
                Log.w(TAG, "injectText() — no InputConnection available")
                return false
            }

            return try {
                // commitText vkládá text na pozici kurzoru
                // cursorPosition=1 → kurzor se posune za vložený text
                val success = ic.commitText(text, 1)
                if (success) {
                    Log.i(TAG, "✓ Text injected via IME: \"$text\"")
                } else {
                    Log.w(TAG, "commitText returned false for: \"$text\"")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject text via IME", e)
                false
            }
        }

        /**
         * Odešle řídící klávesu (Enter, Backspace, Tab, atd.) přes InputConnection.
         *
         * Použití:
         * - KEYCODE_ENTER → odeslání formuláře/zprávy
         * - KEYCODE_DEL → smazání znaku (Backspace)
         * - KEYCODE_TAB → přechod na další pole
         *
         * @param keyCode Android KeyEvent keyCode (např. KeyEvent.KEYCODE_ENTER)
         * @return true pokud klávesa byla úspěšně odeslána
         */
        @Suppress("unused")
        fun injectKey(keyCode: Int): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "injectKey() — service not active (instance is null)")
                return false
            }

            val ic = service.currentInputConnection ?: run {
                Log.w(TAG, "injectKey() — no InputConnection available")
                return false
            }

            return try {
                // Posíláme DOWN + UP pár (kompletní stisk klávesy)
                val downSuccess = ic.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                )
                val upSuccess = ic.sendKeyEvent(
                    KeyEvent(KeyEvent.ACTION_UP, keyCode)
                )

                val success = downSuccess && upSuccess
                if (success) {
                    Log.i(TAG, "✓ Key injected via IME: keyCode=$keyCode")
                } else {
                    Log.w(TAG, "sendKeyEvent returned false for keyCode=$keyCode")
                }
                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject key via IME", e)
                false
            }
        }

        /**
         * Vymaže obsah aktuálního input pole.
         *
         * Strategie: selectAll + delete
         *
         * @return true pokud úspěšně vymazáno
         */
        @Suppress("unused")
        fun clearText(): Boolean {
            val service = instance ?: run {
                Log.w(TAG, "clearText() — service not active")
                return false
            }

            val ic = service.currentInputConnection ?: run {
                Log.w(TAG, "clearText() — no InputConnection available")
                return false
            }

            return try {
                // Získáme text před kurzorem (až 10000 znaků)
                val before = ic.getTextBeforeCursor(10000, 0)
                val after = ic.getTextAfterCursor(10000, 0)
                val totalLength = (before?.length ?: 0) + (after?.length ?: 0)

                if (totalLength == 0) {
                    Log.d(TAG, "clearText() — field already empty")
                    return true
                }

                // Select all a delete
                ic.performContextMenuAction(android.R.id.selectAll)
                ic.commitText("", 1) // Nahradí selection prázdným textem

                Log.i(TAG, "✓ Text cleared via IME ($totalLength chars)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear text via IME", e)
                false
            }
        }

        /**
         * Zkontrolovat zda je služba aktivní a má InputConnection.
         *
         * @return true pokud je IME bindovaná a může injektovat text
         */
        fun isActive(): Boolean {
            val service = instance ?: return false
            return service.currentInputConnection != null
        }
    }

    // ==================== LIFECYCLE ====================

    /**
     * Voláno systémem při vytvoření služby.
     * Registruje singleton instanci.
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "⌨️ HyperKeyboardService created")
    }

    /**
     * Voláno systémem při zničení služby.
     * Čistí singleton referenci.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) {
            instance = null
        }
        Log.i(TAG, "⌨️ HyperKeyboardService destroyed")
    }

    /**
     * Vytvoří vizuální komponentu klávesnice.
     *
     * Vrací View s NULOVOU VÝŠKOU → neviditelná klávesnice.
     * Uživatel nevidí žádné UI, ale InputConnection je plně funkční.
     *
     * Dle Dok. 3 kap. 4.2:
     * > Neviditelnost: onCreateInputView vrací View s nulovou výškou.
     */
    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView — creating zero-height invisible keyboard")

        return FrameLayout(this).apply {
            // Nulová výška → klávesnice je neviditelná
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0 // Nulová výška
            )
        }
    }

    /**
     * Voláno systémem při bindování na input pole (textové pole získá focus).
     */
    override fun onBindInput() {
        super.onBindInput()
        Log.d(TAG, "⌨️ onBindInput — keyboard bound to input field")
    }

    /**
     * Voláno systémem při unbindování z input pole.
     */
    override fun onUnbindInput() {
        super.onUnbindInput()
        Log.d(TAG, "⌨️ onUnbindInput — keyboard unbound from input field")
    }
}
