package com.anotepad.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.DocumentNode
import com.anotepad.file.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrowserState(
    val rootUri: Uri? = null,
    val currentDirUri: Uri? = null,
    val currentDirLabel: String? = null,
    val dirStack: List<Uri> = emptyList(),
    val entries: List<DocumentNode> = emptyList(),
    val isLoading: Boolean = false,
    val fileListFontSizeSp: Float = 14f,
    val defaultFileExtension: String = "txt"
)

class BrowserViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val fileRepository: FileRepository
) : ViewModel() {
    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collectLatest { prefs ->
                val root = prefs.rootTreeUri?.let(Uri::parse)
                val prevRoot = _state.value.rootUri
                _state.update {
                    it.copy(
                        rootUri = root,
                        fileListFontSizeSp = prefs.browserFontSizeSp,
                        defaultFileExtension = prefs.defaultFileExtension
                    )
                }
                val currentDir = _state.value.currentDirUri
                if (root == null) {
                    _state.update { state ->
                        state.copy(
                            currentDirUri = null,
                            currentDirLabel = null,
                            dirStack = emptyList(),
                            entries = emptyList()
                        )
                    }
                } else if (root != prevRoot || currentDir == null) {
                    setRoot(root)
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

    fun refresh() {
        val dirUri = _state.value.currentDirUri ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val entries = fileRepository.listChildren(dirUri)
            _state.update { it.copy(entries = entries, isLoading = false) }
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

    private fun updateCurrentDir(dirUri: Uri, stack: List<Uri>) {
        _state.update {
            it.copy(
                currentDirUri = dirUri,
                currentDirLabel = fileRepository.getTreeDisplayPath(dirUri),
                dirStack = stack
            )
        }
    }
}
