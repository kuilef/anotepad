package com.kuilef.anotepad

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.kuilef.anotepad.data.PreferencesRepository
import com.kuilef.anotepad.data.TemplateRepository
import com.kuilef.anotepad.file.FileRepository
import com.kuilef.anotepad.sync.DriveAuthManager
import com.kuilef.anotepad.sync.SyncRepository
import com.kuilef.anotepad.sync.SyncScheduler
import com.kuilef.anotepad.sync.db.SyncDatabase
import com.kuilef.anotepad.ui.theme.ANotepadTheme

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
                }
                AppNav(deps)
            }
        }
    }
}
