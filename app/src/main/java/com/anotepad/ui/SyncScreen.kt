package com.anotepad.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anotepad.R
import com.anotepad.sync.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.handleSignInResult(it.data)
    }

    if (state.showFolderConflictDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelFolderSelection() },
            title = {
                Text(
                    text = stringResource(
                        id = R.string.label_drive_folder_conflict_title,
                        state.prefs.driveSyncFolderName
                    )
                )
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { viewModel.cancelFolderSelection() }) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            text = {
                LazyColumn {
                    items(state.foundFolders) { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectDriveFolder(folder) }
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = folder.name)
                            Text(
                                text = folder.id.take(5) + "...",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.label_drive_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(id = R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SyncToggleRow(
                title = stringResource(id = R.string.label_drive_sync_enabled),
                checked = state.prefs.driveSyncEnabled,
                onToggle = viewModel::setSyncEnabled
            )

            Text(
                text = stringResource(id = R.string.label_drive_sync_hint),
                style = MaterialTheme.typography.labelSmall
            )

            SectionHeader(text = stringResource(id = R.string.label_drive_account))
            Text(
                text = state.accountEmail ?: stringResource(id = R.string.label_drive_signed_out),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { signInLauncher.launch(viewModel.signInIntent()) },
                    enabled = !state.isSignedIn
                ) {
                    Text(text = stringResource(id = R.string.action_drive_sign_in))
                }
                OutlinedButton(
                    onClick = { viewModel.signOut() },
                    enabled = state.isSignedIn
                ) {
                    Text(text = stringResource(id = R.string.action_drive_sign_out))
                }
            }

            SectionHeader(text = stringResource(id = R.string.label_drive_folder))
            if (!state.driveFolderId.isNullOrBlank()) {
                Text(
                    text = stringResource(
                        id = R.string.label_drive_folder_connected,
                        state.driveFolderName ?: state.prefs.driveSyncFolderName
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                OutlinedButton(onClick = { viewModel.disconnectFolder() }) {
                    Text(text = stringResource(id = R.string.action_drive_disconnect_folder))
                }
            } else {
                Text(
                    text = stringResource(id = R.string.label_drive_folder_not_set),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (state.isSignedIn) {
                    Button(
                        onClick = { viewModel.checkAndConnectDriveFolder() },
                        enabled = !state.isLoadingFolders
                    ) {
                        Text(
                            text = if (state.isLoadingFolders) {
                                stringResource(id = R.string.label_drive_folder_searching)
                            } else {
                                stringResource(
                                    id = R.string.action_drive_auto_connect,
                                    state.prefs.driveSyncFolderName
                                )
                            }
                        )
                    }
                }
            }

            SectionHeader(text = stringResource(id = R.string.label_drive_status))
            Text(text = statusText(state), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = viewModel::syncNow,
                enabled = state.prefs.driveSyncEnabled && !state.prefs.driveSyncPaused && state.isSignedIn
            ) {
                Text(text = stringResource(id = R.string.action_drive_sync_now))
            }

            SectionHeader(text = stringResource(id = R.string.label_drive_constraints))
            SyncToggleRow(
                title = stringResource(id = R.string.label_drive_wifi_only),
                checked = state.prefs.driveSyncWifiOnly,
                onToggle = viewModel::setWifiOnly
            )
            SyncToggleRow(
                title = stringResource(id = R.string.label_drive_charging_only),
                checked = state.prefs.driveSyncChargingOnly,
                onToggle = viewModel::setChargingOnly
            )
            SyncToggleRow(
                title = stringResource(id = R.string.label_drive_pause_sync),
                checked = state.prefs.driveSyncPaused,
                onToggle = viewModel::setPaused
            )
            SyncToggleRow(
                title = stringResource(id = R.string.label_drive_ignore_deletes),
                checked = state.prefs.driveSyncIgnoreRemoteDeletes,
                onToggle = viewModel::setIgnoreRemoteDeletes
            )

            if (!state.errorMessage.isNullOrBlank()) {
                Text(
                    text = state.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SyncToggleRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

private fun statusText(state: SyncUiState): String {
    val base = when (state.status) {
        SyncState.RUNNING -> "Syncing"
        SyncState.PENDING -> "Waiting"
        SyncState.ERROR -> "Error"
        SyncState.SYNCED -> "Synced"
        SyncState.IDLE -> "Idle"
    }
    return state.statusMessage?.let { "$base · $it" } ?: base
}
