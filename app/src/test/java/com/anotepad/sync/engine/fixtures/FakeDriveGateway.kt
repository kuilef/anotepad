package com.anotepad.sync.engine.fixtures

import com.anotepad.sync.DriveChange
import com.anotepad.sync.DriveFile
import com.anotepad.sync.DriveFolder
import com.anotepad.sync.engine.DriveChangesPage
import com.anotepad.sync.engine.DriveGateway
import java.io.ByteArrayInputStream
import java.io.InputStream

class FakeDriveGateway : DriveGateway {
    data class RemoteRecord(
        var file: DriveFile,
        var content: ByteArray
    )

    val calls = mutableListOf<String>()

    var markerFolders: List<DriveFolder> = emptyList()
    val foldersByName = mutableMapOf<String, List<DriveFolder>>()
    var startPageToken: String = "start-token"
    var listChangesError: Exception? = null
    val listChangesErrors = ArrayDeque<Exception>()
    var getStartPageTokenError: Exception? = null

    private val records = mutableMapOf<String, RemoteRecord>()
    private val changesByToken = mutableMapOf<String, DriveChangesPage>()
    private val listChildrenScript = mutableMapOf<Pair<String, String?>, Pair<List<DriveFile>, String?>>()
    private var idCounter = 1
    private var clock = 5_000L

    fun putFolder(
        id: String,
        name: String,
        parentId: String? = null,
        modifiedTime: Long = nextTs()
    ) {
        val parents = parentId?.let { listOf(it) }.orEmpty()
        records[id] = RemoteRecord(
            file = DriveFile(
                id = id,
                name = name,
                mimeType = FOLDER_MIME,
                modifiedTime = modifiedTime,
                trashed = false,
                parents = parents,
                appProperties = emptyMap()
            ),
            content = ByteArray(0)
        )
    }

    fun putFile(
        id: String,
        name: String,
        parentId: String,
        modifiedTime: Long = nextTs(),
        content: String = "",
        appProperties: Map<String, String> = emptyMap(),
        trashed: Boolean = false
    ) {
        records[id] = RemoteRecord(
            file = DriveFile(
                id = id,
                name = name,
                mimeType = "text/plain",
                modifiedTime = modifiedTime,
                trashed = trashed,
                parents = listOf(parentId),
                appProperties = appProperties
            ),
            content = content.toByteArray()
        )
    }

    fun putScriptedChanges(pageToken: String, page: DriveChangesPage) {
        changesByToken[pageToken] = page
    }

    fun putScriptedChildren(
        folderId: String,
        pageToken: String?,
        items: List<DriveFile>,
        nextPageToken: String?
    ) {
        listChildrenScript[folderId to pageToken] = items to nextPageToken
    }

    fun remoteFile(id: String): DriveFile? = records[id]?.file

    override suspend fun findMarkerFolders(token: String): List<DriveFolder> {
        calls += "findMarkerFolders"
        return markerFolders
    }

    override suspend fun findFoldersByName(token: String, name: String): List<DriveFolder> {
        calls += "findFoldersByName:$name"
        return foldersByName[name].orEmpty()
    }

    override suspend fun ensureMarkerFile(token: String, folderId: String, folderName: String) {
        calls += "ensureMarkerFile:$folderId:$folderName"
    }

    override suspend fun listChildren(token: String, folderId: String, pageToken: String?): Pair<List<DriveFile>, String?> {
        calls += "listChildren:$folderId:${pageToken ?: ""}"
        val scripted = listChildrenScript[folderId to pageToken]
        if (scripted != null) {
            return scripted
        }
        val items = records.values
            .map { it.file }
            .filter { !it.trashed && it.parents.contains(folderId) }
            .sortedBy { it.name }
        return items to null
    }

    override suspend fun findChildByName(token: String, parentId: String, name: String): DriveFile? {
        calls += "findChildByName:$parentId:$name"
        return records.values
            .map { it.file }
            .filter { !it.trashed && it.parents.contains(parentId) && it.name == name }
            .maxByOrNull { it.modifiedTime ?: 0L }
    }

    override suspend fun createFolder(token: String, name: String, parentId: String?): DriveFolder {
        calls += "createFolder:$name:${parentId ?: ""}"
        val id = "folder-${idCounter++}"
        putFolder(id = id, name = name, parentId = parentId)
        return DriveFolder(id = id, name = name)
    }

    override suspend fun createOrUpdateFile(
        token: String,
        fileId: String?,
        name: String,
        parentId: String,
        mimeType: String,
        contentLength: Long?,
        contentProvider: () -> InputStream?,
        appProperties: Map<String, String>
    ): DriveFile {
        val id = fileId ?: "file-${idCounter++}"
        calls += "createOrUpdateFile:$id:$name:$parentId"
        val bytes = contentProvider()?.use { it.readBytes() } ?: ByteArray(0)
        val file = DriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            modifiedTime = nextTs(),
            trashed = false,
            parents = listOf(parentId),
            appProperties = appProperties
        )
        records[id] = RemoteRecord(file, bytes)
        return file
    }

    override suspend fun trashFile(token: String, fileId: String) {
        calls += "trashFile:$fileId"
        val record = records[fileId] ?: return
        record.file = record.file.copy(trashed = true, modifiedTime = nextTs())
    }

    override suspend fun deleteFile(token: String, fileId: String) {
        calls += "deleteFile:$fileId"
        records.remove(fileId)
    }

    override suspend fun downloadFile(token: String, fileId: String, consumer: suspend (InputStream) -> Unit) {
        calls += "downloadFile:$fileId"
        val record = records[fileId] ?: error("Missing remote file: $fileId")
        consumer(ByteArrayInputStream(record.content))
    }

    override suspend fun getStartPageToken(token: String): String {
        calls += "getStartPageToken"
        getStartPageTokenError?.let { throw it }
        return startPageToken
    }

    override suspend fun listChanges(token: String, pageToken: String): DriveChangesPage {
        calls += "listChanges:$pageToken"
        if (listChangesErrors.isNotEmpty()) {
            throw listChangesErrors.removeFirst()
        }
        listChangesError?.let { throw it }
        return changesByToken[pageToken] ?: DriveChangesPage(emptyList(), nextPageToken = null, newStartPageToken = null)
    }

    override suspend fun getFileMetadata(token: String, fileId: String): DriveFile {
        calls += "getFileMetadata:$fileId"
        return records[fileId]?.file ?: error("Missing metadata for $fileId")
    }

    fun allRecords(): Map<String, RemoteRecord> = records.toMap()

    private fun nextTs(): Long = clock++

    companion object {
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
