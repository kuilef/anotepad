package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveChange
import com.anotepad.sync.DriveFile
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity

class IncrementalPullUseCase(
    private val drive: DriveGateway,
    private val localFs: LocalFsGateway,
    private val store: SyncStore,
    private val folderPathResolver: FolderPathResolver,
    private val remoteTreeWalker: RemoteTreeWalker,
    private val conflictResolver: ConflictResolver,
    private val deleteResolver: DeleteResolver,
    private val executor: SyncExecutor
) {
    suspend fun execute(
        token: String,
        prefs: AppPreferences,
        rootId: String,
        driveFolderId: String,
        startPageToken: String?
    ) {
        if (startPageToken.isNullOrBlank()) {
            initialRemoteScan(token, rootId, driveFolderId)
            val freshToken = drive.getStartPageToken(token)
            store.setStartPageToken(freshToken)
            store.setLastFullScanAt(System.currentTimeMillis())
            return
        }

        var pageToken: String? = startPageToken
        var newStartPageToken: String? = null
        while (!pageToken.isNullOrBlank()) {
            val result = drive.listChanges(token, pageToken)
            for (change in result.items) {
                handleRemoteChange(token, prefs, rootId, driveFolderId, change)
            }
            if (!result.newStartPageToken.isNullOrBlank()) {
                newStartPageToken = result.newStartPageToken
            }
            pageToken = result.nextPageToken
        }

        if (!newStartPageToken.isNullOrBlank()) {
            store.setStartPageToken(newStartPageToken)
        }
    }

    private suspend fun initialRemoteScan(
        token: String,
        rootId: String,
        driveFolderId: String
    ) {
        remoteTreeWalker.walk(
            token = token,
            rootFolderId = driveFolderId,
            onFolder = { relativePath, folder ->
                if (!isIgnoredPath(relativePath)) {
                    store.upsertFolder(relativePath, folder.id)
                }
            },
            onFile = { relativePath, file ->
                if (!isIgnoredPath(relativePath) && isSupportedNote(relativePath.substringAfterLast('/'))) {
                    pullFileIfNeeded(token, rootId, relativePath, file)
                }
            }
        )
    }

    private suspend fun handleRemoteChange(
        token: String,
        prefs: AppPreferences,
        rootId: String,
        driveFolderId: String,
        change: DriveChange
    ) {
        if (change.removed) {
            deleteResolver.handleRemoteDeletion(prefs, rootId, change.fileId)
            return
        }
        val file = change.file ?: drive.getFileMetadata(token, change.fileId)
        if (file.trashed) {
            deleteResolver.handleRemoteDeletion(prefs, rootId, file.id)
            return
        }
        if (file.mimeType == DRIVE_FOLDER_MIME) {
            handleRemoteFolderChange(token, rootId, driveFolderId, file)
            return
        }
        handleRemoteFileChange(token, rootId, driveFolderId, file)
    }

    private suspend fun handleRemoteFileChange(
        token: String,
        rootId: String,
        driveFolderId: String,
        remoteFile: DriveFile
    ) {
        val safeName = folderPathResolver.sanitizeRemoteFileName(remoteFile)
        if (!isSupportedNote(safeName)) return

        var existingById = store.getItemByDriveId(remoteFile.id)
        val parentPath = folderPathResolver.resolveParentPathWithFetch(token, driveFolderId, remoteFile.parents)
        val resolvedPath = when {
            parentPath != null -> {
                if (parentPath.isBlank()) safeName else "$parentPath/$safeName"
            }

            existingById != null -> {
                val parent = existingById.localRelativePath.substringBeforeLast('/', "")
                if (parent.isBlank()) safeName else "$parent/$safeName"
            }

            else -> remoteFile.appProperties["localRelativePath"]
        }
        if (resolvedPath.isNullOrBlank()) return
        if (isIgnoredPath(resolvedPath)) return

        if (existingById == null) {
            existingById = adoptLocalItemForRemoteIfNeeded(rootId, resolvedPath, remoteFile)
        }

        val uniquePath = conflictResolver.ensureUniqueLocalPath(rootId, resolvedPath, remoteFile.id)
        var movedLocally = false
        if (existingById != null && existingById.localRelativePath != uniquePath) {
            if (localFs.moveFile(rootId, existingById.localRelativePath, uniquePath)) {
                val movedMeta = localFs.getFileMeta(rootId, uniquePath)
                val updated = existingById.copy(
                    localRelativePath = uniquePath,
                    localLastModified = movedMeta?.lastModified ?: existingById.localLastModified,
                    localSize = movedMeta?.size ?: existingById.localSize
                )
                store.deleteItemByPath(existingById.localRelativePath)
                store.upsertItem(updated)
                movedLocally = true
            }
        }

        pullFileIfNeeded(
            token = token,
            rootId = rootId,
            relativePath = uniquePath,
            remoteFile = remoteFile,
            suppressLocalConflict = movedLocally
        )
    }

    private suspend fun adoptLocalItemForRemoteIfNeeded(
        rootId: String,
        relativePath: String,
        remoteFile: DriveFile
    ): SyncItemEntity? {
        val existingByPath = store.getItemByPath(relativePath)
        if (existingByPath != null) {
            if (existingByPath.driveFileId == remoteFile.id) return existingByPath
            if (existingByPath.driveFileId == null) {
                val repaired = existingByPath.copy(
                    driveFileId = remoteFile.id,
                    driveModifiedTime = remoteFile.modifiedTime,
                    syncState = existingByPath.syncState,
                    lastError = null
                )
                store.upsertItem(repaired)
                return repaired
            }
            return null
        }

        val localMeta = localFs.getFileMeta(rootId, relativePath) ?: return null
        val localModified = localMeta.lastModified
        val remoteModified = remoteFile.modifiedTime ?: 0L
        val baseline = when {
            localModified > 0L && remoteModified > 0L -> minOf(localModified, remoteModified)
            localModified > 0L -> localModified
            remoteModified > 0L -> remoteModified
            else -> 0L
        }
        val localIsNewer = localModified > remoteModified
        val adopted = SyncItemEntity(
            localRelativePath = relativePath,
            localLastModified = localModified,
            localSize = localMeta.size,
            localHash = localFs.computeHash(rootId, relativePath),
            driveFileId = remoteFile.id,
            driveModifiedTime = remoteFile.modifiedTime,
            lastSyncedAt = baseline.takeIf { it > 0L },
            syncState = if (localIsNewer) SyncItemState.PENDING_UPLOAD.name else SyncItemState.SYNCED.name,
            lastError = null
        )
        store.upsertItem(adopted)
        return adopted
    }

    private suspend fun handleRemoteFolderChange(
        token: String,
        rootId: String,
        driveFolderId: String,
        folder: DriveFile
    ) {
        if (folder.id == driveFolderId) return

        val parentPath = folderPathResolver.resolveParentPathWithFetch(token, driveFolderId, folder.parents) ?: return
        val safeName = folderPathResolver.sanitizeRemoteFolderName(folder)
        val newPath = if (parentPath.isBlank()) safeName else "$parentPath/$safeName"
        if (isIgnoredPath(newPath)) return

        val existing = store.getFolderByDriveId(folder.id)
        if (existing == null) {
            localFs.ensureDirectory(rootId, newPath)
            store.upsertFolder(newPath, folder.id)
            return
        }

        if (existing.localRelativePath == newPath) {
            localFs.ensureDirectory(rootId, newPath)
            return
        }

        applyFolderMove(rootId, existing.localRelativePath, newPath)
        store.deleteFolderByPath(existing.localRelativePath)
        store.upsertFolder(newPath, folder.id)
    }

    private suspend fun pullFileIfNeeded(
        token: String,
        rootId: String,
        relativePath: String,
        remoteFile: DriveFile,
        suppressLocalConflict: Boolean = false
    ) {
        val existing = store.getItemByPath(relativePath)
        val localMeta = localFs.getFileMeta(rootId, relativePath)
        val localModified = localMeta?.lastModified
        val lastSynced = existing?.lastSyncedAt ?: 0L
        val remoteModified = remoteFile.modifiedTime ?: 0L

        val localChanged = localModified != null && localModified > lastSynced
        val remoteChanged = remoteModified > lastSynced
        if (localChanged && remoteChanged && !suppressLocalConflict) {
            conflictResolver.createConflictCopyFromRemote(
                token = token,
                rootId = rootId,
                driveFileId = remoteFile.id,
                originPath = relativePath
            )
            return
        }
        if (!remoteChanged) return

        val result = executor.execute(
            SyncPlan().add(
                SyncOperation.DownloadFile(
                    token = token,
                    driveFileId = remoteFile.id,
                    rootId = rootId,
                    relativePath = relativePath,
                    mimeType = localFs.guessMimeType(relativePath)
                )
            )
        )
        val downloaded = result.downloaded[relativePath]

        store.upsertItem(
            SyncItemEntity(
                localRelativePath = relativePath,
                localLastModified = downloaded?.lastModified ?: System.currentTimeMillis(),
                localSize = downloaded?.size ?: 0L,
                localHash = downloaded?.hash ?: "",
                driveFileId = remoteFile.id,
                driveModifiedTime = remoteFile.modifiedTime,
                lastSyncedAt = System.currentTimeMillis(),
                syncState = SyncItemState.SYNCED.name,
                lastError = null
            )
        )
    }

    private suspend fun applyFolderMove(rootId: String, oldPath: String, newPath: String) {
        if (oldPath == newPath) return

        val items = store.getItemsByPathPrefix(oldPath)
        for (item in items) {
            val targetPath = replacePathPrefix(item.localRelativePath, oldPath, newPath)
            localFs.moveFile(rootId, item.localRelativePath, targetPath)
            val movedMeta = localFs.getFileMeta(rootId, targetPath)
            val updated = item.copy(
                localRelativePath = targetPath,
                localLastModified = movedMeta?.lastModified ?: item.localLastModified,
                localSize = movedMeta?.size ?: item.localSize
            )
            store.deleteItemByPath(item.localRelativePath)
            store.upsertItem(updated)
        }

        val folders = store.getFoldersByPathPrefix(oldPath)
        for (folder in folders) {
            val targetPath = replacePathPrefix(folder.localRelativePath, oldPath, newPath)
            store.deleteFolderByPath(folder.localRelativePath)
            store.upsertFolder(targetPath, folder.driveFolderId)
            localFs.ensureDirectory(rootId, targetPath)
        }
        localFs.deleteDirectory(rootId, oldPath)
    }
}
