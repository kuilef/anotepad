package com.anotepad.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anotepad.R
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
    var pendingSnapshot by remember { mutableStateOf<TextSnapshot?>(null) }
    val undoStack = remember { mutableStateListOf<TextSnapshot>() }
    val redoStack = remember { mutableStateListOf<TextSnapshot>() }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val savedMessage = stringResource(id = R.string.label_saved)
    var showSavedBubble by remember { mutableStateOf(false) }
    var lastCursorToken by remember { mutableStateOf<Long?>(null) }
    var backInProgress by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.loadToken) {
        undoStack.clear()
        redoStack.clear()
        pendingSnapshot = null
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

    fun currentSnapshot(): TextSnapshot {
        val editText = editTextRef
        val text = editText?.text?.toString() ?: state.text
        val selectionStart = (editText?.selectionStart ?: text.length).coerceAtLeast(0)
        val selectionEnd = (editText?.selectionEnd ?: text.length).coerceAtLeast(0)
        return TextSnapshot(text, selectionStart, selectionEnd)
    }

    fun applySnapshot(snapshot: TextSnapshot) {
        val editText = editTextRef ?: return
        ignoreHistory = true
        ignoreChanges = true
        editText.setText(snapshot.text)
        val length = snapshot.text.length
        val rawStart = snapshot.selectionStart.coerceIn(0, length)
        val rawEnd = snapshot.selectionEnd.coerceIn(0, length)
        val start = minOf(rawStart, rawEnd)
        val end = maxOf(rawStart, rawEnd)
        editText.setSelection(start, end)
        ignoreChanges = false
        ignoreHistory = false
        pendingSnapshot = null
        viewModel.updateText(snapshot.text)
        editText.requestFocus()
    }

    fun performUndo() {
        if (undoStack.isEmpty()) return
        val current = currentSnapshot()
        val previous = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(current)
        applySnapshot(previous)
    }

    fun performRedo() {
        if (redoStack.isEmpty()) return
        val current = currentSnapshot()
        val next = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(current)
        applySnapshot(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val name = if (state.fileName.isBlank()) {
                        stringResource(id = R.string.label_editor_title_new)
                    } else {
                        state.fileName
                    }
                    Text(text = name)
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (backInProgress) return@IconButton
                            backInProgress = true
                            scope.launch {
                                val result = viewModel.saveAndGetResult()
                                onBack(result)
                            }
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
                    Box {
                        IconButton(onClick = { viewModel.saveNow(manual = state.fileUri != null) }) {
                            Icon(
                                Icons.Default.Save,
                                contentDescription = stringResource(id = R.string.action_save)
                            )
                        }
                        if (showSavedBubble) {
                            SavedBubble(
                                text = savedMessage,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 12.dp, y = (-6).dp)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    EditText(context).apply {
                        setText(state.text)
                        setBackgroundColor(backgroundColor)
                        setTextColor(textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, state.editorFontSizeSp)
                        gravity = Gravity.TOP or Gravity.START
                        setSingleLine(false)
                        setHorizontallyScrolling(false)
                        val density = context.resources.displayMetrics.density
                        val paddingPx = (4f * density).roundToInt()
                        setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                        scrollBarSize = (2f * density).roundToInt()
                        isScrollbarFadingEnabled = true
                        movementMethod = LinkMovementMethod.getInstance()
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                                if (ignoreChanges || ignoreHistory) {
                                    pendingSnapshot = null
                                    return
                                }
                                val text = s?.toString().orEmpty()
                                val selectionStart = selectionStart.coerceAtLeast(0)
                                val selectionEnd = selectionEnd.coerceAtLeast(0)
                                pendingSnapshot = TextSnapshot(text, selectionStart, selectionEnd)
                            }
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                if (ignoreChanges) return
                                if (!ignoreHistory) {
                                    pendingSnapshot?.let { snapshot ->
                                        val newText = s?.toString().orEmpty()
                                        if (snapshot.text != newText) {
                                            undoStack.add(snapshot)
                                            if (undoStack.size > UNDO_HISTORY_LIMIT) {
                                                undoStack.removeAt(0)
                                            }
                                            redoStack.clear()
                                        }
                                    }
                                }
                                pendingSnapshot = null
                                viewModel.updateText(s?.toString().orEmpty())
                            }
                        })
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                            val isUndo = event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z && !event.isShiftPressed
                            val isRedo = event.isCtrlPressed &&
                                ((keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed) || keyCode == KeyEvent.KEYCODE_Y)
                            when {
                                isUndo -> {
                                    performUndo()
                                    true
                                }

                                isRedo -> {
                                    performRedo()
                                    true
                                }

                                else -> false
                            }
                        }
                        editTextRef = this
                    }
                },
                update = { editText ->
                    if (editText.text.toString() != state.text) {
                        ignoreChanges = true
                        val selection = editText.selectionStart
                        editText.setText(state.text)
                        if (state.moveCursorToEndOnLoad && lastCursorToken != state.loadToken) {
                            editText.setSelection(state.text.length)
                            lastCursorToken = state.loadToken
                        } else {
                            val newSelection = selection.coerceAtMost(state.text.length)
                            editText.setSelection(newSelection)
                        }
                        ignoreChanges = false
                    } else if (state.moveCursorToEndOnLoad && lastCursorToken != state.loadToken) {
                        editText.setSelection(editText.text.length)
                        lastCursorToken = state.loadToken
                    }
                    if (editText.currentTextColor != textColor) {
                        editText.setTextColor(textColor)
                    }
                    editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, state.editorFontSizeSp)
                    editText.setBackgroundColor(backgroundColor)
                    val availableHeight = editText.height - editText.paddingTop - editText.paddingBottom
                    val contentHeight = editText.lineCount * editText.lineHeight
                    editText.isVerticalScrollBarEnabled = availableHeight > 0 && contentHeight > availableHeight
                    applyLinkify(editText, state.autoLinkWeb, state.autoLinkEmail, state.autoLinkTel)
                }
            )
            UndoRedoBar(
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = ::performUndo,
                onRedo = ::performRedo,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .imePadding()
                    .padding(end = 8.dp, bottom = 8.dp)
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
    } else {
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

private data class TextSnapshot(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int
)

private const val UNDO_HISTORY_LIMIT = 200
