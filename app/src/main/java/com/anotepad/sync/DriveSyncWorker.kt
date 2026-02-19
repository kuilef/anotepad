package com.anotepad.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.anotepad.data.PreferencesRepository
import com.anotepad.file.FileRepository
import com.anotepad.file.ListCacheManager
import com.anotepad.file.SafFileLister
import com.anotepad.file.SafFileReaderWriter
import com.anotepad.file.isSupportedTextFileExtension
import com.anotepad.sync.db.SyncDatabase
import com.anotepad.sync.engine.AuthGatewayAdapter
import com.anotepad.sync.engine.DriveGatewayAdapter
import com.anotepad.sync.engine.LocalFsGatewayAdapter
import com.anotepad.sync.engine.PrefsGatewayAdapter
import com.anotepad.sync.engine.SyncStoreAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class DriveSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val prefsRepo = PreferencesRepository(applicationContext)
            val resolver = applicationContext.contentResolver
            val listCacheManager = ListCacheManager()
            val fileLister = SafFileLister(
                context = applicationContext,
                resolver = resolver,
                cacheManager = listCacheManager,
                isSupportedExtension = ::isSupportedTextFileExtension
            )
            val readerWriter = SafFileReaderWriter(resolver)
            val fileRepo = FileRepository(
                context = applicationContext,
                resolver = resolver,
                cacheManager = listCacheManager,
                fileLister = fileLister,
                readerWriter = readerWriter
            )
            val syncDb = SyncDatabase.getInstance(applicationContext)
            val syncRepository = SyncRepository(syncDb)
            val store = SyncStoreAdapter(syncRepository)
            val authManager = DriveAuthManager(applicationContext)
            val authGateway = AuthGatewayAdapter(authManager)
            val driveGateway = DriveGatewayAdapter(DriveClient())
            val logger = SyncLogger(applicationContext)

            val engine = SyncEngine(
                prefsGateway = PrefsGatewayAdapter(prefsRepo),
                localFsGateway = LocalFsGatewayAdapter(fileRepo),
                syncStore = store,
                authGateway = authGateway,
                driveGateway = driveGateway
            )

            val runner = DriveSyncWorkerRunner(
                engine = engine,
                store = store,
                authGateway = authGateway,
                logger = { event -> logger.log(event) }
            )

            when (runner.run()) {
                WorkerDecision.Success -> Result.success()
                WorkerDecision.Retry -> Result.retry()
                WorkerDecision.Failure -> Result.failure()
            }
        }
    }

    companion object {
        private val syncMutex = Mutex()
    }
}
