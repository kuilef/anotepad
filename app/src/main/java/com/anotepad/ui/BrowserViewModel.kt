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
    private val syncRepository: SyncRepository,
    private val feedManager: FeedManager
) : ViewModel() {
    private val listBatchSize = 50
    private val listFirstBatchSize = 10
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()
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
                        feedManager.clear(
                            state.copy(
                                currentDirUri = null,
                                currentDirLabel = null,
                                dirStack = emptyList(),
                                entries = emptyList(),
                                isLoading = false,
                                isLoadingMore = false,
                                showToolbarOnboarding = false,
                                showFolderUnavailableDialog = state.showFolderUnavailableDialog
                            )
                        )
                    }
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
        val sanitized = fileRepository.sanitizeFileName(name)
        if (sanitized.isBlank()) return
        viewModelScope.launch {
            fileRepository.createDirectory(dirUri, sanitized)
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
        val sanitized = fileRepository.sanitizeFileName(newName)
        if (sanitized.isBlank()) return
        val resolvedName = if (node.isDirectory) {
            sanitized
        } else {
            appendExtensionIfMissing(sanitized, node.name)
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
        feedManager.ensureFeedLoaded(
            state = _state.value,
            stateProvider = { _state.value },
            updateState = { reducer -> _state.update(reducer) },
            scope = viewModelScope
        )
    }

    fun loadMoreFeed() {
        feedManager.loadMoreFeed(
            stateProvider = { _state.value },
            updateState = { reducer -> _state.update(reducer) },
            scope = viewModelScope
        )
    }

    fun updateFeedScroll(index: Int, offset: Int) {
        _state.update { state -> feedManager.updateScroll(state, index, offset) }
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
            feedManager.updateForEditedFile(
                stateProvider = { _state.value },
                updateState = { reducer -> _state.update(reducer) },
                matchUri = matchUri,
                updatedNode = updatedNode
            )
        }
    }

    private fun updateFeedSource(entries: List<DocumentNode>) {
        _state.update { state -> feedManager.updateSource(state, entries) }
        if (_state.value.viewMode == BrowserViewMode.FEED) {
            ensureFeedLoaded()
        }
    }

    private suspend fun resetInvalidRoot() {
        lastRefreshDirUri = null
        _state.update { state ->
            feedManager.clear(
                state.copy(
                    rootUri = null,
                    currentDirUri = null,
                    currentDirLabel = null,
                    dirStack = emptyList(),
                    entries = emptyList(),
                    isLoading = false,
                    isLoadingMore = false,
                    showToolbarOnboarding = false,
                    showFolderUnavailableDialog = true
                )
            )
        }
        preferencesRepository.setRootTreeUri(null)
    }
}
