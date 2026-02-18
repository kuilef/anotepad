package com.anotepad.sync.engine.fixtures

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveFile
import com.anotepad.sync.DriveFolder
import com.anotepad.sync.SyncEngine
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity
import com.anotepad.sync.engine.ConflictResolver
import com.anotepad.sync.engine.DeleteResolver
import com.anotepad.sync.engine.DriveChangesPage
import com.anotepad.sync.engine.FolderPathResolver
import com.anotepad.sync.engine.IncrementalPullUseCase
import com.anotepad.sync.engine.IncrementalPushUseCase
import com.anotepad.sync.engine.InitialSyncUseCase
import com.anotepad.sync.engine.RemoteTreeWalker
import com.anotepad.sync.engine.SyncExecutor
import com.anotepad.sync.engine.SyncPreflight

class SyncFixtureBuilder {
    val drive = FakeDriveGateway()
    val localFs = FakeLocalFsGateway()
    val store = FakeSyncStore()
    val auth = FakeAuthGateway()
    val prefs = FakePrefsGateway()

    fun withPrefs(value: AppPreferences) = apply {
        prefs.prefs = value
    }

    fun withToken(token: String?) = apply {
        auth.token = token
    }

    fun withDriveFolder(id: String?, name: String? = null) = apply {
        store.driveFolderId = id
        store.driveFolderName = name
    }

    fun withMarkerFolders(vararg folders: DriveFolder) = apply {
        drive.markerFolders = folders.toList()
    }

    fun withFoldersByName(name: String, folders: List<DriveFolder>) = apply {
        drive.foldersByName[name] = folders
    }

    fun withLocalFile(path: String, content: String, lastModified: Long = 100L) = apply {
        localFs.putFile(prefs.prefs.rootTreeUri ?: FakeLocalFsGateway.DEFAULT_ROOT, path, content, lastModified)
    }

    fun withRemoteFolder(id: String, name: String, parentId: String? = null, modifiedTime: Long = 200L) = apply {
        drive.putFolder(id, name, parentId, modifiedTime)
    }

    fun withRemoteFile(
        id: String,
        name: String,
        parentId: String,
        content: String,
        modifiedTime: Long = 200L,
        appProperties: Map<String, String> = emptyMap(),
        trashed: Boolean = false
    ) = apply {
        drive.putFile(
            id = id,
            name = name,
            parentId = parentId,
            modifiedTime = modifiedTime,
            content = content,
            appProperties = appProperties,
            trashed = trashed
        )
    }

    fun withStoreItem(
        path: String,
        driveFileId: String? = null,
        lastSyncedAt: Long? = null,
        localLastModified: Long = 100L,
        localSize: Long = 1L,
        localHash: String? = "hash",
        driveModifiedTime: Long? = null,
        syncState: String = SyncItemState.SYNCED.name
    ) = apply {
        store.putItem(
            SyncItemEntity(
                localRelativePath = path,
                localLastModified = localLastModified,
                localSize = localSize,
                localHash = localHash,
                driveFileId = driveFileId,
                driveModifiedTime = driveModifiedTime,
                lastSyncedAt = lastSyncedAt,
                syncState = syncState,
                lastError = null
            )
        )
    }

    fun withFolderMapping(path: String, driveFolderId: String) = apply {
        store.putFolder(path, driveFolderId)
    }

    fun withStartPageToken(token: String?) = apply {
        store.startPageToken = token
    }

    fun withStartTokenFromDrive(token: String) = apply {
        drive.startPageToken = token
    }

    fun withChangesPage(pageToken: String, page: DriveChangesPage) = apply {
        drive.putScriptedChanges(pageToken, page)
    }

    fun withChildrenPage(folderId: String, pageToken: String?, items: List<DriveFile>, nextPageToken: String?) = apply {
        drive.putScriptedChildren(folderId, pageToken, items, nextPageToken)
    }

    fun buildEngine(): SyncEngine {
        return SyncEngine(
            prefsGateway = prefs,
            localFsGateway = localFs,
            syncStore = store,
            authGateway = auth,
            driveGateway = drive
        )
    }

    fun buildWired(): WiredSync {
        val folderResolver = FolderPathResolver(
            drive = drive,
            store = store,
            localFs = localFs
        )
        val executor = SyncExecutor(
            drive = drive,
            localFs = localFs,
            store = store
        )
        val walker = RemoteTreeWalker(
            drive = drive,
            folderPathResolver = folderResolver
        )
        val conflictResolver = ConflictResolver(
            drive = drive,
            localFs = localFs,
            store = store
        )
        val deleteResolver = DeleteResolver(
            localFs = localFs,
            store = store
        )
        val preflight = SyncPreflight(
            prefsGateway = prefs,
            authGateway = auth,
            driveGateway = drive,
            store = store
        )
        val initial = InitialSyncUseCase(
            drive = drive,
            localFs = localFs,
            store = store,
            folderPathResolver = folderResolver,
            remoteTreeWalker = walker,
            executor = executor
        )
        val push = IncrementalPushUseCase(
            localFs = localFs,
            store = store,
            folderPathResolver = folderResolver,
            conflictResolver = conflictResolver,
            executor = executor
        )
        val pull = IncrementalPullUseCase(
            drive = drive,
            localFs = localFs,
            store = store,
            folderPathResolver = folderResolver,
            remoteTreeWalker = walker,
            conflictResolver = conflictResolver,
            deleteResolver = deleteResolver,
            executor = executor
        )
        return WiredSync(
            preflight = preflight,
            initialSync = initial,
            push = push,
            pull = pull,
            conflictResolver = conflictResolver,
            deleteResolver = deleteResolver
        )
    }

    data class WiredSync(
        val preflight: SyncPreflight,
        val initialSync: InitialSyncUseCase,
        val push: IncrementalPushUseCase,
        val pull: IncrementalPullUseCase,
        val conflictResolver: ConflictResolver,
        val deleteResolver: DeleteResolver
    )
}
