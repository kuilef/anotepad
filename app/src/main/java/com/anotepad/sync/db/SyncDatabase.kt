package com.anotepad.sync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SyncItemEntity::class, SyncFolderEntity::class, SyncMetaEntity::class],
    version = 2
)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncItemDao(): SyncItemDao
    abstract fun syncFolderDao(): SyncFolderDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getInstance(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync.db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_sync_folders_driveFolderId " +
                        "ON sync_folders(driveFolderId)"
                )
            }
        }
    }
}
