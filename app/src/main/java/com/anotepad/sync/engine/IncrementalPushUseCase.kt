package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity

class IncrementalPushUseCase(
    private val localFs: LocalFsGateway,
    private val store: SyncStore,
    private val folderPathResolver: FolderPathResolver,
    private val conflictResolver: ConflictResolver,
    private val executor: SyncExecutor
) {
    suspend fun execute(
        token: String,
        prefs: AppPreferences,
        rootId: String,
        driveFolderId: String
    ) {
        val remoteDeletePolicy = resolveRemoteDeletePolicy(prefs.driveSyncRemoteDeletePolicy)

        // Stage 4 optimization: single snapshot per run.
        val localMap = localFs.listFilesRecursive(rootId)
            .filter { !isIgnoredPath(it.relativePath) }
            .associateBy { it.relativePath }

        val existing = store.getAllItems().associateBy { it.localRelativePath }

        for ((path, meta) in localMap) {
            val item = existing[path]
            val localHash = computeHashIfNeeded(
                localFs = localFs,
                rootId = rootId,
                item = item,
                relativePath = path,
                lastModified = meta.lastModified,
                size = meta.size
            )
            val shouldUpload = item == null ||
                item.localHash != localHash ||
                item.driveFileId == null ||
                item.syncState == SyncItemState.PENDING_UPLOAD.name
            if (!shouldUpload) continue

            if (item != null && item.driveFileId != null && item.lastSyncedAt != null) {
                val localChangedAfterSync = meta.lastModified > item.lastSyncedAt
                val remoteChangedAfterSync = (item.driveModifiedTime ?: 0L) > item.lastSyncedAt
                if (localChangedAfterSync && remoteChangedAfterSync) {
                    conflictResolver.createConflictCopyFromRemote(
                        token = token,
                        rootId = rootId,
                        driveFileId = item.driveFileId,
                        originPath = path
                    )
                }
            }

            val parentId = folderPathResolver.ensureDriveFolderForPath(token, driveFolderId, path)
            val name = path.substringAfterLast('/')
            val driveFileId = item?.driveFileId ?: folderPathResolver.findChildByNameCached(token, parentId, name)?.id
            val uploadKey = "upload:$path"
            val result = executor.execute(
                SyncPlan().add(
                    SyncOperation.UploadFile(
                        token = token,
                        rootId = rootId,
                        relativePath = path,
                        parentId = parentId,
                        driveFileId = driveFileId,
                        mimeType = localFs.guessMimeType(name),
                        contentLength = meta.size,
                        appProperties = mapOf("localRelativePath" to path),
                        resultKey = uploadKey
                    )
                )
            )
            val uploaded = requireNotNull(result.uploaded[uploadKey]) {
                "Upload result is missing for path=$path"
            }
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
        }

        val deletePlan = SyncPlan()
        for ((path, item) in existing) {
            if (localMap.containsKey(path)) continue
            if (!isIgnoredPath(path)) {
                val driveId = item.driveFileId
                if (!driveId.isNullOrBlank()) {
                    deletePlan.add(
                        SyncOperation.DeleteRemote(
                            token = token,
                            driveFileId = driveId,
                            policy = remoteDeletePolicy
                        )
                    )
                }
            }
            deletePlan.add(SyncOperation.DeleteItemByPath(path))
        }
        executor.execute(deletePlan)
    }
}
