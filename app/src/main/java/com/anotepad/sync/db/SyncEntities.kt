package com.anotepad.sync.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_items",
    indices = [Index(value = ["driveFileId"])]
)
data class SyncItemEntity(
    @PrimaryKey val localRelativePath: String,
    val localLastModified: Long,
    val localSize: Long,
    val localHash: String?,
    val driveFileId: String?,
    val driveModifiedTime: Long?,
    val lastSyncedAt: Long?,
    val syncState: String,
    val lastError: String?
)

@Entity(tableName = "sync_folders")
data class SyncFolderEntity(
    @PrimaryKey val localRelativePath: String,
    val driveFolderId: String
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val key: String,
    val value: String
)
