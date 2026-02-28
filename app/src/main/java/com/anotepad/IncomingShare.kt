package com.anotepad

import android.content.Context
import android.content.Intent
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val SHARED_NOTES_FOLDER_NAME = "Shared"

private val invisibleShareChars = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]")
private val sharedFileNameRegex =
    Regex("""^Shared \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}(?:\(\d+\))?\.txt$""")

data class SharedTextPayload(
    val text: String
)

data class SharedNoteDraft(
    val fileName: String,
    val content: String
)

class IncomingShareManager(
    _savedStateHandle: SavedStateHandle
) {
    private val _shareRequests = MutableStateFlow(IncomingShareRecoveryStore.peek())
    private var awaitingRootSelection = false
    private var pendingEditorDraft: SharedNoteDraft? = null
    val shareRequests: StateFlow<SharedTextPayload?> = _shareRequests.asStateFlow()

    fun peekPendingShare(): SharedTextPayload? = _shareRequests.value

    fun submitShare(payload: SharedTextPayload) {
        IncomingShareRecoveryStore.persist(payload)
        _shareRequests.value = payload
    }

    fun clearPendingShare() {
        IncomingShareRecoveryStore.clear()
        awaitingRootSelection = false
        _shareRequests.value = null
    }

    fun isAwaitingRootSelection(): Boolean {
        return awaitingRootSelection
    }

    fun markAwaitingRootSelection(awaiting: Boolean) {
        awaitingRootSelection = awaiting
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

        val rawText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { html ->
                HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
            }
            ?: extractClipText(context, intent ?: return@withContext null)

        val text = sanitizeSharedText(rawText) ?: return@withContext null
        SharedTextPayload(text = text)
    }

private fun extractClipText(context: Context, intent: Intent): String? {
    val clipData = intent.clipData ?: return null
    for (index in 0 until clipData.itemCount) {
        val text = clipData.getItemAt(index).coerceToText(context)?.toString()
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
    return normalized.takeIf { it.isNotBlank() }
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

internal fun buildSharedNoteFileName(now: Date = Date()): String {
    val timestamp = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(now)
    return "Shared $timestamp.txt"
}

internal fun isManagedSharedFileName(fileName: String): Boolean {
    return sharedFileNameRegex.matches(fileName)
}
