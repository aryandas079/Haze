package com.example.haze

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import com.google.android.gms.common.api.ApiException
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

object HazeAccountManager {

    private const val PREFS_NAME = "HazeAccountPrefs"
    private const val KEY_IS_SIGNED_IN = "is_signed_in"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_USER_NAME = "user_name"
    private const val KEY_USER_GIVEN_NAME = "user_given_name"
    private const val KEY_USER_PHOTO_URL = "user_photo_url"
    private const val KEY_SIGNED_IN_AT = "signed_in_at"
    private const val KEY_ONBOARDING_COMPLETED = "has_completed_onboarding"

    data class HazeUser(
        val id: String,
        val email: String,
        val displayName: String,
        val givenName: String,
        val photoUrl: String?,
        val signedInAt: Long = System.currentTimeMillis()
    )

    private val accountListeners = CopyOnWriteArrayList<(HazeUser?) -> Unit>()

    fun addAccountListener(listener: (HazeUser?) -> Unit) {
        accountListeners.add(listener)
    }

    fun removeAccountListener(listener: (HazeUser?) -> Unit) {
        accountListeners.remove(listener)
    }

    private fun notifyAccountChanged(user: HazeUser?) {
        for (listener in accountListeners) {
            try {
                listener.invoke(user)
            } catch (e: Exception) {
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun hasCompletedOnboarding(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun isSignedIn(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_SIGNED_IN, false) && getUser(context) != null
    }

    fun getUser(context: Context): HazeUser? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_SIGNED_IN, false)) return null
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val name = prefs.getString(KEY_USER_NAME, "Haze User") ?: "Haze User"
        val givenName = prefs.getString(KEY_USER_GIVEN_NAME, name.split(" ").firstOrNull() ?: "User") ?: "User"
        val photoUrl = prefs.getString(KEY_USER_PHOTO_URL, null)
        val signedInAt = prefs.getLong(KEY_SIGNED_IN_AT, System.currentTimeMillis())

        return HazeUser(
            id = id,
            email = email,
            displayName = name,
            givenName = givenName,
            photoUrl = photoUrl,
            signedInAt = signedInAt
        )
    }

