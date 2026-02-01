package com.kuilef.anotepad.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kuilef.anotepad.data.PreferencesRepository
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SyncScheduler(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: SyncRepository
) {
    private val workManager = WorkManager.getInstance(context)

    suspend fun scheduleDebounced() {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) return
        val constraints = buildConstraints(prefs, manual = false)
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
        syncRepository.setSyncStatus(SyncState.PENDING, "Waiting for sync")
    }

    suspend fun schedulePeriodic() {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) {
            workManager.cancelUniqueWork(WORK_SYNC_PERIODIC)
            workManager.cancelUniqueWork(WORK_SYNC_NOW)
            return
        }
        val constraints = buildConstraints(prefs, manual = false)
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_SYNC_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    suspend fun syncNow() {
        val prefs = preferencesRepository.preferencesFlow.first()
        if (!prefs.driveSyncEnabled || prefs.driveSyncPaused) return
        val constraints = buildConstraints(prefs, manual = true)
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC_NOW, ExistingWorkPolicy.REPLACE, request)
        syncRepository.setSyncStatus(SyncState.PENDING, "Sync scheduled")
    }

    private fun buildConstraints(prefs: com.kuilef.anotepad.data.AppPreferences, manual: Boolean): Constraints {
        val networkType = if (manual) {
            NetworkType.CONNECTED
        } else {
            if (prefs.driveSyncWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        }
        return Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .apply {
                if (!manual) {
                    setRequiresCharging(prefs.driveSyncChargingOnly)
                    setRequiresBatteryNotLow(true)
                }
            }
            .build()
    }

    companion object {
        private const val WORK_SYNC_NOW = "drive_sync_now"
        private const val WORK_SYNC_PERIODIC = "drive_sync_periodic"
        private const val DEBOUNCE_SECONDS = 45L
        private const val PERIODIC_HOURS = 8L
    }
}
