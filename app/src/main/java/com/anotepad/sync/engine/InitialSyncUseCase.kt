package com.anotepad.sync.engine

import com.anotepad.sync.DriveFile
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity

class InitialSyncUseCase(
    private val drive: DriveGateway,
    private val localFs: LocalFsGateway,
    private val store: SyncStore,
    private val folderPathResolver: FolderPathResolver,
    private val remoteTreeWalker: RemoteTreeWalker,
    private val executor: SyncExecutor
) {
    suspend fun execute(token: String, rootId: String, driveFolderId: String) {
        val remoteFiles = indexRemoteFiles(token, driveFolderId)
        val localFiles = localFs.listFilesRecursive(rootId)
            .filter { !isIgnoredPath(it.relativePath) }
            .associateBy { it.relativePath }
            .toMutableMap()

        for ((path, meta) in localFiles) {
            val remoteFile = remoteFiles.remove(path)
            if (remoteFile == null) {
                val uploaded = uploadLocalFile(
                    token = token,
                    rootId = rootId,
                    driveFolderId = driveFolderId,
                    relativePath = path,
                    driveFileId = null,
                    size = meta.size
                )
                val localHash = computeHashIfNeeded(
                    localFs = localFs,
                    rootId = rootId,
                    item = null,
                    relativePath = path,
                    lastModified = meta.lastModified,
                    size = meta.size
                )
                store.upsertItem(
                    SyncItemEntity(
                        localRelativePath = path,
                        localLastModified = meta.lastModified,
                        localSize = meta.size,
                        localHash = localHash,
                        driveFileId = uploaded.id,
                        driveModifiedTime = uploaded.modifiedTime,
                        lastSyncedAt = System.currentTimeMillis(),
                        syncState = SyncItemState.SYNCED.name,
                        lastError = null
                    )
                )
                continue
            }

            val remoteModified = remoteFile.modifiedTime ?: 0L
            if (meta.lastModified >= remoteModified) {
                val updated = uploadLocalFile(
                    token = token,
                    rootId = rootId,
                    driveFolderId = driveFolderId,
                    relativePath = path,
                    driveFileId = remoteFile.id,
                    size = meta.size
                )
                val localHash = computeHashIfNeeded(
                    localFs = localFs,
                    rootId = rootId,
                    item = null,
                    relativePath = path,
                    lastModified = meta.lastModified,
                    size = meta.size
                )
                store.upsertItem(
                    SyncItemEntity(
                        localRelativePath = path,
                        localLastModified = meta.lastModified,
                        localSize = meta.size,
                        localHash = localHash,
                        driveFileId = updated.id,
                        driveModifiedTime = updated.modifiedTime,
                        lastSyncedAt = System.currentTimeMillis(),
                        syncState = SyncItemState.SYNCED.name,
                        lastError = null
                    )
                )
            } else {
                pullFile(
                    token = token,
                    rootId = rootId,
                    relativePath = path,
                    remoteFile = remoteFile
                )
            }
        }

        for ((path, remoteFile) in remoteFiles) {
            pullFile(
                token = token,
                rootId = rootId,
                relativePath = path,
                remoteFile = remoteFile
            )
        }

        val freshToken = drive.getStartPageToken(token)
        store.setStartPageToken(freshToken)
        store.setLastFullScanAt(System.currentTimeMillis())
    }

    private suspend fun pullFile(
        token: String,
        rootId: String,
        relativePath: String,
        remoteFile: DriveFile
    ) {
        val plan = SyncPlan()
            .add(
                SyncOperation.DownloadFile(
                    token = token,
                    driveFileId = remoteFile.id,
                    rootId = rootId,
                    relativePath = relativePath,
                    mimeType = localFs.guessMimeType(relativePath)
                )
            )
        val result = executor.execute(plan)
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

    private suspend fun uploadLocalFile(
        token: String,
        rootId: String,
        driveFolderId: String,
        relativePath: String,
        driveFileId: String?,
        size: Long
    ): DriveFile {
        val parentId = folderPathResolver.ensureDriveFolderForPath(token, driveFolderId, relativePath)
        val key = "upload:$relativePath"
        val plan = SyncPlan()
            .add(
                SyncOperation.UploadFile(
                    token = token,
                    rootId = rootId,
                    relativePath = relativePath,
                    parentId = parentId,
                    driveFileId = driveFileId,
                    mimeType = localFs.guessMimeType(relativePath),
                    contentLength = size,
                    appProperties = mapOf("localRelativePath" to relativePath),
                    resultKey = key
                )
            )
        val result = executor.execute(plan)
        return requireNotNull(result.uploaded[key]) {
            "Upload result is missing for path=$relativePath"
        }
    }

    private suspend fun indexRemoteFiles(
        token: String,
        driveFolderId: String
    ): MutableMap<String, DriveFile> {
        val remoteFiles = mutableMapOf<String, DriveFile>()
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
                    val existing = remoteFiles[relativePath]
                    val existingModified = existing?.modifiedTime ?: 0L
                    val candidateModified = file.modifiedTime ?: 0L
                    if (existing == null || candidateModified >= existingModified) {
                        remoteFiles[relativePath] = file
                    }
                }
            }
        )
        return remoteFiles
    }
}
