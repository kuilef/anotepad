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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anotepad.R
import com.anotepad.data.BrowserViewMode
import com.anotepad.file.DocumentNode
import kotlinx.coroutines.flow.collect

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
    var showNewFolderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.viewMode, state.feedResetSignal) {
        if (state.viewMode == BrowserViewMode.FEED) {
            viewModel.ensureFeedLoaded()
        }
    }

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
                            if (state.viewMode == BrowserViewMode.FEED) {
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
                            } else {
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(text = buildFeedAnnotatedText(item.text), style = textStyle)
                Text(
                    text = "----",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp)
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
