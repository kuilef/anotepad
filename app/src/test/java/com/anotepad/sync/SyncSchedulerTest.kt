package com.anotepad.sync

import com.anotepad.data.AppPreferences
import com.anotepad.sync.engine.fixtures.FakeLocalFsGateway
import com.anotepad.sync.engine.fixtures.FakePrefsGateway
import com.anotepad.sync.engine.fixtures.FakeSyncStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {

    @Test
    fun scheduleDebounced_enqueuesWork_whenSyncEnabledAndNotPaused() = runTest {
        // Given
        val prefs = FakePrefsGateway(
            AppPreferences(
                rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
                driveSyncEnabled = true,
                driveSyncPaused = false
            )
        )
        val store = FakeSyncStore()
        val work = FakeWorkGateway()
        val scheduler = SyncScheduler(prefs, store, work)

        // When
        scheduler.scheduleDebounced()

        // Then
        assertEquals(1, work.debounced)
        assertEquals(SyncState.PENDING, store.statuses.last().state)
    }

    @Test
    fun scheduleDebounced_noop_whenDisabled() = runTest {
        // Given
        val prefs = FakePrefsGateway(AppPreferences(driveSyncEnabled = false))
        val work = FakeWorkGateway()
        val scheduler = SyncScheduler(prefs, FakeSyncStore(), work)

        // When
        scheduler.scheduleDebounced()

        // Then
        assertEquals(0, work.debounced)
    }

    @Test
    fun schedulePeriodic_cancelsAll_whenDisabledOrPaused() = runTest {
        // Given
        val store = FakeSyncStore()
        val work = FakeWorkGateway()
        val schedulerDisabled = SyncScheduler(FakePrefsGateway(AppPreferences(driveSyncEnabled = false)), store, work)

        // When
        schedulerDisabled.schedulePeriodic()

        // Then
        assertEquals(1, work.cancelAll)

        // Given
        val pausedWork = FakeWorkGateway()
        val schedulerPaused = SyncScheduler(
            FakePrefsGateway(AppPreferences(rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT, driveSyncEnabled = true, driveSyncPaused = true)),
            store,
            pausedWork
        )

        // When
        schedulerPaused.schedulePeriodic()

        // Then
        assertEquals(1, pausedWork.cancelAll)
    }

    @Test
    fun schedulePeriodic_enqueuesPeriodic_whenEnabled() = runTest {
        // Given
        val work = FakeWorkGateway()
        val scheduler = SyncScheduler(
            FakePrefsGateway(AppPreferences(rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT, driveSyncEnabled = true)),
            FakeSyncStore(),
            work
        )

        // When
        scheduler.schedulePeriodic()

        // Then
        assertEquals(1, work.periodic)
    }

    @Test
    fun scheduleStartup_requiresEnabledSignedInRootAndFolder() = runTest {
        // Given
        val store = FakeSyncStore()
        val work = FakeWorkGateway()
        val scheduler = SyncScheduler(
            FakePrefsGateway(
                AppPreferences(
                    rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
                    driveSyncEnabled = true,
                    driveSyncAutoOnStart = true
                )
            ),
            store,
            work
        )

        // When
        scheduler.scheduleStartup()

        // Then
        assertEquals(0, work.startup)

        // Given
        store.driveFolderId = "drive-root"

        // When
        scheduler.scheduleStartup()

        // Then
        assertEquals(1, work.startup)
    }

    @Test
    fun syncNow_enqueuesManualWork_whenEnabledNotPaused() = runTest {
        // Given
        val work = FakeWorkGateway()
        val scheduler = SyncScheduler(
            FakePrefsGateway(AppPreferences(rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT, driveSyncEnabled = true)),
            FakeSyncStore(),
            work
        )

        // When
        scheduler.syncNow()

        // Then
        assertEquals(1, work.manual)
    }

    private class FakeWorkGateway : SyncWorkGateway {
        var debounced = 0
        var periodic = 0
        var startup = 0
        var manual = 0
        var cancelAll = 0

        override fun enqueueDebounced() {
            debounced++
        }

        override fun enqueuePeriodic() {
            periodic++
        }

        override fun enqueueStartup() {
            startup++
        }

        override fun enqueueManual() {
            manual++
        }

        override fun cancelAllSyncWork() {
            cancelAll++
        }
    }
}
