package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveChange
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteScenariosTest {

    @Test
    fun remoteDelete_file_ignored_whenIgnoreRemoteDeletesEnabled() = runTest {
        // Given
        val prefs = AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncIgnoreRemoteDeletes = true
        )
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 100L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 100L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNotNull(builder.store.item("note.txt"))
    }

    @Test
    fun remoteDelete_file_movesLocalToTrash_whenLocalUnchanged() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 100L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 200L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item("note.txt"))
        assertTrue(builder.localFs.calls.any { it.contains("moveFile:${FakeLocalFsGateway.DEFAULT_ROOT}:note.txt->.trash/") })
    }

    @Test
    fun remoteDelete_file_marksPendingUpload_whenLocalChangedAfterLastSync() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 300L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 100L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val item = builder.store.item("note.txt")
        assertEquals(SyncItemState.PENDING_UPLOAD.name, item?.syncState)
        assertNull(item?.driveFileId)
    }

    @Test
    fun remoteDelete_folder_movesAllUnchangedChildrenToTrash() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withFolderMapping("folder", "f1")
            .withLocalFile("folder/a.txt", "a", 100L)
            .withLocalFile("folder/b.txt", "b", 100L)
            .withStoreItem(path = "folder/a.txt", driveFileId = "a1", lastSyncedAt = 200L)
            .withStoreItem(path = "folder/b.txt", driveFileId = "b1", lastSyncedAt = 200L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("f1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item("folder/a.txt"))
        assertNull(builder.store.item("folder/b.txt"))
        val moveCalls = builder.localFs.calls.count { it.startsWith("moveFile:${FakeLocalFsGateway.DEFAULT_ROOT}:folder/") }
        assertEquals(2, moveCalls)
    }

    @Test
    fun remoteDelete_folder_marksChangedChildrenPendingUpload() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withFolderMapping("folder", "f1")
            .withLocalFile("folder/a.txt", "a", 300L)
            .withLocalFile("folder/b.txt", "b", 100L)
            .withStoreItem(path = "folder/a.txt", driveFileId = "a1", lastSyncedAt = 100L)
            .withStoreItem(path = "folder/b.txt", driveFileId = "b1", lastSyncedAt = 200L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("f1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val changed = builder.store.item("folder/a.txt")
        assertEquals(SyncItemState.PENDING_UPLOAD.name, changed?.syncState)
        assertNull(changed?.driveFileId)
        assertNotNull(builder.localFs.file("folder/a.txt"))
        assertNull(builder.store.item("folder/b.txt"))
        assertNull(builder.localFs.file("folder/b.txt"))
    }

    @Test
    fun remoteDelete_file_marksPendingUploadWhenTrashMoveFails() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 100L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 200L)
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", true, null)), null, "new"))
        builder.localFs.failMove("note.txt").failCopy("note.txt")
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val item = builder.store.item("note.txt")
        assertEquals(SyncItemState.PENDING_UPLOAD.name, item?.syncState)
        assertNull(item?.driveFileId)
        assertNotNull(builder.localFs.file("note.txt"))
    }

    @Test
    fun remoteDelete_folder_removesFolderMappings() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withFolderMapping("folder", "f1")
            .withFolderMapping("folder/sub", "f2")
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("f1", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.folder("folder"))
        assertNull(builder.store.folder("folder/sub"))
    }

    @Test
    fun remoteDelete_unknownDriveId_isNoOp() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withStoreItem(path = "note.txt", driveFileId = "known")
            .withChangesPage("p1", DriveChangesPage(listOf(DriveChange("unknown", true, null)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNotNull(builder.store.item("note.txt"))
    }

    @Test
    fun localDelete_file_withNoDriveId_removesOnlyLocalDbRecord() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withStoreItem(path = "note.txt", driveFileId = null)
        val push = builder.buildWired().push

        // When
        push.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertNull(builder.store.item("note.txt"))
        assertFalse(builder.drive.calls.any { it.startsWith("trashFile:") || it.startsWith("deleteFile:") })
    }

    @Test
    fun localDelete_file_inIgnoredPath_doesNotTriggerRemoteAction() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withStoreItem(path = ".trash/note.txt", driveFileId = "d1")
        val push = builder.buildWired().push

        // When
        push.execute("token", defaultPrefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertNull(builder.store.item(".trash/note.txt"))
        assertFalse(builder.drive.calls.any { it == "trashFile:d1" || it == "deleteFile:d1" })
    }

    private fun defaultPrefs(): AppPreferences {
        return AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncRemoteDeletePolicy = "TRASH"
        )
    }
}
