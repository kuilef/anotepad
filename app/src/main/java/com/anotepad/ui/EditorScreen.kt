package com.anotepad.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.util.LinkifyCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anotepad.R
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    onBack: () -> Unit,
    onOpenTemplates: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val pendingTemplate by viewModel.pendingTemplateFlow.collectAsState()
    var editTextRef by remember { mutableStateOf<EditText?>(null) }
    var ignoreChanges by remember { mutableStateOf(false) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val backgroundColor = MaterialTheme.colorScheme.surface.toArgb()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.saveNow()
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
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.saveNow() }) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(id = R.string.action_save)
                        )
                    }
                    IconButton(onClick = onOpenTemplates) {
                        Icon(
                            Icons.Default.List,
                            contentDescription = stringResource(id = R.string.action_templates)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    EditText(context).apply {
                        setText(state.text)
                        setBackgroundColor(backgroundColor)
                        setTextColor(textColor)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, state.editorFontSizeSp)
                        setPadding(12, 12, 12, 12)
                        gravity = Gravity.TOP or Gravity.START
                        setSingleLine(false)
                        setHorizontallyScrolling(false)
                        val density = context.resources.displayMetrics.density
                        scrollBarSize = (2f * density).roundToInt()
                        isScrollbarFadingEnabled = true
                        movementMethod = LinkMovementMethod.getInstance()
                        addTextChangedListener(object : TextWatcher {
                            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                            override fun afterTextChanged(s: Editable?) {
                                if (ignoreChanges) return
                                viewModel.updateText(s?.toString().orEmpty())
                            }
                        })
                        editTextRef = this
                    }
                },
                update = { editText ->
                    if (editText.text.toString() != state.text) {
                        ignoreChanges = true
                        val selection = editText.selectionStart
                        editText.setText(state.text)
                        val newSelection = selection.coerceAtMost(state.text.length)
                        editText.setSelection(newSelection)
                        ignoreChanges = false
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
