package com.anotepad.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anotepad.R
import com.anotepad.data.BrowserViewMode
import com.anotepad.file.DocumentNode
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
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
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var showFileActions by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showFolderAccessDialog by remember { mutableStateOf(false) }
    var renameInput by remember { mutableStateOf("") }
    var pendingDestinationAction by remember { mutableStateOf<FileAction?>(null) }
    var actionTarget by remember { mutableStateOf<DocumentNode?>(null) }

    LaunchedEffect(state.viewMode, state.feedResetSignal) {
        if (state.viewMode == BrowserViewMode.FEED) {
            viewModel.ensureFeedLoaded()
        }
    }

    val destinationOptions = buildDestinationOptions(
        state = state,
        currentLabel = stringResource(id = R.string.label_current_folder),
        parentLabel = stringResource(id = R.string.label_parent_folder)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
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
                    IconButton(
                        onClick = { showNewFolderDialog = true },
                        enabled = state.currentDirUri != null
                    ) {
                        Icon(
                            Icons.Default.CreateNewFolder,
                            contentDescription = stringResource(id = R.string.action_new_folder)
                        )
                    }
                    IconButton(onClick = { viewModel.refresh(force = true) }) {
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
                    IconButton(onClick = { viewModel.toggleViewMode() }) {
                        val icon = if (state.viewMode == BrowserViewMode.FEED) {
                            Icons.Default.List
                        } else {
                            Icons.Default.Article
                        }
                        val description = if (state.viewMode == BrowserViewMode.FEED) {
                            stringResource(id = R.string.action_toggle_list)
                        } else {
                            stringResource(id = R.string.action_toggle_feed)
                        }
                        Icon(
                            imageVector = icon,
                            contentDescription = description
                        )
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
                FloatingActionButton(
                    onClick = {
                        val extension = state.defaultFileExtension.ifBlank { "txt" }
                        val dir = state.currentDirUri
                        if (dir != null) {
                            onNewFile(dir, extension)
                        }
                    }
                ) {
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
                    onAction = {
                        if (state.showFolderAccessHint) {
                            showFolderAccessDialog = true
                        } else {
                            onPickDirectory()
                        }
                    }
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
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    when {
                        state.isLoading && state.entries.isEmpty() -> {
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
                                message = stringResource(id = R.string.label_empty_folder)
                            )
                        }

                        else -> {
                            val entryTextStyle = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = state.fileListFontSizeSp.sp
                            )
                            if (state.viewMode == BrowserViewMode.FEED) {
                                if ((state.isLoading || state.isLoadingMore) && state.feedItems.isEmpty()) {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = stringResource(id = R.string.label_searching))
                                    }
                                } else {
                                    val hasFiles = state.entries.any { !it.isDirectory }
                                    if (!hasFiles) {
                                        Box(
                                            modifier = Modifier.fillMaxSize(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(text = stringResource(id = R.string.label_no_notes))
                                        }
                                    } else {
                                        FeedList(
                                            items = state.feedItems,
                                            hasMore = state.feedHasMore,
                                            loading = state.feedLoading,
                                            fontSizeSp = state.fileListFontSizeSp,
                                            initialIndex = state.feedScrollIndex,
                                            initialOffset = state.feedScrollOffset,
                                            resetSignal = state.feedResetSignal,
                                            onLoadMore = viewModel::loadMoreFeed,
                                            onScrollChange = viewModel::updateFeedScroll,
                                            onOpenFile = { node ->
                                                state.currentDirUri?.let { dir ->
                                                    onOpenFile(node.uri, dir)
                                                }
                                            }
                                        )
                                    }
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    if (state.isLoadingMore) {
                                        Text(
                                            text = stringResource(id = R.string.label_loading_more),
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                        )
                                    }
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(state.entries) { entry ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .combinedClickable(
                                                        onClick = {
                                                            if (entry.isDirectory) {
                                                                viewModel.navigateInto(entry.uri)
                                                            } else {
                                                                state.currentDirUri?.let { dir ->
                                                                    onOpenFile(entry.uri, dir)
                                                                }
                                                            }
                                                        },
                                                        onLongClick = {
                                                            if (!entry.isDirectory) {
                                                                actionTarget = entry
                                                                showFileActions = true
                                                            }
                                                        }
                                                    )
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
                                        if (state.isLoadingMore) {
                                            item {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(16.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(text = stringResource(id = R.string.label_loading_more))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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

    if (showFileActions && actionTarget != null) {
        FileActionsDialog(
            onOpen = {
                val target = actionTarget
                showFileActions = false
                if (target != null) {
                    state.currentDirUri?.let { dir -> onOpenFile(target.uri, dir) }
                }
            },
            onDelete = {
                showFileActions = false
                showDeleteDialog = true
            },
            onRename = {
                showFileActions = false
                renameInput = actionTarget?.name.orEmpty()
                showRenameDialog = true
            },
            onCopy = {
                showFileActions = false
                pendingDestinationAction = FileAction.COPY
            },
            onMove = {
                showFileActions = false
                pendingDestinationAction = FileAction.MOVE
            },
            onCancel = {
                showFileActions = false
            }
        )
    }

    if (showDeleteDialog && actionTarget != null) {
        ConfirmDeleteDialog(
            fileName = actionTarget?.name.orEmpty(),
            onConfirm = {
                val target = actionTarget
                showDeleteDialog = false
                if (target != null) {
                    viewModel.deleteFile(target)
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showFolderAccessDialog) {
        AlertDialog(
            onDismissRequest = {
                showFolderAccessDialog = false
                viewModel.markFolderAccessHintShown()
            },
            confirmButton = {
                Button(
                    onClick = {
                        showFolderAccessDialog = false
                        viewModel.markFolderAccessHintShown()
                        onPickDirectory()
                    }
                ) {
                    Text(text = stringResource(id = R.string.action_continue))
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        showFolderAccessDialog = false
                        viewModel.markFolderAccessHintShown()
                    }
                ) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.label_folder_access_title)) },
            text = { Text(text = stringResource(id = R.string.label_folder_access_message)) }
        )
    }

    if (showRenameDialog && actionTarget != null) {
        RenameFileDialog(
            initialName = renameInput,
            onRename = { name ->
                val target = actionTarget
                showRenameDialog = false
                if (target != null) {
                    viewModel.renameFile(target, name)
                }
            },
            onDismiss = { showRenameDialog = false }
        )
    }

    val destinationAction = pendingDestinationAction
    if (destinationAction != null && actionTarget != null) {
        DestinationPickerDialog(
            title = if (destinationAction == FileAction.COPY) {
                stringResource(id = R.string.label_copy_to)
            } else {
                stringResource(id = R.string.label_move_to)
            },
            options = destinationOptions,
            onSelect = { option ->
                val target = actionTarget
                pendingDestinationAction = null
                if (target != null) {
                    if (destinationAction == FileAction.COPY) {
                        viewModel.copyFile(target, option.uri)
                    } else {
                        viewModel.moveFile(target, option.uri)
                    }
                }
            },
            onDismiss = { pendingDestinationAction = null }
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction, modifier = Modifier.padding(top = 16.dp)) {
                Text(text = actionLabel)
            }
        }
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

@Composable
private fun FeedList(
    items: List<FeedItem>,
    hasMore: Boolean,
    loading: Boolean,
    fontSizeSp: Float,
    initialIndex: Int,
    initialOffset: Int,
    resetSignal: Int,
    onLoadMore: () -> Unit,
    onScrollChange: (Int, Int) -> Unit,
    onOpenFile: (DocumentNode) -> Unit
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialOffset
    )
    var lastResetSignal by remember { mutableStateOf(resetSignal) }
    val textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSizeSp.sp)

    LaunchedEffect(resetSignal) {
        if (lastResetSignal != resetSignal) {
            listState.scrollToItem(0, 0)
            lastResetSignal = resetSignal
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) -> onScrollChange(index, offset) }
    }

    LaunchedEffect(listState, hasMore, loading, items.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                if (lastVisible != null && items.isNotEmpty() && hasMore && !loading &&
                    lastVisible >= items.size - 3
                ) {
                    onLoadMore()
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(items, key = { it.node.uri.toString() }) { item ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFile(item.node) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)
                ) {
                    Text(text = buildFeedAnnotatedText(item.text), style = textStyle)
                }
                Divider(
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = stringResource(id = R.string.label_loading_more))
                }
            }
        }
    }
}

@Composable
private fun FileActionsDialog(
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text = stringResource(id = R.string.label_file_actions)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_open))
                }
                TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_delete))
                }
                TextButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_rename))
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_copy))
                }
                TextButton(onClick = onMove, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_move))
                }
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

