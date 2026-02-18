package com.anotepad.sync

import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.engine.AuthGateway
import com.anotepad.sync.engine.AuthGatewayAdapter
import com.anotepad.sync.engine.ConflictResolver
import com.anotepad.sync.engine.DeleteResolver
import com.anotepad.sync.engine.DriveGateway
import com.anotepad.sync.engine.DriveGatewayAdapter
import com.anotepad.sync.engine.FolderPathResolver
import com.anotepad.sync.engine.IncrementalPullUseCase
import com.anotepad.sync.engine.IncrementalPushUseCase
import com.anotepad.sync.engine.InitialSyncUseCase
import com.anotepad.sync.engine.LocalFsGateway
import com.anotepad.sync.engine.LocalFsGatewayAdapter
import com.anotepad.sync.engine.PrefsGateway
import com.anotepad.sync.engine.PrefsGatewayAdapter
import com.anotepad.sync.engine.RemoteTreeWalker
import com.anotepad.sync.engine.SyncEngineCore
import com.anotepad.sync.engine.SyncExecutor
import com.anotepad.sync.engine.SyncPreflight
import com.anotepad.sync.engine.SyncStore
import com.anotepad.sync.engine.SyncStoreAdapter

class SyncEngine(
    private val prefsGateway: PrefsGateway,
    private val localFsGateway: LocalFsGateway,
    private val syncStore: SyncStore,
    private val authGateway: AuthGateway,
    private val driveGateway: DriveGateway
) {
    constructor(
        preferencesRepository: PreferencesRepository,
        fileRepository: FileRepository,
        syncRepository: SyncRepository,
        authManager: DriveAuthManager,
        driveClient: DriveClient
    ) : this(
        prefsGateway = PrefsGatewayAdapter(preferencesRepository),
        localFsGateway = LocalFsGatewayAdapter(fileRepository),
        syncStore = SyncStoreAdapter(syncRepository),
        authGateway = AuthGatewayAdapter(authManager),
        driveGateway = DriveGatewayAdapter(driveClient)
    )

    private val folderPathResolver = FolderPathResolver(
        drive = driveGateway,
        store = syncStore,
        localFs = localFsGateway
    )

    private val executor = SyncExecutor(
        drive = driveGateway,
        localFs = localFsGateway,
        store = syncStore
    )

    private val remoteTreeWalker = RemoteTreeWalker(
        drive = driveGateway,
        folderPathResolver = folderPathResolver
    )

    private val conflictResolver = ConflictResolver(
        drive = driveGateway,
        localFs = localFsGateway,
        store = syncStore
    )

    private val deleteResolver = DeleteResolver(
        localFs = localFsGateway,
        store = syncStore
    )

    private val preflight = SyncPreflight(
        prefsGateway = prefsGateway,
        authGateway = authGateway,
        driveGateway = driveGateway,
        store = syncStore
    )

    private val initialSyncUseCase = InitialSyncUseCase(
        drive = driveGateway,
        localFs = localFsGateway,
        store = syncStore,
        folderPathResolver = folderPathResolver,
        remoteTreeWalker = remoteTreeWalker,
        executor = executor
    )

    private val pushUseCase = IncrementalPushUseCase(
        localFs = localFsGateway,
        store = syncStore,
        folderPathResolver = folderPathResolver,
        conflictResolver = conflictResolver,
        executor = executor
    )

    private val pullUseCase = IncrementalPullUseCase(
        drive = driveGateway,
        localFs = localFsGateway,
        store = syncStore,
        folderPathResolver = folderPathResolver,
        remoteTreeWalker = remoteTreeWalker,
        conflictResolver = conflictResolver,
        deleteResolver = deleteResolver,
        executor = executor
    )

    private val core = SyncEngineCore(
        preflight = preflight,
        initialSyncUseCase = initialSyncUseCase,
        incrementalPushUseCase = pushUseCase,
        incrementalPullUseCase = pullUseCase,
        folderPathResolver = folderPathResolver,
        store = syncStore
    )

    suspend fun runSync(): SyncResult = core.runSync()
}

sealed class SyncResult {
    data object Success : SyncResult()
    data object Skipped : SyncResult()
    data class Failure(val authError: Boolean) : SyncResult()
}
