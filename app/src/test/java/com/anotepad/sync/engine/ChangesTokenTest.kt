package com.anotepad.sync.engine

import com.anotepad.data.AppPreferences
import com.anotepad.sync.DriveChange
import com.anotepad.sync.DriveFile
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.SyncFixtureBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChangesTokenTest {

    @Test
    fun pull_usesNewStartPageToken_fromLastChangesPage() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withStartPageToken("old")
        builder.withChangesPage("p1", DriveChangesPage(emptyList(), nextPageToken = "p2", newStartPageToken = null))
        builder.withChangesPage("p2", DriveChangesPage(emptyList(), nextPageToken = null, newStartPageToken = "fresh"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("fresh", builder.store.startPageToken)
    }

    @Test
    fun pull_doesNotOverrideToken_whenNoNewTokenReturned() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withStartPageToken("old")
        builder.withChangesPage("p1", DriveChangesPage(emptyList(), nextPageToken = null, newStartPageToken = null))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("old", builder.store.startPageToken)
    }

    @Test
    fun pull_handlesEmptyChangesPage_withoutTokenCorruption() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withStartPageToken("stable")
        builder.withChangesPage("p1", DriveChangesPage(items = emptyList(), nextPageToken = null, newStartPageToken = null))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        assertEquals("stable", builder.store.startPageToken)
    }

    @Test
    fun pull_handlesPagination_multiplePages_inOrder() = runTest {
        // Given
        val builder = SyncFixtureBuilder().withStartPageToken("old")
        val file1 = DriveFile("r1", "one.txt", "text/plain", 100L, false, listOf("drive-root"), emptyMap())
        val file2 = DriveFile("r2", "two.txt", "text/plain", 200L, false, listOf("drive-root"), emptyMap())
        builder.drive.putFile("r1", "one.txt", "drive-root", content = "1", modifiedTime = 100L)
        builder.drive.putFile("r2", "two.txt", "drive-root", content = "2", modifiedTime = 200L)
        builder.withChangesPage("p1", DriveChangesPage(listOf(DriveChange("r1", false, file1)), nextPageToken = "p2", newStartPageToken = null))
        builder.withChangesPage("p2", DriveChangesPage(listOf(DriveChange("r2", false, file2)), nextPageToken = "p3", newStartPageToken = null))
        builder.withChangesPage("p3", DriveChangesPage(emptyList(), nextPageToken = null, newStartPageToken = "fresh"))
        val pull = builder.buildWired().pull

        // When
        pull.execute("token", prefs(), FakeLocalFsGateway.DEFAULT_ROOT, "drive-root", "p1")

        // Then
        val order = builder.drive.calls.filter { it.startsWith("listChanges:") }
        assertEquals(listOf("listChanges:p1", "listChanges:p2", "listChanges:p3"), order)
        assertTrue(builder.localFs.allFiles().containsKey("one.txt"))
        assertTrue(builder.localFs.allFiles().containsKey("two.txt"))
    }

    private fun prefs(): AppPreferences {
        return AppPreferences(
            rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
            driveSyncEnabled = true,
            driveSyncPaused = false
        )
    }
}
