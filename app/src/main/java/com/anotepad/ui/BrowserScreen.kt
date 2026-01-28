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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Create
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
import androidx.compose.ui.unit.dp
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

            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stringResource(id = R.string.label_searching))
                }
            }

            state.entries.isEmpty() -> {
                EmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    message = stringResource(id = R.string.label_empty_folder),
                    actionLabel = stringResource(id = R.string.action_pick_folder),
                    onAction = onPickDirectory
                )
            }

            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    state.currentDirUri?.let {
                        Text(
                            text = stringResource(id = R.string.label_current_folder),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
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
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = if (entry.isDirectory) {
                                        Icons.Default.FolderOpen // или Folder, если хотите отдельную иконку
                                    } else {
                                        Icons.Default.InsertDriveFile
                                    },
                                    contentDescription = null
                                )
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showNewDialog) {
        NewFileDialog(
            onDismiss = { showNewDialog = false },
            onSelect = { extension ->
                state.currentDirUri?.let { onNewFile(it, extension) }
                showNewDialog = false
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
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = { Text(text = stringResource(id = R.string.label_create_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSelect("txt") }) {
                    Text(text = stringResource(id = R.string.action_new_note))
                }
                Button(onClick = { onSelect("md") }) {
                    Text(text = stringResource(id = R.string.action_new_markdown))
                }
            }
        }
    )
}
