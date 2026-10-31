package com.example.llama.agent.irismotion.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.llama.SettingsManager
import com.example.llama.agent.irismotion.database.RemoteProfileEntity
import com.example.llama.agent.irismotion.database.RemoteProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RemoteProfilesViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RemoteProfileRepository(application)
    private val settingsManager = SettingsManager(application)

    private val _profiles = MutableStateFlow<List<RemoteProfileEntity>>(emptyList())
    val profiles: StateFlow<List<RemoteProfileEntity>> = _profiles.asStateFlow()

    private val _activeProfileId = MutableStateFlow<String>("")
    val activeProfileId: StateFlow<String> = _activeProfileId.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            // Zajistíme existenci výchozího profilu, pokud je DB prázdná
            repository.ensureDefaultMouseProfileExists()
            
            // Načteme všechny profily
            val allProfiles = repository.getAllProfiles()
            _profiles.value = allProfiles

            // Načteme aktuálně aktivní profil
            _activeProfileId.value = settingsManager.prefs.getString("active_remote_profile_id", "default_pc_mouse") ?: "default_pc_mouse"
        }
    }

    fun setActiveProfile(profileId: String) {
        viewModelScope.launch {
            // Uložíme do SettingsManageru
            settingsManager.prefs.edit().putString("active_remote_profile_id", profileId).apply()
            _activeProfileId.value = profileId
            
            // Poznámka: Služba/Orchestrátor by měla mít listener nebo si profil načte, 
            // nicméně nejspolehlivější by byl eventbus/broadcast nebo prostě restart služby.
            // Zde měníme UI stav a config.
        }
    }

    fun updateProfileMapping(profile: RemoteProfileEntity, actionId: Int, targetMouseAction: Int) {
        viewModelScope.launch {
            val updatedMap = profile.gestureMapping.toMutableMap()
            updatedMap[actionId] = targetMouseAction
            val updatedProfile = profile.copy(gestureMapping = updatedMap)
            repository.saveProfile(updatedProfile)
            
            // Znovu načíst
            loadData()
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val entity = repository.getProfileById(profileId)
            if (entity != null) {
                repository.deleteProfile(profileId)
                loadData()
            }
        }
    }
}
