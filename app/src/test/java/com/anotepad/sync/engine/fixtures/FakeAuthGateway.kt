package com.anotepad.sync.engine.fixtures

import com.anotepad.sync.engine.AuthGateway

class FakeAuthGateway(
    var token: String? = "token"
) : AuthGateway {
    var revokeCalls: Int = 0
    var invalidateCalls: Int = 0
    var invalidateResult: Boolean = true

    override suspend fun getAccessToken(): String? = token

    override suspend fun invalidateAccessToken(): Boolean {
        invalidateCalls++
        if (!invalidateResult) return false
        token = token?.let { "${it}_fresh" }
        return true
    }

    override suspend fun revokeAccess() {
        revokeCalls++
    }
}
