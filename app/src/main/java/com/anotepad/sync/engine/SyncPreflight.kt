package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.SyncResult
import com.anotepad.sync.SyncState

data class PreflightContext(
    val token: String,
    val prefs: AppPreferences,
    val rootId: String,
    val driveFolderId: String,
    val startPageToken: String?
)

sealed class SyncPreflightResult {
    data class Ready(val context: PreflightContext) : SyncPreflightResult()
    data class Done(val result: SyncResult) : SyncPreflightResult()
}

class SyncPreflight(
    private val prefsGateway: PrefsGateway,
    private val authGateway: AuthGateway,
    private val driveGateway: DriveGateway,
    private val store: SyncStore
) {
    suspend fun run(): SyncPreflightResult {
        val prefs = prefsGateway.getPreferences()
        if (!prefs.driveSyncEnabled) {
            store.setSyncStatus(SyncState.IDLE, "Sync disabled")
            return SyncPreflightResult.Done(SyncResult.Skipped)
        }
        if (prefs.driveSyncPaused) {
            store.setSyncStatus(SyncState.PENDING, "Sync paused")
            return SyncPreflightResult.Done(SyncResult.Skipped)
        }
        val rootId = prefs.rootTreeUri
        if (rootId.isNullOrBlank()) {
            store.setSyncStatus(SyncState.ERROR, "No local folder selected")
            return SyncPreflightResult.Done(SyncResult.Failure(authError = false))
        }

        val token = authGateway.getAccessToken()
        if (token.isNullOrBlank()) {
            store.setSyncStatus(SyncState.ERROR, "Sign in required")
            return SyncPreflightResult.Done(SyncResult.Failure(authError = true))
        }

        store.setSyncStatus(SyncState.RUNNING, "Syncing...")

        val folderId = when (val folder = ensureDriveFolder(token, prefs)) {
            is EnsureDriveFolderResult.Found -> folder.id
            is EnsureDriveFolderResult.Error -> {
                store.setSyncStatus(SyncState.ERROR, folder.message)
                return SyncPreflightResult.Done(SyncResult.Failure(authError = false))
            }
        }

        return SyncPreflightResult.Ready(
            PreflightContext(
                token = token,
                prefs = prefs,
                rootId = rootId,
                driveFolderId = folderId,
                startPageToken = store.getStartPageToken()
            )
        )
    }

    suspend fun ensureDriveFolder(token: String, prefs: AppPreferences): EnsureDriveFolderResult {
        val storedId = store.getDriveFolderId()
        if (!storedId.isNullOrBlank()) {
            val name = store.getDriveFolderName() ?: prefs.driveSyncFolderName
            runCatching { driveGateway.ensureMarkerFile(token, storedId, name) }
            return EnsureDriveFolderResult.Found(storedId)
        }

        val markerFolders = driveGateway.findMarkerFolders(token)
        when {
            markerFolders.size == 1 -> {
                val folder = markerFolders.first()
                store.setDriveFolderId(folder.id)
                store.setDriveFolderName(folder.name)
                return EnsureDriveFolderResult.Found(folder.id)
            }

            markerFolders.size > 1 -> {
                return EnsureDriveFolderResult.Error(
                    "Multiple Drive folders found. Open Sync settings to choose."
                )
            }
        }

        val preferredName = store.getDriveFolderName() ?: prefs.driveSyncFolderName
        val foldersByName = driveGateway.findFoldersByName(token, preferredName)
        return when {
            foldersByName.size == 1 -> {
                val folder = foldersByName.first()
                store.setDriveFolderId(folder.id)
                store.setDriveFolderName(folder.name)
                runCatching { driveGateway.ensureMarkerFile(token, folder.id, folder.name) }
                EnsureDriveFolderResult.Found(folder.id)
            }

            foldersByName.size > 1 -> {
                EnsureDriveFolderResult.Error(
                    "Multiple Drive folders found by name. Open Sync settings to choose."
                )
            }

            else -> EnsureDriveFolderResult.Error("Drive folder not connected")
        }
    }
}

sealed class EnsureDriveFolderResult {
    data class Found(val id: String) : EnsureDriveFolderResult()
    data class Error(val message: String) : EnsureDriveFolderResult()
}
