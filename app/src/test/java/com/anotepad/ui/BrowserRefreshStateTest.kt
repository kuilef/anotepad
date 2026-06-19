package com.anotepad.ui

import android.net.TestUri
import com.anotepad.file.DocumentNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserRefreshStateTest {
    @Test
    fun completeBrowserRefresh_replacesStaleEntriesWithEmptyResult() {
        val stale = DocumentNode(
            name = "deleted.txt",
            uri = TestUri("deleted"),
            isDirectory = false
        )
        val state = BrowserState(
            entries = listOf(stale),
            isLoading = true,
            isLoadingMore = true
        )

        val result = completeBrowserRefresh(state, emptyList())

        assertEquals(emptyList<DocumentNode>(), result.entries)
        assertFalse(result.isLoading)
        assertFalse(result.isLoadingMore)
    }

    @Test
    fun removeDeletedNode_removesEntryWhenSourceDirectoryIsStillCurrent() {
        val dir = TestUri("root")
        val deleted = DocumentNode(
            name = "deleted.txt",
            uri = TestUri("deleted"),
            isDirectory = false
        )

        val result = removeDeletedNode(
            state = BrowserState(
                currentDirUri = dir,
                entries = listOf(deleted)
            ),
            sourceDirUri = dir,
            nodeUri = deleted.uri
        )

        assertEquals(emptyList<DocumentNode>(), result.entries)
    }

    @Test
    fun removeDeletedNode_doesNotMutateAnotherDirectory() {
        val current = DocumentNode(
            name = "current.txt",
            uri = TestUri("current"),
            isDirectory = false
        )

        val state = BrowserState(
            currentDirUri = TestUri("other"),
            entries = listOf(current)
        )
        val result = removeDeletedNode(
            state = state,
            sourceDirUri = TestUri("root"),
            nodeUri = TestUri("deleted")
        )

        assertEquals(state, result)
    }

    @Test
    fun browserDeleteFailureEvent_returnsFailureOnlyWhenDeleteDidNotSucceed() {
        assertEquals(BrowserUiEvent.DeleteFailed, browserDeleteFailureEvent(false))
        assertNull(browserDeleteFailureEvent(true))
    }
}
