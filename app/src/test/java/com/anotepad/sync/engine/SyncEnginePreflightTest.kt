package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveFolder
import com.anotepad.sync.SyncResult
import com.anotepad.sync.SyncState
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncEnginePreflightTest {

    @Test
    fun runSync_skips_whenSyncDisabled() = runTest {
        // Given
        val fixture = SyncFixtureBuilder()
            .withPrefs(AppPreferences(driveSyncEnabled = false))
            .buildEngine()

        // When
        val result = fixture.runSync()

        // Then
        assertEquals(SyncResult.Skipped, result)
    }

    @Test
    fun runSync_skips_whenSyncPaused() = runTest {
        // Given
        val engine = SyncFixtureBuilder()
            .withPrefs(
                AppPreferences(
                    rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
                    driveSyncEnabled = true,
                    driveSyncPaused = true
                )
            )
            .buildEngine()

        // When
        val result = engine.runSync()

        // Then
        assertEquals(SyncResult.Skipped, result)
    }

    @Test
    fun runSync_fails_whenNoLocalRoot() = runTest {
        // Given
        val engine = SyncFixtureBuilder()
            .withPrefs(AppPreferences(driveSyncEnabled = true, rootTreeUri = null))
            .buildEngine()

        // When
        val result = engine.runSync()

        // Then
        assertTrue(result is SyncResult.Failure)
        assertFalse((result as SyncResult.Failure).authError)
    }

    @Test
    fun runSync_failsAuth_whenNoToken() = runTest {
        // Given
        val engine = SyncFixtureBuilder()
            .withToken(null)
            .buildEngine()

        // When
        val result = engine.runSync()

        // Then
        assertTrue(result is SyncResult.Failure)
        assertTrue((result as SyncResult.Failure).authError)
    }

    @Test
    fun runSync_setsRunningAndSyncedStatus_onSuccess() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
            .withDriveFolder("drive-root", "Anotepad")
            .withStartPageToken("start")
        val engine = builder.buildEngine()

        // When
        val result = engine.runSync()

        // Then
        assertEquals(SyncResult.Success, result)
        val states = builder.store.statuses.map { it.state }
        assertTrue(states.contains(SyncState.RUNNING))
        assertEquals(SyncState.SYNCED, states.last())
    }

    @Test
    fun runSync_setsErrorStatus_whenDriveFolderNotConnected() = runTest {
        // Given
        val builder = SyncFixtureBuilder()
        val engine = builder.buildEngine()

        // When
        val result = engine.runSync()

        // Then
        assertTrue(result is SyncResult.Failure)
        assertEquals(SyncState.ERROR, builder.store.statuses.last().state)
        assertEquals("Drive folder not connected", builder.store.statuses.last().message)
    }

    @Test
    fun ensureDriveFolder_usesStoredFolderId_whenPresent() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withDriveFolder("folder-1", "A")
        val preflight = builder.buildWired().preflight

        // When
        val result = preflight.ensureDriveFolder("token", builder.prefs.prefs)

        // Then
        assertEquals(EnsureDriveFolderResult.Found("folder-1"), result)
        assertTrue(builder.drive.calls.any { it.startsWith("ensureMarkerFile:folder-1") })
    }

    @Test
    fun ensureDriveFolder_returnsError_whenMultipleMarkerFolders() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withMarkerFolders(
            DriveFolder("f1", "A"),
            DriveFolder("f2", "B")
        )
        val preflight = builder.buildWired().preflight

        // When
        val result = preflight.ensureDriveFolder("token", builder.prefs.prefs)

        // Then
        assertEquals(
            EnsureDriveFolderResult.Error("Multiple Drive folders found. Open Sync settings to choose."),
            result
        )
    }

    @Test
    fun ensureDriveFolder_returnsError_whenMultipleFoldersByName() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withFoldersByName(
            "Anotepad",
            listOf(DriveFolder("f1", "Anotepad"), DriveFolder("f2", "Anotepad"))
        )
        val preflight = builder.buildWired().preflight

        // When
        val result = preflight.ensureDriveFolder("token", builder.prefs.prefs)

        // Then
        assertEquals(
            EnsureDriveFolderResult.Error("Multiple Drive folders found by name. Open Sync settings to choose."),
            result
        )
    }

    @Test
    fun ensureDriveFolder_savesFolderMeta_whenSingleFolderFound() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withMarkerFolders(DriveFolder("f1", "Anotepad"))
        val preflight = builder.buildWired().preflight

        // When
        val result = preflight.ensureDriveFolder("token", builder.prefs.prefs)

        // Then
        assertEquals(EnsureDriveFolderResult.Found("f1"), result)
        assertEquals("f1", builder.store.driveFolderId)
        assertEquals("Anotepad", builder.store.driveFolderName)
    }
}
