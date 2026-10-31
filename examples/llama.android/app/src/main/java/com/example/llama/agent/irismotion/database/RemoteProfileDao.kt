package com.example.llama.agent.irismotion.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteProfileDao {

    @Query("SELECT * FROM remote_profiles")
    fun getAllProfiles(): List<RemoteProfileEntity>

    @Query("SELECT * FROM remote_profiles WHERE id = :profileId LIMIT 1")
    fun getProfileById(profileId: String): RemoteProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertProfile(profile: RemoteProfileEntity)

    @Query("DELETE FROM remote_profiles WHERE id = :profileId")
    fun deleteProfile(profileId: String)
}
