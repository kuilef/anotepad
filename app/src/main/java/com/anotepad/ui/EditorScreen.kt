package com.anotepad.ui

import android.content.Context
import android.text.method.ArrowKeyMovementMethod
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.widget.EditText
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.text.util.LinkifyCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anotepad.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: (EditorSaveResult?) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pendingTemplate by viewModel.pendingTemplateFlow.collectAsState()
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var ignoreChanges by remember { mutableStateOf(false) }
    var ignoreHistory by remember { mutableStateOf(false) }
    val undoStack = viewModel.undoStack
    val redoStack = viewModel.redoStack
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(id = R.string.label_saved)
    var showSavedBubble by remember { mutableStateOf(false) }
    var backInProgress by remember { mutableStateOf(false) }
    var linkifyJob by remember { mutableStateOf<Job?>(null) }
    var saveButtonRightPx by remember { mutableStateOf(0f) }
    var saveButtonBottomPx by remember { mutableStateOf(0f) }
    var savedBubbleSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val truncatedMessage = stringResource(id = R.string.label_editor_truncated_notice)

    fun triggerBack() {
        if (backInProgress) return
        backInProgress = true
        scope.launch {
            editTextRef?.let {
                hideKeyboard(it)
                it.clearFocus()
            }
            val result = viewModel.saveAndGetResult()
            if (viewModel.hasExternalChangePending()) {
                viewModel.showExternalChangeDialog()
                backInProgress = false
                return@launch
            }
            onBack(result)
        }
    }

    BackHandler(enabled = !backInProgress) {
        triggerBack()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNow()
            } else if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkExternalChange()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pendingTemplate) {
        val textToInsert = pendingTemplate
        if (!textToInsert.isNullOrEmpty()) {
            editTextRef?.let { editText ->
                val start = editText.selectionStart.coerceAtLeast(0)
                val end = editText.selectionEnd.coerceAtLeast(0)
                editText.text.replace(start.coerceAtMost(end), end.coerceAtLeast(start), textToInsert)
            }
            viewModel.consumeTemplate()
        }
    }

    LaunchedEffect(editTextRef, state.loadToken) {
        editTextRef?.let { focusAndShowKeyboard(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.manualSaveEvents.collect {
            showSavedBubble = true
            delay(1400)
            showSavedBubble = false
        }
    }

    LaunchedEffect(state.truncatedNoticeToken) {
        if (state.truncatedNoticeToken != null) {
            Toast.makeText(context, truncatedMessage, Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(editTextRef, state.text, state.autoLinkWeb, state.autoLinkEmail, state.autoLinkTel) {
        val editText = editTextRef ?: return@LaunchedEffect
        linkifyJob?.cancel()
        linkifyJob = scope.launch {
            delay(LINKIFY_DEBOUNCE_MS)
            applyLinkify(editText, state.autoLinkWeb, state.autoLinkEmail, state.autoLinkTel)
        }
    }

    fun currentSnapshot(): TextSnapshot {
        val editText = editTextRef
        val text = editText?.text?.toString() ?: state.text
        val selectionStart = (editText?.selectionStart ?: text.length).coerceAtLeast(0)
        val selectionEnd = (editText?.selectionEnd ?: text.length).coerceAtLeast(0)
        return TextSnapshot(text, selectionStart, selectionEnd)
    }

    fun applySnapshot(snapshot: TextSnapshot) {
        val editText = editTextRef ?: return
        val anotepadEditText = editText as? AnotepadEditorEditText
        fun applySelectionAndText() {
            editText.setText(snapshot.text)
            val length = snapshot.text.length
            val rawStart = snapshot.selectionStart.coerceIn(0, length)
            val rawEnd = snapshot.selectionEnd.coerceIn(0, length)
            val start = minOf(rawStart, rawEnd)
            val end = maxOf(rawStart, rawEnd)
            editText.setSelection(start, end)
        }
        ignoreHistory = true
        ignoreChanges = true
        if (anotepadEditText != null) {
            anotepadEditText.runWithoutHistoryAndChangeCallbacks {
                applySelectionAndText()
            }
        } else {
            applySelectionAndText()
        }
        ignoreChanges = false
        ignoreHistory = false
        viewModel.updateText(snapshot.text)
        editText.requestFocus()
    }

    fun performUndo() {
        val previous = viewModel.popUndoSnapshot() ?: return
        val current = currentSnapshot()
        viewModel.pushRedoSnapshot(current)
        applySnapshot(previous)
    }

    fun performRedo() {
        val next = viewModel.popRedoSnapshot() ?: return
        val current = currentSnapshot()
        viewModel.pushUndoSnapshot(current, clearRedo = false)
        applySnapshot(next)
    }

    if (state.showExternalChangeDialog && state.externalChangeDetectedAt != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissExternalChangeDialog() },
            title = { Text(text = stringResource(id = R.string.label_editor_external_change_title)) },
            text = { Text(text = stringResource(id = R.string.label_editor_external_change_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.overwriteExternalChange() }) {
                    Text(text = stringResource(id = R.string.action_overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.reloadExternalChange() }) {
                    Text(text = stringResource(id = R.string.action_reload))
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = {
                            triggerBack()
                        },
                        enabled = !backInProgress
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_back)
                        )
                    }
                },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        UndoRedoBar(
                            canUndo = undoStack.isNotEmpty(),
                            canRedo = redoStack.isNotEmpty(),
                            onUndo = ::performUndo,
                            onRedo = ::performRedo,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        IconButton(
                            onClick = { viewModel.saveNow(manual = state.fileUri != null) },
                            enabled = state.canSave && !state.isSaving,
                            modifier = Modifier.onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInRoot()
                                saveButtonRightPx = bounds.right
                                saveButtonBottomPx = bounds.bottom
                            }
                        ) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(id = R.string.action_save)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
            },
            contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        ) { padding ->
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navBottom = WindowInsets.navigationBars.getBottom(density)
            val bottomInset = with(density) { imeBottom.coerceAtLeast(navBottom).toDp() }
            val fileNameLabel = if (state.fileName.isBlank()) {
                stringResource(id = R.string.label_editor_title_new)
            } else {
                state.fileName
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(bottom = bottomInset)
            ) {
                Text(
                    text = fileNameLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                )
                AnotepadEditText(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    text = state.text,
                    editorFontSizeSp = state.editorFontSizeSp,
                    textColor = textColor,
                    backgroundColor = backgroundColor,
                    moveCursorToEndOnLoad = state.moveCursorToEndOnLoad,
                    loadToken = state.loadToken,
                    ignoreChanges = ignoreChanges,
                    ignoreHistory = ignoreHistory,
                    onIgnoreChangesChange = { ignoreChanges = it },
                    onPushUndoSnapshot = { snapshot ->
                        viewModel.pushUndoSnapshot(snapshot, clearRedo = true)
                    },
                    onTextChanged = { textValue -> viewModel.updateText(textValue) },
                    onUndoRequested = ::performUndo,
                    onRedoRequested = ::performRedo,
                    onEditTextRefChange = { editTextRef = it }
                )
            }
        }

        if (showSavedBubble && saveButtonRightPx > 0f && saveButtonBottomPx > 0f) {
            val gapPx = with(density) { 4.dp.roundToPx() }
            val bubbleX = (saveButtonRightPx.roundToInt() - savedBubbleSize.width - gapPx).coerceAtLeast(0)
            val bubbleY = saveButtonBottomPx.roundToInt() + gapPx
            SavedBubble(
                text = savedMessage,
                modifier = Modifier
                    .offset { IntOffset(bubbleX, bubbleY) }
                    .onSizeChanged { savedBubbleSize = it }
            )
        }
    }
}

