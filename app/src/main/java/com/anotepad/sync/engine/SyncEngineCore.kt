package com.anotepad.sync.engine

import com.anotepad.sync.SyncResult
import com.anotepad.sync.SyncState

class SyncEngineCore(
    private val preflight: SyncPreflight,
    private val initialSyncUseCase: InitialSyncUseCase,
    private val incrementalPushUseCase: IncrementalPushUseCase,
    private val incrementalPullUseCase: IncrementalPullUseCase,
    private val folderPathResolver: FolderPathResolver,
    private val store: SyncStore
) {
    suspend fun runSync(): SyncResult {
        val preflightResult = preflight.run()
        val context = when (preflightResult) {
            is SyncPreflightResult.Done -> return preflightResult.result
            is SyncPreflightResult.Ready -> preflightResult.context
        }

        folderPathResolver.resetRunCaches()
        if (context.startPageToken.isNullOrBlank()) {
            initialSyncUseCase.execute(
                token = context.token,
                rootId = context.rootId,
                driveFolderId = context.driveFolderId
            )
        } else {
            incrementalPushUseCase.execute(
                token = context.token,
                prefs = context.prefs,
                rootId = context.rootId,
                driveFolderId = context.driveFolderId
            )
            incrementalPullUseCase.execute(
                token = context.token,
                prefs = context.prefs,
                rootId = context.rootId,
                driveFolderId = context.driveFolderId,
                startPageToken = context.startPageToken
            )
        }

        store.setSyncStatus(
            SyncState.SYNCED,
            "Synced",
            lastSyncedAt = System.currentTimeMillis()
        )
        return SyncResult.Success
    }
}
