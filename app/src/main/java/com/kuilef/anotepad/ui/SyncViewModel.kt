package com.kuilef.anotepad.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kuilef.anotepad.data.AppPreferences
import com.kuilef.anotepad.data.PreferencesRepository
import com.kuilef.anotepad.sync.DriveAuthManager
import com.kuilef.anotepad.sync.DriveApiException
import com.kuilef.anotepad.sync.DriveClient
import com.kuilef.anotepad.sync.DriveFolder
import com.kuilef.anotepad.sync.DriveNetworkException
import com.kuilef.anotepad.sync.SyncRepository
import com.kuilef.anotepad.sync.SyncScheduler
import com.kuilef.anotepad.sync.SyncState
import com.kuilef.anotepad.sync.userMessage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class SyncUiState(
    val prefs: AppPreferences = AppPreferences(),
    val status: SyncState = SyncState.IDLE,
    val statusMessage: String? = null,
    val lastSyncedAt: Long? = null,
    val isSignedIn: Boolean = false,
    val accountEmail: String? = null,
    val driveFolderName: String? = null,
    val driveFolderId: String? = null,
    val availableFolders: List<DriveFolder> = emptyList(),
    val isLoadingFolders: Boolean = false,
    val errorMessage: String? = null
)

class SyncViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: SyncRepository,
    private val syncScheduler: SyncScheduler,
    private val authManager: DriveAuthManager
) : ViewModel() {

    private val driveClient = DriveClient()
    private val authState = MutableStateFlow(AuthState())
    private val folderState = MutableStateFlow(FolderState())

    private val _state = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = _state.asStateFlow()

    init {
        refreshAuthState()
        viewModelScope.launch {
            combine(
                preferencesRepository.preferencesFlow,
                syncRepository.syncStatusFlow(),
                authState,
                folderState
            ) { prefs, status, auth, folders ->
                SyncUiState(
                    prefs = prefs,
                    status = status.state,
                    statusMessage = status.message,
                    lastSyncedAt = status.lastSyncedAt,
                    isSignedIn = auth.isSignedIn,
                    accountEmail = auth.email,
                    driveFolderName = folders.folderName,
                    driveFolderId = folders.folderId,
                    availableFolders = folders.available,
                    isLoadingFolders = folders.isLoading,
                    errorMessage = folders.errorMessage
                )
            }.collectLatest { combined ->
                _state.value = combined
            }
        }
        viewModelScope.launch { refreshFolderMeta() }
    }

    fun signInIntent(): Intent = authManager.signInIntent()

    fun handleSignInResult(data: Intent?) {
        if (data == null) {
            updateFolderState(error = "Sign-in canceled")
            refreshAuthState()
            return
        }
        try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            updateFolderState(error = null)
            refreshAuthState()
        } catch (_: ApiException) {
            updateFolderState(error = "Sign-in failed")
            refreshAuthState()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { authManager.signOut() }
            refreshAuthState()
        }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncEnabled(enabled)
            if (enabled) {
                syncScheduler.schedulePeriodic()
                syncScheduler.scheduleDebounced()
            } else {
                syncScheduler.schedulePeriodic()
            }
        }
    }

    fun setWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncWifiOnly(enabled)
            syncScheduler.schedulePeriodic()
        }
    }

    fun setChargingOnly(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncChargingOnly(enabled)
            syncScheduler.schedulePeriodic()
        }
    }

    fun setPaused(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncPaused(enabled)
            syncScheduler.schedulePeriodic()
        }
    }

    fun setIgnoreRemoteDeletes(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncIgnoreRemoteDeletes(enabled)
        }
    }

    fun setFolderName(name: String) {
        viewModelScope.launch {
            preferencesRepository.setDriveSyncFolderName(name)
        }
    }

    fun setFolderError(message: String?) {
        updateFolderState(error = message)
    }

    fun syncNow() {
        viewModelScope.launch { syncScheduler.syncNow() }
    }

    fun loadDriveFolders() {
        viewModelScope.launch {
            val token = authManager.getAccessToken()
            if (token.isNullOrBlank()) {
                updateFolderState(error = "Sign in required")
                return@launch
            }
            updateFolderState(isLoading = true, error = null)
            try {
                val folders = mutableListOf<DriveFolder>()
                var page: String? = null
                do {
                    val result = driveClient.listFolders(token, page)
                    folders.addAll(result.items)
                    page = result.nextPageToken
                } while (!page.isNullOrBlank())
                updateFolderState(available = folders, isLoading = false)
            } catch (error: DriveApiException) {
                val detail = error.userMessage()
                val message = detail?.let { "Drive error ${error.code}: $it" } ?: "Drive error ${error.code}"
                updateFolderState(isLoading = false, error = message)
            } catch (error: DriveNetworkException) {
                val message = error.description?.let { "Network error: $it" } ?: "Network error"
                updateFolderState(isLoading = false, error = message)
            } catch (_: Exception) {
                updateFolderState(isLoading = false, error = "Failed to load folders")
            }
        }
    }

    fun selectDriveFolder(folder: DriveFolder) {
        viewModelScope.launch {
            syncRepository.resetForNewFolder(folder.id, folder.name)
            refreshFolderMeta()
            syncScheduler.syncNow()
        }
    }

    fun createDriveFolder(name: String) {
        viewModelScope.launch {
            val token = authManager.getAccessToken()
            if (token.isNullOrBlank()) {
                updateFolderState(error = "Sign in required")
                return@launch
            }
            try {
                val folder = driveClient.createFolder(token, name, null)
                syncRepository.resetForNewFolder(folder.id, folder.name)
                refreshFolderMeta()
                syncScheduler.syncNow()
            } catch (error: DriveApiException) {
                val detail = error.userMessage()
                val message = detail?.let { "Drive error ${error.code}: $it" } ?: "Drive error ${error.code}"
                updateFolderState(error = message)
            } catch (error: DriveNetworkException) {
                val message = error.description?.let { "Network error: $it" } ?: "Network error"
                updateFolderState(error = message)
            } catch (_: Exception) {
                updateFolderState(error = "Failed to create folder")
            }
        }
    }

    private fun refreshAuthState() {
        val account = authManager.getSignedInAccount()
        authState.value = AuthState(
            isSignedIn = account != null,
            email = account?.email
        )
    }

    private suspend fun refreshFolderMeta() {
        val id = syncRepository.getDriveFolderId()
        val name = syncRepository.getDriveFolderName()
        updateFolderState(folderId = id, folderName = name)
    }

    private fun updateFolderState(
        folderId: String? = folderState.value.folderId,
        folderName: String? = folderState.value.folderName,
        available: List<DriveFolder> = folderState.value.available,
        isLoading: Boolean = folderState.value.isLoading,
        error: String? = null
    ) {
        folderState.value = FolderState(
            folderId = folderId,
            folderName = folderName,
            available = available,
            isLoading = isLoading,
            errorMessage = error
        )
    }

    private data class AuthState(
        val isSignedIn: Boolean = false,
        val email: String? = null
    )

    private data class FolderState(
        val folderId: String? = null,
        val folderName: String? = null,
        val available: List<DriveFolder> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )
}
