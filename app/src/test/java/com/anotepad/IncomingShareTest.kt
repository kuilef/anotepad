package com.anotepad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

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
}
