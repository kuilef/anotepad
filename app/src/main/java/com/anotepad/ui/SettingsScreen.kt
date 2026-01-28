package com.anotepad.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import kotlin.math.roundToInt

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
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader(text = stringResource(id = R.string.label_settings_section_browser))
            FontSizeSetting(
                title = stringResource(id = R.string.label_file_list_font_size),
                value = prefs.browserFontSizeSp,
                valueRange = 12f..24f,
                onCommit = viewModel::setBrowserFontSizeSp
            )

            SectionHeader(text = stringResource(id = R.string.label_settings_section_editor))
            SettingRow(
                title = stringResource(id = R.string.label_autosave),
                checked = prefs.autoSaveEnabled,
                onToggle = viewModel::setAutoSaveEnabled
            )
            Text(
                text = stringResource(id = R.string.label_autosave_hint),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp)
            )
            FontSizeSetting(
                title = stringResource(id = R.string.label_editor_font_size),
                value = prefs.editorFontSizeSp,
                valueRange = 12f..28f,
                onCommit = viewModel::setEditorFontSizeSp
            )
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

            SectionHeader(text = stringResource(id = R.string.label_settings_section_new_note))
            SettingRow(
                title = stringResource(id = R.string.label_auto_insert_template),
                checked = prefs.autoInsertTemplateEnabled,
                onToggle = viewModel::setAutoInsertTemplateEnabled
            )
            if (prefs.autoInsertTemplateEnabled) {
                OutlinedTextField(
                    value = prefs.autoInsertTemplate,
                    onValueChange = viewModel::setAutoInsertTemplate,
                    label = { Text(text = stringResource(id = R.string.label_auto_insert_template_format)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(id = R.string.label_none),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                )
            }

            Text(
                text = stringResource(id = R.string.label_default_extension),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ExtensionOption(
                    label = stringResource(id = R.string.label_extension_txt),
                    value = "txt",
                    selected = prefs.defaultFileExtension == "txt",
                    onSelect = viewModel::setDefaultFileExtension
                )
                ExtensionOption(
                    label = stringResource(id = R.string.label_extension_md),
                    value = "md",
                    selected = prefs.defaultFileExtension == "md",
                    onSelect = viewModel::setDefaultFileExtension
                )
            }
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

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun FontSizeSetting(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value) }
    val steps = ((valueRange.endInclusive - valueRange.start).roundToInt() - 1).coerceAtLeast(0)
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = title, modifier = Modifier.weight(1f))
            Text(text = "${sliderValue.roundToInt()} sp", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onCommit(sliderValue) }
        )
    }
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
