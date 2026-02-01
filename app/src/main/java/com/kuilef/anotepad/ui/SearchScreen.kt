package com.kuilef.anotepad.ui

import androidx.compose.material3.ExperimentalMaterial3Api
import android.net.Uri
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kuilef.anotepad.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenResult: (Uri, Uri?) -> Unit
) {
    val state by viewModel.state.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.action_search)) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                label = { Text(text = stringResource(id = R.string.label_search_query)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(id = R.string.label_regex), modifier = Modifier.weight(1f))
                Switch(checked = state.regexEnabled, onCheckedChange = viewModel::toggleRegex)
            }
            Button(
                onClick = viewModel::runSearch,
                enabled = state.baseDir != null && state.query.isNotBlank()
            ) {
                Icon(Icons.Default.Search, contentDescription = null)
                Text(text = stringResource(id = R.string.action_search), modifier = Modifier.padding(start = 8.dp))
            }

            if (state.searching) {
                Text(text = stringResource(id = R.string.label_searching))
            } else if (state.results.isEmpty()) {
                Text(text = stringResource(id = R.string.label_no_results))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.results) { result ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenResult(result.fileUri, result.dirUri ?: state.baseDir) }
                                .padding(vertical = 12.dp)
                        ) {
                            Text(text = result.fileName)
                            Text(text = result.snippet)
                        }
                    }
                }
            }
        }
    }
}
