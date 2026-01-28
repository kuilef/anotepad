package com.anotepad.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anotepad.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val prefs by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.label_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(id = R.string.action_back))
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
        ) {
            SettingRow(
                title = stringResource(id = R.string.label_autolink_web),
                checked = prefs.autoLinkWeb,
                onToggle = viewModel::setAutoLinkWeb
            )
            SettingRow(
                title = stringResource(id = R.string.label_autolink_email),
                checked = prefs.autoLinkEmail,
                onToggle = viewModel::setAutoLinkEmail
            )
            SettingRow(
                title = stringResource(id = R.string.label_autolink_tel),
                checked = prefs.autoLinkTel,
                onToggle = viewModel::setAutoLinkTel
            )
            SettingRow(
                title = stringResource(id = R.string.label_sync_title),
                checked = prefs.syncTitle,
                onToggle = viewModel::setSyncTitle
            )
            Text(
                text = stringResource(id = R.string.label_autosave),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 24.dp)
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}
