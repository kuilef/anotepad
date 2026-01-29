package com.anotepad.sync

import com.anotepad.sync.db.SyncDatabase
import com.anotepad.sync.db.SyncFolderEntity
import com.anotepad.sync.db.SyncItemEntity
import com.anotepad.sync.db.SyncMetaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

class SyncRepository(private val db: SyncDatabase) {
    private val itemDao = db.syncItemDao()
    private val folderDao = db.syncFolderDao()
    private val metaDao = db.syncMetaDao()

    suspend fun getAllItems(): List<SyncItemEntity> = itemDao.getAll()

    suspend fun getItemByPath(path: String): SyncItemEntity? = itemDao.getByPath(path)

    suspend fun getItemByDriveId(driveFileId: String): SyncItemEntity? = itemDao.getByDriveId(driveFileId)

    suspend fun upsertItem(item: SyncItemEntity) = itemDao.upsert(item)

    suspend fun deleteItemByPath(path: String) = itemDao.deleteByPath(path)

    suspend fun clearItems() = itemDao.deleteAll()

    suspend fun getFolderByPath(path: String): SyncFolderEntity? = folderDao.getByPath(path)

    suspend fun getFolderByDriveId(driveFolderId: String): SyncFolderEntity? = folderDao.getByDriveId(driveFolderId)

    suspend fun upsertFolder(path: String, driveFolderId: String) =
        folderDao.upsert(SyncFolderEntity(path, driveFolderId))

    suspend fun clearFolders() = folderDao.deleteAll()

    suspend fun getMeta(key: String): String? = metaDao.getValue(key)

    suspend fun setMeta(key: String, value: String) = metaDao.upsert(SyncMetaEntity(key, value))

    fun syncStatusFlow(): Flow<SyncStatus> {
        val stateFlow = metaDao.observeValue(KEY_SYNC_STATUS)
        val messageFlow = metaDao.observeValue(KEY_SYNC_MESSAGE)
        val lastSyncedFlow = metaDao.observeValue(KEY_SYNC_LAST_SYNCED_AT)
        return combine(stateFlow, messageFlow, lastSyncedFlow) { stateRaw, message, lastSynced ->
            val state = SyncState.entries.firstOrNull { it.name == stateRaw } ?: SyncState.IDLE
            SyncStatus(
                state = state,
                lastSyncedAt = lastSynced?.toLongOrNull(),
                message = message?.ifBlank { null }
            )
        }
    }

    suspend fun setSyncStatus(state: SyncState, message: String? = null, lastSyncedAt: Long? = null) {
        setMeta(KEY_SYNC_STATUS, state.name)
        if (message != null) {
            setMeta(KEY_SYNC_MESSAGE, message)
        } else {
            setMeta(KEY_SYNC_MESSAGE, "")
        }
        if (lastSyncedAt != null) {
            setMeta(KEY_SYNC_LAST_SYNCED_AT, lastSyncedAt.toString())
        }
    }

    suspend fun getDriveFolderId(): String? = getMeta(KEY_DRIVE_FOLDER_ID)?.ifBlank { null }

    suspend fun setDriveFolderId(id: String) = setMeta(KEY_DRIVE_FOLDER_ID, id)

    suspend fun getDriveFolderName(): String? = getMeta(KEY_DRIVE_FOLDER_NAME)?.ifBlank { null }

    suspend fun setDriveFolderName(name: String) = setMeta(KEY_DRIVE_FOLDER_NAME, name)

    suspend fun getStartPageToken(): String? = getMeta(KEY_START_PAGE_TOKEN)?.ifBlank { null }

    suspend fun setStartPageToken(token: String) = setMeta(KEY_START_PAGE_TOKEN, token)

    suspend fun setLastFullScanAt(timestamp: Long) = setMeta(KEY_LAST_FULL_SCAN_AT, timestamp.toString())

    suspend fun getLastFullScanAt(): Long? = getMeta(KEY_LAST_FULL_SCAN_AT)?.toLongOrNull()

    suspend fun resetForNewFolder(folderId: String, folderName: String) {
        clearItems()
        clearFolders()
        setMeta(KEY_START_PAGE_TOKEN, "")
        setMeta(KEY_LAST_FULL_SCAN_AT, "")
        setDriveFolderId(folderId)
        setDriveFolderName(folderName)
    }

    companion object {
        const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
        const val KEY_DRIVE_FOLDER_NAME = "drive_folder_name"
        const val KEY_START_PAGE_TOKEN = "drive_start_page_token"
        const val KEY_LAST_FULL_SCAN_AT = "drive_last_full_scan_at"
        const val KEY_SYNC_STATUS = "sync_status"
        const val KEY_SYNC_MESSAGE = "sync_message"
        const val KEY_SYNC_LAST_SYNCED_AT = "sync_last_synced_at"
    }
}
