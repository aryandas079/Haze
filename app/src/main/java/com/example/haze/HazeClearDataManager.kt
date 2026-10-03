package com.example.haze

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.WebStorage
import android.webkit.WebViewDatabase
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.concurrent.thread

object HazeClearDataManager {

    data class TimeRangeOption(
        val title: String,
        val durationMillis: Long? // null indicates "All time"
    )

    data class ClearCategory(
        val id: String,
        val title: String,
        val description: String,
        var isSelected: Boolean,
        val supportsTimeRange: Boolean
    )

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

    fun showClearDataDialog(activity: MainActivity, onCleared: (() -> Unit)? = null) {
        activity.triggerStrongHaptic(isHeavy = true)

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode

        val surfaceColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val cardColor = if (isDark) Color.parseColor("#262626") else Color.parseColor("#F8F9FA")
        val borderColor = if (isDark) Color.parseColor("#383838") else Color.parseColor("#E0E0E0")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val primaryAccent = if (isDark) Color.parseColor("#FF5252") else Color.parseColor("#D93025")

        val timeRangeOptions = listOf(
            TimeRangeOption("Last 15 minutes", 15 * 60 * 1000L),
            TimeRangeOption("Last hour", 60 * 60 * 1000L),
            TimeRangeOption("Last 24 hours", 24 * 60 * 60 * 1000L), // Default
            TimeRangeOption("Last 7 days", 7L * 24 * 60 * 60 * 1000L),
            TimeRangeOption("Last 4 weeks", 28L * 24 * 60 * 60 * 1000L),
            TimeRangeOption("All time", null)
        )

        var selectedTimeIndex = 2 // Default: Last 24 hours

        val categories = mutableListOf(
            ClearCategory(
                id = "history",
                title = "Browsing history",
                description = "Clears visited websites, titles, and cached favicons.",
                isSelected = true,
                supportsTimeRange = true
            ),
            ClearCategory(
                id = "cookies",
                title = "Cookies and site data",
                description = "Signs you out of most sites and clears web storage.",
                isSelected = true,
                supportsTimeRange = false
            ),
            ClearCategory(
                id = "cache",
                title = "Cached images and files",
                description = "Frees up space and clears temporary cached web pages and images.",
                isSelected = true,
                supportsTimeRange = false
            ),
            ClearCategory(
                id = "downloads",
                title = "Download history",
                description = "Clears the list of downloaded files (does not delete actual files from storage).",
                isSelected = false,
                supportsTimeRange = true
            ),
            ClearCategory(
                id = "forms",
                title = "Saved form data",
                description = "Clears autofill information and saved form inputs.",
                isSelected = false,
                supportsTimeRange = false
            ),
            ClearCategory(
                id = "permissions",
                title = "Site permissions and settings",
                description = "Resets location, camera, microphone, and other site permission choices.",
                isSelected = false,
                supportsTimeRange = false
            )
        )

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val shape = GradientDrawable().apply {
                this.shape = GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(surfaceColor)
            }
            background = shape
        }

