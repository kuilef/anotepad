package com.anotepad.sync

import com.anotepad.sync.engine.AuthGateway
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
        return try {
            logger("sync_start")
            when (val result = engine.runSync()) {
                is SyncResult.Success -> {
                    logger("sync_success")
                    WorkerDecision.Success
                }

                is SyncResult.Skipped -> {
                    logger("sync_skipped")
                    WorkerDecision.Success
                }

                is SyncResult.Failure -> {
                    logger("sync_failure auth=${result.authError}")
                    if (result.authError) WorkerDecision.Failure else WorkerDecision.Retry
                }
            }
        } catch (error: CancellationException) {
            logger("sync_cancelled detail=${error.message ?: "none"}")
            throw error
        } catch (error: Exception) {
            val syncError = error.toSyncError()
            mapSyncError(syncError)
        }
    }

    private suspend fun mapSyncError(error: SyncError): WorkerDecision {
        return when (error) {
            is SyncError.Network -> {
                val message = error.detail?.let { "Network error: $it" } ?: "Network error, will retry"
                logger("sync_retry network_error detail=${error.detail ?: "none"}")
                store.setSyncStatus(SyncState.ERROR, message)
                WorkerDecision.Retry
            }

            is SyncError.Auth -> {
                logger("sync_error auth code=${error.code ?: -1} detail=${error.message ?: "none"}")
                if (error.code == 401) {
                    runCatching { authGateway.revokeAccess() }
                    store.setSyncStatus(SyncState.ERROR, "Sign in required")
                } else {
                    val message = error.message?.let { "Authorization required: $it" } ?: "Authorization required"
                    store.setSyncStatus(SyncState.ERROR, message)
                }
                WorkerDecision.Failure
            }

            is SyncError.DriveApi -> {
                val retryable = error.code == 429 || error.code >= 500
                val readable = error.message?.let { "Drive error ${error.code}: $it" } ?: "Drive error ${error.code}"
                logger("sync_error code=${error.code} detail=${error.message ?: "none"}")
                store.setSyncStatus(SyncState.ERROR, readable)
                if (retryable) WorkerDecision.Retry else WorkerDecision.Failure
            }

            is SyncError.Unexpected -> {
                val message = error.detail?.let { "Unexpected error: ${error.type}: $it" }
                    ?: "Unexpected error: ${error.type}"
                logger("sync_retry unexpected_error type=${error.type} detail=${error.detail ?: "none"}")
                store.setSyncStatus(SyncState.ERROR, message)
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
                401, 403 -> SyncError.Auth(code = code, message = detail)
                else -> SyncError.DriveApi(code = code, message = detail)
            }
        }

        else -> {
            val type = this::class.java.simpleName?.ifBlank { "UnknownException" } ?: "UnknownException"
            SyncError.Unexpected(type, message?.ifBlank { null })
        }
    }
}
