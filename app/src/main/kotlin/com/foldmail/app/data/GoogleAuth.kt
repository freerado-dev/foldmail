package com.foldmail.app.data

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class NeedsConsentException : Exception("Google vyžaduje znova udeliť povolenie. Pridajte účet znova.")

/** Gets Gmail access tokens through Google Play services (no client secret needed on Android). */
object GoogleAuth {
    const val GMAIL_SCOPE = "https://www.googleapis.com/auth/gmail.modify"

    private fun request(email: String?): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GMAIL_SCOPE)))
        if (email != null) b.setAccount(android.accounts.Account(email, "com.google"))
        return b.build()
    }

    /** May return a result that [AuthorizationResult.hasResolution] – the caller then shows the consent screen. */
    suspend fun authorize(context: Context, email: String?): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(request(email)).await()

    fun tokenFromIntent(context: Context, data: Intent?): String =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
            ?: throw IllegalStateException("Google nevrátil prístupový token")

    /** Silent token for an already authorised account. */
    suspend fun accessToken(context: Context, email: String): String {
        val r = authorize(context, email)
        if (r.hasResolution()) throw NeedsConsentException()
        return r.accessToken ?: throw IllegalStateException("Google nevrátil prístupový token")
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
