package com.example.haze

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private lateinit var webContainer: FrameLayout
    private lateinit var addressBar: EditText
    private lateinit var tabCountBtn: TextView
    private lateinit var btnTabsCircular: FrameLayout
    private lateinit var addressBarContainer: View
    private lateinit var appBackgroundImageView: ImageView
    private lateinit var appBackgroundOverlay: View
    private lateinit var findBarContainer: LinearLayout
    private lateinit var findInput: EditText

    private lateinit var prefs: SharedPreferences

    data class BackgroundTheme(
        val id: String,
        val name: String,
        val thumbRes: Int,
        val bgRes: Int,
        val defaultAccent: String
    )

    private val backgroundThemes = listOf(
        BackgroundTheme("default", "Default", R.drawable.ic_theme_default, 0, "#0A56D0"),
        BackgroundTheme("sunset", "Sunset", R.drawable.thumb_theme_sunset, R.drawable.bg_theme_sunset, "#FF7043"),
        BackgroundTheme("space", "Cosmic", R.drawable.thumb_theme_space, R.drawable.bg_theme_space, "#7E57C2"),
        BackgroundTheme("canyon", "Canyon", R.drawable.thumb_theme_canyon, R.drawable.bg_theme_canyon, "#E65100"),
        BackgroundTheme("ocean", "Ocean", R.drawable.thumb_theme_ocean, R.drawable.bg_theme_ocean, "#00ACC1"),
        BackgroundTheme("forest", "Forest", R.drawable.thumb_theme_forest, R.drawable.bg_theme_forest, "#43A047")
    )

    data class ColorTheme(
        val id: String,
        val name: String,
        val colorHex: String,
        val isDefault: Boolean = false
    )

    private val colorThemes = listOf(
        ColorTheme("default", "Default", "#0A56D0", true),
        ColorTheme("blue", "Blue", "#1A73E8"),
        ColorTheme("red", "Red", "#EA4335"),
        ColorTheme("green", "Green", "#34A853"),
        ColorTheme("purple", "Purple", "#9C27B0"),
        ColorTheme("orange", "Orange", "#FF6D01")
    )

    data class TabGroup(var id: String, var name: String, var color: Int)
    data class Shortcut(
        val name: String, 
        val url: String, 
        val iconUrl: String = "",
        val svgPath: String = "", 
        val fillColor: String = ""
    )
    data class Tab(
        val id: Long = System.currentTimeMillis(),
        val webView: WebView,
        var title: String = "New Tab",
        var url: String = "haze://home",
        var favicon: Bitmap? = null,
        var isPrivate: Boolean = false,
        var group: TabGroup? = null,
        var isDesktop: Boolean = false
    )

    private val normalTabs = mutableListOf<Tab>()
    private val privateTabs = mutableListOf<Tab>()
    private var isPrivateMode = false
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

    private val defaultShortcuts = DefaultShortcuts.list.map {
        Shortcut(name = it.name, url = it.url, iconUrl = it.iconUrl)
    }

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

    inner class WebAppInterface {
        @JavascriptInterface
        fun triggerHaptic(heavy: Boolean) {
            runOnUiThread { triggerStrongHaptic(isHeavy = heavy) }
        }
        @JavascriptInterface
        fun focusAddressBar() {
            runOnUiThread {
                triggerStrongHaptic(addressBar)
                addressBar.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(addressBar, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        @JavascriptInterface
        fun startVoiceSearch() { 
            runOnUiThread { 
                triggerStrongHaptic()
                this@MainActivity.startVoiceSearch() 
            } 
        }
        @JavascriptInterface
        fun navigateTo(url: String) { 
            runOnUiThread { 
                triggerStrongHaptic()
                addressBarContainer.visibility = View.VISIBLE
                addressBar.setText(url)
                loadUrlFromInput(url) 
            } 
        }
        @JavascriptInterface
        fun openMenu() { 
            runOnUiThread { 
                triggerStrongHaptic()
                showBrowserMenu() 
            } 
        }
        @JavascriptInterface
        fun openTabManager() { 
            runOnUiThread { 
                triggerStrongHaptic()
                showFullScreenTabs() 
            } 
        }
        @JavascriptInterface
        fun getTabCount(): Int { return currentTabsList.size }
        @JavascriptInterface
        fun openAddShortcutDialog() { 
            runOnUiThread { 
                triggerStrongHaptic()
                showAddShortcutDialog() 
            } 
        }
        @JavascriptInterface
        fun confirmRemoveShortcut(index: Int) { 
            runOnUiThread { 
                triggerStrongHaptic(isHeavy = true)
                showRemoveShortcutDialog(index) 
            } 
        }
        @JavascriptInterface
        fun showMaxShortcutsToast(maxCapacity: Int) {
            runOnUiThread {
                triggerStrongHaptic(isHeavy = true)
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Shortcut Limit Reached")
                    .setMessage("Your shortcuts grid is currently full ($maxCapacity/$maxCapacity).\n\nTo make the Add (+) button active again:\n1. Press and hold (long-press) any shortcut you don't need.\n2. Tap 'Remove'.\n\nOnce removed, this Add button will immediately activate so you can add a new website!")
                    .setPositiveButton("Got It", null)
                    .show()
                Toast.makeText(this@MainActivity, "Hold any icon to remove it and free up space", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun wakeScreen() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "haze:wakelock"
            )
            wakeLock.acquire(15000)
        } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onResume() {
        super.onResume()
        wakeScreen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeScreen()
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("HazePrefs", MODE_PRIVATE)

        webContainer = findViewById(R.id.webContainer)
        addressBar = findViewById(R.id.addressBar)
        tabCountBtn = findViewById(R.id.tabCountBtn)
        btnTabsCircular = findViewById(R.id.btnTabsCircular)
        addressBarContainer = findViewById(R.id.addressBarContainer)
        appBackgroundImageView = findViewById(R.id.appBackgroundImageView)
        appBackgroundOverlay = findViewById(R.id.appBackgroundOverlay)
        val menuBtn: ImageButton = findViewById(R.id.menuBtn)
        val btnVoiceSearch: ImageButton = findViewById(R.id.btnVoiceSearch)
        
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
        addressBar.setOnClickListener {
            triggerStrongHaptic(it)
        }

        // Start App with empty address bar by default
        addressBar.setText("")
        handleCustomIntent(intent)
        applyTheme()
        addNewTab(HAZE_HOME_URL)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCustomIntent(intent)
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
        if (intent.getBooleanExtra("open_personalize", false)) {
            showPersonalizeDialog()
        }
        if (intent.getBooleanExtra("open_menu", false)) {
            webContainer.postDelayed({ showBrowserMenu() }, 400)
        }
        if (intent.getBooleanExtra("open_extra", false)) {
            webContainer.postDelayed({ showExtraToolsDialog() }, 400)
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
                
                // If it's one of the default items or missing authentic iconUrl, use the high-res favicon
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
        return tab.url == HAZE_HOME_URL || tab.url == "$HAZE_HOME_URL/" || tab.url.startsWith("haze://home") || tab.url.isEmpty()
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
                    if (customShortcuts.size >= maxCapacity) {
                        Toast.makeText(this, "Limit reached ($maxCapacity/$maxCapacity). Remove one first.", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                    
                    val host = try { Uri.parse(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }
                    val faviconUrl = "https://www.google.com/s2/favicons?domain=$host&sz=128"
                    
                    customShortcuts.add(Shortcut(name, url, iconUrl = faviconUrl))
                    saveCustomShortcuts(customShortcuts)
                    
                    reloadHomeTabs()
                    Toast.makeText(this, "Shortcut '$name' added!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun showRemoveShortcutDialog(index: Int) {
        val customShortcuts = getCustomShortcuts().toMutableList()
        if (index in 0 until customShortcuts.size) {
            val s = customShortcuts[index]
            AlertDialog.Builder(this)
                .setTitle("Remove Shortcut")
                .setMessage("Do you want to remove '${s.name}' from your home screen?")
                .setPositiveButton("Remove") { _, _ ->
                    val removed = customShortcuts.removeAt(index)
                    saveCustomShortcuts(customShortcuts)
                    reloadHomeTabs()
                    Toast.makeText(this, "Shortcut '${removed.name}' removed! Add button is now active.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun getHazeHomeHtml(): String {
        val isDark = prefs.getBoolean("is_dark", false)
        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2" // "off", "1", "2"
        val accentColor = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val bgThemeId = prefs.getString("bg_theme", "default") ?: "default"
        val selectedBgTheme = backgroundThemes.find { it.id == bgThemeId }
        val hasWallpaper = selectedBgTheme != null && selectedBgTheme.bgRes != 0
        
        val bgColor = if (hasWallpaper) "transparent" else (if (isDark) "#121212" else "#FAFAFA")
        val cardColor = if (hasWallpaper) "rgba(255, 255, 255, 0.22)" else (if (isDark) "#1E1E1E" else "#FFFFFF")
        val textColor = if (hasWallpaper) "#FFFFFF" else (if (isDark) "#FFFFFF" else "#202124")
        val labelColor = if (hasWallpaper) "#FFFFFF" else (if (isDark) "#E0E0E0" else "#202124")
        val textShadow = if (hasWallpaper) "text-shadow: 0 1px 4px rgba(0,0,0,0.85);" else ""
        val cardBorder = if (hasWallpaper) {
            "border: 1px solid rgba(255, 255, 255, 0.35); backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px); box-shadow: 0 4px 16px rgba(0,0,0,0.25);"
        } else if (isDark) {
            "box-shadow: 0 2px 8px rgba(0,0,0,0.3); border: 1px solid rgba(255,255,255,0.08);"
        } else {
            "box-shadow: 0 2px 8px rgba(0,0,0,0.06); border: 1px solid rgba(0,0,0,0.04);"
        }
        
        val maxCapacity = if (shortcutsMode == "1") 4 else 9
        val customShortcuts = getCustomShortcuts()
        val activeShortcuts = customShortcuts.take(maxCapacity)
        val isFull = customShortcuts.size >= maxCapacity
        
        val displayShortcutsHtml = StringBuilder()
        for (i in activeShortcuts.indices) {
            val s = activeShortcuts[i]
            val domain = try { Uri.parse(s.url).host?.removePrefix("www.") ?: s.url } catch (e: Exception) { s.url }
            val fallbackFavicon = "https://www.google.com/s2/favicons?domain=$domain&sz=128"
            val iconSrc = if (s.iconUrl.isNotEmpty()) s.iconUrl else fallbackFavicon
            
            displayShortcutsHtml.append("""
                <div class="shortcut" 
                     ontouchstart="handleTouchStart(event, $i)" 
                     ontouchmove="handleTouchMove(event)" 
                     ontouchend="handleTouchEnd(event, '${s.url}')" 
                     ontouchcancel="handleTouchCancel()"
                     oncontextmenu="return handleContextMenu(event, $i)"
                     onclick="handleClick(event, '${s.url}')">
                    <div class="shortcut-box">
                        <img class="shortcut-icon" src="$iconSrc" onerror="this.onerror=null; this.src='$fallbackFavicon';" alt="${s.name}" />
                    </div>
                    <div class="shortcut-label">${s.name}</div>
                </div>
            """)
        }
        
        if (shortcutsMode != "off") {
            if (isFull) {
                displayShortcutsHtml.append("""
                    <div class="shortcut" onclick="if(window.Android&&window.Android.triggerHaptic)window.Android.triggerHaptic(true); Android.showMaxShortcutsToast($maxCapacity)">
                        <div class="shortcut-box disabled-box">
                            <svg viewBox="0 0 24 24" style="fill: #8A8D93;"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>
                        </div>
                        <div class="shortcut-label" style="opacity: 0.45;">Add</div>
                    </div>
                """)
            } else {
                displayShortcutsHtml.append("""
                    <div class="shortcut" onclick="if(window.Android&&window.Android.triggerHaptic)window.Android.triggerHaptic(false); Android.openAddShortcutDialog()">
                        <div class="shortcut-box">
                            <svg viewBox="0 0 24 24" style="fill: $accentColor;"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>
                        </div>
                        <div class="shortcut-label">Add</div>
                    </div>
                """)
            }
        }
        
        val logoFilter = if (hasWallpaper) {
            """filter: drop-shadow(0 4px 18px rgba(0, 0, 0, 0.88)) drop-shadow(0 2px 4px rgba(0, 0, 0, 0.95));"""
        } else {
            ""
        }
        val logoVignette = if (hasWallpaper) {
            """background: radial-gradient(ellipse at center, rgba(0,0,0,0.38) 0%, rgba(0,0,0,0) 72%); border-radius: 40px; padding: 12px 24px;"""
        } else {
            ""
        }
        val strokeColor = if (hasWallpaper) "#FFFFFF" else textColor
        val op1 = "1.0"
        val op2 = if (hasWallpaper) "0.72" else "0.65"
        val op3 = if (hasWallpaper) "0.45" else "0.35"
        
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=0">
                <style>
                    html, body {
                        margin: 0; padding: 0; height: 100vh; width: 100vw;
                        display: flex; flex-direction: column; align-items: center; justify-content: center;
                        background: $bgColor;
                        background-color: $bgColor;
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        overflow: hidden; position: fixed; touch-action: none;
                        -webkit-user-select: none;
                        user-select: none;
                    }
                    .container {
                        display: flex; flex-direction: column; align-items: center; width: 100%;
                        margin-top: -6vh;
                    }
                    .logo-wrapper {
                        display: flex; justify-content: center; align-items: center;
                        margin-bottom: 24px;
                        $logoVignette
                    }
                    .logo {
                        width: 240px; height: 180px;
                        $logoFilter
                    }
                    .shortcuts {
                        display: ${if (shortcutsMode == "off") "none" else "grid"};
                        grid-template-columns: repeat(5, 1fr);
                        gap: 14px 10px; margin-top: 10px;
                        justify-items: center;
                        width: 94%; max-width: 440px;
                    }
                    .shortcut {
                        display: flex; flex-direction: column; align-items: center;
                        text-decoration: none; cursor: pointer; width: 100%;
                        user-select: none; -webkit-user-select: none; -webkit-touch-callout: none;
                    }
                    .shortcut-box {
                        width: 52px; height: 52px; border-radius: 17px;
                        background: $cardColor;
                        display: flex; justify-content: center; align-items: center;
                        margin-bottom: 6px;
                        transition: transform 0.08s cubic-bezier(0.2, 0.9, 0.3, 1), box-shadow 0.08s ease;
                        will-change: transform;
                        $cardBorder
                    }
                    .shortcut-box:active {
                        transform: scale(0.85);
                        box-shadow: 0 0 12px rgba(10, 86, 208, 0.45);
                    }
                    .shortcut-icon {
                        width: 28px; height: 28px;
                        object-fit: contain;
                        border-radius: 4px;
                        pointer-events: none;
                        -webkit-user-drag: none;
                    }
                    .shortcut-box svg {
                        width: 26px; height: 26px;
                        pointer-events: none;
                    }
                    .shortcut-label {
                        color: $labelColor;
                        font-size: 11px; font-weight: 500;
                        text-align: center;
                        white-space: nowrap; overflow: hidden; text-overflow: ellipsis; width: 100%;
                        $textShadow
                    }
                    .disabled-box {
                        opacity: 0.38 !important;
                        filter: grayscale(100%);
                        cursor: not-allowed;
                        border: 1px dashed rgba(140, 140, 140, 0.4) !important;
                    }
                    .disabled-box:active {
                        transform: none !important;
                        box-shadow: none !important;
                    }
                </style>
                <script>
                    var touchTimer = null;
                    var didLongPress = false;
                    var startX = 0, startY = 0;
                    var hasMoved = false;

                    function handleTouchStart(e, index) {
                        didLongPress = false;
                        hasMoved = false;
                        if (e.touches && e.touches[0]) {
                            startX = e.touches[0].clientX;
                            startY = e.touches[0].clientY;
                        }
                        if (window.Android && window.Android.triggerHaptic) {
                            window.Android.triggerHaptic(false);
                        }
                        touchTimer = setTimeout(function() {
                            if (!hasMoved) {
                                didLongPress = true;
                                if (window.Android && window.Android.triggerHaptic) {
                                    window.Android.triggerHaptic(true);
                                }
                                Android.confirmRemoveShortcut(index);
                            }
                        }, 450);
                    }

                    function handleTouchMove(e) {
                        if (e.touches && e.touches[0]) {
                            var dx = Math.abs(e.touches[0].clientX - startX);
                            var dy = Math.abs(e.touches[0].clientY - startY);
                            if (dx > 12 || dy > 12) {
                                hasMoved = true;
                                clearTimeout(touchTimer);
                            }
                        }
                    }

                    function handleTouchEnd(e, url) {
                        clearTimeout(touchTimer);
                        if (!didLongPress && !hasMoved) {
                            if (window.Android && window.Android.triggerHaptic) {
                                window.Android.triggerHaptic(false);
                            }
                            Android.navigateTo(url);
                        }
                    }

                    function handleTouchCancel() {
                        clearTimeout(touchTimer);
                        hasMoved = true;
                    }

                    function handleContextMenu(e, index) {
                        e.preventDefault();
                        clearTimeout(touchTimer);
                        if (window.Android && window.Android.triggerHaptic) {
                            window.Android.triggerHaptic(true);
                        }
                        Android.confirmRemoveShortcut(index);
                        return false;
                    }

                    function handleClick(e, url) {
                        if (!('ontouchstart' in window) && !didLongPress) {
                            if (window.Android && window.Android.triggerHaptic) {
                                window.Android.triggerHaptic(false);
                            }
                            Android.navigateTo(url);
                        }
                    }
                </script>
            </head>
            <body>
                <div class="container">
                    <div class="logo-wrapper">
                        <svg class="logo" viewBox="0 0 300 200" xmlns="http://www.w3.org/2000/svg">
                            <g transform="rotate(-20 150 100)">
                                <path d="M 50,40 C 90,28 170,25 230,42 C 245,46 235,58 210,56 C 150,50 90,52 45,52 C 35,52 38,43 50,40 Z" fill="$strokeColor" opacity="$op1"/>
                                <path d="M 55,80 C 100,68 175,66 235,82 C 248,85 238,96 215,94 C 155,88 95,90 50,90 C 40,90 43,83 55,80 Z" fill="$strokeColor" opacity="$op2"/>
                                <path d="M 60,120 C 105,108 180,106 240,122 C 250,125 240,135 218,133 C 160,127 100,128 55,128 C 45,128 48,123 60,120 Z" fill="$strokeColor" opacity="$op3"/>
                            </g>
                        </svg>
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
        val isDark = prefs.getBoolean("is_dark", false)
        val bgThemeId = prefs.getString("bg_theme", "default") ?: "default"
        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)
        
        val selectedBgTheme = backgroundThemes.find { it.id == bgThemeId }
        val hasWallpaper = selectedBgTheme != null && selectedBgTheme.bgRes != 0
        
        val currentTab = currentTabsList.getOrNull(activeTabIndex)
        val isHome = currentTab == null || currentTab.url == HAZE_HOME_URL || currentTab.url.isEmpty()
        
        if (hasWallpaper && isHome) {
            appBackgroundImageView.visibility = View.VISIBLE
            appBackgroundImageView.setImageResource(selectedBgTheme!!.bgRes)
            appBackgroundOverlay.visibility = View.VISIBLE
        } else {
            appBackgroundImageView.visibility = View.GONE
            appBackgroundOverlay.visibility = View.GONE
        }
        
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA")
        val barBgColor = if (hasWallpaper && isHome) {
            if (isDark) Color.parseColor("#E61E1E1E") else Color.parseColor("#F5FFFFFF")
        } else {
            if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        }
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val hintColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        
        findViewById<ConstraintLayout>(R.id.main).setBackgroundColor(bgColor)
        
        if (hasWallpaper && isHome) {
            addressBarContainer.setBackgroundColor(Color.TRANSPARENT)
        } else {
            addressBarContainer.setBackgroundColor(bgColor)
        }
        findBarContainer.setBackgroundColor(bgColor)
        
        val addressBarInner = findViewById<LinearLayout>(R.id.addressBarInner)
        val barShape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
            cornerRadius = radiusPx
            setColor(barBgColor)
        }
        addressBarInner.background = barShape
        addressBarInner.elevation = 0f
        addressBarInner.outlineProvider = null
        
        addressBar.setTextColor(textColor)
        addressBar.setHintTextColor(hintColor)
        findInput.setTextColor(textColor)
        findInput.setHintTextColor(hintColor)
        
        findViewById<ImageView>(R.id.searchIcon).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnVoiceSearch).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.menuBtn).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindClose).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindNext).setColorFilter(hintColor)
        findViewById<ImageButton>(R.id.btnFindPrev).setColorFilter(hintColor)
        
        val tabShape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            val tabRadPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, resources.displayMetrics)
            cornerRadius = tabRadPx
            val tabStrokePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.8f, resources.displayMetrics).toInt().coerceAtLeast(1)
            setStroke(tabStrokePx, hintColor)
            setColor(Color.TRANSPARENT)
        }
        btnTabsCircular.background = tabShape
        tabCountBtn.setTextColor(textColor)
        
        if (currentTab != null && isHome) {
            currentTab.webView.setBackgroundColor(Color.TRANSPARENT)
            currentTab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
        }
    }

    private fun addNewTab(url: String) {
        val webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val tab = Tab(webView = webView, isPrivate = isPrivateMode)
        
        currentTabsList.add(tab)
        webContainer.addView(webView)
        
        setupWebView(webView)
        
        if (isPrivateMode) activePrivateTabIndex = currentTabsList.size - 1
        else activeNormalTabIndex = currentTabsList.size - 1
        
        switchTab(activeTabIndex)
        
        when (url) {
            HAZE_HOME_URL -> {
                val bgThemeId = prefs.getString("bg_theme", "default") ?: "default"
                val selectedBgTheme = backgroundThemes.find { it.id == bgThemeId }
                val hasWallpaper = selectedBgTheme != null && selectedBgTheme.bgRes != 0
                val isDark = prefs.getBoolean("is_dark", false)
                if (hasWallpaper) {
                    webView.setBackgroundColor(Color.TRANSPARENT)
                } else {
                    webView.setBackgroundColor(if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))
                }
                webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                tab.title = "New Tab"
                if (activeTabIndex == activeNormalTabIndex || activeTabIndex == activePrivateTabIndex) addressBar.setText("")
            }
            "haze://favorites" -> { webView.loadDataWithBaseURL(url, getInternalPageHtml("Favorites", "⭐"), "text/html", "UTF-8", null); tab.title = "Favorites"; addressBar.setText(url) }
            "haze://history" -> { webView.loadDataWithBaseURL(url, getInternalPageHtml("History", "🕒"), "text/html", "UTF-8", null); tab.title = "History"; addressBar.setText(url) }
            "haze://downloads" -> { webView.loadDataWithBaseURL(url, getInternalPageHtml("Downloads", "⬇️"), "text/html", "UTF-8", null); tab.title = "Downloads"; addressBar.setText(url) }
            "haze://settings" -> { webView.loadDataWithBaseURL(url, getInternalPageHtml("Settings", "⚙️"), "text/html", "UTF-8", null); tab.title = "Settings"; addressBar.setText(url) }
            else -> webView.loadUrl(url)
        }
        tab.url = url
    }

    private fun getInternalPageHtml(title: String, icon: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@100;200;300;400&display=swap');
                    body { font-family: 'Outfit', sans-serif; background-color: #FAFAFA; color: #202124; padding: 24px; text-align: center; }
                    .icon { font-size: 72px; margin-top: 15vh; margin-bottom: 16px; }
                    h1 { font-weight: 300; font-size: 32px; letter-spacing: 2px; }
                    p { color: #5F6368; font-weight: 200; }
                </style>
            </head>
            <body>
                <div class="icon">$icon</div>
                <h1>$title</h1>
                <p>This is your local $title page.</p>
                <p>Functionality coming soon.</p>
            </body>
            </html>
        """.trimIndent()
    }

    private fun switchTab(index: Int) {
        if (index < 0 || index >= currentTabsList.size) {
            tabCountBtn.text = currentTabsList.size.toString()
            return
        }
        
        if (isPrivateMode) activePrivateTabIndex = index else activeNormalTabIndex = index
        
        for (i in 0 until webContainer.childCount) {
            val child = webContainer.getChildAt(i)
            val isCurrentTabWebView = child == currentTabsList[index].webView
            child.visibility = if (isCurrentTabWebView) View.VISIBLE else View.GONE
        }
        
        tabCountBtn.text = currentTabsList.size.toString()
        val currentUrl = currentTabsList[index].url
        
        updateChromeVisibility(currentUrl)
        
        if (isHomeTab(currentTabsList[index]) || currentUrl == HAZE_HOME_URL || currentUrl.startsWith("haze://home") || currentUrl.startsWith("about:") || currentUrl.isEmpty()) {
            addressBar.setText("")
        } else {
            addressBar.setText(currentUrl)
        }
    }

    private fun updateChromeVisibility(url: String?) {
        addressBarContainer.visibility = View.VISIBLE
        val bgThemeId = prefs.getString("bg_theme", "default") ?: "default"
        val selectedBgTheme = backgroundThemes.find { it.id == bgThemeId }
        val hasWallpaper = selectedBgTheme != null && selectedBgTheme.bgRes != 0
        val isHome = url == null || url == HAZE_HOME_URL || url.startsWith("haze://home") || url.isEmpty()
        val isDark = prefs.getBoolean("is_dark", false)
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA")
        
        if (isHome) {
            addressBar.setText("")
        }

        val barBgColor = if (hasWallpaper && isHome) {
            if (isDark) Color.parseColor("#E61E1E1E") else Color.parseColor("#F5FFFFFF")
        } else {
            if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        }
        val addressBarInner = findViewById<LinearLayout>(R.id.addressBarInner)
        val barShape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            val radiusPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 28f, resources.displayMetrics)
            cornerRadius = radiusPx
            setColor(barBgColor)
        }
        addressBarInner.background = barShape
        addressBarInner.elevation = 0f
        addressBarInner.outlineProvider = null
        
        if (hasWallpaper && isHome) {
            appBackgroundImageView.visibility = View.VISIBLE
            appBackgroundImageView.setImageResource(selectedBgTheme!!.bgRes)
            appBackgroundOverlay.visibility = View.VISIBLE
            appBackgroundOverlay.setBackgroundColor(if (isDark) Color.parseColor("#4D000000") else Color.parseColor("#26000000"))
            addressBarContainer.setBackgroundColor(Color.TRANSPARENT)
            currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(Color.TRANSPARENT)
        } else {
            appBackgroundImageView.visibility = View.GONE
            appBackgroundOverlay.visibility = View.GONE
            addressBarContainer.setBackgroundColor(Color.TRANSPARENT)
            if (isHome) {
                currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(bgColor)
            } else {
                currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(Color.WHITE)
            }
        }
    }

    private fun closeTab(index: Int) {
        if (currentTabsList.size <= 1) {
            Toast.makeText(this, "Cannot close the last tab", Toast.LENGTH_SHORT).show()
            return
        }
        val tabToRemove = currentTabsList[index]
        webContainer.removeView(tabToRemove.webView)
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

        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = !isPrivateMode
            databaseEnabled = !isPrivateMode
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = if (isPrivateMode) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
            offscreenPreRaster = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = getTabFromWebView(webView)
                if (tab != null && url != null) {
                    if (url == HAZE_HOME_URL || url == "$HAZE_HOME_URL/" || url.startsWith("haze://home")) {
                        tab.title = "New Tab"
                        tab.url = HAZE_HOME_URL
                    } else {
                        tab.title = view?.title ?: url
                        tab.url = url
                    }
                    if (tab == currentTabsList.getOrNull(activeTabIndex)) {
                        updateChromeVisibility(tab.url)
                        if (isHomeTab(tab) || tab.url == HAZE_HOME_URL || tab.url.startsWith("haze://")) {
                            addressBar.setText("")
                        } else {
                            addressBar.setText(tab.url)
                        }
                    }
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d("HazeWebView", "${consoleMessage?.message()} (line ${consoleMessage?.lineNumber()})")
                return true
            }
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                val tab = getTabFromWebView(webView)
                if (tab != null && title != null) {
                    if (view?.url == HAZE_HOME_URL || tab.url == HAZE_HOME_URL) {
                        tab.title = "New Tab"
                    } else if (!tab.url.startsWith("haze://")) {
                        tab.title = title
                    }
                }
            }
            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                super.onReceivedIcon(view, icon)
                val tab = getTabFromWebView(webView)
                if (tab != null && icon != null) {
                    tab.favicon = icon
                }
            }
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

        btnClose.setOnClickListener { dialog.dismiss() }

        btnNewTabAction.setOnClickListener {
            dialog.dismiss()
            isPrivateMode = viewingPrivate
            addNewTab(HAZE_HOME_URL)
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
                btnTogglePersonal.setTextColor(Color.parseColor("#5F6368"))
                btnTogglePrivate.setTextColor(accentColor)
                tvCurrentModeLabel.text = "Private Tabs"
                btnNewTabAction.text = "+ New Private"
                btnNewTabAction.setTextColor(accentColor)
                
                rvActiveTabs.adapter = privateAdapter
                currentTouchHelper?.attachToRecyclerView(null)
                currentTouchHelper = attachDragAndDrop(rvActiveTabs, privateTabs, privateAdapter, true)
            } else {
                btnTogglePersonal.setTextColor(accentColor)
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
            viewingPrivate = false
            updateUI()
        }

        btnTogglePrivate.setOnClickListener {
            viewingPrivate = true
            updateUI()
        }

        btnNewTabAction.setOnClickListener {
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

            if (tab.favicon != null) holder.tabIcon.setImageBitmap(tab.favicon)
            else holder.tabIcon.setImageResource(R.drawable.ic_globe)
            
            holder.tabTitle.text = tab.title
            holder.tabUrl.text = if (tab.url == HAZE_HOME_URL) "Home" else tab.url

            holder.rowContainer.setOnClickListener { onItemClick(position) }
            holder.tabClose.setOnClickListener { onCloseClick(position) }
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

    private fun showPersonalizeDialog() {
        val isDark = prefs.getBoolean("is_dark", false)
        val shortcutsMode = prefs.getString("shortcuts_mode", "2") ?: "2"
        var selectedBgId = prefs.getString("bg_theme", "default") ?: "default"
        var selectedColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        
        val dialogView = layoutInflater.inflate(R.layout.layout_dialog_personalize, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val scrollView = dialogView.findViewById<ScrollView>(R.id.personalizeScrollView)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvPersonalizeTitle)
        val tvSubtitle = dialogView.findViewById<TextView>(R.id.tvPersonalizeSubtitle)
        val tvSecBg = dialogView.findViewById<TextView>(R.id.tvSectionBackground)
        val tvSecBgSub = dialogView.findViewById<TextView>(R.id.tvSectionBackgroundSub)
        val tvSecCol = dialogView.findViewById<TextView>(R.id.tvSectionColors)
        val tvSecColSub = dialogView.findViewById<TextView>(R.id.tvSectionColorsSub)
        val tvSecApp = dialogView.findViewById<TextView>(R.id.tvSectionAppearance)
        val tvSecShort = dialogView.findViewById<TextView>(R.id.tvSectionShortcuts)
        
        val themeModeGroup = dialogView.findViewById<RadioGroup>(R.id.themeModeGroup)
        val rbLight = dialogView.findViewById<RadioButton>(R.id.rbLightMode)
        val rbDark = dialogView.findViewById<RadioButton>(R.id.rbDarkMode)
        
        val shortcutsGroup = dialogView.findViewById<RadioGroup>(R.id.shortcutsGroup)
        val rbOff = dialogView.findViewById<RadioButton>(R.id.rbShortcutsOff)
        val rb1Row = dialogView.findViewById<RadioButton>(R.id.rbShortcuts1Row)
        val rb2Rows = dialogView.findViewById<RadioButton>(R.id.rbShortcuts2Rows)
        
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelPersonalize)
        val btnApply = dialogView.findViewById<Button>(R.id.btnApplyPersonalize)
        
        val themeBackgroundsLayout = dialogView.findViewById<LinearLayout>(R.id.themeBackgroundsLayout)
        val themeColorsLayout = dialogView.findViewById<LinearLayout>(R.id.themeColorsLayout)
        
        val dialogBgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val titleTextColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
        
        val dialogShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(dialogBgColor)
            setStroke(dpToPx(1), strokeColor)
        }
        scrollView.background = dialogShape
        
        tvTitle.setTextColor(titleTextColor)
        tvSecBg.setTextColor(titleTextColor)
        tvSecCol.setTextColor(titleTextColor)
        tvSecApp.setTextColor(titleTextColor)
        tvSecShort.setTextColor(titleTextColor)
        
        tvSubtitle.setTextColor(subTextColor)
        tvSecBgSub.setTextColor(subTextColor)
        tvSecColSub.setTextColor(subTextColor)
        
        rbLight.setTextColor(titleTextColor)
        rbDark.setTextColor(titleTextColor)
        rbOff.setTextColor(titleTextColor)
        rb1Row.setTextColor(titleTextColor)
        rb2Rows.setTextColor(titleTextColor)
        
        if (isDark) rbDark.isChecked = true else rbLight.isChecked = true
        when (shortcutsMode) {
            "off" -> rbOff.isChecked = true
            "1" -> rb1Row.isChecked = true
            else -> rb2Rows.isChecked = true
        }

        fun updateApplyButton() {
            val accent = Color.parseColor(selectedColorHex)
            val btnShape = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(21).toFloat()
                setColor(accent)
            }
            btnApply.background = btnShape
            btnApply.setTextColor(Color.WHITE)
        }
        updateApplyButton()

        val bgRingViews = mutableListOf<FrameLayout>()
        val bgLabelViews = mutableListOf<TextView>()
        
        fun refreshBgSelection() {
            val accent = Color.parseColor(selectedColorHex)
            backgroundThemes.forEachIndexed { index, theme ->
                val ring = bgRingViews.getOrNull(index) ?: return@forEachIndexed
                val label = bgLabelViews.getOrNull(index) ?: return@forEachIndexed
                val isSelected = theme.id == selectedBgId
                
                val ringDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isSelected) {
                        setStroke(dpToPx(3), accent)
                        setColor(Color.TRANSPARENT)
                    } else {
                        setStroke(dpToPx(1), if (isDark) Color.parseColor("#444444") else Color.parseColor("#E0E0E0"))
                        setColor(Color.TRANSPARENT)
                    }
                }
                ring.background = ringDrawable
                if (isSelected) {
                    label.setTextColor(accent)
                    label.setTypeface(null, Typeface.BOLD)
                } else {
                    label.setTextColor(subTextColor)
                    label.setTypeface(null, Typeface.NORMAL)
                }
            }
        }

        val colorRingViews = mutableListOf<FrameLayout>()
        val colorLabelViews = mutableListOf<TextView>()
        
        fun refreshColorSelection() {
            colorThemes.forEachIndexed { index, col ->
                val ring = colorRingViews.getOrNull(index) ?: return@forEachIndexed
                val label = colorLabelViews.getOrNull(index) ?: return@forEachIndexed
                val isSelected = selectedColorHex.equals(col.colorHex, ignoreCase = true)
                val colorInt = Color.parseColor(col.colorHex)
                
                val ringDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    if (isSelected) {
                        setStroke(dpToPx(3), colorInt)
                        setColor(Color.TRANSPARENT)
                    } else {
                        setStroke(dpToPx(1), if (isDark) Color.parseColor("#444444") else Color.parseColor("#E0E0E0"))
                        setColor(Color.TRANSPARENT)
                    }
                }
                ring.background = ringDrawable
                if (isSelected) {
                    label.setTextColor(colorInt)
                    label.setTypeface(null, Typeface.BOLD)
                } else {
                    label.setTextColor(subTextColor)
                    label.setTypeface(null, Typeface.NORMAL)
                }
            }
            updateApplyButton()
            refreshBgSelection()
        }

        // Build Background Themes horizontal round circles
        themeBackgroundsLayout.removeAllViews()
        backgroundThemes.forEachIndexed { index, theme ->
            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dpToPx(12), 0)
                }
            }
            
            val ringFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(54), dpToPx(54)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            
            val innerImageView = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                    gravity = Gravity.CENTER
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (theme.id == "default") {
                    setImageResource(R.drawable.ic_theme_default)
                } else {
                    setImageResource(theme.thumbRes)
                }
            }
            ringFrame.addView(innerImageView)
            
            val labelView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(62),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                text = theme.name
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
            }
            
            itemContainer.addView(ringFrame)
            itemContainer.addView(labelView)
            
            itemContainer.setOnClickListener {
                selectedBgId = theme.id
                refreshBgSelection()
            }
            
            bgRingViews.add(ringFrame)
            bgLabelViews.add(labelView)
            themeBackgroundsLayout.addView(itemContainer)
        }
        refreshBgSelection()

        // Build Color Themes horizontal round circles
        themeColorsLayout.removeAllViews()
        colorThemes.forEachIndexed { index, col ->
            val itemContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, dpToPx(12), 0)
                }
            }
            
            val ringFrame = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(54), dpToPx(54)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            }
            
            if (col.isDefault) {
                val defaultImageView = ImageView(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                        gravity = Gravity.CENTER
                    }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageResource(R.drawable.ic_color_default)
                }
                ringFrame.addView(defaultImageView)
            } else {
                val colorCircleView = View(this).apply {
                    layoutParams = FrameLayout.LayoutParams(dpToPx(44), dpToPx(44)).apply {
                        gravity = Gravity.CENTER
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(col.colorHex))
                    }
                }
                ringFrame.addView(colorCircleView)
            }
            
            val labelView = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    dpToPx(58),
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dpToPx(6)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                text = col.name
                textSize = 11f
                gravity = Gravity.CENTER
                maxLines = 1
            }
            
            itemContainer.addView(ringFrame)
            itemContainer.addView(labelView)
            
            itemContainer.setOnClickListener {
                selectedColorHex = col.colorHex
                refreshColorSelection()
            }
            
            colorRingViews.add(ringFrame)
            colorLabelViews.add(labelView)
            themeColorsLayout.addView(itemContainer)
        }
        refreshColorSelection()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        btnApply.setOnClickListener {
            val newShortcutsMode = when {
                rbOff.isChecked -> "off"
                rb1Row.isChecked -> "1"
                else -> "2"
            }
            val newIsDark = rbDark.isChecked
            
            prefs.edit()
                .putString("bg_theme", selectedBgId)
                .putString("accent_color", selectedColorHex)
                .putBoolean("is_dark", newIsDark)
                .putString("shortcuts_mode", newShortcutsMode)
                .apply()
                
            dialog.dismiss()
            applyTheme()
            
            val selBg = backgroundThemes.find { it.id == selectedBgId }
            val hasWp = selBg != null && selBg.bgRes != 0
            val homeBgColor = if (hasWp) Color.TRANSPARENT else (if (newIsDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA"))

            for (tab in normalTabs) {
                if (isHomeTab(tab)) {
                    tab.url = HAZE_HOME_URL
                    tab.webView.setBackgroundColor(homeBgColor)
                    tab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                }
            }
            for (tab in privateTabs) {
                if (isHomeTab(tab)) {
                    tab.url = HAZE_HOME_URL
                    tab.webView.setBackgroundColor(homeBgColor)
                    tab.webView.loadDataWithBaseURL(HAZE_HOME_URL, getHazeHomeHtml(), "text/html", "UTF-8", null)
                }
            }
        }
        
        dialog.show()
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

        val isDark = prefs.getBoolean("is_dark", false)
        val popupBg = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val iconColor = if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#5F6368")

        val root = popupView.findViewById<LinearLayout>(R.id.menuRoot)
        root?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(16).toFloat()
            setColor(popupBg)
            setStroke(dpToPx(1), strokeColor)
        }
        val divider = popupView.findViewById<View>(R.id.menuDivider)
        divider?.setBackgroundColor(strokeColor)

        val grid = popupView.findViewById<GridLayout>(R.id.menuGrid)

        val menuItems = listOf(
            Pair("New tab", R.drawable.ic_add),
            Pair("New InPrivate", R.drawable.ic_private),
            Pair("Personalize", R.drawable.ic_palette),
            Pair("Favorites", R.drawable.ic_star),
            Pair("History", R.drawable.ic_history),
            Pair("Downloads", R.drawable.ic_download),
            Pair("Full screen", R.drawable.ic_fullscreen),
            Pair("Reading mode", R.drawable.ic_reading_mode),
            Pair("Extra", R.drawable.ic_extra),
            Pair("Find on page", R.drawable.ic_search),
            Pair("Settings", R.drawable.ic_settings),
            Pair("Close Haze", R.drawable.ic_close)
        )

        for (item in menuItems) {
            val itemView = layoutInflater.inflate(R.layout.item_menu_box, grid, false)
            val iconView = itemView.findViewById<ImageView>(R.id.menuIcon)
            val textView = itemView.findViewById<TextView>(R.id.menuText)

            iconView.setImageResource(item.second)
            iconView.setColorFilter(iconColor)
            textView.text = item.first
            textView.setTextColor(textColor)

            val params = GridLayout.LayoutParams()
            params.width = dpToPx(92)
            params.height = GridLayout.LayoutParams.WRAP_CONTENT
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            itemView.layoutParams = params

            itemView.setOnClickListener {
                triggerStrongHaptic(it)
                popupWindow.dismiss()
                when (item.first) {
                    "New tab" -> addNewTab(HAZE_HOME_URL)
                    "New InPrivate" -> {
                        isPrivateMode = true
                        addNewTab(HAZE_HOME_URL)
                        Toast.makeText(this, "Private mode enabled", Toast.LENGTH_SHORT).show()
                    }
                    "Personalize" -> showPersonalizeDialog()
                    "Favorites" -> addNewTab("haze://favorites")
                    "History" -> addNewTab("haze://history")
                    "Downloads" -> addNewTab("haze://downloads")
                    "Full screen" -> toggleFullScreenMode()
                    "Reading mode" -> toggleReadingMode()
                    "Extra" -> showExtraToolsDialog()
                    "Find on page" -> {
                        findBarContainer.visibility = View.VISIBLE
                        findInput.requestFocus()
                        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
                    }
                    "Settings" -> addNewTab("haze://settings")
                    "Close Haze" -> finish()
                }
            }
            grid.addView(itemView)
        }

        popupView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val popupHeight = popupView.measuredHeight
        val menuBtn = findViewById<ImageButton>(R.id.menuBtn)

        popupWindow.showAsDropDown(menuBtn, 0, -(popupHeight + menuBtn.height + 24))
    }

    private fun toggleFullScreenMode() {
        isFullScreenMode = !isFullScreenMode
        triggerStrongHaptic(isHeavy = true)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullScreenMode) {
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            addressBarContainer.visibility = View.GONE
            Toast.makeText(this, "Full screen enabled. Swipe edges or tap Back to exit.", Toast.LENGTH_SHORT).show()
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
            val currentTab = currentTabsList.getOrNull(activeTabIndex)
            updateChromeVisibility(currentTab?.url)
            Toast.makeText(this, "Exited full screen", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleReadingMode() {
        triggerStrongHaptic()
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Reading mode is available on web articles", Toast.LENGTH_SHORT).show()
            return
        }
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val js = """
            (function() {
                var existing = document.getElementById('haze-reader-style');
                if (existing) {
                    existing.remove();
                    var exitBtn = document.getElementById('haze-reader-exit-btn');
                    if (exitBtn) exitBtn.remove();
                    return 'disabled';
                }
                var style = document.createElement('style');
                style.id = 'haze-reader-style';
                style.innerHTML = `
                    body {
                        max-width: 740px !important;
                        margin: 0 auto !important;
                        padding: 24px 20px 84px 20px !important;
                        font-family: Georgia, 'Times New Roman', serif !important;
                        font-size: 19px !important;
                        line-height: 1.8 !important;
                        background-color: ${if (isDark) "#181818" else "#FBF7ED"} !important;
                        color: ${if (isDark) "#E6E6E6" else "#2C2523"} !important;
                        letter-spacing: 0.2px !important;
                    }
                    header, nav, footer, aside, .sidebar, .advertisement, .ad, .ads, [class*="banner"], [id*="banner"], iframe {
                        display: none !important;
                    }
                    img {
                        max-width: 100% !important;
                        height: auto !important;
                        border-radius: 8px !important;
                        margin: 16px auto !important;
                        display: block !important;
                    }
                    h1, h2, h3 {
                        color: ${if (isDark) "#FFFFFF" else "#1A1A1A"} !important;
                        line-height: 1.3 !important;
                        margin-top: 1.2em !important;
                    }
                    a {
                        color: $accentColor !important;
                        text-decoration: underline !important;
                    }
                `;
                document.head.appendChild(style);
                
                var exitBtn = document.createElement('div');
                exitBtn.id = 'haze-reader-exit-btn';
                exitBtn.innerHTML = '✕ Exit Reading Mode';
                exitBtn.style.cssText = 'position:fixed;bottom:24px;left:50%;transform:translateX(-50%);background:$accentColor;color:#FFF;padding:12px 24px;border-radius:24px;font-family:sans-serif;font-size:14px;font-weight:600;box-shadow:0 4px 16px rgba(0,0,0,0.35);z-index:9999999;cursor:pointer;-webkit-tap-highlight-color:transparent;';
                exitBtn.onclick = function() {
                    var s = document.getElementById('haze-reader-style');
                    if (s) s.remove();
                    exitBtn.remove();
                    if (window.Android && window.Android.triggerHaptic) window.Android.triggerHaptic(false);
                };
                document.body.appendChild(exitBtn);
                return 'enabled';
            })();
        """.trimIndent()

        tab.webView.evaluateJavascript(js) { result ->
            if (result?.contains("enabled") == true) {
                Toast.makeText(this, "Reading mode enabled", Toast.LENGTH_SHORT).show()
            } else if (result?.contains("disabled") == true) {
                Toast.makeText(this, "Reading mode disabled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showExtraToolsDialog() {
        triggerStrongHaptic()
        val tab = currentTabsList.getOrNull(activeTabIndex)
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(20), dpToPx(20), dpToPx(20), dpToPx(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                setColor(bgColor)
                setStroke(dpToPx(1), strokeColor)
            }
        }

        val titleView = TextView(this).apply {
            text = "Extra Tools"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(14))
        }
        dialogView.addView(titleView)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        fun createToolItem(title: String, subtitle: String, iconRes: Int, checked: Boolean? = null, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(8), dpToPx(12), dpToPx(8), dpToPx(12))
                val outValue = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                setBackgroundResource(outValue.resourceId)
            }

            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(accentColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                    marginEnd = dpToPx(16)
                }
            }
            row.addView(icon)

            val textLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tView = TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                setTypeface(null, Typeface.NORMAL)
            }
            val sView = TextView(this).apply {
                text = subtitle
                textSize = 12f
                setTextColor(subTextColor)
            }
            textLayout.addView(tView)
            textLayout.addView(sView)
            row.addView(textLayout)

            if (checked != null) {
                val cb = CheckBox(this).apply {
                    isChecked = checked
                    isClickable = false
                    buttonTintList = android.content.res.ColorStateList.valueOf(accentColor)
                }
                row.addView(cb)
            }

            row.setOnClickListener {
                triggerStrongHaptic(it)
                dialog.dismiss()
                onClick()
            }
            dialogView.addView(row)
        }

        val isDesktop = tab?.isDesktop ?: false
        createToolItem(
            title = "Desktop site",
            subtitle = if (isDesktop) "Desktop layout active" else "Request desktop version",
            iconRes = R.drawable.ic_desktop,
            checked = isDesktop
        ) {
            tab?.let { toggleDesktopSite(it) }
        }

        createToolItem(
            title = "Share page",
            subtitle = "Send link via apps or messaging",
            iconRes = R.drawable.ic_share
        ) {
            shareCurrentPage()
        }

        createToolItem(
            title = "Print / Save PDF",
            subtitle = "Print page or export as PDF",
            iconRes = R.drawable.ic_download
        ) {
            printCurrentPage()
        }

        createToolItem(
            title = "Clear browsing data",
            subtitle = "Clear cache, cookies, and history",
            iconRes = R.drawable.ic_close
        ) {
            clearBrowsingData()
        }

        createToolItem(
            title = "Page details",
            subtitle = "Security and connection info",
            iconRes = R.drawable.ic_settings
        ) {
            showPageDetails()
        }

        dialog.show()
    }

    private fun toggleDesktopSite(tab: Tab) {
        tab.isDesktop = !tab.isDesktop
        val settings = tab.webView.settings
        if (tab.isDesktop) {
            settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            Toast.makeText(this, "Desktop site requested", Toast.LENGTH_SHORT).show()
        } else {
            settings.userAgentString = null
            Toast.makeText(this, "Mobile site restored", Toast.LENGTH_SHORT).show()
        }
        tab.webView.reload()
    }

    private fun shareCurrentPage() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val url = tab.url
        if (isHomeTab(tab) || url.startsWith("haze://")) {
            Toast.makeText(this, "Cannot share home screen", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.title)
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }

    private fun printCurrentPage() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val printManager = getSystemService(PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(this, "Printing service unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        val printAdapter = tab.webView.createPrintDocumentAdapter(tab.title.ifEmpty { "Haze_Webpage" })
        printManager.print("Haze Document", printAdapter, PrintAttributes.Builder().build())
    }

    private fun clearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("Clear Browsing Data")
            .setMessage("Do you want to clear cached web pages, site cookies, and storage?")
            .setPositiveButton("Clear") { _, _ ->
                triggerStrongHaptic(isHeavy = true)
                for (tab in normalTabs) {
                    tab.webView.clearCache(true)
                    tab.webView.clearHistory()
                }
                for (tab in privateTabs) {
                    tab.webView.clearCache(true)
                    tab.webView.clearHistory()
                }
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPageDetails() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val url = tab.url
        val isSecure = url.startsWith("https://")
        val securityStatus = if (isSecure) "🔒 Secure connection (HTTPS)" else "⚠️ Connection not secure"
        val domain = try { Uri.parse(url).host ?: url } catch (e: Exception) { url }

        AlertDialog.Builder(this)
            .setTitle(tab.title)
            .setMessage("Domain: $domain\nURL: $url\n\nSecurity:\n$securityStatus")
            .setPositiveButton("OK", null)
            .show()
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
                loadUrlFromInput(addressBar.text.toString())
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(addressBar.windowToken, 0)
                addressBar.clearFocus()
                true
            } else false
        }
    }

    private fun loadUrlFromInput(input: String) {
        if (activeTabIndex == -1) return
        val query = input.trim()
        if (query.isEmpty()) return

        val url = if (URLUtil.isValidUrl(query)) query
        else if (query.contains(".") && !query.contains(" ")) "https://$query"
        else "https://www.google.com/search?q=${Uri.encode(query)}"

        val tab = currentTabsList[activeTabIndex]
        if (tab.url == HAZE_HOME_URL) tab.title = "Loading..."
        tab.url = url
        updateChromeVisibility(url)
        tab.webView.loadUrl(url)
    }

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullScreenMode) {
                    toggleFullScreenMode()
                    return
                }
                if (findBarContainer.visibility == View.VISIBLE) {
                    findBarContainer.visibility = View.GONE
                    currentTabsList.getOrNull(activeTabIndex)?.webView?.clearMatches()
                    return
                }
                if (activeTabIndex != -1 && currentTabsList[activeTabIndex].webView.canGoBack()) {
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
