package com.anotepad.ui

import java.util.Locale

internal const val MAX_FILE_NAME_BYTES = 255

internal fun buildFileNameFromText(
    text: String,
    extension: String,
    sanitizeFileName: (String) -> String
): String {
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val cleaned = sanitizeFileName(firstLine)
    val base = if (cleaned.isBlank()) "Untitled" else cleaned
    return fitFileNameToByteLimit(base = base, extension = extension)
}

internal fun fitFileNameToByteLimit(
    base: String,
    extension: String,
    suffix: String = "",
    maxBytes: Int = MAX_FILE_NAME_BYTES
): String {
    val normalizedExtension = normalizeGeneratedExtension(extension, maxBytes)
    val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
    val extensionBytes = normalizedExtension.toByteArray(Charsets.UTF_8).size
    val baseBudget = (maxBytes - suffixBytes - extensionBytes).coerceAtLeast(1)
    val limitedBase = truncateUtf8(base, baseBudget).trimEnd()
    val fallbackBase = truncateUtf8("Untitled", baseBudget).ifBlank { "U" }
    val resolvedBase = if (limitedBase.isBlank()) fallbackBase else limitedBase
    return resolvedBase + suffix + normalizedExtension
}

internal fun truncateFileNameToByteLimitPreservingExtension(
    name: String,
    maxBytes: Int = MAX_FILE_NAME_BYTES
): String {
    if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name

    val dotIndex = name.lastIndexOf('.')
    if (dotIndex <= 0 || dotIndex == name.lastIndex) {
        return truncateNameToByteLimit(name, maxBytes)
    }

    val base = name.substring(0, dotIndex)
    val extension = name.substring(dotIndex)
    val extensionBytes = extension.toByteArray(Charsets.UTF_8).size
    if (extensionBytes >= maxBytes) {
        return truncateNameToByteLimit(name, maxBytes)
    }

    val baseBudget = (maxBytes - extensionBytes).coerceAtLeast(1)
    val limitedBase = truncateUtf8(base, baseBudget).trimEnd()
    val fallbackBase = truncateUtf8("Untitled", baseBudget).ifBlank { "U" }
    val resolvedBase = if (limitedBase.isBlank()) fallbackBase else limitedBase
    return resolvedBase + extension
}

internal fun truncateNameToByteLimit(
    name: String,
    maxBytes: Int = MAX_FILE_NAME_BYTES
): String {
    return truncateUtf8(name, maxBytes).trimEnd().ifBlank { "U" }
}

private fun normalizeGeneratedExtension(extension: String, maxBytes: Int): String {
    if (extension.isEmpty()) return ""
    val normalized = extension
        .ifBlank { ".txt" }
        .let { if (it.startsWith('.')) it else ".$it" }
        .lowercase(Locale.getDefault())
    return if (normalized.toByteArray(Charsets.UTF_8).size < maxBytes) normalized else ".txt"
}

internal fun truncateUtf8(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    var bestEnd = 0
    var index = 0
    while (index < value.length) {
        val next = value.offsetByCodePoints(index, 1)
        val candidate = value.substring(0, next)
        if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes) break
        bestEnd = next
        index = next
    }
    return value.substring(0, bestEnd)
}
