package com.example.llama.agent.irismotion.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(entities = [RemoteProfileEntity::class], version = 1, exportSchema = true)
@TypeConverters(MapTypeConverter::class)
abstract class IrisDatabase : RoomDatabase() {

    abstract fun remoteProfileDao(): RemoteProfileDao

    companion object {
        @Volatile
        private var INSTANCE: IrisDatabase? = null

        fun getDatabase(context: Context): IrisDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    IrisDatabase::class.java,
                    "iris_database.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