    fun saveUser(context: Context, user: HazeUser) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_SIGNED_IN, true)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_NAME, user.displayName)
            .putString(KEY_USER_GIVEN_NAME, user.givenName)
            .putString(KEY_USER_PHOTO_URL, user.photoUrl)
            .putLong(KEY_SIGNED_IN_AT, user.signedInAt)
            .apply()

        notifyAccountChanged(user)
    }

    fun clearUser(context: Context) {
        getPrefs(context).edit()
            .putBoolean(KEY_IS_SIGNED_IN, false)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_GIVEN_NAME)
            .remove(KEY_USER_PHOTO_URL)
            .remove(KEY_SIGNED_IN_AT)
            .apply()

        HazeProfileImageHelper.clearAvatar(context)
        notifyAccountChanged(null)
    }

    const val WEB_CLIENT_ID = "1062570508044-gjjqql7b8qshahah65jv459t5aj78kpn.apps.googleusercontent.com"

    fun getGoogleSignInClient(activity: Activity): GoogleSignInClient {
        return try {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(WEB_CLIENT_ID)
                .requestEmail()
                .requestProfile()
                .build()
            GoogleSignIn.getClient(activity, gso)
        } catch (e: Exception) {
            val fallbackGso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestProfile()
                .build()
            GoogleSignIn.getClient(activity, fallbackGso)
        }
    }

    fun handleSignInIntentResult(
        context: Context,
        data: Intent?,
        onSuccess: (HazeUser) -> Unit,
        onError: (String) -> Unit
    ) {
        if (data == null) {
            onError("Sign-in request was cancelled.")
            return
        }

        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account: GoogleSignInAccount = task.getResult(ApiException::class.java)
            val givenName = account.givenName
                ?: account.displayName?.split(" ")?.firstOrNull()
                ?: "User"

            val user = HazeUser(
                id = account.id ?: account.email ?: "google_user_${System.currentTimeMillis()}",
                email = account.email ?: "",
                displayName = account.displayName ?: givenName,
                givenName = givenName,
                photoUrl = account.photoUrl?.toString(),
                signedInAt = System.currentTimeMillis()
            )

            saveUser(context, user)
            setOnboardingCompleted(context, true)
            onSuccess(user)
        } catch (e: ApiException) {
            if (e.statusCode == 10 || e.statusCode == 12500) {
                if (context is MainActivity) {
                    context.launchDeviceAccountPicker()
                    return
                }
            }
            val errorMsg = when (e.statusCode) {
                GoogleSignInStatusCodes.SIGN_IN_CANCELLED -> "Sign-in was cancelled."
                GoogleSignInStatusCodes.NETWORK_ERROR -> "Network error during Google sign-in. Check your connection."
                GoogleSignInStatusCodes.SIGN_IN_CURRENTLY_IN_PROGRESS -> "Sign-in already in progress."
                10 -> "Google Cloud OAuth configuration required for this app."
                12500 -> "Google Sign-In failed (Code 12500). Please retry."
                else -> "Google Sign-In failed (${e.statusCode}): ${e.message}"
            }
            onError(errorMsg)
        } catch (e: Exception) {
            onError("Authentication error: ${e.localizedMessage}")
        }
    }

    fun getDeviceAccountPickerIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                null,
                null,
                null,
                null
            )
        } else {
            @Suppress("DEPRECATION")
            AccountManager.newChooseAccountIntent(
                null,
                null,
                arrayOf("com.google"),
                false,
                null,
                null,
                null,
                null
            )
        }
    }

    fun handleDeviceAccountPickerResult(
        context: Context,
        data: Intent?,
        onSuccess: (HazeUser) -> Unit,
        onError: (String) -> Unit
    ) {
        val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (!accountName.isNullOrEmpty()) {
            val namePart = accountName.substringBefore("@").replace(".", " ")
            val capitalized = namePart.split(" ").joinToString(" ") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            val givenName = capitalized.split(" ").firstOrNull() ?: "User"
            val user = HazeUser(
                id = "google_$accountName",
                email = accountName,
                displayName = capitalized,
                givenName = givenName,
                photoUrl = null,
                signedInAt = System.currentTimeMillis()
            )
            saveUser(context, user)
            setOnboardingCompleted(context, true)
            onSuccess(user)
        } else {
            onError("No Google account selected.")
        }
    }

    fun silentSignIn(activity: Activity, onResult: (HazeUser?) -> Unit) {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(activity)
        if (lastAccount != null) {
            val givenName = lastAccount.givenName
                ?: lastAccount.displayName?.split(" ")?.firstOrNull()
                ?: "User"
            val user = HazeUser(
                id = lastAccount.id ?: lastAccount.email ?: "google_user",
                email = lastAccount.email ?: "",
                displayName = lastAccount.displayName ?: givenName,
                givenName = givenName,
                photoUrl = lastAccount.photoUrl?.toString(),
                signedInAt = System.currentTimeMillis()
            )
            saveUser(activity, user)
            onResult(user)
            return
        }

        val client = getGoogleSignInClient(activity)
        client.silentSignIn().addOnCompleteListener(activity) { task ->
            if (task.isSuccessful) {
                val account = task.result
                val givenName = account.givenName
                    ?: account.displayName?.split(" ")?.firstOrNull()
                    ?: "User"
                val user = HazeUser(
                    id = account.id ?: account.email ?: "google_user",
                    email = account.email ?: "",
                    displayName = account.displayName ?: givenName,
                    givenName = givenName,
                    photoUrl = account.photoUrl?.toString(),
                    signedInAt = System.currentTimeMillis()
                )
                saveUser(activity, user)
                onResult(user)
            } else {
                // Return cached user if any, or null
                onResult(getUser(activity))
            }
        }
    }

    fun signOut(
        activity: Activity,
        keepLocalData: Boolean,
        onComplete: () -> Unit
    ) {
        val client = getGoogleSignInClient(activity)
        client.signOut().addOnCompleteListener(activity) {
            val prevUser = getUser(activity)
            clearUser(activity)

            if (!keepLocalData && prevUser != null) {
                // Clear synced data as explicitly requested
                HazeSyncManager.clearLocalSyncedData(activity)
            }

            onComplete()
        }
    }

    fun revokeAccess(activity: Activity, onComplete: () -> Unit) {
        val client = getGoogleSignInClient(activity)
        client.revokeAccess().addOnCompleteListener(activity) {
            clearUser(activity)
            onComplete()
        }
    }
}
