package com.kuilef.anotepad.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kuilef.anotepad.R
import com.kuilef.anotepad.data.TemplateItem
import com.kuilef.anotepad.data.TemplateMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(
    viewModel: TemplatesViewModel,
    pickMode: Boolean,
    onBack: () -> Unit,
    onTemplatePicked: (String) -> Unit
) {
    val templates by viewModel.templates.collectAsState()
    var editTarget by remember { mutableStateOf<TemplateItem?>(null) }
    var showNewDialog by remember { mutableStateOf(false) }
    var pickNumberTarget by remember { mutableStateOf<TemplateItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (pickMode) {
                            stringResource(id = R.string.label_pick_template)
                        } else {
                            stringResource(id = R.string.label_template_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.action_back))
                    }
                }
            )
        },
        floatingActionButton = {
            if (!pickMode) {
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(id = R.string.label_add_template))
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(templates) { item ->
                TemplateRow(
                    item = item,
                    pickMode = pickMode,
                    onClick = {
                        when {
                            pickMode && (item.mode == TemplateMode.WITHNUMBER || item.mode == TemplateMode.TIME_NUMBER) -> {
                                pickNumberTarget = item
                            }
                            pickMode -> {
                                onTemplatePicked(viewModel.renderTemplate(item))
                            }
                            else -> {
                                editTarget = item
                            }
                        }
                    },
                    onDelete = {
                        if (!pickMode) {
                            viewModel.deleteTemplate(item.id)
                        }
                    }
                )
            }
        }
    }

    if (showNewDialog) {
        TemplateEditDialog(
            title = stringResource(id = R.string.label_add_template),
            initialText = "",
            initialMode = TemplateMode.NORMAL,
            onDismiss = { showNewDialog = false },
            onConfirm = { text, mode ->
                viewModel.addTemplate(text, mode)
                showNewDialog = false
            }
        )
    }

    editTarget?.let { target ->
        TemplateEditDialog(
            title = stringResource(id = R.string.label_edit_template),
            initialText = target.text,
            initialMode = target.mode,
            onDismiss = { editTarget = null },
            onConfirm = { text, mode ->
                viewModel.updateTemplate(target.id, text, mode)
                editTarget = null
            }
        )
    }

    pickNumberTarget?.let { target ->
        NumberInputDialog(
            onDismiss = { pickNumberTarget = null },
            onConfirm = { number ->
                val rendered = viewModel.renderTemplate(target, number)
                onTemplatePicked(rendered)
                pickNumberTarget = null
            }
        )
    }
}

@Composable
private fun TemplateRow(
    item: TemplateItem,
    pickMode: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = item.text, style = MaterialTheme.typography.bodyLarge)
            Text(text = item.mode.name.lowercase(), style = MaterialTheme.typography.labelSmall)
        }
        if (!pickMode) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = null)
            }
        }
    }
}

@Composable
private fun TemplateEditDialog(
    title: String,
    initialText: String,
    initialMode: TemplateMode,
    onDismiss: () -> Unit,
    onConfirm: (String, TemplateMode) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }
    var timeFormat by remember { mutableStateOf(initialMode == TemplateMode.TIMEFORMAT || initialMode == TemplateMode.TIME_NUMBER) }
    var withNumber by remember { mutableStateOf(initialMode == TemplateMode.WITHNUMBER || initialMode == TemplateMode.TIME_NUMBER) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = stringResource(id = R.string.label_template_text)) }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.label_time_format), modifier = Modifier.weight(1f))
                    Switch(checked = timeFormat, onCheckedChange = { timeFormat = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = stringResource(id = R.string.label_with_number), modifier = Modifier.weight(1f))
                    Switch(checked = withNumber, onCheckedChange = { withNumber = it })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mode = when {
                    timeFormat && withNumber -> TemplateMode.TIME_NUMBER
                    timeFormat -> TemplateMode.TIMEFORMAT
                    withNumber -> TemplateMode.WITHNUMBER
                    else -> TemplateMode.NORMAL
                }
                onConfirm(text, mode)
            }) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun NumberInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var numberText by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.label_template_number)) },
        text = {
            OutlinedTextField(
                value = numberText,
                onValueChange = { numberText = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(text = stringResource(id = R.string.label_template_number)) }
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val number = numberText.toIntOrNull() ?: 1
                onConfirm(number)
            }) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = android.R.string.cancel))
            }
        }
    )
}
