package com.kuilef.anotepad.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kuilef.anotepad.data.PreferencesRepository
import com.kuilef.anotepad.file.FileRepository
import com.kuilef.anotepad.sync.db.SyncDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefsRepo = PreferencesRepository(applicationContext)
        val fileRepo = FileRepository(applicationContext)
        val syncDb = SyncDatabase.getInstance(applicationContext)
        val syncRepository = SyncRepository(syncDb)
        val authManager = DriveAuthManager(applicationContext)
        val driveClient = DriveClient()
        val logger = SyncLogger(applicationContext)
        val engine = SyncEngine(prefsRepo, fileRepo, syncRepository, authManager, driveClient)
        try {
            logger.log("sync_start")
            when (val result = engine.runSync()) {
                is SyncResult.Success -> {
                    logger.log("sync_success")
                    Result.success()
                }
                is SyncResult.Skipped -> {
                    logger.log("sync_skipped")
                    Result.success()
                }
                is SyncResult.Failure -> {
                    logger.log("sync_failure auth=${result.authError}")
                    if (result.authError) Result.failure() else Result.retry()
                }
            }
        } catch (error: DriveNetworkException) {
            val description = error.description
            val message = description?.let { "Network error: $it" } ?: "Network error, will retry"
            logger.log(
                "sync_retry network_error type=${error.type ?: "unknown"} detail=${error.detail ?: "none"}"
            )
            syncRepository.setSyncStatus(SyncState.ERROR, message)
            Result.retry()
        } catch (error: DriveApiException) {
            val retryable = error.code == 429 || error.code >= 500
            val auth = error.code == 401 || error.code == 403
            val detail = error.userMessage()
            val message = when {
                auth -> detail?.let { "Authorization required: $it" } ?: "Authorization required"
                detail != null -> "Drive error ${error.code}: $detail"
                else -> "Drive error ${error.code}"
            }
            logger.log(
                "sync_error code=${error.code} detail=${detail ?: "none"} " +
                    "method=${error.method ?: "unknown"} url=${error.url ?: "unknown"}"
            )
            syncRepository.setSyncStatus(SyncState.ERROR, message)
            when {
                auth -> Result.failure()
                retryable -> Result.retry()
                else -> Result.failure()
            }
        } catch (error: Exception) {
            val type = error::class.java.simpleName?.ifBlank { "UnknownException" } ?: "UnknownException"
            val detail = error.message?.ifBlank { null }
            val message = detail?.let { "Unexpected error: $type: $it" } ?: "Unexpected error: $type"
            logger.log(
                "sync_retry unexpected_error type=$type detail=${detail ?: "none"}"
            )
            syncRepository.setSyncStatus(SyncState.ERROR, message)
            Result.retry()
        }
    }
}
