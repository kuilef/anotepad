package com.anotepad.sync

data class SyncStatus(
    val state: SyncState,
    val lastSyncedAt: Long?,
    val message: SyncStatusMessage?
)

data class SyncStatusMessage(
    val type: SyncStatusMessageType,
    val detail: String? = null,
    val code: Int? = null
)

enum class SyncStatusMessageType {
    WAITING_FOR_SYNC,
    SYNC_SCHEDULED,
    SYNC_DISABLED,
    SYNC_PAUSED,
    NO_LOCAL_FOLDER_SELECTED,
    SIGN_IN_REQUIRED,
    REFRESHING_AUTHORIZATION,
    MULTIPLE_DRIVE_FOLDERS,
    DRIVE_FOLDER_NOT_CONNECTED,
    NETWORK_ERROR,
    NETWORK_ERROR_RETRY,
    AUTHORIZATION_REQUIRED,
    DRIVE_ERROR,
    LOCAL_STORAGE_UNAVAILABLE,
    UNEXPECTED_ERROR,
    LEGACY_MESSAGE
}

enum class SyncState {
    IDLE,
    PENDING,
    RUNNING,
    ERROR,
    SYNCED
}

enum class SyncItemState {
    SYNCED,
    PENDING_UPLOAD,
    PENDING_DOWNLOAD,
    CONFLICT,
    ERROR
}

enum class RemoteDeletePolicy {
    TRASH,
    DELETE,
    IGNORE
}
