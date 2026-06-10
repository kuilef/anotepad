package com.anotepad.sync

import com.anotepad.sync.engine.AuthGateway
import com.anotepad.sync.engine.LocalStorageUnavailableException
import com.anotepad.sync.engine.SyncError
import com.anotepad.sync.engine.SyncStore
import kotlinx.coroutines.CancellationException

sealed class WorkerDecision {
    data object Success : WorkerDecision()
    data object Retry : WorkerDecision()
    data object Failure : WorkerDecision()
}

class DriveSyncWorkerRunner(
    private val engine: SyncEngine,
    private val store: SyncStore,
    private val authGateway: AuthGateway,
    private val logger: (String) -> Unit = {}
) {
    suspend fun run(): WorkerDecision {
        logger("sync_start")
        var retriedAfter401 = false
        while (true) {
            try {
                when (val result = engine.runSync()) {
                    is SyncResult.Success -> {
                        logger("sync_success")
                        return WorkerDecision.Success
                    }

                    is SyncResult.Skipped -> {
                        logger("sync_skipped")
                        return WorkerDecision.Success
                    }

                    is SyncResult.Failure -> {
                        logger("sync_failure auth=${result.authError}")
                        if (result.authError && retriedAfter401) {
                            runCatching { authGateway.revokeAccess() }
                            store.setSyncStatus(
                                SyncState.ERROR,
                                SyncStatusMessage(SyncStatusMessageType.SIGN_IN_REQUIRED)
                            )
                        }
                        return if (result.authError) WorkerDecision.Failure else WorkerDecision.Retry
                    }
                }
            } catch (error: CancellationException) {
                logger("sync_cancelled detail=${error.message ?: "none"}")
                throw error
            } catch (error: Exception) {
                val syncError = error.toSyncError()
                if (syncError is SyncError.Auth && syncError.code == 401 && !retriedAfter401) {
                    retriedAfter401 = true
                    val invalidated = runCatching { authGateway.invalidateAccessToken() }.getOrDefault(false)
                    logger("sync_auth_401_retry invalidated=$invalidated")
                    store.setSyncStatus(
                        SyncState.PENDING,
                        SyncStatusMessage(SyncStatusMessageType.REFRESHING_AUTHORIZATION)
                    )
                    continue
                }
                return mapSyncError(syncError, retriedAfter401)
            }
        }
    }

    private suspend fun mapSyncError(error: SyncError, retriedAfter401: Boolean): WorkerDecision {
        return when (error) {
            is SyncError.Network -> {
                logger("sync_retry network_error detail=${error.detail ?: "none"}")
                store.setSyncStatus(
                    SyncState.ERROR,
                    SyncStatusMessage(
                        type = if (error.detail == null) {
                            SyncStatusMessageType.NETWORK_ERROR_RETRY
                        } else {
                            SyncStatusMessageType.NETWORK_ERROR
                        },
                        detail = error.detail
                    )
                )
                WorkerDecision.Retry
            }

            is SyncError.Auth -> {
                logger("sync_error auth code=${error.code ?: -1} detail=${error.message ?: "none"}")
                val shouldRevoke = retriedAfter401
                if (shouldRevoke) {
                    runCatching { authGateway.revokeAccess() }
                    store.setSyncStatus(
                        SyncState.ERROR,
                        SyncStatusMessage(SyncStatusMessageType.SIGN_IN_REQUIRED)
                    )
                } else {
                    store.setSyncStatus(
                        SyncState.ERROR,
                        SyncStatusMessage(
                            type = SyncStatusMessageType.AUTHORIZATION_REQUIRED,
                            detail = error.message
                        )
                    )
                }
                WorkerDecision.Failure
            }

            is SyncError.DriveApi -> {
                val retryable = error.code == 429 || error.code >= 500
                logger("sync_error code=${error.code} detail=${error.message ?: "none"}")
                store.setSyncStatus(
                    SyncState.ERROR,
                    SyncStatusMessage(
                        type = SyncStatusMessageType.DRIVE_ERROR,
                        detail = error.message,
                        code = error.code
                    )
                )
                if (retryable) WorkerDecision.Retry else WorkerDecision.Failure
            }

            is SyncError.LocalStorage -> {
                logger("sync_error local_storage detail=${error.message ?: "none"}")
                store.setSyncStatus(
                    SyncState.ERROR,
                    SyncStatusMessage(
                        type = SyncStatusMessageType.LOCAL_STORAGE_UNAVAILABLE,
                        detail = error.message
                    )
                )
                WorkerDecision.Failure
            }

            is SyncError.Unexpected -> {
                logger("sync_retry unexpected_error type=${error.type} detail=${error.detail ?: "none"}")
                store.setSyncStatus(
                    SyncState.ERROR,
                    SyncStatusMessage(
                        type = SyncStatusMessageType.UNEXPECTED_ERROR,
                        detail = listOfNotNull(error.type, error.detail).joinToString(": ")
                    )
                )
                WorkerDecision.Retry
            }
        }
    }
}

fun Exception.toSyncError(): SyncError {
    return when (this) {
        is DriveNetworkException -> SyncError.Network(this.description)
        is DriveApiException -> {
            val detail = this.userMessage()
            when (code) {
                401 -> SyncError.Auth(code = code, message = detail)
                else -> SyncError.DriveApi(code = code, message = detail)
            }
        }

        is LocalStorageUnavailableException -> SyncError.LocalStorage(message?.ifBlank { null })

        else -> {
            val type = this::class.java.simpleName.ifBlank { "UnknownException" }
            SyncError.Unexpected(type, message?.ifBlank { null })
        }
    }
}
