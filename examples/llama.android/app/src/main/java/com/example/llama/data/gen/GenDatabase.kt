package com.example.llama.data.gen

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [GenModel::class], version = 1, exportSchema = false)
abstract class GenDatabase : RoomDatabase() {
    abstract fun genModelDao(): GenModelDao

    companion object {
        @Volatile
        private var INSTANCE: GenDatabase? = null

        fun getDatabase(context: Context): GenDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GenDatabase::class.java,
                    "gen_studio_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
