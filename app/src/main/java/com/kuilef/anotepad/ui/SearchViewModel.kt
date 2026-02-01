package com.kuilef.anotepad.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuilef.anotepad.file.FileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchResult(
    val fileName: String,
    val fileUri: Uri,
    val dirUri: Uri?,
    val snippet: String
)

data class SearchState(
    val baseDir: Uri? = null,
    val query: String = "",
    val regexEnabled: Boolean = false,
    val searching: Boolean = false,
    val results: List<SearchResult> = emptyList()
)

class SearchViewModel(private val fileRepository: FileRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    fun setBaseDir(uri: Uri?) {
        _state.update { it.copy(baseDir = uri, results = emptyList()) }
    }

    fun updateQuery(query: String) {
        _state.update { it.copy(query = query) }
    }

    fun toggleRegex(enabled: Boolean) {
        _state.update { it.copy(regexEnabled = enabled) }
    }

    fun runSearch() {
        val query = _state.value.query
        val baseDir = _state.value.baseDir
        if (query.isBlank() || baseDir == null) return

        viewModelScope.launch {
            _state.update { it.copy(searching = true, results = emptyList()) }
            val files = fileRepository.listFilesRecursive(baseDir)
            val results = mutableListOf<SearchResult>()
            val regex = if (_state.value.regexEnabled) {
                runCatching { Regex(query, setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }.getOrNull()
            } else {
                null
            }
            if (_state.value.regexEnabled && regex == null) {
                _state.update { it.copy(searching = false, results = emptyList()) }
                return@launch
            }

            files.forEach { node ->
                val snippet = fileRepository.searchInFile(node.uri, query, regex)
                if (snippet != null) {
                    results.add(
                        SearchResult(
                            fileName = node.name,
                            fileUri = node.uri,
                            dirUri = fileRepository.parentTreeUri(node.uri),
                            snippet = snippet
                        )
                    )
                }
            }

            _state.update { it.copy(searching = false, results = results) }
        }
    }
}
