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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
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
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.FileProvider

class MainActivity : AppCompatActivity() {

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

    private lateinit var prefs: SharedPreferences

    data class TabGroup(var id: String, var name: String, var color: Int)
    data class Shortcut(val name: String, val url: String, val iconUrl: String = "", val svgPath: String = "", val fillColor: String = "")
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

    inner class WebAppInterface {
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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("HazePrefs", MODE_PRIVATE)

        webContainer = findViewById(R.id.webContainer)
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
                btnClearQuery.visibility = if (text.isNotEmpty() && isSearchActive) View.VISIBLE else View.GONE
                currentTabsList.getOrNull(activeTabIndex)?.webView?.findAllAsync(text)
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

        addressBar.setText("")
        handleCustomIntent(intent)
        applyTheme()
        addNewTab(HAZE_HOME_URL)
    }

    private fun activateSearchMode() {
        if (isSearchActive) return
        isSearchActive = true

        val rootLayout = findViewById<ConstraintLayout>(R.id.main)
        val autoTransition = AutoTransition().apply {
            duration = 240
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
        dimOverlay.animate().alpha(1f).setDuration(240).start()

        renderRecentSearches()
        recentSearchesContainer.visibility = View.VISIBLE
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
            duration = 200
            interpolator = FastOutSlowInInterpolator()
        }
        TransitionManager.beginDelayedTransition(rootLayout, autoTransition)

        val params = addressBarContainer.layoutParams as ConstraintLayout.LayoutParams
        params.topToTop = ConstraintLayout.LayoutParams.UNSET
        params.topMargin = 0
        params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
        addressBarContainer.layoutParams = params

        dimOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            dimOverlay.visibility = View.GONE
        }.start()

