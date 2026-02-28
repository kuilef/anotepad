package com.anotepad

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.anotepad.data.PreferencesRepository
import com.anotepad.data.TemplateRepository
import com.anotepad.file.FileRepository
import com.anotepad.file.ListCacheManager
import com.anotepad.file.SafFileLister
import com.anotepad.file.SafFileOpsHandler
import com.anotepad.file.SafFileReaderWriter
import com.anotepad.file.isSupportedTextFileExtension
import com.anotepad.sync.DriveAuthManager
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncScheduler
import com.anotepad.sync.db.SyncDatabase
import com.anotepad.ui.FeedManager
import com.anotepad.ui.theme.ANotepadTheme

class MainActivity : ComponentActivity() {
    private val incomingShareManager = IncomingShareManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val deps = remember {
                val prefs = PreferencesRepository(applicationContext)
                val templates = TemplateRepository(prefs)
                val resolver = applicationContext.contentResolver
                val listCacheManager = ListCacheManager()
                val fileLister = SafFileLister(
                    context = applicationContext,
                    resolver = resolver,
                    cacheManager = listCacheManager,
                    isSupportedExtension = ::isSupportedTextFileExtension
                )
                val readerWriter = SafFileReaderWriter(resolver)
                val fileOpsHandler = SafFileOpsHandler(
                    context = applicationContext,
                    resolver = resolver,
                    cacheManager = listCacheManager
                )
                val files = FileRepository(
                    fileLister = fileLister,
                    readerWriter = readerWriter,
                    fileOpsHandler = fileOpsHandler
                )
                val syncDb = SyncDatabase.getInstance(applicationContext)
                val syncRepository = SyncRepository(syncDb)
                val syncScheduler = SyncScheduler(applicationContext, prefs, syncRepository)
                val driveAuthManager = DriveAuthManager(applicationContext)
                AppDependencies(
                    appContext = applicationContext,
                    preferencesRepository = prefs,
                    templateRepository = templates,
                    fileRepository = files,
                    incomingShareManager = incomingShareManager,
                    createFeedManager = {
                        FeedManager(readTextPreview = files::readTextPreview)
                    },
                    syncRepository = syncRepository,
                    syncScheduler = syncScheduler,
                    driveAuthManager = driveAuthManager
                )
            }
            ANotepadTheme {
                LaunchedEffect(Unit) {
                    deps.syncScheduler.schedulePeriodic()
                    if (deps.driveAuthManager.isSignedIn()) {
                        deps.syncScheduler.scheduleStartup()
                    }
                }
                AppNav(deps)
            }
        }

        if (savedInstanceState == null) {
            dispatchIncomingShare(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingShare(intent)
    }

    private fun dispatchIncomingShare(intent: Intent?) {
        val payload = extractSharedTextPayload(applicationContext, intent) ?: return
        incomingShareManager.submitShare(payload)
    }
}
