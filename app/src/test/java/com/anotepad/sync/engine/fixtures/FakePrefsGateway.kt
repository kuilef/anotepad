package com.anotepad.sync.engine.fixtures

import com.anotepad.data.AppPreferences
import com.anotepad.sync.engine.PrefsGateway

class FakePrefsGateway(
    var prefs: AppPreferences = AppPreferences(
        rootTreeUri = FakeLocalFsGateway.DEFAULT_ROOT,
        driveSyncEnabled = true,
        driveSyncPaused = false,
        driveSyncFolderName = "Anotepad"
    )
) : PrefsGateway {
    override suspend fun getPreferences(): AppPreferences = prefs
}
