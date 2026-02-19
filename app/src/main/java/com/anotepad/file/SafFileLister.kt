package com.anotepad.file

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.anotepad.data.FileSortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class SafFileLister(
    private val context: Context,
    private val resolver: ContentResolver,
    private val cacheManager: ListCacheManager,
    private val isSupportedExtension: (String) -> Boolean
) : IFileLister {
    override suspend fun listChildren(dirTreeUri: Uri, sortOrder: FileSortOrder): List<DocumentNode> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<DocumentNode>()
            listChildrenBatched(
                dirTreeUri = dirTreeUri,
                sortOrder = sortOrder,
                batchSize = 50,
                firstBatchSize = 50,
                useCache = true
            ).collect { batch ->
                results.addAll(batch.entries)
            }
            results
        }

    override fun listChildrenBatched(
        dirTreeUri: Uri,
        sortOrder: FileSortOrder,
        batchSize: Int,
        firstBatchSize: Int,
        useCache: Boolean
    ): Flow<ChildBatch> = flow {
        if (useCache) {
            val cached = cacheManager.get(dirTreeUri, sortOrder)
            if (cached != null) {
                emitCachedBatches(
                    entries = cached,
                    batchSize = batchSize.coerceAtLeast(1),
                    firstBatchSize = firstBatchSize.coerceAtLeast(1),
                    emitBatch = { batch -> emit(batch) }
                )
                emit(ChildBatch(emptyList(), true))
                return@flow
            }
        }

        val (treeUri, parentDocId) = resolveTreeAndDocumentId(dirTreeUri) ?: run {
            emit(ChildBatch(emptyList(), true))
            return@flow
        }
        val safeBatchSize = batchSize.coerceAtLeast(1)
        var currentBatchLimit = firstBatchSize.coerceAtLeast(1)
        var firstBatchEmitted = false
        val batch = mutableListOf<DocumentNode>()
        val sort = buildSortOrder(sortOrder)
        val collected = mutableListOf<DocumentNode>()

        suspend fun emitBatch(force: Boolean) {
            if (batch.isNotEmpty() && (force || batch.size >= currentBatchLimit)) {
                emit(ChildBatch(batch.toList(), false))
                collected.addAll(batch)
                batch.clear()
                if (!firstBatchEmitted) {
                    firstBatchEmitted = true
                    currentBatchLimit = safeBatchSize
                }
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
            val sortedDirs = NaturalOrderFileComparator.sort(dirs, sortOrder)
            val sortedFiles = NaturalOrderFileComparator.sort(files, sortOrder)
            val combined = sortedDirs + sortedFiles
            for (node in combined) {
                batch.add(node)
                emitBatch(force = false)
            }
            emitBatch(force = true)
            emit(ChildBatch(emptyList(), true))
            cacheManager.put(dirTreeUri, sortOrder, collected)
            return@flow
        }

        val selectionFiles = "$mimeTypeColumn != ?"
        val filesBuffer = mutableListOf<DocumentNode>()
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
            filesBuffer.add(node)
            true
        }

        val sortedDirs = NaturalOrderFileComparator.sort(dirBuffer, sortOrder)
        val sortedFiles = NaturalOrderFileComparator.sort(filesBuffer, sortOrder)
        val combined = sortedDirs + sortedFiles
        for (node in combined) {
            batch.add(node)
            emitBatch(force = false)
        }
        emitBatch(force = true)
        emit(ChildBatch(emptyList(), true))
        cacheManager.put(dirTreeUri, sortOrder, collected)
    }.flowOn(Dispatchers.IO)

    override suspend fun listNamesInDirectory(dirTreeUri: Uri): Set<String> = withContext(Dispatchers.IO) {
        val dir = resolveDirDocumentFile(dirTreeUri) ?: return@withContext emptySet()
        dir.listFiles().mapNotNull { it.name }.toSet()
    }

    override suspend fun listFilesRecursive(dirTreeUri: Uri): List<DocumentNode> = withContext(Dispatchers.IO) {
        val (treeUri, rootDocId) = resolveTreeAndDocumentId(dirTreeUri) ?: return@withContext emptyList()
        val results = mutableListOf<DocumentNode>()
        val stack = ArrayDeque<String>()
        stack.add(rootDocId)
        while (stack.isNotEmpty()) {
            val parentDocId = stack.removeFirst()
            val queryOk = queryChildrenRaw(
                treeUri = treeUri,
                parentDocId = parentDocId
            ) { docId, name, mimeType ->
                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    stack.add(docId)
                    return@queryChildrenRaw true
                }
                if (!isSupportedExtension(name)) {
                    return@queryChildrenRaw true
                }
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                results.add(DocumentNode(name = name, uri = docUri, isDirectory = false))
                true
            }
            if (!queryOk) {
                continue
            }
        }
        results
    }

    fun invalidateListCache(dirTreeUri: Uri) {
        cacheManager.invalidate(dirTreeUri)
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

    private suspend fun queryChildrenRaw(
        treeUri: Uri,
        parentDocId: String,
        onRow: suspend (docId: String, name: String, mimeType: String) -> Boolean
    ): Boolean {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        resolver.query(childUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol) ?: continue
                val name = cursor.getString(nameCol) ?: continue
                val mimeType = cursor.getString(mimeCol) ?: ""
                if (!onRow(docId, name, mimeType)) return true
            }
        } ?: return false
        return true
    }

    private suspend fun emitCachedBatches(
        entries: List<DocumentNode>,
        batchSize: Int,
        firstBatchSize: Int,
        emitBatch: suspend (ChildBatch) -> Unit
    ) {
        val safeBatchSize = batchSize.coerceAtLeast(1)
        val initialSize = firstBatchSize.coerceAtLeast(1)
        var limit = initialSize
        var first = true
        var index = 0
        while (index < entries.size) {
            val end = (index + limit).coerceAtMost(entries.size)
            emitBatch(ChildBatch(entries.subList(index, end), false))
            index = end
            if (first) {
                first = false
                limit = safeBatchSize
            }
        }
    }
}
