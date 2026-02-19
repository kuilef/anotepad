package com.anotepad.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SafFileOpsHandler(
    private val context: Context,
    private val resolver: ContentResolver,
    private val cacheManager: ListCacheManager
) : IFileOpsHandler {

    override suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            val uri = dir.createFile(mimeType, displayName)?.uri
            cacheManager.invalidate(dirTreeUri)
            uri
        }

    override suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            val uri = dir.createDirectory(displayName)?.uri
            cacheManager.invalidate(dirTreeUri)
            uri
        }

    override suspend fun renameFile(fileUri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.renameDocument(resolver, fileUri, newName)
        parentTreeUri(fileUri)?.let { cacheManager.invalidate(it) }
        uri
    }

    override suspend fun deleteFile(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, fileUri) ?: return@withContext false
        val parent = parentTreeUri(fileUri)
        val deleted = file.delete()
        if (deleted) {
            parent?.let { cacheManager.invalidate(it) }
        }
        deleted
    }

    override suspend fun deleteDirectoryByRelativePath(rootTreeUri: Uri, relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (relativePath.isBlank()) return@withContext false
            val dirUri = resolveDirByRelativePath(rootTreeUri, relativePath, create = false)
                ?: return@withContext false
            val dir = resolveDirDocumentFile(dirUri) ?: return@withContext false
            val deleted = dir.delete()
            if (deleted) {
                cacheManager.invalidate(rootTreeUri)
            }
            deleted
        }

    override suspend fun copyFile(
        fileUri: Uri,
        targetDirUri: Uri,
        displayName: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        val targetDir = resolveDirDocumentFile(targetDirUri) ?: return@withContext null
        val created = targetDir.createFile(mimeType, displayName) ?: return@withContext null
        resolver.openInputStream(fileUri)?.use { input ->
            resolver.openOutputStream(created.uri, "wt")?.use { output ->
                input.copyTo(output)
            }
        }
        cacheManager.invalidate(targetDirUri)
        created.uri
    }

    override suspend fun moveFile(
        fileUri: Uri,
        targetDirUri: Uri,
        displayName: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        val copied = copyFile(fileUri, targetDirUri, displayName, mimeType) ?: return@withContext null
        val deleted = deleteFile(fileUri)
        if (!deleted) {
            return@withContext null
        }
        copied
    }

    override fun getDisplayName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
    }

    override fun getTreeDisplayName(treeUri: Uri): String? {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: DocumentFile.fromSingleUri(context, treeUri)?.name
    }

    override fun getLastModified(uri: Uri): Long? {
        return DocumentFile.fromSingleUri(context, uri)?.lastModified()?.takeIf { it > 0 }
    }

    override fun getSize(uri: Uri): Long? {
        return DocumentFile.fromSingleUri(context, uri)?.length()?.takeIf { it >= 0 }
    }

    override fun getRelativePath(rootTreeUri: Uri, fileUri: Uri): String? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(rootTreeUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(rootTreeUri) }.getOrNull()
            ?: return null
        val fileDocId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        if (!fileDocId.startsWith(rootDocId)) return null
        val suffix = fileDocId.removePrefix(rootDocId).trimStart('/')
        return suffix.ifBlank { getDisplayName(fileUri) }
    }

    override suspend fun resolveDirByRelativePath(rootTreeUri: Uri, relativePath: String, create: Boolean): Uri? =
        withContext(Dispatchers.IO) {
            val root = resolveDirDocumentFile(rootTreeUri) ?: return@withContext null
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            var current = root
            for (segment in segments) {
                val existing = current.findFile(segment)
                if (existing != null && existing.isDirectory) {
                    current = existing
                } else if (create) {
                    val created = current.createDirectory(segment) ?: return@withContext null
                    current = created
                } else {
                    return@withContext null
                }
            }
            current.uri
        }

    override suspend fun findFileByRelativePath(rootTreeUri: Uri, relativePath: String): Uri? =
        withContext(Dispatchers.IO) {
            val root = resolveDirDocumentFile(rootTreeUri) ?: return@withContext null
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext null
            var current = root
            for (segment in segments.dropLast(1)) {
                val next = current.findFile(segment) ?: return@withContext null
                if (!next.isDirectory) return@withContext null
                current = next
            }
            val fileName = segments.last()
            current.findFile(fileName)?.uri
        }

    override suspend fun createFileByRelativePath(
        rootTreeUri: Uri,
        relativePath: String,
        mimeType: String
    ): Uri? = withContext(Dispatchers.IO) {
        val segments = relativePath.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return@withContext null
        val dirSegments = segments.dropLast(1)
        val fileName = segments.last()
        val dirUri = resolveDirByRelativePath(rootTreeUri, dirSegments.joinToString("/"), create = true)
            ?: return@withContext null
        val dir = resolveDirDocumentFile(dirUri) ?: return@withContext null
        val created = dir.createFile(mimeType, fileName)?.uri
        if (created != null) {
            cacheManager.invalidate(rootTreeUri)
        }
        created
    }

    override fun parentTreeUri(fileUri: Uri): Uri? {
        val authority = fileUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        val parentId = docId.substringBeforeLast('/', docId)
        if (parentId == docId) return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(fileUri) }.getOrNull()
            ?: return DocumentsContract.buildTreeDocumentUri(authority, parentId)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
    }

    override fun getTreeDisplayPath(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getDocumentId(treeUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return treeUri.toString()
        val parts = docId.split(":", limit = 2)
        val volume = parts.getOrNull(0)?.ifBlank { "primary" } ?: "primary"
        val relative = parts.getOrNull(1).orEmpty().trimStart('/')
        val base = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isBlank()) base else "$base/$relative"
    }

    private fun resolveDirDocumentFile(dirUri: Uri): DocumentFile? {
        val dir = DocumentFile.fromTreeUri(context, dirUri)
            ?: DocumentFile.fromSingleUri(context, dirUri)
            ?: return null
        return if (dir.isDirectory) dir else null
    }
}
