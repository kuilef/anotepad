package com.anotepad.sync.engine

import com.anotepad.sync.RemoteDeletePolicy
import com.anotepad.sync.db.SyncItemEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
const val TRASH_DIR = ".trash"
const val MAX_DUPLICATE_NAME_ATTEMPTS = 200

fun isSupportedNote(name: String): Boolean {
    return name.lowercase(Locale.getDefault()).endsWith(".txt")
}

fun isIgnoredPath(relativePath: String): Boolean {
    return relativePath == TRASH_DIR || relativePath.startsWith("$TRASH_DIR/")
}

fun resolveRemoteDeletePolicy(rawValue: String): RemoteDeletePolicy {
    return runCatching { RemoteDeletePolicy.valueOf(rawValue) }
        .getOrElse { RemoteDeletePolicy.TRASH }
}

fun buildConflictName(relativePath: String): String {
    val base = relativePath.substringBeforeLast('.')
    val ext = relativePath.substringAfterLast('.', "")
    val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date())
    val suffix = "conflict $stamp"
    return if (ext.isBlank()) "$base ($suffix)" else "$base ($suffix).$ext"
}

suspend fun computeHashIfNeeded(
    localFs: LocalFsGateway,
    rootId: String,
    item: SyncItemEntity?,
    relativePath: String,
    lastModified: Long,
    size: Long
): String {
    val shouldCompute = item == null || item.localLastModified != lastModified || item.localSize != size
    return if (shouldCompute) {
        localFs.computeHash(rootId, relativePath)
    } else {
        item.localHash ?: ""
    }
}

fun replacePathPrefix(path: String, oldPrefix: String, newPrefix: String): String {
    if (oldPrefix.isBlank()) return path
    val suffix = path.removePrefix(oldPrefix).trimStart('/')
    return if (suffix.isBlank()) {
        newPrefix
    } else if (newPrefix.isBlank()) {
        suffix
    } else {
        "$newPrefix/$suffix"
    }
}

fun appendPath(base: String, segments: List<String>): String {
    val tail = segments.joinToString("/")
    return when {
        base.isBlank() -> tail
        tail.isBlank() -> base
        else -> "$base/$tail"
    }
}
