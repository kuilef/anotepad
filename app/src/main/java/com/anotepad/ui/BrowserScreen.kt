package com.anotepad.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anotepad.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    onPickDirectory: () -> Unit,
    onOpenFile: (Uri, Uri) -> Unit,
    onNewFile: (Uri, String) -> Unit,
    onSearch: (Uri) -> Unit,
    onSettings: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    var showNewDialog by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.app_name)) },
                navigationIcon = {
                    // Никакого null: просто условно рисуем кнопку внутри composable-лямбды
                    if (state.dirStack.size > 1) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(
                                Icons.Default.ArrowUpward,
                                contentDescription = stringResource(id = R.string.action_back)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onPickDirectory) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(id = R.string.action_pick_folder)
                        )
                    }
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        enabled = state.currentDirUri != null
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(id = R.string.action_new_folder)
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(id = R.string.action_refresh)
                        )
                    }
                    state.currentDirUri?.let { dir ->
                        IconButton(onClick = { onSearch(dir) }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(id = R.string.action_search)
                            )
                        }
                    }
                    IconButton(onClick = onSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(id = R.string.action_settings)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            if (state.currentDirUri != null) {
                FloatingActionButton(onClick = { showNewDialog = true }) {
                    Icon(
                        Icons.Default.Create,
                        contentDescription = stringResource(id = R.string.action_new_note)
                    )
                }
            }
        }
    ) { padding ->
        when {
            state.rootUri == null -> {
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    message = stringResource(id = R.string.label_no_folder),
                    actionLabel = stringResource(id = R.string.action_pick_folder),
                    onAction = onPickDirectory
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    state.currentDirLabel?.let { path ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.label_current_folder),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    when {
                        state.isLoading -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = stringResource(id = R.string.label_searching))
                            }
                        }

                        state.entries.isEmpty() -> {
                            EmptyState(
                                modifier = Modifier.fillMaxSize(),
                                message = stringResource(id = R.string.label_empty_folder),
                                actionLabel = stringResource(id = R.string.action_pick_folder),
                                onAction = onPickDirectory
                            )
                        }

                        else -> {
                            val entryTextStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = state.fileListFontSizeSp.sp
                            )
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(state.entries) { entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (entry.isDirectory) {
                                                    viewModel.navigateInto(entry.uri)
                                                } else {
                                                    state.currentDirUri?.let { dir ->
                                                        onOpenFile(entry.uri, dir)
                                                    }
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (entry.isDirectory) {
                                                Icons.Default.FolderOpen // или Folder, если хотите отдельную иконку
                                            } else {
                                                Icons.Default.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Text(
                                            text = entry.name,
                                            style = entryTextStyle
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewFileDialog(
            defaultExtension = state.defaultFileExtension,
            onDismiss = { showNewDialog = false },
            onSelect = { extension ->
                state.currentDirUri?.let { onNewFile(it, extension) }
                showNewDialog = false
            }
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate = { name ->
                viewModel.createDirectory(name)
                showNewFolderDialog = false
            }
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    message: String,
    actionLabel: String,
    onAction: () -> Unit
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = actionLabel)
        }
    }
}

@Composable
private fun NewFileDialog(
    defaultExtension: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var selected by remember(defaultExtension) {
        mutableStateOf(defaultExtension.ifBlank { "txt" })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSelect(selected) }) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.label_create_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(id = R.string.label_extension))
                ExtensionOption(
                    label = stringResource(id = R.string.label_extension_txt),
                    value = "txt",
                    selected = selected == "txt",
                    onSelect = { selected = it }
                )
                ExtensionOption(
                    label = stringResource(id = R.string.label_extension_md),
                    value = "md",
                    selected = selected == "md",
                    onSelect = { selected = it }
                )
            }
        }
    )
}

@Composable
private fun ExtensionOption(
    label: String,
    value: String,
    selected: Boolean,
    onSelect: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(value) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = { onSelect(value) })
        Text(text = label)
    }
}

@Composable
private fun NewFolderDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = { onCreate(name) },
                enabled = name.trim().isNotEmpty()
            ) {
                Text(text = stringResource(id = R.string.action_create_folder))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        },
        title = { Text(text = stringResource(id = R.string.label_new_folder)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.hint_folder_name)) }
            )
        }
    )
}
