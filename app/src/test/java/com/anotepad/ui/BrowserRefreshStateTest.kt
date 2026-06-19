package com.anotepad.ui

import android.net.TestUri
import com.anotepad.file.DocumentNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
