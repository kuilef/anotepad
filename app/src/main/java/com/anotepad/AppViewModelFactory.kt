package com.anotepad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.anotepad.ui.BrowserViewModel
import com.anotepad.ui.EditorViewModel
import com.anotepad.ui.SearchViewModel
import com.anotepad.ui.SettingsViewModel
import com.anotepad.ui.SyncViewModel
import com.anotepad.ui.TemplatesViewModel

class AppViewModelFactory(private val deps: AppDependencies) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            BrowserViewModel::class.java -> BrowserViewModel(
                deps.preferencesRepository,
                deps.fileRepository,
                deps.syncRepository
            )
            EditorViewModel::class.java -> EditorViewModel(
                deps.preferencesRepository,
                deps.fileRepository,
                deps.syncScheduler
            )
            SearchViewModel::class.java -> SearchViewModel(deps.fileRepository)
            TemplatesViewModel::class.java -> TemplatesViewModel(deps.templateRepository)
            SettingsViewModel::class.java -> SettingsViewModel(deps.preferencesRepository)
            SyncViewModel::class.java -> SyncViewModel(
                deps.preferencesRepository,
                deps.syncRepository,
                deps.syncScheduler,
                deps.driveAuthManager
            )
            else -> throw IllegalArgumentException("Unknown ViewModel: ${modelClass.name}")
        } as T
    }
}
