package com.anotepad.sync.engine

import com.anotepad.sync.DriveFile

class FolderPathResolver(
    private val drive: DriveGateway,
    private val store: SyncStore,
    private val localFs: LocalFsGateway
) {
    private val folderPathToIdCache = mutableMapOf<String, String>()
    private val folderChildrenCache = mutableMapOf<String, Map<String, DriveFile>>()

    suspend fun ensureDriveFolderForPath(token: String, rootFolderId: String, relativePath: String): String {
        val dirPath = relativePath.substringBeforeLast('/', "")
        if (dirPath.isBlank()) return rootFolderId

        var currentId = rootFolderId
        var currentPath = ""
        for (segment in dirPath.split('/').filter { it.isNotBlank() }) {
            currentPath = if (currentPath.isBlank()) segment else "$currentPath/$segment"
            val cached = getFolderByPathCached(currentPath)
            if (cached != null) {
                currentId = cached
                continue
            }
            val created = drive.createFolder(token, segment, currentId)
            upsertFolderCached(currentPath, created.id)
            currentId = created.id
        }
        return currentId
    }

    suspend fun resolveParentPathWithFetch(
        token: String,
        driveFolderId: String,
        parents: List<String>
    ): String? {
        for (parentId in parents) {
            val mapping = store.getFolderByDriveId(parentId)?.localRelativePath
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

    suspend fun findChildByNameCached(token: String, parentId: String, name: String): DriveFile? {
        val cached = folderChildrenCache[parentId]
        if (cached != null) return cached[name]
        val prefetched = prefetchChildren(token, parentId)
        return prefetched[name]
    }

    fun sanitizeRemoteFileName(file: DriveFile): String {
        val cleaned = localFs.sanitizeFileName(file.name)
        if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            return cleaned
        }
        val ext = file.name.substringAfterLast('.', "")
        val base = "untitled-${file.id.take(6)}"
        return if (ext.isBlank()) base else "$base.$ext"
    }

    fun sanitizeRemoteFolderName(folder: DriveFile): String {
        val cleaned = localFs.sanitizeFileName(folder.name)
        return if (cleaned.isNotBlank() && cleaned != "." && cleaned != "..") {
            cleaned
        } else {
            "folder-${folder.id.take(6)}"
        }
    }

    fun resetRunCaches() {
        folderChildrenCache.clear()
    }

    private suspend fun prefetchChildren(token: String, parentId: String): Map<String, DriveFile> {
        val existing = folderChildrenCache[parentId]
        if (existing != null) return existing

        val byName = mutableMapOf<String, DriveFile>()
        var pageToken: String? = null
        do {
            val (items, nextToken) = drive.listChildren(token, parentId, pageToken)
            for (child in items) {
                val current = byName[child.name]
                if (current == null || (child.modifiedTime ?: 0L) >= (current.modifiedTime ?: 0L)) {
                    byName[child.name] = child
                }
            }
            pageToken = nextToken
        } while (!pageToken.isNullOrBlank())

        folderChildrenCache[parentId] = byName
        return byName
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
            val cached = store.getFolderByDriveId(currentId)
            if (cached != null) {
                val path = appendPath(cached.localRelativePath, chain.asReversed().map { sanitizeRemoteFolderName(it) })
                upsertFolderChain(cached.localRelativePath, chain)
                return path
            }
            if (currentId == driveFolderId) {
                val path = chain.asReversed().joinToString("/") { sanitizeRemoteFolderName(it) }
                upsertFolderChain("", chain)
                return path
            }
            val meta = drive.getFileMetadata(token, currentId)
            if (meta.mimeType != DRIVE_FOLDER_MIME) return null
            chain.add(meta)
            if (meta.parents.contains(driveFolderId)) {
                val path = chain.asReversed().joinToString("/") { sanitizeRemoteFolderName(it) }
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
            val safeName = sanitizeRemoteFolderName(folder)
            currentPath = if (currentPath.isBlank()) safeName else "$currentPath/$safeName"
            upsertFolderCached(currentPath, folder.id)
        }
    }

    private suspend fun getFolderByPathCached(path: String): String? {
        val cached = folderPathToIdCache[path]
        if (cached != null) return cached
        val mapped = store.getFolderByPath(path)?.driveFolderId
        if (mapped != null) {
            folderPathToIdCache[path] = mapped
        }
        return mapped
    }

    private suspend fun upsertFolderCached(path: String, folderId: String) {
        folderPathToIdCache[path] = folderId
        store.upsertFolder(path, folderId)
    }
}
