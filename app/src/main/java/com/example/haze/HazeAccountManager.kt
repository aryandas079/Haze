package com.example.haze

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
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

    private const val KEY_GOOGLE_WEB_LOGIN_COMPLETED = "google_web_login_completed_v2"

    fun hasCompletedOnboarding(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    fun hasCompletedGoogleWebLogin(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GOOGLE_WEB_LOGIN_COMPLETED, false)
    }

    fun setCompletedGoogleWebLogin(context: Context, completed: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GOOGLE_WEB_LOGIN_COMPLETED, completed).apply()
    }

    private const val KEY_IS_GUEST_MODE = "is_guest_mode"
    private const val KEY_PROFILES_LIST = "profiles_list_json"

    fun isGuestMode(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_IS_GUEST_MODE, false)
    }

    fun setGuestMode(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_GUEST_MODE, enabled).apply()
        notifyAccountChanged(if (enabled) null else getUser(context))
    }

    fun isSignedIn(context: Context): Boolean {
        if (isGuestMode(context)) return false
        return getPrefs(context).getBoolean(KEY_IS_SIGNED_IN, false) && getUser(context) != null
    }

    fun getUser(context: Context): HazeUser? {
        if (isGuestMode(context)) return null
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_SIGNED_IN, false)) return null
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        var name = prefs.getString(KEY_USER_NAME, "Haze User") ?: "Haze User"
        if (name.equals("Manojdasexplorer", ignoreCase = true) || name.equals(email.substringBefore("@"), ignoreCase = true)) {
            val cachedName = prefs.getString("cached_display_name_" + email.lowercase(), null)
            if (!cachedName.isNullOrEmpty()) {
                name = cachedName
            } else if (email.equals("manojdasexplorer@gmail.com", ignoreCase = true)) {
                name = "Manoj Das"
            }
        }
        val givenName = prefs.getString(KEY_USER_GIVEN_NAME, name.split(" ").firstOrNull() ?: "User") ?: "User"
        var photoUrl = prefs.getString(KEY_USER_PHOTO_URL, null)
        if (photoUrl.isNullOrEmpty() && email.isNotEmpty()) {
            photoUrl = prefs.getString("cached_photo_url_" + email.lowercase(), null)
            if (photoUrl.isNullOrEmpty() && email.equals("manojdasexplorer@gmail.com", ignoreCase = true)) {
                photoUrl = "https://lh3.googleusercontent.com/ogw/AF2bZyju-ppFDHce-2YiY3M-W7uKD_zDPwgwASPjRIrU5twUlA=s192-c-mo"
            }
            if (!photoUrl.isNullOrEmpty()) {
                prefs.edit()
                    .putString(KEY_USER_PHOTO_URL, photoUrl)
                    .putString("cached_photo_url_" + email.lowercase(), photoUrl)
                    .apply()
                HazeProfileImageHelper.fetchAndCacheAvatar(context, photoUrl)
            }
        }
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

    fun getAllProfiles(context: Context): List<HazeUser> {
        val jsonStr = getPrefs(context).getString(KEY_PROFILES_LIST, null)
        val list = mutableListOf<HazeUser>()
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = org.json.JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        HazeUser(
                            id = obj.getString("id"),
                            email = obj.getString("email"),
                            displayName = obj.getString("displayName"),
                            givenName = obj.getString("givenName"),
                            photoUrl = if (obj.has("photoUrl") && !obj.isNull("photoUrl")) obj.getString("photoUrl") else null,
                            signedInAt = obj.optLong("signedInAt", System.currentTimeMillis())
                        )
                    )
                }
            } catch (_: Exception) {}
        }
        val currentUser = getDirectUser(context)
        if (currentUser != null && list.none { it.email.equals(currentUser.email, ignoreCase = true) }) {
            list.add(0, currentUser)
            saveAllProfiles(context, list)
        }
        return list
    }

    private fun getDirectUser(context: Context): HazeUser? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_IS_SIGNED_IN, false)) return null
        val id = prefs.getString(KEY_USER_ID, null) ?: return null
        val email = prefs.getString(KEY_USER_EMAIL, "") ?: ""
        val name = prefs.getString(KEY_USER_NAME, "Haze User") ?: "Haze User"
        val givenName = prefs.getString(KEY_USER_GIVEN_NAME, name.split(" ").firstOrNull() ?: "User") ?: "User"
        val photoUrl = prefs.getString(KEY_USER_PHOTO_URL, null)
        val signedInAt = prefs.getLong(KEY_SIGNED_IN_AT, System.currentTimeMillis())
        return HazeUser(id, email, name, givenName, photoUrl, signedInAt)
    }

    private fun saveAllProfiles(context: Context, list: List<HazeUser>) {
        val array = org.json.JSONArray()
        for (u in list) {
            val obj = org.json.JSONObject().apply {
                put("id", u.id)
                put("email", u.email)
                put("displayName", u.displayName)
                put("givenName", u.givenName)
                put("photoUrl", u.photoUrl)
                put("signedInAt", u.signedInAt)
            }
            array.put(obj)
        }
        getPrefs(context).edit().putString(KEY_PROFILES_LIST, array.toString()).apply()
    }

    fun addProfile(context: Context, user: HazeUser) {
        val list = getAllProfiles(context).filter { !it.email.equals(user.email, ignoreCase = true) }.toMutableList()
        list.add(0, user)
        saveAllProfiles(context, list)
    }

    fun cycleToNextProfile(context: Context): HazeUser? {
        val profiles = getAllProfiles(context)
        val currentGuest = isGuestMode(context)
        if (currentGuest) {
            setGuestMode(context, false)
            val first = profiles.firstOrNull()
            if (first != null) {
                saveUser(context, first)
                return first
            }
            return null
        }
        if (profiles.isEmpty()) {
            setGuestMode(context, true)
            return null
        }
        if (profiles.size == 1) {
            setGuestMode(context, true)
            return null
        }
        val currentEmail = getDirectUser(context)?.email ?: ""
        val currentIndex = profiles.indexOfFirst { it.email.equals(currentEmail, ignoreCase = true) }
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % profiles.size
        val nextUser = profiles[nextIndex]
        saveUser(context, nextUser)
        return nextUser
    }

    fun saveUser(context: Context, user: HazeUser) {
        val resolvedName = if (user.displayName.equals("Manojdasexplorer", ignoreCase = true) || user.displayName.equals(user.email.substringBefore("@"), ignoreCase = true)) {
            val cachedName = getPrefs(context).getString("cached_display_name_" + user.email.lowercase(), null)
            if (!cachedName.isNullOrEmpty()) {
                cachedName
            } else if (user.email.equals("manojdasexplorer@gmail.com", ignoreCase = true)) {
                "Manoj Das"
            } else {
                user.displayName
            }
        } else {
            user.displayName
        }
        val resolvedGiven = resolvedName.split(" ").firstOrNull() ?: user.givenName

        val resolvedPhoto = if (!user.photoUrl.isNullOrEmpty()) {
            user.photoUrl
        } else {
            getPrefs(context).getString("cached_photo_url_" + user.email.lowercase(), null)
                ?: if (user.email.equals("manojdasexplorer@gmail.com", ignoreCase = true)) "https://lh3.googleusercontent.com/ogw/AF2bZyju-ppFDHce-2YiY3M-W7uKD_zDPwgwASPjRIrU5twUlA=s192-c-mo" else null
        }

        val editor = getPrefs(context).edit()
            .putBoolean(KEY_IS_GUEST_MODE, false)
            .putBoolean(KEY_IS_SIGNED_IN, true)
            .putString(KEY_USER_ID, user.id)
            .putString(KEY_USER_EMAIL, user.email)
            .putString(KEY_USER_NAME, resolvedName)
            .putString(KEY_USER_GIVEN_NAME, resolvedGiven)
            .putString(KEY_USER_PHOTO_URL, resolvedPhoto)
            .putLong(KEY_SIGNED_IN_AT, user.signedInAt)

        if (!resolvedName.isNullOrEmpty() && !resolvedName.equals(user.email.substringBefore("@"), ignoreCase = true)) {
            editor.putString("cached_display_name_" + user.email.lowercase(), resolvedName)
        }
        if (!resolvedPhoto.isNullOrEmpty()) {
            editor.putString("cached_photo_url_" + user.email.lowercase(), resolvedPhoto)
        }
        editor.apply()

        val finalUser = user.copy(displayName = resolvedName, givenName = resolvedGiven, photoUrl = resolvedPhoto)
        addProfile(context, finalUser)
        if (!resolvedPhoto.isNullOrEmpty()) {
            HazeProfileImageHelper.fetchAndCacheAvatar(context, resolvedPhoto)
        }
        syncGoogleAccountToCookieManager(context)
        notifyAccountChanged(finalUser)
    }

    fun updateUserPhoto(context: Context, newPhotoUrl: String) {
        if (newPhotoUrl.isBlank()) return
        val current = getUser(context) ?: return
        if (current.photoUrl == newPhotoUrl) return
        val updated = current.copy(photoUrl = newPhotoUrl)
        saveUser(context, updated)
    }

    fun syncGoogleAccountToCookieManager(context: Context) {
        val user = getUser(context) ?: return
        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            val expires = "Fri, 31 Dec 2038 23:59:59 GMT"
            cookieManager.setCookie("https://.google.com", "ACCOUNT_CHOOSER=${android.net.Uri.encode(user.email)}; Domain=.google.com; Path=/; Secure; SameSite=None; Expires=$expires")
            cookieManager.setCookie("https://accounts.google.com", "Email=${android.net.Uri.encode(user.email)}; Domain=accounts.google.com; Path=/; Secure; SameSite=None; Expires=$expires")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.flush()
            }
        } catch (_: Exception) {}
    }

    fun clearUser(context: Context) {
        val currentEmail = getDirectUser(context)?.email
        val updatedProfiles = getAllProfiles(context).filter { !it.email.equals(currentEmail, ignoreCase = true) }
        saveAllProfiles(context, updatedProfiles)

        getPrefs(context).edit()
            .putBoolean(KEY_IS_SIGNED_IN, false)
            .putBoolean(KEY_IS_GUEST_MODE, false)
            .remove(KEY_USER_ID)
            .remove(KEY_USER_EMAIL)
            .remove(KEY_USER_NAME)
            .remove(KEY_USER_GIVEN_NAME)
            .remove(KEY_USER_PHOTO_URL)
            .remove(KEY_SIGNED_IN_AT)
            .remove(KEY_GOOGLE_WEB_LOGIN_COMPLETED)
            .apply()

        notifyAccountChanged(null)
    }

    fun getGoogleSignInClient(activity: Activity): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        return GoogleSignIn.getClient(activity, gso)
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
            if (!user.photoUrl.isNullOrEmpty()) {
                HazeProfileImageHelper.fetchAndCacheAvatar(context, user.photoUrl)
            }
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
            val lastGoogleAccount = if (context is Activity) GoogleSignIn.getLastSignedInAccount(context) else null
            val photoUrl = if (lastGoogleAccount != null && lastGoogleAccount.email.equals(accountName, ignoreCase = true)) {
                lastGoogleAccount.photoUrl?.toString()
            } else {
                null
            }
            val user = HazeUser(
                id = "google_$accountName",
                email = accountName,
                displayName = capitalized,
                givenName = givenName,
                photoUrl = photoUrl,
                signedInAt = System.currentTimeMillis()
            )
            saveUser(context, user)
            val savedUser = getUser(context) ?: user
            setOnboardingCompleted(context, true)
            onSuccess(savedUser)

            if (savedUser.photoUrl.isNullOrEmpty() && context is Activity) {
                silentSignIn(context) { updated ->
                    if (updated?.photoUrl != null && context is MainActivity) {
                        context.updateProfileButton()
                    }
                }
            }
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
            val photoUrl = lastAccount.photoUrl?.toString()
            val existing = getUser(activity)
            val user = HazeUser(
                id = lastAccount.id ?: lastAccount.email ?: (existing?.id ?: "google_user"),
                email = lastAccount.email ?: (existing?.email ?: ""),
                displayName = lastAccount.displayName ?: (existing?.displayName ?: givenName),
                givenName = givenName,
                photoUrl = photoUrl ?: existing?.photoUrl,
                signedInAt = existing?.signedInAt ?: System.currentTimeMillis()
            )
            saveUser(activity, user)
            if (!user.photoUrl.isNullOrEmpty()) {
                HazeProfileImageHelper.fetchAndCacheAvatar(activity, user.photoUrl)
            }
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
                val photoUrl = account.photoUrl?.toString()
                val existing = getUser(activity)
                val user = HazeUser(
                    id = account.id ?: account.email ?: (existing?.id ?: "google_user"),
                    email = account.email ?: (existing?.email ?: ""),
                    displayName = account.displayName ?: (existing?.displayName ?: givenName),
                    givenName = givenName,
                    photoUrl = photoUrl ?: existing?.photoUrl,
                    signedInAt = existing?.signedInAt ?: System.currentTimeMillis()
                )
                saveUser(activity, user)
                if (!user.photoUrl.isNullOrEmpty()) {
                    HazeProfileImageHelper.fetchAndCacheAvatar(activity, user.photoUrl)
                }
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
