package com.journeyvisualizer.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's single local database (Phase 8).
 *
 * Version 1 — this is the first schema the app ships, so no migration is
 * needed: there is no earlier on-device schema to migrate from. If a future
 * version adds tables or columns, a proper Migration must be added here
 * instead of falling back to destructive migration.
 *
 * Privacy: the database lives in the app's private data directory and is
 * excluded from cloud backup concerns by keeping everything local-only
 * (the app declares allowBackup="false"). It is never synced or uploaded.
 */
@Database(
    entities = [VideoHistoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun videoHistoryDao(): VideoHistoryDao

    companion object {
        private const val DB_NAME = "journey_history.db"

        @Volatile
        private var instance: HistoryDatabase? = null

        fun get(context: Context): HistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }

        /** Test/seam hook — not used by production code. */
        internal fun setForTest(db: HistoryDatabase?) {
            instance = db
        }
    }
}
