package com.example.haze

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.*

object HazeTabSystemManager {

    const val TAB_MODE_GRID = 0
    const val TAB_MODE_CARDS = 1
    const val TAB_MODE_LIST = 2

    const val PREF_TAB_VIEW_MODE = "tab_view_mode"
    const val PREF_GESTURE_SWIPE_SWITCH_TAB = "gesture_swipe_switch_tab"
    const val PREF_GESTURE_SWIPE_DOWN_TAB_OVERVIEW = "gesture_swipe_down_tab_overview"
    const val PREF_GESTURE_EDGE_SWIPE_NAVIGATE = "gesture_edge_swipe_navigate"
    const val PREF_GESTURE_LONG_PRESS_TAB_OPTIONS = "gesture_long_press_tab_options"

    fun getDomain(url: String): String {
        if (url.startsWith("haze://")) return "Haze"
        return try {
            Uri.parse(url).host?.removePrefix("www.")?.ifEmpty { "Other" } ?: "Other"
        } catch (_: Exception) {
            "Other"
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    // =========================================================================
    // 1. TAB SWITCHER OVERFLOW MENU
    // =========================================================================
    fun showTabSwitcherMenu(
        activity: MainActivity,
        tabs: MutableList<MainActivity.Tab>,
        activeIndex: Int,
        currentMode: Int,
        onModeChanged: (Int) -> Unit,
        onSortRequested: (MainActivity.TabSortMode) -> Unit,
        onRefresh: () -> Unit,
        onCloseTab: (MainActivity.Tab) -> Unit,
        isPrivate: Boolean = false,
        onSelectTabsRequested: (() -> Unit)? = null,
        onPinSettingsRequested: (() -> Unit)? = null
    ) {
        activity.triggerStrongHaptic()
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", true)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        HazeTintManager.applyBlurBehindToWindow(dialog.window, 50)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = HazeTintManager.createSheetGlassDrawable(activity, isDark, 28f)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 20))
        }

        // Header
        val header = TextView(activity).apply {
            text = if (isPrivate) "Private Tab Management" else "Tab Management"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 8), dpToPx(activity, 4), dpToPx(activity, 8), dpToPx(activity, 12))
        }
        root.addView(header)

