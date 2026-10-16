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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.net.http.SslError
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.VelocityTracker
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.card.MaterialCardView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.FileProvider
import com.example.haze.engine.*
import com.example.haze.engine.chromium.*
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import java.util.concurrent.Executors
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64

class MainActivity : AppCompatActivity() {

    companion object {
        val BUTTER_INTERPOLATOR = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
        val BUTTER_ACCEL = PathInterpolator(0.2f, 0.0f, 0.4f, 1.0f)
        val FAST_INTERPOLATOR = DecelerateInterpolator(2.0f)
    }

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

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = if (result.resultCode == RESULT_OK && result.data != null) {
            val clipData = result.data?.clipData
            if (clipData != null) {
                Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
            } else {
                result.data?.data?.let { arrayOf(it) }
            }
        } else null
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
    }

    private var pendingPermissionRequest: PermissionRequest? = null
    private val webPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingPermissionRequest ?: return@registerForActivityResult
        pendingPermissionRequest = null
        val grantedResources = mutableListOf<String>()
        if (grants[android.Manifest.permission.CAMERA] == true && req.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
            grantedResources.add(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
        }
        if (grants[android.Manifest.permission.RECORD_AUDIO] == true && req.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
            grantedResources.add(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
        }
        if (grantedResources.isNotEmpty()) {
            req.grant(grantedResources.toTypedArray())
        } else {
            req.deny()
        }
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
    // Chromium can report dozens of progress ticks per page.  Updating Android
    private val renderedProgressByTab = mutableMapOf<Long, Int>()
    private val suggestionExecutor = Executors.newFixedThreadPool(2)
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val suggestionCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>?): Boolean {
                return size > 64
            }
        }
    )
    private val dismissedSuggestions = Collections.synchronizedSet(mutableSetOf<String>())
    private var suggestionRequestId = 0L
    private val suggestionDebounce = Runnable {
        val query = getUserTypedQuery()
        if (isSearchActive && query.isNotEmpty()) {
            val requestId = ++suggestionRequestId
            fetchSearchSuggestions(query) { suggestions ->
                if (requestId == suggestionRequestId &&
                    isSearchActive &&
                    getUserTypedQuery() == query
                ) {
                    renderSearchSuggestions(query, suggestions)
                }
            }
        }
    }

    private lateinit var webContainer: FrameLayout
    internal lateinit var addressBar: EditText
    private lateinit var tabCountBtn: TextView
    private lateinit var btnTabsCircular: FrameLayout
    private lateinit var addressBarContainer: LinearLayout
    private lateinit var clipboardContainer: FrameLayout
    private lateinit var searchSuggestionStrip: ScrollView
    private lateinit var suggestionChipContainer: LinearLayout
    private lateinit var findBarContainer: LinearLayout
    private lateinit var findInput: EditText

    private lateinit var dimOverlay: View
    private lateinit var btnClearQuery: ImageButton
    private lateinit var btnQrScan: ImageButton
    private lateinit var searchEngineSelectorBar: HorizontalScrollView
    private lateinit var searchEngineChipContainer: LinearLayout
    private lateinit var omniboxManager: HazeOmniboxManager
    private var isSearchActive = false
    private var imeAnimationRunning = false
    private var isAutocompleting = false
    private var isUserTypingForward = false

    private val topDomainsList = listOf(
        "youtube.com", "google.com", "facebook.com", "instagram.com", "twitter.com", "x.com",
        "wikipedia.org", "reddit.com", "amazon.com", "netflix.com", "github.com", "linkedin.com",
        "yahoo.com", "twitch.tv", "bing.com", "openai.com", "chatgpt.com", "discord.com",
        "spotify.com", "whatsapp.com", "tiktok.com", "pinterest.com", "duckduckgo.com",
        "apple.com", "microsoft.com", "gmail.com", "maps.google.com"
    )

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

    var onCustomDirSelected: ((Uri) -> Unit)? = null

    val downloadDirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {}
            prefs.edit().putString("custom_download_dir_uri", uri.toString()).apply()
            onCustomDirSelected?.invoke(uri)
            onCustomDirSelected = null
        }
    }

    private val deviceAccountPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
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

    fun reloadHomeTabsIfOpen() {
        runOnUiThread {
            val activeTab = currentTabsList.getOrNull(activeTabIndex)
            if (activeTab != null && isHomeTab(activeTab)) {
                activeTab.url = HAZE_HOME_URL
                activeTab.browserTab.navigationController.loadDataWithBaseURL(
                    HAZE_HOME_URL,
                    getHazeHomeHtml(),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }
    }

    fun updateProfileButton() {
        runOnUiThread {
            if (!::btnProfile.isInitialized || !::ivProfileAvatar.isInitialized) return@runOnUiThread
            val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
            val isGuest = HazeAccountManager.isGuestMode(this)
            val user = HazeAccountManager.getUser(this)

            if (isGuest) {
                ivProfileAvatar.clearColorFilter()
                ivProfileAvatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                val p = dpToPx(5)
                ivProfileAvatar.setPadding(p, p, p, p)
                ivProfileAvatar.setImageResource(R.drawable.ic_private)
                ivProfileAvatar.setColorFilter(if (isDark) Color.parseColor("#80CBC4") else Color.parseColor("#00897B"))
            } else if (user != null && !isPrivateMode) {
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

    fun updatePrivacyShieldButton() {
        val searchIcon = findViewById<ImageView>(R.id.searchIcon) ?: return
        val currentTab = getActiveTab()
        val isHome = currentTab == null || isHomeTab(currentTab) || currentTab.url == HAZE_HOME_URL || currentTab.url.isEmpty()
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val hintColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)

        if (!isHome && !isSearchActive) {
            val shieldEnabled = HazePrivacyShieldEngine.isShieldEnabled(this)
            searchIcon.setImageResource(R.drawable.ic_privacy_shield)
            searchIcon.setColorFilter(if (shieldEnabled) accentColor else hintColor)
            searchIcon.setOnClickListener {
                triggerStrongHaptic(isHeavy = false)
                HazePrivacyShieldDashboard.showDashboard(this@MainActivity)
            }
        } else {
            searchIcon.setImageResource(R.drawable.ic_search)
            searchIcon.setColorFilter(hintColor)
            searchIcon.setOnClickListener(null)
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
        miniPlayerManager?.attach(tab.browserTab, tab.title)
        if (createBrowsingTab) addNewTab(HAZE_HOME_URL)
    }

    enum class TabSortMode {
        DEFAULT,
        RECENT,
        DOMAIN
    }

    data class TabGroup(var id: String, var name: String, var color: Int, var isCollapsed: Boolean = false)
    data class Shortcut(val name: String, val url: String, val iconUrl: String = "", val svgPath: String = "", val fillColor: String = "")
    data class ClosedTabInfo(val title: String, val url: String, val timestamp: Long = System.currentTimeMillis(), val isPrivate: Boolean = false)
    data class Tab(
        val id: Long = System.currentTimeMillis(),
        val browserTab: IBrowserTab,
        val swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null,
        var title: String = "New Tab",
        var url: String = "haze://home",
        var favicon: Bitmap? = null,
        var isPrivate: Boolean = false,
        var group: TabGroup? = null,
        private var initialIsDesktop: Boolean = false,
        var isPinned: Boolean = false,
        var lastAccessedTime: Long = System.currentTimeMillis()
    ) {
        val webView: WebView? get() = (browserTab as? ChromiumBrowserTab)?.webView

        var isDesktop: Boolean
            get() = browserTab.isDesktop
            set(value) {
                browserTab.isDesktop = value
            }

        init {
            if (initialIsDesktop) {
                browserTab.isDesktop = true
            }
        }

        constructor(
            id: Long = System.currentTimeMillis(),
            webView: WebView,
            swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null,
            title: String = "New Tab",
            url: String = "haze://home",
            favicon: Bitmap? = null,
            isPrivate: Boolean = false,
            group: TabGroup? = null,
            isDesktop: Boolean = false,
            isPinned: Boolean = false,
            lastAccessedTime: Long = System.currentTimeMillis()
        ) : this(
            id = id,
            browserTab = ChromiumBrowserTab(webView.context, id, isPrivate),
            swipeRefreshLayout = swipeRefreshLayout,
            title = title,
            url = url,
            favicon = favicon,
            isPrivate = isPrivate,
            group = group,
            initialIsDesktop = isDesktop,
            isPinned = isPinned,
            lastAccessedTime = lastAccessedTime
        )
    }

    private val normalTabs = mutableListOf<Tab>()
    private val privateTabs = mutableListOf<Tab>()
    private val recentlyClosedTabs = mutableListOf<ClosedTabInfo>()
    var isPrivateMode = false
    private var isFullScreenMode = false
    private var activeFullScreenTabsDialog: Dialog? = null
    
    private val currentTabsList: MutableList<Tab>
        get() = if (isPrivateMode) privateTabs else normalTabs

    private var activeNormalTabIndex = -1
    private var activePrivateTabIndex = -1
    private val activeTabIndex: Int
        get() = if (isPrivateMode) activePrivateTabIndex else activeNormalTabIndex

    fun applyAntiTrackingCookiePolicy() {
        normalTabs.forEach { tab ->
            (tab.browserTab.renderView as? WebView)?.let { wv ->
                HazeAntiTrackingEngine.configureWebViewCookies(wv, this, isPrivate = false)
            }
        }
        privateTabs.forEach { tab ->
            (tab.browserTab.renderView as? WebView)?.let { wv ->
                HazeAntiTrackingEngine.configureWebViewCookies(wv, this, isPrivate = true)
            }
        }
    }


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

    private val hapticExecutor = Executors.newSingleThreadExecutor()

    fun triggerStrongHaptic(view: View? = null, isHeavy: Boolean = false) {
        try {
            view?.performHapticFeedback(
                if (isHeavy) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            hapticExecutor.execute {
                try {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(VIBRATOR_SERVICE) as? Vibrator
                    }
                    if (vibrator != null && vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            val duration = if (isHeavy) 45L else 20L
                            val amplitude = if (isHeavy) 200 else 120
                            vibrator.vibrate(VibrationEffect.createOneShot(duration, amplitude))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(if (isHeavy) 45L else 20L)
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun getTabForInterface(ownerTab: IBrowserTab?, ownerWebView: WebView?): Tab? {
        if (ownerTab != null) {
            return normalTabs.find { it.browserTab === ownerTab }
                ?: privateTabs.find { it.browserTab === ownerTab }
                ?: currentTabsList.getOrNull(activeTabIndex)
        }
        if (ownerWebView != null) {
            return getTabFromWebView(ownerWebView)
        }
        return currentTabsList.getOrNull(activeTabIndex)
    }

    inner class WebAppInterface(
        private val ownerTab: IBrowserTab? = null,
        private val ownerWebView: WebView? = null
    ) {
        constructor(webView: WebView) : this(ownerTab = null, ownerWebView = webView)
        constructor(browserTab: IBrowserTab) : this(ownerTab = browserTab, ownerWebView = null)

        @JavascriptInterface
        fun onNonEnglishPageDetected(detectedLang: String, detectedCode: String? = null) {
            runOnUiThread {
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
                if (!isHomeTab(tab)) {
                    HazeTranslationManager.showSuggestionBanner(this@MainActivity, tab, detectedLang, detectedCode ?: "")
                }
            }
        }

        @JavascriptInterface
        fun onTranslatedPageLoaded(targetLang: String) {
            runOnUiThread {
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
                if (!isHomeTab(tab)) {
                    HazeTranslationManager.showActiveTranslationBar(this@MainActivity, tab, targetLang)
                }
            }
        }

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
        fun onElementInspected(selector: String) {
            runOnUiThread {
                if (HazeDevToolsManager.isDevToolsVisible(this@MainActivity)) {
                    HazeDevToolsManager.focusElement(selector)
                } else {
                    val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
                    HazeDevToolsManager.showDevTools(this@MainActivity, tab, "ELEMENTS", selector)
                }
            }
        }

        @JavascriptInterface
        fun clearConsoleLogs() {
            runOnUiThread {
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
                HazeDevToolsManager.clearConsoleLogsOnly(tab.id)
            }
        }

        @JavascriptInterface
        fun onDevToolsConsoleResult(result: String, isError: Boolean) {
            runOnUiThread {
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
                HazeDevToolsManager.recordConsoleMessage(
                    tabId = tab.id,
                    message = result,
                    level = if (isError) HazeDevToolsManager.ConsoleLogEntry.Level.ERROR else HazeDevToolsManager.ConsoleLogEntry.Level.INFO
                )
            }
        }
        
        @JavascriptInterface
        fun launchMiniPlayer() { 
            runOnUiThread { 
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: return@runOnUiThread
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
        fun recordFingerprintAttempt(type: String) {
            runOnUiThread {
                val tab = getTabForInterface(ownerTab, ownerWebView) ?: getActiveTab()
                val pageUrl = tab?.url ?: return@runOnUiThread
                val host = HazeSitePermissionManager.cleanHost(pageUrl)
                if (host.isNotEmpty()) {
                    HazePrivacyShieldEngine.recordFingerprintAttemptForHost(this@MainActivity, host, type)
                    HazeAntiTrackingEngine.incrementFingerprintAttemptsBlocked()
                }
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
        fun onPopupBlocked(blockedUrl: String) {
            runOnUiThread {
                val isPriv = (getTabForInterface(ownerTab, ownerWebView)?.isPrivate) ?: isPrivateMode
                HazePrivacyShieldEngine.recordPopupBlocked(
                    this@MainActivity,
                    blockedUrl,
                    ownerTab?.url ?: ownerWebView?.url,
                    isPriv
                )
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
                val tab = getTabForInterface(ownerTab, ownerWebView)
                isMediaPlaying = isPlaying
                if (isPlaying) activeMediaTab = tab
                else if (activeMediaTab === tab) activeMediaTab = null
                if (width > 0 && height > 0) {
                    lastVideoWidth = width
                    lastVideoHeight = height
                }
            }
        }
        @JavascriptInterface
        fun retryLoad(url: String) {
            runOnUiThread {
                triggerStrongHaptic()
                val tab = getTabForInterface(ownerTab, ownerWebView)
                val fallbackUrl = tab?.url ?: ownerWebView?.url ?: ""
                val target = if (url.isNotEmpty() && url != "about:blank") url else fallbackUrl
                if (target.isNotEmpty() && target != "about:blank") {
                    loadUrlFromInput(target)
                } else {
                    tab?.browserTab?.reload() ?: ownerWebView?.reload()
                }
            }
        }
        @JavascriptInterface
        fun goHome() {
            runOnUiThread {
                triggerStrongHaptic()
                loadUrlFromInput(HAZE_HOME_URL)
            }
        }
        @JavascriptInterface
        fun notifyFormSubmitted() {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        val afm = getSystemService(android.view.autofill.AutofillManager::class.java)
                        afm?.commit()
                    } catch (_: Exception) {}
                }
                HazeSecurityManager.persistCookieStore()
            }
        }
    }

    private fun wakeScreen() {
        try {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val km = getSystemService(KEYGUARD_SERVICE) as? android.app.KeyguardManager
                km?.requestDismissKeyguard(this, null)
            }
            val pm = getSystemService(POWER_SERVICE) as? android.os.PowerManager
            @Suppress("DEPRECATION")
            val wl = pm?.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Haze:WakeUpLock"
            )
            wl?.acquire(60000L)
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("HazePrefs", MODE_PRIVATE)

        webContainer = findViewById(R.id.webContainer)
        fullscreenVideoContainer = findViewById(R.id.fullscreenVideoContainer)
        addressBar = findViewById(R.id.addressBar)
        tabCountBtn = findViewById(R.id.tabCountBtn)
        btnTabsCircular = findViewById(R.id.btnTabsCircular)
        addressBarContainer = findViewById(R.id.addressBarContainer)
        val menuBtn: ImageButton = findViewById(R.id.menuBtn)
        val btnVoiceSearch: ImageButton = findViewById(R.id.btnVoiceSearch)

        dimOverlay = findViewById(R.id.dimOverlay)
        clipboardContainer = findViewById(R.id.clipboardContainer)
        searchSuggestionStrip = findViewById(R.id.searchSuggestionStrip)
        searchSuggestionStrip.isNestedScrollingEnabled = true
        suggestionChipContainer = findViewById(R.id.suggestionChipContainer)
        btnClearQuery = findViewById(R.id.btnClearQuery)
        btnQrScan = findViewById(R.id.btnQrScan)
        searchEngineSelectorBar = findViewById(R.id.searchEngineSelectorBar)
        searchEngineChipContainer = findViewById(R.id.searchEngineChipContainer)
        omniboxManager = HazeOmniboxManager(this, prefs)

        btnQrScan.setOnClickListener {
            triggerStrongHaptic(it)
            omniboxManager.startQrScanner()
        }

        dimOverlay.setOnClickListener { deactivateSearchMode() }

        btnClearQuery.setOnClickListener {
            triggerStrongHaptic(it)
            addressBar.setText("")
            addressBar.requestFocus()
        }

        addressBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isUserTypingForward = !isAutocompleting && count > before
            }

            override fun afterTextChanged(s: Editable?) {
                if (isAutocompleting) return
                val text = s?.toString() ?: ""
                val trimmed = text.trim()
                updateClearQueryButtonVisibility(animate = true)
                
                if (isSearchActive) {
                    if (trimmed.isEmpty()) {
                        suggestionRequestId++
                        mainHandler.removeCallbacks(suggestionDebounce)
                        renderRecentSearchesAndShortcuts()
                    } else {
                        mainHandler.removeCallbacks(suggestionDebounce)
                        mainHandler.postDelayed(suggestionDebounce, 150L)
                    }
                }
            }
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

        findViewById<LinearLayout>(R.id.addressBarInner)?.setOnClickListener {
            triggerStrongHaptic(it)
            activateSearchMode()
        }

        findBarContainer = findViewById(R.id.findBarContainer)
        findInput = findViewById(R.id.findInput)
        val btnFindPrev: ImageButton = findViewById(R.id.btnFindPrev)
        val btnFindNext: ImageButton = findViewById(R.id.btnFindNext)
        val btnFindClose: ImageButton = findViewById(R.id.btnFindClose)

        val tvFindCount = findViewById<TextView>(R.id.tvFindCount)
        btnFindClose.setOnClickListener {
            triggerStrongHaptic(it)
            hideFindBar()
        }
        btnFindNext.setOnClickListener { 
            triggerStrongHaptic(it)
            currentTabsList.getOrNull(activeTabIndex)?.browserTab?.findNext(true) 
        }
        btnFindPrev.setOnClickListener { 
            triggerStrongHaptic(it)
            currentTabsList.getOrNull(activeTabIndex)?.browserTab?.findNext(false) 
        }

        findInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                val currentTab = currentTabsList.getOrNull(activeTabIndex)
                if (q.isNotEmpty()) {
                    currentTab?.browserTab?.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
                        runOnUiThread {
                            if (numberOfMatches > 0) {
                                tvFindCount.visibility = View.VISIBLE
                                tvFindCount.text = "${activeMatchOrdinal + 1}/$numberOfMatches"
                            } else {
                                tvFindCount.visibility = View.VISIBLE
                                tvFindCount.text = "0/0"
                            }
                        }
                    }
                    currentTab?.browserTab?.findAllAsync(q)
                } else {
                    tvFindCount.visibility = View.GONE
                    tvFindCount.text = ""
                    currentTab?.browserTab?.clearMatches()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        findInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                currentTabsList.getOrNull(activeTabIndex)?.browserTab?.findNext(true)
                true
            } else false
        }

        val rootView = findViewById<View>(R.id.main)
        ViewCompat.setWindowInsetsAnimationCallback(
            rootView,
            object : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                override fun onPrepare(animation: WindowInsetsAnimationCompat) {
                    super.onPrepare(animation)
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        imeAnimationRunning = true
                    }
                }

                override fun onProgress(
                    insets: WindowInsetsCompat,
                    runningAnimations: MutableList<WindowInsetsAnimationCompat>
                ): WindowInsetsCompat {
                    if (runningAnimations.any {
                            (it.typeMask and WindowInsetsCompat.Type.ime()) != 0
                        }) {
                        imeAnimationRunning = true
                    }
                    val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                    val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                    val lift = (imeBottom - navBottom).coerceAtLeast(0)
                    if (isSearchActive || (::findBarContainer.isInitialized && findBarContainer.visibility == View.VISIBLE)) {
                        addressBarContainer.translationY = -lift.toFloat()
                        if (::findBarContainer.isInitialized && findBarContainer.visibility == View.VISIBLE) {
                            findBarContainer.translationY = -lift.toFloat()
                        }
                    } else {
                        addressBarContainer.translationY = 0f
                        if (::findBarContainer.isInitialized) {
                            findBarContainer.translationY = 0f
                        }
                    }
                    return insets
                }

                override fun onEnd(animation: WindowInsetsAnimationCompat) {
                    super.onEnd(animation)
                    if ((animation.typeMask and WindowInsetsCompat.Type.ime()) != 0) {
                        imeAnimationRunning = false
                    }
                    val currentInsets = ViewCompat.getRootWindowInsets(rootView)
                    val imeVisible = currentInsets?.isVisible(WindowInsetsCompat.Type.ime()) == true
                    if (!imeVisible) {
                        addressBarContainer.translationY = 0f
                        if (::findBarContainer.isInitialized) {
                            findBarContainer.translationY = 0f
                        }
                    }
                }
            }
        )

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())

            v.setPadding(navBars.left, statusBars.top, navBars.right, navBars.bottom)

            if (!imeAnimationRunning) {
                val lift = (imeInsets.bottom - navBars.bottom).coerceAtLeast(0)
                if (isSearchActive || (::findBarContainer.isInitialized && findBarContainer.visibility == View.VISIBLE)) {
                    addressBarContainer.translationY = -lift.toFloat()
                    if (::findBarContainer.isInitialized && findBarContainer.visibility == View.VISIBLE) {
                        findBarContainer.translationY = -lift.toFloat()
                    }
                } else {
                    addressBarContainer.translationY = 0f
                    if (::findBarContainer.isInitialized) {
                        findBarContainer.translationY = 0f
                    }
                }

                val devToolsDock = findViewById<FrameLayout>(R.id.devToolsDockContainer)
                if (devToolsDock != null && devToolsDock.visibility == View.VISIBLE) {
                    val lp = devToolsDock.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
                    if (lp != null && lp.bottomMargin != lift) {
                        lp.bottomMargin = lift
                        devToolsDock.layoutParams = lp
                    }
                }
            }
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
                        if (tab.browserTab.renderView.parent == null) {
                            val tabContainer = tab.swipeRefreshLayout
                            if (tabContainer != null) {
                                if (tabContainer.parent == null) webContainer.addView(tabContainer)
                                tabContainer.addView(tab.browserTab.renderView)
                            } else {
                                webContainer.addView(tab.browserTab.renderView)
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
        
        val profileGestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                triggerStrongHaptic(btnProfile)
                HazeProfileDialogManager.showProfilePanel(this@MainActivity)
                return true
            }

            override fun onFling(
                e1: android.view.MotionEvent?,
                e2: android.view.MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    val deltaY = e2.y - e1.y
                    val deltaX = Math.abs(e2.x - e1.x)
                    // Downward swipe detected: vertical movement > 30dp and velocityY > 200
                    if (deltaY > dpToPx(24) && velocityY > 200 && deltaY > deltaX) {
                        triggerStrongHaptic(isHeavy = true)
                        // Animate profile icon downward spring effect
                        ivProfileAvatar.animate()
                            .translationY(dpToPx(12).toFloat())
                            .alpha(0.5f)
                            .setDuration(120)
                            .withEndAction {
                                val nextUser = HazeAccountManager.cycleToNextProfile(this@MainActivity)
                                updateProfileButton()
                                reloadHomeTabsIfOpen()
                                ivProfileAvatar.translationY = -dpToPx(12).toFloat()
                                ivProfileAvatar.animate().translationY(0f).alpha(1.0f).setDuration(160).start()
                                if (HazeAccountManager.isGuestMode(this@MainActivity)) {
                                    Toast.makeText(this@MainActivity, "Switched to Guest Mode", Toast.LENGTH_SHORT).show()
                                } else if (nextUser != null) {
                                    Toast.makeText(this@MainActivity, "Switched to ${nextUser.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            }.start()
                        return true
                    }
                }
                return false
            }
        })

        btnProfile.setOnTouchListener { v, event ->
            if (profileGestureDetector.onTouchEvent(event)) {
                true
            } else if (event.action == android.view.MotionEvent.ACTION_UP) {
                v.performClick()
                true
            } else {
                true
            }
        }

        HazeAccountManager.addAccountListener {
            updateProfileButton()
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
        setupSwipeBetweenTabs()
        setupWebEdgeSwipeGestures()
        val initialUrl = intent?.dataString?.takeIf { it.isNotEmpty() } ?: HAZE_HOME_URL
        // Clear any orphaned private sessions on startup to ensure strict isolation
        ChromiumEngineManager.getEngine(this).clearPrivateSession(null)
        // Schedule Faster Reloading background warmup for top candidate sites
        HazeFasterReloadingEngine.scheduleStartupWarmup(this)

        val startupMode = prefs.getString("startup_mode", "home") ?: "home"
        val shouldContinueSession = (startupMode == "resume")
        val hasRestored = if (shouldContinueSession && initialUrl == HAZE_HOME_URL) {
            restoreSavedSessionOnStartup()
        } else false

        if (!hasRestored) {
            addNewTab(HAZE_HOME_URL)
            if (initialUrl != HAZE_HOME_URL) {
                loadUrlFromInput(initialUrl)
            }
        }
        updateProfileButton()
        updatePrivacyShieldButton()

        // Silent sign-in in background
        HazeAccountManager.silentSignIn(this) {
            updateProfileButton()
            checkAndPromptGoogleWebLogin()
        }

        // First-launch onboarding check
        val mainContainer = findViewById<ViewGroup>(R.id.main)
        HazeOnboardingManager.showOnboardingIfNeeded(this, mainContainer)

        // Google Password Manager Autofill check & integration
        HazeSecurityManager.checkAndPromptGooglePasswordManager(this)

        checkAndPromptGoogleWebLogin()
        if (intent != null && (intent.hasExtra("open_dns_protection") || intent.hasExtra("open_settings") || intent.hasExtra("open_menu") || intent.hasExtra("toggle_orientation") || intent.hasExtra("toggle_desktop"))) {
            onNewIntent(intent)
        }
    }

    fun checkAndPromptGoogleWebLogin() {
        if (!HazeAccountManager.isSignedIn(this)) return
        if (HazeAccountManager.hasCompletedGoogleWebLogin(this)) return
        val user = HazeAccountManager.getUser(this) ?: return
        val secPrefs = getSharedPreferences("HazeSecurityPrefs", Context.MODE_PRIVATE)
        if (secPrefs.getBoolean("prompted_google_web_login_v2", false)) return
        secPrefs.edit().putBoolean("prompted_google_web_login_v2", true).apply()

        AlertDialog.Builder(this)
            .setTitle("Connect Google Services")
            .setMessage("You are signed into Haze as ${user.displayName} (${user.email}).\n\nWould you like to connect your Google web services (Gmail, YouTube, Google Search) now so you stay signed in everywhere across the browser?")
            .setPositiveButton("Connect Now") { _, _ ->
                val loginUrl = "https://accounts.google.com/ServiceLogin?Email=${Uri.encode(user.email)}&login_hint=${Uri.encode(user.email)}&continue=https://www.google.com/"
                loadUrlFromInput(loginUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun updateClearQueryButtonVisibility(animate: Boolean = false) {
        val shouldShow = addressBar.text.isNotEmpty() && isSearchActive
        if (!animate) {
            btnClearQuery.visibility = if (shouldShow) View.VISIBLE else View.GONE
            btnClearQuery.alpha = if (shouldShow) 1f else 0f
            return
        }
        if (shouldShow) {
            if (btnClearQuery.visibility != View.VISIBLE) {
                btnClearQuery.alpha = 0f
                btnClearQuery.scaleX = 0.7f
                btnClearQuery.scaleY = 0.7f
                btnClearQuery.visibility = View.VISIBLE
                btnClearQuery.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180L)
                    .setInterpolator(BUTTER_INTERPOLATOR)
                    .start()
            }
        } else {
            if (btnClearQuery.visibility == View.VISIBLE) {
                btnClearQuery.animate()
                    .alpha(0f)
                    .scaleX(0.7f)
                    .scaleY(0.7f)
                    .setDuration(150L)
                    .setInterpolator(BUTTER_INTERPOLATOR)
                    .withEndAction { btnClearQuery.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun showFindBar() {
        findBarContainer.animate().cancel()
        findBarContainer.alpha = 0f
        findBarContainer.translationY = 40f
        findBarContainer.visibility = View.VISIBLE
        findBarContainer.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .setInterpolator(BUTTER_INTERPOLATOR)
            .start()
        findInput.requestFocus()
        findInput.postDelayed({
            WindowCompat.getInsetsController(window, findInput).show(WindowInsetsCompat.Type.ime())
        }, 80L)
    }

    private fun hideFindBar() {
        findBarContainer.animate().cancel()
        findBarContainer.animate()
            .alpha(0f)
            .translationY(30f)
            .setDuration(200L)
            .setInterpolator(BUTTER_ACCEL)
            .withEndAction {
                findBarContainer.visibility = View.GONE
                findBarContainer.translationY = 0f
            }
            .start()
        val tvFindCount = findViewById<TextView>(R.id.tvFindCount)
        tvFindCount?.visibility = View.GONE
        tvFindCount?.text = ""
        findInput.setText("")
        val tab = currentTabsList.getOrNull(activeTabIndex)
        tab?.browserTab?.setFindListener(null)
        tab?.browserTab?.clearMatches()
        WindowCompat.getInsetsController(window, findInput).hide(WindowInsetsCompat.Type.ime())
        findInput.clearFocus()
    }

    private fun activateSearchMode() {
        if (isSearchActive) {
            val text = getUserTypedQuery()
            if (text.isEmpty()) {
                renderRecentSearchesAndShortcuts()
            }
            return
        }
        isSearchActive = true
        updatePrivacyShieldButton()

        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        dimOverlay.setBackgroundColor(if (isDark) Color.parseColor("#CC101216") else Color.parseColor("#66000000"))
        dimOverlay.alpha = 0f
        dimOverlay.visibility = View.VISIBLE
        dimOverlay.animate().cancel()
        dimOverlay.animate().alpha(1f).setDuration(180L).start()

        dimOverlay.bringToFront()
        addressBarContainer.bringToFront()
        addressBarContainer.elevation = dpToPx(30).toFloat()

        btnTabsCircular.visibility = View.GONE
        if (::topRightActions.isInitialized) {
            topRightActions.visibility = View.GONE
        }
        findViewById<ImageButton>(R.id.menuBtn)?.visibility = View.GONE
        btnQrScan.visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.btnVoiceSearch)?.visibility = View.VISIBLE
        searchEngineSelectorBar.visibility = View.VISIBLE
        omniboxManager.renderSearchEngineChips(searchEngineChipContainer) { selectedEngine ->
            val typed = getUserTypedQuery()
            if (typed.isNotEmpty()) {
                val url = omniboxManager.buildSearchUrl(selectedEngine, typed)
                loadUrlFromInput(url)
            }
        }

        val text = getUserTypedQuery()
        if (text.isEmpty()) {
            renderRecentSearchesAndShortcuts()
        } else {
            mainHandler.removeCallbacks(suggestionDebounce)
            mainHandler.post(suggestionDebounce)
        }

        updateClearQueryButtonVisibility(animate = true)

        addressBar.requestFocus()
        addressBar.post {
            if (isSearchActive) {
                WindowCompat.getInsetsController(window, addressBar).show(WindowInsetsCompat.Type.ime())
            }
        }
    }

    private fun deactivateSearchMode() {
        if (!isSearchActive) return
        isSearchActive = false
        updatePrivacyShieldButton()

        // 1. Hide keyboard smoothly via WindowInsetsController
        WindowCompat.getInsetsController(window, addressBar).hide(WindowInsetsCompat.Type.ime())
        addressBar.clearFocus()

        // 2. Hide suggestions strip & Omnibox controls
        if (::clipboardContainer.isInitialized) {
            clipboardContainer.removeAllViews()
            clipboardContainer.visibility = View.GONE
        }
        searchSuggestionStrip.visibility = View.GONE
        suggestionChipContainer.removeAllViews()
        searchEngineSelectorBar.visibility = View.GONE
        btnQrScan.visibility = View.GONE
        btnTabsCircular.visibility = View.VISIBLE
        findViewById<ImageButton>(R.id.menuBtn)?.visibility = View.VISIBLE

        updateClearQueryButtonVisibility(animate = true)

        // 3. Dismiss overlay hides smoothly
        dimOverlay.animate().cancel()
        dimOverlay.animate().alpha(0f).setDuration(150L).withEndAction {
            dimOverlay.visibility = View.GONE
        }.start()

        // 4. Reset translation if keyboard is closed
        val root = findViewById<View>(R.id.main)
        val imeVisible = ViewCompat.getRootWindowInsets(root)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (!imeVisible) {
            addressBarContainer.animate().cancel()
            addressBarContainer.animate()
                .translationY(0f)
                .setDuration(90L)
                .setInterpolator(FAST_INTERPOLATOR)
                .start()
        }

        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        val isHome = currentTab != null && isHomeTab(currentTab)

        if (::topRightActions.isInitialized) {
            topRightActions.animate().cancel()
            val shouldShow = isHome && !isPrivateMode
            if (shouldShow) {
                topRightActions.visibility = View.VISIBLE
                topRightActions.alpha = 0f
                topRightActions.bringToFront()
                topRightActions.animate()
                    .alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(BUTTER_INTERPOLATOR)
                    .start()
            } else {
                topRightActions.visibility = View.GONE
            }
        }
        if (::floatingNavContainer.isInitialized) {
            floatingNavContainer.animate().cancel()
            val shouldShowNav = !isHome && customVideoView == null && miniPlayerManager?.isActive != true
            if (shouldShowNav) {
                floatingNavContainer.visibility = View.VISIBLE
                floatingNavContainer.alpha = 0f
                floatingNavContainer.bringToFront()
                updateNavigationButtons()
                floatingNavContainer.animate()
                    .alpha(1f)
                    .setDuration(260L)
                    .setInterpolator(BUTTER_INTERPOLATOR)
                    .start()
            } else {
                floatingNavContainer.visibility = View.GONE
            }
        }
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
        renderRecentSearchesAndShortcuts()
    }

    private fun deleteRecentSearch(item: String) {
        HazeDatabase.getInstance(this).deleteRecentSearch(item)
        renderRecentSearchesAndShortcuts()
    }

    data class SuggestionRowItem(
        val title: String,
        val subtitle: String? = null,
        val iconRes: Int,
        val target: String,
        val isRecent: Boolean = false,
        val isHistory: Boolean = false,
        val isBookmark: Boolean = false,
        val isShortcut: Boolean = false
    )

    private fun applySuggestionTheme(isDark: Boolean) {
        val cardColor = if (isPrivateMode) {
            Color.parseColor("#1E1E1E")
        } else if (isDark) {
            Color.parseColor("#1E1E1E")
        } else {
            Color.WHITE
        }
        val strokeColor = if (isPrivateMode || isDark) {
            Color.parseColor("#333333")
        } else {
            Color.parseColor("#E0E0E0")
        }

        val bgCard = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(24f).toFloat()
            setColor(cardColor)
            setStroke(dpToPx(1f), strokeColor)
        }
        searchSuggestionStrip.background = bgCard
        searchSuggestionStrip.clipToOutline = true
        searchSuggestionStrip.elevation = dpToPx(6f).toFloat()
    }

    private fun adjustSuggestionStripHeight(itemCount: Int, hasClipboard: Boolean = false) {
        val rowHeight = dpToPx(50)
        val maxAllowedHeight = if (hasClipboard) dpToPx(160) else dpToPx(270)
        val computedHeight = (itemCount * rowHeight) + dpToPx(12)
        val params = searchSuggestionStrip.layoutParams
        if (computedHeight > maxAllowedHeight) {
            params.height = maxAllowedHeight
        } else {
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        searchSuggestionStrip.layoutParams = params
    }

    private fun collapseAndDeleteRow(rowContainer: View, item: SuggestionRowItem) {
        val initialHeight = rowContainer.height.takeIf { it > 0 } ?: dpToPx(50)
        val anim = ValueAnimator.ofInt(initialHeight, 0)
        anim.duration = 180L
        anim.interpolator = FAST_INTERPOLATOR
        anim.addUpdateListener { va ->
            val value = va.animatedValue as Int
            val lp = rowContainer.layoutParams
            lp.height = value
            rowContainer.layoutParams = lp
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                suggestionChipContainer.removeView(rowContainer)
                val remainingCount = suggestionChipContainer.childCount
                if (remainingCount == 0) {
                    searchSuggestionStrip.visibility = View.GONE
                } else {
                    val hasClipboard = ::clipboardContainer.isInitialized && clipboardContainer.visibility == View.VISIBLE
                    adjustSuggestionStripHeight(remainingCount, hasClipboard = hasClipboard)
                }
                executeDeleteSuggestionItem(item)
            }
        })
        anim.start()
    }

    private fun executeDeleteSuggestionItem(item: SuggestionRowItem) {
        val lowerTarget = item.target.lowercase(Locale.US)
        val lowerTitle = item.title.lowercase(Locale.US)
        dismissedSuggestions.add(lowerTarget)
        dismissedSuggestions.add(lowerTitle)
        suggestionCache.clear()

        suggestionExecutor.execute {
            try {
                val db = HazeDatabase.getInstance(this@MainActivity)
                // Delete recent searches
                db.deleteRecentSearch(item.target)
                db.deleteRecentSearch(item.title)
                // Delete history
                db.deleteHistoryByUrl(item.target)
                // Delete bookmark if it was bookmark
                if (item.isBookmark) {
                    db.deleteBookmarkByUrl(item.target)
                }
                // Delete shortcut if it was shortcut
                if (item.isShortcut) {
                    val list = getCustomShortcuts().filterNot {
                        it.url.equals(item.target, ignoreCase = true) || it.name.equals(item.title, ignoreCase = true)
                    }
                    mainHandler.post { saveCustomShortcuts(list) }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error deleting suggestion item", e)
            }
        }
        val displayTitle = if (item.title.length > 25) item.title.take(22) + "..." else item.title
        Toast.makeText(this, "Deleted \"$displayTitle\"", Toast.LENGTH_SHORT).show()
    }

    private fun createSuggestionRowView(item: SuggestionRowItem, isDark: Boolean): View {
        val rowContainer = layoutInflater.inflate(R.layout.item_suggestion_row, suggestionChipContainer, false) as FrameLayout
        val bgDelete = rowContainer.findViewById<View>(R.id.layoutSwipeDeleteBackground)
        val foreground = rowContainer.findViewById<View>(R.id.layoutSuggestionForeground)
        val ivIcon = rowContainer.findViewById<ImageView>(R.id.ivSuggestionIcon)
        val tvTitle = rowContainer.findViewById<TextView>(R.id.tvSuggestionTitle)
        val tvSub = rowContainer.findViewById<TextView>(R.id.tvSuggestionSubtitle)
        val btnFill = rowContainer.findViewById<ImageButton>(R.id.btnFillQuery)

        val cardBgColor = if (isPrivateMode || isDark) Color.parseColor("#1E1E1E") else Color.WHITE
        val rippleColor = if (isPrivateMode || isDark) Color.parseColor("#26FFFFFF") else Color.parseColor("#14000000")
        foreground.background = RippleDrawable(
            ColorStateList.valueOf(rippleColor),
            ColorDrawable(cardBgColor),
            ColorDrawable(Color.WHITE)
        )

        val titleColor = if (isPrivateMode || isDark) Color.WHITE else Color.parseColor("#202124")
        val subColor = if (isPrivateMode || isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val iconColor = if (isPrivateMode || isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        ivIcon.setImageResource(item.iconRes)
        ivIcon.setColorFilter(iconColor)
        tvTitle.text = item.title
        tvTitle.setTextColor(titleColor)
        if (!item.subtitle.isNullOrBlank() && item.subtitle != item.title) {
            tvSub.text = item.subtitle
            tvSub.setTextColor(subColor)
            tvSub.visibility = View.VISIBLE
        } else {
            tvSub.visibility = View.GONE
        }
        btnFill.setColorFilter(iconColor)

        btnFill.setOnClickListener {
            triggerStrongHaptic(it)
            if (item.target == "haze://qr") {
                deactivateSearchMode()
                omniboxManager.startQrScanner()
            } else if (item.target.startsWith("haze://switch_tab/")) {
                val idx = item.target.removePrefix("haze://switch_tab/").toIntOrNull()
                if (idx != null && idx in currentTabsList.indices) {
                    deactivateSearchMode()
                    switchTab(idx)
                    Toast.makeText(this, "Switched to tab: ${currentTabsList[idx].title}", Toast.LENGTH_SHORT).show()
                }
            } else {
                addressBar.setText(item.target)
                addressBar.setSelection(item.target.length)
            }
        }

        foreground.setOnClickListener {
            triggerStrongHaptic(it)
            if (item.target == "haze://qr") {
                deactivateSearchMode()
                omniboxManager.startQrScanner()
            } else if (item.target.startsWith("haze://switch_tab/")) {
                val idx = item.target.removePrefix("haze://switch_tab/").toIntOrNull()
                if (idx != null && idx in currentTabsList.indices) {
                    deactivateSearchMode()
                    switchTab(idx)
                    Toast.makeText(this, "Switched to tab: ${currentTabsList[idx].title}", Toast.LENGTH_SHORT).show()
                }
            } else {
                addressBar.setText(item.target)
                loadUrlFromInput(item.target)
            }
        }

        bgDelete.setOnClickListener {
            triggerStrongHaptic(it, isHeavy = true)
            collapseAndDeleteRow(rowContainer, item)
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var isSwiping = false
        var velocityTracker: VelocityTracker? = null

        foreground.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwiping = false
                    velocityTracker?.recycle()
                    velocityTracker = VelocityTracker.obtain()
                    velocityTracker?.addMovement(event)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    velocityTracker?.addMovement(event)
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isSwiping) {
                        if (dx > touchSlop && dx > Math.abs(dy) * 1.15f) {
                            isSwiping = true
                            searchSuggestionStrip.requestDisallowInterceptTouchEvent(true)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            bgDelete.visibility = View.VISIBLE
                            val cancelEvent = MotionEvent.obtain(
                                event.downTime, event.eventTime,
                                MotionEvent.ACTION_CANCEL, event.x, event.y, 0
                            )
                            v.onTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        }
                    }
                    if (isSwiping) {
                        searchSuggestionStrip.requestDisallowInterceptTouchEvent(true)
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        val translation = (event.rawX - startX).coerceAtLeast(0f)
                        v.translationX = translation
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    velocityTracker?.addMovement(event)
                    searchSuggestionStrip.requestDisallowInterceptTouchEvent(false)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isSwiping) {
                        velocityTracker?.computeCurrentVelocity(1000)
                        val xVel = velocityTracker?.xVelocity ?: 0f
                        val currentX = v.translationX
                        val width = v.width.toFloat().coerceAtLeast(1f)
                        val shouldDelete = currentX > width * 0.35f || (xVel > 800f && currentX > width * 0.12f)
                        if (shouldDelete) {
                            triggerStrongHaptic(v, isHeavy = true)
                            v.animate()
                                .translationX(width + dpToPx(60))
                                .alpha(0f)
                                .setDuration(160L)
                                .setInterpolator(FAST_INTERPOLATOR)
                                .withEndAction {
                                    collapseAndDeleteRow(rowContainer, item)
                                }
                                .start()
                        } else {
                            v.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(200L)
                                .setInterpolator(BUTTER_INTERPOLATOR)
                                .withEndAction {
                                    bgDelete.visibility = View.GONE
                                }
                                .start()
                        }
                        velocityTracker?.recycle()
                        velocityTracker = null
                        isSwiping = false
                        true
                    } else {
                        velocityTracker?.recycle()
                        velocityTracker = null
                        false
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    searchSuggestionStrip.requestDisallowInterceptTouchEvent(false)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isSwiping) {
                        v.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(150L)
                            .setInterpolator(BUTTER_INTERPOLATOR)
                            .withEndAction {
                                bgDelete.visibility = View.GONE
                            }
                            .start()
                    }
                    velocityTracker?.recycle()
                    velocityTracker = null
                    isSwiping = false
                    false
                }
                else -> false
            }
        }

        return rowContainer
    }

    private fun getUserTypedQuery(): String {
        return addressBar.text?.toString()?.trim() ?: ""
    }

    private fun cleanUrlForMatching(url: String): String {
        return url.removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
    }

    private fun findBestInlineCompletion(query: String): String? {
        val q = query.trim()
        if (q.length < 2) return null
        if (omniboxManager.parseSearchShortcut(q) != null) return null
        val lowerQ = q.lowercase(Locale.US)
        val strippedQ = lowerQ.removePrefix("https://").removePrefix("http://").removePrefix("www.")

        // 1. Check history
        try {
            val db = HazeDatabase.getInstance(this)
            for (h in db.searchHistory(strippedQ)) {
                val cleaned = cleanUrlForMatching(h.url)
                if (cleaned.lowercase(Locale.US).startsWith(strippedQ)) {
                    return if (lowerQ.startsWith("www.")) "www.$cleaned" else cleaned
                }
            }
            for (b in db.searchBookmarks(strippedQ)) {
                val cleaned = cleanUrlForMatching(b.url)
                if (cleaned.lowercase(Locale.US).startsWith(strippedQ)) {
                    return if (lowerQ.startsWith("www.")) "www.$cleaned" else cleaned
                }
            }
        } catch (_: Exception) {}

        // 2. Check top domains
        for (d in topDomainsList) {
            if (d.startsWith(strippedQ)) {
                return if (lowerQ.startsWith("www.")) "www.$d" else d
            }
        }

        // 3. Check shortcuts
        try {
            for (s in getCustomShortcuts()) {
                val cleaned = cleanUrlForMatching(s.url)
                if (cleaned.lowercase(Locale.US).startsWith(strippedQ)) {
                    return if (lowerQ.startsWith("www.")) "www.$cleaned" else cleaned
                }
            }
        } catch (_: Exception) {}

        // 4. Check recent searches
        for (r in getRecentSearches()) {
            if (r.lowercase(Locale.US).startsWith(lowerQ) && !r.equals(q, ignoreCase = true)) {
                return r
            }
        }

        // 5. Check cached suggestions
        suggestionCache[strippedQ]?.let { cached ->
            for (s in cached) {
                val cleaned = cleanUrlForMatching(s)
                if (cleaned.lowercase(Locale.US).startsWith(strippedQ)) {
                    return cleaned
                }
                if (s.lowercase(Locale.US).startsWith(lowerQ)) {
                    return s
                }
            }
        }

        return null
    }

    private fun renderRecentSearchesAndShortcuts() {
        suggestionChipContainer.removeAllViews()

        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        applySuggestionTheme(isDark)

        var hasClipboard = false
        // 1. Separate Smart Clipboard Card directly above suggestions
        if (::clipboardContainer.isInitialized) {
            clipboardContainer.removeAllViews()
            val clipboardCard = omniboxManager.renderSmartClipboardCard(clipboardContainer)
            if (clipboardCard != null) {
                clipboardContainer.addView(clipboardCard)
                clipboardContainer.visibility = View.VISIBLE
                hasClipboard = true
            } else {
                clipboardContainer.visibility = View.GONE
            }
        }

        val rowItems = mutableListOf<SuggestionRowItem>()

        // Quick action: Scan QR or Barcode
        rowItems.add(
            SuggestionRowItem(
                title = "Scan QR or Barcode",
                subtitle = "Scan camera to search or open website",
                iconRes = R.drawable.ic_qr_code,
                target = "haze://qr"
            )
        )

        for (r in getRecentSearches()) {
            rowItems.add(SuggestionRowItem(title = r, iconRes = R.drawable.ic_history, target = r, isRecent = true))
        }

        try {
            for (s in getCustomShortcuts()) {
                val label = s.name.ifBlank { cleanUrlForMatching(s.url) }
                val sub = cleanUrlForMatching(s.url)
                if (rowItems.none { it.target == s.url }) {
                    rowItems.add(
                        SuggestionRowItem(
                            title = label,
                            subtitle = if (label != sub) sub else null,
                            iconRes = R.drawable.ic_globe,
                            target = s.url,
                            isShortcut = true
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        val filteredItems = rowItems.filterNot {
            dismissedSuggestions.contains(it.target.lowercase(Locale.US)) ||
            dismissedSuggestions.contains(it.title.lowercase(Locale.US))
        }

        if (filteredItems.isEmpty()) {
            searchSuggestionStrip.visibility = View.GONE
            return
        }

        val itemsToDisplay = filteredItems.take(8)
        adjustSuggestionStripHeight(itemsToDisplay.size, hasClipboard = hasClipboard)

        for (item in itemsToDisplay) {
            val rowView = createSuggestionRowView(item, isDark)
            suggestionChipContainer.addView(rowView)
        }

        searchSuggestionStrip.visibility = View.VISIBLE
        searchSuggestionStrip.scrollTo(0, 0)
    }

    private fun buildLocalIndexedSuggestions(trimmed: String): List<String> {
        val local = LinkedHashSet<String>()
        try {
            val lower = trimmed.lowercase(Locale.US)
            for (r in getRecentSearches()) {
                if (r.lowercase(Locale.US).contains(lower)) {
                    local.add(r)
                    if (local.size >= 3) break
                }
            }
            for (s in getCustomShortcuts()) {
                if (s.name.lowercase(Locale.US).contains(lower) || s.url.lowercase(Locale.US).contains(lower)) {
                    local.add(s.url)
                    if (local.size >= 5) break
                }
            }
            val db = HazeDatabase.getInstance(this)
            for (b in db.searchBookmarks(trimmed).take(3)) {
                local.add(b.url)
            }
            for (h in db.searchHistory(trimmed).take(4)) {
                local.add(h.url)
            }
        } catch (_: Exception) {}
        return local.take(5)
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
            val localMatches = buildLocalIndexedSuggestions(trimmed)
            if (localMatches.isNotEmpty()) {
                mainHandler.post { callback(localMatches) }
            }
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
                val combined = LinkedHashSet<String>()
                combined.addAll(localMatches)
                for (i in 0 until suggestionsArray.length()) {
                    combined.add(suggestionsArray.getString(i))
                    if (combined.size >= 10) break
                }
                val result = combined.toList()
                suggestionCache[trimmed] = result
                mainHandler.post { callback(result) }
            } catch (e: Exception) {
                mainHandler.post { callback(localMatches) }
            } finally {
                conn?.disconnect()
            }
        }
    }

    private fun renderSearchSuggestions(query: String, suggestions: List<String>) {
        suggestionChipContainer.removeAllViews()
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        applySuggestionTheme(isDark)
        if (::clipboardContainer.isInitialized) {
            clipboardContainer.removeAllViews()
            clipboardContainer.visibility = View.GONE
        }
        val rowItems = mutableListOf<SuggestionRowItem>()

        // 0. Search open tabs & "Switch to existing tab"
        val trimmedSearch = query.trim().lowercase(Locale.US)
        if (trimmedSearch.length >= 2) {
            for ((idx, openTab) in currentTabsList.withIndex()) {
                if (idx == activeTabIndex || isHomeTab(openTab)) continue
                val matchesTitle = openTab.title.lowercase(Locale.US).contains(trimmedSearch)
                val matchesUrl = openTab.url.lowercase(Locale.US).contains(trimmedSearch)
                if (matchesTitle || matchesUrl) {
                    val clean = cleanUrlForMatching(openTab.url)
                    rowItems.add(
                        SuggestionRowItem(
                            title = openTab.title,
                            subtitle = "Switch to open tab • $clean",
                            iconRes = R.drawable.ic_restore,
                            target = "haze://switch_tab/$idx"
                        )
                    )
                    break
                }
            }
        }

        // 1. Search shortcut routing e.g. "yt lo-fi music", "g cats", "gh chromium source", "w quantum mechanics"
        val shortcut = omniboxManager.parseSearchShortcut(query)
        if (shortcut != null) {
            val (engine, term) = shortcut
            val directSearchUrl = omniboxManager.buildSearchUrl(engine, term)
            rowItems.add(
                SuggestionRowItem(
                    title = "Search ${engine.name} for \"$term\"",
                    subtitle = "${engine.prefix} shortcut",
                    iconRes = engine.iconRes,
                    target = directSearchUrl
                )
            )
        } else {
            // Check if query is just a shortcut prefix or engine alias (e.g. "yt", "gh", "w", "g", "b", "ddg")
            val trimmedQ = query.trim().lowercase(Locale.US)
            for (engine in omniboxManager.getAllEngines()) {
                if (engine.prefix == trimmedQ) {
                    rowItems.add(
                        SuggestionRowItem(
                            title = "Search with ${engine.name}",
                            subtitle = "Type '${engine.prefix} <query>' to search directly",
                            iconRes = engine.iconRes,
                            target = "${engine.prefix} "
                        )
                    )
                }
            }
        }

        // 2. Top completion candidate (surfaced in search suggestions list, never inside address bar typing area)
        val bestCandidate = findBestInlineCompletion(query)
        if (bestCandidate != null && !bestCandidate.equals(query, ignoreCase = true)) {
            val isUrl = bestCandidate.contains(".") && !bestCandidate.contains(" ")
            val icon = if (isUrl) R.drawable.ic_globe else R.drawable.ic_search
            val target = if (isUrl && !bestCandidate.startsWith("http://") && !bestCandidate.startsWith("https://")) {
                "https://$bestCandidate"
            } else {
                bestCandidate
            }
            if (rowItems.none { it.target.equals(target, ignoreCase = true) || it.title.equals(bestCandidate, ignoreCase = true) }) {
                rowItems.add(
                    SuggestionRowItem(
                        title = bestCandidate,
                        subtitle = if (isUrl) "Navigate to $bestCandidate" else "Search suggestion",
                        iconRes = icon,
                        target = target
                    )
                )
            }
        }

        // 3. History & Bookmarks matching query
        try {
            val db = HazeDatabase.getInstance(this)
            for (b in db.searchBookmarks(query).take(3)) {
                val clean = cleanUrlForMatching(b.url)
                val title = b.title.ifBlank { clean }
                if (rowItems.none { it.target == b.url }) {
                    rowItems.add(
                        SuggestionRowItem(
                            title = title,
                            subtitle = clean,
                            iconRes = R.drawable.ic_globe,
                            target = b.url,
                            isBookmark = true
                        )
                    )
                }
            }
            for (h in db.searchHistory(query).take(3)) {
                val clean = cleanUrlForMatching(h.url)
                val title = h.title.ifBlank { clean }
                if (rowItems.none { it.target == h.url }) {
                    rowItems.add(
                        SuggestionRowItem(
                            title = title,
                            subtitle = clean,
                            iconRes = R.drawable.ic_globe,
                            target = h.url,
                            isHistory = true
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        // 4. Add suggestions from network / local list
        for (s in suggestions) {
            val isUrl = s.startsWith("http://") || s.startsWith("https://") || (s.contains(".") && !s.contains(" "))
            val icon = if (isUrl) R.drawable.ic_globe else R.drawable.ic_search
            val clean = if (isUrl) cleanUrlForMatching(s) else null
            if (rowItems.none { it.target.equals(s, ignoreCase = true) || it.title.equals(s, ignoreCase = true) }) {
                val isRecent = getRecentSearches().any { it.equals(s, ignoreCase = true) }
                rowItems.add(
                    SuggestionRowItem(
                        title = s,
                        subtitle = clean,
                        iconRes = if (isRecent) R.drawable.ic_history else icon,
                        target = s,
                        isRecent = isRecent
                    )
                )
            }
        }

        if (rowItems.isEmpty() && query.isNotEmpty()) {
            rowItems.add(SuggestionRowItem(title = query, subtitle = "Search the web", iconRes = R.drawable.ic_search, target = query))
        }

        val filteredItems = rowItems.filterNot {
            dismissedSuggestions.contains(it.target.lowercase(Locale.US)) ||
            dismissedSuggestions.contains(it.title.lowercase(Locale.US))
        }

        if (filteredItems.isEmpty()) {
            searchSuggestionStrip.visibility = View.GONE
            return
        }

        val itemsToDisplay = filteredItems.take(8)
        adjustSuggestionStripHeight(itemsToDisplay.size)

        for (item in itemsToDisplay) {
            val rowView = createSuggestionRowView(item, isDark)
            suggestionChipContainer.addView(rowView)
        }

        searchSuggestionStrip.visibility = View.VISIBLE
        searchSuggestionStrip.scrollTo(0, 0)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        wakeScreen()
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
        val triggerTranslate = intent.getStringExtra("trigger_translate")
        if (triggerTranslate != null) {
            executeInPageTranslation(triggerTranslate)
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
        if (intent.hasExtra("test_create_tabs")) {
            val count = intent.getIntExtra("test_create_tabs", 3)
            val testUrls = listOf(
                "https://www.google.com" to "Google",
                "https://www.wikipedia.org" to "Wikipedia",
                "https://github.com" to "GitHub",
                "https://www.reddit.com" to "Reddit"
            )
            val makeGroup = intent.getBooleanExtra("test_create_group", false)
            val testGroup = if (makeGroup) TabGroup("grp_test", "Research", groupColors[1]) else null
            for (i in 0 until count) {
                val pair = testUrls[i % testUrls.size]
                val t = addNewBackgroundTab(pair.first)
                t.title = pair.second
                if (makeGroup && (i == 0 || i == 1)) {
                    t.group = testGroup
                }
            }
        }
        if (intent.getBooleanExtra("open_tab_switcher", false)) {
            val viewMode = intent.getIntExtra("tab_view_mode", -1)
            if (viewMode != -1) {
                prefs.edit().putInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, viewMode).apply()
            }
            if (intent.hasExtra("is_private_mode")) {
                isPrivateMode = intent.getBooleanExtra("is_private_mode", false)
            }
            window.decorView.postDelayed({
                showFullScreenTabs()
            }, 300)
        }
        if (intent.getBooleanExtra("open_privacy_settings", false)) {
            window.decorView.postDelayed({
                HazeSettingsManager.showSettingsDialog(this)
            }, 300)
        }
        if (intent.getBooleanExtra("open_incognito_protection_settings", false)) {
            window.decorView.postDelayed({
                HazeIncognitoAuthManager.showProtectionSettingsDialog(this) {}
            }, 300)
        }
        if (intent.getBooleanExtra("open_privacy_shield", false)) {
            val scrollY = intent.getIntExtra("privacy_shield_scroll_y", 0)
            val inspectBlocked = intent.getBooleanExtra("inspect_blocked", false)
            val topTab = intent.getIntExtra("privacy_shield_tab", -1)
            window.decorView.postDelayed({
                HazePrivacyShieldDashboard.showDashboard(
                    this,
                    initialScrollY = scrollY,
                    openInspect = inspectBlocked,
                    initialTopTab = topTab
                )
            }, 300)
        }
        val startUrl = intent.getStringExtra("load_url")
        if (!startUrl.isNullOrEmpty()) {
            window.decorView.postDelayed({ loadUrlFromInput(startUrl) }, 300)
        }
        if (intent.getBooleanExtra("open_tab_switcher", false)) {
            window.decorView.postDelayed({ showFullScreenTabs() }, 200)
        }
        if (intent.hasExtra("set_clipboard")) {
            val clip = intent.getStringExtra("set_clipboard") ?: ""
            val clipManager = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipManager?.setPrimaryClip(android.content.ClipData.newPlainText("haze_clip", clip))
        }
        if (intent.getBooleanExtra("open_search", false)) {
            window.decorView.postDelayed({ activateSearchMode() }, 200)
        }
        if (intent.hasExtra("activate_search")) {
            val q = intent.getStringExtra("activate_search") ?: ""
            window.decorView.postDelayed({
                activateSearchMode()
                if (q.isNotEmpty()) {
                    addressBar.setText(q)
                    addressBar.setSelection(q.length)
                }
            }, 200)
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
        val testClipboard = intent.getStringExtra("test_set_clipboard")
        if (testClipboard != null) {
            val clipManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("test", testClipboard)
            clipManager.setPrimaryClip(clip)
            webContainer.postDelayed({ activateSearchMode() }, 200)
        }
        val testQuery = intent.getStringExtra("test_query")
        if (testQuery != null) {
            webContainer.postDelayed({
                activateSearchMode()
                addressBar.setText(testQuery)
                addressBar.setSelection(testQuery.length)
                val text = getUserTypedQuery()
                fetchSearchSuggestions(text) { suggestions ->
                    renderSearchSuggestions(text, suggestions)
                }
            }, 200)
        }
        if (intent.getBooleanExtra("open_profile", false)) {
            webContainer.postDelayed({ HazeProfileDialogManager.showProfilePanel(this) }, 200)
        }
        if (intent.getBooleanExtra("open_privacy_shield", false)) {
            webContainer.postDelayed({ HazePrivacyShieldDashboard.showDashboard(this) }, 200)
        }
        val loadUrl = intent.getStringExtra("load_url")
        if (!loadUrl.isNullOrEmpty()) {
            webContainer.postDelayed({ loadUrlFromInput(loadUrl) }, 200)
        }
        if (intent.hasExtra("set_tab_mode")) {
            val mode = intent.getIntExtra("set_tab_mode", 0)
            prefs.edit().putInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, mode).apply()
        }
        if (intent.getBooleanExtra("close_dialogs", false)) {
            activeFullScreenTabsDialog?.dismiss()
            activeFullScreenTabsDialog = null
        }
        if (intent.getBooleanExtra("open_tabs", false)) {
            val filterQ = intent.getStringExtra("filter_tabs_query")
            webContainer.postDelayed({ showFullScreenTabs(filterQ) }, 200)
        }
        if (intent.getBooleanExtra("open_tab_menu", false)) {
            webContainer.postDelayed({
                HazeTabSystemManager.showTabSwitcherMenu(
                    activity = this,
                    tabs = currentTabsList,
                    activeIndex = activeTabIndex,
                    currentMode = prefs.getInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, HazeTabSystemManager.TAB_MODE_GRID),
                    onModeChanged = { newMode ->
                        prefs.edit().putInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, newMode).apply()
                    },
                    onSortRequested = { sortMode ->
                        Toast.makeText(this, "Sorted by ${sortMode.name.lowercase()}", Toast.LENGTH_SHORT).show()
                    },
                    onRefresh = { },
                    onCloseTab = { tab ->
                        val idx = currentTabsList.indexOf(tab)
                        if (idx != -1) {
                            webContainer.removeView(tab.swipeRefreshLayout ?: tab.browserTab.renderView)
                            tab.browserTab.destroy()
                            currentTabsList.removeAt(idx)
                            saveSessionState()
                        }
                    }
                )
            }, 200)
        }
        if (intent.getBooleanExtra("open_private_tab", false)) {
            val pUrl = intent.getStringExtra("private_url") ?: HAZE_HOME_URL
            webContainer.postDelayed({ openPrivateTab(pUrl) }, 200)
        }
        if (intent.getBooleanExtra("new_tab", false)) {
            val tUrl = intent.getStringExtra("tab_url") ?: HAZE_HOME_URL
            webContainer.postDelayed({ addNewTab(tUrl) }, 200)
        }
        if (intent.getBooleanExtra("open_zoom", false)) {
            webContainer.postDelayed({ showPageZoomDialog() }, 200)
        }
        if (intent.getBooleanExtra("open_scaling", false)) {
            webContainer.postDelayed({ showTextScalingDialog() }, 200)
        }
        if (intent.getBooleanExtra("open_translate", false)) {
            webContainer.postDelayed({ translateCurrentPage() }, 200)
        }
        if (intent.getBooleanExtra("open_find_in_page", false)) {
            webContainer.postDelayed({ showFindBar() }, 200)
        }
        if (intent.getBooleanExtra("open_offline_pages", false)) {
            webContainer.postDelayed({ showOfflinePagesDialog() }, 200)
        }
        if (intent.getBooleanExtra("save_offline_page", false)) {
            webContainer.postDelayed({ saveCurrentPageForOffline() }, 200)
        }
        if (intent.getBooleanExtra("open_recently_closed", false)) {
            webContainer.postDelayed({ showRecentlyClosedTabsDialog() }, 200)
        }
        if (intent.getBooleanExtra("toggle_desktop", false)) {
            webContainer.postDelayed({
                currentTabsList.getOrNull(activeTabIndex)?.let { toggleDesktopSite(it) }
            }, 200)
        }
        if (intent.getBooleanExtra("toggle_orientation", false)) {
            webContainer.postDelayed({ toggleScreenOrientation() }, 200)
        }
        if (intent.getBooleanExtra("open_dns_protection", false)) {
            webContainer.postDelayed({ HazeDnsDialogManager.showDnsDialog(this) }, 200)
        }
        if (intent.getBooleanExtra("open_settings", false)) {
            webContainer.postDelayed({ HazeSettingsManager.showSettingsDialog(this) }, 200)
        }
        if (intent.getBooleanExtra("open_reader", false)) {
            webContainer.postDelayed({ toggleReadingMode() }, 200)
        }
        if (intent.getBooleanExtra("toggle_fullscreen", false)) {
            webContainer.postDelayed({ toggleFullScreenMode() }, 200)
        }
        if (intent.getBooleanExtra("reopen_closed_tab", false)) {
            webContainer.postDelayed({ reopenLastClosedTab() }, 200)
        }
        if (intent.getBooleanExtra("duplicate_current_tab", false)) {
            webContainer.postDelayed({
                currentTabsList.getOrNull(activeTabIndex)?.let { duplicateTab(it) }
            }, 200)
        }
        if (intent.getBooleanExtra("set_tab_pinned", false)) {
            webContainer.postDelayed({
                currentTabsList.getOrNull(activeTabIndex)?.let { it.isPinned = true }
                saveSessionState()
            }, 200)
        }
        if (intent.getBooleanExtra("create_tab_group", false)) {
            val groupName = intent.getStringExtra("group_name") ?: "Research"
            webContainer.postDelayed({
                val group = TabGroup(java.util.UUID.randomUUID().toString(), groupName, Color.parseColor("#4285F4"))
                currentTabsList.getOrNull(activeTabIndex)?.group = group
                saveSessionState()
            }, 200)
        }
        if (intent.getBooleanExtra("open_gestures_settings", false)) {
            webContainer.postDelayed({ HazeTabSystemManager.showGesturesSettingsDialog(this) }, 200)
        }
        if (intent.getBooleanExtra("test_tab_history", false)) {
            webContainer.postDelayed({
                val tab = currentTabsList.getOrNull(activeTabIndex)
                if (tab != null) {
                    HazeTabSystemManager.showTabHistoryDialog(this, tab) { }
                }
            }, 200)
        }
        if (intent.getBooleanExtra("test_send_to_device", false)) {
            webContainer.postDelayed({
                HazeTabSystemManager.showSendToDeviceDialog(this, currentTabsList)
            }, 200)
        }
        if (intent.getBooleanExtra("test_qr_dialog", false)) {
            webContainer.postDelayed({
                val tab = currentTabsList.getOrNull(activeTabIndex)
                val url = tab?.url ?: HAZE_HOME_URL
                val title = tab?.title ?: "Haze Tab"
                HazeTabSystemManager.showQrCodeDialog(this, title, url)
            }, 200)
        }
        if (intent.getBooleanExtra("test_auto_group", false)) {
            webContainer.postDelayed({
                HazeTabSystemManager.autoGroupByDomain(this, currentTabsList) {
                    saveSessionState()
                }
            }, 200)
        }
        if (intent.getBooleanExtra("test_close_inactive", false)) {
            webContainer.postDelayed({
                HazeTabSystemManager.closeInactiveTabs(
                    this,
                    currentTabsList,
                    activeTabIndex
                ) { tab ->
                    val idx = currentTabsList.indexOf(tab)
                    if (idx != -1) {
                        webContainer.removeView(tab.swipeRefreshLayout ?: tab.browserTab.renderView)
                        tab.browserTab.destroy()
                        currentTabsList.removeAt(idx)
                        saveSessionState()
                    }
                }
            }, 200)
        }
        if (intent.getBooleanExtra("test_close_all", false)) {
            webContainer.postDelayed({
                HazeTabSystemManager.closeAllTabs(
                    this,
                    currentTabsList
                ) { tab ->
                    val idx = currentTabsList.indexOf(tab)
                    if (idx != -1) {
                        webContainer.removeView(tab.swipeRefreshLayout ?: tab.browserTab.renderView)
                        tab.browserTab.destroy()
                        currentTabsList.removeAt(idx)
                        saveSessionState()
                    }
                }
            }, 200)
        }
        if (intent.getBooleanExtra("test_gesture_swipe_down", false)) {
            webContainer.postDelayed({
                val loc = IntArray(2)
                addressBarContainer.getLocationOnScreen(loc)
                val midX = (loc[0] + addressBarContainer.width / 2).toFloat()
                val startY = (loc[1] + dpToPx(10)).toFloat()
                val endY = (loc[1] + dpToPx(80)).toFloat()
                val now = android.os.SystemClock.uptimeMillis()
                dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, midX, startY, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, midX, (startY + endY) / 2, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, midX, endY, 0))
            }, 300)
        }
        if (intent.getBooleanExtra("test_gesture_swipe_tabs", false)) {
            val direction = intent.getIntExtra("swipe_dir", -1)
            webContainer.postDelayed({
                val loc = IntArray(2)
                addressBarContainer.getLocationOnScreen(loc)
                val midY = (loc[1] + addressBarContainer.height / 2).toFloat()
                val startX = if (direction < 0) (loc[0] + addressBarContainer.width * 0.8f) else (loc[0] + addressBarContainer.width * 0.2f)
                val endX = if (direction < 0) (loc[0] + addressBarContainer.width * 0.2f) else (loc[0] + addressBarContainer.width * 0.8f)
                val now = android.os.SystemClock.uptimeMillis()
                dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, midY, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, (startX + endX) / 2, midY, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, endX, midY, 0))
            }, 300)
        }
        if (intent.getBooleanExtra("test_gesture_edge_nav", false)) {
            val isLeft = intent.getBooleanExtra("is_left_edge", true)
            webContainer.postDelayed({
                val screenW = resources.displayMetrics.widthPixels.toFloat()
                val midY = (resources.displayMetrics.heightPixels / 2).toFloat()
                val startX = if (isLeft) dpToPx(10).toFloat() else (screenW - dpToPx(10).toFloat())
                val endX = if (isLeft) dpToPx(120).toFloat() else (screenW - dpToPx(120).toFloat())
                val now = android.os.SystemClock.uptimeMillis()
                dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, startX, midY, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, (startX + endX) / 2, midY, 0))
                dispatchTouchEvent(MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, endX, midY, 0))
            }, 300)
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

    fun isHomeTab(tab: Tab): Boolean {
        val u = tab.url.trim()
        return u == HAZE_HOME_URL || u == "$HAZE_HOME_URL/" || u.startsWith("haze://home") || u == "about:blank" || u.startsWith("about:") || u.isEmpty()
    }

    private fun reloadHomeTabs() {
        val activeTab = currentTabsList.getOrNull(activeTabIndex)
        if (activeTab != null && isHomeTab(activeTab)) {
            activeTab.url = HAZE_HOME_URL
            activeTab.browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
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

    private fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        ).toInt()
    }

    private val bgPhotoCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun getBgPhotoBase64(theme: String): String? {
        if (theme == "default") return null
        return bgPhotoCache.getOrPut(theme) {
            try {
                val bytes = assets.open("bg_theme_$theme.webp").readBytes()
                "data:image/webp;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                ""
            }
        }.takeIf { it.isNotEmpty() }
    }

    private fun getHazeHomeHtml(): String {
        val isDarkSetting = prefs.getBoolean("is_dark", false)
        val isIncognito = isPrivateMode
        val isDark = isDarkSetting || isIncognito

        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2" // "off", "1", "2"
        val accentColor = if (isIncognito) "#A8C7FA" else (prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        
        val bgColor = if (isIncognito) "#121212" else (if (isDarkSetting) "#121212" else "#FAFAFA")
        val cardColor = if (isIncognito) "#1E1E1E" else (if (isDarkSetting) "#1E1E1E" else "#FFFFFF")
        val textColor = if (isIncognito || isDark) "#FFFFFF" else "#202124"
        val labelColor = if (isIncognito || isDark) "#E0E0E0" else "#202124"
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
        val bgImageBase64 = if (!isIncognito) getBgPhotoBase64(bgTheme) else null
        val hasBgPhoto = bgImageBase64 != null

        if (isIncognito) {
            val incognitoHtml = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                    <style>
                        body {
                            margin: 0; padding: 0; font-family: 'Google Sans', 'Roboto', sans-serif;
                            background-color: $bgColor; color: $textColor; display: flex; flex-direction: column;
                            min-height: 100vh; overflow-x: hidden; user-select: none; -webkit-user-select: none;
                        }
                        .container {
                            flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: flex-start;
                            padding: 64px 24px 24px; box-sizing: border-box; max-width: 600px; margin: 0 auto; width: 100%;
                        }
                        .logo-wrapper {
                            margin-bottom: 32px; display: flex; justify-content: center;
                            animation: float 4s ease-in-out infinite;
                        }
                        .logo {
                            width: 80px; height: 80px; filter: drop-shadow(0 4px 14px rgba(0,0,0,0.3));
                        }
                        @keyframes float { 0% { transform: translateY(0px); } 50% { transform: translateY(-8px); } 100% { transform: translateY(0px); } }
                        .title { font-size: 24px; font-weight: 600; margin-bottom: 24px; text-align: center; }
                        .info-box {
                            background-color: $cardColor; border-radius: 16px; padding: 20px;
                            box-shadow: 0 4px 16px rgba(0,0,0,0.1); margin-bottom: 24px; width: 100%; box-sizing: border-box;
                        }
                        .info-title { font-size: 14px; font-weight: 600; margin-bottom: 12px; color: $accentColor; }
                        .info-list { list-style-type: none; padding: 0; margin: 0; }
                        .info-list li {
                            font-size: 13.5px; margin-bottom: 10px; line-height: 1.4; color: $textColor;
                            display: flex; align-items: flex-start;
                        }
                        .info-list li:last-child { margin-bottom: 0; }
                        .info-list li::before {
                            content: "•"; color: $accentColor; font-weight: bold; display: inline-block; width: 16px; font-size: 16px;
                        }
                        .warning-text { font-size: 12.5px; color: #9AA0A6; line-height: 1.5; text-align: left; width: 100%; margin-bottom: 32px; }
                        .actions-grid {
                            display: grid; grid-template-columns: 1fr 1fr; gap: 16px; width: 100%; margin-bottom: 24px;
                        }
                        .action-btn {
                            background-color: $cardColor; border: 1px solid #3C4043; border-radius: 12px;
                            padding: 14px 12px; display: flex; flex-direction: column; align-items: center;
                            justify-content: center; text-align: center; font-size: 12.5px; font-weight: 500;
                            color: $textColor; cursor: pointer; transition: background-color 0.2s;
                        }
                        .action-btn:active { background-color: #3C4043; }
                        .action-icon { width: 24px; height: 24px; margin-bottom: 8px; fill: $accentColor; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="logo-wrapper">
                            <svg class="logo" viewBox="0 0 100 80" xmlns="http://www.w3.org/2000/svg" style="pointer-events: none; user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;">
                <path d="M50 10 C38 10 28 16 22 22 C18 30 5 32 5 38 C5 40 7 42 10 42 L90 42 C93 42 95 40 95 38 C95 32 82 30 78 22 C72 16 62 10 50 10 Z" fill="$textColor"/>
                <circle cx="32" cy="62" r="15" fill="$textColor"/>
                <circle cx="32" cy="62" r="11" fill="$bgColor"/>
                <circle cx="68" cy="62" r="15" fill="$textColor"/>
                <circle cx="68" cy="62" r="11" fill="$bgColor"/>
                <path d="M47 62 L53 62" stroke="$textColor" stroke-width="4" stroke-linecap="round"/>
            </svg>
        </div>
        <div class="title">Private Browsing</div>
                        
                        <div class="info-box">
                            <div class="info-title">Haze won't save the following:</div>
                            <ul class="info-list">
                                <li>Your browsing history</li>
                                <li>Cookies and site data</li>
                                <li>Information entered in forms</li>
                            </ul>
                        </div>
                        
                        <div class="warning-text">
                            Your activity might still be visible to websites you visit, your employer or school, or your internet service provider. Downloaded files and bookmarks will be saved.
                        </div>

                        <div class="actions-grid">
                            <div class="action-btn" onclick="Android.triggerHaptic(false); Android.focusAddressBar()">
                                <svg class="action-icon" viewBox="0 0 24 24"><path d="M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"/></svg>
                                Search privately
                            </div>
                            <div class="action-btn" onclick="Android.triggerHaptic(false); Android.navigateTo('haze://shield')">
                                <svg class="action-icon" viewBox="0 0 24 24"><path d="M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm0 10.99h7c-.53 4.12-3.28 7.79-7 8.94V12H5V6.3l7-3.11v8.8z"/></svg>
                                Privacy settings
                            </div>
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()
            return incognitoHtml
        }

        val bgStyle = if (hasBgPhoto) {
            """
            background-color: #121212;
            background-image: linear-gradient(rgba(0, 0, 0, 0.35), rgba(0, 0, 0, 0.50)), url('$bgImageBase64');
            background-size: cover;
            background-position: center;
            background-repeat: no-repeat;
            background-attachment: fixed;
            """.trimIndent()
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
                <path d="M50 10 C38 10 28 16 22 22 C18 30 5 32 5 38 C5 40 7 42 10 42 L90 42 C93 42 95 40 95 38 C95 32 82 30 78 22 C72 16 62 10 50 10 Z" fill="#FFFFFF"/>
                <circle cx="32" cy="62" r="15" fill="#FFFFFF"/>
                <circle cx="32" cy="62" r="11" fill="$bgColor"/>
                <circle cx="68" cy="62" r="15" fill="#FFFFFF"/>
                <circle cx="68" cy="62" r="11" fill="$bgColor"/>
                <path d="M47 62 L53 62" stroke="#FFFFFF" stroke-width="4" stroke-linecap="round"/>
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
                    @keyframes homeFadeIn {
                        0% { opacity: 0; transform: scale(0.96) translateY(10px); }
                        100% { opacity: 1; transform: scale(1) translateY(0); }
                    }
                    .container {
                        display: flex; flex-direction: column; align-items: center; width: 100%;
                        margin-top: -6vh;
                        animation: homeFadeIn 0.35s cubic-bezier(0.2, 0, 0, 1) backwards;
                        will-change: transform, opacity;
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
                        transition: transform 0.22s cubic-bezier(0.2, 0, 0, 1), opacity 0.22s cubic-bezier(0.2, 0, 0, 1);
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
        
        val bgColor = if (isPrivateMode) Color.parseColor("#121212") else (if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))
        val barBgColor = if (isPrivateMode) Color.parseColor("#1E1E1E") else (if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF"))
        val textColor = if (isPrivateMode || isDark) Color.WHITE else Color.parseColor("#202124")
        val hintColor = if (isPrivateMode || isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val strokeColor = if (isPrivateMode || isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
        
        val bgTheme = if (isPrivateMode) "default" else (prefs.getString("bg_theme", "default") ?: "default")
        val containerBg = if (bgTheme != "default" && !isPrivateMode) Color.TRANSPARENT else bgColor

        findViewById<ConstraintLayout>(R.id.main).setBackgroundColor(bgColor)
        addressBarContainer.setBackgroundColor(containerBg)
        findBarContainer.setBackgroundColor(containerBg)
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
            setStroke(1, strokeColor)
        }
        addressBarInner.background = shape
        addressBarInner.elevation = 0f
        addressBarInner.outlineProvider = null
        
        addressBar.setTextColor(textColor)
        addressBar.setHintTextColor(hintColor)
        findInput.setTextColor(textColor)
        findInput.setHintTextColor(hintColor)
        
        findViewById<ImageView>(R.id.searchIcon).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.menuBtn).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnVoiceSearch)?.setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnQrScan)?.setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnClearQuery)?.setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindClose).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindNext).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindPrev).setColorFilter(hintColor)
        
        val tabShape = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(if (isPrivateMode || isDark) Color.parseColor("#2D3033") else Color.parseColor("#E8EAED"))
        }
        btnTabsCircular.background = tabShape
        tabCountBtn.setTextColor(textColor)

        if (::btnProfile.isInitialized) {
            btnProfile.background = null
            btnProfile.elevation = 0f
            btnProfile.outlineProvider = null
            updateProfileButton()
        }

        updatePrivacyShieldButton()

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
    }

    fun addNewTab(url: String): Tab {
        val browserTab = ChromiumEngineManager.getEngine(this).createTab(this, isPrivateMode)
        val renderView = browserTab.renderView
        renderView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        val swipeRefreshLayout = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setColorSchemeColors(accentColor)
            setOnChildScrollUpCallback { _, _ ->
                val web = (browserTab as? ChromiumBrowserTab)?.webView
                web?.canScrollVertically(-1) == true
            }
            setOnRefreshListener {
                triggerStrongHaptic()
                browserTab.reload()
            }
        }
        swipeRefreshLayout.addView(renderView)

        val tab = Tab(
            id = browserTab.id,
            browserTab = browserTab,
            swipeRefreshLayout = swipeRefreshLayout,
            isPrivate = isPrivateMode,
            url = url
        )
        
        currentTabsList.add(tab)
        webContainer.addView(swipeRefreshLayout)
        
        setupBrowserTab(browserTab)
        
        if (isPrivateMode) activePrivateTabIndex = currentTabsList.size - 1
        else activeNormalTabIndex = currentTabsList.size - 1
        
        switchTab(activeTabIndex)
        
        when (url) {
            HAZE_HOME_URL -> {
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                tab.title = "New Tab"
                if (activeTabIndex == activeNormalTabIndex || activeTabIndex == activePrivateTabIndex) addressBar.setText("")
            }
            "haze://favorites", "haze://bookmarks" -> {
                tab.title = "Favorites"
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeBookmarksManager.showBookmarksDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            }
            "haze://history" -> {
                tab.title = "History"
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeHistoryManager.showHistoryDialog(this) { navigateUrl -> loadUrlFromInput(navigateUrl) }
            }
            "haze://downloads" -> {
                tab.title = "Downloads"
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeDownloadsManager.showDownloadsDialog(this)
            }
            "haze://settings" -> {
                tab.title = "Settings"
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazeSettingsManager.showSettingsDialog(this)
            }
            "haze://shield", "haze://privacy", "haze://adblock" -> {
                tab.title = "Privacy Shield"
                browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                HazePrivacyShieldDashboard.showDashboard(this)
            }
            else -> browserTab.navigationController.loadUrl(url)
        }
        tab.url = url
        return tab
    }

    fun duplicateTab(tab: Tab): Tab {
        val newTab = addNewTab(tab.url)
        newTab.title = tab.title
        newTab.isPinned = tab.isPinned
        newTab.group = tab.group
        Toast.makeText(this, "Tab duplicated", Toast.LENGTH_SHORT).show()
        return newTab
    }

    private fun switchTab(index: Int, animated: Boolean = true) {
        if (index < 0 || index >= currentTabsList.size) {
            tabCountBtn.text = currentTabsList.size.toString()
            return
        }

        HazeTranslationManager.dismissSuggestionBanner(this@MainActivity, clearLastPrompted = false)
        val previousTab = currentTabsList.getOrNull(activeTabIndex)
        val nextTab = currentTabsList[index]
        nextTab.lastAccessedTime = System.currentTimeMillis()
        if (previousTab != null && previousTab !== nextTab) {
            if (activeMediaTab !== previousTab) {
                try {
                    previousTab.browserTab.onPause()
                } catch (_: Exception) {}
            }
        }
        if (previousTab != null &&
            previousTab !== nextTab &&
            activeMediaTab === previousTab &&
            miniPlayerManager?.isActive != true
        ) {
            showMiniPlayer(previousTab, createBrowsingTab = false)
        }
        
        if (::webContainer.isInitialized) {
            webContainer.translationY = 0f
            webContainer.translationX = 0f
        }
        if (::addressBarContainer.isInitialized) {
            addressBarContainer.translationY = 0f
            addressBarContainer.translationX = 0f
        }
        if (::addressBar.isInitialized) {
            addressBar.translationX = 0f
        }
        
        val oldIndex = activeTabIndex
        if (isPrivateMode) activePrivateTabIndex = index else activeNormalTabIndex = index
        
        val prevView = previousTab?.swipeRefreshLayout ?: previousTab?.browserTab?.renderView
        val nextView = nextTab.swipeRefreshLayout ?: nextTab.browserTab.renderView
        val isDifferent = previousTab != null && previousTab !== nextTab && prevView != null && nextView != null && prevView !== nextView
        val containerWidth = if (webContainer.width > 0) webContainer.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()

        if (animated && isDifferent) {
            val movingForward = index > oldIndex
            val enterStartX = if (movingForward) containerWidth else -containerWidth
            val exitEndX = if (movingForward) -containerWidth * 0.35f else containerWidth * 0.35f

            nextView.translationX = enterStartX
            nextView.alpha = 1f
            nextView.visibility = View.VISIBLE
            nextView.bringToFront()

            prevView.animate().cancel()
            nextView.animate().cancel()

            prevView.animate()
                .translationX(exitEndX)
                .withLayer()
                .setDuration(130L)
                .setInterpolator(FAST_INTERPOLATOR)
                .withEndAction {
                    prevView.visibility = View.GONE
                    prevView.translationX = 0f
                    prevView.alpha = 1f
                }
                .start()

            nextView.animate()
                .translationX(0f)
                .withLayer()
                .setDuration(130L)
                .setInterpolator(FAST_INTERPOLATOR)
                .start()

            for (i in 0 until webContainer.childCount) {
                val child = webContainer.getChildAt(i)
                if (child !== prevView && child !== nextView) {
                    child.visibility = View.GONE
                }
            }
        } else {
            for (i in 0 until webContainer.childCount) {
                val child = webContainer.getChildAt(i)
                val currentTab = currentTabsList.getOrNull(index)
                val isCurrentTabContainer = currentTab?.swipeRefreshLayout?.let { child == it } ?: (child == currentTab?.browserTab?.renderView)
                child.visibility = if (isCurrentTabContainer) View.VISIBLE else View.GONE
                if (isCurrentTabContainer) {
                    child.translationX = 0f
                    child.alpha = 1f
                }
            }
        }
        
        tabCountBtn.text = currentTabsList.size.toString()
        val currentTab = currentTabsList[index]
        val wasAlreadyActiveEngineTab = false
        currentTab.browserTab.onResume()

        val isHome = isHomeTab(currentTab)
        currentTab.swipeRefreshLayout?.isEnabled = !isHome

        val currentUrl = currentTab.url
        if (currentTab.browserTab !is ChromiumBrowserTab && !wasAlreadyActiveEngineTab) {
            if (isHome) {
                currentTab.browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
            } else if (currentUrl.isNotEmpty()) {
                currentTab.browserTab.navigationController.loadUrl(currentUrl)
            }
        }
        updateChromeVisibility(currentUrl)
        
        val targetText = if (isHome) "" else currentUrl
        if (animated && isDifferent && addressBar.text.toString() != targetText) {
            addressBar.animate().cancel()
            addressBar.setText(targetText)
            addressBar.alpha = 0.65f
            addressBar.animate()
                .alpha(1f)
                .setDuration(90L)
                .setInterpolator(FAST_INTERPOLATOR)
                .start()
        } else {
            addressBar.setText(targetText)
            addressBar.alpha = 1f
        }
    }

    private fun updateChromeVisibility(url: String?) {
        addressBarContainer.visibility = View.VISIBLE
        val isHome = url == null || url == HAZE_HOME_URL || url == "$HAZE_HOME_URL/" || url.startsWith("haze://home") || url == "about:blank" || url.startsWith("about:") || url.isEmpty()
        
        applyTheme()

        if (isHome) {
            addressBar.setText("")
        }

        val bgTheme = if (isPrivateMode) "default" else (prefs.getString("bg_theme", "default") ?: "default")
        val hasBgPhoto = bgTheme != "default" && !isPrivateMode
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val bgColor = if (isPrivateMode) Color.parseColor("#121212") else (if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))

        if (isHome) {
            currentTabsList.getOrNull(activeTabIndex)?.browserTab?.setBackgroundColor(if (hasBgPhoto) Color.TRANSPARENT else bgColor)
            val appBgImageView = findViewById<ImageView>(R.id.appBackgroundImageView)
            val appBgOverlay = findViewById<View>(R.id.appBackgroundOverlay)
            if (hasBgPhoto) {
                appBgImageView?.visibility = View.VISIBLE
                appBgOverlay?.visibility = View.VISIBLE
            } else {
                appBgImageView?.visibility = View.GONE
                appBgOverlay?.visibility = View.GONE
            }
        } else {
            currentTabsList.getOrNull(activeTabIndex)?.browserTab?.setBackgroundColor(Color.WHITE)
            findViewById<ImageView>(R.id.appBackgroundImageView)?.visibility = View.GONE
            findViewById<View>(R.id.appBackgroundOverlay)?.visibility = View.GONE
        }

        if (::topRightActions.isInitialized) {
            topRightActions.visibility = if (isHome && !isPrivateMode && !isSearchActive) View.VISIBLE else View.GONE
            if (isHome && !isPrivateMode && !isSearchActive) {
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
        updatePrivacyShieldButton()

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
                webContainer.removeView(tabToRemove.browserTab.renderView)
            }
            tabToRemove.browserTab.destroy()
            currentTabsList.removeAt(0)
            switchTab(0)
            return
        }
        val tabToRemove = currentTabsList[index]
        if (tabToRemove.swipeRefreshLayout != null) {
            webContainer.removeView(tabToRemove.swipeRefreshLayout)
        } else {
            webContainer.removeView(tabToRemove.browserTab.renderView)
        }
        tabToRemove.browserTab.destroy()
        currentTabsList.removeAt(index)
        if (isPrivateMode && privateTabs.isEmpty()) {
            ChromiumEngineManager.getEngine(this).clearPrivateSession()
        }
        
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

    private fun setupBrowserTab(browserTab: IBrowserTab) {
        if (browserTab is ChromiumBrowserTab) {
            setupWebView(browserTab.webView)
            return
        }

        browserTab.addJavascriptInterface(WebAppInterface(browserTab), "Android")
        browserTab.setEngineObserver(object : IEngineObserver {
            override fun onPageStarted(tab: IBrowserTab, url: String, favicon: Bitmap?) {
                runOnUiThread {
                    HazeTranslationManager.dismissSuggestionBanner(this@MainActivity, clearLastPrompted = true)
                    val ownerTab = getTabForInterface(tab, null)
                    val isPriv = ownerTab?.isPrivate ?: isPrivateMode
                    if (!url.startsWith("haze://") && !url.startsWith("about:") && !url.startsWith("javascript:") && !url.startsWith("data:")) {
                        val decision = HazePrivacyShieldEngine.evaluateRequest(
                            context = this@MainActivity,
                            requestUrl = url,
                            pageUrl = url,
                            isMainFrame = true,
                            isPrivate = isPriv
                        )
                        if (decision.shouldBlock && decision.isHarmfulPageNavigation) {
                            val isDark = prefs.getBoolean("is_dark", false) || isPriv
                            val warningHtml = HazeSecurityWarningManager.getSecurityWarningHtml(url, decision.category, isDark)
                            tab.navigationController.loadDataWithBaseURL(url, warningHtml, "text/html", "UTF-8", null)
                            return@runOnUiThread
                        }
                    }

                    if (url != HAZE_HOME_URL && !url.startsWith("haze://")) {
                        showPageLoading(5)
                        val antiTrackingScript = HazeAntiTrackingEngine.generateAntiFingerprintScript(this@MainActivity)
                        tab.evaluateJavascript(antiTrackingScript, null)
                        val earlyCss = """
                            (function() {
                                if (window.__hazeEarlyCssInjected) return;
                                window.__hazeEarlyCssInjected = true;
                                var style = document.createElement('style');
                                style.innerHTML = 'div[class*="ad-"], div[id*="ad-"], .adsbygoogle, iframe[src*="doubleclick"], .sponsored, .promoted, ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-banner-promo-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-ad-slot-renderer, ytm-companion-ad-renderer, ytm-inline-ad, #masthead-ad, ytm-statement-banner-renderer, ytm-banner-promo-renderer, ytm-compact-promoted-video-renderer, .ytp-ad-module, .video-ads { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; width: 0 !important; }';
                                if (document.documentElement) document.documentElement.appendChild(style);
                                else document.addEventListener('DOMContentLoaded', function() { document.documentElement.appendChild(style); });
                            })();
                        """.trimIndent()
                        tab.evaluateJavascript(earlyCss, null)
                    }
                }
            }

            override fun onPageProgress(tab: IBrowserTab, progress: Int) {
                runOnUiThread {
                    // Background tabs must never drive the visible loading bar.
                    if (getActiveTab()?.browserTab !== tab) return@runOnUiThread
                    val previous = renderedProgressByTab[tab.id] ?: -1
                    if (progress != 100 && previous >= 0 && progress - previous < 3) return@runOnUiThread
                    renderedProgressByTab[tab.id] = progress
                    if (tab.url == HAZE_HOME_URL || tab.url.startsWith("haze://")) {
                        finishPageLoading()
                    } else if (progress < 100) {
                        showPageLoading(progress.coerceAtLeast(5))
                    } else {
                        finishPageLoading()
                    }
                }
            }

            override fun onPageFinished(tab: IBrowserTab, url: String) {
                runOnUiThread {
                    finishPageLoading()
                    val ownerTab = getTabForInterface(tab, null) ?: return@runOnUiThread
                    val isHome = url == HAZE_HOME_URL || url == "$HAZE_HOME_URL/" || url == "about:blank" || url.startsWith("about:") || url.startsWith("haze://home") || url.startsWith("data:") || url.startsWith("https://haze.local")
                    if (isHome) {
                        ownerTab.title = "New Tab"
                        ownerTab.url = HAZE_HOME_URL
                    } else {
                        ownerTab.url = url
                    }
                    if (ownerTab === currentTabsList.getOrNull(activeTabIndex)) {
                        updateChromeVisibility(ownerTab.url)
                        if (isHome) {
                            addressBar.setText("")
                        } else if (!isSearchActive) {
                            addressBar.setText(HazeTranslationManager.getDisplayUrl(url))
                        }
                    }

                    if (!ownerTab.isPrivate && !isHome && !url.startsWith("haze://") && !url.startsWith("about:")) {
                        val pageTitle = ownerTab.title.takeIf { it.isNotEmpty() && it != "New Tab" && it != "Loading..." } ?: url
                        val cachedFavicon = HazeFaviconManager.getCachedFaviconFile(this@MainActivity, url)
                        val faviconPath = if (cachedFavicon.exists()) cachedFavicon.absolutePath else null
                        HazeDatabase.getInstance(this@MainActivity).addHistory(url, pageTitle, isPrivate = false, faviconPath = faviconPath)
                    }

                    if (!isHome) {
                        val cosmeticCss = """
                            (function() {
                                if (window.__hazeCosmeticInjected) return;
                                window.__hazeCosmeticInjected = true;
                                var style = document.createElement('style');
                                style.innerHTML = '.adsbygoogle, ins.adsbygoogle, iframe[src*="doubleclick"], .sponsored, .promoted, ytd-ad-slot-renderer, ytd-promoted-video-renderer, ytd-banner-promo-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-ad-slot-renderer, ytm-companion-ad-renderer, ytm-inline-ad, #masthead-ad, ytm-statement-banner-renderer, ytm-banner-promo-renderer, ytm-compact-promoted-video-renderer, .ytp-ad-overlay-container, .ytp-ad-message-container { display: none !important; opacity: 0 !important; pointer-events: none !important; height: 0 !important; width: 0 !important; }\nvideo, video.video-stream, video.html5-main-video, .html5-main-video, [class*="video-stream"] video, [class*="player-container"] video { opacity: 1 !important; visibility: visible !important; max-height: none !important; max-width: none !important; pointer-events: auto !important; }';
                                if (document.head) document.head.appendChild(style);
                                else document.documentElement.appendChild(style);
                            })();
                        """.trimIndent()
                        tab.evaluateJavascript(cosmeticCss, null)

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
                                        '.videoAdUiSkipButton',
                                        'button[class*="skip-button"]',
                                        '.ytp-skip-ad-button',
                                        '.ytm-ad-overlay-close-button'
                                    ];

                                    function handleYtPlayerVisibility() {
                                        try {
                                            var isWatch = window.location.pathname.indexOf('/watch') !== -1 ||
                                                          window.location.pathname.indexOf('/shorts') !== -1 ||
                                                          window.location.search.indexOf('v=') !== -1;
                                            var containers = document.querySelectorAll('#player-container-id, .player-container');
                                            for (var k = 0; k < containers.length; k++) {
                                                var c = containers[k];
                                                var activeVideo = c.querySelector('video[src], video:not([src=""])');
                                                if (!isWatch && (!activeVideo || activeVideo.paused)) {
                                                    c.style.setProperty('display', 'none', 'important');
                                                } else if (isWatch) {
                                                    c.style.removeProperty('display');
                                                }
                                            }
                                        } catch(e) {}
                                    }

                                    function restoreNormalVideo(v) {
                                        if (!v) v = document.querySelector('video');
                                        if (v) {
                                            if (v.playbackRate > 1) v.playbackRate = normalPlaybackRate;
                                            v.muted = false;
                                            v.style.setProperty('visibility', 'visible', 'important');
                                            v.style.setProperty('opacity', '1', 'important');
                                        }
                                    }

                                    function skipYouTubeAd() {
                                        handleYtPlayerVisibility();
                                        var adPlayer = document.querySelector('.ad-showing, .ad-interrupting, .video-ads.ad-showing');
                                        var video = document.querySelector('video');
                                        if (!adPlayer) {
                                            if (adWasActive) {
                                                restoreNormalVideo(video);
                                            }
                                            adWasActive = false;
                                            return;
                                        }
                                        adWasActive = true;
                                        for (var i = 0; i < adSelectors.length; i++) {
                                            var button = document.querySelector(adSelectors[i]);
                                            if (button) { button.click(); return; }
                                        }
                                        if (video) {
                                            var dur = video.duration;
                                            if (isFinite(dur) && dur > 0 && dur <= 90) {
                                                if (video.playbackRate <= 1) normalPlaybackRate = video.playbackRate || 1;
                                                video.muted = true;
                                                if (dur > 0.5) video.currentTime = dur - 0.1;
                                                video.playbackRate = 16;
                                                video.play().catch(function() {});
                                            } else if (dur > 90) {
                                                restoreNormalVideo(video);
                                            }
                                        }
                                        var overlays = document.querySelectorAll(
                                            '.ytp-ad-overlay-container, .ytp-ad-text, .ytp-ad-message-container, .ytp-ad-image-overlay, ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-ad-slot-renderer, #masthead-ad'
                                        );
                                        for (var j = 0; j < overlays.length; j++) overlays[j].style.setProperty('display', 'none', 'important');
                                    }
                                    handleYtPlayerVisibility();
                                    setInterval(skipYouTubeAd, 300);
                                    window.addEventListener('popstate', handleYtPlayerVisibility);
                                    document.addEventListener('yt-navigate-finish', handleYtPlayerVisibility);
                                    document.addEventListener('click', function() { setTimeout(skipYouTubeAd, 0); setTimeout(handleYtPlayerVisibility, 200); }, true);
                                    var observer = new MutationObserver(skipYouTubeAd);
                                    observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
                                    skipYouTubeAd();
                                })();
                            """.trimIndent()
                            tab.evaluateJavascript(safeYtSkips, null)
                        }

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
                                            if (text && text.trim().length > 0 && window.Android && window.Android.onTextSelection) {
                                                window.Android.onTextSelection(text.trim());
                                            }
                                        }, 800);
                                    }
                                });
                            })();
                        """.trimIndent()
                        tab.evaluateJavascript(selectionScript, null)

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
                                    for (var i = 0; i < all.length; i++) trackElement(all[i]);
                                    checkMedia();
                                }
                                scanElements();
                                var observer = new MutationObserver(function() { scanElements(); });
                                if (document.body || document.documentElement) {
                                    observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
                                }
                                setInterval(checkMedia, 1500);
                            })();
                        """.trimIndent()
                        tab.evaluateJavascript(mediaScript, null)

                        // Automatic non-English page detection hook
                        tab.evaluateJavascript(HazeTranslationManager.getLanguageDetectionScript(), null)
                    }
                    updateNavigationButtons()
                }
            }

            override fun onTitleReceived(tab: IBrowserTab, title: String) {
                runOnUiThread {
                    val ownerTab = getTabForInterface(tab, null) ?: return@runOnUiThread
                    val isHome = isHomeTab(ownerTab) || title.startsWith("data:") || title.contains("haze.local", ignoreCase = true)
                    if (isHome) {
                        ownerTab.title = "New Tab"
                    } else if (!ownerTab.url.startsWith("haze://")) {
                        ownerTab.title = title
                        if (!ownerTab.isPrivate && ownerTab.url != HAZE_HOME_URL && !ownerTab.url.startsWith("about:")) {
                            HazeDatabase.getInstance(this@MainActivity).addHistory(ownerTab.url, title, isPrivate = false)
                        }
                    }
                }
            }

            override fun onFaviconReceived(tab: IBrowserTab, favicon: Bitmap) {
                runOnUiThread {
                    val ownerTab = getTabForInterface(tab, null) ?: return@runOnUiThread
                    ownerTab.favicon = favicon
                    if (!ownerTab.isPrivate && !isHomeTab(ownerTab) && !ownerTab.url.startsWith("haze://")) {
                        val path = HazeFaviconManager.saveFavicon(this@MainActivity, ownerTab.url, favicon)
                        if (path != null) {
                            HazeDatabase.getInstance(this@MainActivity).updateHistoryFavicon(ownerTab.url, path)
                        }
                    }
                }
            }

            override fun onReceivedError(tab: IBrowserTab, errorCode: Int, description: String, failingUrl: String) {
                runOnUiThread {
                    if (failingUrl.isNotEmpty() && !failingUrl.startsWith("haze://") && !failingUrl.startsWith("about:") && !failingUrl.startsWith("https://haze.local") && !failingUrl.startsWith("data:")) {
                        val ownerTab = getTabForInterface(tab, null)
                        val isPriv = ownerTab?.isPrivate ?: isPrivateMode
                        val isDark = prefs.getBoolean("is_dark", false) || isPriv
                        val offlineHtml = getOfflineErrorHtml(failingUrl, isDark)
                        tab.navigationController.loadDataWithBaseURL(failingUrl, offlineHtml, "text/html", "UTF-8", null)
                    }
                }
            }

            override fun onRenderProcessGone(tab: IBrowserTab, didCrash: Boolean): Boolean {
                runOnUiThread {
                    tab.reload()
                }
                return true
            }

            override fun onNewWindowRequested(url: String, isUserGesture: Boolean, isDialog: Boolean): Boolean {
                val blockPopups = prefs.getBoolean(HazePrivacyShieldEngine.PREF_BLOCK_POPUPS, true)
                val shieldActive = HazePrivacyShieldEngine.isShieldEnabled(this@MainActivity)
                if (shieldActive && blockPopups && !isUserGesture) {
                    HazePrivacyShieldEngine.recordPopupBlocked(this@MainActivity, url, getActiveTab()?.url, isPrivateMode)
                    return false
                }
                runOnUiThread {
                    addNewTab(if (url.isNotEmpty() && !url.startsWith("about:")) url else HAZE_HOME_URL)
                }
                return true
            }
        })
    }

    private fun injectCosmeticAdBlockers(targetView: WebView?, targetUrl: String?) {
        if (targetView == null || targetUrl.isNullOrEmpty() || targetUrl.startsWith("haze://") || targetUrl.startsWith("about:") || targetUrl.startsWith("data:")) return
        if (!HazePrivacyShieldEngine.isShieldEnabled(this)) return
        val domain = runCatching { Uri.parse(targetUrl).host }.getOrNull()
        val compiledCosmetic = HazeFilterListManager.getCosmeticCss(this, domain)
        if (compiledCosmetic.isNotEmpty()) {
            val escapedCss = compiledCosmetic.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
            val cosmeticScript = """
                (function() {
                    try {
                        var style = document.getElementById('__haze_cosmetic_style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = '__haze_cosmetic_style';
                            style.type = 'text/css';
                            (document.head || document.documentElement).appendChild(style);
                        }
                        style.textContent = '$escapedCss';
                    } catch(e) {}
                })();
            """.trimIndent()
            targetView.evaluateJavascript(cosmeticScript, null)
        }
        targetView.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DYNAMIC_BANNER_CLEANER_SCRIPT, null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView) {
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        webView.addJavascriptInterface(WebAppInterface(webView), "Android")

        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        webView.settings.apply {
            javaScriptEnabled = jsEnabled
            domStorageEnabled = !isPrivateMode
            databaseEnabled = !isPrivateMode
            // Web content has no reason to access app-local files. Downloads and picker flows
            // use scoped content URIs instead.
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            val tabForSettings = getTabFromWebView(webView)
            val isDesk = tabForSettings?.isDesktop == true || tabForSettings?.browserTab?.isDesktop == true
            val isSpotify = (tabForSettings?.url ?: "").contains("spotify.com", ignoreCase = true)
            userAgentString = if (isDesk) {
                if (isSpotify) com.example.haze.engine.chromium.ChromiumBrowserTab.SPOTIFY_DESKTOP_UA else com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_USER_AGENT
            } else {
                com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_USER_AGENT
            }
            useWideViewPort = isDesk
            loadWithOverviewMode = isDesk
            cacheMode = if (isPrivateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            // Pre-rasterizing every tab consumes significant memory and competes
            // with the active tab for rendering time.
            offscreenPreRaster = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
            saveFormData = true
            savePassword = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
        }

        // Chromium Cookie Store Session Security & Anti-Tracking Policy
        HazeAntiTrackingEngine.configureWebViewCookies(webView, this, isPrivateMode)
        HazeSecurityManager.configureSessionSecurity(webView)
        HazeSecurityManager.applyBrowserIsolation(webView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            WebView.startSafeBrowsing(this) { }
        }

        val isPriv = getTabFromWebView(webView)?.isPrivate ?: isPrivateMode
        if (!isPriv && HazeAccountManager.isSignedIn(this)) {
            HazeAccountManager.syncGoogleAccountToCookieManager(this)
        }

        // Web download listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            val isPriv = getTabFromWebView(webView)?.isPrivate ?: isPrivateMode
            HazeDownloadsManager.initDownload(this, url, userAgent, contentDisposition, mimeType, contentLength, isPriv)
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
                        val currentTab = getTabFromWebView(webView)
                        if (currentTab != null) {
                            HazeContextMenuManager.showPageContextMenu(this, currentUrl, currentTitle, currentTab.browserTab)
                            true
                        } else false
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

            private fun handleExternalScheme(view: WebView?, targetUrl: String): Boolean {
                if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://") ||
                    targetUrl.startsWith("about:") || targetUrl.startsWith("data:") ||
                    targetUrl.startsWith("javascript:") || targetUrl.startsWith("haze://")) {
                    return false
                }
                return try {
                    val intent = Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME)
                    if (intent != null) {
                        val pm = packageManager
                        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        if (info != null) {
                            startActivity(intent)
                            return true
                        }
                        // Handle browser fallback URL (e.g. from intent:// urls on Google News / MSN)
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty() && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                            view?.loadUrl(fallbackUrl)
                            return true
                        }
                    }
                    false
                } catch (e: Exception) {
                    true // Consume invalid or unsupported scheme to prevent WebView crash / net::ERR_UNKNOWN_URL_SCHEME
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var url = request?.url?.toString() ?: return false
                if (handleExternalScheme(view, url)) return true

                if (HazeAntiTrackingEngine.isBounceTrackingProtectionEnabled(this@MainActivity)) {
                    val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(url, this@MainActivity)
                    if (unwrapped != null) {
                        url = unwrapped
                        view?.loadUrl(url)
                        return true
                    }
                }
                if (HazeAntiTrackingEngine.isLinkTrackingProtectionEnabled(this@MainActivity)) {
                    val cleaned = HazeAntiTrackingEngine.stripTrackingParameters(url, this@MainActivity)
                    if (cleaned != url) {
                        url = cleaned
                        view?.loadUrl(url)
                        return true
                    }
                }
                if (request.isForMainFrame) {
                    if (handleNavigationSecurity(view, url)) return true
                }
                return super.shouldOverrideUrlLoading(view, request)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                var finalUrl = url
                if (handleExternalScheme(view, finalUrl)) return true

                if (HazeAntiTrackingEngine.isBounceTrackingProtectionEnabled(this@MainActivity)) {
                    val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(finalUrl, this@MainActivity)
                    if (unwrapped != null) {
                        finalUrl = unwrapped
                        view?.loadUrl(finalUrl)
                        return true
                    }
                }
                if (HazeAntiTrackingEngine.isLinkTrackingProtectionEnabled(this@MainActivity)) {
                    val cleaned = HazeAntiTrackingEngine.stripTrackingParameters(finalUrl, this@MainActivity)
                    if (cleaned != finalUrl) {
                        finalUrl = cleaned
                        view?.loadUrl(finalUrl)
                        return true
                    }
                }
                if (handleNavigationSecurity(view, finalUrl)) return true
                return super.shouldOverrideUrlLoading(view, finalUrl)
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

                HazeDevToolsManager.recordNetworkRequest(
                    tabId = getTabFromWebView(view ?: webView)?.id ?: 0L,
                    url = reqUrl,
                    method = request.method ?: "GET",
                    headers = request.requestHeaders ?: emptyMap(),
                    isMainFrame = isMainFrame,
                    isBlocked = decision.shouldBlock
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
                if (!url.isNullOrEmpty()) {
                    topLevelUrl = url
                    HazeFasterReloadingEngine.recordNavigation(this@MainActivity, url)
                }
                HazeTranslationManager.dismissSuggestionBanner(this@MainActivity, clearLastPrompted = true)
                showPageLoading(5)
                super.onPageStarted(view, url, favicon)
                updatePrivacyShieldButton()
                
                if (url != null && !url.startsWith("haze://") && !url.startsWith("about:")) {
                    val currentTab = getTabFromWebView(view ?: webView)
                    val isDesk = currentTab?.isDesktop == true || currentTab?.browserTab?.isDesktop == true
                    val isSpotify = url.contains("spotify.com", ignoreCase = true)
                    val isYouTube = url.contains("youtube.com", ignoreCase = true)
                    if (isYouTube) {
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.YOUTUBE_ENGINE_SCRIPT, null)
                    }
                    if (isSpotify) {
                        try {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(view ?: webView, true)
                        } catch (_: Throwable) {}
                        view?.settings?.userAgentString = if (isDesk) com.example.haze.engine.chromium.ChromiumBrowserTab.SPOTIFY_DESKTOP_UA else com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_USER_AGENT
                        view?.settings?.useWideViewPort = isDesk
                        view?.settings?.loadWithOverviewMode = isDesk
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.SPOTIFY_ENGINE_SCRIPT, null)
                    } else if (isDesk) {
                        view?.settings?.userAgentString = com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_USER_AGENT
                        view?.settings?.useWideViewPort = true
                        view?.settings?.loadWithOverviewMode = true
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_VIEWPORT_SCRIPT, null)
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_CLIENT_HINTS_SCRIPT, null)
                    } else {
                        view?.settings?.userAgentString = com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_USER_AGENT
                        view?.settings?.useWideViewPort = false
                        view?.settings?.loadWithOverviewMode = false
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_VIEWPORT_SCRIPT, null)
                    }
                    val antiTrackingScript = HazeAntiTrackingEngine.generateAntiFingerprintScript(this@MainActivity)
                    view?.evaluateJavascript(antiTrackingScript, null)

                    injectCosmeticAdBlockers(view, url)
                }
            }

            override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
                val url = request?.url?.toString() ?: return callback?.backToSafety(false) ?: Unit
                HazePrivacyShieldEngine.recordSafeBrowsingThreat(this@MainActivity, url, tabIsPrivate(), threatType)
                val isDark = prefs.getBoolean("is_dark", false) || tabIsPrivate()
                view?.post { view.loadDataWithBaseURL(url, HazeSecurityWarningManager.getSecurityWarningHtml(url, HazePrivacyShieldEngine.CATEGORY_PHISHING, isDark), "text/html", "UTF-8", null) }
                callback?.backToSafety(false)
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val cert = error?.certificate
                val issuedTo = cert?.issuedTo?.dName ?: "Unknown Entity"
                val issuedBy = cert?.issuedBy?.dName ?: "Unknown Authority"
                val failingUrl = error?.url ?: view?.url ?: ""
                
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Security Certificate Warning")
                    .setMessage(
                        "The security certificate for this site is not trusted or invalid.\n\n" +
                        "URL: $failingUrl\n" +
                        "Issued To: $issuedTo\n" +
                        "Issued By: $issuedBy\n\n" +
                        "Continuing may expose your personal information to attackers."
                    )
                    .setPositiveButton("Go Back") { _, _ ->
                        handler?.cancel()
                        if (view?.canGoBack() == true) view.goBack() else loadUrlFromInput(HAZE_HOME_URL)
                    }
                    .setNegativeButton("Proceed anyway (unsafe)") { _, _ ->
                        handler?.proceed()
                    }
                    .setCancelable(false)
                    .show()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: true
                val tab = getTabFromWebView(webView)
                if (tab != null) {
                    runOnUiThread {
                        val msg = if (didCrash) "Web renderer crashed. Reloading page..." else "Renderer reclaimed by OS. Reloading..."
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                        tab.browserTab.reload()
                    }
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    val failingUrl = request.url?.toString() ?: ""
                    if (failingUrl.startsWith("haze://") || failingUrl.startsWith("about:")) return
                    val isDark = prefs.getBoolean("is_dark", false) || tabIsPrivate()
                    val offlineHtml = getOfflineErrorHtml(failingUrl, isDark)
                    view?.post {
                        view.loadDataWithBaseURL(failingUrl, offlineHtml, "text/html", "UTF-8", null)
                    }
                }
            }

            @Suppress("DEPRECATION")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (failingUrl != null && !failingUrl.startsWith("haze://") && !failingUrl.startsWith("about:")) {
                    val isDark = prefs.getBoolean("is_dark", false) || tabIsPrivate()
                    val offlineHtml = getOfflineErrorHtml(failingUrl, isDark)
                    view?.post {
                        view.loadDataWithBaseURL(failingUrl, offlineHtml, "text/html", "UTF-8", null)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                finishPageLoading()
                HazeSecurityManager.persistCookieStore()
                if (url != null && !url.startsWith("haze://") && !url.startsWith("about:")) {
                    val currentTab = getTabFromWebView(view ?: webView)
                    val isDesk = currentTab?.isDesktop == true || currentTab?.browserTab?.isDesktop == true
                    val isSpotify = url.contains("spotify.com", ignoreCase = true)
                    if (isSpotify) {
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.SPOTIFY_ENGINE_SCRIPT, null)
                    } else if (isDesk) {
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_VIEWPORT_SCRIPT, null)
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_CLIENT_HINTS_SCRIPT, null)
                    } else {
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_VIEWPORT_SCRIPT, null)
                    }
                    injectCosmeticAdBlockers(view, url)

                    // Automatic non-English page detection hook
                    view?.evaluateJavascript(HazeTranslationManager.getLanguageDetectionScript(), null)
                }

                // Google & Universal Password Manager hook: commit autofill and persist cookies on submission
                val authHook = """
                    (function() {
                        if (window.__hazeAuthObserverInjected) return;
                        window.__hazeAuthObserverInjected = true;
                        document.addEventListener('submit', function(e) {
                            try {
                                if (window.Android && window.Android.notifyFormSubmitted) {
                                    window.Android.notifyFormSubmitted();
                                }
                            } catch(_) {}
                        }, true);
                    })();
                """.trimIndent()
                view?.evaluateJavascript(authHook, null)

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
                            addressBar.setText(HazeTranslationManager.getDisplayUrl(url))
                        }
                    }

                    // Record history asynchronously off UI thread
                    if (!tab.isPrivate && !isHome && !url.startsWith("haze://") && !url.startsWith("about:")) {
                        val pageTitle = tab.title.ifEmpty { view?.title ?: "Web Page" }
                        dbExecutor.execute {
                            try {
                                val cachedFavicon = HazeFaviconManager.getCachedFaviconFile(this@MainActivity, url)
                                val faviconPath = if (cachedFavicon.exists()) cachedFavicon.absolutePath else null
                                HazeDatabase.getInstance(this@MainActivity).addHistory(url, pageTitle, isPrivate = false, faviconPath = faviconPath)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }

                    // 1. Advanced Cosmetic Filtering (Element Hiding CSS) across all websites
                    val pageDomain = runCatching { Uri.parse(url).host }.getOrNull()
                    val compiledCss = HazeFilterListManager.getCosmeticCss(this@MainActivity, pageDomain)
                    val escapedCss = compiledCss.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                    val cosmeticCss = """
                        (function() {
                            var style = document.getElementById('__haze_cosmetic_style');
                            if (!style) {
                                style = document.createElement('style');
                                style.id = '__haze_cosmetic_style';
                                style.type = 'text/css';
                                (document.head || document.documentElement).appendChild(style);
                            }
                            if ('$escapedCss'.length > 0) {
                                style.textContent = '$escapedCss';
                            }
                            // Unblock page scrolling if cookie banner or anti-adblock overlay locked overflow
                            try {
                                if (document.body && (getComputedStyle(document.body).overflow === 'hidden' || getComputedStyle(document.documentElement).overflow === 'hidden')) {
                                    document.body.style.setProperty('overflow', 'auto', 'important');
                                    document.documentElement.style.setProperty('overflow', 'auto', 'important');
                                }
                            } catch(_) {}
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(cosmeticCss, null)
                    updatePrivacyShieldButton()

                    // 2. Safe & Reliable YouTube Ad-Free Engine
                    if (url.contains("youtube.com", ignoreCase = true)) {
                        view?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.YOUTUBE_ENGINE_SCRIPT, null)
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

                            var mediaScanTimer = null;
                            var observer = new MutationObserver(function() {
                                if (!mediaScanTimer) {
                                    mediaScanTimer = setTimeout(function() {
                                        mediaScanTimer = null;
                                        scanElements();
                                    }, 2000);
                                }
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
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val tabId = getTabFromWebView(webView)?.id ?: 0L
                    val msg = consoleMessage.message()
                    if (msg.equals("Console was cleared", ignoreCase = true)) {
                        HazeDevToolsManager.clearConsoleLogsOnly(tabId)
                    }
                    val level = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> HazeDevToolsManager.ConsoleLogEntry.Level.ERROR
                        ConsoleMessage.MessageLevel.WARNING -> HazeDevToolsManager.ConsoleLogEntry.Level.WARNING
                        ConsoleMessage.MessageLevel.LOG -> HazeDevToolsManager.ConsoleLogEntry.Level.LOG
                        ConsoleMessage.MessageLevel.TIP, ConsoleMessage.MessageLevel.DEBUG -> HazeDevToolsManager.ConsoleLogEntry.Level.INFO
                        else -> HazeDevToolsManager.ConsoleLogEntry.Level.LOG
                    }
                    HazeDevToolsManager.recordConsoleMessage(
                        tabId = tabId,
                        message = msg,
                        sourceId = consoleMessage.sourceId(),
                        lineNumber = consoleMessage.lineNumber(),
                        level = level
                    )
                }
                return super.onConsoleMessage(consoleMessage)
            }
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (newProgress < 100) showPageLoading(newProgress.coerceAtLeast(5))
                else finishPageLoading()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val blockPopups = prefs.getBoolean(HazePrivacyShieldEngine.PREF_BLOCK_POPUPS, true)
                val shieldActive = HazePrivacyShieldEngine.isShieldEnabled(this@MainActivity)
                if (shieldActive && blockPopups && !isUserGesture) {
                    HazePrivacyShieldEngine.recordPopupBlocked(this@MainActivity, view?.url ?: "", view?.url, isPrivateMode)
                    return false
                }

                // 1. Check if user clicked an anchor/link element with target="_blank"
                val hitTest = view?.hitTestResult
                val hitUrl = hitTest?.extra
                if (!hitUrl.isNullOrEmpty() && (hitUrl.startsWith("http://") || hitUrl.startsWith("https://") || hitUrl.startsWith("haze://"))) {
                    addNewTab(hitUrl)
                    return true
                }

                // 2. Window.open(...) or async script navigation via WebViewTransport
                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null && resultMsg != null) {
                    val tempWebView = WebView(this@MainActivity)
                    tempWebView.settings.javaScriptEnabled = true
                    tempWebView.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest?): Boolean {
                            val destUrl = request?.url?.toString()
                            if (!destUrl.isNullOrEmpty() && !destUrl.startsWith("about:")) {
                                runOnUiThread {
                                    addNewTab(destUrl)
                                    tempWebView.destroy()
                                }
                                return true
                            }
                            return false
                        }

                        @Suppress("DEPRECATION")
                        override fun shouldOverrideUrlLoading(v: WebView?, destUrl: String?): Boolean {
                            if (!destUrl.isNullOrEmpty() && !destUrl.startsWith("about:")) {
                                runOnUiThread {
                                    addNewTab(destUrl)
                                    tempWebView.destroy()
                                }
                                return true
                            }
                            return false
                        }
                    }
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }

                return false
            }

            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    this@MainActivity.filePathCallback = null
                    false
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(request.resources)
                    return
                }
                val origin = request.origin?.toString() ?: ""
                val isCameraRequested = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                val isMicRequested = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)

                val camState = if (isCameraRequested) HazeSitePermissionManager.getPermission(this@MainActivity, origin, HazeSitePermissionManager.PermissionType.CAMERA) else null
                val micState = if (isMicRequested) HazeSitePermissionManager.getPermission(this@MainActivity, origin, HazeSitePermissionManager.PermissionType.MICROPHONE) else null

                if (camState == HazeSitePermissionManager.State.DENIED || micState == HazeSitePermissionManager.State.DENIED) {
                    request.deny()
                    return
                }

                val missingPermissions = mutableListOf<String>()
                if (isCameraRequested &&
                    checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(android.Manifest.permission.CAMERA)
                }
                if (isMicRequested &&
                    checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    missingPermissions.add(android.Manifest.permission.RECORD_AUDIO)
                }

                if (missingPermissions.isNotEmpty()) {
                    pendingPermissionRequest = request
                    webPermissionLauncher.launch(missingPermissions.toTypedArray())
                } else {
                    request.grant(request.resources)
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                if (origin == null || callback == null) return
                val locState = HazeSitePermissionManager.getPermission(this@MainActivity, origin, HazeSitePermissionManager.PermissionType.LOCATION)
                if (locState == HazeSitePermissionManager.State.DENIED) {
                    callback.invoke(origin, false, false)
                    return
                } else if (locState == HazeSitePermissionManager.State.ALLOWED) {
                    callback.invoke(origin, true, false)
                    return
                }

                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Location Permission")
                    .setMessage("$origin wants to access your device location.")
                    .setPositiveButton("Allow") { _, _ -> 
                        HazeSitePermissionManager.setPermission(this@MainActivity, origin, HazeSitePermissionManager.PermissionType.LOCATION, HazeSitePermissionManager.State.ALLOWED)
                        callback.invoke(origin, true, true) 
                    }
                    .setNegativeButton("Block") { _, _ -> 
                        HazeSitePermissionManager.setPermission(this@MainActivity, origin, HazeSitePermissionManager.PermissionType.LOCATION, HazeSitePermissionManager.State.DENIED)
                        callback.invoke(origin, false, true) 
                    }
                    .show()
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
                        if (!tab.isPrivate && tab.url != HAZE_HOME_URL && !tab.url.startsWith("about:")) {
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
                    if (!tab.isPrivate && tab.url != HAZE_HOME_URL && !tab.url.startsWith("haze://") && !tab.url.startsWith("about:")) {
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
        wakeScreen()
        if (isPrivateMode && HazeIncognitoAuthManager.isProtectionEnabled(this) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
            HazeIncognitoAuthManager.requestUnlock(this, onSuccess = {
                try {
                    currentTabsList.getOrNull(activeTabIndex)?.browserTab?.onResume()
                } catch (_: Exception) {}
            }, onFailure = {
                // If auth cancelled or failed, safely switch back to normal tabs so private session is protected
                isPrivateMode = false
                if (normalTabs.isEmpty()) {
                    addNewTab(HAZE_HOME_URL)
                } else {
                    switchTab(activeNormalTabIndex.coerceIn(0, normalTabs.size - 1))
                }
                applyTheme()
            })
        } else {
            try {
                currentTabsList.getOrNull(activeTabIndex)?.browserTab?.onResume()
            } catch (_: Exception) {}
        }
        val tab = activeMediaTab
        if (tab != null && isMediaPlaying && miniPlayerManager?.isActive != true) {
            showMiniPlayer(tab, createBrowsingTab = false)
        }
    }

    private fun getTabFromWebView(webView: WebView): Tab? {
        return normalTabs.find { it.webView == webView } ?: privateTabs.find { it.webView == webView }
    }

    override fun onPause() {
        super.onPause()
        HazeIncognitoAuthManager.onAppBackgrounded(this)
        saveSessionState()
        if (!isMediaPlaying && miniPlayerManager?.isActive != true) {
            try {
                currentTabsList.getOrNull(activeTabIndex)?.browserTab?.onPause()
            } catch (_: Exception) {}
        }
    }

    fun saveSessionState() {
        try {
            val tabsJson = JSONArray()
            val urlList = mutableListOf<String>()
            for (tab in normalTabs) {
                if (isHomeTab(tab) && normalTabs.size > 1) continue
                urlList.add(tab.url)
                val obj = JSONObject()
                obj.put("url", tab.url)
                obj.put("title", tab.title)
                obj.put("isPinned", tab.isPinned)
                obj.put("isDesktop", tab.isDesktop)
                if (tab.group != null) {
                    val gObj = JSONObject()
                    gObj.put("id", tab.group!!.id)
                    gObj.put("name", tab.group!!.name)
                    gObj.put("color", tab.group!!.color)
                    gObj.put("isCollapsed", tab.group!!.isCollapsed)
                    obj.put("group", gObj)
                }
                tabsJson.put(obj)
            }
            prefs.edit()
                .putString("saved_session_tabs_json", tabsJson.toString())
                .putString("saved_session_urls", JSONArray(urlList).toString())
                .putInt("saved_session_active_index", activeNormalTabIndex.coerceAtLeast(0))
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreSavedSessionOnStartup(): Boolean {
        val jsonStr = prefs.getString("saved_session_tabs_json", null)
        if (jsonStr.isNullOrBlank()) {
            val legacyUrls = prefs.getString("saved_session_urls", null) ?: return false
            return try {
                val array = JSONArray(legacyUrls)
                if (array.length() == 0) return false
                for (i in 0 until array.length()) {
                    addNewTab(array.getString(i))
                }
                val savedActive = prefs.getInt("saved_session_active_index", 0)
                if (savedActive in normalTabs.indices) switchTab(savedActive, false)
                true
            } catch (_: Exception) { false }
        }
        return try {
            val array = JSONArray(jsonStr)
            if (array.length() == 0) return false
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val url = obj.optString("url", HAZE_HOME_URL)
                val title = obj.optString("title", "New Tab")
                val isPinned = obj.optBoolean("isPinned", false)
                val isDesktop = obj.optBoolean("isDesktop", false)
                val groupObj = obj.optJSONObject("group")
                val group = if (groupObj != null) {
                    TabGroup(
                        id = groupObj.getString("id"),
                        name = groupObj.getString("name"),
                        color = groupObj.getInt("color"),
                        isCollapsed = groupObj.optBoolean("isCollapsed", false)
                    )
                } else null

                val tab = addNewTab(url)
                tab.title = title
                tab.isPinned = isPinned
                tab.isDesktop = isDesktop
                tab.group = group
                if (isDesktop) tab.browserTab.isDesktop = true
            }
            val savedActive = prefs.getInt("saved_session_active_index", 0)
            if (savedActive in normalTabs.indices) {
                switchTab(savedActive, false)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun restorePreviousSession(promptUser: Boolean = false) {
        val jsonStr = prefs.getString("saved_session_tabs_json", null)
        val legacyUrls = prefs.getString("saved_session_urls", null)
        if (jsonStr.isNullOrBlank() && legacyUrls.isNullOrBlank()) {
            if (promptUser) Toast.makeText(this, "No previous session saved", Toast.LENGTH_SHORT).show()
            return
        }
        val count = try {
            if (!jsonStr.isNullOrBlank()) JSONArray(jsonStr).length()
            else JSONArray(legacyUrls).length()
        } catch (_: Exception) { 0 }

        if (count == 0) {
            if (promptUser) Toast.makeText(this, "No tabs in previous session", Toast.LENGTH_SHORT).show()
            return
        }

        if (promptUser) {
            AlertDialog.Builder(this)
                .setTitle("Restore Session?")
                .setMessage("Restore $count tab(s) from your previous session?")
                .setPositiveButton("Restore") { _, _ ->
                    restoreSavedSessionOnStartup()
                    Toast.makeText(this, "Restored $count tab(s)", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            restoreSavedSessionOnStartup()
        }
    }

    fun showRecentlyClosedTabsDialog() {
        if (recentlyClosedTabs.isEmpty()) {
            Toast.makeText(this, "No recently closed tabs", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = recentlyClosedTabs.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recently Closed Tabs")
            .setItems(titles) { _, which ->
                if (which in recentlyClosedTabs.indices) {
                    val closed = recentlyClosedTabs.removeAt(which)
                    if (closed.isPrivate) isPrivateMode = true
                    addNewTab(closed.url)
                    Toast.makeText(this, "Reopened: ${closed.title}", Toast.LENGTH_SHORT).show()
                }
            }
            .setPositiveButton("Reopen Last") { _, _ -> reopenLastClosedTab() }
            .setNegativeButton("Clear All") { _, _ ->
                recentlyClosedTabs.clear()
                Toast.makeText(this, "Recently closed tabs cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    fun reopenLastClosedTab() {
        if (recentlyClosedTabs.isNotEmpty()) {
            val last = recentlyClosedTabs.removeAt(0)
            if (last.isPrivate) isPrivateMode = true
            addNewTab(last.url)
            Toast.makeText(this, "Reopened: ${last.title}", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No closed tabs to reopen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showFullScreenTabs(filterQuery: String? = null) {
        activeFullScreenTabsDialog?.dismiss()
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar)
        activeFullScreenTabsDialog = dialog
        dialog.setOnDismissListener {
            if (activeFullScreenTabsDialog == dialog) {
                activeFullScreenTabsDialog = null
            }
        }
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
        @Suppress("DEPRECATION")
        dialog.window?.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        dialog.setContentView(R.layout.layout_full_screen_tabs)

        val tabsRootContainer = dialog.findViewById<LinearLayout>(R.id.tabsRootContainer)
        val btnNewTabSquircle = dialog.findViewById<FrameLayout>(R.id.btnNewTabSquircle)
        val chromeSegmentedPill = dialog.findViewById<LinearLayout>(R.id.chromeSegmentedPill)
        val btnTogglePersonal = dialog.findViewById<FrameLayout>(R.id.btnTogglePersonal)
        val tvTabCountPill = dialog.findViewById<TextView>(R.id.tvTabCountPill)
        val btnTogglePrivate = dialog.findViewById<FrameLayout>(R.id.btnTogglePrivate)
        val ivPrivatePillIcon = dialog.findViewById<ImageView>(R.id.ivPrivatePillIcon)
        val searchPillContainer = dialog.findViewById<LinearLayout>(R.id.searchPillContainer)

        val btnClose = dialog.findViewById<ImageButton>(R.id.btnCloseTabs)
        val btnRecentlyClosed = dialog.findViewById<ImageButton>(R.id.btnRecentlyClosedTabs)
        val btnToggleViewMode = dialog.findViewById<ImageButton>(R.id.btnToggleViewMode)
        val btnTabsMoreOptions = dialog.findViewById<ImageButton>(R.id.btnTabsMoreOptions)
        val tvCurrentModeLabel = dialog.findViewById<TextView>(R.id.tvCurrentModeLabel)
        val btnNewTabAction = dialog.findViewById<TextView>(R.id.btnNewTabAction)
        val etTabSearch = dialog.findViewById<EditText>(R.id.etTabSearch)
        val btnClearTabSearch = dialog.findViewById<ImageButton>(R.id.btnClearTabSearch)
        val rvActiveTabs = dialog.findViewById<RecyclerView>(R.id.rvActiveTabs)
        val layoutEmptyPersonal = dialog.findViewById<LinearLayout>(R.id.layoutEmptyPersonal)
        val layoutEmptyIncognito = dialog.findViewById<LinearLayout>(R.id.layoutEmptyIncognito)
        val btnEmptyNewIncognito = dialog.findViewById<TextView>(R.id.btnEmptyNewIncognito)
        val btnEmptyIncognitoPin = dialog.findViewById<LinearLayout>(R.id.btnEmptyIncognitoPin)
        val tvEmptyIncognitoPin = dialog.findViewById<TextView>(R.id.tvEmptyIncognitoPin)
        val layoutLockedIncognito = dialog.findViewById<LinearLayout>(R.id.layoutLockedIncognito)
        val btnUnlockIncognito = dialog.findViewById<TextView>(R.id.btnUnlockIncognito)

        val barMultiSelect = dialog.findViewById<LinearLayout>(R.id.barMultiSelect)
        val btnMultiSelectAll = dialog.findViewById<ImageButton>(R.id.btnMultiSelectAll)
        val tvMultiSelectCount = dialog.findViewById<TextView>(R.id.tvMultiSelectCount)
        val btnMultiSelectGroup = dialog.findViewById<ImageButton>(R.id.btnMultiSelectGroup)
        val btnMultiSelectClose = dialog.findViewById<ImageButton>(R.id.btnMultiSelectClose)
        val btnMultiSelectDone = dialog.findViewById<TextView>(R.id.btnMultiSelectDone)

        btnEmptyNewIncognito?.setOnClickListener {
            triggerStrongHaptic(it)
            if (HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                    dialog.dismiss()
                    isPrivateMode = true
                    addNewTab(HAZE_HOME_URL)
                    applyTheme()
                })
            } else {
                dialog.dismiss()
                isPrivateMode = true
                addNewTab(HAZE_HOME_URL)
                applyTheme()
            }
        }

        if (!filterQuery.isNullOrEmpty()) {
            etTabSearch.setText(filterQuery)
        }

        var viewingPrivate = isPrivateMode
        var currentMode = prefs.getInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, HazeTabSystemManager.TAB_MODE_GRID)

        btnNewTabSquircle?.setOnClickListener {
            triggerStrongHaptic(it)
            if (viewingPrivate && HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                    dialog.dismiss()
                    isPrivateMode = true
                    addNewTab(HAZE_HOME_URL)
                    applyTheme()
                })
            } else {
                dialog.dismiss()
                isPrivateMode = viewingPrivate
                addNewTab(HAZE_HOME_URL)
                applyTheme()
            }
        }

        fun updateViewModeIcon() {
            val iconRes = when (currentMode) {
                HazeTabSystemManager.TAB_MODE_CARDS -> R.drawable.ic_view_card
                HazeTabSystemManager.TAB_MODE_LIST -> R.drawable.ic_view_list
                else -> R.drawable.ic_grid
            }
            btnToggleViewMode.setImageResource(iconRes)
        }
        updateViewModeIcon()

        btnRecentlyClosed.setOnClickListener {
            triggerStrongHaptic(it)
            showRecentlyClosedTabsDialog()
        }

        btnClose.setOnClickListener { 
            triggerStrongHaptic(it)
            dialog.dismiss() 
        }

        lateinit var personalAdapter: TabsAdapter
        lateinit var privateAdapter: TabsAdapter

        fun onTabClosed(tabToRemove: Tab, isPriv: Boolean) {
            val list = if (isPriv) privateTabs else normalTabs
            val closedIndex = list.indexOf(tabToRemove)
            if (closedIndex == -1) return

            webContainer.removeView(tabToRemove.swipeRefreshLayout ?: tabToRemove.browserTab.renderView)
            tabToRemove.browserTab.destroy()
            list.removeAt(closedIndex)

            if (!isHomeTab(tabToRemove) && !tabToRemove.isPrivate) {
                recentlyClosedTabs.add(0, ClosedTabInfo(tabToRemove.title, tabToRemove.url, isPrivate = tabToRemove.isPrivate))
                if (recentlyClosedTabs.size > 25) recentlyClosedTabs.removeLast()
                val restoredUrl = tabToRemove.url
                com.google.android.material.snackbar.Snackbar.make(
                    findViewById(R.id.main),
                    "Closed '${tabToRemove.title}'",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).setAction("UNDO") {
                    addNewTab(restoredUrl)
                }.show()
            }

            if (list.isEmpty()) {
                if (isPriv) {
                    ChromiumEngineManager.getEngine(this@MainActivity).clearPrivateSession()
                    HazeIncognitoAuthManager.isIncognitoSessionUnlocked = false
                    if (normalTabs.isNotEmpty()) {
                        isPrivateMode = false
                        switchTab(activeNormalTabIndex.coerceIn(0, normalTabs.size - 1))
                    } else {
                        addNewTab(HAZE_HOME_URL)
                        activeNormalTabIndex = 0
                    }
                } else {
                    // Normal Tab Safety Rule: Invariant: Haze must always retain or create at least one usable normal tab when all normal tabs are closed (never 0 normal tabs).
                    addNewTab(HAZE_HOME_URL)
                    activeNormalTabIndex = 0
                }
            } else {
                if (isPriv) {
                    if (activePrivateTabIndex >= closedIndex) activePrivateTabIndex--
                    if (activePrivateTabIndex < 0) activePrivateTabIndex = 0
                    if (isPrivateMode) switchTab(activePrivateTabIndex)
                } else {
                    if (activeNormalTabIndex >= closedIndex) activeNormalTabIndex--
                    if (activeNormalTabIndex < 0) activeNormalTabIndex = 0
                    if (!isPrivateMode) switchTab(activeNormalTabIndex)
                }
            }
            tabCountBtn.text = currentTabsList.size.toString()
            val currentCount = if (viewingPrivate) privateTabs.size else normalTabs.size
            tvTabCountPill?.text = currentCount.toString()
            val adapter = if (isPriv) privateAdapter else personalAdapter
            adapter.notifyItemRemoved(closedIndex)
            if (list.isEmpty()) {
                dialog.dismiss()
                showFullScreenTabs()
            }
        }

        personalAdapter = TabsAdapter(normalTabs, currentMode, { clickedTab ->
            isPrivateMode = false
            val idx = normalTabs.indexOf(clickedTab)
            dialog.dismiss()
            if (idx != -1) switchTab(idx)
            applyTheme()
        }, { tabToClose ->
            if (tabToClose.isPinned) {
                AlertDialog.Builder(this)
                    .setTitle("Pinned Tab")
                    .setMessage("This tab is pinned. Are you sure you want to close it?")
                    .setPositiveButton("Close") { _, _ -> onTabClosed(tabToClose, false) }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                onTabClosed(tabToClose, false)
            }
        }, false)

        privateAdapter = TabsAdapter(privateTabs, currentMode, { clickedTab ->
            if (HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                    isPrivateMode = true
                    val idx = privateTabs.indexOf(clickedTab)
                    dialog.dismiss()
                    if (idx != -1) switchTab(idx)
                    applyTheme()
                })
            } else {
                isPrivateMode = true
                val idx = privateTabs.indexOf(clickedTab)
                dialog.dismiss()
                if (idx != -1) switchTab(idx)
                applyTheme()
            }
        }, { tabToClose ->
            onTabClosed(tabToClose, true)
        }, true)

        fun setupLayoutManager() {
            rvActiveTabs.layoutManager = when (currentMode) {
                HazeTabSystemManager.TAB_MODE_GRID -> {
                    val orientation = resources.configuration.orientation
                    val spanCount = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 3 else 2
                    androidx.recyclerview.widget.GridLayoutManager(this@MainActivity, spanCount)
                }
                else -> LinearLayoutManager(this@MainActivity)
            }
        }
        setupLayoutManager()

        fun applyNewViewMode(newMode: Int) {
            currentMode = newMode
            prefs.edit().putInt(HazeTabSystemManager.PREF_TAB_VIEW_MODE, newMode).apply()
            updateViewModeIcon()
            setupLayoutManager()
            personalAdapter.tabMode = newMode
            privateAdapter.tabMode = newMode
            personalAdapter.notifyDataSetChanged()
            privateAdapter.notifyDataSetChanged()
        }

        btnToggleViewMode.setOnClickListener {
            triggerStrongHaptic(it)
            HazeTabSystemManager.showViewModeSelector(this, currentMode) { newMode ->
                applyNewViewMode(newMode)
            }
        }

        etTabSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim() ?: ""
                btnClearTabSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                personalAdapter.filter(q)
                privateAdapter.filter(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnClearTabSearch.setOnClickListener {
            etTabSearch.setText("")
        }

        fun updateSelectionUI() {
            val adapter = if (viewingPrivate) privateAdapter else personalAdapter
            val count = adapter.selectedTabIds.size
            tvMultiSelectCount?.text = "$count selected"
            val hasSelection = count >= 1
            btnMultiSelectGroup?.alpha = if (hasSelection) 1f else 0.4f
            btnMultiSelectGroup?.isEnabled = hasSelection
            btnMultiSelectClose?.alpha = if (hasSelection) 1f else 0.4f
            btnMultiSelectClose?.isEnabled = hasSelection
        }

        personalAdapter.onSelectionChanged = { updateSelectionUI() }
        privateAdapter.onSelectionChanged = { updateSelectionUI() }

        btnMultiSelectDone?.setOnClickListener {
            triggerStrongHaptic(it)
            val adapter = if (viewingPrivate) privateAdapter else personalAdapter
            adapter.isSelectionMode = false
            adapter.selectedTabIds.clear()
            barMultiSelect?.visibility = View.GONE
            searchPillContainer?.visibility = View.VISIBLE
            adapter.notifyDataSetChanged()
        }

        btnMultiSelectAll?.setOnClickListener {
            triggerStrongHaptic(it)
            val adapter = if (viewingPrivate) privateAdapter else personalAdapter
            if (adapter.selectedTabIds.size >= adapter.displayedList.size) {
                adapter.selectedTabIds.clear()
            } else {
                adapter.selectedTabIds.addAll(adapter.displayedList.map { it.id })
            }
            updateSelectionUI()
            adapter.notifyDataSetChanged()
        }

        btnMultiSelectGroup?.setOnClickListener {
            triggerStrongHaptic(it)
            val adapter = if (viewingPrivate) privateAdapter else personalAdapter
            val selected = adapter.displayedList.filter { adapter.selectedTabIds.contains(it.id) }
            if (selected.isEmpty()) {
                Toast.makeText(this@MainActivity, "Select tabs to group", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val input = EditText(this@MainActivity).apply {
                val domain = HazeTabSystemManager.getDomain(selected.first().url)
                setText("Group ($domain)")
            }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Group ${selected.size} Tabs")
                .setView(input)
                .setPositiveButton("Group") { _, _ ->
                    val groupName = input.text.toString().trim().ifEmpty { "New Group" }
                    val newGroup = TabGroup(
                        id = System.currentTimeMillis().toString(),
                        name = groupName,
                        color = groupColors.random()
                    )
                    selected.forEach { it.group = newGroup }
                    adapter.isSelectionMode = false
                    adapter.selectedTabIds.clear()
                    barMultiSelect?.visibility = View.GONE
                    searchPillContainer?.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, "Grouped ${selected.size} tabs into '$groupName'", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnMultiSelectClose?.setOnClickListener {
            triggerStrongHaptic(it)
            val adapter = if (viewingPrivate) privateAdapter else personalAdapter
            val selected = adapter.displayedList.filter { adapter.selectedTabIds.contains(it.id) }
            if (selected.isEmpty()) return@setOnClickListener

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Close ${selected.size} Tabs?")
                .setMessage("Are you sure you want to close ${selected.size} selected tabs?")
                .setPositiveButton("Close") { _, _ ->
                    val count = selected.size
                    for (tab in selected) {
                        onTabClosed(tab, viewingPrivate)
                    }
                    adapter.isSelectionMode = false
                    adapter.selectedTabIds.clear()
                    barMultiSelect?.visibility = View.GONE
                    searchPillContainer?.visibility = View.VISIBLE
                    adapter.notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, "Closed $count tabs", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        var currentTouchHelper: ItemTouchHelper? = null
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")

        val touchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            var dragFrom = -1
            var dragTo = -1

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                if (adapter.isSelectionMode) return 0
                val pos = viewHolder.bindingAdapterPosition
                val items = adapter.displayedItems
                if (pos !in items.indices) return 0
                val item = items[pos]
                val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                val swipeFlags = if (item.isPinned) 0 else (ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                return makeMovementFlags(dragFlags, swipeFlags)
            }

            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                val fromPos = viewHolder.bindingAdapterPosition
                val toPos = target.bindingAdapterPosition
                if (fromPos !in adapter.displayedItems.indices || toPos !in adapter.displayedItems.indices) return false
                if (dragFrom == -1) dragFrom = fromPos
                dragTo = toPos

                adapter.onItemMoved(fromPos, toPos)
                return true
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.alpha = 1.0f
                viewHolder.itemView.scaleX = 1.0f
                viewHolder.itemView.scaleY = 1.0f
                val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    adapter.onDragCompleted(dragFrom, dragTo)
                }
                dragFrom = -1
                dragTo = -1
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    triggerStrongHaptic(viewHolder?.itemView)
                    viewHolder?.itemView?.animate()?.scaleX(1.04f)?.scaleY(1.04f)?.setDuration(120)?.start()
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                val pos = viewHolder.bindingAdapterPosition
                val items = adapter.displayedItems
                if (pos in items.indices) {
                    val item = items[pos]
                    if (item.isPinned) {
                        adapter.notifyItemChanged(pos)
                        Toast.makeText(this@MainActivity, "Pinned tabs cannot be swiped away", Toast.LENGTH_SHORT).show()
                        return
                    }
                    when (item) {
                        is TabSwitcherItem.Single -> {
                            onTabClosed(item.tab, viewingPrivate)
                        }
                        is TabSwitcherItem.Group -> {
                            for (t in item.tabs.toList()) {
                                onTabClosed(t, viewingPrivate)
                            }
                        }
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val width = viewHolder.itemView.width.toFloat()
                    val alpha = (1.0f - Math.abs(dX) / width).coerceIn(0.15f, 1.0f)
                    viewHolder.itemView.alpha = alpha
                    viewHolder.itemView.translationX = dX
                } else {
                    super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
                }
            }
        }
        currentTouchHelper = ItemTouchHelper(touchHelperCallback)
        currentTouchHelper.attachToRecyclerView(rvActiveTabs)

        fun updateUI(animate: Boolean = false) {
            val isDarkTheme = prefs.getBoolean("is_dark", false) || viewingPrivate
            val count = if (viewingPrivate) privateTabs.size else normalTabs.size
            tvTabCountPill?.text = count.toString()

            HazeIncognitoAuthManager.applyWindowSecurity(this@MainActivity, viewingPrivate)

            if (viewingPrivate) {
                tabsRootContainer?.setBackgroundColor(Color.parseColor("#141518"))
                chromeSegmentedPill?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(20f).toFloat()
                    setColor(Color.parseColor("#25272B"))
                }
                searchPillContainer?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(24f).toFloat()
                    setColor(Color.parseColor("#25272B"))
                }
                barMultiSelect?.setBackgroundColor(Color.parseColor("#20232A"))
                tvMultiSelectCount?.setTextColor(Color.parseColor("#A8C7FA"))
                btnMultiSelectAll?.setColorFilter(Color.parseColor("#A8C7FA"))
                btnMultiSelectGroup?.setColorFilter(Color.parseColor("#A8C7FA"))
                btnMultiSelectDone?.setTextColor(Color.parseColor("#A8C7FA"))

                etTabSearch.setTextColor(Color.WHITE)
                etTabSearch.setHintTextColor(Color.parseColor("#9AA0A6"))
                btnClose.setColorFilter(Color.WHITE)
                btnRecentlyClosed.setColorFilter(Color.parseColor("#9AA0A6"))
                val hasPin = HazeIncognitoAuthManager.hasPinConfigured(this@MainActivity)
                tvEmptyIncognitoPin?.text = if (hasPin) "Incognito Protection Settings" else "Set PIN Protection"
                btnToggleViewMode.setColorFilter(Color.WHITE)
                btnTabsMoreOptions.setColorFilter(Color.WHITE)

                // Personal icon inactive
                btnTogglePersonal?.background = null
                btnTogglePersonal?.elevation = 0f
                tvTabCountPill?.setTextColor(Color.parseColor("#9AA0A6"))
                tvTabCountPill?.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tab_box)?.mutate()?.apply {
                    setTint(Color.parseColor("#5F6368"))
                }

                // Private icon active (highlighted rounded pill)
                btnTogglePrivate?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(14f).toFloat()
                    setColor(Color.parseColor("#383B40"))
                }
                btnTogglePrivate?.elevation = dpToPx(1f).toFloat()
                ivPrivatePillIcon?.setColorFilter(Color.WHITE)

                tvCurrentModeLabel.text = "Private Tabs"
                btnNewTabAction.text = "+ New Private"
                btnNewTabAction.setTextColor(accentColor)

                val isLocked = HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked

                if (isLocked) {
                    rvActiveTabs.visibility = View.GONE
                    layoutEmptyIncognito?.visibility = View.GONE
                    layoutLockedIncognito?.visibility = View.VISIBLE
                    val unlockAction = View.OnClickListener {
                        triggerStrongHaptic(it)
                        HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                            updateUI(animate = true)
                        })
                    }
                    btnUnlockIncognito?.setOnClickListener(unlockAction)
                    layoutLockedIncognito?.setOnClickListener(unlockAction)
                } else if (privateTabs.isEmpty()) {
                    layoutLockedIncognito?.visibility = View.GONE
                layoutEmptyPersonal?.visibility = View.GONE
                rvActiveTabs.visibility = View.GONE
                layoutEmptyIncognito?.visibility = View.VISIBLE
            } else {
                layoutLockedIncognito?.visibility = View.GONE
                layoutEmptyPersonal?.visibility = View.GONE
                layoutEmptyIncognito?.visibility = View.GONE
                rvActiveTabs.visibility = View.VISIBLE
                    if (animate) {
                        rvActiveTabs.animate().cancel()
                        rvActiveTabs.alpha = 0f
                        rvActiveTabs.adapter = privateAdapter
                        rvActiveTabs.animate().alpha(1f).setDuration(220L).setInterpolator(BUTTER_INTERPOLATOR).start()
                    } else {
                        rvActiveTabs.adapter = privateAdapter
                    }
                    currentTouchHelper?.attachToRecyclerView(rvActiveTabs)
                }
            } else {
                val lightBg = if (isDarkTheme) Color.parseColor("#1A1C1E") else Color.parseColor("#F8FAFD")
                val pillBg = if (isDarkTheme) Color.parseColor("#282A2E") else Color.parseColor("#E9EEF6")
                val textColor = if (isDarkTheme) Color.WHITE else Color.parseColor("#202124")
                val subColor = if (isDarkTheme) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

                tabsRootContainer?.setBackgroundColor(lightBg)
                chromeSegmentedPill?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(20f).toFloat()
                    setColor(pillBg)
                }
                searchPillContainer?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(24f).toFloat()
                    setColor(pillBg)
                }
                barMultiSelect?.setBackgroundColor(if (isDarkTheme) Color.parseColor("#26282E") else Color.parseColor("#E8EEF9"))
                tvMultiSelectCount?.setTextColor(accentColor)
                btnMultiSelectAll?.setColorFilter(accentColor)
                btnMultiSelectGroup?.setColorFilter(accentColor)
                btnMultiSelectDone?.setTextColor(accentColor)

                etTabSearch.setTextColor(textColor)
                etTabSearch.setHintTextColor(subColor)
                btnClose.setColorFilter(textColor)
                btnRecentlyClosed.setColorFilter(subColor)
                btnToggleViewMode.setColorFilter(textColor)
                btnTabsMoreOptions.setColorFilter(textColor)

                // Personal active (white rounded card inside pill)
                btnTogglePersonal?.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(14f).toFloat()
                    setColor(if (isDarkTheme) Color.parseColor("#383B40") else Color.WHITE)
                    if (!isDarkTheme) {
                        setStroke(dpToPx(1f), Color.parseColor("#D8DEE8"))
                    }
                }
                btnTogglePersonal?.elevation = dpToPx(1f).toFloat()
                tvTabCountPill?.setTextColor(if (isDarkTheme) Color.WHITE else Color.parseColor("#202124"))
                tvTabCountPill?.background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_tab_box)?.mutate()?.apply {
                    setTint(if (isDarkTheme) Color.WHITE else Color.parseColor("#202124"))
                }

                // Private inactive
                btnTogglePrivate?.background = null
                btnTogglePrivate?.elevation = 0f
                ivPrivatePillIcon?.setColorFilter(subColor)

                tvCurrentModeLabel.text = "Personal Tabs"
                btnNewTabAction.text = "+ New Tab"
                btnNewTabAction.setTextColor(accentColor)

                layoutLockedIncognito?.visibility = View.GONE
                layoutEmptyIncognito?.visibility = View.GONE

                if (normalTabs.isEmpty()) {
                    rvActiveTabs.visibility = View.GONE
                    layoutEmptyPersonal?.visibility = View.VISIBLE
                } else {
                    layoutEmptyPersonal?.visibility = View.GONE
                    rvActiveTabs.visibility = View.VISIBLE
                    if (animate) {
                        rvActiveTabs.animate().cancel()
                        rvActiveTabs.alpha = 0f
                        rvActiveTabs.adapter = personalAdapter
                        rvActiveTabs.animate().alpha(1f).setDuration(220L).setInterpolator(BUTTER_INTERPOLATOR).start()
                    } else {
                        rvActiveTabs.adapter = personalAdapter
                    }
                    currentTouchHelper?.attachToRecyclerView(rvActiveTabs)
                }
            }
        }

        fun handlePrivatePinAction() {
            if (!HazeIncognitoAuthManager.hasPinConfigured(this@MainActivity)) {
                HazeIncognitoAuthManager.showSetPinDialog(this@MainActivity) { success ->
                    if (success) {
                        updateUI(animate = false)
                    }
                }
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Incognito Security")
                    .setItems(arrayOf("Lock Private Tabs Now", "Change PIN / Protection Settings")) { _, which ->
                        if (which == 0) {
                            HazeIncognitoAuthManager.lockNow(this@MainActivity)
                            updateUI(animate = true)
                            Toast.makeText(this@MainActivity, "Incognito locked", Toast.LENGTH_SHORT).show()
                        } else {
                            HazeIncognitoAuthManager.showProtectionSettingsDialog(this@MainActivity) {
                                updateUI(animate = false)
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        btnEmptyIncognitoPin?.setOnClickListener {
            triggerStrongHaptic(it)
            handlePrivatePinAction()
        }

        btnTabsMoreOptions.setOnClickListener {
            triggerStrongHaptic(it)
            val list = if (viewingPrivate) privateTabs else normalTabs
            HazeTabSystemManager.showTabSwitcherMenu(
                activity = this@MainActivity,
                tabs = list,
                activeIndex = if (viewingPrivate) activePrivateTabIndex else activeNormalTabIndex,
                currentMode = currentMode,
                onModeChanged = { newMode -> applyNewViewMode(newMode) },
                onSortRequested = { sortMode ->
                    personalAdapter.sortMode = sortMode
                    privateAdapter.sortMode = sortMode
                    personalAdapter.notifyDataSetChanged()
                    privateAdapter.notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, "Sorted tabs by ${sortMode.name.lowercase()}", Toast.LENGTH_SHORT).show()
                },
                onRefresh = {
                    personalAdapter.notifyDataSetChanged()
                    privateAdapter.notifyDataSetChanged()
                },
                onCloseTab = { tabToClose ->
                    onTabClosed(tabToClose, viewingPrivate)
                },
                isPrivate = viewingPrivate,
                onSelectTabsRequested = {
                    val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                    adapter.isSelectionMode = true
                    barMultiSelect?.visibility = View.VISIBLE
                    searchPillContainer?.visibility = View.GONE
                    adapter.selectedTabIds.clear()
                    updateSelectionUI()
                    adapter.notifyDataSetChanged()
                },
                onPinSettingsRequested = {
                    handlePrivatePinAction()
                }
            )
        }

        btnTogglePersonal.setOnClickListener {
            triggerStrongHaptic(it)
            if (viewingPrivate) {
                viewingPrivate = false
                val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                if (adapter.isSelectionMode) {
                    barMultiSelect?.visibility = View.VISIBLE
                    searchPillContainer?.visibility = View.GONE
                } else {
                    barMultiSelect?.visibility = View.GONE
                    searchPillContainer?.visibility = View.VISIBLE
                }
                updateUI(animate = true)
            }
        }

        btnTogglePrivate.setOnClickListener {
            triggerStrongHaptic(it)
            if (!viewingPrivate) {
                if (HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                    HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                        viewingPrivate = true
                        val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                        if (adapter.isSelectionMode) {
                            barMultiSelect?.visibility = View.VISIBLE
                            searchPillContainer?.visibility = View.GONE
                        } else {
                            barMultiSelect?.visibility = View.GONE
                            searchPillContainer?.visibility = View.VISIBLE
                        }
                        updateUI(animate = true)
                    }, onFailure = {
                        // User cancelled/failed auth; keep viewing personal tabs
                    })
                } else {
                    viewingPrivate = true
                    val adapter = if (viewingPrivate) privateAdapter else personalAdapter
                    if (adapter.isSelectionMode) {
                        barMultiSelect?.visibility = View.VISIBLE
                        searchPillContainer?.visibility = View.GONE
                    } else {
                        barMultiSelect?.visibility = View.GONE
                        searchPillContainer?.visibility = View.VISIBLE
                    }
                    updateUI(animate = true)
                }
            }
        }

        btnNewTabAction.setOnClickListener {
            triggerStrongHaptic(it)
            if (viewingPrivate && HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                    dialog.dismiss()
                    isPrivateMode = true
                    addNewTab(HAZE_HOME_URL)
                    applyTheme()
                })
            } else {
                dialog.dismiss()
                isPrivateMode = viewingPrivate
                addNewTab(HAZE_HOME_URL)
                applyTheme()
            }
        }

        updateUI()
        dialog.show()

        if (viewingPrivate && HazeIncognitoAuthManager.isProtectionEnabled(this@MainActivity) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
            HazeIncognitoAuthManager.requestUnlock(this@MainActivity, onSuccess = {
                updateUI(animate = true)
            }, onFailure = {
                viewingPrivate = false
                updateUI(animate = true)
            })
        }
    }

    private fun showGroupPromptDialog(tab1: Tab, tab2: Tab, adapter: TabsAdapter) {
        val defaultTitle = tab2.group?.name ?: "2 tabs"
        val defaultColor = tab2.group?.color ?: HazeTabGroupDialogManager.GROUP_COLORS[1]
        HazeTabGroupDialogManager.showNewTabGroupDialog(
            context = this,
            initialTitle = defaultTitle,
            initialColor = defaultColor,
            isEditing = false
        ) { title, color ->
            val group = tab2.group ?: TabGroup(
                id = System.currentTimeMillis().toString(),
                name = title,
                color = color
            )
            group.name = title
            group.color = color
            tab1.group = group
            tab2.group = group
            adapter.notifyDataSetChanged()
        }
    }

    sealed class TabSwitcherItem {
        abstract val id: Long
        abstract val isPinned: Boolean

        data class Single(val tab: Tab) : TabSwitcherItem() {
            override val id: Long get() = tab.id
            override val isPinned: Boolean get() = tab.isPinned
        }

        data class Group(val group: TabGroup, val tabs: MutableList<Tab>) : TabSwitcherItem() {
            override val id: Long get() = group.id.hashCode().toLong()
            override val isPinned: Boolean get() = tabs.any { it.isPinned }
        }
    }

    inner class TabsAdapter(
        private val originalList: MutableList<Tab>,
        var tabMode: Int,
        private val onItemClick: (Tab) -> Unit,
        private val onCloseClick: (Tab) -> Unit,
        private val isPrivateList: Boolean
    ) : RecyclerView.Adapter<TabsAdapter.TabViewHolder>() {

        var sortMode: TabSortMode = TabSortMode.DEFAULT
        private var filterQuery: String = ""
        val selectedTabIds = mutableSetOf<Long>()
        var isSelectionMode: Boolean = false
        var onSelectionChanged: ((Int) -> Unit)? = null

        val displayedItems: List<TabSwitcherItem>
            get() {
                val base = if (filterQuery.isEmpty()) {
                    originalList
                } else {
                    originalList.filter {
                        it.title.contains(filterQuery, ignoreCase = true) || it.url.contains(filterQuery, ignoreCase = true)
                    }
                }
                val sorted = when (sortMode) {
                    TabSortMode.RECENT -> base.sortedWith(compareByDescending<Tab> { it.isPinned }.thenByDescending { it.lastAccessedTime })
                    TabSortMode.DOMAIN -> base.sortedWith(compareByDescending<Tab> { it.isPinned }.thenBy { HazeTabSystemManager.getDomain(it.url).lowercase() })
                    TabSortMode.DEFAULT -> base.sortedWith(compareByDescending<Tab> { it.isPinned }.thenBy { it.group?.id ?: "" })
                }

                if (tabMode == HazeTabSystemManager.TAB_MODE_LIST) {
                    val seenCollapsedGroupIds = mutableSetOf<String>()
                    val result = mutableListOf<TabSwitcherItem>()
                    for (tab in sorted) {
                        val grp = tab.group
                        if (grp != null && grp.isCollapsed) {
                            if (!seenCollapsedGroupIds.contains(grp.id)) {
                                seenCollapsedGroupIds.add(grp.id)
                                result.add(TabSwitcherItem.Single(tab))
                            }
                        } else {
                            result.add(TabSwitcherItem.Single(tab))
                        }
                    }
                    return result
                }

                // Cards & Tiles View: Cluster member tabs into unified Tab Group card (Image 1)
                val seenGroupIds = mutableSetOf<String>()
                val result = mutableListOf<TabSwitcherItem>()
                for (tab in sorted) {
                    val grp = tab.group
                    if (grp != null) {
                        if (!seenGroupIds.contains(grp.id)) {
                            seenGroupIds.add(grp.id)
                            val memberTabs = sorted.filter { it.group?.id == grp.id }.toMutableList()
                            result.add(TabSwitcherItem.Group(grp, memberTabs))
                        }
                    } else {
                        result.add(TabSwitcherItem.Single(tab))
                    }
                }
                return result
            }

        val displayedList: List<Tab>
            get() = displayedItems.flatMap { item ->
                when (item) {
                    is TabSwitcherItem.Single -> listOf(item.tab)
                    is TabSwitcherItem.Group -> item.tabs
                }
            }

        fun filter(query: String) {
            filterQuery = query.lowercase().trim()
            notifyDataSetChanged()
        }

        fun onItemMoved(fromPosition: Int, toPosition: Int) {
            val items = displayedItems
            if (fromPosition in items.indices && toPosition in items.indices) {
                val fromItem = items[fromPosition]
                val toItem = items[toPosition]
                val fromTab = when (fromItem) { is TabSwitcherItem.Single -> fromItem.tab; is TabSwitcherItem.Group -> fromItem.tabs.firstOrNull() }
                val toTab = when (toItem) { is TabSwitcherItem.Single -> toItem.tab; is TabSwitcherItem.Group -> toItem.tabs.firstOrNull() }
                if (fromTab != null && toTab != null) {
                    val originalFromIndex = originalList.indexOf(fromTab)
                    val originalToIndex = originalList.indexOf(toTab)
                    if (originalFromIndex != -1 && originalToIndex != -1) {
                        java.util.Collections.swap(originalList, originalFromIndex, originalToIndex)
                        notifyItemMoved(fromPosition, toPosition)
                    }
                }
            }
        }

        fun onDragCompleted(fromPosition: Int, toPosition: Int) {
            val items = displayedItems
            if (fromPosition in items.indices && toPosition in items.indices && fromPosition != toPosition) {
                val fromItem = items[fromPosition]
                val toItem = items[toPosition]

                if (fromItem is TabSwitcherItem.Single && toItem is TabSwitcherItem.Single) {
                    val draggedTab = fromItem.tab
                    val targetTab = toItem.tab

                    HazeTabGroupDialogManager.showNewTabGroupDialog(
                        context = this@MainActivity,
                        initialTitle = "2 tabs",
                        initialColor = HazeTabGroupDialogManager.GROUP_COLORS[1]
                    ) { title, color ->
                        val newGroup = TabGroup(
                            id = System.currentTimeMillis().toString(),
                            name = title,
                            color = color
                        )
                        draggedTab.group = newGroup
                        targetTab.group = newGroup
                        notifyDataSetChanged()
                    }
                } else if (fromItem is TabSwitcherItem.Single && toItem is TabSwitcherItem.Group) {
                    val draggedTab = fromItem.tab
                    val targetGroup = toItem.group
                    val oldGroup = draggedTab.group
                    draggedTab.group = targetGroup
                    notifyDataSetChanged()
                    com.google.android.material.snackbar.Snackbar.make(
                        findViewById(R.id.main),
                        "Added '${draggedTab.title}' to group '${targetGroup.name}'",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).setAction("UNDO") {
                        draggedTab.group = oldGroup
                        notifyDataSetChanged()
                    }.show()
                } else if (fromItem is TabSwitcherItem.Group && toItem is TabSwitcherItem.Single) {
                    val targetTab = toItem.tab
                    val sourceGroup = fromItem.group
                    targetTab.group = sourceGroup
                    notifyDataSetChanged()
                }
            }
        }

        inner class TabViewHolder(val view: View, val viewType: Int) : RecyclerView.ViewHolder(view) {
            // Row views (viewType == 0)
            val rowContainer: LinearLayout? = view.findViewById(R.id.tabRowContainer)
            val groupHeader: LinearLayout? = view.findViewById(R.id.groupHeader)
            val groupColorBox: View? = view.findViewById(R.id.groupColorBox)
            val groupNameText: TextView? = view.findViewById(R.id.groupNameText)
            val groupCountText: TextView? = view.findViewById(R.id.groupCountText)
            val groupSummaryText: TextView? = view.findViewById(R.id.groupSummaryText)
            val groupChevron: ImageView? = view.findViewById(R.id.groupChevron)
            val btnGroupMore: ImageView? = view.findViewById(R.id.btnGroupMore)
            val groupSideIndicator: View? = view.findViewById(R.id.groupSideIndicator)
            val tabIcon: ImageView? = view.findViewById(R.id.tabIcon)
            val tabTitle: TextView? = view.findViewById(R.id.tabTitle)
            val tabUrl: TextView? = view.findViewById(R.id.tabUrl)
            val tabPinIcon: ImageView? = view.findViewById(R.id.tabPinIcon)
            val tabClose: ImageButton? = view.findViewById(R.id.tabClose)
            val rowTabSelectCheckbox: ImageView? = view.findViewById(R.id.rowTabSelectCheckbox)
            val tabRowThumbnail: ImageView? = view.findViewById(R.id.tabRowThumbnail)
            val cardTabRowThumbnail: androidx.cardview.widget.CardView? = view.findViewById(R.id.cardTabRowThumbnail)

            // Grid views (viewType == 1)
            val cardTabGrid: MaterialCardView? = view.findViewById(R.id.cardTabGrid)
            val gridTabIcon: ImageView? = view.findViewById(R.id.gridTabIcon)
            val gridTabTitle: TextView? = view.findViewById(R.id.gridTabTitle)
            val gridTabPinIcon: ImageView? = view.findViewById(R.id.gridTabPinIcon)
            val gridTabClose: ImageButton? = view.findViewById(R.id.gridTabClose)
            val gridTabSelectCheckbox: ImageView? = view.findViewById(R.id.gridTabSelectCheckbox)
            val gridTabThumbnail: ImageView? = view.findViewById(R.id.gridTabThumbnail)
            val gridTabUrl: TextView? = view.findViewById(R.id.gridTabUrl)
            val gridGroupIndicator: View? = view.findViewById(R.id.gridGroupIndicator)

            // Group Card views (viewType == 2, Image 1)
            val cardGroupContainer: MaterialCardView? = view.findViewById(R.id.cardGroupContainer)
            val tvGroupTitle: TextView? = view.findViewById(R.id.tvGroupTitle)
            val btnGroupMenu: ImageButton? = view.findViewById(R.id.btnGroupMenu)
            val vGroupColorOuterRing: View? = view.findViewById(R.id.vGroupColorOuterRing)
            val vGroupColorInnerDot: View? = view.findViewById(R.id.vGroupColorInnerDot)

            val miniCard0: androidx.cardview.widget.CardView? = view.findViewById(R.id.miniCard0)
            val miniThumb0: ImageView? = view.findViewById(R.id.miniThumb0)
            val miniFavicon0: ImageView? = view.findViewById(R.id.miniFavicon0)
            val placeholder0: View? = view.findViewById(R.id.placeholder0)

            val miniCard1: androidx.cardview.widget.CardView? = view.findViewById(R.id.miniCard1)
            val miniThumb1: ImageView? = view.findViewById(R.id.miniThumb1)
            val miniFavicon1: ImageView? = view.findViewById(R.id.miniFavicon1)
            val placeholder1: View? = view.findViewById(R.id.placeholder1)

            val miniCard2: androidx.cardview.widget.CardView? = view.findViewById(R.id.miniCard2)
            val miniThumb2: ImageView? = view.findViewById(R.id.miniThumb2)
            val miniFavicon2: ImageView? = view.findViewById(R.id.miniFavicon2)
            val placeholder2: View? = view.findViewById(R.id.placeholder2)

            val miniCard3: androidx.cardview.widget.CardView? = view.findViewById(R.id.miniCard3)
            val miniThumb3: ImageView? = view.findViewById(R.id.miniThumb3)
            val miniFavicon3: ImageView? = view.findViewById(R.id.miniFavicon3)
            val placeholder3: View? = view.findViewById(R.id.placeholder3)
        }

        override fun getItemViewType(position: Int): Int {
            if (tabMode == HazeTabSystemManager.TAB_MODE_LIST) return 0
            val item = displayedItems.getOrNull(position) ?: return 1
            return when (item) {
                is TabSwitcherItem.Single -> 1
                is TabSwitcherItem.Group -> 2
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
            val layoutRes = when (viewType) {
                2 -> R.layout.item_tab_group_card
                1 -> R.layout.item_tab_grid
                else -> R.layout.item_tab_row
            }
            val view = layoutInflater.inflate(layoutRes, parent, false)
            return TabViewHolder(view, viewType)
        }

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            val items = displayedItems
            if (position >= items.size) return
            val currentItem = items[position]
            val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
            val isDark = prefs.getBoolean("is_dark", false) || isPrivateList

            if (holder.viewType == 2) {
                // Tab Group Card in Cards/Tiles view (Image 1)
                val groupItem = currentItem as? TabSwitcherItem.Group ?: return
                val cardHeight = if (tabMode == HazeTabSystemManager.TAB_MODE_CARDS) dpToPx(300f) else dpToPx(216f)
                holder.cardGroupContainer?.layoutParams?.height = cardHeight
                holder.cardGroupContainer?.setCardBackgroundColor(groupItem.group.color)

                holder.tvGroupTitle?.text = groupItem.group.name

                // Header group dot indicator
                holder.vGroupColorInnerDot?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                holder.vGroupColorOuterRing?.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dpToPx(1.5f), Color.WHITE)
                }

                // 3-dots overflow menu
                holder.btnGroupMenu?.setOnClickListener { v ->
                    triggerStrongHaptic(v)
                    val popup = androidx.appcompat.widget.PopupMenu(this@MainActivity, v)
                    popup.menu.add("New tab in group")
                    popup.menu.add("Edit group name and color")
                    popup.menu.add("Ungroup tabs")
                    popup.menu.add("Close group")
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.title) {
                            "New tab in group" -> {
                                val newTab = addNewTab(HAZE_HOME_URL)
                                newTab.group = groupItem.group
                                notifyDataSetChanged()
                                true
                            }
                            "Edit group name and color" -> {
                                showEditGroupDialog(groupItem.group)
                                true
                            }
                            "Ungroup tabs" -> {
                                for (t in groupItem.tabs) {
                                    t.group = null
                                }
                                notifyDataSetChanged()
                                true
                            }
                            "Close group" -> {
                                for (t in groupItem.tabs.toList()) {
                                    onCloseClick(t)
                                }
                                notifyDataSetChanged()
                                true
                            }
                            else -> false
                        }
                    }
                    popup.show()
                }

                // Populate 2x2 Mini-Grid Slots (Matching Image 1)
                val memberTabs = groupItem.tabs
                val cards = listOf(holder.miniCard0, holder.miniCard1, holder.miniCard2, holder.miniCard3)
                val thumbs = listOf(holder.miniThumb0, holder.miniThumb1, holder.miniThumb2, holder.miniThumb3)
                val favs = listOf(holder.miniFavicon0, holder.miniFavicon1, holder.miniFavicon2, holder.miniFavicon3)
                val placeholders = listOf(holder.placeholder0, holder.placeholder1, holder.placeholder2, holder.placeholder3)

                for (i in 0 until 4) {
                    val c = cards[i]
                    val t = thumbs[i]
                    val f = favs[i]
                    val p = placeholders[i]

                    if (i < memberTabs.size) {
                        val tab = memberTabs[i]
                        c?.visibility = View.VISIBLE
                        p?.visibility = View.GONE

                        tab.browserTab.captureThumbnail { bmp: Bitmap? ->
                            if (bmp != null) t?.setImageBitmap(bmp)
                            else t?.setImageResource(R.drawable.haze_logo)
                        }

                        if (isHomeTab(tab)) {
                            f?.setImageResource(R.drawable.ic_haze_logo)
                        } else if (tab.favicon != null) {
                            f?.setImageBitmap(tab.favicon)
                        } else {
                            f?.setImageResource(R.drawable.ic_globe)
                        }
                    } else {
                        c?.visibility = View.GONE
                        p?.visibility = View.VISIBLE
                        val darkerShade = ColorUtils.blendARGB(groupItem.group.color, Color.BLACK, 0.18f)
                        p?.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = dpToPx(14f).toFloat()
                            setColor(darkerShade)
                        }
                    }
                }

                // Clicking Group Card expands the group in full detail dialog
                val openGroupAction = View.OnClickListener {
                    triggerStrongHaptic(it)
                    HazeTabGroupDialogManager.showTabGroupDetailDialog(
                        activity = this@MainActivity,
                        group = groupItem.group,
                        groupTabs = groupItem.tabs,
                        isPrivate = isPrivateList,
                        onTabSelected = { selectedTab ->
                            onItemClick(selectedTab)
                        },
                        onTabClosed = { closedTab ->
                            onCloseClick(closedTab)
                        },
                        onNewTabInGroup = {
                            val newTab = addNewTab(HAZE_HOME_URL)
                            newTab.group = groupItem.group
                            notifyDataSetChanged()
                        },
                        onGroupModified = {
                            notifyDataSetChanged()
                        }
                    )
                }

                holder.cardGroupContainer?.setOnClickListener(openGroupAction)
                holder.view.findViewById<View>(R.id.groupCardHeader)?.setOnClickListener(openGroupAction)
                holder.view.findViewById<View>(R.id.gridMiniThumbnails)?.setOnClickListener(openGroupAction)

            } else if (holder.viewType == 1) {
                // Single Tab Card in Cards/Tiles view (Image 3)
                val tab = (currentItem as? TabSwitcherItem.Single)?.tab ?: return
                val isActive = (isPrivateMode == isPrivateList && originalList.indexOf(tab) == activeTabIndex)

                val cardHeight = if (tabMode == HazeTabSystemManager.TAB_MODE_CARDS) dpToPx(300f) else dpToPx(216f)
                holder.cardTabGrid?.layoutParams?.height = cardHeight

                holder.gridTabTitle?.text = tab.title
                holder.gridTabUrl?.text = if (isHomeTab(tab)) "Home" else tab.url
                holder.gridTabPinIcon?.visibility = if (tab.isPinned) View.VISIBLE else View.GONE
                holder.gridGroupIndicator?.visibility = View.GONE

                tab.browserTab.captureThumbnail { bmp: Bitmap? ->
                    if (bmp != null) holder.gridTabThumbnail?.setImageBitmap(bmp)
                    else holder.gridTabThumbnail?.setImageResource(R.drawable.haze_logo)
                }

                val mCard = holder.cardTabGrid
                val headerLayout = holder.view.findViewById<LinearLayout>(R.id.gridHeaderLayout)
                val iconContainer = holder.view.findViewById<View>(R.id.gridTabIconContainer)

                if (isActive) {
                    // Active Chrome Card Styling (Image 3 Right: Blue Header + 3.5dp Blue Border)
                    mCard?.strokeColor = accentColor
                    mCard?.strokeWidth = dpToPx(3.5f)
                    mCard?.cardElevation = dpToPx(4f).toFloat()
                    mCard?.setCardBackgroundColor(if (isDark) Color.parseColor("#1E2024") else Color.WHITE)

                    headerLayout?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(
                            dpToPx(20f).toFloat(), dpToPx(20f).toFloat(),
                            dpToPx(20f).toFloat(), dpToPx(20f).toFloat(),
                            0f, 0f, 0f, 0f
                        )
                        setColor(accentColor)
                    }

                    iconContainer?.setBackgroundResource(R.drawable.bg_circle_icon_container)
                    holder.gridTabTitle?.setTextColor(Color.WHITE)
                    holder.gridTabClose?.setColorFilter(Color.WHITE)
                    holder.gridTabPinIcon?.setColorFilter(Color.WHITE)
                    if (isHomeTab(tab)) {
                        holder.gridTabIcon?.setImageResource(R.drawable.ic_haze_logo)
                        holder.gridTabIcon?.setColorFilter(Color.WHITE)
                    } else if (tab.favicon != null) {
                        holder.gridTabIcon?.setImageBitmap(tab.favicon)
                        holder.gridTabIcon?.clearColorFilter()
                    } else {
                        holder.gridTabIcon?.setImageResource(R.drawable.ic_globe)
                        holder.gridTabIcon?.setColorFilter(Color.WHITE)
                    }
                } else {
                    // Inactive Chrome Card Styling (Image 3 Left: Grey Header + 1.5dp Subtle Border)
                    val inactiveCardBg = if (isDark) Color.parseColor("#1E2024") else Color.WHITE
                    val inactiveHeaderBg = if (isDark) Color.parseColor("#2A2C30") else Color.parseColor("#E8EAED")
                    val inactiveStroke = if (isDark) Color.parseColor("#383A3E") else Color.parseColor("#E0E5EC")
                    val inactiveTitleColor = if (isDark) Color.WHITE else Color.parseColor("#1F1F1F")
                    val inactiveCloseColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

                    mCard?.strokeColor = inactiveStroke
                    mCard?.strokeWidth = dpToPx(1.5f)
                    mCard?.cardElevation = dpToPx(1f).toFloat()
                    mCard?.setCardBackgroundColor(inactiveCardBg)

                    headerLayout?.background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = floatArrayOf(
                            dpToPx(20f).toFloat(), dpToPx(20f).toFloat(),
                            dpToPx(20f).toFloat(), dpToPx(20f).toFloat(),
                            0f, 0f, 0f, 0f
                        )
                        setColor(inactiveHeaderBg)
                    }

                    iconContainer?.setBackgroundResource(R.drawable.bg_circle_icon_container)
                    holder.gridTabTitle?.setTextColor(inactiveTitleColor)
                    holder.gridTabClose?.setColorFilter(inactiveCloseColor)
                    holder.gridTabPinIcon?.setColorFilter(inactiveCloseColor)
                    if (isHomeTab(tab)) {
                        holder.gridTabIcon?.setImageResource(R.drawable.ic_haze_logo)
                        holder.gridTabIcon?.setColorFilter(accentColor)
                    } else if (tab.favicon != null) {
                        holder.gridTabIcon?.setImageBitmap(tab.favicon)
                        holder.gridTabIcon?.clearColorFilter()
                    } else {
                        holder.gridTabIcon?.setImageResource(R.drawable.ic_globe)
                        holder.gridTabIcon?.setColorFilter(inactiveCloseColor)
                    }
                }

                // Multi-select Checkbox vs Close Button in Grid/Cards
                if (isSelectionMode) {
                    holder.gridTabClose?.visibility = View.GONE
                    holder.gridTabSelectCheckbox?.visibility = View.VISIBLE
                    if (selectedTabIds.contains(tab.id)) {
                        holder.gridTabSelectCheckbox?.setImageResource(R.drawable.ic_check)
                        holder.gridTabSelectCheckbox?.setColorFilter(Color.WHITE)
                        holder.gridTabSelectCheckbox?.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(accentColor)
                        }
                    } else {
                        holder.gridTabSelectCheckbox?.setImageDrawable(null)
                        holder.gridTabSelectCheckbox?.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dpToPx(2f), Color.parseColor("#9AA0A6"))
                        }
                    }
                } else {
                    holder.gridTabClose?.visibility = View.VISIBLE
                    holder.gridTabSelectCheckbox?.visibility = View.GONE
                }

                val tabLongClickListener = View.OnLongClickListener { v ->
                    val enabled = prefs.getBoolean(HazeTabSystemManager.PREF_GESTURE_LONG_PRESS_TAB_OPTIONS, true)
                    if (enabled && !isSelectionMode) {
                        triggerStrongHaptic(v, isHeavy = true)
                        showTabOptionsMenu(tab)
                        true
                    } else false
                }
                holder.itemView.isLongClickable = true
                holder.itemView.setOnLongClickListener(tabLongClickListener)
                holder.cardTabGrid?.isLongClickable = true
                holder.cardTabGrid?.setOnLongClickListener(tabLongClickListener)

                val tabClickListener = View.OnClickListener {
                    triggerStrongHaptic(it)
                    if (isSelectionMode) {
                        if (selectedTabIds.contains(tab.id)) {
                            selectedTabIds.remove(tab.id)
                        } else {
                            selectedTabIds.add(tab.id)
                        }
                        notifyItemChanged(position)
                        onSelectionChanged?.invoke(selectedTabIds.size)
                    } else {
                        onItemClick(tab)
                    }
                }
                holder.cardTabGrid?.setOnClickListener(tabClickListener)
                holder.itemView.setOnClickListener(tabClickListener)
                holder.gridTabThumbnail?.setOnClickListener(tabClickListener)
                holder.view.findViewById<View>(R.id.gridHeaderLayout)?.setOnClickListener(tabClickListener)

                holder.gridTabClose?.setOnClickListener {
                    triggerStrongHaptic(it)
                    onCloseClick(tab)
                }

            } else {
                // List View (viewType == 0)
                val tab = (currentItem as? TabSwitcherItem.Single)?.tab ?: return
                val isActive = (isPrivateMode == isPrivateList && originalList.indexOf(tab) == activeTabIndex)

                val prevTab = if (position > 0) (items[position - 1] as? TabSwitcherItem.Single)?.tab else null
                val isFirstInGroup = tab.group != null && (prevTab == null || prevTab.group?.id != tab.group?.id)
                if (tab.group != null && isFirstInGroup) {
                    holder.groupHeader?.visibility = View.VISIBLE
                    holder.groupColorBox?.setBackgroundColor(tab.group!!.color)
                    holder.groupNameText?.text = tab.group!!.name
                    val count = originalList.count { it.group?.id == tab.group!!.id }
                    holder.groupCountText?.text = "($count tab${if (count > 1) "s" else ""})"
                    holder.groupChevron?.setImageResource(if (tab.group!!.isCollapsed) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)

                    if (tab.group!!.isCollapsed) {
                        val groupTabs = originalList.filter { it.group?.id == tab.group!!.id }
                        val summary = groupTabs.take(3).joinToString(", ") { it.title.take(12) } + if (groupTabs.size > 3) "..." else ""
                        holder.groupSummaryText?.text = summary
                        holder.groupSummaryText?.visibility = View.VISIBLE
                    } else {
                        holder.groupSummaryText?.visibility = View.GONE
                    }

                    holder.groupHeader?.setOnClickListener {
                        triggerStrongHaptic(it)
                        tab.group!!.isCollapsed = !tab.group!!.isCollapsed
                        notifyDataSetChanged()
                    }
                    holder.btnGroupMore?.setOnClickListener {
                        triggerStrongHaptic(it)
                        showEditGroupDialog(tab.group!!)
                    }
                } else {
                    holder.groupHeader?.visibility = View.GONE
                }

                if (tab.group != null && tab.group!!.isCollapsed) {
                    holder.rowContainer?.visibility = View.GONE
                } else {
                    holder.rowContainer?.visibility = View.VISIBLE
                }

                if (tab.group != null) {
                    holder.groupSideIndicator?.visibility = View.VISIBLE
                    holder.groupSideIndicator?.setBackgroundColor(tab.group!!.color)
                } else {
                    holder.groupSideIndicator?.visibility = View.GONE
                }

                holder.tabPinIcon?.visibility = if (tab.isPinned) View.VISIBLE else View.GONE

                if (isActive) {
                    val alphaColor = Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                    holder.rowContainer?.setBackgroundColor(alphaColor)
                } else {
                    holder.rowContainer?.setBackgroundColor(Color.TRANSPARENT)
                }

                if (isHomeTab(tab)) {
                    holder.tabIcon?.setImageResource(R.drawable.ic_haze_logo)
                } else if (tab.favicon != null) {
                    holder.tabIcon?.setImageBitmap(tab.favicon)
                } else {
                    holder.tabIcon?.setImageResource(R.drawable.ic_globe)
                }

                holder.tabTitle?.text = tab.title
                holder.tabUrl?.text = if (isHomeTab(tab)) "Home" else tab.url

                holder.cardTabRowThumbnail?.visibility = View.VISIBLE
                tab.browserTab.captureThumbnail { bmp: Bitmap? ->
                    if (bmp != null) holder.tabRowThumbnail?.setImageBitmap(bmp)
                    else holder.tabRowThumbnail?.setImageResource(R.drawable.haze_logo)
                }

                if (isSelectionMode) {
                    holder.tabClose?.visibility = View.GONE
                    holder.rowTabSelectCheckbox?.visibility = View.VISIBLE
                    if (selectedTabIds.contains(tab.id)) {
                        holder.rowTabSelectCheckbox?.setImageResource(R.drawable.ic_check)
                        holder.rowTabSelectCheckbox?.setColorFilter(Color.WHITE)
                        holder.rowTabSelectCheckbox?.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(accentColor)
                        }
                    } else {
                        holder.rowTabSelectCheckbox?.setImageDrawable(null)
                        holder.rowTabSelectCheckbox?.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setStroke(dpToPx(2f), Color.parseColor("#9AA0A6"))
                        }
                    }
                } else {
                    holder.tabClose?.visibility = View.VISIBLE
                    holder.rowTabSelectCheckbox?.visibility = View.GONE
                }

                val listClickListener = View.OnClickListener {
                    triggerStrongHaptic(it)
                    if (isSelectionMode) {
                        if (selectedTabIds.contains(tab.id)) {
                            selectedTabIds.remove(tab.id)
                        } else {
                            selectedTabIds.add(tab.id)
                        }
                        notifyItemChanged(position)
                        onSelectionChanged?.invoke(selectedTabIds.size)
                    } else {
                        onItemClick(tab)
                    }
                }
                holder.rowContainer?.setOnClickListener(listClickListener)
                holder.itemView.setOnClickListener(listClickListener)

                holder.tabClose?.setOnClickListener {
                    triggerStrongHaptic(it)
                    onCloseClick(tab)
                }
            }
        }

        override fun getItemCount(): Int = displayedItems.size

        private fun showEditGroupDialog(group: TabGroup) {
            HazeTabGroupDialogManager.showNewTabGroupDialog(
                context = this@MainActivity,
                initialTitle = group.name,
                initialColor = group.color,
                isEditing = true
            ) { newTitle, newColor ->
                group.name = newTitle
                group.color = newColor
                notifyDataSetChanged()
            }
        }

        private fun showTabOptionsMenu(tab: Tab) {
            val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
            val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
            val bgColor = if (isDark) Color.parseColor("#202124") else Color.WHITE
            val textColor = if (isDark) Color.WHITE else Color.parseColor("#1F1F1F")
            val iconColor = if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#444746")
            val borderColor = if (isDark) Color.parseColor("#33353A") else Color.parseColor("#E0E3E7")

            val dialog = Dialog(this@MainActivity)
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.30f)

            val root = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(32f).toFloat()
                    setColor(bgColor)
                    setStroke(dpToPx(1f), borderColor)
                }
                setPadding(dpToPx(8), dpToPx(14), dpToPx(8), dpToPx(14))
                layoutParams = ViewGroup.LayoutParams(dpToPx(275), ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            data class TabActionItem(val title: String, val iconRes: Int, val isDestructive: Boolean = false, val action: () -> Unit)
            val actionItems = mutableListOf<TabActionItem>()

            // Exact 6 items from Chrome (Image 3)
            // 1. Add tab to new group
            if (tab.group != null) {
                actionItems.add(TabActionItem("Remove from '${tab.group!!.name}'", R.drawable.ic_grid) {
                    tab.group = null
                    notifyDataSetChanged()
                    Toast.makeText(this@MainActivity, "Removed from group", Toast.LENGTH_SHORT).show()
                })
            } else {
                actionItems.add(TabActionItem("Add tab to new group", R.drawable.ic_grid) {
                    val input = EditText(this@MainActivity).apply { hint = "Group Name (e.g. Work, Shopping)" }
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("New Tab Group")
                        .setView(input)
                        .setPositiveButton("Create") { _, _ ->
                            val name = input.text.toString().trim().ifEmpty { "Group" }
                            val newGroup = TabGroup(
                                id = System.currentTimeMillis().toString(),
                                name = name,
                                color = groupColors.random()
                            )
                            tab.group = newGroup
                            notifyDataSetChanged()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                })
            }

            // 2. Add to bookmarks
            actionItems.add(TabActionItem("Add to bookmarks", R.drawable.ic_star) {
                HazeBookmarksManager.showBookmarkThisPageDialog(this@MainActivity, tab.title, tab.url)
            })

            // 3. Pin tab
            actionItems.add(TabActionItem(if (tab.isPinned) "Unpin tab" else "Pin tab", R.drawable.ic_pin) {
                tab.isPinned = !tab.isPinned
                notifyDataSetChanged()
                Toast.makeText(this@MainActivity, if (tab.isPinned) "Tab pinned" else "Tab unpinned", Toast.LENGTH_SHORT).show()
            })

            // 4. Mute site
            val isMuted = prefs.getBoolean("site_muted_${tab.url.hashCode()}", false)
            actionItems.add(TabActionItem(if (isMuted) "Unmute site" else "Mute site", if (isMuted) R.drawable.ic_volume_up else R.drawable.ic_volume_off) {
                prefs.edit().putBoolean("site_muted_${tab.url.hashCode()}", !isMuted).apply()
                tab.browserTab.evaluateJavascript("document.querySelectorAll('video, audio').forEach(el => el.muted = ${!isMuted});", null)
                Toast.makeText(this@MainActivity, if (!isMuted) "Site audio muted" else "Site audio unmuted", Toast.LENGTH_SHORT).show()
            })

            // 5. Select tab (switches directly to tab or focuses it)
            actionItems.add(TabActionItem("Select tab", R.drawable.ic_edit) {
                onItemClick(tab)
            })

            // 6. Close tab
            actionItems.add(TabActionItem("Close tab", R.drawable.ic_close, isDestructive = false) {
                onCloseClick(tab)
            })

            for (item in actionItems) {
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(48f))
                    val rippleColor = if (isDark) Color.parseColor("#26FFFFFF") else Color.parseColor("#14000000")
                    background = android.graphics.drawable.RippleDrawable(
                        ColorStateList.valueOf(rippleColor),
                        GradientDrawable().apply {
                            cornerRadius = dpToPx(20f).toFloat()
                            setColor(Color.TRANSPARENT)
                        },
                        GradientDrawable().apply {
                            cornerRadius = dpToPx(20f).toFloat()
                            setColor(Color.WHITE)
                        }
                    )
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        triggerStrongHaptic(it)
                        dialog.dismiss()
                        item.action()
                    }
                }

                val iv = ImageView(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(22f), dpToPx(22f)).apply {
                        marginStart = dpToPx(18f)
                        marginEnd = dpToPx(16f)
                    }
                    setImageResource(item.iconRes)
                    setColorFilter(if (item.isDestructive) Color.parseColor("#E53935") else iconColor)
                }

                val tv = TextView(this@MainActivity).apply {
                    text = item.title
                    textSize = 15f
                    setTextColor(if (item.isDestructive) Color.parseColor("#E53935") else textColor)
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                row.addView(iv)
                row.addView(tv)
                root.addView(row)
            }

            dialog.setContentView(root)
            dialog.show()
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

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(28).toFloat()
                setColor(surfaceColor)
            }
            clipToOutline = true
        }

        val scrollContent = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        cardLayout.addView(scrollContent)
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(12))
        }
        scrollContent.addView(content)
        
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
            setPadding(0, 0, 0, dpToPx(18))
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
            setPadding(0, 0, 0, dpToPx(14)) 
        }
        val rbLight = RadioButton(this).apply {
            text = "Light"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dpToPx(4), 0, dpToPx(16), 0)
        }
        val rbDark = RadioButton(this).apply {
            text = "Dark"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dpToPx(4), 0, dpToPx(16), 0)
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
            setPadding(0, 0, 0, dpToPx(14)) 
        }
        val rbOff = RadioButton(this).apply {
            text = "Off"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dpToPx(4), 0, dpToPx(12), 0)
        }
        val rb1Row = RadioButton(this).apply {
            text = "1 Row"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dpToPx(4), 0, dpToPx(12), 0)
        }
        val rb2Row = RadioButton(this).apply {
            text = "2 Rows"
            setTextColor(textColor)
            buttonTintList = ColorStateList.valueOf(accent)
            setPadding(dpToPx(4), 0, dpToPx(12), 0)
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
            setPadding(0, 0, 0, dpToPx(14))
        }
        val colorLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorScroll.addView(colorLayout)
        
        val colors = listOf(
            "#0A56D0", // Default Blue
            "#EA4335", // Red
            "#34A853", // Green
            "#9C27B0", // Purple
            "#FF6D01", // Orange
            "#00897B", // Teal
            "#212121"  // Dark Storm Grey
        )

        var btnApply: TextView? = null
        
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
            val parsedColor = Color.parseColor(selectedColor)
            val csl = ColorStateList.valueOf(parsedColor)
            rbLight.buttonTintList = csl
            rbDark.buttonTintList = csl
            rbOff.buttonTintList = csl
            rb1Row.buttonTintList = csl
            rb2Row.buttonTintList = csl
            btnApply?.let { (it.background as? GradientDrawable)?.setColor(parsedColor) }
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
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.parseColor(c))
                }
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
            setPadding(0, 0, 0, dpToPx(14))
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
        addSectionCard(bgScroll, 12)

        lateinit var dialog: AlertDialog

        // Bottom Action Button Bar (Cleanly integrated INSIDE the card)
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dpToPx(20), dpToPx(8), dpToPx(20), dpToPx(20))
        }

        val btnCancel = TextView(this).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(secondaryTextColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(10))
            setBackgroundResource(getSelectableRes())
            isClickable = true
            isFocusable = true
            setOnClickListener {
                triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        buttonBar.addView(btnCancel)

        val spacer = Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(8), 0)
        }
        buttonBar.addView(spacer)

        btnApply = TextView(this).apply {
            text = "Apply"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(26), dpToPx(10), dpToPx(26), dpToPx(10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(20).toFloat()
                setColor(Color.parseColor(selectedColor))
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                triggerStrongHaptic(it, isHeavy = true)
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
                reloadHomeTabs()
                dialog.dismiss()
            }
        }
        buttonBar.addView(btnApply)
        cardLayout.addView(buttonBar)

        refreshColorBorders()
        
        dialog = AlertDialog.Builder(this)
            .setView(cardLayout)
            .create()
            
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
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
        popupWindow.animationStyle = R.style.HazePopupAnimation
        
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
        btnMenuBack?.setColorFilter(iconColor)
        btnMenuForward?.setColorFilter(iconColor)
        btnMenuReload?.setColorFilter(iconColor)
        btnMenuHome?.setColorFilter(iconColor)

        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        val currentUrl = currentTab?.url ?: HAZE_HOME_URL
        val db = HazeDatabase.getInstance(this)
        var isStarred = currentTab != null && !isHomeTab(currentTab) && !currentUrl.startsWith("haze://") && db.isBookmarked(currentUrl)

        fun updateStarIcon() {
            if (isStarred) {
                btnMenuStar?.setImageResource(R.drawable.ic_star_filled)
                btnMenuStar?.setColorFilter(Color.parseColor("#FF6D00"))
            } else {
                btnMenuStar?.setImageResource(R.drawable.ic_star_outline)
                btnMenuStar?.setColorFilter(iconColor)
            }
        }
        updateStarIcon()

        btnMenuBack?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            if (currentTab?.browserTab?.navigationController?.canGoBack() == true) {
                currentTab.browserTab.navigationController.goBack()
            }
        }
        btnMenuForward?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            if (currentTab?.browserTab?.navigationController?.canGoForward() == true) {
                currentTab.browserTab.navigationController.goForward()
            }
        }
        btnMenuStar?.setOnClickListener {
            triggerStrongHaptic(it)
            if (currentTab == null || isHomeTab(currentTab) || currentUrl.startsWith("haze://")) {
                Toast.makeText(this, "Home page cannot be favorited", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isStarred) {
                db.deleteBookmarkByUrl(currentUrl)
                isStarred = false
                updateStarIcon()
                Toast.makeText(this, "Removed from Favorites", Toast.LENGTH_SHORT).show()
            } else {
                val title = currentTab.title.ifBlank { currentUrl }
                db.addBookmark(title, currentUrl, 0L)
                isStarred = true
                updateStarIcon()
                Toast.makeText(this, "Added to Favorites!", Toast.LENGTH_SHORT).show()
            }
        }
        btnMenuStar?.setOnLongClickListener {
            triggerStrongHaptic(it, isHeavy = true)
            showStarBookmarkDialog(popupWindow)
            true
        }
        btnMenuReload?.setOnClickListener {
            triggerStrongHaptic(it)
            popupWindow.dismiss()
            currentTab?.browserTab?.reload()
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
        
        val activeTab = currentTabsList.getOrNull(activeTabIndex)
        val isDesktopActive = activeTab?.isDesktop == true || activeTab?.browserTab?.isDesktop == true

        val menuItems = mutableListOf(
            Pair("New Tab", R.drawable.ic_add),
            Pair("New InPrivate", R.drawable.ic_private)
        )
        if (isPrivateMode) {
            val pinTitle = if (HazeIncognitoAuthManager.hasPinConfigured(this)) "Lock Private" else "Set Private PIN"
            menuItems.add(Pair(pinTitle, R.drawable.ic_lock_shield))
        }
        menuItems.addAll(listOf(
            Pair("Privacy Shield", R.drawable.ic_privacy_shield),
            Pair("Personalize", R.drawable.ic_palette),
            Pair("Favorites", R.drawable.ic_star),
            Pair("History", R.drawable.ic_history),
            Pair("Downloads", R.drawable.ic_download),
            Pair("Full Screen", R.drawable.ic_fullscreen),
            Pair("Orientation", R.drawable.ic_screen_rotation),
            Pair("Accessibility", R.drawable.ic_accessibility),
            Pair(if (isDesktopActive) "Mobile Site" else "Desktop Site", R.drawable.ic_desktop),
            Pair("Find on Page", R.drawable.ic_search),
            Pair("Share", R.drawable.ic_share),
            Pair("Recent Tabs", R.drawable.ic_restore),
            Pair("Save Offline", R.drawable.ic_download),
            Pair("DevTools", R.drawable.ic_code),
            Pair("Extra", R.drawable.ic_extra),
            Pair("Settings", R.drawable.ic_settings)
        ))
        
        for (item in menuItems) {
            val itemView = layoutInflater.inflate(R.layout.item_menu_box, grid, false)
            val iconView = itemView.findViewById<ImageView>(R.id.menuIcon)
            val textView = itemView.findViewById<TextView>(R.id.menuText)
            iconView.setImageResource(item.second)
            iconView.contentDescription = item.first
            textView.text = item.first
            itemView.contentDescription = item.first
            if (item.first == "Mobile Site" || (item.first == "Desktop Site" && isDesktopActive)) {
                val activeTint = Color.parseColor("#4A90E2")
                iconView.setColorFilter(activeTint)
                textView.setTextColor(activeTint)
            } else if (isDark) {
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
                        if (HazeIncognitoAuthManager.isProtectionEnabled(this) && !HazeIncognitoAuthManager.isIncognitoSessionUnlocked) {
                            HazeIncognitoAuthManager.requestUnlock(this, onSuccess = {
                                isPrivateMode = true
                                addNewTab(HAZE_HOME_URL)
                                applyTheme()
                                Toast.makeText(this, "Private mode enabled", Toast.LENGTH_SHORT).show()
                            }, onFailure = {
                                Toast.makeText(this, "Authentication required for InPrivate", Toast.LENGTH_SHORT).show()
                            })
                        } else {
                            isPrivateMode = true
                            addNewTab(HAZE_HOME_URL)
                            applyTheme()
                            Toast.makeText(this, "Private mode enabled", Toast.LENGTH_SHORT).show()
                        }
                    }
                    "Set Private PIN", "Lock Private" -> {
                        if (!HazeIncognitoAuthManager.hasPinConfigured(this)) {
                            HazeIncognitoAuthManager.showSetPinDialog(this) {}
                        } else {
                            AlertDialog.Builder(this)
                                .setTitle("Incognito Security")
                                .setItems(arrayOf("Lock Private Tabs Now", "Protection Settings")) { _, which ->
                                    if (which == 0) {
                                        HazeIncognitoAuthManager.lockNow(this)
                                        isPrivateMode = false
                                        if (normalTabs.isNotEmpty()) switchTab(activeNormalTabIndex.coerceIn(0, normalTabs.size - 1))
                                        Toast.makeText(this, "Private tabs locked", Toast.LENGTH_SHORT).show()
                                    } else {
                                        HazeIncognitoAuthManager.showProtectionSettingsDialog(this) {}
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    "Privacy Shield" -> HazePrivacyShieldDashboard.showDashboard(this)
                    "Personalize" -> showPersonalizeDialog()
                    "Favorites" -> HazeBookmarksManager.showBookmarksDialog(this) { loadUrlFromInput(it) }
                    "History" -> HazeHistoryManager.showHistoryDialog(this) { loadUrlFromInput(it) }
                    "Downloads" -> HazeDownloadsManager.showDownloadsDialog(this)
                    "Full Screen" -> toggleFullScreenMode()
                    "Orientation" -> toggleScreenOrientation()
                    "Accessibility" -> showAccessibilityDialog()
                    "Desktop Site", "Mobile Site" -> currentTabsList.getOrNull(activeTabIndex)?.let { toggleDesktopSite(it) }
                    "Find on Page" -> showFindBar()
                    "Share" -> shareCurrentPage()
                    "Recent Tabs" -> showRecentlyClosedTabsDialog()
                    "Save Offline" -> saveCurrentPageForOffline()
                    "DevTools", "Inspect" -> currentTabsList.getOrNull(activeTabIndex)?.let {
                        if (HazeDevToolsManager.isDevToolsVisible(this)) {
                            HazeDevToolsManager.hideDevTools(this)
                        } else {
                            HazeDevToolsManager.showDevTools(this, it)
                        }
                    }
                    "Extra" -> showExtraToolsDialog()
                    "Settings" -> HazeSettingsManager.showSettingsDialog(this)
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
        val textColor = if (isPrivateMode || isDark) Color.WHITE else Color.parseColor("#202124")
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
                val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.setPadding(navBars.left, statusBars.top, navBars.right, navBars.bottom)
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
                        var length = (candidate.innerText || candidate.textContent || '').replace(/\s+/g, ' ').trim().length;
                        if (length > bestLength) {
                            bestLength = length;
                            article = candidate;
                        }
                    }
                }
                if (!article) article = document.body;
                var copy = article.cloneNode(true);

                // Systematically expand all details, dropdowns, accordions, and collapsed sections
                var detailsEls = copy.querySelectorAll('details');
                for (var d = 0; d < detailsEls.length; d++) {
                    detailsEls[d].open = true;
                    detailsEls[d].setAttribute('open', 'true');
                }

                var collapsedEls = copy.querySelectorAll('[aria-expanded="false"], [aria-hidden="true"], .collapse, .collapsed, .hidden, [hidden], .mw-collapsed');
                for (var c = 0; c < collapsedEls.length; c++) {
                    var el = collapsedEls[c];
                    el.removeAttribute('hidden');
                    el.removeAttribute('aria-hidden');
                    el.setAttribute('aria-expanded', 'true');
                    el.classList.remove('collapse', 'collapsed', 'hidden', 'mw-collapsed');
                    el.classList.add('show', 'in', 'expanded');
                    el.style.display = 'block';
                    el.style.visibility = 'visible';
                    el.style.maxHeight = 'none';
                    el.style.height = 'auto';
                    el.style.opacity = '1';
                }

                var unwanted = copy.querySelectorAll('script, style, noscript, iframe, video, audio, canvas, nav, footer, form, aside, .ad, .ads, .advertisement');
                for (var k = 0; k < unwanted.length; k++) unwanted[k].remove();

                var plain = (copy.innerText || copy.textContent || '').replace(/\s+/g, ' ').trim();
                var wordCount = plain.split(/\s+/).filter(Boolean).length;
                var readTimeMinutes = Math.max(1, Math.ceil(wordCount / 200));
                var title = document.title;

                var author = '';
                var authorMeta = document.querySelector('meta[name="author"], meta[property="article:author"], meta[name="twitter:creator"]');
                if (authorMeta) author = authorMeta.content || '';
                if (!author) {
                    var authorEl = document.querySelector('.author, .byline, [rel="author"], .by-line');
                    if (authorEl) author = authorEl.innerText.trim();
                }

                var paragraphs = copy.querySelectorAll('p').length;
                var readable = plain.length >= 300 && (paragraphs >= 1 || plain.length >= 600);

                return JSON.stringify({
                    title: title,
                    author: author,
                    readTime: readTimeMinutes + ' min read',
                    html: copy.innerHTML,
                    plainText: plain,
                    readable: readable,
                    characters: plain.length
                });
            })();
        """.trimIndent()
        
        tab.browserTab.evaluateJavascript(readerScript) { result ->
            if (result == null) return@evaluateJavascript
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
                val author = json.optString("author", "")
                val readTime = json.optString("readTime", "2 min read")
                val contentHtml = json.optString("html", "")
                val plainText = json.optString("plainText", "")
                
                showReaderModeDialog(title, author, readTime, contentHtml, plainText)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not format page for reading", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var isTtsSpeaking = false
    private var ttsSpeed = 1.0f

    private fun speakLongText(tts: TextToSpeech?, text: String, speed: Float) {
        if (tts == null) return
        tts.stop()
        tts.setSpeechRate(speed)

        val clean = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace(Regex("&#39;|&#x27;"), "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isEmpty()) return

        val maxChunk = 2500
        var start = 0
        var isFirst = true

        while (start < clean.length) {
            var end = (start + maxChunk).coerceAtMost(clean.length)
            if (end < clean.length) {
                val lastPeriod = clean.substring(start, end).lastIndexOfAny(charArrayOf('.', '!', '?', '\n'))
                if (lastPeriod > 400) {
                    end = start + lastPeriod + 1
                }
            }
            val chunk = clean.substring(start, end).trim()
            if (chunk.isNotEmpty()) {
                val queueMode = if (isFirst) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val utteranceId = "reader_tts_$start"
                tts.speak(chunk, queueMode, null, utteranceId)
                isFirst = false
            }
            start = end
        }
    }

    private fun showReaderModeDialog(title: String, author: String, readTime: String, contentHtml: String, plainText: String) {
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        // Start with a readable surface that follows the browser theme.
        var currentBg = if (isDark) "#121212" else "#FDFBF7"
        var currentText = if (isDark) "#F1F3F4" else "#202124"
        var currentFont = "'Outfit', sans-serif"
        var currentFontSize = "18px"
        var currentLineHeight = "1.85"
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
            text = "Reader Mode"
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
            val authorHtml = if (author.isNotEmpty()) "<span class=\"author\">By $author</span> • " else ""
            val readerHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        @import url('https://fonts.googleapis.com/css2?family=Merriweather:ital,wght@0,300;0,400;0,700;1,300&family=Outfit:wght@300;400;500;600&family=Georgia&family=Sitka+Text&display=swap');
                        body {
                            font-family: $currentFont;
                            line-height: $currentLineHeight;
                            font-size: $currentFontSize;
                            padding: 28px 20px 80px 20px;
                            margin: 0 auto;
                            max-width: $maxWidth;
                            color: $currentText;
                            background: $currentBg;
                        }
                        .article-badge {
                            display: inline-block;
                            font-size: 11px;
                            font-weight: 700;
                            letter-spacing: 1px;
                            text-transform: uppercase;
                            padding: 4px 8px;
                            border-radius: 6px;
                            background: rgba(26, 115, 232, 0.15);
                            color: $accentColor;
                            margin-bottom: 12px;
                        }
                        h1 { font-size: 28px; line-height: 1.3; margin-top: 0; margin-bottom: 12px; font-weight: 700; color: $currentText; }
                        .article-meta {
                            font-size: 13px;
                            opacity: 0.75;
                            margin-bottom: 20px;
                        }
                        .divider {
                            border: none;
                            height: 1px;
                            background: rgba(128, 128, 128, 0.25);
                            margin-bottom: 24px;
                        }
                        h2, h3 { color: $currentText; margin-top: 28px; }
                        img { max-width: 100%; height: auto; border-radius: 12px; margin: 20px 0; }
                        p { margin-bottom: 20px; }
                        a { color: $accentColor; text-decoration: none; }
                    </style>
                </head>
                <body>
                    <div class="article-badge">ARTICLE</div>
                    <h1>$title</h1>
                    <div class="article-meta">$authorHtml<span>$readTime</span></div>
                    <hr class="divider"/>
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
                val speechText = if (plainText.isNotBlank()) plainText else contentHtml.replace(Regex("<[^>]*>"), "")
                speakLongText(tts, speechText, ttsSpeed)
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
            val names = HazeTranslationManager.SUPPORTED_LANGUAGES.map { it.first }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Translate Article To")
                .setItems(names) { _, which ->
                    val chosen = HazeTranslationManager.SUPPORTED_LANGUAGES[which]
                    val currentTab = currentTabsList.getOrNull(activeTabIndex)
                    if (currentTab != null) {
                        dialog.dismiss()
                        HazeTranslationManager.translateTab(this, currentTab, chosen.second, chosen.third)
                    }
                }
                .setNegativeButton("Cancel", null)
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

            // Line Spacing
            val tvLineSpacingLbl = TextView(this).apply {
                text = "Line Spacing"
                setTypeface(null, Typeface.BOLD)
                setTextColor(appearanceText)
                setPadding(0, dpToPx(16), 0, 0)
            }
            val lineSpacingSeek = SeekBar(this).apply {
                max = 4
                progress = 2 // default 1.85
                progressTintList = ColorStateList.valueOf(accentColor)
                thumbTintList = ColorStateList.valueOf(accentColor)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        currentLineHeight = when (progress) {
                            0 -> "1.3"
                            1 -> "1.5"
                            2 -> "1.85"
                            3 -> "2.1"
                            else -> "2.4"
                        }
                        reloadReaderContent()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            popView.addView(tvLineSpacingLbl)
            popView.addView(lineSpacingSeek)

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
                Pair("Cream", Pair("#FDFBF7", "#202124")),
                Pair("Sepia", Pair("#FFF3E0", "#5D1A00")),
                Pair("White", Pair("#FFFFFF", "#202124")),
                Pair("Dark", Pair("#121212", "#E0E0E0")),
                Pair("OLED", Pair("#000000", "#FFFFFF")),
                Pair("Mint", Pair("#E6F4EA", "#137333")),
                Pair("Blue", Pair("#E8F0FE", "#1967D2"))
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
        
        addTool("Print / Save as PDF", R.drawable.ic_download) {
            printCurrentPage()
        }
        addTool("Capture Screenshot", R.drawable.ic_image) {
            captureCurrentTabScreenshot()
        }
        addTool("Save for offline reading", R.drawable.ic_download) {
            saveCurrentPageForOffline()
        }
        addTool("Saved offline pages", R.drawable.ic_reading_mode) {
            showOfflinePagesDialog()
        }
        addTool("Duplicate tab", R.drawable.ic_add) {
            tab?.let { duplicateTab(it) }
        }
        addTool("Recently closed tabs", R.drawable.ic_restore) {
            showRecentlyClosedTabsDialog()
        }
        addTool("Restore previous session", R.drawable.ic_restore) {
            restorePreviousSession(promptUser = true)
        }
        addTool("Gestures & Navigation", R.drawable.ic_view_card) {
            HazeTabSystemManager.showGesturesSettingsDialog(this)
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

    fun showAccessibilityDialog() {
        val tab = currentTabsList.getOrNull(activeTabIndex)
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode

        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            setPadding(dpToPx(16), dpToPx(16), dpToPx(16), dpToPx(24))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(8), 0, dpToPx(8), dpToPx(16))
        }

        val titleTv = TextView(this).apply {
            text = "Accessibility"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(titleTv)

        val btnClose = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368"))
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            setBackgroundResource(getSelectableRes())
            setOnClickListener {
                triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        headerRow.addView(btnClose)
        root.addView(headerRow)

        fun addAccessibilityItem(label: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(56))
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
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
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                    marginEnd = dpToPx(16)
                }
            }
            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mainText = TextView(this).apply {
                text = label
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            }
            val subText = TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368"))
            }
            textContainer.addView(mainText)
            textContainer.addView(subText)
            row.addView(icon)
            row.addView(textContainer)
            root.addView(row)
        }

        addAccessibilityItem("Text Size", "Adjust article and font size scaling", R.drawable.ic_format_size) {
            showTextScalingDialog()
        }
        addAccessibilityItem("Page Zoom", "Scale complete webpage layout (50% - 250%)", R.drawable.ic_zoom_in) {
            showPageZoomDialog()
        }
        addAccessibilityItem("Translate Page", "Translate webpage into English with Google Translate", R.drawable.ic_globe) {
            translateCurrentPage()
        }
        addAccessibilityItem("Reading Mode", "Distraction-free article reader view", R.drawable.ic_reading_mode) {
            toggleReadingMode()
        }
        addAccessibilityItem("High Contrast / Invert", "High contrast mode for enhanced readability", R.drawable.ic_palette) {
            toggleHighContrastMode()
        }

        dialog.setContentView(root)
        dialog.show()
    }

    fun toggleHighContrastMode() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val script = """
            (function() {
                var id = '__haze_high_contrast';
                var existing = document.getElementById(id);
                if (existing) {
                    existing.remove();
                    return 'disabled';
                } else {
                    var style = document.createElement('style');
                    style.id = id;
                    style.innerHTML = 'html { filter: invert(1) hue-rotate(180deg) contrast(1.1) !important; background: #121212 !important; } img, video, canvas, svg { filter: invert(1) hue-rotate(180deg) !important; }';
                    document.documentElement.appendChild(style);
                    return 'enabled';
                }
            })();
        """.trimIndent()
        tab.browserTab.evaluateJavascript(script) { result ->
            val enabled = result?.contains("enabled") == true
            Toast.makeText(this, if (enabled) "High Contrast enabled" else "High Contrast disabled", Toast.LENGTH_SHORT).show()
        }
    }

    fun toggleDesktopSite(tab: Tab) {
        val targetDesktop = !(tab.isDesktop || tab.browserTab.isDesktop)
        tab.isDesktop = targetDesktop
        tab.browserTab.isDesktop = targetDesktop
        // The reload is handled internally by ChromiumBrowserTab's property setter
        Toast.makeText(this, if (targetDesktop) "Desktop site enabled" else "Mobile site enabled", Toast.LENGTH_SHORT).show()
    }

    fun toggleScreenOrientation() {
        val currentOrientation = resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Toast.makeText(this, "Switched to Landscape", Toast.LENGTH_SHORT).show()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Toast.makeText(this, "Switched to Portrait", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val tab = currentTabsList.getOrNull(activeTabIndex)
        val webView = tab?.browserTab?.renderView as? WebView
        webView?.requestLayout()
        val isDesk = tab?.isDesktop == true || tab?.browserTab?.isDesktop == true
        val isSpotify = (tab?.url ?: "").contains("spotify.com", ignoreCase = true)
        if (isDesk) {
            webView?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.DESKTOP_VIEWPORT_SCRIPT, null)
        } else if (isSpotify) {
            webView?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.SPOTIFY_ENGINE_SCRIPT, null)
        } else {
            webView?.evaluateJavascript(com.example.haze.engine.chromium.ChromiumBrowserTab.MOBILE_VIEWPORT_SCRIPT, null)
        }
    }

    // -------------------------------------------------------------------------
    // GESTURE CONTROLS (Swipe Address Bar, Swipe Down Overview, Edge Nav)
    // -------------------------------------------------------------------------
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    private var isAddressBarTouch = false
    private var isAddressBarSwiping = false
    private var isAddressBarPullDown = false
    private var isEdgeTouch = false
    private var isEdgeSwiping = false
    private var isLeftEdge = false
    private var isRightEdge = false
    private var childTouchesCanceled = false
    private var gestureVelocityTracker: VelocityTracker? = null
    private var activeGestureCurrView: View? = null
    private var activeGestureAdjacentView: View? = null
    private var activeGestureTargetIndex = -1

    fun setupSwipeBetweenTabs() {
        // Handled centrally in dispatchTouchEvent for 100% reliable interception across all Android views
    }

    fun setupWebEdgeSwipeGestures() {
        // Handled centrally in dispatchTouchEvent for 100% reliable interception across all Android views
    }

    private fun resetGestures() {
        isAddressBarTouch = false
        isAddressBarSwiping = false
        isAddressBarPullDown = false
        isEdgeTouch = false
        isEdgeSwiping = false
        isLeftEdge = false
        isRightEdge = false
        childTouchesCanceled = false
        activeGestureCurrView = null
        activeGestureAdjacentView = null
        activeGestureTargetIndex = -1
        try {
            gestureVelocityTracker?.recycle()
        } catch (_: Exception) {}
        gestureVelocityTracker = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isSearchActive || activeFullScreenTabsDialog?.isShowing == true) {
            return super.dispatchTouchEvent(ev)
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val edgeZone = dpToPx(36)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureStartX = ev.rawX
                gestureStartY = ev.rawY
                isAddressBarTouch = false
                isAddressBarSwiping = false
                isAddressBarPullDown = false
                isEdgeTouch = false
                isEdgeSwiping = false
                isLeftEdge = false
                isRightEdge = false
                childTouchesCanceled = false
                activeGestureCurrView = null
                activeGestureAdjacentView = null
                activeGestureTargetIndex = -1

                try {
                    gestureVelocityTracker?.recycle()
                } catch (_: Exception) {}
                gestureVelocityTracker = VelocityTracker.obtain()
                gestureVelocityTracker?.addMovement(ev)

                // Cancel in-flight animations to ensure instant response (<0.01s)
                for (i in 0 until webContainer.childCount) {
                    val child = webContainer.getChildAt(i)
                    if (child.translationX != 0f) {
                        child.animate().cancel()
                        child.translationX = 0f
                    }
                }
                if (!isSearchActive && ::addressBarContainer.isInitialized && addressBarContainer.translationY != 0f) {
                    addressBarContainer.animate().cancel()
                    addressBarContainer.translationY = 0f
                }
                if (::addressBar.isInitialized && addressBar.translationX != 0f) {
                    addressBar.animate().cancel()
                    addressBar.translationX = 0f
                }

                if (!isSearchActive) {
                    // Check if touch down is inside bottom address bar
                    if (::addressBarContainer.isInitialized && addressBarContainer.isShown) {
                        val loc = IntArray(2)
                        addressBarContainer.getLocationOnScreen(loc)
                        val left = loc[0]
                        val top = loc[1]
                        val right = left + addressBarContainer.width
                        val bottom = top + addressBarContainer.height
                        if (gestureStartX >= left && gestureStartX <= right && gestureStartY >= top && gestureStartY <= bottom) {
                            isAddressBarTouch = true
                            val currentTab = currentTabsList.getOrNull(activeTabIndex)
                            activeGestureCurrView = currentTab?.swipeRefreshLayout ?: currentTab?.browserTab?.renderView
                        }
                    }

                    // Check if touch down is within screen edge navigation zones
                    if (!isAddressBarTouch) {
                        val edgeEnabled = prefs.getBoolean(HazeTabSystemManager.PREF_GESTURE_EDGE_SWIPE_NAVIGATE, true)
                        if (edgeEnabled) {
                            if (gestureStartX <= edgeZone) {
                                isEdgeTouch = true
                                isLeftEdge = true
                            } else if (gestureStartX >= screenWidth - edgeZone) {
                                isEdgeTouch = true
                                isRightEdge = true
                            }
                        }
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val tracker = gestureVelocityTracker ?: VelocityTracker.obtain().also { gestureVelocityTracker = it }
                tracker.addMovement(ev)

                val dx = ev.rawX - gestureStartX
                val dy = ev.rawY - gestureStartY
                val absX = Math.abs(dx)
                val absY = Math.abs(dy)

                // 1. Address bar swipe tracking
                if (isAddressBarTouch && !isSearchActive) {
                    val swipeSwitchEnabled = prefs.getBoolean(HazeTabSystemManager.PREF_GESTURE_SWIPE_SWITCH_TAB, true)
                    val swipeDownEnabled = prefs.getBoolean(HazeTabSystemManager.PREF_GESTURE_SWIPE_DOWN_TAB_OVERVIEW, true)

                    // Ultra-low threshold of 6dp (~15px) triggers in <0.01s (first motion frame)
                    if (!isAddressBarSwiping && !isAddressBarPullDown) {
                        if (swipeSwitchEnabled && absX > dpToPx(6) && absX > absY) {
                            isAddressBarSwiping = true
                        } else if (swipeDownEnabled && dy > dpToPx(8) && dy > absX) {
                            isAddressBarPullDown = true
                        }

                        if (isAddressBarSwiping || isAddressBarPullDown) {
                            if (!childTouchesCanceled) {
                                val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                                super.dispatchTouchEvent(cancelEvent)
                                cancelEvent.recycle()
                                childTouchesCanceled = true
                            }
                        }
                    }

                    if (isAddressBarPullDown) {
                        val pullProgress = (dy.coerceAtLeast(0f) * 0.35f).coerceAtMost(dpToPx(60).toFloat())
                        addressBarContainer.translationY = pullProgress
                        webContainer.translationY = (dy.coerceAtLeast(0f) * 0.15f).coerceAtMost(dpToPx(30).toFloat())
                        return true
                    }

                    if (isAddressBarSwiping) {
                        val containerWidth = if (webContainer.width > 0) webContainer.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
                        val currView = activeGestureCurrView ?: (currentTabsList.getOrNull(activeTabIndex)?.let { it.swipeRefreshLayout ?: it.browserTab.renderView })
                        if (currView != null) {
                            activeGestureCurrView = currView
                            if (currView.layerType != View.LAYER_TYPE_HARDWARE) {
                                currView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                            }

                            if (dx > 0) {
                                // Moving Right -> Seeking Previous Tab
                                if (activeTabIndex > 0) {
                                    val prevTab = currentTabsList[activeTabIndex - 1]
                                    val prevView = prevTab.swipeRefreshLayout ?: prevTab.browserTab.renderView
                                    if (prevView != null) {
                                        if (activeGestureAdjacentView !== prevView) {
                                            activeGestureAdjacentView?.visibility = View.GONE
                                            activeGestureAdjacentView?.translationX = 0f
                                            activeGestureAdjacentView = prevView
                                            activeGestureTargetIndex = activeTabIndex - 1
                                        }
                                        if (prevView.visibility != View.VISIBLE) {
                                            prevView.visibility = View.VISIBLE
                                            if (prevView.parent == null) webContainer.addView(prevView)
                                            prevView.bringToFront()
                                            currView.bringToFront()
                                        }
                                        if (prevView.layerType != View.LAYER_TYPE_HARDWARE) {
                                            prevView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                        }
                                        currView.translationX = dx
                                        prevView.translationX = -containerWidth + dx
                                    }
                                } else {
                                    activeGestureAdjacentView?.visibility = View.GONE
                                    activeGestureAdjacentView = null
                                    activeGestureTargetIndex = -1
                                    currView.translationX = dx * 0.22f
                                }
                            } else if (dx < 0) {
                                // Moving Left -> Seeking Next Tab
                                if (activeTabIndex < currentTabsList.size - 1) {
                                    val nextTab = currentTabsList[activeTabIndex + 1]
                                    val nextView = nextTab.swipeRefreshLayout ?: nextTab.browserTab.renderView
                                    if (nextView != null) {
                                        if (activeGestureAdjacentView !== nextView) {
                                            activeGestureAdjacentView?.visibility = View.GONE
                                            activeGestureAdjacentView?.translationX = 0f
                                            activeGestureAdjacentView = nextView
                                            activeGestureTargetIndex = activeTabIndex + 1
                                        }
                                        if (nextView.visibility != View.VISIBLE) {
                                            nextView.visibility = View.VISIBLE
                                            if (nextView.parent == null) webContainer.addView(nextView)
                                            nextView.bringToFront()
                                        }
                                        if (nextView.layerType != View.LAYER_TYPE_HARDWARE) {
                                            nextView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                        }
                                        currView.translationX = dx
                                        nextView.translationX = containerWidth + dx
                                    }
                                } else {
                                    activeGestureAdjacentView?.visibility = View.GONE
                                    activeGestureAdjacentView = null
                                    activeGestureTargetIndex = -1
                                    currView.translationX = dx * 0.22f
                                }
                            } else {
                                currView.translationX = 0f
                            }
                            if (::addressBar.isInitialized) {
                                addressBar.translationX = dx * 0.12f
                            }
                        }
                        return true
                    }
                }

                // 2. Edge swipe navigation tracking
                if (isEdgeTouch && !isSearchActive) {
                    val isEdgeDrag = (isLeftEdge && dx > dpToPx(14) && dx > absY) ||
                                     (isRightEdge && dx < -dpToPx(14) && absX > absY)
                    if (isEdgeDrag) {
                        isEdgeSwiping = true
                        if (!childTouchesCanceled) {
                            val cancelEvent = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                            super.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                            childTouchesCanceled = true
                        }
                        val currView = currentTabsList.getOrNull(activeTabIndex)?.let { it.swipeRefreshLayout ?: it.browserTab.renderView }
                        if (currView != null) {
                            currView.translationX = dx * 0.25f
                        }
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val tracker = gestureVelocityTracker
                tracker?.addMovement(ev)
                tracker?.computeCurrentVelocity(1000)
                val vx = tracker?.xVelocity ?: 0f
                try {
                    tracker?.recycle()
                } catch (_: Exception) {}
                gestureVelocityTracker = null

                val dx = ev.rawX - gestureStartX
                val dy = ev.rawY - gestureStartY
                val absX = Math.abs(dx)
                val absY = Math.abs(dy)

                // 1. Complete Address Bar Gestures
                if (isAddressBarTouch && !isSearchActive) {
                    if (isAddressBarPullDown) {
                        addressBarContainer.animate().translationY(0f).setDuration(120L).setInterpolator(FAST_INTERPOLATOR).start()
                        webContainer.animate().translationY(0f).setDuration(120L).setInterpolator(FAST_INTERPOLATOR).start()
                        val shouldOpenOverview = (dy > dpToPx(32) && dy > absX)
                        resetGestures()
                        if (shouldOpenOverview) {
                            triggerStrongHaptic(isHeavy = true)
                            showFullScreenTabs()
                        }
                        return true
                    }

                    if (isAddressBarSwiping) {
                        val containerWidth = if (webContainer.width > 0) webContainer.width.toFloat() else resources.displayMetrics.widthPixels.toFloat()
                        val currView = activeGestureCurrView
                        val adjView = activeGestureAdjacentView
                        val targetIdx = activeGestureTargetIndex

                        val thresholdDist = containerWidth * 0.20f
                        val minFlickVel = dpToPx(400).toFloat()
                        val shouldSwitchRight = dx > 0 && targetIdx == activeTabIndex - 1 && (dx > thresholdDist || (vx > minFlickVel && dx > dpToPx(10)))
                        val shouldSwitchLeft = dx < 0 && targetIdx == activeTabIndex + 1 && (-dx > thresholdDist || (vx < -minFlickVel && -dx > dpToPx(10)))

                        val ultraFastDecel = DecelerateInterpolator(2.0f)

                        if (shouldSwitchRight && currView != null && adjView != null) {
                            val remainingDist = containerWidth - dx
                            val speed = Math.max(Math.abs(vx), dpToPx(1200).toFloat())
                            val duration = ((remainingDist / speed) * 1000).toLong().coerceIn(60L, 130L)

                            currView.animate().cancel()
                            adjView.animate().cancel()
                            if (::addressBar.isInitialized) addressBar.animate().cancel()

                            if (::addressBar.isInitialized) {
                                addressBar.animate().translationX(0f).setDuration(duration).setInterpolator(ultraFastDecel).start()
                            }

                            currView.animate()
                                .translationX(containerWidth)
                                .setDuration(duration)
                                .setInterpolator(ultraFastDecel)
                                .start()

                            adjView.animate()
                                .translationX(0f)
                                .setDuration(duration)
                                .setInterpolator(ultraFastDecel)
                                .withEndAction {
                                    currView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    adjView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    currView.translationX = 0f
                                    switchTab(targetIdx, animated = false)
                                }
                                .start()

                            triggerStrongHaptic(isHeavy = true)
                            resetGestures()
                            return true

                        } else if (shouldSwitchLeft && currView != null && adjView != null) {
                            val remainingDist = containerWidth - (-dx)
                            val speed = Math.max(Math.abs(vx), dpToPx(1200).toFloat())
                            val duration = ((remainingDist / speed) * 1000).toLong().coerceIn(60L, 130L)

                            currView.animate().cancel()
                            adjView.animate().cancel()
                            if (::addressBar.isInitialized) addressBar.animate().cancel()

                            if (::addressBar.isInitialized) {
                                addressBar.animate().translationX(0f).setDuration(duration).setInterpolator(ultraFastDecel).start()
                            }

                            currView.animate()
                                .translationX(-containerWidth)
                                .setDuration(duration)
                                .setInterpolator(ultraFastDecel)
                                .start()

                            adjView.animate()
                                .translationX(0f)
                                .setDuration(duration)
                                .setInterpolator(ultraFastDecel)
                                .withEndAction {
                                    currView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    adjView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    currView.translationX = 0f
                                    switchTab(targetIdx, animated = false)
                                }
                                .start()

                            triggerStrongHaptic(isHeavy = true)
                            resetGestures()
                            return true

                        } else {
                            // Snap back
                            currView?.animate()?.cancel()
                            adjView?.animate()?.cancel()
                            if (::addressBar.isInitialized) addressBar.animate().cancel()

                            if (::addressBar.isInitialized) {
                                addressBar.animate().translationX(0f).setDuration(100L).setInterpolator(ultraFastDecel).start()
                            }

                            currView?.animate()
                                ?.translationX(0f)
                                ?.setDuration(100L)
                                ?.setInterpolator(ultraFastDecel)
                                ?.withEndAction {
                                    currView.setLayerType(View.LAYER_TYPE_NONE, null)
                                }
                                ?.start()

                            if (adjView != null) {
                                val cancelTargetX = if (dx > 0) -containerWidth else containerWidth
                                adjView.animate()
                                    .translationX(cancelTargetX)
                                    .setDuration(100L)
                                    .setInterpolator(ultraFastDecel)
                                    .withEndAction {
                                        adjView.visibility = View.GONE
                                        adjView.translationX = 0f
                                        adjView.setLayerType(View.LAYER_TYPE_NONE, null)
                                    }
                                    .start()
                            }

                            resetGestures()
                            return true
                        }
                    }

                    if (childTouchesCanceled) {
                        resetGestures()
                        return true
                    }
                }

                // 2. Complete Edge Navigation Gestures
                if (isEdgeTouch && !isSearchActive) {
                    val edgeEnabled = prefs.getBoolean(HazeTabSystemManager.PREF_GESTURE_EDGE_SWIPE_NAVIGATE, true)
                    val currView = currentTabsList.getOrNull(activeTabIndex)?.let { it.swipeRefreshLayout ?: it.browserTab.renderView }
                    if (edgeEnabled && isEdgeSwiping) {
                        val currentTab = currentTabsList.getOrNull(activeTabIndex)
                        if (isLeftEdge && dx > dpToPx(40) && dx > absY) {
                            if (currentTab?.browserTab?.navigationController?.canGoBack() == true) {
                                triggerStrongHaptic(isHeavy = true)
                                currentTab.browserTab.navigationController.goBack()
                                Toast.makeText(this, "Navigating back", Toast.LENGTH_SHORT).show()
                            }
                        } else if (isRightEdge && dx < -dpToPx(40) && absX > absY) {
                            if (currentTab?.browserTab?.navigationController?.canGoForward() == true) {
                                triggerStrongHaptic(isHeavy = true)
                                currentTab.browserTab.navigationController.goForward()
                                Toast.makeText(this, "Navigating forward", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    currView?.animate()?.translationX(0f)?.setDuration(100L)?.setInterpolator(FAST_INTERPOLATOR)?.start()
                    if (isEdgeSwiping || childTouchesCanceled) {
                        resetGestures()
                        return true
                    }
                }

                resetGestures()
            }

            MotionEvent.ACTION_CANCEL -> {
                activeGestureCurrView?.animate()?.translationX(0f)?.setDuration(100L)?.start()
                activeGestureAdjacentView?.let { adj ->
                    adj.animate().translationX(0f).setDuration(100L).withEndAction {
                        adj.visibility = View.GONE
                    }.start()
                }
                if (!isSearchActive && ::addressBarContainer.isInitialized && addressBarContainer.translationY != 0f) {
                    addressBarContainer.animate().translationY(0f).setDuration(100L).start()
                }
                if (::webContainer.isInitialized && webContainer.translationY != 0f) {
                    webContainer.animate().translationY(0f).setDuration(100L).start()
                }
                if (::addressBar.isInitialized && addressBar.translationX != 0f) {
                    addressBar.animate().translationX(0f).setDuration(100L).start()
                }
                resetGestures()
            }
        }

        return super.dispatchTouchEvent(ev)
    }

    fun showPageZoomDialog() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        var currentZoom = (tab.browserTab.renderView as? WebView)?.settings?.textZoom ?: 100

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16))
        }
        val title = TextView(this).apply {
            text = "Page Zoom"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368"))
            setBackgroundResource(getSelectableRes())
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, dpToPx(16))
        }
        val btnMinus = TextView(this).apply {
            text = "–"
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_popup_rounded)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(44))
        }
        val tvValue = TextView(this).apply {
            text = "$currentZoom%"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(120), dpToPx(44))
        }
        val btnPlus = TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_popup_rounded)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(44))
        }

        fun applyZoom(newVal: Int) {
            currentZoom = newVal.coerceIn(50, 300)
            tvValue.text = "$currentZoom%"
            tab.browserTab.evaluateJavascript("document.body.style.zoom = '${currentZoom}%';") {}
            (tab.browserTab.renderView as? WebView)?.settings?.textZoom = currentZoom
        }

        btnMinus.setOnClickListener {
            triggerStrongHaptic(it)
            applyZoom(currentZoom - 10)
        }
        btnPlus.setOnClickListener {
            triggerStrongHaptic(it)
            applyZoom(currentZoom + 10)
        }
        stepper.addView(btnMinus)
        stepper.addView(tvValue)
        stepper.addView(btnPlus)
        root.addView(stepper)

        val chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(16))
        }
        for (preset in listOf(75, 100, 125, 150, 200)) {
            val chip = TextView(this).apply {
                text = "$preset%"
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_suggestion_chip)
                setTextColor(if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#202124"))
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }
                setOnClickListener {
                    triggerStrongHaptic(it)
                    applyZoom(preset)
                }
            }
            chipContainer.addView(chip)
        }
        root.addView(chipContainer)

        val btnReset = Button(this).apply {
            text = "Reset to 100%"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#0A56D0"))
            setOnClickListener {
                triggerStrongHaptic(it)
                applyZoom(100)
            }
        }
        root.addView(btnReset)

        dialog.setContentView(root)
        dialog.show()
    }

    fun showTextScalingDialog() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val isDark = prefs.getBoolean("is_dark", false) || isPrivateMode
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)

        var currentScale = (tab.browserTab.renderView as? WebView)?.settings?.textZoom ?: 100

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            setPadding(dpToPx(20), dpToPx(16), dpToPx(20), dpToPx(24))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16))
        }
        val title = TextView(this).apply {
            text = "Text Scaling"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val btnClose = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368"))
            setBackgroundResource(getSelectableRes())
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(title)
        header.addView(btnClose)
        root.addView(header)

        val stepper = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(8), 0, dpToPx(16))
        }
        val btnMinus = TextView(this).apply {
            text = "–"
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_popup_rounded)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(44))
        }
        val tvValue = TextView(this).apply {
            text = "$currentScale%"
            textSize = 22f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(120), dpToPx(44))
        }
        val btnPlus = TextView(this).apply {
            text = "+"
            textSize = 24f
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bg_popup_rounded)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            layoutParams = LinearLayout.LayoutParams(dpToPx(52), dpToPx(44))
        }

        fun applyScale(newVal: Int) {
            currentScale = newVal.coerceIn(50, 250)
            tvValue.text = "$currentScale%"
            tab.browserTab.evaluateJavascript("document.documentElement.style.webkitTextSizeAdjust = '${currentScale}%';") {}
            (tab.browserTab.renderView as? WebView)?.settings?.textZoom = currentScale
        }

        btnMinus.setOnClickListener {
            triggerStrongHaptic(it)
            applyScale(currentScale - 10)
        }
        btnPlus.setOnClickListener {
            triggerStrongHaptic(it)
            applyScale(currentScale + 10)
        }
        stepper.addView(btnMinus)
        stepper.addView(tvValue)
        stepper.addView(btnPlus)
        root.addView(stepper)

        val chipContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(16))
        }
        for (preset in listOf(80, 100, 120, 150, 180)) {
            val chip = TextView(this).apply {
                text = "$preset%"
                textSize = 12f
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_suggestion_chip)
                setTextColor(if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#202124"))
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpToPx(4), 0, dpToPx(4), 0)
                }
                setOnClickListener {
                    triggerStrongHaptic(it)
                    applyScale(preset)
                }
            }
            chipContainer.addView(chip)
        }
        root.addView(chipContainer)

        val btnReset = Button(this).apply {
            text = "Reset to 100%"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.parseColor("#0A56D0"))
            setOnClickListener {
                triggerStrongHaptic(it)
                applyScale(100)
            }
        }
        root.addView(btnReset)

        dialog.setContentView(root)
        dialog.show()
    }

    fun showTranslationSuggestionBanner(tab: Tab, languageName: String) {
        HazeTranslationManager.showSuggestionBanner(this, tab, languageName)
    }

    fun dismissTranslationSuggestionBanner() {
        HazeTranslationManager.dismissSuggestionBanner(this)
    }

    fun executeInPageTranslation(targetLang: String = "en") {
        val currentTab = currentTabsList.getOrNull(activeTabIndex) ?: return
        HazeTranslationManager.translateTab(this, currentTab, targetLang)
    }

    fun translateCurrentPage() {
        HazeTranslationManager.showLanguagePickerDialog(this)
    }

    fun saveCurrentPageForOffline() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Cannot save internal page offline", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Saving page for offline reading...", Toast.LENGTH_SHORT).show()
        val offlineDir = File(filesDir, "offline_pages").apply { if (!exists()) mkdirs() }
        val archiveFile = File(offlineDir, "offline_${System.currentTimeMillis()}.mht")

        tab.browserTab.saveWebArchive(archiveFile.absolutePath, false) { savedPath ->
            if (!savedPath.isNullOrEmpty()) {
                val db = HazeDatabase.getInstance(this)
                db.saveOfflinePage(tab.title, tab.url, archiveFile.absolutePath)
                runOnUiThread {
                    Toast.makeText(this, "Page saved for offline reading!", Toast.LENGTH_SHORT).show()
                }
            } else {
                tab.browserTab.evaluateJavascript("document.documentElement.outerHTML") { htmlResult ->
                    val rawHtml = if (!htmlResult.isNullOrBlank() && htmlResult != "null") {
                        try {
                            org.json.JSONTokener(htmlResult).nextValue().toString()
                        } catch (e: Exception) {
                            htmlResult
                        }
                    } else {
                        "<html><body><h1>${tab.title}</h1><p>Saved from ${tab.url}</p></body></html>"
                    }
                    val db = HazeDatabase.getInstance(this)
                    db.saveOfflinePage(tab.title, tab.url, rawHtml)
                    runOnUiThread {
                        Toast.makeText(this, "Page saved for offline reading!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun showOfflinePagesDialog() {
        val db = HazeDatabase.getInstance(this)
        val pages = db.getOfflinePages()
        if (pages.isEmpty()) {
            Toast.makeText(this, "No offline pages saved yet", Toast.LENGTH_SHORT).show()
            return
        }
        val items = pages.map { "${it.title}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved Offline Pages")
            .setItems(items) { _, which ->
                if (which in pages.indices) {
                    val item = pages[which]
                    if (item.content.endsWith(".mht") && File(item.content).exists()) {
                        val newTab = addNewTab("file://${item.content}")
                        newTab.title = "[Offline] " + item.title
                        Toast.makeText(this, "Viewing offline saved page", Toast.LENGTH_SHORT).show()
                    } else {
                        val offlineHtml = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    #hazeOfflineBanner {
                                        position: fixed;
                                        top: 0;
                                        left: 0;
                                        right: 0;
                                        background: #F2994A;
                                        color: #FFFFFF;
                                        font-family: sans-serif;
                                        font-size: 13px;
                                        font-weight: bold;
                                        padding: 8px 16px;
                                        text-align: center;
                                        z-index: 999999;
                                        box-shadow: 0 2px 8px rgba(0,0,0,0.2);
                                    }
                                    body { padding-top: 36px !important; }
                                </style>
                            </head>
                            <body>
                                <div id="hazeOfflineBanner">Offline Saved Copy &bull; ${item.url}</div>
                                ${item.content}
                            </body>
                            </html>
                        """.trimIndent()
                        val newTab = addNewTab(item.url)
                        newTab.title = "[Offline] " + item.title
                        newTab.browserTab.navigationController.loadDataWithBaseURL(item.url, offlineHtml, "text/html", "UTF-8", null)
                        Toast.makeText(this, "Viewing offline saved page", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setPositiveButton("Clear All") { _, _ ->
                db.clearAllOfflinePages()
                Toast.makeText(this, "All offline pages cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
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
        val printAdapter = tab.browserTab.createPrintDocumentAdapter("Haze - " + tab.title) as? android.print.PrintDocumentAdapter
        if (printAdapter != null) {
            printManager?.print("Haze - " + tab.title, printAdapter, android.print.PrintAttributes.Builder().build())
        }
    }

    fun captureCurrentTabScreenshot() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        tab.browserTab.captureThumbnail { bitmap ->
            if (bitmap != null) {
                try {
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
            } else {
                Toast.makeText(this, "Could not capture tab screenshot", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun clearTabsCache() {
        runOnUiThread {
            for (tab in normalTabs) tab.browserTab.clearCache(true)
            for (tab in privateTabs) tab.browserTab.clearCache(true)
        }
    }

    fun clearBrowsingData(onCleared: (() -> Unit)? = null) {
        HazeClearDataManager.showClearDataDialog(this, onCleared)
    }

    fun showPageDetails() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val cert = tab.browserTab.getCertificateInfo()
        val isSecure = tab.url.startsWith("https://") || tab.url.startsWith("haze://") || tab.url == HAZE_HOME_URL
        val message = StringBuilder().apply {
            append("URL: ${tab.url}\n\n")
            append("Security: ${if (isSecure) "Secure Haze Page (HTTPS)" else "Not Secure (HTTP)"}\n\n")
            if (cert != null) {
                append("Issued to: ${cert.issuedTo}\n")
                append("Issued by: ${cert.issuedBy}\n")
                append("Valid until: ${cert.validNotAfter}\n")
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

    fun addNewBackgroundTab(url: String): Tab {
        val browserTab = ChromiumEngineManager.getEngine(this).createTab(this, isPrivateMode)
        val renderView = browserTab.renderView
        renderView.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        val swipeRefreshLayout = androidx.swiperefreshlayout.widget.SwipeRefreshLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setColorSchemeColors(accentColor)
            setOnChildScrollUpCallback { _, _ ->
                val web = (browserTab as? ChromiumBrowserTab)?.webView
                web?.canScrollVertically(-1) == true
            }
            setOnRefreshListener {
                triggerStrongHaptic()
                browserTab.reload()
            }
        }
        swipeRefreshLayout.addView(renderView)

        val tab = Tab(
            id = browserTab.id,
            browserTab = browserTab,
            swipeRefreshLayout = swipeRefreshLayout,
            isPrivate = isPrivateMode,
            url = url,
            title = "New Tab"
        )
        currentTabsList.add(tab)
        webContainer.addView(swipeRefreshLayout)
        setupBrowserTab(browserTab)
        swipeRefreshLayout.visibility = View.GONE
        tab.browserTab.navigationController.loadUrl(url)
        tabCountBtn.text = currentTabsList.size.toString()
        Toast.makeText(this, "Opened in background tab", Toast.LENGTH_SHORT).show()
        return tab
    }

    private fun isNetworkAvailable(): Boolean {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork ?: return false
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                val networkInfo = connectivityManager.activeNetworkInfo ?: return false
                @Suppress("DEPRECATION")
                networkInfo.isConnected
            }
        } catch (e: Exception) {
            true
        }
    }

    private var cachedOfflineImageBase64: String? = null

    private fun getOfflineImageBase64(): String {
        cachedOfflineImageBase64?.let { return it }
        return try {
            val bytes = assets.open("offline_astronaut_cat.png").use { it.readBytes() }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            cachedOfflineImageBase64 = b64
            b64
        } catch (e: Exception) {
            ""
        }
    }

    private fun getOfflineErrorHtml(url: String, isDark: Boolean): String {
        val bgColor = if (isDark) "#121212" else "#FFFFFF"
        val textColor = if (isDark) "#FFFFFF" else "#202124"
        val subTextColor = if (isDark) "#9AA0A6" else "#5F6368"
        val accentColor = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val cardBg = if (isDark) "#1E1E1E" else "#F8F9FA"
        val cardBorder = if (isDark) "#333333" else "#E8EAED"
        val escapedUrl = url.replace("\"", "&quot;").replace("'", "\\'")
        val displayUrl = if (url.startsWith("http://") || url.startsWith("https://")) {
            try { Uri.parse(url).host ?: url } catch (e: Exception) { url }
        } else url
        val imageBase64 = getOfflineImageBase64()
        val imageSrc = if (imageBase64.isNotEmpty()) "data:image/png;base64,$imageBase64" else "file:///android_asset/offline_astronaut_cat.png"
        val artworkStyle = if (isDark) "filter: invert(1); mix-blend-mode: screen;" else "mix-blend-mode: multiply;"

        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600;700&display=swap');
                    * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                    html, body {
                        margin: 0; padding: 0; min-height: 100vh; width: 100vw;
                        display: flex; flex-direction: column; align-items: center; justify-content: center;
                        background: $bgColor;
                        font-family: 'Outfit', -apple-system, BlinkMacSystemFont, sans-serif;
                        color: $textColor;
                        text-align: center;
                        overflow-x: hidden;
                    }
                    .container {
                        display: flex; flex-direction: column; align-items: center;
                        max-width: 420px; width: 90%; padding: 32px 20px;
                        animation: fadeIn 0.4s cubic-bezier(0.16, 1, 0.3, 1);
                    }
                    @keyframes fadeIn {
                        from { opacity: 0; transform: translateY(12px); }
                        to { opacity: 1; transform: translateY(0); }
                    }
                    .illustration-wrap {
                        position: relative;
                        width: 100%;
                        max-width: 320px;
                        margin-bottom: 24px;
                    }
                    .astronaut-art {
                        width: 100%;
                        height: auto;
                        max-height: 220px;
                        object-fit: contain;
                        display: block;
                        margin: 0 auto;
                        $artworkStyle
                    }
                    h1 {
                        font-size: 24px;
                        font-weight: 700;
                        margin: 0 0 10px 0;
                        letter-spacing: -0.3px;
                    }
                    .message {
                        font-size: 14.5px;
                        color: $subTextColor;
                        margin: 0 0 24px 0;
                        line-height: 1.55;
                    }
                    .url-chip {
                        display: inline-block;
                        padding: 6px 14px;
                        background: $cardBg;
                        border: 1px solid $cardBorder;
                        border-radius: 100px;
                        font-size: 12.5px;
                        color: $subTextColor;
                        max-width: 280px;
                        overflow: hidden;
                        text-overflow: ellipsis;
                        white-space: nowrap;
                        margin-bottom: 28px;
                    }
                    .actions {
                        display: flex;
                        flex-direction: column;
                        gap: 12px;
                        width: 100%;
                        max-width: 260px;
                    }
                    .btn-primary {
                        background: $accentColor;
                        color: #FFFFFF;
                        border: none;
                        border-radius: 100px;
                        padding: 13px 28px;
                        font-size: 15px;
                        font-weight: 600;
                        font-family: inherit;
                        cursor: pointer;
                        display: inline-flex;
                        align-items: center;
                        justify-content: center;
                        gap: 8px;
                        box-shadow: 0 4px 14px rgba(0,0,0,0.12);
                        transition: transform 0.12s ease, opacity 0.12s ease;
                    }
                    .btn-primary:active { transform: scale(0.97); opacity: 0.9; }
                    .btn-secondary {
                        background: transparent;
                        color: $textColor;
                        border: 1px solid $cardBorder;
                        border-radius: 100px;
                        padding: 12px 24px;
                        font-size: 14px;
                        font-weight: 500;
                        font-family: inherit;
                        cursor: pointer;
                        transition: transform 0.12s ease;
                    }
                    .btn-secondary:active { transform: scale(0.97); }
                    .spinner {
                        width: 16px;
                        height: 16px;
                        border: 2px solid rgba(255,255,255,0.3);
                        border-top-color: #FFFFFF;
                        border-radius: 50%;
                        animation: spin 0.8s linear infinite;
                        display: inline-block;
                    }
                    @keyframes spin {
                        to { transform: rotate(360deg); }
                    }
                </style>
                <script>
                    function handleRetry() {
                        var btn = document.getElementById('retryBtn');
                        if (btn) {
                            btn.disabled = true;
                            btn.innerHTML = '<span class="spinner"></span> Connecting...';
                        }
                        setTimeout(function() {
                            if (window.Android && window.Android.retryLoad) {
                                window.Android.retryLoad('$escapedUrl');
                            } else {
                                window.location.reload();
                            }
                        }, 250);
                    }
                    function handleHome() {
                        if (window.Android && window.Android.goHome) {
                            window.Android.goHome();
                        } else {
                            window.location.href = 'haze://home';
                        }
                    }
                </script>
            </head>
            <body>
                <div class="container">
                    <div class="illustration-wrap">
                        <img class="astronaut-art" src="$imageSrc" alt="Offline Astronaut and Cat">
                    </div>
                    <h1>You're offline</h1>
                    <p class="message">Check your internet connection or Wi-Fi network and try again.</p>
                    ${if (displayUrl.isNotEmpty() && displayUrl != "about:blank" && !displayUrl.startsWith("haze://")) "<div class=\"url-chip\">$displayUrl</div>" else ""}
                    <div class="actions">
                        <button id="retryBtn" class="btn-primary" onclick="handleRetry()">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/></svg>
                            Try Again
                        </button>
                        <button class="btn-secondary" onclick="handleHome()">Return to Home</button>
                    </div>
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
        val target = progress.coerceIn(1, 99)
        ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, target).apply {
            duration = 180L
            interpolator = BUTTER_INTERPOLATOR
            start()
        }
        progressBar.bringToFront()
    }

    private fun finishPageLoading() {
        currentTabsList.getOrNull(activeTabIndex)?.swipeRefreshLayout?.isRefreshing = false
        val progressBar = findViewById<ProgressBar>(R.id.pageProgressBar) ?: return
        val completedGeneration = pageLoadingGeneration
        ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, 100).apply {
            duration = 140L
            interpolator = BUTTER_INTERPOLATOR
            start()
        }
        progressBar.animate().cancel()
        progressBar.animate()
            .alpha(0f)
            .setDuration(240L)
            .setInterpolator(BUTTER_INTERPOLATOR)
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
            tab.browserTab.setJavaScriptEnabled(jsEnabled)
        }
        for (tab in privateTabs) {
            tab.browserTab.setJavaScriptEnabled(jsEnabled)
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
            val isEnter = (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            val isAction = actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_SEND ||
                    actionId == EditorInfo.IME_ACTION_NEXT ||
                    (actionId == EditorInfo.IME_NULL && event?.action == KeyEvent.ACTION_DOWN)
            if (isEnter || isAction) {
                triggerStrongHaptic(addressBar)
                val typed = addressBar.text?.toString()?.trim() ?: ""
                if (typed.isNotEmpty()) {
                    loadUrlFromInput(typed)
                }
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                addressBar.clearFocus()
                true
            } else false
        }
    }

    fun loadUrlFromInput(input: String) {
        if (activeTabIndex == -1) return
        val raw = input.trim()
        if (raw.isEmpty()) return
        val query = if (raw.contains("%20") || raw.contains("%3A", ignoreCase = true)) {
            runCatching { Uri.decode(raw) }.getOrDefault(raw).trim()
        } else {
            raw
        }

        if (query == HAZE_HOME_URL || query == "haze://home" || query.startsWith("haze://home")) {
            val tab = currentTabsList[activeTabIndex]
            tab.url = HAZE_HOME_URL
            tab.title = "New Tab"
            tab.browserTab.navigationController.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
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
        if (query == "haze://qr" || query == "haze://scan" || query == "haze://qrcode") {
            deactivateSearchMode()
            omniboxManager.startQrScanner()
            return
        }

        // 1. Omnibox search shortcut routing (e.g. "g cats", "yt lo-fi music", "gh chromium source", "w quantum mechanics")
        val shortcut = omniboxManager.parseSearchShortcut(query)
        var url = if (shortcut != null) {
            omniboxManager.buildSearchUrl(shortcut.first, shortcut.second)
        } else if (URLUtil.isValidUrl(query)) {
            query
        } else if (query.contains(".") && !query.contains(" ")) {
            "https://$query"
        } else {
            val searchEngineName = prefs.getString("search_engine", "Google") ?: "Google"
            val activeEngine = omniboxManager.getAllEngines().find { it.name.equals(searchEngineName, ignoreCase = true) }
                ?: omniboxManager.defaultEngines[0]
            omniboxManager.buildSearchUrl(activeEngine, query)
        }

        // Apply Haze Anti-Tracking Engine protections (Bounce protection & Link tracking parameter stripping)
        if (HazeAntiTrackingEngine.isBounceTrackingProtectionEnabled(this)) {
            val unwrapped = HazeAntiTrackingEngine.unwrapBounceTrackingUrl(url)
            if (unwrapped != null) {
                url = unwrapped
            }
        }
        if (HazeAntiTrackingEngine.isLinkTrackingProtectionEnabled(this)) {
            url = HazeAntiTrackingEngine.stripTrackingParameters(url)
        }

        val tab = currentTabsList[activeTabIndex]

        val duplicateTab = currentTabsList.find { 
            it != tab && !isHomeTab(it) && (it.url.equals(url, ignoreCase = true) || it.url.trimEnd('/').equals(url.trimEnd('/'), ignoreCase = true))
        }
        if (duplicateTab != null) {
            val duplicateIdx = currentTabsList.indexOf(duplicateTab)
            if (duplicateIdx != -1) {
                saveRecentSearch(query)
                deactivateSearchMode()
                switchTab(duplicateIdx)
                Toast.makeText(this, "Switched to existing tab: ${duplicateTab.title}", Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (tab.url == HAZE_HOME_URL) tab.title = "Loading..."
        tab.url = url
        
        saveRecentSearch(query)
        deactivateSearchMode()
        
        updateChromeVisibility(url)
        addressBar.setText(url)
        tab.browserTab.navigationController.loadUrl(url)
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
        currentTab.browserTab.evaluateJavascript(toggleScript) { result ->
            val isPlaying = result?.contains("playing") == true
            isMediaPlaying = isPlaying
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(suggestionDebounce)
        suggestionExecutor.shutdownNow()
        dbExecutor.shutdownNow()
        miniPlayerManager?.release()
        super.onDestroy()
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (HazeDevToolsManager.isDevToolsVisible(this@MainActivity)) {
                    HazeDevToolsManager.hideDevTools(this@MainActivity)
                    return
                }
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
                    hideFindBar()
                } else if (activeTabIndex != -1 && currentTabsList[activeTabIndex].browserTab.navigationController.canGoBack()) {
                    val tab = currentTabsList[activeTabIndex]
                    if (tab.url != HAZE_HOME_URL && !tab.url.startsWith("haze://")) tab.browserTab.navigationController.goBack() 
                    else {
                        isEnabled = false; onBackPressedDispatcher.onBackPressed()
                    }
                } else if (currentTabsList.size > 1) {
                    val tabToRemove = currentTabsList[activeTabIndex]
                    webContainer.removeView(tabToRemove.swipeRefreshLayout ?: tabToRemove.browserTab.renderView)
                    tabToRemove.browserTab.destroy()
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
