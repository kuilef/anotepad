package com.anotepad.sync.engine

import com.anotepad.sync.DriveFile
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialSyncTest {

    @Test
    fun initialSync_uploadsLocalOnlyFiles() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withDriveFolder("drive-root", "Anotepad")
            .withLocalFile("note.txt", "local", 100L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.startsWith("createOrUpdateFile:") })
        assertNotNull(builder.store.item("note.txt")?.driveFileId)
    }

    @Test
    fun initialSync_downloadsRemoteOnlyFiles() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withRemoteFile("r1", "note.txt", "drive-root", content = "remote", modifiedTime = 200L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals("r1", builder.store.item("note.txt")?.driveFileId)
        val text = builder.localFs.file("note.txt")?.content?.toString(Charsets.UTF_8)
        assertEquals("remote", text)
    }

    @Test
    fun initialSync_prefersLocal_whenLocalNewer() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local-new", 300L)
            .withRemoteFile("r1", "note.txt", "drive-root", content = "remote-old", modifiedTime = 100L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:r1:note.txt") })
        assertEquals("r1", builder.store.item("note.txt")?.driveFileId)
    }

    @Test
    fun initialSync_prefersRemote_whenRemoteNewer() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local-old", 100L)
            .withRemoteFile("r1", "note.txt", "drive-root", content = "remote-new", modifiedTime = 300L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        val text = builder.localFs.file("note.txt")?.content?.toString(Charsets.UTF_8)
        assertEquals("remote-new", text)
        assertEquals("r1", builder.store.item("note.txt")?.driveFileId)
    }

    @Test
    fun initialSync_overwritesExistingLocalFile_withoutCreatingDuplicateName() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local-old", 100L)
            .withRemoteFile("r1", "note.txt", "drive-root", content = "remote-new", modifiedTime = 300L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        val createdDuplicateName = builder.localFs.calls.any {
            it.startsWith("createFile:${FakeLocalFsGateway.DEFAULT_ROOT}:note.txt:")
        }
        assertFalse(createdDuplicateName)
        val text = builder.localFs.file("note.txt")?.content?.toString(Charsets.UTF_8)
        assertEquals("remote-new", text)
    }

    @Test
    fun initialSync_ignoresTrashDirectoryPaths() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile(".trash/old.txt", "old", 100L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertFalse(builder.drive.calls.any { it.startsWith("createOrUpdateFile") })
        assertEquals(null, builder.store.item(".trash/old.txt"))
    }

    @Test
    fun initialSync_skipsUnsupportedExtensions() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        builder.drive.putFile("r-md", "readme.md", "drive-root", modifiedTime = 100L, content = "md")
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals(null, builder.store.item("readme.md"))
        assertFalse(builder.localFs.allFiles().containsKey("readme.md"))
    }

    @Test
    fun initialSync_savesStartPageToken() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withStartTokenFromDrive("token-42")
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals("token-42", builder.store.startPageToken)
    }

    @Test
    fun initialSync_savesFolderMappings() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withRemoteFolder("f-sub", "sub", "drive-root")
            .withRemoteFile("r1", "note.txt", "f-sub", content = "remote")
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals("f-sub", builder.store.folder("sub")?.driveFolderId)
    }

    @Test
    fun initialSync_handlesDuplicateRemotePaths_byLatestModified() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        val old = DriveFile("r-old", "dup.txt", "text/plain", 100L, false, listOf("drive-root"), emptyMap())
        val fresh = DriveFile("r-new", "dup.txt", "text/plain", 500L, false, listOf("drive-root"), emptyMap())
        builder.withChildrenPage("drive-root", null, listOf(old, fresh), null)
        builder.drive.putFile("r-old", "dup.txt", "drive-root", modifiedTime = 100L, content = "old")
        builder.drive.putFile("r-new", "dup.txt", "drive-root", modifiedTime = 500L, content = "new")
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertEquals("r-new", builder.store.item("dup.txt")?.driveFileId)
    }

    @Test
    fun initialSync_doesNotCreateDriveDuplicates_forExistingRemote() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withLocalFile("note.txt", "local", 300L)
            .withRemoteFile("r1", "note.txt", "drive-root", content = "remote", modifiedTime = 100L)
        val useCase = builder.buildWired().initialSync

        // When
        useCase.execute("token", FakeLocalFsGateway.DEFAULT_ROOT, "drive-root")

        // Then
        assertTrue(builder.drive.calls.any { it.contains("createOrUpdateFile:r1:note.txt") })
        val duplicates = builder.drive.allRecords().values.count { it.file.name == "note.txt" && !it.file.trashed }
        assertEquals(1, duplicates)
    }
}
