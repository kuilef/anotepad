package com.anotepad

import android.content.Context
import android.content.Intent
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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

class IncomingShareManager {
    private val _shareRequests = MutableSharedFlow<SharedTextPayload>(extraBufferCapacity = 1)
    val shareRequests = _shareRequests.asSharedFlow()

    private var pendingEditorDraft: SharedNoteDraft? = null

    fun submitShare(payload: SharedTextPayload) {
        _shareRequests.tryEmit(payload)
    }

    fun setPendingEditorDraft(draft: SharedNoteDraft) {
        pendingEditorDraft = draft
    }

    fun consumePendingEditorDraft(): SharedNoteDraft? {
        val draft = pendingEditorDraft
        pendingEditorDraft = null
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
