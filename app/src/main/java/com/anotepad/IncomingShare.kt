package com.anotepad

import android.content.Context
import android.content.Intent
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import java.io.InputStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

internal const val SHARED_NOTES_FOLDER_NAME = "Shared"
internal const val MAX_SHARED_TEXT_CHARS = 1_000_000

private val invisibleShareChars = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
private val sharedFileNameRegex =
    Regex("""^Shared \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}(?:-\d{3})?(?:\(\d+\))?\.txt$""")

data class SharedTextPayload(
    val text: String
)

data class SharedNoteDraft(
    val fileName: String,
    val content: String
)

class IncomingShareManager(
    private val savedStateHandle: SavedStateHandle
) {
    private val shareRequestMutex = Mutex()
    private val shareRequestVersion = AtomicLong(0)
    private val _shareRequests = MutableStateFlow<SharedTextPayload?>(null)
    private val _initialRestoreCompleted = MutableStateFlow(false)
    private var awaitingRootSelection = savedStateHandle[STATE_AWAITING_ROOT_SELECTION] ?: false
    private var pendingEditorDraft: SharedNoteDraft? = null
    val shareRequests: StateFlow<SharedTextPayload?> = _shareRequests.asStateFlow()
    val initialRestoreCompleted: StateFlow<Boolean> = _initialRestoreCompleted.asStateFlow()

    fun peekPendingShare(): SharedTextPayload? = _shareRequests.value

    suspend fun restorePendingShare() {
        val expectedVersion = shareRequestVersion.get()
        try {
            val payload = IncomingShareRecoveryStore.peek() ?: return
            shareRequestMutex.withLock {
                if (shareRequestVersion.get() != expectedVersion) return@withLock
                if (_shareRequests.value != null) return@withLock
                _shareRequests.value = payload
            }
        } finally {
            _initialRestoreCompleted.value = true
        }
    }

    suspend fun submitShare(payload: SharedTextPayload) {
        IncomingShareRecoveryStore.persist(payload)
        shareRequestMutex.withLock {
            shareRequestVersion.incrementAndGet()
            _shareRequests.value = payload
        }
    }

    suspend fun clearPendingShare() {
        IncomingShareRecoveryStore.clear()
        shareRequestMutex.withLock {
            shareRequestVersion.incrementAndGet()
            awaitingRootSelection = false
            savedStateHandle[STATE_AWAITING_ROOT_SELECTION] = false
            _shareRequests.value = null
        }
    }

    fun isAwaitingRootSelection(): Boolean {
        return awaitingRootSelection
    }

    fun markAwaitingRootSelection(awaiting: Boolean) {
        awaitingRootSelection = awaiting
        savedStateHandle[STATE_AWAITING_ROOT_SELECTION] = awaiting
    }

    fun setPendingEditorDraft(draft: SharedNoteDraft) {
        pendingEditorDraft = draft
    }

    fun peekPendingEditorDraft(): SharedNoteDraft? {
        return pendingEditorDraft
    }

    fun consumePendingEditorDraft(): SharedNoteDraft? {
        val draft = peekPendingEditorDraft()
        pendingEditorDraft = null
        return draft
    }
}

internal fun isSupportedShareIntent(intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_SEND) return false
    val type = intent.type
    return type == null || type.startsWith("text/")
}

internal suspend fun extractSharedTextPayload(context: Context, intent: Intent?): SharedTextPayload? =
    withContext(Dispatchers.IO) {
        if (!isSupportedShareIntent(intent)) return@withContext null
        val shareIntent = intent ?: return@withContext null

        val rawText = shareIntent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(MAX_SHARED_TEXT_CHARS)
            ?: shareIntent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { html ->
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().take(MAX_SHARED_TEXT_CHARS)
            }
            ?: extractClipText(context, shareIntent)

        val text = sanitizeSharedText(rawText) ?: return@withContext null
        SharedTextPayload(text = text)
    }

private fun extractClipText(context: Context, intent: Intent): String? {
    val clipData = intent.clipData ?: return null
    for (index in 0 until clipData.itemCount) {
        val item = clipData.getItemAt(index)
        val text = item.text?.toString()?.take(MAX_SHARED_TEXT_CHARS)
            ?: item.htmlText?.let { html ->
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().take(MAX_SHARED_TEXT_CHARS)
            }
            ?: item.uri?.let { uri ->
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        InputStreamReader(input, Charsets.UTF_8).use { reader ->
                            val buffer = CharArray(SHARED_TEXT_READ_BUFFER_SIZE)
                            val builder = StringBuilder()
                            var remaining = MAX_SHARED_TEXT_CHARS
                            while (remaining > 0) {
                                val read = reader.read(buffer, 0, minOf(buffer.size, remaining))
                                if (read <= 0) break
                                builder.append(buffer, 0, read)
                                remaining -= read
                            }
                            builder.toString()
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        if (!text.isNullOrBlank()) return text
    }
    return null
}

internal fun sanitizeSharedText(rawText: String?): String? {
    val normalized = rawText
        ?.replace("\r\n", "\n")
        ?.replace('\r', '\n')
        ?.replace(invisibleShareChars, "")
        ?.trim()
        ?: return null
    return normalized
        .take(MAX_SHARED_TEXT_CHARS)
        .takeIf { it.isNotBlank() }
}

internal fun buildSharedNoteDraft(
    payload: SharedTextPayload,
    now: Date = Date()
): SharedNoteDraft {
    val fileName = buildSharedNoteFileName(now)
    return SharedNoteDraft(
        fileName = fileName,
        content = "$fileName\n\n${payload.text}"
    )
}

internal fun replaceSharedNoteHeader(content: String, fileName: String): String {
    val firstBreak = content.indexOf('\n')
    if (firstBreak < 0) return fileName
    return fileName + content.substring(firstBreak)
}

internal fun buildSharedNoteFileName(now: Date = Date()): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss-SSS", Locale.US).format(now)
    return "Shared $timestamp.txt"
}

internal fun isManagedSharedFileName(fileName: String): Boolean {
    return sharedFileNameRegex.matches(fileName)
}

private const val SHARED_TEXT_READ_BUFFER_SIZE = 8 * 1024
private const val STATE_AWAITING_ROOT_SELECTION = "awaiting_root_selection"
