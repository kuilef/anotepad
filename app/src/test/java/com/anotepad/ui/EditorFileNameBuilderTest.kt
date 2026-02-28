package com.anotepad.ui

import com.anotepad.file.FileRepository
import com.anotepad.file.IFileLister
import com.anotepad.file.IFileOpsHandler
import com.anotepad.file.IFileReaderWriter
import com.anotepad.file.ReadTextResult
import android.net.Uri
import com.anotepad.data.FileSortOrder
import com.anotepad.file.ChildBatch
import com.anotepad.file.DocumentNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InputStream

class EditorFileNameBuilderTest {

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
    fun buildFileNameFromText_keepsSafePunctuationFromFirstLine() {
        val noteText = "Hello, world; draft!\nsecond line"

        val fileName = buildFileNameFromText(noteText, ".txt", repository::sanitizeFileName)

        assertEquals("Hello, world; draft!.txt", fileName)
    }

    @Test
    fun buildFileNameFromText_removesUnsafePunctuationOnlyFromFileName() {
        val noteText = "Chapter 1: intro?\nBody keeps punctuation: ? and :"

        val fileName = buildFileNameFromText(noteText, ".txt", repository::sanitizeFileName)

        assertEquals("Chapter 1 intro.txt", fileName)
        assertEquals("Chapter 1: intro?\nBody keeps punctuation: ? and :", noteText)
    }

    @Test
    fun buildFileNameFromText_usesOnlyFirstLineForGeneratedName() {
        val noteText = "Title without symbols\nsecond line: should not affect file name?"

        val fileName = buildFileNameFromText(noteText, ".txt", repository::sanitizeFileName)

        assertEquals("Title without symbols.txt", fileName)
    }

    @Test
    fun buildFileNameFromText_fallsBackToUntitledWhenFirstLineBecomesEmpty() {
        val noteText = "..::??\nbody"

        val fileName = buildFileNameFromText(noteText, ".txt", repository::sanitizeFileName)

        assertEquals("Untitled.txt", fileName)
    }
}