        // --- Drag Handle ---
        val handleWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 20))
        }
        val handlePill = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#555555") else Color.parseColor("#CCCCCC"))
            }
        }
        handleWrapper.addView(handlePill)
        root.addView(handleWrapper)

        // --- Title & Header ---
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 4), dpToPx(activity, 20), dpToPx(activity, 12))
        }
        val tvHeader = TextView(activity).apply {
            text = "Clear browsing data"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvSubHeader = TextView(activity).apply {
            text = "Choose the data types and time range you want to clear."
            textSize = 13f
            setTextColor(subTextColor)
            setPadding(0, dpToPx(activity, 4), 0, 0)
        }
        headerLayout.addView(tvHeader)
        headerLayout.addView(tvSubHeader)
        root.addView(headerLayout)

        // --- Scrollable Content ---
        val scrollView = ScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            isVerticalScrollBarEnabled = false
        }
        val scrollContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dpToPx(activity, 16))
        }

        // --- Time Range Selector Section ---
        val timeSectionLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), 0, dpToPx(activity, 20), dpToPx(activity, 14))
        }
        val tvTimeLabel = TextView(activity).apply {
            text = "Time range"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
        }
        timeSectionLayout.addView(tvTimeLabel)

        val timePill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 48)).apply {
                topMargin = dpToPx(activity, 6)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), borderColor)
            }
            setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 14), 0)
        }

        val tvSelectedRange = TextView(activity).apply {
            text = timeRangeOptions[selectedTimeIndex].title
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val ivChevron = ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(subTextColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22))
        }
        timePill.addView(tvSelectedRange)
        timePill.addView(ivChevron)
        timeSectionLayout.addView(timePill)
        scrollContent.addView(timeSectionLayout)

        // --- Divider ---
        val div1 = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1))
            setBackgroundColor(if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#EEEEEE"))
        }
        scrollContent.addView(div1)

        // --- Checkboxes Section ---
        val checkboxesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dpToPx(activity, 6), 0, dpToPx(activity, 6))
        }

        val checkboxViews = mutableListOf<CheckBox>()

        // Dynamic summary views (declared early for closure access)
        val summaryCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 8))
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 14), dpToPx(activity, 16), dpToPx(activity, 14))
        }

        val tvSummaryTitle = TextView(activity).apply {
            text = "Ready to clear:"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvSummaryList = TextView(activity).apply {
            textSize = 13f
            setTextColor(subTextColor)
            setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 4))
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
        }
        val tvSummaryRange = TextView(activity).apply {
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dpToPx(activity, 4), 0, 0)
        }
        val tvSummaryNotice = TextView(activity).apply {
            text = "ℹ Note: Cookies, cache, form data, and site permissions will be cleared for all time (Android system limitations)."
            textSize = 11.5f
            setTextColor(if (isDark) Color.parseColor("#FFAB91") else Color.parseColor("#C5221F"))
            setPadding(0, dpToPx(activity, 6), 0, 0)
            visibility = View.GONE
        }

        summaryCard.addView(tvSummaryTitle)
        summaryCard.addView(tvSummaryList)
        summaryCard.addView(tvSummaryRange)
        summaryCard.addView(tvSummaryNotice)

        // Action button reference for enabling/disabling
        var btnClearDataRef: Button? = null

        fun updateSummary() {
            val selectedCats = categories.filter { it.isSelected }
            val timeOption = timeRangeOptions[selectedTimeIndex]

            tvSummaryRange.text = "Time range: ${timeOption.title}"

            if (selectedCats.isEmpty()) {
                tvSummaryList.text = "No data categories selected — select at least one option above."
                tvSummaryList.setTextColor(if (isDark) Color.parseColor("#FF8A80") else Color.parseColor("#D93025"))
                tvSummaryNotice.visibility = View.GONE
                btnClearDataRef?.isEnabled = false
                btnClearDataRef?.alpha = 0.4f
            } else {
                tvSummaryList.setTextColor(subTextColor)
                val sb = StringBuilder()
                selectedCats.forEach { cat ->
                    sb.append("• ").append(cat.title).append("\n")
                }
                tvSummaryList.text = sb.toString().trimEnd()

                // Check if any non-time-range category is selected while duration != null
                val hasUnsupportedTimeRange = selectedCats.any { !it.supportsTimeRange } && timeOption.durationMillis != null
                tvSummaryNotice.visibility = if (hasUnsupportedTimeRange) View.VISIBLE else View.GONE

                btnClearDataRef?.isEnabled = true
                btnClearDataRef?.alpha = 1.0f
            }
        }

        // Build each CheckBox row
        for (cat in categories) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(dpToPx(activity, 20), dpToPx(activity, 10), dpToPx(activity, 20), dpToPx(activity, 10))
                setBackgroundResource(getSelectableRes(activity))
            }

            val cb = CheckBox(activity).apply {
                isChecked = cat.isSelected
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList.valueOf(primaryAccent)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 14)
                }
            }
            checkboxViews.add(cb)

            val textLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvTitle = TextView(activity).apply {
                text = cat.title
                textSize = 15f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }

            val tvDesc = TextView(activity).apply {
                text = cat.description
                textSize = 12.5f
                setTextColor(subTextColor)
                setPadding(0, dpToPx(activity, 2), 0, 0)
            }

            textLayout.addView(tvTitle)
            textLayout.addView(tvDesc)

            row.addView(cb)
            row.addView(textLayout)

            row.setOnClickListener {
                activity.triggerStrongHaptic(it)
                cat.isSelected = !cat.isSelected
                cb.isChecked = cat.isSelected
                updateSummary()
            }

            checkboxesContainer.addView(row)
        }

        scrollContent.addView(checkboxesContainer)
        scrollContent.addView(summaryCard)
        scrollView.addView(scrollContent)
        root.addView(scrollView)

        // Time Range click listener
        timePill.setOnClickListener {
            activity.triggerStrongHaptic(it)
            val titles = timeRangeOptions.map { it.title }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Select time range")
                .setSingleChoiceItems(titles, selectedTimeIndex) { selectDialog, which ->
                    activity.triggerStrongHaptic(isHeavy = false)
                    selectedTimeIndex = which
                    tvSelectedRange.text = timeRangeOptions[which].title
                    updateSummary()
                    selectDialog.dismiss()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- Bottom Action Buttons ---
        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 14), dpToPx(activity, 20), dpToPx(activity, 24))
            setBackgroundColor(surfaceColor)
        }

        val btnCancel = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Cancel"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(activity, 46)).apply {
                marginEnd = dpToPx(activity, 12)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }

        val btnClearData = Button(activity).apply {
            text = "Clear data"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(primaryAccent)
            }
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(activity, 46), 1f)
        }
        btnClearDataRef = btnClearData

        actionsContainer.addView(btnCancel)
        actionsContainer.addView(btnClearData)
        root.addView(actionsContainer)

        // Execution logic on Clear Data click
        btnClearData.setOnClickListener {
            val selectedCats = categories.filter { it.isSelected }
            if (selectedCats.isEmpty()) {
                Toast.makeText(activity, "Please select at least one data category", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            activity.triggerStrongHaptic(it, isHeavy = true)

            // Show loading state
            btnClearData.isEnabled = false
            btnCancel.isEnabled = false
            btnClearData.text = "Clearing data..."

            val timeOption = timeRangeOptions[selectedTimeIndex]
            val sinceMillis = if (timeOption.durationMillis != null) {
                System.currentTimeMillis() - timeOption.durationMillis
            } else 0L

            thread {
                val db = HazeDatabase.getInstance(activity)

                // 1. Browsing history (does NOT delete bookmarks)
                if (categories.find { it.id == "history" }?.isSelected == true) {
                    if (sinceMillis == 0L) {
                        db.clearAllHistory()
                        HazeFaviconManager.clearFaviconCache(activity)
                    } else {
                        db.clearHistoryRange(sinceMillis)
                    }
                }

                // 2. Cookies and site data
                if (categories.find { it.id == "cookies" }?.isSelected == true) {
                    try {
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        WebStorage.getInstance().deleteAllData()
                    } catch (e: Exception) {
                    }
                }

                // 3. Cached images and files (clears real WebView cache + cache dir)
                if (categories.find { it.id == "cache" }?.isSelected == true) {
                    try {
                        activity.clearTabsCache()
                        activity.cacheDir?.deleteRecursively()
                    } catch (e: Exception) {
                    }
                }

                // 4. Download history (clears database records only, does not delete files from storage)
                if (categories.find { it.id == "downloads" }?.isSelected == true) {
                    if (sinceMillis == 0L) {
                        db.clearAllDownloads()
                    } else {
                        db.clearDownloadsRange(sinceMillis)
                    }
                }

                // 5. Saved form data
                if (categories.find { it.id == "forms" }?.isSelected == true) {
                    try {
                        val webDb = WebViewDatabase.getInstance(activity)
                        webDb.clearFormData()
                        webDb.clearHttpAuthUsernamePassword()
                    } catch (e: Exception) {
                    }
                }

                // 6. Site permissions
                if (categories.find { it.id == "permissions" }?.isSelected == true) {
                    try {
                        GeolocationPermissions.getInstance().clearAll()
                    } catch (e: Exception) {
                    }
                }

                // Small delay to ensure disk sync and smooth visual transition
                Thread.sleep(300)

                activity.runOnUiThread {
                    dialog.dismiss()
                    activity.triggerStrongHaptic(isHeavy = true)
                    Toast.makeText(activity, "Selected browsing data cleared", Toast.LENGTH_SHORT).show()
                    onCleared?.invoke()
                }
            }
        }

        // Initialize dynamic summary
        updateSummary()

        dialog.setContentView(root)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
            }
        }

        dialog.show()
    }
}
