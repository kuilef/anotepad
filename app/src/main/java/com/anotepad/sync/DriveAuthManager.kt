package com.anotepad.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveAuthManager(private val context: Context) {
    private val scope = Scope(DRIVE_SCOPE)

    private fun buildSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scope)
            .build()
    }

    fun signInIntent(): Intent {
        return signInClient().signInIntent
    }

    fun signInClient(): GoogleSignInClient {
        return GoogleSignIn.getClient(context, buildSignInOptions())
    }

    fun getSignedInAccount(): GoogleSignInAccount? {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
        return if (GoogleSignIn.hasPermissions(account, scope)) account else null
    }

    fun isSignedIn(): Boolean = getSignedInAccount() != null

    suspend fun getAccessTokenResult(): DriveAccessTokenResult = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)
            ?: return@withContext DriveAccessTokenResult.NoAccount
        val scopeString = "oauth2:$DRIVE_SCOPE"
        return@withContext try {
            val acct = account.account ?: return@withContext DriveAccessTokenResult.NoAccount
            val token = GoogleAuthUtil.getToken(context, acct, scopeString)
            if (token.isBlank()) {
                DriveAccessTokenResult.Error
            } else {
                DriveAccessTokenResult.Success(token)
            }
        } catch (error: UserRecoverableAuthException) {
            DriveAccessTokenResult.Recoverable(error.intent)
        } catch (_: Exception) {
            DriveAccessTokenResult.Error
        }
    }

    suspend fun getAccessToken(): String? {
        return when (val result = getAccessTokenResult()) {
            is DriveAccessTokenResult.Success -> result.token
            else -> null
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        signInClient().signOut().await()
    }

    suspend fun revokeAccess() = withContext(Dispatchers.IO) {
        signInClient().revokeAccess().await()
    }

    companion object {
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}

sealed class DriveAccessTokenResult {
    data class Success(val token: String) : DriveAccessTokenResult()
    data class Recoverable(val intent: Intent) : DriveAccessTokenResult()
    data object NoAccount : DriveAccessTokenResult()
    data object Error : DriveAccessTokenResult()
}
