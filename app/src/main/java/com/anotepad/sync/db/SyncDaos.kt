package com.anotepad.sync.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncItemDao {
    @Query("SELECT * FROM sync_items")
    suspend fun getAll(): List<SyncItemEntity>

    @Query("SELECT * FROM sync_items WHERE localRelativePath = :path LIMIT 1")
    suspend fun getByPath(path: String): SyncItemEntity?

    @Query("SELECT * FROM sync_items WHERE driveFileId = :driveFileId LIMIT 1")
    suspend fun getByDriveId(driveFileId: String): SyncItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SyncItemEntity)

    @Query("DELETE FROM sync_items WHERE localRelativePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM sync_items")
    suspend fun deleteAll()
}

@Dao
interface SyncFolderDao {
    @Query("SELECT * FROM sync_folders")
    suspend fun getAll(): List<SyncFolderEntity>

    @Query("SELECT * FROM sync_folders WHERE localRelativePath = :path LIMIT 1")
    suspend fun getByPath(path: String): SyncFolderEntity?

    @Query("SELECT * FROM sync_folders WHERE driveFolderId = :driveFolderId LIMIT 1")
    suspend fun getByDriveId(driveFolderId: String): SyncFolderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: SyncFolderEntity)

    @Query("DELETE FROM sync_folders WHERE localRelativePath = :path")
    suspend fun deleteByPath(path: String)

    @Query("DELETE FROM sync_folders")
    suspend fun deleteAll()
}

@Dao
interface SyncMetaDao {
    @Query("SELECT value FROM sync_meta WHERE key = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM sync_meta WHERE key = :key LIMIT 1")
    fun observeValue(key: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SyncMetaEntity)
}
