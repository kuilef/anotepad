package com.anotepad.sync.engine.fixtures

import com.anotepad.sync.engine.LocalFileEntry
import com.anotepad.sync.engine.LocalFsGateway
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

class FakeLocalFsGateway : LocalFsGateway {
    data class FakeFile(
        var content: ByteArray,
        var lastModified: Long
    ) {
        val size: Long
            get() = content.size.toLong()
    }

    val calls = mutableListOf<String>()

    private val filesByRoot = mutableMapOf<String, MutableMap<String, FakeFile>>()
    private val dirsByRoot = mutableMapOf<String, MutableSet<String>>()
    private val moveFailures = mutableSetOf<String>()
    private val copyFailures = mutableSetOf<String>()
    private var listFilesRecursiveFailure: Exception? = null
    private var clock = 1_000L

    fun putFile(rootId: String, path: String, content: String, lastModified: Long = nextTs()) {
        val rootFiles = filesByRoot.getOrPut(rootId) { mutableMapOf() }
        rootFiles[path] = FakeFile(content.toByteArray(), lastModified)
        ensureDirChain(rootId, path.substringBeforeLast('/', ""))
    }

    fun file(path: String, rootId: String = DEFAULT_ROOT): FakeFile? {
        return filesByRoot[rootId]?.get(path)
    }

    fun allFiles(rootId: String = DEFAULT_ROOT): Map<String, FakeFile> {
        return filesByRoot[rootId]?.toMap().orEmpty()
    }

    fun failMove(path: String): FakeLocalFsGateway {
        moveFailures += path
        return this
    }

    fun failCopy(path: String): FakeLocalFsGateway {
        copyFailures += path
        return this
    }

    fun failListFilesRecursive(error: Exception): FakeLocalFsGateway {
        listFilesRecursiveFailure = error
        return this
    }

    override suspend fun listFilesRecursive(rootId: String): List<LocalFileEntry> {
        calls += "listFilesRecursive:$rootId"
        listFilesRecursiveFailure?.let { throw it }
        return filesByRoot[rootId]
            ?.entries
            ?.map { (path, file) ->
                LocalFileEntry(path, file.lastModified, file.size)
            }
            ?.sortedBy { it.relativePath }
            .orEmpty()
    }

    override suspend fun getFileMeta(rootId: String, relativePath: String): LocalFileEntry? {
        calls += "getFileMeta:$rootId:$relativePath"
        val file = filesByRoot[rootId]?.get(relativePath) ?: return null
        return LocalFileEntry(relativePath, file.lastModified, file.size)
    }

    override suspend fun exists(rootId: String, relativePath: String): Boolean {
        calls += "exists:$rootId:$relativePath"
        return filesByRoot[rootId]?.containsKey(relativePath) == true
    }

    override fun openInputStream(rootId: String, relativePath: String): InputStream? {
        calls += "openInputStream:$rootId:$relativePath"
        val file = filesByRoot[rootId]?.get(relativePath) ?: return null
        return ByteArrayInputStream(file.content)
    }

    override suspend fun createFile(rootId: String, relativePath: String, mimeType: String): Boolean {
        calls += "createFile:$rootId:$relativePath:$mimeType"
        val rootFiles = filesByRoot.getOrPut(rootId) { mutableMapOf() }
        val existing = rootFiles[relativePath]
        if (existing == null) {
            rootFiles[relativePath] = FakeFile(ByteArray(0), nextTs())
            ensureDirChain(rootId, relativePath.substringBeforeLast('/', ""))
        }
        return true
    }

    override suspend fun writeStream(rootId: String, relativePath: String, input: InputStream): Boolean {
        calls += "writeStream:$rootId:$relativePath"
        val bytes = input.readBytes()
        val rootFiles = filesByRoot.getOrPut(rootId) { mutableMapOf() }
        val file = rootFiles[relativePath]
        if (file == null) {
            rootFiles[relativePath] = FakeFile(bytes, nextTs())
            ensureDirChain(rootId, relativePath.substringBeforeLast('/', ""))
        } else {
            file.content = bytes
            file.lastModified = nextTs()
        }
        return true
    }

    override suspend fun computeHash(rootId: String, relativePath: String): String {
        calls += "computeHash:$rootId:$relativePath"
        val file = filesByRoot[rootId]?.get(relativePath) ?: return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(file.content)
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun moveFile(rootId: String, fromPath: String, toPath: String): Boolean {
        calls += "moveFile:$rootId:$fromPath->$toPath"
        if (moveFailures.contains(fromPath)) return false
        val rootFiles = filesByRoot.getOrPut(rootId) { mutableMapOf() }
        val from = rootFiles.remove(fromPath) ?: return false
        rootFiles[toPath] = from.copy(lastModified = nextTs())
        ensureDirChain(rootId, toPath.substringBeforeLast('/', ""))
        return true
    }

    override suspend fun copyFile(rootId: String, fromPath: String, toPath: String): Boolean {
        calls += "copyFile:$rootId:$fromPath->$toPath"
        if (copyFailures.contains(fromPath)) return false
        val from = filesByRoot[rootId]?.get(fromPath) ?: return false
        val rootFiles = filesByRoot.getOrPut(rootId) { mutableMapOf() }
        rootFiles[toPath] = FakeFile(from.content.copyOf(), nextTs())
        ensureDirChain(rootId, toPath.substringBeforeLast('/', ""))
        return true
    }

    override suspend fun deleteFile(rootId: String, relativePath: String): Boolean {
        calls += "deleteFile:$rootId:$relativePath"
        return filesByRoot[rootId]?.remove(relativePath) != null
    }

    override suspend fun ensureDirectory(rootId: String, relativePath: String): Boolean {
        calls += "ensureDirectory:$rootId:$relativePath"
        ensureDirChain(rootId, relativePath)
        return true
    }

    override suspend fun deleteDirectory(rootId: String, relativePath: String): Boolean {
        calls += "deleteDirectory:$rootId:$relativePath"
        val rootFiles = filesByRoot[rootId] ?: return false
        val before = rootFiles.size
        rootFiles.keys
            .filter { it == relativePath || it.startsWith("$relativePath/") }
            .toList()
            .forEach { rootFiles.remove(it) }
        dirsByRoot[rootId]?.removeAll { it == relativePath || it.startsWith("$relativePath/") }
        return rootFiles.size != before
    }

    override fun sanitizeFileName(input: String): String {
        var text = input.trim()
        text = text.replace(Regex("^[\\s\\u3000]+"), "")
        text = text.replace(Regex("[\\s\\u3000]+$"), "")
        text = text.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        text = text.replace(Regex("[/:,;*?\"<>|]"), "")
        text = text.replace("\\\\", "")
        return text
    }

    override fun guessMimeType(name: String): String = "text/plain"

    private fun ensureDirChain(rootId: String, relativePath: String) {
        val dirs = dirsByRoot.getOrPut(rootId) { mutableSetOf("") }
        if (relativePath.isBlank()) {
            dirs += ""
            return
        }
        var current = ""
        for (segment in relativePath.split('/').filter { it.isNotBlank() }) {
            current = if (current.isBlank()) segment else "$current/$segment"
            dirs += current
        }
    }

    private fun nextTs(): Long = clock++

    companion object {
        const val DEFAULT_ROOT = "root://notes"
    }
}
