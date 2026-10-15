package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.*
import androidx.core.widget.NestedScrollView

object HazeSettingsManager {

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }

    fun showSettingsDialog(activity: MainActivity) {
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)

        val bgColor = if (isDark) Color.parseColor("#141414") else Color.parseColor("#F8F9FA")
        val cardColor = if (isDark) Color.parseColor("#222222") else Color.WHITE
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val dividerColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E5E5E5")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
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
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 36))
        }
        scrollView.addView(content)
        root.addView(scrollView)

        fun createCardContainer(): LinearLayout {
            return LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 18).toFloat()
                    setColor(cardColor)
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 16)
                }
                setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 4))
            }
        }

        fun addSectionHeader(title: String) {
            val tv = TextView(activity).apply {
                text = title
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 8))
            }
            content.addView(tv)
        }

        // --- CARD 1: ACCOUNT & SYNC ---
        addSectionHeader("Account & Sync")
        val cardAccount = createCardContainer()
        val currentUser = HazeAccountManager.getUser(activity)
        if (currentUser != null) {
            cardAccount.addView(createRow(activity, "Google Account", "${currentUser.displayName} (${currentUser.email})", textColor, subTextColor) {
                dialog.dismiss()
                HazeProfileDialogManager.showProfilePanel(activity)
            })
            val syncSubtitle = if (HazeSyncManager.isSyncEnabled(activity)) HazeSyncManager.getLastSyncFormatted(activity) else "Sync paused"
            cardAccount.addView(createRow(activity, "Cloud Synchronization", syncSubtitle, textColor, subTextColor) {
                dialog.dismiss()
                HazeProfileDialogManager.showProfilePanel(activity)
            })
        } else {
            cardAccount.addView(createRow(activity, "Sign in with Google", "Back up bookmarks and sync across devices", textColor, subTextColor) {
                dialog.dismiss()
                HazeProfileDialogManager.showProfilePanel(activity)
            })
        }
        content.addView(cardAccount)

        // --- CARD 2: SEARCH & STARTUP ---
        addSectionHeader("Search & Startup")
        val cardSearch = createCardContainer()
        val currentEngine = prefs.getString("search_engine", "Google") ?: "Google"
        cardSearch.addView(createRow(activity, "Search Engine", currentEngine, textColor, subTextColor) {
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
        })

        val currentStartup = prefs.getString("startup_mode", "home") ?: "home"
        val startupLabel = if (currentStartup == "resume") "Continue where you left off" else "Open new home page"
        cardSearch.addView(createRow(activity, "On Startup", startupLabel, textColor, subTextColor) {
            val options = arrayOf("Open new home page", "Continue where you left off")
            val currentIdx = if (currentStartup == "resume") 1 else 0
            AlertDialog.Builder(activity)
                .setTitle("On Startup")
                .setSingleChoiceItems(options, currentIdx) { d, which ->
                    val mode = if (which == 1) "resume" else "home"
                    prefs.edit().putString("startup_mode", mode).apply()
                    d.dismiss()
                    Toast.makeText(activity, "Startup setting updated", Toast.LENGTH_SHORT).show()
                    showSettingsDialog(activity)
                }
                .show()
        })
        content.addView(cardSearch)

        // --- CARD 3: PRIVACY & SECURITY ---
        addSectionHeader("Privacy & Security")
        val cardPrivacy = createCardContainer()
        cardPrivacy.addView(createSwitchRow(activity, textColor, subTextColor, accentColor, prefs, "Block Third-Party Cookies", "Prevent cross-site trackers from saving cookies", HazeAntiTrackingEngine.PREF_THIRD_PARTY_COOKIES_BLOCKED, true) {
            activity.applyAntiTrackingCookiePolicy()
        })
        cardPrivacy.addView(createSwitchRow(activity, textColor, subTextColor, accentColor, prefs, "Link Tracking Protection", "Strip tracking parameters (utm_source, gclid, etc.) from URLs", HazeAntiTrackingEngine.PREF_LINK_TRACKING_PROTECTION, true) {})
        cardPrivacy.addView(createSwitchRow(activity, textColor, subTextColor, accentColor, prefs, "Fingerprinting Protection", "Mask Canvas, WebGL, Audio & Hardware signatures", HazeAntiTrackingEngine.PREF_FINGERPRINTING_PROTECTION, true) {})
        cardPrivacy.addView(createRow(activity, "Privacy Shield & Anti-Tracking", "Configure advanced tracker blocking, bounce protection, and live stats", textColor, subTextColor) {
            dialog.dismiss()
            HazePrivacyShieldDashboard.showDashboard(activity)
        })
        cardPrivacy.addView(createRow(activity, "DNS Protection", HazeDnsManager.getDnsSummary(activity), textColor, subTextColor) {
            HazeDnsDialogManager.showDnsDialog(activity) {
                dialog.dismiss()
                showSettingsDialog(activity)
            }
        })
        cardPrivacy.addView(createRow(activity, "Google Password Manager", "Manage passwords, passkeys, and autofill credentials", textColor, subTextColor) {
            HazeSecurityManager.requestGooglePasswordManagerIntegration(activity)
        })
        cardPrivacy.addView(createRow(activity, "Clear Browsing Data", "Clear cache, cookies, history, and storage", textColor, subTextColor) {
            HazeClearDataManager.showClearDataDialog(activity)
        })
        content.addView(cardPrivacy)

        // --- CARD 4: MEDIA & MINIPLAYER ---
        addSectionHeader("Media & Miniplayer")
        val cardMedia = createCardContainer()
        cardMedia.addView(createSwitchRow(activity, textColor, subTextColor, accentColor, prefs, "Automatic Miniplayer (PiP)", "Pop out floating video player when minimizing browser", "auto_pip_enabled", true) { isEnabled ->
            activity.updatePipAutoEnter(isEnabled)
        })
        content.addView(cardMedia)

        // --- CARD 5: GENERAL ---
        addSectionHeader("General")
        val cardGeneral = createCardContainer()
        cardGeneral.addView(createRow(activity, "Personalize Theme", "Choose wallpapers, solid colors, and dark mode", textColor, subTextColor) {
            dialog.dismiss()
            activity.showPersonalizeDialog()
        })
        cardGeneral.addView(createRow(activity, "Set as Default Browser", "Open links by default in Haze", textColor, subTextColor) {
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
        })
        content.addView(cardGeneral)

        // --- CARD 6: ADVANCED & DEVELOPER TOOLS ---
        addSectionHeader("Advanced & Developer Tools")
        val cardAdvanced = createCardContainer()
        cardAdvanced.addView(createRow(activity, "Developer Tools", "Inspect DOM elements, console logs, network requests, and active viewport", textColor, subTextColor) {
            dialog.dismiss()
            val activeTab = activity.getActiveTab()
            if (activeTab != null) {
                HazeDevToolsManager.showDevToolsDialog(activity, activeTab)
            } else {
                Toast.makeText(activity, "No active tab to inspect", Toast.LENGTH_SHORT).show()
            }
        })
        content.addView(cardAdvanced)
        cardGeneral.addView(createRow(activity, "About Haze Browser", "Haze Browser v2.2 - Ultra-fast minimalist browser", textColor, subTextColor) {
            val isDarkAbout = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
            val dialogBg = if (isDarkAbout) Color.parseColor("#1E1E1E") else Color.WHITE
            val cText = if (isDarkAbout) Color.WHITE else Color.parseColor("#202124")
            val cSub = if (isDarkAbout) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
            val cCard = if (isDarkAbout) Color.parseColor("#2A2A2A") else Color.parseColor("#F8F9FA")

            val scrollView = NestedScrollView(activity).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 24).toFloat()
                    setColor(dialogBg)
                }
            }

            val layout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 24), dpToPx(activity, 20))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 24).toFloat()
                    setColor(dialogBg)
                }
            }
            scrollView.addView(layout)

            val tvTitle = TextView(activity).apply {
                text = "Haze Browser"
                textSize = 22f
                setTypeface(null, Typeface.BOLD)
                setTextColor(cText)
            }
            val tvVer = TextView(activity).apply {
                text = "Version 2.2"
                textSize = 13f
                setTextColor(cSub)
                setPadding(0, dpToPx(activity, 2), 0, dpToPx(activity, 12))
            }
            val tvDesc = TextView(activity).apply {
                text = "Designed for speed, elegance, and privacy."
                textSize = 14f
                setTextColor(cText)
                setPadding(0, 0, 0, dpToPx(activity, 12))
            }
            val tvDev = TextView(activity).apply {
                text = "Built and developed by Aryan Das"
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(0, 0, 0, dpToPx(activity, 20))
            }

            layout.addView(tvTitle)
            layout.addView(tvVer)
            layout.addView(tvDesc)
            layout.addView(tvDev)

            fun addSocialRow(iconRes: Int, label: String, url: String) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 14).toFloat()
                        setColor(cCard)
                    }
                    setPadding(dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 12))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(activity, 10)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        activity.triggerStrongHaptic()
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            activity.startActivity(intent)
                        } catch (e: Exception) {}
                    }
                }
                val icon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                        marginEnd = dpToPx(activity, 14)
                    }
                    setImageResource(iconRes)
                    setColorFilter(accentColor)
                }
                val tv = TextView(activity).apply {
                    text = label
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(cText)
                }
                row.addView(icon)
                row.addView(tv)
                layout.addView(row)
            }

            addSocialRow(R.drawable.ic_github, "GitHub: @aryandas079", "https://github.com/aryandas079")
            addSocialRow(R.drawable.ic_instagram, "Instagram: @iam_aryandas", "https://instagram.com/iam_aryandas")
            addSocialRow(R.drawable.ic_linkedin, "LinkedIn: @iam-aryandas", "https://linkedin.com/in/iam-aryandas")

            val aboutDialog = AlertDialog.Builder(activity)
                .setView(scrollView)
                .setPositiveButton("Awesome", null)
                .create()

            aboutDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            aboutDialog.show()
            aboutDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accentColor)
        })
        content.addView(cardGeneral)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun createRow(context: Context, title: String, subtitle: String, textColor: Int, subTextColor: Int, onClick: () -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(context, 16), dpToPx(context, 12), dpToPx(context, 16), dpToPx(context, 12))
            setBackgroundResource(getSelectableRes(context))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                if (context is MainActivity) context.triggerStrongHaptic(it)
                onClick()
            }
            val t = TextView(context).apply { text = title; textSize = 15f; setTextColor(textColor) }
            val s = TextView(context).apply { text = subtitle; textSize = 12f; setTextColor(subTextColor); setPadding(0, dpToPx(context, 2), 0, 0) }
            addView(t)
            addView(s)
        }
    }

    private fun createSwitchRow(context: Context, textColor: Int, subTextColor: Int, accentColor: Int, prefs: android.content.SharedPreferences, title: String, subtitle: String, prefKey: String, defaultValue: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, 16), dpToPx(context, 10), dpToPx(context, 16), dpToPx(context, 10))
            
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val t = TextView(context).apply { text = title; textSize = 15f; setTextColor(textColor) }
            val s = TextView(context).apply { text = subtitle; textSize = 12f; setTextColor(subTextColor); setPadding(0, dpToPx(context, 2), 0, 0) }
            textCol.addView(t)
            textCol.addView(s)

            val sw = Switch(context).apply {
                isChecked = prefs.getBoolean(prefKey, defaultValue)
                val thumbColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, Color.GRAY)
                )
                thumbTintList = thumbColor
                setOnCheckedChangeListener { _, isChecked ->
                    if (context is MainActivity) context.triggerStrongHaptic()
                    prefs.edit().putBoolean(prefKey, isChecked).apply()
                    onChange(isChecked)
                }
            }
            addView(textCol)
            addView(sw)
        }
    }
}
