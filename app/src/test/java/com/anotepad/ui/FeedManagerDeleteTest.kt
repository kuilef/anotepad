package com.anotepad.ui

import android.net.TestUri
import com.anotepad.file.DocumentNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FeedManagerDeleteTest {
    @Test
    fun removeNode_removesNodeFromSourceAndLoadedItems() = runTest {
        val deleted = node("deleted")
        val kept = node("kept")
        val manager = FeedManager(
            readTextPreview = { uri, _ -> uri.toString() }
        )
        var state = manager.updateSource(
            BrowserState(),
            listOf(deleted, kept)
        )
        manager.ensureFeedLoaded(
            state = state,
            stateProvider = { state },
            updateState = { reducer -> state = reducer(state) },
            scope = this
        )
        advanceUntilIdle()

        state = manager.removeNode(state, deleted.uri)

        assertEquals(listOf(kept.uri), state.feedItems.map { it.node.uri })
        assertFalse(state.feedHasMore)
    }

    private fun node(name: String): DocumentNode = DocumentNode(
        name = "$name.txt",
        uri = TestUri(name),
        isDirectory = false
    )
}
