package com.anotepad

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val incomingShareViewModel: IncomingShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        IncomingShareRecoveryStore.initialize(applicationContext)
        if (savedInstanceState == null) {
            dispatchIncomingShare(intent)
        }

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
                val sharedDraftRecoveryStore = SharedDraftRecoveryStore.fromContext(applicationContext)
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
                    incomingShareManager = incomingShareViewModel.manager,
                    sharedDraftRecoveryStore = sharedDraftRecoveryStore,
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatchIncomingShare(intent)
    }

    private fun dispatchIncomingShare(intent: Intent?) {
        if (!isSupportedShareIntent(intent)) return
        lifecycleScope.launch(Dispatchers.IO) {
            val payload = extractSharedTextPayload(applicationContext, intent)
            if (payload == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, R.string.error_shared_text_empty, Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            incomingShareViewModel.manager.submitShare(payload)
        }
    }
}
