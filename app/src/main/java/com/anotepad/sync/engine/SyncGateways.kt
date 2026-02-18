package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveChange
import com.anotepad.sync.DriveFile
import com.anotepad.sync.DriveFolder
import com.anotepad.sync.SyncState
import com.anotepad.sync.db.SyncFolderEntity
import com.anotepad.sync.db.SyncItemEntity
import java.io.InputStream

data class LocalFileEntry(
    val relativePath: String,
    val lastModified: Long,
    val size: Long
)

data class DriveChangesPage(
    val items: List<DriveChange>,
    val nextPageToken: String?,
    val newStartPageToken: String?
)

interface PrefsGateway {
    suspend fun getPreferences(): AppPreferences
}

interface AuthGateway {
    suspend fun getAccessToken(): String?
    suspend fun revokeAccess()
}

interface DriveGateway {
    suspend fun findMarkerFolders(token: String): List<DriveFolder>
    suspend fun findFoldersByName(token: String, name: String): List<DriveFolder>
    suspend fun ensureMarkerFile(token: String, folderId: String, folderName: String)
    suspend fun listChildren(token: String, folderId: String, pageToken: String?): Pair<List<DriveFile>, String?>
    suspend fun findChildByName(token: String, parentId: String, name: String): DriveFile?
    suspend fun createFolder(token: String, name: String, parentId: String?): DriveFolder
    suspend fun createOrUpdateFile(
        token: String,
        fileId: String?,
        name: String,
        parentId: String,
        mimeType: String,
        contentLength: Long?,
        contentProvider: () -> InputStream?,
        appProperties: Map<String, String>
    ): DriveFile

    suspend fun trashFile(token: String, fileId: String)
    suspend fun deleteFile(token: String, fileId: String)
    suspend fun downloadFile(token: String, fileId: String, consumer: suspend (InputStream) -> Unit)
    suspend fun getStartPageToken(token: String): String
    suspend fun listChanges(token: String, pageToken: String): DriveChangesPage
    suspend fun getFileMetadata(token: String, fileId: String): DriveFile
}

interface LocalFsGateway {
    suspend fun listFilesRecursive(rootId: String): List<LocalFileEntry>
    suspend fun getFileMeta(rootId: String, relativePath: String): LocalFileEntry?
    suspend fun exists(rootId: String, relativePath: String): Boolean
    suspend fun openInputStream(rootId: String, relativePath: String): InputStream?
    suspend fun createFile(rootId: String, relativePath: String, mimeType: String): Boolean
    suspend fun writeStream(rootId: String, relativePath: String, input: InputStream): Boolean
    suspend fun computeHash(rootId: String, relativePath: String): String
    suspend fun moveFile(rootId: String, fromPath: String, toPath: String): Boolean
    suspend fun copyFile(rootId: String, fromPath: String, toPath: String): Boolean
    suspend fun deleteFile(rootId: String, relativePath: String): Boolean
    suspend fun ensureDirectory(rootId: String, relativePath: String): Boolean
    suspend fun deleteDirectory(rootId: String, relativePath: String): Boolean
    fun sanitizeFileName(input: String): String
    fun guessMimeType(name: String): String
}

interface SyncStore {
    suspend fun getAllItems(): List<SyncItemEntity>
    suspend fun getItemByPath(path: String): SyncItemEntity?
    suspend fun getItemByDriveId(driveFileId: String): SyncItemEntity?
    suspend fun upsertItem(item: SyncItemEntity)
    suspend fun deleteItemByPath(path: String)
    suspend fun getItemsByPathPrefix(path: String): List<SyncItemEntity>
    suspend fun deleteItemsByPathPrefix(path: String)

    suspend fun getFolderByPath(path: String): SyncFolderEntity?
    suspend fun getFolderByDriveId(driveFolderId: String): SyncFolderEntity?
    suspend fun getAllFolders(): List<SyncFolderEntity>
    suspend fun getFoldersByPathPrefix(path: String): List<SyncFolderEntity>
    suspend fun upsertFolder(path: String, driveFolderId: String)
    suspend fun deleteFolderByPath(path: String)
    suspend fun deleteFoldersByPathPrefix(path: String)

    suspend fun setSyncStatus(state: SyncState, message: String? = null, lastSyncedAt: Long? = null)

    suspend fun getDriveFolderId(): String?
    suspend fun setDriveFolderId(id: String)
    suspend fun getDriveFolderName(): String?
    suspend fun setDriveFolderName(name: String)
    suspend fun getStartPageToken(): String?
    suspend fun setStartPageToken(token: String)
    suspend fun setLastFullScanAt(timestamp: Long)
    suspend fun getLastFullScanAt(): Long?
}