        fun addMenuItem(title: String, iconRes: Int, subtitle: String = "", onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 52))
                setPadding(dpToPx(activity, 10), 0, dpToPx(activity, 10), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mainTv = TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
            }
            textLayout.addView(mainTv)
            if (subtitle.isNotEmpty()) {
                val subTv = TextView(activity).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(subTextColor)
                }
                textLayout.addView(subTv)
            }
            row.addView(icon)
            row.addView(textLayout)
            root.addView(row)
        }

        // 0. Multi-Select Tabs (moved into menu)
        if (onSelectTabsRequested != null) {
            addMenuItem("Select Tabs", R.drawable.ic_check, "Select multiple tabs to group or close") {
                onSelectTabsRequested()
            }
        }

        // 0.1 In Private: Option to set PIN or manage Incognito Lock
        if (isPrivate && onPinSettingsRequested != null) {
            val hasPin = HazeIncognitoAuthManager.hasPinConfigured(activity)
            val pinTitle = if (hasPin) "Incognito Lock & PIN Settings" else "Set Private PIN Protection"
            val pinSubtitle = if (hasPin) "Lock private tabs now or manage security" else "Require PIN or biometrics to view private tabs"
            addMenuItem(pinTitle, R.drawable.ic_lock_shield, pinSubtitle) {
                onPinSettingsRequested()
            }
        }

        // 1. Layout Mode Switcher
        val modeNames = arrayOf("Card View (Webpage Previews)", "Tile View (Responsive Grid)", "List View (Compact Rows)")
        val currentModeName = modeNames.getOrElse(currentMode) { "Card View" }
        addMenuItem("Change View Mode", R.drawable.ic_grid, currentModeName) {
            showViewModeSelector(activity, currentMode) { newMode ->
                onModeChanged(newMode)
            }
        }

        // 2. Sort by Recent
        addMenuItem("Sort by Recent", R.drawable.ic_history, "Most recently visited tabs first") {
            onSortRequested(MainActivity.TabSortMode.RECENT)
        }

        // 3. Sort by Domain
        addMenuItem("Sort by Domain", R.drawable.ic_globe, "Group tabs alphabetically by website") {
            onSortRequested(MainActivity.TabSortMode.DOMAIN)
        }

        // 4. Auto-group by Domain
        addMenuItem("Group Tabs by Domain", R.drawable.ic_folder, "Automatically group tabs with the same site") {
            autoGroupByDomain(activity, tabs) {
                onRefresh()
            }
        }

        // 5. Save Tab Group / Save All Tabs
        addMenuItem("Save Open Tabs as Group", R.drawable.ic_star, "Save all open tabs to a Bookmarks folder") {
            saveTabGroup(activity, null, tabs)
        }

        // 6. Close Inactive Tabs
        addMenuItem("Close Inactive Tabs", R.drawable.ic_restore, "Close tabs not visited in over 2 hours") {
            closeInactiveTabs(activity, tabs, activeIndex, onCloseTab)
        }

        // 7. Share All Open Tabs
        addMenuItem("Share All Open Tabs", R.drawable.ic_share, "Export or share all tab links as a list") {
            shareAllTabs(activity, tabs)
        }

        // 8. Send Tabs to Device
        addMenuItem("Send Tabs to Another Device", R.drawable.ic_desktop, "Send via Quick Share, sync notification, or QR code") {
            showSendToDeviceDialog(activity, tabs)
        }

        // 9. Gesture Settings
        addMenuItem("Gesture & Navigation Settings", R.drawable.ic_accessibility, "Customize swipe, tab overview, and navigation gestures") {
            showGesturesSettingsDialog(activity)
        }

        // 10. Close All Tabs
        addMenuItem("Close All Tabs", R.drawable.ic_delete, "Close all tabs except pinned tabs") {
            closeAllTabs(activity, tabs, onCloseTab)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 2. VIEW MODE SELECTOR
    // =========================================================================
    fun showViewModeSelector(activity: MainActivity, currentMode: Int, onSelected: (Int) -> Unit) {
        val modes = arrayOf("Card View (Webpage Previews)", "Tile View (Responsive Grid)", "List View (Compact Rows)")
        val modeValues = intArrayOf(TAB_MODE_CARDS, TAB_MODE_GRID, TAB_MODE_LIST)
        val selectedIdx = modeValues.indexOf(currentMode).coerceAtLeast(0)

        AlertDialog.Builder(activity)
            .setTitle("Select Tab View Mode")
            .setSingleChoiceItems(modes, selectedIdx) { dialog, which ->
                activity.triggerStrongHaptic()
                val newMode = modeValues[which]
                activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putInt(PREF_TAB_VIEW_MODE, newMode)
                    .apply()
                onSelected(newMode)
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 3. TAB HISTORY DIALOG
    // =========================================================================
    fun showTabHistoryDialog(activity: MainActivity, tab: MainActivity.Tab, onNavigated: () -> Unit) {
        activity.triggerStrongHaptic()
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", true)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val history = tab.browserTab.navigationController.getNavigationHistory()
        if (history.entries.isEmpty()) {
            Toast.makeText(activity, "No earlier navigation history for this tab", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        val titleTv = TextView(activity).apply {
            text = "Tab Navigation History"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 4))
        }
        val subTitleTv = TextView(activity).apply {
            text = "${history.entries.size} page(s) visited in this tab. Tap any page to jump back or forward."
            textSize = 12f
            setTextColor(subTextColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        root.addView(titleTv)
        root.addView(subTitleTv)

        val scroll = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 320))
        }
        val listLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Show entries from newest to oldest
        for (i in history.entries.indices.reversed()) {
            val entry = history.entries[i]
            val isCurrent = (i == history.currentIndex)
            val isBack = (i < history.currentIndex)

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 52))
                setPadding(dpToPx(activity, 10), 0, dpToPx(activity, 10), 0)
                setBackgroundResource(getSelectableRes(activity))
                if (isCurrent) {
                    setBackgroundColor(Color.argb(20, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                }
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    val step = i - history.currentIndex
                    if (step != 0) {
                        tab.browserTab.navigationController.goBackOrForward(step)
                        onNavigated()
                    }
                    dialog.dismiss()
                }
            }

            val icon = ImageView(activity).apply {
                if (isCurrent) {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(accentColor)
                } else if (isBack) {
                    setImageResource(R.drawable.ic_arrow_back)
                    setColorFilter(subTextColor)
                } else {
                    setImageResource(R.drawable.ic_arrow_forward)
                    setColorFilter(subTextColor)
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                    marginEnd = dpToPx(activity, 12)
                }
            }

            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val entryTitle = TextView(activity).apply {
                text = entry.title.ifEmpty { entry.url }
                textSize = 14f
                setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isCurrent) accentColor else textColor)
                isSingleLine = true
            }
            val entryUrl = TextView(activity).apply {
                text = entry.url
                textSize = 11f
                setTextColor(subTextColor)
                isSingleLine = true
            }
            textLayout.addView(entryTitle)
            textLayout.addView(entryUrl)

            row.addView(icon)
            row.addView(textLayout)

            if (isCurrent) {
                val currentBadge = TextView(activity).apply {
                    text = "Current"
                    textSize = 11f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentColor)
                    setPadding(dpToPx(activity, 8), dpToPx(activity, 2), dpToPx(activity, 8), dpToPx(activity, 2))
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 10).toFloat()
                        setColor(Color.argb(30, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
                    }
                }
                row.addView(currentBadge)
            }

            listLayout.addView(row)
        }

        scroll.addView(listLayout)
        root.addView(scroll)
        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 4. SEND TAB TO ANOTHER DEVICE
    // =========================================================================
    fun showSendToDeviceDialog(activity: MainActivity, tabs: List<MainActivity.Tab>) {
        activity.triggerStrongHaptic()
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", true)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val targetTabs = tabs.filter { !activity.isHomeTab(it) }
        if (targetTabs.isEmpty()) {
            Toast.makeText(activity, "No active web pages to send", Toast.LENGTH_SHORT).show()
            return
        }

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        val header = TextView(activity).apply {
            text = "Send to Another Device"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 4))
        }
        val sub = TextView(activity).apply {
            text = "${targetTabs.size} tab(s) ready to send. Choose your destination or share method."
            textSize = 12f
            setTextColor(subTextColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        root.addView(header)
        root.addView(sub)

        fun addTarget(title: String, subtitle: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 54))
                setPadding(dpToPx(activity, 10), 0, dpToPx(activity, 10), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(accentColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                    marginEnd = dpToPx(activity, 14)
                }
            }
            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mainTv = TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            }
            val subTv = TextView(activity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(subTextColor)
            }
            textLayout.addView(mainTv)
            textLayout.addView(subTv)
            row.addView(icon)
            row.addView(textLayout)
            root.addView(row)
        }

        // 1. Quick Share / Nearby Share
        addTarget("Quick Share / Nearby Share", "Direct wireless transfer to PC, phone, or tablet", R.drawable.ic_sync) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                val textToSend = if (targetTabs.size == 1) targetTabs[0].url else targetTabs.joinToString("\n") { it.url }
                putExtra(Intent.EXTRA_TEXT, textToSend)
            }
            activity.startActivity(Intent.createChooser(shareIntent, "Send Tab via Quick Share"))
        }

        // 2. Instant QR Code
        addTarget("Instant QR Code for Other Devices", "Scan with camera on any PC, phone, or tablet to open instantly", R.drawable.ic_qr_code) {
            showQrCodeDialog(activity, targetTabs.first().title, targetTabs.first().url)
        }

        // 5. Copy Device Sync Links
        addTarget("Copy Sync Link", "Copy URL to clipboard for manual pasting", R.drawable.ic_link) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val textToSend = targetTabs.joinToString("\n") { it.url }
            cm.setPrimaryClip(ClipData.newPlainText("Haze Tabs", textToSend))
            Toast.makeText(activity, "Copied ${targetTabs.size} tab link(s) to clipboard", Toast.LENGTH_SHORT).show()
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 5. INSTANT QR CODE DIALOG
    // =========================================================================
    fun showQrCodeDialog(activity: MainActivity, title: String, url: String) {
        activity.triggerStrongHaptic()
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", true)
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = AlertDialog.Builder(activity).create()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        val headerTv = TextView(activity).apply {
            text = "Scan to Open on Device"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }
        val subTv = TextView(activity).apply {
            text = "Scan with your PC, phone, or tablet camera to open this webpage immediately."
            textSize = 12f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 16))
        }
        root.addView(headerTv)
        root.addView(subTv)

        val qrImageView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 220), dpToPx(activity, 220))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dpToPx(activity, 12).toFloat()
            }
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
        }

        try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, 512, 512)
            val bmp = Bitmap.createBitmap(512, 512, Bitmap.Config.RGB_565)
            for (x in 0 until 512) {
                for (y in 0 until 512) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            qrImageView.setImageBitmap(bmp)
        } catch (e: Exception) {
            qrImageView.setImageResource(R.drawable.ic_qr_code)
        }
        root.addView(qrImageView)

        val urlTv = TextView(activity).apply {
            text = url
            textSize = 12f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(activity, 12), 0, dpToPx(activity, 8))
        }
        root.addView(urlTv)

        val btnClose = Button(activity).apply {
            text = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(btnClose)

        dialog.setView(root)
        dialog.show()
    }

    // =========================================================================
    // 6. SAVE TAB GROUP TO BOOKMARKS
    // =========================================================================
    fun saveTabGroup(activity: MainActivity, group: MainActivity.TabGroup?, tabs: List<MainActivity.Tab>) {
        activity.triggerStrongHaptic()
        val targetTabs = if (group != null) {
            tabs.filter { it.group?.id == group.id && !activity.isHomeTab(it) }
        } else {
            tabs.filter { !activity.isHomeTab(it) }
        }

        if (targetTabs.isEmpty()) {
            Toast.makeText(activity, "No active web pages in this group to save", Toast.LENGTH_SHORT).show()
            return
        }

        val defaultName = group?.name ?: "Saved Tabs (${SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date())})"
        val input = EditText(activity).apply {
            setText(defaultName)
            setSelection(defaultName.length)
        }

        AlertDialog.Builder(activity)
            .setTitle("Save Tab Group to Bookmarks")
            .setMessage("Save ${targetTabs.size} tab(s) into a persistent Bookmarks folder?")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val folderName = input.text.toString().trim().ifEmpty { defaultName }
                val db = HazeDatabase.getInstance(activity)
                val folderId = db.createFolder(folderName, 0L)
                for (t in targetTabs) {
                    db.addBookmark(t.title, t.url, folderId)
                }
                activity.triggerStrongHaptic(isHeavy = true)
                Toast.makeText(activity, "Saved ${targetTabs.size} tab(s) to folder '$folderName'!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 7. AUTO-GROUP BY DOMAIN
    // =========================================================================
    fun autoGroupByDomain(
        activity: MainActivity,
        tabs: MutableList<MainActivity.Tab>,
        onUpdated: () -> Unit
    ) {
        activity.triggerStrongHaptic()
        val domainColors = mapOf(
            "wikipedia.org" to Color.parseColor("#3366CC"),
            "youtube.com" to Color.parseColor("#FF0000"),
            "google.com" to Color.parseColor("#4285F4"),
            "github.com" to Color.parseColor("#24292E"),
            "reddit.com" to Color.parseColor("#FF4500"),
            "twitter.com" to Color.parseColor("#1DA1F2"),
            "x.com" to Color.parseColor("#1DA1F2"),
            "amazon.com" to Color.parseColor("#FF9900"),
            "spotify.com" to Color.parseColor("#1DB954")
        )
        val palette = intArrayOf(
            Color.parseColor("#4285F4"),
            Color.parseColor("#EA4335"),
            Color.parseColor("#FBBC05"),
            Color.parseColor("#34A853"),
            Color.parseColor("#9C27B0"),
            Color.parseColor("#009688")
        )

        // Group tabs by domain
        val domainMap = mutableMapOf<String, MutableList<MainActivity.Tab>>()
        for (tab in tabs) {
            if (activity.isHomeTab(tab)) continue
            val domain = getDomain(tab.url)
            domainMap.getOrPut(domain) { mutableListOf() }.add(tab)
        }

        var createdGroups = 0
        var groupedTabsCount = 0

        for ((domain, domainTabs) in domainMap) {
            if (domainTabs.size >= 2) {
                val groupColor = domainColors[domain] ?: palette[Math.abs(domain.hashCode()) % palette.size]
                val groupName = domain.split(".").firstOrNull()?.replaceFirstChar { it.uppercase() } ?: domain
                val group = MainActivity.TabGroup(
                    id = UUID.randomUUID().toString(),
                    name = groupName,
                    color = groupColor
                )
                for (tab in domainTabs) {
                    tab.group = group
                    groupedTabsCount++
                }
                createdGroups++
            }
        }

        if (createdGroups > 0) {
            activity.saveSessionState()
            onUpdated()
            Toast.makeText(activity, "Auto-grouped $groupedTabsCount tabs into $createdGroups domain group(s)!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "No matching domain duplicates found to auto-group", Toast.LENGTH_SHORT).show()
        }
    }

    // =========================================================================
    // 8. CLOSE INACTIVE TABS
    // =========================================================================
    fun closeInactiveTabs(
        activity: MainActivity,
        tabs: MutableList<MainActivity.Tab>,
        activeIndex: Int,
        onCloseTab: (MainActivity.Tab) -> Unit
    ) {
        activity.triggerStrongHaptic()
        val currentTab = tabs.getOrNull(activeIndex)
        val twoHoursAgo = System.currentTimeMillis() - (2 * 60 * 60 * 1000)

        // Tabs older than 2 hours or inactive background tabs that aren't pinned
        val inactiveTabs = tabs.filter {
            it != currentTab && !it.isPinned && !activity.isHomeTab(it) && it.lastAccessedTime < twoHoursAgo
        }

        val candidates = if (inactiveTabs.isNotEmpty()) inactiveTabs else {
            // Fallback: unpinned background tabs not accessed in the last 15 minutes
            val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)
            tabs.filter { it != currentTab && !it.isPinned && !activity.isHomeTab(it) && it.lastAccessedTime < fifteenMinsAgo }
        }

        if (candidates.isEmpty()) {
            Toast.makeText(activity, "All tabs are recently active or pinned. No inactive tabs found.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(activity)
            .setTitle("Close Inactive Tabs")
            .setMessage("Close ${candidates.size} inactive tab(s)? Pinned tabs and your active tab will be kept.")
            .setPositiveButton("Close Inactive") { _, _ ->
                for (tab in candidates) {
                    onCloseTab(tab)
                }
                activity.triggerStrongHaptic(isHeavy = true)
                Toast.makeText(activity, "Closed ${candidates.size} inactive tab(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 9. CLOSE ALL TABS
    // =========================================================================
    fun closeAllTabs(
        activity: MainActivity,
        tabs: MutableList<MainActivity.Tab>,
        onCloseTab: (MainActivity.Tab) -> Unit
    ) {
        activity.triggerStrongHaptic()
        val unpinned = tabs.filter { !it.isPinned }
        val pinnedCount = tabs.size - unpinned.size

        if (unpinned.isEmpty()) {
            Toast.makeText(activity, "All open tabs are pinned/locked. Unpin tabs to close them.", Toast.LENGTH_SHORT).show()
            return
        }

        val message = if (pinnedCount > 0) {
            "Close ${unpinned.size} unpinned tab(s)? Your $pinnedCount pinned tab(s) will be kept."
        } else {
            "Close all ${unpinned.size} open tab(s)?"
        }

        AlertDialog.Builder(activity)
            .setTitle("Close All Tabs")
            .setMessage(message)
            .setPositiveButton("Close All") { _, _ ->
                for (tab in unpinned) {
                    onCloseTab(tab)
                }
                activity.triggerStrongHaptic(isHeavy = true)
                Toast.makeText(activity, "Closed ${unpinned.size} tab(s)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // =========================================================================
    // 10. SHARE ALL OPEN TABS
    // =========================================================================
    fun shareAllTabs(activity: MainActivity, tabs: List<MainActivity.Tab>) {
        activity.triggerStrongHaptic()
        val activeTabs = tabs.filter { !activity.isHomeTab(it) }
        if (activeTabs.isEmpty()) {
            Toast.makeText(activity, "No active web pages to share", Toast.LENGTH_SHORT).show()
            return
        }

        val builder = StringBuilder("Open Tabs in Haze Browser (${activeTabs.size} tabs):\n\n")
        activeTabs.forEachIndexed { i, tab ->
            builder.append("${i + 1}. ${tab.title}\n${tab.url}\n\n")
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Open Tabs in Haze Browser (${activeTabs.size})")
            putExtra(Intent.EXTRA_TEXT, builder.toString().trim())
        }
        activity.startActivity(Intent.createChooser(intent, "Share All Open Tabs"))
    }

    // =========================================================================
    // 11. CONFIGURABLE GESTURES SETTINGS DIALOG
    // =========================================================================
    fun showGesturesSettingsDialog(activity: MainActivity) {
        activity.triggerStrongHaptic()
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", true)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        val titleTv = TextView(activity).apply {
            text = "Gestures & Navigation"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 4))
        }
        val subTv = TextView(activity).apply {
            text = "Configure gesture shortcuts for fast one-handed tab switching and browsing."
            textSize = 12f
            setTextColor(subTextColor)
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        root.addView(titleTv)
        root.addView(subTv)

        fun addGestureSwitch(title: String, subtitle: String, prefKey: String, defaultValue: Boolean = true) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                val outVal = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, outVal, true)
                setBackgroundResource(outVal.resourceId)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 8)
                }
                setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            }
            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val mainTv = TextView(activity).apply {
                text = title
                textSize = 15f
                setTextColor(textColor)
                setTypeface(null, Typeface.BOLD)
            }
            val descTv = TextView(activity).apply {
                text = subtitle
                textSize = 11f
                setTextColor(subTextColor)
            }
            textLayout.addView(mainTv)
            textLayout.addView(descTv)

            val switch = Switch(activity).apply {
                isChecked = prefs.getBoolean(prefKey, defaultValue)
                isFocusable = false
                isClickable = false
            }
            row.setOnClickListener {
                activity.triggerStrongHaptic()
                switch.toggle()
                prefs.edit().putBoolean(prefKey, switch.isChecked).apply()
            }
            row.addView(textLayout)
            row.addView(switch)
            root.addView(row)
        }

        addGestureSwitch(
            "Swipe address bar → switch tab",
            "Fling left or right on the bottom address bar to switch between open tabs",
            PREF_GESTURE_SWIPE_SWITCH_TAB,
            true
        )

        addGestureSwitch(
            "Swipe down → tab overview",
            "Swipe down on the address bar to quickly open the full-screen tab switcher",
            PREF_GESTURE_SWIPE_DOWN_TAB_OVERVIEW,
            true
        )

        addGestureSwitch(
            "Swipe left / right → navigate",
            "Fling inwards from the screen edge on web pages to navigate back and forward",
            PREF_GESTURE_EDGE_SWIPE_NAVIGATE,
            true
        )

        addGestureSwitch(
            "Long press tab → tab options",
            "Long press any tab card in the switcher to access pin, group, share, and tab history",
            PREF_GESTURE_LONG_PRESS_TAB_OPTIONS,
            true
        )

        val btnDone = Button(activity).apply {
            text = "Done"
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(btnDone)

        dialog.setContentView(root)
        dialog.show()
    }
}
