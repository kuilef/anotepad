package com.anotepad.sync

import android.net.Uri
import com.anotepad.data.AppPreferences
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.db.SyncItemEntity
import kotlinx.coroutines.flow.first
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SyncEngine(
    private val preferencesRepository: PreferencesRepository,
    private val fileRepository: FileRepository,
    private val syncRepository: SyncRepository,
    private val authManager: DriveAuthManager,
    private val driveClient: DriveClient
) {
    suspend fun runSync(): SyncResult {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.driveSyncEnabled) {
            syncRepository.setSyncStatus(SyncState.IDLE, "Sync disabled")
            return SyncResult.Skipped
        }
        if (prefs.driveSyncPaused) {
            syncRepository.setSyncStatus(SyncState.PENDING, "Sync paused")
            return SyncResult.Skipped
        }
        val rootUri = prefs.rootTreeUri?.let(Uri::parse)
        if (rootUri == null) {
            syncRepository.setSyncStatus(SyncState.ERROR, "No local folder selected")
            return SyncResult.Failure(authError = false)
        }
        val token = authManager.getAccessToken()
        if (token.isNullOrBlank()) {
            syncRepository.setSyncStatus(SyncState.ERROR, "Sign in required")
            return SyncResult.Failure(authError = true)
        }
        syncRepository.setSyncStatus(SyncState.RUNNING, "Syncing...")

        val folderId = ensureDriveFolder(token, prefs)

        pushLocalChanges(token, prefs, rootUri, folderId)
        pullRemoteChanges(token, prefs, rootUri, folderId)

        syncRepository.setSyncStatus(
            SyncState.SYNCED,
            "Synced",
            lastSyncedAt = System.currentTimeMillis()
        )
        return SyncResult.Success
    }

    private suspend fun ensureDriveFolder(token: String, prefs: AppPreferences): String {
        val storedId = syncRepository.getDriveFolderId()
        if (!storedId.isNullOrBlank()) return storedId
        val folderName = syncRepository.getDriveFolderName()
            ?: prefs.driveSyncFolderName
        val folder = driveClient.createFolder(token, folderName, null)
        syncRepository.setDriveFolderId(folder.id)
        syncRepository.setDriveFolderName(folder.name)
        return folder.id
    }

    private suspend fun pushLocalChanges(
        token: String,
        prefs: AppPreferences,
        rootUri: Uri,
        driveFolderId: String
    ) {
        val remoteDeletePolicy = resolveRemoteDeletePolicy(prefs)
        val localFiles = fileRepository.listFilesRecursive(rootUri)
        val localMap = mutableMapOf<String, LocalFileMeta>()
        for (node in localFiles) {
            val relativePath = fileRepository.getRelativePath(rootUri, node.uri) ?: continue
            val lastModified = fileRepository.getLastModified(node.uri) ?: 0L
            val size = fileRepository.getSize(node.uri) ?: 0L
            localMap[relativePath] = LocalFileMeta(node.uri, lastModified, size)
        }

        val existing = syncRepository.getAllItems().associateBy { it.localRelativePath }

        for ((path, meta) in localMap) {
            val item = existing[path]
            val localHash = computeHashIfNeeded(item, meta)
            val shouldUpload = item == null || item.localHash != localHash
            if (!shouldUpload) continue
            if (item != null && item.driveFileId != null && item.lastSyncedAt != null) {
                val localChangedAfterSync = meta.lastModified > item.lastSyncedAt
                val remoteChangedAfterSync = (item.driveModifiedTime ?: 0L) > item.lastSyncedAt
                if (localChangedAfterSync && remoteChangedAfterSync) {
                    val remoteContent = driveClient.downloadFile(token, item.driveFileId)
                    val conflictName = buildConflictName(path)
                    val conflictUri = fileRepository.createFileByRelativePath(
                        rootUri,
                        conflictName,
                        fileRepository.guessMimeType(conflictName)
                    )
                    if (conflictUri != null) {
                        fileRepository.writeText(conflictUri, remoteContent)
                        syncRepository.upsertItem(
                            SyncItemEntity(
                                localRelativePath = conflictName,
                                localLastModified = fileRepository.getLastModified(conflictUri)
                                    ?: System.currentTimeMillis(),
                                localSize = fileRepository.getSize(conflictUri)
                                    ?: remoteContent.length.toLong(),
                                localHash = sha256(remoteContent.toByteArray(Charsets.UTF_8)),
                                driveFileId = null,
                                driveModifiedTime = null,
                                lastSyncedAt = System.currentTimeMillis(),
                                syncState = SyncItemState.CONFLICT.name,
                                lastError = null
                            )
                        )
                    }
                }
            }

            val parentId = ensureDriveFolderForPath(token, driveFolderId, path)
            val name = path.substringAfterLast('/')
            val mimeType = fileRepository.guessMimeType(name)
            val content = fileRepository.readText(meta.uri).toByteArray(Charsets.UTF_8)
            val appProps = mapOf("localRelativePath" to path)
            val updated = driveClient.createOrUpdateFile(
                token = token,
                fileId = item?.driveFileId,
                name = name,
                parentId = parentId,
                mimeType = mimeType,
                content = content,
                appProperties = appProps
            )
            syncRepository.upsertItem(
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
        }

        for ((path, item) in existing) {
            if (localMap.containsKey(path)) continue
            val driveId = item.driveFileId ?: continue
            when (remoteDeletePolicy) {
                RemoteDeletePolicy.TRASH -> driveClient.trashFile(token, driveId)
                RemoteDeletePolicy.DELETE -> driveClient.deleteFile(token, driveId)
                RemoteDeletePolicy.IGNORE -> {}
            }
            syncRepository.deleteItemByPath(path)
        }
    }

    private suspend fun pullRemoteChanges(
        token: String,
        prefs: AppPreferences,
        rootUri: Uri,
        driveFolderId: String
    ) {
        val startToken = syncRepository.getStartPageToken()
        if (startToken.isNullOrBlank()) {
            initialRemoteScan(token, prefs, rootUri, driveFolderId)
            val freshToken = driveClient.getStartPageToken(token)
            syncRepository.setStartPageToken(freshToken)
            syncRepository.setLastFullScanAt(System.currentTimeMillis())
            return
        }
        var pageToken: String? = startToken
        while (!pageToken.isNullOrBlank()) {
            val result = driveClient.listChanges(token, pageToken)
            for (change in result.items) {
                handleRemoteChange(token, prefs, rootUri, driveFolderId, change)
            }
            pageToken = result.nextPageToken
        }
        syncRepository.setStartPageToken(driveClient.getStartPageToken(token))
    }

    private suspend fun initialRemoteScan(
        token: String,
        prefs: AppPreferences,
        rootUri: Uri,
        driveFolderId: String
    ) {
        val queue = ArrayDeque<DriveFolderNode>()
        queue.add(DriveFolderNode(driveFolderId, ""))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            var pageToken: String? = null
            do {
                val list = driveClient.listChildren(token, current.id, pageToken)
                for (file in list.items) {
                    if (file.mimeType == DRIVE_FOLDER_MIME) {
                        val nextPath = if (current.relativePath.isBlank()) file.name else "${current.relativePath}/${file.name}"
                        syncRepository.upsertFolder(nextPath, file.id)
                        queue.add(DriveFolderNode(file.id, nextPath))
                    } else if (isSupportedNote(file.name)) {
                        val relativePath = if (current.relativePath.isBlank()) {
                            file.name
                        } else {
                            "${current.relativePath}/${file.name}"
                        }
                        pullFileIfNeeded(token, rootUri, relativePath, file)
                    }
                }
                pageToken = list.nextPageToken
            } while (!pageToken.isNullOrBlank())
        }
    }

    private suspend fun handleRemoteChange(
        token: String,
        prefs: AppPreferences,
        rootUri: Uri,
        driveFolderId: String,
        change: DriveChange
    ) {
        if (change.removed) {
            handleRemoteDeletion(prefs, rootUri, change.fileId)
            return
        }
        val file = change.file ?: driveClient.getFileMetadata(token, change.fileId)
        if (file.trashed) {
            handleRemoteDeletion(prefs, rootUri, file.id)
            return
        }
        if (file.mimeType == DRIVE_FOLDER_MIME) {
            handleRemoteFolderChange(token, rootUri, driveFolderId, file)
            return
        }
        handleRemoteFileChange(token, rootUri, driveFolderId, file)
    }

    private suspend fun handleRemoteFileChange(
        token: String,
        rootUri: Uri,
        driveFolderId: String,
        remoteFile: DriveFile
    ) {
        if (!isSupportedNote(remoteFile.name)) return
        val existingById = syncRepository.getItemByDriveId(remoteFile.id)
        val parentPath = resolveParentPathWithFetch(token, driveFolderId, remoteFile.parents)
        val resolvedPath = when {
            parentPath != null -> {
                if (parentPath.isBlank()) remoteFile.name else "$parentPath/${remoteFile.name}"
            }
            existingById != null -> {
                val parent = existingById.localRelativePath.substringBeforeLast('/', "")
                if (parent.isBlank()) remoteFile.name else "$parent/${remoteFile.name}"
            }
            else -> remoteFile.appProperties["localRelativePath"]
        }
        if (resolvedPath.isNullOrBlank()) return
        val uniquePath = ensureUniqueLocalPath(rootUri, resolvedPath, remoteFile.id)
        var movedLocally = false
        if (existingById != null && existingById.localRelativePath != uniquePath) {
            val movedUri = moveLocalFile(rootUri, existingById.localRelativePath, uniquePath)
            val updated = existingById.copy(
                localRelativePath = uniquePath,
                localLastModified = movedUri?.let { fileRepository.getLastModified(it) }
                    ?: existingById.localLastModified,
                localSize = movedUri?.let { fileRepository.getSize(it) } ?: existingById.localSize
            )
            syncRepository.deleteItemByPath(existingById.localRelativePath)
            syncRepository.upsertItem(updated)
            movedLocally = movedUri != null
        }
        pullFileIfNeeded(token, rootUri, uniquePath, remoteFile, suppressLocalConflict = movedLocally)
    }

    private suspend fun handleRemoteFolderChange(
        token: String,
        rootUri: Uri,
        driveFolderId: String,
        folder: DriveFile
    ) {
        if (folder.id == driveFolderId) return
        val parentPath = resolveParentPathWithFetch(token, driveFolderId, folder.parents) ?: return
        val newPath = if (parentPath.isBlank()) folder.name else "$parentPath/${folder.name}"
        val existing = syncRepository.getFolderByDriveId(folder.id)
        if (existing == null) {
            fileRepository.resolveDirByRelativePath(rootUri, newPath, create = true)
            syncRepository.upsertFolder(newPath, folder.id)
            return
        }
        if (existing.localRelativePath == newPath) {
            fileRepository.resolveDirByRelativePath(rootUri, newPath, create = true)
            return
        }
        applyFolderMove(rootUri, existing.localRelativePath, newPath)
        syncRepository.deleteFolderByPath(existing.localRelativePath)
        syncRepository.upsertFolder(newPath, folder.id)
    }

    private suspend fun pullFileIfNeeded(
        token: String,
        rootUri: Uri,
        relativePath: String,
        remoteFile: DriveFile,
        suppressLocalConflict: Boolean = false
    ) {
        val existing = syncRepository.getItemByPath(relativePath)
        val localUri = fileRepository.findFileByRelativePath(rootUri, relativePath)
        val localModified = localUri?.let { fileRepository.getLastModified(it) }
        val lastSynced = existing?.lastSyncedAt ?: 0L
        val remoteModified = remoteFile.modifiedTime ?: 0L
        val localChanged = localModified != null && localModified > lastSynced
        val remoteChanged = remoteModified > lastSynced
        if (localChanged && remoteChanged && !suppressLocalConflict) {
            val conflictName = buildConflictName(relativePath)
            val content = driveClient.downloadFile(token, remoteFile.id)
            val conflictUri = fileRepository.createFileByRelativePath(
                rootUri,
                conflictName,
                fileRepository.guessMimeType(conflictName)
            ) ?: return
            fileRepository.writeText(conflictUri, content)
            syncRepository.upsertItem(
                SyncItemEntity(
                    localRelativePath = conflictName,
                    localLastModified = fileRepository.getLastModified(conflictUri) ?: System.currentTimeMillis(),
                    localSize = fileRepository.getSize(conflictUri) ?: content.length.toLong(),
                    localHash = sha256(content.toByteArray(Charsets.UTF_8)),
                    driveFileId = null,
                    driveModifiedTime = null,
                    lastSyncedAt = System.currentTimeMillis(),
                    syncState = SyncItemState.CONFLICT.name,
                    lastError = null
                )
            )
            return
        }
        if (!remoteChanged) return
        val content = driveClient.downloadFile(token, remoteFile.id)
        val targetUri = localUri ?: fileRepository.createFileByRelativePath(
            rootUri,
            relativePath,
            fileRepository.guessMimeType(relativePath)
        )
        if (targetUri != null) {
            fileRepository.writeText(targetUri, content)
            syncRepository.upsertItem(
                SyncItemEntity(
                    localRelativePath = relativePath,
                    localLastModified = fileRepository.getLastModified(targetUri) ?: System.currentTimeMillis(),
                    localSize = fileRepository.getSize(targetUri) ?: content.length.toLong(),
                    localHash = sha256(content.toByteArray(Charsets.UTF_8)),
                    driveFileId = remoteFile.id,
                    driveModifiedTime = remoteFile.modifiedTime,
                    lastSyncedAt = System.currentTimeMillis(),
                    syncState = SyncItemState.SYNCED.name,
                    lastError = null
                )
            )
        }
    }

    private suspend fun handleRemoteDeletion(
        prefs: AppPreferences,
        rootUri: Uri,
        driveFileId: String
    ) {
        if (prefs.driveSyncIgnoreRemoteDeletes) return
        val folder = syncRepository.getFolderByDriveId(driveFileId)
        if (folder != null) {
            handleRemoteFolderDeletion(rootUri, folder.localRelativePath)
            return
        }
        handleRemoteFileDeletion(rootUri, driveFileId)
    }

    private suspend fun handleRemoteFileDeletion(rootUri: Uri, driveFileId: String) {
        val existing = syncRepository.getItemByDriveId(driveFileId) ?: return
        val localUri = fileRepository.findFileByRelativePath(rootUri, existing.localRelativePath)
        val localModified = localUri?.let { fileRepository.getLastModified(it) } ?: 0L
        val lastSynced = existing.lastSyncedAt ?: 0L
        if (localModified > lastSynced) {
            syncRepository.upsertItem(
                existing.copy(
                    driveFileId = null,
                    driveModifiedTime = null,
                    syncState = SyncItemState.PENDING_UPLOAD.name
                )
            )
            return
        }
        moveLocalToTrash(rootUri, existing.localRelativePath)
        syncRepository.deleteItemByPath(existing.localRelativePath)
    }

    private suspend fun handleRemoteFolderDeletion(
        rootUri: Uri,
        folderPath: String
    ) {
        val items = syncRepository.getAllItems()
            .filter { it.localRelativePath == folderPath || it.localRelativePath.startsWith("$folderPath/") }
        for (item in items) {
            val localUri = fileRepository.findFileByRelativePath(rootUri, item.localRelativePath)
            val localModified = localUri?.let { fileRepository.getLastModified(it) } ?: 0L
            val lastSynced = item.lastSyncedAt ?: 0L
            if (localModified > lastSynced) {
                syncRepository.upsertItem(
                    item.copy(
                        driveFileId = null,
                        driveModifiedTime = null,
                        syncState = SyncItemState.PENDING_UPLOAD.name
                    )
                )
            } else {
                moveLocalToTrash(rootUri, item.localRelativePath)
                syncRepository.deleteItemByPath(item.localRelativePath)
            }
        }
        val folders = syncRepository.getAllFolders()
            .filter { it.localRelativePath == folderPath || it.localRelativePath.startsWith("$folderPath/") }
        for (folder in folders) {
            syncRepository.deleteFolderByPath(folder.localRelativePath)
        }
        fileRepository.deleteDirectoryByRelativePath(rootUri, folderPath)
    }

    private suspend fun ensureDriveFolderForPath(token: String, rootFolderId: String, relativePath: String): String {
        val dirPath = relativePath.substringBeforeLast('/', "")
        if (dirPath.isBlank()) return rootFolderId
        val existing = syncRepository.getFolderByPath(dirPath)
        if (existing != null) return existing.driveFolderId
        var currentId = rootFolderId
        var currentPath = ""
        for (segment in dirPath.split('/')) {
            currentPath = if (currentPath.isBlank()) segment else "$currentPath/$segment"
            val cached = syncRepository.getFolderByPath(currentPath)
            if (cached != null) {
                currentId = cached.driveFolderId
            } else {
                val created = driveClient.createFolder(token, segment, currentId)
                syncRepository.upsertFolder(currentPath, created.id)
                currentId = created.id
            }
        }
        return currentId
    }

    private suspend fun moveLocalToTrash(rootUri: Uri, relativePath: String) {
        val fileUri = fileRepository.findFileByRelativePath(rootUri, relativePath) ?: return
        val trashDir = ".trash"
        val trashUri = fileRepository.resolveDirByRelativePath(rootUri, trashDir, create = true) ?: return
        val name = relativePath.substringAfterLast('/')
        val newName = buildConflictName("$trashDir/$name")
        fileRepository.copyFile(fileUri, trashUri, newName.substringAfterLast('/'))
        fileRepository.deleteFile(fileUri)
    }

    private suspend fun resolveParentPathWithFetch(
        token: String,
        driveFolderId: String,
        parents: List<String>
    ): String? {
        for (parentId in parents) {
            val mapping = syncRepository.getFolderByDriveId(parentId)?.localRelativePath
            if (mapping != null) {
                return mapping
            }
        }
        if (parents.contains(driveFolderId)) return ""
        for (parentId in parents) {
            val resolved = buildFolderPathFromDrive(token, driveFolderId, parentId)
            if (resolved != null) return resolved
        }
        return null
    }

    private suspend fun buildFolderPathFromDrive(
        token: String,
        driveFolderId: String,
        folderId: String
    ): String? {
        val chain = mutableListOf<DriveFile>()
        var currentId = folderId
        val visited = mutableSetOf<String>()
        while (true) {
            if (!visited.add(currentId)) return null
            val cached = syncRepository.getFolderByDriveId(currentId)
            if (cached != null) {
                val path = appendPath(cached.localRelativePath, chain.asReversed().map { it.name })
                upsertFolderChain(cached.localRelativePath, chain)
                return path
            }
            if (currentId == driveFolderId) {
                val path = chain.asReversed().joinToString("/")
                upsertFolderChain("", chain)
                return path
            }
            val meta = driveClient.getFileMetadata(token, currentId)
            if (meta.mimeType != DRIVE_FOLDER_MIME) return null
            chain.add(meta)
            if (meta.parents.contains(driveFolderId)) {
                val path = chain.asReversed().joinToString("/")
                upsertFolderChain("", chain)
                return path
            }
            val next = meta.parents.firstOrNull() ?: return null
            currentId = next
        }
    }

    private suspend fun upsertFolderChain(basePath: String, chain: List<DriveFile>) {
        var currentPath = basePath
        for (folder in chain.asReversed()) {
            currentPath = if (currentPath.isBlank()) folder.name else "$currentPath/${folder.name}"
            syncRepository.upsertFolder(currentPath, folder.id)
        }
    }

    private fun appendPath(base: String, segments: List<String>): String {
        val tail = segments.joinToString("/")
        return when {
            base.isBlank() -> tail
            tail.isBlank() -> base
            else -> "$base/$tail"
        }
    }

    private suspend fun applyFolderMove(rootUri: Uri, oldPath: String, newPath: String) {
        if (oldPath == newPath) return
        val items = syncRepository.getAllItems()
            .filter { it.localRelativePath == oldPath || it.localRelativePath.startsWith("$oldPath/") }
        for (item in items) {
            val targetPath = replacePathPrefix(item.localRelativePath, oldPath, newPath)
            val movedUri = moveLocalFile(rootUri, item.localRelativePath, targetPath)
            val updated = item.copy(
                localRelativePath = targetPath,
                localLastModified = movedUri?.let { fileRepository.getLastModified(it) } ?: item.localLastModified,
                localSize = movedUri?.let { fileRepository.getSize(it) } ?: item.localSize
            )
            syncRepository.deleteItemByPath(item.localRelativePath)
            syncRepository.upsertItem(updated)
        }
        val folders = syncRepository.getAllFolders()
            .filter { it.localRelativePath == oldPath || it.localRelativePath.startsWith("$oldPath/") }
        for (folder in folders) {
            val targetPath = replacePathPrefix(folder.localRelativePath, oldPath, newPath)
            syncRepository.deleteFolderByPath(folder.localRelativePath)
            syncRepository.upsertFolder(targetPath, folder.driveFolderId)
            fileRepository.resolveDirByRelativePath(rootUri, targetPath, create = true)
        }
        fileRepository.deleteDirectoryByRelativePath(rootUri, oldPath)
    }

    private suspend fun moveLocalFile(rootUri: Uri, fromPath: String, toPath: String): Uri? {
        val fromUri = fileRepository.findFileByRelativePath(rootUri, fromPath) ?: return null
        val targetDir = toPath.substringBeforeLast('/', "")
        val targetDirUri = fileRepository.resolveDirByRelativePath(rootUri, targetDir, create = true) ?: return null
        val name = toPath.substringAfterLast('/')
        return fileRepository.moveFile(fromUri, targetDirUri, name)
    }

    private fun replacePathPrefix(path: String, oldPrefix: String, newPrefix: String): String {
        if (oldPrefix.isBlank()) return path
        val suffix = path.removePrefix(oldPrefix).trimStart('/')
        return if (suffix.isBlank()) {
            newPrefix
        } else if (newPrefix.isBlank()) {
            suffix
        } else {
            "$newPrefix/$suffix"
        }
    }

    private fun buildConflictName(relativePath: String): String {
        val base = relativePath.substringBeforeLast('.')
        val ext = relativePath.substringAfterLast('.', "")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date())
        val suffix = "conflict $stamp"
        return if (ext.isBlank()) "$base ($suffix)" else "$base ($suffix).$ext"
    }

    private fun isSupportedNote(name: String): Boolean {
        return name.lowercase(Locale.getDefault()).endsWith(".txt") ||
            name.lowercase(Locale.getDefault()).endsWith(".md")
    }

    private suspend fun computeHashIfNeeded(item: SyncItemEntity?, meta: LocalFileMeta): String {
        val shouldCompute = item == null || item.localLastModified != meta.lastModified || item.localSize != meta.size
        return if (shouldCompute) {
            fileRepository.computeHash(meta.uri)
        } else {
            item.localHash ?: ""
        }
    }

    private suspend fun ensureUniqueLocalPath(rootUri: Uri, desiredPath: String, driveId: String): String {
        val existingByPath = syncRepository.getItemByPath(desiredPath)
        val sameDrive = existingByPath?.driveFileId == driveId
        val localUri = fileRepository.findFileByRelativePath(rootUri, desiredPath)
        if ((existingByPath == null || sameDrive) && localUri == null) return desiredPath
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
            val candidateItem = syncRepository.getItemByPath(candidatePath)
            val candidateLocal = fileRepository.findFileByRelativePath(rootUri, candidatePath)
            if (candidateItem == null && candidateLocal == null) {
                return candidatePath
            }
            index++
        }
        return desiredPath
    }

    private fun resolveRemoteDeletePolicy(prefs: AppPreferences): RemoteDeletePolicy {
        return runCatching { RemoteDeletePolicy.valueOf(prefs.driveSyncRemoteDeletePolicy) }
            .getOrElse { RemoteDeletePolicy.TRASH }
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private data class LocalFileMeta(
        val uri: Uri,
        val lastModified: Long,
        val size: Long
    )

    private data class DriveFolderNode(
        val id: String,
        val relativePath: String
    )

    companion object {
        private const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val MAX_DUPLICATE_NAME_ATTEMPTS = 200
    }
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object Skipped : SyncResult()
    data class Failure(val authError: Boolean) : SyncResult()
}
