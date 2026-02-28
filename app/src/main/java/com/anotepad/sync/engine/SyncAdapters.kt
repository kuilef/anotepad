package com.anotepad.sync.engine

import android.net.Uri
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.DriveAuthManager
import com.anotepad.sync.DriveClient
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncState
import com.anotepad.sync.db.SyncFolderEntity
import com.anotepad.sync.db.SyncItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.InputStream

class PrefsGatewayAdapter(
    private val preferencesRepository: PreferencesRepository
) : PrefsGateway {
    override suspend fun getPreferences() = preferencesRepository.preferencesFlow.first()
}

class AuthGatewayAdapter(
    private val authManager: DriveAuthManager
) : AuthGateway {
    override suspend fun getAccessToken(): String? = authManager.getAccessToken()

    override suspend fun invalidateAccessToken(): Boolean = authManager.invalidateAccessToken()

    override suspend fun revokeAccess() {
        authManager.revokeAccess()
    }
}

class DriveGatewayAdapter(
    private val driveClient: DriveClient
) : DriveGateway {
    override suspend fun findMarkerFolders(token: String) = driveClient.findMarkerFolders(token)

    override suspend fun findFoldersByName(token: String, name: String) = driveClient.findFoldersByName(token, name)

    override suspend fun ensureMarkerFile(token: String, folderId: String, folderName: String) {
        driveClient.ensureMarkerFile(token, folderId, folderName)
    }

    override suspend fun listChildren(
        token: String,
        folderId: String,
        pageToken: String?
    ): Pair<List<com.anotepad.sync.DriveFile>, String?> {
        val result = driveClient.listChildren(token, folderId, pageToken)
        return result.items to result.nextPageToken
    }

    override suspend fun findChildByName(token: String, parentId: String, name: String) =
        driveClient.findChildByName(token, parentId, name)

    override suspend fun createFolder(token: String, name: String, parentId: String?) =
        driveClient.createFolder(token, name, parentId)

    override suspend fun createOrUpdateFile(
        token: String,
        fileId: String?,
        name: String,
        parentId: String,
        mimeType: String,
        contentLength: Long?,
        contentProvider: () -> InputStream?,
        appProperties: Map<String, String>
    ) = driveClient.createOrUpdateFile(
        token = token,
        fileId = fileId,
        name = name,
        parentId = parentId,
        mimeType = mimeType,
        contentLength = contentLength,
        contentProvider = contentProvider,
        appProperties = appProperties
    )

    override suspend fun trashFile(token: String, fileId: String) {
        driveClient.trashFile(token, fileId)
    }

    override suspend fun deleteFile(token: String, fileId: String) {
        driveClient.deleteFile(token, fileId)
    }

    override suspend fun downloadFile(token: String, fileId: String, consumer: suspend (InputStream) -> Unit) {
        driveClient.downloadFile(token, fileId, consumer)
    }

    override suspend fun getStartPageToken(token: String): String = driveClient.getStartPageToken(token)

    override suspend fun listChanges(token: String, pageToken: String): DriveChangesPage {
        val result = driveClient.listChanges(token, pageToken)
        return DriveChangesPage(
            items = result.items,
            nextPageToken = result.nextPageToken,
            newStartPageToken = result.newStartPageToken
        )
    }

    override suspend fun getFileMetadata(token: String, fileId: String) =
        driveClient.getFileMetadata(token, fileId)
}