        recentSearchesContainer.visibility = View.GONE
        btnClearQuery.visibility = View.GONE

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
        if (intent.getBooleanExtra("open_bookmarks", false)) {
            webContainer.postDelayed({ HazeBookmarksManager.showBookmarksDialog(this) { loadUrlFromInput(it) } }, 300)
        }
        if (intent.getBooleanExtra("open_history", false)) {
            webContainer.postDelayed({ HazeHistoryManager.showHistoryDialog(this) { loadUrlFromInput(it) } }, 300)
        }
        if (intent.getBooleanExtra("open_downloads", false)) {
            webContainer.postDelayed({ HazeDownloadsManager.showDownloadsDialog(this) }, 300)
        }
        if (intent.getBooleanExtra("open_menu", false)) {
            webContainer.postDelayed({ showBrowserMenu() }, 400)
        }
        if (intent.getBooleanExtra("activate_search", false)) {
            webContainer.postDelayed({ activateSearchMode() }, 300)
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
            "background: linear-gradient(rgba(0,0,0,0.32), rgba(0,0,0,0.48)), url('file:///android_asset/bg_theme_$bgTheme.webp') no-repeat center center fixed; background-size: cover;"
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

        val logoSrc = if (isDark || hasBgPhoto) "file:///android_asset/haze_logo_dark.png" else "file:///android_asset/haze_logo.png"
        val logoViewHtml = if (isIncognito) {
            """
            <svg class="logo" viewBox="0 0 100 80" xmlns="http://www.w3.org/2000/svg">
                <path d="M50 10 C38 10 28 16 22 22 C18 30 5 32 5 38 C5 40 7 42 10 42 L90 42 C93 42 95 40 95 38 C95 32 82 30 78 22 C72 16 62 10 50 10 Z" fill="$textColor"/>
                <circle cx="32" cy="62" r="15" fill="$textColor"/>
                <circle cx="32" cy="62" r="11" fill="$bgColor"/>
                <circle cx="68" cy="62" r="15" fill="$textColor"/>
                <circle cx="68" cy="62" r="11" fill="$bgColor"/>
                <path d="M47 62 L53 62" stroke="$textColor" stroke-width="4" stroke-linecap="round"/>
            </svg>
            """.trimIndent()
        } else {
            """<img class="logo" src="$logoSrc" alt="Haze" />"""
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
                        width: 220px; height: 155px;
                        object-fit: contain;
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
        
        reloadHomeTabs()
    }

    private fun getInternalPageHtml(title: String, icon: String): String {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600&display=swap');
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

    fun addNewTab(url: String) {
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
            else -> webView.loadUrl(url)
        }
        tab.url = url
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
        val isHome = url == null || url == HAZE_HOME_URL || url.startsWith("haze://home") || url.isEmpty()
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
        
        addressBarContainer.setBackgroundColor(Color.TRANSPARENT)
        if (isHome) {
            currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(bgColor)
        } else {
            currentTabsList.getOrNull(activeTabIndex)?.webView?.setBackgroundColor(Color.WHITE)
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

        val jsEnabled = prefs.getBoolean("javascript_enabled", true)
        webView.settings.apply {
            javaScriptEnabled = jsEnabled
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

        // Web download listener
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            HazeDownloadsManager.initDownload(this, url, userAgent, contentDisposition, mimeType, contentLength)
        }

        // Native Context Menu on Long Click
        webView.setOnLongClickListener {
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
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val tab = getTabFromWebView(webView)
                if (tab != null && url != null) {
                    if (url == HAZE_HOME_URL) {
                        tab.title = "New Tab"
                        tab.url = url
                    } else {
                        tab.url = url
                    }
                    if (tab == currentTabsList.getOrNull(activeTabIndex)) {
                        updateChromeVisibility(url)
                        if (url == HAZE_HOME_URL || url.startsWith("haze://")) {
                            if (url == HAZE_HOME_URL) addressBar.setText("") else addressBar.setText(url)
                        } else {
                            addressBar.setText(url)
                        }
                    }

                    // Record history if not private and not internal
                    if (!isPrivateMode && url != HAZE_HOME_URL && !url.startsWith("haze://") && !url.startsWith("about:")) {
                        val pageTitle = tab.title.ifEmpty { view?.title ?: "Web Page" }
                        HazeDatabase.getInstance(this@MainActivity).addHistory(url, pageTitle, isPrivate = false)
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
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
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

            if (tab.favicon != null) holder.tabIcon.setImageBitmap(tab.favicon)
            else holder.tabIcon.setImageResource(R.drawable.ic_globe)
            
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

        val dialogView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(24))
        }
        dialogView.addView(content)
        
        val titleText = TextView(this).apply {
            text = "Personalize"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#202124"))
            setPadding(0, 0, 0, dpToPx(20))
        }
        content.addView(titleText)
        
        // Theme Toggle (Light / Dark)
        val themeLabel = TextView(this).apply {
            text = "Theme"
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(8))
        }
        content.addView(themeLabel)
        
        val themeGroup = RadioGroup(this).apply { 
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(16)) 
        }
        val rbLight = RadioButton(this).apply { text = "Light Mode"; setTextColor(if(isDark) Color.WHITE else Color.BLACK) }
        val rbDark = RadioButton(this).apply { text = "Dark Mode"; setTextColor(if(isDark) Color.WHITE else Color.BLACK) }
        themeGroup.addView(rbLight)
        themeGroup.addView(rbDark)
        if (isDark) rbDark.isChecked = true else rbLight.isChecked = true
        content.addView(themeGroup)
        
        // Shortcuts Mode Selector
        val shortcutsLabel = TextView(this).apply {
            text = "Shortcuts Rows"
            setTextColor(Color.GRAY)
            textSize = 13f
            setPadding(0, 0, 0, dpToPx(8))
        }
        content.addView(shortcutsLabel)
        
        val shortcutsGroup = RadioGroup(this).apply { 
            orientation = RadioGroup.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(20)) 
        }
        val rbOff = RadioButton(this).apply { text = "Off"; setTextColor(if(isDark) Color.WHITE else Color.BLACK) }
        val rb1Row = RadioButton(this).apply { text = "1 Row"; setTextColor(if(isDark) Color.WHITE else Color.BLACK) }
        val rb2Row = RadioButton(this).apply { text = "2 Rows"; setTextColor(if(isDark) Color.WHITE else Color.BLACK) }
        shortcutsGroup.addView(rbOff)
        shortcutsGroup.addView(rb1Row)
        shortcutsGroup.addView(rb2Row)
        when (shortcutsMode) {
            "off" -> rbOff.isChecked = true
            "1" -> rb1Row.isChecked = true
            else -> rb2Row.isChecked = true
        }
        content.addView(shortcutsGroup)
        
        // Accent Colors (Round circles: Default + 5 solid colors)
        val accentLabel = TextView(this).apply {
            text = "Accent Color (Default + 5 Solid Colors)"
            setTextColor(Color.GRAY)
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
        content.addView(colorScroll)
        
        // Background Themes (Round circles: Default + 5 Photo Themes)
        val bgLabel = TextView(this).apply {
            text = "Background Theme (Default + 5 Photos)"
            setTextColor(Color.GRAY)
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
        content.addView(bgScroll)
        
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
        val menuRoot = popupView.findViewById<LinearLayout>(R.id.menuRoot)
        val menuLogo = popupView.findViewById<ImageView>(R.id.menuLogo)
        val menuDivider = popupView.findViewById<View>(R.id.menuDivider)
        
        if (isDark) {
            menuLogo.setImageResource(R.drawable.haze_logo_dark)
            menuDivider.setBackgroundColor(Color.parseColor("#333333"))
            menuRoot.setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        val grid = popupView.findViewById<GridLayout>(R.id.menuGrid)
        
        val menuItems = listOf(
            Pair("New Tab", R.drawable.ic_add),
            Pair("New InPrivate", R.drawable.ic_private),
            Pair("Personalize", R.drawable.ic_palette),
            Pair("Favorites", R.drawable.ic_star),
            Pair("History", R.drawable.ic_history),
            Pair("Downloads", R.drawable.ic_download),
            Pair("Full Screen", R.drawable.ic_fullscreen),
            Pair("Reading Mode", R.drawable.ic_reading_mode),
            Pair("Extra", R.drawable.ic_extra),
            Pair("Find on Page", R.drawable.ic_search),
            Pair("Settings", R.drawable.ic_settings),
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
            params.width = (88 * resources.displayMetrics.density).toInt()
            params.height = GridLayout.LayoutParams.WRAP_CONTENT
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

    private fun toggleReadingMode() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        if (isHomeTab(tab) || tab.url.startsWith("haze://")) {
            Toast.makeText(this, "Reading mode is only available on web pages", Toast.LENGTH_SHORT).show()
            return
        }
        
        val readerScript = """
            (function() {
                var article = document.querySelector('article') || document.querySelector('main') || document.querySelector('.post-content') || document.querySelector('.article-content') || document.querySelector('#content');
                var text = article ? article.innerHTML : document.body.innerHTML;
                var title = document.title;
                return JSON.stringify({ title: title, html: text });
            })();
        """.trimIndent()
        
        tab.webView.evaluateJavascript(readerScript) { result ->
            try {
                val cleanResult = if (result.startsWith("\"") && result.endsWith("\"")) {
                    org.json.JSONTokener(result).nextValue().toString()
                } else result
                val json = JSONObject(cleanResult)
                val title = json.optString("title", "Reader Mode")
                val contentHtml = json.optString("html", "")
                
                showReaderModeDialog(title, contentHtml)
            } catch (e: Exception) {
                Toast.makeText(this, "Could not format page for reading", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReaderModeDialog(title: String, contentHtml: String) {
        val dialog = Dialog(this, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        val isDark = prefs.getBoolean("is_dark", false)
        
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isDark) Color.parseColor("#181818") else Color.parseColor("#FAFAFA"))
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
        header.addView(btnClose)
        header.addView(headerTitle)
        root.addView(header)
        
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            settings.javaScriptEnabled = false
            setBackgroundColor(Color.TRANSPARENT)
        }
        
        val readerHtml = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <style>
                    @import url('https://fonts.googleapis.com/css2?family=Outfit:wght@300;400;500;600&display=swap');
                    body {
                        font-family: 'Outfit', sans-serif;
                        line-height: 1.8;
                        font-size: 18px;
                        padding: 24px 20px 60px 20px;
                        margin: 0 auto;
                        max-width: 680px;
                        color: ${if (isDark) "#E0E0E0" else "#202124"};
                        background: ${if (isDark) "#181818" else "#FAFAFA"};
                    }
                    h1 { font-size: 26px; line-height: 1.35; margin-bottom: 24px; font-weight: 600; color: ${if (isDark) "#FFFFFF" else "#111111"}; }
                    h2, h3 { color: ${if (isDark) "#FFFFFF" else "#222222"}; margin-top: 28px; }
                    img { max-width: 100%; height: auto; border-radius: 12px; margin: 20px 0; }
                    p { margin-bottom: 20px; }
                    a { color: ${prefs.getString("accent_color", "#0A56D0")}; text-decoration: none; }
                </style>
            </head>
            <body>
                <h1>$title</h1>
                $contentHtml
            </body>
            </html>
        """.trimIndent()
        
        webView.loadDataWithBaseURL(null, readerHtml, "text/html", "UTF-8", null)
        root.addView(webView)
        
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

    fun clearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle("Clear Browsing Data")
            .setMessage("This will clear all browsing history, cache, cookies, and local database records.")
            .setPositiveButton("Clear All") { _, _ ->
                triggerStrongHaptic(isHeavy = true)
                HazeDatabase.getInstance(this).clearAllHistory()
                HazeDatabase.getInstance(this).clearRecentSearches()
                CookieManager.getInstance().removeAllCookies(null)
                WebStorage.getInstance().deleteAllData()
                for (tab in normalTabs) tab.webView.clearCache(true)
                for (tab in privateTabs) tab.webView.clearCache(true)
                Toast.makeText(this, "Browsing data cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showPageDetails() {
        val tab = currentTabsList.getOrNull(activeTabIndex) ?: return
        val cert = tab.webView.certificate
        val isSecure = tab.url.startsWith("https://")
        val message = StringBuilder().apply {
            append("URL: ${tab.url}\n\n")
            append("Security: ${if (isSecure) "Secure (HTTPS)" else "Not Secure (HTTP)"}\n\n")
            if (cert != null) {
                append("Issued to: ${cert.issuedTo.cName}\n")
                append("Issued by: ${cert.issuedBy.cName}\n")
                append("Valid until: ${cert.validNotAfterDate}\n")
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

    private fun handleBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
