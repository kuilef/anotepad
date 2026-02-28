package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.flow.Flow
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
    private val fileLister: IFileLister,
    private val readerWriter: IFileReaderWriter,
    private val fileOpsHandler: IFileOpsHandler
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

    suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri? {
        return fileOpsHandler.createFile(dirTreeUri, displayName, mimeType)
    }

    suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri? {
        return fileOpsHandler.createDirectory(dirTreeUri, displayName)
    }

    suspend fun renameFile(fileUri: Uri, newName: String): Uri? {
        return fileOpsHandler.renameFile(fileUri, newName)
    }

    suspend fun deleteFile(fileUri: Uri): Boolean {
        return fileOpsHandler.deleteFile(fileUri)
    }

    suspend fun deleteDirectoryByRelativePath(rootTreeUri: Uri, relativePath: String): Boolean {
        return fileOpsHandler.deleteDirectoryByRelativePath(rootTreeUri, relativePath)
    }

    suspend fun copyFile(fileUri: Uri, targetDirUri: Uri, displayName: String): Uri? {
        return fileOpsHandler.copyFile(fileUri, targetDirUri, displayName, guessMimeType(displayName))
    }

    suspend fun moveFile(fileUri: Uri, targetDirUri: Uri, displayName: String): Uri? {
        return fileOpsHandler.moveFile(fileUri, targetDirUri, displayName, guessMimeType(displayName))
    }

    fun isSupportedExtension(name: String): Boolean {
        return isSupportedTextFileExtension(name)
    }

    fun sanitizeFileName(input: String): String {
        var text = input.trim()
        text = text.replace(Regex("^[.\\s\\u3000]+"), "")
        text = text.replace(Regex("[\\s\\u3000]+$"), "")
        text = text.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        text = text.replace(Regex("[/\\\\:*?\"<>|]"), "")
        return when (text) {
            ".", ".." -> ""
            else -> text
        }
    }

    fun guessMimeType(name: String): String {
        return "text/plain"
    }

    fun getDisplayName(uri: Uri): String? {
        return fileOpsHandler.getDisplayName(uri)
    }

    fun getTreeDisplayName(treeUri: Uri): String? {
        return fileOpsHandler.getTreeDisplayName(treeUri)
    }

    fun getLastModified(uri: Uri): Long? {
        return fileOpsHandler.getLastModified(uri)
    }

    fun getSize(uri: Uri): Long? {
        return fileOpsHandler.getSize(uri)
    }

    fun getRelativePath(rootTreeUri: Uri, fileUri: Uri): String? {
        return fileOpsHandler.getRelativePath(rootTreeUri, fileUri)
    }

    suspend fun resolveDirByRelativePath(rootTreeUri: Uri, relativePath: String, create: Boolean): Uri? {
        return fileOpsHandler.resolveDirByRelativePath(rootTreeUri, relativePath, create)
    }

    suspend fun findFileByRelativePath(rootTreeUri: Uri, relativePath: String): Uri? {
        return fileOpsHandler.findFileByRelativePath(rootTreeUri, relativePath)
    }

    suspend fun createFileByRelativePath(rootTreeUri: Uri, relativePath: String, mimeType: String): Uri? {
        return fileOpsHandler.createFileByRelativePath(rootTreeUri, relativePath, mimeType)
    }

    fun parentTreeUri(fileUri: Uri): Uri? {
        return fileOpsHandler.parentTreeUri(fileUri)
    }

    fun getTreeDisplayPath(treeUri: Uri): String {
        return fileOpsHandler.getTreeDisplayPath(treeUri)
    }
}

fun isSupportedTextFileExtension(name: String): Boolean {
    val lower = name.lowercase(Locale.getDefault())
    return lower.endsWith(".txt")
}
