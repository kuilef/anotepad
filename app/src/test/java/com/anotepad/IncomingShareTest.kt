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
    fun buildSharedNoteDraft_usesGeneratedFileNameAsFirstLine() {
        val draft = buildSharedNoteDraft(
            payload = SharedTextPayload("Shared body"),
            now = Date(1_709_132_112_000L)
        )

        assertTrue(isManagedSharedFileName(draft.fileName))
        assertEquals("${draft.fileName}\n\nShared body", draft.content)
    }

    @Test
    fun replaceSharedNoteHeader_rewritesOnlyTheFirstLine() {
        val updated = replaceSharedNoteHeader(
            content = "Shared 2024-02-28 14-15-12.txt\n\nShared body",
            fileName = "Shared 2024-02-28 14-15-12(1).txt"
        )

        assertEquals("Shared 2024-02-28 14-15-12(1).txt\n\nShared body", updated)
    }

    @Test
    fun isManagedSharedFileName_acceptsTimestampNamesWithCollisionSuffix() {
        assertTrue(isManagedSharedFileName("Shared 2026-02-28 14-35-12(2).txt"))
    }

    @Test
    fun incomingShareManager_restoresPendingShareAcrossRecreation() = runBlocking {
        setStoreFile(createStoreFile())
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)

        manager.submitShare(SharedTextPayload("Shared body"))
        manager.markAwaitingRootSelection(true)

        val restored = IncomingShareManager(handle)

        assertEquals(SharedTextPayload("Shared body"), restored.peekPendingShare())
        assertTrue(restored.isAwaitingRootSelection())
    }

    @Test
    fun incomingShareManager_keepsPendingDraftAvailableUntilConsumed() {
        val handle = SavedStateHandle()
        val manager = IncomingShareManager(handle)
        val draft = SharedNoteDraft(
            fileName = "Shared 2026-02-28 14-35-12.txt",
            content = "Shared 2026-02-28 14-35-12.txt\n\nShared body"
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
            content = "Shared 2026-02-28 14-35-12.txt\n\n" + "A".repeat(8_192)
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
