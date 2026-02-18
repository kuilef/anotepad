package com.anotepad.sync.engine

import com.anotepad.sync.DriveFile
import com.anotepad.sync.RemoteDeletePolicy
import com.anotepad.sync.db.SyncFolderEntity
import com.anotepad.sync.db.SyncItemEntity

data class SyncPlan(
    val operations: MutableList<SyncOperation> = mutableListOf()
) {
    fun add(operation: SyncOperation): SyncPlan {
        operations.add(operation)
        return this
    }
}

sealed class SyncOperation {
    data class DownloadFile(
        val token: String,
        val driveFileId: String,
        val rootId: String,
        val relativePath: String,
        val mimeType: String
    ) : SyncOperation()

    data class UploadFile(
        val token: String,
        val rootId: String,
        val relativePath: String,
        val parentId: String,
        val driveFileId: String?,
        val mimeType: String,
        val contentLength: Long,
        val appProperties: Map<String, String>,
        val resultKey: String
    ) : SyncOperation()

    data class MoveLocalFile(
        val rootId: String,
        val fromPath: String,
        val toPath: String
    ) : SyncOperation()

    data class DeleteRemote(
        val token: String,
        val driveFileId: String,
        val policy: RemoteDeletePolicy
    ) : SyncOperation()

    data class UpsertItem(
        val item: SyncItemEntity
    ) : SyncOperation()

    data class DeleteItemByPath(
        val localRelativePath: String
    ) : SyncOperation()

    data class UpsertFolder(
        val folder: SyncFolderEntity
    ) : SyncOperation()

    data class DeleteFolderByPath(
        val localRelativePath: String
    ) : SyncOperation()
}

data class DownloadedMeta(
    val lastModified: Long,
    val size: Long,
    val hash: String
)

data class SyncExecutionResult(
    val uploaded: Map<String, DriveFile> = emptyMap(),
    val downloaded: Map<String, DownloadedMeta> = emptyMap(),
    val movedFiles: Set<String> = emptySet()
)

class SyncExecutor(
    private val drive: DriveGateway,
    private val localFs: LocalFsGateway,
    private val store: SyncStore
) {
    suspend fun execute(plan: SyncPlan): SyncExecutionResult {
        val uploaded = mutableMapOf<String, DriveFile>()
        val downloaded = mutableMapOf<String, DownloadedMeta>()
        val moved = mutableSetOf<String>()

        for (operation in plan.operations) {
            when (operation) {
                is SyncOperation.DownloadFile -> {
                    localFs.createFile(
                        rootId = operation.rootId,
                        relativePath = operation.relativePath,
                        mimeType = operation.mimeType
                    )
                    drive.downloadFile(operation.token, operation.driveFileId) { input ->
                        localFs.writeStream(operation.rootId, operation.relativePath, input)
                    }
                    val meta = localFs.getFileMeta(operation.rootId, operation.relativePath)
                    val hash = localFs.computeHash(operation.rootId, operation.relativePath)
                    downloaded[operation.relativePath] = DownloadedMeta(
                        lastModified = meta?.lastModified ?: System.currentTimeMillis(),
                        size = meta?.size ?: 0L,
                        hash = hash
                    )
                }

                is SyncOperation.UploadFile -> {
                    val uploadedFile = drive.createOrUpdateFile(
                        token = operation.token,
                        fileId = operation.driveFileId,
                        name = operation.relativePath.substringAfterLast('/'),
                        parentId = operation.parentId,
                        mimeType = operation.mimeType,
                        contentLength = operation.contentLength,
                        contentProvider = {
                            localFs.openInputStream(operation.rootId, operation.relativePath)
                        },
                        appProperties = operation.appProperties
                    )
                    uploaded[operation.resultKey] = uploadedFile
                }

                is SyncOperation.MoveLocalFile -> {
                    if (localFs.moveFile(operation.rootId, operation.fromPath, operation.toPath)) {
                        moved += operation.toPath
                    }
                }

                is SyncOperation.DeleteRemote -> {
                    when (operation.policy) {
                        RemoteDeletePolicy.TRASH -> drive.trashFile(operation.token, operation.driveFileId)
                        RemoteDeletePolicy.DELETE -> drive.deleteFile(operation.token, operation.driveFileId)
                        RemoteDeletePolicy.IGNORE -> Unit
                    }
                }

                is SyncOperation.UpsertItem -> store.upsertItem(operation.item)
                is SyncOperation.DeleteItemByPath -> store.deleteItemByPath(operation.localRelativePath)
                is SyncOperation.UpsertFolder -> store.upsertFolder(
                    operation.folder.localRelativePath,
                    operation.folder.driveFolderId
                )

                is SyncOperation.DeleteFolderByPath -> store.deleteFolderByPath(operation.localRelativePath)
            }
        }

        return SyncExecutionResult(
            uploaded = uploaded,
            downloaded = downloaded,
            movedFiles = moved
        )
    }
}
