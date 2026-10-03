package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*

object HazeSettingsManager {

    fun showSettingsDialog(activity: MainActivity) {
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#181818") else Color.parseColor("#FAFAFA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.statusBarColor = bgColor

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 60))
            setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 16), 0)
        }

        val btnBack = ImageButton(activity).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        header.addView(btnBack)

        val tvTitle = TextView(activity).apply {
            text = "Settings"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.RIGHT_OF, btnBack.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dpToPx(activity, 16)
            }
        }
        header.addView(tvTitle)
        root.addView(header)

        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 32))
        }
        scrollView.addView(content)
        root.addView(scrollView)

        fun addSectionHeader(title: String) {
            val tv = TextView(activity).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 6))
            }
            content.addView(tv)
        }

        fun addRow(title: String, subtitle: String, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 12), dpToPx(activity, 12), dpToPx(activity, 12))
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    onClick()
                }
            }
            val t = TextView(activity).apply { text = title; textSize = 15f; setTextColor(textColor) }
            val s = TextView(activity).apply { text = subtitle; textSize = 12f; setTextColor(subTextColor) }
            row.addView(t)
            row.addView(s)
            content.addView(row)
        }

        fun addSwitchRow(title: String, subtitle: String, prefKey: String, defaultValue: Boolean, onChange: (Boolean) -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val t = TextView(activity).apply { text = title; textSize = 15f; setTextColor(textColor) }
            val s = TextView(activity).apply { text = subtitle; textSize = 12f; setTextColor(subTextColor) }
            textCol.addView(t)
            textCol.addView(s)

            val sw = Switch(activity).apply {
                isChecked = prefs.getBoolean(prefKey, defaultValue)
                setOnCheckedChangeListener { _, isChecked ->
                    activity.triggerStrongHaptic()
                    prefs.edit().putBoolean(prefKey, isChecked).apply()
                    onChange(isChecked)
                }
            }
            row.addView(textCol)
            row.addView(sw)
            content.addView(row)
        }

        // Section 1: Search & Navigation
        addSectionHeader("Search")
        val currentEngine = prefs.getString("search_engine", "Google") ?: "Google"
        addRow("Search Engine", currentEngine) {
            val engines = arrayOf("Google", "Bing", "DuckDuckGo", "Yahoo", "Ecosia")
            AlertDialog.Builder(activity)
                .setTitle("Select Search Engine")
                .setItems(engines) { _, which ->
                    val selected = engines[which]
                    prefs.edit().putString("search_engine", selected).apply()
                    Toast.makeText(activity, "Default engine set to $selected", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    showSettingsDialog(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Section 2: Privacy & Security
        addSectionHeader("Privacy & Security")
        addSwitchRow("JavaScript Enabled", "Allow web pages to run scripts", "javascript_enabled", true) { isEnabled ->
            activity.updateWebSettings()
        }
        addSwitchRow("Accept Cookies", "Allow websites to store cookies", "cookies_enabled", true) { isEnabled ->
            CookieManager.getInstance().setAcceptCookie(isEnabled)
        }
        addRow("Clear Browsing Data", "Clear cache, cookies, history, and storage") {
            AlertDialog.Builder(activity)
                .setTitle("Clear Browsing Data")
                .setMessage("This will remove cached files, cookies, and local database entries.")
                .setPositiveButton("Clear Everything") { _, _ ->
                    activity.triggerStrongHaptic(isHeavy = true)
                    HazeDatabase.getInstance(activity).clearAllHistory()
                    CookieManager.getInstance().removeAllCookies(null)
                    WebStorage.getInstance().deleteAllData()
                    Toast.makeText(activity, "Browsing data cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Section 3: General & System
        addSectionHeader("General")
        addRow("Personalize Theme", "Choose wallpapers, solid colors, and dark mode") {
            dialog.dismiss()
            activity.showPersonalizeDialog()
        }
        addRow("Set as Default Browser", "Open links by default in Haze") {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
                    activity.startActivity(intent)
                } else {
                    Toast.makeText(activity, "Select Haze in Android Settings > Default Apps", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Select Haze in Android Settings > Default Apps", Toast.LENGTH_SHORT).show()
            }
        }
        addRow("About Haze Browser", "Haze Browser v2.2 - Ultra-fast minimalist browser") {
            AlertDialog.Builder(activity)
                .setTitle("Haze Browser")
                .setMessage("Haze Browser v2.2\n\nDesigned for speed, elegance, and privacy.\nBuilt with pair-programming assistance by Google DeepMind.")
                .setPositiveButton("Awesome", null)
                .show()
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }
}
