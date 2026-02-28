package com.anotepad.sync.engine

import com.anotepad.sync.db.SyncItemEntity
import java.util.UUID

class ConflictResolver(
    private val drive: DriveGateway,
    private val localFs: LocalFsGateway,
    private val store: SyncStore
) {
    suspend fun createConflictCopyFromRemote(
        token: String,
        rootId: String,
        driveFileId: String,
        originPath: String
    ): String? {
        val conflictPath = buildConflictName(originPath)
        val created = localFs.createFile(rootId, conflictPath, localFs.guessMimeType(conflictPath))
        if (!created) return null

        drive.downloadFile(token, driveFileId) { input ->
            localFs.writeStream(rootId, conflictPath, input)
        }

        val meta = localFs.getFileMeta(rootId, conflictPath)
        val hash = localFs.computeHash(rootId, conflictPath)
        store.upsertItem(
            SyncItemEntity(
                localRelativePath = conflictPath,
                localLastModified = meta?.lastModified ?: System.currentTimeMillis(),
                localSize = meta?.size ?: 0L,
                localHash = hash,
                driveFileId = null,
                driveModifiedTime = null,
                lastSyncedAt = System.currentTimeMillis(),
                syncState = com.anotepad.sync.SyncItemState.CONFLICT.name,
                lastError = null
            )
        )
        return conflictPath
    }

    suspend fun ensureUniqueLocalPath(rootId: String, desiredPath: String, driveId: String): String {
        val existingByPath = store.getItemByPath(desiredPath)
        val sameDrive = existingByPath?.driveFileId == driveId
        val localExists = localFs.exists(rootId, desiredPath)
        if ((existingByPath == null || sameDrive) && !localExists) return desiredPath
        if (sameDrive) return desiredPath

        val dirPath = desiredPath.substringBeforeLast('/', "")
        val fileName = desiredPath.substringAfterLast('/')
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        var index = 1
        while (index <= MAX_DUPLICATE_NAME_ATTEMPTS) {
            val candidateName = if (ext.isBlank()) {
                "$base ($index)"
            } else {
                "$base ($index).$ext"
            }
            val candidatePath = if (dirPath.isBlank()) candidateName else "$dirPath/$candidateName"
            val candidateItem = store.getItemByPath(candidatePath)
            val candidateLocal = localFs.exists(rootId, candidatePath)
            if (candidateItem == null && !candidateLocal) {
                return candidatePath
            }
            index++
        }
        while (true) {
            val candidateName = if (ext.isBlank()) {
                "$base (conflict-${UUID.randomUUID()})"
            } else {
                "$base (conflict-${UUID.randomUUID()}).$ext"
            }
            val candidatePath = if (dirPath.isBlank()) candidateName else "$dirPath/$candidateName"
            val candidateItem = store.getItemByPath(candidatePath)
            val candidateLocal = localFs.exists(rootId, candidatePath)
            if (candidateItem == null && !candidateLocal) {
                return candidatePath
            }
        }
    }
}
