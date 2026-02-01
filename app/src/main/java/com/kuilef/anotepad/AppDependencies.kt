package com.kuilef.anotepad

import android.content.Context
import com.kuilef.anotepad.data.PreferencesRepository
import com.kuilef.anotepad.data.TemplateRepository
import com.kuilef.anotepad.file.FileRepository
import com.kuilef.anotepad.sync.DriveAuthManager
import com.kuilef.anotepad.sync.SyncRepository
import com.kuilef.anotepad.sync.SyncScheduler

class AppDependencies(
    val appContext: Context,
    val preferencesRepository: PreferencesRepository,
    val templateRepository: TemplateRepository,
    val fileRepository: FileRepository,
    val syncRepository: SyncRepository,
    val syncScheduler: SyncScheduler,
    val driveAuthManager: DriveAuthManager
)