class LocalFsGatewayAdapter(
    private val fileRepository: FileRepository
) : LocalFsGateway {
    override suspend fun listFilesRecursive(rootId: String): List<LocalFileEntry> {
        val rootUri = requireAccessibleRootUri(rootId)
        return fileRepository.listFilesRecursive(rootUri).mapNotNull { node ->
            val relativePath = fileRepository.getRelativePath(rootUri, node.uri) ?: return@mapNotNull null
            val lastModified = fileRepository.getLastModified(node.uri) ?: 0L
            val size = fileRepository.getSize(node.uri) ?: 0L
            LocalFileEntry(relativePath, lastModified, size)
        }
    }

    override suspend fun getFileMeta(rootId: String, relativePath: String): LocalFileEntry? {
        val rootUri = requireAccessibleRootUri(rootId)
        val uri = fileRepository.findFileByRelativePath(rootUri, relativePath) ?: return null
        val lastModified = fileRepository.getLastModified(uri) ?: 0L
        val size = fileRepository.getSize(uri) ?: 0L
        return LocalFileEntry(relativePath, lastModified, size)
    }

    override suspend fun exists(rootId: String, relativePath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        return fileRepository.findFileByRelativePath(rootUri, relativePath) != null
    }

    override fun openInputStream(rootId: String, relativePath: String): InputStream? {
        val rootUri = requireAccessibleRootUriBlocking(rootId)
        val uri = runBlocking { fileRepository.findFileByRelativePath(rootUri, relativePath) } ?: return null
        return fileRepository.openInputStream(uri)
    }

    override suspend fun createFile(rootId: String, relativePath: String, mimeType: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        return fileRepository.createFileByRelativePath(rootUri, relativePath, mimeType) != null
    }

    override suspend fun writeStream(rootId: String, relativePath: String, input: InputStream): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        val existing = fileRepository.findFileByRelativePath(rootUri, relativePath)
            ?: fileRepository.createFileByRelativePath(rootUri, relativePath, fileRepository.guessMimeType(relativePath))
            ?: return false
        fileRepository.writeStream(existing, input)
        return true
    }

    override suspend fun computeHash(rootId: String, relativePath: String): String {
        val rootUri = requireAccessibleRootUri(rootId)
        val uri = fileRepository.findFileByRelativePath(rootUri, relativePath) ?: return ""
        return fileRepository.computeHash(uri)
    }

    override suspend fun moveFile(rootId: String, fromPath: String, toPath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        val fromUri = fileRepository.findFileByRelativePath(rootUri, fromPath) ?: return false
        val targetDir = toPath.substringBeforeLast('/', "")
        val targetDirUri = fileRepository.resolveDirByRelativePath(rootUri, targetDir, create = true) ?: return false
        val name = toPath.substringAfterLast('/')
        return fileRepository.moveFile(fromUri, targetDirUri, name) != null
    }

    override suspend fun copyFile(rootId: String, fromPath: String, toPath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        val fromUri = fileRepository.findFileByRelativePath(rootUri, fromPath) ?: return false
        val targetDir = toPath.substringBeforeLast('/', "")
        val targetDirUri = fileRepository.resolveDirByRelativePath(rootUri, targetDir, create = true) ?: return false
        val name = toPath.substringAfterLast('/')
        return fileRepository.copyFile(fromUri, targetDirUri, name) != null
    }

    override suspend fun deleteFile(rootId: String, relativePath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        val fileUri = fileRepository.findFileByRelativePath(rootUri, relativePath) ?: return false
        return fileRepository.deleteFile(fileUri)
    }

    override suspend fun ensureDirectory(rootId: String, relativePath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        if (relativePath.isBlank()) return true
        return fileRepository.resolveDirByRelativePath(rootUri, relativePath, create = true) != null
    }

    override suspend fun deleteDirectory(rootId: String, relativePath: String): Boolean {
        val rootUri = requireAccessibleRootUri(rootId)
        return fileRepository.deleteDirectoryByRelativePath(rootUri, relativePath)
    }

    override fun sanitizeFileName(input: String): String = fileRepository.sanitizeFileName(input)

    override fun guessMimeType(name: String): String = fileRepository.guessMimeType(name)

    private suspend fun requireAccessibleRootUri(rootId: String): Uri {
        val rootUri = Uri.parse(rootId)
        if (fileRepository.resolveDirByRelativePath(rootUri, "", create = false) != null) {
            return rootUri
        }
        throw LocalStorageUnavailableException()
    }

    private fun requireAccessibleRootUriBlocking(rootId: String): Uri {
        val rootUri = Uri.parse(rootId)
        if (runBlocking { fileRepository.resolveDirByRelativePath(rootUri, "", create = false) } != null) {
            return rootUri
        }
        throw LocalStorageUnavailableException()
    }
}

class SyncStoreAdapter(
    private val syncRepository: SyncRepository
) : SyncStore {
    override suspend fun getAllItems(): List<SyncItemEntity> = syncRepository.getAllItems()

    override suspend fun getItemByPath(path: String): SyncItemEntity? = syncRepository.getItemByPath(path)

    override suspend fun getItemByDriveId(driveFileId: String): SyncItemEntity? =
        syncRepository.getItemByDriveId(driveFileId)

    override suspend fun upsertItem(item: SyncItemEntity) = syncRepository.upsertItem(item)

    override suspend fun deleteItemByPath(path: String) = syncRepository.deleteItemByPath(path)

    override suspend fun getItemsByPathPrefix(path: String): List<SyncItemEntity> =
        syncRepository.getItemsByPathPrefix(path)

    override suspend fun deleteItemsByPathPrefix(path: String) = syncRepository.deleteItemsByPathPrefix(path)

    override suspend fun getFolderByPath(path: String): SyncFolderEntity? = syncRepository.getFolderByPath(path)

    override suspend fun getFolderByDriveId(driveFolderId: String): SyncFolderEntity? =
        syncRepository.getFolderByDriveId(driveFolderId)

    override suspend fun getAllFolders(): List<SyncFolderEntity> = syncRepository.getAllFolders()

    override suspend fun getFoldersByPathPrefix(path: String): List<SyncFolderEntity> =
        syncRepository.getFoldersByPathPrefix(path)

    override suspend fun upsertFolder(path: String, driveFolderId: String) =
        syncRepository.upsertFolder(path, driveFolderId)

    override suspend fun deleteFolderByPath(path: String) = syncRepository.deleteFolderByPath(path)

    override suspend fun deleteFoldersByPathPrefix(path: String) =
        syncRepository.deleteFoldersByPathPrefix(path)

    override suspend fun setSyncStatus(state: SyncState, message: String?, lastSyncedAt: Long?) {
        syncRepository.setSyncStatus(state, message, lastSyncedAt)
    }

    override suspend fun getDriveFolderId(): String? = syncRepository.getDriveFolderId()

    override suspend fun setDriveFolderId(id: String) {
        syncRepository.setDriveFolderId(id)
    }

    override suspend fun getDriveFolderName(): String? = syncRepository.getDriveFolderName()

    override suspend fun setDriveFolderName(name: String) {
        syncRepository.setDriveFolderName(name)
    }

    override suspend fun getStartPageToken(): String? = syncRepository.getStartPageToken()

    override suspend fun setStartPageToken(token: String) {
        syncRepository.setStartPageToken(token)
    }

    override suspend fun setLastFullScanAt(timestamp: Long) {
        syncRepository.setLastFullScanAt(timestamp)
    }

    override suspend fun getLastFullScanAt(): Long? = syncRepository.getLastFullScanAt()
}
