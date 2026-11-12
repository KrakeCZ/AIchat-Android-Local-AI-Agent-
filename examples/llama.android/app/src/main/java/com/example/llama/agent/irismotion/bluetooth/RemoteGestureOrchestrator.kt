package com.example.llama.agent.irismotion.bluetooth

import android.content.Context
import android.util.Log
import com.example.llama.SettingsManager
import com.example.llama.agent.irismotion.bluetooth.profiles.MouseProfile
import com.example.llama.agent.irismotion.bluetooth.profiles.DigitizerProfile
import com.example.llama.agent.irismotion.bluetooth.profiles.RemoteProfile
import com.example.llama.agent.irismotion.database.RemoteProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centrální dispatcher pro vzdálená gesta.
 * Přijímá události z AiAgentAccessibilityService a předává je
 * aktuálně aktivnímu profilu (např. Myš, Gamepad).
 */
class RemoteGestureOrchestrator(
    context: Context,
    private val settingsManager: SettingsManager
) {

    private val TAG = "RemoteOrchestrator"

    // Repozitář pro práci s Room Databází
    private val repository = RemoteProfileRepository(context)

    // IN-MEMORY CACHE: Toto je profil, který žije v RAM. Žádné I/O zpoždění!
    // Na začátku ho natvrdo nastavíme na Myš, než dorazí data z disku.
    @Volatile
    private var activeProfile: RemoteProfile = MouseProfile(settingsManager, emptyMap())

    init {
        // Asynchronní start (neblokuje Main Thread)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Zkontrolujeme, zda vůbec existuje defaultní profil. Pokud ne, vytvoříme ho.
                repository.ensureDefaultMouseProfileExists()
                repository.ensureDefaultDigitizerProfileExists()

                // 2. Tady se podíváme do SettingsManageru, jaký profil si uživatel naposledy vybral.
                val activeProfileId = settingsManager.prefs.getString("active_remote_profile_id", "default_pc_mouse") ?: "default_pc_mouse"

                // 3. Stáhneme mapování z disku
                val entity = repository.getProfileById(activeProfileId)
                if (entity != null) {
                    Log.i(TAG, "💿 Úspěšně načten profil z Databáze: ${entity.name}")
                    
                    // 4. Vyměníme Instanci v RAM za tu chytrou s mapováním
                    activeProfile = if (entity.type == "DIGITIZER") {
                        DigitizerProfile(settingsManager, entity.gestureMapping)
                    } else {
                        MouseProfile(settingsManager, entity.gestureMapping)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Chyba při inicializaci Orchestrátoru a Databáze", e)
            }
        }
    }

    /**
     * Přepne aktivní profil. Voláno typicky z UI, když uživatel změní aktivní profil.
     */
    fun switchProfile(profileId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val entity = repository.getProfileById(profileId) ?: return@launch
            
            // Uložení volby
            settingsManager.prefs.edit().putString("active_remote_profile_id", profileId).apply()

            val newProfile = if (entity.type == "MOUSE") {
                MouseProfile(settingsManager, entity.gestureMapping)
            } else if (entity.type == "DIGITIZER") {
                DigitizerProfile(settingsManager, entity.gestureMapping)
            } else {
                MouseProfile(settingsManager, entity.gestureMapping)
            }

            activeProfile.resetTracking()
            activeProfile = newProfile
            Log.i(TAG, "🔄 Orchestrátor přepnul na profil: ${entity.name}")
        }
    }

    /**
     * Umožňuje za běhu přepnout profil bez restartu Bluetooth služby.
     */
    fun switchProfile(newProfile: RemoteProfile) {
        activeProfile.resetTracking()
        activeProfile = newProfile
    }

    /**
     * Zpracuje novou pozici očí a odešle ji do aktuálního profilu.
     */
    fun dispatchGazeUpdate(x: Float, y: Float) {
        activeProfile.handleGazeUpdate(x, y, HidBridge.transmitter)
    }

    /**
     * Zpracuje identifikované gesto očí a odešle ho do aktuálního profilu.
     */
    fun dispatchEyeGesture(actionId: Int) {
        activeProfile.handleEyeGesture(actionId, HidBridge.transmitter)
    }

    /**
     * Přenese data o celotělovém postoji z MediaPipe (připraveno pro budoucí implementaci).
     */
    fun dispatchHolisticPose(poseData: Any) {
        activeProfile.handleHolisticPose(poseData, HidBridge.transmitter)
    }

    /**
     * Resetuje stav aktuálního profilu.
     */
    fun reset() {
        activeProfile.resetTracking()
    }

    /**
     * Aktualizuje citlivost aktivního profilu.
     */
    fun updateSensitivity(sensitivity: Float) {
        activeProfile.updateSensitivity(sensitivity)
    }
}
