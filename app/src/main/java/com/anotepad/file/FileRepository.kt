package com.anotepad.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale

data class ChildBatch(
    val entries: List<DocumentNode>,
    val done: Boolean
)

class FileRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    suspend fun listChildren(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<DocumentNode>()
            listChildrenBatched(dirTreeUri, sortOrder).collect { batch ->
                results.addAll(batch.entries)
            }
            results
        }

    fun listChildrenBatched(
        dirTreeUri: Uri,
        sortOrder: FileSortOrder,
        batchSize: Int = 50
    ): Flow<ChildBatch> = flow {
        val (treeUri, parentDocId) = resolveTreeAndDocumentId(dirTreeUri) ?: run {
            emit(ChildBatch(emptyList(), true))
            return@flow
        }
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val batch = mutableListOf<DocumentNode>()
        val sort = buildSortOrder(sortOrder)

        suspend fun emitBatch(force: Boolean) {
            if (batch.isNotEmpty() && (force || batch.size >= safeBatchSize)) {
                emit(ChildBatch(batch.toList(), false))
                batch.clear()
            }
        }

        val mimeTypeColumn = DocumentsContract.Document.COLUMN_MIME_TYPE
        val dirMime = DocumentsContract.Document.MIME_TYPE_DIR
        val selectionDirs = "$mimeTypeColumn = ?"
        var dirSelectionRespected = true
        val dirBuffer = mutableListOf<DocumentNode>()

        val dirsQueryOk = queryChildren(
            treeUri = treeUri,
            parentDocId = parentDocId,
            selection = selectionDirs,
            selectionArgs = arrayOf(dirMime),
            sortOrder = sort
        ) { node, mime ->
            if (mime != dirMime) {
                dirSelectionRespected = false
                return@queryChildren false
            }
            dirBuffer.add(node)
            true
        }

        if (!dirsQueryOk) {
            emit(ChildBatch(emptyList(), true))
            return@flow
        }

        if (!dirSelectionRespected) {
            batch.clear()
            val dirs = mutableListOf<DocumentNode>()
            val files = mutableListOf<DocumentNode>()
            queryChildren(
                treeUri = treeUri,
                parentDocId = parentDocId,
                selection = null,
                selectionArgs = null,
                sortOrder = sort
            ) { node, mime ->
                if (mime == dirMime || node.isDirectory) {
                    dirs.add(node)
                } else if (isSupportedExtension(node.name)) {
                    files.add(node)
                }
                true
            }
            val sortedDirs = sortByName(dirs, sortOrder)
            val sortedFiles = sortByName(files, sortOrder)
            for (node in sortedDirs + sortedFiles) {
                batch.add(node)
                emitBatch(force = false)
            }
            emitBatch(force = true)
            emit(ChildBatch(emptyList(), true))
            return@flow
        }

        for (node in dirBuffer) {
            batch.add(node)
            emitBatch(force = false)
        }
        emitBatch(force = true)

        val selectionFiles = "$mimeTypeColumn != ?"
        queryChildren(
            treeUri = treeUri,
            parentDocId = parentDocId,
            selection = selectionFiles,
            selectionArgs = arrayOf(dirMime),
            sortOrder = sort
        ) { node, mime ->
            if (mime == dirMime || node.isDirectory) {
                return@queryChildren true
            }
            if (!isSupportedExtension(node.name)) {
                return@queryChildren true
            }
            batch.add(node)
            emitBatch(force = false)
            true
        }

        emitBatch(force = true)
        emit(ChildBatch(emptyList(), true))
    }.flowOn(Dispatchers.IO)

    suspend fun listNamesInDirectory(dirTreeUri: Uri): Set<String> = withContext(Dispatchers.IO) {
        val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext emptySet()
        dir.listFiles().mapNotNull { it.name }.toSet()
    }

    suspend fun listFilesRecursive(dirTreeUri: Uri): List<DocumentNode> = withContext(Dispatchers.IO) {
        val root = resolveDirDocumentFile(dirTreeUri) ?: return@withContext emptyList()
        val results = mutableListOf<DocumentNode>()
        val stack = ArrayDeque<DocumentFile>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            current.listFiles().forEach { file ->
                val name = file.name ?: return@forEach
                if (file.isDirectory) {
                    stack.add(file)
                } else if (isSupportedExtension(name)) {
                    results.add(DocumentNode(name = name, uri = file.uri, isDirectory = false))
                }
            }
        }
        results
    }

    suspend fun readText(fileUri: Uri): String = withContext(Dispatchers.IO) {
        resolver.openInputStream(fileUri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
        } ?: ""
    }

    suspend fun writeText(fileUri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(fileUri, "wt")?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(text)
            }
        }
    }

    suspend fun createFile(dirTreeUri: Uri, displayName: String, mimeType: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            dir.createFile(mimeType, displayName)?.uri
        }

    suspend fun createDirectory(dirTreeUri: Uri, displayName: String): Uri? =
        withContext(Dispatchers.IO) {
            val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext null
            dir.createDirectory(displayName)?.uri
        }

    suspend fun renameFile(fileUri: Uri, newName: String): Uri? = withContext(Dispatchers.IO) {
        DocumentsContract.renameDocument(resolver, fileUri, newName)
    }

    fun isSupportedExtension(name: String): Boolean {
        val lower = name.lowercase(Locale.getDefault())
        return lower.endsWith(".txt") || lower.endsWith(".md")
    }

    fun guessMimeType(name: String): String {
        val lower = name.lowercase(Locale.getDefault())
        return if (lower.endsWith(".md")) "text/markdown" else "text/plain"
    }

    fun getDisplayName(uri: Uri): String? {
        return DocumentFile.fromSingleUri(context, uri)?.name
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

    private fun resolveTreeAndDocumentId(dirUri: Uri): Pair<Uri, String>? {
        val authority = dirUri.authority ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(dirUri) }.getOrNull()
        val docId = runCatching { DocumentsContract.getDocumentId(dirUri) }.getOrNull()
        val parentDocId = docId ?: treeDocId ?: return null
        val treeUri = if (treeDocId != null) {
            DocumentsContract.buildTreeDocumentUri(authority, treeDocId)
        } else {
            DocumentsContract.buildTreeDocumentUri(authority, parentDocId)
        }
        return treeUri to parentDocId
    }

    private fun buildSortOrder(sortOrder: FileSortOrder): String {
        val direction = if (sortOrder == FileSortOrder.NAME_ASC) "ASC" else "DESC"
        val column = DocumentsContract.Document.COLUMN_DISPLAY_NAME
        return "$column COLLATE NOCASE $direction"
    }

    private suspend fun queryChildren(
        treeUri: Uri,
        parentDocId: String,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
        onRow: suspend (DocumentNode, String) -> Boolean
    ): Boolean {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childUri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val docId = cursor.getString(idCol) ?: continue
                val mimeType = cursor.getString(mimeCol) ?: ""
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                val node = DocumentNode(
                    name = name,
                    uri = docUri,
                    isDirectory = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                )
                if (!onRow(node, mimeType)) return true
            }
        } ?: return false
        return true
    }

    private fun sortByName(entries: List<DocumentNode>, order: FileSortOrder): List<DocumentNode> {
        val comparator = compareBy<DocumentNode> { it.name.lowercase(Locale.getDefault()) }
        return when (order) {
            FileSortOrder.NAME_DESC -> entries.sortedWith(comparator.reversed())
            FileSortOrder.NAME_ASC -> entries.sortedWith(comparator)
        }
    }
}
