package com.example.llama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [WhisperModel::class], version = 1, exportSchema = false)
abstract class WhisperDatabase : RoomDatabase() {
    abstract fun whisperModelDao(): WhisperModelDao

    companion object {
        @Volatile private var INSTANCE: WhisperDatabase? = null

        fun getDatabase(context: Context): WhisperDatabase {
            return INSTANCE
                    ?: synchronized(this) {
                        val instance =
                                Room.databaseBuilder(
                                                context.applicationContext,
                                                WhisperDatabase::class.java,
                                                "whisper_app_database"
                                        )
                                        .fallbackToDestructiveMigration()
                                        .build()
                        INSTANCE = instance
                        instance
                    }
        }
    }
}
