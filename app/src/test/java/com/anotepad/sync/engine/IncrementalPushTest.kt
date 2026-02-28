package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalPushTest {

    @Test
    fun push_uploads_whenItemMissingInDb() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withLocalFile("note.txt", "local", 100L)
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.startsWith("createOrUpdateFile:") })
        assertNotNull(builder.store.item("note.txt")?.driveFileId)
    }

    @Test
    fun push_uploads_whenHashChanged() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "new", 200L)
            .withStoreItem(
                path = "note.txt",
                driveFileId = "d1",
                localHash = "old-hash",
                localLastModified = 100L,
                localSize = 3L
            )
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:d1:note.txt") })
    }

    @Test
    fun push_uploads_whenPendingUpload() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "pending", 200L)
            .withStoreItem(
                path = "note.txt",
                driveFileId = "d1",
                syncState = SyncItemState.PENDING_UPLOAD.name,
                localHash = "same"
            )
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:d1:note.txt") })
    }

    @Test
    fun push_skipsUpload_whenHashUnchanged() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withLocalFile("note.txt", "same", 300L)
        val hash = builder.localFs.computeHash(FakeLocalFsGateway.DEFAULT_ROOT, "note.txt")
        builder.withStoreItem(
            path = "note.txt",
            driveFileId = "d1",
            localHash = hash,
            localLastModified = 300L,
            localSize = 4L,
            syncState = SyncItemState.SYNCED.name
        )
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertFalse(builder.drive.calls.any { it.startsWith("createOrUpdateFile:") })
    }

    @Test
    fun push_createsConflictCopy_whenLocalAndRemoteChangedSinceLastSync() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local-change", 400L)
            .withStoreItem(
                path = "note.txt",
                driveFileId = "d1",
                lastSyncedAt = 100L,
                driveModifiedTime = 300L,
                localHash = "old"
            )
        builder.drive.putFile("d1", "note.txt", "drive-root", modifiedTime = 300L, content = "remote-change")
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        val conflict = builder.store.getAllItems().firstOrNull { it.syncState == SyncItemState.CONFLICT.name }
        assertNotNull(conflict)
        assertTrue(builder.drive.calls.any { it == "downloadFile:d1" })
    }

    @Test
    fun push_usesExistingDriveId_whenKnown() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "update", 250L)
            .withStoreItem(path = "note.txt", driveFileId = "known-id", localHash = "old")
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:known-id:note.txt") })
    }

    @Test
    fun push_resolvesDriveIdByName_whenMissingInDb() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "content", 250L)
            .withStoreItem(path = "note.txt", driveFileId = null, localHash = "old")
            .withRemoteFile("remote-id", "note.txt", "drive-root", content = "old", modifiedTime = 200L)
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.startsWith("listChildren:drive-root:") })
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:remote-id:note.txt") })
    }

    @Test
    fun push_createsNestedDriveFolders_whenNeeded() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withLocalFile("a/b/note.txt", "nested", 200L)
        val push = builder.buildWired().push

        // When
        push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.startsWith("createFolder:a:drive-root") })
        assertTrue(builder.drive.calls.any { it.startsWith("createFolder:b:folder-") })
    }

    @Test
    fun push_deletesRemoteWithTrashPolicy_whenLocalDeleted() = runTest {
        // Given
        val prefs = AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncRemoteDeletePolicy = "TRASH"
        )
        val builder = SyncFixtureBuilder()
            .withPrefs(prefs)
            .withStoreItem(path = "note.txt", driveFileId = "d1")
            .withRemoteFile("d1", "note.txt", "drive-root", content = "x")
        val push = builder.buildWired().push

        // When
        push.execute("token", prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.contains("trashFile:d1"))
    }

    @Test
    fun push_deletesRemoteWithDeletePolicy_whenLocalDeleted() = runTest {
        // Given
        val prefs = AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncRemoteDeletePolicy = "DELETE"
        )
        val builder = SyncFixtureBuilder()
            .withPrefs(prefs)
            .withStoreItem(path = "note.txt", driveFileId = "d1")
            .withRemoteFile("d1", "note.txt", "drive-root", content = "x")
        val push = builder.buildWired().push

        // When
        push.execute("token", prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.contains("deleteFile:d1"))
    }

    @Test
    fun push_keepsRemote_whenIgnoreDeletePolicy_andLocalDeleted() = runTest {
        // Given
        val prefs = AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncRemoteDeletePolicy = "IGNORE"
        )
        val builder = SyncFixtureBuilder()
            .withPrefs(prefs)
            .withStoreItem(path = "note.txt", driveFileId = "d1")
            .withRemoteFile("d1", "note.txt", "drive-root", content = "x")
        val push = builder.buildWired().push

        // When
        push.execute("token", prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertFalse(builder.drive.calls.any { it == "trashFile:d1" || it == "deleteFile:d1" })
        assertNotNull(builder.drive.remoteFile("d1"))
    }

    @Test
    fun push_aborts_whenLocalRootBecomesInaccessible() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withStoreItem(path = "note.txt", driveFileId = "d1")
            .withRemoteFile("d1", "note.txt", "drive-root", content = "x")
        builder.localFs.failListFilesRecursive(LocalStorageUnavailableException())
        val push = builder.buildWired().push

        // When
        try {
            push.execute("token", builder.prefs.prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")
            fail("Expected LocalStorageUnavailableException")
        } catch (_: LocalStorageUnavailableException) {
            // Expected.
        }

        // Then
        assertFalse(builder.drive.calls.contains("trashFile:d1"))
        assertFalse(builder.drive.calls.contains("deleteFile:d1"))
        assertNotNull(builder.store.item("note.txt"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class IncrementalPushDeletePolicyParameterizedTest(
    private val policy: String
) {
    @Test
    fun push_removesDbItem_whenLocalDeleted_anyPolicy() = runTest {
        // Given
        val prefs = AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncRemoteDeletePolicy = policy
        )
        val builder = SyncFixtureBuilder()
            .withPrefs(prefs)
            .withStoreItem(path = "note.txt", driveFileId = "d1")
        val push = builder.buildWired().push

        // When
        push.execute("token", prefs, FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals(null, builder.store.item("note.txt"))
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "policy={0}")
        fun data(): Collection<Array<String>> {
            return listOf(
                arrayOf("TRASH"),
                arrayOf("DELETE"),
                arrayOf("IGNORE")
            )
        }
    }
}
