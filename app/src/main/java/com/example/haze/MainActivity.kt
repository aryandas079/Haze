package com.example.haze

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.FileProvider
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.util.Rational
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val googleTtsEngine = "com.google.android.tts"
    private var pendingPasswordUnlock: (() -> Unit)? = null
    private val passwordUnlockLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingPasswordUnlock
        pendingPasswordUnlock = null
        if (result.resultCode == RESULT_OK) callback?.invoke()
        else Toast.makeText(this, "Password access cancelled", Toast.LENGTH_SHORT).show()
    }
    private val passwordImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        Thread {
            val count = runCatching { HazePasswordManager.importCsv(this, uri) }.getOrElse { -1 }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (count >= 0) "$count passwords imported securely" else "Could not import that file",
                    Toast.LENGTH_LONG
                ).show()
                if (count >= 0) {
                    showPasswordManagerList()
                }
            }
        }.start()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun launchPasswordImport() {
        passwordImportLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
    }

    private fun showPasswordManagerList() {
        HazePasswordManager.show(this) { launchPasswordImport() }
    }

    fun requestPasswordUnlock(item: PasswordItem, onUnlocked: () -> Unit) {
        val keyguard = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguard.isKeyguardSecure) {
            AlertDialog.Builder(this)
                .setTitle("Set a screen lock")
                .setMessage("Haze uses your Android screen lock to protect saved passwords. Set a PIN, pattern, or password in system settings first.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Open settings") { _, _ ->
                    startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
                }
                .show()
            return
        }
        pendingPasswordUnlock = onUnlocked
        val intent = keyguard.createConfirmDeviceCredentialIntent(
            "Verify to open password",
            item.title.ifBlank { "Haze Password Manager" }
        )
        if (intent == null) {
            pendingPasswordUnlock = null
            Toast.makeText(this, "System lock is unavailable", Toast.LENGTH_LONG).show()
        } else {
            passwordUnlockLauncher.launch(intent)
        }
    }
    private var pageLoadingGeneration = 0
    private val suggestionExecutor = Executors.newFixedThreadPool(2)
    private val suggestionCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                return size > 64
            }
        }
    )
    private var suggestionRequestId = 0L
    private val suggestionDebounce = Runnable {
        val query = addressBar.text.toString().trim()
        if (isSearchActive && query.isNotEmpty()) {
            val requestId = ++suggestionRequestId
            fetchSearchSuggestions(query) { suggestions ->
                if (requestId == suggestionRequestId &&
                    isSearchActive &&
                    addressBar.text.toString().trim() == query
                ) {
                    renderSearchSuggestions(query, suggestions)
                }
            }
        }

    }

    private lateinit var webContainer: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var tabCountBtn: TextView
    private lateinit var btnTabsCircular: FrameLayout
    private lateinit var addressBarContainer: FrameLayout
    private lateinit var findBarContainer: LinearLayout
    private lateinit var findInput: EditText

    private lateinit var dimOverlay: View
    private lateinit var recentSearchesContainer: LinearLayout
    private lateinit var recentSearchesList: LinearLayout
    private lateinit var btnClearQuery: ImageButton
    private var isSearchActive = false

    private lateinit var floatingNavContainer: LinearLayout
    private lateinit var btnFloatingBack: FrameLayout
    private lateinit var ivFloatingBackIcon: ImageView
    private lateinit var btnFloatingForward: FrameLayout
    private lateinit var ivFloatingForwardIcon: ImageView
    private lateinit var floatingNavDivider: View

    private lateinit var topRightActions: LinearLayout
    lateinit var btnProfile: FrameLayout
    private lateinit var ivProfileAvatar: ImageView

    // Picture-in-Picture / Miniplayer & Media State
    var isMediaPlaying = false
    private var lastVideoWidth = 16
    private var lastVideoHeight = 9
    private var customVideoView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private lateinit var fullscreenVideoContainer: FrameLayout
    private val ACTION_PIP_PLAY_PAUSE = "com.example.haze.ACTION_PIP_PLAY_PAUSE"

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_PLAY_PAUSE) {
                toggleMediaPlayback()
            }
        }
    }

    private var onGoogleSignInSuccess: ((HazeAccountManager.HazeUser) -> Unit)? = null
    private var onGoogleSignInError: ((String) -> Unit)? = null

    val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        HazeAccountManager.handleSignInIntentResult(
            this,
            result.data,
            onSuccess = { user ->
                updateProfileButton()
                onGoogleSignInSuccess?.invoke(user)
            },
            onError = { err ->
                onGoogleSignInError?.invoke(err)
            }
        )
    }

    fun launchGoogleSignIn(
        onSuccess: (HazeAccountManager.HazeUser) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onGoogleSignInSuccess = onSuccess
        this.onGoogleSignInError = onError
        try {
            val client = HazeAccountManager.getGoogleSignInClient(this)
            googleSignInLauncher.launch(client.getSignInIntent())
        } catch (e: Exception) {
            launchDeviceAccountPicker()
        }
    }

    val deviceAccountPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            HazeAccountManager.handleDeviceAccountPickerResult(
                this,
                result.data,
                onSuccess = { user ->
                    updateProfileButton()
                    onGoogleSignInSuccess?.invoke(user)
                },
                onError = { err ->
                    onGoogleSignInError?.invoke(err)
                }
            )
        } else {
            onGoogleSignInError?.invoke("Account selection was cancelled.")
        }
    }

    fun launchDeviceAccountPicker() {
        try {
            val intent = HazeAccountManager.getDeviceAccountPickerIntent()
            deviceAccountPickerLauncher.launch(intent)
        } catch (e: Exception) {
            onGoogleSignInError?.invoke("Could not open account picker: ${e.message}")
        }
    }

    fun updateProfileButton() {
        runOnUiThread {
            if (!::btnProfile.isInitialized || !::ivProfileAvatar.isInitialized) return@runOnUiThread
            val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
            val user = HazeAccountManager.getUser(this)

            if (user != null && !isPrivateMode) {
                ivProfileAvatar.clearColorFilter()
                ivProfileAvatar.scaleType = ImageView.ScaleType.CENTER_CROP
                ivProfileAvatar.setPadding(0, 0, 0, 0)
                HazeProfileImageHelper.loadAvatar(
                    this,
                    user.photoUrl,
                    user.givenName,
                    ivProfileAvatar,
                    dpToPx(38)
                )
            } else {
                ivProfileAvatar.clearColorFilter()
                ivProfileAvatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = dpToPx(5)
                ivProfileAvatar.setPadding(p, p, p, p)
                ivProfileAvatar.setImageResource(R.drawable.ic_person)
                ivProfileAvatar.setColorFilter(if (isDark) Color.parseColor("#CCCCCC") else Color.parseColor("#5F6368"))
            }
        }
    }

    fun getActiveTab(): Tab? = currentTabsList.getOrNull(activeTabIndex)

    private lateinit var btnPrivacyShield: FrameLayout
    private lateinit var ivPrivacyShieldIcon: ImageView
    private lateinit var shieldBadgeDot: View

    fun updatePrivacyShieldButton() {
        runOnUiThread {
            if (!::btnPrivacyShield.isInitialized || !::ivPrivacyShieldIcon.isInitialized || !::shieldBadgeDot.isInitialized) return@runOnUiThread
            val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
            val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

            val totalBlocked = HazePrivacyShieldEngine.getTotalBlocked(this)
            val isEnabled = HazePrivacyShieldEngine.isShieldEnabled(this)

            ivPrivacyShieldIcon.setColorFilter(
                if (!isEnabled) Color.parseColor("#9AA0A6")
                else accentColor
            )

            val dotShape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accentColor)
            }
            shieldBadgeDot.background = dotShape

            shieldBadgeDot.visibility = if (totalBlocked > 0L && isEnabled) View.VISIBLE else View.GONE
        }
    }

    fun updateNavigationButtons() {
        // Floating navigation capsule removed per user request for a cleaner UI
    }

    private lateinit var prefs: SharedPreferences
    private var miniPlayerManager: HazeMiniPlayer? = null
    private var miniPlayerTab: Tab? = null
    private var activeMediaTab: Tab? = null

    private fun showMiniPlayer(tab: Tab, createBrowsingTab: Boolean) {
        if (isHomeTab(tab) || miniPlayerManager?.isActive == true) return
        miniPlayerTab = tab
        miniPlayerManager?.attach(tab.webView, tab.title)
        if (createBrowsingTab) addNewTab(HAZE_HOME_URL)
    }

    data class TabGroup(var id: String, var name: String, var color: Int)
    data class Shortcut(val name: String, val url: String, val iconUrl: String = "", val svgPath: String = "", val fillColor: String = "")
    data class Tab(
        val id: Long = System.currentTimeMillis(),
        val webView: WebView,
        val swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null,
        var title: String = "New Tab",
        var url: String = "haze://home",
        var favicon: Bitmap? = null,
        var isPrivate: Boolean = false,
        var group: TabGroup? = null,
        var isDesktop: Boolean = false
    )

    private val normalTabs = mutableListOf<Tab>()
    private val privateTabs = mutableListOf<Tab>()
    var isPrivateMode = false
    private var isFullScreenMode = false
    
    private val currentTabsList: MutableList<Tab>
        get() = if (isPrivateMode) privateTabs else normalTabs

    private var activeNormalTabIndex = -1
    private var activePrivateTabIndex = -1
    private val activeTabIndex: Int
        get() = if (isPrivateMode) activePrivateTabIndex else activeNormalTabIndex

    private val groupColors = listOf(
        Color.parseColor("#EA4335"), // Red
        Color.parseColor("#4285F4"), // Blue
        Color.parseColor("#34A853"), // Green
        Color.parseColor("#FBBC05"), // Yellow
        Color.parseColor("#FF6D01"), // Orange
        Color.parseColor("#46BDC6"), // Cyan
        Color.parseColor("#9C27B0")  // Purple
    )

    object DefaultShortcuts {
        val list = listOf(
            Shortcut("Google", "https://www.google.com", "https://www.google.com/s2/favicons?domain=google.com&sz=128", "M21.35,11.1H12.18V13.83H18.69C18.36,17.64 15.19,19.27 12.19,19.27C8.36,19.27 5,16.25 5,12C5,7.9 8.2,4.73 12.2,4.73C15.29,4.73 17.1,6.7 17.1,6.7L19,4.72C19,4.72 16.56,2 12.1,2C6.42,2 2.03,6.8 2.03,12C2.03,17.05 6.16,22 12.25,22C17.6,22 21.5,18.33 21.5,12.91C21.5,11.76 21.35,11.1 21.35,11.1V11.1Z", "#4285F4"),
            Shortcut("YouTube", "https://www.youtube.com", "https://www.google.com/s2/favicons?domain=youtube.com&sz=128", "M21.58,7.19C21.35,6.33 20.67,5.65 19.81,5.42C18.25,5 12,5 12,5C12,5 5.75,5 4.19,5.42C3.33,5.65 2.65,6.33 2.42,7.19C2,8.75 2,12 2,12C2,12 2,15.25 2.42,16.81C2.65,17.67 3.33,18.35 4.19,18.58C5.75,19 12,19 12,19C12,19 18.25,19 19.81,18.58C20.67,18.35 21.35,17.67 21.58,16.81C22,15.25 22,12 22,12C22,12 22,8.75 21.58,7.19Z", "#FF0000"),
            Shortcut("GitHub", "https://github.com", "https://www.google.com/s2/favicons?domain=github.com&sz=128", "M12,2A10,10 0 0,0 8.84,21.5C9.34,21.58 9.5,21.27 9.5,21V19.31C6.73,19.91 6.14,17.97 6.14,17.97C5.68,16.81 5.03,16.5 5.03,16.5C4.12,15.88 5.1,15.9 5.1,15.9C6.1,15.97 6.63,16.93 6.63,16.93C7.5,18.45 8.97,18 9.54,17.76C9.63,17.11 9.89,16.67 10.17,16.42C7.95,16.17 5.62,15.31 5.62,11.5C5.62,10.39 6,9.5 6.65,8.79C6.55,8.54 6.2,7.5 6.75,6.15C6.75,6.15 7.59,5.88 9.5,7.17C10.29,6.95 11.15,6.84 12,6.84C12.85,6.84 13.71,6.95 14.5,7.17C16.41,5.88 17.25,6.15 17.25,6.15C17.8,7.5 17.45,8.54 17.35,8.79C18,9.5 18.38,10.39 18.38,11.5C18.38,15.32 16.04,16.16 13.81,16.41C14.17,16.72 14.5,17.33 14.5,18.26V21C14.5,21.27 14.66,21.59 15.17,21.5A10,10 0 0,0 12,2Z", "#202124"),
            Shortcut("Notion", "https://www.notion.so", "https://www.google.com/s2/favicons?domain=notion.so&sz=128", "M5,4H19V20H5V4M7,6V18H17V6H7M9,8H15V10H9V8M9,12H15V14H9V12M9,16H13V18H9V16Z", "#202124"),
            Shortcut("Twitter", "https://twitter.com", "https://www.google.com/s2/favicons?domain=twitter.com&sz=128", "M18.244 2.25h3.308l-7.227 8.26 8.502 11.24H16.17l-5.214-6.817L4.99 21.75H1.68l7.73-8.835L1.254 2.25H8.08l4.713 6.231zm-1.161 17.52h1.833L7.084 4.126H5.117z", "#1DA1F2"),
            Shortcut("Instagram", "https://instagram.com", "https://www.google.com/s2/favicons?domain=instagram.com&sz=128", "M12 2.163c3.204 0 3.584.012 4.85.07 3.252.148 4.771 1.691 4.919 4.919.058 1.265.069 1.645.069 4.849 0 3.205-.012 3.584-.069 4.849-.149 3.225-1.664 4.771-4.919 4.919-1.266.058-1.644.07-4.85.07-3.204 0-3.584-.012-4.849-.07-3.26-.149-4.771-1.699-4.919-4.92-.058-1.265-.07-1.644-.07-4.849 0-3.204.013-3.583.07-4.849.149-3.227 1.664-4.771 4.919-4.919 1.266-.057 1.645-.069 4.849-.069zm0-2.163c-3.259 0-3.667.014-4.947.072-4.358.2-6.78 2.618-6.98 6.98-.059 1.281-.073 1.689-.073 4.948 0 3.259.014 3.668.072 4.948.2 4.358 2.618 6.78 6.98 6.98 1.281.058 1.689.072 4.948.072 3.259 0 3.668-.014 4.948-.072 4.354-.2 6.782-2.618 6.979-6.98.059-1.28.073-1.689.073-4.948 0-3.259-.014-3.667-.072-4.947-.196-4.354-2.617-6.78-6.979-6.98-1.281-.059-1.69-.073-4.949-.073zm0 5.838c-3.403 0-6.162 2.759-6.162 6.162s2.759 6.163 6.162 6.163 6.162-2.759 6.162-6.163c0-3.403-2.759-6.162-6.162-6.162zm0 10.162c-2.209 0-4-1.79-4-4 0-2.209 1.791-4 4-4s4 1.791 4 4c0 2.21-1.791 4-4 4zm6.406-11.845c-.796 0-1.441.645-1.441 1.44s.645 1.44 1.441 1.44c.795 0 1.439-.645 1.439-1.44s-.644-1.44-1.439-1.44z", "#E1306C"),
            Shortcut("Reddit", "https://www.reddit.com", "https://www.google.com/s2/favicons?domain=reddit.com&sz=128", "M12 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0zm5.01 4.744c.688 0 1.25.561 1.25 1.249a1.25 1.25 0 0 1-2.498.056l-2.597-.547-.8 3.747c1.824.07 3.48.632 4.674 1.488.308-.221.685-.356 1.093-.356.999 0 1.81.811 1.81 1.81 0 .633-.323 1.189-.81 1.512.03.208.051.42.051.633 0 4.316-5.013 7.82-11.196 7.82s-11.196-3.504-11.196-7.82c0-.213.02-.425.05-.633-.487-.323-.81-.879-.81-1.512 0-.999.811-1.81 1.81-1.81.408 0 .785.135 1.093.356 1.194-.856 2.85-1.418 4.674-1.488l.8-3.747 2.597.547a1.25 1.25 0 0 1 2.498-.056l.547-2.556a.625.625 0 0 1 .75-.484l3.14.662a1.248 1.248 0 0 1 1.205-.884z", "#FF4500"),
            Shortcut("Spotify", "https://spotify.com", "https://www.google.com/s2/favicons?domain=spotify.com&sz=128", "M12 0C5.4 0 0 5.4 0 12s5.4 12 12 12 12-5.4 12-12S18.66 0 12 0zm5.521 17.34c-.24.359-.66.48-1.021.24-2.82-1.74-6.36-2.101-10.561-1.141-.418.122-.778-.179-.899-.599-.12-.42.18-.78.6-.9 4.56-1.02 8.52-.6 11.76 1.44.36.18.48.66.24 1.02zm1.44-3.84c-.301.481-.921.601-1.401.3-3.24-1.98-8.16-2.58-11.94-1.38-.54.18-1.14-.12-1.32-.66-.18-.54.12-1.14.66-1.32 4.38-1.32 9.84-.66 13.68 1.62.48.3.66.96.3 1.44zm.12-4.08C15.24 7.32 8.88 7.08 5.16 8.22c-.66.2-1.38-.16-1.58-.82-.2-.66.16-1.38.82-1.58 4.32-1.32 11.4-1.02 15.66 1.5.6.36.78 1.14.42 1.74-.36.6-1.14.78-1.74.42z", "#1DB954"),
            Shortcut("Wikipedia", "https://www.wikipedia.org", "https://www.google.com/s2/favicons?domain=wikipedia.org&sz=128", "M12.09,2.15C11.96,2.15 11.83,2.23 11.78,2.37L10.27,6.86C8.84,6.44 7.42,6.03 10,6.03C10,6.03 6.9,6.5 5.5,7C4.1,7.5 3.03,8.6 2.5,10C1.97,11.4 1.7,13.2 1.5,15.5C1.3,17.8 1.1,20.5 1,22.5C2.5,22.5 4,22.5 5.5,22.5C5.7,20.5 5.9,18 6.1,15.5C6.3,13 6.5,11.2 7,10.2C7.5,9.2 8.8,8.8 9.1,8.6C10,8.4 11.1,8.7 12.3,9.5L14,14.5L16.2,9.2C17.5,8.9 18.8,8.8 20,8.8C20.5,8.8 21,8.9 21.5,9C21.7,10.5 21.9,12.5 22.1,15C22.3,17.5 22.5,20 22.7,22.5C24.2,22.5 25.7,22.5 27.2,22.5C27.1,20.5 26.9,17.8 26.7,15.5C26.5,13.2 26.2,11.4 25.7,10C25.2,8.6 24.1,7.5 22.7,7C21.3,6.5 19.8,6.2 18.3,6.1L16.8,2.37C16.75,2.23 16.62,2.15 16.49,2.15C16.36,2.15 16.23,2.23 16.18,2.37L14,9.5L12.09,2.37C12.04,2.23 11.91,2.15 12.09,2.15Z", "#202124")
        )
    }

    private val defaultShortcuts = DefaultShortcuts.list

    private val HAZE_HOME_URL = "haze://home"

    private val voiceSearchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!matches.isNullOrEmpty()) {
                val query = matches[0]
                addressBar.setText(query)
                loadUrlFromInput(query)
            }
        }
    }

    fun triggerStrongHaptic(view: View? = null, isHeavy: Boolean = false) {
        try {
            view?.performHapticFeedback(
                if (isHeavy) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val duration = if (isHeavy) 80L else 35L
                    val amplitude = if (isHeavy) 255 else 190
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(if (isHeavy) 80L else 35L)
                }
            }
        } catch (e: Exception) {}
    }

    inner class WebAppInterface(private val ownerWebView: WebView) {
        @JavascriptInterface
        fun triggerHaptic(heavy: Boolean) {
            runOnUiThread { triggerStrongHaptic(isHeavy = heavy) }
        }
        @JavascriptInterface
        fun focusAddressBar() {
            runOnUiThread {
                activateSearchMode()
            }
        }
        @JavascriptInterface
        fun startVoiceSearch() { runOnUiThread { this@MainActivity.startVoiceSearch() } }
        @JavascriptInterface
        fun launchMiniPlayer() { 
            runOnUiThread { 
                val tab = getTabFromWebView(ownerWebView) ?: return@runOnUiThread
                showMiniPlayer(tab, createBrowsingTab = true)
            }
        }
        @JavascriptInterface
        fun navigateTo(url: String) { 
            runOnUiThread { 
                addressBarContainer.visibility = View.VISIBLE
                addressBar.setText(url)
                loadUrlFromInput(url) 
            } 
        }
        @JavascriptInterface
        fun openMenu() { runOnUiThread { showBrowserMenu() } }
        @JavascriptInterface
        fun openTabManager() { runOnUiThread { showFullScreenTabs() } }
        @JavascriptInterface
        fun openProfile() {
            runOnUiThread {
                triggerStrongHaptic(isHeavy = false)
                HazeProfileDialogManager.showProfilePanel(this@MainActivity)
            }
        }
        @JavascriptInterface
        fun openPrivacyShield() {
            runOnUiThread {
                triggerStrongHaptic(isHeavy = false)
                HazePrivacyShieldDashboard.showDashboard(this@MainActivity)
            }
        }
        @JavascriptInterface
        fun bypassSecurityWarning(url: String) {
            runOnUiThread {
                try {
                    val host = Uri.parse(url).host
                    if (host != null) {
                        HazeDatabase.getInstance(this@MainActivity).setDomainWhitelisted(host, true)
                    }
                    loadUrlFromInput(url)
                } catch (e: Exception) {
                    loadUrlFromInput(url)
                }
            }
        }
        @JavascriptInterface
        fun getTabCount(): Int { return currentTabsList.size }
        @JavascriptInterface
        fun openAddShortcutDialog() { runOnUiThread { showAddShortcutDialog() } }
        @JavascriptInterface
        fun confirmRemoveShortcut(index: Int) { runOnUiThread { showRemoveShortcutDialog(index) } }
        @JavascriptInterface
        fun showMaxShortcutsToast() {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Maximum shortcuts reached. Long-press an existing shortcut to remove it.", Toast.LENGTH_LONG).show()
            }
        }
        @JavascriptInterface
        fun onTextSelection(text: String) {
            runOnUiThread {
                if (text.trim().isNotEmpty()) {
                    HazeContextMenuManager.showTextSelectionContextMenu(this@MainActivity, text.trim())
                }
            }
        }
        @JavascriptInterface
        fun onMediaStateChange(isPlaying: Boolean, width: Int, height: Int) {
            runOnUiThread {
                isMediaPlaying = isPlaying
                if (isPlaying) activeMediaTab = getTabFromWebView(ownerWebView)
                else if (activeMediaTab?.webView == ownerWebView) activeMediaTab = null
                if (width > 0 && height > 0) {
                    lastVideoWidth = width
                    lastVideoHeight = height
                }
                updatePipAutoEnter()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("HazePrefs", MODE_PRIVATE)

        webContainer = findViewById(R.id.webContainer)
        fullscreenVideoContainer = findViewById(R.id.fullscreenVideoContainer)
        try {
            ContextCompat.registerReceiver(
                this,
                pipReceiver,
                IntentFilter(ACTION_PIP_PLAY_PAUSE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        addressBar = findViewById(R.id.addressBar)
        tabCountBtn = findViewById(R.id.tabCountBtn)
        btnTabsCircular = findViewById(R.id.btnTabsCircular)
        addressBarContainer = findViewById(R.id.addressBarContainer)
        val menuBtn: ImageButton = findViewById(R.id.menuBtn)
        val btnVoiceSearch: ImageButton = findViewById(R.id.btnVoiceSearch)

        dimOverlay = findViewById(R.id.dimOverlay)
        recentSearchesContainer = findViewById(R.id.recentSearchesContainer)
        recentSearchesList = findViewById(R.id.recentSearchesList)
        btnClearQuery = findViewById(R.id.btnClearQuery)
        val btnClearHistory = findViewById<TextView>(R.id.btnClearHistory)

        dimOverlay.setOnClickListener { deactivateSearchMode() }
        btnClearHistory.setOnClickListener {
            triggerStrongHaptic(it)
            clearRecentSearches()
        }

        btnClearQuery.setOnClickListener {
            triggerStrongHaptic(it)
            addressBar.setText("")
            addressBar.requestFocus()
        }

        addressBar.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString() ?: ""
                val trimmed = text.trim()
                btnClearQuery.visibility = if (text.isNotEmpty() && isSearchActive) View.VISIBLE else View.GONE
                
                if (isSearchActive) {
                    if (trimmed.isEmpty()) {
                        suggestionRequestId++
                        mainHandler.removeCallbacks(suggestionDebounce)
                        renderRecentSearches()
                    } else {
                        mainHandler.removeCallbacks(suggestionDebounce)
                        mainHandler.postDelayed(suggestionDebounce, 150L)
                    }
                } else {
                    currentTabsList.getOrNull(activeTabIndex)?.webView?.findAllAsync(text)
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        addressBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                triggerStrongHaptic(addressBar)
                activateSearchMode()
            }
        }

        addressBar.setOnClickListener {
            triggerStrongHaptic(it)
            activateSearchMode()
        }

        findBarContainer = findViewById(R.id.findBarContainer)
        findInput = findViewById(R.id.findInput)
        val btnFindPrev: ImageButton = findViewById(R.id.btnFindPrev)
        val btnFindNext: ImageButton = findViewById(R.id.btnFindNext)
        val btnFindClose: ImageButton = findViewById(R.id.btnFindClose)

        btnFindClose.setOnClickListener {
            triggerStrongHaptic(it)
            findBarContainer.visibility = View.GONE
            currentTabsList.getOrNull(activeTabIndex)?.webView?.clearMatches()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(findInput.windowToken, 0)
        }
        btnFindNext.setOnClickListener { 
            triggerStrongHaptic(it)
            currentTabsList.getOrNull(activeTabIndex)?.webView?.findNext(true) 
        }
        btnFindPrev.setOnClickListener { 
            triggerStrongHaptic(it)
            currentTabsList.getOrNull(activeTabIndex)?.webView?.findNext(false) 
        }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentTabsList.getOrNull(activeTabIndex)?.webView?.findAllAsync(s.toString())
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupAddressBar()
        handleBackNavigation()
        
        btnTabsCircular.setOnClickListener { 
            triggerStrongHaptic(it)
            showFullScreenTabs() 
        }
        menuBtn.setOnClickListener { 
            triggerStrongHaptic(it)
            showBrowserMenu() 
        }
        btnVoiceSearch.setOnClickListener { 
            triggerStrongHaptic(it)
            startVoiceSearch() 
        }

        val miniPlayerRoot = findViewById<FrameLayout>(R.id.miniPlayerRoot)
        if (miniPlayerRoot != null) {
            miniPlayerManager = HazeMiniPlayer(
                activity = this,
                rootLayout = miniPlayerRoot,
                onExpand = {
                    val tab = miniPlayerTab
                    if (tab != null) {
                        if (tab.webView.parent == null) {
                            val tabContainer = tab.swipeRefreshLayout
                            if (tabContainer != null) {
                                if (tabContainer.parent == null) webContainer.addView(tabContainer)
                                tabContainer.addView(tab.webView)
                            } else {
                                webContainer.addView(tab.webView)
                            }
                        }
                        val index = currentTabsList.indexOfFirst { it === tab }
                        if (index >= 0) switchTab(index)
                    }
                    activeMediaTab = null
                    miniPlayerTab = null
                },
                onClose = {
                    val tab = miniPlayerTab
                    val index = tab?.let { currentTabsList.indexOfFirst { candidate -> candidate === it } } ?: -1
                    if (index >= 0) closeTab(index)
                    activeMediaTab = null
                    miniPlayerTab = null
                }
            )
        }

        topRightActions = findViewById(R.id.topRightActions)
        btnProfile = findViewById(R.id.btnProfile)
        ivProfileAvatar = findViewById(R.id.ivProfileAvatar)
        btnProfile.setOnClickListener {
            triggerStrongHaptic(it)
            HazeProfileDialogManager.showProfilePanel(this)
        }
        HazeAccountManager.addAccountListener {
            updateProfileButton()
        }

        btnPrivacyShield = findViewById(R.id.btnPrivacyShield)
        ivPrivacyShieldIcon = findViewById(R.id.ivPrivacyShieldIcon)
        shieldBadgeDot = findViewById(R.id.shieldBadgeDot)
        btnPrivacyShield.setOnClickListener {
            triggerStrongHaptic(it)
            HazePrivacyShieldDashboard.showDashboard(this)
        }
        HazePrivacyShieldEngine.init(this)
        HazePrivacyShieldEngine.addListener(object : HazePrivacyShieldEngine.ShieldListener {
            override fun onShieldEvent(category: String, totalBlocked: Long) {
                runOnUiThread { updatePrivacyShieldButton() }
            }
        })

        addressBar.setText("")
        handleCustomIntent(intent)
        applyTheme()
        val initialUrl = intent?.dataString?.takeIf { it.isNotEmpty() } ?: HAZE_HOME_URL
        addNewTab(initialUrl)
        updateProfileButton()
        updatePrivacyShieldButton()

        // Silent sign-in in background
        HazeAccountManager.silentSignIn(this) {
            updateProfileButton()
        }

        // First-launch onboarding check
        val mainContainer = findViewById<ViewGroup>(R.id.main)
        HazeOnboardingManager.showOnboardingIfNeeded(this, mainContainer)
    }

    private fun activateSearchMode() {
        if (isSearchActive) return
        isSearchActive = true

        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val autoTransition = AutoTransition().apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }
        TransitionManager.beginDelayedTransition(rootLayout, autoTransition)

        val params = addressBarContainer.layoutParams as ConstraintLayout.LayoutParams
        params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
        params.topMargin = dpToPx(36)
        addressBarContainer.layoutParams = params

        dimOverlay.visibility = View.VISIBLE
        dimOverlay.alpha = 0f
        dimOverlay.animate().alpha(1f).setDuration(300).start()

        if (::topRightActions.isInitialized) {
            topRightActions.visibility = View.GONE
        }
        if (::floatingNavContainer.isInitialized) {
            floatingNavContainer.visibility = View.GONE
        }

        // Fade out Haze logo & shortcuts if on home tab
        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        if (currentTab != null && isHomeTab(currentTab)) {
            currentTab.webView.evaluateJavascript(
                "if(document.querySelector('.container')) { document.querySelector('.container').style.transition = 'opacity 0.2s ease'; document.querySelector('.container').style.opacity = '0'; }",
                null
            )
        }

        dimOverlay.bringToFront()
        recentSearchesContainer.bringToFront()
        addressBarContainer.bringToFront()
        addressBarContainer.elevation = dpToPx(30).toFloat()

        val text = addressBar.text.toString().trim()
        if (text.isEmpty()) {
            renderRecentSearches()
        } else {
            mainHandler.removeCallbacks(suggestionDebounce)
            mainHandler.post(suggestionDebounce)
        }

        recentSearchesContainer.visibility = View.VISIBLE
        recentSearchesContainer.alpha = 0f
        recentSearchesContainer.translationY = -40f
        recentSearchesContainer.scaleX = 0.96f
        recentSearchesContainer.scaleY = 0.96f
        recentSearchesContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(280)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()

        btnClearQuery.visibility = if (addressBar.text.isNotEmpty()) View.VISIBLE else View.GONE

        addressBar.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun deactivateSearchMode() {
        if (!isSearchActive) return
        isSearchActive = false

        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val autoTransition = AutoTransition().apply {
            duration = 300
            interpolator = FastOutSlowInInterpolator()
        }
        TransitionManager.beginDelayedTransition(rootLayout, autoTransition)

        val params = addressBarContainer.layoutParams as ConstraintLayout.LayoutParams
        params.topToTop = ConstraintLayout.LayoutParams.UNSET
        params.topMargin = 0
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        addressBarContainer.layoutParams = params

        dimOverlay.animate().alpha(0f).setDuration(250).withEndAction {
            dimOverlay.visibility = View.GONE
        }.start()

        recentSearchesContainer.visibility = View.GONE
        btnClearQuery.visibility = View.GONE

        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        val isHome = currentTab != null && isHomeTab(currentTab)
        if (isHome) {
            currentTab.webView.evaluateJavascript(
                "if(document.querySelector('.container')) { document.querySelector('.container').style.transition = 'opacity 0.25s ease'; document.querySelector('.container').style.opacity = '1'; }",
                null
            )
        }

        if (::topRightActions.isInitialized) {
            topRightActions.visibility = if (isHome && !isPrivateMode) View.VISIBLE else View.GONE
            if (isHome && !isPrivateMode) {
                topRightActions.bringToFront()
            }
        }
        if (::floatingNavContainer.isInitialized) {
            val shouldShowNav = !isHome && customVideoView == null && miniPlayerManager?.isActive != true
            floatingNavContainer.visibility = if (shouldShowNav) View.VISIBLE else View.GONE
            if (shouldShowNav) {
                floatingNavContainer.bringToFront()
                updateNavigationButtons()
            }
        }

        addressBar.clearFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
    }

    private fun saveRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("haze://")) return
        HazeDatabase.getInstance(this).addRecentSearch(trimmed)
    }

    private fun getRecentSearches(): List<String> {
        return HazeDatabase.getInstance(this).getRecentSearches(10).map { it.query }
    }

    private fun clearRecentSearches() {
        HazeDatabase.getInstance(this).clearRecentSearches()
        renderRecentSearches()
    }

    private fun deleteRecentSearch(item: String) {
        HazeDatabase.getInstance(this).deleteRecentSearch(item)
        renderRecentSearches()
    }

    private fun renderRecentSearches() {
        val tvHeaderTitle = findViewById<TextView>(R.id.tvRecentHeaderTitle)
        val btnClearHistory = findViewById<TextView>(R.id.btnClearHistory)
        tvHeaderTitle?.text = "Recent Searches"
        btnClearHistory?.visibility = View.VISIBLE

        recentSearchesList.removeAllViews()
        val searches = getRecentSearches()
        if (searches.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No recent searches"
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 13f
                setPadding(0, 16, 0, 16)
            }
            recentSearchesList.addView(emptyTv)
            return
        }

        for (query in searches) {
            val rowView = layoutInflater.inflate(R.layout.item_recent_search, recentSearchesList, false)
            val tvQuery = rowView.findViewById<TextView>(R.id.tvRecentQuery)
            val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeleteRecent)
            val ivIcon = rowView.findViewById<ImageView>(R.id.ivRecentIcon)

            ivIcon?.setImageResource(R.drawable.ic_history)
            tvQuery.text = query
            rowView.setOnClickListener {
                triggerStrongHaptic(it)
                addressBar.setText(query)
                loadUrlFromInput(query)
            }
            btnDelete.setOnClickListener {
                triggerStrongHaptic(it)
                deleteRecentSearch(query)
            }
            recentSearchesList.addView(rowView)
        }
    }

    private fun fetchSearchSuggestions(query: String, callback: (List<String>) -> Unit) {
        val trimmed = query.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("haze://")) {
            callback(emptyList())
            return
        }
        suggestionCache[trimmed]?.let {
            callback(it)
            return
        }
        suggestionExecutor.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                val encoded = Uri.encode(trimmed)
                val url = java.net.URL("https://suggestqueries.google.com/complete/search?client=chrome&q=$encoded")
                conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(text)
                val suggestionsArray = jsonArray.getJSONArray(1)
                val list = mutableListOf<String>()
                for (i in 0 until suggestionsArray.length()) {
                    list.add(suggestionsArray.getString(i))
                }
                val result = list.toList()
                suggestionCache[trimmed] = result
                mainHandler.post { callback(result) }
            } catch (e: Exception) {
                mainHandler.post { callback(emptyList()) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun renderSearchSuggestions(query: String, suggestions: List<String>) {
        val tvHeaderTitle = findViewById<TextView>(R.id.tvRecentHeaderTitle)
        val btnClearHistory = findViewById<TextView>(R.id.btnClearHistory)
        tvHeaderTitle?.text = "Search Suggestions"
        btnClearHistory?.visibility = View.GONE

        recentSearchesList.removeAllViews()
        if (suggestions.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Search for \"$query\""
                setTextColor(Color.parseColor("#9AA0A6"))
                textSize = 13.5f
                setPadding(0, 16, 0, 16)
            }
            recentSearchesList.addView(emptyTv)
            return
        }

        for (suggestion in suggestions) {
            val rowView = layoutInflater.inflate(R.layout.item_recent_search, recentSearchesList, false)
            val tvQuery = rowView.findViewById<TextView>(R.id.tvRecentQuery)
            val btnDelete = rowView.findViewById<ImageButton>(R.id.btnDeleteRecent)
            val ivIcon = rowView.findViewById<ImageView>(R.id.ivRecentIcon)

            ivIcon?.setImageResource(R.drawable.ic_search)
            tvQuery.text = suggestion

            btnDelete.setImageResource(R.drawable.ic_wide_arrow_down)
            btnDelete.rotation = 135f
            btnDelete.setOnClickListener {
                triggerStrongHaptic(it)
                addressBar.setText(suggestion)
                addressBar.setSelection(suggestion.length)
            }

            rowView.setOnClickListener {
                triggerStrongHaptic(it)
                addressBar.setText(suggestion)
                loadUrlFromInput(suggestion)
            }
            recentSearchesList.addView(rowView)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCustomIntent(intent)
        val incomingUrl = intent?.dataString
        if (!incomingUrl.isNullOrEmpty()) {
            loadUrlFromInput(incomingUrl)
        }
    }

    private fun handleCustomIntent(intent: Intent?) {
        if (intent == null) return
        var changed = false
        val newBg = intent.getStringExtra("set_bg_theme")
        if (newBg != null) {
            prefs.edit().putString("bg_theme", newBg).apply()
            changed = true
        }
        val newColor = intent.getStringExtra("set_accent_color")
        if (newColor != null) {
            prefs.edit().putString("accent_color", newColor).apply()
            changed = true
        }
        if (intent.hasExtra("set_dark_mode")) {
            val dark = intent.getBooleanExtra("set_dark_mode", false)
            prefs.edit().putBoolean("is_dark", dark).apply()
            changed = true
        }
        if (intent.getBooleanExtra("reset_shortcuts", false)) {
            saveCustomShortcuts(defaultShortcuts)
            changed = true
        }
        val removeIndex = intent.getIntExtra("test_remove_shortcut", -1)
        if (removeIndex != -1) {
            val list = getCustomShortcuts().toMutableList()
            if (removeIndex in list.indices) {
                list.removeAt(removeIndex)
                saveCustomShortcuts(list)
                changed = true
            }
        }
        if (changed) {
            applyTheme()
            reloadHomeTabs()
        }
        if (intent.getBooleanExtra("test_seed_data", false)) {
            val db = HazeDatabase.getInstance(this)
            // Seed downloads across all categories
            db.addDownload(101L, "Flutter_Architecture_Cheatsheet.pdf", "/storage/emulated/0/Download/Flutter_Architecture_Cheatsheet.pdf", 4521984L, "https://flutter.dev/cheatsheet.pdf", "application/pdf", HazeDownloadEngine.STATUS_COMPLETED)
            db.addDownload(102L, "Android_Studio_Ladybug.zip", "/storage/emulated/0/Download/Android_Studio_Ladybug.zip", 1048576000L, "https://developer.android.com/studio.zip", "application/zip", HazeDownloadEngine.STATUS_DOWNLOADING)
            db.addDownload(103L, "Kotlin_Language_Spec.epub", "/storage/emulated/0/Download/Kotlin_Language_Spec.epub", 12582912L, "https://kotlinlang.org/spec.epub", "application/epub+zip", HazeDownloadEngine.STATUS_QUEUED)
            db.addDownload(104L, "Nature_4K_Wallpaper.png", "/storage/emulated/0/Download/Nature_4K_Wallpaper.png", 8388608L, "https://images.unsplash.com/nature.png", "image/png", HazeDownloadEngine.STATUS_PAUSED)
            db.addDownload(105L, "Firmware_Update_v2.bin", "/storage/emulated/0/Download/Firmware_Update_v2.bin", 52428800L, "https://firmware.download/v2.bin", "application/octet-stream", HazeDownloadEngine.STATUS_FAILED)

            // Seed nested bookmark folders
            val devFolderId = db.createFolder("Development", 0L)
            val androidFolderId = db.createFolder("Android SDK", devFolderId)
            db.addBookmark("Android Developers", "https://developer.android.com", androidFolderId)
            db.addBookmark("Kotlin Official", "https://kotlinlang.org", androidFolderId)
            db.addBookmark("GitHub", "https://github.com", devFolderId)
            db.addBookmark("Google Search", "https://www.google.com", 0L)
            db.addBookmark("Wikipedia", "https://wikipedia.org", 0L)
        }
        if (intent.getBooleanExtra("test_clear_data", false)) {
            val db = HazeDatabase.getInstance(this)
            db.clearAllHistory()
            db.clearAllDownloads()
            db.clearAllBookmarks()
        }
        if (intent.getBooleanExtra("open_personalize", false)) {
            showPersonalizeDialog()
        }
        if (intent.getBooleanExtra("open_bookmarks", false)) {
            webContainer.postDelayed({ HazeBookmarksManager.showBookmarksDialog(this) { loadUrlFromInput(it) } }, 200)
        }
        if (intent.getBooleanExtra("open_history", false)) {
            webContainer.postDelayed({ HazeHistoryManager.showHistoryDialog(this) { loadUrlFromInput(it) } }, 200)
        }
        if (intent.getBooleanExtra("open_downloads", false)) {
            webContainer.postDelayed({ HazeDownloadsManager.showDownloadsDialog(this) }, 200)
        }
        if (intent.getBooleanExtra("open_clear_data", false)) {
            webContainer.postDelayed({ clearBrowsingData() }, 200)
        }
        if (intent.getBooleanExtra("open_menu", false)) {
            webContainer.postDelayed({ showBrowserMenu() }, 300)
        }
        if (intent.getBooleanExtra("activate_search", false)) {
            webContainer.postDelayed({ activateSearchMode() }, 200)
        }
        if (intent.getBooleanExtra("open_profile", false)) {
            webContainer.postDelayed({ HazeProfileDialogManager.showProfilePanel(this) }, 200)
        }
        if (intent.getBooleanExtra("open_privacy_shield", false)) {
            webContainer.postDelayed({ HazePrivacyShieldDashboard.showDashboard(this) }, 200)
        }
    }

    private fun getCustomShortcuts(): List<Shortcut> {
        if (!prefs.getBoolean("favicons_initialized_v2", false)) {
            saveCustomShortcuts(defaultShortcuts)
            prefs.edit().putBoolean("favicons_initialized_v2", true).apply()
            return defaultShortcuts
        }
        val jsonStr = prefs.getString("custom_shortcuts_json", null) ?: return defaultShortcuts
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<Shortcut>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val url = obj.getString("url")
                var iconUrl = obj.optString("iconUrl", "")
                
                if (iconUrl.isEmpty() || !iconUrl.startsWith("data:image/png;base64,")) {
                    val defaultItem = DefaultShortcuts.list.find { 
                        it.name.equals(name, ignoreCase = true) || it.url.equals(url, ignoreCase = true) 
                    }
                    if (defaultItem != null) {
                        iconUrl = defaultItem.iconUrl
                    } else if (iconUrl.isEmpty()) {
                        val host = try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
                        iconUrl = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                    }
                }
                
                list.add(Shortcut(
                    name = name, 
                    url = url, 
                    iconUrl = iconUrl,
                    svgPath = obj.optString("svg", ""),
                    fillColor = obj.optString("color", "")
                ))
            }
            if (list.isEmpty()) defaultShortcuts else list
        } catch (e: Exception) {
            defaultShortcuts
        }
    }

    private fun saveCustomShortcuts(list: List<Shortcut>) {
        try {
            val arr = JSONArray()
            for (s in list) {
                val obj = JSONObject()
                obj.put("name", s.name)
                obj.put("url", s.url)
                obj.put("iconUrl", s.iconUrl)
                obj.put("svg", s.svgPath)
                obj.put("color", s.fillColor)
                arr.put(obj)
            }
            prefs.edit().putString("custom_shortcuts_json", arr.toString()).apply()
        } catch (e: Exception) {}
    }

    private fun isHomeTab(tab: Tab): Boolean {
        val u = tab.url.trim()
        return u == HAZE_HOME_URL || u == "$HAZE_HOME_URL/" || u.startsWith("haze://home") || u == "about:blank" || u.startsWith("about:") || u.isEmpty()
    }

    private fun reloadHomeTabs() {
        for (tab in normalTabs) {
            if (isHomeTab(tab)) {
                tab.url = HAZE_HOME_URL
                tab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
            }
        }
        for (tab in privateTabs) {
            if (isHomeTab(tab)) {
                tab.url = HAZE_HOME_URL
                tab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
            }
        }
    }

    private fun showAddShortcutDialog() {
        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2"
        val maxCapacity = if (shortcutsMode == "1") 4 else 9
        val currentShortcuts = getCustomShortcuts()
        if (currentShortcuts.size >= maxCapacity) {
            AlertDialog.Builder(this)
                .setTitle("Shortcut Limit Reached")
                .setMessage("Your shortcuts grid is full ($maxCapacity/$maxCapacity).\n\nTo make room for a new shortcut:\n• Press and hold (long-press) any shortcut you don't need\n• Tap 'Remove'\n\nOnce removed, this Add (+) button will immediately activate!")
                .setPositiveButton("Got It", null)
                .show()
            return
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 40)
        }
        
        val nameInput = EditText(this).apply { hint = "Name (e.g. Amazon)" }
        val urlInput = EditText(this).apply { hint = "URL (e.g. https://amazon.com)" }
        
        layout.addView(nameInput)
        layout.addView(urlInput)

        AlertDialog.Builder(this)
            .setTitle("Add Custom Shortcut")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val name = nameInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (name.isNotEmpty() && url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val customShortcuts = getCustomShortcuts().toMutableList()
                    val host = try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
                    val iconUrl = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                    customShortcuts.add(Shortcut(name, url, iconUrl = iconUrl))
                    saveCustomShortcuts(customShortcuts)
                    
                    reloadHomeTabs()
                    Toast.makeText(this, "Shortcut added!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRemoveShortcutDialog(index: Int) {
        val customShortcuts = getCustomShortcuts().toMutableList()
        if (index in 0 until customShortcuts.size) {
            val s = customShortcuts[index]
            AlertDialog.Builder(this)
                .setTitle("Remove Shortcut")
                .setMessage("Do you want to remove '${s.name}' from your home screen?")
                .setPositiveButton("Remove") { _, _ ->
                    customShortcuts.removeAt(index)
                    saveCustomShortcuts(customShortcuts)
                    reloadHomeTabs()
                    Toast.makeText(this, "Shortcut removed!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun getHazeHomeHtml(): String {
        val isDarkSetting = prefs.getBoolean("is_dark", false)
        val isIncognito = isPrivateMode
        val isDark = isDarkSetting || isIncognito

        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2" // "off", "1", "2"
        val accentColor = if (isIncognito) "#A8C7FA" else (prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        
        val bgColor = if (isIncognito) "#181818" else (if (isDarkSetting) "#121212" else "#FAFAFA")
        val cardColor = if (isIncognito) "#2A2A2A" else (if (isDarkSetting) "#1E1E1E" else "#FFFFFF")
        val textColor = if (isDark) "#FFFFFF" else "#202124"
        val labelColor = if (isDark) "#E0E0E0" else "#202124"
        val cardBorder = "box-shadow: 0 2px 8px rgba(0,0,0,0.05);"
        
        val maxCapacity = if (shortcutsMode == "1") 4 else 9
        val customShortcuts = getCustomShortcuts()
        val activeShortcuts = customShortcuts.take(maxCapacity)
        val isFull = customShortcuts.size >= maxCapacity
        
        val displayShortcutsHtml = StringBuilder()
        for (i in activeShortcuts.indices) {
            val s = activeShortcuts[i]
            val iconContent = if (s.iconUrl.isNotEmpty()) {
                """<img src="${s.iconUrl}" style="width:28px; height:28px; border-radius: 6px; object-fit: contain;" />"""
            } else {
                """<svg viewBox="0 0 24 24" style="fill: ${s.fillColor}; width:26px; height:26px;"><path d="${s.svgPath}"/></svg>"""
            }
            displayShortcutsHtml.append("""
                <div class="shortcut" 
                     onclick="Android.triggerHaptic(false); Android.navigateTo('${s.url}')" 
                     oncontextmenu="Android.triggerHaptic(true); event.preventDefault(); Android.confirmRemoveShortcut($i); return false;">
                    <div class="shortcut-box">$iconContent</div>
                    <div class="shortcut-label">${s.name}</div>
                </div>
            """)
        }
        
        if (shortcutsMode != "off") {
            val addClickAction = if (isFull) "Android.triggerHaptic(true); Android.showMaxShortcutsToast()" else "Android.triggerHaptic(false); Android.openAddShortcutDialog()"
            val addStyle = if (isFull) "opacity: 0.35; filter: grayscale(100%); cursor: not-allowed;" else "opacity: 1;"
            
            displayShortcutsHtml.append("""
                <div class="shortcut" onclick="$addClickAction" style="$addStyle">
                    <div class="shortcut-box"><svg viewBox="0 0 24 24" style="fill: $accentColor; width:26px; height:26px;"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg></div>
                    <div class="shortcut-label">Add</div>
                </div>
            """)
        }
        
        val bgTheme = if (isIncognito) "default" else (prefs.getString("bg_theme", "default") ?: "default")
        val hasBgPhoto = bgTheme != "default"

        val bgStyle = if (hasBgPhoto) {
            "background: transparent; background-color: transparent;"
        } else {
            "background: $bgColor; background-color: $bgColor;"
        }

        val logoShadow = if (hasBgPhoto) {
            "filter: drop-shadow(0 8px 24px rgba(0,0,0,0.85)) contrast(1.25) brightness(1.1);"
        } else {
            if (isDark) "filter: drop-shadow(0 4px 14px rgba(0,0,0,0.35));" else "filter: drop-shadow(0 4px 14px rgba(0,0,0,0.12));"
        }

        val effectiveCardColor = if (hasBgPhoto) "rgba(255, 255, 255, 0.85)" else cardColor
        val effectiveCardBorder = if (hasBgPhoto) "backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); box-shadow: 0 4px 14px rgba(0,0,0,0.2);" else cardBorder
        val effectiveLabelColor = if (hasBgPhoto) "#FFFFFF" else labelColor
        val labelShadow = if (hasBgPhoto) "text-shadow: 0 1px 4px rgba(0,0,0,0.9);" else ""

        val stroke1Color = if (isDark || hasBgPhoto) "#FFFFFF" else "#1A1C1E"
        val stroke2Color = if (isDark || hasBgPhoto) "#D8D8D8" else "#5E6267"
        val stroke3Color = if (isDark || hasBgPhoto) "#A8A8A8" else "#9EA2A7"

        val logoViewHtml = if (isIncognito) {
            """
            <svg class="logo" viewBox="0 0 100 80" xmlns="http://www.w3.org/2000/svg" style="pointer-events: none; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;">
                <path d="M50 10 C38 10 28 16 22 22 C18 30 5 32 5 38 C5 40 7 42 10 42 L90 42 C93 42 95 40 95 38 C95 32 82 30 78 22 C72 16 62 10 50 10 Z" fill="$textColor"/>
                <circle cx="32" cy="62" r="15" fill="$textColor"/>
                <circle cx="32" cy="62" r="11" fill="$bgColor"/>
                <circle cx="68" cy="62" r="15" fill="$textColor"/>
                <circle cx="68" cy="62" r="11" fill="$bgColor"/>
                <path d="M47 62 L53 62" stroke="$textColor" stroke-width="4" stroke-linecap="round"/>
            </svg>
            """.trimIndent()
        } else {
            """
            <svg class="logo" viewBox="160 260 700 500" width="220" height="157" xmlns="http://www.w3.org/2000/svg" style="width: 220px; height: 157px; max-width: 65vw; display: block; pointer-events: none; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;">
                <path d="${HazeLogoVector.STROKE_1}" fill="$stroke1Color" />
                <path d="${HazeLogoVector.STROKE_2}" fill="$stroke2Color" />
                <path d="${HazeLogoVector.STROKE_3}" fill="$stroke3Color" />
            </svg>
            """.trimIndent()
        }

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600&display=swap');
                    html, body {
                        margin: 0; padding: 0; height: 100vh; width: 100vw;
                        display: flex; flex-direction: column; align-items: center; justify-content: center;
                        $bgStyle
                        font-family: 'Outfit', sans-serif;
                        overflow: hidden; position: fixed; touch-action: none;
                    }
                    .container {
                        display: flex; flex-direction: column; align-items: center; width: 100%;
                        margin-top: -6vh;
                    }
                    .logo-wrapper {
                        display: flex; justify-content: center; align-items: center;
                        margin-bottom: 24px;
                    }
                    .logo {
                        width: 220px; height: 157px;
                        max-width: 65vw;
                        display: block;
                        pointer-events: none; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
                        $logoShadow
                    }
                    .shortcuts {
                        display: ${if (shortcutsMode == "off") "none" else "grid"};
                        grid-template-columns: repeat(5, 1fr);
                        gap: 10px; margin-top: 10px;
                        justify-items: center;
                        width: 94%; max-width: 440px;
                    }
                    .shortcut {
                        display: flex; flex-direction: column; align-items: center;
                        text-decoration: none; cursor: pointer; width: 100%;
                        transition: transform 0.1s ease;
                    }
                    .shortcut:active {
                        transform: scale(0.92);
                    }
                    .shortcut-box {
                        width: 52px; height: 52px; border-radius: 16px;
                        background: $effectiveCardColor;
                        display: flex; justify-content: center; align-items: center;
                        margin-bottom: 6px;
                        $effectiveCardBorder
                    }
                    .shortcut-label {
                        color: $effectiveLabelColor;
                        font-size: 11px; font-weight: 500;
                        text-align: center;
                        $labelShadow
                        white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 100%;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="logo-wrapper">
                        $logoViewHtml
                    </div>

                    <div class="shortcuts">
                        $displayShortcutsHtml
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun applyTheme() {
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        
        val bgColor = if (isPrivateMode) Color.parseColor("#181818") else (if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))
        val barBgColor = if (isPrivateMode) Color.parseColor("#2A2A2A") else (if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF"))
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val hintColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        
        findViewById<ConstraintLayout>(R.id.main).setBackgroundColor(bgColor)
        addressBarContainer.setBackgroundColor(bgColor)
        findBarContainer.setBackgroundColor(bgColor)

        val bgTheme = if (isPrivateMode) "default" else (prefs.getString("bg_theme", "default") ?: "default")
        val appBgImageView = findViewById<ImageView>(R.id.appBackgroundImageView)
        val appBgOverlay = findViewById<View>(R.id.appBackgroundOverlay)
        if (bgTheme != "default" && !isPrivateMode) {
            try {
                val inputStream = assets.open("bg_theme_$bgTheme.webp")
                val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)
                appBgImageView?.setImageBitmap(bitmap)
                appBgImageView?.visibility = View.VISIBLE
                appBgOverlay?.visibility = View.VISIBLE
            } catch (e: Exception) {
                appBgImageView?.visibility = View.GONE
                appBgOverlay?.visibility = View.GONE
            }
        } else {
            appBgImageView?.visibility = View.GONE
            appBgOverlay?.visibility = View.GONE
        }
        
        val addressBarInner = findViewById<LinearLayout>(R.id.addressBarInner)
        val shape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
            cornerRadius = radiusPx
            setColor(barBgColor)
            val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
            setStroke(1, strokeColor)
        }
        addressBarInner.background = shape
        
        addressBar.setTextColor(textColor)
        addressBar.setHintTextColor(hintColor)
        findInput.setTextColor(textColor)
        findInput.setHintTextColor(hintColor)
        
        findViewById<ImageView>(R.id.searchIcon).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.menuBtn).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindClose).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindNext).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindPrev).setColorFilter(hintColor)
        
        val tabShape = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(barBgColor)
        }
        btnTabsCircular.background = tabShape
        tabCountBtn.setTextColor(textColor)

        if (::btnProfile.isInitialized) {
            btnProfile.background = null
            btnProfile.elevation = 0f
            btnProfile.outlineProvider = null
            updateProfileButton()
        }

        if (::btnPrivacyShield.isInitialized) {
            btnPrivacyShield.background = null
            btnPrivacyShield.elevation = 0f
            btnPrivacyShield.outlineProvider = null
            updatePrivacyShieldButton()
        }

        if (::floatingNavContainer.isInitialized) {
            val navPillShape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(20).toFloat()
                setColor(if (isDark) Color.parseColor("#E6262626") else Color.parseColor("#F5FFFFFF"))
                setStroke(dpToPx(1), if (isDark) Color.parseColor("#383838") else Color.parseColor("#DADCE0"))
            }
            floatingNavContainer.background = navPillShape
            floatingNavContainer.elevation = dpToPx(8).toFloat()

            val iconColor = if (isDark) Color.parseColor("#F1F3F4") else Color.parseColor("#202124")
            ivFloatingBackIcon.setColorFilter(iconColor)
            ivFloatingForwardIcon.setColorFilter(iconColor)
            floatingNavDivider.setBackgroundColor(if (isDark) Color.parseColor("#383838") else Color.parseColor("#DADCE0"))

            updateNavigationButtons()
        }

        reloadHomeTabs()
    }

        fun addNewTab(url: String) {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val swipeRefreshLayout = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setOnRefreshListener {
                triggerStrongHaptic()
                webView.reload()
                postDelayed({ isRefreshing = false }, 1000)
            }
        }
        swipeRefreshLayout.addView(webView)

        val tab = Tab(webView = webView, swipeRefreshLayout = swipeRefreshLayout, isPrivate = isPrivateMode)
        
        currentTabsList.add(tab)
        webContainer.addView(swipeRefreshLayout)
        
        setupWebView(webView)
        
        if (isPrivateMode) activePrivateTabIndex = currentTabsList.size - 1
        else activeNormalTabIndex = currentTabsList.size - 1
        
        switchTab(activeTabIndex)
        
        when (url) {
            HAZE_HOME_URL -> {
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                tab.title = "New Tab"
                if (activeTabIndex == activeNormalTabIndex || activeTabIndex == activePrivateTabIndex) addressBar.setText("")
            }
            "haze://favorites", "haze://bookmarks" -> {
                tab.title = "Favorites"
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeBookmarksManager.showBookmarksDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            }
            "haze://history" -> {
                tab.title = "History"
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeHistoryManager.showHistoryDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            }
            "haze://downloads" -> {
                tab.title = "Downloads"
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeDownloadsManager.showDownloadsDialog(this)
            }
            "haze://settings" -> {
                tab.title = "Settings"
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeSettingsManager.showSettingsDialog(this)
            }
            "haze://shield", "haze://privacy", "haze://adblock" -> {
                tab.title = "Privacy Shield"
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazePrivacyShieldDashboard.showDashboard(this)
            }
            else -> webView.loadUrl(url)
        }
        tab.url = url
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= currentTabsList.size) {
            tabCountBtn.text = currentTabsList.size.toString()
            return
        }

        val previousTab = currentTabsList.getOrNull(activeTabIndex)
        val nextTab = currentTabsList[index]
        if (previousTab != null &&
            previousTab !== nextTab &&
            activeMediaTab === previousTab &&
            miniPlayerManager?.isActive != true
        ) {
            showMiniPlayer(previousTab, createBrowsingTab = false)
        }
        
        if (isPrivateMode) activePrivateTabIndex = index else activeNormalTabIndex = index
        
        for (i in 0 until webContainer.childCount) {
            val child = webContainer.getChildAt(i)
            val currentTab = currentTabsList.getOrNull(index)
            val isCurrentTabContainer = currentTab?.swipeRefreshLayout?.let { child == it } ?: (child == currentTab?.webView)
            child.visibility = if (isCurrentTabContainer) View.VISIBLE else View.GONE
        }
        
        tabCountBtn.text = currentTabsList.size.toString()
        val currentTab = currentTabsList[index]
        val isHome = isHomeTab(currentTab)
        currentTab.swipeRefreshLayout?.isEnabled = !isHome

        val currentUrl = currentTab.url
        updateChromeVisibility(currentUrl)
        
        if (isHome) {
            addressBar.setText("")
        } else {
            addressBar.setText(currentUrl)
        }
    }

    private fun updateChromeVisibility(url: String?) {
        addressBarContainer.visibility = View.VISIBLE
        val isHome = url == null || url == HAZE_HOME_URL || url == "$HAZE_HOME_URL/" || url.startsWith("haze://home") || url == "about:blank" || url.startsWith("about:") || url.isEmpty()
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val bgColor = if (isPrivateMode) Color.parseColor("#181818") else (if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))
        
        if (isHome) {
            addressBar.setText("")
        }

        val barBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val addressBarInner = findViewById<LinearLayout>(R.id.addressBarInner)
        val barShape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
            cornerRadius = radiusPx
            setColor(barBgColor)
            val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
            setStroke(1, strokeColor)
        }
        addressBarInner.background = barShape
        addressBarInner.elevation = 0f
        addressBarInner.outlineProvider = null
        
        addressBarContainer.setBackgroundColor(Color.TRANSPARENT)
        addressBarContainer.elevation = 0f
        val bgTheme = if (isPrivateMode) "default" else (prefs.getString("bg_theme", "default") ?: "default")
        val hasBgPhoto = bgTheme != "default" && !isPrivateMode
        val appBgImageView = findViewById<ImageView>(R.id.appBackgroundImageView)
        val appBgOverlay = findViewById<View>(R.id.appBackgroundOverlay)

        if (isHome) {
            currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(if (hasBgPhoto) Color.TRANSPARENT else bgColor)
            if (hasBgPhoto) {
                appBgImageView?.visibility = View.VISIBLE
                appBgOverlay?.visibility = View.VISIBLE
            } else {
                appBgImageView?.visibility = View.GONE
                appBgOverlay?.visibility = View.GONE
            }
        } else {
            currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(Color.WHITE)
            appBgImageView?.visibility = View.GONE
            appBgOverlay?.visibility = View.GONE
        }

        if (::topRightActions.isInitialized) {
            topRightActions.visibility = if (isHome && !isPrivateMode) View.VISIBLE else View.GONE
            if (isHome && !isPrivateMode) {
                topRightActions.bringToFront()
            }
        }
        if (::floatingNavContainer.isInitialized) {
            val shouldShowNav = !isHome && !isSearchActive && customVideoView == null && miniPlayerManager?.isActive != true
            floatingNavContainer.visibility = if (shouldShowNav) View.VISIBLE else View.GONE
            if (shouldShowNav) {
                floatingNavContainer.bringToFront()
                updateNavigationButtons()
            }
        }
        if (::btnProfile.isInitialized) {
            updateProfileButton()
        }
        if (::btnPrivacyShield.isInitialized) {
            updatePrivacyShieldButton()
        }

        if (miniPlayerManager?.isActive == true) {
            addressBarContainer.visibility = View.GONE
            if (::topRightActions.isInitialized) topRightActions.visibility = View.GONE
            if (::floatingNavContainer.isInitialized) floatingNavContainer.visibility = View.GONE
        }
    }

    private fun closeTab(index: Int) {
        if (currentTabsList.size <= 1) {
            addNewTab(HAZE_HOME_URL)
            val tabToRemove = currentTabsList[0]
            if (tabToRemove.swipeRefreshLayout != null) {
                webContainer.removeView(tabToRemove.swipeRefreshLayout)
            } else {
                webContainer.removeView(tabToRemove.webView)
            }
            tabToRemove.webView.destroy()
            currentTabsList.removeAt(0)
            switchTab(0)
            return
        }
        val tabToRemove = currentTabsList[index]
        if (tabToRemove.swipeRefreshLayout != null) {
            webContainer.removeView(tabToRemove.swipeRefreshLayout)
        } else {
            webContainer.removeView(tabToRemove.webView)
        }
        tabToRemove.webView.destroy()
        currentTabsList.removeAt(index)
        
        if (isPrivateMode) {
            if (activePrivateTabIndex >= index) activePrivateTabIndex--
            if (activePrivateTabIndex < 0) activePrivateTabIndex = 0
            switchTab(activePrivateTabIndex)
        } else {
            if (activeNormalTabIndex >= index) activeNormalTabIndex--
            if (activeNormalTabIndex < 0) activeNormalTabIndex = 0
            switchTab(activeNormalTabIndex)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.addJavascriptInterface(WebAppInterface(webView), "Android")

        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        webView.settings.apply {
            javaScriptEnabled = jsEnabled
            domStorageEnabled = !isPrivateMode
            databaseEnabled = !isPrivateMode
            allowFileAccess = true
            allowContentAccess = true
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = if (isPrivateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            // Pre-rasterizing every tab consumes significant memory and competes
            // with the active tab for rendering time.
            offscreenPreRaster = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
        // CookieManager is the WebView cookie store.  This controls new third-party
        // cookie acceptance; it does not claim to delete existing cookies.
        CookieManager.getInstance().setAcceptThirdPartyCookies(
            webView, prefs.getBoolean("block_third_party_cookies", true).not()
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this) { }
        }

        if (HazeAccountManager.isSignedIn(this)) {
            HazeAccountManager.syncGoogleAccountToCookieManager(this)
        }

        // Web download listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            HazeDownloadsManager.initDownload(this, url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // Native Context Menu on Long Click
        webView.setOnLongClickListener {
            val currentUrl = webView.url ?: ""
            if (currentUrl == HAZE_HOME_URL || currentUrl.startsWith("haze://") || currentUrl.isEmpty()) {
                return@setOnLongClickListener true
            }
            val result = webView.hitTestResult
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                    val url = result.extra
                    if (!url.isNullOrEmpty()) {
                        HazeContextMenuManager.showLinkContextMenu(this, url)
                        true
                    } else false
                }
                WebView.HitTestResult.IMAGE_TYPE -> {
                    val imageUrl = result.extra
                    if (!imageUrl.isNullOrEmpty()) {
                        HazeContextMenuManager.showImageContextMenu(this, imageUrl)
                        true
                    } else false
                }
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                    val extra = result.extra
                    if (!extra.isNullOrEmpty()) {
                        HazeContextMenuManager.showLinkContextMenu(this, extra)
                        true
                    } else false
                }
                else -> {
                    val currentUrl = webView.url ?: ""
                    if (currentUrl.isNotEmpty() && currentUrl != HAZE_HOME_URL && !currentUrl.startsWith("haze://")) {
                        val currentTitle = webView.title ?: "Web Page"
                        HazeContextMenuManager.showPageContextMenu(this, currentUrl, currentTitle, webView)
                        true
                    } else false
                }
            }
        }
        
        webView.webViewClient = object : WebViewClient() {
            private var topLevelUrl: String? = null
            private fun tabIsPrivate() = getTabFromWebView(webView)?.isPrivate ?: isPrivateMode
            private fun handleNavigationSecurity(view: WebView?, url: String): Boolean {
                if (url.startsWith("haze://") || url.startsWith("about:") || url.startsWith("javascript:") || url.startsWith("data:")) {
                    return false
                }
                val decision = HazePrivacyShieldEngine.evaluateRequest(
                    context = this@MainActivity,
                    requestUrl = url,
                    pageUrl = url,
                    isMainFrame = true,
                    isPrivate = tabIsPrivate()
                )
                if (decision.shouldBlock && decision.isHarmfulPageNavigation) {
                    val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
                    val warningHtml = HazeSecurityWarningManager.getSecurityWarningHtml(url, decision.category, isDark)
                    view?.post {
                        view.loadDataWithBaseURL(url, warningHtml, "text/html", "UTF-8", null)
                    }
                    return true
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (request.isForMainFrame) {
                    if (handleNavigationSecurity(view, url)) return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                if (handleNavigationSecurity(view, url)) return true
                return super.shouldOverrideUrlLoading(view, url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                if (request == null) return null
                val reqUrl = request.url?.toString() ?: return null
                val isMainFrame = request.isForMainFrame
                val pageUrl = if (isMainFrame) reqUrl else (request.requestHeaders?.get("Referer") ?: request.requestHeaders?.get("referer") ?: topLevelUrl)

                val decision = HazePrivacyShieldEngine.evaluateRequest(
                    context = this@MainActivity,
                    requestUrl = reqUrl,
                    pageUrl = pageUrl,
                    isMainFrame = isMainFrame,
                    isPrivate = tabIsPrivate()
                )

                if (decision.shouldBlock) {
                    if (isMainFrame && decision.isHarmfulPageNavigation) {
                        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
                        val html = HazeSecurityWarningManager.getSecurityWarningHtml(reqUrl, decision.category, isDark)
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        val headers = mapOf("Content-Type" to "text/html; charset=utf-8")
                        return WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                    } else {
                        return HazePrivacyShieldEngine.createBlockedResponse(reqUrl)
                    }
                }

                return null
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!url.isNullOrEmpty()) topLevelUrl = url
                showPageLoading(5)
                super.onPageStarted(view, url, favicon)
                
                if (url != null) {
                    val earlyCss = """
                        (function() {
                            if (window.__hazeEarlyCssInjected) return;
                            window.__hazeEarlyCssInjected = true;
                            var style = document.createElement('style');
                            style.innerHTML = 'div[class*="ad-"], div[id*="ad-"], .adsbygoogle, iframe[src*="doubleclick"], .sponsored, .promoted, ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-banner-promo-renderer, .ytp-ad-module, .video-ads { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; width: 0 !important; }';
                            if (document.documentElement) document.documentElement.appendChild(style);
                            else document.addEventListener('DOMContentLoaded', function() { document.documentElement.appendChild(style); });
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(earlyCss, null)
                }
            }

            override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
                val url = request?.url?.toString() ?: return callback?.backToSafety(false) ?: Unit
                HazePrivacyShieldEngine.recordSafeBrowsingThreat(this@MainActivity, url, tabIsPrivate(), threatType)
                val isDark = prefs.getBoolean("is_dark", false) || tabIsPrivate()
                view?.post { view.loadDataWithBaseURL(url, HazeSecurityWarningManager.getSecurityWarningHtml(url, HazePrivacyShieldEngine.CATEGORY_PHISHING, isDark), "text/html", "UTF-8", null) }
                callback?.backToSafety(false)
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url?.toString() ?: ""
                    val isDark = prefs.getBoolean("is_dark", false) || tabIsPrivate()
                    val offlineHtml = getOfflineErrorHtml(failingUrl, isDark)
                    view?.post {
                        view.loadDataWithBaseURL("file:///android_asset/", offlineHtml, "text/html", "UTF-8", null)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                finishPageLoading()
                val tab = getTabFromWebView(webView)
                if (tab != null && url != null) {
                    val isHome = url == HAZE_HOME_URL || url == "$HAZE_HOME_URL/" || url == "about:blank" || url.startsWith("about:") || url.startsWith("haze://home")
                    if (isHome) {
                        tab.title = "New Tab"
                        tab.url = HAZE_HOME_URL
                    } else {
                        tab.url = url
                    }
                    if (tab == currentTabsList.getOrNull(activeTabIndex)) {
                        updateChromeVisibility(tab.url)
                        if (isHome) {
                            addressBar.setText("")
                        } else {
                            addressBar.setText(url)
                        }
                    }

                    // Record history if not private and not internal
                    if (!isPrivateMode && !isHome && !url.startsWith("haze://") && !url.startsWith("about:")) {
                        val pageTitle = tab.title.ifEmpty { view?.title ?: "Web Page" }
                        val cachedFavicon = HazeFaviconManager.getCachedFaviconFile(this@MainActivity, url)
                        val faviconPath = if (cachedFavicon.exists()) cachedFavicon.absolutePath else null
                        HazeDatabase.getInstance(this@MainActivity).addHistory(url, pageTitle, isPrivate = false, faviconPath = faviconPath)
                    }

                    // 1. Advanced Cosmetic Filtering (Element Hiding CSS) across all websites
                    val cosmeticCss = """
                        (function() {
                            if (window.__hazeCosmeticInjected) return;
                            window.__hazeCosmeticInjected = true;
                            var style = document.createElement('style');
                            style.innerHTML = 'div[class*="ad-"], div[id*="ad-"], .adsbygoogle, iframe[src*="doubleclick"], .sponsored, .promoted, ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-banner-promo-renderer, .ytp-ad-module { display: none !important; opacity: 0 !important; pointer-events: none !important; height: 0 !important; width: 0 !important; }';
                            if (document.head) document.head.appendChild(style);
                            else document.documentElement.appendChild(style);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(cosmeticCss, null)

                    // 2. Safe & Reliable YouTube Ad Skipper
                    if (url.contains("youtube.com", ignoreCase = true)) {
                        val safeYtSkips = """
                            (function() {
                                if (window.__hazeSafeYtSkipper) return;
                                window.__hazeSafeYtSkipper = true;

                                var adWasActive = false;
                                var normalPlaybackRate = 1;
                                var adSelectors = [
                                    '.ytp-ad-skip-button',
                                    '.ytp-ad-skip-button-modern',
                                    '.ytp-ad-overlay-close-button',
                                    '.videoAdUiSkipButton'
                                ];

                                function skipYouTubeAd() {
                                    var adPlayer = document.querySelector('.ad-showing');
                                    if (!adPlayer) {
                                        if (adWasActive) {
                                            var normalVideo = document.querySelector('video');
                                            if (normalVideo && normalVideo.playbackRate > 1) {
                                                normalVideo.playbackRate = normalPlaybackRate;
                                            }
                                        }
                                        adWasActive = false;
                                        return;
                                    }

                                    adWasActive = true;
                                    for (var i = 0; i < adSelectors.length; i++) {
                                        var button = document.querySelector(adSelectors[i]);
                                        if (button) {
                                            button.click();
                                            return;
                                        }
                                    }

                                    var video = document.querySelector('video');
                                    if (video) {
                                        if (video.playbackRate <= 1) normalPlaybackRate = video.playbackRate || 1;
                                        // Seek as soon as metadata exists. Do not wait for
                                        // the ad duration or a timer tied to playback.
                                        if (isFinite(video.duration) && video.duration > 0) {
                                            video.currentTime = video.duration;
                                        } else {
                                            video.currentTime = 2147483647;
                                        }
                                        video.play().catch(function() {});
                                        video.playbackRate = 16;
                                    }

                                    var overlays = document.querySelectorAll(
                                        '.ytp-ad-overlay-container, .ytp-ad-text, .ytp-ad-message-container, .ytp-ad-player-overlay'
                                    );
                                    for (var j = 0; j < overlays.length; j++) {
                                        overlays[j].style.setProperty('display', 'none', 'important');
                                    }
                                }

                                setInterval(skipYouTubeAd, 25);
                                document.addEventListener('click', function() {
                                    setTimeout(skipYouTubeAd, 0);
                                }, true);
                                var observer = new MutationObserver(skipYouTubeAd);
                                observer.observe(document.documentElement, {
                                    childList: true,
                                    subtree: true,
                                    attributes: true,
                                    attributeFilter: ['class']
                                });
                                skipYouTubeAd();
                            })();
                        """.trimIndent()
                        view?.evaluateJavascript(safeYtSkips, null)
                    }

                    // Text Selection Listener injection
                    val selectionScript = """
                        (function() {
                            if (window.__hazeSelectionInjected) return;
                            window.__hazeSelectionInjected = true;
                            document.addEventListener('selectionchange', function() {
                                var sel = window.getSelection().toString();
                                if (sel && sel.trim().length > 0 && sel.trim().length < 500) {
                                    clearTimeout(window.__hazeSelTimer);
                                    window.__hazeSelTimer = setTimeout(function() {
                                        var text = window.getSelection().toString();
                                        if (text && text.trim().length > 0) {
                                            Android.onTextSelection(text.trim());
                                        }
                                    }, 800);
                                }
                            });
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(selectionScript, null)

                    // Media Playback detection script injection
                    val mediaScript = """
                        (function() {
                            if (window.__hazeMediaTrackInstalled) return;
                            window.__hazeMediaTrackInstalled = true;

                            function checkMedia() {
                                try {
                                    var media = document.querySelectorAll('video, audio');
                                    var isPlaying = false;
                                    var width = 16;
                                    var height = 9;
                                    for (var i = 0; i < media.length; i++) {
                                        var m = media[i];
                                        if (!m.paused && !m.ended && m.readyState > 1) {
                                            isPlaying = true;
                                            if (m.tagName.toLowerCase() === 'video' && m.videoWidth > 0 && m.videoHeight > 0) {
                                                width = m.videoWidth;
                                                height = m.videoHeight;
                                            }
                                            break;
                                        }
                                    }
                                    if (window.Android && window.Android.onMediaStateChange) {
                                        window.Android.onMediaStateChange(isPlaying, width, height);
                                    }
                                } catch(e) {}
                            }

                            function trackElement(el) {
                                if (!el || el.__hazeTracked) return;
                                el.__hazeTracked = true;
                                el.addEventListener('play', checkMedia, true);
                                el.addEventListener('playing', checkMedia, true);
                                el.addEventListener('pause', checkMedia, true);
                                el.addEventListener('ended', checkMedia, true);
                                el.addEventListener('ratechange', checkMedia, true);
                            }

                            function scanElements() {
                                var all = document.querySelectorAll('video, audio');
                                for (var i = 0; i < all.length; i++) {
                                    trackElement(all[i]);
                                }
                                checkMedia();
                            }

                            scanElements();

                            var observer = new MutationObserver(function() {
                                scanElements();
                            });
                            if (document.body || document.documentElement) {
                                observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                            }

                            setInterval(checkMedia, 1500);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(mediaScript, null)
                    updateNavigationButtons()
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNavigationButtons()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) showPageLoading(newProgress.coerceAtLeast(5))
                else finishPageLoading()
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customVideoView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customVideoView = view
                customViewCallback = callback
                fullscreenVideoContainer.removeAllViews()
                if (view != null) {
                    fullscreenVideoContainer.addView(
                        view,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            Gravity.CENTER
                        )
                    )
                }
                fullscreenVideoContainer.visibility = View.VISIBLE
                webContainer.visibility = View.GONE
                addressBarContainer.visibility = View.GONE
                if (::topRightActions.isInitialized) {
                    topRightActions.visibility = View.GONE
                }
                if (::floatingNavContainer.isInitialized) {
                    floatingNavContainer.visibility = View.GONE
                }
                isMediaPlaying = true
                updatePipAutoEnter()
            }

            override fun onHideCustomView() {
                if (customVideoView == null) return
                fullscreenVideoContainer.removeAllViews()
                fullscreenVideoContainer.visibility = View.GONE
                webContainer.visibility = View.VISIBLE
                customVideoView = null
                customViewCallback?.onCustomViewHidden()
                customViewCallback = null
                val currentTab = currentTabsList.getOrNull(activeTabIndex)
                updateChromeVisibility(currentTab?.url)
                updatePipAutoEnter()
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val tab = getTabFromWebView(webView)
                if (tab != null && title != null) {
                    val isHome = view?.url == HAZE_HOME_URL || tab.url == HAZE_HOME_URL || view?.url == "about:blank" || tab.url == "about:blank" || view?.url?.startsWith("about:") == true || tab.url.startsWith("about:")
                    if (isHome) {
                        tab.title = "New Tab"
                    } else if (!tab.url.startsWith("haze://")) {
                        tab.title = title
                        if (!isPrivateMode && tab.url != HAZE_HOME_URL && !tab.url.startsWith("about:")) {
                            HazeDatabase.getInstance(this@MainActivity).addHistory(tab.url, title, isPrivate = false)
                        }
                    }
                }
            }
            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val tab = getTabFromWebView(webView)
                if (tab != null && icon != null) {
                    tab.favicon = icon
                    if (!isPrivateMode && tab.url != HAZE_HOME_URL && !tab.url.startsWith("haze://") && !tab.url.startsWith("about:")) {
                        val path = HazeFaviconManager.saveFavicon(this@MainActivity, tab.url, icon)
                        if (path != null) {
                            HazeDatabase.getInstance(this@MainActivity).updateHistoryFavicon(tab.url, path)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val tab = activeMediaTab
        if (tab != null && isMediaPlaying && miniPlayerManager?.isActive != true) {
            showMiniPlayer(tab, createBrowsingTab = false)
        }
    }

    private fun getTabFromWebView(webView: WebView): Tab? {
        return normalTabs.find { it.webView == webView } ?: privateTabs.find { it.webView == webView }
    }

    private fun showFullScreenTabs() {
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar)
        dialog.setContentView(R.layout.layout_full_screen_tabs)

        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseTabs)
        val btnTogglePersonal = dialog.findViewById<TextView>(R.id.btnTogglePersonal)
        val btnTogglePrivate = dialog.findViewById<TextView>(R.id.btnTogglePrivate)
        val tvCurrentModeLabel = dialog.findViewById<TextView>(R.id.tvCurrentModeLabel)
        val btnNewTabAction = dialog.findViewById<TextView>(R.id.btnNewTabAction)
        val rvActiveTabs = dialog.findViewById<RecyclerView>(R.id.rvActiveTabs)

        var viewingPrivate = isPrivateMode

        btnClose.setOnClickListener { 
            triggerStrongHaptic(it)
            dialog.dismiss() 
        }

        val personalAdapter = TabsAdapter(normalTabs, { clickedIndex ->
            isPrivateMode = false
            switchTab(clickedIndex)
            dialog.dismiss()
        }, { closedIndex ->
            val tabToRemove = normalTabs[closedIndex]
            webContainer.removeView(tabToRemove.webView)
            tabToRemove.webView.destroy()
            normalTabs.removeAt(closedIndex)
            if (!isPrivateMode) {
                if (activeNormalTabIndex >= closedIndex) activeNormalTabIndex--
                if (activeNormalTabIndex < 0) activeNormalTabIndex = 0
                switchTab(activeNormalTabIndex)
            } else {
                tabCountBtn.text = currentTabsList.size.toString()
            }
            dialog.dismiss()
            showFullScreenTabs()
        }, false)

        val privateAdapter = TabsAdapter(privateTabs, { clickedIndex ->
            isPrivateMode = true
            switchTab(clickedIndex)
            dialog.dismiss()
        }, { closedIndex ->
            val tabToRemove = privateTabs[closedIndex]
            webContainer.removeView(tabToRemove.webView)
            tabToRemove.webView.destroy()
            privateTabs.removeAt(closedIndex)
            if (isPrivateMode) {
                if (activePrivateTabIndex >= closedIndex) activePrivateTabIndex--
                if (activePrivateTabIndex < 0) activePrivateTabIndex = 0
                switchTab(activePrivateTabIndex)
            } else {
                tabCountBtn.text = currentTabsList.size.toString()
            }
            dialog.dismiss()
            showFullScreenTabs()
        }, true)

        rvActiveTabs.layoutManager = LinearLayoutManager(this)

        var currentTouchHelper: ItemTouchHelper? = null
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))

        fun updateUI() {
            if (viewingPrivate) {
                btnTogglePersonal.background = null
                btnTogglePersonal.setTextColor(Color.parseColor("#5F6368"))
                btnTogglePrivate.background = getDrawable(R.drawable.bg_popup_rounded)
                btnTogglePrivate.setTextColor(Color.parseColor("#202124"))

                tvCurrentModeLabel.text = "Private Tabs"
                btnNewTabAction.text = "+ New Private"
                btnNewTabAction.setTextColor(accentColor)
                
                rvActiveTabs.adapter = privateAdapter
                currentTouchHelper?.attachToRecyclerView(null)
                currentTouchHelper = attachDragAndDrop(rvActiveTabs, privateTabs, privateAdapter, true)
            } else {
                btnTogglePersonal.background = getDrawable(R.drawable.bg_popup_rounded)
                btnTogglePersonal.setTextColor(Color.parseColor("#202124"))
                btnTogglePrivate.background = null
                btnTogglePrivate.setTextColor(Color.parseColor("#5F6368"))

                tvCurrentModeLabel.text = "Personal Tabs"
                btnNewTabAction.text = "+ New Tab"
                btnNewTabAction.setTextColor(accentColor)
                
                rvActiveTabs.adapter = personalAdapter
                currentTouchHelper?.attachToRecyclerView(null)
                currentTouchHelper = attachDragAndDrop(rvActiveTabs, normalTabs, personalAdapter, false)
            }
        }

        btnTogglePersonal.setOnClickListener {
            triggerStrongHaptic(it)
            viewingPrivate = false
            updateUI()
        }

        btnTogglePrivate.setOnClickListener {
            triggerStrongHaptic(it)
            viewingPrivate = true
            updateUI()
        }

        btnNewTabAction.setOnClickListener {
            triggerStrongHaptic(it)
            dialog.dismiss()
            isPrivateMode = viewingPrivate
            addNewTab(HAZE_HOME_URL)
        }

        updateUI()
        dialog.show()
    }

    private fun attachDragAndDrop(recyclerView: RecyclerView, tabList: MutableList<Tab>, adapter: TabsAdapter, isPrivateList: Boolean): ItemTouchHelper {
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            private var dragFrom = -1
            private var dragTo = -1
            
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val from = viewHolder.adapterPosition
                val to = target.adapterPosition
                if (dragFrom == -1) dragFrom = from
                dragTo = to
                Collections.swap(tabList, from, to)
                
                if (isPrivateMode == isPrivateList) {
                    if (activeTabIndex == from) {
                        if (isPrivateMode) activePrivateTabIndex = to else activeNormalTabIndex = to
                    } else if (activeTabIndex == to) {
                        if (isPrivateMode) activePrivateTabIndex = from else activeNormalTabIndex = from
                    }
                }
                
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    val droppedTab = tabList[dragTo]
                    val adjacentIndex = if (dragTo > 0) dragTo - 1 else dragTo + 1
                    if (adjacentIndex in 0 until tabList.size) {
                        val adjacentTab = tabList[adjacentIndex]
                        if (droppedTab.group?.id != adjacentTab.group?.id || droppedTab.group == null) {
                            showGroupPromptDialog(droppedTab, adjacentTab, adapter)
                        }
                    }
                }
                dragFrom = -1
                dragTo = -1
            }
        })
        itemTouchHelper.attachToRecyclerView(recyclerView)
        return itemTouchHelper
    }

    private fun showGroupPromptDialog(tab1: Tab, tab2: Tab, adapter: TabsAdapter) {
        AlertDialog.Builder(this)
            .setTitle("Group Tabs?")
            .setMessage("Do you want to group '${tab1.title}' with '${tab2.title}'?")
            .setPositiveButton("Group") { _, _ ->
                val group = tab2.group ?: TabGroup(
                    id = System.currentTimeMillis().toString(),
                    name = "New Group",
                    color = groupColors.random()
                )
                tab1.group = group
                tab2.group = group
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class TabsAdapter(
        private val list: MutableList<Tab>,
        private val onItemClick: (Int) -> Unit,
        private val onCloseClick: (Int) -> Unit,
        private val isPrivateList: Boolean
    ) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

        inner class TabViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val rowContainer: LinearLayout = view.findViewById(R.id.tabRowContainer)
            val groupHeader: LinearLayout = view.findViewById(R.id.groupHeader)
            val groupColorBox: View = view.findViewById(R.id.groupColorBox)
            val groupNameText: TextView = view.findViewById(R.id.groupNameText)
            
            val groupSideIndicator: View = view.findViewById(R.id.groupSideIndicator)
            val tabIcon: ImageView = view.findViewById(R.id.tabIcon)
            val tabTitle: TextView = view.findViewById(R.id.tabTitle)
            val tabUrl: TextView = view.findViewById(R.id.tabUrl)
            val tabClose: ImageButton = view.findViewById(R.id.tabClose)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
            val view = layoutInflater.inflate(R.layout.item_tab_row, parent, false)
            return TabViewHolder(view)
        }

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            val tab = list[position]
            val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
            
            val prevTab = if (position > 0) list[position - 1] else null
            if (tab.group != null && (prevTab == null || prevTab.group?.id != tab.group?.id)) {
                holder.groupHeader.visibility = View.VISIBLE
                holder.groupColorBox.setBackgroundColor(tab.group!!.color)
                holder.groupNameText.text = tab.group!!.name
                
                holder.groupHeader.setOnClickListener {
                    triggerStrongHaptic(it)
                    showEditGroupDialog(tab.group!!)
                }
            } else {
                holder.groupHeader.visibility = View.GONE
            }

            if (tab.group != null) {
                holder.groupSideIndicator.visibility = View.VISIBLE
                holder.groupSideIndicator.setBackgroundColor(tab.group!!.color)
            } else {
                holder.groupSideIndicator.visibility = View.GONE
            }

            val isActive = (isPrivateMode == isPrivateList && position == activeTabIndex)
            if (isActive) {
                val alphaColor = Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                holder.rowContainer.setBackgroundColor(alphaColor)
            } else {
                holder.rowContainer.setBackgroundColor(Color.TRANSPARENT)
            }

            if (isHomeTab(tab)) {
                holder.tabIcon.setImageResource(R.drawable.ic_haze_logo)
            } else if (tab.favicon != null) {
                holder.tabIcon.setImageBitmap(tab.favicon)
            } else {
                holder.tabIcon.setImageResource(R.drawable.ic_globe)
            }
            
            holder.tabTitle.text = tab.title
            holder.tabUrl.text = if (isHomeTab(tab)) "Home" else tab.url

            holder.rowContainer.setOnClickListener { 
                triggerStrongHaptic(it)
                onItemClick(position) 
            }
            holder.tabClose.setOnClickListener { 
                triggerStrongHaptic(it)
                onCloseClick(position) 
            }
        }

        override fun getItemCount(): Int = list.size

        private fun showEditGroupDialog(group: TabGroup) {
            val layout = LinearLayout(this@MainActivity)
            layout.orientation = LinearLayout.VERTICAL
            layout.setPadding(40, 20, 40, 20)
            
            val input = EditText(this@MainActivity)
            input.setText(group.name)
            layout.addView(input)

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Edit Group")
                .setView(layout)
                .setPositiveButton("Save") { _, _ ->
                    group.name = input.text.toString()
                    group.color = groupColors.random()
                    notifyDataSetChanged()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    fun showPersonalizeDialog() {
        val isDark = prefs.getBoolean("is_dark", false)
        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2"
        val accentColor = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val currentBg = prefs.getString("bg_theme", "default") ?: "default"
        
        var selectedColor = accentColor
        var selectedBg = currentBg

        val surfaceColor = if (isDark) Color.parseColor("#292A24") else Color.parseColor("#FFFFFF")
        val cardColor = if (isDark) Color.parseColor("#37382D") else Color.parseColor("#F1F3F4")
        val textColor = if (isDark) Color.parseColor("#F1F3E8") else Color.parseColor("#202124")
        val secondaryTextColor = if (isDark) Color.parseColor("#C4C6B8") else Color.parseColor("#5F6368")
        val accent = Color.parseColor(accentColor)

        val dialogView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            overScrollMode = View.OVER_SCROLL_NEVER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(28).toFloat()
                setColor(surfaceColor)
            }
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(26), dpToPx(24), dpToPx(18))
        }
        dialogView.addView(content)
        
        val titleText = TextView(this).apply {
            text = "Personalize"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(4))
        }
        content.addView(titleText)

        val subtitleText = TextView(this).apply {
            text = "Customize the look of your Haze home page"
            textSize = 13f
            setTextColor(secondaryTextColor)
            setPadding(0, 0, 0, dpToPx(20))
        }
        content.addView(subtitleText)

        fun addSectionCard(view: View, bottomMargin: Int = 16) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(18).toFloat()
                    setColor(cardColor)
                }
                setPadding(dpToPx(16), dpToPx(14), dpToPx(16), dpToPx(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { this.bottomMargin = dpToPx(bottomMargin) }
            }
            (view.parent as? ViewGroup)?.removeView(view)
            card.addView(view)
            content.addView(card)
        }
        
        // Theme Toggle (Light / Dark)
        val themeLabel = TextView(this).apply {
            text = "Theme"
            setTextColor(secondaryTextColor)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(8))
        }
        content.addView(themeLabel)
        
        val themeGroup = RadioGroup(this).apply { 
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(16)) 
        }
        val rbLight = RadioButton(this).apply {
            text = "Light"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        val rbDark = RadioButton(this).apply {
            text = "Dark"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        themeGroup.addView(rbLight)
        themeGroup.addView(rbDark)
        if (isDark) rbDark.isChecked = true else rbLight.isChecked = true
        addSectionCard(themeGroup)
        
        // Shortcuts Mode Selector
        val shortcutsLabel = TextView(this).apply {
            text = "Shortcuts Rows"
            setTextColor(secondaryTextColor)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(8))
        }
        content.addView(shortcutsLabel)
        
        val shortcutsGroup = RadioGroup(this).apply { 
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(20)) 
        }
        val rbOff = RadioButton(this).apply {
            text = "Off"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        val rb1Row = RadioButton(this).apply {
            text = "1 Row"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        val rb2Row = RadioButton(this).apply {
            text = "2 Rows"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
        }
        shortcutsGroup.addView(rbOff)
        shortcutsGroup.addView(rb1Row)
        shortcutsGroup.addView(rb2Row)
        when (shortcutsMode) {
            "off" -> rbOff.isChecked = true
            "1" -> rb1Row.isChecked = true
            else -> rb2Row.isChecked = true
        }
        addSectionCard(shortcutsGroup)
        
        // Accent Colors (Round circles: Default + 5 solid colors)
        val accentLabel = TextView(this).apply {
            text = "Accent Color (Default + 5 Solid Colors)"
            setTextColor(secondaryTextColor)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(10))
        }
        content.addView(accentLabel)
        
        val colorScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dpToPx(20))
        }
        val colorLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorScroll.addView(colorLayout)
        
        val colors = listOf(
            "#0A56D0", // Default
            "#EA4335", // Red
            "#34A853", // Green
            "#9C27B0", // Purple
            "#FF6D01", // Orange
            "#00897B"  // Teal
        )
        
        val colorContainers = mutableListOf<FrameLayout>()
        fun refreshColorBorders() {
            colorContainers.forEachIndexed { idx, frame ->
                val c = colors[idx]
                if (c.equals(selectedColor, ignoreCase = true)) {
                    val ring = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(3), if (isDark) Color.WHITE else Color.BLACK)
                    }
                    frame.background = ring
                } else {
                    frame.background = null
                }
            }
        }
        
        for (c in colors) {
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(52)).apply {
                    marginEnd = dpToPx(12)
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            val circleView = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(c))
                }
                background = shape
            }
            frame.addView(circleView)
            frame.setOnClickListener {
                triggerStrongHaptic(it)
                selectedColor = c
                refreshColorBorders()
            }
            colorContainers.add(frame)
            colorLayout.addView(frame)
        }
        refreshColorBorders()
        addSectionCard(colorScroll)
        
        // Background Themes (Round circles: Default + 5 Photo Themes)
        val bgLabel = TextView(this).apply {
            text = "Background Theme (Default + 5 Photos)"
            setTextColor(secondaryTextColor)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(10))
        }
        content.addView(bgLabel)
        
        val bgScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dpToPx(16))
        }
        val bgLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bgScroll.addView(bgLayout)
        
        val themes = listOf(
            Pair("default", R.drawable.ic_theme_default),
            Pair("canyon", R.drawable.thumb_theme_canyon),
            Pair("forest", R.drawable.thumb_theme_forest),
            Pair("ocean", R.drawable.thumb_theme_ocean),
            Pair("space", R.drawable.thumb_theme_space),
            Pair("sunset", R.drawable.thumb_theme_sunset)
        )
        
        val bgContainers = mutableListOf<FrameLayout>()
        fun refreshBgBorders() {
            bgContainers.forEachIndexed { idx, frame ->
                val themeId = themes[idx].first
                if (themeId.equals(selectedBg, ignoreCase = true)) {
                    val ring = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(3), Color.parseColor(selectedColor))
                    }
                    frame.background = ring
                } else {
                    frame.background = null
                }
            }
        }
        
        for (theme in themes) {
            val frame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(56), dpToPx(56)).apply {
                    marginEnd = dpToPx(12)
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            val imgView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                setImageResource(theme.second)
            }
            frame.addView(imgView)
            frame.setOnClickListener {
                triggerStrongHaptic(it)
                selectedBg = theme.first
                refreshBgBorders()
            }
            bgContainers.add(frame)
            bgLayout.addView(frame)
        }
        refreshBgBorders()
        addSectionCard(bgScroll, 8)
        
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setPositiveButton("Apply") { _, _ ->
                val mode = when {
                    rbOff.isChecked -> "off"
                    rb1Row.isChecked -> "1"
                    else -> "2"
                }
                prefs.edit()
                    .putBoolean("is_dark", rbDark.isChecked)
                    .putString("shortcuts_mode", mode)
                    .putString("accent_color", selectedColor)
                    .putString("bg_theme", selectedBg)
                    .apply()
                
                applyTheme()
            }
            .setNegativeButton("Cancel", null)
            .create()
            
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(accent)
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(secondaryTextColor)
            isAllCaps = false
        }
    }

    private fun showBrowserMenu() {
        val popupView = layoutInflater.inflate(R.layout.layout_browser_menu, null)
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.elevation = 24f
        
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val menuRoot = popupView.findViewById<LinearLayout>(R.id.menuRoot)
        val menuLogo = popupView.findViewById<ImageView>(R.id.menuLogo)
        val menuDivider = popupView.findViewById<View>(R.id.menuDivider)
        val btnMenuBack = popupView.findViewById<ImageButton>(R.id.btnMenuBack)
        val btnMenuForward = popupView.findViewById<ImageButton>(R.id.btnMenuForward)
        val btnMenuStar = popupView.findViewById<ImageButton>(R.id.btnMenuStar)
        val btnMenuReload = popupView.findViewById<ImageButton>(R.id.btnMenuReload)
        val btnMenuHome = popupView.findViewById<ImageButton>(R.id.btnMenuHome)

        val iconColor = if (isDark) Color.parseColor("#CCCCCC") else Color.parseColor("#5F6368")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        btnMenuBack?.setColorFilter(iconColor)
        btnMenuForward?.setColorFilter(iconColor)
        btnMenuStar?.setColorFilter(accentColor)
        btnMenuReload?.setColorFilter(iconColor)
        btnMenuHome?.setColorFilter(iconColor)

        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        btnMenuBack?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            if (currentTab?.webView?.canGoBack() == true) {
                currentTab.webView.goBack()
            }
        }
        btnMenuForward?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            if (currentTab?.webView?.canGoForward() == true) {
                currentTab.webView.goForward()
            }
        }
        btnMenuStar?.setOnClickListener {
            triggerStrongHaptic(it)
            showStarBookmarkDialog(popupWindow)
        }
        btnMenuReload?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            currentTab?.webView?.reload()
        }
        btnMenuHome?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            loadUrlFromInput(HAZE_HOME_URL)
        }

        val menuSurface = if (isDark) Color.parseColor("#292A24") else Color.parseColor("#FFFFFF")
        val menuStroke = if (isDark) Color.parseColor("#454638") else Color.parseColor("#DADCE0")
        menuLogo.setImageResource(if (isDark) R.drawable.haze_logo_dark else R.drawable.haze_logo)
        menuDivider.setBackgroundColor(if (isDark) Color.parseColor("#454638") else Color.parseColor("#E5E7EB"))
        menuRoot.background = GradientDrawable().apply {
            cornerRadius = dpToPx(22).toFloat()
            setColor(menuSurface)
            setStroke(dpToPx(1), menuStroke)
        }

        val grid = popupView.findViewById<GridLayout>(R.id.menuGrid)
        
        val menuItems = listOf(
            Pair("New Tab", R.drawable.ic_add),
            Pair("New InPrivate", R.drawable.ic_private),
            Pair("Privacy Shield", R.drawable.ic_privacy_shield),
            Pair("Personalize", R.drawable.ic_palette),
            Pair("Favorites", R.drawable.ic_star),
            Pair("History", R.drawable.ic_history),
            Pair("Downloads", R.drawable.ic_download),
            Pair("Full Screen", R.drawable.ic_fullscreen),
            Pair("Reading Mode", R.drawable.ic_reading_mode),
            Pair("Extra", R.drawable.ic_extra),
            Pair("Find on Page", R.drawable.ic_search),
            Pair("Settings", R.drawable.ic_settings),
            Pair("Password Manager", R.drawable.ic_key),
            Pair("Close Haze", R.drawable.ic_close)
        )
        
        for (item in menuItems) {
            val itemView = layoutInflater.inflate(R.layout.item_menu_box, grid, false)
            val iconView = itemView.findViewById<ImageView>(R.id.menuIcon)
            val textView = itemView.findViewById<TextView>(R.id.menuText)
            iconView.setImageResource(item.second)
            textView.text = item.first
            if (isDark) {
                iconView.setColorFilter(Color.parseColor("#E0E0E0"))
                textView.setTextColor(Color.parseColor("#E0E0E0"))
            }
            
            val params = GridLayout.LayoutParams()
            params.width = (74 * resources.displayMetrics.density).toInt()
            params.height = (76 * resources.displayMetrics.density).toInt()
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            itemView.layoutParams = params
            
            itemView.setOnClickListener {
                triggerStrongHaptic(it)
                popupWindow.dismiss()
                when (item.first) {
                    "New Tab" -> addNewTab(HAZE_HOME_URL)
                    "New InPrivate" -> {
                        isPrivateMode = true
                        addNewTab(HAZE_HOME_URL)
                        Toast.makeText(this, "Private mode enabled", Toast.LENGTH_SHORT).show()
                    }
                    "Privacy Shield" -> HazePrivacyShieldDashboard.showDashboard(this)
                    "Personalize" -> showPersonalizeDialog()
                    "Favorites" -> HazeBookmarksManager.showBookmarksDialog(this) { loadUrlFromInput(it) }
                    "History" -> HazeHistoryManager.showHistoryDialog(this) { loadUrlFromInput(it) }
                    "Downloads" -> HazeDownloadsManager.showDownloadsDialog(this)
                    "Full Screen" -> toggleFullScreenMode()
                    "Reading Mode" -> toggleReadingMode()
                    "Extra" -> showExtraToolsDialog()
                    "Find on Page" -> {
                        findBarContainer.visibility = View.VISIBLE
                        findInput.requestFocus()
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                    "Settings" -> HazeSettingsManager.showSettingsDialog(this)
                    "Password Manager" -> openPasswordManager()
                    "Close Haze" -> popupWindow.dismiss()
                }

            }
            grid.addView(itemView)
        }
        
        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = popupView.measuredHeight
        val menuBtn = findViewById<ImageButton>(R.id.menuBtn)
        
        if (menuBtn.isShown) {
            popupWindow.showAsDropDown(menuBtn, 0, -(popupHeight + menuBtn.height + 24))
        } else {
            popupWindow.showAtLocation(webContainer, Gravity.TOP or Gravity.END, 32, 140)
        }
    }

    private fun openPasswordManager() {
        HazePasswordManager.show(this) {
            passwordImportLauncher.launch(arrayOf("text/*", "text/csv", "application/csv"))
        }
    }

    private fun showStarBookmarkDialog(popupWindow: PopupWindow) {
        popupWindow.dismiss()
        val tab = currentTabsList.getOrNull(activeTabIndex)
        if (tab == null || isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Cannot bookmark internal or home pages", Toast.LENGTH_SHORT).show()
            return
        }

        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val cardColor = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#F8F9FA")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(16))
        }

        val titleTv = TextView(this).apply {
            text = "Add to Favorites"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(16))
        }
        layout.addView(titleTv)

        val titleInput = EditText(this).apply {
            setText(tab.title)
            hint = "Bookmark Name"
            setTextColor(textColor)
            setHintTextColor(subTextColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(cardColor)
                setStroke(1, if (isDark) Color.parseColor("#383838") else Color.parseColor("#DADCE0"))
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            textSize = 14.5f
            isSingleLine = true
        }
        layout.addView(titleInput)

        val urlTv = TextView(this).apply {
            text = tab.url
            textSize = 12f
            setTextColor(subTextColor)
            setPadding(0, dpToPx(8), 0, dpToPx(16))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        layout.addView(urlTv)

        val folders = HazeDatabase.getInstance(this).getAllBookmarkFolders()
        val folderNames = mutableListOf("Root (No Folder)")
        val folderIds = mutableListOf(0L)
        for (f in folders) {
            folderNames.add(f.name)
            folderIds.add(f.id)
        }

        val spinnerLabel = TextView(this).apply {
            text = "Save in folder:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            setPadding(0, 0, 0, dpToPx(6))
        }
        layout.addView(spinnerLabel)

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, folderNames)
        }
        layout.addView(spinner)

        AlertDialog.Builder(this)
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                triggerStrongHaptic()
                val name = titleInput.text.toString().trim().ifEmpty { tab.title }
                val selectedPos = spinner.selectedItemPosition
                val folderId = if (selectedPos in folderIds.indices) folderIds[selectedPos] else 0L

                val cachedFavicon = HazeFaviconManager.getCachedFaviconFile(this, tab.url)
                val faviconPath = if (cachedFavicon.exists()) cachedFavicon.absolutePath else null

                HazeDatabase.getInstance(this).addBookmark(name, tab.url, folderId, faviconPath)
                Toast.makeText(this, "Added to Favorites!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toggleFullScreenMode() {
        isFullScreenMode = !isFullScreenMode
        if (isFullScreenMode) {
            addressBarContainer.visibility = View.GONE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            Toast.makeText(this, "Full screen active. Press Back to exit.", Toast.LENGTH_SHORT).show()
        } else {
            addressBarContainer.visibility = View.VISIBLE
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }
    }

    fun toggleReadingMode() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Reading mode is only available on readable articles.", Toast.LENGTH_LONG).show()
            return
        }
        val pageHost = runCatching { Uri.parse(tab.url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        if (pageHost == "youtube.com" || pageHost?.endsWith(".youtube.com") == true ||
            pageHost == "youtube-nocookie.com" || pageHost?.endsWith(".youtube-nocookie.com") == true ||
            pageHost == "netflix.com" || pageHost?.endsWith(".netflix.com") == true ||
            pageHost == "twitch.tv" || pageHost?.endsWith(".twitch.tv") == true
        ) {
            Toast.makeText(this, "Reading mode is not available for video and streaming pages.", Toast.LENGTH_LONG).show()
            return
        }
        
        val readerScript = """
            (function() {
                var selectors = ['article', 'main', '[role="main"]', '.post-content', '.article-content', '.entry-content', '#content'];
                var article = null;
                var bestLength = 0;
                for (var i = 0; i < selectors.length; i++) {
                    var candidates = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < candidates.length; j++) {
                        var candidate = candidates[j];
                        var length = (candidate.innerText || '').replace(/\s+/g, ' ').trim().length;
                        if (length > bestLength) {
                            bestLength = length;
                            article = candidate;
                        }
                    }
                }
                if (!article) article = document.body;
                var copy = article.cloneNode(true);
                var unwanted = copy.querySelectorAll('script, style, noscript, iframe, video, audio, canvas, nav, footer, form, aside');
                for (var k = 0; k < unwanted.length; k++) unwanted[k].remove();
                var plain = (copy.innerText || '').replace(/\s+/g, ' ').trim();
                var paragraphs = copy.querySelectorAll('p').length;
                var title = document.title;
                var readable = plain.length >= 500 && (paragraphs >= 3 || plain.length >= 1100);
                return JSON.stringify({ title: title, html: copy.innerHTML, readable: readable, characters: plain.length });
            })();
        """.trimIndent()
        
        tab.webView.evaluateJavascript(readerScript) { result ->
            try {
                val cleanResult = if (result.startsWith("\"") && result.endsWith("\"")) {
                    org.json.JSONTokener(result).nextValue().toString()
                } else result
                val json = JSONObject(cleanResult)
                if (!json.optBoolean("readable", false)) {
                    Toast.makeText(this, "This page does not contain enough readable article content.", Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }
                val title = json.optString("title", "Reader Mode")
                val contentHtml = json.optString("html", "")
                
                showReaderModeDialog(title, contentHtml)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not format page for reading", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isTtsSpeaking = false
    private var ttsSpeed = 1.0f

    private fun showReaderModeDialog(title: String, contentHtml: String) {
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        // Start with a readable surface that follows the browser theme.
        var currentBg = if (isDark) "#121212" else "#FFF3E0"
        var currentText = if (isDark) "#F1F3F4" else "#5D1A00"
        var currentFont = "'Outfit', sans-serif"
        var currentFontSize = "18px"
        var maxWidth = "680px"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor(currentBg))
        }
        
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(12))
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            elevation = 8f
        }
        
        val btnClose = ImageButton(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            setOnClickListener {
                triggerStrongHaptic(it)
                tts?.stop()
                tts?.shutdown()
                tts = null
                isTtsSpeaking = false
                dialog.dismiss()
            }
        }
        val headerTitle = TextView(this).apply {
            text = "Reading Mode"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(12)
            }
        }

        // Toolbar Action Buttons (Read Aloud, Translate, Appearance)
        val btnReadAloud = ImageButton(this).apply {
            setImageResource(R.drawable.ic_volume_up)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { marginEnd = dpToPx(8) }
            contentDescription = "Read Aloud (Long press for 5+ voices)"
        }

        val btnTranslate = ImageButton(this).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { marginEnd = dpToPx(8) }
            contentDescription = "Translate"
        }

        val btnAppearance = ImageButton(this).apply {
            setImageResource(R.drawable.ic_palette)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            contentDescription = "Appearance Settings"
        }

        header.addView(btnClose)
        header.addView(headerTitle)
        header.addView(btnReadAloud)
        header.addView(btnTranslate)
        header.addView(btnAppearance)
        root.addView(header)
        
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }

        fun reloadReaderContent() {
            val readerHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        @import url('https://fonts.googleapis.com/css2?family=Merriweather:ital,wght@0,300;0,400;0,700;1,300&family=Outfit:wght@300;400;500;600&family=Georgia&family=Sitka+Text&display=swap');
                        body {
                            font-family: $currentFont;
                            line-height: 1.85;
                            font-size: $currentFontSize;
                            padding: 28px 20px 80px 20px;
                            margin: 0 auto;
                            max-width: $maxWidth;
                            color: $currentText;
                            background: $currentBg;
                        }
                        h1 { font-size: 28px; line-height: 1.35; margin-bottom: 24px; font-weight: 700; color: $currentText; }
                        h2, h3 { color: $currentText; margin-top: 28px; }
                        img { max-width: 100%; height: auto; border-radius: 12px; margin: 20px 0; }
                        p { margin-bottom: 20px; }
                        a { color: $accentColor; text-decoration: none; }
                    </style>
                </head>
                <body>
                    <h1>$title</h1>
                    $contentHtml
                </body>
                </html>
            """.trimIndent()
            val currentTab = currentTabsList.getOrNull(activeTabIndex)
            webView.loadDataWithBaseURL(currentTab?.url, readerHtml, "text/html", "UTF-8", null)
            root.setBackgroundColor(Color.parseColor(currentBg))
        }

        reloadReaderContent()
        root.addView(webView)

        // --- TTS Implementation (Google TTS Engine) ---
        tts = TextToSpeech(this, { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            } else {
                Toast.makeText(this, "Google Text-to-Speech is unavailable on this device.", Toast.LENGTH_LONG).show()
            }
        }, googleTtsEngine)

        btnReadAloud.setOnClickListener {
            triggerStrongHaptic(it)
            if (isTtsSpeaking) {
                tts?.stop()
                isTtsSpeaking = false
                Toast.makeText(this, "Read aloud paused", Toast.LENGTH_SHORT).show()
            } else {
                val plainText = contentHtml.replace(Regex("<[^>]*>"), "")
                tts?.setSpeechRate(ttsSpeed)
                tts?.speak(plainText, TextToSpeech.QUEUE_FLUSH, null, null)
                isTtsSpeaking = true
                Toast.makeText(this, "Reading aloud...", Toast.LENGTH_SHORT).show()
            }
        }

        btnReadAloud.setOnLongClickListener {
            triggerStrongHaptic(it)
            val availableVoices = tts?.voices
                ?.filter { it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired }
                ?: emptyList()
            if (availableVoices.isEmpty()) {
                Toast.makeText(this, "Install Google Text-to-Speech voices to customize reading aloud.", Toast.LENGTH_LONG).show()
                return@setOnLongClickListener true
            }
            val female = availableVoices.filter {
                val name = it.name.lowercase()
                name.contains("female") || name.contains("woman") || name.contains("f1") ||
                    name.contains("f2") || name.contains("f3")
            }
            val male = availableVoices.filter {
                val name = it.name.lowercase()
                name.contains("male") || name.contains("man") || name.contains("m1") ||
                    name.contains("m2")
            }
            val selectedVoices = (female.take(3) + male.take(2) +
                availableVoices.filterNot { it in female || it in male }).distinct().take(5)
            val labels = selectedVoices.mapIndexed { index, voice ->
                val group = if (voice in female.take(3)) "Female ${index + 1}" else "Male ${index + 1}"
                "$group - ${voice.locale.displayName}"
            }
            val settings = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(20), dpToPx(4), dpToPx(20), dpToPx(8))
            }
            val voiceGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
            selectedVoices.forEachIndexed { index, voice ->
                voiceGroup.addView(RadioButton(this).apply {
                    text = labels[index]
                    setTextColor(if (prefs.getBoolean("is_dark", false)) Color.WHITE else Color.parseColor("#202124"))
                    isChecked = tts?.voice?.name == voice.name
                    setOnClickListener {
                        tts?.voice = voice
                    }
                })
            }
            settings.addView(voiceGroup)
            val speedLabel = TextView(this).apply {
                text = "Playback speed: ${"%.1f".format(Locale.US, ttsSpeed)}x"
                setTextColor(if (prefs.getBoolean("is_dark", false)) Color.WHITE else Color.parseColor("#202124"))
                setPadding(0, dpToPx(12), 0, 0)
            }
            settings.addView(speedLabel)
            val speedSeek = SeekBar(this).apply {
                max = 15
                progress = ((ttsSpeed - 0.5f) * 10).toInt()
                progressTintList = ColorStateList.valueOf(accentColor)
                thumbTintList = ColorStateList.valueOf(accentColor)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        ttsSpeed = 0.5f + progress / 10f
                        speedLabel.text = "Playback speed: ${"%.1f".format(Locale.US, ttsSpeed)}x"
                        tts?.setSpeechRate(ttsSpeed)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                })
            }
            settings.addView(speedSeek)
            AlertDialog.Builder(this)
                .setTitle("Google TTS Voice & Speed")
                .setView(settings)
                .setPositiveButton("Done", null)
                .show()
            true
        }

        // --- Translate Implementation ---
        btnTranslate.setOnClickListener {
            triggerStrongHaptic(it)
            val languages = arrayOf("Spanish", "French", "German", "Hindi", "Japanese", "Chinese", "Arabic", "Portuguese", "Russian")
            AlertDialog.Builder(this)
                .setTitle("Translate Article To")
                .setItems(languages) { _, which ->
                    val langCode = when (languages[which]) {
                        "Spanish" -> "es"
                        "French" -> "fr"
                        "German" -> "de"
                        "Hindi" -> "hi"
                        "Japanese" -> "ja"
                        "Chinese" -> "zh"
                        "Arabic" -> "ar"
                        "Portuguese" -> "pt"
                        "Russian" -> "ru"
                        else -> "es"
                    }
                    val currentTab = currentTabsList.getOrNull(activeTabIndex)
                    if (currentTab != null) {
                        val transUrl = "https://translate.google.com/translate?sl=auto&tl=$langCode&u=${Uri.encode(currentTab.url)}"
                        dialog.dismiss()
                        loadUrlFromInput(transUrl)
                    }
                }
                .show()
        }

        // --- Appearance Settings Popup (Fonts, Font Size, Column Width, Themes) ---
        btnAppearance.setOnClickListener {
            triggerStrongHaptic(it)
            val appearanceSurface = if (isDark) Color.parseColor("#292A24") else Color.WHITE
            val appearanceText = if (isDark) Color.parseColor("#F1F3E8") else Color.parseColor("#202124")
            val popView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(24).toFloat()
                    setColor(appearanceSurface)
                }
            }

            // Font Family Selector
            val tvFontLbl = TextView(this).apply {
                text = "Font Family"
                setTypeface(null, Typeface.BOLD)
                setTextColor(appearanceText)
            }
            val fontSpinner = Spinner(this).apply {
                val fonts = arrayOf("Outfit (Default)", "Serif (Georgia)", "Sitka", "Merriweather", "Monospace")
                adapter = object : ArrayAdapter<String>(
                    this@MainActivity,
                    android.R.layout.simple_spinner_item,
                    fonts
                ) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                        return super.getView(position, convertView, parent).apply {
                            (this as? TextView)?.setTextColor(appearanceText)
                        }
                    }

                    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                        return super.getDropDownView(position, convertView, parent).apply {
                            (this as? TextView)?.setTextColor(appearanceText)
                        }
                    }
                }.also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        currentFont = when (position) {
                            1 -> "'Georgia', serif"
                            2 -> "'Sitka Text', serif"
                            3 -> "'Merriweather', serif"
                            4 -> "monospace"
                            else -> "'Outfit', sans-serif"
                        }
                        reloadReaderContent()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            }
            popView.addView(tvFontLbl)
            popView.addView(fontSpinner)

            // Font Size
            val tvSizeLbl = TextView(this).apply {
                text = "Font Size"
                setTypeface(null, Typeface.BOLD)
                setTextColor(appearanceText)
                setPadding(0, dpToPx(16), 0, 0)
            }
            val sizeSeek = SeekBar(this).apply {
                max = 16
                progress = 4 // default 18px
                progressTintList = ColorStateList.valueOf(accentColor)
                thumbTintList = ColorStateList.valueOf(accentColor)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentFontSize = "${14 + progress}px"
                        reloadReaderContent()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            popView.addView(tvSizeLbl)
            popView.addView(sizeSeek)

            // Column Width
            val tvWidthLbl = TextView(this).apply {
                text = "Column Width"
                setTypeface(null, Typeface.BOLD)
                setTextColor(appearanceText)
                setPadding(0, dpToPx(16), 0, 0)
            }
            val widthSeek = SeekBar(this).apply {
                max = 5
                progress = 2 // default 680px
                progressTintList = ColorStateList.valueOf(accentColor)
                thumbTintList = ColorStateList.valueOf(accentColor)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        maxWidth = "${500 + progress * 80}px"
                        reloadReaderContent()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            popView.addView(tvWidthLbl)
            popView.addView(widthSeek)

            // Color Themes
            val tvThemeLbl = TextView(this).apply {
                text = "Reading Theme"
                setTypeface(null, Typeface.BOLD)
                setTextColor(appearanceText)
                setPadding(0, dpToPx(16), 0, dpToPx(8))
            }
            popView.addView(tvThemeLbl)

            val themeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val themes = listOf(
                Pair("Paper Cream", Pair("#FDFBF7", "#202124")),
                Pair("White", Pair("#FFFFFF", "#202124")),
                Pair("Dark", Pair("#121212", "#E0E0E0")),
                Pair("Mint", Pair("#E6F4EA", "#137333")),
                Pair("Blue", Pair("#E8F0FE", "#1967D2")),
                Pair("Yellow", Pair("#FEF7E0", "#B06000"))
            )
            for (th in themes) {
                val btn = Button(this).apply {
                    text = th.first.take(1)
                    layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { marginEnd = dpToPx(6) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(th.second.first))
                        setStroke(2, Color.parseColor(th.second.second))
                    }
                    setOnClickListener {
                        currentBg = th.second.first
                        currentText = th.second.second
                        reloadReaderContent()
                    }
                }
                themeRow.addView(btn)
            }
            popView.addView(themeRow)

            val appearanceDialog = AlertDialog.Builder(this)
                .setTitle("Appearance Settings")
                .setView(popView)
                .setPositiveButton("Done", null)
                .create()
            appearanceDialog.show()
            appearanceDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            appearanceDialog.findViewById<TextView>(
                resources.getIdentifier("alertTitle", "id", "android")
            )?.setTextColor(appearanceText)
            appearanceDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(accentColor)
                isAllCaps = false
                setTypeface(null, Typeface.BOLD)
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    fun showExtraToolsDialog() {
        val tab = currentTabsList.getOrNull(activeTabIndex)
        val isDark = prefs.getBoolean("is_dark", false)
        
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(24))
        }
        
        val titleTv = TextView(this).apply {
            text = "Extra Tools"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(16))
        }
        root.addView(titleTv)
        
        fun addTool(label: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48))
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                setBackgroundResource(getSelectableRes())
                setOnClickListener {
                    triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368"))
                layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                    marginEnd = dpToPx(16)
                }
            }
            val text = TextView(this).apply {
                this.text = label
                textSize = 15f
                setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }
        
        val isDesktop = tab?.isDesktop == true
        addTool(if (isDesktop) "Mobile Site" else "Desktop Site", R.drawable.ic_desktop) {
            tab?.let { toggleDesktopSite(it) }
        }
        addTool("Bookmark this page", R.drawable.ic_star) {
            if (tab != null && !isHomeTab(tab)) {
                HazeBookmarksManager.showBookmarkThisPageDialog(this, tab.title, tab.url)
            } else {
                Toast.makeText(this, "Cannot bookmark this page", Toast.LENGTH_SHORT).show()
            }
        }
        addTool("Share page", R.drawable.ic_share) {
            shareCurrentPage()
        }
        addTool("Print / Save as PDF", R.drawable.ic_download) {
            printCurrentPage()
        }
        addTool("Capture Screenshot", R.drawable.ic_image) {
            captureCurrentTabScreenshot()
        }
        addTool("Floating Miniplayer (PiP)", R.drawable.ic_fullscreen) {
            val success = enterMiniplayerPip()
            if (!success) {
                Toast.makeText(this, "Miniplayer requires Android 8.0+ or active media playback", Toast.LENGTH_SHORT).show()
            }
        }
        addTool("Page Details", R.drawable.ic_globe) {
            showPageDetails()
        }
        addTool("Clear Browsing Data", R.drawable.ic_close) {
            clearBrowsingData()
        }
        
        dialog.setContentView(root)
        dialog.show()
    }

    fun toggleDesktopSite(tab: Tab) {
        tab.isDesktop = !tab.isDesktop
        val settings = tab.webView.settings
        if (tab.isDesktop) {
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            Toast.makeText(this, "Desktop site enabled", Toast.LENGTH_SHORT).show()
        } else {
            settings.userAgentString = null
            Toast.makeText(this, "Mobile site enabled", Toast.LENGTH_SHORT).show()
        }
        tab.webView.reload()
    }

    fun shareCurrentPage() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Cannot share this page", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, tab.url)
        }
        startActivity(Intent.createChooser(intent, "Share page"))
    }

    fun printCurrentPage() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Cannot print this page", Toast.LENGTH_SHORT).show()
            return
        }
        val printManager = getSystemService(PRINT_SERVICE) as? android.print.PrintManager
        val printAdapter = tab.webView.createPrintDocumentAdapter("Haze - " + tab.title)
        printManager?.print("Haze - " + tab.title, printAdapter, android.print.PrintAttributes.Builder().build())
    }

    fun captureCurrentTabScreenshot() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        try {
            val bitmap = Bitmap.createBitmap(tab.webView.width, tab.webView.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            tab.webView.draw(canvas)
            
            val file = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Screenshot"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to capture screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun clearTabsCache() {
        runOnUiThread {
            for (tab in normalTabs) tab.webView.clearCache(true)
            for (tab in privateTabs) tab.webView.clearCache(true)
        }
    }

    fun clearBrowsingData(onCleared: (() -> Unit)? = null) {
        HazeClearDataManager.showClearDataDialog(this, onCleared)
    }

    fun showPageDetails() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val cert = tab.webView.certificate
        val isSecure = tab.url.startsWith("https://") || tab.url.startsWith("haze://") || tab.url == HAZE_HOME_URL
        val message = StringBuilder().apply {
            append("URL: ${tab.url}\n\n")
            append("Security: ${if (isSecure) "Secure Haze Page (HTTPS)" else "Not Secure (HTTP)"}\n\n")
            if (cert != null) {
                append("Issued to: ${cert.issuedTo.cName}\n")
                append("Issued by: ${cert.issuedBy.cName}\n")
                append("Valid until: ${cert.validNotAfterDate}\n")
            } else if (tab.url.startsWith("haze://") || tab.url == HAZE_HOME_URL) {
                append("Issued to: Haze Private Root Authority\n")
                append("Issued by: Haze Core Security\n")
                append("Status: Verified & Secure Internal Page\n")
            }
        }.toString()
        
        AlertDialog.Builder(this)
            .setTitle(tab.title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    fun openPrivateTab(url: String) {
        isPrivateMode = true
        addNewTab(url)
    }

    fun addNewBackgroundTab(url: String) {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val tab = Tab(webView = webView, isPrivate = isPrivateMode, url = url, title = "New Tab")
        currentTabsList.add(tab)
        webContainer.addView(webView)
        setupWebView(webView)
        webView.visibility = View.GONE
        webView.loadUrl(url)
        tabCountBtn.text = currentTabsList.size.toString()
        Toast.makeText(this, "Opened in background tab", Toast.LENGTH_SHORT).show()
    }

    private fun getOfflineErrorHtml(url: String, isDark: Boolean): String {
        val bgColor = if (isDark) "#121212" else "#FFFFFF"
        val textColor = if (isDark) "#FFFFFF" else "#202124"
        val subTextColor = if (isDark) "#9AA0A6" else "#5F6368"
        val accentColor = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val artworkFilter = if (isDark) "filter: invert(0.88) hue-rotate(180deg); opacity: 0.92;" else ""

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600&display=swap');
                    html, body {
                        margin: 0; padding: 0; height: 100vh; width: 100vw;
                        display: flex; flex-direction: column; align-items: center; justify-content: center;
                        background: $bgColor;
                        font-family: 'Outfit', sans-serif;
                        color: $textColor;
                        text-align: center;
                        overflow: hidden;
                    }
                    .container {
                        display: flex; flex-direction: column; align-items: center; max-width: 380px; padding: 24px;
                    }
                    .astronaut-art {
                        width: 280px; height: 186px; margin-bottom: 24px; object-fit: contain;
                        $artworkFilter
                    }
                    h1 { font-size: 22px; font-weight: 600; margin-bottom: 8px; }
                    p { font-size: 14px; color: $subTextColor; margin-bottom: 28px; line-height: 1.5; }
                    .btn-reload {
                        background: $accentColor; color: white; border: none; border-radius: 24px;
                        padding: 12px 32px; font-size: 15px; font-weight: 600; cursor: pointer;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.15); transition: transform 0.1s ease;
                    }
                    .btn-reload:active { transform: scale(0.96); }
                </style>
            </head>
            <body>
                <div class="container">
                    <img class="astronaut-art" src="file:///android_asset/offline_astronaut_cat.png" alt="Astronaut and cat offline illustration">
                    <h1>No internet connection</h1>
                    <p>You're offline. Check your connection or Wi-Fi and try again.</p>
                    <button class="btn-reload" onclick="location.reload()">Try Again</button>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun showPageLoading(progress: Int) {
        val progressBar = findViewById<ProgressBar>(R.id.pageProgressBar) ?: return
        pageLoadingGeneration++
        progressBar.animate().cancel()
        progressBar.visibility = View.VISIBLE
        progressBar.alpha = 1f
        progressBar.progress = progress.coerceIn(1, 99)
        progressBar.bringToFront()
    }

    private fun finishPageLoading() {
        val progressBar = findViewById<ProgressBar>(R.id.pageProgressBar) ?: return
        val completedGeneration = pageLoadingGeneration
        progressBar.progress = 100
        progressBar.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                if (completedGeneration != pageLoadingGeneration) return@withEndAction
                progressBar.visibility = View.GONE
                progressBar.alpha = 1f
                progressBar.progress = 0
            }
            .start()
    }

    fun updateWebSettings() {
        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        for (tab in normalTabs) {
            tab.webView.settings.javaScriptEnabled = jsEnabled
        }
        for (tab in privateTabs) {
            tab.webView.settings.javaScriptEnabled = jsEnabled
        }
    }

    private fun getSelectableRes(): Int {
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Voice search is not supported on this device.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupAddressBar() {
        addressBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                triggerStrongHaptic(addressBar)
                loadUrlFromInput(addressBar.text.toString())
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                addressBar.clearFocus()
                true
            } else false
        }
    }

    fun loadUrlFromInput(input: String) {
        if (activeTabIndex == -1) return
        val query = input.trim()
        if (query.isEmpty()) return

        if (query == HAZE_HOME_URL || query == "haze://home" || query.startsWith("haze://home")) {
            val tab = currentTabsList[activeTabIndex]
            tab.url = HAZE_HOME_URL
            tab.title = "New Tab"
            tab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
            deactivateSearchMode()
            addressBar.setText("")
            updateChromeVisibility(HAZE_HOME_URL)
            return
        }

        if (query == "haze://favorites" || query == "haze://bookmarks") {
            HazeBookmarksManager.showBookmarksDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            deactivateSearchMode()
            return
        }
        if (query == "haze://history") {
            HazeHistoryManager.showHistoryDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            deactivateSearchMode()
            return
        }
        if (query == "haze://downloads") {
            HazeDownloadsManager.showDownloadsDialog(this)
            deactivateSearchMode()
            return
        }
        if (query == "haze://settings") {
            HazeSettingsManager.showSettingsDialog(this)
            deactivateSearchMode()
            return
        }
        if (query == "haze://shield" || query == "haze://privacy" || query == "haze://adblock") {
            HazePrivacyShieldDashboard.showDashboard(this)
            deactivateSearchMode()
            return
        }

        val searchEngine = prefs.getString("search_engine", "Google") ?: "Google"
        val searchEngineUrl = when (searchEngine) {
            "Bing" -> "https://www.bing.com/search?q="
            "DuckDuckGo" -> "https://duckduckgo.com/?q="
            "Yahoo" -> "https://search.yahoo.com/search?p="
            "Ecosia" -> "https://www.ecosia.org/search?q="
            else -> "https://www.google.com/search?q="
        }

        val url = if (URLUtil.isValidUrl(query)) query
        else if (query.contains(".") && !query.contains(" ")) "https://$query"
        else "$searchEngineUrl${Uri.encode(query)}"

        val tab = currentTabsList[activeTabIndex]
        if (tab.url == HAZE_HOME_URL) tab.title = "Loading..."
        tab.url = url
        
        saveRecentSearch(query)
        deactivateSearchMode()
        
        tab.webView.loadUrl(url)
    }

    fun toggleMediaPlayback() {
        if (miniPlayerManager?.isActive == true) {
            miniPlayerManager?.togglePlayback()
            return
        }
        val currentTab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val toggleScript = """
            (function() {
                var media = document.querySelectorAll('video, audio');
                for (var i = 0; i < media.length; i++) {
                    var m = media[i];
                    if (!m.paused && !m.ended) {
                        m.pause();
                        return 'paused';
                    }
                }
                for (var i = 0; i < media.length; i++) {
                    var m = media[i];
                    if (m.paused && m.readyState > 1) {
                        m.play();
                        return 'playing';
                    }
                }
                return 'none';
            })();
        """.trimIndent()
        currentTab.webView.evaluateJavascript(toggleScript) { result ->
            val isPlaying = result?.contains("playing") == true
            isMediaPlaying = isPlaying
            updatePipAutoEnter()
        }
    }

    fun buildPipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        val builder = PictureInPictureParams.Builder()

        val w = if (lastVideoWidth > 0) lastVideoWidth else 16
        val h = if (lastVideoHeight > 0) lastVideoHeight else 9
        val rational = try {
            val r = Rational(w, h)
            val f = r.toFloat()
            if (f in 0.42f..2.38f) r else Rational(16, 9)
        } catch (e: Exception) {
            Rational(16, 9)
        }
        builder.setAspectRatio(rational)

        // Actions: Play / Pause in PiP window
        try {
            val isPlaying = isMediaPlaying
            val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            val title = if (isPlaying) "Pause" else "Play"
            val intent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                this,
                101,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val action = RemoteAction(
                Icon.createWithResource(this, iconRes),
                title,
                title,
                pendingIntent
            )
            builder.setActions(listOf(action))
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Android 12+ (API 31+): auto-enter support
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isAutoPipEnabled = prefs.getBoolean("auto_pip_enabled", true)
            val currentTab = currentTabsList.getOrNull(activeTabIndex)
            val isHome = miniPlayerManager?.isActive != true && (currentTab == null || isHomeTab(currentTab))
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val isAudioActive = audioManager?.isMusicActive == true
            val shouldAutoEnter = isAutoPipEnabled && !isHome && (isMediaPlaying || customVideoView != null || isAudioActive)
            builder.setAutoEnterEnabled(shouldAutoEnter)
        }

        return builder.build()
    }

    fun updatePipAutoEnter(enabled: Boolean = prefs.getBoolean("auto_pip_enabled", true)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams()
            if (params != null) {
                try {
                    setPictureInPictureParams(params)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun enterMiniplayerPip(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val isPipSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
            if (!isPipSupported) return false
            val params = buildPipParams()
            return try {
                if (params != null) {
                    enterPictureInPictureMode(params)
                } else {
                    @Suppress("DEPRECATION")
                    enterPictureInPictureMode()
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
        return false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val isAutoPipEnabled = prefs.getBoolean("auto_pip_enabled", true)
        if (!isAutoPipEnabled) return

        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        val isHome = miniPlayerManager?.isActive != true && (currentTab == null || isHomeTab(currentTab))
        if (isHome) return

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isAudioActive = audioManager?.isMusicActive == true
        val hasMedia = isMediaPlaying || customVideoView != null || isAudioActive

        if (hasMedia) {
            enterMiniplayerPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            addressBarContainer.visibility = View.GONE
            findBarContainer.visibility = View.GONE
            recentSearchesContainer.visibility = View.GONE
            dimOverlay.visibility = View.GONE
            if (::topRightActions.isInitialized) {
                topRightActions.visibility = View.GONE
            }
            if (::floatingNavContainer.isInitialized) {
                floatingNavContainer.visibility = View.GONE
            }
            if (customVideoView != null) {
                fullscreenVideoContainer.visibility = View.VISIBLE
                webContainer.visibility = View.GONE
            } else {
                webContainer.visibility = View.VISIBLE
            }
        } else {
            if (customVideoView != null) {
                fullscreenVideoContainer.visibility = View.VISIBLE
                webContainer.visibility = View.GONE
                addressBarContainer.visibility = View.GONE
            } else {
                fullscreenVideoContainer.visibility = View.GONE
                webContainer.visibility = View.VISIBLE
                val currentTab = currentTabsList.getOrNull(activeTabIndex)
                updateChromeVisibility(currentTab?.url)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(suggestionDebounce)
        suggestionExecutor.shutdownNow()
        miniPlayerManager?.release()
        super.onDestroy()
        try {
            unregisterReceiver(pipReceiver)
        } catch (e: Exception) {}
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customVideoView != null) {
                    customViewCallback?.onCustomViewHidden()
                    fullscreenVideoContainer.removeAllViews()
                    fullscreenVideoContainer.visibility = View.GONE
                    webContainer.visibility = View.VISIBLE
                    customVideoView = null
                    customViewCallback = null
                    val currentTab = currentTabsList.getOrNull(activeTabIndex)
                    updateChromeVisibility(currentTab?.url)
                    return
                }
                if (isSearchActive) {
                    deactivateSearchMode()
                } else if (isFullScreenMode) {
                    toggleFullScreenMode()
                } else if (findBarContainer.visibility == View.VISIBLE) {
                    findBarContainer.visibility = View.GONE
                    currentTabsList.getOrNull(activeTabIndex)?.webView?.clearMatches()
                    val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(findInput.windowToken, 0)
                } else if (activeTabIndex != -1 && currentTabsList[activeTabIndex].webView.canGoBack()) {
                    val tab = currentTabsList[activeTabIndex]
                    if (tab.url != HAZE_HOME_URL && !tab.url.startsWith("haze://")) tab.webView.goBack() 
                    else {
                        isEnabled = false; onBackPressedDispatcher.onBackPressed()
                    }
                } else if (currentTabsList.size > 1) {
                    val tabToRemove = currentTabsList[activeTabIndex]
                    webContainer.removeView(tabToRemove.webView)
                    tabToRemove.webView.destroy()
                    currentTabsList.removeAt(activeTabIndex)
                    
                    if (isPrivateMode) {
                        if (activePrivateTabIndex >= currentTabsList.size) activePrivateTabIndex = currentTabsList.size - 1
                        switchTab(activePrivateTabIndex)
                    } else {
                        if (activeNormalTabIndex >= currentTabsList.size) activeNormalTabIndex = currentTabsList.size - 1
                        switchTab(activeNormalTabIndex)
                    }
                } else {
                    isEnabled = false; onBackPressedDispatcher.onBackPressed() 
                }
            }
        })
    }
}
