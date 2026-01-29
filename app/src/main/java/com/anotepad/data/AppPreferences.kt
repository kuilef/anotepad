package com.anotepad.data

data class AppPreferences(
    val rootTreeUri: String? = null,
    val autoLinkWeb: Boolean = true,
    val autoLinkEmail: Boolean = true,
    val autoLinkTel: Boolean = false,
    val syncTitle: Boolean = true,
    val autoSaveDebounceMs: Long = 1200L,
    val autoSaveEnabled: Boolean = true,
    val browserFontSizeSp: Float = 14f,
    val fileSortOrder: FileSortOrder = FileSortOrder.NAME_DESC,
    val browserViewMode: BrowserViewMode = BrowserViewMode.LIST,
    val editorFontSizeSp: Float = 16f,
    val autoInsertTemplateEnabled: Boolean = true,
    val autoInsertTemplate: String = "yyyy-MM-dd",
    val defaultFileExtension: String = "txt",
    val driveSyncEnabled: Boolean = false,
    val driveSyncWifiOnly: Boolean = true,
    val driveSyncChargingOnly: Boolean = false,
    val driveSyncPaused: Boolean = false,
    val driveSyncIgnoreRemoteDeletes: Boolean = false,
    val driveSyncFolderName: String = "MiniNoteViewer"
)
