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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.anotepad.sync.SyncState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(viewModel: SyncViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    var showFolderPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var newFolderName by remember(state.prefs.driveSyncFolderName) {
        mutableStateOf(state.prefs.driveSyncFolderName)
    }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.handleSignInResult()
    }

    if (showFolderPicker) {
        AlertDialog(
            onDismissRequest = { showFolderPicker = false },
            title = { Text(text = stringResource(id = R.string.label_drive_pick_folder)) },
            confirmButton = {},
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isLoadingFolders) {
                        Text(text = stringResource(id = R.string.label_loading))
                    } else if (state.availableFolders.isEmpty()) {
                        Text(text = stringResource(id = R.string.label_no_folders))
                    } else {
                        state.availableFolders.forEach { folder ->
                            Text(
                                text = folder.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.selectDriveFolder(folder)
                                        showFolderPicker = false
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        )
    }

    if (showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text(text = stringResource(id = R.string.label_drive_create_folder)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.createDriveFolder(newFolderName.trim().ifBlank { state.prefs.driveSyncFolderName })
                    showCreateDialog = false
                }) {
                    Text(text = stringResource(id = R.string.action_create_folder))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateDialog = false }) {
                    Text(text = stringResource(id = R.string.action_cancel))
                }
            },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = {
                        newFolderName = it
                        viewModel.setFolderName(it)
                    },
                    label = { Text(text = stringResource(id = R.string.label_drive_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
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
                            imageVector = androidx.compose.material.icons.Icons.Default.ArrowBack,
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
            Text(
                text = state.driveFolderName ?: stringResource(id = R.string.label_drive_folder_not_set),
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.loadDriveFolders()
                        showFolderPicker = true
                    },
                    enabled = state.isSignedIn
                ) {
                    Text(text = stringResource(id = R.string.action_drive_pick_existing))
                }
                OutlinedButton(
                    onClick = { showCreateDialog = true },
                    enabled = state.isSignedIn
                ) {
                    Text(text = stringResource(id = R.string.action_drive_create_new))
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
