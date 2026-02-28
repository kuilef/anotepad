package com.anotepad.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.KeyListener
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.VelocityTracker
import android.widget.EditText
import android.widget.OverScroller
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class AnotepadEditorEditText(context: Context) : EditText(context) {
    private val viewConfiguration = ViewConfiguration.get(context)
    private val scroller = OverScroller(context)
    private val density = context.resources.displayMetrics.density
    private val scrollIndicatorWidthPx = max(1, (2f * density).roundToInt())
    private val scrollIndicatorInsetPx = max(1, (1f * density).roundToInt())
    private val minScrollIndicatorHeightPx = max(1, (24f * density).roundToInt())
    private val scrollIndicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(96, 0, 0, 0)
    }
    private var velocityTracker: VelocityTracker? = null
    private var suppressChangesDepth = 0
    private var suppressHistoryDepth = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var startScrollY = 0
    private var hasVerticalDrag = false
    private var hasMultiplePointers = false
    private var deferCursorVisibilityUntilTouchEnds = false
    private var hasDeferredCursorVisibilityUpdate = false
    private var restoreCursorAfterTouch = false
    private var editableKeyListener: KeyListener? = null
    private var readOnly = false

    fun runWithoutHistoryAndChangeCallbacks(block: () -> Unit) {
        suppressChangesDepth += 1
        suppressHistoryDepth += 1
        try {
            block()
        } finally {
            suppressHistoryDepth = (suppressHistoryDepth - 1).coerceAtLeast(0)
            suppressChangesDepth = (suppressChangesDepth - 1).coerceAtLeast(0)
        }
    }

    fun isChangeCallbacksSuppressed(): Boolean = suppressChangesDepth > 0

    fun isHistoryCallbacksSuppressed(): Boolean = suppressHistoryDepth > 0

    fun updateScrollIndicatorColor(textColor: Int) {
        scrollIndicatorPaint.color = Color.argb(
            96,
            Color.red(textColor),
            Color.green(textColor),
            Color.blue(textColor)
        )
    }

    fun stopFling() {
        abortFling()
        restoreCursorVisibilityAfterTouch()
        recycleVelocityTracker()
        resetTouchTracking()
    }

    fun setReadOnly(enabled: Boolean) {
        if (editableKeyListener == null && keyListener != null) {
            editableKeyListener = keyListener
        }
        if (readOnly == enabled) {
            showSoftInputOnFocus = !enabled
            isCursorVisible = !enabled
            setTextIsSelectable(enabled)
            return
        }
        readOnly = enabled
        if (enabled) {
            editableKeyListener = keyListener ?: editableKeyListener
            keyListener = null
            showSoftInputOnFocus = false
            isCursorVisible = false
            setTextIsSelectable(true)
        } else {
            if (keyListener == null) {
                keyListener = editableKeyListener
            }
            showSoftInputOnFocus = true
            isCursorVisible = true
            setTextIsSelectable(false)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                abortFling()
                resetTouchTracking()
                deferCursorVisibilityUntilTouchEnds = true
                hasDeferredCursorVisibilityUpdate = false
                restoreCursorAfterTouch = isCursorVisible
                if (restoreCursorAfterTouch) {
                    isCursorVisible = false
                }
                activePointerId = event.getPointerId(0)
                initialTouchX = event.x
                initialTouchY = event.y
                startScrollY = scrollY
                recycleVelocityTracker()
                velocityTracker = VelocityTracker.obtain()
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                hasMultiplePointers = true
            }
        }

        velocityTracker?.addMovement(event)
        val handled = super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                updateVerticalDragState(event)
            }

            MotionEvent.ACTION_UP -> {
                deferCursorVisibilityUntilTouchEnds = false
                maybeStartFling()
                flushDeferredCursorVisibility()
                restoreCursorVisibilityAfterTouch()
                recycleVelocityTracker()
                resetTouchTracking()
            }

            MotionEvent.ACTION_CANCEL -> {
                deferCursorVisibilityUntilTouchEnds = false
                flushDeferredCursorVisibility()
                restoreCursorVisibilityAfterTouch()
                recycleVelocityTracker()
                resetTouchTracking()
            }
        }

        return handled
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        canvas.translate(scrollX.toFloat(), scrollY.toFloat())
        drawScrollIndicator(canvas)
        canvas.restore()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scrollX, scroller.currY)
            postInvalidateOnAnimation()
            return
        }
        super.computeScroll()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (deferCursorVisibilityUntilTouchEnds) {
            hasDeferredCursorVisibilityUpdate = true
            return
        }
        ensureCursorVisible(this, allowPost = false)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        abortFling()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        abortFling()
        recycleVelocityTracker()
        super.onDetachedFromWindow()
    }

    private fun updateVerticalDragState(event: MotionEvent) {
        if (hasMultiplePointers) return
        val pointerIndex = event.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return
        val totalDy = event.getY(pointerIndex) - initialTouchY
        val totalDx = event.getX(pointerIndex) - initialTouchX
        if (abs(totalDy) <= viewConfiguration.scaledTouchSlop) return
        if (abs(totalDy) > abs(totalDx) || abs(scrollY - startScrollY) > viewConfiguration.scaledTouchSlop) {
            hasVerticalDrag = true
        }
    }

    private fun maybeStartFling() {
        if (!hasVerticalDrag || hasMultiplePointers) return
        val scrollRange = computeVerticalScrollRange() - computeVerticalScrollExtent()
        if (scrollRange <= 0) return
        val tracker = velocityTracker ?: return
        tracker.computeCurrentVelocity(1000, viewConfiguration.scaledMaximumFlingVelocity.toFloat())
        val velocityY = tracker.getYVelocity(activePointerId)
        val velocityX = tracker.getXVelocity(activePointerId)
        if (abs(velocityY) < viewConfiguration.scaledMinimumFlingVelocity) return
        if (abs(velocityY) <= abs(velocityX)) return
        scroller.fling(
            scrollX,
            scrollY,
            0,
            (-velocityY).roundToInt(),
            0,
            0,
            0,
            scrollRange
        )
        postInvalidateOnAnimation()
    }

    private fun abortFling() {
        if (!scroller.isFinished) {
            scroller.forceFinished(true)
        }
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun flushDeferredCursorVisibility() {
        if (!hasDeferredCursorVisibilityUpdate) return
        hasDeferredCursorVisibilityUpdate = false
        ensureCursorVisible(this, allowPost = false)
    }

    private fun restoreCursorVisibilityAfterTouch() {
        if (!restoreCursorAfterTouch) return
        isCursorVisible = true
        restoreCursorAfterTouch = false
    }

    private fun drawScrollIndicator(canvas: Canvas) {
        val range = computeVerticalScrollRange()
        val extent = computeVerticalScrollExtent()
        if (range <= extent || extent <= 0) return
        val trackTop = paddingTop + scrollIndicatorInsetPx
        val trackBottom = height - paddingBottom - scrollIndicatorInsetPx
        val trackHeight = trackBottom - trackTop
        if (trackHeight <= 0) return
        val thumbHeight = max(minScrollIndicatorHeightPx, ((trackHeight.toFloat() * extent) / range).roundToInt())
            .coerceAtMost(trackHeight)
        val maxOffset = (range - extent).coerceAtLeast(1)
        val offset = computeVerticalScrollOffset().coerceIn(0, maxOffset)
        val thumbTop = trackTop + ((trackHeight - thumbHeight) * offset.toFloat() / maxOffset).roundToInt()
        val thumbLeft = width - scrollIndicatorInsetPx - scrollIndicatorWidthPx
        val thumbRight = width - scrollIndicatorInsetPx
        canvas.drawRoundRect(
            thumbLeft.toFloat(),
            thumbTop.toFloat(),
            thumbRight.toFloat(),
            (thumbTop + thumbHeight).toFloat(),
            scrollIndicatorWidthPx.toFloat(),
            scrollIndicatorWidthPx.toFloat(),
            scrollIndicatorPaint
        )
    }

    private fun resetTouchTracking() {
        activePointerId = MotionEvent.INVALID_POINTER_ID
        hasVerticalDrag = false
        hasMultiplePointers = false
        deferCursorVisibilityUntilTouchEnds = false
        hasDeferredCursorVisibilityUpdate = false
        restoreCursorAfterTouch = false
        initialTouchX = 0f
        initialTouchY = 0f
        startScrollY = scrollY
    }
}

