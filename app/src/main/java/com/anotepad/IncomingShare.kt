package com.anotepad

import android.content.Context
import android.content.Intent
import androidx.core.text.HtmlCompat
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

private const val KEY_PENDING_SHARE_TEXT = "incoming_share.pending_share_text"
private const val KEY_PENDING_DRAFT_FILE_NAME = "incoming_share.pending_draft_file_name"
private const val KEY_PENDING_DRAFT_CONTENT = "incoming_share.pending_draft_content"
private const val KEY_AWAITING_ROOT_SELECTION = "incoming_share.awaiting_root_selection"

class IncomingShareManager(
    private val savedStateHandle: SavedStateHandle
) {
    private val _shareRequests = MutableStateFlow(
        savedStateHandle.get<String?>(KEY_PENDING_SHARE_TEXT)?.let(::SharedTextPayload)
    )
    val shareRequests: StateFlow<SharedTextPayload?> = _shareRequests.asStateFlow()

    fun peekPendingShare(): SharedTextPayload? = _shareRequests.value

    fun submitShare(payload: SharedTextPayload) {
        savedStateHandle[KEY_PENDING_SHARE_TEXT] = payload.text
        _shareRequests.value = payload
    }

    fun clearPendingShare() {
        savedStateHandle[KEY_PENDING_SHARE_TEXT] = null
        savedStateHandle[KEY_AWAITING_ROOT_SELECTION] = false
        _shareRequests.value = null
    }

    fun isAwaitingRootSelection(): Boolean {
        return savedStateHandle[KEY_AWAITING_ROOT_SELECTION] ?: false
    }

    fun markAwaitingRootSelection(awaiting: Boolean) {
        savedStateHandle[KEY_AWAITING_ROOT_SELECTION] = awaiting
    }

    fun setPendingEditorDraft(draft: SharedNoteDraft) {
        savedStateHandle[KEY_PENDING_DRAFT_FILE_NAME] = draft.fileName
        savedStateHandle[KEY_PENDING_DRAFT_CONTENT] = draft.content
    }

    fun peekPendingEditorDraft(): SharedNoteDraft? {
        val fileName = savedStateHandle.get<String?>(KEY_PENDING_DRAFT_FILE_NAME)
        val content = savedStateHandle.get<String?>(KEY_PENDING_DRAFT_CONTENT)
        return if (fileName != null && content != null) {
            SharedNoteDraft(fileName = fileName, content = content)
        } else {
            null
        }
    }

    fun consumePendingEditorDraft(): SharedNoteDraft? {
        val draft = peekPendingEditorDraft()
        savedStateHandle[KEY_PENDING_DRAFT_FILE_NAME] = null
        savedStateHandle[KEY_PENDING_DRAFT_CONTENT] = null
        return draft
    }
}

internal fun isSupportedShareIntent(intent: Intent?): Boolean {
    if (intent?.action != Intent.ACTION_SEND) return false
    val type = intent.type
    return type == null || type.startsWith("text/")
}

internal fun extractSharedTextPayload(context: Context, intent: Intent?): SharedTextPayload? {
    if (!isSupportedShareIntent(intent)) return null

    val rawText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { html ->
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        }
        ?: extractClipText(context, intent ?: return null)

    val text = sanitizeSharedText(rawText) ?: return null
    return SharedTextPayload(text = text)
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

internal fun replaceSharedNoteHeader(content: String, fileName: String): String {
    val remainder = content.substringAfter('\n', "")
    return if (remainder.isEmpty()) {
        fileName
    } else {
        "$fileName\n$remainder"
    }
}

internal fun isManagedSharedFileName(fileName: String): Boolean {
    return sharedFileNameRegex.matches(fileName)
}
