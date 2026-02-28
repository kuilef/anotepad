package com.anotepad.ui

import android.net.Uri
import com.anotepad.SharedDraftRecoveryStore
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.SHARED_NOTES_FOLDER_NAME
import com.anotepad.SharedNoteDraft
import com.anotepad.isManagedSharedFileName
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.file.ReadTextResult
import com.anotepad.sync.SyncScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val editorPrefsLoaded: Boolean = false,
    val openNotesInReadMode: Boolean = false,
    val syncTitle: Boolean = false,
    val newFileExtension: String = "txt",
    val editorFontSizeSp: Float = 16f,
    val externalChangeDetectedAt: Long? = null,
    val showExternalChangeDialog: Boolean = false,
    val canSave: Boolean = false,
    val truncatedNoticeToken: Long? = null,
    val proposedFileName: String? = null,
    val suppressSyncTitle: Boolean = false
)

data class EditorSaveResult(
    val originalUri: Uri?,
    val currentUri: Uri?,
    val dirUri: Uri?
)

internal fun shouldDiscardBlankSharedDraftRecovery(
    hasPendingSharedDraftRecovery: Boolean,
    fileUri: Uri?,
    text: String
): Boolean {
    return hasPendingSharedDraftRecovery && fileUri == null && text.isBlank()
}

private const val MAX_FILE_NAME_BYTES = 255

internal fun buildFileNameFromText(
    text: String,
    extension: String,
    sanitizeFileName: (String) -> String
): String {
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val cleaned = sanitizeFileName(firstLine)
    val base = if (cleaned.isBlank()) "Untitled" else cleaned
    return fitFileNameToByteLimit(base = base, extension = extension)
}

internal fun fitFileNameToByteLimit(
    base: String,
    extension: String,
    suffix: String = "",
    maxBytes: Int = MAX_FILE_NAME_BYTES
): String {
    val normalizedExtension = normalizeGeneratedExtension(extension, maxBytes)
    val suffixBytes = suffix.toByteArray(Charsets.UTF_8).size
    val extensionBytes = normalizedExtension.toByteArray(Charsets.UTF_8).size
    val baseBudget = (maxBytes - suffixBytes - extensionBytes).coerceAtLeast(1)
    val limitedBase = truncateUtf8(base, baseBudget).trimEnd()
    val fallbackBase = truncateUtf8("Untitled", baseBudget).ifBlank { "U" }
    val resolvedBase = if (limitedBase.isBlank()) fallbackBase else limitedBase
    return resolvedBase + suffix + normalizedExtension
}

private fun normalizeGeneratedExtension(extension: String, maxBytes: Int): String {
    if (extension.isEmpty()) return ""
    val normalized = extension
        .ifBlank { ".txt" }
        .let { if (it.startsWith('.')) it else ".$it" }
        .lowercase(Locale.getDefault())
    return if (normalized.toByteArray(Charsets.UTF_8).size < maxBytes) normalized else ".txt"
}

private fun truncateUtf8(value: String, maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
    var bestEnd = 0
    var index = 0
    while (index < value.length) {
        val next = value.offsetByCodePoints(index, 1)
        val candidate = value.substring(0, next)
        if (candidate.toByteArray(Charsets.UTF_8).size > maxBytes) break
        bestEnd = next
        index = next
    }
    return value.substring(0, bestEnd)
}

class EditorViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val fileRepository: FileRepository,
    private val syncScheduler: SyncScheduler,
    private val sharedDraftRecoveryStore: SharedDraftRecoveryStore
) : ViewModel() {
    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private val textChanges = MutableStateFlow("")
    private val pendingTemplate = MutableStateFlow<String?>(null)
    val pendingTemplateFlow: StateFlow<String?> = pendingTemplate.asStateFlow()
    private var templateInsertionSelection: Pair<Int, Int>? = null
    private val _manualSaveEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val manualSaveEvents: SharedFlow<Unit> = _manualSaveEvents.asSharedFlow()
    private val _saveFailureEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val saveFailureEvents: SharedFlow<Unit> = _saveFailureEvents.asSharedFlow()

    private var autosaveJob: Job? = null
    private var isLoaded = false
    private var lastSavedText = ""
    private var debounceMs = 1200L
    private var autoSaveEnabled = true
    private var autoInsertTemplateEnabled = true
    private var autoInsertTemplate = "yyyy-MM-dd"
    private var openedFileUri: Uri? = null
    private var loadCounter = 0L
    private var prefsLoaded = false
    private var lastKnownModified: Long? = null
    private var truncatedNoticeCounter = 0L
    private var hasPendingSharedDraftRecovery = false
    private var activeSharedDraftRecovery: SharedNoteDraft? = null
    private val saveMutex = Mutex()
    val undoStack = mutableStateListOf<TextSnapshot>()
    val redoStack = mutableStateListOf<TextSnapshot>()
    private val historyManager = TextHistoryManager(undoStack, redoStack)

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collectLatest { prefs ->
                prefsLoaded = true
                debounceMs = prefs.autoSaveDebounceMs
                autoSaveEnabled = prefs.autoSaveEnabled
                autoInsertTemplateEnabled = prefs.autoInsertTemplateEnabled
                autoInsertTemplate = prefs.autoInsertTemplate
                _state.update {
                    it.copy(
                        autoLinkWeb = prefs.autoLinkWeb,
                        autoLinkEmail = prefs.autoLinkEmail,
                        autoLinkTel = prefs.autoLinkTel,
                        editorPrefsLoaded = true,
                        openNotesInReadMode = prefs.openNotesInReadMode,
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
            val resolvedDir = if (dirUri == null && fileUri != null) {
                fileRepository.parentTreeUri(fileUri)
            } else {
                dirUri
            }
            val sameTarget = if (fileUri == null) {
                isLoaded && openedFileUri == null &&
                    _state.value.dirUri == resolvedDir &&
                    _state.value.newFileExtension == newFileExtension
            } else {
                isLoaded && openedFileUri == fileUri &&
                    _state.value.dirUri == resolvedDir
            }
            if (sameTarget) return@launch

            ensureTemplatePrefsLoaded()
            isLoaded = false
            hasPendingSharedDraftRecovery = false
            activeSharedDraftRecovery = null
            openedFileUri = fileUri
            templateInsertionSelection = null
            loadCounter += 1
            clearHistory()
            val textResult = if (fileUri != null) {
                fileRepository.readText(fileUri)
            } else {
                ReadTextResult(text = "", truncated = false)
            }
            val text = textResult.text
            val fileName = fileUri?.let { uri ->
                fileRepository.getDisplayName(uri) ?: ""
            } ?: ""
            val lastModified = fileUri?.let { uri -> fileRepository.getLastModified(uri) }
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
                    newFileExtension = newFileExtension,
                    externalChangeDetectedAt = null,
                    showExternalChangeDialog = false,
                    canSave = false,
                    truncatedNoticeToken = nextTruncatedNoticeToken(textResult.truncated),
                    proposedFileName = null,
                    suppressSyncTitle = shouldSuppressSyncTitleForNote(resolvedDir, fileName)
                )
            }
            lastSavedText = if (fileUri == null) "" else initialText
            textChanges.value = initialText
            lastKnownModified = lastModified
            isLoaded = true
            updateCanSaveState()
        }
    }

    fun loadSharedDraft(dirUri: Uri?, draft: SharedNoteDraft, newFileExtension: String) {
        viewModelScope.launch {
            ensureTemplatePrefsLoaded()
            isLoaded = false
            hasPendingSharedDraftRecovery = true
            activeSharedDraftRecovery = draft
            openedFileUri = null
            templateInsertionSelection = null
            loadCounter += 1
            clearHistory()
            lastKnownModified = null
            sharedDraftRecoveryStore.persist(draft)
            _state.update {
                it.copy(
                    fileUri = null,
                    dirUri = dirUri,
                    fileName = draft.fileName,
                    text = draft.content,
                    loadToken = loadCounter,
                    moveCursorToEndOnLoad = true,
                    newFileExtension = newFileExtension,
                    externalChangeDetectedAt = null,
                    showExternalChangeDialog = false,
                    canSave = false,
                    truncatedNoticeToken = null,
                    proposedFileName = draft.fileName,
                    suppressSyncTitle = true
                )
            }
            lastSavedText = ""
            textChanges.value = draft.content
            isLoaded = true
            updateCanSaveState()
        }
    }

    fun updateText(text: String) {
        _state.update { it.copy(text = text) }
        textChanges.value = text
        updateCanSaveState()
    }

    fun queueTemplate(text: String) {
        pendingTemplate.value = text
    }

    fun rememberTemplateInsertionSelection(start: Int, end: Int) {
        templateInsertionSelection = start.coerceAtLeast(0) to end.coerceAtLeast(0)
    }

    fun consumeTemplateInsertionSelection(): Pair<Int, Int>? {
        val saved = templateInsertionSelection
        templateInsertionSelection = null
        return saved
    }

    fun consumeTemplate() {
        pendingTemplate.value = null
    }

    fun saveNow(manual: Boolean = false) {
        viewModelScope.launch {
            val saved = runSaveCatching {
                saveIfNeeded(_state.value.text)
            }
            if (manual && saved) {
                _manualSaveEvents.emit(Unit)
            } else if (manual && hasUnsavedChanges()) {
                _saveFailureEvents.emit(Unit)
            }
        }
    }

    suspend fun saveAndGetResult(): EditorSaveResult? {
        discardBlankSharedDraftRecoveryIfNeeded(_state.value)
        runSaveCatching {
            saveIfNeeded(_state.value.text)
        }
        val state = _state.value
        val currentUri = state.fileUri ?: return null
        return EditorSaveResult(
            originalUri = openedFileUri,
            currentUri = currentUri,
            dirUri = state.dirUri
        )
    }

    fun hasExternalChangePending(): Boolean = _state.value.externalChangeDetectedAt != null

    fun hasUnsavedChanges(): Boolean = _state.value.canSave

    suspend fun discardPendingSharedDraftRecovery() {
        clearCurrentSharedDraftRecovery()
    }

    fun showExternalChangeDialog() {
        if (_state.value.externalChangeDetectedAt == null) return
        _state.update { it.copy(showExternalChangeDialog = true) }
    }

    fun dismissExternalChangeDialog() {
        _state.update { it.copy(showExternalChangeDialog = false) }
    }

    fun reloadExternalChange() {
        viewModelScope.launch {
            val fileUri = _state.value.fileUri ?: return@launch
            val modifiedAt = fileRepository.getLastModified(fileUri)
                ?: _state.value.externalChangeDetectedAt
                ?: System.currentTimeMillis()
            reloadFromDisk(fileUri, modifiedAt)
        }
    }

    fun overwriteExternalChange() {
        viewModelScope.launch {
            clearExternalChange()
            runSaveCatching {
                saveIfNeeded(_state.value.text, ignoreExternalChange = true)
            }
        }
    }

    fun checkExternalChange() {
        viewModelScope.launch {
            if (!isLoaded) return@launch
            if (_state.value.externalChangeDetectedAt != null) return@launch
            val fileUri = _state.value.fileUri ?: return@launch
            val modifiedAt = detectExternalChange(fileUri) ?: return@launch
            if (_state.value.text == lastSavedText) {
                reloadFromDisk(fileUri, modifiedAt)
            } else {
                _state.update {
                    it.copy(
                        externalChangeDetectedAt = modifiedAt,
                        showExternalChangeDialog = true
                    )
                }
            }
        }
    }

    private fun restartAutosave() {
        autosaveJob?.cancel()
        if (!autoSaveEnabled) {
            autosaveJob = null
            return
        }
        autosaveJob = viewModelScope.launch {
            textChanges.debounce(debounceMs).collectLatest { text ->
                runSaveCatching {
                    saveIfNeeded(text)
                }
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

    private suspend fun ensureTemplatePrefsLoaded() {
        if (prefsLoaded) return
        val prefs = preferencesRepository.preferencesFlow.first()
        _state.update {
            it.copy(
                autoLinkWeb = prefs.autoLinkWeb,
                autoLinkEmail = prefs.autoLinkEmail,
                autoLinkTel = prefs.autoLinkTel,
                editorPrefsLoaded = true,
                openNotesInReadMode = prefs.openNotesInReadMode,
                syncTitle = prefs.syncTitle,
                editorFontSizeSp = prefs.editorFontSizeSp
            )
        }
        autoInsertTemplateEnabled = prefs.autoInsertTemplateEnabled
        autoInsertTemplate = prefs.autoInsertTemplate
        prefsLoaded = true
    }

    fun clearHistory() {
        historyManager.clear()
    }

    fun pushUndoSnapshot(snapshot: TextSnapshot, clearRedo: Boolean) {
        historyManager.pushUndo(snapshot, clearRedo)
    }

    fun pushRedoSnapshot(snapshot: TextSnapshot) {
        historyManager.pushRedo(snapshot)
    }

    fun popUndoSnapshot(): TextSnapshot? = historyManager.popUndo()

    fun popRedoSnapshot(): TextSnapshot? = historyManager.popRedo()

    private suspend fun discardBlankSharedDraftRecoveryIfNeeded(state: EditorState) {
        if (!shouldDiscardBlankSharedDraftRecovery(hasPendingSharedDraftRecovery, state.fileUri, state.text)) {
            return
        }
        clearCurrentSharedDraftRecovery()
    }

    private suspend fun saveIfNeeded(text: String, ignoreExternalChange: Boolean = false): Boolean {
        return saveMutex.withLock {
            if (!isLoaded) return@withLock false
            if (!ignoreExternalChange && _state.value.externalChangeDetectedAt != null) {
                _state.update { it.copy(showExternalChangeDialog = true) }
                return@withLock false
            }
            val dirUri = _state.value.dirUri
            var fileUri = _state.value.fileUri
            var textToSave = text
            if (!ignoreExternalChange && fileUri != null) {
                val modifiedAt = detectExternalChange(fileUri)
                if (modifiedAt != null) {
                    if (textToSave == lastSavedText) {
                        reloadFromDisk(fileUri, modifiedAt)
                    } else {
                        _state.update {
                            it.copy(
                                externalChangeDetectedAt = modifiedAt,
                                showExternalChangeDialog = true
                            )
                        }
                    }
                    return@withLock false
                }
            }
            if (textToSave == lastSavedText) return@withLock false
            val shouldClearSharedDraftRecovery = hasPendingSharedDraftRecovery
            if (fileUri == null) {
                if (dirUri == null || text.isBlank()) return@withLock false
                val desiredName = _state.value.proposedFileName ?: run {
                    val rawExtension = _state.value.newFileExtension.ifBlank { "txt" }
                    val extension = ".${rawExtension.lowercase(Locale.getDefault())}"
                    buildNameFromText(textToSave, extension)
                }
                val uniqueName = ensureUniqueName(dirUri, desiredName, null)
                if (_state.value.proposedFileName != null) {
                    _state.update {
                        it.copy(
                            proposedFileName = uniqueName,
                            fileName = uniqueName
                        )
                    }
                }
                fileUri = fileRepository.createFile(dirUri, uniqueName, fileRepository.guessMimeType(uniqueName))
                if (fileUri == null) return@withLock false
                _state.update { it.copy(fileUri = fileUri, fileName = uniqueName) }
            }
            var currentFileUri = fileUri ?: return@withLock false

            _state.update { it.copy(isSaving = true) }
            try {
                runNonCancellableWrite {
                    fileRepository.writeText(currentFileUri, textToSave)
                }
                lastSavedText = textToSave
                lastKnownModified = fileRepository.getLastModified(currentFileUri) ?: System.currentTimeMillis()

                if (_state.value.syncTitle && !_state.value.suppressSyncTitle) {
                    val currentName = _state.value.fileName
                    val ext = currentName.substringAfterLast('.', "txt")
                    val desiredName = buildNameFromText(textToSave, ".${ext}")
                    if (desiredName.isNotBlank() && desiredName != currentName && dirUri != null) {
                        val uniqueName = ensureUniqueName(dirUri, desiredName, currentName)
                        val renamedUri = fileRepository.renameFile(currentFileUri, uniqueName)
                        if (renamedUri != null) {
                            currentFileUri = renamedUri
                            _state.update { it.copy(fileUri = currentFileUri, fileName = uniqueName) }
                        }
                    }
                }

                _state.update { it.copy(lastSavedAt = System.currentTimeMillis()) }
                if (shouldClearSharedDraftRecovery) {
                    clearCurrentSharedDraftRecovery()
                }
                updateCanSaveState()
                syncScheduler.scheduleDebounced()
                return@withLock true
            } finally {
                _state.update { it.copy(isSaving = false) }
            }
        }
    }

    private suspend fun runSaveCatching(block: suspend () -> Boolean): Boolean {
        return try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            _state.update { it.copy(isSaving = false) }
            false
        }
    }

    private suspend fun detectExternalChange(fileUri: Uri): Long? {
        val current = fileRepository.getLastModified(fileUri) ?: return null
        val known = lastKnownModified
        if (known == null) {
            lastKnownModified = current
            return null
        }
        return if (current > known) current else null
    }

    private suspend fun reloadFromDisk(fileUri: Uri, modifiedAt: Long) {
        val updatedTextResult = fileRepository.readText(fileUri)
        val updatedText = updatedTextResult.text
        val updatedName = fileRepository.getDisplayName(fileUri) ?: _state.value.fileName
        clearHistory()
        _state.update {
            it.copy(
                text = updatedText,
                fileName = updatedName,
                externalChangeDetectedAt = null,
                showExternalChangeDialog = false,
                truncatedNoticeToken = nextTruncatedNoticeToken(updatedTextResult.truncated)
            )
        }
        lastSavedText = updatedText
        textChanges.value = updatedText
        lastKnownModified = modifiedAt
        updateCanSaveState()
    }

    private fun clearExternalChange() {
        _state.update {
            it.copy(
                externalChangeDetectedAt = null,
                showExternalChangeDialog = false
            )
        }
    }

    private suspend fun ensureUniqueName(dirUri: Uri, desiredName: String, currentName: String?): String {
        val names = fileRepository.listNamesInDirectory(dirUri)
        if (desiredName == currentName || !names.contains(desiredName)) return desiredName
        val base = desiredName.substringBeforeLast('.')
        val extension = desiredName.substringAfterLast('.', "").takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (index < 1000) {
            val candidate = fitFileNameToByteLimit(base = base, extension = extension, suffix = "($index)")
            if (!names.contains(candidate)) return candidate
            index++
        }
        return desiredName
    }

    private fun buildNameFromText(text: String, extension: String): String {
        return buildFileNameFromText(text, extension, fileRepository::sanitizeFileName)
    }

    private fun nextTruncatedNoticeToken(truncated: Boolean): Long? {
        if (!truncated) return null
        truncatedNoticeCounter += 1
        return truncatedNoticeCounter
    }

    private fun updateCanSaveState() {
        val current = _state.value
        val canSave = isLoaded &&
            current.text != lastSavedText &&
            (current.fileUri != null || (current.dirUri != null && current.text.isNotBlank()))
        if (current.canSave == canSave) return
        _state.update { it.copy(canSave = canSave) }
    }

    private suspend fun clearCurrentSharedDraftRecovery() {
        val draft = activeSharedDraftRecovery
        if (draft != null) {
            sharedDraftRecoveryStore.remove(draft)
        }
        hasPendingSharedDraftRecovery = false
        activeSharedDraftRecovery = null
    }

    private fun shouldSuppressSyncTitleForNote(dirUri: Uri?, fileName: String): Boolean {
        if (dirUri == null || fileName.isBlank()) return false
        if (!isManagedSharedFileName(fileName)) return false
        return fileRepository.getTreeDisplayName(dirUri) == SHARED_NOTES_FOLDER_NAME
    }
}