@Composable
fun AnotepadEditText(
    text: String,
    editorFontSizeSp: Float,
    textColor: Int,
    backgroundColor: Int,
    readOnly: Boolean,
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
            AnotepadEditorEditText(context).apply {
                setText(text)
                setBackgroundColor(backgroundColor)
                setTextColor(textColor)
                updateScrollIndicatorColor(textColor)
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
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                setReadOnly(readOnly)
                val density = context.resources.displayMetrics.density
                val horizontalPaddingPx = (12f * density).roundToInt()
                val topPaddingPx = (8f * density).roundToInt()
                val bottomPaddingPx = (8f * density).roundToInt()
                setPaddingRelative(
                    horizontalPaddingPx,
                    topPaddingPx,
                    horizontalPaddingPx,
                    bottomPaddingPx
                )
                scrollBarSize = (2f * density).roundToInt()
                isScrollbarFadingEnabled = true
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                        if (
                            latestIgnoreChanges ||
                            latestIgnoreHistory ||
                            this@apply.isChangeCallbacksSuppressed() ||
                            this@apply.isHistoryCallbacksSuppressed()
                        ) {
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
                        if (latestIgnoreChanges || this@apply.isChangeCallbacksSuppressed()) {
                            pendingSnapshot = null
                            return
                        }
                        if (!latestIgnoreHistory) {
                            if (!this@apply.isHistoryCallbacksSuppressed()) {
                                pendingSnapshot?.let { snapshot ->
                                    val newText = s?.toString().orEmpty()
                                    if (snapshot.text != newText) {
                                        latestOnPushUndoSnapshot(snapshot)
                                    }
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
                editText.stopFling()
                latestOnIgnoreChangesChange(true)
                editText.runWithoutHistoryAndChangeCallbacks {
                    val selection = editText.selectionStart
                    editText.setText(text)
                    if (moveCursorToEndOnLoad && lastCursorToken != loadToken) {
                        editText.setSelection(text.length)
                        lastCursorToken = loadToken
                    } else {
                        val newSelection = selection.coerceAtMost(text.length)
                        editText.setSelection(newSelection)
                    }
                }
                latestOnIgnoreChangesChange(false)
            } else if (moveCursorToEndOnLoad && lastCursorToken != loadToken) {
                editText.setSelection(editText.text.length)
                lastCursorToken = loadToken
            }
            if (editText.currentTextColor != textColor) {
                editText.setTextColor(textColor)
            }
            editText.updateScrollIndicatorColor(textColor)
            editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, editorFontSizeSp)
            editText.setBackgroundColor(backgroundColor)
            editText.setReadOnly(readOnly)
            val density = editText.resources.displayMetrics.density
            val horizontalPaddingPx = (12f * density).roundToInt()
            val topPaddingPx = (8f * density).roundToInt()
            val targetBottom = (8f * density).roundToInt()
            if (
                editText.paddingStart != horizontalPaddingPx ||
                editText.paddingTop != topPaddingPx ||
                editText.paddingEnd != horizontalPaddingPx ||
                editText.paddingBottom != targetBottom
            ) {
                editText.setPaddingRelative(
                    horizontalPaddingPx,
                    topPaddingPx,
                    horizontalPaddingPx,
                    targetBottom
                )
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
