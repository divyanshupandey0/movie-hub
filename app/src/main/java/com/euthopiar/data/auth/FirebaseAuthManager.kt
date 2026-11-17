package com.euthopiar.data.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.euthopiar.core.BuildConfig
import com.euthopiar.data.preferences.PreferencesRepository
import com.euthopiar.data.sync.supabase.SupabaseSyncManager
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Robust coordinator for Firebase Authentication with "Continue with Google" sign-in.
 * Bridges Firebase user identity with Supabase synchronization, preserving all existing user data.
 */
class FirebaseAuthManager private constructor(
    private val context: Context,
    private val preferencesRepository: PreferencesRepository = PreferencesRepository.getInstance(context)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firebaseAuth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private val _currentUser = MutableStateFlow<FirebaseUser?>(null)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isSigningIn = MutableStateFlow(false)
    val isSigningIn: StateFlow<Boolean> = _isSigningIn.asStateFlow()

    init {
        _currentUser.value = firebaseAuth.currentUser
        firebaseAuth.addAuthStateListener { auth ->
            _currentUser.value = auth.currentUser
        }
    }

    /**
     * Executes the recommended Google Identity Credential Manager sign-in flow
     * and authenticates the returned token with Firebase Authentication.
     */
    suspend fun signInWithGoogle(activity: Activity): Result<FirebaseUser> = withContext(Dispatchers.IO) {
        if (_isSigningIn.value) {
            return@withContext Result.failure(IllegalStateException("Sign in request already in progress"))
        }
        _isSigningIn.value = true
        try {
            val serverClientId = resolveServerClientId(activity)
            val rawNonce = generateRawNonce()
            val hashedNonce = sha256(rawNonce)

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .setNonce(hashedNonce)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val credentialManager = CredentialManager.create(activity)
            val result = try {
                credentialManager.getCredential(
                    request = request,
                    context = activity
                )
            } catch (e: GetCredentialCancellationException) {
                return@withContext Result.failure(Exception("Sign in cancelled by user"))
            } catch (e: GetCredentialException) {
                return@withContext Result.failure(Exception("Credential Manager error: ${e.message}"))
            }

            val credential = result.credential
            val idToken = when {
                credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    googleIdTokenCredential.idToken
                }
                else -> {
                    return@withContext Result.failure(IllegalStateException("Unrecognized credential format returned."))
                }
            }

            // Authenticate directly with Firebase Authentication
            val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = firebaseAuth.signInWithCredential(firebaseCredential).await()
            val firebaseUser = authResult.user
                ?: return@withContext Result.failure(IllegalStateException("Firebase user was null after authentication"))

            _currentUser.value = firebaseUser

            // Bridge to Supabase cloud sync to preserve existing user data and profile
            try {
                val supabaseManager = SupabaseSyncManager.getInstance(activity)
                supabaseManager.authenticateFromGoogleToken(idToken, rawNonce, firebaseUser)
            } catch (se: Exception) {
                Log.w(TAG, "Supabase bridge sync warning: ${se.message}")
            }

            Result.success(firebaseUser)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Google sign-in failed", e)
            Result.failure(e)
        } finally {
            _isSigningIn.value = false
        }
    }

    suspend fun signOut(activity: Activity? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            firebaseAuth.signOut()
            _currentUser.value = null
            if (activity != null) {
                try {
                    val credentialManager = CredentialManager.create(activity)
                    credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
                } catch (_: Exception) {}
            }
            // Also sign out from Supabase sync
            try {
                SupabaseSyncManager.getInstance(context).signOut()
            } catch (_: Exception) {}

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Sign out error", e)
            Result.failure(e)
        }
    }

    private fun resolveServerClientId(activity: Activity): String {
        val configured = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (configured.isNotBlank()) return configured

        val resId = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
        if (resId != 0) {
            val fromRes = activity.getString(resId)
            if (fromRes.isNotBlank()) return fromRes
        }
        return "446316340361"
    }

    private fun generateRawNonce(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "FirebaseAuthManager"

        @Volatile
        private var instance: FirebaseAuthManager? = null

        fun getInstance(context: Context): FirebaseAuthManager {
            return instance ?: synchronized(this) {
                instance ?: FirebaseAuthManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
