package com.anotepad.ui

import android.net.Uri
import com.anotepad.data.FileSortOrder
import com.anotepad.file.DocumentNode
import com.anotepad.file.DocumentNodeListUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class FeedManager(
    private val readTextPreview: suspend (Uri, Int) -> String,
    private val pageSize: Int = DEFAULT_FEED_PAGE_SIZE,
    private val previewChars: Int = DEFAULT_FEED_PREVIEW_CHARS
) {
    private var feedFiles: List<DocumentNode> = emptyList()
    private var feedGeneration = 0

    fun clear(state: BrowserState): BrowserState {
        feedGeneration += 1
        feedFiles = emptyList()
        return state.copy(
            feedItems = emptyList(),
            feedHasMore = false,
            feedLoading = false,
            feedScrollIndex = 0,
            feedScrollOffset = 0,
            feedResetSignal = state.feedResetSignal + 1
        )
    }

    fun updateSource(state: BrowserState, entries: List<DocumentNode>): BrowserState {
        feedGeneration += 1
        feedFiles = entries.filterNot { it.isDirectory }
        return state.copy(
            feedItems = emptyList(),
            feedHasMore = feedFiles.isNotEmpty(),
            feedLoading = false,
            feedScrollIndex = 0,
            feedScrollOffset = 0,
            feedResetSignal = state.feedResetSignal + 1
        )
    }

    fun ensureFeedLoaded(
        state: BrowserState,
        stateProvider: () -> BrowserState,
        updateState: ((BrowserState) -> BrowserState) -> Unit,
        scope: CoroutineScope
    ) {
        if (state.feedItems.isEmpty() && feedFiles.isNotEmpty() && !state.feedLoading) {
            loadMoreFeed(stateProvider, updateState, scope)
        }
    }

    fun loadMoreFeed(
        stateProvider: () -> BrowserState,
        updateState: ((BrowserState) -> BrowserState) -> Unit,
        scope: CoroutineScope
    ) {
        val state = stateProvider()
        if (state.feedLoading) return
        val start = state.feedItems.size
        if (start >= feedFiles.size) {
            updateState { it.copy(feedHasMore = false) }
            return
        }
        val end = (start + pageSize).coerceAtMost(feedFiles.size)
        val batch = feedFiles.subList(start, end)
        val generation = feedGeneration
        scope.launch {
            updateState { it.copy(feedLoading = true) }
            val items = batch.map { node ->
                FeedItem(node = node, text = readTextPreview(node.uri, previewChars))
            }
            updateState { current ->
                if (generation != feedGeneration) {
                    current
                } else {
                    val combined = current.feedItems + items
                    current.copy(
                        feedItems = combined,
                        feedHasMore = combined.size < feedFiles.size,
                        feedLoading = false
                    )
                }
            }
        }
    }

    fun updateScroll(
        state: BrowserState,
        index: Int,
        offset: Int
    ): BrowserState {
        return state.copy(feedScrollIndex = index, feedScrollOffset = offset)
    }

    suspend fun updateForEditedFile(
        stateProvider: () -> BrowserState,
        updateState: ((BrowserState) -> BrowserState) -> Unit,
        matchUri: Uri,
        updatedNode: DocumentNode,
        sortOrder: FileSortOrder
    ) {
        feedFiles = DocumentNodeListUpdater.mergeAndSortEntries(
            entries = feedFiles,
            updatedNode = updatedNode,
            matchUri = matchUri,
            sortOrder = sortOrder
        ).filterNot { it.isDirectory }

        val currentItems = stateProvider().feedItems
        if (currentItems.isEmpty()) {
            updateState { it.copy(feedHasMore = feedFiles.isNotEmpty()) }
            return
        }

        val visibleCount = currentItems.size.coerceAtMost(feedFiles.size)
        val previousItemsByUri = currentItems.associateBy { it.node.uri }
        val reorderedItems = mutableListOf<FeedItem>()
        for (node in feedFiles.take(visibleCount)) {
            val text = if (node.uri == updatedNode.uri) {
                readTextPreview(node.uri, previewChars)
            } else {
                previousItemsByUri[node.uri]?.text ?: readTextPreview(node.uri, previewChars)
            }
            reorderedItems += FeedItem(node = node, text = text)
        }
        updateState {
            it.copy(
                feedItems = reorderedItems,
                feedHasMore = reorderedItems.size < feedFiles.size
            )
        }
    }
}

private const val DEFAULT_FEED_PAGE_SIZE = 10
private const val DEFAULT_FEED_PREVIEW_CHARS = 2_048
