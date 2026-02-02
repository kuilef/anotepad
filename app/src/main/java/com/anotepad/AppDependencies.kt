package com.anotepad

import android.content.Context
import com.anotepad.data.PreferencesRepository
import com.anotepad.data.TemplateRepository
import com.anotepad.file.FileRepository
import com.anotepad.sync.DriveAuthManager
import com.anotepad.sync.SyncRepository
import com.anotepad.sync.SyncScheduler

class AppDependencies(
    val appContext: Context,
    val preferencesRepository: PreferencesRepository,
    val templateRepository: TemplateRepository,
    val fileRepository: FileRepository,
    val syncRepository: SyncRepository,
    val syncScheduler: SyncScheduler,
    val driveAuthManager: DriveAuthManager
)
