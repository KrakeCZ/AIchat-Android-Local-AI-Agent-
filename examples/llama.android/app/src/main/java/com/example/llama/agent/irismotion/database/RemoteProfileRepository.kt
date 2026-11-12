package com.example.llama.agent.irismotion.database

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repozitář pro správu Remote Profilů.
 * Zajišťuje bezpečné čtení/zápis na pozadí (Dispatchers.IO) 
 * tak, aby neblokoval Main Thread a senzoriku očí.
 */
class RemoteProfileRepository(context: Context) {

    private val dao = IrisDatabase.getDatabase(context).remoteProfileDao()

    suspend fun getAllProfiles(): List<RemoteProfileEntity> {
        return withContext(Dispatchers.IO) {
            dao.getAllProfiles()
        }
    }

    suspend fun getProfileById(id: String): RemoteProfileEntity? {
        return withContext(Dispatchers.IO) {
            dao.getProfileById(id)
        }
    }

    suspend fun saveProfile(profile: RemoteProfileEntity) {
        withContext(Dispatchers.IO) {
            dao.insertProfile(profile)
        }
    }

    suspend fun deleteProfile(id: String) {
        withContext(Dispatchers.IO) {
            dao.deleteProfile(id)
        }
    }

    /**
     * Vytvoří výchozí profil myši, pokud databáze zeje prázdnotou.
     */
    suspend fun ensureDefaultMouseProfileExists() {
        withContext(Dispatchers.IO) {
            val existing = dao.getProfileById("default_pc_mouse")
            if (existing == null) {
                // Výchozí mapování (shodné s MouseProfile.kt starou hardcoded logikou)
                // 1=CLICK, 2=BACK, 3=HOME, 4=TORCH, 5=NOTIFICATIONS
                val defaultMapping = mapOf(
                    1 to 0, // Mrknutí L -> Levé tlačítko (konstanty vyřešíme v UI)
                    2 to 1, // Mrknutí P -> Pravé tlačítko
                    3 to 2, // Úsměv -> Střední tlačítko
                    4 to -1, // Zamračení -> Kolečko dolů
                    5 to 1   // Vytažené obočí -> Kolečko nahoru
                )
                dao.insertProfile(
                    RemoteProfileEntity(
                        id = "default_pc_mouse",
                        name = "PC Myš (Výchozí)",
                        type = "MOUSE",
                        gestureMapping = defaultMapping
                    )
                )
            }
        }
    }

    /**
     * Vytvoří výchozí profil Digitizeru (Absolutní Mód).
     */
    suspend fun ensureDefaultDigitizerProfileExists() {
        withContext(Dispatchers.IO) {
            val existing = dao.getProfileById("default_pc_digitizer")
            if (existing == null) {
                val defaultMapping = mapOf(
                    1 to 0, // Mrknutí L -> Levé tlačítko
                    2 to 1, // Mrknutí P -> Pravé tlačítko
                    3 to 2, // Úsměv -> Střední tlačítko
                    4 to -1, // Zamračení -> Kolečko dolů (Zatím nepodporováno v DigitizerProfile, ale připraveno)
                    5 to 1   // Vytažené obočí -> Kolečko nahoru
                )
                dao.insertProfile(
                    RemoteProfileEntity(
                        id = "default_pc_digitizer",
                        name = "PC Digitizér (Absolutní Mód)",
                        type = "DIGITIZER",
                        gestureMapping = defaultMapping
                    )
                )
            }
        }
    }
}
