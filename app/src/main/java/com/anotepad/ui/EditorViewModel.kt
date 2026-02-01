package com.anotepad.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.SyncScheduler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


data class EditorState(
    val fileUri: Uri? = null,
    val dirUri: Uri? = null,
    val fileName: String = "",
    val text: String = "",
    val loadToken: Long = 0L,
    val moveCursorToEndOnLoad: Boolean = false,
    val isSaving: Boolean = false,
    val lastSavedAt: Long? = null,
    val autoLinkWeb: Boolean = false,
    val autoLinkEmail: Boolean = false,
    val autoLinkTel: Boolean = false,
    val syncTitle: Boolean = false,
    val newFileExtension: String = "txt",
    val editorFontSizeSp: Float = 16f
)

data class EditorSaveResult(
    val originalUri: Uri?,
    val currentUri: Uri?,
    val dirUri: Uri?
)

class EditorViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val fileRepository: FileRepository,
    private val syncScheduler: SyncScheduler
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val textChanges = MutableStateFlow("")
    private val pendingTemplate = MutableStateFlow<String?>(null)
    val pendingTemplateFlow: StateFlow<String?> = pendingTemplate.asStateFlow()
    private val _manualSaveEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val manualSaveEvents: SharedFlow<Unit> = _manualSaveEvents.asSharedFlow()

    private var autosaveJob: Job? = null
    private var isLoaded = false
    private var lastSavedText = ""
    private var debounceMs = 1200L
    private var autoSaveEnabled = true
    private var autoInsertTemplateEnabled = true
    private var autoInsertTemplate = "yyyy-MM-dd"
    private var openedFileUri: Uri? = null
    private var loadCounter = 0L

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collectLatest { prefs ->
                debounceMs = prefs.autoSaveDebounceMs
                autoSaveEnabled = prefs.autoSaveEnabled
                autoInsertTemplateEnabled = prefs.autoInsertTemplateEnabled
                autoInsertTemplate = prefs.autoInsertTemplate
                _state.update {
                    it.copy(
                        autoLinkWeb = prefs.autoLinkWeb,
                        autoLinkEmail = prefs.autoLinkEmail,
                        autoLinkTel = prefs.autoLinkTel,
                        syncTitle = prefs.syncTitle,
                        editorFontSizeSp = prefs.editorFontSizeSp
                    )
                }
                restartAutosave()
            }
        }
        restartAutosave()
    }

    fun load(fileUri: Uri?, dirUri: Uri?, newFileExtension: String) {
        viewModelScope.launch {
            isLoaded = false
            openedFileUri = fileUri
            loadCounter += 1
            val text = if (fileUri != null) fileRepository.readText(fileUri) else ""
            val fileName = fileUri?.let { uri ->
                fileRepository.getDisplayName(uri) ?: ""
            } ?: ""
            val resolvedDir = if (dirUri == null && fileUri != null) {
                fileRepository.parentTreeUri(fileUri)
            } else {
                dirUri
            }
            val autoInsertedText = if (fileUri == null && text.isBlank()) {
                resolveAutoInsertText()
            } else {
                ""
            }
            val initialText = if (autoInsertedText.isNotBlank()) {
                if (autoInsertedText.endsWith("\n")) autoInsertedText else "$autoInsertedText\n"
            } else {
                text
            }
            val moveCursorToEndOnLoad = autoInsertedText.isNotBlank()
            _state.update {
                it.copy(
                    fileUri = fileUri,
                    dirUri = resolvedDir,
                    fileName = fileName,
                    text = initialText,
                    loadToken = loadCounter,
                    moveCursorToEndOnLoad = moveCursorToEndOnLoad,
                    newFileExtension = newFileExtension
                )
            }
            lastSavedText = if (fileUri == null) "" else initialText
            textChanges.value = initialText
            isLoaded = true
        }
    }

    fun updateText(text: String) {
        _state.update { it.copy(text = text) }
        textChanges.value = text
    }

    fun queueTemplate(text: String) {
        pendingTemplate.value = text
    }

    fun consumeTemplate() {
        pendingTemplate.value = null
    }

    fun saveNow(manual: Boolean = false) {
        viewModelScope.launch {
            val saved = saveIfNeeded(_state.value.text)
            if (manual && saved) {
                _manualSaveEvents.emit(Unit)
            }
        }
    }

    suspend fun saveAndGetResult(): EditorSaveResult? {
        saveIfNeeded(_state.value.text)
        val state = _state.value
        val currentUri = state.fileUri ?: return null
        return EditorSaveResult(
            originalUri = openedFileUri,
            currentUri = currentUri,
            dirUri = state.dirUri
        )
    }

    private fun restartAutosave() {
        autosaveJob?.cancel()
        if (!autoSaveEnabled) {
            autosaveJob = null
            return
        }
        autosaveJob = viewModelScope.launch {
            textChanges.debounce(debounceMs).collectLatest { text ->
                saveIfNeeded(text)
            }
        }
    }

    private fun resolveAutoInsertText(): String {
        if (!autoInsertTemplateEnabled) return ""
        val pattern = autoInsertTemplate.trim()
        if (pattern.isBlank()) return ""
        return runCatching {
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
        }.getOrElse {
            pattern
        }
    }

    private suspend fun saveIfNeeded(text: String): Boolean {
        if (!isLoaded) return false
        if (text == lastSavedText) return false
        val dirUri = _state.value.dirUri
        var fileUri = _state.value.fileUri
        if (fileUri == null) {
            if (dirUri == null || text.isBlank()) return false
            val rawExtension = _state.value.newFileExtension.ifBlank { "txt" }
            val extension = ".${rawExtension.lowercase(Locale.getDefault())}"
            val desiredName = buildNameFromText(text, extension)
            val uniqueName = ensureUniqueName(dirUri, desiredName, null)
            fileUri = fileRepository.createFile(dirUri, uniqueName, fileRepository.guessMimeType(uniqueName))
            if (fileUri == null) return false
            _state.update { it.copy(fileUri = fileUri, fileName = uniqueName) }
        }

        _state.update { it.copy(isSaving = true) }
        fileRepository.writeText(fileUri, text)
        lastSavedText = text

        if (_state.value.syncTitle) {
            val currentName = _state.value.fileName
            val ext = currentName.substringAfterLast('.', "txt")
            val desiredName = buildNameFromText(text, ".${ext}")
            if (desiredName.isNotBlank() && desiredName != currentName && dirUri != null) {
                val uniqueName = ensureUniqueName(dirUri, desiredName, currentName)
                val renamedUri = fileRepository.renameFile(fileUri, uniqueName)
                if (renamedUri != null) {
                    fileUri = renamedUri
                    _state.update { it.copy(fileUri = fileUri, fileName = uniqueName) }
                }
            }
        }

        _state.update { it.copy(isSaving = false, lastSavedAt = System.currentTimeMillis()) }
        syncScheduler.scheduleDebounced()
        return true
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

    private fun buildNameFromText(text: String, extension: String): String {
        val firstLine = text.lineSequence().firstOrNull().orEmpty()
        val cleaned = sanitizeFileName(firstLine)
        val base = if (cleaned.isBlank()) "Untitled" else cleaned
        return base + extension
    }

    private fun sanitizeFileName(input: String): String {
        var text = input.trim()
        text = text.replace(Regex("^[\\s\\u3000]+"), "")
        text = text.replace(Regex("[\\s\\u3000]+$"), "")
        text = text.replace(Regex("[/:,;*?\"<>|]"), "")
        text = text.replace("\\\\", "")
        return text
    }
}