@Composable
private fun SavedBubble(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UndoRedoBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onUndo,
                enabled = canUndo,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(id = R.string.action_undo)
                )
            }
            IconButton(
                onClick = onRedo,
                enabled = canRedo,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = stringResource(id = R.string.action_redo)
                )
            }
        }
    }
}

private fun applyLinkify(editText: EditText, web: Boolean, email: Boolean, tel: Boolean) {
    val mask = (if (web) Linkify.WEB_URLS else 0) or
        (if (email) Linkify.EMAIL_ADDRESSES else 0) or
        (if (tel) Linkify.PHONE_NUMBERS else 0)
    editText.autoLinkMask = 0
    if (mask != 0) {
        LinkifyCompat.addLinks(editText, mask)
        editText.linksClickable = true
        editText.movementMethod = LinkMovementMethod.getInstance()
    } else {
        editText.linksClickable = false
        editText.movementMethod = ArrowKeyMovementMethod.getInstance()
        editText.text?.let { text ->
            if (text is android.text.Spannable) {
                val spans = text.getSpans(0, text.length, android.text.style.URLSpan::class.java)
                spans.forEach { text.removeSpan(it) }
            }
        }
    }
}

private fun focusAndShowKeyboard(editText: EditText) {
    editText.requestFocus()
    editText.post {
        val imm = editText.context
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }
}

private fun hideKeyboard(editText: EditText) {
    val imm = editText.context
        .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.hideSoftInputFromWindow(editText.windowToken, 0)
}

private const val LINKIFY_DEBOUNCE_MS = 250L
