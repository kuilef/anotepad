package com.anotepad.sync.engine

sealed class SyncError {
    data class Network(val detail: String?) : SyncError()
    data class DriveApi(val code: Int, val message: String?) : SyncError()
    data class Auth(val code: Int?, val message: String?) : SyncError()
    data class Unexpected(val type: String, val detail: String?) : SyncError()
}
