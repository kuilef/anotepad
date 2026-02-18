package com.anotepad.sync.engine.fixtures

import com.anotepad.sync.engine.AuthGateway

class FakeAuthGateway(
    var token: String? = "token"
) : AuthGateway {
    var revokeCalls: Int = 0

    override suspend fun getAccessToken(): String? = token

    override suspend fun revokeAccess() {
        revokeCalls++
    }
}
