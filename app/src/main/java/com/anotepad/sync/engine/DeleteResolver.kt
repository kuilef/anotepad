package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.RemoteDeletePolicy
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity

class DeleteResolver(
    private val localFs: LocalFsGateway,
    private val store: SyncStore
) {
    suspend fun handleRemoteDeletion(
        prefs: AppPreferences,
        rootId: String,
        driveFileId: String
    ) {
        if (prefs.driveSyncIgnoreRemoteDeletes) return

        val folder = store.getFolderByDriveId(driveFileId)
        if (folder != null) {
            if (isIgnoredPath(folder.localRelativePath)) {
                store.deleteFolderByPath(folder.localRelativePath)
                return
            }
            handleRemoteFolderDeletion(rootId, folder.localRelativePath)
            return
        }
        handleRemoteFileDeletion(rootId, driveFileId)
    }

    suspend fun handleLocalDeletion(
        token: String,
        policy: RemoteDeletePolicy,
        path: String,
        item: SyncItemEntity
    ) {
        val driveId = item.driveFileId
        if (!driveId.isNullOrBlank()) {
            when (policy) {
                RemoteDeletePolicy.TRASH -> {
                    // Executed by caller via SyncPlan when needed.
                }

                RemoteDeletePolicy.DELETE -> {
                    // Executed by caller via SyncPlan when needed.
                }

                RemoteDeletePolicy.IGNORE -> Unit
            }
        }
        store.deleteItemByPath(path)
    }

    suspend fun moveLocalToTrash(rootId: String, relativePath: String): TrashMoveResult {
        if (!localFs.exists(rootId, relativePath)) return TrashMoveResult.MISSING
        localFs.ensureDirectory(rootId, TRASH_DIR)
        val name = relativePath.substringAfterLast('/')
        val newName = buildConflictName("$TRASH_DIR/$name")
        if (localFs.moveFile(rootId, relativePath, newName)) {
            return TrashMoveResult.MOVED
        }
        if (!localFs.copyFile(rootId, relativePath, newName)) {
            return TrashMoveResult.FAILED
        }
        return if (localFs.deleteFile(rootId, relativePath)) {
            TrashMoveResult.MOVED
        } else {
            TrashMoveResult.FAILED
        }
    }

    private suspend fun handleRemoteFileDeletion(rootId: String, driveFileId: String) {
        val existing = store.getItemByDriveId(driveFileId) ?: return
        if (isIgnoredPath(existing.localRelativePath)) {
            store.deleteItemByPath(existing.localRelativePath)
            return
        }

        val localMeta = localFs.getFileMeta(rootId, existing.localRelativePath)
        val localModified = localMeta?.lastModified ?: 0L
        val lastSynced = existing.lastSyncedAt ?: 0L
        if (localModified > lastSynced) {
            store.upsertItem(
                existing.copy(
                    driveFileId = null,
                    driveModifiedTime = null,
                    syncState = SyncItemState.PENDING_UPLOAD.name
                )
            )
            return
        }

        when (moveLocalToTrash(rootId, existing.localRelativePath)) {
            TrashMoveResult.MOVED, TrashMoveResult.MISSING -> {
                store.deleteItemByPath(existing.localRelativePath)
            }

            TrashMoveResult.FAILED -> {
                markPendingUpload(existing)
            }
        }
    }

    private suspend fun handleRemoteFolderDeletion(rootId: String, folderPath: String) {
        val items = store.getItemsByPathPrefix(folderPath)
        var hasRetainedLocalItems = false
        for (item in items) {
            val localMeta = localFs.getFileMeta(rootId, item.localRelativePath)
            val localModified = localMeta?.lastModified ?: 0L
            val lastSynced = item.lastSyncedAt ?: 0L
            if (localModified > lastSynced) {
                markPendingUpload(item)
                hasRetainedLocalItems = true
            } else {
                when (moveLocalToTrash(rootId, item.localRelativePath)) {
                    TrashMoveResult.MOVED, TrashMoveResult.MISSING -> {
                        store.deleteItemByPath(item.localRelativePath)
                    }

                    TrashMoveResult.FAILED -> {
                        markPendingUpload(item)
                        hasRetainedLocalItems = true
                    }
                }
            }
        }

        val folders = store.getFoldersByPathPrefix(folderPath)
        for (folder in folders) {
            store.deleteFolderByPath(folder.localRelativePath)
        }
        if (!hasRetainedLocalItems) {
            localFs.deleteDirectory(rootId, folderPath)
        }
    }

    private suspend fun markPendingUpload(item: SyncItemEntity) {
        store.upsertItem(
            item.copy(
                driveFileId = null,
                driveModifiedTime = null,
                syncState = SyncItemState.PENDING_UPLOAD.name
            )
        )
    }

    enum class TrashMoveResult {
        MOVED,
        MISSING,
        FAILED
    }
}
