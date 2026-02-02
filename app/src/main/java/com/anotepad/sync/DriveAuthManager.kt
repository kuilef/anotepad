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
    private val metadataScope = Scope(DRIVE_METADATA_SCOPE)

    private fun buildSignInOptions(): GoogleSignInOptions {
        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(scope, metadataScope)
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
        return if (GoogleSignIn.hasPermissions(account, scope, metadataScope)) account else null
    }

    fun isSignedIn(): Boolean = getSignedInAccount() != null

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: return@withContext null
        val scopeString = "oauth2:$DRIVE_SCOPE $DRIVE_METADATA_SCOPE"
        return@withContext try {
            val acct = account.account ?: return@withContext null
            GoogleAuthUtil.getToken(context, acct, scopeString)
        } catch (_: UserRecoverableAuthException) {
            null
        } catch (_: Exception) {
            null
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
        const val DRIVE_METADATA_SCOPE = "https://www.googleapis.com/auth/drive.metadata.readonly"
    }
}
