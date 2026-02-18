package com.anotepad.sync.engine.fixtures

import com.anotepad.sync.SyncState
import com.anotepad.sync.db.SyncFolderEntity
import com.anotepad.sync.db.SyncItemEntity
import com.anotepad.sync.engine.SyncStore

class FakeSyncStore : SyncStore {
    data class StatusEntry(
        val state: SyncState,
        val message: String?,
        val lastSyncedAt: Long?
    )

    val calls = mutableListOf<String>()
    val statuses = mutableListOf<StatusEntry>()

    private val items = linkedMapOf<String, SyncItemEntity>()
    private val folders = linkedMapOf<String, SyncFolderEntity>()

    var driveFolderId: String? = null
    var driveFolderName: String? = null
    var startPageToken: String? = null
    var lastFullScanAt: Long? = null

    override suspend fun getAllItems(): List<SyncItemEntity> = items.values.toList()

    override suspend fun getItemByPath(path: String): SyncItemEntity? = items[path]

    override suspend fun getItemByDriveId(driveFileId: String): SyncItemEntity? =
        items.values.firstOrNull { it.driveFileId == driveFileId }

    override suspend fun upsertItem(item: SyncItemEntity) {
        calls += "upsertItem:${item.localRelativePath}"
        items[item.localRelativePath] = item
    }

    override suspend fun deleteItemByPath(path: String) {
        calls += "deleteItemByPath:$path"
        items.remove(path)
    }

    override suspend fun getItemsByPathPrefix(path: String): List<SyncItemEntity> {
        return items.values
            .filter { it.localRelativePath == path || it.localRelativePath.startsWith("$path/") }
            .sortedBy { it.localRelativePath }
    }

    override suspend fun deleteItemsByPathPrefix(path: String) {
        calls += "deleteItemsByPathPrefix:$path"
        items.keys
            .filter { it == path || it.startsWith("$path/") }
            .toList()
            .forEach { items.remove(it) }
    }

    override suspend fun getFolderByPath(path: String): SyncFolderEntity? = folders[path]

    override suspend fun getFolderByDriveId(driveFolderId: String): SyncFolderEntity? =
        folders.values.firstOrNull { it.driveFolderId == driveFolderId }

    override suspend fun getAllFolders(): List<SyncFolderEntity> = folders.values.toList()

    override suspend fun getFoldersByPathPrefix(path: String): List<SyncFolderEntity> {
        return folders.values
            .filter { it.localRelativePath == path || it.localRelativePath.startsWith("$path/") }
            .sortedBy { it.localRelativePath }
    }

    override suspend fun upsertFolder(path: String, driveFolderId: String) {
        calls += "upsertFolder:$path:$driveFolderId"
        folders[path] = SyncFolderEntity(path, driveFolderId)
    }

    override suspend fun deleteFolderByPath(path: String) {
        calls += "deleteFolderByPath:$path"
        folders.remove(path)
    }

    override suspend fun deleteFoldersByPathPrefix(path: String) {
        calls += "deleteFoldersByPathPrefix:$path"
        folders.keys
            .filter { it == path || it.startsWith("$path/") }
            .toList()
            .forEach { folders.remove(it) }
    }

    override suspend fun setSyncStatus(state: SyncState, message: String?, lastSyncedAt: Long?) {
        calls += "setSyncStatus:${state.name}:${message ?: ""}"
        statuses += StatusEntry(state, message, lastSyncedAt)
    }

    override suspend fun getDriveFolderId(): String? = driveFolderId

    override suspend fun setDriveFolderId(id: String) {
        driveFolderId = id
    }

    override suspend fun getDriveFolderName(): String? = driveFolderName

    override suspend fun setDriveFolderName(name: String) {
        driveFolderName = name
    }

    override suspend fun getStartPageToken(): String? = startPageToken

    override suspend fun setStartPageToken(token: String) {
        startPageToken = token
    }

    override suspend fun setLastFullScanAt(timestamp: Long) {
        lastFullScanAt = timestamp
    }

    override suspend fun getLastFullScanAt(): Long? = lastFullScanAt

    fun putItem(item: SyncItemEntity) {
        items[item.localRelativePath] = item
    }

    fun putFolder(path: String, driveFolderId: String) {
        folders[path] = SyncFolderEntity(path, driveFolderId)
    }

    fun item(path: String): SyncItemEntity? = items[path]

    fun folder(path: String): SyncFolderEntity? = folders[path]

    fun itemByDriveId(driveId: String): SyncItemEntity? = items.values.firstOrNull { it.driveFileId == driveId }
}
