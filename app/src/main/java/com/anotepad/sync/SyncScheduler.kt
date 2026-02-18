package com.anotepad.sync

import android.content.Context
import com.anotepad.data.PreferencesRepository
import com.anotepad.sync.engine.PrefsGateway
import com.anotepad.sync.engine.PrefsGatewayAdapter
import com.anotepad.sync.engine.SyncStore
import com.anotepad.sync.engine.SyncStoreAdapter

class SyncScheduler(
    private val prefsGateway: PrefsGateway,
    private val syncStore: SyncStore,
    private val workGateway: SyncWorkGateway
) {
    constructor(
        context: Context,
        preferencesRepository: PreferencesRepository,
        syncRepository: SyncRepository
    ) : this(
        prefsGateway = PrefsGatewayAdapter(preferencesRepository),
        syncStore = SyncStoreAdapter(syncRepository),
        workGateway = WorkManagerSyncWorkGateway(context)
    )

    suspend fun scheduleDebounced() {
        val prefs = prefsGateway.getPreferences()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) return
        workGateway.enqueueDebounced()
        syncStore.setSyncStatus(SyncState.PENDING, "Waiting for sync")
    }

    suspend fun schedulePeriodic() {
        val prefs = prefsGateway.getPreferences()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) {
            workGateway.cancelAllSyncWork()
            return
        }
        workGateway.enqueuePeriodic()
    }

    suspend fun scheduleStartup() {
        val prefs = prefsGateway.getPreferences()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused || !prefs.driveSyncAutoOnStart) return
        if (prefs.rootTreeUri.isNullOrBlank()) return
        val folderId = syncStore.getDriveFolderId()
        if (folderId.isNullOrBlank()) return
        syncStore.setSyncStatus(SyncState.PENDING, "Sync scheduled")
        workGateway.enqueueStartup()
    }

    suspend fun syncNow() {
        val prefs = prefsGateway.getPreferences()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) return
        syncStore.setSyncStatus(SyncState.PENDING, "Sync scheduled")
        workGateway.enqueueManual()
    }
}
