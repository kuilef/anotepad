package com.anotepad.ui

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
import androidx.compose.ui.unit.dp
import com.anotepad.R
import com.anotepad.data.TemplateItem

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
                        if (pickMode) {
                            onTemplatePicked(viewModel.renderTemplate(item))
                        } else {
                            editTarget = item
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
            onDismiss = { showNewDialog = false },
            onConfirm = { text ->
                viewModel.addTemplate(text)
                showNewDialog = false
            }
        )
    }

    editTarget?.let { target ->
        TemplateEditDialog(
            title = stringResource(id = R.string.label_edit_template),
            initialText = target.text,
            onDismiss = { editTarget = null },
            onConfirm = { text ->
                viewModel.updateTemplate(target.id, text)
                editTarget = null
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
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialText) }

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
                Text(
                    text = stringResource(id = R.string.label_date_time_template_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(text = stringResource(id = R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}
