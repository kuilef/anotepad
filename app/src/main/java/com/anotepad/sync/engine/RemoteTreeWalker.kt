package com.anotepad.sync.engine

import com.anotepad.sync.DriveFile

class RemoteTreeWalker(
    private val drive: DriveGateway,
    private val folderPathResolver: FolderPathResolver
) {
    suspend fun walk(
        token: String,
        rootFolderId: String,
        onFolder: suspend (relativePath: String, folder: DriveFile) -> Unit,
        onFile: suspend (relativePath: String, file: DriveFile) -> Unit
    ) {
        val queue = ArrayDeque<RemoteFolderNode>()
        queue.add(RemoteFolderNode(rootFolderId, ""))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            var pageToken: String? = null
            do {
                val (items, nextPageToken) = drive.listChildren(token, current.id, pageToken)
                for (file in items) {
                    if (file.mimeType == DRIVE_FOLDER_MIME) {
                        val safeName = folderPathResolver.sanitizeRemoteFolderName(file)
                        val nextPath = if (current.relativePath.isBlank()) {
                            safeName
                        } else {
                            "${current.relativePath}/$safeName"
                        }
                        onFolder(nextPath, file)
                        queue.add(RemoteFolderNode(file.id, nextPath))
                    } else {
                        val safeName = folderPathResolver.sanitizeRemoteFileName(file)
                        val relativePath = if (current.relativePath.isBlank()) {
                            safeName
                        } else {
                            "${current.relativePath}/$safeName"
                        }
                        onFile(relativePath, file)
                    }
                }
                pageToken = nextPageToken
            } while (!pageToken.isNullOrBlank())
        }
    }

    private data class RemoteFolderNode(
        val id: String,
        val relativePath: String
    )
}
