package com.anotepad.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSharedDraftRecoveryTest {

    @Test
    fun shouldDiscardBlankSharedDraftRecovery_returnsTrueForUnsavedBlankSharedDraft() {
        assertTrue(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                hasSavedFile = false,
                text = "   "
            )
        )
    }

    @Test
    fun shouldDiscardBlankSharedDraftRecovery_returnsFalseForSavedOrNonBlankDraft() {
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                hasSavedFile = true,
                text = ""
            )
        )
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = true,
                hasSavedFile = false,
                text = "Shared text"
            )
        )
        assertFalse(
            shouldDiscardBlankSharedDraftRecovery(
                hasPendingSharedDraftRecovery = false,
                hasSavedFile = false,
                text = ""
            )
        )
    }
}