@Composable
private fun ConfirmDeleteDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.label_delete_file)) },
        text = { Text(text = stringResource(id = R.string.label_delete_file_message, fileName)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(text = stringResource(id = R.string.action_delete))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun RenameFileDialog(
    initialName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.label_rename_file)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.hint_file_name)) }
            )
        },
        confirmButton = {
            Button(
                onClick = { onRename(name) },
                enabled = name.trim().isNotEmpty()
            ) {
                Text(text = stringResource(id = R.string.action_rename))
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun DestinationPickerDialog(
    title: String,
    options: List<DestinationOption>,
    onSelect: (DestinationOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                options.forEach { option ->
                    TextButton(
                        onClick = { onSelect(option) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = option.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.action_cancel))
            }
        }
    )
}

private fun buildFeedAnnotatedText(text: String) = buildAnnotatedString {
    val normalized = text.replace("\r\n", "\n")
    val parts = normalized.split("\n", limit = 2)
    val firstLine = parts.getOrElse(0) { "" }
    val rest = if (parts.size > 1) "\n${parts[1]}" else ""
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
        append(firstLine)
    }
    if (rest.isNotEmpty()) {
        append(rest)
    }
}

private enum class FileAction {
    COPY,
    MOVE
}

private data class DestinationOption(
    val label: String,
    val uri: Uri
)

private fun buildDestinationOptions(
    state: BrowserState,
    currentLabel: String,
    parentLabel: String
): List<DestinationOption> {
    val currentDir = state.currentDirUri ?: return emptyList()
    val options = mutableListOf<DestinationOption>()
    options.add(DestinationOption(label = currentLabel, uri = currentDir))
    if (state.dirStack.size > 1) {
        options.add(DestinationOption(label = parentLabel, uri = state.dirStack[state.dirStack.size - 2]))
    }
    state.entries.filter { it.isDirectory }.forEach { entry ->
        options.add(DestinationOption(label = entry.name, uri = entry.uri))
    }
    return options
}
