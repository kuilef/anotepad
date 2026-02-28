package com.anotepad.ui

import android.net.Uri
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSharedDraftRecoveryTest {

    @Test
    fun shouldDiscardBlankSharedDraftRecovery_returnsTrueForUnsavedBlankSharedDraft() {
        assertTrue(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                fileUri = null,
                text = "   "
            )
        )
    }

    @Test
    fun shouldDiscardBlankSharedDraftRecovery_returnsFalseForSavedOrNonBlankDraft() {
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                fileUri = Uri.parse("content://shared/note.txt"),
                text = ""
            )
        )
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                fileUri = null,
                text = "Shared text"
            )
        )
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = false,
                fileUri = null,
                text = ""
            )
        )
    }
}
