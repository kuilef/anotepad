package com.anotepad.file

import android.net.Uri
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream

class FileRepositorySanitizeFileNameTest {

    private val repository = FileRepository(
        fileLister = object : IFileLister {
            override suspend fun listChildren(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode> = emptyList()

            override fun listChildrenBatched(
                dirTreeUri: Uri,
                sortOrder: FileSortOrder,
                batchSize: Int,
                firstBatchSize: Int,
                useCache: Boolean
            ): Flow<ChildBatch> = emptyFlow()

            override suspend fun listNamesInDirectory(dirTreeUri: Uri): Set<String> = emptySet()

            override suspend fun listFilesRecursive(dirTreeUri: Uri): List<DocumentNode> = emptyList()
        },
        readerWriter = object : IFileReaderWriter {
            override suspend fun readText(fileUri: Uri): ReadTextResult = ReadTextResult("", truncated = false)

            override fun openInputStream(fileUri: Uri): InputStream? = null

            override suspend fun readTextPreview(fileUri: Uri, maxLength: Int): String = ""

            override suspend fun searchInFile(fileUri: Uri, query: String, regex: Regex?): String? = null

            override suspend fun computeHash(fileUri: Uri): String = ""

            override suspend fun writeText(fileUri: Uri, text: String) = Unit

            override suspend fun writeStream(fileUri: Uri, input: InputStream) = Unit
        },
        fileOpsHandler = object : IFileOpsHandler {
            override suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri? = null

            override suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri? = null

            override suspend fun renameFile(fileUri: Uri, newName: String): Uri? = null

            override suspend fun deleteFile(fileUri: Uri): Boolean = false

            override suspend fun deleteDirectoryByRelativePath(rootTreeUri: Uri, relativePath: String): Boolean = false

            override suspend fun copyFile(fileUri: Uri, targetDirUri: Uri, displayName: String, mimeType: String): Uri? = null

            override suspend fun moveFile(fileUri: Uri, targetDirUri: Uri, displayName: String, mimeType: String): Uri? = null

            override fun getDisplayName(uri: Uri): String? = null

            override fun getTreeDisplayName(treeUri: Uri): String? = null

            override fun getLastModified(uri: Uri): Long? = null

            override fun getSize(uri: Uri): Long? = null

            override fun getRelativePath(rootTreeUri: Uri, fileUri: Uri): String? = null

            override suspend fun resolveDirByRelativePath(rootTreeUri: Uri, relativePath: String, create: Boolean): Uri? = null

            override suspend fun findFileByRelativePath(rootTreeUri: Uri, relativePath: String): Uri? = null

            override suspend fun createFileByRelativePath(rootTreeUri: Uri, relativePath: String, mimeType: String): Uri? = null

            override fun parentTreeUri(fileUri: Uri): Uri? = null

            override fun getTreeDisplayPath(treeUri: Uri): String = ""
        }
    )

    @Test
    fun sanitize_keepsSafePunctuation() {
        assertEquals("Hello, world; draft!", repository.sanitizeFileName("Hello, world; draft!"))
    }

    @Test
    fun sanitize_removesLeadingDotsAndForbiddenCharacters() {
        assertEquals("chapter 1 intro", repository.sanitizeFileName(".chapter 1: intro"))
        assertEquals("report final", repository.sanitizeFileName("report* final?"))
    }

    @Test
    fun sanitize_returnsEmptyForDotOnlyNames() {
        assertEquals("", repository.sanitizeFileName(".."))
    }

    @Test
    fun sanitize_removesControlCharacters() {
        assertEquals("reportfinal.txt", repository.sanitizeFileName("report\u0000final\u001F.txt"))
    }
}
