package com.anotepad.file

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.MessageDigest

class SafFileReaderWriter(
    private val resolver: ContentResolver
) : IFileReaderWriter {
    override suspend fun readText(fileUri: Uri): ReadTextResult = withContext(Dispatchers.IO) {
        resolver.openInputStream(fileUri)?.use { input ->
            InputStreamReader(input, Charsets.UTF_8).use { reader ->
                val buffer = CharArray(READ_BUFFER_SIZE)
                val builder = StringBuilder()
                var remaining = MAX_TEXT_READ_CHARS
                var truncated = false
                while (remaining > 0) {
                    val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    builder.append(buffer, 0, read)
                    remaining -= read
                    if (remaining == 0) {
                        truncated = true
                        break
                    }
                }
                if (truncated) {
                    builder.append(TRUNCATED_SUFFIX)
                }
                ReadTextResult(
                    text = builder.toString(),
                    truncated = truncated
                )
            }
        } ?: ReadTextResult(text = "", truncated = false)
    }

    override fun openInputStream(fileUri: Uri): InputStream? {
        return resolver.openInputStream(fileUri)
    }

    override suspend fun readTextPreview(fileUri: Uri, maxLength: Int): String = withContext(Dispatchers.IO) {
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

    override suspend fun searchInFile(fileUri: Uri, query: String, regex: Regex?): String? =
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

    override suspend fun computeHash(fileUri: Uri): String = withContext(Dispatchers.IO) {
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

    override suspend fun writeText(fileUri: Uri, text: String) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(fileUri, "wt")?.use { output ->
            OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                writer.write(text)
            }
        }
    }

    override suspend fun writeStream(fileUri: Uri, input: InputStream) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(fileUri, "wt")?.use { output ->
            input.copyTo(output)
        }
    }

    private fun buildSearchSnippet(line: String, start: Int, length: Int): String {
        val from = (start - SEARCH_SNIPPET_WINDOW).coerceAtLeast(0)
        val to = (start + length + SEARCH_SNIPPET_WINDOW).coerceAtMost(line.length)
        val prefix = if (from > 0) "..." else ""
        val suffix = if (to < line.length) "..." else ""
        return prefix + line.substring(from, to).replace("\n", " ") + suffix
    }
}

private const val MAX_TEXT_READ_CHARS = 5_000_000
private const val HASH_BUFFER_SIZE = 8 * 1024
private const val READ_BUFFER_SIZE = 8 * 1024
private const val PREVIEW_BUFFER_SIZE = 2 * 1024
private const val SEARCH_SNIPPET_WINDOW = 48
private const val TRUNCATED_SUFFIX = "\n\n[...truncated]"
