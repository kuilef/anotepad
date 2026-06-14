package com.anotepad.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorReadModeStateTest {

    @Test
    fun shouldApplyEditorReadLock_locksReadModeFilesUntilEditIsUnlocked() {
        val state = EditorState(openNotesInReadMode = true)

        assertFalse(state.isEditUnlocked)
        assertTrue(shouldApplyEditorReadLock(state))
        assertTrue(shouldShowEnterEditModeAction(state))
    }

    @Test
    fun shouldApplyEditorReadLock_returnsFalseAfterEditIsUnlocked() {
        val state = EditorState(
            openNotesInReadMode = true,
            isEditUnlocked = true
        )

        assertFalse(shouldApplyEditorReadLock(state))
        assertFalse(shouldShowEnterEditModeAction(state))
    }

    @Test
    fun shouldApplyEditorReadLock_returnsFalseWhenReadModeSettingIsDisabled() {
        val state = EditorState(openNotesInReadMode = false)

        assertFalse(shouldApplyEditorReadLock(state))
        assertFalse(shouldShowEnterEditModeAction(state))
    }

    @Test
    fun shouldShowEnterEditModeAction_returnsFalseForTruncatedReadOnlyFiles() {
        val state = EditorState(
            openNotesInReadMode = true,
            isReadOnly = true
        )

        assertTrue(shouldApplyEditorReadLock(state))
        assertFalse(shouldShowEnterEditModeAction(state))
    }

    @Test
    fun shouldMoveCursorToEndOnFocus_returnsTrueOnlyAfterUnlockingReadMode() {
        assertTrue(
            shouldMoveCursorToEndOnFocus(
                EditorState(
                    openNotesInReadMode = true,
                    isEditUnlocked = true
                )
            )
        )
        assertFalse(
            shouldMoveCursorToEndOnFocus(
                EditorState(openNotesInReadMode = true)
            )
        )
        assertFalse(
            shouldMoveCursorToEndOnFocus(
                EditorState(openNotesInReadMode = false)
            )
        )
    }

    @Test
    fun shouldUseSelectableReadOnly_returnsTrueForRealReadOnlyFilesAndReadLock() {
        assertTrue(
            shouldUseSelectableReadOnly(
                EditorState(
                    openNotesInReadMode = true,
                    isReadOnly = true
                )
            )
        )
        assertTrue(
            shouldUseSelectableReadOnly(
                EditorState(openNotesInReadMode = true)
            )
        )
        assertFalse(
            shouldUseSelectableReadOnly(
                EditorState(
                    openNotesInReadMode = true,
                    isEditUnlocked = true
                )
            )
        )
    }
}
