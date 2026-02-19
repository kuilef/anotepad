package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.flow.Flow
import java.io.InputStream

interface IFileLister {
    suspend fun listChildren(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode>

    fun listChildrenBatched(
        dirTreeUri: Uri,
        sortOrder: FileSortOrder,
        batchSize: Int = 50,
        firstBatchSize: Int = batchSize,
        useCache: Boolean = true
    ): Flow<ChildBatch>

    suspend fun listNamesInDirectory(dirTreeUri: Uri): Set<String>

    suspend fun listFilesRecursive(dirTreeUri: Uri): List<DocumentNode>
}

interface IFileReaderWriter {
    suspend fun readText(fileUri: Uri): ReadTextResult

    fun openInputStream(fileUri: Uri): InputStream?

    suspend fun readTextPreview(fileUri: Uri, maxLength: Int): String

    suspend fun searchInFile(fileUri: Uri, query: String, regex: Regex?): String?

    suspend fun computeHash(fileUri: Uri): String

    suspend fun writeText(fileUri: Uri, text: String)

    suspend fun writeStream(fileUri: Uri, input: InputStream)
}

interface IFileOpsHandler {
    suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri?

    suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri?

    suspend fun renameFile(fileUri: Uri, newName: String): Uri?

    suspend fun deleteFile(fileUri: Uri): Boolean

    suspend fun deleteDirectoryByRelativePath(rootTreeUri: Uri, relativePath: String): Boolean

    suspend fun copyFile(fileUri: Uri, targetDirUri: Uri, displayName: String, mimeType: String): Uri?

    suspend fun moveFile(fileUri: Uri, targetDirUri: Uri, displayName: String, mimeType: String): Uri?

    fun getDisplayName(uri: Uri): String?

    fun getTreeDisplayName(treeUri: Uri): String?

    fun getLastModified(uri: Uri): Long?

    fun getSize(uri: Uri): Long?

    fun getRelativePath(rootTreeUri: Uri, fileUri: Uri): String?

    suspend fun resolveDirByRelativePath(rootTreeUri: Uri, relativePath: String, create: Boolean): Uri?

    suspend fun findFileByRelativePath(rootTreeUri: Uri, relativePath: String): Uri?

    suspend fun createFileByRelativePath(rootTreeUri: Uri, relativePath: String, mimeType: String): Uri?

    fun parentTreeUri(fileUri: Uri): Uri?

    fun getTreeDisplayPath(treeUri: Uri): String
}
