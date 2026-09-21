package com.example.llama.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room databáze pro BertModel záznamy (TFLite modely a vocab soubory).
 *
 * Migrace:
 * v1 → v2: Přidána pole isVocabFile, modelSource, vocabStrategy, linkedModelId
 *           Čistá ALTER TABLE migrace — žádná stávající data nejsou ztracena.
 */
@Database(entities = [BertModel::class], version = 2, exportSchema = false)
abstract class BertDatabase : RoomDatabase() {

    abstract fun bertModelDao(): BertModelDao

    companion object {

        @Volatile
        private var INSTANCE: BertDatabase? = null

        /** Migrace v1 → v2: přidání nových sloupců pro vocab management (idempotentní) */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val existingColumns = mutableSetOf<String>()
                db.query("PRAGMA table_info(bert_models)").use { cursor ->
                    val nameIdx = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        existingColumns.add(cursor.getString(nameIdx))
                    }
                }

                if ("isVocabFile" !in existingColumns) {
                    db.execSQL(
                        "ALTER TABLE bert_models ADD COLUMN isVocabFile INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if ("modelSource" !in existingColumns) {
                    db.execSQL(
                        "ALTER TABLE bert_models ADD COLUMN modelSource TEXT NOT NULL DEFAULT 'bundled_google'"
                    )
                }
                if ("vocabStrategy" !in existingColumns) {
                    db.execSQL(
                        "ALTER TABLE bert_models ADD COLUMN vocabStrategy TEXT NOT NULL DEFAULT 'bundled_asset'"
                    )
                }
                if ("linkedModelId" !in existingColumns) {
                    db.execSQL(
                        "ALTER TABLE bert_models ADD COLUMN linkedModelId TEXT"
                    )
                }
            }
        }

        fun getDatabase(context: Context): BertDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BertDatabase::class.java,
                    "bert_app_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
