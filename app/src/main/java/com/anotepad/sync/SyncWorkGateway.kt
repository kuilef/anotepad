package com.anotepad.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface SyncWorkGateway {
    fun enqueueDebounced()
    fun enqueuePeriodic()
    fun enqueueStartup()
    fun enqueueManual()
    fun cancelAllSyncWork()
}

class WorkManagerSyncWorkGateway(
    context: Context
) : SyncWorkGateway {
    private val workManager = WorkManager.getInstance(context)

    override fun enqueueDebounced() {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC_AUTO, ExistingWorkPolicy.REPLACE, request)
    }

    override fun enqueuePeriodic() {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(PERIODIC_HOURS, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()
        workManager.enqueueUniquePeriodicWork(WORK_SYNC_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    override fun enqueueStartup() {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setInitialDelay(STARTUP_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(WORK_SYNC_STARTUP, ExistingWorkPolicy.KEEP, request)
    }

    override fun enqueueManual() {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>().build()
        workManager.enqueueUniqueWork(WORK_SYNC_MANUAL, ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelAllSyncWork() {
        workManager.cancelUniqueWork(WORK_SYNC_PERIODIC)
        workManager.cancelUniqueWork(WORK_SYNC_AUTO)
        workManager.cancelUniqueWork(WORK_SYNC_MANUAL)
        workManager.cancelUniqueWork(WORK_SYNC_STARTUP)
    }

    companion object {
        private const val WORK_SYNC_AUTO = "drive_sync_auto"
        private const val WORK_SYNC_MANUAL = "drive_sync_manual"
        private const val WORK_SYNC_PERIODIC = "drive_sync_periodic"
        private const val WORK_SYNC_STARTUP = "drive_sync_startup"
        private const val DEBOUNCE_SECONDS = 10L
        private const val PERIODIC_HOURS = 8L
        private const val STARTUP_DELAY_SECONDS = 5L
    }
}
