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
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest
import java.util.Locale

data class ChildBatch(
    val entries: List<DocumentNode>,
    val done: Boolean
)

class FileRepository(private val context: Context) {
    private val resolver: ContentResolver = context.contentResolver
    private val listCache = object : LinkedHashMap<ListCacheKey, ListCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ListCacheKey, ListCacheEntry>): Boolean {
            return size > LIST_CACHE_MAX_ENTRIES
        }
    }

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
        batchSize: Int = 50,
        firstBatchSize: Int = batchSize,
        useCache: Boolean = true
    ): Flow<ChildBatch> = flow {
        val cacheKey = ListCacheKey(dirTreeUri.toString(), sortOrder)
        if (useCache) {
            val cached = getCachedList(cacheKey)
            if (cached != null) {
                emitCachedBatches(
                    cached,
                    batchSize.coerceAtLeast(1),
                    firstBatchSize.coerceAtLeast(1)
                ) { batch ->
                    emit(batch)
                }
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
            val sortedDirs = sortByName(dirs, sortOrder)
            val sortedFiles = sortByName(files, sortOrder)
            val combined = sortedDirs + sortedFiles
            storeCachedList(cacheKey, combined)
            for (node in combined) {
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
        storeCachedList(cacheKey, collected)
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

    fun openInputStream(fileUri: Uri): InputStream? {
        return resolver.openInputStream(fileUri)
    }

    suspend fun readTextPreview(fileUri: Uri, maxLength: Int): String = withContext(Dispatchers.IO) {
        if (maxLength <= 0) return@withContext ""
        resolver.openInputStream(fileUri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(PREVIEW_BUFFER_SIZE)
                val builder = StringBuilder()
                var remaining = maxLength
                while (remaining > 0) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    builder.append(buffer, 0, read)
                    remaining -= read
                }
                builder.toString()
            }
        } ?: ""
    }

    suspend fun searchInFile(fileUri: Uri, query: String, regex: Regex?): String? =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext null
            resolver.openInputStream(fileUri)?.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val match = if (regex != null) {
                            regex.find(line)?.let { it.range.first to it.value.length }
                        } else {
                            val index = line.indexOf(query, ignoreCase = true)
                            if (index >= 0) index to query.length else null
                        }
                        if (match != null) {
                            return@withContext buildSearchSnippet(line, match.first, match.second)
                        }
                        line = reader.readLine()
                    }
                }
            }
            null
        }

    suspend fun computeHash(fileUri: Uri): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val readOk = resolver.openInputStream(fileUri)?.use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
            true
        } ?: false
        if (!readOk) return@withContext ""
        digest.digest().joinToString("") { "%02x".format(it) }
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

    private fun getCachedList(key: ListCacheKey): List<DocumentNode>? {
        val now = System.currentTimeMillis()
        synchronized(listCache) {
            val entry = listCache[key] ?: return null
            return if (now - entry.timestampMs <= LIST_CACHE_TTL_MS) {
                entry.entries
            } else {
                listCache.remove(key)
                null
            }
        }
    }

    private fun storeCachedList(key: ListCacheKey, entries: List<DocumentNode>) {
        synchronized(listCache) {
            listCache[key] = ListCacheEntry(entries, System.currentTimeMillis())
        }
    }

    private fun invalidateListCache(dirTreeUri: Uri) {
        val keyPrefix = dirTreeUri.toString()
        synchronized(listCache) {
            val iterator = listCache.keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key.dirUri == keyPrefix) {
                    iterator.remove()
                }
            }
        }
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

    private fun buildSearchSnippet(line: String, start: Int, length: Int): String {
        val window = SEARCH_SNIPPET_WINDOW
        val from = (start - window).coerceAtLeast(0)
        val to = (start + length + window).coerceAtMost(line.length)
        val prefix = if (from > 0) "..." else ""
        val suffix = if (to < line.length) "..." else ""
        return prefix + line.substring(from, to).replace("\n", " ") + suffix
    }
}

private data class ListCacheKey(
    val dirUri: String,
    val sortOrder: FileSortOrder
)

private data class ListCacheEntry(
    val entries: List<DocumentNode>,
    val timestampMs: Long
)

private const val LIST_CACHE_TTL_MS = 15_000L
private const val LIST_CACHE_MAX_ENTRIES = 12
private const val HASH_BUFFER_SIZE = 8 * 1024
private const val PREVIEW_BUFFER_SIZE = 2 * 1024
private const val SEARCH_SNIPPET_WINDOW = 48
