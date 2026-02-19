package com.anotepad.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.data.BrowserViewMode
import com.anotepad.data.FileSortOrder
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.ChildBatch
import com.anotepad.file.DocumentNode
import com.anotepad.file.FileRepository
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class FeedItem(
    val node: DocumentNode,
    val text: String
)

data class BrowserState(
    val rootUri: Uri? = null,
    val currentDirUri: Uri? = null,
    val currentDirLabel: String? = null,
    val dirStack: List<Uri> = emptyList(),
    val entries: List<DocumentNode> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val fileListFontSizeSp: Float = 14f,
    val fileSortOrder: FileSortOrder = FileSortOrder.NAME_DESC,
    val defaultFileExtension: String = "txt",
    val viewMode: BrowserViewMode = BrowserViewMode.LIST,
    val feedItems: List<FeedItem> = emptyList(),
    val feedHasMore: Boolean = false,
    val feedLoading: Boolean = false,
    val feedScrollIndex: Int = 0,
    val feedScrollOffset: Int = 0,
    val feedResetSignal: Int = 0,
    val showFolderAccessHint: Boolean = false,
    val showToolbarOnboarding: Boolean = false,
    val showFolderUnavailableDialog: Boolean = false
)

class BrowserViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val fileRepository: FileRepository,
    private val syncRepository: SyncRepository
) : ViewModel() {
    private val listBatchSize = 50
    private val listFirstBatchSize = 10
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
    private var feedFiles: List<DocumentNode> = emptyList()
    private val feedPageSize = 10
    private var feedGeneration = 0
    private var refreshJob: Job? = null
    private var lastSyncedAtSeen: Long? = null
    private var lastRefreshDirUri: Uri? = null

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collectLatest { prefs ->
                val root = prefs.rootTreeUri?.let(Uri::parse)
                val prevRoot = _state.value.rootUri
                val prevSortOrder = _state.value.fileSortOrder
                _state.update {
                    it.copy(
                        rootUri = root,
                        fileListFontSizeSp = prefs.browserFontSizeSp,
                        fileSortOrder = prefs.fileSortOrder,
                        defaultFileExtension = prefs.defaultFileExtension,
                        viewMode = prefs.browserViewMode,
                        showFolderAccessHint = !prefs.folderAccessHintShown,
                        showToolbarOnboarding = root != null && !prefs.toolbarOnboardingShown,
                        showFolderUnavailableDialog = if (root != null) false else it.showFolderUnavailableDialog
                    )
                }
                val currentDir = _state.value.currentDirUri
                if (root == null) {
                    _state.update { state ->
                        state.copy(
                            currentDirUri = null,
                            currentDirLabel = null,
                            dirStack = emptyList(),
                            entries = emptyList(),
                            feedItems = emptyList(),
                            feedHasMore = false,
                            feedLoading = false,
                            feedScrollIndex = 0,
                            feedScrollOffset = 0,
                            feedResetSignal = state.feedResetSignal + 1,
                            isLoading = false,
                            isLoadingMore = false,
                            showToolbarOnboarding = false,
                            showFolderUnavailableDialog = state.showFolderUnavailableDialog
                        )
                    }
                    feedFiles = emptyList()
                } else if (root != prevRoot || currentDir == null) {
                    setRoot(root)
                } else if (prevSortOrder != prefs.fileSortOrder) {
                    refresh()
                }
            }
        }
        viewModelScope.launch {
            syncRepository.syncStatusFlow().collectLatest { status ->
                val lastSyncedAt = status.lastSyncedAt ?: return@collectLatest
                val previous = lastSyncedAtSeen
                lastSyncedAtSeen = lastSyncedAt
                if (previous != null && lastSyncedAt != previous && status.state == SyncState.SYNCED) {
                    refresh(force = true)
                }
            }
        }
    }

    fun setRoot(root: Uri) {
        updateCurrentDir(root, listOf(root))
        refresh()
    }

    fun navigateInto(dirUri: Uri) {
        val newStack = _state.value.dirStack + dirUri
        updateCurrentDir(dirUri, newStack)
        refresh()
    }

    fun navigateUp() {
        val stack = _state.value.dirStack
        if (stack.size <= 1) return
        val newStack = stack.dropLast(1)
        updateCurrentDir(newStack.last(), newStack)
        refresh()
    }

    fun refresh(force: Boolean = false) {
        val dirUri = _state.value.currentDirUri ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val shouldClear = lastRefreshDirUri != dirUri
            lastRefreshDirUri = dirUri
            _state.update { state ->
                state.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    entries = if (shouldClear) emptyList() else state.entries
                )
            }
            val collected = mutableListOf<DocumentNode>()
            try {
                fileRepository.listChildrenBatched(
                    dirUri,
                    _state.value.fileSortOrder,
                    batchSize = listBatchSize,
                    firstBatchSize = listFirstBatchSize,
                    useCache = !force
                ).collect { batch: ChildBatch ->
                    if (batch.entries.isNotEmpty()) {
                        collected.addAll(batch.entries)
                        _state.update {
                            it.copy(
                                entries = collected.toList(),
                                isLoading = false,
                                isLoadingMore = !batch.done
                            )
                        }
                    } else if (batch.done) {
                        _state.update { it.copy(isLoading = false, isLoadingMore = false) }
                    }
                }
                updateFeedSource(collected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SecurityException) {
                resetInvalidRoot()
            } catch (_: IllegalArgumentException) {
                resetInvalidRoot()
            }
        }
    }

    fun createDirectory(name: String) {
        val dirUri = _state.value.currentDirUri ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            fileRepository.createDirectory(dirUri, trimmed)
            refresh()
        }
    }

    fun deleteFile(node: DocumentNode) {
        viewModelScope.launch {
            fileRepository.deleteFile(node.uri)
            refresh(force = true)
        }
    }

    fun renameFile(node: DocumentNode, newName: String) {
        val dirUri = _state.value.currentDirUri ?: return
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val resolvedName = if (node.isDirectory) {
            trimmed
        } else {
            appendExtensionIfMissing(trimmed, node.name)
        }
        if (resolvedName == node.name) return
        viewModelScope.launch {
            val uniqueName = ensureUniqueName(dirUri, resolvedName, node.name)
            fileRepository.renameFile(node.uri, uniqueName)
            refresh(force = true)
        }
    }

    fun copyFile(node: DocumentNode, targetDirUri: Uri) {
        if (node.isDirectory) return
        viewModelScope.launch {
            val uniqueName = ensureUniqueName(targetDirUri, node.name, null)
            fileRepository.copyFile(node.uri, targetDirUri, uniqueName)
            if (_state.value.currentDirUri == targetDirUri) {
                refresh(force = true)
            }
        }
    }

    fun moveFile(node: DocumentNode, targetDirUri: Uri) {
        if (node.isDirectory) return
        val currentDir = _state.value.currentDirUri ?: return
        if (currentDir == targetDirUri) return
        viewModelScope.launch {
            val uniqueName = ensureUniqueName(targetDirUri, node.name, null)
            fileRepository.moveFile(node.uri, targetDirUri, uniqueName)
            refresh(force = true)
        }
    }

    private fun updateCurrentDir(dirUri: Uri, stack: List<Uri>) {
        _state.update {
            it.copy(
                currentDirUri = dirUri,
                currentDirLabel = fileRepository.getTreeDisplayPath(dirUri),
                dirStack = stack
            )
        }
    }

    private suspend fun ensureUniqueName(dirUri: Uri, desiredName: String, currentName: String?): String {
        val names = fileRepository.listNamesInDirectory(dirUri)
        if (desiredName == currentName || !names.contains(desiredName)) return desiredName
        val base = desiredName.substringBeforeLast('.')
        val ext = desiredName.substringAfterLast('.', "")
        var index = 1
        while (index < 1000) {
            val candidate = if (ext.isBlank()) "$base($index)" else "$base($index).$ext"
            if (!names.contains(candidate)) return candidate
            index++
        }
        return desiredName
    }

    private fun appendExtensionIfMissing(name: String, originalName: String): String {
        if ('.' in name) return name
        val ext = originalName.substringAfterLast('.', "")
        return if (ext.isBlank()) name else "$name.$ext"
    }

    fun toggleViewMode() {
        val next = if (_state.value.viewMode == BrowserViewMode.LIST) {
            BrowserViewMode.FEED
        } else {
            BrowserViewMode.LIST
        }
        _state.update { it.copy(viewMode = next) }
        viewModelScope.launch { preferencesRepository.setBrowserViewMode(next) }
        if (next == BrowserViewMode.FEED) {
            ensureFeedLoaded()
        }
    }

    fun markFolderAccessHintShown() {
        if (!_state.value.showFolderAccessHint) return
        _state.update { it.copy(showFolderAccessHint = false) }
        viewModelScope.launch { preferencesRepository.setFolderAccessHintShown(true) }
    }

    fun markToolbarOnboardingShown() {
        if (!_state.value.showToolbarOnboarding) return
        _state.update { it.copy(showToolbarOnboarding = false) }
        viewModelScope.launch { preferencesRepository.setToolbarOnboardingShown(true) }
    }

    fun dismissFolderUnavailableDialog() {
        if (!_state.value.showFolderUnavailableDialog) return
        _state.update { it.copy(showFolderUnavailableDialog = false) }
    }

    fun ensureFeedLoaded() {
        if (_state.value.feedItems.isEmpty() && feedFiles.isNotEmpty() && !_state.value.feedLoading) {
            loadMoreFeed()
        }
    }

    fun loadMoreFeed() {
        if (_state.value.feedLoading) return
        val start = _state.value.feedItems.size
        if (start >= feedFiles.size) {
            _state.update { it.copy(feedHasMore = false) }
            return
        }
        val end = (start + feedPageSize).coerceAtMost(feedFiles.size)
        val batch = feedFiles.subList(start, end)
        val generation = feedGeneration
        viewModelScope.launch {
            _state.update { it.copy(feedLoading = true) }
            val items = batch.map { node ->
                FeedItem(node = node, text = fileRepository.readTextPreview(node.uri, FEED_PREVIEW_CHARS))
            }
            _state.update { state ->
                if (generation != feedGeneration) {
                    state
                } else {
                    val combined = state.feedItems + items
                    state.copy(
                        feedItems = combined,
                        feedHasMore = combined.size < feedFiles.size,
                        feedLoading = false
                    )
                }
            }
        }
    }

    fun updateFeedScroll(index: Int, offset: Int) {
        _state.update { it.copy(feedScrollIndex = index, feedScrollOffset = offset) }
    }

    fun applyEditorUpdate(originalUri: Uri?, currentUri: Uri?, dirUri: Uri?) {
        val currentDir = _state.value.currentDirUri ?: return
        if (dirUri != null && dirUri != currentDir) return
        val targetUri = currentUri ?: return
        val matchUri = originalUri ?: targetUri
        viewModelScope.launch {
            val name = fileRepository.getDisplayName(targetUri) ?: return@launch
            val updatedNode = DocumentNode(name = name, uri = targetUri, isDirectory = false)
            val entries = _state.value.entries
            val matchIndex = entries.indexOfFirst { !it.isDirectory && it.uri == matchUri }
            val currentIndex = if (matchIndex >= 0 || matchUri == targetUri) {
                -1
            } else {
                entries.indexOfFirst { !it.isDirectory && it.uri == targetUri }
            }
            val indexToUpdate = if (matchIndex >= 0) matchIndex else currentIndex
            val updatedEntries = if (indexToUpdate >= 0) {
                entries.toMutableList().apply { set(indexToUpdate, updatedNode) }
            } else {
                entries + updatedNode
            }
            _state.update { it.copy(entries = updatedEntries) }
            updateFeedForEditedFile(matchUri, updatedNode)
        }
    }

    private fun updateFeedSource(entries: List<DocumentNode>) {
        feedGeneration += 1
        feedFiles = entries.filterNot { it.isDirectory }
        _state.update { state ->
            state.copy(
                feedItems = emptyList(),
                feedHasMore = feedFiles.isNotEmpty(),
                feedLoading = false,
                feedScrollIndex = 0,
                feedScrollOffset = 0,
                feedResetSignal = state.feedResetSignal + 1
            )
        }
        if (_state.value.viewMode == BrowserViewMode.FEED) {
            ensureFeedLoaded()
        }
    }

    private suspend fun resetInvalidRoot() {
        feedFiles = emptyList()
        lastRefreshDirUri = null
        _state.update { state ->
            state.copy(
                rootUri = null,
                currentDirUri = null,
                currentDirLabel = null,
                dirStack = emptyList(),
                entries = emptyList(),
                isLoading = false,
                isLoadingMore = false,
                feedItems = emptyList(),
                feedHasMore = false,
                feedLoading = false,
                feedScrollIndex = 0,
                feedScrollOffset = 0,
                feedResetSignal = state.feedResetSignal + 1,
                showToolbarOnboarding = false,
                showFolderUnavailableDialog = true
            )
        }
        preferencesRepository.setRootTreeUri(null)
    }

    private suspend fun updateFeedForEditedFile(matchUri: Uri, updatedNode: DocumentNode) {
        val matchIndex = feedFiles.indexOfFirst { it.uri == matchUri }
        val currentIndex = if (matchIndex >= 0 || matchUri == updatedNode.uri) {
            -1
        } else {
            feedFiles.indexOfFirst { it.uri == updatedNode.uri }
        }
        val indexToUpdate = if (matchIndex >= 0) matchIndex else currentIndex
        feedFiles = if (indexToUpdate >= 0) {
            feedFiles.toMutableList().apply { set(indexToUpdate, updatedNode) }
        } else {
            feedFiles + updatedNode
        }
        val feedItems = _state.value.feedItems
        val matchItemIndex = feedItems.indexOfFirst { it.node.uri == matchUri }
        val currentItemIndex = if (matchItemIndex >= 0 || matchUri == updatedNode.uri) {
            -1
        } else {
            feedItems.indexOfFirst { it.node.uri == updatedNode.uri }
        }
        val itemIndex = if (matchItemIndex >= 0) matchItemIndex else currentItemIndex
        if (itemIndex >= 0) {
            val updatedText = fileRepository.readTextPreview(updatedNode.uri, FEED_PREVIEW_CHARS)
            val updatedItems = feedItems.toMutableList().apply {
                set(itemIndex, FeedItem(node = updatedNode, text = updatedText))
            }
            _state.update { it.copy(feedItems = updatedItems, feedHasMore = updatedItems.size < feedFiles.size) }
        } else {
            _state.update { it.copy(feedHasMore = it.feedItems.size < feedFiles.size) }
        }
    }
}

private const val FEED_PREVIEW_CHARS = 2_048
