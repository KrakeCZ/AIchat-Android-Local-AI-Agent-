package com.example.llama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Samostatná Room databáze pro NLP modely.
 *
 * Oddělená od WhisperDatabase — žádný version bump, žádná migrace.
 * Každý modul (Whisper, NLP) má vlastní izolovanou databázi.
 */
@Database(entities = [NlpModel::class], version = 1, exportSchema = false)
abstract class NlpDatabase : RoomDatabase() {
    abstract fun nlpModelDao(): NlpModelDao

    companion object {
        @Volatile private var INSTANCE: NlpDatabase? = null

        fun getDatabase(context: Context): NlpDatabase {
            return INSTANCE
                    ?: synchronized(this) {
                        val instance =
                                Room.databaseBuilder(
                                                context.applicationContext,
                                                NlpDatabase::class.java,
                                                "nlp_app_database"
                                        )
                                        .fallbackToDestructiveMigration()
                                        .build()
                        INSTANCE = instance
                        instance
                    }
        }
    }
}
