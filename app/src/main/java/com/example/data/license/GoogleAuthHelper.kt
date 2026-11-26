package com.example.data.license

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private fun getWebClientId(context: Context): String {
    return try {
        context.getString(R.string.default_web_client_id).trim()
    } catch (e: Exception) {
        Log.w("Auth", "Failed to read default_web_client_id: ${e.message}")
        ""
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

suspend fun attemptAutoSignIn(context: Context): Boolean {
    if (Firebase.auth.currentUser != null) return true
    val clientId = getWebClientId(context)
    if (clientId.isBlank() || clientId.startsWith("UNUSED")) return false

    val credentialManager = CredentialManager.create(context)
    val activityContext = context.findActivity() ?: context
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    return try {
        val result = credentialManager.getCredential(activityContext, request)
        val credential = result.credential
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val authCredential = GoogleAuthProvider.getCredential(googleIdToken, null)
            Firebase.auth.signInWithCredential(authCredential).await()
            Firebase.auth.currentUser != null
        } else {
            false
        }
    } catch (e: GetCredentialCancellationException) {
        Log.w("Auth", "Silent auto sign-in cancelled: ${e.message}")
        false
    } catch (e: Exception) {
        false
    }
}

suspend fun onGoogleSignInClicked(context: Context): Result<String> {
    val clientId = getWebClientId(context)
    if (clientId.isBlank() || clientId.startsWith("UNUSED")) {
        return Result.failure(
            IllegalStateException(
                com.example.ui.i18n.tr(
                    "default_web_client_id not found",
                    "default_web_client_id পাওয়া যায়নি",
                    "default_web_client_id नहीं मिला"
                )
            )
        )
    }

    val credentialManager = CredentialManager.create(context)
    val activityContext = context.findActivity() ?: context

    // Primary interactive Google account chooser bottom sheet (shows all Gmail accounts on phone)
    return try {
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()
        val result = credentialManager.getCredential(activityContext, request)
        val credential = result.credential
        if (credential is CustomCredential && credential.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val tokenCred = GoogleIdTokenCredential.createFrom(credential.data)
            val authCredential = GoogleAuthProvider.getCredential(tokenCred.idToken, null)
            val authResult = Firebase.auth.signInWithCredential(authCredential).await()
            val email = authResult.user?.email ?: tokenCred.id
            Result.success(email)
        } else {
            Result.failure(
                IllegalStateException(
                    com.example.ui.i18n.tr(
                        "Unexpected credential type returned",
                        "অপ্রত্যাশিত গুগল ক্রেডেনশিয়াল পাওয়া গেছে",
                        "अप्रत्याशित क्रेडेंशियल प्रकार मिला"
                    )
                )
            )
        }
    } catch (e: GetCredentialCancellationException) {
        Log.w("Auth", "User cancelled or SHA-1 mismatch in GetSignInWithGoogleOption", e)
        Result.failure(
            Exception(
                com.example.ui.i18n.tr(
                    "Google Sign-In was cancelled",
                    "গুগল লগইন বাতিল করা হয়েছে",
                    "गूगल साइन-इन रद्द कर दिया गया"
                )
            )
        )
    } catch (e: Exception) {
        Log.w("Auth", "GetSignInWithGoogleOption failed (${e.javaClass.simpleName}): ${e.message}", e)
        // Secondary native CredentialManager request showing all device Google accounts
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(false)
                .build()
            val fallbackRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val result2 = credentialManager.getCredential(activityContext, fallbackRequest)
            val credential2 = result2.credential
            if (credential2 is CustomCredential && credential2.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val tokenCred = GoogleIdTokenCredential.createFrom(credential2.data)
                val authCredential = GoogleAuthProvider.getCredential(tokenCred.idToken, null)
                val authResult = Firebase.auth.signInWithCredential(authCredential).await()
                val email = authResult.user?.email ?: tokenCred.id
                Result.success(email)
            } else {
                Result.failure(e)
            }
        } catch (e2: GetCredentialCancellationException) {
            Log.w("Auth", "User cancelled GetGoogleIdOption fallback", e2)
            Result.failure(
                Exception(
                    com.example.ui.i18n.tr(
                        "Google Sign-In was cancelled",
                        "গুগল লগইন বাতিল করা হয়েছে",
                        "गूगल साइन-इन रद्द कर दिया गया"
                    )
                )
            )
        } catch (e2: Exception) {
            Log.e("Auth", "Google sign-in failed", e2)
            Result.failure(e2)
        }
    }
}

suspend fun signOutGoogle(context: Context) {
    withContext(NonCancellable) {
        Firebase.auth.signOut()
        try {
            val credentialManager = CredentialManager.create(context)
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.e("Auth", "Failed to clear credential state", e)
        }
    }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    scope.launch {
        if (attemptAutoSignIn(context)) {
            onAuthSuccess()
        } else {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    scope.launch {
        val res = onGoogleSignInClicked(context)
        res.onSuccess {
            onAuthSuccess()
        }.onFailure { e ->
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun signOutGoogle(
    context: Context,
    credentialManager: CredentialManager,
    onSignOutComplete: () -> Unit,
    scope: CoroutineScope
) {
    scope.launch {
        signOutGoogle(context)
        onSignOutComplete()
    }
}

@Composable
fun GoogleSignInButton(
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    var isLoading by remember { mutableStateOf(false) }

    Button(
        onClick = {
            isLoading = true
            onGoogleSignInClicked(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {
                    isLoading = false
                    onAuthSuccess()
                },
                onAuthError = { errorMsg ->
                    isLoading = false
                    onAuthError(errorMsg)
                },
                scope = coroutineScope,
                onAuthCancelled = { isLoading = false }
            )
        },
        enabled = !isLoading,
        modifier = modifier
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("Sign in with Google")
        }
    }
}
