package com.anotepad.sync

data class SyncStatus(
    val state: SyncState,
    val lastSyncedAt: Long?,
    val message: String?
)

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
