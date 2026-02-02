package com.anotepad.sync.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SyncItemEntity::class, SyncFolderEntity::class, SyncMetaEntity::class],
    version = 1
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
                ).build().also { INSTANCE = it }
            }
        }
    }
}
