package com.anotepad.ui

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.anotepad.data.AppPreferences
import com.anotepad.data.PreferencesRepository
import com.anotepad.sync.DriveAuthManager
import com.anotepad.sync.DriveApiException
import com.anotepad.sync.DriveClient
import com.anotepad.sync.DriveFolder
import com.anotepad.sync.DriveNetworkException
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncScheduler
import com.anotepad.sync.SyncState
import com.anotepad.sync.userMessage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
    val showFolderConflictDialog: Boolean = false,
    val foundFolders: List<DriveFolder> = emptyList(),
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
                    showFolderConflictDialog = folders.showConflictDialog,
                    foundFolders = folders.foundFolders,
                    isLoadingFolders = folders.isLoading,
                    errorMessage = folders.errorMessage
                )
            }.collectLatest { combined ->
                _state.value = combined
            }
        }
        viewModelScope.launch {
            refreshFolderMeta()
            maybeAutoConnect()
        }
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
            checkAndConnectDriveFolder()
        } catch (error: ApiException) {
            val status = error.statusCode
            val statusText = GoogleSignInStatusCodes.getStatusCodeString(status)
            updateFolderState(error = "Sign-in failed: $statusText ($status)")
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

    fun syncNow() {
        viewModelScope.launch { syncScheduler.syncNow() }
    }

    fun selectDriveFolder(folder: DriveFolder) {
        viewModelScope.launch {
            selectDriveFolderInternal(folder)
        }
    }

    fun checkAndConnectDriveFolder() {
        viewModelScope.launch { checkAndConnectDriveFolderInternal() }
    }

    fun cancelFolderSelection() {
        updateFolderState(
            showFolderConflictDialog = false,
            foundFolders = emptyList(),
            isLoading = false
        )
    }

    fun disconnectFolder() {
        viewModelScope.launch {
            syncRepository.disconnectDriveFolder()
            refreshFolderMeta()
            updateFolderState(
                showFolderConflictDialog = false,
                foundFolders = emptyList(),
                isLoading = false,
                error = null
            )
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

    private suspend fun maybeAutoConnect() {
        if (!authState.value.isSignedIn) return
        val existingId = syncRepository.getDriveFolderId()
        if (!existingId.isNullOrBlank()) return
        checkAndConnectDriveFolderInternal()
    }

    private suspend fun checkAndConnectDriveFolderInternal() {
        val token = authManager.getAccessToken()
        if (token.isNullOrBlank()) {
            updateFolderState(error = "Sign in required")
            return
        }
        val existingId = syncRepository.getDriveFolderId()
        if (!existingId.isNullOrBlank()) {
            refreshFolderMeta()
            updateFolderState(isLoading = false)
            return
        }
        val folderName = state.value.prefs.driveSyncFolderName
        updateFolderState(
            isLoading = true,
            error = null,
            showFolderConflictDialog = false,
            foundFolders = emptyList()
        )
        try {
            val folders = driveClient.findFoldersByName(token, folderName)
            when {
                folders.isEmpty() -> {
                    updateFolderState(isLoading = false)
                    createDriveFolderInternal(folderName, token)
                }
                folders.size == 1 -> {
                    updateFolderState(isLoading = false)
                    selectDriveFolderInternal(folders.first())
                }
                else -> {
                    updateFolderState(
                        showFolderConflictDialog = true,
                        foundFolders = folders,
                        isLoading = false
                    )
                }
            }
        } catch (error: DriveApiException) {
            val detail = error.userMessage()
            val message = detail?.let { "Drive error ${error.code}: $it" } ?: "Drive error ${error.code}"
            updateFolderState(isLoading = false, error = message)
        } catch (error: DriveNetworkException) {
            val message = error.description?.let { "Network error: $it" } ?: "Network error"
            updateFolderState(isLoading = false, error = message)
        } catch (_: Exception) {
            updateFolderState(isLoading = false, error = "Failed to find folders")
        }
    }

    private suspend fun createDriveFolderInternal(name: String, tokenOverride: String? = null) {
        val token = tokenOverride ?: authManager.getAccessToken()
        if (token.isNullOrBlank()) {
            updateFolderState(error = "Sign in required", isLoading = false)
            return
        }
        updateFolderState(isLoading = true, error = null)
        try {
            val folder = driveClient.createFolder(token, name, null)
            syncRepository.resetForNewFolder(folder.id, folder.name)
            refreshFolderMeta()
            syncScheduler.syncNow()
            updateFolderState(
                isLoading = false,
                showFolderConflictDialog = false,
                foundFolders = emptyList(),
                error = null
            )
        } catch (error: DriveApiException) {
            val detail = error.userMessage()
            val message = detail?.let { "Drive error ${error.code}: $it" } ?: "Drive error ${error.code}"
            updateFolderState(isLoading = false, error = message)
        } catch (error: DriveNetworkException) {
            val message = error.description?.let { "Network error: $it" } ?: "Network error"
            updateFolderState(isLoading = false, error = message)
        } catch (_: Exception) {
            updateFolderState(isLoading = false, error = "Failed to create folder")
        }
    }

    private suspend fun selectDriveFolderInternal(folder: DriveFolder) {
        updateFolderState(
            showFolderConflictDialog = false,
            foundFolders = emptyList(),
            isLoading = false,
            error = null
        )
        syncRepository.resetForNewFolder(folder.id, folder.name)
        refreshFolderMeta()
        syncScheduler.syncNow()
    }

    private fun updateFolderState(
        folderId: String? = folderState.value.folderId,
        folderName: String? = folderState.value.folderName,
        foundFolders: List<DriveFolder> = folderState.value.foundFolders,
        showFolderConflictDialog: Boolean = folderState.value.showConflictDialog,
        isLoading: Boolean = folderState.value.isLoading,
        error: String? = null
    ) {
        folderState.value = FolderState(
            folderId = folderId,
            folderName = folderName,
            foundFolders = foundFolders,
            showConflictDialog = showFolderConflictDialog,
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
        val foundFolders: List<DriveFolder> = emptyList(),
        val showConflictDialog: Boolean = false,
        val isLoading: Boolean = false,
        val errorMessage: String? = null
    )
}
