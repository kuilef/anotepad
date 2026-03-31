package com.anotepad

import androidx.lifecycle.SavedStateHandle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date
import kotlinx.coroutines.runBlocking

class IncomingShareTest {

    @Test
    fun sanitizeSharedText_normalizesLineBreaksAndRemovesInvisibleCharacters() {
        val sanitized = sanitizeSharedText(" \u200BLine 1\r\nLine 2\rLine 3\uFEFF ")

        assertEquals("Line 1\nLine 2\nLine 3", sanitized)
    }

    @Test
    fun sanitizeSharedText_returnsNullWhenResultIsBlank() {
        val sanitized = sanitizeSharedText(" \u200B \uFEFF ")

        assertNull(sanitized)
    }

    @Test
    fun sanitizeSharedText_limitsResultLength() {
        val sanitized = sanitizeSharedText("A".repeat(MAX_SHARED_TEXT_CHARS + 128))

        assertEquals(MAX_SHARED_TEXT_CHARS, sanitized?.length)
    }

    @Test
    fun buildSharedNoteDraft_usesGeneratedTitleOnFirstLine() {
        val now = Date(1_709_132_112_000L)
        val draft = buildSharedNoteDraft(
            payload = SharedTextPayload("Shared body"),
            now = now
        )

        assertEquals(buildSharedNoteFileName(now), draft.fileName)
        assertEquals("${buildSharedNoteTitle(now)}\n\nShared body", draft.content)
    }

    @Test
    fun buildSharedNoteTitle_omitsFileExtension() {
        val title = buildSharedNoteTitle(Date(1_709_132_112_789L))

        assertTrue(Regex("""^Shared \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}$""").matches(title))
        assertFalse(title.endsWith(".txt"))
    }

    @Test
    fun buildSharedNoteFileName_omitsMilliseconds() {
        val fileName = buildSharedNoteFileName(Date(1_709_132_112_789L))

        assertTrue(Regex("""^Shared \d{4}-\d{2}-\d{2} \d{2}-\d{2}-\d{2}\.txt$""").matches(fileName))
        assertFalse(Regex(""".*-\d{3}\.txt$""").matches(fileName))
    }

    @Test
    fun incomingShareManager_restoresPendingShareAcrossRecreation() = runBlocking {
        setStoreFile(createStoreFile())
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)

        manager.submitShare(SharedTextPayload("Shared body"))
        manager.markAwaitingRootSelection(true)

        val restored = IncomingShareManager(handle)
        restored.restorePendingShare()

        assertEquals(SharedTextPayload("Shared body"), restored.peekPendingShare())
        assertTrue(restored.isAwaitingRootSelection())
    }

    @Test
    fun incomingShareManager_keepsPendingDraftAvailableUntilConsumed() {
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)
        val draft = SharedNoteDraft(
            fileName = "Shared 2026-02-28 14-35-12.txt",
            content = "Shared 2026-02-28 14-35-12\n\nShared body"
        )

        manager.setPendingEditorDraft(draft)

        assertEquals(draft, manager.consumePendingEditorDraft())
        assertNull(manager.consumePendingEditorDraft())
    }

    @Test
    fun incomingShareManager_doesNotPersistLargePayloadsInSavedStateHandle() = runBlocking {
        setStoreFile(createStoreFile())
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)
        val draft = SharedNoteDraft(
            fileName = "Shared 2026-02-28 14-35-12.txt",
            content = "Shared 2026-02-28 14-35-12\n\n" + "A".repeat(8_192)
        )

        manager.submitShare(SharedTextPayload("A".repeat(8_192)))
        manager.setPendingEditorDraft(draft)

        assertNull(handle.get<String>("pending_share_text"))
        assertNull(handle.get<String>("pending_editor_draft_name"))
        assertNull(handle.get<String>("pending_editor_draft_content"))
    }

    @Test
    fun incomingShareManager_clearPendingShareResetsAwaitingSelection() = runBlocking {
        setStoreFile(createStoreFile())
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)

        manager.submitShare(SharedTextPayload("Shared body"))
        manager.markAwaitingRootSelection(true)
        manager.clearPendingShare()

        assertNull(manager.peekPendingShare())
        assertFalse(manager.isAwaitingRootSelection())
    }

    @Test
    fun incomingShareManager_replacesPendingShareWithoutDroppingAwaitingSelection() = runBlocking {
        setStoreFile(createStoreFile())
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)

        manager.submitShare(SharedTextPayload("First share"))
        manager.markAwaitingRootSelection(true)
        manager.submitShare(SharedTextPayload("Second share"))

        assertEquals(SharedTextPayload("Second share"), manager.peekPendingShare())
        assertTrue(manager.isAwaitingRootSelection())
    }

    private fun createStoreFile(): File {
        val root = createTempDir(prefix = "incoming-share-manager")
        return File(root, "recovery.json")
    }

    private fun setStoreFile(file: File) {
        val field = IncomingShareRecoveryStore::class.java.getDeclaredField("file")
        field.isAccessible = true
        field.set(IncomingShareRecoveryStore, file)
    }
}
