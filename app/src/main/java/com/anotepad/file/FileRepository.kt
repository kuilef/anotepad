package com.anotepad.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale

data class ChildBatch(
    val entries: List<DocumentNode>,
    val done: Boolean
)

data class ReadTextResult(
    val text: String,
    val truncated: Boolean
)

class FileRepository(
    private val context: Context,
    private val resolver: ContentResolver,
    private val cacheManager: ListCacheManager,
    private val fileLister: SafFileLister,
    private val readerWriter: SafFileReaderWriter
) {

    suspend fun listChildren(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode> {
        return fileLister.listChildren(dirTreeUri, sortOrder)
    }

    fun listChildrenBatched(
        dirTreeUri: Uri,
        sortOrder: FileSortOrder,
        batchSize: Int = 50,
        firstBatchSize: Int = batchSize,
        useCache: Boolean = true
    ): Flow<ChildBatch> {
        return fileLister.listChildrenBatched(
            dirTreeUri = dirTreeUri,
            sortOrder = sortOrder,
            batchSize = batchSize,
            firstBatchSize = firstBatchSize,
            useCache = useCache
        )
    }

    suspend fun listNamesInDirectory(dirTreeUri: Uri): Set<String> {
        return fileLister.listNamesInDirectory(dirTreeUri)
    }

    suspend fun listFilesRecursive(dirTreeUri: Uri): List<DocumentNode> {
        return fileLister.listFilesRecursive(dirTreeUri)
    }

    suspend fun readText(fileUri: Uri): ReadTextResult {
        return readerWriter.readText(fileUri)
    }

    fun openInputStream(fileUri: Uri): InputStream? {
        return readerWriter.openInputStream(fileUri)
    }

    suspend fun readTextPreview(fileUri: Uri, maxLength: Int): String {
        return readerWriter.readTextPreview(fileUri, maxLength)
    }

    suspend fun searchInFile(fileUri: Uri, query: String, regex: Regex?): String? {
        return readerWriter.searchInFile(fileUri, query, regex)
    }

    suspend fun computeHash(fileUri: Uri): String {
        return readerWriter.computeHash(fileUri)
    }

    suspend fun writeText(fileUri: Uri, text: String) {
        readerWriter.writeText(fileUri, text)
    }

    suspend fun writeStream(fileUri: Uri, input: InputStream) {
        readerWriter.writeStream(fileUri, input)
    }

    suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            val uri = dir.createFile(mimeType, displayName)?.uri
            invalidateListCache(dirTreeUri)
            uri
        }

    suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            val uri = dir.createDirectory(displayName)?.uri
            invalidateListCache(dirTreeUri)
            uri
        }

    suspend fun renameFile(fileUri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        val uri = DocumentsContract.renameDocument(resolver, fileUri, newName)
        parentTreeUri(fileUri)?.let { invalidateListCache(it) }
        uri
    }

    suspend fun deleteFile(fileUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val file = DocumentFile.fromSingleUri(context, fileUri) ?: return@withContext false
        val parent = parentTreeUri(fileUri)
        val deleted = file.delete()
        if (deleted) {
            parent?.let { invalidateListCache(it) }
        }
        deleted
    }

    suspend fun deleteDirectoryByRelativePath(rootTreeUri: Uri, relativePath: String): Boolean =
        withContext(Dispatchers.IO) {
            if (relativePath.isBlank()) return@withContext false
            val dirUri = resolveDirByRelativePath(rootTreeUri, relativePath, create = false)
                ?: return@withContext false
            val dir = resolveDirDocumentFile(dirUri) ?: return@withContext false
            val deleted = dir.delete()
            if (deleted) {
                invalidateListCache(rootTreeUri)
            }
            deleted
        }

    suspend fun copyFile(fileUri: Uri, targetDirUri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            val targetDir = resolveDirDocumentFile(targetDirUri) ?: return@withContext null
            val mimeType = guessMimeType(displayName)
            val created = targetDir.createFile(mimeType, displayName) ?: return@withContext null
            resolver.openInputStream(fileUri)?.use { input ->
                resolver.openOutputStream(created.uri, "wt")?.use { output ->
                    input.copyTo(output)
                }
            }
            invalidateListCache(targetDirUri)
            created.uri
        }

    suspend fun moveFile(fileUri: Uri, targetDirUri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            val copied = copyFile(fileUri, targetDirUri, displayName) ?: return@withContext null
            val deleted = deleteFile(fileUri)
            if (!deleted) {
                return@withContext null
            }
            copied
        }

    fun isSupportedExtension(name: String): Boolean {
        return isSupportedTextFileExtension(name)
    }

    fun sanitizeFileName(input: String): String {
        var text = input.trim()
        text = text.replace(Regex("^[\\s\\u3000]+"), "")
        text = text.replace(Regex("[\\s\\u3000]+$"), "")
        text = text.replace(Regex("[/:,;*?\"<>|]"), "")
        text = text.replace("\\\\", "")
        return text
    }

    fun guessMimeType(name: String): String {
        return "text/plain"
    }

    fun getDisplayName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
    }

    fun getTreeDisplayName(treeUri: Uri): String? {
        return DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: DocumentFile.fromSingleUri(context, treeUri)?.name
    }

    fun getLastModified(uri: Uri): Long? {
        return DocumentFile.fromSingleUri(context, uri)?.lastModified()?.takeIf { it > 0 }
    }

    fun getSize(uri: Uri): Long? {
        return DocumentFile.fromSingleUri(context, uri)?.length()?.takeIf { it >= 0 }
    }

    fun getRelativePath(rootTreeUri: Uri, fileUri: Uri): String? {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(rootTreeUri) }.getOrNull()
            ?: runCatching { DocumentsContract.getDocumentId(rootTreeUri) }.getOrNull()
            ?: return null
        val fileDocId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        if (!fileDocId.startsWith(rootDocId)) return null
        val suffix = fileDocId.removePrefix(rootDocId).trimStart('/')
        return suffix.ifBlank { getDisplayName(fileUri) }
    }

    suspend fun resolveDirByRelativePath(rootTreeUri: Uri, relativePath: String, create: Boolean): Uri? =
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

    suspend fun findFileByRelativePath(rootTreeUri: Uri, relativePath: String): Uri? =
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

    suspend fun createFileByRelativePath(rootTreeUri: Uri, relativePath: String, mimeType: String): Uri? =
        withContext(Dispatchers.IO) {
            val segments = relativePath.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return@withContext null
            val dirSegments = segments.dropLast(1)
            val fileName = segments.last()
            val dirUri = resolveDirByRelativePath(rootTreeUri, dirSegments.joinToString("/"), create = true)
                ?: return@withContext null
            val dir = resolveDirDocumentFile(dirUri) ?: return@withContext null
            val created = dir.createFile(mimeType, fileName)?.uri
            if (created != null) {
                invalidateListCache(rootTreeUri)
            }
            created
        }

    fun parentTreeUri(fileUri: Uri): Uri? {
        val authority = fileUri.authority ?: return null
        val docId = runCatching { DocumentsContract.getDocumentId(fileUri) }.getOrNull() ?: return null
        val parentId = docId.substringBeforeLast('/', docId)
        if (parentId == docId) return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(fileUri) }.getOrNull()
            ?: return DocumentsContract.buildTreeDocumentUri(authority, parentId)
        val treeUri = DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
    }

    fun getTreeDisplayPath(treeUri: Uri): String {
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

    private fun invalidateListCache(dirTreeUri: Uri) {
        cacheManager.invalidate(dirTreeUri)
    }
}

fun isSupportedTextFileExtension(name: String): Boolean {
    val lower = name.lowercase(Locale.getDefault())
    return lower.endsWith(".txt")
}
