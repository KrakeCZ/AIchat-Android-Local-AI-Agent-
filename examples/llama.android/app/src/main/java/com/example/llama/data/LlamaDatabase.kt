package com.example.llama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [AiModel::class], version = 1, exportSchema = false)
abstract class LlamaDatabase : RoomDatabase() {
    abstract fun aiModelDao(): AiModelDao

    companion object {
        @Volatile
        private var INSTANCE: LlamaDatabase? = null

        fun getDatabase(context: Context): LlamaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LlamaDatabase::class.java,
                    "llama_app_database"
                )
                // Fallback pro dev (při změně schématu smaže data, lepší než crash)
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
