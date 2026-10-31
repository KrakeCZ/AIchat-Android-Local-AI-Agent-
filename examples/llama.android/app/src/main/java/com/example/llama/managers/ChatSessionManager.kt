package com.example.llama.managers

import android.view.MenuItem
import android.widget.Toast

import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.example.llama.ChatHistoryManager
import com.example.llama.MainActivity
import com.example.llama.Message
import com.example.llama.MessageAdapter
import com.example.llama.R
import com.example.llama.SettingsManager
import com.example.llama.ui.dialogs.DialogManager
import com.google.android.material.navigation.NavigationView
import java.util.UUID
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * Řídí logiku aktuální konverzace.
 * - Spravuje seznam zpráv (MutableList).
 * - Ovládá Adapter (notify updates).
 * - Řeší "Nový chat", "Načíst chat", "Smazat chat".
 * - Stará se o vykreslení historie v bočním menu.
 */
class ChatSessionManager(
    private val activity: MainActivity,
    private val adapter: MessageAdapter,
    private val messages: MutableList<Message>,
    private val historyManager: ChatHistoryManager,
    private val settingsManager: SettingsManager,
    private val drawerLayout: DrawerLayout,
    private val navView: NavigationView
) {

    var currentSessionId: String = UUID.randomUUID().toString()
        private set

    // Callbacky pro akce, které musí Activity vyřešit (např. otevření Settings)
    var onSettingsClicked: (() -> Unit)? = null
    var onModelManagementClicked: (() -> Unit)? = null
    var onSkillsClicked: (() -> Unit)? = null

    /**
     * Vyčistí obrazovku a založí novou relaci.
     * Pokud je nastaven System Message, přidá ho.
     */
    fun startNewSession(skipEngineReset: Boolean = false) {
        activity.stopGeneration()
        // 1. Vyčistit UI
        val oldSize = messages.size
        messages.clear()
        if (oldSize > 0) adapter.notifyItemRangeRemoved(0, oldSize)

        // 2. Nové ID
        currentSessionId = UUID.randomUUID().toString()

        // 3. System Message
        val sysMsg = settingsManager.systemMessage
        if (sysMsg.isNotBlank() && settingsManager.showSystemMessage) {
            messages.add(Message(UUID.randomUUID().toString(), StringBuilder("SYSTEM: $sysMsg"), false))
            adapter.notifyItemInserted(0)
        }

        // 4. UI Feedback
        if (settingsManager.autoShowSidebar && !settingsManager.alwaysShowSidebar) {
            drawerLayout.openDrawer(GravityCompat.START)
        }
        Toast.makeText(activity, activity.getString(R.string.info_new_chat_started), Toast.LENGTH_SHORT).show()

        // 5. Refresh menu (aby se odznačil předchozí chat)
        refreshNavigationMenu()

        // 6. Explicitní KV cache reset (fix halucinací z předchozího chatu, přeskočeno pokud byl model právě načten)
        if (!skipEngineReset) {
            activity.resetEngineContext()
        }
    }

    /**
     * Načte existující relaci z historie.
     */
    fun loadSession(sessionId: String) {
        activity.stopGeneration()
        activity.resetEngineContext()
        val loadedMsgs = historyManager.loadSession(sessionId)
        if (loadedMsgs.isNotEmpty()) {
            val oldSize = messages.size
            messages.clear()
            if (oldSize > 0) adapter.notifyItemRangeRemoved(0, oldSize)

            messages.addAll(loadedMsgs)
            adapter.notifyItemRangeInserted(0, messages.size)
            currentSessionId = sessionId

            // Zavřít menu po výběru
            if (!settingsManager.alwaysShowSidebar) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        }
    }

    /**
     * Zobrazí dialog pro smazání aktuální konverzace.
     */
    fun confirmDeleteCurrentSession() {
        if (messages.isEmpty()) return

        DialogManager.showDeleteChatConfirm(activity) {
            deleteCurrentSession()
        }
    }

    private fun deleteCurrentSession() {
        historyManager.deleteSession(currentSessionId)
        startNewSession()
        refreshNavigationMenu()
    }

    /**
     * Kompletně překreslí položky v bočním menu (Historii).
     */
    fun refreshNavigationMenu() {
        val menu = navView.menu
        menu.clear()

        // Statické položky
        menu.add(0, 0, 0, "+ Nový Chat").setIcon(android.R.drawable.ic_menu_add)
        menu.add(0, 999, 1, "Nastavení").setIcon(android.R.drawable.ic_menu_preferences)
        menu.add(0, 993, 2, "🧠 Agent Skills & Instrukce").setIcon(android.R.drawable.ic_menu_agenda)
        menu.add(0, 998, 3, "🎙️ Audio Studio & Titulkování").setIcon(com.example.llama.R.drawable.ic_mic_24)
        menu.add(0, 996, 4, "🔍 ObjectDetector Studio").setIcon(android.R.drawable.ic_menu_camera)
        menu.add(0, 994, 5, "🎬 GEN_Studio (Video & Image)").setIcon(android.R.drawable.ic_menu_slideshow)
        menu.add(0, 997, 6, "🎮 Remote Profily & HW").setIcon(android.R.drawable.ic_menu_compass)
        menu.add(0, 995, 7, "🏆 Poděkování (Thanks)").setIcon(android.R.drawable.ic_menu_info_details)

        // Dynamická historie
        val historySubMenu = menu.addSubMenu("Historie konverzací")
        val sessions = historyManager.getSessions()

        sessions.forEachIndexed { index, sessionId ->
            // Zkrácení ID nebo získání prvních slov (pokud bychom četli soubor, ale zde jen ID)
            val title = "Chat ${sessionId.take(8)}..."
            historySubMenu.add(1, index + 1000, index + 2, title)
                .setIcon(android.R.drawable.ic_menu_recent_history)
        }

        val modelsSubMenu = menu.addSubMenu("Model")
        modelsSubMenu.add(2, 2000, 3, activity.getString(R.string.menu_manage_models))

        // Nastavení listeneru
        navView.setNavigationItemSelectedListener { item ->
            handleNavigationItem(item, sessions)
            true
        }
    }

    private fun handleNavigationItem(item: MenuItem, sessions: List<String>) {
        when (item.itemId) {
            0 -> startNewSession()
            999 -> onSettingsClicked?.invoke()
            993 -> onSkillsClicked?.invoke()
            998 -> activity.startActivity(android.content.Intent(activity, com.example.llama.AudioStudioActivity::class.java))
            996 -> activity.startActivity(android.content.Intent(activity, com.example.llama.agent.ui.studio.ObjectDetectorStudioActivity::class.java))
            994 -> activity.startActivity(android.content.Intent(activity, com.example.llama.agent.ui.studio.genstudio.GenStudioActivity::class.java))
            997 -> activity.startActivity(android.content.Intent(activity, com.example.llama.agent.irismotion.ui.RemoteProfilesActivity::class.java))
            995 -> activity.startActivity(android.content.Intent(activity, com.example.llama.ui.thanks.ThanksActivity::class.java))
            2000 -> onModelManagementClicked?.invoke()
            else -> {
                if (item.groupId == 1) {
                    val sessionIndex = item.itemId - 1000
                    if (sessionIndex >= 0 && sessionIndex < sessions.size) {
                        loadSession(sessions[sessionIndex])
                    }
                }
            }
        }
        // Zavření menu (pokud není pinned)
        if (!settingsManager.alwaysShowSidebar) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
    }

    // Uloží aktuální stav (voláno při pauze nebo generování)
    fun saveCurrentState() {
        // OPTIMIZACE: Ukládání na disk musí běžet na background threadu (IO).
        // SNAPSHOT: Musíme vytvořit kopii dat na Hlavním Vlákně, protože 'messages' není thread-safe!
        val sessionId = currentSessionId
        val snapshot = messages.toList()

        activity.lifecycleScope.launch(Dispatchers.IO) {
            historyManager.saveSession(sessionId, snapshot)

            // UI aktualizace (refresh menu) musí zpět na Main thread
            withContext(Dispatchers.Main) {
                 refreshNavigationMenu()
            }
        }
    }
}
