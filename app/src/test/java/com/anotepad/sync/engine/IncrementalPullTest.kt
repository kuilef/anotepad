package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveChange
import com.anotepad.sync.DriveFile
import com.anotepad.sync.SyncItemState
import com.anotepad.sync.db.SyncItemEntity
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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@OptIn(ExperimentalCoroutinesApi::class)
class IncrementalPullTest {

    @Test
    fun pull_appliesRemoteFileCreate_toLocal() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        val file = driveFile("r1", "note.txt", parents = listOf("drive-root"), modified = 200L)
        builder.drive.putFile("r1", "note.txt", "drive-root", content = "remote", modifiedTime = 200L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("remote", builder.localFs.file("note.txt")?.content?.toString(Charsets.UTF_8))
        assertEquals("r1", builder.store.item("note.txt")?.driveFileId)
    }

    @Test
    fun pull_appliesRemoteFileUpdate_toLocal() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "old", 100L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 100L, driveModifiedTime = 100L)
        val file = driveFile("r1", "note.txt", parents = listOf("drive-root"), modified = 250L)
        builder.drive.putFile("r1", "note.txt", "drive-root", content = "new", modifiedTime = 250L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("new", builder.localFs.file("note.txt")?.content?.toString(Charsets.UTF_8))
    }

    @Test
    fun pull_skipsRemoteFile_whenUnsupportedExtension() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        val file = driveFile("r1", "readme.md", parents = listOf("drive-root"), modified = 200L)
        builder.drive.putFile("r1", "readme.md", "drive-root", content = "md", modifiedTime = 200L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item("readme.md"))
        assertFalse(builder.localFs.allFiles().containsKey("readme.md"))
    }

    @Test
    fun pull_skipsRemotePath_inTrashArea() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withFolderMapping(".trash", "trash-folder")
        val file = driveFile("r1", "old.txt", parents = listOf("trash-folder"), modified = 200L)
        builder.drive.putFile("r1", "old.txt", "trash-folder", content = "trash", modifiedTime = 200L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item(".trash/old.txt"))
        assertFalse(builder.drive.calls.any { it == "downloadFile:r1" })
    }

    @Test
    fun pull_renamesLocal_whenRemoteRenamed() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("old.txt", "local", 120L)
            .withStoreItem(path = "old.txt", driveFileId = "r1", lastSyncedAt = 100L, driveModifiedTime = 100L)
        val file = driveFile("r1", "new.txt", parents = listOf("drive-root"), modified = 200L)
        builder.drive.putFile("r1", "new.txt", "drive-root", content = "remote", modifiedTime = 200L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item("old.txt"))
        assertNotNull(builder.store.item("new.txt"))
        assertNotNull(builder.localFs.file("new.txt"))
    }

    @Test
    fun pull_movesLocal_whenRemoteMovedToAnotherFolder() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withFolderMapping("dest", "dest-folder")
            .withLocalFile("note.txt", "local", 120L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 100L, driveModifiedTime = 100L)
        val file = driveFile("r1", "note.txt", parents = listOf("dest-folder"), modified = 220L)
        builder.drive.putFile("r1", "note.txt", "dest-folder", content = "remote", modifiedTime = 220L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNull(builder.store.item("note.txt"))
        assertNotNull(builder.store.item("dest/note.txt"))
        assertNotNull(builder.localFs.file("dest/note.txt"))
    }

    @Test
    fun pull_createsFolderLocally_whenRemoteFolderCreated() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        val folder = DriveFile(
            id = "f1",
            name = "projects",
            mimeType = "application/vnd.google-apps.folder",
            modifiedTime = 200L,
            trashed = false,
            parents = listOf("drive-root"),
            appProperties = emptyMap()
        )
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("f1", false, folder)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("f1", builder.store.folder("projects")?.driveFolderId)
        assertTrue(builder.localFs.calls.any { it.startsWith("ensureDirectory:${FakeLocalFsGateway.DEFAULT_ROOT}:projects") })
    }

    @Test
    fun pull_movesFolderTree_whenRemoteFolderMoved() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withFolderMapping("old", "f1")
            .withStoreItem(path = "old/note.txt", driveFileId = "r1")
            .withLocalFile("old/note.txt", "local", 100L)
        val folder = DriveFile(
            id = "f1",
            name = "new",
            mimeType = "application/vnd.google-apps.folder",
            modifiedTime = 210L,
            trashed = false,
            parents = listOf("drive-root"),
            appProperties = emptyMap()
        )
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("f1", false, folder)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertNotNull(builder.store.item("new/note.txt"))
        assertEquals("f1", builder.store.folder("new")?.driveFolderId)
    }

    @Test
    fun pull_resolvesParentPath_viaFolderMapping() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withFolderMapping("sub", "f-sub")
        val file = driveFile("r1", "note.txt", parents = listOf("f-sub"), modified = 230L)
        builder.drive.putFile("r1", "note.txt", "f-sub", content = "remote", modifiedTime = 230L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("r1", builder.store.item("sub/note.txt")?.driveFileId)
    }

    @Test
    fun pull_fetchesParentChain_whenMappingMissing() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        builder.drive.putFolder("f-parent", "parent", "drive-root", modifiedTime = 100L)
        val file = driveFile("r1", "note.txt", parents = listOf("f-parent"), modified = 300L)
        builder.drive.putFile("r1", "note.txt", "f-parent", content = "remote", modifiedTime = 300L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("r1", builder.store.item("parent/note.txt")?.driveFileId)
        assertEquals("f-parent", builder.store.folder("parent")?.driveFolderId)
    }

    @Test
    fun pull_ensuresUniqueLocalPath_onNameCollision() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 200L)
            .withStoreItem(path = "note.txt", driveFileId = "another")
        val file = driveFile("r1", "note.txt", parents = listOf("drive-root"), modified = 300L)
        builder.drive.putFile("r1", "note.txt", "drive-root", content = "remote", modifiedTime = 300L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val collided = builder.store.getAllItems().firstOrNull { it.driveFileId == "r1" }
        assertEquals("note (1).txt", collided?.localRelativePath)
    }

    @Test
    fun pull_createsConflictCopy_whenBothSidesChanged() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 300L)
            .withStoreItem(path = "note.txt", driveFileId = "r1", lastSyncedAt = 100L, driveModifiedTime = 100L)
        val file = driveFile("r1", "note.txt", parents = listOf("drive-root"), modified = 400L)
        builder.drive.putFile("r1", "note.txt", "drive-root", content = "remote", modifiedTime = 400L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val conflict = builder.store.getAllItems().firstOrNull { it.syncState == SyncItemState.CONFLICT.name }
        assertNotNull(conflict)
    }

    @Test
    fun pull_suppressesConflict_afterLocalMoveTriggeredByRemoteRename() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("old.txt", "local", 350L)
            .withStoreItem(path = "old.txt", driveFileId = "r1", lastSyncedAt = 100L, driveModifiedTime = 100L)
        val file = driveFile("r1", "new.txt", parents = listOf("drive-root"), modified = 450L)
        builder.drive.putFile("r1", "new.txt", "drive-root", content = "remote", modifiedTime = 450L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertTrue(builder.store.getAllItems().none { it.syncState == SyncItemState.CONFLICT.name })
        assertNotNull(builder.store.item("new.txt"))
    }

    private fun prefs(): AppPreferences {
        return AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncPaused = false
        )
    }

    private fun driveFile(
        id: String,
        name: String,
        parents: List<String>,
        modified: Long
    ): DriveFile {
        return DriveFile(
            id = id,
            name = name,
            mimeType = "text/plain",
            modifiedTime = modified,
            trashed = false,
            parents = parents,
            appProperties = emptyMap()
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class IncrementalPullConflictParameterizedTest(
    private val renamed: Boolean,
    private val expectConflict: Boolean
) {
    @Test
    fun pull_conflictScenario_parameterized() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile(if (renamed) "old.txt" else "note.txt", "local", 350L)
            .withStoreItem(
                path = if (renamed) "old.txt" else "note.txt",
                driveFileId = "r1",
                lastSyncedAt = 100L,
                driveModifiedTime = 100L
            )
        val remoteName = if (renamed) "new.txt" else "note.txt"
        val file = DriveFile("r1", remoteName, "text/plain", 450L, false, listOf("drive-root"), emptyMap())
        builder.drive.putFile("r1", remoteName, "drive-root", content = "remote", modifiedTime = 450L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file)), null, "new"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", AppPreferences(rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT, driveSyncEnabled = true), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val hasConflict = builder.store.getAllItems().any { it.syncState == SyncItemState.CONFLICT.name }
        assertEquals(expectConflict, hasConflict)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "renamed={0}, conflict={1}")
        fun data(): Collection<Array<Any>> {
            return listOf(
                arrayOf(false, true),
                arrayOf(true, false)
            )
        }
    }
}
