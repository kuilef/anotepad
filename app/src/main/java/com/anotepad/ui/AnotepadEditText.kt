package com.anotepad.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

@Composable
fun AnotepadEditText(
    text: String,
    editorFontSizeSp: Float,
    textColor: Int,
    backgroundColor: Int,
    moveCursorToEndOnLoad: Boolean,
    loadToken: Long,
    ignoreChanges: Boolean,
    ignoreHistory: Boolean,
    onIgnoreChangesChange: (Boolean) -> Unit,
    onPushUndoSnapshot: (TextSnapshot) -> Unit,
    onTextChanged: (String) -> Unit,
    onUndoRequested: () -> Unit,
    onRedoRequested: () -> Unit,
    onEditTextRefChange: (EditText?) -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingSnapshot by remember { mutableStateOf<TextSnapshot?>(null) }
    var lastCursorToken by remember { mutableStateOf<Long?>(null) }
    val latestIgnoreChanges by rememberUpdatedState(ignoreChanges)
    val latestIgnoreHistory by rememberUpdatedState(ignoreHistory)
    val latestOnIgnoreChangesChange by rememberUpdatedState(onIgnoreChangesChange)
    val latestOnPushUndoSnapshot by rememberUpdatedState(onPushUndoSnapshot)
    val latestOnTextChanged by rememberUpdatedState(onTextChanged)
    val latestOnUndoRequested by rememberUpdatedState(onUndoRequested)
    val latestOnRedoRequested by rememberUpdatedState(onRedoRequested)
    val latestOnEditTextRefChange by rememberUpdatedState(onEditTextRefChange)

    AndroidView(
        modifier = modifier,
        factory = { context ->
            object : EditText(context) {
                override fun onSelectionChanged(selStart: Int, selEnd: Int) {
                    super.onSelectionChanged(selStart, selEnd)
                    ensureCursorVisible(this, allowPost = false)
                }
            }.apply {
                setText(text)
                setBackgroundColor(backgroundColor)
                setTextColor(textColor)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, editorFontSizeSp)
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                imeOptions = imeOptions or
                    EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN
                gravity = Gravity.TOP or Gravity.START
                setSingleLine(false)
                setHorizontallyScrolling(false)
                isNestedScrollingEnabled = false
                isVerticalScrollBarEnabled = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                val density = context.resources.displayMetrics.density
                val paddingPx = (2f * density).roundToInt()
                setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
                scrollBarSize = (2f * density).roundToInt()
                isScrollbarFadingEnabled = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                        if (latestIgnoreChanges || latestIgnoreHistory) {
                            pendingSnapshot = null
                            return
                        }
                        val currentText = s?.toString().orEmpty()
                        val selectionStart = selectionStart.coerceAtLeast(0)
                        val selectionEnd = selectionEnd.coerceAtLeast(0)
                        pendingSnapshot = TextSnapshot(currentText, selectionStart, selectionEnd)
                    }

                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

                    override fun afterTextChanged(s: Editable?) {
                        if (latestIgnoreChanges) return
                        if (!latestIgnoreHistory) {
                            pendingSnapshot?.let { snapshot ->
                                val newText = s?.toString().orEmpty()
                                if (snapshot.text != newText) {
                                    latestOnPushUndoSnapshot(snapshot)
                                }
                            }
                        }
                        pendingSnapshot = null
                        latestOnTextChanged(s?.toString().orEmpty())
                        ensureCursorVisible(this@apply, allowPost = true)
                    }
                })
                setOnKeyListener { _, keyCode, event ->
                    if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                    val isUndo = event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_Z && !event.isShiftPressed
                    val isRedo = event.isCtrlPressed &&
                        ((keyCode == KeyEvent.KEYCODE_Z && event.isShiftPressed) || keyCode == KeyEvent.KEYCODE_Y)
                    when {
                        isUndo -> {
                            latestOnUndoRequested()
                            true
                        }

                        isRedo -> {
                            latestOnRedoRequested()
                            true
                        }

                        else -> false
                    }
                }
                latestOnEditTextRefChange(this)
            }
        },
        update = { editText ->
            if (editText.text.toString() != text) {
                latestOnIgnoreChangesChange(true)
                val selection = editText.selectionStart
                editText.setText(text)
                if (moveCursorToEndOnLoad && lastCursorToken != loadToken) {
                    editText.setSelection(text.length)
                    lastCursorToken = loadToken
                } else {
                    val newSelection = selection.coerceAtMost(text.length)
                    editText.setSelection(newSelection)
                }
                latestOnIgnoreChangesChange(false)
            } else if (moveCursorToEndOnLoad && lastCursorToken != loadToken) {
                editText.setSelection(editText.text.length)
                lastCursorToken = loadToken
            }
            if (editText.currentTextColor != textColor) {
                editText.setTextColor(textColor)
            }
            editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorFontSizeSp)
            editText.setBackgroundColor(backgroundColor)
            val density = editText.resources.displayMetrics.density
            val basePaddingPx = (2f * density).roundToInt()
            val extraLinePx = editText.lineHeight.coerceAtLeast(0)
            val targetBottom = basePaddingPx + extraLinePx
            if (
                editText.paddingLeft != basePaddingPx ||
                editText.paddingTop != basePaddingPx ||
                editText.paddingRight != basePaddingPx ||
                editText.paddingBottom != targetBottom
            ) {
                editText.setPadding(basePaddingPx, basePaddingPx, basePaddingPx, targetBottom)
            }
            ensureCursorVisible(editText, allowPost = false)
        },
        onRelease = {
            latestOnEditTextRefChange(null)
        }
    )
}

private fun ensureCursorVisible(editText: EditText, allowPost: Boolean) {
    val layout = editText.layout
    val visibleHeight = editText.height - editText.paddingTop - editText.paddingBottom
    if (layout == null || visibleHeight <= 0) {
        if (allowPost) {
            editText.post { ensureCursorVisible(editText, allowPost = false) }
        }
        return
    }
    val selection = editText.selectionStart.coerceAtLeast(0)
    val line = layout.getLineForOffset(selection)
    val lineBottom = layout.getLineBottom(line)
    val visibleBottom = editText.scrollY + visibleHeight
    if (lineBottom > visibleBottom) {
        editText.scrollTo(0, lineBottom - visibleHeight)
    }
}
