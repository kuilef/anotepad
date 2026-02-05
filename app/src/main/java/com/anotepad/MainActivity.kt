package com.anotepad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.anotepad.data.PreferencesRepository
import com.anotepad.data.TemplateRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.DriveAuthManager
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncScheduler
import com.anotepad.sync.db.SyncDatabase
import com.anotepad.ui.theme.ANotepadTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val deps = remember {
                val prefs = PreferencesRepository(applicationContext)
                val templates = TemplateRepository(prefs)
                val files = FileRepository(applicationContext)
                val syncDb = SyncDatabase.getInstance(applicationContext)
                val syncRepository = SyncRepository(syncDb)
                val syncScheduler = SyncScheduler(applicationContext, prefs, syncRepository)
                val driveAuthManager = DriveAuthManager(applicationContext)
                AppDependencies(
                    appContext = applicationContext,
                    preferencesRepository = prefs,
                    templateRepository = templates,
                    fileRepository = files,
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
}
